/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.compat.betterf3.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * BetterF3 (MIT, compatibility only) shows "GPU: xx%" from {@code Minecraft.getGpuUtilization()}, but vanilla only
 * measures it (GPU timer query per frame) while its own {@code gpu_utilization} F3 entry is enabled, which the default
 * F3 profile does not do; BetterF3 never enables it, so it shows "N/A" on every backend. With BetterF3 installed this
 * also measures while the F3 overlay is open. Without BetterF3 nothing changes.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftGpuUtilizationMixin {
    @Unique
    private static final boolean METALLIUM$BETTERF3 = FabricLoader.getInstance().isModLoaded("betterf3");

    @WrapOperation(
            method = "renderFrame(Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/debug/DebugScreenEntryList;isCurrentlyEnabled(Lnet/minecraft/resources/Identifier;)Z"),
            require = 0
    )
    private boolean metallium$measureGpuForBetterF3(final DebugScreenEntryList entries, final Identifier id, final Operation<Boolean> original) {
        return original.call(entries, id)
                || METALLIUM$BETTERF3 && id.equals(DebugScreenEntries.GPU_UTILIZATION) && entries.isOverlayVisible();
    }
}
