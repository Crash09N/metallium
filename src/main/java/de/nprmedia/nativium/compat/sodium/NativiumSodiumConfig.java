/*
 * Portions of this file are derived from Metallum (https://github.com/kokodio/metallum),
 * Copyright (c) 2026 kokodio, licensed under the MIT License (see LICENSES/MIT-metallum.txt).
 * Modifications Copyright (c) 2026 Crash09N – NPR Media, licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */
package de.nprmedia.nativium.compat.sodium;

import de.nprmedia.nativium.BackendSelection;
import de.nprmedia.nativium.EngineSync;
import de.nprmedia.nativium.Nativium;
import de.nprmedia.nativium.NativiumConfig.GraphicsEngine;
import de.nprmedia.nativium.NativiumConfig;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import de.nprmedia.nativium.power.PowerManager;
import de.nprmedia.nativium.power.PowerMode;
import de.nprmedia.nativium.screen.NativiumStatusScreen;
import java.util.EnumSet;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class NativiumSodiumConfig implements ConfigEntryPoint {
    private static GraphicsEngine displayed(final GraphicsEngine engine) {
        return engine == GraphicsEngine.METAL && !BackendSelection.metalSupported() ? GraphicsEngine.OPENGL : engine;
    }

    @Override
    public void registerConfigLate(final ConfigBuilder builder) {
        if (!de.nprmedia.nativium.NativiumPlatform.supported()) {
            return; // unsupported platform: no Nativium page in Sodium's settings
        }
        builder.registerOwnModOptions().addPage(builder.createOptionPage()
                .setName(Component.literal("General"))
                .addOption(builder.createEnumOption(Identifier.fromNamespaceAndPath(Nativium.MOD_ID, "graphics_engine"), GraphicsEngine.class)
                        .setName(Component.literal("Graphics Engine"))
                        .setTooltip(engine -> Component.literal(switch (engine) {
                            case METAL -> "Metal: Nativium's native Metal renderer (Apple Silicon macOS only). Falls back to OpenGL if Metal fails to start.";
                            case OPENGL -> "OpenGL: vanilla's OpenGL renderer (Apple's OpenGL 4.1 on macOS).";
                            case VULKAN -> "Vulkan: vanilla's Vulkan renderer. On macOS it runs through MoltenVK (which the game's LWJGL provides; no separate Vulkan SDK was needed in testing).";
                        } + "\nRequires a game restart. Also sets vanilla's Graphics API option."))
                        .setElementNameProvider(engine -> Component.literal(switch (engine) {
                            case METAL -> "Metal";
                            case OPENGL -> "OpenGL";
                            case VULKAN -> "Vulkan";
                        }))
                        .setAllowedValues(BackendSelection.metalSupported()
                                ? EnumSet.allOf(GraphicsEngine.class)
                                : EnumSet.of(GraphicsEngine.OPENGL, GraphicsEngine.VULKAN))
                        .setDefaultValue(BackendSelection.metalSupported() ? GraphicsEngine.METAL : GraphicsEngine.OPENGL)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .setBinding(
                                value -> EngineSync.apply(value, Minecraft.getInstance().options),
                                () -> displayed(NativiumConfig.INSTANCE.engine))
                        .setStorageHandler(() -> { }))
                .addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(Nativium.MOD_ID, "display_p3"))
                        .setName(Component.literal("Display P3"))
                        .setTooltip(Component.literal(
                                "On: saturated Display P3 colors, like before 26.2.\nOff: sRGB colors, 26.3 and later."
                        ))
                        .setDefaultValue(false)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .setBinding(value -> NativiumConfig.INSTANCE.displayP3 = value, () -> NativiumConfig.INSTANCE.displayP3)
                        .setStorageHandler(NativiumConfig.INSTANCE::save))
                .addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(Nativium.MOD_ID, "gl_auto_routing"))
                        .setName(Component.literal("Translate raw OpenGL mods"))
                        .setTooltip(Component.literal(
                                "On: mods that call OpenGL directly (instead of Minecraft's graphics layer) run through nativium-gl"
                                        + " automatically, if it is installed.\nOff: only mods that opt in are translated."
                                        + "\nPer-mod overrides: Nativium status."))
                        .setDefaultValue(true)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .setBinding(value -> NativiumConfig.INSTANCE.glAutoRouting = value, () -> NativiumConfig.INSTANCE.glAutoRouting)
                        .setStorageHandler(NativiumConfig.INSTANCE::save))
                .addOption(builder.createExternalButtonOption(Identifier.fromNamespaceAndPath(Nativium.MOD_ID, "status"))
                        .setName(Component.literal("Nativium status…"))
                        .setTooltip(Component.literal("Engine and Metal details, Sodium/Iris state, power state, and which mods run"
                                + " natively on Metal or through nativium-gl.\nDiagnostics: start the game with the environment"
                                + " variable MTL_HUD_ENABLED=1 for Apple's Metal performance HUD."))
                        .setScreenConsumer(parent -> Minecraft.getInstance().gui.setScreen(new NativiumStatusScreen(parent)))))
                .addPage(builder.createOptionPage()
                        .setName(Component.literal("Power"))
                        .addOption(builder.createEnumOption(Identifier.fromNamespaceAndPath(Nativium.MOD_ID, "power_mode"), PowerMode.class)
                                .setName(Component.literal("Power mode"))
                                .setTooltip(mode -> Component.literal(switch (mode) {
                                    case AUTOMATIC -> "Automatic: follows macOS. Low Power Mode or a hot Mac → Low Power, on battery → Balanced, else Performance.";
                                    case PERFORMANCE -> "Performance: full performance, also on battery and with macOS Low Power Mode on.";
                                    case BALANCED -> "Balanced: FPS capped to the battery FPS limit.";
                                    case LOW_POWER -> "Low Power: FPS capped to the battery FPS limit, at most " + PowerManager.LOW_POWER_FPS + ".";
                                } + "\nApplies immediately. " + PowerManager.statusLine()))
                                .setElementNameProvider(mode -> Component.literal(PowerManager.name(mode)))
                                .setDefaultValue(PowerMode.AUTOMATIC)
                                .setBinding(PowerManager::setMode, PowerManager::mode)
                                .setStorageHandler(NativiumConfig.INSTANCE::save))
                        .addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath(Nativium.MOD_ID, "battery_fps_limit"))
                                .setName(Component.literal("FPS limit on battery"))
                                .setTooltip(Component.literal("FPS cap of Balanced (and of Automatic on battery). Low Power uses at most "
                                        + PowerManager.LOW_POWER_FPS + "."))
                                .setRange(20, 240, 5)
                                .setValueFormatter(v -> Component.literal(v + " FPS"))
                                .setDefaultValue(60)
                                .setBinding(value -> NativiumConfig.INSTANCE.batteryFpsLimit = value, () -> NativiumConfig.INSTANCE.batteryFpsLimit)
                                .setStorageHandler(NativiumConfig.INSTANCE::save)));
    }
}
