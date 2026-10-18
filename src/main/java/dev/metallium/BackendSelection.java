package dev.metallium;

import net.fabricmc.loader.api.FabricLoader;

import java.util.Locale;

/**
 * Decides whether the Metal backend is offered to vanilla's backend list.
 *
 * <p>Metal is only tried on Apple Silicon macOS. Vanilla keeps its OpenGL and Vulkan backends behind it, so a failed
 * Metal device creation falls back to OpenGL. The decision can be overridden with {@code -Dmetallium.backend=off|metal}.
 */
public final class BackendSelection {
    private static final String PROPERTY = "metallium.backend";
    private static final String IRIS_ADAPTER_MOD_ID = "metallium-iris";

    private static Decision decision;

    private BackendSelection() {
    }

    public static synchronized Decision decision() {
        if (decision == null) {
            decision = decide();
        }
        return decision;
    }

    /** Whether the Metal choice makes sense on this machine (Apple Silicon macOS). */
    public static boolean metalSupported() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") && (arch.equals("aarch64") || arch.equals("arm64"));
    }

    public static boolean shouldOfferMetal() {
        return decision().offerMetal();
    }

    static void logDecision() {
        Decision d = decision();
        if (d.offerMetal()) {
            Metallium.LOGGER.info("[metallium] Metal backend enabled ({})", d.reason());
        } else {
            Metallium.LOGGER.warn("[metallium] Metal backend disabled: {}", d.reason());
        }
    }

    private static Decision decide() {
        dev.metallium.compat.CrashGuard.init(); // reads the last session's marker, starts this one's
        String override = System.getProperty(PROPERTY, "").trim().toLowerCase(Locale.ROOT);
        if (override.equals("off") || override.equals("false")) {
            return new Decision(false, "-D" + PROPERTY + "=" + override);
        }
        boolean forced = override.equals("metal");

        if (!metalSupported() && !forced) {
            return new Decision(false, "not Apple Silicon macOS (" + System.getProperty("os.name") + ", " + System.getProperty("os.arch") + ")");
        }

        if (MetalliumConfig.INSTANCE.engine != MetalliumConfig.GraphicsEngine.METAL && !forced) {
            return new Decision(false, "Graphics Engine is " + MetalliumConfig.INSTANCE.engine + " in config/metallium.json");
        }

        if (!forced && dev.metallium.compat.CrashGuard.safeMode()) {
            return new Decision(false, "the last session crashed in Metallium or a mod running through metallium-gl; OpenGL this session");
        }

        FabricLoader loader = FabricLoader.getInstance();
        if (loader.isModLoaded("iris") && !loader.isModLoaded(IRIS_ADAPTER_MOD_ID)) {
            // Iris 1.11 hard-casts the device to vanilla's GlDevice and would crash on Metal.
            return new Decision(false, "Iris is installed without the Metallium Iris adapter; using OpenGL so Iris keeps working");
        }

        return new Decision(true, forced ? "forced by -D" + PROPERTY + "=metal" : "Apple Silicon macOS");
    }

    public record Decision(boolean offerMetal, String reason) {
    }
}
