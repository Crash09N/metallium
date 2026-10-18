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
    public static final String MESSAGE = "Voxy needs OpenGL 4.6 and is disabled on Metal";
    private static boolean noticeShown;

    private VoxyCompat() {
    }

    public static boolean isVoxyLoaded() {
        return FabricLoader.getInstance().isModLoaded("voxy");
    }

    /** Called when Voxy's init was cancelled. */
    public static void onVoxyDisabled() {
        Metallium.LOGGER.warn("[Metallium] {}", MESSAGE);
        MetalliumRuntime.registerStatusLine(() -> "Voxy: disabled (" + MESSAGE + ")");
    }

    /** One chat message per game session, when the player joins a world. */
    public static void showNoticeOnce() {
        if (noticeShown || !MetalliumRuntime.isMetalActive() || !isVoxyLoaded()) {
            return;
        }
        noticeShown = true;
        Minecraft.getInstance().gui.hud.getChat()
                .addClientSystemMessage(Component.literal("[Metallium] " + MESSAGE).withStyle(ChatFormatting.GOLD));
    }
}
