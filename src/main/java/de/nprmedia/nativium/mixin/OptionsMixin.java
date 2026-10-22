/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.mixin;

import de.nprmedia.nativium.EngineSync;
import net.minecraft.client.Options;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reconciles Nativium's Graphics Engine with vanilla's Graphics API option right after options.txt is loaded. */
@Mixin(Options.class)
abstract class OptionsMixin {
    @Shadow
    private PreferredGraphicsApi preferredGraphicsBackendFromStartup;

    @Inject(method = "load", at = @At("TAIL"))
    private void nativium$syncEngine(final CallbackInfo ci) {
        this.preferredGraphicsBackendFromStartup = EngineSync.syncOnLoad((Options) (Object) this);
    }
}
