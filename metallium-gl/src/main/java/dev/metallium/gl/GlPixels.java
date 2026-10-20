/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.gl;

import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.opengl.GL46C.*;

/** Converts client pixel data (GL format/type + unpack state) into a texture's storage layout. */
final class GlPixels {
    private GlPixels() {
    }

    /** GL_UNPACK_* state that affects how client rows are read. */
    record Unpack(int alignment, int rowLength, int skipRows, int skipPixels) {
        static final Unpack DEFAULT = new Unpack(4, 0, 0, 0);
    }

    static int channels(final int format) {
        return switch (format) {
            case GL_RED, GL_RED_INTEGER, GL_DEPTH_COMPONENT, GL_GREEN, GL_BLUE, GL_ALPHA -> 1;
            case GL_RG, GL_RG_INTEGER, GL_DEPTH_STENCIL -> 2;
            case GL_RGB, GL_BGR, GL_RGB_INTEGER -> 3;
            case GL_RGBA, GL_BGRA, GL_RGBA_INTEGER, GL_BGRA_INTEGER -> 4;
            default -> -1;
        };
    }

    static int componentBytes(final int type) {
        return switch (type) {
            case GL_UNSIGNED_BYTE, GL_BYTE -> 1;
            case GL_UNSIGNED_SHORT, GL_SHORT, GL_HALF_FLOAT -> 2;
            case GL_UNSIGNED_INT, GL_INT, GL_FLOAT -> 4;
            default -> -1;
        };
    }

    /** Bytes per client pixel, or -1 for unsupported format/type combinations. */
    static int clientPixelBytes(final int format, final int type) {
        if (type == GL_UNSIGNED_INT_8_8_8_8_REV || type == GL_UNSIGNED_INT_8_8_8_8) {
            return channels(format) == 4 ? 4 : -1;
        }
        if (type == GL_UNSIGNED_INT_24_8 || type == GL_FLOAT_32_UNSIGNED_INT_24_8_REV) {
            return format == GL_DEPTH_STENCIL ? (type == GL_UNSIGNED_INT_24_8 ? 4 : 8) : -1;
        }
        int channels = channels(format);
        int bytes = componentBytes(type);
        return channels < 0 || bytes < 0 ? -1 : channels * bytes;
    }

    /** Client bytes needed for a {@code width x height} upload under {@code unpack}. */
    static long clientBytes(final int format, final int type, final int width, final int height, final Unpack unpack) {
        int pixel = clientPixelBytes(format, type);
        long stride = rowStride(pixel, width, unpack);
        return (unpack.skipRows() + Math.max(0, height - 1)) * stride + (long) (unpack.skipPixels() + width) * pixel;
    }

    private static long rowStride(final int pixel, final int width, final Unpack unpack) {
        int rowPixels = unpack.rowLength() > 0 ? unpack.rowLength() : width;
        long raw = (long) rowPixels * pixel;
        int align = Math.max(1, unpack.alignment());
        return (raw + align - 1) / align * align;
    }

    /**
     * Returns tightly packed pixels in {@code target}'s layout (caller frees with {@link MemoryUtil#memFree}), or null if
     * this format/type → storage conversion is not supported.
     */
    static @Nullable ByteBuffer convert(final ByteBuffer source, final int format, final int type, final int width, final int height,
                                        final Unpack unpack, final TexFormat target) {
        int pixel = clientPixelBytes(format, type);
        if (pixel < 0) {
            return null;
        }
        long stride = rowStride(pixel, width, unpack);
        ByteBuffer out = MemoryUtil.memAlloc(width * height * target.bytesPerPixel).order(ByteOrder.nativeOrder());
        ByteBuffer src = source.duplicate().order(ByteOrder.nativeOrder());
        int base = source.position();
        if (source.isDirect() && sameLayout(format, type, target)) {
            int rowBytes = width * target.bytesPerPixel;
            long srcAddress = MemoryUtil.memAddress(source);
            long dstAddress = MemoryUtil.memAddress(out);
            for (int y = 0; y < height; y++) {
                long rowStart = (unpack.skipRows() + y) * stride + (long) unpack.skipPixels() * pixel;
                MemoryUtil.memCopy(srcAddress + rowStart, dstAddress + (long) y * rowBytes, rowBytes);
            }
            return out;
        }
        float[] rgba = new float[4];
        for (int y = 0; y < height; y++) {
            long rowStart = base + (unpack.skipRows() + y) * stride + (long) unpack.skipPixels() * pixel;
            for (int x = 0; x < width; x++) {
                int at = (int) (rowStart + (long) x * pixel);
                int dst = (y * width + x) * target.bytesPerPixel;
                if (!decode(src, at, format, type, rgba)) {
                    MemoryUtil.memFree(out);
                    return null;
                }
                encode(out, dst, target, rgba);
            }
        }
        return out;
    }

    /** Client pixels already have the storage layout: rows can be copied as they are. */
    private static boolean sameLayout(final int format, final int type, final TexFormat target) {
        boolean channelOrder = format == GL_RGBA || format == GL_RG || format == GL_RED || format == GL_RED_INTEGER;
        if (!channelOrder || channels(format) != target.channels) {
            return false;
        }
        return switch (target.kind) {
            case UNORM8 -> type == GL_UNSIGNED_BYTE;
            case HALF -> type == GL_HALF_FLOAT;
            case FLOAT -> type == GL_FLOAT;
            case UINT -> format == GL_RED_INTEGER && type == GL_UNSIGNED_INT;
            case SINT -> format == GL_RED_INTEGER && type == GL_INT;
            case DEPTH, DEPTH_STENCIL -> false;
        };
    }

    /** Decodes one client pixel into RGBA floats (missing channels: 0,0,0,1 like GL). Depth goes into {@code rgba[0]}. */
    private static boolean decode(final ByteBuffer src, final int at, final int format, final int type, final float[] rgba) {
        rgba[0] = 0f;
        rgba[1] = 0f;
        rgba[2] = 0f;
        rgba[3] = 1f;
        if (type == GL_UNSIGNED_INT_8_8_8_8_REV || type == GL_UNSIGNED_INT_8_8_8_8) {
            int packed = src.getInt(at);
            int b0 = type == GL_UNSIGNED_INT_8_8_8_8_REV ? packed & 0xFF : packed >>> 24;
            int b1 = type == GL_UNSIGNED_INT_8_8_8_8_REV ? (packed >>> 8) & 0xFF : (packed >>> 16) & 0xFF;
            int b2 = type == GL_UNSIGNED_INT_8_8_8_8_REV ? (packed >>> 16) & 0xFF : (packed >>> 8) & 0xFF;
            int b3 = type == GL_UNSIGNED_INT_8_8_8_8_REV ? packed >>> 24 : packed & 0xFF;
            float[] c = {b0 / 255f, b1 / 255f, b2 / 255f, b3 / 255f};
            assign(format, c, 4, rgba);
            return true;
        }
        if (type == GL_UNSIGNED_INT_24_8) {
            rgba[0] = (src.getInt(at) >>> 8) / 16777215f;
            return true;
        }
        if (type == GL_FLOAT_32_UNSIGNED_INT_24_8_REV) {
            rgba[0] = src.getFloat(at);
            return true;
        }
        int channels = channels(format);
        int bytes = componentBytes(type);
        float[] c = new float[channels];
        for (int i = 0; i < channels; i++) {
            int p = at + i * bytes;
            c[i] = switch (type) {
                case GL_UNSIGNED_BYTE -> (src.get(p) & 0xFF) / 255f;
                case GL_BYTE -> Math.max(-1f, src.get(p) / 127f);
                case GL_UNSIGNED_SHORT -> (src.getShort(p) & 0xFFFF) / 65535f;
                case GL_SHORT -> Math.max(-1f, src.getShort(p) / 32767f);
                case GL_UNSIGNED_INT -> (float) ((src.getInt(p) & 0xFFFFFFFFL) / 4294967295.0);
                case GL_INT -> (float) Math.max(-1.0, src.getInt(p) / 2147483647.0);
                case GL_HALF_FLOAT -> Float.float16ToFloat(src.getShort(p));
                case GL_FLOAT -> src.getFloat(p);
                default -> Float.NaN;
            };
        }
        assign(format, c, channels, rgba);
        return true;
    }

    private static void assign(final int format, final float[] c, final int n, final float[] rgba) {
        switch (format) {
            case GL_BGRA, GL_BGRA_INTEGER, GL_BGR -> {
                rgba[0] = c[2];
                rgba[1] = c[1];
                rgba[2] = c[0];
                if (n == 4) rgba[3] = c[3];
            }
            case GL_GREEN -> rgba[1] = c[0];
            case GL_BLUE -> rgba[2] = c[0];
            case GL_ALPHA -> rgba[3] = c[0];
            default -> System.arraycopy(c, 0, rgba, 0, Math.min(n, 4));
        }
    }

    private static void encode(final ByteBuffer out, final int at, final TexFormat target, final float[] rgba) {
        switch (target.kind) {
            case UNORM8 -> {
                for (int i = 0; i < target.channels; i++) {
                    out.put(at + i, (byte) Math.round(Math.clamp(rgba[i], 0f, 1f) * 255f));
                }
            }
            case HALF -> {
                for (int i = 0; i < target.channels; i++) {
                    out.putShort(at + i * 2, Float.floatToFloat16(rgba[i]));
                }
            }
            case FLOAT -> {
                for (int i = 0; i < target.channels; i++) {
                    out.putFloat(at + i * 4, rgba[i]);
                }
            }
            case DEPTH -> out.putFloat(at, rgba[0]);
            case UINT, SINT -> out.putInt(at, (int) rgba[0]);
            case DEPTH_STENCIL -> {
                out.putFloat(at, rgba[0]);
                out.putInt(at + 4, 0);
            }
        }
    }
}
