package dev.metallium;

import java.util.Locale;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.network.chat.Component;

/**
 * Labels for vanilla's Graphics API values. On Apple Silicon macOS the "Default" entry means "Metal first" (see
 * PreferredGraphicsApiMixin), so it is shown as "Metal (via Metallium)". OpenGL and Vulkan keep their own names.
 */
public final class GraphicsApiLabels {
    private GraphicsApiLabels() {
    }

    /** True when the DEFAULT entry should be shown as Metal: Apple Silicon macOS and not disabled with -Dmetallium.backend=off. */
    public static boolean relabelDefault() {
        String override = System.getProperty("metallium.backend", "").trim().toLowerCase(Locale.ROOT);
        return !override.equals("off") && !override.equals("false") && BackendSelection.metalSupported();
    }

    public static boolean isMetalEntry(Object value) {
        return value == PreferredGraphicsApi.DEFAULT && relabelDefault();
    }

    public static Component metalName() {
        return Component.translatable("metallium.graphicsApi.metal");
    }

    public static Component metalTooltip() {
        return Component.translatable("metallium.graphicsApi.metal.tooltip");
    }
}
