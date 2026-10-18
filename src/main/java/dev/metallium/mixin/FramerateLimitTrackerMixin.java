/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.metallium.power.PowerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Power mode FPS cap: lowers vanilla's frame limit (260 = unlimited) to the active profile's cap. Applies live. */
@Mixin(FramerateLimitTracker.class)
abstract class FramerateLimitTrackerMixin {
    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void metallium$powerCap(final CallbackInfoReturnable<Integer> cir) {
        int cap = PowerManager.fpsCap();
        if (cap > 0 && cir.getReturnValueI() > cap) {
            cir.setReturnValue(cap);
        }
    }
}
