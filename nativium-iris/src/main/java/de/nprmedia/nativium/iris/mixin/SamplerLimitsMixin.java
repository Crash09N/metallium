/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.iris.mixin;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import de.nprmedia.nativium.api.NativiumRuntime;
import net.irisshaders.iris.gl.sampler.SamplerLimits;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL43C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Answers the GL limit queries with Metal's per-stage limits (Apple GPUs: 16 sampler states, 8 colour attachments,
 * Iris's 16 SSBO bindings - bufferObject indices are at most 12).
 */
@Mixin(value = SamplerLimits.class, remap = false)
abstract class SamplerLimitsMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_getInteger(I)I"))
    private int nativium$limit(final int pname) {
        if (!NativiumRuntime.isMetalActive()) {
            return GlStateManager._getInteger(pname);
        }
        return switch (pname) {
            case GL20C.GL_MAX_TEXTURE_IMAGE_UNITS -> 16;
            case GL20C.GL_MAX_DRAW_BUFFERS -> 8;
            case GL43C.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS -> 16; // stage 5: SSBOs are Metal buffers
            default -> 0;
        };
    }
}
