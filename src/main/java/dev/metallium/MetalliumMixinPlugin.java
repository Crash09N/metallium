// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Crash09N – NPR Media
package dev.metallium;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin gate of every Metallium mixin config (the configs with their own plugin call {@link #allowed} too).
 *
 * <ul>
 *   <li>Unsupported platform ({@link MetalliumPlatform}): no Metallium mixin applies at all.</li>
 *   <li>Supported Mac, Metal not used this session (Graphics Engine OpenGL/Vulkan, crash recovery, Iris without adapter,
 *       a mod that needs OpenGL): only {@link #ENGINE_SELECTOR} applies, i.e. what lets the player see Metallium's state
 *       and switch back to Metal (Graphics API label, engine sync, crash-recovery / needs-OpenGL screens, status button
 *       and F3 line) plus the Voxy natives safety check that applies on Apple Silicon whatever the renderer. Everything
 *       that changes rendering or other mods' behaviour stays off.</li>
 *   <li>Metal offered: all mixins apply; those that change behaviour still check
 *       {@link dev.metallium.api.MetalliumRuntime#isMetalActive()} for the case that Metal device creation fails and
 *       vanilla falls back to OpenGL.</li>
 * </ul>
 * Kept free of Minecraft classes: runs while mixin configs load.
 */
public final class MetalliumMixinPlugin implements IMixinConfigPlugin {
    /** Simple names of mixins that also apply on a supported Mac when Metal is not used this session. */
    public static final Set<String> ENGINE_SELECTOR = Set.of(
            // core: crash guard, engine selection and status
            "CrashReportMixin", "MinecraftMixin", "OptionsMixin", "PreferredGraphicsApiCaptionMixin",
            "PreferredGraphicsApiMixin", "GuiMixin", "VideoSettingsScreenMixin", "DebugEntrySystemSpecsMixin",
            // Sodium's Graphics API option shows the same "Metal (via Metallium)" label as vanilla's
            "EnumOptionMixin", "StatefulOptionMixin",
            // BetterF3: Metallium's F3 line
            "MiscRightModuleMixin",
            // Voxy: only acts when its macOS arm64 storage natives are missing (would crash on any renderer) or on Metal
            "VoxyClientMixin", "ClientPacketListenerMixin");

    /** Whether a Metallium mixin may apply this session (live platform and backend decision). */
    public static boolean allowed(final String mixinClassName) {
        if (!MetalliumPlatform.supported()) {
            return false;
        }
        return policy(mixinClassName, true, BackendSelection.shouldOfferMetal());
    }

    /** Pure policy (unit-tested). */
    static boolean policy(final String mixinClassName, final boolean platformSupported, final boolean metalOffered) {
        if (!platformSupported) {
            return false;
        }
        if (metalOffered) {
            return true;
        }
        String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        return ENGINE_SELECTOR.contains(simple);
    }

    @Override
    public void onLoad(final String mixinPackage) {
        MetalliumPlatform.status(); // the platform decision (and its single log line) happens at the first config load
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        return allowed(mixinClassName);
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
