package dev.metallium.compat.voxy.mixin;

import dev.metallium.api.MetalliumRuntime;
import dev.metallium.compat.voxy.VoxyCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Voxy calls {@code VoxyClient.initVoxyClient()} from its RenderSystem.initRenderer hook; that queries
 * {@code GL.getCapabilities()}, which throws without an OpenGL context. Cancelling it leaves Voxy without an instance
 * factory, i.e. unavailable (the same state as on a GPU it considers unsupported). Interop by name only.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.VoxyClient", remap = false)
public class VoxyClientMixin {
    @Inject(method = "initVoxyClient", at = @At("HEAD"), cancellable = true, require = 0)
    private static void metallium$disableOnMetal(final CallbackInfo ci) {
        if (MetalliumRuntime.isMetalActive()) {
            VoxyCompat.onVoxyDisabled();
            ci.cancel();
        }
    }
}
