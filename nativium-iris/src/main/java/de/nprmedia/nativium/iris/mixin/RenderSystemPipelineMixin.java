/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.iris.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import de.nprmedia.nativium.iris.post.GbufferPasses;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Remembers which {@link RenderPipeline} a compiled pipeline came from (needed to pick the pack's gbuffers program). */
@Mixin(value = RenderSystem.class, remap = false)
abstract class RenderSystemPipelineMixin {
    @Inject(method = "getCompiledPipelineNullable", at = @At("RETURN"))
    private static void nativium$rememberSource(final RenderPipeline pipeline, final CallbackInfoReturnable<CompiledRenderPipeline> cir) {
        GbufferPasses.remember(pipeline, cir.getReturnValue());
    }
}
