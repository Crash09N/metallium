/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.compat.physicsmod.mixin;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import dev.metallium.compat.physicsmod.PhysicsModCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * GPU liquid simulation probes non-Vulkan devices with raw OpenGL calls. On Metal with metallium-gl routing Physics Mod
 * ({@link PhysicsModCompat#gpuCompute()}) the probe and the OpenGL compute path run translated; without it the probe
 * answers "unsupported" (the mod's own public {@code Availability(boolean, String)} record) instead of aborting.
 */
@Pseudo
@Mixin(targets = "net.diebuddies.physics.liquid.compute.LiquidComputeSupport", remap = false)
public abstract class LiquidComputeSupportMixin {
    private static final String AVAILABILITY = "net.diebuddies.physics.liquid.compute.LiquidComputeSupport$Availability";
    private static MethodHandle metallium$unsupported;

    @Inject(method = "query", at = @At("HEAD"), cancellable = true)
    private static void metallium$noComputeOnMetal(final GpuDeviceBackend backend, final CallbackInfoReturnable<Object> cir) {
        if (!PhysicsModCompat.metal() || PhysicsModCompat.gpuCompute()) return;
        try {
            if (metallium$unsupported == null) {
                Class<?> type = Class.forName(AVAILABILITY, false, LiquidComputeSupportMixin.class.getClassLoader());
                metallium$unsupported = MethodHandles.publicLookup().findConstructor(type, MethodType.methodType(void.class, boolean.class, String.class));
            }
            cir.setReturnValue(metallium$unsupported.invoke(false, PhysicsModCompat.METAL_REASON));
        } catch (Throwable t) {
            throw new IllegalStateException("Metallium: could not report Physics Mod liquid compute as unsupported on Metal", t);
        }
    }
}
