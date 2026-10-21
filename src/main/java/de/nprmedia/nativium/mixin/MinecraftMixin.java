/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.mixin;

import de.nprmedia.nativium.compat.CrashGuard;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Crash recovery: the end of the constructor marks start-up as finished; a normal quit (Main calls exitWorldAndClose
 * after the game loop) removes the session marker.
 */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void nativium$startupFinished(final CallbackInfo ci) {
        CrashGuard.markStarted();
    }

    @Inject(method = "exitWorldAndClose", at = @At("RETURN"))
    private void nativium$cleanShutdown(final CallbackInfo ci) {
        CrashGuard.markCleanShutdown();
    }
}
