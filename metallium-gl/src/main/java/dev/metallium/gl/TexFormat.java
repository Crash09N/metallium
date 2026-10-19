/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl;

import org.jspecify.annotations.Nullable;

import static org.lwjgl.opengl.GL46C.*;

/** Storage formats metallium-gl can allocate, with the GL internal formats that map to each. */
public enum TexFormat {
    RGBA8(4, 4, Kind.UNORM8),
    SRGB8_ALPHA8(4, 4, Kind.UNORM8),
    R8(1, 1, Kind.UNORM8),
    RG8(2, 2, Kind.UNORM8),
    R16F(2, 1, Kind.HALF),
    RG16F(4, 2, Kind.HALF),
    RGBA16F(8, 4, Kind.HALF),
    R32F(4, 1, Kind.FLOAT),
    RG32F(8, 2, Kind.FLOAT),
    RGBA32F(16, 4, Kind.FLOAT),
    DEPTH32F(4, 1, Kind.DEPTH),
    DEPTH32F_STENCIL8(8, 2, Kind.DEPTH_STENCIL),
    /** Integer formats: storage images (atomics) and texelFetch; no filtering, no client conversion. */
    R32UI(4, 1, Kind.UINT),
    R32I(4, 1, Kind.SINT);

    public enum Kind { UNORM8, HALF, FLOAT, DEPTH, DEPTH_STENCIL, UINT, SINT }

    public final int bytesPerPixel;
    public final int channels;
    public final Kind kind;

    TexFormat(final int bytesPerPixel, final int channels, final Kind kind) {
        this.bytesPerPixel = bytesPerPixel;
        this.channels = channels;
        this.kind = kind;
    }

    public boolean isDepth() {
        return kind == Kind.DEPTH || kind == Kind.DEPTH_STENCIL;
    }

    /** Storage for a GL internal format, or null if unsupported. Unsized formats pick the 8-bit / float default. */
    public static @Nullable TexFormat fromInternalFormat(final int internalFormat) {
        return switch (internalFormat) {
            case GL_RGBA, GL_RGBA8, GL_RGB, GL_RGB8, 4, 3 -> RGBA8; // RGB has no Metal format: stored as RGBA, alpha 1
            case GL_SRGB8_ALPHA8, GL_SRGB_ALPHA, GL_SRGB8, GL_SRGB -> SRGB8_ALPHA8;
            case GL_RED, GL_R8, 1 -> R8;
            case GL_RG, GL_RG8, 2 -> RG8;
            case GL_R16F -> R16F;
            case GL_RG16F -> RG16F;
            case GL_RGBA16F, GL_RGB16F -> RGBA16F;
            case GL_R32F -> R32F;
            case GL_RG32F -> RG32F;
            case GL_RGBA32F, GL_RGB32F -> RGBA32F;
            case GL_DEPTH_COMPONENT, GL_DEPTH_COMPONENT16, GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT32, GL_DEPTH_COMPONENT32F -> DEPTH32F;
            case GL_DEPTH_STENCIL, GL_DEPTH24_STENCIL8, GL_DEPTH32F_STENCIL8 -> DEPTH32F_STENCIL8;
            case GL_R32UI -> R32UI;
            case GL_R32I -> R32I;
            default -> null;
        };
    }
}
