/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.backend;

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
    static volatile long windowHandle;

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

    /** Rendered frames that were not presented (VSync off). */
    public static long skipped() {
        return skipped.get();
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
