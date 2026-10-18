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
    static long blitCommands;
    static long clearEncoders;
    static long draws;
    static long stagedWrites;
    static long stagedWriteBytes;
    static long directWrites;
    static long orphanWrites;
    static long orphanCopyBytes;
    static long submitWaitNs;
    private static long submits;

    private PerfCounters() {
    }

    static void submitted() {
        if (++submits < WINDOW) {
            return;
        }
        double n = submits;
        Metallium.LOGGER.info(String.format(
                "Metallium perf (per submit, %d submits): renderEnc=%.1f blitEnc=%.1f blitCmds=%.1f clearEnc=%.1f draws=%.0f"
                        + " stagedWrites=%.1f (%.1f KiB) directWrites=%.1f orphanWrites=%.1f (copy %.1f KiB) submitWait=%.3f ms",
                submits, renderEncoders / n, blitEncoders / n, blitCommands / n, clearEncoders / n, draws / n,
                stagedWrites / n, stagedWriteBytes / n / 1024.0, directWrites / n, orphanWrites / n, orphanCopyBytes / n / 1024.0,
                submitWaitNs / n / 1e6));
        renderEncoders = blitEncoders = blitCommands = clearEncoders = draws = 0;
        stagedWrites = stagedWriteBytes = directWrites = orphanWrites = orphanCopyBytes = submitWaitNs = 0;
        submits = 0;
    }
}
