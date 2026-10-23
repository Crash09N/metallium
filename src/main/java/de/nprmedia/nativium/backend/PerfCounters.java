/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.backend;

import de.nprmedia.nativium.Nativium;

/**
 * Optional per-submit counters for backend work (enable with {@code -Dnativium.perfCounters=true}).
 * Render-thread only; plain fields, no synchronisation. Logs averages every {@link #WINDOW} submits.
 */
final class PerfCounters {
    static final boolean ENABLED = Boolean.getBoolean("nativium.perfCounters");
    /** With {@code -Dnativium.perfTrace=true}: logs every encoder of one submit per window (why it was opened). */
    private static final boolean TRACE = ENABLED && Boolean.getBoolean("nativium.perfTrace");
    /** True while the submit being encoded is traced. */
    static boolean tracing;
    private static final int WINDOW = 600;

    static long renderEncoders;
    static long blitEncoders;
    /** writeToBuffer, copyToBuffer, writeToTexture, copyBufferToTexture, copyTextureToBuffer, copyTextureToTexture. */
    static final long[] blitKinds = new long[6];
    static long clearEncoders;
    /** Render-encoder attachments ended with {@code MTLStoreActionDontCare} (contents dead, see MetalCommandEncoder). */
    static long discardedStores;
    /** Uploads recorded in the upload command buffer instead of in place, and its blit encoders. */
    static long hoistedUploads;
    static long uploadEncoders;
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
    /** Terrain: RenderPass.multiDrawIndexed calls (Sodium: one per region and pass), their sub-draws and encode time. */
    static long multiDraws;
    static long subDraws;
    static long multiDrawNs;
    private static long submits;
    private static long lastSubmitNs;
    private static long frameNs;

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
            Nativium.LOGGER.info("Nativium trace: " + event);
        }
    }

    /** The first caller outside the backend package (for traces). */
    static String caller() {
        return StackWalker.getInstance().walk(frames -> frames
                .filter(f -> !f.getClassName().startsWith("de.nprmedia.nativium.backend.") && !f.getClassName().startsWith("com.mojang.renderpearl."))
                .findFirst().map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName() + ":" + f.getLineNumber())
                .orElse("?"));
    }

    static void submitted() {
        tracing = TRACE && submits == WINDOW / 2 - 1;
        if (tracing) {
            Nativium.LOGGER.info("Nativium trace: ---- begin submit ----");
        }
        long now = System.nanoTime();
        if (lastSubmitNs != 0L) {
            frameNs += now - lastSubmitNs;
            de.nprmedia.nativium.backend.terrain.TerrainPerf.totalFrameNs += now - lastSubmitNs;
        }
        de.nprmedia.nativium.backend.terrain.TerrainPerf.totalSubmits++;
        lastSubmitNs = now;
        if (++submits < WINDOW) {
            return;
        }
        double n = submits;
        Nativium.LOGGER.info(String.format(
                "Nativium perf (per submit, %d submits): renderEnc=%.1f blitEnc=%.1f (coalesced %.1f) blits[wb,cb,wt,bt,tb,tt]=%s clearEnc=%.1f dontCareStores=%.2f hoisted=%.1f (uploadEnc %.1f) draws=%.0f"
                        + " stagedWrites=%.1f (%.1f KiB) directWrites=%.1f orphanWrites=%.1f (copy %.1f KiB) submitWait=%.3f ms gpu=%.3f ms",
                submits, renderEncoders / n, blitEncoders / n, coalescedBlits / n, kinds(n), clearEncoders / n, discardedStores / n, hoistedUploads / n, uploadEncoders / n, draws / n,
                stagedWrites / n, stagedWriteBytes / n / 1024.0, directWrites / n, orphanWrites / n, orphanCopyBytes / n / 1024.0,
                submitWaitNs / n / 1e6, gpuNs / n / 1e6));
        Nativium.LOGGER.info(String.format(
                "Nativium terrain perf (per submit): multiDraws=%.1f subDraws=%.0f multiDrawEncode=%.3f ms frame=%.3f ms %s",
                multiDraws / n, subDraws / n, multiDrawNs / n / 1e6, frameNs / n / 1e6, de.nprmedia.nativium.backend.terrain.TerrainPerf.formatAndReset(n)));
        multiDraws = subDraws = multiDrawNs = frameNs = 0;
        java.util.Arrays.fill(blitKinds, 0);
        renderEncoders = blitEncoders = clearEncoders = discardedStores = hoistedUploads = uploadEncoders = coalescedBlits = draws = 0;
        stagedWrites = stagedWriteBytes = directWrites = orphanWrites = orphanCopyBytes = submitWaitNs = gpuNs = 0;
        submits = 0;
    }
}
