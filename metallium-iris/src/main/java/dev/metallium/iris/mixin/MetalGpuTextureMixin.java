package dev.metallium.iris.mixin;

import dev.metallium.iris.GlNames;
import net.irisshaders.iris.mixinterface.GpuTextureInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Iris identifies textures by GL name. Metal textures get a stable synthetic name from {@link GlNames} instead of
 * Iris's default for non-GL textures, which throws.
 */
@Mixin(targets = "dev.metallium.backend.MetalGpuTexture", remap = false)
abstract class MetalGpuTextureMixin implements GpuTextureInterface {
    @Unique
    private int metallium$glName;

    @Override
    public int iris$getGlId() {
        if (this.metallium$glName == 0) {
            this.metallium$glName = GlNames.register(this);
        }
        return this.metallium$glName;
    }

    @Override
    public void iris$markMipmapNonLinear() {
    }
}
