/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.iris.mixin;

import de.nprmedia.nativium.compat.ShaderClock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test switch {@link ShaderClock#frozen}: {@code frameCounter} stands still (deterministic shader-pack frames). */
@Mixin(targets = "net.irisshaders.iris.uniforms.SystemTimeUniforms$FrameCounter", remap = false)
abstract class IrisFrameCounterMixin {
    @Inject(method = "beginFrame", at = @At("HEAD"), cancellable = true)
    private void nativium$frozen(final CallbackInfo ci) {
        if (ShaderClock.frozen) {
            ci.cancel();
        }
    }
}
