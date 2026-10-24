/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.compat.voxy.mixin;

import de.nprmedia.nativium.compat.voxy.VoxyCompat;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Voxy far terrain with nothing visible in vanilla range (camera high above the ground at a low render distance).
 * Voxy 0.2.19 draws its LODs from a hook just before {@code ShaderChunkRenderer.end} inside Sodium's
 * {@code DefaultChunkRenderer.render}; Sodium returns early from that method when {@code prepare} found no draw
 * batches for the pass, so Voxy's hook never runs and no LODs appear. Like Voxy 0.2.20's own fix, this marks the
 * cutout pass as "should draw" after {@code prepare} whenever Voxy's render system exists, so Sodium runs its
 * (empty) cutout pass and Voxy's single hook draws the LODs. Only a flag is set: Voxy still renders from its own
 * hook exactly once per frame, and on Voxy versions that set the flag themselves this is a no-op.
 * Off with {@code -Dnativium.voxy.emptyPassRender=false}. Interop by field/method name; no Sodium or Voxy code.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer", remap = false)
public class VoxyEmptyPassMixin {
    @Shadow(remap = false)
    @Final
    private boolean[] shouldDraw;

    @Inject(method = "prepare", at = @At("TAIL"), require = 0, remap = false)
    private void nativium$voxyEmptyPass(final CallbackInfo ci) {
        if (!VoxyCompat.EMPTY_PASS_RENDER) {
            return;
        }
        int cutout = DefaultTerrainRenderPasses.getPassIndex(DefaultTerrainRenderPasses.CUTOUT);
        if (!shouldDraw[cutout] && VoxyCompat.voxyRenderSystemActive()) {
            shouldDraw[cutout] = true;
            VoxyCompat.onEmptyPassForced();
        }
    }
}
