/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.gl;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;

/**
 * What the GL state tracker ({@link GlContext}) needs from a GPU. The tracker resolves all GL state into fully
 * specified operations ({@link DrawOp}, {@link ClearOp}); a device only executes them. Production uses the Metal device
 * ({@code dev.metallium.gl.metal.MetalGlDevice}); unit tests use a recording device without any GPU.
 *
 * <p>GL enums that describe fixed-function state (blend factors, compare functions, primitive modes) are passed through
 * unchanged as GL ints; the device maps them to its own API.
 */
public interface GlDevice {
    /** Something the GPU will read or write; complete once all GPU work submitted before it finished. */
    interface GpuUse {
        boolean isComplete();

        /** Waits up to {@code timeoutNs}; false on timeout or if waiting is impossible (e.g. work of the current frame). */
        boolean await(long timeoutNs);
    }

    /** Linear GPU memory, CPU-visible (unified memory): {@link #contents()} is the storage itself. */
    interface BufferStore {
        long size();

        ByteBuffer contents();

        /** True if GPU work that is not complete yet references this storage (writing it now would race). */
        boolean inUseByGpu();
    }

    interface TextureStore {
        TexFormat format();

        int width();

        int height();

        int levels();

        /** Depth of a 3D texture, else 1. */
        default int depth() {
            return 1;
        }

        /** Array layers (2D array / cube faces), else 1. */
        default int layers() {
            return 1;
        }

        default boolean is3D() {
            return false;
        }
    }

    interface ProgramStore {
    }

    final class DeviceException extends Exception {
        public DeviceException(final String message) {
            super(message);
        }
    }

    /** MSL of one stage plus its entry point. */
    record StageCode(String msl, String entryPoint) {
    }

    /**
     * Attachments of one render pass, indexed by Metal colour attachment index (GL draw buffer index); {@code null}
     * entries are unused. Levels are mip levels.
     */
    record RenderTargets(@Nullable TextureStore[] colors, int[] colorLevels, @Nullable TextureStore depth, int depthLevel,
                         boolean depthHasStencil) {
        public int width() {
            for (int i = 0; i < colors.length; i++) {
                if (colors[i] != null) {
                    return Math.max(1, colors[i].width() >> colorLevels[i]);
                }
            }
            return depth == null ? 0 : Math.max(1, depth.width() >> depthLevel);
        }

        public int height() {
            for (int i = 0; i < colors.length; i++) {
                if (colors[i] != null) {
                    return Math.max(1, colors[i].height() >> colorLevels[i]);
                }
            }
            return depth == null ? 0 : Math.max(1, depth.height() >> depthLevel);
        }
    }

    /** glClear: what to clear and the state that restricts it ({@code scissor} null = whole target). */
    record ClearOp(boolean color, float[] rgba, boolean[] colorMask, boolean depth, float depthValue,
                   boolean stencil, int stencilValue, int @Nullable [] scissor) {
    }

    /** One vertex attribute. {@code buffer == null}: a constant value ({@code constant}, 4 components). */
    record VertexAttrib(int location, int components, int glType, boolean normalized, boolean integer,
                        @Nullable BufferStore buffer, long offset, int stride, int divisor, float @Nullable [] constant) {
    }

    /** Sampler state in GL terms. */
    record SamplerState(int minFilter, int magFilter, int wrapS, int wrapT, int wrapR, float minLod, float maxLod,
                        float lodBias, int compareMode, int compareFunc, float maxAnisotropy) {
        public static final SamplerState DEFAULT = new SamplerState(0x2702 /* NEAREST_MIPMAP_LINEAR */, 0x2601 /* LINEAR */,
                0x2901, 0x2901, 0x2901, -1000f, 1000f, 0f, 0, 0x0203 /* LEQUAL */, 1f);
    }

    record TextureBinding(int textureIndex, int samplerIndex, @Nullable TextureStore texture, SamplerState sampler) {
    }

    /** A uniform or storage buffer at Metal buffer index {@code index}; {@code buffer == null}: nothing bound in GL. */
    record BufferBinding(int index, @Nullable BufferStore buffer, long offset, boolean written) {
    }

    /** A storage image (glBindImageTexture) at Metal texture index {@code index}: one mip level of {@code texture}. */
    record ImageBinding(int index, @Nullable TextureStore texture, int level, boolean written) {
    }

    /** Resources of one shader stage. {@code uniformBufferIndex < 0}: the stage has no loose uniforms. */
    record StageBindings(@Nullable ByteBuffer uniforms, int uniformBufferIndex, TextureBinding[] textures, BufferBinding[] buffers,
                         ImageBinding[] images) {
        static final BufferBinding[] NO_BUFFERS = new BufferBinding[0];
        static final ImageBinding[] NO_IMAGES = new ImageBinding[0];

        public StageBindings(final @Nullable ByteBuffer uniforms, final int uniformBufferIndex, final TextureBinding[] textures) {
            this(uniforms, uniformBufferIndex, textures, NO_BUFFERS, NO_IMAGES);
        }
    }

    /** Stencil test state in GL terms (front and back share one state; GL's separate variants are not tracked). */
    record StencilState(boolean enabled, int func, int ref, int readMask, int writeMask, int sfail, int dpfail, int dppass) {
        public static final StencilState DISABLED = new StencilState(false, 0x0207 /* ALWAYS */, 0, 0xFF, 0xFF, 0x1E00, 0x1E00, 0x1E00);
    }

    /**
     * glDispatchCompute: {@code groups} threadgroups, or, if {@code indirect != null}, the three uints at
     * {@code indirectOffset} in that buffer (GL's DispatchIndirectCommand layout = Metal's).
     */
    record DispatchOp(ProgramStore program, int groupsX, int groupsY, int groupsZ, @Nullable BufferStore indirect, long indirectOffset,
                      StageBindings bindings) {
    }

    /**
     * Indirect indexed draws: {@code drawCount} GL {@code DrawElementsIndirectCommand}s (5 uints, layout identical to
     * Metal's {@code MTLDrawIndexedPrimitivesIndirectArguments}) at {@code offset}, {@code stride} bytes apart. With a
     * {@code countBuffer}, the uint at {@code countOffset} (GPU-written) limits the number of draws; {@code drawCount} is
     * then GL's {@code maxdrawcount}.
     */
    record IndirectDraws(BufferStore commands, long offset, int drawCount, int stride, @Nullable BufferStore countBuffer, long countOffset) {
    }

    /** Per colour attachment blend state (GL enums). */
    record BlendState(boolean enabled, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha, int eqRgb, int eqAlpha,
                      boolean writeR, boolean writeG, boolean writeB, boolean writeA) {
    }

    /**
     * Fixed-function state of a draw. Viewport/scissor are in GL window coordinates (origin bottom-left), which on
     * Metallium's render targets equal Metal's (images are stored bottom row first, like GL).
     */
    record RasterState(int[] viewport, float depthNear, float depthFar, int @Nullable [] scissor, BlendState[] blend,
                       float[] blendColor, boolean depthTest, int depthFunc, boolean depthWrite, int cullFace,
                       boolean frontFaceCcw, boolean polygonOffset, float offsetFactor, float offsetUnits,
                       boolean wireframe, StencilState stencil, boolean clipDepthZeroToOne) {
    }

    /** A fully resolved draw. {@code indexBuffer == null}: non-indexed ({@code first} is the first vertex). */
    record DrawOp(ProgramStore program, RenderTargets targets, int glPrimitive, VertexAttrib[] attributes,
                  @Nullable BufferStore indexBuffer, long indexOffset, boolean index32, int first, int count,
                  int instanceCount, int baseVertex, int baseInstance, StageBindings vertex, StageBindings fragment,
                  RasterState raster) {
    }

    String name();

    BufferStore createBuffer(long size);

    TextureStore createTexture(TexFormat format, int width, int height, int levels);

    /** Uploads tightly described pixels already in {@code texture}'s format, ordered with the GPU work around it. */
    void writeTexture(TextureStore texture, int level, int x, int y, int width, int height, ByteBuffer pixels, int bytesPerRow);

    /** Copies mip levels {@code 0..levels-1} (used when a texture's storage is reallocated with more levels). */
    void copyTexture(TextureStore source, TextureStore destination, int levels);

    void generateMipmaps(TextureStore texture);

    ProgramStore createProgram(String label, StageCode vertex, StageCode fragment) throws DeviceException;

    /** The window / main render target ("framebuffer 0"), or null if there is none right now. */
    @Nullable RenderTargets defaultFramebuffer();

    void clear(RenderTargets targets, ClearOp op);

    void draw(DrawOp op);

    /**
     * Reads RGBA8 pixels of colour attachment {@code attachment}. Only supported where the device can wait for the GPU
     * (headless); returns false otherwise.
     */
    boolean readPixels(RenderTargets targets, int attachment, int x, int y, int width, int height, ByteBuffer rgba8);

    /** Marks the current point in the GPU command stream. */
    GpuUse fence();

    /** Frees a store once the GPU no longer uses it. */
    void release(Object store);

    /** glFlush: close any open encoder (work is submitted with the host's frame). */
    void flush();

    /** glFinish: submit and wait for all GPU work so far; true if it waited (all earlier fences are then complete). */
    boolean finish();

    // ------------------------------------------------------------------------------------------ GL 4.x (compute etc.)
    // Default implementations: CPU fallbacks or "unsupported", so test devices only implement what they exercise.

    /** A compute program; {@code localSize} is the workgroup size from the shader. */
    default ProgramStore createComputeProgram(final String label, final StageCode compute, final int[] localSize) throws DeviceException {
        throw new DeviceException(name() + ": compute is not supported");
    }

    void dispatch(DispatchOp op);

    /** {@code op} with its count/instance fields ignored: the commands come from {@code draws}. */
    void drawIndirect(DrawOp op, IndirectDraws draws);

    /** GPU-ordered copy between buffers (glCopyBufferSubData). */
    void copyBuffer(BufferStore source, long sourceOffset, BufferStore destination, long destinationOffset, long size);

    /** GPU-ordered fill of {@code size} bytes with a repeated 4-byte pattern (glClearBufferSubData). */
    void fillBuffer(BufferStore destination, long offset, long size, int pattern);

    /** GPU-ordered write of CPU data into a buffer the GPU may still read or write this frame. */
    void uploadBuffer(BufferStore destination, long offset, ByteBuffer data);

    /** Reads buffer bytes after all GPU work so far (glGetBufferSubData); false if the device cannot wait. */
    boolean readBuffer(BufferStore source, long offset, ByteBuffer out);

    /** Reads one mip level as tightly packed texels of the texture's format (glGetTexImage); false if impossible. */
    default boolean readTexture(final TextureStore texture, final int level, final ByteBuffer out) {
        return false;
    }

    /** GPU-ordered fill of one mip level with one texel value in the texture's format (glClearTexImage). */
    default void clearTexture(final TextureStore texture, final int level, final ByteBuffer texel) {
    }

    /** Copies a region between textures of the same format (glCopyImageSubData / glBlitFramebuffer 1:1). */
    default void copyTextureRegion(final TextureStore source, final int sourceLevel, final int sx, final int sy,
                                   final TextureStore destination, final int destinationLevel, final int dx, final int dy,
                                   final int width, final int height) {
    }

    /**
     * Wraps a texture owned by the engine (Metallium's {@code GpuTexture}) so GL can sample it, attach it or copy it; null
     * if {@code engineTexture} is not one this device understands.
     */
    default @Nullable TextureStore externalTexture(final Object engineTexture) {
        return null;
    }

    /** The device's own texture object behind {@code texture} (Metal: the {@code MTLTexture}), or null. */
    default @Nullable Object nativeTexture(final TextureStore texture) {
        return null;
    }

    /**
     * Wraps a buffer owned by the engine (Metallium's {@code GpuBuffer}) so GL can bind it (SSBO, copies, pack target);
     * null if {@code engineBuffer} is not one this device understands. GPU access only.
     */
    default @Nullable BufferStore externalBuffer(final Object engineBuffer) {
        return null;
    }

    /**
     * GPU copy of mip {@code level} of {@code texture} (all layers / depth slices, tightly packed, storage layout) into
     * {@code buffer} at {@code offset}: glGetTexImage into a pixel-pack buffer. False if the device cannot.
     */
    default boolean copyTextureToBuffer(final TextureStore texture, final int level, final BufferStore buffer, final long offset) {
        return false;
    }

    /** A view of {@code texture} reinterpreting it as {@code format} (glTextureView); null if impossible. */
    default @Nullable TextureStore textureView(final TextureStore texture, final TexFormat format, final int minLevel, final int levels) {
        return null;
    }

    /**
     * The depth convention of the engine's own GL context: Minecraft's OpenGL backend switches its context to
     * {@code glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)} when the device reports zero-to-one depth, and GL mods
     * rely on that (Voxy's {@code USE_ZERO_ONE_DEPTH}). The emulated context starts in the same state.
     */
    default boolean engineDepthZeroToOne() {
        return false;
    }

    /** glTextureBarrier: later draws see earlier render-target writes when sampling. */
    default void textureBarrier() {
    }
}
