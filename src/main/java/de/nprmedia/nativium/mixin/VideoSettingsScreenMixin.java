/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.mixin;

import de.nprmedia.nativium.NativiumConfig;
import de.nprmedia.nativium.power.PowerManager;
import de.nprmedia.nativium.power.PowerMode;
import de.nprmedia.nativium.screen.NativiumStatusScreen;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Without Sodium (whose own video settings carry the Nativium tab): a "Nativium" section at the end of vanilla's
 * Video Settings with the power mode and a button to the status screen.
 */
@Mixin(VideoSettingsScreen.class)
abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
    private VideoSettingsScreenMixin(final Screen lastScreen, final Options options, final Component title) {
        super(lastScreen, options, title);
    }

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void nativium$addSection(final CallbackInfo ci) {
        if (this.list == null) {
            return;
        }
        this.list.addHeader(Component.literal("Nativium"));
        CycleButton<PowerMode> power = CycleButton.builder(mode -> Component.literal(PowerManager.name(mode)), PowerManager.mode())
                .withValues(PowerMode.values())
                .withTooltip(mode -> Tooltip.create(Component.literal(switch (mode) {
                    case AUTOMATIC -> "Follows macOS: Low Power Mode → Low Power, on battery → Balanced, else Performance.";
                    case PERFORMANCE -> "Full performance, also on battery and with macOS Low Power Mode on.";
                    case BALANCED -> "FPS capped to " + NativiumConfig.INSTANCE.batteryFpsLimit + ".";
                    case LOW_POWER -> "FPS capped to " + Math.min(NativiumConfig.INSTANCE.batteryFpsLimit, PowerManager.LOW_POWER_FPS) + ".";
                })))
                .create(0, 0, 150, 20, Component.literal("Power mode"), (button, mode) -> {
                    PowerManager.setMode(mode);
                    NativiumConfig.INSTANCE.save();
                });
        Button status = Button.builder(Component.literal("Nativium status…"), b -> this.minecraft.gui.setScreen(new NativiumStatusScreen(this)))
                .tooltip(Tooltip.create(Component.literal("Engine, Sodium/Iris state, power state and which mods run natively on Metal or through nativium-gl.")))
                .size(150, 20).build();
        this.list.addSmall(power, status);
    }
}
