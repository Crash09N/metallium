package de.nprmedia.nativium.compat.voxy.mixin;

import de.nprmedia.nativium.api.NativiumRuntime;
import de.nprmedia.nativium.compat.voxy.VoxyCompat;
import de.nprmedia.nativium.compat.voxy.VoxyNatives;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Voxy calls {@code VoxyClient.initVoxyClient()} from its RenderSystem.initRenderer hook; that queries
 * {@code GL.getCapabilities()}, which throws without an OpenGL context. Cancelling it leaves Voxy without an instance
 * factory, i.e. unavailable (the same state as on a GPU it considers unsupported). Interop by name only. Steps aside
 * when nativium-gl routes Voxy's GL calls to Metal. Also the safe path on a Mac without Voxy's storage natives (see
 * {@link de.nprmedia.nativium.compat.voxy.VoxyNatives}): there Voxy would crash the game on world join, on any engine.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.VoxyClient", remap = false)
public class VoxyClientMixin {
    @Inject(method = "initVoxyClient", at = @At("HEAD"), cancellable = true, require = 0)
    private static void nativium$disableOnMetal(final CallbackInfo ci) {
        if (VoxyNatives.blocksVoxy()) {
            VoxyCompat.onVoxyDisabledMissingNatives();
            ci.cancel();
            return;
        }
        if (NativiumRuntime.isMetalActive() && !VoxyCompat.isRoutedThroughNativiumGl()) {
            VoxyCompat.onVoxyDisabled();
            ci.cancel();
        }
    }
}
