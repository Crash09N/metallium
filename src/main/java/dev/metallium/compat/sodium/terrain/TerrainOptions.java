/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.compat.sodium.terrain;

/** Terrain switches inside Sodium ({@code -Dmetallium.terrain.<name>=...}). */
public final class TerrainOptions {
    /**
     * Measurement only ({@code skipDraws=all|main|shadow}): Sodium terrain draws of that kind are not submitted. The
     * CPU/GPU difference to a normal run is the upper bound of what culling could save there ({@code true} = all).
     */
    public static final String SKIP_DRAWS = System.getProperty("metallium.terrain.skipDraws", "none");

    private TerrainOptions() {
    }

    /** True when the terrain draw about to be submitted should be skipped (measurement switch). */
    public static boolean skipDraw() {
        return switch (SKIP_DRAWS) {
            case "true", "all" -> true;
            case "main" -> !IrisShadowState.inShadowPass();
            case "shadow" -> IrisShadowState.inShadowPass();
            default -> false;
        };
    }
}
