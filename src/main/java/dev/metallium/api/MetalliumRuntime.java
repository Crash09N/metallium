package dev.metallium.api;

/**
 * Stable, minimal runtime queries for other mods and Metallium's own compatibility modules.
 */
public final class MetalliumRuntime {
    private static volatile boolean metalActive;

    private MetalliumRuntime() {
    }

    /** True once the Metal backend created the game's GPU device (i.e. no OpenGL context exists). */
    public static boolean isMetalActive() {
        return metalActive;
    }

    /** Internal: called by the Metal backend after successful device creation. */
    public static void markMetalActive() {
        metalActive = true;
    }
}
