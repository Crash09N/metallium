package de.nprmedia.nativium.iris.mixin;

import de.nprmedia.nativium.api.NativiumRuntime;
import de.nprmedia.nativium.iris.NativiumIris;
import de.nprmedia.nativium.iris.post.MetalPostPipeline;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * On Metal, a loaded shader pack gets {@link MetalPostPipeline} instead of Iris's OpenGL-bound
 * {@code IrisRenderingPipeline}. Failures fall back to the vanilla pipeline with a chat message, never a crash.
 */
@Mixin(value = Iris.class, remap = false)
abstract class IrisCreatePipelineMixin {
    @Shadow
    private static ShaderPack currentPack;

    @Inject(method = "createPipeline", at = @At("HEAD"), cancellable = true)
    private static void nativium$createMetalPipeline(final NamespacedId dimensionId, final CallbackInfoReturnable<WorldRenderingPipeline> cir) {
        if (!NativiumRuntime.isMetalActive() || currentPack == null) {
            return;
        }
        try {
            cir.setReturnValue(new MetalPostPipeline(currentPack.getProgramSet(dimensionId)));
        } catch (Exception | LinkageError e) {
            NativiumIris.LOGGER.error("[nativium-iris] Could not create the Metal shader pack pipeline; shaders stay off", e);
            try {
                Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(
                        Component.literal("[Nativium] Shader pack could not be started on Metal: " + e).withStyle(ChatFormatting.RED));
            } catch (RuntimeException ignored) {
                // chat not available
            }
            cir.setReturnValue(new VanillaRenderingPipeline());
        }
    }
}
