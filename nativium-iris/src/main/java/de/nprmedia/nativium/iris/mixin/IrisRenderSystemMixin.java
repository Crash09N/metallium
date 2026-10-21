package de.nprmedia.nativium.iris.mixin;

import de.nprmedia.nativium.api.NativiumRuntime;
import de.nprmedia.nativium.iris.FakeGlCapabilities;
import net.irisshaders.iris.gl.IrisRenderSystem;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = IrisRenderSystem.class, remap = false)
abstract class IrisRenderSystemMixin {
    @Redirect(method = {"initRenderer", "bindImageTexture", "getMaxImageUnits", "supportsSSBO", "supportsImageLoadStore",
            "supportsBufferBlending", "getVRAM"},
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL;getCapabilities()Lorg/lwjgl/opengl/GLCapabilities;"))
    private static GLCapabilities nativium$capabilities() {
        return FakeGlCapabilities.getCapabilities();
    }

    /**
     * Metal blends each colour attachment independently, so packs that declare per-buffer blending
     * ({@code blend.<program>.<buffer>}) are not rejected at load time. (Only the gbuffers stage uses it; it is
     * applied once gbuffers run on Metal.)
     */
    /**
     * Stage 5: compute shaders, image load/store (custom images, colorimg) and SSBOs run on Metal
     * (docs/design/iris-stage5-compute.md), so Iris activates the COMPUTE_SHADERS, CUSTOM_IMAGES and SSBO feature
     * flags for packs (their {@code IRIS_FEATURE_*} macros and pack loading checks).
     */
    @Inject(method = {"supportsCompute", "supportsSSBO", "supportsImageLoadStore"}, at = @At("HEAD"), cancellable = true)
    private static void nativium$computeFeatures(final CallbackInfoReturnable<Boolean> cir) {
        if (NativiumRuntime.isMetalActive()) {
            cir.setReturnValue(true);
        }
    }

    /** Iris's {@code getMaxImageUnits} (Metal: 8 read-write textures per kernel are safe on all Apple GPUs). */
    @Inject(method = "getMaxImageUnits", at = @At("HEAD"), cancellable = true)
    private static void nativium$imageUnits(final CallbackInfoReturnable<Integer> cir) {
        if (NativiumRuntime.isMetalActive()) {
            cir.setReturnValue(8);
        }
    }

    @Inject(method = "supportsBufferBlending", at = @At("HEAD"), cancellable = true)
    private static void nativium$bufferBlending(final CallbackInfoReturnable<Boolean> cir) {
        if (NativiumRuntime.isMetalActive()) {
            cir.setReturnValue(true);
        }
    }
}
