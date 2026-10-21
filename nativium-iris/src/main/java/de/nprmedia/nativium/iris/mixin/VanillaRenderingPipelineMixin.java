package de.nprmedia.nativium.iris.mixin;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import de.nprmedia.nativium.api.NativiumRuntime;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** "Shaders off" pipeline: the GL program unbind is meaningless on Metal. */
@Mixin(value = VanillaRenderingPipeline.class, remap = false)
abstract class VanillaRenderingPipelineMixin {
    @Redirect(method = "beginLevelRendering", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_glUseProgram(I)V"))
    private void nativium$useProgram(final int program) {
        if (!NativiumRuntime.isMetalActive()) {
            GlStateManager._glUseProgram(program);
        }
    }
}
