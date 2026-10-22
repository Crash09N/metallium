/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.iris.mixin;

import de.nprmedia.nativium.iris.post.MetalPostPipeline;
import net.irisshaders.iris.Iris;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stage 3: Iris's "a shader pack renders the world" switch ({@code isPackInUseQuick}, normally
 * {@code instanceof IrisRenderingPipeline}) is also true for {@link MetalPostPipeline} when the pack's gbuffers programs
 * run. It enables Iris's extended vertex formats (entity/block IDs, tangents, mid-texcoords in vanilla buffers), the
 * in-level hand rendering ({@code gbuffers_hand}) and the entity/block-entity ID capture, exactly as on OpenGL.
 */
@Mixin(value = Iris.class, remap = false)
abstract class IrisPackInUseMixin {
    @Inject(method = "isPackInUseQuick", at = @At("RETURN"), cancellable = true)
    private static void nativium$gbuffersPackInUse(final CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && Iris.getPipelineManager().getPipelineNullable() instanceof MetalPostPipeline pipeline && pipeline.rendersGbuffers()) {
            cir.setReturnValue(true);
        }
    }
}
