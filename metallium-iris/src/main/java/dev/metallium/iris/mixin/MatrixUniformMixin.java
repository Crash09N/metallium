package dev.metallium.iris.mixin;

import dev.metallium.api.MetalliumRuntime;
import dev.metallium.iris.post.PostUniforms;
import org.lwjgl.opengl.GL46C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.FloatBuffer;

/** The two Iris matrix uniforms that call LWJGL directly instead of IrisRenderSystem. */
@Mixin(targets = {"net.irisshaders.iris.gl.uniform.MatrixUniform", "net.irisshaders.iris.gl.uniform.MatrixFromFloatArrayUniform"}, remap = false)
abstract class MatrixUniformMixin {
    @Redirect(method = "*", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL46C;glUniformMatrix4fv(IZLjava/nio/FloatBuffer;)V"))
    private void metallium$uniformMatrix4(final int location, final boolean transpose, final FloatBuffer matrix) {
        if (!MetalliumRuntime.isMetalActive()) {
            GL46C.glUniformMatrix4fv(location, transpose, matrix);
            return;
        }
        float[] values = new float[16];
        matrix.get(matrix.position(), values, 0, Math.min(16, matrix.remaining()));
        PostUniforms.setMatrix(location, 4, values);
    }
}
