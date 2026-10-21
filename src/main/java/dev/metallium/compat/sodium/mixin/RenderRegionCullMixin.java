/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.compat.sodium.mixin;

import dev.metallium.compat.sodium.terrain.CullData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

/** Terrain occlusion culling: per-region test entries and latest results. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion", remap = false)
public abstract class RenderRegionCullMixin implements CullData.RegionHolder {
    @Unique
    private CullData.Region metallium$cull;

    @Override
    public CullData.Region metallium$cullRegion() {
        if (metallium$cull == null) {
            metallium$cull = new CullData.Region();
        }
        return metallium$cull;
    }
}
