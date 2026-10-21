/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.gametest;

/**
 * Time the render thread spends in Fabric's client-gametest lock-step barrier (client tick frame, integrated server tick
 * and test thread meet once per tick; {@code LockstepTickMixin}).
 *
 * <p>Why it matters (2026-10-21): benchmarks sporadically ran at 20-24 fps with every frame ~50 ms (p50 48.8-50.0 ms)
 * while GPU time was 3 ms and the identical rerun gave ~138 fps. The barrier has two stable states. Normally the server
 * arrives first and the client never waits. If the client's tick clock ends up just before the server's tick schedule,
 * the client waits for the server's next tick, and because Minecraft's tick clock counts that wait, the next frame is
 * due a tick again: every frame waits ~45 ms and the game test renders one frame per tick. A real game has no barrier.
 * With {@link #FIX} the wait is taken out of the client's tick clock, so it cannot lock (a real client's tick clock does
 * not see the server either).
 */
public final class LockstepStats {
    /** {@code -Dnativium.bench.lockstepFix} (default: on in bench mode). */
    public static final boolean FIX = Boolean.parseBoolean(System.getProperty("nativium.bench.lockstepFix",
            String.valueOf("bench".equals(System.getProperty("nativium.gametest.mode")))));

    public static volatile long tickFrames;
    public static volatile long waitNs;
    public static volatile long maxWaitNs;
    /** Frames that waited more than 20 ms in the barrier. */
    public static volatile long longWaits;
    /** Milliseconds taken out of the client's tick clock ({@link #FIX}). */
    public static volatile long shiftedMs;

    private LockstepStats() {
    }

    /** Render thread only. */
    public static void record(final long ns) {
        tickFrames++;
        waitNs += ns;
        if (ns > maxWaitNs) {
            maxWaitNs = ns;
        }
        if (ns > 20_000_000L) {
            longWaits++;
        }
    }

    public static long[] snapshot() {
        return new long[]{tickFrames, waitNs, longWaits, shiftedMs};
    }

    public static String describe(final long[] a, final long[] b, final double seconds) {
        long frames = b[0] - a[0];
        return String.format(java.util.Locale.ROOT,
                "bench-lockstep: fix=%s tick_frames_per_s=%.1f barrier_wait_ms_per_tick_frame=%.2f barrier_wait_share=%.3f long_waits=%d shifted_ms=%d max_wait_ms=%.1f",
                FIX, frames / seconds, frames == 0 ? 0.0 : (b[1] - a[1]) / 1e6 / frames, (b[1] - a[1]) / 1e9 / seconds,
                b[2] - a[2], b[3] - a[3], maxWaitNs / 1e6);
    }
}
