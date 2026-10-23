/*
 * Portions of this file are derived from Metallum (https://github.com/kokodio/metallum),
 * Copyright (c) 2026 kokodio, licensed under the MIT License (see LICENSES/MIT-metallum.txt).
 * Modifications Copyright (c) 2026 Crash09N – NPR Media, licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */
package de.nprmedia.nativium;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import de.nprmedia.nativium.power.PowerMode;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

public final class NativiumConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = configFile();
    public static final NativiumConfig INSTANCE = load();

    /** Selected graphics engine. Null only transiently while migrating a pre-selector config. */
    public GraphicsEngine engine;
    /** Legacy boolean from before the engine selector; read once for migration, never written back. */
    public Boolean enabled;
    /** The vanilla preferredGraphicsBackend value Nativium last wrote, used to detect changes made via vanilla's own option. */
    public String syncedVanillaApi;
    public boolean displayP3 = false;
    /** Mods that call OpenGL directly run through nativium-gl automatically (if it is installed). */
    public boolean glAutoRouting = true;
    /** Per-mod override of the routing decision, by mod id. Missing = AUTO. */
    public Map<String, ModOverride> modOverrides = new TreeMap<>();
    public PowerMode powerMode = PowerMode.AUTOMATIC;
    /** FPS cap for Balanced (and Automatic on battery). Low Power uses min(this, 30). */
    public int batteryFpsLimit = 60;
    /**
     * With Iris on Metal, shader packs do not see {@code MC_OS_MAC} (they use it to mean "Apple OpenGL 4.1: no compute,
     * images or SSBOs" and switch features off, e.g. Complementary's colored lighting). Metal supports those features.
     * Set to false to give packs the real OS macro again.
     */
    public boolean irisHideMacOs = true;
    /**
     * Safety net: while a shader pack without Voxy support (no {@code voxy.json}) renders through the Metal Iris adapter,
     * or Voxy's shader-pack pipeline could not be created, Voxy's LOD rendering is skipped (it would draw unshaded LODs
     * the pack does not expect: stripes / fire textures). Packs with Voxy support draw the LODs. Set to false to test.
     */
    public boolean voxySuppressWithShaders = true;
    /**
     * GPU occlusion culling of Sodium's main-view terrain on Metal ({@code docs/benchmarks/terrain-cull-2026-10-21.md}).
     * AUTO: on at render distance 24 and above (measured +4..+77% at rd32 without a shader pack, +2..+16% with
     * Complementary), also with shader packs (on vs off checked pixel by pixel with a frozen pack clock: Reimagined,
     * Photon, BSL) unless the pack's options bend the world or wave terrain far (world curvature, waving intensity
     * of 10 or more); off below rd 24 (neutral or up to 8% slower). ON: always. Never while Voxy is loaded. The system property {@code -Dnativium.terrain.gpuCull=off|on|count|verify|auto} overrides this.
     */
    public TerrainCulling terrainOcclusionCulling = TerrainCulling.AUTO;

    public enum TerrainCulling {
        AUTO, ON, OFF
    }
    /**
     * Answers to "this mod needs OpenGL" ({@code de.nprmedia.nativium.compat.OpenGlRequirements}), by mod id. Missing = ask
     * before the next title screen (and use OpenGL until answered).
     */
    public Map<String, OpenGlChoice> openGlChoices = new TreeMap<>();

    public enum GraphicsEngine {
        METAL, OPENGL, VULKAN
    }

    /** What to do with a mod that needs Minecraft's OpenGL backend. */
    public enum OpenGlChoice {
        /** Use OpenGL while the mod is installed. */
        OPENGL,
        /** Try Metal anyway (experimental: the mod may abort the game at start-up). */
        METAL
    }

    /** How a mod's direct OpenGL calls are handled. */
    public enum ModOverride {
        /** Native on Metal if the mod only uses Minecraft's graphics layer, else through nativium-gl. */
        AUTO,
        /** Never retarget (the mod's GL calls stay as they are; they fail on Metal). */
        NATIVE,
        /** Retarget all of the mod's classes through nativium-gl, even if the scan found no GL calls. */
        // written as METALLIUM_GL by Metallium (before the rename); still read from migrated configs
        @com.google.gson.annotations.SerializedName(value = "NATIVIUM_GL", alternate = "METALLIUM_GL")
        NATIVIUM_GL
    }

    private NativiumConfig() {
    }

    private static Path configFile() {
        try {
            LegacyMigration.migrateConfigOnce(); // copies config/metallium.json etc. on the first start after the rename
            return FabricLoader.getInstance().getConfigDir().resolve(LegacyMigration.CONFIG_FILE);
        } catch (RuntimeException | LinkageError e) {
            return null; // unit tests: no game directory
        }
    }

    private static NativiumConfig load() {
        if (FILE == null || !Files.isRegularFile(FILE)) {
            return migrate(new NativiumConfig());
        }
        try {
            return parse(Files.readString(FILE));
        } catch (IOException e) {
            return migrate(new NativiumConfig());
        }
    }

    /** Parses and migrates a config file's contents; a broken file yields the defaults. */
    static NativiumConfig parse(final String json) {
        try {
            NativiumConfig config = GSON.fromJson(json, NativiumConfig.class);
            return migrate(config != null ? config : new NativiumConfig());
        } catch (JsonParseException e) {
            return migrate(new NativiumConfig());
        }
    }

    private static NativiumConfig migrate(NativiumConfig config) {
        if (config.engine == null) {
            config.engine = Boolean.FALSE.equals(config.enabled) ? GraphicsEngine.OPENGL : GraphicsEngine.METAL;
        }
        config.enabled = null;
        Map<String, ModOverride> overrides = new TreeMap<>();
        if (config.modOverrides != null) {
            // Unknown enum names (newer/older Nativium) read as null: drop them, and AUTO is the default anyway.
            config.modOverrides.forEach((id, value) -> {
                if (id != null && value != null && value != ModOverride.AUTO) overrides.put(id, value);
            });
        }
        config.modOverrides = overrides;
        Map<String, OpenGlChoice> choices = new TreeMap<>();
        if (config.openGlChoices != null) {
            config.openGlChoices.forEach((id, value) -> {
                if (id != null && value != null) choices.put(id, value);
            });
        }
        config.openGlChoices = choices;
        if (config.powerMode == null) {
            config.powerMode = PowerMode.AUTOMATIC;
        }
        config.batteryFpsLimit = config.batteryFpsLimit <= 0 ? 60 : Math.clamp(config.batteryFpsLimit, 20, 240);
        String override = System.getProperty("nativium.engine", "").trim().toUpperCase(java.util.Locale.ROOT);
        for (GraphicsEngine engine : GraphicsEngine.values()) {
            if (engine.name().equals(override)) {
                config.engine = engine; // test/debug override, e.g. -Dnativium.engine=VULKAN; not persisted unless saved
            }
        }
        return config;
    }

    public ModOverride override(final String modId) {
        return modOverrides.getOrDefault(modId, ModOverride.AUTO);
    }

    public void setOverride(final String modId, final ModOverride value) {
        if (value == ModOverride.AUTO) {
            modOverrides.remove(modId);
        } else {
            modOverrides.put(modId, value);
        }
    }

    String toJson() {
        return GSON.toJson(this);
    }

    public void save() {
        if (FILE == null) {
            return;
        }
        try {
            Files.writeString(FILE, toJson());
        } catch (IOException e) {
            Nativium.LOGGER.warn("Failed to save {}", FILE, e);
        }
    }
}
