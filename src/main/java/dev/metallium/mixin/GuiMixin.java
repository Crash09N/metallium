/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.mixin;

import dev.metallium.compat.CrashGuard;
import dev.metallium.compat.OpenGlRequirements;
import dev.metallium.screen.CrashRecoveryScreen;
import dev.metallium.screen.NeedsOpenGlScreen;
import dev.metallium.screen.VoxyNativesScreen;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Before the first title screen: {@link CrashRecoveryScreen} after a Metal-related crash, else {@link NeedsOpenGlScreen}
 * when a mod needs Minecraft's OpenGL backend and the player has not chosen yet (at most one of them per session); then,
 * on the next title screen, {@link VoxyNativesScreen} when Voxy's storage libraries lack their macOS natives.
 */
@Mixin(Gui.class)
abstract class GuiMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen metallium$crashRecoveryBeforeTitle(final Screen screen) {
        if (screen instanceof TitleScreen && CrashGuard.shouldShowScreen()) {
            CrashGuard.dismissScreen();
            OpenGlRequirements.markAsked();
            return new CrashRecoveryScreen(screen, CrashGuard.recovery());
        }
        if (screen instanceof TitleScreen && OpenGlRequirements.shouldAsk()) {
            OpenGlRequirements.markAsked();
            return new NeedsOpenGlScreen(screen, OpenGlRequirements.outcome().unanswered());
        }
        if (screen instanceof TitleScreen && VoxyNativesScreen.shouldShow()) {
            return new VoxyNativesScreen(screen);
        }
        return screen;
    }
}
