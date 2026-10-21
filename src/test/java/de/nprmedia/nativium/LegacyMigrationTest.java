/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium;

import de.nprmedia.nativium.NativiumConfig.ModOverride;
import de.nprmedia.nativium.NativiumConfig.OpenGlChoice;
import de.nprmedia.nativium.api.NativiumRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyMigrationTest {
    private static final String OLD_CONFIG = """
            {"engine": "METAL", "glAutoRouting": false, "modOverrides": {"voxy": "METALLIUM_GL", "x": "NATIVE"},
             "openGlChoices": {"physicsmod": "OPENGL"}, "batteryFpsLimit": 45}""";

    @Test
    void legacyPropertiesAreAliasedWithoutOverridingNewOnes() {
        Properties p = new Properties();
        p.setProperty("metallium.backend", "off");
        p.setProperty("metallium.gl.mods", "a,b");
        p.setProperty("nativium.gl.mods", "c");
        p.setProperty("metallium.", "ignored");
        p.setProperty("other.metallium.x", "ignored");
        List<String> aliased = LegacyMigration.aliasProperties(p);
        assertEquals(List.of("metallium.backend"), aliased);
        assertEquals("off", p.getProperty("nativium.backend"));
        assertEquals("c", p.getProperty("nativium.gl.mods"));
        assertEquals("off", p.getProperty("metallium.backend")); // the old one stays
        assertEquals(null, p.getProperty("nativium."));
        assertTrue(LegacyMigration.aliasProperties(p).isEmpty()); // idempotent
    }

    @Test
    void oldConfigIsCopiedOnceAndKept(@TempDir final Path config) throws IOException {
        Files.writeString(config.resolve("metallium.json"), OLD_CONFIG);
        Files.createDirectories(config.resolve("metallium/sub"));
        Files.writeString(config.resolve("metallium/scan-cache.json"), "{\"voxy\": {}}");
        Files.writeString(config.resolve("metallium/needs-opengl.json"), "{\"mods\": []}");
        Files.writeString(config.resolve("metallium/sub/extra.txt"), "x");
        Files.writeString(config.resolve("metallium/session.json"), "{\"start\": 1}");
        Files.writeString(config.resolve("metallium-gl.properties"), "mods=voxy\n");

        List<String> migrated = LegacyMigration.migrateConfig(config);

        assertTrue(migrated.contains("metallium.json"), migrated.toString());
        assertTrue(migrated.contains("metallium-gl.properties"), migrated.toString());
        assertEquals(OLD_CONFIG, Files.readString(config.resolve("nativium.json")));
        assertEquals("{\"voxy\": {}}", Files.readString(config.resolve("nativium/scan-cache.json")));
        assertEquals("{\"mods\": []}", Files.readString(config.resolve("nativium/needs-opengl.json")));
        assertEquals("x", Files.readString(config.resolve("nativium/sub/extra.txt")));
        assertEquals("mods=voxy\n", Files.readString(config.resolve("nativium-gl.properties")));
        // the old session's crash marker is not carried over: the first Nativium session starts clean
        assertFalse(Files.exists(config.resolve("nativium/session.json")));
        assertFalse(Files.exists(config.resolve("nativium.json.migrating")));
        // the old files stay
        assertTrue(Files.exists(config.resolve("metallium.json")));
        assertTrue(Files.exists(config.resolve("metallium/session.json")));
        assertTrue(Files.exists(config.resolve("metallium-gl.properties")));

        // second start: nativium.json exists, nothing is touched (even if the old config changed meanwhile)
        Files.writeString(config.resolve("metallium.json"), "{\"engine\": \"OPENGL\"}");
        Files.delete(config.resolve("nativium/scan-cache.json"));
        assertTrue(LegacyMigration.migrateConfig(config).isEmpty());
        assertEquals(OLD_CONFIG, Files.readString(config.resolve("nativium.json")));
        assertFalse(Files.exists(config.resolve("nativium/scan-cache.json")));
    }

    @Test
    void existingNativiumFilesAreNeverOverwritten(@TempDir final Path config) throws IOException {
        Files.createDirectories(config.resolve("metallium"));
        Files.writeString(config.resolve("metallium/scan-cache.json"), "old");
        Files.createDirectories(config.resolve("nativium"));
        Files.writeString(config.resolve("nativium/scan-cache.json"), "new");
        Files.writeString(config.resolve("nativium-gl.properties"), "mods=new\n");
        Files.writeString(config.resolve("metallium-gl.properties"), "mods=old\n");
        LegacyMigration.migrateConfig(config); // no metallium.json: only the directory and properties are considered
        assertEquals("new", Files.readString(config.resolve("nativium/scan-cache.json")));
        assertEquals("mods=new\n", Files.readString(config.resolve("nativium-gl.properties")));
        assertFalse(Files.exists(config.resolve("nativium.json")));
    }

    @Test
    void nothingToMigrateOnAFreshInstall(@TempDir final Path config) throws IOException {
        assertTrue(LegacyMigration.migrateConfig(config).isEmpty());
        try (var files = Files.list(config)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void migratedConfigKeepsOverridesAndOpenGlChoices() {
        NativiumConfig c = NativiumConfig.parse(OLD_CONFIG);
        assertEquals(Map.of("voxy", ModOverride.NATIVIUM_GL, "x", ModOverride.NATIVE), c.modOverrides);
        assertEquals(Map.of("physicsmod", OpenGlChoice.OPENGL), c.openGlChoices);
        assertFalse(c.glAutoRouting);
        assertEquals(45, c.batteryFpsLimit);
        assertTrue(c.toJson().contains("\"NATIVIUM_GL\""), c.toJson()); // written back under the new name
        assertEquals(ModOverride.NATIVIUM_GL, NativiumConfig.parse(c.toJson()).modOverrides.get("voxy"));
    }

    @Test
    void oldShaderCacheIsDeleted(@TempDir final Path game) throws IOException {
        Path old = game.resolve(".metallium-cache/env-123");
        Files.createDirectories(old);
        Files.writeString(old.resolve("pipelines.bin"), "x");
        assertTrue(LegacyMigration.deleteTree(game.resolve(".metallium-cache")));
        assertFalse(Files.exists(game.resolve(".metallium-cache")));
        assertFalse(LegacyMigration.deleteTree(game.resolve(".metallium-cache")));
    }

    @Test
    @SuppressWarnings("removal")
    void deprecatedApiShimDelegates() {
        String line = "shim-line-" + System.nanoTime();
        dev.metallium.api.MetalliumRuntime.registerStatusLine(() -> line);
        assertTrue(NativiumRuntime.statusLines().contains(line));
        assertEquals(NativiumRuntime.isMetalActive(), dev.metallium.api.MetalliumRuntime.isMetalActive());
        assertEquals(NativiumRuntime.metalDescription(), dev.metallium.api.MetalliumRuntime.metalDescription());
    }
}
