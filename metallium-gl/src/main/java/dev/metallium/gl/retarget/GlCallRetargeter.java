/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl.retarget;

import dev.metallium.gl.MetalGL;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
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
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rewrites {@code INVOKESTATIC org/lwjgl/opengl/GL*.gl*} (plus {@code GL.getCapabilities/createCapabilities}) in one
 * class. Each distinct target gets a synthetic static bridge in the same class:
 * <pre>
 *   if (MetalGL.isActive()) return MetalGL.glX(args);          // implemented (same name + descriptor)
 *   if (MetalGL.isActive()) { MetalGL.unimplemented(mod, fn); return default; }   // not implemented
 *   return GL15C.glX(args);                                      // OpenGL backend: the original call
 * </pre>
 * so a retargeted mod keeps working when Metallium falls back to OpenGL. Call sites keep their descriptor, so no stack
 * map frames change; the bridge carries its own single SAME frame.
 */
public final class GlCallRetargeter {
    static final String SINK = Type.getInternalName(MetalGL.class);
    private static final String GL_PACKAGE = "org/lwjgl/opengl/";
    private static final Pattern FUNCTION_CLASS = Pattern.compile("GL\\d\\dC?|(ARB|EXT|KHR|NV|AMD|INTEL|APPLE|ATI|OVR)\\w+");
    private static final String BRIDGE_PREFIX = "metallium$gl$";
    private static final Set<String> IMPLEMENTED = implemented();

    private GlCallRetargeter() {
    }

    /** Result of transforming one class. */
    public record Result(byte @Nullable [] bytes, int callSites, int unimplemented, int methodReferences) {
    }

    private static Set<String> implemented() {
        Set<String> set = new HashSet<>();
        for (Method method : MetalGL.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())) {
                set.add(method.getName() + Type.getMethodDescriptor(method));
            }
        }
        return Set.copyOf(set);
    }

    /** True if MetalGL has a method with exactly this name and descriptor. */
    public static boolean isImplemented(final String name, final String descriptor) {
        return IMPLEMENTED.contains(name + descriptor);
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

    /** Quick pre-check on the raw bytes: does the constant pool mention LWJGL's GL package at all? */
    public static boolean mentionsGl(final byte[] bytes) {
        byte[] needle = GL_PACKAGE.getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC && isGlCall(call.owner, call.name)) {
                    String key = call.owner + "." + call.name + call.desc;
                    MethodNode bridge = bridges.get(key);
                    if (bridge == null) {
                        boolean implemented = isImplemented(call.name, call.desc);
                        if (!implemented) unimplemented++;
                        bridge = bridge(BRIDGE_PREFIX + bridges.size() + "$" + call.name, call, implemented, modId, isInterface, version);
                        bridges.put(key, bridge);
                    }
                    call.owner = node.name;
                    call.name = bridge.name;
                    call.itf = isInterface;
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

    private static MethodNode bridge(final String name, final MethodInsnNode call, final boolean implemented, final String modId,
                                     final boolean isInterface, final int version) {
        int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC
                | (isInterface && version < Opcodes.V9 ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE);
        MethodNode bridge = new MethodNode(access, name, call.desc, null, null);
        InsnList code = bridge.instructions;
        Type type = Type.getMethodType(call.desc);
        LabelNode fallback = new LabelNode();
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SINK, "isActive", "()Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        if (implemented) {
            loadArguments(code, type);
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SINK, call.name, call.desc, false));
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
