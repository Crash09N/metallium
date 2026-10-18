/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl.metal;

import dev.metallium.gl.GlDevice;
import io.github.kokodio.metaljvm.metal.MTLCommandBuffer;
import io.github.kokodio.metaljvm.metal.MTLDevice;
import io.github.kokodio.metaljvm.metal.MTLFence;
import io.github.kokodio.metaljvm.metal.MTLTexture;
import org.jspecify.annotations.Nullable;

/**
 * Where {@link MetalGlDevice} encodes: in the game, Metallium's frame command buffer ({@link GameMetalHost}); in tests,
 * a private queue ({@link HeadlessMetalHost}).
 */
public interface MetalHost {
    MTLDevice device();

    String name();

    /**
     * Returns the command buffer to encode into. {@code onInterrupt} runs (once) when the host needs to encode itself;
     * the device must then close its open encoder and call {@code begin} again before encoding more.
     */
    MTLCommandBuffer begin(Runnable onInterrupt);

    /** Fence every external encoder waits for at its start and updates at its end, or null if not needed. */
    @Nullable MTLFence fence();

    /** Colour and depth textures of "framebuffer 0" ({@code [color, depth]}, depth may be null), or null if none. */
    @Nullable MTLTexture[] defaultFramebuffer();

    /** Runs {@code action} when the GPU has finished all work encoded so far. */
    void releaseAfterGpu(Runnable action);

    /** The current point in the command stream. */
    GlDevice.GpuUse currentUse();

    /** See {@code GlDevice.engineDepthZeroToOne}. */
    default boolean engineDepthZeroToOne() {
        return false;
    }

    /** Commits and waits for all work so far; false if the host cannot (the device then reports the GL op as unsupported). */
    boolean submitAndWait();

    /**
     * The Metal texture of an engine texture (Minecraft's {@code GpuTexture} in game) with pending lazy clears applied
     * ({@code write}: the caller renders into it), or null if {@code engine} is not one.
     */
    default @Nullable MTLTexture engineTexture(final Object engine, final boolean write) {
        return engine instanceof MTLTexture texture ? texture : null;
    }
}
