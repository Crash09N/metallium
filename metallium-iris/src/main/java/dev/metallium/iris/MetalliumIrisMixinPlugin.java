package dev.metallium.iris;

import dev.metallium.iris.gl.GlStateManagerGuard;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Applies {@link GlStateManagerGuard} to vanilla {@code GlStateManager} (via the empty {@code GlStateManagerGuardMixin}). */
public final class MetalliumIrisMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(final String mixinPackage) {
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
        return dev.metallium.compat.IrisSupport.adapterUsable() && dev.metallium.MetalliumMixinPlugin.allowed(mixinClassName)
                && dev.metallium.BackendSelection.shouldOfferMetal();
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
            MetalliumIris.LOGGER.info("[metallium-iris] GlStateManager: {} methods answer without GL when Metal is active", count);
        }
    }
}
