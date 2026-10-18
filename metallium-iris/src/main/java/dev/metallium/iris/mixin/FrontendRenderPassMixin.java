package dev.metallium.iris.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import dev.metallium.iris.post.GbufferPasses;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stage 3: in a render pass captured for the gbuffers, every pipeline is replaced by the shader pack's program for it,
 * and the pack's uniforms/samplers are bound right after it.
 */
@Mixin(value = FrontendRenderPass.class, remap = false)
abstract class FrontendRenderPassMixin {
    @Unique
    private CompiledRenderPipeline metallium$substituted;

    @ModifyVariable(method = "setPipeline", at = @At("HEAD"), argsOnly = true)
    private CompiledRenderPipeline metallium$substitute(final CompiledRenderPipeline pipeline) {
        if (!GbufferPasses.isCaptured((RenderPass) this)) {
            metallium$substituted = null;
            return pipeline;
        }
        metallium$substituted = GbufferPasses.substitute((RenderPass) this, pipeline);
        return metallium$substituted;
    }

    @Inject(method = "setPipeline", at = @At("TAIL"))
    private void metallium$bindGbufferResources(final CompiledRenderPipeline pipeline, final CallbackInfo ci) {
        if (metallium$substituted != null) {
            GbufferPasses.afterSetPipeline((RenderPass) this, metallium$substituted);
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void metallium$closed(final CallbackInfo ci) {
        GbufferPasses.closed((RenderPass) this);
    }
}
