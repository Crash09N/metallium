/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.shadercache;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

/**
 * Process-wide shader cache configuration and the opened {@link ShaderDiskCache} (null until the Metal device opened
 * it, or when disabled).
 *
 * <p>System properties: {@code -Dmetallium.shaderCache=false} disables all persistent shader caching (in-memory
 * de-duplication stays); {@code -Dmetallium.shaderCache.maxMB=N} caps the cache directory (default 1024 MiB);
 * {@code -Dmetallium.shaderCache.dir=/path} overrides the location (default {@code <game dir>/.metallium-cache}).
 */
public final class ShaderCache {
    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("metallium.shaderCache", "true").trim());
    public static final long MAX_BYTES = Math.max(16L, Long.getLong("metallium.shaderCache.maxMB", 1024L)) * 1024L * 1024L;
    public static final String DIRECTORY_NAME = ".metallium-cache";

    private static volatile @Nullable ShaderDiskCache disk;

    private ShaderCache() {
    }

    /** The default cache root for a game directory. */
    public static Path root(final Path gameDirectory) {
        String override = System.getProperty("metallium.shaderCache.dir", "").trim();
        return override.isEmpty() ? gameDirectory.resolve(DIRECTORY_NAME) : Path.of(override);
    }

    /** Opens the cache once; later calls return the already opened cache. Null if disabled or not writable. */
    public static synchronized @Nullable ShaderDiskCache open(final Path root, final String environment) {
        if (!ENABLED) {
            return null;
        }
        if (disk == null) {
            disk = ShaderDiskCache.open(root, environment, MAX_BYTES);
        }
        return disk;
    }

    public static @Nullable ShaderDiskCache disk() {
        return disk;
    }
}
