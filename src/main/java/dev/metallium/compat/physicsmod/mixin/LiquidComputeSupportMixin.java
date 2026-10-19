/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
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
 * GPU liquid simulation probes non-Vulkan devices with raw OpenGL calls (aborts on Metal). On Metal the probe answers
 * "unsupported" with the mod's own public {@code Availability(boolean, String)} record. Interop by name only.
 */
@Pseudo
@Mixin(targets = "net.diebuddies.physics.liquid.compute.LiquidComputeSupport", remap = false)
public abstract class LiquidComputeSupportMixin {
    private static final String AVAILABILITY = "net.diebuddies.physics.liquid.compute.LiquidComputeSupport$Availability";
    private static MethodHandle metallium$unsupported;

    @Inject(method = "query", at = @At("HEAD"), cancellable = true)
    private static void metallium$noComputeOnMetal(final GpuDeviceBackend backend, final CallbackInfoReturnable<Object> cir) {
        if (!PhysicsModCompat.metal()) return;
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
