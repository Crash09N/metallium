/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.gl.fixture;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.mojang.renderpearl.backend.opengl.GlTextureView;

/** Stand-in for mod code using Minecraft's OpenGL backend internals (GlStateManager, GlTexture.glId()). */
public final class McGlUser {
    private McGlUser() {
    }

    public static void enableDepthTest() {
        GlStateManager._enableDepthTest();
    }

    public static int viewId(final GpuTextureView view) {
        return ((GlTextureView) view).glId();
    }

    public static int textureId(final GpuTexture texture) {
        return ((GlTexture) texture).glId();
    }
}
