/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlUsageScannerTest {
    /** A synthetic class {@code name} with one static method whose body {@code body} writes (before RETURN). */
    private static byte[] clazz(final String name, final Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "draw", "()V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void nativeClassIsNotReported() {
        byte[] bytes = clazz("com/example/NativeRenderer", mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/mojang/blaze3d/systems/RenderSystem", "getDevice",
                    "()Lcom/mojang/renderpearl/api/device/GpuDevice;", false);
            mv.visitInsn(Opcodes.POP);
        });
        assertNull(GlUsageScanner.scan(bytes));
    }

    @Test
    void rawGlCallIsReportedWithFunctionName() {
        byte[] bytes = clazz("com/example/gl/Quads", mv -> {
            mv.visitIntInsn(Opcodes.SIPUSH, 0x0BE2);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glEnable", "(I)V", false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL", "getCapabilities", "()Lorg/lwjgl/opengl/GLCapabilities;", false);
            mv.visitInsn(Opcodes.POP);
        });
        GlUsageScanner.ClassUsage usage = GlUsageScanner.scan(bytes);
        assertNotNull(usage);
        assertTrue(usage.rawGl());
        assertFalse(usage.backendGl());
        assertEquals("com/example/gl/Quads", usage.internalName());
        assertTrue(usage.functions().contains("GL11.glEnable"), usage.functions().toString());
        assertTrue(usage.functions().contains("GL.getCapabilities"));
    }

    @Test
    void methodReferenceToGlCountsAsRawGl() {
        byte[] bytes = clazz("com/example/Lambdas", mv -> {
            org.objectweb.asm.Handle bsm = new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                            + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
            mv.visitInvokeDynamicInsn("accept", "()Ljava/util/function/IntConsumer;", bsm,
                    org.objectweb.asm.Type.getType("(I)V"),
                    new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "org/lwjgl/opengl/GL11", "glEnable", "(I)V", false),
                    org.objectweb.asm.Type.getType("(I)V"));
            mv.visitInsn(Opcodes.POP);
        });
        GlUsageScanner.ClassUsage usage = GlUsageScanner.scan(bytes);
        assertNotNull(usage);
        assertTrue(usage.rawGl());
    }

    @Test
    void minecraftOpenGlBackendUseIsReported() {
        byte[] bytes = clazz("com/example/StateHack", mv -> {
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/mojang/renderpearl/backend/opengl/GlStateManager", "_enableBlend", "()V", false);
            mv.visitInsn(Opcodes.POP);
        });
        GlUsageScanner.ClassUsage usage = GlUsageScanner.scan(bytes);
        assertNotNull(usage);
        assertFalse(usage.rawGl());
        assertTrue(usage.backendGl());
    }

    @Test
    void stringMentioningGlPackageIsNotAUse() {
        byte[] bytes = clazz("com/example/Strings", mv -> {
            mv.visitLdcInsn("org/lwjgl/opengl/GL11 is not called here");
            mv.visitInsn(Opcodes.POP);
        });
        assertNull(GlUsageScanner.scan(bytes));
    }

    @Test
    void reportFoldsClassesIntoMod() {
        GlUsageScanner.ClassUsage a = GlUsageScanner.scan(clazz("com/example/gl/A", mv ->
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glFlush", "()V", false)));
        GlUsageScanner.ClassUsage b = GlUsageScanner.scan(clazz("com/example/hud/B", mv ->
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/mojang/renderpearl/backend/opengl/GlStateManager", "_disableBlend", "()V", false)));
        ModReport report = ModScan.report("example", "Example", "1.0", List.of(a, b));
        assertTrue(report.rawGl());
        assertEquals(List.of("com.example.gl.A"), report.glClasses());
        assertEquals(1, report.backendClasses());
        assertEquals(List.of("com.example.gl"), report.packages());
        assertEquals(List.of("GL11.glFlush"), report.functions());
    }

    @Test
    void exclusions() {
        assertTrue(ModScan.excluded("sodium"));
        assertTrue(ModScan.excluded("fabric-rendering-v1"));
        assertTrue(ModScan.excluded("minecraft"));
        assertFalse(ModScan.excluded("metallium-gametest"));
        assertFalse(ModScan.excluded("xaerominimap"));
    }
}
