// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Crash09N – NPR Media
package de.nprmedia.nativium.compat.voxy.mixin;

import de.nprmedia.nativium.compat.voxy.VoxyCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Safety net: skips Voxy's LOD draw ({@code VoxyRenderSystem.renderOpaque}, reached from its Sodium
 * DefaultChunkRenderer injection) while a shader pack renders through the Metal Iris adapter. Voxy's world storage and
 * ingest are untouched. Interop by name only.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.VoxyRenderSystem", remap = false)
public class VoxyRenderSystemMixin {
    @Inject(method = "renderOpaque", at = @At("HEAD"), cancellable = true, require = 0)
    private void nativium$pauseWithShaders(final CallbackInfo ci) {
        if (VoxyCompat.suppressRender()) {
            ci.cancel();
        }
    }
}
