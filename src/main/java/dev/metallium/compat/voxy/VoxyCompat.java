package dev.metallium.compat.voxy;

import dev.metallium.Metallium;
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
}
