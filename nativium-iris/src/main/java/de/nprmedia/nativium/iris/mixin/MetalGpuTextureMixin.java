package de.nprmedia.nativium.iris.mixin;

import de.nprmedia.nativium.iris.GlNames;
import net.irisshaders.iris.mixinterface.GpuTextureInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Iris identifies textures by GL name. Metal textures get a stable synthetic name from {@link GlNames} instead of
 * Iris's default for non-GL textures, which throws.
 */
@Mixin(targets = "de.nprmedia.nativium.backend.MetalGpuTexture", remap = false)
abstract class MetalGpuTextureMixin implements GpuTextureInterface {
    @Unique
    private int nativium$glName;

    @Override
    public int iris$getGlId() {
        if (this.nativium$glName == 0) {
            this.nativium$glName = GlNames.register(this);
        }
        return this.nativium$glName;
    }

    @Override
    public void iris$markMipmapNonLinear() {
    }
}
