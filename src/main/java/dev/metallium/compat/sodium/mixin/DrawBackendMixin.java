package dev.metallium.compat.sodium.mixin;

import dev.metallium.api.MetalliumRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium names its draw path "gl_multidraw (sodium)" for every non-Vulkan device. On Metal the terrain is drawn through
 * renderpearl's Metal backend, so say so in the F3 "Terrain Rendering" line. Interop by name only; no Sodium code.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend", remap = false)
public class DrawBackendMixin {
    @Inject(method = "getName", at = @At("HEAD"), cancellable = true, require = 0)
    private void metallium$metalLabel(final CallbackInfoReturnable<String> cir) {
        if (MetalliumRuntime.isMetalActive()) {
            cir.setReturnValue("metal_multidraw (sodium, via Metallium)");
        }
    }
}
