package dev.metallium.iris.mixin;

import dev.metallium.api.MetalliumRuntime;
import dev.metallium.iris.post.PostUniforms;
import net.irisshaders.iris.gl.IrisRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.FloatBuffer;

/** Loose-uniform writes from Iris's uniform objects go to the Metal post programs' CPU uniform blocks. */
@Mixin(value = IrisRenderSystem.class, remap = false)
abstract class IrisRenderSystemUniformMixin {
    @Inject(method = "uniform1f", at = @At("HEAD"), cancellable = true)
    private static void metallium$uniform1f(final int location, final float v0, final CallbackInfo ci) {
        if (MetalliumRuntime.isMetalActive()) {
            PostUniforms.setFloats(location, v0);
            ci.cancel();
        }
    }

    @Inject(method = "uniform2f", at = @At("HEAD"), cancellable = true)
    private static void metallium$uniform2f(final int location, final float v0, final float v1, final CallbackInfo ci) {
        if (MetalliumRuntime.isMetalActive()) {
            PostUniforms.setFloats(location, v0, v1);
            ci.cancel();
        }
    }

    @Inject(method = "uniform3f", at = @At("HEAD"), cancellable = true)
    private static void metallium$uniform3f(final int location, final float v0, final float v1, final float v2, final CallbackInfo ci) {
        if (MetalliumRuntime.isMetalActive()) {
            PostUniforms.setFloats(location, v0, v1, v2);
            ci.cancel();
        }
    }

    @Inject(method = "uniform4f", at = @At("HEAD"), cancellable = true)
    private static void metallium$uniform4f(final int location, final float v0, final float v1, final float v2, final float v3, final CallbackInfo ci) {
        if (MetalliumRuntime.isMetalActive()) {
            PostUniforms.setFloats(location, v0, v1, v2, v3);
            ci.cancel();
        }
    }

    @Inject(method = "uniform2i", at = @At("HEAD"), cancellable = true)
    private static void metallium$uniform2i(final int location, final int v0, final int v1, final CallbackInfo ci) {
        if (MetalliumRuntime.isMetalActive()) {
            PostUniforms.setInts(location, v0, v1);
            ci.cancel();
        }
    }

    @Inject(method = "uniform3i", at = @At("HEAD"), cancellable = true)
    private static void metallium$uniform3i(final int location, final int v0, final int v1, final int v2, final CallbackInfo ci) {
        if (MetalliumRuntime.isMetalActive()) {
            PostUniforms.setInts(location, v0, v1, v2);
            ci.cancel();
        }
    }

    @Inject(method = "uniform4i", at = @At("HEAD"), cancellable = true)
    private static void metallium$uniform4i(final int location, final int v0, final int v1, final int v2, final int v3, final CallbackInfo ci) {
        if (MetalliumRuntime.isMetalActive()) {
            PostUniforms.setInts(location, v0, v1, v2, v3);
            ci.cancel();
        }
    }

    @Inject(method = "uniformMatrix4fv(IZLjava/nio/FloatBuffer;)V", at = @At("HEAD"), cancellable = true)
    private static void metallium$uniformMatrix4(final int location, final boolean transpose, final FloatBuffer matrix, final CallbackInfo ci) {
        if (MetalliumRuntime.isMetalActive()) {
            PostUniforms.setMatrix(location, 4, toArray(matrix, 16));
            ci.cancel();
        }
    }

    @Inject(method = "uniformMatrix4fv(IZ[F)V", at = @At("HEAD"), cancellable = true)
    private static void metallium$uniformMatrix4(final int location, final boolean transpose, final float[] matrix, final CallbackInfo ci) {
        if (MetalliumRuntime.isMetalActive()) {
            PostUniforms.setMatrix(location, 4, matrix);
            ci.cancel();
        }
    }

    @Inject(method = "uniformMatrix3fv(IZLjava/nio/FloatBuffer;)V", at = @At("HEAD"), cancellable = true)
    private static void metallium$uniformMatrix3(final int location, final boolean transpose, final FloatBuffer matrix, final CallbackInfo ci) {
        if (MetalliumRuntime.isMetalActive()) {
            PostUniforms.setMatrix(location, 3, toArray(matrix, 9));
            ci.cancel();
        }
    }

    @Inject(method = "uniformMatrix3fv(IZ[F)V", at = @At("HEAD"), cancellable = true)
    private static void metallium$uniformMatrix3(final int location, final boolean transpose, final float[] matrix, final CallbackInfo ci) {
        if (MetalliumRuntime.isMetalActive()) {
            PostUniforms.setMatrix(location, 3, matrix);
            ci.cancel();
        }
    }

    private static float[] toArray(final FloatBuffer buffer, final int count) {
        float[] values = new float[count];
        buffer.get(buffer.position(), values, 0, Math.min(count, buffer.remaining()));
        return values;
    }
}
