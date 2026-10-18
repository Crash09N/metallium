/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

/**
 * Minecraft-side GL interop for retargeted mods: {@code GlTexture.glId()} / {@code GlTextureView.glId()} on Metallium's
 * textures (main render target colour / depth, lightmap, atlases) return a metallium-gl texture name that wraps the same
 * Metal texture, so the mod can sample it, attach it to its framebuffers or copy from it with GL calls.
 */
public final class MetalGLInterop {
    private MetalGLInterop() {
    }

    /**
     * Called by retargeted code right before a cast to one of Minecraft's OpenGL backend types ({@code GlTexture},
     * {@code GlTextureView}, {@code GlBuffer}, {@code GlSampler}; {@code glType} is its internal name): on Metal an
     * engine object is replaced by a stand-in of that type carrying a metallium-gl name ({@link GlShadowObjects}). On
     * OpenGL, and for objects that already are of the type, returns {@code object} unchanged.
     */
    public static Object shadow(final Object object, final String glType) {
        if (object == null || !MetalGL.isActive()) {
            return object;
        }
        return GlShadowObjects.of(object, glType);
    }

    /** Called by retargeting bridges in place of {@code ((GlTexture) texture).glId()} on Metal. */
    public static int glId(final Object texture) {
        if (texture instanceof com.mojang.renderpearl.backend.opengl.GlTexture standIn) {
            return standIn.glId(); // a stand-in from shadow(): already a metallium-gl name
        }
        if (texture instanceof com.mojang.renderpearl.backend.opengl.GlTextureView standIn) {
            return standIn.glId();
        }
        Object engine = texture instanceof GpuTextureView view ? view.texture() : texture;
        if (texture instanceof GpuTextureView view && view.baseMipLevel() != 0) {
            MetalGLLog.unsupported("glId() of a texture view with base mip level " + view.baseMipLevel() + " (whole texture used)");
        }
        if (!(engine instanceof GpuTexture)) {
            MetalGLLog.unsupported("glId() of " + (engine == null ? "null" : engine.getClass().getName()));
            return 0;
        }
        return GlContext.current().externalTextures.nameOf(engine, o -> o instanceof GpuTexture t && t.isClosed());
    }
}
