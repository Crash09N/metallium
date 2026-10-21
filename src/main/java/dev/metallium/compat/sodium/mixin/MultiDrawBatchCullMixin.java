/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.compat.sodium.mixin;

import dev.metallium.compat.sodium.terrain.CullData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

/** Terrain occlusion culling: section of each sub-draw of a cached Sodium batch. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch", remap = false)
public abstract class MultiDrawBatchCullMixin implements CullData.BatchHolder {
    @Unique
    private final CullData.Batch metallium$cull = new CullData.Batch();

    @Override
    public CullData.Batch metallium$cullBatch() {
        return metallium$cull;
    }
}
