/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.compat.sodium.mixin;

import dev.metallium.backend.terrain.TerrainPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Measurement only ({@code -Dmetallium.perfCounters=true}): CPU time Sodium spends building and submitting terrain draws.
 * Interop by method name; no Sodium code.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer", remap = false)
public class DefaultChunkRendererPerfMixin {
    @Unique
    private long metallium$renderStart;
    @Unique
    private long metallium$prepareStart;

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void metallium$renderHead(final CallbackInfo ci) {
        if (TerrainPerf.ENABLED) {
            metallium$renderStart = System.nanoTime();
        }
    }

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void metallium$renderReturn(final CallbackInfo ci) {
        if (TerrainPerf.ENABLED) {
            TerrainPerf.renderNs += System.nanoTime() - metallium$renderStart;
            TerrainPerf.renderCalls++;
        }
    }

    @Inject(method = "prepare", at = @At("HEAD"), require = 0)
    private void metallium$prepareHead(final CallbackInfo ci) {
        if (TerrainPerf.ENABLED) {
            metallium$prepareStart = System.nanoTime();
        }
    }

    @Inject(method = "prepare", at = @At("RETURN"), require = 0)
    private void metallium$prepareReturn(final CallbackInfo ci) {
        if (TerrainPerf.ENABLED) {
            TerrainPerf.prepareNs += System.nanoTime() - metallium$prepareStart;
        }
    }
}
