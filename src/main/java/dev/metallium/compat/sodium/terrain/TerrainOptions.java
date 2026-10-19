/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.compat.sodium.terrain;

/** Terrain optimisation switches inside Sodium ({@code -Dmetallium.terrain.<name>=true|false}). */
public final class TerrainOptions {
    /** Merge adjacent quad draws of a region's batch into one Metal draw ({@link DrawMerger}). */
    public static final boolean MERGE_DRAWS = Boolean.parseBoolean(System.getProperty("metallium.terrain.merge", "false"));

    private TerrainOptions() {
    }
}
