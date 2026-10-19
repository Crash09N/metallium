/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.compat.sodium.mixin;

import dev.metallium.api.MetalliumRuntime;
import dev.metallium.compat.sodium.terrain.DrawMerger;
import dev.metallium.compat.sodium.terrain.TerrainOptions;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * After Sodium filled a region's draw batch (cached until its render list changes), merge adjacent quad draws so the
 * Metal backend encodes fewer {@code drawIndexedPrimitives} calls. Only for batches drawn with the shared quad index
 * buffer ({@code useIndexedTessellation == false}); translucent sorted index data is never touched.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer", remap = false)
public class DefaultChunkRendererMergeMixin {
    @Unique
    private static final int[] METALLIUM$MAX = new int[1];

    @Inject(method = "fillCommandBuffer", at = @At("RETURN"), require = 0)
    private static void metallium$mergeDraws(final MultiDrawBatch batch, final RenderRegion region, final SectionRenderDataStorage storage,
                                             final ChunkRenderList renderList, final CameraTransform camera, final TerrainRenderPass pass,
                                             final boolean useBlockFaceCulling, final boolean useIndexedTessellation, final CallbackInfo ci) {
        if (!TerrainOptions.MERGE_DRAWS || useIndexedTessellation || batch.size < 2
                || !(batch instanceof GLDrawBatchAccessor arrays) || !MetalliumRuntime.isMetalActive()) {
            return;
        }
        batch.size = DrawMerger.merge(arrays.metallium$elementPointers(), arrays.metallium$elementCounts(), arrays.metallium$baseVertices(),
                batch.size, METALLIUM$MAX);
        ((MultiDrawBatchInvoker) batch).metallium$updateMaxElementCount(METALLIUM$MAX[0]);
    }
}
