/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.gl;

import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.opengl.GL46C.*;

/**
 * The GL 4.x part of the GL sink (compute, SSBO / UBO binding points, images, sampler objects, indirect and
 * indirect-count draws, DSA, queries, debug labels, raw-pointer {@code ngl*} variants). Same contract as {@link MetalGL}:
 * static methods with exactly LWJGL's names and descriptors; {@code GlCallRetargeter} routes retargeted calls to
 * whichever sink class has the method. No logic here beyond adapting LWJGL's buffer / array / pointer overloads.
 */
@SuppressWarnings("unused")
public final class MetalGL4 {
    private MetalGL4() {
    }

    private static GlContext gl() {
        return GlContext.current();
    }

    // ---------------------------------------------------------------------------------------------- queries / state

    public static long glGetInteger64(final int pname) {
        return gl().getInteger64(pname);
    }

    public static void glGetInteger64v(final int pname, final LongBuffer params) {
        params.put(params.position(), gl().getInteger64(pname));
    }

    public static void glGetInteger64v(final int pname, final long[] params) {
        params[0] = gl().getInteger64(pname);
    }

    public static int glGetIntegeri(final int target, final int index) {
        return gl().getIntegeri(target, index);
    }

    public static void glGetIntegeri_v(final int target, final int index, final IntBuffer data) {
        data.put(data.position(), gl().getIntegeri(target, index));
    }

    public static void glGetIntegeri_v(final int target, final int index, final int[] data) {
        data[0] = gl().getIntegeri(target, index);
    }

    public static void glClipControl(final int origin, final int depth) {
        gl().clipControl(origin, depth);
    }

    public static void glProvokingVertex(final int mode) {
        // Metal's provoking vertex for flat varyings is the first vertex (GL_FIRST_VERTEX_CONVENTION).
        if (mode == GL_LAST_VERTEX_CONVENTION) {
            MetalGLLog.unsupported("glProvokingVertex(GL_LAST_VERTEX_CONVENTION): Metal always uses the first vertex");
        }
    }

    public static void glStencilFunc(final int func, final int ref, final int mask) {
        gl().stencilFunc(func, ref, mask);
    }

    public static void glStencilFuncSeparate(final int face, final int func, final int ref, final int mask) {
        if (face != GL_FRONT_AND_BACK) MetalGLLog.unsupported("glStencilFuncSeparate per face");
        gl().stencilFunc(func, ref, mask);
    }

    public static void glStencilOp(final int sfail, final int dpfail, final int dppass) {
        gl().stencilOp(sfail, dpfail, dppass);
    }

    public static void glStencilOpSeparate(final int face, final int sfail, final int dpfail, final int dppass) {
        if (face != GL_FRONT_AND_BACK) MetalGLLog.unsupported("glStencilOpSeparate per face");
        gl().stencilOp(sfail, dpfail, dppass);
    }

    public static void glStencilMask(final int mask) {
        gl().stencilMask(mask);
    }

    public static void glStencilMaskSeparate(final int face, final int mask) {
        gl().stencilMask(mask);
    }

    public static void glBlendFuncSeparateiARB(final int buf, final int srcRgb, final int dstRgb, final int srcAlpha, final int dstAlpha) {
        gl().blendFuncSeparate(buf, srcRgb, dstRgb, srcAlpha, dstAlpha);
    }

    // ---------------------------------------------------------------------------------------------- debug (no-ops)

    public static void glObjectLabel(final int identifier, final int name, final CharSequence label) {
    }

    public static void glObjectLabel(final int identifier, final int name, final ByteBuffer label) {
    }

    public static void nglObjectLabel(final int identifier, final int name, final int length, final long label) {
    }

    public static void glPushDebugGroup(final int source, final int id, final CharSequence message) {
    }

    public static void glPushDebugGroup(final int source, final int id, final ByteBuffer message) {
    }

    public static void glPopDebugGroup() {
    }

    public static void glDebugMessageCallback(final org.lwjgl.opengl.@Nullable GLDebugMessageCallbackI callback, final long userParam) {
    }

    public static void glDebugMessageControl(final int source, final int type, final int severity, final @Nullable IntBuffer ids, final boolean enabled) {
    }

    public static void glDebugMessageControl(final int source, final int type, final int severity, final int id, final boolean enabled) {
    }

    // ---------------------------------------------------------------------------------------------- shaders (raw pointers)

    /** nglShaderSource: {@code strings} = char**, {@code length} = int* (0: NUL-terminated strings). */
    public static void nglShaderSource(final int shader, final int count, final long strings, final long length) {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < count; i++) {
            long string = MemoryUtil.memGetAddress(strings + (long) i * Long.BYTES);
            int len = length == 0 ? -1 : MemoryUtil.memGetInt(length + (long) i * Integer.BYTES);
            source.append(len < 0 ? MemoryUtil.memUTF8(string) : MemoryUtil.memUTF8(string, len));
        }
        gl().shaderSource(shader, source.toString());
    }

    public static void glShaderSource(final int shader, final PointerBuffer strings, final @Nullable IntBuffer length) {
        nglShaderSource(shader, strings.remaining(), MemoryUtil.memAddress(strings), length == null ? 0 : MemoryUtil.memAddress(length));
    }

    public static void nglUniformMatrix4fv(final int location, final int count, final boolean transpose, final long value) {
        float[] values = new float[count * 16];
        MemoryUtil.memFloatBuffer(value, values.length).get(values);
        gl().uniformMatrix(location, 4, 4, count, transpose, values);
    }

    public static void nglUniformMatrix3fv(final int location, final int count, final boolean transpose, final long value) {
        float[] values = new float[count * 9];
        MemoryUtil.memFloatBuffer(value, values.length).get(values);
        gl().uniformMatrix(location, 3, 3, count, transpose, values);
    }

    public static void nglGetSynciv(final long sync, final int pname, final int bufSize, final long length, final long values) {
        if (bufSize < 1) {
            return;
        }
        int value = MetalGL.glGetSynci(sync, pname, null);
        MemoryUtil.memPutInt(values, value);
        if (length != 0) MemoryUtil.memPutInt(length, 1);
    }

    // ---------------------------------------------------------------------------------------------- indexed buffers, images, samplers

    public static void glBindBufferBase(final int target, final int index, final int buffer) {
        gl().bindings.bindBufferRange(target, index, buffer, 0, 0, false);
    }

    public static void glBindBufferRange(final int target, final int index, final int buffer, final long offset, final long size) {
        gl().bindings.bindBufferRange(target, index, buffer, offset, size, true);
    }

    public static void glBindImageTexture(final int unit, final int texture, final int level, final boolean layered, final int layer,
                                          final int access, final int format) {
        gl().bindings.bindImageTexture(unit, texture, level, layered, layer, access, format);
    }

    public static int glGenSamplers() {
        return gl().bindings.genSampler();
    }

    public static void glGenSamplers(final IntBuffer samplers) {
        for (int i = samplers.position(); i < samplers.limit(); i++) samplers.put(i, gl().bindings.genSampler());
    }

    public static void glGenSamplers(final int[] samplers) {
        for (int i = 0; i < samplers.length; i++) samplers[i] = gl().bindings.genSampler();
    }

    public static int glCreateSamplers() {
        return gl().bindings.genSampler();
    }

    public static void glCreateSamplers(final IntBuffer samplers) {
        glGenSamplers(samplers);
    }

    public static void glCreateSamplers(final int[] samplers) {
        glGenSamplers(samplers);
    }

    public static void glDeleteSamplers(final int sampler) {
        gl().bindings.deleteSampler(sampler);
    }

    public static void glDeleteSamplers(final IntBuffer samplers) {
        for (int i = samplers.position(); i < samplers.limit(); i++) gl().bindings.deleteSampler(samplers.get(i));
    }

    public static void glDeleteSamplers(final int[] samplers) {
        for (int sampler : samplers) gl().bindings.deleteSampler(sampler);
    }

    public static boolean glIsSampler(final int sampler) {
        return gl().bindings.isSampler(sampler);
    }

    public static void glBindSampler(final int unit, final int sampler) {
        gl().bindings.bindSampler(unit, sampler);
    }

    public static void glSamplerParameteri(final int sampler, final int pname, final int param) {
        gl().bindings.samplerParameter(sampler, pname, param);
    }

    public static void glSamplerParameterf(final int sampler, final int pname, final float param) {
        gl().bindings.samplerParameter(sampler, pname, param);
    }

    // ---------------------------------------------------------------------------------------------- compute

    public static void glDispatchCompute(final int x, final int y, final int z) {
        gl().compute.dispatch(x, y, z);
    }

    public static void glDispatchComputeIndirect(final long indirect) {
        gl().compute.dispatchIndirect(indirect);
    }

    public static void glMemoryBarrier(final int barriers) {
        gl().compute.memoryBarrier(barriers);
    }

    public static void glMemoryBarrierByRegion(final int barriers) {
        gl().compute.memoryBarrier(barriers);
    }

    public static void glTextureBarrier() {
        gl().device.textureBarrier();
    }

    // ---------------------------------------------------------------------------------------------- draws

    public static void glDrawElementsIndirect(final int mode, final int type, final long indirect) {
        gl().compute.drawElementsIndirect(mode, type, indirect, 1, 0, -1);
    }

    public static void glMultiDrawElementsIndirect(final int mode, final int type, final long indirect, final int drawcount, final int stride) {
        gl().compute.drawElementsIndirect(mode, type, indirect, drawcount, stride, -1);
    }

    public static void glMultiDrawElementsIndirectCount(final int mode, final int type, final long indirect, final long drawcount,
                                                        final int maxdrawcount, final int stride) {
        gl().compute.drawElementsIndirect(mode, type, indirect, maxdrawcount, stride, drawcount);
    }

    public static void glMultiDrawElementsIndirectCountARB(final int mode, final int type, final long indirect, final long drawcount,
                                                           final int maxdrawcount, final int stride) {
        glMultiDrawElementsIndirectCount(mode, type, indirect, drawcount, maxdrawcount, stride);
    }

    public static void glDrawElementsInstancedBaseInstance(final int mode, final int count, final int type, final long indices,
                                                           final int primcount, final int baseinstance) {
        gl().drawElementsBase(mode, count, type, indices, primcount, 0, baseinstance);
    }

    public static void glDrawElementsInstancedBaseVertexBaseInstance(final int mode, final int count, final int type, final long indices,
                                                                     final int primcount, final int basevertex, final int baseinstance) {
        gl().drawElementsBase(mode, count, type, indices, primcount, basevertex, baseinstance);
    }

    public static void glDrawElementsInstancedBaseVertex(final int mode, final int count, final int type, final long indices,
                                                         final int primcount, final int basevertex) {
        gl().drawElementsBase(mode, count, type, indices, primcount, basevertex, 0);
    }

    public static void glDrawArraysInstancedBaseInstance(final int mode, final int first, final int count, final int primcount, final int baseinstance) {
        gl().drawArrays(mode, first, count, primcount, baseinstance);
    }

    // ---------------------------------------------------------------------------------------------- buffers

    public static void glCopyBufferSubData(final int readTarget, final int writeTarget, final long readOffset, final long writeOffset, final long size) {
        GlContext gl = gl();
        gl.compute.copyBufferSubData(gl.requireBound(readTarget), gl.requireBound(writeTarget), readOffset, writeOffset, size);
    }

    public static void nglClearBufferSubData(final int target, final int internalformat, final long offset, final long size, final int format,
                                             final int type, final long data) {
        GlContext gl = gl();
        gl.compute.clearBufferSubData(gl.requireBound(target), internalformat, offset, size, format, type, pointer(data, 16));
    }

    public static void glClearBufferSubData(final int target, final int internalformat, final long offset, final long size, final int format,
                                            final int type, final @Nullable ByteBuffer data) {
        GlContext gl = gl();
        gl.compute.clearBufferSubData(gl.requireBound(target), internalformat, offset, size, format, type, data);
    }

    public static void glClearBufferSubData(final int target, final int internalformat, final long offset, final long size, final int format,
                                            final int type, final @Nullable IntBuffer data) {
        glClearBufferSubData(target, internalformat, offset, size, format, type, data == null ? null : bytes(data));
    }

    public static void glClearBufferSubData(final int target, final int internalformat, final long offset, final long size, final int format,
                                            final int type, final int @Nullable [] data) {
        ByteBuffer copy = data == null ? null : copyOf(data);
        try {
            glClearBufferSubData(target, internalformat, offset, size, format, type, copy);
        } finally {
            if (copy != null) MemoryUtil.memFree(copy);
        }
    }

    public static void glClearBufferData(final int target, final int internalformat, final int format, final int type, final @Nullable ByteBuffer data) {
        GlContext gl = gl();
        GlObjects.Buffer buffer = gl.requireBound(target);
        if (buffer != null) gl.compute.clearBufferSubData(buffer, internalformat, 0, buffer.size, format, type, data);
    }

    public static void nglClearBufferData(final int target, final int internalformat, final int format, final int type, final long data) {
        glClearBufferData(target, internalformat, format, type, pointer(data, 16));
    }

    public static void glGetBufferSubData(final int target, final long offset, final ByteBuffer data) {
        GlContext gl = gl();
        gl.compute.getBufferSubData(gl.requireBound(target), offset, data);
    }

    public static void glGetBufferSubData(final int target, final long offset, final IntBuffer data) {
        glGetBufferSubData(target, offset, bytes(data));
    }

    public static void glGetBufferSubData(final int target, final long offset, final int[] data) {
        ByteBuffer out = MemoryUtil.memAlloc(data.length * 4).order(ByteOrder.nativeOrder());
        try {
            glGetBufferSubData(target, offset, out);
            out.asIntBuffer().get(data);
        } finally {
            MemoryUtil.memFree(out);
        }
    }

    public static void nglGetBufferSubData(final int target, final long offset, final long size, final long data) {
        glGetBufferSubData(target, offset, MemoryUtil.memByteBuffer(data, (int) size));
    }

    // ---------------------------------------------------------------------------------------------- DSA: buffers

    public static void glNamedBufferStorage(final int buffer, final long size, final int flags) {
        gl().dsa.namedBufferStorage(buffer, size, null, flags);
    }

    public static void glNamedBufferStorage(final int buffer, final ByteBuffer data, final int flags) {
        gl().dsa.namedBufferStorage(buffer, data.remaining(), data, flags);
    }

    public static void glNamedBufferStorage(final int buffer, final IntBuffer data, final int flags) {
        glNamedBufferStorage(buffer, bytes(data), flags);
    }

    public static void glNamedBufferStorage(final int buffer, final FloatBuffer data, final int flags) {
        glNamedBufferStorage(buffer, bytes(data), flags);
    }

    public static void nglNamedBufferStorage(final int buffer, final long size, final long data, final int flags) {
        gl().dsa.namedBufferStorage(buffer, size, data == 0 ? null : MemoryUtil.memByteBuffer(data, (int) size), flags);
    }

    public static void glNamedBufferData(final int buffer, final long size, final int usage) {
        gl().dsa.namedBufferData(buffer, size, null, usage);
    }

    public static void glNamedBufferData(final int buffer, final ByteBuffer data, final int usage) {
        gl().dsa.namedBufferData(buffer, data.remaining(), data, usage);
    }

    public static void glNamedBufferData(final int buffer, final IntBuffer data, final int usage) {
        glNamedBufferData(buffer, bytes(data), usage);
    }

    public static void nglNamedBufferData(final int buffer, final long size, final long data, final int usage) {
        gl().dsa.namedBufferData(buffer, size, data == 0 ? null : MemoryUtil.memByteBuffer(data, (int) size), usage);
    }

    public static void glNamedBufferSubData(final int buffer, final long offset, final ByteBuffer data) {
        gl().dsa.namedBufferSubData(buffer, offset, data);
    }

    public static void glNamedBufferSubData(final int buffer, final long offset, final IntBuffer data) {
        glNamedBufferSubData(buffer, offset, bytes(data));
    }

    public static void glNamedBufferSubData(final int buffer, final long offset, final FloatBuffer data) {
        glNamedBufferSubData(buffer, offset, bytes(data));
    }

    public static void glNamedBufferSubData(final int buffer, final long offset, final LongBuffer data) {
        glNamedBufferSubData(buffer, offset, MemoryUtil.memByteBuffer(MemoryUtil.memAddress(data), data.remaining() * 8));
    }

    public static void glNamedBufferSubData(final int buffer, final long offset, final int[] data) {
        ByteBuffer copy = copyOf(data);
        try {
            glNamedBufferSubData(buffer, offset, copy);
        } finally {
            MemoryUtil.memFree(copy);
        }
    }

    public static void nglNamedBufferSubData(final int buffer, final long offset, final long size, final long data) {
        gl().dsa.namedBufferSubData(buffer, offset, MemoryUtil.memByteBuffer(data, (int) size));
    }

    public static @Nullable ByteBuffer glMapNamedBufferRange(final int buffer, final long offset, final long length, final int access) {
        return gl().dsa.mapNamedBufferRange(buffer, offset, length, access);
    }

    public static @Nullable ByteBuffer glMapNamedBufferRange(final int buffer, final long offset, final long length, final int access,
                                                             final @Nullable ByteBuffer old) {
        return glMapNamedBufferRange(buffer, offset, length, access);
    }

    public static long nglMapNamedBufferRange(final int buffer, final long offset, final long length, final int access) {
        ByteBuffer mapped = glMapNamedBufferRange(buffer, offset, length, access);
        return mapped == null ? 0 : MemoryUtil.memAddress(mapped);
    }

    public static @Nullable ByteBuffer glMapNamedBuffer(final int buffer, final int access) {
        return gl().dsa.mapNamedBuffer(buffer, access);
    }

    public static @Nullable ByteBuffer glMapNamedBuffer(final int buffer, final int access, final @Nullable ByteBuffer old) {
        return glMapNamedBuffer(buffer, access);
    }

    public static long nglMapNamedBuffer(final int buffer, final int access) {
        ByteBuffer mapped = glMapNamedBuffer(buffer, access);
        return mapped == null ? 0 : MemoryUtil.memAddress(mapped);
    }

    public static boolean glUnmapNamedBuffer(final int buffer) {
        return gl().dsa.unmapNamedBuffer(buffer);
    }

    public static void glFlushMappedNamedBufferRange(final int buffer, final long offset, final long length) {
        // Unified memory, coherent by construction (see MetalGL.glFlushMappedBufferRange).
    }

    public static void glCopyNamedBufferSubData(final int readBuffer, final int writeBuffer, final long readOffset, final long writeOffset, final long size) {
        gl().dsa.copyNamedBufferSubData(readBuffer, writeBuffer, readOffset, writeOffset, size);
    }

    public static void nglClearNamedBufferSubData(final int buffer, final int internalformat, final long offset, final long size, final int format,
                                                  final int type, final long data) {
        gl().dsa.clearNamedBufferSubData(buffer, internalformat, offset, size, format, type, pointer(data, 16));
    }

    public static void glClearNamedBufferSubData(final int buffer, final int internalformat, final long offset, final long size, final int format,
                                                 final int type, final @Nullable ByteBuffer data) {
        gl().dsa.clearNamedBufferSubData(buffer, internalformat, offset, size, format, type, data);
    }

    public static void glClearNamedBufferSubData(final int buffer, final int internalformat, final long offset, final long size, final int format,
                                                 final int type, final @Nullable IntBuffer data) {
        glClearNamedBufferSubData(buffer, internalformat, offset, size, format, type, data == null ? null : bytes(data));
    }

    public static void glClearNamedBufferSubData(final int buffer, final int internalformat, final long offset, final long size, final int format,
                                                 final int type, final int @Nullable [] data) {
        ByteBuffer copy = data == null ? null : copyOf(data);
        try {
            glClearNamedBufferSubData(buffer, internalformat, offset, size, format, type, copy);
        } finally {
            if (copy != null) MemoryUtil.memFree(copy);
        }
    }

    public static void nglClearNamedBufferData(final int buffer, final int internalformat, final int format, final int type, final long data) {
        gl().dsa.clearNamedBufferSubData(buffer, internalformat, 0, -1, format, type, pointer(data, 16));
    }

    public static void glClearNamedBufferData(final int buffer, final int internalformat, final int format, final int type, final @Nullable ByteBuffer data) {
        gl().dsa.clearNamedBufferSubData(buffer, internalformat, 0, -1, format, type, data);
    }

    public static void glClearNamedBufferData(final int buffer, final int internalformat, final int format, final int type, final @Nullable IntBuffer data) {
        glClearNamedBufferData(buffer, internalformat, format, type, data == null ? null : bytes(data));
    }

    public static void glClearNamedBufferData(final int buffer, final int internalformat, final int format, final int type, final int @Nullable [] data) {
        ByteBuffer copy = data == null ? null : copyOf(data);
        try {
            glClearNamedBufferData(buffer, internalformat, format, type, copy);
        } finally {
            if (copy != null) MemoryUtil.memFree(copy);
        }
    }

    public static void glGetNamedBufferSubData(final int buffer, final long offset, final ByteBuffer data) {
        gl().dsa.getNamedBufferSubData(buffer, offset, data);
    }

    public static void glGetNamedBufferSubData(final int buffer, final long offset, final IntBuffer data) {
        glGetNamedBufferSubData(buffer, offset, bytes(data));
    }

    public static void glGetNamedBufferSubData(final int buffer, final long offset, final int[] data) {
        ByteBuffer out = MemoryUtil.memAlloc(data.length * 4).order(ByteOrder.nativeOrder());
        try {
            glGetNamedBufferSubData(buffer, offset, out);
            out.asIntBuffer().get(data);
        } finally {
            MemoryUtil.memFree(out);
        }
    }

    public static void nglGetNamedBufferSubData(final int buffer, final long offset, final long size, final long data) {
        glGetNamedBufferSubData(buffer, offset, MemoryUtil.memByteBuffer(data, (int) size));
    }

    public static int glGetNamedBufferParameteri(final int buffer, final int pname) {
        return (int) gl().dsa.getNamedBufferParameter(buffer, pname);
    }

    public static long glGetNamedBufferParameteri64(final int buffer, final int pname) {
        return gl().dsa.getNamedBufferParameter(buffer, pname);
    }

    // ---------------------------------------------------------------------------------------------- DSA: textures

    public static int glCreateTextures(final int target) {
        return gl().dsa.createTexture(target);
    }

    public static void glCreateTextures(final int target, final IntBuffer textures) {
        for (int i = textures.position(); i < textures.limit(); i++) textures.put(i, gl().dsa.createTexture(target));
    }

    public static void glCreateTextures(final int target, final int[] textures) {
        for (int i = 0; i < textures.length; i++) textures[i] = gl().dsa.createTexture(target);
    }

    public static void glTextureStorage2D(final int texture, final int levels, final int internalformat, final int width, final int height) {
        gl().dsa.textureStorage2D(texture, levels, internalformat, width, height);
    }

    public static void glTextureSubImage2D(final int texture, final int level, final int xoffset, final int yoffset, final int width, final int height,
                                           final int format, final int type, final ByteBuffer pixels) {
        gl().dsa.textureSubImage2D(texture, level, xoffset, yoffset, width, height, format, type, pixels);
    }

    public static void glTextureSubImage2D(final int texture, final int level, final int xoffset, final int yoffset, final int width, final int height,
                                           final int format, final int type, final IntBuffer pixels) {
        glTextureSubImage2D(texture, level, xoffset, yoffset, width, height, format, type, bytes(pixels));
    }

    public static void glTextureSubImage2D(final int texture, final int level, final int xoffset, final int yoffset, final int width, final int height,
                                           final int format, final int type, final int[] pixels) {
        ByteBuffer copy = copyOf(pixels);
        try {
            glTextureSubImage2D(texture, level, xoffset, yoffset, width, height, format, type, copy);
        } finally {
            MemoryUtil.memFree(copy);
        }
    }

    /** {@code pixels}: an offset into GL_PIXEL_UNPACK_BUFFER (LWJGL's long overload). */
    public static void glTextureSubImage2D(final int texture, final int level, final int xoffset, final int yoffset, final int width, final int height,
                                           final int format, final int type, final long pixels) {
        nglTextureSubImage2D(texture, level, xoffset, yoffset, width, height, format, type, pixels);
    }

    public static void nglTextureSubImage2D(final int texture, final int level, final int xoffset, final int yoffset, final int width, final int height,
                                            final int format, final int type, final long pixels) {
        GlContext gl = gl();
        ByteBuffer source = gl.hasUnpackBuffer() ? gl.unpackSource(pixels)
                : MemoryUtil.memByteBuffer(pixels, (int) GlPixels.clientBytes(format, type, width, height, gl.unpack));
        if (source != null) {
            gl.dsa.textureSubImage2D(texture, level, xoffset, yoffset, width, height, format, type, source);
        }
    }

    public static void glTextureParameteri(final int texture, final int pname, final int param) {
        gl().dsa.textureParameteri(texture, pname, param);
    }

    public static void glTextureParameterf(final int texture, final int pname, final float param) {
        gl().dsa.textureParameterf(texture, pname, param);
    }

    public static int glGetTextureLevelParameteri(final int texture, final int level, final int pname) {
        return gl().dsa.getTextureLevelParameteri(texture, level, pname);
    }

    public static int glGetTextureParameteri(final int texture, final int pname) {
        return gl().dsa.getTextureParameteri(texture, pname);
    }

    public static void glGenerateTextureMipmap(final int texture) {
        gl().dsa.generateTextureMipmap(texture);
    }

    public static void glBindTextureUnit(final int unit, final int texture) {
        gl().dsa.bindTextureUnit(unit, texture);
    }

    public static void glGetTextureImage(final int texture, final int level, final int format, final int type, final ByteBuffer pixels) {
        gl().dsa.getTextureImage(texture, level, format, type, pixels);
    }

    public static void glGetTextureImage(final int texture, final int level, final int format, final int type, final IntBuffer pixels) {
        glGetTextureImage(texture, level, format, type, bytes(pixels));
    }

    public static void glGetTextureImage(final int texture, final int level, final int format, final int type, final int[] pixels) {
        ByteBuffer out = MemoryUtil.memAlloc(pixels.length * 4).order(ByteOrder.nativeOrder());
        try {
            glGetTextureImage(texture, level, format, type, out);
            out.asIntBuffer().get(pixels);
        } finally {
            MemoryUtil.memFree(out);
        }
    }

    public static void nglGetTextureImage(final int texture, final int level, final int format, final int type, final int bufSize, final long pixels) {
        glGetTextureImage(texture, level, format, type, MemoryUtil.memByteBuffer(pixels, bufSize));
    }

    public static void nglClearTexImage(final int texture, final int level, final int format, final int type, final long data) {
        gl().dsa.clearTexImage(texture, level, format, type, pointer(data, 16));
    }

    public static void glClearTexImage(final int texture, final int level, final int format, final int type, final @Nullable ByteBuffer data) {
        gl().dsa.clearTexImage(texture, level, format, type, data);
    }

    public static void glClearTexImage(final int texture, final int level, final int format, final int type, final @Nullable FloatBuffer data) {
        glClearTexImage(texture, level, format, type, data == null ? null : bytes(data));
    }

    public static void glClearTexImage(final int texture, final int level, final int format, final int type, final @Nullable IntBuffer data) {
        glClearTexImage(texture, level, format, type, data == null ? null : bytes(data));
    }

    public static void glTextureView(final int texture, final int target, final int origtexture, final int internalformat, final int minlevel,
                                     final int numlevels, final int minlayer, final int numlayers) {
        gl().dsa.textureView(texture, target, origtexture, internalformat, minlevel, numlevels, minlayer, numlayers);
    }

    // ---------------------------------------------------------------------------------------------- DSA: framebuffers, renderbuffers

    public static int glCreateFramebuffers() {
        return gl().dsa.createFramebuffer();
    }

    public static void glCreateFramebuffers(final IntBuffer framebuffers) {
        for (int i = framebuffers.position(); i < framebuffers.limit(); i++) framebuffers.put(i, gl().dsa.createFramebuffer());
    }

    public static void glNamedFramebufferTexture(final int framebuffer, final int attachment, final int texture, final int level) {
        gl().dsa.namedFramebufferTexture(framebuffer, attachment, texture, level);
    }

    public static void glNamedFramebufferRenderbuffer(final int framebuffer, final int attachment, final int renderbuffertarget, final int renderbuffer) {
        gl().dsa.namedFramebufferRenderbuffer(framebuffer, attachment, renderbuffer);
    }

    public static void glNamedFramebufferDrawBuffers(final int framebuffer, final int[] bufs) {
        gl().dsa.namedFramebufferDrawBuffers(framebuffer, bufs.clone());
    }

    public static void glNamedFramebufferDrawBuffers(final int framebuffer, final IntBuffer bufs) {
        int[] array = new int[bufs.remaining()];
        bufs.duplicate().get(array);
        gl().dsa.namedFramebufferDrawBuffers(framebuffer, array);
    }

    public static void glNamedFramebufferDrawBuffers(final int framebuffer, final int buf) {
        gl().dsa.namedFramebufferDrawBuffers(framebuffer, new int[]{buf});
    }

    public static void nglNamedFramebufferDrawBuffers(final int framebuffer, final int n, final long bufs) {
        int[] array = new int[n];
        MemoryUtil.memIntBuffer(bufs, n).get(array);
        gl().dsa.namedFramebufferDrawBuffers(framebuffer, array);
    }

    public static void glNamedFramebufferDrawBuffer(final int framebuffer, final int buf) {
        gl().dsa.namedFramebufferDrawBuffers(framebuffer, new int[]{buf});
    }

    public static void glNamedFramebufferReadBuffer(final int framebuffer, final int src) {
        gl().dsa.namedFramebufferReadBuffer(framebuffer, src);
    }

    public static int glCheckNamedFramebufferStatus(final int framebuffer, final int target) {
        return gl().dsa.checkNamedFramebufferStatus(framebuffer, target);
    }

    public static void glClearNamedFramebufferfv(final int framebuffer, final int buffer, final int drawbuffer, final float[] value) {
        clearNamed(framebuffer, buffer, drawbuffer, value);
    }

    public static void glClearNamedFramebufferfv(final int framebuffer, final int buffer, final int drawbuffer, final FloatBuffer value) {
        float[] v = new float[Math.min(4, value.remaining())];
        value.duplicate().get(v);
        clearNamed(framebuffer, buffer, drawbuffer, v);
    }

    public static void nglClearNamedFramebufferfv(final int framebuffer, final int buffer, final int drawbuffer, final long value) {
        float[] v = new float[buffer == GL_COLOR ? 4 : 1];
        MemoryUtil.memFloatBuffer(value, v.length).get(v);
        clearNamed(framebuffer, buffer, drawbuffer, v);
    }

    public static void glClearNamedFramebufferiv(final int framebuffer, final int buffer, final int drawbuffer, final int[] value) {
        clearNamedInt(framebuffer, buffer, drawbuffer, value);
    }

    public static void glClearNamedFramebufferiv(final int framebuffer, final int buffer, final int drawbuffer, final IntBuffer value) {
        int[] v = new int[Math.min(4, value.remaining())];
        value.duplicate().get(v);
        clearNamedInt(framebuffer, buffer, drawbuffer, v);
    }

    public static void nglClearNamedFramebufferiv(final int framebuffer, final int buffer, final int drawbuffer, final long value) {
        int[] v = new int[buffer == GL_COLOR ? 4 : 1];
        MemoryUtil.memIntBuffer(value, v.length).get(v);
        clearNamedInt(framebuffer, buffer, drawbuffer, v);
    }

    public static void glClearNamedFramebufferuiv(final int framebuffer, final int buffer, final int drawbuffer, final int[] value) {
        clearNamedInt(framebuffer, buffer, drawbuffer, value);
    }

    public static void nglClearNamedFramebufferuiv(final int framebuffer, final int buffer, final int drawbuffer, final long value) {
        nglClearNamedFramebufferiv(framebuffer, buffer, drawbuffer, value);
    }

    public static void glClearNamedFramebufferfi(final int framebuffer, final int buffer, final int drawbuffer, final float depth, final int stencil) {
        gl().dsa.clearNamedFramebuffer(framebuffer, buffer, drawbuffer, null, depth, stencil);
    }

    public static void glClearBufferfv(final int buffer, final int drawbuffer, final float[] value) {
        clearNamed(drawFramebufferName(), buffer, drawbuffer, value);
    }

    public static void glClearBufferfv(final int buffer, final int drawbuffer, final FloatBuffer value) {
        glClearNamedFramebufferfv(drawFramebufferName(), buffer, drawbuffer, value);
    }

    public static void nglClearBufferfv(final int buffer, final int drawbuffer, final long value) {
        nglClearNamedFramebufferfv(drawFramebufferName(), buffer, drawbuffer, value);
    }

    public static void glClearBufferiv(final int buffer, final int drawbuffer, final int[] value) {
        clearNamedInt(drawFramebufferName(), buffer, drawbuffer, value);
    }

    public static void glClearBufferiv(final int buffer, final int drawbuffer, final IntBuffer value) {
        glClearNamedFramebufferiv(drawFramebufferName(), buffer, drawbuffer, value);
    }

    public static void glClearBufferuiv(final int buffer, final int drawbuffer, final int[] value) {
        clearNamedInt(drawFramebufferName(), buffer, drawbuffer, value);
    }

    public static void glClearBufferfi(final int buffer, final int drawbuffer, final float depth, final int stencil) {
        glClearNamedFramebufferfi(drawFramebufferName(), buffer, drawbuffer, depth, stencil);
    }

    private static int drawFramebufferName() {
        GlObjects.Framebuffer fb = gl().drawFramebuffer;
        return fb == null ? 0 : fb.name;
    }

    private static void clearNamed(final int framebuffer, final int buffer, final int drawbuffer, final float[] value) {
        if (buffer == GL_COLOR) {
            float[] rgba = {0, 0, 0, 0};
            System.arraycopy(value, 0, rgba, 0, Math.min(4, value.length));
            gl().dsa.clearNamedFramebuffer(framebuffer, buffer, drawbuffer, rgba, 0f, 0);
        } else {
            gl().dsa.clearNamedFramebuffer(framebuffer, buffer, drawbuffer, null, value.length > 0 ? value[0] : 0f, 0);
        }
    }

    private static void clearNamedInt(final int framebuffer, final int buffer, final int drawbuffer, final int[] value) {
        if (buffer == GL_STENCIL) {
            gl().dsa.clearNamedFramebuffer(framebuffer, buffer, drawbuffer, null, 0f, value.length > 0 ? value[0] : 0);
        } else {
            // Integer colour clears go through the float path: exact for the 8-bit / float formats metallium-gl allocates.
            float[] rgba = new float[4];
            for (int i = 0; i < Math.min(4, value.length); i++) rgba[i] = value[i];
            gl().dsa.clearNamedFramebuffer(framebuffer, buffer, drawbuffer, rgba, 0f, 0);
        }
    }

    public static void glBlitNamedFramebuffer(final int readFramebuffer, final int drawFramebuffer, final int srcX0, final int srcY0, final int srcX1,
                                              final int srcY1, final int dstX0, final int dstY0, final int dstX1, final int dstY1, final int mask,
                                              final int filter) {
        gl().dsa.blitNamedFramebuffer(readFramebuffer, drawFramebuffer, srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
    }

    public static void glBlitFramebuffer(final int srcX0, final int srcY0, final int srcX1, final int srcY1, final int dstX0, final int dstY0,
                                         final int dstX1, final int dstY1, final int mask, final int filter) {
        GlContext gl = gl();
        int read = gl.readFramebuffer == null ? 0 : gl.readFramebuffer.name;
        gl.dsa.blitNamedFramebuffer(read, drawFramebufferName(), srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
    }

    public static int glCreateRenderbuffers() {
        return gl().dsa.createRenderbuffer();
    }

    public static void glNamedRenderbufferStorage(final int renderbuffer, final int internalformat, final int width, final int height) {
        gl().dsa.namedRenderbufferStorage(renderbuffer, internalformat, width, height);
    }

    // ---------------------------------------------------------------------------------------------- DSA: vertex arrays

    public static int glCreateVertexArrays() {
        return gl().dsa.createVertexArray();
    }

    public static void glCreateVertexArrays(final IntBuffer arrays) {
        for (int i = arrays.position(); i < arrays.limit(); i++) arrays.put(i, gl().dsa.createVertexArray());
    }

    public static void glEnableVertexArrayAttrib(final int vaobj, final int index) {
        gl().dsa.enableVertexArrayAttrib(vaobj, index, true);
    }

    public static void glDisableVertexArrayAttrib(final int vaobj, final int index) {
        gl().dsa.enableVertexArrayAttrib(vaobj, index, false);
    }

    public static void glVertexArrayElementBuffer(final int vaobj, final int buffer) {
        gl().dsa.vertexArrayElementBuffer(vaobj, buffer);
    }

    public static void glVertexArrayVertexBuffer(final int vaobj, final int bindingindex, final int buffer, final long offset, final int stride) {
        gl().dsa.vertexArrayVertexBuffer(vaobj, bindingindex, buffer, offset, stride);
    }

    public static void glVertexArrayAttribFormat(final int vaobj, final int attribindex, final int size, final int type, final boolean normalized,
                                                 final int relativeoffset) {
        gl().dsa.vertexArrayAttribFormat(vaobj, attribindex, size, type, normalized, false, relativeoffset);
    }

    public static void glVertexArrayAttribIFormat(final int vaobj, final int attribindex, final int size, final int type, final int relativeoffset) {
        gl().dsa.vertexArrayAttribFormat(vaobj, attribindex, size, type, false, true, relativeoffset);
    }

    public static void glVertexArrayAttribBinding(final int vaobj, final int attribindex, final int bindingindex) {
        gl().dsa.vertexArrayAttribBinding(vaobj, attribindex, bindingindex);
    }

    public static void glVertexArrayBindingDivisor(final int vaobj, final int bindingindex, final int divisor) {
        gl().dsa.vertexArrayBindingDivisor(vaobj, bindingindex, divisor);
    }

    // ---------------------------------------------------------------------------------------------- query objects

    public static int glGenQueries() {
        return gl().queries.gen();
    }

    public static void glGenQueries(final IntBuffer ids) {
        for (int i = ids.position(); i < ids.limit(); i++) ids.put(i, gl().queries.gen());
    }

    public static void glGenQueries(final int[] ids) {
        for (int i = 0; i < ids.length; i++) ids[i] = gl().queries.gen();
    }

    public static int glCreateQueries(final int target) {
        return gl().queries.create(target);
    }

    public static void glCreateQueries(final int target, final IntBuffer ids) {
        for (int i = ids.position(); i < ids.limit(); i++) ids.put(i, gl().queries.create(target));
    }

    public static void glDeleteQueries(final int id) {
        gl().queries.delete(id);
    }

    public static void glDeleteQueries(final IntBuffer ids) {
        for (int i = ids.position(); i < ids.limit(); i++) gl().queries.delete(ids.get(i));
    }

    public static void glDeleteQueries(final int[] ids) {
        for (int id : ids) gl().queries.delete(id);
    }

    public static boolean glIsQuery(final int id) {
        return gl().queries.isQuery(id);
    }

    public static void glBeginQuery(final int target, final int id) {
        gl().queries.begin(target, id);
    }

    public static void glEndQuery(final int target) {
        gl().queries.end(target);
    }

    public static void glQueryCounter(final int id, final int target) {
        gl().queries.queryCounter(id, target);
    }

    public static int glGetQueryObjecti(final int id, final int pname) {
        return (int) gl().queries.result(id, pname);
    }

    public static int glGetQueryObjectui(final int id, final int pname) {
        return (int) gl().queries.result(id, pname);
    }

    public static long glGetQueryObjecti64(final int id, final int pname) {
        return gl().queries.result(id, pname);
    }

    public static long glGetQueryObjectui64(final int id, final int pname) {
        return gl().queries.result(id, pname);
    }

    public static void glGetQueryObjectiv(final int id, final int pname, final IntBuffer params) {
        params.put(params.position(), (int) gl().queries.result(id, pname));
    }

    public static void glGetQueryObjecti64v(final int id, final int pname, final LongBuffer params) {
        params.put(params.position(), gl().queries.result(id, pname));
    }

    public static void glGetQueryObjectui64v(final int id, final int pname, final LongBuffer params) {
        params.put(params.position(), gl().queries.result(id, pname));
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private static @Nullable ByteBuffer pointer(final long address, final int bytes) {
        return address == 0 ? null : MemoryUtil.memByteBuffer(address, bytes).order(ByteOrder.nativeOrder());
    }

    private static ByteBuffer bytes(final IntBuffer buffer) {
        return MemoryUtil.memByteBuffer(MemoryUtil.memAddress(buffer), buffer.remaining() * 4).order(ByteOrder.nativeOrder());
    }

    private static ByteBuffer bytes(final FloatBuffer buffer) {
        return MemoryUtil.memByteBuffer(MemoryUtil.memAddress(buffer), buffer.remaining() * 4).order(ByteOrder.nativeOrder());
    }

    private static ByteBuffer copyOf(final int[] data) {
        ByteBuffer copy = MemoryUtil.memAlloc(data.length * 4).order(ByteOrder.nativeOrder());
        copy.asIntBuffer().put(data);
        return copy;
    }
}
