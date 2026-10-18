/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl.retarget;

import dev.metallium.gl.MetalGLLog;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Which classes get their GL calls retargeted. A mod opts in by
 * <ul>
 *   <li>declaring {@code "custom": {"metallium-gl": true}} (or {@code {"packages": ["com.example.render"]}}) in its
 *       {@code fabric.mod.json}, or</li>
 *   <li>being listed by the user: {@code -Dmetallium.gl.mods=modid,modid2} or {@code mods=...} in
 *       {@code config/metallium-gl.properties}.</li>
 * </ul>
 * Retargeting is per class: the class files under the mod's root paths (optionally restricted to packages).
 */
public final class OptInMods {
    /** Never retargeted: Minecraft, the loader, Metallium itself and mods with their own Metal adapters. */
    private static final Set<String> NEVER = Set.of("minecraft", "java", "fabricloader", "metallium", "metallium-gl", "metallium-iris",
            "iris", "sodium", "mixinextras");

    private final Map<String, String> classToMod = new HashMap<>();
    private final Map<String, Integer> classCounts = new LinkedHashMap<>();

    private OptInMods() {
    }

    public static OptInMods discover() {
        OptInMods optIn = new OptInMods();
        Map<String, List<String>> requested = new LinkedHashMap<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            CustomValue value = mod.getMetadata().getCustomValue("metallium-gl");
            if (value == null) continue;
            String id = mod.getMetadata().getId();
            if (value.getType() == CustomValue.CvType.BOOLEAN && value.getAsBoolean()) {
                requested.put(id, List.of());
            } else if (value.getType() == CustomValue.CvType.OBJECT) {
                List<String> packages = new ArrayList<>();
                CustomValue list = value.getAsObject().get("packages");
                if (list != null && list.getType() == CustomValue.CvType.ARRAY) {
                    for (CustomValue entry : list.getAsArray()) packages.add(entry.getAsString().replace('.', '/') + "/");
                }
                requested.put(id, packages);
            }
        }
        for (String id : userList()) {
            requested.putIfAbsent(id, List.of());
        }
        for (Map.Entry<String, List<String>> entry : requested.entrySet()) {
            String id = entry.getKey();
            if (NEVER.contains(id)) {
                MetalGLLog.LOGGER.warn("[metallium-gl] {} cannot be retargeted (excluded)", id);
                continue;
            }
            ModContainer mod = FabricLoader.getInstance().getModContainer(id).orElse(null);
            if (mod == null) {
                MetalGLLog.LOGGER.warn("[metallium-gl] opted-in mod {} is not installed", id);
                continue;
            }
            optIn.addMod(id, mod.getRootPaths(), entry.getValue());
        }
        return optIn;
    }

    private static List<String> userList() {
        List<String> ids = new ArrayList<>();
        String property = System.getProperty("metallium.gl.mods", "");
        Path config = FabricLoader.getInstance().getConfigDir().resolve("metallium-gl.properties");
        if (Files.isRegularFile(config)) {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(config)) {
                properties.load(in);
                property = property + "," + properties.getProperty("mods", "");
            } catch (IOException e) {
                MetalGLLog.LOGGER.warn("[metallium-gl] could not read {}: {}", config, e.toString());
            }
        }
        for (String id : property.split(",")) {
            if (!id.isBlank()) ids.add(id.trim());
        }
        return ids;
    }

    void addMod(final String id, final List<Path> roots, final List<String> packages) {
        int count = 0;
        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    if (!relative.endsWith(".class") || relative.startsWith("META-INF/")) continue;
                    String internal = relative.substring(0, relative.length() - ".class".length());
                    if (!packages.isEmpty() && packages.stream().noneMatch(internal::startsWith)) continue;
                    classToMod.put(internal.replace('/', '.'), id);
                    count++;
                }
            } catch (IOException e) {
                MetalGLLog.LOGGER.warn("[metallium-gl] could not list classes of {} in {}: {}", id, root, e.toString());
            }
        }
        classCounts.put(id, count);
        MetalGLLog.LOGGER.info("[metallium-gl] retargeting GL calls of {} ({} classes{})", id, count,
                packages.isEmpty() ? "" : " in " + packages);
    }

    /** Mod id owning {@code className} (binary name with dots) if it is retargeted, else null. */
    public @Nullable String modFor(final String className) {
        return classToMod.get(className);
    }

    public Map<String, Integer> mods() {
        return classCounts;
    }
}
