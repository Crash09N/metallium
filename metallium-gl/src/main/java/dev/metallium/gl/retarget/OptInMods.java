/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.gl.retarget;

import dev.metallium.compat.GlRouting;
import dev.metallium.gl.MetalGLLog;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Which classes get their GL calls retargeted. The decision is Metallium core's {@link GlRouting} policy: mods whose
 * classes call {@code org.lwjgl.opengl.*} (found by the start-up scan) are routed automatically when Metal is used;
 * explicit opt-ins still work:
 * <ul>
 *   <li>the mod declares {@code "custom": {"metallium-gl": true}} (or {@code {"packages": ["com.example.render"]}}) in
 *       its {@code fabric.mod.json}; {@code "metallium-gl": false} opts out of automatic routing;</li>
 *   <li>the user lists it: {@code -Dmetallium.gl.mods=modid,modid2}, {@code mods=...} in
 *       {@code config/metallium-gl.properties}, or the per-mod override on the Metallium status screen.</li>
 * </ul>
 * Retargeting is per class: automatic routing covers exactly the classes the scan found; explicit opt-ins cover all
 * class files under the mod's root paths (optionally restricted to packages).
 */
public final class OptInMods {
    private final Map<String, String> classToMod = new HashMap<>();
    private final Map<String, Integer> classCounts = new LinkedHashMap<>();

    private OptInMods() {
    }

    public static OptInMods discover() {
        OptInMods optIn = new OptInMods();
        for (GlRouting.Decision decision : GlRouting.plan().values()) {
            if (!decision.routed()) continue;
            String id = decision.modId();
            if (!decision.classes().isEmpty()) {
                for (String className : decision.classes()) optIn.classToMod.put(className, id);
                optIn.classCounts.put(id, decision.classes().size());
                MetalGLLog.LOGGER.info("[metallium-gl] retargeting GL calls of {} automatically ({} classes call OpenGL)", id, decision.classes().size());
                continue;
            }
            ModContainer mod = FabricLoader.getInstance().getModContainer(id).orElse(null);
            if (mod == null) {
                MetalGLLog.LOGGER.warn("[metallium-gl] opted-in mod {} is not installed", id);
                continue;
            }
            optIn.addMod(id, mod.getRootPaths(), decision.packages());
        }
        return optIn;
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
