/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.compat.sodium.mixin;

import de.nprmedia.nativium.GraphicsApiLabels;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium's Graphics API option uses its own name provider (not PreferredGraphicsApi#caption), and Reese's Sodium Options
 * calls getElementName too. Relabel the DEFAULT value of a PreferredGraphicsApi enum option only. Interop by name only.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.config.structure.EnumOption", remap = false)
public class EnumOptionMixin {
    @Inject(method = "getElementName", at = @At("HEAD"), cancellable = true, require = 0)
    private void nativium$metalName(final Enum<?> element, final CallbackInfoReturnable<Component> cir) {
        if (GraphicsApiLabels.isMetalEntry(element)) {
            cir.setReturnValue(GraphicsApiLabels.metalName());
        }
    }
}
