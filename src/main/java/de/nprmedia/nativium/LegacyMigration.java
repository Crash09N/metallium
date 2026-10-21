// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Crash09N – NPR Media
package de.nprmedia.nativium;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;

/**
 * One-release bridge from the project's former name, Metallium (renamed to Nativium in 0.1.0-alpha).
 *
 * <ul>
 *   <li>System properties: every {@code -Dmetallium.<key>} is copied to {@code nativium.<key>} unless that is set
 *       too ({@link #aliasLegacyProperties()}, first thing at mixin load); one deprecation line is logged on a supported
 *       Mac ({@link #logDeprecatedProperties()}).</li>
 *   <li>Config: on the first start without {@code config/nativium.json}, {@code config/metallium.json},
 *       {@code config/metallium/} and {@code config/metallium-gl.properties} are copied to their Nativium names; the
 *       old files stay. The old session marker ({@code session.json}) is not carried over: it describes a run of the
 *       old mod, whose crash evidence names the old packages, so the first Nativium session starts clean.</li>
 *   <li>Shader cache: entries in {@code .metallium-cache} are keyed by the old mod id and can never hit; the
 *       directory is removed (best effort) and {@code .nativium-cache} starts fresh.</li>
 * </ul>
 * Nothing is written on an unsupported platform (Nativium stays inert there). Kept free of Minecraft classes.
 */
public final class LegacyMigration {
    public static final String LEGACY_PROPERTY_PREFIX = "metallium.";
    public static final String PROPERTY_PREFIX = "nativium.";
    public static final String LEGACY_CONFIG_FILE = "metallium.json";
    public static final String CONFIG_FILE = "nativium.json";
    public static final String LEGACY_CONFIG_DIR = "metallium";
    public static final String CONFIG_DIR = "nativium";
    public static final String LEGACY_GL_PROPERTIES = "metallium-gl.properties";
    public static final String GL_PROPERTIES = "nativium-gl.properties";
    public static final String LEGACY_SHADER_CACHE_DIR = ".metallium-cache";
    /** Not migrated: the per-session crash marker (see the class comment). */
    static final String SESSION_MARKER = "session.json";

    private static final List<String> aliasedProperties = new ArrayList<>();
    private static boolean propertiesAliased;
    private static boolean deprecationLogged;
    private static boolean configChecked;
    private static boolean shaderCacheChecked;

    private LegacyMigration() {
    }

    /** Copies {@code metallium.*} system properties to {@code nativium.*} (not overriding). Idempotent, no logging. */
    public static synchronized void aliasLegacyProperties() {
        if (propertiesAliased) {
            return;
        }
        propertiesAliased = true;
        aliasedProperties.addAll(aliasProperties(System.getProperties()));
    }

    /** Pure step (unit-tested): returns the legacy keys that were copied. */
    static List<String> aliasProperties(final Properties properties) {
        List<String> aliased = new ArrayList<>();
        for (String key : new TreeSet<>(properties.stringPropertyNames())) {
            if (!key.startsWith(LEGACY_PROPERTY_PREFIX) || key.length() == LEGACY_PROPERTY_PREFIX.length()) {
                continue;
            }
            String current = PROPERTY_PREFIX + key.substring(LEGACY_PROPERTY_PREFIX.length());
            if (properties.getProperty(current) == null) {
                properties.setProperty(current, properties.getProperty(key));
                aliased.add(key);
            }
        }
        return aliased;
    }

    /** Logs the deprecated property names once (only called on a supported platform). */
    public static synchronized void logDeprecatedProperties() {
        if (deprecationLogged || aliasedProperties.isEmpty()) {
            return;
        }
        deprecationLogged = true;
        Nativium.LOGGER.warn("[nativium] deprecated system properties {} (Metallium was renamed to Nativium): use -Dnativium.* instead;"
                + " the old names are still read for this release", aliasedProperties.stream().map(k -> "-D" + k).toList());
    }

    /**
     * Migrates the old config files once per process, before the first read of Nativium's config directory. No-op
     * outside a Fabric game (unit tests) and on an unsupported platform.
     */
    public static synchronized void migrateConfigOnce() {
        if (configChecked) {
            return;
        }
        configChecked = true;
        Path configDir;
        try {
            configDir = FabricLoader.getInstance().getConfigDir();
        } catch (RuntimeException | LinkageError e) {
            return; // unit tests: no game directory
        }
        if (!NativiumPlatform.supported()) {
            return;
        }
        try {
            List<String> migrated = migrateConfig(configDir);
            if (!migrated.isEmpty()) {
                Nativium.LOGGER.info("[nativium] Metallium was renamed to Nativium: copied {} to their Nativium names (old files kept)", migrated);
            }
        } catch (IOException | RuntimeException e) {
            Nativium.LOGGER.warn("[nativium] could not migrate the Metallium config: {}; starting with defaults", e.toString());
        }
    }

    /**
     * Pure step (unit-tested): copies the legacy config into {@code configDir} if {@code nativium.json} does not exist
     * yet. Existing Nativium files are never overwritten. Returns the migrated names (relative to {@code configDir}).
     */
    static List<String> migrateConfig(final Path configDir) throws IOException {
        List<String> migrated = new ArrayList<>();
        if (Files.exists(configDir.resolve(CONFIG_FILE))) {
            return migrated; // Nativium ran here before: its config wins, nothing to migrate
        }
        Path legacyFile = configDir.resolve(LEGACY_CONFIG_FILE);
        Path legacyDir = configDir.resolve(LEGACY_CONFIG_DIR);
        Path legacyGl = configDir.resolve(LEGACY_GL_PROPERTIES);
        if (Files.isRegularFile(legacyGl) && !Files.exists(configDir.resolve(GL_PROPERTIES))) {
            Files.copy(legacyGl, configDir.resolve(GL_PROPERTIES), StandardCopyOption.COPY_ATTRIBUTES);
            migrated.add(LEGACY_GL_PROPERTIES);
        }
        if (Files.isDirectory(legacyDir)) {
            Path target = configDir.resolve(CONFIG_DIR);
            List<String> copied = copyTree(legacyDir, target);
            if (!copied.isEmpty()) {
                migrated.add(LEGACY_CONFIG_DIR + "/" + String.join(",", copied));
            }
        }
        // the main file last: its presence marks the migration as done
        if (Files.isRegularFile(legacyFile)) {
            Path tmp = configDir.resolve(CONFIG_FILE + ".migrating");
            Files.copy(legacyFile, tmp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            Files.move(tmp, configDir.resolve(CONFIG_FILE), StandardCopyOption.ATOMIC_MOVE);
            migrated.add(LEGACY_CONFIG_FILE);
        }
        return migrated;
    }

    private static List<String> copyTree(final Path from, final Path to) throws IOException {
        List<String> copied = new ArrayList<>();
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                Path relative = from.relativize(file);
                if (relative.toString().equals(SESSION_MARKER)) {
                    return FileVisitResult.CONTINUE;
                }
                Path target = to.resolve(relative.toString());
                if (!Files.exists(target)) {
                    Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                    copied.add(relative.toString().replace('\\', '/'));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return copied;
    }

    /**
     * Removes the old shader cache below {@code gameDir} once (best effort; a failure only leaves disk space used).
     * Only called by the Metal backend, i.e. on a supported platform.
     */
    public static synchronized void dropLegacyShaderCacheOnce(final Path gameDir) {
        if (shaderCacheChecked) {
            return;
        }
        shaderCacheChecked = true;
        try {
            if (deleteTree(gameDir.resolve(LEGACY_SHADER_CACHE_DIR))) {
                Nativium.LOGGER.info("[nativium] removed the old Metallium shader cache ({}); the Nativium cache starts fresh", LEGACY_SHADER_CACHE_DIR);
            }
        } catch (IOException | RuntimeException e) {
            Nativium.LOGGER.warn("[nativium] could not remove the old shader cache {}: {}", LEGACY_SHADER_CACHE_DIR, e.toString());
        }
    }

    /** Pure step (unit-tested): deletes {@code dir} recursively; false if it did not exist. Symlinks are not followed. */
    static boolean deleteTree(final Path dir) throws IOException {
        if (!Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path d, final IOException e) throws IOException {
                if (e != null) throw e;
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
        return true;
    }
}
