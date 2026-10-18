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
import io.github.kokodio.metaljvm.metal.MTLTexture;
import io.github.kokodio.metaljvm.quartzcore.CAMetalLayer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import io.github.kokodio.metaljvm.objc.AutoreleasePool;
import io.github.kokodio.metaljvm.objc.ObjCBlock;
import io.github.kokodio.metaljvm.quartzcore.CAMetalDrawable;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLMetal;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

@Environment(EnvType.CLIENT)
final class MetalSurface implements GpuSurfaceBackend {
    private static final long PRESENT_PENDING_TIMEOUT_NS = 100_000_000L;
    private static final Set<GpuSurface.PresentMode> SUPPORTED_PRESENT_MODES = EnumSet.of(GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.MAILBOX);
    private final long metalView;
    private final CAMetalLayer metalLayer;
    private MetalCommandEncoder pendingPresentEncoder;
    private final DrawablePrefetcher prefetcher = new DrawablePrefetcher();
    /** Set while a vsync-off present has not reached the display yet; cleared by the drawable's presented handler. */
    private final AtomicBoolean presentPending = new AtomicBoolean();
    private final long presentedHandler = ObjCBlock.withRunnable(() -> presentPending.set(false));
    private long lastPresentNs;
    private boolean displaySync = true;
    private int width;
    private int height;

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
        this.width = config.width();
        this.height = config.height();
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
        CAMetalDrawable drawable = this.prefetcher.take();
        if (drawable != null && !matchesSize(drawable)) {
            drawable.release(); // acquired before a resize
            drawable = null;
        }
        if (this.displaySync) {
            // vsync: acquiring the drawable on the render thread is the frame pacing.
            if (drawable != null) {
                metalEncoder.presentTextureToDrawable(drawable, textureView, 0L);
                drawable.release();
            } else {
                metalEncoder.presentTextureToDrawable(metalLayer, textureView);
            }
            return;
        }
        // No vsync: a windowed CAMetalLayer only hands drawables back at the display refresh, so a blocking
        // nextDrawable() would cap the frame rate at the refresh rate. Instead a frame is presented only when the
        // previous present has reached the display (presented handler; 100 ms safety valve) and a drawable acquired
        // by the helper thread is ready; otherwise the drawable blit is skipped (the frame itself is still rendered),
        // like a mailbox swapchain dropping frames. Presenting no more than the display shows also keeps the GPU from
        // waiting on drawables the compositor still holds.
        long now = System.nanoTime();
        if (this.presentPending.get() && now - this.lastPresentNs < PRESENT_PENDING_TIMEOUT_NS) {
            if (drawable != null) {
                this.prefetcher.putBack(drawable);
            }
            return;
        }
        if (drawable != null) {
            this.presentPending.set(true);
            this.lastPresentNs = now;
            metalEncoder.presentTextureToDrawable(drawable, textureView, this.presentedHandler);
            drawable.release();
        }
        this.prefetcher.request();
    }

    private boolean matchesSize(final CAMetalDrawable drawable) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLTexture texture = drawable.texture();
            return texture.width() == this.width && texture.height() == this.height;
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
        this.prefetcher.stop();
        SDLMetal.SDL_Metal_DestroyView(this.metalView);
    }

    @Override
    public @NonNull Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return SUPPORTED_PRESENT_MODES;
    }

    /** Acquires drawables off the render thread (see {@link #blitFromTexture}); at most one request in flight. */
    private final class DrawablePrefetcher implements Runnable {
        private final Semaphore requests = new Semaphore(0);
        private final AtomicReference<CAMetalDrawable> ready = new AtomicReference<>();
        private volatile boolean running = true;
        private boolean requested;
        @Nullable
        private Thread thread;

        /** Render thread: the drawable acquired by the last request, or null if it is not there yet. */
        @Nullable
        CAMetalDrawable take() {
            CAMetalDrawable drawable = this.ready.getAndSet(null);
            if (drawable != null) {
                this.requested = false;
            }
            return drawable;
        }

        /** Render thread: keeps an unused drawable from {@link #take} for a later frame. */
        void putBack(final CAMetalDrawable drawable) {
            this.requested = true;
            CAMetalDrawable previous = this.ready.getAndSet(drawable);
            if (previous != null) {
                previous.release();
            }
        }

        /** Render thread: makes sure a drawable is being acquired for a later frame. */
        void request() {
            if (this.requested) {
                return;
            }
            if (this.thread == null) {
                this.thread = Thread.ofPlatform().daemon().name("Metallium drawable acquire").start(this);
            }
            this.requested = true;
            this.requests.release();
        }

        void stop() {
            this.running = false;
            this.requests.release();
            CAMetalDrawable drawable = this.ready.getAndSet(null);
            if (drawable != null) {
                drawable.release();
            }
        }

        @Override
        public void run() {
            while (true) {
                this.requests.acquireUninterruptibly();
                if (!this.running) {
                    return;
                }
                CAMetalDrawable drawable;
                try (AutoreleasePool _ = AutoreleasePool.push()) {
                    drawable = metalLayer.nextDrawable();
                    if (drawable != null) {
                        drawable.retain();
                    }
                }
                if (drawable == null) {
                    // Only without a valid drawable size; retry on the next request.
                    this.requests.release();
                    LockSupport.parkNanos(1_000_000L);
                    continue;
                }
                if (!this.running) {
                    drawable.release();
                    return;
                }
                this.ready.set(drawable);
            }
        }
    }
}
