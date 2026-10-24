/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.compat.voxy;

import de.nprmedia.nativium.Nativium;
import de.nprmedia.nativium.NativiumConfig;
import de.nprmedia.nativium.api.NativiumRuntime;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Voxy renders with raw OpenGL 4.6 (compute, SSBOs, indirect-count draws) and cannot run on Metal. Nativium cancels
 * Voxy's renderer initialisation (see {@code VoxyClientMixin}) so the game starts, and tells the player once.
 */
public final class VoxyCompat {
    public static final String MESSAGE = "Voxy needs OpenGL 4.6 and is disabled on Metal (nativium-gl runs it on Metal: see Nativium's status screen)";
    private static boolean noticeShown;
    private static boolean disabledForNatives;

    public static final String SHADER_PAUSE_MESSAGE = "Voxy's far terrain is paused while this shader pack is active (the pack has no Voxy support, or Voxy's pack pipeline failed: see the log)";
    private static volatile java.util.function.BooleanSupplier shaderPackProbe = () -> false;
    private static final java.util.concurrent.atomic.AtomicLong renderCalls = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong suppressedCalls = new java.util.concurrent.atomic.AtomicLong();
    private static volatile long lastSuppressedNanos;
    private static boolean pauseNoticeShown;

    /** {@code -Dnativium.voxy.emptyPassRender=false} turns off {@code VoxyEmptyPassMixin} (LODs with nothing visible in vanilla range). */
    public static final boolean EMPTY_PASS_RENDER = !"false".equalsIgnoreCase(System.getProperty("nativium.voxy.emptyPassRender", "true"));
    private static final class VoxyHandle {
        static final java.lang.invoke.MethodHandle GET = findVoxyRenderSystem();
    }
    private static boolean emptyPassLogged;

    private VoxyCompat() {
    }

    private static java.lang.invoke.MethodHandle findVoxyRenderSystem() {
        try {
            Class<?> holder = Class.forName("me.cortex.voxy.client.core.IVoxyRenderSystemHolder");
            return java.lang.invoke.MethodHandles.publicLookup().findStatic(holder, "getNullable",
                    java.lang.invoke.MethodType.methodType(Class.forName("me.cortex.voxy.client.core.VoxyRenderSystem")));
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** Whether Voxy's render system exists right now ({@code IVoxyRenderSystemHolder.getNullable() != null}). */
    public static boolean voxyRenderSystemActive() {
        if (VoxyHandle.GET == null) {
            return false;
        }
        try {
            return VoxyHandle.GET.invoke() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Sodium's cutout pass had nothing to draw and was kept so Voxy's hook draws the LODs (logged once). */
    public static void onEmptyPassForced() {
        if (!emptyPassLogged) {
            emptyPassLogged = true;
            Nativium.LOGGER.info("[Nativium] Voxy: no vanilla terrain visible; keeping Sodium's cutout pass so Voxy draws its LODs (-Dnativium.voxy.emptyPassRender=false turns this off)");
        }
    }

    public static boolean isVoxyLoaded() {
        return FabricLoader.getInstance().isModLoaded("voxy");
    }

    /**
     * True when nativium-gl retargets Voxy's GL calls to Metal (opt-in); then Voxy must start normally. nativium-gl
     * publishes the routed mod ids in a system property at start-up (core does not depend on nativium-gl).
     */
    public static boolean isRoutedThroughNativiumGl() {
        for (String id : System.getProperty("nativium.gl.routedMods", "").split(",")) {
            if (id.trim().equals("voxy")) {
                return true;
            }
        }
        return false;
    }

    /** Called when Voxy's init was cancelled. */
    public static void onVoxyDisabled() {
        Nativium.LOGGER.warn("[Nativium] {}", MESSAGE);
        NativiumRuntime.registerStatusLine(() -> "Voxy: disabled (" + MESSAGE + ")");
    }

    /** Called when Voxy's init was cancelled because its storage natives are missing on this Mac. */
    public static void onVoxyDisabledMissingNatives() {
        disabledForNatives = true;
        Nativium.LOGGER.warn("[Nativium] {}", missingNativesMessage());
        NativiumRuntime.registerStatusLine(() -> "Voxy: disabled (" + missingNativesMessage() + ")");
    }

    public static String missingNativesMessage() {
        return "Voxy is disabled: its storage libraries have no macOS natives here, so it would crash the game on world join. "
                + VoxyNatives.advice();
    }

    public static boolean disabledForNatives() {
        return disabledForNatives;
    }

    /** One chat message per game session, when the player joins a world. */
    public static void showNoticeOnce() {
        if (noticeShown || !isVoxyLoaded()) {
            return;
        }
        String message = disabledForNatives ? missingNativesMessage()
                : NativiumRuntime.isMetalActive() && !isRoutedThroughNativiumGl() ? MESSAGE : null;
        if (message == null) {
            return;
        }
        noticeShown = true;
        Minecraft.getInstance().gui.hud.getChat()
                .addClientSystemMessage(Component.literal("[Nativium] " + message).withStyle(ChatFormatting.GOLD));
    }

    /** nativium-iris registers how to tell that a shader pack renders through its Metal pipeline. */
    public static void registerShaderPackProbe(final java.util.function.BooleanSupplier probe) {
        shaderPackProbe = probe;
    }

    /**
     * Called at the head of {@code VoxyRenderSystem.renderOpaque}: true = skip Voxy's render call (a pack renders on
     * Metal without Voxy's shader-pack pipeline: no {@code voxy.json}, or nativium-iris's bridge failed).
     * Voxy's world storage and ingest keep running, so LODs are ready when shaders are switched off.
     */
    public static boolean suppressRender() {
        boolean suppress = false;
        try {
            suppress = NativiumConfig.INSTANCE.voxySuppressWithShaders && NativiumRuntime.isMetalActive() && shaderPackProbe.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            // probe unavailable: render normally
        }
        if (!suppress) {
            renderCalls.incrementAndGet();
            return false;
        }
        suppressedCalls.incrementAndGet();
        lastSuppressedNanos = System.nanoTime();
        if (!pauseNoticeShown) {
            pauseNoticeShown = true;
            Nativium.LOGGER.warn("[Nativium] {}", SHADER_PAUSE_MESSAGE);
            NativiumRuntime.registerStatusLine(() -> shaderPauseActive() ? "Voxy: " + SHADER_PAUSE_MESSAGE : null);
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().gui.hud.getChat()
                    .addClientSystemMessage(Component.literal("[Nativium] " + SHADER_PAUSE_MESSAGE).withStyle(ChatFormatting.GOLD)));
        }
        return true;
    }

    /** True while Voxy's render calls are being skipped because a shader pack is active (status line / screen). */
    public static boolean shaderPauseActive() {
        return System.nanoTime() - lastSuppressedNanos < 1_000_000_000L && lastSuppressedNanos != 0;
    }

    /** Voxy render calls that went through (game test). */
    public static long renderCalls() {
        return renderCalls.get();
    }

    /** Voxy render calls skipped because a shader pack was active (game test). */
    public static long suppressedCalls() {
        return suppressedCalls.get();
    }
}
