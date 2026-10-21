/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.compat.sodium.mixin;

import de.nprmedia.nativium.backend.terrain.TerrainPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Measurement only ({@code -Dnativium.perfCounters=true}): CPU time Sodium spends building and submitting terrain draws.
 * Interop by method name; no Sodium code.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer", remap = false)
public class DefaultChunkRendererPerfMixin {
    @Unique
    private long nativium$renderStart;
    @Unique
    private long nativium$prepareStart;

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void nativium$renderHead(final CallbackInfo ci) {
        if (TerrainPerf.ENABLED) {
            nativium$renderStart = System.nanoTime();
        }
    }

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void nativium$renderReturn(final CallbackInfo ci) {
        if (TerrainPerf.ENABLED) {
            TerrainPerf.renderNs += System.nanoTime() - nativium$renderStart;
            TerrainPerf.renderCalls++;
        }
    }

    @Inject(method = "prepare", at = @At("HEAD"), require = 0)
    private void nativium$prepareHead(final CallbackInfo ci) {
        if (TerrainPerf.ENABLED) {
            nativium$prepareStart = System.nanoTime();
        }
    }

    @Inject(method = "prepare", at = @At("RETURN"), require = 0)
    private void nativium$prepareReturn(final CallbackInfo ci) {
        if (TerrainPerf.ENABLED) {
            TerrainPerf.prepareNs += System.nanoTime() - nativium$prepareStart;
        }
    }
}
