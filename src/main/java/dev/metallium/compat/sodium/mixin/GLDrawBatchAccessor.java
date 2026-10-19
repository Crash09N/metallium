/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.compat.sodium.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Native draw arrays of Sodium's GL-style multi-draw batch (byte offsets as pointers, index counts, base vertices). */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gpu.device.batch.GLDrawBatch", remap = false)
public interface GLDrawBatchAccessor {
    @Accessor(value = "pElementPointer", remap = false)
    long metallium$elementPointers();

    @Accessor(value = "pElementCount", remap = false)
    long metallium$elementCounts();

    @Accessor(value = "pBaseVertex", remap = false)
    long metallium$baseVertices();
}
