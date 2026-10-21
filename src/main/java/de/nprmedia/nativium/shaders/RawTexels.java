/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.shaders;

import com.mojang.renderpearl.api.GpuFormat;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * Converts raw texel data as OpenGL's {@code glTexImage*} would read it ({@code format}/{@code type} pair, rows aligned
 * to {@code GL_UNPACK_ALIGNMENT} 4) into the tightly packed layout of a Metal pixel format. Used for shader-pack raw
 * textures ({@code texture.<stage>.<name> = file.dat TEXTURE_3D RGB16F 32 64 32 RGB HALF_FLOAT}): Metal has no 3-channel
 * formats, so RGB data is widened to RGBA (alpha 1, like GL's missing-component rule), and data whose component type
 * differs from the storage format (e.g. bytes into a float texture) is converted as GL does (normalised unless the
 * target is an integer format).
 */
public final class RawTexels {
    /** GL pixel types for unpacked data. */
    public enum SourceType {
        BYTE(1), UNSIGNED_BYTE(1), SHORT(2), UNSIGNED_SHORT(2), INT(4), UNSIGNED_INT(4), HALF_FLOAT(2), FLOAT(4);

        final int bytes;

        SourceType(final int bytes) {
            this.bytes = bytes;
        }

        /** By GL name ({@code UNSIGNED_BYTE}, {@code HALF_FLOAT} ...); packed types are not supported. */
        public static SourceType of(final String name) {
            try {
                return valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("pixel type " + name + " is not supported for raw textures", e);
            }
        }
    }

    private enum Kind { UNORM, SNORM, UINT, SINT, FLOAT }

    private RawTexels() {
    }

    /**
     * @param components source components per texel (1-4); {@code bgr}: the source is BGR/BGRA ordered
     * @return a new direct buffer (free with {@link MemoryUtil#memFree}) of {@code width*height*depth} texels in {@code target}
     */
    public static ByteBuffer convert(final byte[] source, final int width, final int height, final int depth, final int components, final boolean bgr,
                                     final SourceType type, final GpuFormat target) {
        if (components < 1 || components > 4) {
            throw new IllegalArgumentException("components " + components);
        }
        Kind kind = kind(target);
        int targetComponents = target.componentCount();
        int targetBits = target.blockSize() * 8 / targetComponents;
        if (target.blockSize() % targetComponents != 0 || (kind == Kind.FLOAT && targetBits != 16 && targetBits != 32)) {
            throw new IllegalArgumentException("raw textures into packed format " + target + " are not supported");
        }
        int sourceRow = align4(width * components * type.bytes);
        long needed = (long) sourceRow * height * depth;
        if (source.length < needed) {
            throw new IllegalArgumentException("raw texture has " + source.length + " bytes, " + needed + " expected for " + width + "x" + height + "x" + depth);
        }
        ByteBuffer src = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer out = MemoryUtil.memAlloc(width * height * depth * target.blockSize()).order(ByteOrder.LITTLE_ENDIAN);
        boolean integerTarget = kind == Kind.UINT || kind == Kind.SINT;
        double[] texel = new double[4];
        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < height; y++) {
                int row = (z * height + y) * sourceRow;
                for (int x = 0; x < width; x++) {
                    texel[0] = 0;
                    texel[1] = 0;
                    texel[2] = 0;
                    texel[3] = 1;
                    int base = row + x * components * type.bytes;
                    for (int c = 0; c < components; c++) {
                        int dst = bgr && c < 3 ? 2 - c : c;
                        texel[dst] = read(src, base + c * type.bytes, type, integerTarget);
                    }
                    for (int c = 0; c < targetComponents; c++) {
                        write(out, texel[c], kind, targetBits);
                    }
                }
            }
        }
        return out.flip();
    }

    private static int align4(final int bytes) {
        return (bytes + 3) & ~3;
    }

    private static Kind kind(final GpuFormat format) {
        String name = format.name();
        if (name.endsWith("_UNORM")) {
            return Kind.UNORM;
        } else if (name.endsWith("_SNORM")) {
            return Kind.SNORM;
        } else if (name.endsWith("_UINT")) {
            return Kind.UINT;
        } else if (name.endsWith("_SINT")) {
            return Kind.SINT;
        } else if (name.endsWith("_FLOAT")) {
            return Kind.FLOAT;
        }
        throw new IllegalArgumentException("raw textures into " + format + " are not supported");
    }

    /** One source component: its integer value for integer targets, else as GL converts it (normalised fixed point). */
    private static double read(final ByteBuffer src, final int at, final SourceType type, final boolean integerTarget) {
        return switch (type) {
            case BYTE -> integerTarget ? src.get(at) : Math.max(src.get(at) / 127.0, -1.0);
            case UNSIGNED_BYTE -> integerTarget ? src.get(at) & 0xFF : (src.get(at) & 0xFF) / 255.0;
            case SHORT -> integerTarget ? src.getShort(at) : Math.max(src.getShort(at) / 32767.0, -1.0);
            case UNSIGNED_SHORT -> integerTarget ? src.getShort(at) & 0xFFFF : (src.getShort(at) & 0xFFFF) / 65535.0;
            case INT -> integerTarget ? src.getInt(at) : Math.max(src.getInt(at) / 2147483647.0, -1.0);
            case UNSIGNED_INT -> integerTarget ? Integer.toUnsignedLong(src.getInt(at)) : Integer.toUnsignedLong(src.getInt(at)) / 4294967295.0;
            case HALF_FLOAT -> Float.float16ToFloat(src.getShort(at));
            case FLOAT -> src.getFloat(at);
        };
    }

    private static void write(final ByteBuffer out, final double value, final Kind kind, final int bits) {
        switch (kind) {
            case UNORM -> {
                long max = (1L << bits) - 1;
                put(out, Math.round(Math.clamp(value, 0.0, 1.0) * max), bits);
            }
            case SNORM -> {
                long max = (1L << (bits - 1)) - 1;
                put(out, Math.round(Math.clamp(value, -1.0, 1.0) * max), bits);
            }
            case UINT -> put(out, (long) Math.clamp(value, 0.0, (double) ((1L << bits) - 1)), bits);
            case SINT -> put(out, (long) Math.clamp(value, (double) -(1L << (bits - 1)), (double) ((1L << (bits - 1)) - 1)), bits);
            case FLOAT -> {
                if (bits == 16) {
                    out.putShort(Float.floatToFloat16((float) value));
                } else {
                    out.putFloat((float) value);
                }
            }
        }
    }

    private static void put(final ByteBuffer out, final long value, final int bits) {
        switch (bits) {
            case 8 -> out.put((byte) value);
            case 16 -> out.putShort((short) value);
            case 32 -> out.putInt((int) value);
            default -> throw new IllegalArgumentException(bits + "-bit components");
        }
    }
}
