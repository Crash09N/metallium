/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.backend;

import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_DisplayMode;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Present counters of the Metal surface, for benchmarks and tests: presents submitted, presents that reached the
 * display or were dropped (drawable presented handler; at most one shown per refresh when composited), and rendered
 * frames whose present was skipped (VSync off, a newer frame wins). Counters are cumulative; callers take differences.
 */
public final class PresentStats {
    static final AtomicLong submitted = new AtomicLong();
    static final AtomicLong presented = new AtomicLong();
    static final AtomicLong dropped = new AtomicLong();
    static final AtomicLong skipped = new AtomicLong();
    static final AtomicLong displayed = new AtomicLong();
    /** Sum over reported presents of (presented handler time - present submit time), for the mean present latency. */
    static final AtomicLong handlerLatencyNs = new AtomicLong();
    static final java.util.concurrent.ConcurrentLinkedQueue<Long> submitTimes = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** presentedTime of the last displayed present (presented handlers run on one Quartz thread). */
    static volatile double lastPresentedTime;
    static volatile long windowHandle;
    /** Sizes at the last present (drawable, layer drawableSize, presented texture, configured surface), for benches. */
    static volatile String sizes = "none";

    /** Drawable / layer / presented texture / surface sizes at a recent present ("none" before the first). */
    public static String sizes() {
        return sizes;
    }

    private PresentStats() {
    }

    /** Presents submitted to the display. */
    public static long submitted() {
        return submitted.get();
    }

    /** Presents that reached the screen (presented handler with a non-zero presentedTime): displayed frames. */
    public static long presented() {
        return presented.get();
    }

    /** Presents the layer dropped for a newer one before they were shown (presented handler, presentedTime 0). */
    public static long dropped() {
        return dropped.get();
    }

    /**
     * Presents shown at a different display time than the previous shown one: frames that actually reached the screen
     * (at most one per refresh; {@link #presented} also counts presents the compositor latched and then replaced).
     */
    public static long displayed() {
        return displayed.get();
    }

    /** Sum of submit-to-presented-handler times in ns over all reported presents (see {@link #presented}, {@link #dropped}). */
    public static long handlerLatencyNs() {
        return handlerLatencyNs.get();
    }

    /** Rendered frames that were not presented (VSync off). */
    public static long skipped() {
        return skipped.get();
    }

    private static long lineNs;
    private static long lineSubmitted;
    private static long lineSkipped;
    private static long lineDisplayed;
    private static String line;

    /**
     * F3 line (render thread), refreshed once a second: frames shown on the display per second next to frames rendered
     * per second. With VSync off the game (and Minecraft's own fps counter) renders as fast as it can; at most about
     * one frame per display refresh can be shown, the others are rendered but not presented. Null on OpenGL.
     */
    public static String f3Line() {
        if (windowHandle == 0L) {
            return null;
        }
        long now = System.nanoTime();
        if (line == null || now - lineNs >= 1_000_000_000L) {
            long sub = submitted.get();
            long skip = skipped.get();
            long disp = displayed.get();
            if (lineNs != 0L) {
                double s = (now - lineNs) / 1e9;
                line = String.format(java.util.Locale.ROOT, "Metal present: %.0f shown/s of %.0f rendered/s",
                        (disp - lineDisplayed) / s, (sub - lineSubmitted + skip - lineSkipped) / s);
            } else {
                line = "Metal present: measuring";
            }
            lineNs = now;
            lineSubmitted = sub;
            lineSkipped = skip;
            lineDisplayed = disp;
        }
        return line;
    }

    /**
     * Window visibility as SDL reports it ("visible", or a list of occluded/hidden/minimized), plus "focused".
     * An occluded window has every present dropped (presented ~0), so benchmarks must reject such runs.
     */
    public static String windowState() {
        long window = windowHandle;
        if (window == 0L) {
            return "unknown";
        }
        long flags = SDLVideo.SDL_GetWindowFlags(window);
        StringBuilder state = new StringBuilder();
        if ((flags & SDLVideo.SDL_WINDOW_OCCLUDED) != 0) state.append("occluded,");
        if ((flags & SDLVideo.SDL_WINDOW_HIDDEN) != 0) state.append("hidden,");
        if ((flags & SDLVideo.SDL_WINDOW_MINIMIZED) != 0) state.append("minimized,");
        if (state.isEmpty()) state.append("visible,");
        state.append((flags & SDLVideo.SDL_WINDOW_INPUT_FOCUS) != 0 ? "focused" : "unfocused");
        return state.toString();
    }

    /** Refresh rate in Hz of the display the game window is on, or 0 if unknown. Call on the render thread. */
    public static float displayRefreshHz() {
        long window = windowHandle;
        if (window == 0L) {
            return 0f;
        }
        int display = SDLVideo.SDL_GetDisplayForWindow(window);
        if (display == 0) {
            return 0f;
        }
        SDL_DisplayMode mode = SDLVideo.SDL_GetCurrentDisplayMode(display);
        return mode == null ? 0f : mode.refresh_rate();
    }
}
