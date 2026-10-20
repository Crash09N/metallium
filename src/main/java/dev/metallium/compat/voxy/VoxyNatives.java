/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.compat.voxy;

import dev.metallium.Metallium;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Voxy (and mods that bundle the same libraries) ships RocksDB and LWJGL zstd / LMDB without their macOS natives, so on a
 * Mac its world storage throws on world join ("librocksdbjni-osx-arm64.jnilib was not found inside JAR") and Voxy
 * force-crashes the game. The {@code metallium_voxy_natives} addon (jar-in-jar in Metallium's release jar, also
 * standalone) adds exactly those files at the paths each library's loader looks up through Fabric's class loader.
 * This class checks, per bundled library mod, that its macOS arm64 native is findable and matches the addon's version.
 * Only active on macOS arm64; everywhere else {@link #problems()} is empty.
 */
public final class VoxyNatives {
    public static final String ADDON_ID = "metallium_voxy_natives";
    public static final String ADDON_JAR = "metallium-voxy-natives";

    /** A JNI library that a mod bundles as a nested jar mod ({@code libraryModId}), and its macOS arm64 resource. */
    record Native(String libraryModId, String name, String resource, boolean neededByVoxyDefaults) {
    }

    static final List<Native> MACOS_ARM64 = List.of(
            new Native("org_rocksdb_rocksdbjni", "RocksDB", "librocksdbjni-osx-arm64.jnilib", true),
            new Native("org_lwjgl_lwjgl-zstd", "LWJGL zstd", "macos/arm64/org/lwjgl/zstd/liblwjgl_zstd.dylib", true),
            new Native("org_lwjgl_lwjgl-lmdb", "LWJGL LMDB", "macos/arm64/org/lwjgl/lmdb/liblwjgl_lmdb.dylib", false));

    /**
     * One problem: {@code missing} = the native is not on the class path (the library fails to load);
     * otherwise the addon supplies a different version than the bundled Java classes ({@code suppliedVersion}).
     */
    public record Problem(String library, String version, String bundledBy, boolean missing, @Nullable String suppliedVersion,
                          boolean fatalForVoxy) {
        public String describe() {
            return missing
                    ? library + " " + version + " (bundled by " + bundledBy + ") has no macOS native"
                    : library + " " + version + " (bundled by " + bundledBy + "): " + ADDON_JAR + " supplies " + suppliedVersion;
        }
    }

    private static @Nullable List<Problem> cached;

    private VoxyNatives() {
    }

    public static boolean isMacArm64() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.startsWith("mac") && (arch.equals("aarch64") || arch.equals("arm64"));
    }

    public static boolean addonLoaded() {
        return FabricLoader.getInstance().isModLoaded(ADDON_ID);
    }

    public static Optional<String> addonVersion() {
        return FabricLoader.getInstance().getModContainer(ADDON_ID).map(m -> m.getMetadata().getVersion().getFriendlyString());
    }

    /** Problems on this machine (computed once; resources do not change during a session). */
    public static synchronized List<Problem> problems() {
        if (cached == null) {
            cached = isMacArm64() ? scan() : List.of();
            for (Problem p : cached) {
                Metallium.LOGGER.warn("[Metallium] macOS natives: {}", p.describe());
            }
        }
        return cached;
    }

    /** True when Voxy's default storage (RocksDB + zstd) cannot load: Voxy would crash the game on world join. */
    public static boolean blocksVoxy() {
        return FabricLoader.getInstance().isModLoaded("voxy") && problems().stream().anyMatch(p -> p.missing() && p.fatalForVoxy());
    }

    public static boolean anyMissing() {
        return problems().stream().anyMatch(Problem::missing);
    }

    private static List<Problem> scan() {
        FabricLoader loader = FabricLoader.getInstance();
        ClassLoader classLoader = VoxyNatives.class.getClassLoader();
        List<Problem> problems = new ArrayList<>();
        for (Native n : MACOS_ARM64) {
            Optional<ModContainer> library = loader.getModContainer(n.libraryModId());
            if (library.isEmpty()) {
                continue;
            }
            String version = library.get().getMetadata().getVersion().getFriendlyString();
            String bundledBy = library.get().getContainingMod().map(m -> m.getMetadata().getName()).orElse(n.libraryModId());
            boolean fatal = n.neededByVoxyDefaults() && bundledByVoxy(library.get());
            if (classLoader.getResource(n.resource()) == null) {
                problems.add(new Problem(n.name(), version, bundledBy, true, null, fatal));
                continue;
            }
            String supplied = suppliedVersion(n.libraryModId());
            if (supplied != null && !supplied.equals(version)) {
                problems.add(new Problem(n.name(), version, bundledBy, false, supplied, fatal));
            }
        }
        return List.copyOf(problems);
    }

    private static boolean bundledByVoxy(final ModContainer library) {
        return library.getContainingMod().map(m -> m.getMetadata().getId().equals("voxy")).orElse(false);
    }

    /** The library version the addon's natives were taken from ({@code custom."metallium:natives"} in its fabric.mod.json). */
    private static @Nullable String suppliedVersion(final String libraryModId) {
        return FabricLoader.getInstance().getModContainer(ADDON_ID)
                .map(m -> m.getMetadata().getCustomValue("metallium:natives"))
                .filter(v -> v.getType() == CustomValue.CvType.OBJECT)
                .map(v -> v.getAsObject().get(libraryModId))
                .filter(v -> v.getType() == CustomValue.CvType.STRING)
                .map(CustomValue::getAsString)
                .orElse(null);
    }

    /** Status-screen / log summary of the addon state (macOS arm64 only; null elsewhere or without bundled libraries). */
    public static @Nullable String statusLine() {
        if (!isMacArm64()) {
            return null;
        }
        boolean anyLibrary = MACOS_ARM64.stream().anyMatch(n -> FabricLoader.getInstance().isModLoaded(n.libraryModId()));
        if (!anyLibrary) {
            return null;
        }
        if (problems().isEmpty()) {
            return "macOS natives for Voxy's storage: present" + addonVersion().map(v -> " (" + ADDON_JAR + " " + v + ")").orElse("");
        }
        List<String> parts = problems().stream().map(Problem::describe).toList();
        return "macOS natives for Voxy's storage: " + String.join("; ", parts);
    }

    /** What the player should do, one sentence. */
    public static String advice() {
        return addonLoaded()
                ? "Update " + ADDON_JAR + " (or Metallium, which bundles it) to a build for these library versions."
                : "Install " + ADDON_JAR + " (bundled in Metallium's release jar; also available standalone) into the mods folder.";
    }
}
