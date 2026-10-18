package dev.metallium.iris.mixin;

import dev.metallium.api.MetalliumRuntime;
import dev.metallium.iris.FakeGlCapabilities;
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
    private static GLCapabilities metallium$capabilities() {
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
    private static void metallium$computeFeatures(final CallbackInfoReturnable<Boolean> cir) {
        if (MetalliumRuntime.isMetalActive()) {
            cir.setReturnValue(true);
        }
    }

    /** Iris's {@code getMaxImageUnits} (Metal: 8 read-write textures per kernel are safe on all Apple GPUs). */
    @Inject(method = "getMaxImageUnits", at = @At("HEAD"), cancellable = true)
    private static void metallium$imageUnits(final CallbackInfoReturnable<Integer> cir) {
        if (MetalliumRuntime.isMetalActive()) {
            cir.setReturnValue(8);
        }
    }

    @Inject(method = "supportsBufferBlending", at = @At("HEAD"), cancellable = true)
    private static void metallium$bufferBlending(final CallbackInfoReturnable<Boolean> cir) {
        if (MetalliumRuntime.isMetalActive()) {
            cir.setReturnValue(true);
        }
    }
}
