/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.mixin;

import dev.metallium.compat.CrashGuard;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Crash recovery: a normal quit (Main calls exitWorldAndClose after the game loop) removes the session marker. */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Inject(method = "exitWorldAndClose", at = @At("HEAD"))
    private void metallium$cleanShutdown(final CallbackInfo ci) {
        CrashGuard.markCleanShutdown();
    }
}
