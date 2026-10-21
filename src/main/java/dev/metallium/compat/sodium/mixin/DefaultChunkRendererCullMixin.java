/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.compat.sodium.mixin;

import dev.metallium.compat.sodium.terrain.CullData;
import dev.metallium.compat.sodium.terrain.TerrainOcclusion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Terrain occlusion culling: records which section each sub-draw of a cached batch belongs to while Sodium fills the
 * batch (the batch itself only holds offsets and counts). Interop by method name; no Sodium code.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer", remap = false)
public abstract class DefaultChunkRendererCullMixin {
    @Inject(method = "fillCommandBuffer", at = @At("HEAD"), require = 0)
    private static void metallium$beginFill(final net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch batch,
                                            final net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion region,
                                            final net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage storage,
                                            final net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList list,
                                            final net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform camera,
                                            final net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass pass,
                                            final boolean faceCulling, final boolean indexed, final CallbackInfo ci) {
        if (TerrainOcclusion.RECORD) {
            CullData.beginFill(batch, region);
        }
    }

    @ModifyArg(method = "fillCommandBuffer", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/data/SectionRenderDataStorage;getDataPointer(I)J"))
    private static int metallium$section(final int sectionIndex) {
        if (TerrainOcclusion.RECORD) {
            CullData.section(sectionIndex);
        }
        return sectionIndex;
    }

    @Inject(method = {"addSharedIndexedDrawCommands", "addLocalIndexedDrawCommands"}, at = @At("RETURN"), require = 0)
    private static void metallium$afterSection(final net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch batch,
                                               final long mesh, final int mask, final CallbackInfo ci) {
        if (TerrainOcclusion.RECORD) {
            CullData.afterSectionCommands(batch);
        }
    }
}
