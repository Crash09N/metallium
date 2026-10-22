/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.iris.post;

import de.nprmedia.nativium.backend.NativiumInterop;
import de.nprmedia.nativium.iris.NativiumIris;
import io.github.kokodio.metaljvm.metal.MTLBuffer;
import io.github.kokodio.metaljvm.metal.MTLResourceOptions;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.irisshaders.iris.gl.buffer.BuiltShaderStorageInfo;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

/**
 * Stage 5: the pack's shader storage buffers ({@code bufferObject.N = size [relative scaleX scaleY]} or
 * {@code bufferObject.N = size file}), like Iris's {@code ShaderStorageBufferHolder}: created once per pipeline and kept
 * across frames (packs accumulate data in them), zero-filled or filled from the pack file, relative ones re-created
 * (zeroed) when the screen size changes. Shared storage on Apple's unified memory, untracked like all Nativium
 * resources (ordering comes from the frame fence and serial compute dispatch).
 */
final class StorageBuffers implements AutoCloseable {
    private final Int2ObjectMap<BuiltShaderStorageInfo> infos;
    private final @Nullable MTLBuffer[] buffers;
    private int width;
    private int height;

    StorageBuffers(final Int2ObjectMap<BuiltShaderStorageInfo> infos, final int width, final int height) {
        this.infos = infos;
        int max = -1;
        for (int index : infos.keySet()) {
            max = Math.max(max, index);
        }
        this.buffers = new MTLBuffer[max + 1];
        this.width = width;
        this.height = height;
        infos.forEach((index, info) -> buffers[index] = create(index, info));
    }

    boolean isEmpty() {
        return buffers.length == 0;
    }

    private MTLBuffer create(final int index, final BuiltShaderStorageInfo info) {
        long size = info.relative()
                ? (long) (width * info.scaleX()) * (long) (height * info.scaleY()) * info.size()
                : info.size();
        size = Math.max(16, (size + 15) & ~15L);
        MTLBuffer buffer = NativiumInterop.device().newBuffer(size,
                MTLResourceOptions.StorageModeShared | NativiumInterop.hazardTrackingOption());
        if (buffer == null) {
            throw new IllegalStateException("could not allocate SSBO " + index + " (" + size + " bytes)");
        }
        MemorySegment contents = buffer.contents().reinterpret(size);
        contents.fill((byte) 0);
        byte[] data = info.content();
        if (data != null && !info.relative()) {
            MemorySegment.copy(MemorySegment.ofArray(data), 0, contents, 0, Math.min(data.length, size));
        }
        NativiumIris.LOGGER.debug("[nativium-iris] SSBO {}: {} bytes{}", index, size, info.relative() ? " (screen relative)" : "");
        return buffer;
    }

    /** Re-creates the screen-relative buffers after a resize (content is lost, as in Iris). */
    void resize(final int newWidth, final int newHeight) {
        if (newWidth == width && newHeight == height) {
            return;
        }
        width = newWidth;
        height = newHeight;
        infos.forEach((index, info) -> {
            if (info.relative()) {
                MTLBuffer old = buffers[index];
                if (old != null) {
                    NativiumInterop.releaseAfterGpu(old::release);
                }
                buffers[index] = create(index, info);
            }
        });
    }

    @Nullable
    MTLBuffer get(final int index) {
        return index >= 0 && index < buffers.length ? buffers[index] : null;
    }

    @Override
    public void close() {
        for (int i = 0; i < buffers.length; i++) {
            MTLBuffer buffer = buffers[i];
            if (buffer != null) {
                NativiumInterop.releaseAfterGpu(buffer::release);
                buffers[i] = null;
            }
        }
    }
}
