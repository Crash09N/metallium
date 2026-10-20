/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.compat.physicsmod.mixin;

import dev.metallium.compat.physicsmod.PhysicsModCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Volumetric smoke needs GPU compute (Vulkan, or OpenGL 4.3 probed with raw GL calls). On Metal with metallium-gl
 * routing Physics Mod ({@link PhysicsModCompat#gpuCompute()}) the mod's OpenGL compute path runs translated; without it
 * the feature is reported unsupported (as on macOS OpenGL 4.1) instead of aborting. Interop by name only.
 */
@Pseudo
@Mixin(targets = "net.diebuddies.render.SmokeVolumeRenderer", remap = false)
public abstract class SmokeVolumeRendererMixin {
    @Inject(method = "supportsVolumetricSmoke", at = @At("HEAD"), cancellable = true)
    private static void metallium$noComputeOnMetal(final CallbackInfoReturnable<Boolean> cir) {
        if (PhysicsModCompat.metal() && !PhysicsModCompat.gpuCompute()) {
            cir.setReturnValue(false);
        }
    }
}
