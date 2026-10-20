/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.gl;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Sink for retargeted calls to Minecraft's {@code com.mojang.renderpearl.backend.opengl.GlStateManager} (its OpenGL
 * backend's state cache). GL mods call it to change or restore GL state around their rendering; on Metal there is no GL
 * context behind it, so the calls go to metallium-gl's emulated context instead. Same names and descriptors as
 * GlStateManager; anything not listed is reported as unimplemented by the retargeter.
 */
@SuppressWarnings("unused")
public final class MetalGLStateManager {
    private MetalGLStateManager() {
    }

    public static void _activeTexture(final int texture) {
        MetalGL.glActiveTexture(texture);
    }

    public static void _bindTexture(final int texture) {
        MetalGL.glBindTexture(GL_TEXTURE_2D, texture);
    }

    public static void _enableDepthTest() {
        MetalGL.glEnable(GL_DEPTH_TEST);
    }

    public static void _disableDepthTest() {
        MetalGL.glDisable(GL_DEPTH_TEST);
    }

    public static void _depthFunc(final int func) {
        MetalGL.glDepthFunc(func);
    }

    public static void _depthMask(final boolean flag) {
        MetalGL.glDepthMask(flag);
    }

    /** {@code buffer}: draw buffer index (26.3 tracks blending per attachment). */
    public static void _enableBlend(final int buffer) {
        MetalGL.glEnablei(GL_BLEND, buffer);
    }

    public static void _disableBlend(final int buffer) {
        MetalGL.glDisablei(GL_BLEND, buffer);
    }

    public static void _blendFuncSeparate(final int srcRgb, final int dstRgb, final int srcAlpha, final int dstAlpha) {
        MetalGL.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
    }

    public static void _blendEquationSeparate(final int rgb, final int alpha) {
        MetalGL.glBlendEquationSeparate(rgb, alpha);
    }

    public static void _glUseProgram(final int program) {
        MetalGL.glUseProgram(program);
    }

    public static void _glBindVertexArray(final int array) {
        MetalGL.glBindVertexArray(array);
    }

    public static void _glBindBuffer(final int target, final int buffer) {
        MetalGL.glBindBuffer(target, buffer);
    }

    public static void _glBindFramebuffer(final int target, final int framebuffer) {
        MetalGL.glBindFramebuffer(target, framebuffer);
    }

    public static void _enableCull() {
        MetalGL.glEnable(GL_CULL_FACE);
    }

    public static void _disableCull() {
        MetalGL.glDisable(GL_CULL_FACE);
    }

    public static void _enablePolygonOffset() {
        MetalGL.glEnable(GL_POLYGON_OFFSET_FILL);
    }

    public static void _disablePolygonOffset() {
        MetalGL.glDisable(GL_POLYGON_OFFSET_FILL);
    }

    public static void _polygonOffset(final float factor, final float units) {
        MetalGL.glPolygonOffset(factor, units);
    }

    public static void _enableScissorTest() {
        MetalGL.glEnable(GL_SCISSOR_TEST);
    }

    public static void _disableScissorTest() {
        MetalGL.glDisable(GL_SCISSOR_TEST);
    }

    public static void _scissorBox(final int x, final int y, final int width, final int height) {
        MetalGL.glScissor(x, y, width, height);
    }

    public static void _viewport(final int x, final int y, final int width, final int height) {
        MetalGL.glViewport(x, y, width, height);
    }

    public static void _polygonMode(final int face, final int mode) {
        MetalGL.glPolygonMode(face, mode);
    }

    public static int _getError() {
        return MetalGL.glGetError();
    }

    public static int _getInteger(final int pname) {
        return MetalGL.glGetInteger(pname);
    }
}
