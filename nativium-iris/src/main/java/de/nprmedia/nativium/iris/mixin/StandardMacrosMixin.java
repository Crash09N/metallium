/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.iris.mixin;

import de.nprmedia.nativium.api.NativiumRuntime;
import net.irisshaders.iris.gl.shader.StandardMacros;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Packs use {@code MC_OS_MAC} as "Apple OpenGL 4.1, no compute/images/SSBOs" and switch features off (Complementary
 * Unbound forces {@code COLORED_LIGHTING 0} and shows "not supported on Apple devices"). On Metal those features work,
 * so this reports {@code MC_OS_UNKNOWN} instead - by default (owner decision 2026-10-18); config
 * {@code irisHideMacOs = false} or {@code -Dnativium.iris.hideMacOs=false} restores {@code MC_OS_MAC}.
 */
@Mixin(value = StandardMacros.class, remap = false)
abstract class StandardMacrosMixin {
    @Inject(method = "getOsString", at = @At("HEAD"), cancellable = true)
    private static void nativium$hideMacOs(final CallbackInfoReturnable<String> cir) {
        if (NativiumRuntime.isMetalActive() && hideMacOs()) {
            cir.setReturnValue("MC_OS_UNKNOWN");
        }
    }

    private static boolean hideMacOs() {
        String property = System.getProperty("nativium.iris.hideMacOs");
        return property != null ? Boolean.parseBoolean(property) : de.nprmedia.nativium.NativiumConfig.INSTANCE.irisHideMacOs;
    }
}
