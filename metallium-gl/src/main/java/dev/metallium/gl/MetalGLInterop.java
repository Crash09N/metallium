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

    /** Called by retargeting bridges in place of {@code ((GlTexture) texture).glId()} on Metal. */
    public static int glId(final Object texture) {
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
