/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.mixin;

import dev.metallium.compat.CrashGuard;
import dev.metallium.screen.CrashRecoveryScreen;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Crash recovery: shows {@link CrashRecoveryScreen} instead of the first title screen after a Metal-related crash. */
@Mixin(Gui.class)
abstract class GuiMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen metallium$crashRecoveryBeforeTitle(final Screen screen) {
        if (screen instanceof TitleScreen && CrashGuard.shouldShowScreen()) {
            CrashGuard.dismissScreen();
            return new CrashRecoveryScreen(screen, CrashGuard.recovery());
        }
        return screen;
    }
}
