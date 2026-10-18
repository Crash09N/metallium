/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl;

import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** A {@link GlDevice} without a GPU: heap-free direct buffers, records every operation for assertions. */
final class RecordingDevice implements GlDevice {
    final List<DrawOp> draws = new ArrayList<>();
    final List<ClearOp> clears = new ArrayList<>();
    final List<String> uploads = new ArrayList<>();
    final List<Object> released = new ArrayList<>();
    boolean gpuBusy;
    final Texture defaultColor = new Texture(TexFormat.RGBA8, 64, 32, 1);
    final Texture defaultDepth = new Texture(TexFormat.DEPTH32F, 64, 32, 1);

    final class Buffer implements BufferStore {
        final ByteBuffer contents;

        Buffer(final long size) {
            contents = MemoryUtil.memCalloc((int) size);
        }

        @Override
        public long size() {
            return contents.capacity();
        }

        @Override
        public ByteBuffer contents() {
            return contents;
        }

        @Override
        public boolean inUseByGpu() {
            return gpuBusy;
        }
    }

    record Texture(TexFormat format, int width, int height, int levels) implements TextureStore {
    }

    record Program(String label, StageCode vertex, StageCode fragment) implements ProgramStore {
    }

    @Override
    public String name() {
        return "recording";
    }

    @Override
    public BufferStore createBuffer(final long size) {
        return new Buffer(size);
    }

    @Override
    public TextureStore createTexture(final TexFormat format, final int width, final int height, final int levels) {
        return new Texture(format, width, height, levels);
    }

    @Override
    public void writeTexture(final TextureStore texture, final int level, final int x, final int y, final int width, final int height,
                             final ByteBuffer pixels, final int bytesPerRow) {
        java.util.StringJoiner first = new java.util.StringJoiner(",");
        for (int i = 0; i < Math.min(8, pixels.remaining()); i++) first.add(String.valueOf(pixels.get(i) & 0xFF));
        uploads.add(texture.format() + " L" + level + " " + x + "," + y + " " + width + "x" + height + " row=" + bytesPerRow + " [" + first + "]");
    }

    @Override
    public void copyTexture(final TextureStore source, final TextureStore destination, final int levels) {
        uploads.add("copy " + levels);
    }

    @Override
    public void generateMipmaps(final TextureStore texture) {
        uploads.add("mipmaps " + texture.levels());
    }

    @Override
    public ProgramStore createProgram(final String label, final StageCode vertex, final StageCode fragment) {
        return new Program(label, vertex, fragment);
    }

    @Override
    public @Nullable RenderTargets defaultFramebuffer() {
        return new RenderTargets(new TextureStore[]{defaultColor}, new int[]{0}, defaultDepth, 0, false);
    }

    @Override
    public void clear(final RenderTargets targets, final ClearOp op) {
        clears.add(op);
    }

    @Override
    public void draw(final DrawOp op) {
        draws.add(op);
    }

    @Override
    public boolean readPixels(final RenderTargets targets, final int attachment, final int x, final int y, final int width, final int height,
                              final ByteBuffer rgba8) {
        return false;
    }

    @Override
    public GpuUse fence() {
        return new GpuUse() {
            @Override
            public boolean isComplete() {
                return !gpuBusy;
            }

            @Override
            public boolean await(final long timeoutNs) {
                return !gpuBusy;
            }
        };
    }

    @Override
    public void release(final Object store) {
        released.add(store);
    }

    @Override
    public void flush() {
    }

    @Override
    public void finish() {
    }
}
