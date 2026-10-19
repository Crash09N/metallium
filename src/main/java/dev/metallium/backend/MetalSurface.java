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
import io.github.kokodio.metaljvm.quartzcore.CAMetalDrawable;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLMetal;

import io.github.kokodio.metaljvm.metal.MTLDrawable;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

@Environment(EnvType.CLIENT)
final class MetalSurface implements GpuSurfaceBackend {
    private static final Set<GpuSurface.PresentMode> SUPPORTED_PRESENT_MODES = EnumSet.of(GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.MAILBOX);
    private final long metalView;
    private final CAMetalLayer metalLayer;
    private MetalCommandEncoder pendingPresentEncoder;
    private final DrawablePrefetcher prefetcher = new DrawablePrefetcher();
    /** Presents not yet reported by their drawable's presented handler (shown or dropped). */
    private final AtomicInteger presentsInFlight = new AtomicInteger();
    private final long presentedHandler = presentedHandlerBlock(this);
    private long lastPresentNs;
    private long refreshPeriodNs = 1_000_000_000L / 60;
    private long refreshCheckedNs;
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

        PresentStats.windowHandle = windowHandle;
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
        this.presentsInFlight.set(0);
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
            // vsync (FIFO): acquiring the drawable on the render thread is the frame pacing.
            if (drawable == null) {
                drawable = acquireBlocking();
                if (drawable == null) {
                    return; // no valid drawable size
                }
            }
            present(metalEncoder, drawable, textureView, System.nanoTime());
            return;
        }
        // No vsync (mailbox): a CAMetalLayer only hands drawables back at the display refresh, so a blocking
        // nextDrawable() would cap the frame rate at the refresh rate. A helper thread acquires drawables; a rendered
        // frame is presented when a drawable is ready, the GPU has finished the previous present (its drawable is with
        // the compositor) and at least half a refresh period has passed since it; otherwise the drawable blit is skipped
        // (the frame itself is still rendered), like a mailbox swapchain dropping frames. This feeds every refresh with
        // a recent frame without one present per rendered frame (thousands per second make the layer drop presents and
        // stall). Pacing on the presented handler instead (until 2026-10-19) halved the shown rate when the handler
        // arrives a refresh late (owner: ~70 shown on a 144 Hz display at ~200 rendered fps).
        long now = System.nanoTime();
        if (now - this.refreshCheckedNs > 1_000_000_000L) {
            this.refreshCheckedNs = now;
            float hz = PresentStats.displayRefreshHz();
            this.refreshPeriodNs = hz > 1f ? (long) (1e9 / hz) : 1_000_000_000L / 60;
        }
        boolean previousDone = metalEncoder.previousPresentCompleted();
        boolean due = now - this.lastPresentNs >= this.refreshPeriodNs / 2;
        if (!previousDone || !due) {
            if (drawable != null) {
                this.prefetcher.putBack(drawable);
            }
            PresentStats.skipped.incrementAndGet();
            this.prefetcher.request();
            return;
        }
        if (drawable != null) {
            this.presentsInFlight.incrementAndGet();
            this.lastPresentNs = now;
            PresentStats.submitted.incrementAndGet();
            metalEncoder.presentTextureToDrawableDecoupled(drawable, textureView, this.presentedHandler);
            drawable.release();
        } else {
            PresentStats.skipped.incrementAndGet();
        }
        this.prefetcher.request();
    }

    /**
     * Encodes the present of a retained drawable and drops the caller's reference (the command buffer keeps its own).
     * The drawable must not outlive the command buffer in Java: releasing the last reference of a drawable whose
     * presented handler has not run yet makes Quartz call the handler synchronously inside that (critical) downcall,
     * which crashes the JVM ("wrong thread state for upcall").
     */
    private void present(final MetalCommandEncoder encoder, final CAMetalDrawable drawable, final GpuTextureView textureView, final long now) {
        this.presentsInFlight.incrementAndGet();
        this.lastPresentNs = now;
        PresentStats.submitted.incrementAndGet();
        encoder.presentTextureToDrawable(drawable, textureView, this.presentedHandler);
        drawable.release();
    }

    /**
     * Presented handler (Quartz dispatch thread): the drawable reached the display ({@code presentedTime > 0}) or was
     * dropped by the layer in favour of a newer one (VSync off). Only a read-only property call here, no release.
     */
    private void onPresented(final MemorySegment block, final MemorySegment drawable) {
        this.presentsInFlight.updateAndGet(n -> n > 0 ? n - 1 : 0);
        double presentedTime = drawable.address() != 0L ? new MTLDrawable(drawable.address()).presentedTime() : 0.0;
        boolean shown = presentedTime > 0.0;
        (shown ? PresentStats.presented : PresentStats.dropped).incrementAndGet();
        if (shown && presentedTime > PresentStats.lastPresentedTime + 0.001) {
            PresentStats.lastPresentedTime = presentedTime;
            PresentStats.displayed.incrementAndGet();
        }
    }

    /**
     * A global Objective-C block {@code void (^)(id<MTLDrawable>)} calling {@link #onPresented} (Apple block ABI:
     * isa, flags, reserved, invoke, descriptor{reserved, size}); metal-jvm's runnable blocks don't pass the argument.
     */
    private static long presentedHandlerBlock(final MetalSurface surface) {
        try {
            MethodHandle target = MethodHandles.lookup().findVirtual(MetalSurface.class, "onPresented",
                    MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class)).bindTo(surface);
            Arena arena = Arena.global(); // one block per surface; surfaces live as long as the window
            MemorySegment invoke = Linker.nativeLinker().upcallStub(target,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS), arena);
            MemorySegment descriptor = arena.allocate(16, 8);
            descriptor.set(ValueLayout.JAVA_LONG, 0, 0L);
            descriptor.set(ValueLayout.JAVA_LONG, 8, 32L);
            MemorySegment block = arena.allocate(32, 8);
            block.set(ValueLayout.ADDRESS, 0, Linker.nativeLinker().defaultLookup().findOrThrow("_NSConcreteGlobalBlock"));
            block.set(ValueLayout.JAVA_INT, 8, 1 << 28); // BLOCK_IS_GLOBAL
            block.set(ValueLayout.JAVA_INT, 12, 0);
            block.set(ValueLayout.ADDRESS, 16, invoke);
            block.set(ValueLayout.ADDRESS, 24, descriptor);
            return block.address();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Nullable
    private CAMetalDrawable acquireBlocking() {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            CAMetalDrawable drawable = this.metalLayer.nextDrawable();
            if (drawable != null) {
                drawable.retain();
            }
            return drawable;
        }
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
        // Presented handlers are FFM upcalls on a Quartz thread: one arriving after the JVM began shutting down aborts
        // it ("Could not attach thread for upcall"). Let every submitted present report back before the view goes away.
        awaitPresentsReported(500L);
        PresentStats.windowHandle = 0L;
        SDLMetal.SDL_Metal_DestroyView(this.metalView);
        awaitPresentsReported(100L);
    }

    private void awaitPresentsReported(final long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (this.presentsInFlight.get() > 0 && System.nanoTime() < deadline) {
            LockSupport.parkNanos(1_000_000L);
        }
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
            if (this.thread != null) {
                try {
                    this.thread.join(200L); // it may sit in nextDrawable() until the display hands one back
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
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
