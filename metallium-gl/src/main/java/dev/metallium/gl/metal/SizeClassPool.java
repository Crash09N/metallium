/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl.metal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * Reuse of GPU objects whose size depends on a per-call count (indirect command buffers, indirect-argument scratch).
 * Capacities are power-of-two size classes (so a slowly growing count — Voxy's {@code maxdrawcount} rises by a few
 * hundred per frame while it loads — allocates O(log n) times instead of once per step); an object goes back to the
 * pool when the GPU is done with it ({@link #recycle}) and is reused by any later request that fits. Pooled objects
 * that no request used for {@link #IDLE_LIMIT} acquisitions are freed, so memory follows the working set when the
 * counts change. Synchronized: recycling runs from GPU-completion callbacks.
 */
final class SizeClassPool<T> {
    /** Acquisitions after which an unused pooled object is freed. */
    static final int IDLE_LIMIT = 512;

    /** A pooled object and its capacity (in the caller's units). */
    record Entry<T>(T value, int capacity) {
    }

    private record Idle<T>(Entry<T> entry, long since) {
    }

    private final int minCapacity;
    private final IntFunction<T> allocator;
    private final Consumer<T> freer;
    private final List<Idle<T>> idle = new ArrayList<>();
    private long tick;
    private long allocatedTotal;
    private int allocations;
    private int live;
    private long liveCapacity;

    SizeClassPool(final int minCapacity, final IntFunction<T> allocator, final Consumer<T> freer) {
        this.minCapacity = Integer.highestOneBit(Math.max(1, minCapacity - 1)) << 1;
        this.allocator = allocator;
        this.freer = freer;
    }

    /** The capacity class serving {@code count}: the next power of two, at least the minimum. */
    int sizeClass(final int count) {
        if (count <= minCapacity) {
            return minCapacity;
        }
        int c = Integer.highestOneBit(count - 1) << 1;
        return c <= 0 ? Integer.MAX_VALUE : c;
    }

    /** An object with capacity of at least {@code count}: the smallest fitting pooled one, else a new one. */
    synchronized Entry<T> acquire(final int count) {
        tick++;
        int best = -1;
        for (int i = 0; i < idle.size(); i++) {
            int capacity = idle.get(i).entry().capacity();
            if (capacity >= count && (best < 0 || capacity < idle.get(best).entry().capacity())) {
                best = i;
            }
        }
        Entry<T> result;
        if (best >= 0) {
            result = idle.remove(best).entry();
        } else {
            int capacity = sizeClass(count);
            result = new Entry<>(allocator.apply(capacity), capacity);
            allocatedTotal += capacity;
            allocations++;
            live++;
            liveCapacity += capacity;
        }
        evictIdle();
        return result;
    }

    /** Returns {@code entry} once the GPU no longer uses it. */
    synchronized void recycle(final Entry<T> entry) {
        idle.add(new Idle<>(entry, tick));
    }

    private void evictIdle() {
        for (int i = idle.size() - 1; i >= 0; i--) {
            Idle<T> candidate = idle.get(i);
            if (tick - candidate.since() > IDLE_LIMIT) {
                idle.remove(i);
                free(candidate.entry());
            }
        }
    }

    private void free(final Entry<T> entry) {
        live--;
        liveCapacity -= entry.capacity();
        freer.accept(entry.value());
    }

    /** Frees every pooled (idle) object. */
    synchronized void clear() {
        for (Idle<T> i : idle) {
            free(i.entry());
        }
        idle.clear();
    }

    /** Sum of the capacities of every allocation ever made. */
    synchronized long allocatedTotal() {
        return allocatedTotal;
    }

    synchronized int allocations() {
        return allocations;
    }

    /** Objects alive now (in use or pooled). */
    synchronized int live() {
        return live;
    }

    synchronized long liveCapacity() {
        return liveCapacity;
    }

    synchronized int pooled() {
        return idle.size();
    }
}
