/*
 * Portions of this file are derived from Metallum (https://github.com/kokodio/metallum),
 * Copyright (c) 2026 kokodio, licensed under the MIT License (see LICENSES/MIT-metallum.txt).
 * Modifications Copyright (c) 2026 Crash09N – NPR Media, licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */
package de.nprmedia.nativium.compat.distanthorizons.mixin;

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
    private boolean nativium$treatMetalLikeVulkan(final String backendName, final String expected) {
        // DH compares the backend name against "OpenGL" and then "Vulkan" (since DH 2ff0af18). Metal must only match
        // "Vulkan", which selects DH's backend-neutral renderpearl renderer; matching "OpenGL" would select the GL path.
        if ("Metal".equalsIgnoreCase(backendName)) {
            return "Vulkan".equalsIgnoreCase(expected);
        }
        return backendName.equalsIgnoreCase(expected);
    }
}
