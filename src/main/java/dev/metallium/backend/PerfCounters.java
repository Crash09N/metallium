/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.backend;

import dev.metallium.Metallium;

/**
 * Optional per-submit counters for backend work (enable with {@code -Dmetallium.perfCounters=true}).
 * Render-thread only; plain fields, no synchronisation. Logs averages every {@link #WINDOW} submits.
 */
final class PerfCounters {
    static final boolean ENABLED = Boolean.getBoolean("metallium.perfCounters");
    private static final int WINDOW = 600;

    static long renderEncoders;
    static long blitEncoders;
    /** writeToBuffer, copyToBuffer, writeToTexture, copyBufferToTexture, copyTextureToBuffer, copyTextureToTexture. */
    static final long[] blitKinds = new long[6];
    static long clearEncoders;
    static long draws;
    static long stagedWrites;
    static long stagedWriteBytes;
    static long directWrites;
    static long orphanWrites;
    static long orphanCopyBytes;
    static long submitWaitNs;
    static long gpuNs;
    private static long submits;

    private static String kinds(double n) {
        StringBuilder sb = new StringBuilder();
        for (long k : blitKinds) {
            sb.append(sb.isEmpty() ? "" : ",").append(String.format("%.2f", k / n));
        }
        return sb.toString();
    }

    private PerfCounters() {
    }

    static void submitted() {
        if (++submits < WINDOW) {
            return;
        }
        double n = submits;
        Metallium.LOGGER.info(String.format(
                "Metallium perf (per submit, %d submits): renderEnc=%.1f blitEnc=%.1f blits[wb,cb,wt,bt,tb,tt]=%s clearEnc=%.1f draws=%.0f"
                        + " stagedWrites=%.1f (%.1f KiB) directWrites=%.1f orphanWrites=%.1f (copy %.1f KiB) submitWait=%.3f ms gpu=%.3f ms",
                submits, renderEncoders / n, blitEncoders / n, kinds(n), clearEncoders / n, draws / n,
                stagedWrites / n, stagedWriteBytes / n / 1024.0, directWrites / n, orphanWrites / n, orphanCopyBytes / n / 1024.0,
                submitWaitNs / n / 1e6, gpuNs / n / 1e6));
        java.util.Arrays.fill(blitKinds, 0);
        renderEncoders = blitEncoders = clearEncoders = draws = 0;
        stagedWrites = stagedWriteBytes = directWrites = orphanWrites = orphanCopyBytes = submitWaitNs = gpuNs = 0;
        submits = 0;
    }
}
