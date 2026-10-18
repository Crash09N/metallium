/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.backend;

import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.textures.GpuTexture;
import io.github.kokodio.metaljvm.metal.MTLCommandBuffer;
import io.github.kokodio.metaljvm.metal.MTLDevice;
import io.github.kokodio.metaljvm.metal.MTLFence;
import io.github.kokodio.metaljvm.metal.MTLTexture;
import org.jspecify.annotations.Nullable;

/**
 * Minimal extension API for code that encodes its own Metal commands into Metallium's frame (currently only the
 * {@code metallium-gl} translation layer). Everything here must be called on the render thread.
 *
 * <p>Contract for an external encoder:
 * <ol>
 *   <li>{@link #beginExternalEncoding} ends Metallium's open encoder and returns the frame's command buffer; the
 *       caller may now create its own encoders on it.</li>
 *   <li>Each external encoder waits for {@link #frameFence()} when it starts and updates it when it ends, exactly like
 *       Metallium's own encoders (Metallium's resources are not hazard-tracked).</li>
 *   <li>Before Metallium opens its next encoder (or submits / presents) it calls
 *       {@link ExternalEncoderClient#endExternalEncoding()} once, so the client closes whatever encoder it left open.</li>
 * </ol>
 */
public final class MetalliumInterop {
    /** Owner of an open external encoder; asked to close it before Metallium encodes again. */
    public interface ExternalEncoderClient {
        void endExternalEncoding();
    }

    private static volatile @Nullable MetalDevice device;
    private static @Nullable ExternalEncoderClient externalClient;

    private MetalliumInterop() {
    }

    static void attach(final MetalDevice metalDevice) {
        device = metalDevice;
    }

    /** Called by {@link MetalCommandEncoder#endEncoder()} before Metallium encodes anything else. */
    static void endExternalEncoding() {
        ExternalEncoderClient client = externalClient;
        if (client != null) {
            externalClient = null;
            client.endExternalEncoding();
        }
    }

    /** True once the Metal device exists. */
    public static boolean isAvailable() {
        return device != null;
    }

    public static MTLDevice device() {
        return require().metalDevice();
    }

    /** Ends Metallium's open encoder and returns the current frame's command buffer for external encoders. */
    public static MTLCommandBuffer beginExternalEncoding(final ExternalEncoderClient client) {
        MetalCommandEncoder encoder = require().createCommandEncoder();
        encoder.endEncoder();
        externalClient = client;
        return encoder.commandBuffer();
    }

    /** The fence that orders Metallium's encoders; external encoders wait for and update it too. */
    public static MTLFence frameFence() {
        return require().createCommandEncoder().frameFence();
    }

    /**
     * The Metal texture behind a Metallium {@link GpuTexture}, with any lazily recorded clear applied first.
     * {@code willWrite}: the caller renders into it (drops Metallium's cached "already cleared" state).
     */
    public static MTLTexture metalTexture(final GpuTexture texture, final boolean willWrite) {
        MetalGpuTexture metal = (MetalGpuTexture) texture;
        MetalCommandEncoder encoder = require().createCommandEncoder();
        encoder.flushPendingClear(metal);
        if (willWrite) {
            metal.markContentsDirty();
        }
        return metal.metalTexture();
    }

    /**
     * Commits everything encoded so far in the current frame and waits until the GPU finished it (synchronous GL
     * readbacks). Expensive: only for rare, explicit waits. Encoding then continues in a new command buffer.
     */
    public static void submitAndWait() {
        require().createCommandEncoder().commitAndWait();
    }

    /** Runs {@code action} once the GPU has finished every command buffer submitted so far (incl. the current one). */
    public static void releaseAfterGpu(final Runnable action) {
        require().createCommandEncoder().queueForDestroy(action);
    }

    /** A fence that signals when the current frame's command buffer has completed on the GPU. */
    public static GpuFence createFence() {
        return require().createCommandEncoder().createFence();
    }

    private static MetalDevice require() {
        MetalDevice current = device;
        if (current == null) {
            throw new IllegalStateException("Metallium's Metal device is not initialised");
        }
        return current;
    }
}
