package dev.metallium.compat.voxy;

import dev.metallium.Metallium;
import dev.metallium.MetalliumConfig;
import dev.metallium.api.MetalliumRuntime;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Voxy renders with raw OpenGL 4.6 (compute, SSBOs, indirect-count draws) and cannot run on Metal. Metallium cancels
 * Voxy's renderer initialisation (see {@code VoxyClientMixin}) so the game starts, and tells the player once.
 */
public final class VoxyCompat {
    public static final String MESSAGE = "Voxy needs OpenGL 4.6 and is disabled on Metal (metallium-gl runs it on Metal: see Metallium's status screen)";
    private static boolean noticeShown;
    private static boolean disabledForNatives;

    public static final String SHADER_PAUSE_MESSAGE = "Voxy's far terrain is paused while a shader pack is active (not supported on Metal yet)";
    private static volatile java.util.function.BooleanSupplier shaderPackProbe = () -> false;
    private static final java.util.concurrent.atomic.AtomicLong renderCalls = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong suppressedCalls = new java.util.concurrent.atomic.AtomicLong();
    private static volatile long lastSuppressedNanos;
    private static boolean pauseNoticeShown;

    private VoxyCompat() {
    }

    public static boolean isVoxyLoaded() {
        return FabricLoader.getInstance().isModLoaded("voxy");
    }

    /**
     * True when metallium-gl retargets Voxy's GL calls to Metal (opt-in); then Voxy must start normally. metallium-gl
     * publishes the routed mod ids in a system property at start-up (core does not depend on metallium-gl).
     */
    public static boolean isRoutedThroughMetalliumGl() {
        for (String id : System.getProperty("metallium.gl.routedMods", "").split(",")) {
            if (id.trim().equals("voxy")) {
                return true;
            }
        }
        return false;
    }

    /** Called when Voxy's init was cancelled. */
    public static void onVoxyDisabled() {
        Metallium.LOGGER.warn("[Metallium] {}", MESSAGE);
        MetalliumRuntime.registerStatusLine(() -> "Voxy: disabled (" + MESSAGE + ")");
    }

    /** Called when Voxy's init was cancelled because its storage natives are missing on this Mac. */
    public static void onVoxyDisabledMissingNatives() {
        disabledForNatives = true;
        Metallium.LOGGER.warn("[Metallium] {}", missingNativesMessage());
        MetalliumRuntime.registerStatusLine(() -> "Voxy: disabled (" + missingNativesMessage() + ")");
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
                : MetalliumRuntime.isMetalActive() && !isRoutedThroughMetalliumGl() ? MESSAGE : null;
        if (message == null) {
            return;
        }
        noticeShown = true;
        Minecraft.getInstance().gui.hud.getChat()
                .addClientSystemMessage(Component.literal("[Metallium] " + message).withStyle(ChatFormatting.GOLD));
    }

    /** metallium-iris registers how to tell that a shader pack renders through its Metal pipeline. */
    public static void registerShaderPackProbe(final java.util.function.BooleanSupplier probe) {
        shaderPackProbe = probe;
    }

    /**
     * Called at the head of {@code VoxyRenderSystem.renderOpaque}: true = skip Voxy's render call (Voxy's shader-pack
     * integration hooks Iris's OpenGL pipeline, which the Metal adapter does not support yet: stripes and fire textures).
     * Voxy's world storage and ingest keep running, so LODs are ready when shaders are switched off.
     */
    public static boolean suppressRender() {
        boolean suppress = false;
        try {
            suppress = MetalliumConfig.INSTANCE.voxySuppressWithShaders && MetalliumRuntime.isMetalActive() && shaderPackProbe.getAsBoolean();
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
            Metallium.LOGGER.warn("[Metallium] {}", SHADER_PAUSE_MESSAGE);
            MetalliumRuntime.registerStatusLine(() -> shaderPauseActive() ? "Voxy: " + SHADER_PAUSE_MESSAGE : null);
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().gui.hud.getChat()
                    .addClientSystemMessage(Component.literal("[Metallium] " + SHADER_PAUSE_MESSAGE).withStyle(ChatFormatting.GOLD)));
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
