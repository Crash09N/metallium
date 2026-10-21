/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import de.nprmedia.nativium.power.PowerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Power mode FPS cap: lowers vanilla's frame limit (260 = unlimited) to the active profile's cap. Applies live. */
@Mixin(FramerateLimitTracker.class)
abstract class FramerateLimitTrackerMixin {
    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void nativium$powerCap(final CallbackInfoReturnable<Integer> cir) {
        if (!de.nprmedia.nativium.api.NativiumRuntime.isMetalActive()) {
            return; // Metal device creation failed and vanilla fell back: no Nativium behaviour on OpenGL
        }
        int cap = PowerManager.fpsCap();
        if (cap > 0 && cir.getReturnValueI() > cap) {
            cir.setReturnValue(cap);
        }
    }
}
