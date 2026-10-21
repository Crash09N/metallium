/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.compat.sodium.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.metallium.compat.sodium.terrain.IrisShadowState;
import dev.metallium.compat.sodium.terrain.TerrainOcclusion;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Terrain occlusion culling hooks ({@code -Dmetallium.terrain.gpuCull}) around the main view's opaque group (solid +
 * cutout, one render pass): HEAD picks phase 1's sections, RETURN tests all sections against the depth so far and, when
 * culling, runs the layer again as phase 2 (the deferred sub-draws as GPU-decided indirect draws). Never in Iris's
 * shadow pass. Interop by method name; no Sodium code.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class SodiumWorldRendererCullMixin {
    @Shadow
    private RenderSectionManager renderSectionManager;

    @Shadow
    public abstract void drawChunkLayer(RenderPass pass, ChunkSectionLayerGroup group, ChunkRenderMatrices matrices,
                                        double x, double y, double z, GpuSampler terrainSampler, OitStage stage);

    @Inject(method = "drawChunkLayer", at = @At("HEAD"), require = 0)
    private void metallium$beforeOpaque(final RenderPass pass, final ChunkSectionLayerGroup group, final ChunkRenderMatrices matrices,
                                        final double x, final double y, final double z, final GpuSampler terrainSampler,
                                        final OitStage stage, final CallbackInfo ci) {
        if (TerrainOcclusion.CULL && group == ChunkSectionLayerGroup.OPAQUE && renderSectionManager != null
                && !TerrainOcclusion.inPhaseTwo() && !IrisShadowState.inShadowPass()) {
            TerrainOcclusion.beforeOpaque(renderSectionManager.getRenderLists(), x, y, z);
        }
    }

    @Inject(method = "drawChunkLayer", at = @At("RETURN"), require = 0)
    private void metallium$afterOpaque(final RenderPass pass, final ChunkSectionLayerGroup group, final ChunkRenderMatrices matrices,
                                       final double x, final double y, final double z, final GpuSampler terrainSampler,
                                       final OitStage stage, final CallbackInfo ci) {
        if (!TerrainOcclusion.ENABLED || group != ChunkSectionLayerGroup.OPAQUE || renderSectionManager == null
                || TerrainOcclusion.inPhaseTwo() || IrisShadowState.inShadowPass()) {
            return;
        }
        if (TerrainOcclusion.afterOpaque(renderSectionManager.getRenderLists(), matrices, x, y, z)) {
            try {
                drawChunkLayer(pass, group, matrices, x, y, z, terrainSampler, stage);
            } finally {
                TerrainOcclusion.afterPhaseTwo();
            }
        }
    }
}
