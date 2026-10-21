package dev.metallium.iris.mixin;

import dev.metallium.iris.post.VoxyIrisBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

/**
 * Voxy creates its shader-pack LOD pipeline only for Iris's OpenGL pipeline (which carries Voxy's pipeline data). On
 * Metal the active Iris pipeline is {@code MetalPostPipeline}: {@link VoxyIrisBridge} builds that data for it and
 * returns Voxy's {@code IrisVoxyRenderPipeline}; null (no pack Voxy patch, failure) keeps Voxy's own behaviour. Voxy is
 * referenced by name only.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.RenderPipelineFactory", remap = false)
abstract class VoxyPipelineFactoryMixin {
    @Inject(method = "createIrisPipeline", at = @At("HEAD"), cancellable = true, require = 0)
    private static void metallium$metalPipeline(@Coerce final Object properties, @Coerce final Object nodeManager, @Coerce final Object nodeCleaner,
                                                @Coerce final Object traversal, final BooleanSupplier frexStillHasWork,
                                                final CallbackInfoReturnable<Object> cir) {
        try {
            Object pipeline = VoxyIrisBridge.createIrisPipeline(properties, nodeManager, nodeCleaner, traversal, frexStillHasWork);
            if (pipeline != null) {
                cir.setReturnValue(pipeline);
            }
        } catch (RuntimeException | LinkageError e) {
            dev.metallium.iris.MetalliumIris.LOGGER.error("[metallium-iris] Voxy: shader-pack pipeline setup failed", e);
        }
    }
}
