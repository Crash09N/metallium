/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.gl;

import de.nprmedia.nativium.api.NativiumRuntime;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import static org.lwjgl.opengl.GL46C.*;

/**
 * The GL sink: static methods with exactly the names and descriptors of LWJGL's {@code org.lwjgl.opengl.GL*} entry
 * points. Calls in opted-in mods are retargeted here at class-load time ({@code GlCallRetargeter}); a call whose
 * name + descriptor has no method here is routed to {@link #unimplemented} instead (logged once, default result).
 *
 * <p>Everything delegates to the current {@link GlContext}. Methods here hold no logic beyond adapting LWJGL's
 * buffer/array overloads.
 */
@SuppressWarnings("unused")
public final class MetalGL {
    private static volatile boolean enabled = true;

    private MetalGL() {
    }

    // ---------------------------------------------------------------------------------------------- runtime hooks

    /** True when retargeted calls must go to nativium-gl (Metal backend active); otherwise they go to real GL. */
    public static boolean isActive() {
        return enabled && NativiumRuntime.isMetalActive();
    }

    public static void setEnabled(final boolean value) {
        enabled = value;
    }

    /** Called by retargeting bridges for GL functions that have no implementation here. */
    public static void unimplemented(final String mod, final String function) {
        MetalGLLog.unimplemented(mod, function);
    }

    private static GlContext gl() {
        return GlContext.current();
    }

    // ---------------------------------------------------------------------------------------------- GL (capabilities)

    public static GLCapabilities getCapabilities() {
        return MetalGLCapabilities.get();
    }

    public static GLCapabilities createCapabilities() {
        return MetalGLCapabilities.get();
    }

    // ---------------------------------------------------------------------------------------------- errors, queries

    public static int glGetError() {
        return gl().getError();
    }

    public static @Nullable String glGetString(final int name) {
        return gl().getString(name);
    }

    public static @Nullable String glGetStringi(final int name, final int index) {
        return gl().getStringi(name, index);
    }

    public static int glGetInteger(final int pname) {
        int[] out = new int[4];
        gl().getInteger(pname, out);
        return out[0];
    }

    public static void glGetIntegerv(final int pname, final IntBuffer params) {
        int[] out = new int[4];
        int n = gl().getInteger(pname, out);
        for (int i = 0; i < n && params.position() + i < params.limit(); i++) {
            params.put(params.position() + i, out[i]);
        }
    }

    public static void glGetIntegerv(final int pname, final int[] params) {
        int[] out = new int[4];
        int n = gl().getInteger(pname, out);
        System.arraycopy(out, 0, params, 0, Math.min(n, params.length));
    }

    public static boolean glGetBoolean(final int pname) {
        return glGetInteger(pname) != 0;
    }

    public static float glGetFloat(final int pname) {
        return gl().getFloat(pname);
    }

    public static boolean glIsEnabled(final int cap) {
        return gl().isEnabled(cap);
    }

    public static void glFlush() {
        gl().device.flush();
    }

    public static void glFinish() {
        gl().finish();
    }

    public static void glHint(final int target, final int mode) {
        // Hints have no Metal equivalent.
    }

    // ---------------------------------------------------------------------------------------------- state

    public static void glEnable(final int cap) {
        gl().enable(cap, true);
    }

    public static void glDisable(final int cap) {
        gl().enable(cap, false);
    }

    public static void glEnablei(final int cap, final int index) {
        gl().enablei(cap, index, true);
    }

    public static void glDisablei(final int cap, final int index) {
        gl().enablei(cap, index, false);
    }

    public static void glViewport(final int x, final int y, final int width, final int height) {
        gl().viewport(x, y, width, height);
    }

    public static void glScissor(final int x, final int y, final int width, final int height) {
        gl().scissor(x, y, width, height);
    }

    public static void glBlendFunc(final int sfactor, final int dfactor) {
        gl().blendFuncSeparate(-1, sfactor, dfactor, sfactor, dfactor);
    }

    public static void glBlendFuncSeparate(final int srcRgb, final int dstRgb, final int srcAlpha, final int dstAlpha) {
        gl().blendFuncSeparate(-1, srcRgb, dstRgb, srcAlpha, dstAlpha);
    }

    public static void glBlendFunci(final int buf, final int sfactor, final int dfactor) {
        gl().blendFuncSeparate(buf, sfactor, dfactor, sfactor, dfactor);
    }

    public static void glBlendFuncSeparatei(final int buf, final int srcRgb, final int dstRgb, final int srcAlpha, final int dstAlpha) {
        gl().blendFuncSeparate(buf, srcRgb, dstRgb, srcAlpha, dstAlpha);
    }

    public static void glBlendEquation(final int mode) {
        gl().blendEquationSeparate(-1, mode, mode);
    }

    public static void glBlendEquationSeparate(final int rgb, final int alpha) {
        gl().blendEquationSeparate(-1, rgb, alpha);
    }

    public static void glBlendEquationSeparatei(final int buf, final int rgb, final int alpha) {
        gl().blendEquationSeparate(buf, rgb, alpha);
    }

    public static void glBlendColor(final float r, final float g, final float b, final float a) {
        float[] c = gl().blendColor;
        c[0] = r;
        c[1] = g;
        c[2] = b;
        c[3] = a;
    }

    public static void glColorMask(final boolean r, final boolean g, final boolean b, final boolean a) {
        gl().colorMask(-1, r, g, b, a);
    }

    public static void glColorMaski(final int buf, final boolean r, final boolean g, final boolean b, final boolean a) {
        gl().colorMask(buf, r, g, b, a);
    }

    public static void glDepthFunc(final int func) {
        gl().depthFunc(func);
    }

    public static void glDepthMask(final boolean flag) {
        gl().depthMask = flag;
    }

    public static void glDepthRange(final double near, final double far) {
        GlContext gl = gl();
        gl.depthNear = (float) Math.clamp(near, 0.0, 1.0);
        gl.depthFar = (float) Math.clamp(far, 0.0, 1.0);
    }

    public static void glCullFace(final int mode) {
        gl().cullFace(mode);
    }

    public static void glFrontFace(final int mode) {
        gl().frontFace(mode);
    }

    public static void glPolygonOffset(final float factor, final float units) {
        GlContext gl = gl();
        gl.polygonOffsetFactor = factor;
        gl.polygonOffsetUnits = units;
    }

    public static void glPolygonMode(final int face, final int mode) {
        gl().polygonMode(face, mode);
    }

    public static void glLineWidth(final float width) {
        if (width <= 0f) {
            gl().error(GL_INVALID_VALUE);
            return;
        }
        gl().lineWidth = width; // Metal rasterises 1px lines only
    }

    public static void glClearColor(final float r, final float g, final float b, final float a) {
        float[] c = gl().clearColor;
        c[0] = r;
        c[1] = g;
        c[2] = b;
        c[3] = a;
    }

    public static void glClearDepth(final double depth) {
        gl().clearDepth = (float) Math.clamp(depth, 0.0, 1.0);
    }

    public static void glClearStencil(final int s) {
        gl().clearStencil = s;
    }

    public static void glClear(final int mask) {
        gl().clear(mask);
    }

    public static void glPixelStorei(final int pname, final int param) {
        gl().pixelStorei(pname, param);
    }

    // ---------------------------------------------------------------------------------------------- buffers

    public static int glGenBuffers() {
        return gl().genBuffer();
    }

    public static void glGenBuffers(final IntBuffer buffers) {
        for (int i = buffers.position(); i < buffers.limit(); i++) buffers.put(i, gl().genBuffer());
    }

    public static void glGenBuffers(final int[] buffers) {
        for (int i = 0; i < buffers.length; i++) buffers[i] = gl().genBuffer();
    }

    public static int glCreateBuffers() {
        return gl().createBuffer();
    }

    public static void glDeleteBuffers(final int buffer) {
        gl().deleteBuffer(buffer);
    }

    public static void glDeleteBuffers(final IntBuffer buffers) {
        for (int i = buffers.position(); i < buffers.limit(); i++) gl().deleteBuffer(buffers.get(i));
    }

    public static void glDeleteBuffers(final int[] buffers) {
        for (int buffer : buffers) gl().deleteBuffer(buffer);
    }

    public static boolean glIsBuffer(final int buffer) {
        return gl().isBuffer(buffer);
    }

    public static void glBindBuffer(final int target, final int buffer) {
        gl().bindBuffer(target, buffer);
    }

    public static void glBufferData(final int target, final long size, final int usage) {
        gl().bufferData(target, size, null, usage);
    }

    public static void glBufferData(final int target, final ByteBuffer data, final int usage) {
        gl().bufferData(target, data.remaining(), data, usage);
    }

    public static void glBufferData(final int target, final ShortBuffer data, final int usage) {
        gl().bufferData(target, data.remaining() * 2L, bytes(data), usage);
    }

    public static void glBufferData(final int target, final IntBuffer data, final int usage) {
        gl().bufferData(target, data.remaining() * 4L, bytes(data), usage);
    }

    public static void glBufferData(final int target, final FloatBuffer data, final int usage) {
        gl().bufferData(target, data.remaining() * 4L, bytes(data), usage);
    }

    public static void glBufferData(final int target, final float[] data, final int usage) {
        ByteBuffer copy = copyOf(data);
        try {
            gl().bufferData(target, copy.remaining(), copy, usage);
        } finally {
            MemoryUtil.memFree(copy);
        }
    }

    public static void glBufferData(final int target, final int[] data, final int usage) {
        ByteBuffer copy = copyOf(data);
        try {
            gl().bufferData(target, copy.remaining(), copy, usage);
        } finally {
            MemoryUtil.memFree(copy);
        }
    }

    public static void glBufferSubData(final int target, final long offset, final ByteBuffer data) {
        gl().bufferSubData(target, offset, data);
    }

    public static void glBufferSubData(final int target, final long offset, final ShortBuffer data) {
        gl().bufferSubData(target, offset, bytes(data));
    }

    public static void glBufferSubData(final int target, final long offset, final IntBuffer data) {
        gl().bufferSubData(target, offset, bytes(data));
    }

    public static void glBufferSubData(final int target, final long offset, final FloatBuffer data) {
        gl().bufferSubData(target, offset, bytes(data));
    }

    public static void glBufferSubData(final int target, final long offset, final float[] data) {
        ByteBuffer copy = copyOf(data);
        try {
            gl().bufferSubData(target, offset, copy);
        } finally {
            MemoryUtil.memFree(copy);
        }
    }

    public static void glBufferStorage(final int target, final long size, final int flags) {
        gl().bufferStorage(target, size, null, flags);
    }

    public static void glBufferStorage(final int target, final ByteBuffer data, final int flags) {
        gl().bufferStorage(target, data.remaining(), data, flags);
    }

    public static void glBufferStorage(final int target, final FloatBuffer data, final int flags) {
        gl().bufferStorage(target, data.remaining() * 4L, bytes(data), flags);
    }

    public static void glBufferStorage(final int target, final IntBuffer data, final int flags) {
        gl().bufferStorage(target, data.remaining() * 4L, bytes(data), flags);
    }

    public static @Nullable ByteBuffer glMapBuffer(final int target, final int access) {
        return gl().mapBuffer(target, access);
    }

    public static @Nullable ByteBuffer glMapBufferRange(final int target, final long offset, final long length, final int access) {
        return gl().mapBufferRange(target, offset, length, access);
    }

    public static void glFlushMappedBufferRange(final int target, final long offset, final long length) {
        // Unified memory: writes through a mapping are visible to the GPU without an explicit flush.
    }

    public static boolean glUnmapBuffer(final int target) {
        return gl().unmapBuffer(target);
    }

    // ---------------------------------------------------------------------------------------------- textures

    public static int glGenTextures() {
        return gl().genTexture();
    }

    public static void glGenTextures(final IntBuffer textures) {
        for (int i = textures.position(); i < textures.limit(); i++) textures.put(i, gl().genTexture());
    }

    public static void glGenTextures(final int[] textures) {
        for (int i = 0; i < textures.length; i++) textures[i] = gl().genTexture();
    }

    public static void glDeleteTextures(final int texture) {
        gl().deleteTexture(texture);
    }

    public static void glDeleteTextures(final IntBuffer textures) {
        for (int i = textures.position(); i < textures.limit(); i++) gl().deleteTexture(textures.get(i));
    }

    public static void glDeleteTextures(final int[] textures) {
        for (int texture : textures) gl().deleteTexture(texture);
    }

    public static boolean glIsTexture(final int texture) {
        return gl().isTexture(texture);
    }

    public static void glActiveTexture(final int texture) {
        gl().activeTexture(texture);
    }

    public static void glBindTexture(final int target, final int texture) {
        gl().bindTexture(target, texture);
    }

    public static void glTexImage2D(final int target, final int level, final int internalformat, final int width, final int height,
                                    final int border, final int format, final int type, final @Nullable ByteBuffer pixels) {
        gl().texImage2D(target, level, internalformat, width, height, border, format, type, pixels);
    }

    public static void glTexImage2D(final int target, final int level, final int internalformat, final int width, final int height,
                                    final int border, final int format, final int type, final @Nullable IntBuffer pixels) {
        gl().texImage2D(target, level, internalformat, width, height, border, format, type, pixels == null ? null : bytes(pixels));
    }

    public static void glTexImage2D(final int target, final int level, final int internalformat, final int width, final int height,
                                    final int border, final int format, final int type, final @Nullable FloatBuffer pixels) {
        gl().texImage2D(target, level, internalformat, width, height, border, format, type, pixels == null ? null : bytes(pixels));
    }

    public static void glTexImage2D(final int target, final int level, final int internalformat, final int width, final int height,
                                    final int border, final int format, final int type, final long pixels) {
        GlContext gl = gl();
        ByteBuffer source = gl.hasUnpackBuffer() ? gl.unpackSource(pixels) : null;
        if (!gl.hasUnpackBuffer() && pixels != 0L) {
            source = MemoryUtil.memByteBuffer(pixels, (int) GlPixels.clientBytes(format, type, width, height, gl.unpack));
        }
        gl.texImage2D(target, level, internalformat, width, height, border, format, type, source);
    }

    public static void glTexSubImage2D(final int target, final int level, final int xoffset, final int yoffset, final int width, final int height,
                                       final int format, final int type, final ByteBuffer pixels) {
        gl().texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
    }

    public static void glTexSubImage2D(final int target, final int level, final int xoffset, final int yoffset, final int width, final int height,
                                       final int format, final int type, final IntBuffer pixels) {
        gl().texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, bytes(pixels));
    }

    public static void glTexSubImage2D(final int target, final int level, final int xoffset, final int yoffset, final int width, final int height,
                                       final int format, final int type, final FloatBuffer pixels) {
        gl().texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, bytes(pixels));
    }

    public static void glTexSubImage2D(final int target, final int level, final int xoffset, final int yoffset, final int width, final int height,
                                       final int format, final int type, final long pixels) {
        GlContext gl = gl();
        ByteBuffer source = gl.hasUnpackBuffer() ? gl.unpackSource(pixels)
                : MemoryUtil.memByteBuffer(pixels, (int) GlPixels.clientBytes(format, type, width, height, gl.unpack));
        if (source != null) {
            gl.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, source);
        }
    }

    public static void glTexStorage2D(final int target, final int levels, final int internalformat, final int width, final int height) {
        gl().texStorage2D(target, levels, internalformat, width, height);
    }

    public static void glTexParameteri(final int target, final int pname, final int param) {
        gl().texParameteri(target, pname, param);
    }

    public static void glTexParameterf(final int target, final int pname, final float param) {
        gl().texParameterf(target, pname, param);
    }

    public static void glTexParameteriv(final int target, final int pname, final IntBuffer params) {
        gl().texParameteri(target, pname, params.get(params.position()));
    }

    public static void glTexParameteriv(final int target, final int pname, final int[] params) {
        gl().texParameteri(target, pname, params[0]);
    }

    public static int glGetTexParameteri(final int target, final int pname) {
        return gl().getTexParameteri(target, pname);
    }

    public static int glGetTexLevelParameteri(final int target, final int level, final int pname) {
        return gl().getTexLevelParameteri(target, level, pname);
    }

    public static void glGenerateMipmap(final int target) {
        gl().generateMipmap(target);
    }

    // ---------------------------------------------------------------------------------------------- framebuffers

    public static int glGenFramebuffers() {
        return gl().genFramebuffer();
    }

    public static void glGenFramebuffers(final IntBuffer framebuffers) {
        for (int i = framebuffers.position(); i < framebuffers.limit(); i++) framebuffers.put(i, gl().genFramebuffer());
    }

    public static void glDeleteFramebuffers(final int framebuffer) {
        gl().deleteFramebuffer(framebuffer);
    }

    public static void glDeleteFramebuffers(final IntBuffer framebuffers) {
        for (int i = framebuffers.position(); i < framebuffers.limit(); i++) gl().deleteFramebuffer(framebuffers.get(i));
    }

    public static boolean glIsFramebuffer(final int framebuffer) {
        return gl().framebuffers.get(framebuffer) != null;
    }

    public static void glBindFramebuffer(final int target, final int framebuffer) {
        gl().bindFramebuffer(target, framebuffer);
    }

    public static void glFramebufferTexture2D(final int target, final int attachment, final int textarget, final int texture, final int level) {
        if (texture != 0 && textarget != GL_TEXTURE_2D) {
            MetalGLLog.unsupported("glFramebufferTexture2D textarget 0x" + Integer.toHexString(textarget));
            gl().error(GL_INVALID_OPERATION);
            return;
        }
        gl().framebufferAttach(target, attachment, texture, level, false);
    }

    public static void glFramebufferTexture(final int target, final int attachment, final int texture, final int level) {
        gl().framebufferAttach(target, attachment, texture, level, false);
    }

    public static void glFramebufferRenderbuffer(final int target, final int attachment, final int renderbuffertarget, final int renderbuffer) {
        if (renderbuffertarget != GL_RENDERBUFFER) {
            gl().error(GL_INVALID_ENUM);
            return;
        }
        gl().framebufferAttach(target, attachment, renderbuffer, 0, true);
    }

    public static int glCheckFramebufferStatus(final int target) {
        return gl().checkFramebufferStatus(target);
    }

    public static void glDrawBuffer(final int buf) {
        GlContext gl = gl();
        gl.drawBuffers(new int[]{buf});
    }

    public static void glDrawBuffers(final int buf) {
        gl().drawBuffers(new int[]{buf});
    }

    public static void glDrawBuffers(final IntBuffer bufs) {
        int[] array = new int[bufs.remaining()];
        bufs.duplicate().get(array);
        gl().drawBuffers(array);
    }

    public static void glDrawBuffers(final int[] bufs) {
        gl().drawBuffers(bufs.clone());
    }

    public static void glReadBuffer(final int src) {
        gl().readBuffer(src);
    }

    public static int glGenRenderbuffers() {
        return gl().genRenderbuffer();
    }

    public static void glDeleteRenderbuffers(final int renderbuffer) {
        gl().deleteRenderbuffer(renderbuffer);
    }

    public static void glBindRenderbuffer(final int target, final int renderbuffer) {
        gl().bindRenderbuffer(target, renderbuffer);
    }

    public static void glRenderbufferStorage(final int target, final int internalformat, final int width, final int height) {
        gl().renderbufferStorage(target, internalformat, width, height);
    }

    public static void glGetTexImage(final int target, final int level, final int format, final int type, final long pixels) {
        gl().getTexImage(target, level, format, type, pixels);
    }

    public static void glReadPixels(final int x, final int y, final int width, final int height, final int format, final int type, final ByteBuffer pixels) {
        gl().readPixels(x, y, width, height, format, type, pixels);
    }

    // ---------------------------------------------------------------------------------------------- vertex arrays

    public static int glGenVertexArrays() {
        return gl().genVertexArray();
    }

    public static void glGenVertexArrays(final IntBuffer arrays) {
        for (int i = arrays.position(); i < arrays.limit(); i++) arrays.put(i, gl().genVertexArray());
    }

    public static void glDeleteVertexArrays(final int array) {
        gl().deleteVertexArray(array);
    }

    public static void glDeleteVertexArrays(final IntBuffer arrays) {
        for (int i = arrays.position(); i < arrays.limit(); i++) gl().deleteVertexArray(arrays.get(i));
    }

    public static boolean glIsVertexArray(final int array) {
        return gl().isVertexArray(array);
    }

    public static void glBindVertexArray(final int array) {
        gl().bindVertexArray(array);
    }

    public static void glEnableVertexAttribArray(final int index) {
        gl().enableVertexAttribArray(index, true);
    }

    public static void glDisableVertexAttribArray(final int index) {
        gl().enableVertexAttribArray(index, false);
    }

    public static void glVertexAttribPointer(final int index, final int size, final int type, final boolean normalized, final int stride, final long pointer) {
        gl().vertexAttribPointer(index, size, type, normalized, false, stride, pointer);
    }

    public static void glVertexAttribIPointer(final int index, final int size, final int type, final int stride, final long pointer) {
        gl().vertexAttribPointer(index, size, type, false, true, stride, pointer);
    }

    public static void glVertexAttribDivisor(final int index, final int divisor) {
        gl().vertexAttribDivisor(index, divisor);
    }

    public static void glVertexAttrib1f(final int index, final float x) {
        gl().vertexAttrib4f(index, x, 0f, 0f, 1f);
    }

    public static void glVertexAttrib2f(final int index, final float x, final float y) {
        gl().vertexAttrib4f(index, x, y, 0f, 1f);
    }

    public static void glVertexAttrib3f(final int index, final float x, final float y, final float z) {
        gl().vertexAttrib4f(index, x, y, z, 1f);
    }

    public static void glVertexAttrib4f(final int index, final float x, final float y, final float z, final float w) {
        gl().vertexAttrib4f(index, x, y, z, w);
    }

    // ---------------------------------------------------------------------------------------------- draws

    public static void glDrawArrays(final int mode, final int first, final int count) {
        gl().drawArrays(mode, first, count, 1, 0);
    }

    public static void glDrawArraysInstanced(final int mode, final int first, final int count, final int primcount) {
        gl().drawArrays(mode, first, count, primcount, 0);
    }

    public static void glDrawElements(final int mode, final int count, final int type, final long indices) {
        gl().drawElements(mode, count, type, indices, 1, 0);
    }

    public static void glDrawElements(final int mode, final int type, final ByteBuffer indices) {
        gl().drawElementsClient(mode, type, indices);
    }

    public static void glDrawElements(final int mode, final ByteBuffer indices) {
        gl().drawElementsClient(mode, GL_UNSIGNED_BYTE, indices);
    }

    public static void glDrawElements(final int mode, final ShortBuffer indices) {
        gl().drawElementsClient(mode, GL_UNSIGNED_SHORT, bytes(indices));
    }

    public static void glDrawElements(final int mode, final IntBuffer indices) {
        gl().drawElementsClient(mode, GL_UNSIGNED_INT, bytes(indices));
    }

    public static void glDrawElementsInstanced(final int mode, final int count, final int type, final long indices, final int primcount) {
        gl().drawElements(mode, count, type, indices, primcount, 0);
    }

    public static void glDrawElementsBaseVertex(final int mode, final int count, final int type, final long indices, final int basevertex) {
        gl().drawElements(mode, count, type, indices, 1, basevertex);
    }

    // ---------------------------------------------------------------------------------------------- shaders, programs

    public static int glCreateShader(final int type) {
        return gl().createShader(type);
    }

    public static void glShaderSource(final int shader, final CharSequence string) {
        gl().shaderSource(shader, string.toString());
    }

    public static void glShaderSource(final int shader, final CharSequence... strings) {
        gl().shaderSource(shader, String.join("", strings));
    }

    public static void glCompileShader(final int shader) {
        gl().compileShader(shader);
    }

    public static void glDeleteShader(final int shader) {
        gl().deleteShader(shader);
    }

    public static boolean glIsShader(final int shader) {
        return gl().isShader(shader);
    }

    public static int glGetShaderi(final int shader, final int pname) {
        return gl().getShaderi(shader, pname);
    }

    public static void glGetShaderiv(final int shader, final int pname, final IntBuffer params) {
        params.put(params.position(), gl().getShaderi(shader, pname));
    }

    public static void glGetShaderiv(final int shader, final int pname, final int[] params) {
        params[0] = gl().getShaderi(shader, pname);
    }

    public static String glGetShaderInfoLog(final int shader) {
        return gl().getShaderInfoLog(shader);
    }

    public static String glGetShaderInfoLog(final int shader, final int maxLength) {
        String log = gl().getShaderInfoLog(shader);
        return log.length() > maxLength ? log.substring(0, Math.max(0, maxLength)) : log;
    }

    public static int glCreateProgram() {
        return gl().createProgram();
    }

    public static void glAttachShader(final int program, final int shader) {
        gl().attachShader(program, shader);
    }

    public static void glDetachShader(final int program, final int shader) {
        gl().detachShader(program, shader);
    }

    public static void glBindAttribLocation(final int program, final int index, final CharSequence name) {
        gl().bindAttribLocation(program, index, name.toString());
    }

    public static void glLinkProgram(final int program) {
        gl().linkProgram(program);
    }

    public static void glValidateProgram(final int program) {
        gl().validateProgram(program);
    }

    public static void glUseProgram(final int program) {
        gl().useProgram(program);
    }

    public static void glDeleteProgram(final int program) {
        gl().deleteProgram(program);
    }

    public static boolean glIsProgram(final int program) {
        return gl().isProgram(program);
    }

    public static int glGetProgrami(final int program, final int pname) {
        return gl().getProgrami(program, pname);
    }

    public static void glGetProgramiv(final int program, final int pname, final IntBuffer params) {
        params.put(params.position(), gl().getProgrami(program, pname));
    }

    public static void glGetProgramiv(final int program, final int pname, final int[] params) {
        params[0] = gl().getProgrami(program, pname);
    }

    public static String glGetProgramInfoLog(final int program) {
        return gl().getProgramInfoLog(program);
    }

    public static String glGetProgramInfoLog(final int program, final int maxLength) {
        String log = gl().getProgramInfoLog(program);
        return log.length() > maxLength ? log.substring(0, Math.max(0, maxLength)) : log;
    }

    public static int glGetAttribLocation(final int program, final CharSequence name) {
        return gl().getAttribLocation(program, name.toString());
    }

    public static int glGetUniformLocation(final int program, final CharSequence name) {
        return gl().getUniformLocation(program, name.toString());
    }

    // ---------------------------------------------------------------------------------------------- uniforms

    public static void glUniform1f(final int location, final float v0) {
        gl().uniform(location, 1, 1, false, false, null, new float[]{v0});
    }

    public static void glUniform2f(final int location, final float v0, final float v1) {
        gl().uniform(location, 2, 1, false, false, null, new float[]{v0, v1});
    }

    public static void glUniform3f(final int location, final float v0, final float v1, final float v2) {
        gl().uniform(location, 3, 1, false, false, null, new float[]{v0, v1, v2});
    }

    public static void glUniform4f(final int location, final float v0, final float v1, final float v2, final float v3) {
        gl().uniform(location, 4, 1, false, false, null, new float[]{v0, v1, v2, v3});
    }

    public static void glUniform1i(final int location, final int v0) {
        gl().uniform(location, 1, 1, true, false, new int[]{v0}, null);
    }

    public static void glUniform2i(final int location, final int v0, final int v1) {
        gl().uniform(location, 2, 1, true, false, new int[]{v0, v1}, null);
    }

    public static void glUniform3i(final int location, final int v0, final int v1, final int v2) {
        gl().uniform(location, 3, 1, true, false, new int[]{v0, v1, v2}, null);
    }

    public static void glUniform4i(final int location, final int v0, final int v1, final int v2, final int v3) {
        gl().uniform(location, 4, 1, true, false, new int[]{v0, v1, v2, v3}, null);
    }

    public static void glUniform1ui(final int location, final int v0) {
        gl().uniform(location, 1, 1, true, true, new int[]{v0}, null);
    }

    public static void glUniform2ui(final int location, final int v0, final int v1) {
        gl().uniform(location, 2, 1, true, true, new int[]{v0, v1}, null);
    }

    public static void glUniform3ui(final int location, final int v0, final int v1, final int v2) {
        gl().uniform(location, 3, 1, true, true, new int[]{v0, v1, v2}, null);
    }

    public static void glUniform4ui(final int location, final int v0, final int v1, final int v2, final int v3) {
        gl().uniform(location, 4, 1, true, true, new int[]{v0, v1, v2, v3}, null);
    }

    public static void glUniform1fv(final int location, final FloatBuffer value) {
        floatv(location, 1, value);
    }

    public static void glUniform2fv(final int location, final FloatBuffer value) {
        floatv(location, 2, value);
    }

    public static void glUniform3fv(final int location, final FloatBuffer value) {
        floatv(location, 3, value);
    }

    public static void glUniform4fv(final int location, final FloatBuffer value) {
        floatv(location, 4, value);
    }

    public static void glUniform1fv(final int location, final float[] value) {
        gl().uniform(location, 1, value.length, false, false, null, value);
    }

    public static void glUniform2fv(final int location, final float[] value) {
        gl().uniform(location, 2, value.length / 2, false, false, null, value);
    }

    public static void glUniform3fv(final int location, final float[] value) {
        gl().uniform(location, 3, value.length / 3, false, false, null, value);
    }

    public static void glUniform4fv(final int location, final float[] value) {
        gl().uniform(location, 4, value.length / 4, false, false, null, value);
    }

    public static void glUniform1iv(final int location, final IntBuffer value) {
        intv(location, 1, false, value);
    }

    public static void glUniform2iv(final int location, final IntBuffer value) {
        intv(location, 2, false, value);
    }

    public static void glUniform3iv(final int location, final IntBuffer value) {
        intv(location, 3, false, value);
    }

    public static void glUniform4iv(final int location, final IntBuffer value) {
        intv(location, 4, false, value);
    }

    public static void glUniform1iv(final int location, final int[] value) {
        gl().uniform(location, 1, value.length, true, false, value, null);
    }

    public static void glUniform4iv(final int location, final int[] value) {
        gl().uniform(location, 4, value.length / 4, true, false, value, null);
    }

    public static void glUniformMatrix2fv(final int location, final boolean transpose, final FloatBuffer value) {
        matrix(location, 2, 2, transpose, value);
    }

    public static void glUniformMatrix3fv(final int location, final boolean transpose, final FloatBuffer value) {
        matrix(location, 3, 3, transpose, value);
    }

    public static void glUniformMatrix4fv(final int location, final boolean transpose, final FloatBuffer value) {
        matrix(location, 4, 4, transpose, value);
    }

    public static void glUniformMatrix2fv(final int location, final boolean transpose, final float[] value) {
        gl().uniformMatrix(location, 2, 2, value.length / 4, transpose, value);
    }

    public static void glUniformMatrix3fv(final int location, final boolean transpose, final float[] value) {
        gl().uniformMatrix(location, 3, 3, value.length / 9, transpose, value);
    }

    public static void glUniformMatrix4fv(final int location, final boolean transpose, final float[] value) {
        gl().uniformMatrix(location, 4, 4, value.length / 16, transpose, value);
    }

    private static void floatv(final int location, final int components, final FloatBuffer value) {
        float[] values = new float[value.remaining()];
        value.duplicate().get(values);
        gl().uniform(location, components, values.length / components, false, false, null, values);
    }

    private static void intv(final int location, final int components, final boolean unsigned, final IntBuffer value) {
        int[] values = new int[value.remaining()];
        value.duplicate().get(values);
        gl().uniform(location, components, values.length / components, true, unsigned, values, null);
    }

    private static void matrix(final int location, final int columns, final int rows, final boolean transpose, final FloatBuffer value) {
        float[] values = new float[value.remaining()];
        value.duplicate().get(values);
        gl().uniformMatrix(location, columns, rows, values.length / (columns * rows), transpose, values);
    }

    // ---------------------------------------------------------------------------------------------- sync

    public static long glFenceSync(final int condition, final int flags) {
        return gl().fenceSync(condition, flags);
    }

    public static int glClientWaitSync(final long sync, final int flags, final long timeout) {
        return gl().clientWaitSync(sync, flags, timeout);
    }

    public static void glWaitSync(final long sync, final int flags, final long timeout) {
        // GPU-side wait: commands are already ordered on the single Metal queue.
        if (!gl().isSync(sync)) {
            gl().error(GL_INVALID_VALUE);
        }
    }

    public static void glDeleteSync(final long sync) {
        gl().deleteSync(sync);
    }

    public static boolean glIsSync(final long sync) {
        return gl().isSync(sync);
    }

    public static int glGetSynci(final long sync, final int pname, final @Nullable IntBuffer length) {
        GlContext gl = gl();
        int value = switch (pname) {
            case GL_SYNC_STATUS -> gl.getSyncStatus(sync);
            case GL_OBJECT_TYPE -> GL_SYNC_FENCE;
            case GL_SYNC_CONDITION -> GL_SYNC_GPU_COMMANDS_COMPLETE;
            case GL_SYNC_FLAGS -> 0;
            default -> {
                gl.error(GL_INVALID_ENUM);
                yield 0;
            }
        };
        if (length != null) {
            length.put(length.position(), 1);
        }
        return value;
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private static ByteBuffer bytes(final ShortBuffer buffer) {
        return MemoryUtil.memByteBuffer(MemoryUtil.memAddress(buffer), buffer.remaining() * 2).order(ByteOrder.nativeOrder());
    }

    private static ByteBuffer bytes(final IntBuffer buffer) {
        return MemoryUtil.memByteBuffer(MemoryUtil.memAddress(buffer), buffer.remaining() * 4).order(ByteOrder.nativeOrder());
    }

    private static ByteBuffer bytes(final FloatBuffer buffer) {
        return MemoryUtil.memByteBuffer(MemoryUtil.memAddress(buffer), buffer.remaining() * 4).order(ByteOrder.nativeOrder());
    }

    private static ByteBuffer copyOf(final float[] data) {
        ByteBuffer copy = MemoryUtil.memAlloc(data.length * 4).order(ByteOrder.nativeOrder());
        copy.asFloatBuffer().put(data);
        return copy;
    }

    private static ByteBuffer copyOf(final int[] data) {
        ByteBuffer copy = MemoryUtil.memAlloc(data.length * 4).order(ByteOrder.nativeOrder());
        copy.asIntBuffer().put(data);
        return copy;
    }
}
