/*
 * Portions of this file are derived from Metallum (https://github.com/kokodio/metallum),
 * Copyright (c) 2026 kokodio, licensed under the MIT License (see LICENSES/MIT-metallum.txt).
 * Modifications Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.metallium.power.PowerMode;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

public final class MetalliumConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = configFile();
    public static final MetalliumConfig INSTANCE = load();

    /** Selected graphics engine. Null only transiently while migrating a pre-selector config. */
    public GraphicsEngine engine;
    /** Legacy boolean from before the engine selector; read once for migration, never written back. */
    public Boolean enabled;
    /** The vanilla preferredGraphicsBackend value Metallium last wrote, used to detect changes made via vanilla's own option. */
    public String syncedVanillaApi;
    public boolean displayP3 = false;
    /** Mods that call OpenGL directly run through metallium-gl automatically (if it is installed). */
    public boolean glAutoRouting = true;
    /** Per-mod override of the routing decision, by mod id. Missing = AUTO. */
    public Map<String, ModOverride> modOverrides = new TreeMap<>();
    public PowerMode powerMode = PowerMode.AUTOMATIC;
    /** FPS cap for Balanced (and Automatic on battery). Low Power uses min(this, 30). */
    public int batteryFpsLimit = 60;
    /**
     * Answers to "this mod needs OpenGL" ({@code dev.metallium.compat.OpenGlRequirements}), by mod id. Missing = ask
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
        /** Native on Metal if the mod only uses Minecraft's graphics layer, else through metallium-gl. */
        AUTO,
        /** Never retarget (the mod's GL calls stay as they are; they fail on Metal). */
        NATIVE,
        /** Retarget all of the mod's classes through metallium-gl, even if the scan found no GL calls. */
        METALLIUM_GL
    }

    private MetalliumConfig() {
    }

    private static Path configFile() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("metallium.json");
        } catch (RuntimeException | LinkageError e) {
            return null; // unit tests: no game directory
        }
    }

    private static MetalliumConfig load() {
        if (FILE == null || !Files.isRegularFile(FILE)) {
            return migrate(new MetalliumConfig());
        }
        try {
            return parse(Files.readString(FILE));
        } catch (IOException e) {
            return migrate(new MetalliumConfig());
        }
    }

    /** Parses and migrates a config file's contents; a broken file yields the defaults. */
    static MetalliumConfig parse(final String json) {
        try {
            MetalliumConfig config = GSON.fromJson(json, MetalliumConfig.class);
            return migrate(config != null ? config : new MetalliumConfig());
        } catch (JsonParseException e) {
            return migrate(new MetalliumConfig());
        }
    }

    private static MetalliumConfig migrate(MetalliumConfig config) {
        if (config.engine == null) {
            config.engine = Boolean.FALSE.equals(config.enabled) ? GraphicsEngine.OPENGL : GraphicsEngine.METAL;
        }
        config.enabled = null;
        Map<String, ModOverride> overrides = new TreeMap<>();
        if (config.modOverrides != null) {
            // Unknown enum names (newer/older Metallium) read as null: drop them, and AUTO is the default anyway.
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
        String override = System.getProperty("metallium.engine", "").trim().toUpperCase(java.util.Locale.ROOT);
        for (GraphicsEngine engine : GraphicsEngine.values()) {
            if (engine.name().equals(override)) {
                config.engine = engine; // test/debug override, e.g. -Dmetallium.engine=VULKAN; not persisted unless saved
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
            Metallium.LOGGER.warn("Failed to save {}", FILE, e);
        }
    }
}
