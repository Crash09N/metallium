/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl;

import dev.metallium.gl.GlObjects.Texture;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

import static org.lwjgl.opengl.GL46C.*;

/**
 * GL texture names for textures the engine owns (Minecraft's render targets, lightmap, block atlas): mods read
 * {@code GlTexture.glId()} on OpenGL and then use the name with GL calls. On Metal, the retargeted {@code glId()} lands
 * here and gets a name in the context's texture name space whose storage wraps the engine's Metal texture (never
 * released by GL). One name per engine texture object; names of closed engine textures are dropped.
 */
final class GlExternalTextures {
    private final GlContext gl;
    private final Map<Object, Integer> names = new IdentityHashMap<>();

    GlExternalTextures(final GlContext gl) {
        this.gl = gl;
    }

    /** The GL name of {@code engineTexture}, or 0 if the device cannot wrap it. */
    int nameOf(final Object engineTexture, final java.util.function.Predicate<Object> closed) {
        purge(closed);
        Integer existing = names.get(engineTexture);
        if (existing != null && gl.textures.get(existing) != null) {
            return existing;
        }
        GlDevice.TextureStore store = gl.device.externalTexture(engineTexture);
        if (store == null) {
            MetalGLLog.unsupported("engine texture " + engineTexture.getClass().getName() + " has no GL name");
            return 0;
        }
        int name = gl.textures.reserve();
        Texture texture = new Texture(name);
        texture.target = GL_TEXTURE_2D;
        texture.external = true;
        texture.immutable = true;
        texture.store = store;
        texture.format = store.format();
        texture.internalFormat = internalFormat(store.format());
        texture.width = store.width();
        texture.height = store.height();
        texture.levels = store.levels();
        texture.minFilter = store.levels() > 1 ? GL_NEAREST_MIPMAP_LINEAR : GL_NEAREST;
        texture.magFilter = GL_NEAREST;
        gl.textures.put(name, texture);
        names.put(engineTexture, name);
        return name;
    }

    private void purge(final java.util.function.Predicate<Object> closed) {
        for (Iterator<Map.Entry<Object, Integer>> it = names.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Object, Integer> entry = it.next();
            if (closed.test(entry.getKey())) {
                gl.deleteTexture(entry.getValue());
                it.remove();
            }
        }
    }

    static int internalFormat(final TexFormat format) {
        return switch (format) {
            case RGBA8 -> GL_RGBA8;
            case SRGB8_ALPHA8 -> GL_SRGB8_ALPHA8;
            case R8 -> GL_R8;
            case RG8 -> GL_RG8;
            case R16F -> GL_R16F;
            case RG16F -> GL_RG16F;
            case RGBA16F -> GL_RGBA16F;
            case R32F -> GL_R32F;
            case RG32F -> GL_RG32F;
            case RGBA32F -> GL_RGBA32F;
            case DEPTH32F -> GL_DEPTH_COMPONENT32F;
            case DEPTH32F_STENCIL8 -> GL_DEPTH32F_STENCIL8;
            default -> GL_RGBA8;
        };
    }
}
