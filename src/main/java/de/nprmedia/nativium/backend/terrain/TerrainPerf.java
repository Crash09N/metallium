/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.backend.terrain;

/**
 * Terrain counters filled by the Sodium compat hooks when {@code -Dnativium.perfCounters=true}; logged with the backend's
 * per-submit perf line. Render thread only.
 */
public final class TerrainPerf {
    public static final boolean ENABLED = Boolean.getBoolean("nativium.perfCounters");

    /** Wall time inside Sodium's chunk renderer {@code render(...)} (all terrain passes incl. shadow), nanoseconds. */
    public static long renderNs;
    /** Calls of Sodium's chunk renderer {@code render(...)}. */
    public static long renderCalls;
    /** Wall time inside Sodium's chunk renderer {@code prepare(...)} (draw-list building), nanoseconds. */
    public static long prepareNs;

    private TerrainPerf() {
    }

    public static String formatAndReset(final double submits) {
        String s = String.format("sodiumRender=%.3f ms (%.1f calls) sodiumPrepare=%.3f ms",
                renderNs / submits / 1e6, renderCalls / submits, prepareNs / submits / 1e6);
        renderNs = renderCalls = prepareNs = 0;
        return s;
    }
}
