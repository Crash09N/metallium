/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.gl.metal;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Pooling policy behind nativium-gl's indirect command buffers (no GPU). */
class SizeClassPoolTest {
    @Test
    void sizeClassesArePowersOfTwo() {
        SizeClassPool<Object> pool = new SizeClassPool<>(1000, c -> new Object(), o -> { });
        assertEquals(1024, pool.sizeClass(1));
        assertEquals(1024, pool.sizeClass(1024));
        assertEquals(2048, pool.sizeClass(1025));
        assertEquals(131072, pool.sizeClass(79872));
    }

    /**
     * The owner's session (2026-10-19): Voxy's maxdrawcount grew by 1024 every few frames up to 79 872 with two
     * MDI-count calls per frame; the old pool rounded to multiples of 1024 and never freed the outgrown ICBs (6.1 million
     * commands allocated in 5 minutes). Now: O(log n) allocations, and what stays alive is the working set.
     */
    @Test
    void growingCountsAllocateLogarithmicallyAndOutgrownObjectsAreFreed() {
        AtomicInteger freed = new AtomicInteger();
        SizeClassPool<Object> pool = new SizeClassPool<>(1024, c -> new Object(), o -> freed.incrementAndGet());
        int framesInFlight = 3;
        ArrayDeque<List<SizeClassPool.Entry<Object>>> inFlight = new ArrayDeque<>();
        int frames = 0;
        for (int draws = 1024; draws <= 79872 + 1024 * 2000; draws += 64) { // grows, then stays at the top for many frames
            int count = Math.min(draws, 79872);
            List<SizeClassPool.Entry<Object>> frame = new ArrayList<>();
            frame.add(pool.acquire(count));
            frame.add(pool.acquire(count / 3));
            frame.forEach(e -> assertTrue(e.capacity() >= count / 3));
            assertTrue(frame.getFirst().capacity() >= count);
            inFlight.add(frame);
            if (inFlight.size() > framesInFlight) {
                inFlight.poll().forEach(pool::recycle); // the GPU finished that frame
            }
            frames++;
        }
        assertTrue(frames > 20000);
        assertTrue(pool.allocations() <= 40, "allocations: " + pool.allocations());
        // Steady state: 2 calls x (frames in flight + 1) objects, of the two classes in use (131072 and 32768).
        assertTrue(pool.live() <= 2 * (framesInFlight + 1), "live: " + pool.live());
        assertTrue(pool.liveCapacity() <= (framesInFlight + 1) * (131072L + 32768L), "live capacity: " + pool.liveCapacity());
        assertEquals(pool.allocations() - pool.live(), freed.get());
    }

    @Test
    void reusesTheSmallestFittingObject() {
        SizeClassPool<String> pool = new SizeClassPool<>(16, c -> "c" + c, o -> { });
        SizeClassPool.Entry<String> big = pool.acquire(1000);
        SizeClassPool.Entry<String> small = pool.acquire(10);
        pool.recycle(big);
        pool.recycle(small);
        assertSame(small, pool.acquire(16));
        assertSame(big, pool.acquire(17));
        assertEquals(2, pool.allocations());
    }
}
