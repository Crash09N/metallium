/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.iris.mixin;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;

/** Marker only: {@link de.nprmedia.nativium.iris.NativiumIrisMixinPlugin} guards GlStateManager when this mixin is applied. */
@Mixin(value = GlStateManager.class, remap = false)
abstract class GlStateManagerGuardMixin {
}
