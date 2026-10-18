/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.compat.physicsmod.mixin;

import dev.metallium.compat.physicsmod.PhysicsModCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The FFT ocean picks its compute backend with {@code instanceof VulkanDevice / GlDevice} and rejects every other device.
 * On Metal with {@link PhysicsModCompat#gpuCompute()} it gets the mod's OpenGL compute backend, which metallium-gl runs.
 * Interop by name only (the mod's classes are reached reflectively).
 */
@Pseudo
@Mixin(targets = "net.diebuddies.render.fft.OceanComputeBackend", remap = false)
public interface OceanComputeBackendMixin {
    @Inject(method = "create", at = @At("HEAD"), cancellable = true)
    private static void metallium$glBackendOnMetal(final CallbackInfoReturnable<Object> cir) {
        if (PhysicsModCompat.gpuCompute()) {
            cir.setReturnValue(PhysicsModCompat.newOceanGlBackend());
        }
    }

    @Inject(method = "unavailableReason", at = @At("HEAD"), cancellable = true)
    private static void metallium$glAvailabilityOnMetal(final CallbackInfoReturnable<String> cir) {
        if (PhysicsModCompat.gpuCompute()) {
            cir.setReturnValue(PhysicsModCompat.oceanGlUnavailableReason());
        }
    }
}
