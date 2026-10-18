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

    public boolean enabled = true;
    public boolean displayP3 = false;

    private MetalliumConfig() {
    }

    private static MetalliumConfig load() {
        try (Reader reader = Files.newBufferedReader(FILE)) {
            MetalliumConfig config = GSON.fromJson(reader, MetalliumConfig.class);
            return config != null ? config : new MetalliumConfig();
        } catch (IOException | JsonParseException e) {
            return new MetalliumConfig();
        }
    }

    public void save() {
        try {
            Files.writeString(FILE, GSON.toJson(this));
        } catch (IOException e) {
            Metallium.LOGGER.warn("Failed to save {}", FILE, e);
        }
    }
}
