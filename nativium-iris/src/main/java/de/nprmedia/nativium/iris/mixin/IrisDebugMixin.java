/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.iris.mixin;

import de.nprmedia.nativium.api.NativiumRuntime;
import net.irisshaders.iris.Iris;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;

/** GL debug callbacks do not exist on Metal; keep only the config persistence of {@code Iris.setDebug}. */
@Mixin(value = Iris.class, remap = false)
abstract class IrisDebugMixin {
    @Inject(method = "setDebug", at = @At("HEAD"), cancellable = true)
    private static void nativium$setDebugOnMetal(final boolean enable, final CallbackInfo ci) {
        if (!NativiumRuntime.isMetalActive()) {
            return;
        }
        try {
            Iris.getIrisConfig().setDebugEnabled(enable);
            Iris.getIrisConfig().save();
        } catch (IOException e) {
            Iris.logger.fatal("Failed to save config!", e);
        }
        ci.cancel();
    }
}
