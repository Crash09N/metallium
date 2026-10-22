/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.iris;

import de.nprmedia.nativium.iris.gl.GlStateManagerGuard;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Applies {@link GlStateManagerGuard} to vanilla {@code GlStateManager} (via the empty {@code GlStateManagerGuardMixin}). */
public final class NativiumIrisMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(final String mixinPackage) {
        de.nprmedia.nativium.LegacyMigration.aliasLegacyProperties(); // -Dmetallium.* fallback, idempotent
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        // The release jar always ships this adapter; without a supported Iris (and Sodium) every mixin stays off.
        // Off as well on unsupported platforms and whenever Metal is not used this session: Iris then runs its own
        // OpenGL pipeline untouched.
        return de.nprmedia.nativium.compat.IrisSupport.adapterUsable() && de.nprmedia.nativium.NativiumMixinPlugin.allowed(mixinClassName)
                && de.nprmedia.nativium.BackendSelection.shouldOfferMetal();
    }

    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName, final IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName, final IMixinInfo mixinInfo) {
        if (mixinClassName.endsWith(".GlStateManagerGuardMixin")) {
            // postApply: Iris's own injectors on GlStateManager are already merged; the guard goes in front of them.
            int count = GlStateManagerGuard.apply(targetClass);
            NativiumIris.LOGGER.info("[nativium-iris] GlStateManager: {} methods answer without GL when Metal is active", count);
        }
    }
}
