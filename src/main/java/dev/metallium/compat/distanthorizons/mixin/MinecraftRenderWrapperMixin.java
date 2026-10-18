/*
 * Portions of this file are derived from Metallum (https://github.com/kokodio/metallum),
 * Copyright (c) 2026 kokodio, licensed under the MIT License (see LICENSES/MIT-metallum.txt).
 * Modifications Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.compat.distanthorizons.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper", remap = false)
public class MinecraftRenderWrapperMixin {
    @Redirect(
            method = "getMcRenderingApi",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/String;equalsIgnoreCase(Ljava/lang/String;)Z"
            )
    )
    private boolean metallium$treatMetalLikeVulkan(final String backendName, final String expected) {
        return backendName.equalsIgnoreCase(expected) || "Metal".equalsIgnoreCase(backendName);
    }
}
