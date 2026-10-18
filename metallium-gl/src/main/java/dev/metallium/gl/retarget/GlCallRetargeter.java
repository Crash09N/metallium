/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl.retarget;

import dev.metallium.gl.MetalGL;
import dev.metallium.gl.MetalGL4;
import dev.metallium.gl.MetalGLInterop;
import dev.metallium.gl.MetalGLStateManager;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Rewrites {@code INVOKESTATIC org/lwjgl/opengl/GL*.gl*} (plus {@code GL.getCapabilities/createCapabilities}) in one
 * class. Each distinct target gets a synthetic static bridge in the same class:
 * <pre>
 *   if (MetalGL.isActive()) return MetalGL.glX(args);          // implemented (same name + descriptor, MetalGL or MetalGL4)
 *   if (MetalGL.isActive()) { MetalGL.unimplemented(mod, fn); return default; }   // not implemented
 *   return GL15C.glX(args);                                      // OpenGL backend: the original call
 * </pre>
 * so a retargeted mod keeps working when Metallium falls back to OpenGL. Call sites keep their descriptor, so no stack
 * map frames change; the bridge carries its own single SAME frame.
 *
 * <p>Two Minecraft OpenGL-backend internals that GL mods use are redirected the same way:
 * <ul>
 *   <li>{@code GlStateManager._x(...)} (static) → {@link MetalGLStateManager} (same name + descriptor);</li>
 *   <li>{@code ((GlTexture) t).glId()} / {@code ((GlTextureView) v).glId()} — a CHECKCAST immediately followed by the
 *       {@code glId()} call — → one bridge {@code (Object)I} that returns the metallium-gl name of Metallium's texture
 *       ({@link MetalGLInterop#glId}). Stack in and out are the same (object → int), so frames are unaffected.</li>
 * </ul>
 */
public final class GlCallRetargeter {
    static final String SINK = Type.getInternalName(MetalGL.class);
    private static final String GL_PACKAGE = "org/lwjgl/opengl/";
    private static final String MC_GL_PACKAGE = "com/mojang/renderpearl/backend/opengl/";
    static final String STATE_MANAGER = MC_GL_PACKAGE + "GlStateManager";
    private static final Set<String> GL_TEXTURE_CLASSES = Set.of(MC_GL_PACKAGE + "GlTexture", MC_GL_PACKAGE + "GlTextureView");
    private static final String INTEROP = Type.getInternalName(MetalGLInterop.class);
    private static final String MIXIN_MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
    private static final Pattern FUNCTION_CLASS = Pattern.compile("GL\\d\\dC?|(ARB|EXT|KHR|NV|AMD|INTEL|APPLE|ATI|OVR)\\w+");
    private static final String BRIDGE_PREFIX = "metallium$gl$";
    /** name + descriptor → internal name of the sink class implementing it. */
    private static final Map<String, String> IMPLEMENTED = implemented(MetalGL.class, MetalGL4.class);
    private static final Map<String, String> STATE_IMPLEMENTED = implemented(MetalGLStateManager.class);

    private GlCallRetargeter() {
    }

    /** Result of transforming one class. */
    public record Result(byte @Nullable [] bytes, int callSites, int unimplemented, int methodReferences) {
    }

    private static Map<String, String> implemented(final Class<?>... sinks) {
        Map<String, String> map = new HashMap<>();
        for (Class<?> sink : sinks) {
            for (Method method : sink.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())) {
                    map.putIfAbsent(method.getName() + Type.getMethodDescriptor(method), Type.getInternalName(sink));
                }
            }
        }
        return Map.copyOf(map);
    }

    /** True if a GL sink (MetalGL / MetalGL4) has a method with exactly this name and descriptor. */
    public static boolean isImplemented(final String name, final String descriptor) {
        return IMPLEMENTED.containsKey(name + descriptor);
    }

    static boolean isGlCall(final String owner, final String name) {
        if (!owner.startsWith(GL_PACKAGE)) {
            return false;
        }
        String simple = owner.substring(GL_PACKAGE.length());
        if (simple.equals("GL")) {
            return name.equals("getCapabilities") || name.equals("createCapabilities");
        }
        return FUNCTION_CLASS.matcher(simple).matches() && (name.startsWith("gl") || name.startsWith("ngl"));
    }

    /** Quick pre-check on the raw bytes: does the constant pool mention LWJGL's GL package or Minecraft's GL backend? */
    public static boolean mentionsGl(final byte[] bytes) {
        return contains(bytes, GL_PACKAGE) || contains(bytes, MC_GL_PACKAGE);
    }

    static boolean contains(final byte[] bytes, final String text) {
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= bytes.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    public static Result transform(final String modId, final byte[] bytes) {
        if (!mentionsGl(bytes)) {
            return new Result(null, 0, 0, 0);
        }
        return transform(bytes, method -> modId);
    }

    /**
     * Retargets only methods that Mixin merged into a class from a mixin of an opted-in mod ({@code @MixinMerged}):
     * {@code modOfMixin} maps the mixin class name (dots) to its mod id, or null. Lets a mod's GL code injected into
     * classes of other mods (e.g. Sodium's renderer) reach metallium-gl too.
     */
    public static Result transformMerged(final byte[] bytes, final Function<String, @Nullable String> modOfMixin) {
        if (!mentionsGl(bytes) || !contains(bytes, "MixinMerged")) {
            return new Result(null, 0, 0, 0);
        }
        return transform(bytes, method -> mergedFrom(method, modOfMixin));
    }

    private static @Nullable String mergedFrom(final MethodNode method, final Function<String, @Nullable String> modOfMixin) {
        for (List<AnnotationNode> annotations : java.util.Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (annotations == null) continue;
            for (AnnotationNode annotation : annotations) {
                if (!MIXIN_MERGED.equals(annotation.desc) || annotation.values == null) continue;
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    if ("mixin".equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof String mixin) {
                        return modOfMixin.apply(mixin);
                    }
                }
            }
        }
        return null;
    }

    /** {@code modOf}: mod id whose code {@code method} is (null: leave the method alone). */
    private static Result transform(final byte[] bytes, final Function<MethodNode, @Nullable String> modOf) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean isInterface = (node.access & Opcodes.ACC_INTERFACE) != 0;
        int version = node.version & 0xFFFF;
        if (isInterface && version < Opcodes.V1_8) {
            return new Result(null, 0, 0, 0);
        }
        Map<String, MethodNode> bridges = new LinkedHashMap<>();
        int sites = 0;
        int unimplemented = 0;
        int references = 0;
        for (MethodNode method : node.methods) {
            String modId = modOf.apply(method);
            if (modId == null) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                        && (isGlCall(call.owner, call.name) || (call.owner.equals(STATE_MANAGER) && call.name.startsWith("_")))) {
                    boolean stateManager = call.owner.equals(STATE_MANAGER);
                    String key = call.owner + "." + call.name + call.desc;
                    MethodNode bridge = bridges.get(key);
                    if (bridge == null) {
                        String sink = (stateManager ? STATE_IMPLEMENTED : IMPLEMENTED).get(call.name + call.desc);
                        if (sink == null) unimplemented++;
                        bridge = bridge(BRIDGE_PREFIX + bridges.size() + "$" + call.name, call, sink, modId, isInterface, version);
                        bridges.put(key, bridge);
                    }
                    call.owner = node.name;
                    call.name = bridge.name;
                    call.itf = isInterface;
                    sites++;
                } else if (insn instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST && GL_TEXTURE_CLASSES.contains(cast.desc)
                        && next(cast) instanceof MethodInsnNode glId && glId.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && glId.owner.equals(cast.desc) && glId.name.equals("glId") && glId.desc.equals("()I")) {
                    String key = cast.desc + ".glId";
                    MethodNode bridge = bridges.get(key);
                    if (bridge == null) {
                        bridge = glIdBridge(BRIDGE_PREFIX + bridges.size() + "$glId", cast.desc, isInterface, version);
                        bridges.put(key, bridge);
                    }
                    AbstractInsnNode after = cast.getNext();
                    method.instructions.remove(cast);
                    glId.setOpcode(Opcodes.INVOKESTATIC);
                    glId.owner = node.name;
                    glId.name = bridge.name;
                    glId.desc = "(Ljava/lang/Object;)I";
                    glId.itf = isInterface;
                    insn = after;
                    sites++;
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof Handle handle && isGlCall(handle.getOwner(), handle.getName())) {
                            references++; // e.g. GL11::glEnable as a lambda; not retargeted yet (logged by the caller)
                        }
                    }
                }
            }
        }
        if (bridges.isEmpty()) {
            return new Result(null, 0, 0, references);
        }
        node.methods.addAll(bridges.values());
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return new Result(writer.toByteArray(), sites, unimplemented, references);
    }

    /** The next instruction that is not a label, line number or frame. */
    private static @Nullable AbstractInsnNode next(final AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next;
    }

    private static int bridgeAccess(final boolean isInterface, final int version) {
        return Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | (isInterface && version < Opcodes.V9 ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE);
    }

    private static MethodNode bridge(final String name, final MethodInsnNode call, final @Nullable String sink, final String modId,
                                     final boolean isInterface, final int version) {
        MethodNode bridge = new MethodNode(bridgeAccess(isInterface, version), name, call.desc, null, null);
        InsnList code = bridge.instructions;
        Type type = Type.getMethodType(call.desc);
        LabelNode fallback = new LabelNode();
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SINK, "isActive", "()Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        if (sink != null) {
            loadArguments(code, type);
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, sink, call.name, call.desc, false));
        } else {
            code.add(new LdcInsnNode(modId));
            code.add(new LdcInsnNode(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name + call.desc));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SINK, "unimplemented", "(Ljava/lang/String;Ljava/lang/String;)V", false));
            pushDefault(code, type.getReturnType());
        }
        code.add(new InsnNode(type.getReturnType().getOpcode(Opcodes.IRETURN)));
        code.add(fallback);
        if (version >= Opcodes.V1_6) {
            code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        }
        loadArguments(code, type);
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, call.owner, call.name, call.desc, false));
        code.add(new InsnNode(type.getReturnType().getOpcode(Opcodes.IRETURN)));
        return bridge;
    }

    /** {@code (Object)I}: Metal → {@code MetalGLInterop.glId(o)}; OpenGL → {@code ((owner) o).glId()}. */
    private static MethodNode glIdBridge(final String name, final String owner, final boolean isInterface, final int version) {
        MethodNode bridge = new MethodNode(bridgeAccess(isInterface, version), name, "(Ljava/lang/Object;)I", null, null);
        InsnList code = bridge.instructions;
        LabelNode fallback = new LabelNode();
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SINK, "isActive", "()Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEROP, "glId", "(Ljava/lang/Object;)I", false));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(fallback);
        if (version >= Opcodes.V1_6) {
            code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, owner));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "glId", "()I", false));
        code.add(new InsnNode(Opcodes.IRETURN));
        return bridge;
    }

    private static void loadArguments(final InsnList code, final Type method) {
        int slot = 0;
        for (Type argument : method.getArgumentTypes()) {
            code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
            slot += argument.getSize();
        }
    }

    private static void pushDefault(final InsnList code, final Type type) {
        switch (type.getSort()) {
            case Type.VOID -> {
            }
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> code.add(new InsnNode(Opcodes.ICONST_0));
            case Type.LONG -> code.add(new InsnNode(Opcodes.LCONST_0));
            case Type.FLOAT -> code.add(new InsnNode(Opcodes.FCONST_0));
            case Type.DOUBLE -> code.add(new InsnNode(Opcodes.DCONST_0));
            default -> code.add(new InsnNode(Opcodes.ACONST_NULL));
        }
    }
}
