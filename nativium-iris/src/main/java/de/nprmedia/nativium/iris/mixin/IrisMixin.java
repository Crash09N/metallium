/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.iris.mixin;

import de.nprmedia.nativium.iris.FakeGlCapabilities;
import net.irisshaders.iris.Iris;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = Iris.class, remap = false)
abstract class IrisMixin {
    @Redirect(method = "onRenderSystemInit", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL;getCapabilities()Lorg/lwjgl/opengl/GLCapabilities;"))
    private static GLCapabilities nativium$capabilities() {
        return FakeGlCapabilities.getCapabilities();
    }
}
