package dev.metallium.iris.mixin;

import dev.metallium.api.MetalliumRuntime;
import net.irisshaders.iris.gl.shader.StandardMacros;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stage 5, opt-in ({@code -Dmetallium.iris.hideMacOs=true}): packs use {@code MC_OS_MAC} as "Apple OpenGL 4.1, no
 * compute/images" and switch features off (Complementary forces {@code COLORED_LIGHTING 0}). On Metal those features
 * work, so this reports {@code MC_OS_UNKNOWN} instead. Off by default: packs also use the macro for other Apple GL
 * workarounds, and whether to hide it is a product decision.
 */
@Mixin(value = StandardMacros.class, remap = false)
abstract class StandardMacrosMixin {
    @Inject(method = "getOsString", at = @At("HEAD"), cancellable = true)
    private static void metallium$hideMacOs(final CallbackInfoReturnable<String> cir) {
        if (MetalliumRuntime.isMetalActive() && Boolean.getBoolean("metallium.iris.hideMacOs")) {
            cir.setReturnValue("MC_OS_UNKNOWN");
        }
    }
}
