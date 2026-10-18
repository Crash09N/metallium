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
    /** With {@code -Dmetallium.perfTrace=true}: logs every encoder of one submit per window (why it was opened). */
    private static final boolean TRACE = ENABLED && Boolean.getBoolean("metallium.perfTrace");
    /** True while the submit being encoded is traced. */
    static boolean tracing;
    private static final int WINDOW = 600;

    static long renderEncoders;
    static long blitEncoders;
    /** writeToBuffer, copyToBuffer, writeToTexture, copyBufferToTexture, copyTextureToBuffer, copyTextureToTexture. */
    static final long[] blitKinds = new long[6];
    static long clearEncoders;
    /** Copies that reused an already open blit encoder. */
    static long coalescedBlits;
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

    static void trace(final String event) {
        if (tracing) {
            Metallium.LOGGER.info("Metallium trace: " + event);
        }
    }

    /** The first caller outside the backend package (for traces). */
    static String caller() {
        return StackWalker.getInstance().walk(frames -> frames
                .filter(f -> !f.getClassName().startsWith("dev.metallium.backend.") && !f.getClassName().startsWith("com.mojang.renderpearl."))
                .findFirst().map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName() + ":" + f.getLineNumber())
                .orElse("?"));
    }

    static void submitted() {
        tracing = TRACE && submits == WINDOW / 2 - 1;
        if (tracing) {
            Metallium.LOGGER.info("Metallium trace: ---- begin submit ----");
        }
        if (++submits < WINDOW) {
            return;
        }
        double n = submits;
        Metallium.LOGGER.info(String.format(
                "Metallium perf (per submit, %d submits): renderEnc=%.1f blitEnc=%.1f (coalesced %.1f) blits[wb,cb,wt,bt,tb,tt]=%s clearEnc=%.1f draws=%.0f"
                        + " stagedWrites=%.1f (%.1f KiB) directWrites=%.1f orphanWrites=%.1f (copy %.1f KiB) submitWait=%.3f ms gpu=%.3f ms",
                submits, renderEncoders / n, blitEncoders / n, coalescedBlits / n, kinds(n), clearEncoders / n, draws / n,
                stagedWrites / n, stagedWriteBytes / n / 1024.0, directWrites / n, orphanWrites / n, orphanCopyBytes / n / 1024.0,
                submitWaitNs / n / 1e6, gpuNs / n / 1e6));
        java.util.Arrays.fill(blitKinds, 0);
        renderEncoders = blitEncoders = clearEncoders = coalescedBlits = draws = 0;
        stagedWrites = stagedWriteBytes = directWrites = orphanWrites = orphanCopyBytes = submitWaitNs = gpuNs = 0;
        submits = 0;
    }
}
