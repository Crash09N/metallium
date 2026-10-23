/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.iris.mixin;

import de.nprmedia.nativium.compat.ShaderClock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Test switch {@link ShaderClock#frozen}: {@code frameTimeCounter} stands still (waving plants, clouds and water keep
 * one phase) and {@code frameTime} is {@link ShaderClock#frameTime} (or the last real one), so Iris's smoothed
 * uniforms ({@code eyeBrightnessSmooth}, packs' {@code smooth()}) converge in a fixed number of frames.
 */
@Mixin(targets = "net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer", remap = false)
abstract class IrisFrameTimerMixin {
    @Shadow
    private float lastFrameTime;

    @Inject(method = "beginFrame", at = @At("HEAD"), cancellable = true)
    private void nativium$frozen(final long frameStartTime, final CallbackInfo ci) {
        if (ShaderClock.frozen) {
            if (ShaderClock.frameTime > 0) {
                lastFrameTime = ShaderClock.frameTime;
            }
            ci.cancel();
        }
    }
}
