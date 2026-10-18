package dev.metallium.api;

/**
 * Stable, minimal runtime queries for other mods and Metallium's own compatibility modules.
 */
public final class MetalliumRuntime {
    private static volatile boolean metalActive;
    private static final java.util.List<java.util.function.Supplier<String>> STATUS_PROVIDERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    private MetalliumRuntime() {
    }

    /** True once the Metal backend created the game's GPU device (i.e. no OpenGL context exists). */
    public static boolean isMetalActive() {
        return metalActive;
    }

    /**
     * Registers an extra line for Metallium's F3 block (e.g. compatibility adapters reporting their state).
     * A supplier returning null or an empty string adds no line.
     */
    public static void registerStatusLine(final java.util.function.Supplier<String> line) {
        STATUS_PROVIDERS.add(line);
    }

    /** Current extra status lines, in registration order. */
    public static java.util.List<String> statusLines() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (java.util.function.Supplier<String> provider : STATUS_PROVIDERS) {
            try {
                String line = provider.get();
                if (line != null && !line.isEmpty()) {
                    lines.add(line);
                }
            } catch (RuntimeException e) {
                lines.add("(status provider failed: " + e.getClass().getSimpleName() + ")");
            }
        }
        return lines;
    }

    /** Internal: called by the Metal backend after successful device creation. */
    public static void markMetalActive() {
        metalActive = true;
    }
}
