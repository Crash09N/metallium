package de.nprmedia.nativium.iris.mixin;

import de.nprmedia.nativium.iris.FakeGlCapabilities;
import net.irisshaders.iris.gl.GLDebug;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = GLDebug.class, remap = false)
abstract class GLDebugMixin {
    @Redirect(method = {"setupDebugMessageCallback(Ljava/io/PrintStream;)I", "disableDebugMessages", "reloadDebugState"},
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL;getCapabilities()Lorg/lwjgl/opengl/GLCapabilities;"))
    private static GLCapabilities nativium$capabilities() {
        return FakeGlCapabilities.getCapabilities();
    }
}
