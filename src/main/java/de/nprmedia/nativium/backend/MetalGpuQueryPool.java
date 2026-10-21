/*
 * Portions of this file are derived from Metallum (https://github.com/kokodio/metallum),
 * Copyright (c) 2026 kokodio, licensed under the MIT License (see LICENSES/MIT-metallum.txt).
 * Modifications Copyright (c) 2026 Crash09N – NPR Media, licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */
package de.nprmedia.nativium.backend;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import io.github.kokodio.metaljvm.metal.MTLCommandBuffer;
import io.github.kokodio.metaljvm.metal.MTLCommandBufferStatus;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.OptionalLong;

/**
 * GPU timestamp queries (vanilla's {@code TimerQuery} behind F3 "GPU: xx%", also Tracy GPU zones).
 *
 * <p>Metal has no "write a timestamp here" command (Apple GPUs sample counters only at encoder/stage boundaries), and
 * Nativium records a whole frame into one {@link MTLCommandBuffer}. So a timestamp resolves at command-buffer
 * granularity: the first timestamp written into a command buffer reads its {@code GPUStartTime}, later ones its
 * {@code GPUEndTime} (see {@link MetalCommandEncoder#writeGpuTimestamp}). For vanilla's begin/end pair around a frame this
 * is the GPU time of that frame's command buffer(s). Values are host-time nanoseconds ({@code CFTimeInterval} seconds *
 * 1e9, the mach clock that {@link System#nanoTime()} also uses on macOS), so {@code timestampPeriod} stays 1.0.
 *
 * <p>No CPU stall: a pending slot stays empty until its command buffer has completed (polled via {@code status}),
 * typically 1-3 frames later, as with GL/Vulkan queries. Render thread only.
 */
@Environment(EnvType.CLIENT)
final class MetalGpuQueryPool implements GpuQueryPool {
    private final OptionalLong[] values;
    /** Retained command buffer whose start/end time a slot waits for, or null. */
    private final @Nullable MTLCommandBuffer[] pending;
    private final boolean[] pendingEnd;

    MetalGpuQueryPool(final int size) {
        this.values = new OptionalLong[size];
        this.pending = new MTLCommandBuffer[size];
        this.pendingEnd = new boolean[size];

        for (int i = 0; i < size; i++) {
            this.values[i] = OptionalLong.empty();
        }
    }

    void setValue(final int index, final long value) {
        clearPending(index);
        this.values[index] = OptionalLong.of(value);
    }

    /**
     * Slot {@code index} takes the GPU start ({@code end == false}) or end time of {@code buffer} once that has completed;
     * the buffer is retained until then.
     */
    void setPending(final int index, final MTLCommandBuffer buffer, final boolean end) {
        clearPending(index);
        buffer.retain();
        this.pending[index] = buffer;
        this.pendingEnd[index] = end;
        this.values[index] = OptionalLong.empty();
    }

    private void clearPending(final int index) {
        MTLCommandBuffer buffer = this.pending[index];
        if (buffer != null) {
            this.pending[index] = null;
            buffer.release();
        }
    }

    private OptionalLong resolve(final int index) {
        MTLCommandBuffer buffer = this.pending[index];
        if (buffer != null) {
            MTLCommandBufferStatus status = buffer.status();
            if (status == MTLCommandBufferStatus.Completed) {
                double seconds = this.pendingEnd[index] ? buffer.GPUEndTime() : buffer.GPUStartTime();
                this.values[index] = OptionalLong.of((long) (seconds * 1e9));
                clearPending(index);
            } else if (status == MTLCommandBufferStatus.Error) {
                // A failed buffer has no GPU times, but a value is needed or vanilla's TimerQuery waits forever.
                this.values[index] = OptionalLong.of(System.nanoTime());
                clearPending(index);
            }
        }
        return this.values[index];
    }

    @Override
    public int size() {
        return this.values.length;
    }

    @Override
    public @NonNull OptionalLong getValue(final int index) {
        return resolve(index);
    }

    @Override
    public OptionalLong @NonNull [] getValues(final int index, final int count) {
        OptionalLong[] result = new OptionalLong[count];
        for (int i = 0; i < count; i++) {
            result[i] = resolve(index + i);
        }
        return result;
    }

    @Override
    public void close() {
        for (int i = 0; i < this.values.length; i++) {
            clearPending(i);
            this.values[i] = OptionalLong.empty();
        }
    }
}
