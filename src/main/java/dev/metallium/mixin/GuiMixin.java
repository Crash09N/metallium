/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.mixin;

import dev.metallium.compat.CrashGuard;
import dev.metallium.compat.OpenGlRequirements;
import dev.metallium.screen.CrashRecoveryScreen;
import dev.metallium.screen.NeedsOpenGlScreen;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Before the first title screen: {@link CrashRecoveryScreen} after a Metal-related crash, else {@link NeedsOpenGlScreen}
 * when a mod needs Minecraft's OpenGL backend and the player has not chosen yet (at most one of them per session).
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
        return screen;
    }
}
