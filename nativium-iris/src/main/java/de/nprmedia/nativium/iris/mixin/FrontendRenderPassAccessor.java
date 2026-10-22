/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.iris.mixin;

import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Stage 5: the backend pass behind a renderpearl render pass (per-draw storage bindings through NativiumInterop). */
@Mixin(value = FrontendRenderPass.class, remap = false)
public interface FrontendRenderPassAccessor {
    @Accessor("backend")
    RenderPassBackend nativium$backend();
}
