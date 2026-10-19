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
        CompiledRenderPipeline substituted = GbufferPasses.substitute((RenderPass) this, pipeline);
        metallium$substituted = GbufferPasses.isCaptured((RenderPass) this) ? substituted : null;
        return substituted;
    }

    @Inject(method = "setPipeline", at = @At("TAIL"))
    private void metallium$bindGbufferResources(final CompiledRenderPipeline pipeline, final CallbackInfo ci) {
        if (metallium$substituted != null) {
            GbufferPasses.afterSetPipeline((RenderPass) this, metallium$substituted);
        }
    }

    @Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V",
            at = @At("HEAD"))
    private void metallium$textureBound(final String name, final com.mojang.renderpearl.api.textures.GpuTextureView view,
                                        final com.mojang.renderpearl.api.textures.GpuSampler sampler, final CallbackInfo ci) {
        GbufferPasses.textureBound((RenderPass) this, name, view);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void metallium$closed(final CallbackInfo ci) {
        GbufferPasses.closed((RenderPass) this);
    }
}
