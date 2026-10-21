/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.shadercache;

import java.util.concurrent.atomic.LongAdder;

/**
 * Process-wide counters for shader / pipeline compile work (always on; a few adds per pipeline). Printed by the game
 * test and on request ({@link #summary()}); {@link #snapshot()} lets a caller report the work done in a time window.
 */
public final class ShaderCacheStats {
    /** GLSL to SPIR-V compiles (shaderc) run by the core translator (Iris adapter, nativium-gl). */
    public static final LongAdder glslCompiles = new LongAdder();
    public static final LongAdder glslNs = new LongAdder();
    public static final LongAdder glslCacheHits = new LongAdder();
    /** SPIR-V to MSL translations (SPIRV-Cross) of renderpearl pipelines. */
    public static final LongAdder mslTranslations = new LongAdder();
    public static final LongAdder mslNs = new LongAdder();
    public static final LongAdder mslCacheHits = new LongAdder();
    /** MSL front-end compiles ({@code newLibraryWithSource}). */
    public static final LongAdder libraryCompiles = new LongAdder();
    public static final LongAdder libraryNs = new LongAdder();
    /** Pipeline state objects created ({@code newRenderPipelineState}), any thread. */
    public static final LongAdder pipelineStates = new LongAdder();
    public static final LongAdder pipelineStateNs = new LongAdder();
    /** Of those: created lazily on the render thread while encoding (a new attachment layout); these stutter. */
    public static final LongAdder renderThreadPipelineStates = new LongAdder();
    public static final LongAdder renderThreadPipelineStateNs = new LongAdder();
    /** Binary archive lookups: hit = the GPU binary came from the archive (no backend compile). */
    public static final LongAdder archiveHits = new LongAdder();
    public static final LongAdder archiveMisses = new LongAdder();
    /** Attachment-layout variants built ahead of time (on the compile thread) from the recorded variant index. */
    public static final LongAdder prewarmedVariants = new LongAdder();
    /** MSL functions taken precompiled from the binary archive (no front-end compile). */
    public static final LongAdder archivedFunctions = new LongAdder();

    private ShaderCacheStats() {
    }

    /** All counters in a fixed order (see {@link #summary(long[])}). */
    public static long[] snapshot() {
        return new long[] {
                glslCompiles.sum(), glslNs.sum(), glslCacheHits.sum(),
                mslTranslations.sum(), mslNs.sum(), mslCacheHits.sum(),
                libraryCompiles.sum(), libraryNs.sum(),
                pipelineStates.sum(), pipelineStateNs.sum(),
                renderThreadPipelineStates.sum(), renderThreadPipelineStateNs.sum(),
                archiveHits.sum(), archiveMisses.sum(), prewarmedVariants.sum(), archivedFunctions.sum()
        };
    }

    /** Work done since {@code since} (a {@link #snapshot()}), or in total if {@code since} is null. */
    public static long[] delta(final long[] since) {
        long[] now = snapshot();
        if (since != null) {
            for (int i = 0; i < now.length; i++) {
                now[i] -= since[i];
            }
        }
        return now;
    }

    public static String summary() {
        return summary(snapshot());
    }

    public static String summary(final long[] s) {
        return String.format(
                "glsl=%d (%.0f ms, %d cached) spirv->msl=%d (%.0f ms, %d cached) mslLibraries=%d (%.0f ms, %d functions from archive)"
                        + " pso=%d (%.0f ms) renderThreadPso=%d (%.0f ms) archive hit/miss=%d/%d prewarmedVariants=%d",
                s[0], s[1] / 1e6, s[2], s[3], s[4] / 1e6, s[5], s[6], s[7] / 1e6, s[15], s[8], s[9] / 1e6,
                s[10], s[11] / 1e6, s[12], s[13], s[14]);
    }
}
