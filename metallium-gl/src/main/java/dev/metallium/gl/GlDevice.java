/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
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

    /** Resources of one shader stage. {@code uniformBufferIndex < 0}: the stage has no loose uniforms. */
    record StageBindings(@Nullable ByteBuffer uniforms, int uniformBufferIndex, TextureBinding[] textures) {
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
                       boolean wireframe) {
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

    /** glFinish: submit and wait where possible (headless); in-game this only closes the encoder. */
    void finish();
}
