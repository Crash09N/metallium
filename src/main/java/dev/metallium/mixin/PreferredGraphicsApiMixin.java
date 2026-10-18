package dev.metallium.mixin;

import dev.metallium.BackendSelection;
import dev.metallium.EngineSync;
import dev.metallium.MetalliumConfig;
import dev.metallium.backend.MetalBackend;
import com.mojang.renderpearl.api.device.GpuBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts Metal in front of vanilla's backend list for the "Default" preference only. An explicit OpenGL or Vulkan choice
 * is respected, and vanilla's own backends stay in the list as fallbacks.
 */
@Mixin(PreferredGraphicsApi.class)
abstract class PreferredGraphicsApiMixin {
    @Inject(method = "getBackendsToTry", at = @At("RETURN"), cancellable = true)
    private void metallium$preferMetal(final CallbackInfoReturnable<GpuBackend[]> cir) {
        if ((Object) this != PreferredGraphicsApi.DEFAULT) {
            return;
        }
        // The Graphics Engine config is authoritative even if vanilla's option was not synced (yet).
        MetalliumConfig.GraphicsEngine engine = MetalliumConfig.INSTANCE.engine;
        if (engine == MetalliumConfig.GraphicsEngine.OPENGL || engine == MetalliumConfig.GraphicsEngine.VULKAN) {
            cir.setReturnValue(EngineSync.toVanilla(engine).getBackendsToTry());
            return;
        }
        if (!BackendSelection.shouldOfferMetal()) {
            return;
        }
        GpuBackend[] vanilla = cir.getReturnValue();
        GpuBackend[] withMetal = new GpuBackend[vanilla.length + 1];
        withMetal[0] = new MetalBackend();
        System.arraycopy(vanilla, 0, withMetal, 1, vanilla.length);
        cir.setReturnValue(withMetal);
    }
}
