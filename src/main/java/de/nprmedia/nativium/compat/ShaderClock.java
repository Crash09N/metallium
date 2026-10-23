/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.compat;

/**
 * Test switch: while {@link #frozen}, Iris's per-frame clock ({@code frameCounter}, {@code frameTime},
 * {@code frameTimeCounter}) stands still (nativium-iris {@code IrisFrameCounterMixin} / {@code IrisFrameTimerMixin}).
 * With a fixed camera, world time and weather, a shader pack then renders the same image every frame once its temporal
 * effects (TAA history, smoothed exposure) have converged, so two renderings can be compared pixel by pixel
 * (terrain-cull game test). Never set in play.
 */
public final class ShaderClock {
    public static volatile boolean frozen = Boolean.getBoolean("nativium.iris.freezeClock");
    /**
     * {@code frameTime} (seconds) while frozen; 0 keeps the last real one. A long step lets exponentially smoothed
     * uniforms (eye brightness after a teleport underground) settle within a few frames.
     */
    public static volatile float frameTime;

    private ShaderClock() {
    }
}
