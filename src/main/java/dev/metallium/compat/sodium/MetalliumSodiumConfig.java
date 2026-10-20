/*
 * Portions of this file are derived from Metallum (https://github.com/kokodio/metallum),
 * Copyright (c) 2026 kokodio, licensed under the MIT License (see LICENSES/MIT-metallum.txt).
 * Modifications Copyright (c) 2026 Crash09N – NPR Media, licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */
package dev.metallium.compat.sodium;

import dev.metallium.BackendSelection;
import dev.metallium.EngineSync;
import dev.metallium.Metallium;
import dev.metallium.MetalliumConfig.GraphicsEngine;
import dev.metallium.MetalliumConfig;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import dev.metallium.power.PowerManager;
import dev.metallium.power.PowerMode;
import dev.metallium.screen.MetalliumStatusScreen;
import java.util.EnumSet;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class MetalliumSodiumConfig implements ConfigEntryPoint {
    private static GraphicsEngine displayed(final GraphicsEngine engine) {
        return engine == GraphicsEngine.METAL && !BackendSelection.metalSupported() ? GraphicsEngine.OPENGL : engine;
    }

    @Override
    public void registerConfigLate(final ConfigBuilder builder) {
        if (!dev.metallium.MetalliumPlatform.supported()) {
            return; // unsupported platform: no Metallium page in Sodium's settings
        }
        builder.registerOwnModOptions().addPage(builder.createOptionPage()
                .setName(Component.literal("General"))
                .addOption(builder.createEnumOption(Identifier.fromNamespaceAndPath(Metallium.MOD_ID, "graphics_engine"), GraphicsEngine.class)
                        .setName(Component.literal("Graphics Engine"))
                        .setTooltip(engine -> Component.literal(switch (engine) {
                            case METAL -> "Metal: Metallium's native Metal renderer (Apple Silicon macOS only). Falls back to OpenGL if Metal fails to start.";
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
                                () -> displayed(MetalliumConfig.INSTANCE.engine))
                        .setStorageHandler(() -> { }))
                .addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(Metallium.MOD_ID, "display_p3"))
                        .setName(Component.literal("Display P3"))
                        .setTooltip(Component.literal(
                                "On: saturated Display P3 colors, like before 26.2.\nOff: sRGB colors, 26.3 and later."
                        ))
                        .setDefaultValue(false)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .setBinding(value -> MetalliumConfig.INSTANCE.displayP3 = value, () -> MetalliumConfig.INSTANCE.displayP3)
                        .setStorageHandler(MetalliumConfig.INSTANCE::save))
                .addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(Metallium.MOD_ID, "gl_auto_routing"))
                        .setName(Component.literal("Translate raw OpenGL mods"))
                        .setTooltip(Component.literal(
                                "On: mods that call OpenGL directly (instead of Minecraft's graphics layer) run through metallium-gl"
                                        + " automatically, if it is installed.\nOff: only mods that opt in are translated."
                                        + "\nPer-mod overrides: Metallium status."))
                        .setDefaultValue(true)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .setBinding(value -> MetalliumConfig.INSTANCE.glAutoRouting = value, () -> MetalliumConfig.INSTANCE.glAutoRouting)
                        .setStorageHandler(MetalliumConfig.INSTANCE::save))
                .addOption(builder.createExternalButtonOption(Identifier.fromNamespaceAndPath(Metallium.MOD_ID, "status"))
                        .setName(Component.literal("Metallium status…"))
                        .setTooltip(Component.literal("Engine and Metal details, Sodium/Iris state, power state, and which mods run"
                                + " natively on Metal or through metallium-gl.\nDiagnostics: start the game with the environment"
                                + " variable MTL_HUD_ENABLED=1 for Apple's Metal performance HUD."))
                        .setScreenConsumer(parent -> Minecraft.getInstance().gui.setScreen(new MetalliumStatusScreen(parent)))))
                .addPage(builder.createOptionPage()
                        .setName(Component.literal("Power"))
                        .addOption(builder.createEnumOption(Identifier.fromNamespaceAndPath(Metallium.MOD_ID, "power_mode"), PowerMode.class)
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
                                .setStorageHandler(MetalliumConfig.INSTANCE::save))
                        .addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath(Metallium.MOD_ID, "battery_fps_limit"))
                                .setName(Component.literal("FPS limit on battery"))
                                .setTooltip(Component.literal("FPS cap of Balanced (and of Automatic on battery). Low Power uses at most "
                                        + PowerManager.LOW_POWER_FPS + "."))
                                .setRange(20, 240, 5)
                                .setValueFormatter(v -> Component.literal(v + " FPS"))
                                .setDefaultValue(60)
                                .setBinding(value -> MetalliumConfig.INSTANCE.batteryFpsLimit = value, () -> MetalliumConfig.INSTANCE.batteryFpsLimit)
                                .setStorageHandler(MetalliumConfig.INSTANCE::save)));
    }
}
