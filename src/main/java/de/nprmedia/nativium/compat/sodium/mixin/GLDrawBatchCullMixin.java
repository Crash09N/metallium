/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.compat.sodium.mixin;

import de.nprmedia.nativium.compat.sodium.terrain.TerrainOcclusion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Terrain occlusion culling: while the main view's opaque terrain is drawn with culling active, Nativium submits the
 * batch itself (phase 1: sections not known to be hidden; phase 2: indirect draws whose instance count the GPU test
 * decides). Interop by method name; no Sodium code.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gpu.device.batch.GLDrawBatch", remap = false)
public abstract class GLDrawBatchCullMixin {
    @Shadow
    @Final
    private long pElementPointer;
    @Shadow
    @Final
    private long pElementCount;
    @Shadow
    @Final
    private long pBaseVertex;

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true, require = 0)
    private void nativium$cullDraw(final net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext context, final CallbackInfo ci) {
        if (TerrainOcclusion.CULL && TerrainOcclusion.drawBatch(this, pElementPointer, pElementCount, pBaseVertex, context.getPass())) {
            ci.cancel();
        }
    }
}
