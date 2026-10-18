/*
 * Portions of this file are derived from Metallum (https://github.com/kokodio/metallum),
 * Copyright (c) 2026 kokodio, licensed under the MIT License (see LICENSES/MIT-metallum.txt).
 * Modifications Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MetalliumConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("metallium.json");
    public static final MetalliumConfig INSTANCE = load();

    /** Selected graphics engine. Null only transiently while migrating a pre-selector config. */
    public GraphicsEngine engine;
    /** Legacy boolean from before the engine selector; read once for migration, never written back. */
    public Boolean enabled;
    /** The vanilla preferredGraphicsBackend value Metallium last wrote, used to detect changes made via vanilla's own option. */
    public String syncedVanillaApi;
    public boolean displayP3 = false;

    public enum GraphicsEngine {
        METAL, OPENGL, VULKAN
    }

    private MetalliumConfig() {
    }

    private static MetalliumConfig load() {
        try (Reader reader = Files.newBufferedReader(FILE)) {
            MetalliumConfig config = GSON.fromJson(reader, MetalliumConfig.class);
            return migrate(config != null ? config : new MetalliumConfig());
        } catch (IOException | JsonParseException e) {
            return migrate(new MetalliumConfig());
        }
    }

    private static MetalliumConfig migrate(MetalliumConfig config) {
        if (config.engine == null) {
            config.engine = Boolean.FALSE.equals(config.enabled) ? GraphicsEngine.OPENGL : GraphicsEngine.METAL;
        }
        config.enabled = null;
        String override = System.getProperty("metallium.engine", "").trim().toUpperCase(java.util.Locale.ROOT);
        for (GraphicsEngine engine : GraphicsEngine.values()) {
            if (engine.name().equals(override)) {
                config.engine = engine; // test/debug override, e.g. -Dmetallium.engine=VULKAN; not persisted unless saved
            }
        }
        return config;
    }

    public void save() {
        try {
            Files.writeString(FILE, GSON.toJson(this));
        } catch (IOException e) {
            Metallium.LOGGER.warn("Failed to save {}", FILE, e);
        }
    }
}
