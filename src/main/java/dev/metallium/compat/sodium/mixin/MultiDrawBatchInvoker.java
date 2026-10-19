/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.compat.sodium.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the draw merger report the largest merged draw so Sodium sizes its shared quad index buffer for it. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch", remap = false)
public interface MultiDrawBatchInvoker {
    @Invoker(value = "updateMaxElementCount", remap = false)
    void metallium$updateMaxElementCount(int elementCount);
}
