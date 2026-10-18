/*
 * Portions of this file are derived from Metallum (https://github.com/kokodio/metallum),
 * Copyright (c) 2026 kokodio, licensed under the MIT License (see LICENSES/MIT-metallum.txt).
 * Modifications Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.compat.sodium;

import dev.metallium.Metallium;
import dev.metallium.MetalliumConfig;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class MetalliumSodiumConfig implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(final ConfigBuilder builder) {
        builder.registerOwnModOptions().addPage(builder.createOptionPage()
                .setName(Component.literal("Display"))
                .addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(Metallium.MOD_ID, "enabled"))
                        .setName(Component.literal("Metal Backend"))
                        .setTooltip(Component.literal(
                                "On: render with Metal on Apple Silicon (falls back to OpenGL if Metal fails).\nOff: use vanilla's OpenGL/Vulkan backends."
                        ))
                        .setDefaultValue(true)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .setBinding(value -> MetalliumConfig.INSTANCE.enabled = value, () -> MetalliumConfig.INSTANCE.enabled)
                        .setStorageHandler(MetalliumConfig.INSTANCE::save))
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
