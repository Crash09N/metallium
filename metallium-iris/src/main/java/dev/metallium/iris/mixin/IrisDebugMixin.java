package dev.metallium.iris.mixin;

import dev.metallium.api.MetalliumRuntime;
import net.irisshaders.iris.Iris;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;

/** GL debug callbacks do not exist on Metal; keep only the config persistence of {@code Iris.setDebug}. */
@Mixin(value = Iris.class, remap = false)
abstract class IrisDebugMixin {
    @Inject(method = "setDebug", at = @At("HEAD"), cancellable = true)
    private static void metallium$setDebugOnMetal(final boolean enable, final CallbackInfo ci) {
        if (!MetalliumRuntime.isMetalActive()) {
            return;
        }
        try {
            Iris.getIrisConfig().setDebugEnabled(enable);
            Iris.getIrisConfig().save();
        } catch (IOException e) {
            Iris.logger.fatal("Failed to save config!", e);
        }
        ci.cancel();
    }
}
