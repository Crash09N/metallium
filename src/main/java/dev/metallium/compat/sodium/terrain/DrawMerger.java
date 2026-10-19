/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.compat.sodium.terrain;

import org.lwjgl.system.MemoryUtil;

/**
 * Merges consecutive quad draws of a Sodium multi-draw batch whose vertex ranges are adjacent into one draw.
 *
 * <p>Metal has no multi-draw: every sub-draw of Sodium's batch costs one {@code drawIndexedPrimitives} on the CPU. Draws
 * that read the shared quad index buffer from element 0 draw quads {@code baseVertex .. baseVertex + 4*n}; two such
 * draws with {@code base2 == base1 + 4*n1} therefore equal one draw of {@code n1 + n2} quads at {@code base1}. Sodium
 * lays a section's facings out back to back and often sections of one region too, so many draws merge. Only
 * draws at element offset 0 with whole quads (counts divisible by 6) are merged; order is kept.
 *
 * <p>The arrays are Sodium's GL batch layout: 8-byte element byte offsets, 4-byte index counts, 4-byte base vertices.
 */
public final class DrawMerger {
    /** Upper bound for a merged draw (elements) so the shared quad index buffer stays small: 1M quads, 24 MiB. */
    public static final int MAX_MERGED_ELEMENTS = 6 * (1 << 20);

    private DrawMerger() {
    }

    /**
     * Merges in place. Returns the new draw count; {@code maxElements[0]} receives the largest index count written.
     */
    public static int merge(final long pOffsets, final long pCounts, final long pBaseVertices, final int size, final int[] maxElements) {
        int out = 0;
        int max = 0;
        long lastOffset = -1L;
        int lastCount = 0;
        int lastBase = 0;
        for (int i = 0; i < size; i++) {
            int count = MemoryUtil.memGetInt(pCounts + 4L * i);
            if (count <= 0) {
                continue;
            }
            long offset = MemoryUtil.memGetLong(pOffsets + 8L * i);
            int base = MemoryUtil.memGetInt(pBaseVertices + 4L * i);
            if (out > 0 && offset == 0L && lastOffset == 0L
                    && count % 6 == 0 && lastCount % 6 == 0
                    && base == lastBase + lastCount / 6 * 4
                    && (long) lastCount + count <= MAX_MERGED_ELEMENTS) {
                lastCount += count;
                MemoryUtil.memPutInt(pCounts + 4L * (out - 1), lastCount);
            } else {
                MemoryUtil.memPutLong(pOffsets + 8L * out, offset);
                MemoryUtil.memPutInt(pCounts + 4L * out, count);
                MemoryUtil.memPutInt(pBaseVertices + 4L * out, base);
                out++;
                lastOffset = offset;
                lastCount = count;
                lastBase = base;
            }
            max = Math.max(max, lastCount);
        }
        maxElements[0] = max;
        return out;
    }
}
