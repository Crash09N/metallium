/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.compat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.metallium.Metallium;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Crash-loop protection. Each session writes {@code config/metallium/session.json} (start time, GL-routed mods and their
 * packages); a clean shutdown deletes it. If the marker is still there on the next start, the previous session ended
 * abnormally: the newest crash report, JVM fatal error log ({@code hs_err_pid*.log}) or rotated game log written since
 * that start is searched for the routed mods' packages, metallium-gl and the Metal backend. On a match this session
 * starts on OpenGL (safe mode) and {@code CrashRecoveryScreen} asks the user what to do before the title screen.
 * Nothing is ever deleted or disabled without an explicit click.
 */
public final class CrashGuard {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** Evidence files larger than this are only read at their end (stack traces are at the end of logs). */
    private static final int MAX_EVIDENCE_BYTES = 4 << 20;

    public record RoutedMod(String id, String name, List<String> packages, @Nullable String jar) {
    }

    /** Previous session's marker. */
    static final class Marker {
        long start;
        String engine;
        List<RoutedMod> routed = new ArrayList<>();
    }

    /**
     * Result of the check: suspect mods (routed mods whose packages appear in the evidence), whether metallium-gl or
     * Metallium's Metal backend itself appear, and which file was the evidence.
     */
    public record Recovery(List<RoutedMod> suspects, boolean metalliumGl, boolean metalBackend, String evidence) {
        public boolean safeMode() {
            return !suspects.isEmpty() || metalliumGl || metalBackend;
        }
    }

    private static boolean initialized;
    private static @Nullable Recovery recovery;
    private static @Nullable Marker current;
    private static boolean dismissed;

    private CrashGuard() {
    }

    /** Off in game tests (a crashed earlier test run must not block the next one) unless {@code -Dmetallium.crashRecovery=on}. */
    private static boolean enabled() {
        String value = System.getProperty("metallium.crashRecovery", "on");
        return !value.equalsIgnoreCase("off") && !value.equalsIgnoreCase("false");
    }

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("metallium");
    }

    /** Reads the previous session's marker and starts this session's. Idempotent. */
    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        Path marker = dir().resolve("session.json");
        Marker previous = readMarker(marker);
        if (previous != null && enabled()) {
            try {
                recovery = evaluate(previous, FabricLoader.getInstance().getGameDir());
            } catch (RuntimeException e) {
                Metallium.LOGGER.warn("[metallium] crash check failed: {}", e.toString());
            }
            if (recovery != null) {
                Metallium.LOGGER.warn("[metallium] previous session crashed (evidence: {}); suspects {}, metallium-gl {}, Metal backend {}."
                        + " Starting on OpenGL this session.", recovery.evidence(), recovery.suspects().stream().map(RoutedMod::id).toList(),
                        recovery.metalliumGl(), recovery.metalBackend());
            } else {
                Metallium.LOGGER.info("[metallium] previous session did not shut down cleanly; no sign that Metallium or a GL-routed mod was involved");
            }
        }
        current = new Marker();
        current.start = System.currentTimeMillis();
        current.engine = dev.metallium.MetalliumConfig.INSTANCE.engine == null ? "?" : dev.metallium.MetalliumConfig.INSTANCE.engine.name();
        writeMarker();
    }

    /** Pending crash recovery (safe mode this session), or null. */
    public static synchronized @Nullable Recovery recovery() {
        init();
        return recovery;
    }

    /** True if this session starts on OpenGL because the last one crashed in Metallium code or a GL-routed mod. */
    public static boolean safeMode() {
        Recovery r = recovery();
        return r != null && r.safeMode();
    }

    /** Whether the recovery screen still has to be shown (once per session). */
    public static synchronized boolean shouldShowScreen() {
        return recovery() != null && !dismissed;
    }

    public static synchronized void dismissScreen() {
        dismissed = true;
    }

    static synchronized void recordRouted(final List<RoutedMod> routed) {
        init();
        current.routed = new ArrayList<>(routed);
        writeMarker();
    }

    /** Clean shutdown: the marker goes away. */
    public static synchronized void markCleanShutdown() {
        try {
            Files.deleteIfExists(dir().resolve("session.json"));
        } catch (IOException e) {
            Metallium.LOGGER.warn("[metallium] could not remove session marker: {}", e.toString());
        }
        current = null;
    }

    private static void writeMarker() {
        if (current == null) return;
        try {
            Files.createDirectories(dir());
            Files.writeString(dir().resolve("session.json"), GSON.toJson(current));
        } catch (IOException e) {
            Metallium.LOGGER.warn("[metallium] could not write session marker: {}", e.toString());
        }
    }

    private static @Nullable Marker readMarker(final Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            Marker marker = GSON.fromJson(Files.readString(file), Marker.class);
            if (marker != null && marker.routed == null) marker.routed = new ArrayList<>();
            return marker;
        } catch (IOException | JsonParseException e) {
            return null;
        }
    }

    /** Finds the evidence written since the previous start and checks it. */
    static @Nullable Recovery evaluate(final Marker previous, final Path gameDir) {
        // Newest first: a JVM fatal error (native Metal crash), then the crash report, then the rotated log.
        List<Path> candidates = new ArrayList<>();
        candidates.addAll(newest(gameDir, "hs_err_pid", ".log", previous.start));
        candidates.addAll(newest(gameDir.resolve("crash-reports"), "crash-", ".txt", previous.start));
        candidates.addAll(newest(gameDir.resolve("logs"), "", ".log.gz", previous.start));
        for (Path file : candidates) {
            String text = read(file);
            if (text == null) continue;
            Recovery r = match(previous.routed, text, gameDir.relativize(file).toString());
            if (r != null) return r;
        }
        return null;
    }

    /** Pure matching (unit-tested): which routed mods / Metallium parts appear in a crash text. */
    static @Nullable Recovery match(final List<RoutedMod> routed, final String fullText, final String evidence) {
        String text = stackLines(fullText);
        List<RoutedMod> suspects = new ArrayList<>();
        for (RoutedMod mod : routed) {
            boolean hit = false;
            for (String pkg : mod.packages()) {
                if (!pkg.isEmpty() && text.contains(pkg + ".")) {
                    hit = true;
                    break;
                }
            }
            if (hit) suspects.add(mod);
        }
        boolean gl = text.contains("dev.metallium.gl.") || text.contains("metallium-gl: unimplemented");
        boolean backend = text.contains("dev.metallium.backend.") || text.contains("libMTL") || text.contains("MTLDebug")
                || text.contains("AGXMetal");
        if (suspects.isEmpty() && !gl && !backend) {
            return null;
        }
        return new Recovery(List.copyOf(suspects), gl, backend, evidence);
    }

    /**
     * Only the lines of a crash text that belong to a stack trace or exception message: Java frames ({@code at ...}),
     * {@code Caused by:}/exception headlines and hs_err frame lines ({@code j }, {@code J }, {@code C }, {@code V }).
     * Ordinary log lines (which name routed classes during normal start-up) are ignored.
     */
    static String stackLines(final String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            String t = line.strip();
            boolean frame = t.startsWith("at ") || t.startsWith("Caused by:") || t.startsWith("j ") || t.startsWith("J ")
                    || t.startsWith("C ") || t.startsWith("V ") || t.startsWith("Description:")
                    || t.matches("^[\\w.$]+(Exception|Error)(: .*)?$");
            if (frame) out.append(t).append('\n');
        }
        return out.toString();
    }

    private static List<Path> newest(final Path dir, final String prefix, final String suffix, final long since) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith(prefix) && n.endsWith(suffix) && modified(p) >= since;
                    })
                    .sorted(Comparator.comparingLong(CrashGuard::modified).reversed())
                    .limit(1)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static long modified(final Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static @Nullable String read(final Path file) {
        try (InputStream raw = Files.newInputStream(file);
             InputStream in = file.toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw) {
            byte[] bytes = in.readAllBytes();
            int from = Math.max(0, bytes.length - MAX_EVIDENCE_BYTES);
            return new String(bytes, from, bytes.length - from, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Renames a routed mod's jar to {@code <name>.jar.disabled} (Fabric ignores it; renaming back re-enables it).
     * Only called from the recovery screen's explicit button.
     */
    public static String disableJar(final RoutedMod mod) throws IOException {
        if (mod.jar() == null) {
            throw new IOException(mod.name() + " is not a plain jar in the mods folder");
        }
        Path jar = Path.of(mod.jar());
        Path target = jar.resolveSibling(jar.getFileName() + ".disabled");
        Files.move(jar, target);
        Metallium.LOGGER.warn("[metallium] disabled {} on user request: {} -> {}", mod.id(), jar, target.getFileName());
        return target.getFileName().toString();
    }
}
