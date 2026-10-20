/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.compat.physicsmod;

import dev.metallium.compat.OpenGlRequirements;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Applies the Physics Mod compatibility mixins only to the Physics Mod versions they were verified against
 * ({@link #supported()}): there they are required (a changed method fails loudly in testing), other
 * versions are left alone and fall back to the "needs OpenGL" question before start-up.
 */
public final class PhysicsModMixinPlugin implements IMixinConfigPlugin {
    private static Boolean supported;

    /**
     * Physics Mod is loaded in a version the compatibility mixins were verified against ({@code metalliumCompat} of
     * its entry in needs-opengl.json). Kept free of Minecraft classes: it runs while mixin configs load.
     */
    public static synchronized boolean supported() {
        if (supported == null) {
            supported = FabricLoader.getInstance().getModContainer("physicsmod").map(mod -> {
                String version = mod.getMetadata().getVersion().getFriendlyString();
                for (OpenGlRequirements.KnownMod known : OpenGlRequirements.known()) {
                    if (known.id().equals("physicsmod") && known.metalliumCompat() != null) {
                        return OpenGlRequirements.matches(version, known.metalliumCompat());
                    }
                }
                return false;
            }).orElse(false);
        }
        return supported;
    }

    @Override
    public void onLoad(final String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        return dev.metallium.MetalliumMixinPlugin.allowed(mixinClassName) && supported();
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
    }
}
