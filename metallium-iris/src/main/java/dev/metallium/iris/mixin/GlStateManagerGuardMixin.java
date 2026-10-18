package dev.metallium.iris.mixin;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;

/** Marker only: {@link dev.metallium.iris.MetalliumIrisMixinPlugin} guards GlStateManager when this mixin is applied. */
@Mixin(value = GlStateManager.class, remap = false)
abstract class GlStateManagerGuardMixin {
}
