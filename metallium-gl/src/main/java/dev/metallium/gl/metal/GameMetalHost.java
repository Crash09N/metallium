/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl.metal;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.metallium.backend.MetalliumInterop;
import dev.metallium.gl.GlDevice;
import io.github.kokodio.metaljvm.metal.MTLCommandBuffer;
import io.github.kokodio.metaljvm.metal.MTLDevice;
import io.github.kokodio.metaljvm.metal.MTLFence;
import io.github.kokodio.metaljvm.metal.MTLTexture;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Encodes into Metallium's current frame through {@link MetalliumInterop}: GL draws land in the same command buffer as
 * the game's own passes, in call order. "Framebuffer 0" is Minecraft's main render target.
 */
public final class GameMetalHost implements MetalHost {
    private @Nullable Runnable interrupt;
    private final MetalliumInterop.ExternalEncoderClient client = () -> {
        Runnable r = interrupt;
        interrupt = null;
        if (r != null) {
            r.run();
        }
    };

    @Override
    public MTLDevice device() {
        return MetalliumInterop.device();
    }

    @Override
    public String name() {
        return MetalliumInterop.device().name();
    }

    @Override
    public MTLCommandBuffer begin(final Runnable onInterrupt) {
        interrupt = onInterrupt;
        return MetalliumInterop.beginExternalEncoding(client);
    }

    @Override
    public MTLFence fence() {
        return MetalliumInterop.frameFence();
    }

    @Override
    public @Nullable MTLTexture[] defaultFramebuffer() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameRenderer == null) {
            return null;
        }
        RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
        GpuTexture color = main.getColorTexture();
        if (color == null) {
            return null;
        }
        GpuTexture depth = main.getDepthTexture();
        return new MTLTexture[]{MetalliumInterop.metalTexture(color, true), depth == null ? null : MetalliumInterop.metalTexture(depth, true)};
    }

    @Override
    public void releaseAfterGpu(final Runnable action) {
        MetalliumInterop.releaseAfterGpu(action);
    }

    @Override
    public GlDevice.GpuUse currentUse() {
        GpuFence fence = MetalliumInterop.createFence();
        return new GlDevice.GpuUse() {
            @Override
            public boolean isComplete() {
                return fence.awaitCompletion(0L);
            }

            @Override
            public boolean await(final long timeoutNs) {
                try {
                    return fence.awaitCompletion(timeoutNs);
                } catch (IllegalStateException e) {
                    return false; // work of the frame being recorded: cannot complete before the frame is submitted
                }
            }
        };
    }

    @Override
    public boolean engineDepthZeroToOne() {
        try {
            return com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean submitAndWait() {
        MetalliumInterop.submitAndWait();
        return true;
    }

    @Override
    public @Nullable MTLTexture engineTexture(final Object engine, final boolean write) {
        if (engine instanceof GpuTexture texture && !texture.isClosed()) {
            return MetalliumInterop.metalTexture(texture, write);
        }
        return engine instanceof MTLTexture texture ? texture : null;
    }
}
