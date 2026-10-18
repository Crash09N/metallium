/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.compat;

import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Classifies one class file by how it talks to the GPU. Pure bytecode inspection, no class loading:
 * <ul>
 *   <li><b>raw GL</b>: calls LWJGL's OpenGL bindings ({@code org/lwjgl/opengl/*}) directly. These calls fail on Metal
 *       (there is no OpenGL context) unless metallium-gl retargets them.</li>
 *   <li><b>GL backend internals</b>: uses classes of Minecraft's own OpenGL backend
 *       ({@code com/mojang/renderpearl/backend/opengl/*}, e.g. {@code GlStateManager}, {@code GlDevice} casts). metallium-gl
 *       cannot translate those; such code usually breaks on any non-OpenGL backend.</li>
 *   <li>otherwise <b>native</b>: only Minecraft's graphics layer (renderpearl {@code GpuDevice}/{@code RenderPass}/...),
 *       which Metallium implements on Metal.</li>
 * </ul>
 */
public final class GlUsageScanner {
    static final String LWJGL_GL = "org/lwjgl/opengl/";
    static final String BACKEND_GL = "com/mojang/renderpearl/backend/opengl/";
    private static final byte[] LWJGL_NEEDLE = LWJGL_GL.getBytes(StandardCharsets.UTF_8);
    private static final byte[] BACKEND_NEEDLE = BACKEND_GL.getBytes(StandardCharsets.UTF_8);
    /** Distinct GL function names remembered per class (for the status screen). */
    private static final int MAX_FUNCTIONS = 16;

    private GlUsageScanner() {
    }

    /** What one class uses. {@code functions} holds e.g. "GL11.glEnable" (at most a few per class). */
    public record ClassUsage(String internalName, boolean rawGl, boolean backendGl, Set<String> functions) {
    }

    /** Null if the class neither calls raw GL nor touches Minecraft's OpenGL backend. */
    public static @Nullable ClassUsage scan(final byte[] bytes) {
        boolean maybeRaw = contains(bytes, LWJGL_NEEDLE);
        boolean maybeBackend = contains(bytes, BACKEND_NEEDLE);
        if (!maybeRaw && !maybeBackend) {
            return null; // constant pool never mentions either package: the common, fast path
        }
        Collector collector = new Collector();
        new ClassReader(bytes).accept(collector, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (!collector.rawGl && !collector.backendGl) {
            return null; // e.g. only a string constant mentions the package
        }
        return new ClassUsage(collector.name, collector.rawGl, collector.backendGl, Set.copyOf(collector.functions));
    }

    static boolean contains(final byte[] haystack, final byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static final class Collector extends ClassVisitor {
        String name = "";
        boolean rawGl;
        boolean backendGl;
        final Set<String> functions = new LinkedHashSet<>();

        Collector() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(final int version, final int access, final String name, final String signature, final String superName,
                          final String[] interfaces) {
            this.name = name;
            type(superName);
            if (interfaces != null) {
                for (String i : interfaces) type(i);
            }
        }

        @Override
        public FieldVisitor visitField(final int access, final String name, final String descriptor, final String signature, final Object value) {
            descriptor(descriptor);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(final int access, final String name, final String descriptor, final String signature,
                                         final String[] exceptions) {
            descriptor(descriptor);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(final int opcode, final String owner, final String name, final String descriptor, final boolean itf) {
                    call(owner, name);
                    descriptor(descriptor);
                }

                @Override
                public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
                    type(owner);
                    descriptor(descriptor);
                }

                @Override
                public void visitTypeInsn(final int opcode, final String type) {
                    type(type);
                }

                @Override
                public void visitLdcInsn(final Object value) {
                    if (value instanceof Type t && t.getSort() == Type.OBJECT) type(t.getInternalName());
                }

                @Override
                public void visitInvokeDynamicInsn(final String name, final String descriptor, final Handle bsm, final Object... args) {
                    for (Object arg : args) {
                        if (arg instanceof Handle handle) call(handle.getOwner(), handle.getName());
                    }
                }
            };
        }

        private void call(final String owner, final String method) {
            if (owner == null) return;
            if (owner.startsWith(LWJGL_GL)) {
                rawGl = true;
                if (functions.size() < MAX_FUNCTIONS) functions.add(owner.substring(LWJGL_GL.length()) + "." + method);
            } else {
                type(owner);
            }
        }

        private void type(final String internalName) {
            if (internalName == null) return;
            if (internalName.startsWith(BACKEND_GL) || internalName.startsWith("[") && internalName.contains("L" + BACKEND_GL)) {
                backendGl = true;
            }
            // A bare type reference to LWJGL GL (e.g. a GLCapabilities field) is not a call; calls are what fail.
        }

        private void descriptor(final String descriptor) {
            if (descriptor != null && descriptor.contains("L" + BACKEND_GL)) backendGl = true;
        }
    }
}
