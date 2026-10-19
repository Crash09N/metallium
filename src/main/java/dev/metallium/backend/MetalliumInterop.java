/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.textures.GpuTexture;
import io.github.kokodio.metaljvm.metal.MTLCommandBuffer;
import io.github.kokodio.metaljvm.metal.MTLDevice;
import io.github.kokodio.metaljvm.metal.MTLFence;
import io.github.kokodio.metaljvm.metal.MTLPixelFormat;
import io.github.kokodio.metaljvm.metal.MTLTexture;
import org.jspecify.annotations.Nullable;

/**
 * Minimal extension API for code that encodes its own Metal commands into Metallium's frame (the
 * {@code metallium-gl} translation layer and the Iris adapter's compute passes). Everything here must be called on the render thread.
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
    /**
     * Extra {@code GpuTexture} usage bit understood only by Metallium's {@code createTexture}: the texture can also be
     * bound as a read-write storage image (MTLTextureUsageShaderWrite, plus ShaderAtomic for R32 integer formats). The
     * renderpearl frontend passes unknown usage bits through. Used by the Iris adapter for {@code colorimgN} images.
     */
    public static final int TEXTURE_USAGE_STORAGE = 1 << 20;

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
        if (PerfCounters.tracing) PerfCounters.trace("external encoding from " + PerfCounters.caller());
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
     * The Metal buffer currently behind a Metallium {@link com.mojang.renderpearl.api.buffers.GpuBuffer} (for GPU-side use
     * by external encoders, e.g. a mod's compute shader writing a buffer that renderpearl then draws from). Resolve it at
     * each use: CPU writes to a dynamic buffer may swap its backing.
     */
    public static io.github.kokodio.metaljvm.metal.MTLBuffer metalBuffer(final com.mojang.renderpearl.api.buffers.GpuBuffer buffer) {
        return ((MetalGpuBuffer) buffer).metalBuffer();
    }

    /**
     * Commits everything encoded so far in the current frame and waits until the GPU finished it (synchronous GL
     * readbacks). Expensive: only for rare, explicit waits. Encoding then continues in a new command buffer.
     */
    public static void submitAndWait() {
        require().createCommandEncoder().commitAndWait();
    }

    /** The Metal pixel format Metallium uses for a renderpearl {@link GpuFormat} (for textures created directly in Metal). */
    public static MTLPixelFormat pixelFormat(final GpuFormat format) {
        return MetalConversions.pixelFormat(format);
    }

    /**
     * Wraps a texture created directly in Metal as a renderpearl {@link GpuTexture} (sampling in render passes, any
     * texture type incl. 3D). The wrapper owns the reference: closing it releases the Metal texture.
     */
    public static GpuTexture wrapTexture(final MTLTexture texture, final GpuFormat format, final String label) {
        return new MetalGpuTexture(require(), texture, label, format);
    }

    /**
     * Resources a render pass's current pipeline uses outside the renderpearl binding table (storage images and
     * buffers at Metal indices the caller chose above the backend's own): {@code binder} is applied before the next
     * draw and again whenever Metallium re-creates the pass's encoder; {@code null} removes it. {@code pass} is the
     * backend pass behind a renderpearl {@code RenderPass}.
     */
    public static void setDrawBindings(final com.mojang.renderpearl.backend.api.RenderPassBackend pass,
                                       final java.util.function.@Nullable Consumer<io.github.kokodio.metaljvm.metal.MTLRenderCommandEncoder> binder) {
        ((MetalRenderPass) pass).setExtraBindings(binder);
    }

    /**
     * For pipelines with more than 16 textures in a stage (Metal's sampler-state limit): the sampler each texture
     * uniform of pipeline {@code pipelineName} is always bound with. Those textures use it as an MSL
     * {@code constexpr sampler} (identical ones count once towards Metal's 16); the sampler passed at bind time is
     * ignored for them. Call before compiling the
     * pipeline; an empty map removes the entry.
     */
    public static void setConstexprSamplers(final String pipelineName, final java.util.Map<String, com.mojang.renderpearl.api.textures.GpuSampler> samplers) {
        if (samplers.isEmpty()) {
            MetalCrossShaderCompiler.CONSTEXPR_SAMPLERS.remove(pipelineName);
            return;
        }
        java.util.Map<String, MetalGpuSampler> metal = new java.util.HashMap<>();
        samplers.forEach((name, sampler) -> metal.put(name, (MetalGpuSampler) sampler));
        MetalCrossShaderCompiler.CONSTEXPR_SAMPLERS.put(pipelineName, java.util.Map.copyOf(metal));
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
