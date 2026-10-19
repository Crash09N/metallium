/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.compat.sodium.mixin;

import dev.metallium.compat.sodium.terrain.TerrainOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Measurement only ({@code -Dmetallium.terrain.skipDraws=all/main/shadow}): Sodium's terrain multi-draws are not submitted at all.
 * The CPU/GPU time difference to a normal run is the upper bound of what any terrain culling could save.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gpu.device.batch.GLDrawBatch", remap = false)
public class GLDrawBatchDrawMixin {
    @Inject(method = "draw", at = @At("HEAD"), cancellable = true, require = 0)
    private void metallium$skipDraws(final CallbackInfo ci) {
        if (TerrainOptions.skipDraw()) {
            ci.cancel();
        }
    }
}
