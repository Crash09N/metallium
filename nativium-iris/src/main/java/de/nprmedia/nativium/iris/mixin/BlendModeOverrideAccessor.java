/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.iris.mixin;

import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A pack's {@code blend.<program>} override as data (Iris applies it to GL state directly). */
@Mixin(value = BlendModeOverride.class, remap = false)
public interface BlendModeOverrideAccessor {
    @Accessor("blendMode")
    BlendMode nativium$getBlendMode();
}
