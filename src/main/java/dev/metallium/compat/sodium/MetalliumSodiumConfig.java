/*
 * Portions of this file are derived from Metallum (https://github.com/kokodio/metallum),
 * Copyright (c) 2026 kokodio, licensed under the MIT License (see LICENSES/MIT-metallum.txt).
 * Modifications Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
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
        builder.registerOwnModOptions().addPage(builder.createOptionPage()
                .setName(Component.literal("Display"))
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
                        .setStorageHandler(MetalliumConfig.INSTANCE::save)));
    }
}
