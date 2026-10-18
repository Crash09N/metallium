/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl;

import dev.metallium.gl.GlObjects.Attachment;
import dev.metallium.gl.GlObjects.Buffer;
import dev.metallium.gl.GlObjects.Framebuffer;
import dev.metallium.gl.GlObjects.Names;
import dev.metallium.gl.GlObjects.Program;
import dev.metallium.gl.GlObjects.Shader;
import dev.metallium.gl.GlObjects.Sync;
import dev.metallium.gl.GlObjects.Texture;
import dev.metallium.gl.GlObjects.VertexArray;
import dev.metallium.gl.GlObjects.VertexAttribState;
import dev.metallium.shaders.GlslProgramTranslator;
import dev.metallium.shaders.GlslProgramTranslator.BlockMember;
import dev.metallium.shaders.GlslProgramTranslator.ScalarType;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static org.lwjgl.opengl.GL46C.*;

/**
 * The emulated GL context: name spaces (GL names → objects), the state tracker, GL error semantics, and the resolution
 * of draws/clears into {@link GlDevice} operations. One context exists per game (GL calls come from the render thread).
 */
public final class GlContext {
    public static final int MAX_COLOR_ATTACHMENTS = 8;
    public static final int MAX_VERTEX_ATTRIBS = 16;
    public static final int MAX_TEXTURE_UNITS = 32;
    public static final int MAX_TEXTURE_SIZE = 16384;
    /** Fake "GL_QUADS" (compat profile) — accepted and converted to triangles. */
    static final int GL_QUADS = 0x0007;

    private static @Nullable GlContext current;
    private static @Nullable Supplier<GlDevice> deviceFactory;

    final GlDevice device;
    private int error = GL_NO_ERROR;

    final Names<Buffer> buffers = new Names<>();
    final Names<Texture> textures = new Names<>();
    final Names<Texture> renderbuffers = new Names<>();
    final Names<Framebuffer> framebuffers = new Names<>();
    final Names<VertexArray> vertexArrays = new Names<>();
    /** Shaders and programs share one name space, as in GL. */
    final Names<Object> shaderObjects = new Names<>();
    private final java.util.Map<Long, Sync> syncs = new java.util.HashMap<>();
    private long nextSync = 1;

    // Bindings
    @Nullable Buffer arrayBuffer;
    @Nullable Buffer copyReadBuffer;
    @Nullable Buffer copyWriteBuffer;
    private @Nullable Buffer pixelUnpackBuffer;
    private @Nullable Buffer pixelPackBuffer;
    private @Nullable Buffer uniformBuffer;
    /** Generic bindings of GL 4.x buffer targets (SSBO, indirect, parameter, ...). */
    final java.util.Map<Integer, Buffer> otherBuffers = new java.util.HashMap<>();
    /** Indexed binding points, image units, sampler objects (GL 4.x). */
    final GlBindings bindings = new GlBindings(this);
    /** Compute, indirect draws, GPU-ordered buffer operations (GL 4.3+). */
    final GlCompute compute = new GlCompute(this);
    /** Direct state access (GL 4.5). */
    final GlDsa dsa = new GlDsa(this);
    final GlQueries queries = new GlQueries(this);
    final GlExternalTextures externalTextures = new GlExternalTextures(this);
    int activeTexture;
    final @Nullable Texture[] bound2D = new Texture[MAX_TEXTURE_UNITS];
    @Nullable Framebuffer drawFramebuffer;
    @Nullable Framebuffer readFramebuffer;
    @Nullable Texture boundRenderbuffer;
    private final VertexArray defaultVertexArray = new VertexArray(0);
    VertexArray vertexArray = defaultVertexArray;
    @Nullable Program program;
    private final int[] defaultDrawBuffers = {GL_BACK};

    // Fixed-function state
    final int[] viewport = {0, 0, 0, 0};
    final int[] scissorBox = {0, 0, 0, 0};
    private boolean viewportInitialised;
    boolean scissorTest;
    final boolean[] blendEnabled = new boolean[MAX_COLOR_ATTACHMENTS];
    final int[][] blendFunc = new int[MAX_COLOR_ATTACHMENTS][];
    final int[][] blendEquation = new int[MAX_COLOR_ATTACHMENTS][];
    final boolean[][] colorMask = new boolean[MAX_COLOR_ATTACHMENTS][];
    final float[] blendColor = new float[4];
    boolean depthTest;
    int depthFunc = GL_LESS;
    boolean depthMask = true;
    float depthNear = 0f;
    float depthFar = 1f;
    boolean cullFace;
    int cullFaceMode = GL_BACK;
    int frontFace = GL_CCW;
    boolean polygonOffsetFill;
    float polygonOffsetFactor;
    float polygonOffsetUnits;
    int polygonMode = GL_FILL;
    boolean stencilTest;
    int stencilFunc = GL_ALWAYS;
    int stencilRef;
    int stencilValueMask = 0xFF;
    int stencilWriteMask = 0xFF;
    int stencilFail = GL_KEEP;
    int stencilDepthFail = GL_KEEP;
    int stencilDepthPass = GL_KEEP;
    final float[] clearColor = new float[4];
    float clearDepth = 1f;
    int clearStencil;
    float lineWidth = 1f;
    GlPixels.Unpack unpack = GlPixels.Unpack.DEFAULT;
    int packAlignment = 4;
    int packRowLength;
    int packSkipRows;
    int packSkipPixels;
    final float[][] currentAttrib = new float[MAX_VERTEX_ATTRIBS][];

    public GlContext(final GlDevice device) {
        this.device = device;
        for (int i = 0; i < MAX_COLOR_ATTACHMENTS; i++) {
            blendFunc[i] = new int[]{GL_ONE, GL_ZERO, GL_ONE, GL_ZERO};
            blendEquation[i] = new int[]{GL_FUNC_ADD, GL_FUNC_ADD};
            colorMask[i] = new boolean[]{true, true, true, true};
        }
        for (int i = 0; i < MAX_VERTEX_ATTRIBS; i++) {
            currentAttrib[i] = new float[]{0f, 0f, 0f, 1f};
        }
    }

    /** Production hook: how the first context gets its device (set by metallium-gl at start-up). */
    public static void setDeviceFactory(final Supplier<GlDevice> factory) {
        deviceFactory = factory;
    }

    public static GlContext current() {
        GlContext context = current;
        if (context == null) {
            Supplier<GlDevice> factory = deviceFactory;
            if (factory == null) {
                throw new IllegalStateException("metallium-gl: no GL context and no device factory");
            }
            context = new GlContext(factory.get());
            current = context;
        }
        return context;
    }

    public static void makeCurrent(final @Nullable GlContext context) {
        current = context;
    }

    // ---------------------------------------------------------------------------------------------- errors

    void error(final int code) {
        if (error == GL_NO_ERROR) {
            error = code;
        }
    }

    public int getError() {
        int e = error;
        error = GL_NO_ERROR;
        return e;
    }

    // ---------------------------------------------------------------------------------------------- buffers

    public int genBuffer() {
        return buffers.reserve();
    }

    public int createBuffer() {
        int name = buffers.reserve();
        buffers.put(name, new Buffer(name));
        return name;
    }

    public void deleteBuffer(final int name) {
        if (name == 0) {
            return;
        }
        Buffer buffer = buffers.remove(name);
        if (buffer == null) {
            return;
        }
        if (arrayBuffer == buffer) arrayBuffer = null;
        if (copyReadBuffer == buffer) copyReadBuffer = null;
        if (copyWriteBuffer == buffer) copyWriteBuffer = null;
        if (pixelUnpackBuffer == buffer) pixelUnpackBuffer = null;
        if (pixelPackBuffer == buffer) pixelPackBuffer = null;
        if (uniformBuffer == buffer) uniformBuffer = null;
        if (vertexArray.elementBuffer == buffer) vertexArray.elementBuffer = null;
        for (VertexAttribState attrib : vertexArray.attribs) {
            if (attrib.buffer == buffer) attrib.buffer = null;
        }
        otherBuffers.values().removeIf(b -> b == buffer);
        bindings.forget(buffer);
        releaseStore(buffer);
    }

    public boolean isBuffer(final int name) {
        return buffers.get(name) != null;
    }

    public void bindBuffer(final int target, final int name) {
        Buffer buffer = null;
        if (name != 0) {
            if (!buffers.isReserved(name)) {
                error(GL_INVALID_OPERATION);
                return;
            }
            buffer = buffers.get(name);
            if (buffer == null) {
                buffer = new Buffer(name);
                buffers.put(name, buffer);
            }
        }
        switch (target) {
            case GL_ARRAY_BUFFER -> arrayBuffer = buffer;
            case GL_ELEMENT_ARRAY_BUFFER -> vertexArray.elementBuffer = buffer;
            case GL_COPY_READ_BUFFER -> copyReadBuffer = buffer;
            case GL_COPY_WRITE_BUFFER -> copyWriteBuffer = buffer;
            case GL_PIXEL_UNPACK_BUFFER -> pixelUnpackBuffer = buffer;
            case GL_PIXEL_PACK_BUFFER -> pixelPackBuffer = buffer;
            case GL_UNIFORM_BUFFER -> uniformBuffer = buffer;
            default -> {
                if (!isOtherBufferTarget(target)) {
                    MetalGLLog.unsupported("glBindBuffer target 0x" + Integer.toHexString(target));
                    error(GL_INVALID_ENUM);
                } else if (buffer == null) {
                    otherBuffers.remove(target);
                } else {
                    otherBuffers.put(target, buffer);
                }
            }
        }
    }

    static boolean isOtherBufferTarget(final int target) {
        return switch (target) {
            case GL_SHADER_STORAGE_BUFFER, GL_DRAW_INDIRECT_BUFFER, GL_DISPATCH_INDIRECT_BUFFER, GL_PARAMETER_BUFFER, GL_QUERY_BUFFER,
                 GL_TEXTURE_BUFFER, GL_ATOMIC_COUNTER_BUFFER, GL_TRANSFORM_FEEDBACK_BUFFER -> true;
            default -> false;
        };
    }

    @Nullable Buffer boundBuffer(final int target) {
        return switch (target) {
            case GL_ARRAY_BUFFER -> arrayBuffer;
            case GL_ELEMENT_ARRAY_BUFFER -> vertexArray.elementBuffer;
            case GL_COPY_READ_BUFFER -> copyReadBuffer;
            case GL_COPY_WRITE_BUFFER -> copyWriteBuffer;
            case GL_PIXEL_UNPACK_BUFFER -> pixelUnpackBuffer;
            case GL_PIXEL_PACK_BUFFER -> pixelPackBuffer;
            case GL_UNIFORM_BUFFER -> uniformBuffer;
            default -> otherBuffers.get(target);
        };
    }

    @Nullable Buffer requireBound(final int target) {
        if (!isBufferTarget(target)) {
            error(GL_INVALID_ENUM);
            return null;
        }
        Buffer buffer = boundBuffer(target);
        if (buffer == null) {
            error(GL_INVALID_OPERATION);
        }
        return buffer;
    }

    private static boolean isBufferTarget(final int target) {
        return switch (target) {
            case GL_ARRAY_BUFFER, GL_ELEMENT_ARRAY_BUFFER, GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, GL_PIXEL_UNPACK_BUFFER,
                 GL_PIXEL_PACK_BUFFER, GL_UNIFORM_BUFFER -> true;
            default -> isOtherBufferTarget(target);
        };
    }

    /** glBufferData; {@code data == null}: uninitialised (zeroed here) storage of {@code size} bytes. */
    public void bufferData(final int target, final long size, final @Nullable ByteBuffer data, final int usage) {
        Buffer buffer = requireBound(target);
        if (buffer == null) {
            return;
        }
        if (size < 0) {
            error(GL_INVALID_VALUE);
            return;
        }
        if (buffer.immutable) {
            error(GL_INVALID_OPERATION);
            return;
        }
        if (buffer.mapped) {
            buffer.mapped = false;
        }
        allocate(buffer, size);
        buffer.usage = usage;
        if (data != null && size > 0) {
            MemoryUtil.memCopy(MemoryUtil.memAddress(data), MemoryUtil.memAddress(buffer.store.contents()), size);
        }
    }

    /** glBufferStorage (immutable; persistent mapping returns the GPU storage itself on unified memory). */
    public void bufferStorage(final int target, final long size, final @Nullable ByteBuffer data, final int flags) {
        Buffer buffer = requireBound(target);
        if (buffer == null) {
            return;
        }
        if (size <= 0) {
            error(GL_INVALID_VALUE);
            return;
        }
        if (buffer.immutable) {
            error(GL_INVALID_OPERATION);
            return;
        }
        allocate(buffer, size);
        buffer.immutable = true;
        buffer.storageFlags = flags;
        if (data != null) {
            MemoryUtil.memCopy(MemoryUtil.memAddress(data), MemoryUtil.memAddress(buffer.store.contents()), size);
        }
    }

    void allocate(final Buffer buffer, final long size) {
        releaseStore(buffer);
        buffer.store = device.createBuffer(Math.max(size, 4));
        buffer.size = size;
    }

    private void releaseStore(final Buffer buffer) {
        if (buffer.store != null) {
            device.release(buffer.store);
            buffer.store = null;
        }
    }

    public void bufferSubData(final int target, final long offset, final ByteBuffer data) {
        Buffer buffer = requireBound(target);
        if (buffer == null) {
            return;
        }
        long length = data.remaining();
        if (offset < 0 || offset + length > buffer.size) {
            error(GL_INVALID_VALUE);
            return;
        }
        if (buffer.immutable && (buffer.storageFlags & GL_DYNAMIC_STORAGE_BIT) == 0) {
            error(GL_INVALID_OPERATION);
            return;
        }
        if (buffer.mapped && !buffer.persistent()) {
            error(GL_INVALID_OPERATION);
            return;
        }
        if (buffer.gpuWritten && !buffer.persistent() && buffer.store.inUseByGpu()) {
            // The GPU writes this buffer (SSBO / copy target) in pending work: renaming would lose those writes, so the
            // update is a GPU-ordered upload instead.
            device.uploadBuffer(buffer.store, offset, data);
            return;
        }
        ensureWritable(buffer);
        MemoryUtil.memCopy(MemoryUtil.memAddress(data), MemoryUtil.memAddress(buffer.store.contents()) + offset, length);
    }

    /**
     * GL orders a buffer update after earlier draws. Storage is shared with the GPU, so if pending GPU work still reads it
     * the buffer gets fresh storage (contents copied) instead of being overwritten ("renaming"). Persistent buffers are
     * synchronised by the application (fences), as in GL.
     */
    void ensureWritable(final Buffer buffer) {
        GlDevice.BufferStore store = buffer.store;
        if (store == null || buffer.persistent() || buffer.gpuWritten || !store.inUseByGpu()) {
            return;
        }
        GlDevice.BufferStore fresh = device.createBuffer(store.size());
        MemoryUtil.memCopy(MemoryUtil.memAddress(store.contents()), MemoryUtil.memAddress(fresh.contents()), store.size());
        device.release(store);
        buffer.store = fresh;
    }

    public @Nullable ByteBuffer mapBufferRange(final int target, final long offset, final long length, final int access) {
        Buffer buffer = requireBound(target);
        if (buffer == null) {
            return null;
        }
        if (offset < 0 || length <= 0 || offset + length > buffer.size || buffer.mapped
                || (access & (GL_MAP_READ_BIT | GL_MAP_WRITE_BIT)) == 0) {
            error(buffer.mapped ? GL_INVALID_OPERATION : GL_INVALID_VALUE);
            return null;
        }
        if ((access & GL_MAP_PERSISTENT_BIT) != 0 && (buffer.storageFlags & GL_MAP_PERSISTENT_BIT) == 0) {
            error(GL_INVALID_OPERATION);
            return null;
        }
        if ((access & GL_MAP_WRITE_BIT) != 0 && (access & GL_MAP_UNSYNCHRONIZED_BIT) == 0) {
            if ((access & GL_MAP_INVALIDATE_BUFFER_BIT) != 0 && !buffer.persistent() && buffer.store != null && buffer.store.inUseByGpu()) {
                // Contents may be discarded: fresh storage without the copy.
                device.release(buffer.store);
                buffer.store = device.createBuffer(Math.max(buffer.size, 4));
            } else {
                ensureWritable(buffer);
            }
        }
        buffer.mapped = true;
        buffer.mapOffset = offset;
        buffer.mapLength = length;
        buffer.mapAccess = access;
        return MemoryUtil.memByteBuffer(MemoryUtil.memAddress(buffer.store.contents()) + offset, (int) length).order(ByteOrder.nativeOrder());
    }

    public @Nullable ByteBuffer mapBuffer(final int target, final int access) {
        Buffer buffer = requireBound(target);
        if (buffer == null) {
            return null;
        }
        int bits = switch (access) {
            case GL_READ_ONLY -> GL_MAP_READ_BIT;
            case GL_WRITE_ONLY -> GL_MAP_WRITE_BIT;
            case GL_READ_WRITE -> GL_MAP_READ_BIT | GL_MAP_WRITE_BIT;
            default -> 0;
        };
        if (bits == 0) {
            error(GL_INVALID_ENUM);
            return null;
        }
        return mapBufferRange(target, 0, buffer.size, bits);
    }

    public boolean unmapBuffer(final int target) {
        Buffer buffer = requireBound(target);
        if (buffer == null) {
            return false;
        }
        if (!buffer.mapped) {
            error(GL_INVALID_OPERATION);
            return false;
        }
        buffer.mapped = false;
        return true;
    }

    // ---------------------------------------------------------------------------------------------- textures

    public int genTexture() {
        return textures.reserve();
    }

    public void deleteTexture(final int name) {
        if (name == 0) {
            return;
        }
        Texture texture = textures.remove(name);
        if (texture == null) {
            return;
        }
        for (int i = 0; i < bound2D.length; i++) {
            if (bound2D[i] == texture) bound2D[i] = null;
        }
        bindings.forget(texture);
        detachEverywhere(texture);
        if (texture.store != null && !texture.external) {
            device.release(texture.store);
        }
        texture.store = null;
    }

    private void detachEverywhere(final Texture texture) {
        for (Framebuffer fb : new Framebuffer[]{drawFramebuffer, readFramebuffer}) {
            if (fb == null) continue;
            for (int i = 0; i < fb.color.length; i++) {
                if (fb.color[i] != null && fb.color[i].texture() == texture) fb.color[i] = null;
            }
            if (fb.depth != null && fb.depth.texture() == texture) fb.depth = null;
            if (fb.stencil != null && fb.stencil.texture() == texture) fb.stencil = null;
        }
    }

    public boolean isTexture(final int name) {
        return textures.get(name) != null;
    }

    public void activeTexture(final int unit) {
        int index = unit - GL_TEXTURE0;
        if (index < 0 || index >= MAX_TEXTURE_UNITS) {
            error(GL_INVALID_ENUM);
            return;
        }
        activeTexture = index;
    }

    public void bindTexture(final int target, final int name) {
        if (target != GL_TEXTURE_2D) {
            MetalGLLog.unsupported("texture target 0x" + Integer.toHexString(target));
            error(GL_INVALID_ENUM);
            return;
        }
        if (name == 0) {
            bound2D[activeTexture] = null;
            return;
        }
        if (!textures.isReserved(name)) {
            error(GL_INVALID_OPERATION);
            return;
        }
        Texture texture = textures.get(name);
        if (texture == null) {
            texture = new Texture(name);
            texture.target = target;
            textures.put(name, texture);
        } else if (texture.target != target) {
            error(GL_INVALID_OPERATION);
            return;
        }
        bound2D[activeTexture] = texture;
    }

    @Nullable Texture boundTexture(final int target) {
        if (target != GL_TEXTURE_2D) {
            MetalGLLog.unsupported("texture target 0x" + Integer.toHexString(target));
            error(GL_INVALID_ENUM);
            return null;
        }
        Texture texture = bound2D[activeTexture];
        if (texture == null) {
            error(GL_INVALID_OPERATION);
        }
        return texture;
    }

    public void texImage2D(final int target, final int level, final int internalFormat, final int width, final int height, final int border,
                           final int format, final int type, final @Nullable ByteBuffer pixels) {
        Texture texture = boundTexture(target);
        if (texture == null) {
            return;
        }
        TexFormat storage = TexFormat.fromInternalFormat(internalFormat);
        if (storage == null) {
            MetalGLLog.unsupported("internal format 0x" + Integer.toHexString(internalFormat));
            error(GL_INVALID_ENUM);
            return;
        }
        if (level < 0 || width < 0 || height < 0 || width > MAX_TEXTURE_SIZE || height > MAX_TEXTURE_SIZE || border != 0) {
            error(GL_INVALID_VALUE);
            return;
        }
        if (texture.immutable) {
            error(GL_INVALID_OPERATION);
            return;
        }
        if (GlPixels.clientPixelBytes(format, type) < 0) {
            error(GL_INVALID_ENUM);
            return;
        }
        if (width == 0 || height == 0) {
            return;
        }
        if (level == 0) {
            boolean same = texture.store != null && texture.format == storage && texture.width == width && texture.height == height;
            if (!same) {
                allocateTexture(texture, storage, internalFormat, width, height, 1);
            }
        } else {
            if (texture.store == null || (Math.max(1, texture.width >> level) != width) || (Math.max(1, texture.height >> level) != height)) {
                // Level sizes that do not follow level 0 would make the GL texture incomplete; keep it simple.
                MetalGLLog.unsupported("glTexImage2D level " + level + " before/independent of level 0");
                return;
            }
            ensureLevels(texture, level + 1);
        }
        if (pixels != null) {
            upload(texture, level, 0, 0, width, height, format, type, pixels);
        }
    }

    public void texStorage2D(final int target, final int levels, final int internalFormat, final int width, final int height) {
        Texture texture = boundTexture(target);
        if (texture == null) {
            return;
        }
        TexFormat storage = TexFormat.fromInternalFormat(internalFormat);
        if (storage == null) {
            error(GL_INVALID_ENUM);
            return;
        }
        if (levels < 1 || width < 1 || height < 1 || levels > maxLevels(width, height)) {
            error(GL_INVALID_VALUE);
            return;
        }
        if (texture.immutable) {
            error(GL_INVALID_OPERATION);
            return;
        }
        allocateTexture(texture, storage, internalFormat, width, height, levels);
        texture.immutable = true;
    }

    static int maxLevels(final int width, final int height) {
        return 32 - Integer.numberOfLeadingZeros(Math.max(1, Math.max(width, height)));
    }

    void allocateTexture(final Texture texture, final TexFormat format, final int internalFormat, final int width, final int height, final int levels) {
        if (texture.store != null) {
            device.release(texture.store);
        }
        texture.format = format;
        texture.internalFormat = internalFormat;
        texture.width = width;
        texture.height = height;
        texture.levels = levels;
        texture.store = device.createTexture(format, width, height, levels);
    }

    /** Grows the storage to {@code levels} mip levels, keeping existing contents. */
    private void ensureLevels(final Texture texture, final int levels) {
        if (texture.levels >= levels || texture.store == null) {
            return;
        }
        int wanted = maxLevels(texture.width, texture.height);
        GlDevice.TextureStore old = texture.store;
        GlDevice.TextureStore grown = device.createTexture(texture.format, texture.width, texture.height, wanted);
        device.copyTexture(old, grown, texture.levels);
        device.release(old);
        texture.store = grown;
        texture.levels = wanted;
    }

    public void texSubImage2D(final int target, final int level, final int x, final int y, final int width, final int height,
                              final int format, final int type, final ByteBuffer pixels) {
        Texture texture = boundTexture(target);
        if (texture == null) {
            return;
        }
        if (texture.store == null || level < 0 || level >= texture.levels) {
            error(texture.store == null ? GL_INVALID_OPERATION : GL_INVALID_VALUE);
            return;
        }
        int levelWidth = Math.max(1, texture.width >> level);
        int levelHeight = Math.max(1, texture.height >> level);
        if (x < 0 || y < 0 || width < 0 || height < 0 || x + width > levelWidth || y + height > levelHeight) {
            error(GL_INVALID_VALUE);
            return;
        }
        if (GlPixels.clientPixelBytes(format, type) < 0) {
            error(GL_INVALID_ENUM);
            return;
        }
        if (width == 0 || height == 0) {
            return;
        }
        upload(texture, level, x, y, width, height, format, type, pixels);
    }

    private void upload(final Texture texture, final int level, final int x, final int y, final int width, final int height,
                        final int format, final int type, final ByteBuffer pixels) {
        if (pixels.remaining() < GlPixels.clientBytes(format, type, width, height, unpack)) {
            error(GL_INVALID_OPERATION);
            return;
        }
        ByteBuffer converted = GlPixels.convert(pixels, format, type, width, height, unpack, texture.format);
        if (converted == null) {
            MetalGLLog.unsupported("pixel format 0x" + Integer.toHexString(format) + "/type 0x" + Integer.toHexString(type)
                    + " into " + texture.format);
            error(GL_INVALID_OPERATION);
            return;
        }
        try {
            device.writeTexture(texture.store, level, x, y, width, height, converted, width * texture.format.bytesPerPixel);
        } finally {
            MemoryUtil.memFree(converted);
        }
    }

    /** Pixel data source for uploads: client memory, or an offset into the bound GL_PIXEL_UNPACK_BUFFER. */
    @Nullable ByteBuffer unpackSource(final long offset) {
        Buffer buffer = pixelUnpackBuffer;
        if (buffer == null || buffer.store == null) {
            return null;
        }
        if (offset < 0 || offset > buffer.size) {
            error(GL_INVALID_OPERATION);
            return null;
        }
        return MemoryUtil.memByteBuffer(MemoryUtil.memAddress(buffer.store.contents()) + offset, (int) (buffer.size - offset));
    }

    boolean hasUnpackBuffer() {
        return pixelUnpackBuffer != null;
    }

    public void texParameteri(final int target, final int pname, final int value) {
        Texture texture = boundTexture(target);
        if (texture == null) {
            return;
        }
        switch (pname) {
            case GL_TEXTURE_MIN_FILTER -> {
                if (!isFilter(value, true)) {
                    error(GL_INVALID_ENUM);
                    return;
                }
                texture.minFilter = value;
            }
            case GL_TEXTURE_MAG_FILTER -> {
                if (!isFilter(value, false)) {
                    error(GL_INVALID_ENUM);
                    return;
                }
                texture.magFilter = value;
            }
            case GL_TEXTURE_WRAP_S, GL_TEXTURE_WRAP_T, GL_TEXTURE_WRAP_R -> {
                if (!isWrap(value)) {
                    error(GL_INVALID_ENUM);
                    return;
                }
                if (pname == GL_TEXTURE_WRAP_S) texture.wrapS = value;
                else if (pname == GL_TEXTURE_WRAP_T) texture.wrapT = value;
                else texture.wrapR = value;
            }
            case GL_TEXTURE_BASE_LEVEL -> texture.baseLevel = Math.max(0, value);
            case GL_TEXTURE_MAX_LEVEL -> texture.maxLevel = Math.max(0, value);
            case GL_TEXTURE_MIN_LOD -> texture.minLod = value;
            case GL_TEXTURE_MAX_LOD -> texture.maxLod = value;
            case GL_TEXTURE_COMPARE_MODE -> texture.compareMode = value;
            case GL_TEXTURE_COMPARE_FUNC -> texture.compareFunc = value;
            case GL_TEXTURE_MAX_ANISOTROPY -> texture.maxAnisotropy = Math.max(1, value);
            case GL_DEPTH_STENCIL_TEXTURE_MODE -> {
                // Metal samples the depth of a depth-stencil texture, which is GL's default (GL_DEPTH_COMPONENT).
                if (value != GL_DEPTH_COMPONENT) MetalGLLog.unsupported("GL_DEPTH_STENCIL_TEXTURE_MODE = GL_STENCIL_INDEX");
            }
            default -> {
                MetalGLLog.unsupported("glTexParameter pname 0x" + Integer.toHexString(pname));
                return;
            }
        }
        texture.samplerCache = null;
    }

    public void texParameterf(final int target, final int pname, final float value) {
        switch (pname) {
            case GL_TEXTURE_MIN_LOD, GL_TEXTURE_MAX_LOD, GL_TEXTURE_LOD_BIAS, GL_TEXTURE_MAX_ANISOTROPY -> {
                Texture texture = boundTexture(target);
                if (texture == null) {
                    return;
                }
                if (pname == GL_TEXTURE_MIN_LOD) texture.minLod = value;
                else if (pname == GL_TEXTURE_MAX_LOD) texture.maxLod = value;
                else if (pname == GL_TEXTURE_LOD_BIAS) texture.lodBias = value;
                else texture.maxAnisotropy = Math.max(1f, value);
                texture.samplerCache = null;
            }
            default -> texParameteri(target, pname, (int) value);
        }
    }

    public int getTexParameteri(final int target, final int pname) {
        Texture texture = boundTexture(target);
        if (texture == null) {
            return 0;
        }
        return switch (pname) {
            case GL_TEXTURE_MIN_FILTER -> texture.minFilter;
            case GL_TEXTURE_MAG_FILTER -> texture.magFilter;
            case GL_TEXTURE_WRAP_S -> texture.wrapS;
            case GL_TEXTURE_WRAP_T -> texture.wrapT;
            case GL_TEXTURE_WRAP_R -> texture.wrapR;
            case GL_TEXTURE_BASE_LEVEL -> texture.baseLevel;
            case GL_TEXTURE_MAX_LEVEL -> texture.maxLevel;
            case GL_TEXTURE_IMMUTABLE_FORMAT -> texture.immutable ? 1 : 0;
            case GL_TEXTURE_IMMUTABLE_LEVELS -> texture.immutable ? texture.levels : 0;
            default -> {
                error(GL_INVALID_ENUM);
                yield 0;
            }
        };
    }

    public int getTexLevelParameteri(final int target, final int level, final int pname) {
        Texture texture = boundTexture(target);
        if (texture == null) {
            return 0;
        }
        boolean present = texture.store != null && level < texture.levels;
        return switch (pname) {
            case GL_TEXTURE_WIDTH -> present ? Math.max(1, texture.width >> level) : 0;
            case GL_TEXTURE_HEIGHT -> present ? Math.max(1, texture.height >> level) : 0;
            case GL_TEXTURE_DEPTH -> present ? 1 : 0;
            case GL_TEXTURE_INTERNAL_FORMAT -> present ? texture.internalFormat : GL_RGBA;
            default -> {
                error(GL_INVALID_ENUM);
                yield 0;
            }
        };
    }

    private static boolean isFilter(final int value, final boolean min) {
        return switch (value) {
            case GL_NEAREST, GL_LINEAR -> true;
            case GL_NEAREST_MIPMAP_NEAREST, GL_LINEAR_MIPMAP_NEAREST, GL_NEAREST_MIPMAP_LINEAR, GL_LINEAR_MIPMAP_LINEAR -> min;
            default -> false;
        };
    }

    private static boolean isWrap(final int value) {
        return value == GL_REPEAT || value == GL_CLAMP_TO_EDGE || value == GL_MIRRORED_REPEAT || value == GL_CLAMP_TO_BORDER
                || value == GL_MIRROR_CLAMP_TO_EDGE;
    }

    public void generateMipmap(final int target) {
        Texture texture = boundTexture(target);
        if (texture == null) {
            return;
        }
        if (texture.store == null || texture.format.isDepth()) {
            error(GL_INVALID_OPERATION);
            return;
        }
        if (!texture.immutable) {
            ensureLevels(texture, maxLevels(texture.width, texture.height));
        }
        if (texture.levels > 1) {
            device.generateMipmaps(texture.store);
        }
    }

    public void pixelStorei(final int pname, final int value) {
        switch (pname) {
            case GL_UNPACK_ALIGNMENT -> {
                if (value != 1 && value != 2 && value != 4 && value != 8) {
                    error(GL_INVALID_VALUE);
                    return;
                }
                unpack = new GlPixels.Unpack(value, unpack.rowLength(), unpack.skipRows(), unpack.skipPixels());
            }
            case GL_UNPACK_ROW_LENGTH -> unpack = new GlPixels.Unpack(unpack.alignment(), value, unpack.skipRows(), unpack.skipPixels());
            case GL_UNPACK_SKIP_ROWS -> unpack = new GlPixels.Unpack(unpack.alignment(), unpack.rowLength(), value, unpack.skipPixels());
            case GL_UNPACK_SKIP_PIXELS -> unpack = new GlPixels.Unpack(unpack.alignment(), unpack.rowLength(), unpack.skipRows(), value);
            case GL_PACK_ALIGNMENT -> packAlignment = value;
            case GL_PACK_ROW_LENGTH -> packRowLength = Math.max(0, value);
            case GL_PACK_SKIP_ROWS -> packSkipRows = Math.max(0, value);
            case GL_PACK_SKIP_PIXELS -> packSkipPixels = Math.max(0, value);
            case GL_PACK_IMAGE_HEIGHT, GL_PACK_SKIP_IMAGES, GL_UNPACK_IMAGE_HEIGHT, GL_UNPACK_SKIP_IMAGES -> {
                if (value != 0) MetalGLLog.unsupported("glPixelStorei pname 0x" + Integer.toHexString(pname) + " = " + value);
            }
            default -> MetalGLLog.unsupported("glPixelStorei pname 0x" + Integer.toHexString(pname));
        }
    }

    // ---------------------------------------------------------------------------------------------- renderbuffers / FBOs

    public int genRenderbuffer() {
        return renderbuffers.reserve();
    }

    public void bindRenderbuffer(final int target, final int name) {
        if (target != GL_RENDERBUFFER) {
            error(GL_INVALID_ENUM);
            return;
        }
        if (name == 0) {
            boundRenderbuffer = null;
            return;
        }
        if (!renderbuffers.isReserved(name)) {
            error(GL_INVALID_OPERATION);
            return;
        }
        Texture rb = renderbuffers.get(name);
        if (rb == null) {
            rb = new Texture(name);
            rb.renderbuffer = true;
            rb.target = GL_RENDERBUFFER;
            renderbuffers.put(name, rb);
        }
        boundRenderbuffer = rb;
    }

    public void renderbufferStorage(final int target, final int internalFormat, final int width, final int height) {
        if (target != GL_RENDERBUFFER) {
            error(GL_INVALID_ENUM);
            return;
        }
        Texture rb = boundRenderbuffer;
        if (rb == null) {
            error(GL_INVALID_OPERATION);
            return;
        }
        TexFormat format = TexFormat.fromInternalFormat(internalFormat);
        if (format == null) {
            error(GL_INVALID_ENUM);
            return;
        }
        if (width < 0 || height < 0 || width > MAX_TEXTURE_SIZE || height > MAX_TEXTURE_SIZE) {
            error(GL_INVALID_VALUE);
            return;
        }
        allocateTexture(rb, format, internalFormat, Math.max(1, width), Math.max(1, height), 1);
    }

    public void deleteRenderbuffer(final int name) {
        if (name == 0) {
            return;
        }
        Texture rb = renderbuffers.remove(name);
        if (rb == null) {
            return;
        }
        if (boundRenderbuffer == rb) boundRenderbuffer = null;
        detachEverywhere(rb);
        if (rb.store != null) {
            device.release(rb.store);
        }
    }

    public int genFramebuffer() {
        return framebuffers.reserve();
    }

    public void bindFramebuffer(final int target, final int name) {
        Framebuffer fb = null;
        if (name != 0) {
            if (!framebuffers.isReserved(name)) {
                error(GL_INVALID_OPERATION);
                return;
            }
            fb = framebuffers.get(name);
            if (fb == null) {
                fb = new Framebuffer(name);
                framebuffers.put(name, fb);
            }
        }
        switch (target) {
            case GL_FRAMEBUFFER -> {
                drawFramebuffer = fb;
                readFramebuffer = fb;
            }
            case GL_DRAW_FRAMEBUFFER -> drawFramebuffer = fb;
            case GL_READ_FRAMEBUFFER -> readFramebuffer = fb;
            default -> error(GL_INVALID_ENUM);
        }
    }

    public void deleteFramebuffer(final int name) {
        if (name == 0) {
            return;
        }
        Framebuffer fb = framebuffers.remove(name);
        if (fb == null) {
            return;
        }
        if (drawFramebuffer == fb) drawFramebuffer = null;
        if (readFramebuffer == fb) readFramebuffer = null;
    }

    @Nullable Framebuffer framebufferFor(final int target, final boolean[] valid) {
        valid[0] = true;
        return switch (target) {
            case GL_FRAMEBUFFER, GL_DRAW_FRAMEBUFFER -> drawFramebuffer;
            case GL_READ_FRAMEBUFFER -> readFramebuffer;
            default -> {
                valid[0] = false;
                yield null;
            }
        };
    }

    /** glFramebufferTexture2D / glFramebufferTexture / glFramebufferRenderbuffer. */
    public void framebufferAttach(final int target, final int attachment, final int name, final int level, final boolean renderbuffer) {
        boolean[] valid = new boolean[1];
        Framebuffer fb = framebufferFor(target, valid);
        if (!valid[0]) {
            error(GL_INVALID_ENUM);
            return;
        }
        if (fb == null) {
            error(GL_INVALID_OPERATION);
            return;
        }
        Attachment value = null;
        if (name != 0) {
            Texture texture = renderbuffer ? renderbuffers.get(name) : textures.get(name);
            if (texture == null) {
                error(GL_INVALID_OPERATION);
                return;
            }
            if (level < 0 || (!renderbuffer && level > 0 && level >= maxLevels(Math.max(1, texture.width), Math.max(1, texture.height)))) {
                error(GL_INVALID_VALUE);
                return;
            }
            value = new Attachment(texture, level);
        }
        int color = attachment - GL_COLOR_ATTACHMENT0;
        if (color >= 0 && color < MAX_COLOR_ATTACHMENTS) {
            fb.color[color] = value;
        } else if (attachment == GL_DEPTH_ATTACHMENT) {
            fb.depth = value;
        } else if (attachment == GL_STENCIL_ATTACHMENT) {
            fb.stencil = value;
        } else if (attachment == GL_DEPTH_STENCIL_ATTACHMENT) {
            fb.depth = value;
            fb.stencil = value;
        } else {
            error(GL_INVALID_ENUM);
        }
    }

    public int checkFramebufferStatus(final int target) {
        boolean[] valid = new boolean[1];
        Framebuffer fb = framebufferFor(target, valid);
        if (!valid[0]) {
            error(GL_INVALID_ENUM);
            return 0;
        }
        return fb == null ? GL_FRAMEBUFFER_COMPLETE : status(fb);
    }

    static int status(final Framebuffer fb) {
        boolean any = false;
        for (Attachment a : fb.color) {
            if (a == null) continue;
            any = true;
            if (a.texture().store == null || a.texture().format.isDepth() || a.level() >= a.texture().levels) {
                return GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT;
            }
        }
        for (Attachment a : new Attachment[]{fb.depth, fb.stencil}) {
            if (a == null) continue;
            any = true;
            if (a.texture().store == null || !a.texture().format.isDepth()) {
                return GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT;
            }
        }
        if (fb.depth != null && fb.stencil != null && fb.depth.texture() != fb.stencil.texture()) {
            return GL_FRAMEBUFFER_UNSUPPORTED; // separate depth and stencil images: Metal needs one combined texture
        }
        if (!any) {
            return GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT;
        }
        // GL 4.1+ (and so the 4.6 metallium-gl reports) dropped INCOMPLETE_DRAW_BUFFER / READ_BUFFER: a draw buffer
        // without an attachment just discards its writes (e.g. depth-only framebuffers with the default draw buffer).
        return GL_FRAMEBUFFER_COMPLETE;
    }

    public void drawBuffers(final int[] buffers) {
        if (buffers.length > MAX_COLOR_ATTACHMENTS) {
            error(GL_INVALID_VALUE);
            return;
        }
        Framebuffer fb = drawFramebuffer;
        if (fb == null) {
            if (buffers.length != 1 || (buffers[0] != GL_BACK && buffers[0] != GL_NONE && buffers[0] != GL_FRONT && buffers[0] != GL_BACK_LEFT)) {
                error(GL_INVALID_OPERATION);
                return;
            }
            defaultDrawBuffers[0] = buffers[0];
            return;
        }
        for (int i = 0; i < buffers.length; i++) {
            int index = buffers[i] - GL_COLOR_ATTACHMENT0;
            if (buffers[i] != GL_NONE && (index < 0 || index >= MAX_COLOR_ATTACHMENTS)) {
                error(GL_INVALID_ENUM);
                return;
            }
        }
        Arrays.fill(fb.drawBuffers, GL_NONE);
        System.arraycopy(buffers, 0, fb.drawBuffers, 0, buffers.length);
    }

    public void readBuffer(final int mode) {
        Framebuffer fb = readFramebuffer;
        if (fb != null) {
            fb.readBuffer = mode;
        }
    }

    /** The draw framebuffer's attachments, or null (with the GL error set) if nothing can be drawn. */
    private GlDevice.@Nullable RenderTargets drawTargets() {
        return targetsOf(drawFramebuffer);
    }

    /** Attachments of {@code fb} (null: framebuffer 0) through its draw buffers; null (GL error set) if incomplete. */
    GlDevice.@Nullable RenderTargets targetsOf(final @Nullable Framebuffer fb) {
        if (fb == null) {
            GlDevice.RenderTargets targets = device.defaultFramebuffer();
            if (targets == null || defaultDrawBuffers[0] == GL_NONE) {
                return null;
            }
            return targets;
        }
        if (status(fb) != GL_FRAMEBUFFER_COMPLETE) {
            error(GL_INVALID_FRAMEBUFFER_OPERATION);
            return null;
        }
        GlDevice.TextureStore[] colors = new GlDevice.TextureStore[MAX_COLOR_ATTACHMENTS];
        int[] levels = new int[MAX_COLOR_ATTACHMENTS];
        int count = 0;
        for (int i = 0; i < MAX_COLOR_ATTACHMENTS; i++) {
            int index = fb.drawBuffers[i] - GL_COLOR_ATTACHMENT0;
            if (fb.drawBuffers[i] != GL_NONE && fb.color[index] != null) {
                colors[i] = fb.color[index].texture().store;
                levels[i] = fb.color[index].level();
                count = i + 1;
            }
        }
        Attachment depth = fb.depth != null ? fb.depth : fb.stencil;
        return new GlDevice.RenderTargets(Arrays.copyOf(colors, count), Arrays.copyOf(levels, count),
                depth == null ? null : depth.texture().store, depth == null ? 0 : depth.level(),
                depth != null && depth.texture().format == TexFormat.DEPTH32F_STENCIL8);
    }

    // ---------------------------------------------------------------------------------------------- vertex arrays

    public int genVertexArray() {
        return vertexArrays.reserve();
    }

    public void bindVertexArray(final int name) {
        if (name == 0) {
            vertexArray = defaultVertexArray;
            return;
        }
        if (!vertexArrays.isReserved(name)) {
            error(GL_INVALID_OPERATION);
            return;
        }
        VertexArray vao = vertexArrays.get(name);
        if (vao == null) {
            vao = new VertexArray(name);
            vertexArrays.put(name, vao);
        }
        vertexArray = vao;
    }

    public void deleteVertexArray(final int name) {
        if (name == 0) {
            return;
        }
        VertexArray vao = vertexArrays.remove(name);
        if (vao != null && vertexArray == vao) {
            vertexArray = defaultVertexArray;
        }
    }

    public boolean isVertexArray(final int name) {
        return vertexArrays.get(name) != null;
    }

    public void enableVertexAttribArray(final int index, final boolean enabled) {
        if (index < 0 || index >= MAX_VERTEX_ATTRIBS) {
            error(GL_INVALID_VALUE);
            return;
        }
        vertexArray.attribs[index].enabled = enabled;
    }

    public void vertexAttribPointer(final int index, final int size, final int type, final boolean normalized, final boolean integer,
                                    final int stride, final long offset) {
        if (index < 0 || index >= MAX_VERTEX_ATTRIBS || size < 1 || size > 4 || stride < 0) {
            error(GL_INVALID_VALUE);
            return;
        }
        if (GlPixels.componentBytes(type) < 0 || (integer && (type == GL_FLOAT || type == GL_HALF_FLOAT))) {
            error(GL_INVALID_ENUM);
            return;
        }
        if (arrayBuffer == null && offset != 0) {
            error(GL_INVALID_OPERATION); // client-side arrays (compatibility profile) are not supported
            return;
        }
        VertexAttribState attrib = vertexArray.attribs[index];
        attrib.size = size;
        attrib.type = type;
        attrib.normalized = normalized;
        attrib.integer = integer;
        attrib.stride = stride;
        attrib.offset = offset;
        attrib.buffer = arrayBuffer;
        attrib.binding = -1;
    }

    public void vertexAttribDivisor(final int index, final int divisor) {
        if (index < 0 || index >= MAX_VERTEX_ATTRIBS) {
            error(GL_INVALID_VALUE);
            return;
        }
        vertexArray.attribs[index].divisor = divisor;
    }

    public void vertexAttrib4f(final int index, final float x, final float y, final float z, final float w) {
        if (index < 0 || index >= MAX_VERTEX_ATTRIBS) {
            error(GL_INVALID_VALUE);
            return;
        }
        currentAttrib[index] = new float[]{x, y, z, w};
    }

    // ---------------------------------------------------------------------------------------------- shaders & programs

    public int createShader(final int type) {
        if (type != GL_VERTEX_SHADER && type != GL_FRAGMENT_SHADER && type != GL_COMPUTE_SHADER) {
            if (type == GL_GEOMETRY_SHADER || type == GL_TESS_CONTROL_SHADER || type == GL_TESS_EVALUATION_SHADER) {
                MetalGLLog.unsupported("shader type 0x" + Integer.toHexString(type));
            }
            error(GL_INVALID_ENUM);
            return 0;
        }
        int name = shaderObjects.reserve();
        shaderObjects.put(name, new Shader(name, type));
        return name;
    }

    private @Nullable Shader shader(final int name) {
        Object object = shaderObjects.get(name);
        if (object instanceof Shader shader) {
            return shader;
        }
        error(object == null ? GL_INVALID_VALUE : GL_INVALID_OPERATION);
        return null;
    }

    private @Nullable Program programObject(final int name) {
        Object object = shaderObjects.get(name);
        if (object instanceof Program program) {
            return program;
        }
        error(object == null ? GL_INVALID_VALUE : GL_INVALID_OPERATION);
        return null;
    }

    public void shaderSource(final int name, final String source) {
        Shader shader = shader(name);
        if (shader != null) {
            shader.source = source;
        }
    }

    public void compileShader(final int name) {
        Shader shader = shader(name);
        if (shader == null) {
            return;
        }
        String log = GlProgramLinker.checkCompile("shader" + name, switch (shader.type) {
            case GL_VERTEX_SHADER -> GlslProgramTranslator.Stage.VERTEX;
            case GL_COMPUTE_SHADER -> GlslProgramTranslator.Stage.COMPUTE;
            default -> GlslProgramTranslator.Stage.FRAGMENT;
        }, shader.source);
        shader.compiled = log == null;
        shader.log = log == null ? "" : log;
    }

    public void deleteShader(final int name) {
        if (name == 0) {
            return;
        }
        Shader shader = shader(name);
        if (shader == null) {
            return;
        }
        if (shader.attachCount > 0) {
            shader.deletePending = true;
        } else {
            shaderObjects.remove(name);
        }
    }

    public int createProgram() {
        int name = shaderObjects.reserve();
        shaderObjects.put(name, new Program(name));
        return name;
    }

    public void attachShader(final int programName, final int shaderName) {
        Program program = programObject(programName);
        Shader shader = shader(shaderName);
        if (program == null || shader == null) {
            return;
        }
        if (program.shaders.contains(shader)) {
            error(GL_INVALID_OPERATION);
            return;
        }
        program.shaders.add(shader);
        shader.attachCount++;
    }

    public void detachShader(final int programName, final int shaderName) {
        Program program = programObject(programName);
        Shader shader = shader(shaderName);
        if (program == null || shader == null) {
            return;
        }
        if (!program.shaders.remove(shader)) {
            error(GL_INVALID_OPERATION);
            return;
        }
        shader.attachCount--;
        if (shader.deletePending && shader.attachCount == 0) {
            shaderObjects.remove(shader.name);
        }
    }

    public void bindAttribLocation(final int programName, final int index, final String name) {
        Program program = programObject(programName);
        if (program == null) {
            return;
        }
        if (index < 0 || index >= MAX_VERTEX_ATTRIBS) {
            error(GL_INVALID_VALUE);
            return;
        }
        if (name.startsWith("gl_")) {
            error(GL_INVALID_OPERATION);
            return;
        }
        program.attributeBindings.put(name, index);
    }

    public void linkProgram(final int name) {
        Program program = programObject(name);
        if (program == null) {
            return;
        }
        program.linked = false;
        Shader vertex = null;
        Shader fragment = null;
        Shader compute = null;
        for (Shader shader : program.shaders) {
            if (shader.type == GL_VERTEX_SHADER) vertex = shader;
            else if (shader.type == GL_FRAGMENT_SHADER) fragment = shader;
            else if (shader.type == GL_COMPUTE_SHADER) compute = shader;
        }
        if (compute != null) {
            linkCompute(program, compute, vertex != null || fragment != null);
            return;
        }
        if (vertex == null || fragment == null || !vertex.compiled || !fragment.compiled) {
            program.log = vertex == null || fragment == null ? "metallium-gl: a program needs a vertex and a fragment shader"
                    : "metallium-gl: attached shader is not compiled";
            return;
        }
        try {
            GlProgramLinker.Linked linked = GlProgramLinker.link("program" + name, vertex.source, fragment.source, program.attributeBindings);
            GlDevice.ProgramStore store = device.createProgram("program" + name,
                    new GlDevice.StageCode(linked.vertex().msl(), linked.vertex().entryPoint()),
                    new GlDevice.StageCode(linked.fragment().msl(), linked.fragment().entryPoint()));
            installLinked(program, linked, store);
            program.log = "";
            program.linked = true;
        } catch (GlProgramLinker.LinkException | GlDevice.DeviceException e) {
            program.log = String.valueOf(e.getMessage());
            MetalGLLog.warn("link of program " + name + " failed: " + program.log);
        }
    }

    private void linkCompute(final Program program, final Shader compute, final boolean mixed) {
        if (mixed || !compute.compiled) {
            program.log = mixed ? "metallium-gl: a compute program cannot have other stages" : "metallium-gl: attached shader is not compiled";
            return;
        }
        try {
            GlProgramLinker.Linked linked = GlProgramLinker.linkCompute("program" + program.name, compute.source);
            GlDevice.ProgramStore store = device.createComputeProgram("program" + program.name,
                    new GlDevice.StageCode(linked.vertex().msl(), linked.vertex().entryPoint()), linked.localSize());
            installLinked(program, linked, store);
            program.log = "";
            program.linked = true;
        } catch (GlProgramLinker.LinkException | GlDevice.DeviceException e) {
            program.log = String.valueOf(e.getMessage());
            MetalGLLog.warn("link of compute program " + program.name + " failed: " + program.log);
        }
    }

    private void installLinked(final Program program, final GlProgramLinker.Linked linked, final GlDevice.ProgramStore store) {
        if (program.store != null) {
            device.release(program.store);
        }
        freeUniforms(program);
        program.linkedProgram = linked;
        program.store = store;
        program.vertexUniforms = linked.vertexBlockSize() > 0 ? MemoryUtil.memCalloc(linked.vertexBlockSize()).order(ByteOrder.nativeOrder()) : null;
        program.fragmentUniforms = linked.fragmentBlockSize() > 0 ? MemoryUtil.memCalloc(linked.fragmentBlockSize()).order(ByteOrder.nativeOrder()) : null;
        int locations = 0;
        for (GlProgramLinker.Uniform u : linked.uniforms()) locations = Math.max(locations, u.location() + u.locations());
        for (GlProgramLinker.Sampler s : linked.samplers()) locations = Math.max(locations, s.location() + 1);
        program.uniformByLocation = new GlProgramLinker.Uniform[locations];
        program.samplerByLocation = new GlProgramLinker.Sampler[locations];
        for (GlProgramLinker.Uniform u : linked.uniforms()) {
            for (int i = 0; i < u.locations(); i++) program.uniformByLocation[u.location() + i] = u;
        }
        for (GlProgramLinker.Sampler s : linked.samplers()) program.samplerByLocation[s.location()] = s;
        program.samplerUnits = new int[locations];
        for (GlProgramLinker.Sampler s : linked.samplers()) program.samplerUnits[s.location()] = Math.min(s.unit(), MAX_TEXTURE_UNITS - 1);
    }

    private static void freeUniforms(final Program program) {
        if (program.vertexUniforms != null) MemoryUtil.memFree(program.vertexUniforms);
        if (program.fragmentUniforms != null) MemoryUtil.memFree(program.fragmentUniforms);
        program.vertexUniforms = null;
        program.fragmentUniforms = null;
    }

    public void useProgram(final int name) {
        if (name == 0) {
            releaseIfPending(program, null);
            program = null;
            return;
        }
        Program next = programObject(name);
        if (next == null) {
            return;
        }
        if (!next.linked) {
            error(GL_INVALID_OPERATION);
            return;
        }
        Program previous = program;
        program = next;
        releaseIfPending(previous, next);
    }

    private void releaseIfPending(final @Nullable Program previous, final @Nullable Program next) {
        if (previous != null && previous != next && previous.deletePending) {
            destroyProgram(previous);
        }
    }

    public void deleteProgram(final int name) {
        if (name == 0) {
            return;
        }
        Program target = programObject(name);
        if (target == null) {
            return;
        }
        if (program == target) {
            target.deletePending = true;
        } else {
            destroyProgram(target);
        }
    }

    private void destroyProgram(final Program target) {
        shaderObjects.remove(target.name);
        for (Shader shader : new ArrayList<>(target.shaders)) {
            shader.attachCount--;
            if (shader.deletePending && shader.attachCount == 0) {
                shaderObjects.remove(shader.name);
            }
        }
        target.shaders.clear();
        if (target.store != null) {
            device.release(target.store);
            target.store = null;
        }
        freeUniforms(target);
    }

    public boolean isProgram(final int name) {
        return shaderObjects.get(name) instanceof Program;
    }

    public boolean isShader(final int name) {
        return shaderObjects.get(name) instanceof Shader;
    }

    public int getShaderi(final int name, final int pname) {
        Shader shader = shader(name);
        if (shader == null) {
            return 0;
        }
        return switch (pname) {
            case GL_SHADER_TYPE -> shader.type;
            case GL_COMPILE_STATUS -> shader.compiled ? GL_TRUE : GL_FALSE;
            case GL_DELETE_STATUS -> shader.deletePending ? GL_TRUE : GL_FALSE;
            case GL_INFO_LOG_LENGTH -> shader.log.isEmpty() ? 0 : shader.log.length() + 1;
            case GL_SHADER_SOURCE_LENGTH -> shader.source.isEmpty() ? 0 : shader.source.length() + 1;
            default -> {
                error(GL_INVALID_ENUM);
                yield 0;
            }
        };
    }

    public String getShaderInfoLog(final int name) {
        Shader shader = shader(name);
        return shader == null ? "" : shader.log;
    }

    public int getProgrami(final int name, final int pname) {
        Program program = programObject(name);
        if (program == null) {
            return 0;
        }
        GlProgramLinker.Linked linked = program.linkedProgram;
        return switch (pname) {
            case GL_LINK_STATUS -> program.linked ? GL_TRUE : GL_FALSE;
            case GL_VALIDATE_STATUS -> program.validated ? GL_TRUE : GL_FALSE;
            case GL_DELETE_STATUS -> program.deletePending ? GL_TRUE : GL_FALSE;
            case GL_INFO_LOG_LENGTH -> program.log.isEmpty() ? 0 : program.log.length() + 1;
            case GL_ATTACHED_SHADERS -> program.shaders.size();
            case GL_ACTIVE_UNIFORMS -> linked == null ? 0 : linked.uniforms().size() + linked.samplers().size();
            case GL_ACTIVE_ATTRIBUTES -> linked == null ? 0 : linked.attributes().size();
            case GL_COMPUTE_WORK_GROUP_SIZE -> linked == null || linked.localSize() == null ? 0 : linked.localSize()[0];
            default -> {
                error(GL_INVALID_ENUM);
                yield 0;
            }
        };
    }

    public String getProgramInfoLog(final int name) {
        Program program = programObject(name);
        return program == null ? "" : program.log;
    }

    public void validateProgram(final int name) {
        Program program = programObject(name);
        if (program != null) {
            program.validated = program.linked;
        }
    }

    public int getAttribLocation(final int programName, final String name) {
        Program program = programObject(programName);
        if (program == null) {
            return -1;
        }
        if (!program.linked) {
            error(GL_INVALID_OPERATION);
            return -1;
        }
        return program.linkedProgram.attributes().getOrDefault(name, -1);
    }

    public int getUniformLocation(final int programName, final String name) {
        Program program = programObject(programName);
        if (program == null) {
            return -1;
        }
        if (!program.linked) {
            error(GL_INVALID_OPERATION);
            return -1;
        }
        String base = name;
        int element = 0;
        int bracket = name.indexOf('[');
        if (bracket > 0 && name.endsWith("]")) {
            base = name.substring(0, bracket);
            try {
                element = Integer.parseInt(name.substring(bracket + 1, name.length() - 1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        for (GlProgramLinker.Uniform u : program.linkedProgram.uniforms()) {
            if (u.name().equals(base)) {
                return element < u.locations() && (element == 0 || u.type().arraySize() > 0) ? u.location() + element : -1;
            }
        }
        for (GlProgramLinker.Sampler s : program.linkedProgram.samplers()) {
            if (s.name().equals(base) && element == 0) {
                return s.location();
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------------------------------------- uniforms

    /**
     * glUniform*: {@code count} elements of {@code components} values each; {@code ints} or {@code floats} holds them.
     * Writes into each stage's default-block bytes per std140 layout.
     */
    public void uniform(final int location, final int components, final int count, final boolean integer, final boolean unsigned,
                        final int @Nullable [] ints, final float @Nullable [] floats) {
        Program program = this.program;
        if (program == null) {
            error(GL_INVALID_OPERATION);
            return;
        }
        if (location == -1) {
            return;
        }
        if (location < 0 || location >= program.uniformByLocation.length) {
            error(GL_INVALID_OPERATION);
            return;
        }
        GlProgramLinker.Sampler sampler = program.samplerByLocation[location];
        if (sampler != null) {
            if (!integer || unsigned || components != 1 || count != 1 || ints == null) {
                error(GL_INVALID_OPERATION);
                return;
            }
            if (ints[0] < 0 || ints[0] >= MAX_TEXTURE_UNITS) {
                error(GL_INVALID_VALUE);
                return;
            }
            program.samplerUnits[location] = ints[0];
            return;
        }
        GlProgramLinker.Uniform uniform = program.uniformByLocation[location];
        if (uniform == null) {
            error(GL_INVALID_OPERATION);
            return;
        }
        BlockMember type = uniform.type();
        boolean typeOk = switch (type.scalar()) {
            case FLOAT -> !integer;
            case INT -> integer && !unsigned;
            case UINT -> integer && unsigned;
            case BOOL -> true;
            case OTHER -> false;
        };
        if (!typeOk || type.columns() > 1 || type.vectorSize() != components) {
            error(GL_INVALID_OPERATION);
            return;
        }
        int element = location - uniform.location();
        int available = uniform.locations() - element;
        if (count > 1 && type.arraySize() == 0) {
            error(GL_INVALID_OPERATION);
            return;
        }
        int n = Math.min(count, available);
        for (GlProgramLinker.StagePlacement placement : new GlProgramLinker.StagePlacement[]{uniform.vertex(), uniform.fragment()}) {
            if (placement == null) continue;
            ByteBuffer block = placement == uniform.vertex() ? program.vertexUniforms : program.fragmentUniforms;
            int stride = placement.member().arrayStride() > 0 ? placement.member().arrayStride() : components * 4;
            for (int e = 0; e < n; e++) {
                int at = placement.offset() + (element + e) * stride;
                for (int c = 0; c < components; c++) {
                    int i = e * components + c;
                    if (type.scalar() == ScalarType.BOOL) {
                        block.putInt(at + c * 4, (ints != null ? ints[i] != 0 : floats[i] != 0f) ? 1 : 0);
                    } else if (integer) {
                        block.putInt(at + c * 4, ints[i]);
                    } else {
                        block.putFloat(at + c * 4, floats[i]);
                    }
                }
            }
        }
    }

    /** glUniformMatrix{C}x{R}fv: {@code values} holds {@code count} column-major (or row-major if transposed) matrices. */
    public void uniformMatrix(final int location, final int columns, final int rows, final int count, final boolean transpose, final float[] values) {
        Program program = this.program;
        if (program == null) {
            error(GL_INVALID_OPERATION);
            return;
        }
        if (location == -1) {
            return;
        }
        if (location < 0 || location >= program.uniformByLocation.length || program.uniformByLocation[location] == null) {
            error(GL_INVALID_OPERATION);
            return;
        }
        GlProgramLinker.Uniform uniform = program.uniformByLocation[location];
        BlockMember type = uniform.type();
        if (type.scalar() != ScalarType.FLOAT || type.columns() != columns || type.vectorSize() != rows) {
            error(GL_INVALID_OPERATION);
            return;
        }
        int element = location - uniform.location();
        int n = Math.min(count, uniform.locations() - element);
        for (GlProgramLinker.StagePlacement placement : new GlProgramLinker.StagePlacement[]{uniform.vertex(), uniform.fragment()}) {
            if (placement == null) continue;
            ByteBuffer block = placement == uniform.vertex() ? program.vertexUniforms : program.fragmentUniforms;
            int matrixStride = placement.member().matrixStride() > 0 ? placement.member().matrixStride() : 16;
            int arrayStride = placement.member().arrayStride() > 0 ? placement.member().arrayStride() : matrixStride * columns;
            for (int e = 0; e < n; e++) {
                int base = placement.offset() + (element + e) * arrayStride;
                for (int c = 0; c < columns; c++) {
                    for (int r = 0; r < rows; r++) {
                        int src = e * columns * rows + (transpose ? r * columns + c : c * rows + r);
                        block.putFloat(base + c * matrixStride + r * 4, values[src]);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- fixed-function state

    public void enable(final int cap, final boolean on) {
        switch (cap) {
            case GL_BLEND -> Arrays.fill(blendEnabled, on);
            case GL_DEPTH_TEST -> depthTest = on;
            case GL_CULL_FACE -> cullFace = on;
            case GL_SCISSOR_TEST -> scissorTest = on;
            case GL_POLYGON_OFFSET_FILL -> polygonOffsetFill = on;
            case GL_STENCIL_TEST -> stencilTest = on;
            case GL_MULTISAMPLE, GL_DITHER, GL_LINE_SMOOTH, GL_POLYGON_SMOOTH, GL_PROGRAM_POINT_SIZE, GL_TEXTURE_CUBE_MAP_SEAMLESS,
                 GL_FRAMEBUFFER_SRGB, GL_DEBUG_OUTPUT, GL_DEBUG_OUTPUT_SYNCHRONOUS, GL_PRIMITIVE_RESTART_FIXED_INDEX, GL_DEPTH_CLAMP -> {
                // No-op on Metal (always on / handled elsewhere) or diagnostics only.
            }
            default -> {
                MetalGLLog.unsupported("glEnable/glDisable cap 0x" + Integer.toHexString(cap));
                error(GL_INVALID_ENUM);
            }
        }
    }

    public void enablei(final int cap, final int index, final boolean on) {
        if (cap != GL_BLEND) {
            MetalGLLog.unsupported("glEnablei cap 0x" + Integer.toHexString(cap));
            error(GL_INVALID_ENUM);
            return;
        }
        if (index < 0 || index >= MAX_COLOR_ATTACHMENTS) {
            error(GL_INVALID_VALUE);
            return;
        }
        blendEnabled[index] = on;
    }

    public boolean isEnabled(final int cap) {
        return switch (cap) {
            case GL_BLEND -> blendEnabled[0];
            case GL_DEPTH_TEST -> depthTest;
            case GL_CULL_FACE -> cullFace;
            case GL_SCISSOR_TEST -> scissorTest;
            case GL_POLYGON_OFFSET_FILL -> polygonOffsetFill;
            case GL_STENCIL_TEST -> stencilTest;
            default -> false;
        };
    }

    public void viewport(final int x, final int y, final int width, final int height) {
        if (width < 0 || height < 0) {
            error(GL_INVALID_VALUE);
            return;
        }
        viewport[0] = x;
        viewport[1] = y;
        viewport[2] = width;
        viewport[3] = height;
        viewportInitialised = true;
    }

    public void scissor(final int x, final int y, final int width, final int height) {
        if (width < 0 || height < 0) {
            error(GL_INVALID_VALUE);
            return;
        }
        scissorBox[0] = x;
        scissorBox[1] = y;
        scissorBox[2] = width;
        scissorBox[3] = height;
    }

    public void blendFuncSeparate(final int buffer, final int srcRgb, final int dstRgb, final int srcAlpha, final int dstAlpha) {
        if (!isBlendFactor(srcRgb) || !isBlendFactor(dstRgb) || !isBlendFactor(srcAlpha) || !isBlendFactor(dstAlpha)) {
            error(GL_INVALID_ENUM);
            return;
        }
        int from = buffer < 0 ? 0 : buffer;
        int to = buffer < 0 ? MAX_COLOR_ATTACHMENTS : buffer + 1;
        for (int i = from; i < to; i++) {
            blendFunc[i] = new int[]{srcRgb, dstRgb, srcAlpha, dstAlpha};
        }
    }

    public void blendEquationSeparate(final int buffer, final int rgb, final int alpha) {
        if (!isBlendEquation(rgb) || !isBlendEquation(alpha)) {
            error(GL_INVALID_ENUM);
            return;
        }
        int from = buffer < 0 ? 0 : buffer;
        int to = buffer < 0 ? MAX_COLOR_ATTACHMENTS : buffer + 1;
        for (int i = from; i < to; i++) {
            blendEquation[i] = new int[]{rgb, alpha};
        }
    }

    private static boolean isBlendFactor(final int f) {
        return switch (f) {
            case GL_ZERO, GL_ONE, GL_SRC_COLOR, GL_ONE_MINUS_SRC_COLOR, GL_DST_COLOR, GL_ONE_MINUS_DST_COLOR, GL_SRC_ALPHA,
                 GL_ONE_MINUS_SRC_ALPHA, GL_DST_ALPHA, GL_ONE_MINUS_DST_ALPHA, GL_CONSTANT_COLOR, GL_ONE_MINUS_CONSTANT_COLOR,
                 GL_CONSTANT_ALPHA, GL_ONE_MINUS_CONSTANT_ALPHA, GL_SRC_ALPHA_SATURATE, GL_SRC1_COLOR, GL_ONE_MINUS_SRC1_COLOR,
                 GL_SRC1_ALPHA, GL_ONE_MINUS_SRC1_ALPHA -> true;
            default -> false;
        };
    }

    private static boolean isBlendEquation(final int e) {
        return e == GL_FUNC_ADD || e == GL_FUNC_SUBTRACT || e == GL_FUNC_REVERSE_SUBTRACT || e == GL_MIN || e == GL_MAX;
    }

    public void colorMask(final int buffer, final boolean r, final boolean g, final boolean b, final boolean a) {
        int from = buffer < 0 ? 0 : buffer;
        int to = buffer < 0 ? MAX_COLOR_ATTACHMENTS : buffer + 1;
        if (buffer >= MAX_COLOR_ATTACHMENTS) {
            error(GL_INVALID_VALUE);
            return;
        }
        for (int i = from; i < to; i++) {
            colorMask[i] = new boolean[]{r, g, b, a};
        }
    }

    public void stencilFunc(final int func, final int ref, final int mask) {
        if (func < GL_NEVER || func > GL_ALWAYS) {
            error(GL_INVALID_ENUM);
            return;
        }
        stencilFunc = func;
        stencilRef = ref;
        stencilValueMask = mask;
    }

    public void stencilOp(final int sfail, final int dpfail, final int dppass) {
        for (int op : new int[]{sfail, dpfail, dppass}) {
            switch (op) {
                case GL_KEEP, GL_ZERO, GL_REPLACE, GL_INCR, GL_INCR_WRAP, GL_DECR, GL_DECR_WRAP, GL_INVERT -> {
                }
                default -> {
                    error(GL_INVALID_ENUM);
                    return;
                }
            }
        }
        stencilFail = sfail;
        stencilDepthFail = dpfail;
        stencilDepthPass = dppass;
    }

    public void stencilMask(final int mask) {
        stencilWriteMask = mask;
    }

    public void depthFunc(final int func) {
        if (func < GL_NEVER || func > GL_ALWAYS) {
            error(GL_INVALID_ENUM);
            return;
        }
        depthFunc = func;
    }

    public void cullFace(final int mode) {
        if (mode != GL_FRONT && mode != GL_BACK && mode != GL_FRONT_AND_BACK) {
            error(GL_INVALID_ENUM);
            return;
        }
        cullFaceMode = mode;
    }

    public void frontFace(final int mode) {
        if (mode != GL_CW && mode != GL_CCW) {
            error(GL_INVALID_ENUM);
            return;
        }
        frontFace = mode;
    }

    public void polygonMode(final int face, final int mode) {
        if (face != GL_FRONT_AND_BACK || (mode != GL_FILL && mode != GL_LINE && mode != GL_POINT)) {
            error(GL_INVALID_ENUM);
            return;
        }
        if (mode == GL_POINT) {
            MetalGLLog.unsupported("glPolygonMode GL_POINT");
        }
        polygonMode = mode;
    }

    // ---------------------------------------------------------------------------------------------- clear & draw

    public void clear(final int mask) {
        if ((mask & ~(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT)) != 0) {
            error(GL_INVALID_VALUE);
            return;
        }
        GlDevice.RenderTargets targets = drawTargets();
        if (targets == null || mask == 0) {
            return;
        }
        boolean color = (mask & GL_COLOR_BUFFER_BIT) != 0;
        boolean depth = (mask & GL_DEPTH_BUFFER_BIT) != 0 && depthMask && targets.depth() != null;
        boolean stencil = (mask & GL_STENCIL_BUFFER_BIT) != 0 && targets.depthHasStencil();
        device.clear(targets, new GlDevice.ClearOp(color, clearColor.clone(), colorMask[0].clone(), depth, clearDepth,
                stencil, clearStencil, scissorTest ? scissorBox.clone() : null));
    }

    private static boolean isPrimitive(final int mode) {
        return switch (mode) {
            case GL_POINTS, GL_LINES, GL_LINE_STRIP, GL_LINE_LOOP, GL_TRIANGLES, GL_TRIANGLE_STRIP, GL_TRIANGLE_FAN, GL_QUADS -> true;
            default -> false;
        };
    }

    public void drawArrays(final int mode, final int first, final int count, final int instances, final int baseInstance) {
        if (!isPrimitive(mode)) {
            error(GL_INVALID_ENUM);
            return;
        }
        if (first < 0 || count < 0 || instances < 0) {
            error(GL_INVALID_VALUE);
            return;
        }
        if (count == 0 || instances == 0) {
            return;
        }
        if (needsIndexConversion(mode)) {
            int[] indices = new int[count];
            for (int i = 0; i < count; i++) indices[i] = first + i;
            drawConverted(mode, indices, 0, instances, baseInstance);
            return;
        }
        submitDraw(mode, null, 0, false, first, count, instances, 0, baseInstance);
    }

    public void drawElements(final int mode, final int count, final int type, final long offset, final int instances, final int baseVertex) {
        drawElementsBase(mode, count, type, offset, instances, baseVertex, 0);
    }

    /** glDrawElements* with base vertex and base instance (GL 4.2). */
    public void drawElementsBase(final int mode, final int count, final int type, final long offset, final int instances, final int baseVertex,
                                 final int baseInstance) {
        if (!isPrimitive(mode)) {
            error(GL_INVALID_ENUM);
            return;
        }
        if (type != GL_UNSIGNED_BYTE && type != GL_UNSIGNED_SHORT && type != GL_UNSIGNED_INT) {
            error(GL_INVALID_ENUM);
            return;
        }
        if (count < 0 || instances < 0) {
            error(GL_INVALID_VALUE);
            return;
        }
        Buffer elements = vertexArray.elementBuffer;
        if (elements == null || elements.store == null) {
            error(GL_INVALID_OPERATION); // client-side index arrays come in through drawElementsClient
            return;
        }
        if (count == 0 || instances == 0) {
            return;
        }
        int size = GlPixels.componentBytes(type);
        if (offset < 0 || offset + (long) count * size > elements.size) {
            error(GL_INVALID_OPERATION);
            return;
        }
        if (type == GL_UNSIGNED_BYTE || needsIndexConversion(mode) || offset % size != 0) {
            drawConverted(mode, readIndices(elements.store.contents(), offset, count, type), baseVertex, instances, baseInstance);
            return;
        }
        submitDraw(mode, elements.store, offset, type == GL_UNSIGNED_INT, 0, count, instances, baseVertex, baseInstance);
    }

    /** glDrawElements with indices in client memory (LWJGL's buffer overloads). */
    public void drawElementsClient(final int mode, final int type, final ByteBuffer indices) {
        if (type != GL_UNSIGNED_BYTE && type != GL_UNSIGNED_SHORT && type != GL_UNSIGNED_INT) {
            error(GL_INVALID_ENUM);
            return;
        }
        if (!isPrimitive(mode)) {
            error(GL_INVALID_ENUM);
            return;
        }
        int count = indices.remaining() / GlPixels.componentBytes(type);
        if (count == 0) {
            return;
        }
        drawConverted(mode, readIndices(indices, 0, count, type), 0, 1, 0);
    }

    private static int[] readIndices(final ByteBuffer source, final long offset, final int count, final int type) {
        ByteBuffer src = source.duplicate().order(ByteOrder.nativeOrder());
        int base = source.position() + (int) offset;
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = switch (type) {
                case GL_UNSIGNED_BYTE -> src.get(base + i) & 0xFF;
                case GL_UNSIGNED_SHORT -> src.getShort(base + i * 2) & 0xFFFF;
                default -> src.getInt(base + i * 4);
            };
        }
        return out;
    }

    private static boolean needsIndexConversion(final int mode) {
        return mode == GL_TRIANGLE_FAN || mode == GL_QUADS || mode == GL_LINE_LOOP;
    }

    /** Fans, quads and line loops have no Metal equivalent: expanded to triangle/line lists in a temporary index buffer. */
    private void drawConverted(final int mode, final int[] indices, final int baseVertex, final int instances, final int baseInstance) {
        int[] expanded;
        int primitive;
        switch (mode) {
            case GL_TRIANGLE_FAN -> {
                int triangles = Math.max(0, indices.length - 2);
                expanded = new int[triangles * 3];
                for (int t = 0; t < triangles; t++) {
                    expanded[t * 3] = indices[0];
                    expanded[t * 3 + 1] = indices[t + 1];
                    expanded[t * 3 + 2] = indices[t + 2];
                }
                primitive = GL_TRIANGLES;
            }
            case GL_QUADS -> {
                int quads = indices.length / 4;
                expanded = new int[quads * 6];
                for (int q = 0; q < quads; q++) {
                    int[] v = {indices[q * 4], indices[q * 4 + 1], indices[q * 4 + 2], indices[q * 4 + 3]};
                    System.arraycopy(new int[]{v[0], v[1], v[2], v[0], v[2], v[3]}, 0, expanded, q * 6, 6);
                }
                primitive = GL_TRIANGLES;
            }
            case GL_LINE_LOOP -> {
                expanded = indices.length < 2 ? new int[0] : Arrays.copyOf(indices, indices.length + 1);
                if (expanded.length > 0) expanded[indices.length] = indices[0];
                primitive = GL_LINE_STRIP;
            }
            default -> {
                expanded = indices;
                primitive = mode;
            }
        }
        if (expanded.length == 0) {
            return;
        }
        GlDevice.BufferStore temp = device.createBuffer((long) expanded.length * 4);
        temp.contents().order(ByteOrder.nativeOrder()).asIntBuffer().put(expanded);
        try {
            submitDraw(primitive, temp, 0, true, 0, expanded.length, instances, baseVertex, baseInstance);
        } finally {
            device.release(temp);
        }
    }

    private void submitDraw(final int mode, final GlDevice.@Nullable BufferStore indexBuffer, final long indexOffset, final boolean index32,
                            final int first, final int count, final int instances, final int baseVertex, final int baseInstance) {
        GlDevice.DrawOp op = resolveDraw(mode, indexBuffer, indexOffset, index32, first, count, instances, baseVertex, baseInstance);
        if (op != null) {
            device.draw(op);
        }
    }

    /** Resolves the current state into a draw; null (GL error set where GL sets one) if nothing can be drawn. */
    GlDevice.@Nullable DrawOp resolveDraw(final int mode, final GlDevice.@Nullable BufferStore indexBuffer, final long indexOffset, final boolean index32,
                                          final int first, final int count, final int instances, final int baseVertex, final int baseInstance) {
        Program program = this.program;
        if (program == null || !program.linked || program.store == null || program.linkedProgram.compute()) {
            error(GL_INVALID_OPERATION);
            return null;
        }
        GlDevice.RenderTargets targets = drawTargets();
        if (targets == null) {
            return null;
        }
        GlProgramLinker.Linked linked = program.linkedProgram;
        List<GlDevice.VertexAttrib> attributes = new ArrayList<>();
        for (int location : linked.attributes().values()) {
            if (location < 0 || location >= MAX_VERTEX_ATTRIBS) continue;
            VertexAttribState state = vertexArray.attribs[location];
            if (state.enabled) {
                if (state.buffer == null || state.buffer.store == null) {
                    error(GL_INVALID_OPERATION);
                    return null;
                }
                int stride = state.stride != 0 ? state.stride : state.size * GlPixels.componentBytes(state.type);
                attributes.add(new GlDevice.VertexAttrib(location, state.size, state.type, state.normalized, state.integer,
                        state.buffer.store, state.offset, stride, state.divisor, null));
            } else {
                attributes.add(new GlDevice.VertexAttrib(location, 4, GL_FLOAT, false, false, null, 0, 16, 0, currentAttrib[location].clone()));
            }
        }
        writeDepthRange(program);
        GlDevice.StageBindings vertex = bindings.resolve(program, linked.vertexInterface(), program.vertexUniforms, linked.vertexBlockIndex(),
                bindings.textures(program, true));
        GlDevice.StageBindings fragment = bindings.resolve(program, linked.fragmentInterface(), program.fragmentUniforms, linked.fragmentBlockIndex(),
                bindings.textures(program, false));
        return new GlDevice.DrawOp(program.store, targets, mode, attributes.toArray(GlDevice.VertexAttrib[]::new),
                indexBuffer, indexOffset, index32, first, count, instances, baseVertex, baseInstance, vertex, fragment, rasterState(targets));
    }

    /** Fills the uniforms that stand in for GL's gl_DepthRange (see GlProgramLinker.glCompat). */
    private void writeDepthRange(final Program program) {
        for (GlProgramLinker.Uniform u : program.linkedProgram.uniforms()) {
            if (!u.name().startsWith("metallium_DepthRange")) continue;
            float value = switch (u.name()) {
                case GlProgramLinker.DEPTH_RANGE_NEAR -> depthNear;
                case GlProgramLinker.DEPTH_RANGE_FAR -> depthFar;
                default -> depthFar - depthNear;
            };
            uniform(u.location(), 1, 1, false, false, null, new float[]{value});
        }
    }

    private GlDevice.RasterState rasterState(final GlDevice.RenderTargets targets) {
        if (!viewportInitialised) {
            // GL initialises the viewport to the window size when the context is first made current.
            viewport[2] = targets.width();
            viewport[3] = targets.height();
            scissorBox[2] = targets.width();
            scissorBox[3] = targets.height();
            viewportInitialised = true;
        }
        int attachments = targets.colors().length;
        GlDevice.BlendState[] blend = new GlDevice.BlendState[attachments];
        for (int i = 0; i < attachments; i++) {
            int[] f = blendFunc[i];
            int[] e = blendEquation[i];
            boolean[] m = colorMask[i];
            blend[i] = new GlDevice.BlendState(blendEnabled[i], f[0], f[1], f[2], f[3], e[0], e[1], m[0], m[1], m[2], m[3]);
        }
        return new GlDevice.RasterState(viewport.clone(), depthNear, depthFar, scissorTest ? scissorBox.clone() : null, blend,
                blendColor.clone(), depthTest, depthFunc, depthTest && depthMask, cullFace ? cullFaceMode : GL_NONE,
                frontFace == GL_CCW, polygonOffsetFill, polygonOffsetFactor, polygonOffsetUnits, polygonMode == GL_LINE,
                stencilTest && targets.depthHasStencil()
                        ? new GlDevice.StencilState(true, stencilFunc, stencilRef, stencilValueMask, stencilWriteMask, stencilFail, stencilDepthFail, stencilDepthPass)
                        : GlDevice.StencilState.DISABLED);
    }

    /** glReadPixels of the read framebuffer into RGBA8 (the only format supported so far). */
    public void readPixels(final int x, final int y, final int width, final int height, final int format, final int type, final ByteBuffer out) {
        if (format != GL_RGBA || type != GL_UNSIGNED_BYTE) {
            MetalGLLog.unsupported("glReadPixels format 0x" + Integer.toHexString(format) + "/0x" + Integer.toHexString(type));
            error(GL_INVALID_OPERATION);
            return;
        }
        if (width < 0 || height < 0 || out.remaining() < width * height * 4) {
            error(GL_INVALID_VALUE);
            return;
        }
        Framebuffer fb = readFramebuffer;
        GlDevice.RenderTargets targets;
        int attachment = 0;
        if (fb == null) {
            targets = device.defaultFramebuffer();
        } else {
            int index = fb.readBuffer - GL_COLOR_ATTACHMENT0;
            if (index < 0 || index >= MAX_COLOR_ATTACHMENTS || fb.color[index] == null || fb.color[index].texture().store == null) {
                error(GL_INVALID_OPERATION);
                return;
            }
            Attachment a = fb.color[index];
            targets = new GlDevice.RenderTargets(new GlDevice.TextureStore[]{a.texture().store}, new int[]{a.level()}, null, 0, false);
        }
        if (targets == null || !device.readPixels(targets, attachment, x, y, width, height, out)) {
            MetalGLLog.unsupported("glReadPixels in this context");
        }
    }

    // ---------------------------------------------------------------------------------------------- sync

    public long fenceSync(final int condition, final int flags) {
        if (condition != GL_SYNC_GPU_COMMANDS_COMPLETE || flags != 0) {
            error(condition != GL_SYNC_GPU_COMMANDS_COMPLETE ? GL_INVALID_ENUM : GL_INVALID_VALUE);
            return 0;
        }
        long handle = nextSync++;
        syncs.put(handle, new Sync(device.fence()));
        return handle;
    }

    public int clientWaitSync(final long handle, final int flags, final long timeoutNs) {
        Sync sync = syncs.get(handle);
        if (sync == null) {
            error(GL_INVALID_VALUE);
            return GL_WAIT_FAILED;
        }
        if (sync.signaled || sync.use.isComplete()) {
            sync.signaled = true;
            return GL_ALREADY_SIGNALED;
        }
        if (timeoutNs > 0 && sync.use.await(timeoutNs)) {
            sync.signaled = true;
            return GL_CONDITION_SATISFIED;
        }
        return GL_TIMEOUT_EXPIRED;
    }

    public int getSyncStatus(final long handle) {
        Sync sync = syncs.get(handle);
        if (sync == null) {
            error(GL_INVALID_VALUE);
            return 0;
        }
        if (!sync.signaled && sync.use.isComplete()) {
            sync.signaled = true;
        }
        return sync.signaled ? GL_SIGNALED : GL_UNSIGNALED;
    }

    public void deleteSync(final long handle) {
        if (handle != 0 && syncs.remove(handle) == null) {
            error(GL_INVALID_VALUE);
        }
    }

    public boolean isSync(final long handle) {
        return syncs.containsKey(handle);
    }

    // ---------------------------------------------------------------------------------------------- queries

    public int getInteger(final int pname, final int[] out) {
        int n = 1;
        switch (pname) {
            case GL_MAJOR_VERSION -> out[0] = 4;
            case GL_MINOR_VERSION -> out[0] = 6;
            case GL_NUM_EXTENSIONS -> out[0] = MetalGLStrings.EXTENSIONS.size();
            case GL_CONTEXT_PROFILE_MASK -> out[0] = GL_CONTEXT_CORE_PROFILE_BIT;
            case GL_CONTEXT_FLAGS -> out[0] = 0;
            case GL_MAX_TEXTURE_SIZE, GL_MAX_RENDERBUFFER_SIZE -> out[0] = MAX_TEXTURE_SIZE;
            case GL_MAX_VERTEX_ATTRIBS -> out[0] = MAX_VERTEX_ATTRIBS;
            case GL_MAX_TEXTURE_IMAGE_UNITS, GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS -> out[0] = 16;
            case GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS -> out[0] = MAX_TEXTURE_UNITS;
            case GL_MAX_DRAW_BUFFERS, GL_MAX_COLOR_ATTACHMENTS -> out[0] = MAX_COLOR_ATTACHMENTS;
            case GL_MAX_VIEWPORT_DIMS -> {
                out[0] = MAX_TEXTURE_SIZE;
                out[1] = MAX_TEXTURE_SIZE;
                n = 2;
            }
            case GL_MAX_UNIFORM_BLOCK_SIZE -> out[0] = 65536;
            case GL_MAX_VERTEX_UNIFORM_COMPONENTS, GL_MAX_FRAGMENT_UNIFORM_COMPONENTS -> out[0] = 4096;
            case GL_MAX_ELEMENTS_VERTICES, GL_MAX_ELEMENTS_INDICES -> out[0] = 1 << 24;
            case GL_VIEWPORT -> {
                System.arraycopy(viewport, 0, out, 0, 4);
                n = 4;
            }
            case GL_SCISSOR_BOX -> {
                System.arraycopy(scissorBox, 0, out, 0, 4);
                n = 4;
            }
            case GL_ARRAY_BUFFER_BINDING -> out[0] = arrayBuffer == null ? 0 : arrayBuffer.name;
            case GL_ELEMENT_ARRAY_BUFFER_BINDING -> out[0] = vertexArray.elementBuffer == null ? 0 : vertexArray.elementBuffer.name;
            case GL_UNIFORM_BUFFER_BINDING -> out[0] = uniformBuffer == null ? 0 : uniformBuffer.name;
            case GL_PIXEL_UNPACK_BUFFER_BINDING -> out[0] = pixelUnpackBuffer == null ? 0 : pixelUnpackBuffer.name;
            case GL_VERTEX_ARRAY_BINDING -> out[0] = vertexArray.name;
            case GL_CURRENT_PROGRAM -> out[0] = program == null ? 0 : program.name;
            case GL_ACTIVE_TEXTURE -> out[0] = GL_TEXTURE0 + activeTexture;
            case GL_TEXTURE_BINDING_2D -> out[0] = bound2D[activeTexture] == null ? 0 : bound2D[activeTexture].name;
            case GL_DRAW_FRAMEBUFFER_BINDING -> out[0] = drawFramebuffer == null ? 0 : drawFramebuffer.name;
            case GL_READ_FRAMEBUFFER_BINDING -> out[0] = readFramebuffer == null ? 0 : readFramebuffer.name;
            case GL_RENDERBUFFER_BINDING -> out[0] = boundRenderbuffer == null ? 0 : boundRenderbuffer.name;
            case GL_UNPACK_ALIGNMENT -> out[0] = unpack.alignment();
            case GL_UNPACK_ROW_LENGTH -> out[0] = unpack.rowLength();
            case GL_PACK_ALIGNMENT -> out[0] = packAlignment;
            case GL_BLEND_SRC_RGB -> out[0] = blendFunc[0][0];
            case GL_BLEND_DST_RGB -> out[0] = blendFunc[0][1];
            case GL_BLEND_SRC_ALPHA -> out[0] = blendFunc[0][2];
            case GL_BLEND_DST_ALPHA -> out[0] = blendFunc[0][3];
            case GL_BLEND_EQUATION_RGB -> out[0] = blendEquation[0][0];
            case GL_BLEND_EQUATION_ALPHA -> out[0] = blendEquation[0][1];
            case GL_DEPTH_FUNC -> out[0] = depthFunc;
            case GL_CULL_FACE_MODE -> out[0] = cullFaceMode;
            case GL_FRONT_FACE -> out[0] = frontFace;
            case GL_DEPTH_WRITEMASK -> out[0] = depthMask ? 1 : 0;
            case GL_BLEND, GL_DEPTH_TEST, GL_CULL_FACE, GL_SCISSOR_TEST, GL_POLYGON_OFFSET_FILL, GL_STENCIL_TEST -> out[0] = isEnabled(pname) ? 1 : 0;
            case GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT -> out[0] = 16;
            case GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT -> out[0] = 256;
            case GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS, GL_MAX_UNIFORM_BUFFER_BINDINGS -> out[0] = GlBindings.MAX_BUFFER_BINDINGS;
            case GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS, GL_MAX_COMBINED_SHADER_STORAGE_BLOCKS, GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS,
                 GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS -> out[0] = 16;
            case GL_MAX_IMAGE_UNITS -> out[0] = GlBindings.MAX_IMAGE_UNITS;
            case GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS -> out[0] = 1024;
            case GL_MAX_COMPUTE_SHARED_MEMORY_SIZE -> out[0] = 32768;
            case GL_MAX_SHADER_STORAGE_BLOCK_SIZE -> out[0] = Integer.MAX_VALUE;
            case GL_SHADER_STORAGE_BUFFER_BINDING, GL_DRAW_INDIRECT_BUFFER_BINDING, GL_DISPATCH_INDIRECT_BUFFER_BINDING,
                 GL_PARAMETER_BUFFER_BINDING, GL_COPY_READ_BUFFER_BINDING, GL_COPY_WRITE_BUFFER_BINDING, GL_PIXEL_PACK_BUFFER_BINDING -> {
                Buffer b = switch (pname) {
                    case GL_SHADER_STORAGE_BUFFER_BINDING -> otherBuffers.get(GL_SHADER_STORAGE_BUFFER);
                    case GL_DRAW_INDIRECT_BUFFER_BINDING -> otherBuffers.get(GL_DRAW_INDIRECT_BUFFER);
                    case GL_DISPATCH_INDIRECT_BUFFER_BINDING -> otherBuffers.get(GL_DISPATCH_INDIRECT_BUFFER);
                    case GL_PARAMETER_BUFFER_BINDING -> otherBuffers.get(GL_PARAMETER_BUFFER);
                    case GL_COPY_READ_BUFFER_BINDING -> copyReadBuffer;
                    case GL_COPY_WRITE_BUFFER_BINDING -> copyWriteBuffer;
                    default -> pixelPackBuffer;
                };
                out[0] = b == null ? 0 : b.name;
            }
            case GL_STENCIL_FUNC -> out[0] = stencilFunc;
            case GL_STENCIL_REF -> out[0] = stencilRef;
            case GL_STENCIL_VALUE_MASK -> out[0] = stencilValueMask;
            case GL_STENCIL_WRITEMASK -> out[0] = stencilWriteMask;
            case GL_STENCIL_FAIL -> out[0] = stencilFail;
            case GL_STENCIL_PASS_DEPTH_FAIL -> out[0] = stencilDepthFail;
            case GL_STENCIL_PASS_DEPTH_PASS -> out[0] = stencilDepthPass;
            case GL_SAMPLER_BINDING -> {
                GlBindings.SamplerObject sampler = bindings.samplerUnits[activeTexture];
                out[0] = sampler == null ? 0 : sampler.name;
            }
            default -> {
                MetalGLLog.unsupported("glGetInteger pname 0x" + Integer.toHexString(pname));
                error(GL_INVALID_ENUM);
                out[0] = 0;
            }
        }
        return n;
    }

    /** glGetInteger64v (GL 3.2): 64-bit limits; everything else as glGetIntegerv. */
    public long getInteger64(final int pname) {
        return switch (pname) {
            case GL_MAX_SHADER_STORAGE_BLOCK_SIZE -> 1L << 31;
            case GL_TIMESTAMP -> System.nanoTime();
            case GL_MAX_SERVER_WAIT_TIMEOUT -> Long.MAX_VALUE;
            default -> {
                int[] out = new int[4];
                getInteger(pname, out);
                yield out[0];
            }
        };
    }

    /** glGetIntegeri_v: indexed bindings. */
    public int getIntegeri(final int pname, final int index) {
        return switch (pname) {
            case GL_SHADER_STORAGE_BUFFER_BINDING, GL_UNIFORM_BUFFER_BINDING -> bindings.bufferBinding(pname, index);
            case GL_MAX_COMPUTE_WORK_GROUP_COUNT -> index >= 0 && index < 3 ? 65535 : 0;
            case GL_MAX_COMPUTE_WORK_GROUP_SIZE -> index == 2 ? 64 : 1024;
            default -> {
                MetalGLLog.unsupported("glGetIntegeri pname 0x" + Integer.toHexString(pname));
                error(GL_INVALID_ENUM);
                yield 0;
            }
        };
    }

    public float getFloat(final int pname) {
        return switch (pname) {
            case GL_LINE_WIDTH -> lineWidth;
            case GL_DEPTH_CLEAR_VALUE -> clearDepth;
            case GL_MAX_TEXTURE_MAX_ANISOTROPY -> 16f;
            default -> {
                int[] out = new int[4];
                getInteger(pname, out);
                yield out[0];
            }
        };
    }

    public @Nullable String getString(final int pname) {
        return switch (pname) {
            case GL_VENDOR -> "Metallium";
            case GL_RENDERER -> "metallium-gl on " + device.name();
            case GL_VERSION -> "4.6.0 Core Profile (metallium-gl)";
            case GL_SHADING_LANGUAGE_VERSION -> "4.60";
            case GL_EXTENSIONS -> {
                error(GL_INVALID_ENUM); // core profile: use glGetStringi
                yield null;
            }
            default -> {
                error(GL_INVALID_ENUM);
                yield null;
            }
        };
    }

    public @Nullable String getStringi(final int pname, final int index) {
        if (pname != GL_EXTENSIONS) {
            error(GL_INVALID_ENUM);
            return null;
        }
        if (index < 0 || index >= MetalGLStrings.EXTENSIONS.size()) {
            error(GL_INVALID_VALUE);
            return null;
        }
        return MetalGLStrings.EXTENSIONS.get(index);
    }
}
