package dev.metallium.iris.mixin;

import dev.metallium.iris.FakeGlCapabilities;
import net.irisshaders.iris.Iris;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = Iris.class, remap = false)
abstract class IrisMixin {
    @Redirect(method = "onRenderSystemInit", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL;getCapabilities()Lorg/lwjgl/opengl/GLCapabilities;"))
    private static GLCapabilities metallium$capabilities() {
        return FakeGlCapabilities.getCapabilities();
    }
}
