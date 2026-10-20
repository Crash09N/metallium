/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.gl.metal;

import dev.metallium.gl.GlDevice;
import io.github.kokodio.metaljvm.metal.MTLCommandBuffer;
import io.github.kokodio.metaljvm.metal.MTLCommandQueue;
import io.github.kokodio.metaljvm.metal.MTLDevice;
import io.github.kokodio.metaljvm.metal.MTLFence;
import io.github.kokodio.metaljvm.metal.MTLPixelFormat;
import io.github.kokodio.metaljvm.metal.MTLStorageMode;
import io.github.kokodio.metaljvm.metal.MTLTexture;
import io.github.kokodio.metaljvm.metal.MTLTextureDescriptor;
import io.github.kokodio.metaljvm.metal.MTLTextureUsage;
import io.github.kokodio.metaljvm.metal.Metal;
import io.github.kokodio.metaljvm.objc.AutoreleasePool;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A host without Minecraft: its own command queue and an offscreen RGBA8 + Depth32Float "window". Used by the unit
 * tests (macOS only) to run GL programs end to end and read back pixels.
 */
public final class HeadlessMetalHost implements MetalHost, AutoCloseable {
    private final MTLDevice device;
    private final MTLCommandQueue queue;
    private final MTLTexture color;
    private final MTLTexture depth;
    private @Nullable MTLCommandBuffer commandBuffer;
    private final List<Runnable> pendingReleases = new ArrayList<>();
    private long submitted;
    private long completed;

    public HeadlessMetalHost(final int width, final int height) {
        MTLDevice created = Metal.MTLCreateSystemDefaultDevice();
        if (created == null) {
            throw new IllegalStateException("no Metal device");
        }
        device = created;
        queue = device.newCommandQueue();
        color = texture(MTLPixelFormat.RGBA8Unorm, width, height);
        depth = texture(MTLPixelFormat.Depth32Float, width, height);
    }

    /** True if this machine has a Metal device (tests skip otherwise). */
    public static boolean available() {
        try {
            if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) {
                return false;
            }
            MTLDevice probe = Metal.MTLCreateSystemDefaultDevice();
            if (probe == null) {
                return false;
            }
            probe.release();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private MTLTexture texture(final MTLPixelFormat format, final int width, final int height) {
        MTLTextureDescriptor descriptor = MTLTextureDescriptor.texture2DDescriptor(format, width, height, false);
        descriptor.setUsage(MTLTextureUsage.RenderTarget | MTLTextureUsage.ShaderRead);
        descriptor.setStorageMode(MTLStorageMode.Private);
        return device.newTexture(descriptor);
    }

    @Override
    public MTLDevice device() {
        return device;
    }

    @Override
    public String name() {
        return device.name() + " (headless)";
    }

    @Override
    public MTLCommandBuffer begin(final Runnable onInterrupt) {
        if (commandBuffer == null) {
            try (AutoreleasePool _ = AutoreleasePool.push()) {
                commandBuffer = queue.commandBuffer();
                commandBuffer.retain();
            }
        }
        return commandBuffer;
    }

    @Override
    public @Nullable MTLFence fence() {
        return null;
    }

    @Override
    public MTLTexture[] defaultFramebuffer() {
        return new MTLTexture[]{color, depth};
    }

    @Override
    public void releaseAfterGpu(final Runnable action) {
        if (commandBuffer == null) {
            action.run();
        } else {
            pendingReleases.add(action);
        }
    }

    @Override
    public GlDevice.GpuUse currentUse() {
        long serial = submitted + 1;
        return new GlDevice.GpuUse() {
            @Override
            public boolean isComplete() {
                return completed >= serial;
            }

            @Override
            public boolean await(final long timeoutNs) {
                if (!isComplete() && submitted < serial) {
                    submitAndWait();
                }
                return isComplete();
            }
        };
    }

    @Override
    public boolean submitAndWait() {
        if (commandBuffer != null) {
            commandBuffer.commit();
            commandBuffer.waitUntilCompleted();
            commandBuffer.release();
            commandBuffer = null;
        }
        submitted++;
        completed = submitted;
        pendingReleases.forEach(Runnable::run);
        pendingReleases.clear();
        return true;
    }

    @Override
    public void close() {
        submitAndWait();
        color.release();
        depth.release();
        queue.release();
        device.release();
    }
}
