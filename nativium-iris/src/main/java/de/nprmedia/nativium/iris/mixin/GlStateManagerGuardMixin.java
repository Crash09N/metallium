package de.nprmedia.nativium.iris.mixin;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;

/** Marker only: {@link de.nprmedia.nativium.iris.NativiumIrisMixinPlugin} guards GlStateManager when this mixin is applied. */
@Mixin(value = GlStateManager.class, remap = false)
abstract class GlStateManagerGuardMixin {
}
