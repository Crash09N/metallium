package de.nprmedia.nativium.iris.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import de.nprmedia.nativium.iris.post.GbufferPasses;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stage 3: level render passes on the main target are created on the shader pack's gbuffer attachments instead. */
@Mixin(value = FrontendCommandEncoder.class, remap = false)
abstract class FrontendCommandEncoderMixin {
    private static final String CREATE = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;";

    @ModifyVariable(method = CREATE, at = @At("HEAD"), argsOnly = true)
    private RenderPassDescriptor nativium$retargetToGbuffers(final RenderPassDescriptor descriptor) {
        return GbufferPasses.retarget(descriptor);
    }

    @Inject(method = CREATE, at = @At("RETURN"))
    private void nativium$trackGbufferPass(final RenderPassDescriptor descriptor, final CallbackInfoReturnable<RenderPass> cir) {
        GbufferPasses.created(descriptor, cir.getReturnValue());
    }
}
