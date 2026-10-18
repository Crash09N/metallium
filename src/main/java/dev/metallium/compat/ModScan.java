/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.compat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import dev.metallium.Metallium;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Start-up scan of every loaded mod for direct OpenGL use ({@link GlUsageScanner}). Runs once, as early as
 * metallium-gl's mixin plugin (before mod classes load) or lazily when the status screen asks. Results are cached in
 * {@code config/metallium/scan-cache.json}, keyed by a hash of each mod's class-file listing (names, sizes, times), so
 * an unchanged mod is not read again.
 */
public final class ModScan {
    /**
     * Mods that are never scanned or routed, with the reason shown on the status screen. Minecraft, the loader and
     * Fabric API use Minecraft's own renderer; Sodium, Iris, Distant Horizons and Voxy are handled by Metallium's
     * dedicated compatibility code; Metallium's own modules are Metal-aware.
     */
    public static final Map<String, String> EXCLUDED = Map.ofEntries(
            Map.entry("minecraft", "Minecraft"),
            Map.entry("java", "platform"),
            Map.entry("fabricloader", "Fabric Loader"),
            Map.entry("mixinextras", "library"),
            Map.entry("fabric-api", "Fabric API (uses Minecraft's renderer)"),
            Map.entry("metallium", "Metallium"),
            Map.entry("metallium-gl", "Metallium"),
            Map.entry("metallium-iris", "Metallium"),
            Map.entry("sodium", "Metallium's Sodium backend"),
            Map.entry("iris", "Metallium Iris adapter"),
            Map.entry("distanthorizons", "Metallium's Distant Horizons compat"),
            Map.entry("voxy", "disabled on Metal by Metallium (needs OpenGL 4.6)"));
    private static final int CACHE_VERSION = 3; // 2: mixins into the GL backend are not reported; 3: backendObjectClasses
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static @Nullable List<ModReport> results;
    private static long scanMillis;
    private static int scannedMods;
    private static int cachedMods;

    private ModScan() {
    }

    /** True for ids in {@link #EXCLUDED} and Fabric API modules ({@code fabric-*}). */
    public static boolean excluded(final String id) {
        return EXCLUDED.containsKey(id) || id.startsWith("fabric-");
    }

    /** Reports for all scanned mods (excluded ones are absent), in load order. */
    public static synchronized List<ModReport> results() {
        if (results == null) {
            long start = System.nanoTime();
            results = List.copyOf(scanAll());
            scanMillis = (System.nanoTime() - start) / 1_000_000;
            long raw = results.stream().filter(ModReport::rawGl).count();
            Metallium.LOGGER.info("[metallium] mod scan: {} mods ({} from cache) in {} ms, {} call OpenGL directly",
                    results.size(), cachedMods, scanMillis, raw);
            for (ModReport report : results) {
                if (report.rawGl() || report.usesBackendInternals()) {
                    Metallium.LOGGER.info("[metallium]   {}: {} raw-GL classes, {} classes use Minecraft's OpenGL backend ({} build its objects) {}",
                            report.id(), report.glClasses().size(), report.backendClasses(),
                            report.buildsBackendObjects() ? report.backendObjectClasses().size() : 0, report.functions());
                }
            }
        }
        return results;
    }

    public static synchronized @Nullable ModReport report(final String id) {
        for (ModReport report : results()) {
            if (report.id().equals(id)) return report;
        }
        return null;
    }

    public static synchronized String summary() {
        results();
        return scannedMods + " scanned, " + cachedMods + " cached, " + scanMillis + " ms";
    }

    private static List<ModReport> scanAll() {
        FabricLoader loader = FabricLoader.getInstance();
        Path cacheFile = loader.getConfigDir().resolve("metallium").resolve("scan-cache.json");
        Map<String, CacheEntry> cache = readCache(cacheFile);
        Map<String, CacheEntry> updated = new LinkedHashMap<>();
        List<ModReport> reports = new ArrayList<>();
        for (ModContainer mod : loader.getAllMods()) {
            String id = mod.getMetadata().getId();
            if (excluded(id) || mod.getContainingMod().map(parent -> excluded(parent.getMetadata().getId())).orElse(false)) {
                continue;
            }
            try {
                List<Path> classes = classFiles(mod.getRootPaths());
                String key = key(mod, classes);
                CacheEntry entry = cache.get(id);
                if (entry != null && key.equals(entry.key) && entry.report != null) {
                    cachedMods++;
                } else {
                    entry = new CacheEntry(key, scanMod(mod, classes));
                    scannedMods++;
                }
                updated.put(id, entry);
                reports.add(entry.report);
            } catch (IOException | RuntimeException e) {
                Metallium.LOGGER.warn("[metallium] mod scan: could not scan {}: {}", id, e.toString());
            }
        }
        if (!updated.equals(cache)) {
            writeCache(cacheFile, updated);
        }
        return reports;
    }

    private static List<Path> classFiles(final List<Path> roots) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> {
                    String s = root.relativize(p).toString().replace('\\', '/');
                    return s.endsWith(".class") && !s.startsWith("META-INF/");
                }).forEach(files::add);
            }
        }
        return files;
    }

    /** Hash over id, version and every class file's path, size and modification time (no file contents). */
    private static String key(final ModContainer mod, final List<Path> classes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((CACHE_VERSION + "|" + mod.getMetadata().getId() + "|" + mod.getMetadata().getVersion().getFriendlyString())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (Path file : classes) {
                digest.update((file + "|" + Files.size(file) + "|" + Files.getLastModifiedTime(file).toMillis() + "\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ModReport scanMod(final ModContainer mod, final List<Path> classes) throws IOException {
        List<GlUsageScanner.ClassUsage> usages = new ArrayList<>();
        for (Path file : classes) {
            GlUsageScanner.ClassUsage usage = GlUsageScanner.scan(Files.readAllBytes(file));
            if (usage != null) usages.add(usage);
        }
        return report(mod.getMetadata().getId(), mod.getMetadata().getName(), mod.getMetadata().getVersion().getFriendlyString(), usages);
    }

    /** Folds per-class usages into a mod report (pure; unit-tested). */
    static ModReport report(final String id, final String name, final String version, final List<GlUsageScanner.ClassUsage> usages) {
        List<String> glClasses = new ArrayList<>();
        Set<String> functions = new TreeSet<>();
        Set<String> packages = new LinkedHashSet<>();
        List<String> objects = new ArrayList<>();
        int backend = 0;
        for (GlUsageScanner.ClassUsage usage : usages) {
            if (usage.backendGl()) backend++;
            if (usage.constructsBackend()) objects.add(usage.internalName().replace('/', '.'));
            if (!usage.rawGl()) continue;
            String binary = usage.internalName().replace('/', '.');
            glClasses.add(binary);
            functions.addAll(usage.functions());
            int dot = binary.lastIndexOf('.');
            if (dot > 0) packages.add(binary.substring(0, dot));
        }
        glClasses.sort(null);
        List<String> sample = new ArrayList<>(functions);
        if (sample.size() > 24) sample = new ArrayList<>(sample.subList(0, 24));
        objects.sort(null);
        return new ModReport(id, name, version, List.copyOf(glClasses), backend, List.copyOf(sample), List.copyOf(packages), List.copyOf(objects));
    }

    private static final class CacheEntry {
        String key;
        ModReport report;

        CacheEntry(final String key, final ModReport report) {
            this.key = key;
            this.report = report;
        }

        @Override
        public boolean equals(final Object o) {
            return o instanceof CacheEntry other && java.util.Objects.equals(key, other.key) && java.util.Objects.equals(report, other.report);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(key, report);
        }
    }

    private static final class CacheFile {
        int version;
        Map<String, CacheEntry> mods;
    }

    private static Map<String, CacheEntry> readCache(final Path file) {
        if (!Files.isRegularFile(file)) return new HashMap<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            CacheFile cache = GSON.fromJson(reader, new TypeToken<CacheFile>() { }.getType());
            if (cache == null || cache.version != CACHE_VERSION || cache.mods == null) return new HashMap<>();
            return cache.mods;
        } catch (IOException | JsonParseException | IllegalStateException e) {
            return new HashMap<>(); // corrupt cache: rescan
        }
    }

    private static void writeCache(final Path file, final Map<String, CacheEntry> mods) {
        CacheFile cache = new CacheFile();
        cache.version = CACHE_VERSION;
        cache.mods = mods;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(cache));
        } catch (IOException e) {
            Metallium.LOGGER.warn("[metallium] could not write {}: {}", file, e.toString());
        }
    }
}
