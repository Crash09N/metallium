/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.compat.distanthorizons.mixin;

import de.nprmedia.nativium.Nativium;
import de.nprmedia.nativium.api.NativiumRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * With Iris installed, Distant Horizons switches its rendering engine from AUTO to OpenGL ("Distant Horizons / Iris",
 * in {@code AbstractModInitializer.logIncompatibilityWarnings}): its shader-pack integration only exists for the GL
 * renderer. On Metal that engine cannot start, DH's post-init thread fails and the client hangs on the title screen.
 * Nativium runs shader packs through its own Metal pipeline (Iris's GL pipeline and its DH hooks are never created), so
 * on Metal the engine is set back to DH's backend-neutral Blaze3D renderer: LODs render, without shader-pack lighting.
 * Only names are referenced (reflection); DH is optional.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.AbstractModInitializer", remap = false)
public class DhIrisEngineMixin {
    @Inject(method = "logIncompatibilityWarnings", at = @At("RETURN"), require = 0)
    private static void nativium$keepBlazeRendererOnMetal(final CallbackInfo ci) {
        if (!NativiumRuntime.isMetalActive() && !de.nprmedia.nativium.BackendSelection.shouldOfferMetal()) {
            return;
        }
        try {
            Class<?> experimental = Class.forName("com.seibel.distanthorizons.core.config.Config$Client$Advanced$Graphics$Experimental");
            Object entry = experimental.getField("renderingEngine").get(null);
            Object value = entry.getClass().getMethod("get").invoke(entry);
            if (value != null && "OPEN_GL".equals(value.toString())) {
                Class<?> engines = Class.forName("com.seibel.distanthorizons.api.enums.config.EDhApiRenderingEngine");
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object blaze = Enum.valueOf((Class) engines, "BLAZE_3D");
                entry.getClass().getMethod("setApiValue", Object.class, String.class).invoke(entry, blaze, "Nativium (Metal: DH's OpenGL renderer cannot run)");
                Nativium.LOGGER.info("[Nativium] Distant Horizons + Iris on Metal: DH renderer set to Blaze3D (was OpenGL); LODs are not shaded by the pack");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Nativium.LOGGER.warn("[Nativium] could not keep Distant Horizons on its Blaze3D renderer: {}", e.toString());
        }
    }
}
