/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.mixin;

import de.nprmedia.nativium.GraphicsApiLabels;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla's Video Settings Graphics API button shows caption(); only DEFAULT is relabelled. */
@Mixin(PreferredGraphicsApi.class)
abstract class PreferredGraphicsApiCaptionMixin {
    @Inject(method = "caption", at = @At("HEAD"), cancellable = true)
    private void nativium$metalCaption(final CallbackInfoReturnable<Component> cir) {
        if (GraphicsApiLabels.isMetalEntry(this)) {
            cir.setReturnValue(GraphicsApiLabels.metalName());
        }
    }
}
