package dev.metallium.iris.mixin;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import dev.metallium.api.MetalliumRuntime;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** "Shaders off" pipeline: the GL program unbind is meaningless on Metal. */
@Mixin(value = VanillaRenderingPipeline.class, remap = false)
abstract class VanillaRenderingPipelineMixin {
    @Redirect(method = "beginLevelRendering", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_glUseProgram(I)V"))
    private void metallium$useProgram(final int program) {
        if (!MetalliumRuntime.isMetalActive()) {
            GlStateManager._glUseProgram(program);
        }
    }
}
