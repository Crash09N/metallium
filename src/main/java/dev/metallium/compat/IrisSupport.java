package dev.metallium.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

/**
 * Which Iris the bundled Metallium Iris adapter supports. Shared by the core (backend selection, status screen) and the
 * adapter (which stays inert unless this says it can work), so a release jar that always ships the adapter is harmless
 * without Iris, with an unsupported Iris, or without Sodium.
 */
public final class IrisSupport {
    public static final String IRIS_RANGE = ">=1.11.6 <1.12";

    private IrisSupport() {
    }

    /** Iris is installed and its version is one the adapter was written against. */
    public static boolean irisSupported() {
        ModContainer mod = FabricLoader.getInstance().getModContainer("iris").orElse(null);
        if (mod == null) return false;
        try {
            return VersionPredicate.parse(IRIS_RANGE).test(mod.getMetadata().getVersion());
        } catch (VersionParsingException e) {
            return false;
        }
    }

    /** The adapter can do its job: supported Iris and Sodium present (Iris itself requires Sodium). */
    public static boolean adapterUsable() {
        return irisSupported() && FabricLoader.getInstance().isModLoaded("sodium");
    }

    /** Why the adapter cannot be used (empty when it can). */
    public static String problem() {
        FabricLoader loader = FabricLoader.getInstance();
        ModContainer iris = loader.getModContainer("iris").orElse(null);
        if (iris == null) return "Iris is not installed";
        if (!irisSupported()) {
            return "Iris " + iris.getMetadata().getVersion().getFriendlyString()
                    + " is not supported by the Metallium Iris adapter (needs " + IRIS_RANGE + ")";
        }
        return loader.isModLoaded("sodium") ? "" : "Sodium is missing";
    }
}
