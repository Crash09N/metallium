/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl;

import dev.metallium.gl.GlObjects.Buffer;
import dev.metallium.gl.GlObjects.Framebuffer;
import dev.metallium.gl.GlObjects.Texture;
import dev.metallium.gl.GlObjects.VertexArray;
import dev.metallium.gl.GlObjects.VertexAttribState;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Supplier;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Direct state access (GL 4.5) on top of {@link GlContext}: each {@code glNamed*} / {@code glTexture*} /
 * {@code glVertexArray*} call temporarily binds the named object to a scratch binding point, runs the bind-to-edit
 * implementation (so validation and error semantics are identical) and restores the previous binding.
 */
final class GlDsa {
    private final GlContext gl;

    GlDsa(final GlContext gl) {
        this.gl = gl;
    }

    // ---------------------------------------------------------------------------------------------- buffers

    private @Nullable Buffer buffer(final int name) {
        Buffer buffer = name == 0 ? null : gl.buffers.get(name);
        if (buffer == null) {
            gl.error(GL_INVALID_OPERATION);
        }
        return buffer;
    }

    /** Runs {@code action} with buffer {@code name} bound to GL_COPY_WRITE_BUFFER. */
    private <T> T onBuffer(final int name, final T failed, final Supplier<T> action) {
        Buffer buffer = buffer(name);
        if (buffer == null) {
            return failed;
        }
        Buffer previous = gl.copyWriteBuffer;
        gl.copyWriteBuffer = buffer;
        try {
            return action.get();
        } finally {
            gl.copyWriteBuffer = previous;
        }
    }

    void namedBufferStorage(final int name, final long size, final @Nullable ByteBuffer data, final int flags) {
        onBuffer(name, null, () -> {
            gl.bufferStorage(GL_COPY_WRITE_BUFFER, size, data, flags);
            return null;
        });
    }

    void namedBufferData(final int name, final long size, final @Nullable ByteBuffer data, final int usage) {
        onBuffer(name, null, () -> {
            gl.bufferData(GL_COPY_WRITE_BUFFER, size, data, usage);
            return null;
        });
    }

    void namedBufferSubData(final int name, final long offset, final ByteBuffer data) {
        onBuffer(name, null, () -> {
            gl.bufferSubData(GL_COPY_WRITE_BUFFER, offset, data);
            return null;
        });
    }

    @Nullable ByteBuffer mapNamedBufferRange(final int name, final long offset, final long length, final int access) {
        return onBuffer(name, null, () -> gl.mapBufferRange(GL_COPY_WRITE_BUFFER, offset, length, access));
    }

    @Nullable ByteBuffer mapNamedBuffer(final int name, final int access) {
        return onBuffer(name, null, () -> gl.mapBuffer(GL_COPY_WRITE_BUFFER, access));
    }

    boolean unmapNamedBuffer(final int name) {
        return onBuffer(name, false, () -> gl.unmapBuffer(GL_COPY_WRITE_BUFFER));
    }

    void copyNamedBufferSubData(final int read, final int write, final long readOffset, final long writeOffset, final long size) {
        Buffer source = buffer(read);
        Buffer destination = buffer(write);
        if (source != null && destination != null) {
            gl.compute.copyBufferSubData(source, destination, readOffset, writeOffset, size);
        }
    }

    void clearNamedBufferSubData(final int name, final int internalFormat, final long offset, final long size, final int format, final int type,
                                 final @Nullable ByteBuffer data) {
        Buffer buffer = buffer(name);
        if (buffer != null) {
            gl.compute.clearBufferSubData(buffer, internalFormat, offset, size < 0 ? buffer.size - offset : size, format, type, data);
        }
    }

    void getNamedBufferSubData(final int name, final long offset, final ByteBuffer out) {
        Buffer buffer = buffer(name);
        if (buffer != null) {
            gl.compute.getBufferSubData(buffer, offset, out);
        }
    }

    long getNamedBufferParameter(final int name, final int pname) {
        Buffer buffer = buffer(name);
        if (buffer == null) {
            return 0;
        }
        return switch (pname) {
            case GL_BUFFER_SIZE -> buffer.size;
            case GL_BUFFER_USAGE -> buffer.usage;
            case GL_BUFFER_IMMUTABLE_STORAGE -> buffer.immutable ? 1 : 0;
            case GL_BUFFER_STORAGE_FLAGS -> buffer.storageFlags;
            case GL_BUFFER_MAPPED -> buffer.mapped ? 1 : 0;
            case GL_BUFFER_MAP_OFFSET -> buffer.mapOffset;
            case GL_BUFFER_MAP_LENGTH -> buffer.mapLength;
            case GL_BUFFER_ACCESS_FLAGS -> buffer.mapAccess;
            default -> {
                gl.error(GL_INVALID_ENUM);
                yield 0;
            }
        };
    }

    // ---------------------------------------------------------------------------------------------- textures

    int createTexture(final int target) {
        int name = gl.textures.reserve();
        Texture texture = new Texture(name);
        texture.target = target;
        gl.textures.put(name, texture);
        if (target != GL_TEXTURE_2D) {
            MetalGLLog.unsupported("glCreateTextures target 0x" + Integer.toHexString(target));
        }
        return name;
    }

    @Nullable Texture texture(final int name) {
        Texture texture = name == 0 ? null : gl.textures.get(name);
        if (texture == null) {
            gl.error(GL_INVALID_OPERATION);
        }
        return texture;
    }

    /** Runs {@code action} with texture {@code name} bound to GL_TEXTURE_2D of the active unit. */
    private <T> T onTexture(final int name, final T failed, final Supplier<T> action) {
        Texture texture = texture(name);
        if (texture == null) {
            return failed;
        }
        if (texture.target != GL_TEXTURE_2D) {
            MetalGLLog.unsupported("DSA on texture target 0x" + Integer.toHexString(texture.target));
            gl.error(GL_INVALID_OPERATION);
            return failed;
        }
        Texture previous = gl.bound2D[gl.activeTexture];
        gl.bound2D[gl.activeTexture] = texture;
        try {
            return action.get();
        } finally {
            gl.bound2D[gl.activeTexture] = previous;
        }
    }

    void textureStorage2D(final int name, final int levels, final int internalFormat, final int width, final int height) {
        onTexture(name, null, () -> {
            gl.texStorage2D(GL_TEXTURE_2D, levels, internalFormat, width, height);
            return null;
        });
    }

    void textureSubImage2D(final int name, final int level, final int x, final int y, final int width, final int height, final int format,
                           final int type, final ByteBuffer pixels) {
        onTexture(name, null, () -> {
            gl.texSubImage2D(GL_TEXTURE_2D, level, x, y, width, height, format, type, pixels);
            return null;
        });
    }

    void textureParameteri(final int name, final int pname, final int value) {
        onTexture(name, null, () -> {
            gl.texParameteri(GL_TEXTURE_2D, pname, value);
            return null;
        });
    }

    void textureParameterf(final int name, final int pname, final float value) {
        onTexture(name, null, () -> {
            gl.texParameterf(GL_TEXTURE_2D, pname, value);
            return null;
        });
    }

    int getTextureLevelParameteri(final int name, final int level, final int pname) {
        return onTexture(name, 0, () -> gl.getTexLevelParameteri(GL_TEXTURE_2D, level, pname));
    }

    int getTextureParameteri(final int name, final int pname) {
        return onTexture(name, 0, () -> gl.getTexParameteri(GL_TEXTURE_2D, pname));
    }

    void generateTextureMipmap(final int name) {
        onTexture(name, null, () -> {
            gl.generateMipmap(GL_TEXTURE_2D);
            return null;
        });
    }

    /** glBindTextureUnit: binds to the unit's target of the texture (2D only); 0 unbinds. */
    void bindTextureUnit(final int unit, final int name) {
        if (unit < 0 || unit >= GlContext.MAX_TEXTURE_UNITS) {
            gl.error(GL_INVALID_VALUE);
            return;
        }
        if (name == 0) {
            gl.bound2D[unit] = null;
            return;
        }
        Texture texture = texture(name);
        if (texture == null) {
            return;
        }
        if (texture.target != GL_TEXTURE_2D) {
            MetalGLLog.unsupported("glBindTextureUnit with target 0x" + Integer.toHexString(texture.target));
            return;
        }
        gl.bound2D[unit] = texture;
    }

    /** glGetTextureImage: waits for the GPU; supports reading back in the storage format (and RGBA8 as RGBA/BGRA bytes). */
    void getTextureImage(final int name, final int level, final int format, final int type, final ByteBuffer out) {
        Texture texture = texture(name);
        if (texture == null || texture.store == null) {
            if (texture != null) gl.error(GL_INVALID_OPERATION);
            return;
        }
        if (level < 0 || level >= texture.levels) {
            gl.error(GL_INVALID_VALUE);
            return;
        }
        TexFormat storage = texture.format;
        boolean rgba8 = (storage == TexFormat.RGBA8 || storage == TexFormat.SRGB8_ALPHA8)
                && (format == GL_RGBA || format == GL_BGRA) && (type == GL_UNSIGNED_BYTE || type == GL_UNSIGNED_INT_8_8_8_8_REV);
        boolean raw = GlPixels.clientPixelBytes(format, type) == storage.bytesPerPixel;
        if (!rgba8 && !raw) {
            MetalGLLog.unsupported("glGetTextureImage " + storage + " as 0x" + Integer.toHexString(format) + "/0x" + Integer.toHexString(type));
            gl.error(GL_INVALID_OPERATION);
            return;
        }
        int w = Math.max(1, texture.width >> level);
        int h = Math.max(1, texture.height >> level);
        long size = (long) w * h * storage.bytesPerPixel;
        if (out.remaining() < size) {
            gl.error(GL_INVALID_OPERATION);
            return;
        }
        if (!gl.device.readTexture(texture.store, level, out)) {
            MetalGLLog.unsupported("glGetTextureImage in this context");
            return;
        }
        if (rgba8 && format == GL_BGRA) {
            for (int i = out.position(); i < out.position() + size; i += 4) {
                byte r = out.get(i);
                out.put(i, out.get(i + 2)).put(i + 2, r);
            }
        }
    }

    void clearTexImage(final int name, final int level, final int format, final int type, final @Nullable ByteBuffer data) {
        Texture texture = texture(name);
        if (texture == null || texture.store == null) {
            if (texture != null) gl.error(GL_INVALID_OPERATION);
            return;
        }
        int bpp = texture.format.bytesPerPixel;
        ByteBuffer texel = MemoryUtil.memCalloc(bpp).order(ByteOrder.nativeOrder());
        try {
            if (data != null) {
                if (GlPixels.clientPixelBytes(format, type) != bpp) {
                    MetalGLLog.unsupported("glClearTexImage conversion to " + texture.format);
                    gl.error(GL_INVALID_OPERATION);
                    return;
                }
                MemoryUtil.memCopy(MemoryUtil.memAddress(data), MemoryUtil.memAddress(texel), bpp);
            }
            gl.device.clearTexture(texture.store, level, texel);
        } finally {
            MemoryUtil.memFree(texel);
        }
    }

    /** glTextureView: shares the original's storage ({@code minLevel}/{@code levels} of it; same format only). */
    void textureView(final int name, final int target, final int original, final int internalFormat, final int minLevel, final int levels,
                     final int minLayer, final int layers) {
        Texture view = gl.textures.get(name);
        if (view == null && gl.textures.isReserved(name)) {
            // glTextureView takes a name from glGenTextures that was never bound (the object does not exist yet).
            view = new Texture(name);
            view.target = target;
            gl.textures.put(name, view);
        }
        Texture source = texture(original);
        if (source == null) {
            return;
        }
        if (view == null || view.store != null || !source.immutable || target != GL_TEXTURE_2D || minLayer != 0 || layers != 1) {
            gl.error(GL_INVALID_OPERATION);
            return;
        }
        TexFormat format = TexFormat.fromInternalFormat(internalFormat);
        GlDevice.TextureStore store = format == null ? null : gl.device.textureView(source.store, format, minLevel, levels);
        if (store == null) {
            gl.error(GL_INVALID_OPERATION);
            return;
        }
        view.target = target;
        view.format = format;
        view.internalFormat = internalFormat;
        view.width = store.width();
        view.height = store.height();
        view.levels = levels;
        view.immutable = true;
        view.store = store;
        view.viewOf = source;
    }

    // ---------------------------------------------------------------------------------------------- framebuffers

    int createFramebuffer() {
        int name = gl.framebuffers.reserve();
        gl.framebuffers.put(name, new Framebuffer(name));
        return name;
    }

    int createRenderbuffer() {
        int name = gl.renderbuffers.reserve();
        Texture rb = new Texture(name);
        rb.renderbuffer = true;
        rb.target = GL_RENDERBUFFER;
        gl.renderbuffers.put(name, rb);
        return name;
    }

    void namedRenderbufferStorage(final int name, final int internalFormat, final int width, final int height) {
        Texture rb = name == 0 ? null : gl.renderbuffers.get(name);
        if (rb == null) {
            gl.error(GL_INVALID_OPERATION);
            return;
        }
        Texture previous = gl.boundRenderbuffer;
        gl.boundRenderbuffer = rb;
        try {
            gl.renderbufferStorage(GL_RENDERBUFFER, internalFormat, width, height);
        } finally {
            gl.boundRenderbuffer = previous;
        }
    }

    /** {@code name == 0}: the default framebuffer (null). Returns false (error set) if the name is not a framebuffer. */
    private boolean framebuffer(final int name, final @Nullable Framebuffer[] out) {
        if (name == 0) {
            out[0] = null;
            return true;
        }
        Framebuffer fb = gl.framebuffers.get(name);
        if (fb == null) {
            gl.error(GL_INVALID_OPERATION);
            return false;
        }
        out[0] = fb;
        return true;
    }

    private <T> T onFramebuffer(final int name, final T failed, final Supplier<T> action) {
        Framebuffer[] fb = new Framebuffer[1];
        if (!framebuffer(name, fb)) {
            return failed;
        }
        Framebuffer previous = gl.drawFramebuffer;
        gl.drawFramebuffer = fb[0];
        try {
            return action.get();
        } finally {
            gl.drawFramebuffer = previous;
        }
    }

    void namedFramebufferTexture(final int fb, final int attachment, final int texture, final int level) {
        onFramebuffer(fb, null, () -> {
            gl.framebufferAttach(GL_DRAW_FRAMEBUFFER, attachment, texture, level, false);
            return null;
        });
    }

    void namedFramebufferRenderbuffer(final int fb, final int attachment, final int renderbuffer) {
        onFramebuffer(fb, null, () -> {
            gl.framebufferAttach(GL_DRAW_FRAMEBUFFER, attachment, renderbuffer, 0, true);
            return null;
        });
    }

    void namedFramebufferDrawBuffers(final int fb, final int[] buffers) {
        onFramebuffer(fb, null, () -> {
            gl.drawBuffers(buffers);
            return null;
        });
    }

    void namedFramebufferReadBuffer(final int fb, final int mode) {
        Framebuffer[] out = new Framebuffer[1];
        if (framebuffer(fb, out) && out[0] != null) {
            out[0].readBuffer = mode;
        }
    }

    int checkNamedFramebufferStatus(final int fb, final int target) {
        Framebuffer[] out = new Framebuffer[1];
        if (!framebuffer(fb, out)) {
            return 0;
        }
        return out[0] == null ? GL_FRAMEBUFFER_COMPLETE : GlContext.status(out[0]);
    }

    /**
     * glClearNamedFramebuffer{fv,iv,uiv,fi} / glClearBuffer*: {@code buffer} GL_COLOR (draw buffer {@code drawBuffer}),
     * GL_DEPTH, GL_STENCIL or GL_DEPTH_STENCIL. Honors the scissor test and colour / depth masks, as GL does.
     */
    void clearNamedFramebuffer(final int fb, final int buffer, final int drawBuffer, final float @Nullable [] color, final float depth,
                               final int stencil) {
        Framebuffer[] out = new Framebuffer[1];
        if (!framebuffer(fb, out)) {
            return;
        }
        GlDevice.RenderTargets all = gl.targetsOf(out[0]);
        if (all == null) {
            return;
        }
        int[] scissor = gl.scissorTest ? gl.scissorBox.clone() : null;
        switch (buffer) {
            case GL_COLOR -> {
                if (drawBuffer < 0 || drawBuffer >= GlContext.MAX_COLOR_ATTACHMENTS) {
                    gl.error(GL_INVALID_VALUE);
                    return;
                }
                if (drawBuffer >= all.colors().length || all.colors()[drawBuffer] == null || color == null) {
                    return;
                }
                GlDevice.TextureStore[] colors = new GlDevice.TextureStore[drawBuffer + 1];
                colors[drawBuffer] = all.colors()[drawBuffer];
                int[] levels = new int[drawBuffer + 1];
                levels[drawBuffer] = all.colorLevels()[drawBuffer];
                GlDevice.RenderTargets one = new GlDevice.RenderTargets(colors, levels, null, 0, false);
                gl.device.clear(one, new GlDevice.ClearOp(true, color.clone(), gl.colorMask[drawBuffer].clone(), false, 0f, false, 0, scissor));
            }
            case GL_DEPTH, GL_STENCIL, GL_DEPTH_STENCIL -> {
                if (all.depth() == null) {
                    return;
                }
                GlDevice.RenderTargets depthOnly = new GlDevice.RenderTargets(new GlDevice.TextureStore[0], new int[0], all.depth(), all.depthLevel(),
                        all.depthHasStencil());
                boolean clearDepth = buffer != GL_STENCIL && gl.depthMask;
                boolean clearStencil = buffer != GL_DEPTH && all.depthHasStencil();
                gl.device.clear(depthOnly, new GlDevice.ClearOp(false, new float[4], new boolean[]{true, true, true, true}, clearDepth, depth,
                        clearStencil, stencil, scissor));
            }
            default -> gl.error(GL_INVALID_ENUM);
        }
    }

    /**
     * glBlitNamedFramebuffer: 1:1 copies (same size, same storage format) of the colour read buffer and/or depth;
     * scaled or format-converting blits are not supported yet.
     */
    void blitNamedFramebuffer(final int read, final int draw, final int sx0, final int sy0, final int sx1, final int sy1,
                              final int dx0, final int dy0, final int dx1, final int dy1, final int mask, final int filter) {
        Framebuffer[] r = new Framebuffer[1];
        Framebuffer[] d = new Framebuffer[1];
        if (!framebuffer(read, r) || !framebuffer(draw, d)) {
            return;
        }
        int w = sx1 - sx0;
        int h = sy1 - sy0;
        if (w != dx1 - dx0 || h != dy1 - dy0 || w <= 0 || h <= 0) {
            MetalGLLog.unsupported("scaled / flipped glBlitFramebuffer");
            return;
        }
        GlDevice.RenderTargets source = r[0] == null ? gl.device.defaultFramebuffer() : readTargets(r[0]);
        GlDevice.RenderTargets destination = gl.targetsOf(d[0]);
        if (source == null || destination == null) {
            return;
        }
        if ((mask & GL_COLOR_BUFFER_BIT) != 0 && source.colors().length > 0 && source.colors()[0] != null) {
            for (int i = 0; i < destination.colors().length; i++) {
                if (destination.colors()[i] == null) continue;
                copy(source.colors()[0], source.colorLevels()[0], destination.colors()[i], destination.colorLevels()[i], sx0, sy0, dx0, dy0, w, h);
            }
        }
        if ((mask & (GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT)) != 0 && source.depth() != null && destination.depth() != null) {
            copy(source.depth(), source.depthLevel(), destination.depth(), destination.depthLevel(), sx0, sy0, dx0, dy0, w, h);
        }
    }

    private void copy(final GlDevice.TextureStore source, final int sourceLevel, final GlDevice.TextureStore destination, final int destinationLevel,
                      final int sx, final int sy, final int dx, final int dy, final int w, final int h) {
        if (source.format() != destination.format()) {
            MetalGLLog.unsupported("glBlitFramebuffer between " + source.format() + " and " + destination.format());
            return;
        }
        int sw = Math.max(1, source.width() >> sourceLevel);
        int sh = Math.max(1, source.height() >> sourceLevel);
        int dw = Math.max(1, destination.width() >> destinationLevel);
        int dh = Math.max(1, destination.height() >> destinationLevel);
        int cw = Math.min(w, Math.min(sw - sx, dw - dx));
        int ch = Math.min(h, Math.min(sh - sy, dh - dy));
        if (cw > 0 && ch > 0 && sx >= 0 && sy >= 0 && dx >= 0 && dy >= 0) {
            gl.device.copyTextureRegion(source, sourceLevel, sx, sy, destination, destinationLevel, dx, dy, cw, ch);
        }
    }

    private GlDevice.@Nullable RenderTargets readTargets(final Framebuffer fb) {
        int index = fb.readBuffer - GL_COLOR_ATTACHMENT0;
        GlDevice.TextureStore color = index >= 0 && index < GlContext.MAX_COLOR_ATTACHMENTS && fb.color[index] != null ? fb.color[index].texture().store : null;
        int level = color == null ? 0 : fb.color[index].level();
        GlObjects.Attachment depth = fb.depth != null ? fb.depth : fb.stencil;
        return new GlDevice.RenderTargets(color == null ? new GlDevice.TextureStore[0] : new GlDevice.TextureStore[]{color},
                color == null ? new int[0] : new int[]{level}, depth == null ? null : depth.texture().store, depth == null ? 0 : depth.level(),
                depth != null && depth.texture().format == TexFormat.DEPTH32F_STENCIL8);
    }

    // ---------------------------------------------------------------------------------------------- vertex arrays

    int createVertexArray() {
        int name = gl.vertexArrays.reserve();
        gl.vertexArrays.put(name, new VertexArray(name));
        return name;
    }

    private @Nullable VertexArray vertexArray(final int name) {
        VertexArray vao = name == 0 ? null : gl.vertexArrays.get(name);
        if (vao == null) {
            gl.error(GL_INVALID_OPERATION);
        }
        return vao;
    }

    void enableVertexArrayAttrib(final int name, final int index, final boolean enabled) {
        VertexArray vao = vertexArray(name);
        if (vao == null) {
            return;
        }
        if (index < 0 || index >= GlContext.MAX_VERTEX_ATTRIBS) {
            gl.error(GL_INVALID_VALUE);
            return;
        }
        vao.attribs[index].enabled = enabled;
    }

    void vertexArrayElementBuffer(final int name, final int buffer) {
        VertexArray vao = vertexArray(name);
        if (vao == null) {
            return;
        }
        if (buffer == 0) {
            vao.elementBuffer = null;
            return;
        }
        Buffer b = buffer(buffer);
        if (b != null) {
            vao.elementBuffer = b;
        }
    }

    /** glVertexArrayVertexBuffer: binding point {@code binding} reads {@code buffer} from {@code offset} with {@code stride}. */
    void vertexArrayVertexBuffer(final int name, final int binding, final int buffer, final long offset, final int stride) {
        VertexArray vao = vertexArray(name);
        if (vao == null) {
            return;
        }
        if (binding < 0 || binding >= GlContext.MAX_VERTEX_ATTRIBS || offset < 0 || stride < 0) {
            gl.error(GL_INVALID_VALUE);
            return;
        }
        Buffer b = buffer == 0 ? null : buffer(buffer);
        if (buffer != 0 && b == null) {
            return;
        }
        vao.bindingBuffer[binding] = b;
        vao.bindingOffset[binding] = offset;
        vao.bindingStride[binding] = stride;
        if (vao.attribs[binding].binding < 0) vao.attribs[binding].binding = binding; // GL default: attribute i reads binding i
        refresh(vao);
    }

    void vertexArrayAttribFormat(final int name, final int index, final int size, final int type, final boolean normalized, final boolean integer,
                                 final int relativeOffset) {
        VertexArray vao = vertexArray(name);
        if (vao == null) {
            return;
        }
        if (index < 0 || index >= GlContext.MAX_VERTEX_ATTRIBS || size < 1 || size > 4 || relativeOffset < 0) {
            gl.error(GL_INVALID_VALUE);
            return;
        }
        VertexAttribState attrib = vao.attribs[index];
        attrib.size = size;
        attrib.type = type;
        attrib.normalized = normalized;
        attrib.integer = integer;
        attrib.relativeOffset = relativeOffset;
        if (attrib.binding < 0) attrib.binding = index;
        refresh(vao);
    }

    void vertexArrayAttribBinding(final int name, final int index, final int binding) {
        VertexArray vao = vertexArray(name);
        if (vao == null) {
            return;
        }
        if (index < 0 || index >= GlContext.MAX_VERTEX_ATTRIBS || binding < 0 || binding >= GlContext.MAX_VERTEX_ATTRIBS) {
            gl.error(GL_INVALID_VALUE);
            return;
        }
        vao.attribs[index].binding = binding;
        refresh(vao);
    }

    void vertexArrayBindingDivisor(final int name, final int binding, final int divisor) {
        VertexArray vao = vertexArray(name);
        if (vao == null) {
            return;
        }
        if (binding < 0 || binding >= GlContext.MAX_VERTEX_ATTRIBS) {
            gl.error(GL_INVALID_VALUE);
            return;
        }
        vao.bindingDivisor[binding] = divisor;
        refresh(vao);
    }

    /** Separate attribute format + binding state → the combined per-attribute state the draw path reads. */
    private static void refresh(final VertexArray vao) {
        for (VertexAttribState attrib : vao.attribs) {
            if (attrib.binding < 0) continue; // set through glVertexAttribPointer
            int b = attrib.binding;
            attrib.buffer = vao.bindingBuffer[b];
            attrib.offset = vao.bindingOffset[b] + attrib.relativeOffset;
            attrib.stride = vao.bindingStride[b];
            attrib.divisor = vao.bindingDivisor[b];
        }
    }
}
