/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.shaders;

import dev.metallium.Metallium;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

/**
 * Diagnostics ({@code -Dmetallium.shaderDump=<directory>}): writes every MSL source Metallium compiles (and the GLSL
 * it came from, where the caller has it) to that directory, so slow or wrong shader translations can be inspected
 * and profiled offline. Off by default; never used on a normal run.
 */
public final class ShaderDump {
    private static final String DIRECTORY = System.getProperty("metallium.shaderDump", "");

    private ShaderDump() {
    }

    public static boolean enabled() {
        return !DIRECTORY.isEmpty();
    }

    /** {@code name} only needs to be recognisable; a content hash keeps different variants apart. */
    public static void write(final String name, final String extension, final String source) {
        if (DIRECTORY.isEmpty()) {
            return;
        }
        try {
            Path dir = Path.of(DIRECTORY);
            Files.createDirectories(dir);
            String safe = name.replaceAll("[^A-Za-z0-9_.-]", "_");
            String hash = HexFormat.of().toHexDigits(source.hashCode());
            Files.writeString(dir.resolve(safe + "-" + hash + "." + extension), source);
        } catch (IOException | RuntimeException e) {
            Metallium.LOGGER.debug("[metallium] shader dump failed for {}", name, e);
        }
    }
}
