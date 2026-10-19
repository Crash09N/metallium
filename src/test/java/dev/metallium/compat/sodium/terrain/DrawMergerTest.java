/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.compat.sodium.terrain;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DrawMergerTest {
    /** draws: {byteOffset, indexCount, baseVertex}; returns the merged draws in the same form. */
    private static long[][] run(final long[][] draws, final int[] max) {
        int n = draws.length;
        long off = MemoryUtil.nmemAlloc(8L * n + 8), cnt = MemoryUtil.nmemAlloc(4L * n + 4), base = MemoryUtil.nmemAlloc(4L * n + 4);
        try {
            for (int i = 0; i < n; i++) {
                MemoryUtil.memPutLong(off + 8L * i, draws[i][0]);
                MemoryUtil.memPutInt(cnt + 4L * i, (int) draws[i][1]);
                MemoryUtil.memPutInt(base + 4L * i, (int) draws[i][2]);
            }
            int size = DrawMerger.merge(off, cnt, base, n, max);
            long[][] out = new long[size][];
            for (int i = 0; i < size; i++) {
                out[i] = new long[]{MemoryUtil.memGetLong(off + 8L * i), MemoryUtil.memGetInt(cnt + 4L * i), MemoryUtil.memGetInt(base + 4L * i)};
            }
            return out;
        } finally {
            MemoryUtil.nmemFree(off);
            MemoryUtil.nmemFree(cnt);
            MemoryUtil.nmemFree(base);
        }
    }

    @Test
    void mergesAdjacentQuadRanges() {
        int[] max = new int[1];
        // 2 quads at vertex 0, 3 quads at vertex 8 (adjacent), 1 quad at 100 (gap), 1 quad at 104 (adjacent)
        long[][] out = run(new long[][]{{0, 12, 0}, {0, 18, 8}, {0, 6, 100}, {0, 6, 104}}, max);
        assertEquals(2, out.length);
        assertArrayEquals(new long[]{0, 30, 0}, out[0]);
        assertArrayEquals(new long[]{0, 12, 100}, out[1]);
        assertEquals(30, max[0]);
    }

    @Test
    void keepsNonZeroOffsetsAndGapsAndDropsEmpty() {
        int[] max = new int[1];
        long[][] out = run(new long[][]{{64, 6, 0}, {64, 6, 4}, {0, 0, 8}, {0, 6, 8}, {0, 6, 20}}, max);
        assertEquals(4, out.length);
        assertArrayEquals(new long[]{64, 6, 0}, out[0]);
        assertArrayEquals(new long[]{64, 6, 4}, out[1]);
        assertArrayEquals(new long[]{0, 6, 8}, out[2]);
        assertArrayEquals(new long[]{0, 6, 20}, out[3]);
        assertEquals(6, max[0]);
    }

    @Test
    void doesNotMergePartialQuads() {
        int[] max = new int[1];
        long[][] out = run(new long[][]{{0, 3, 0}, {0, 6, 0}}, max);
        assertEquals(2, out.length);
    }
}
