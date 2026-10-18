/*
 * Portions of this file are derived from Metallum (https://github.com/kokodio/metallum),
 * Copyright (c) 2026 kokodio, licensed under the MIT License (see LICENSES/MIT-metallum.txt).
 * Modifications Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.backend;

import dev.metallium.MetalliumConfig;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import io.github.kokodio.metaljvm.coregraphics.CGColorSpace;
import io.github.kokodio.metaljvm.coregraphics.CGSize;
import io.github.kokodio.metaljvm.metal.MTLDevice;
import io.github.kokodio.metaljvm.metal.MTLPixelFormat;
import io.github.kokodio.metaljvm.quartzcore.CAMetalLayer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import io.github.kokodio.metaljvm.objc.ObjCBlock;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLMetal;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Environment(EnvType.CLIENT)
final class MetalSurface implements GpuSurfaceBackend {
    private static final Set<GpuSurface.PresentMode> SUPPORTED_PRESENT_MODES = EnumSet.of(GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.MAILBOX);
    private static final long PRESENT_PENDING_TIMEOUT_NS = 100_000_000L;
    private final long metalView;
    private final CAMetalLayer metalLayer;
    private MetalCommandEncoder pendingPresentEncoder;
    /**
     * Without vsync, a CAMetalLayer in a window still only releases drawables at the display refresh, so a blocking
     * {@code nextDrawable} caps the frame rate at the refresh rate. With vsync off we therefore present a frame only
     * when no earlier present is still waiting for the display (the presented handler clears the flag) and skip the
     * drawable blit otherwise, like a mailbox swapchain dropping frames. The frame itself is still rendered.
     */
    private final AtomicBoolean presentPending = new AtomicBoolean();
    private final long presentedHandler = ObjCBlock.withRunnable(() -> presentPending.set(false));
    private boolean displaySync = true;
    private long lastPresentNs;

    MetalSurface(final long windowHandle, final MTLDevice metalDevice) {
        this.metalView = SDLMetal.SDL_Metal_CreateView(windowHandle);
        if (this.metalView == 0L) {
            throw new IllegalStateException("Failed to create Metal view: " + SDLError.SDL_GetError());
        }
        long layerHandle = SDLMetal.SDL_Metal_GetLayer(this.metalView);
        if (layerHandle == 0L) {
            SDLMetal.SDL_Metal_DestroyView(this.metalView);
            throw new IllegalStateException("Failed to get Metal layer: " + SDLError.SDL_GetError());
        }

        this.metalLayer = new CAMetalLayer(layerHandle);
        this.metalLayer.setDevice(metalDevice);
        this.metalLayer.setFramebufferOnly(false);
        this.metalLayer.setOpaque(true);
        long colorspace = CGColorSpace.CGColorSpaceCreateWithName(MetalliumConfig.INSTANCE.displayP3 ? CGColorSpace.kCGColorSpaceDisplayP3 : CGColorSpace.kCGColorSpaceSRGB);
        this.metalLayer.setColorspace(colorspace);
        CGColorSpace.CGColorSpaceRelease(colorspace);
    }

    @Override
    public void configure(final GpuSurface.Configuration config) throws SurfaceException {
        if (config.width() <= 0 || config.height() <= 0) {
            throw new SurfaceException("Metal surface configuration must be positive, got " + config.width() + "x" + config.height());
        }

        this.metalLayer.setPixelFormat(MTLPixelFormat.BGRA8Unorm);
        this.metalLayer.setDrawableSize(new CGSize(config.width(), config.height()));
        this.metalLayer.setAllowsNextDrawableTimeout(false);
        this.metalLayer.setPresentsWithTransaction(false);
        this.displaySync = config.presentMode() == GpuSurface.PresentMode.FIFO || config.presentMode() == GpuSurface.PresentMode.FIFO_RELAXED;
        this.metalLayer.setDisplaySyncEnabled(this.displaySync);
        this.presentPending.set(false);
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    @Override
    public void acquireNextTexture() {
    }

    @Override
    public void blitFromTexture(final @NonNull CommandEncoderBackend commandEncoder, final @NonNull GpuTextureView textureView) {
        if (!(commandEncoder instanceof MetalCommandEncoder metalEncoder)) {
            throw new IllegalArgumentException("Metal surface requires MetalCommandEncoder");
        }

        this.pendingPresentEncoder = metalEncoder;
        if (this.displaySync) {
            metalEncoder.presentTextureToDrawable(metalLayer, textureView, 0L);
            return;
        }
        long now = System.nanoTime();
        // Safety valve: if a presented callback never arrives (e.g. occluded window), present anyway after 100 ms.
        if (this.presentPending.get() && now - this.lastPresentNs < PRESENT_PENDING_TIMEOUT_NS) {
            return;
        }
        this.presentPending.set(true);
        this.lastPresentNs = now;
        if (!metalEncoder.presentTextureToDrawable(metalLayer, textureView, this.presentedHandler)) {
            this.presentPending.set(false);
        }
    }

    @Override
    public void present() {
        MetalCommandEncoder encoder = this.pendingPresentEncoder;
        this.pendingPresentEncoder = null;
        if (encoder != null) {
            encoder.submit();
        }
    }

    @Override
    public void close() {
        SDLMetal.SDL_Metal_DestroyView(this.metalView);
    }

    @Override
    public @NonNull Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return SUPPORTED_PRESENT_MODES;
    }
}
