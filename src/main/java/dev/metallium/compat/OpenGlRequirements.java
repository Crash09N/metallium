/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.compat;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dev.metallium.Metallium;
import dev.metallium.MetalliumConfig;
import dev.metallium.MetalliumConfig.OpenGlChoice;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-start detection of mods that need Minecraft's OpenGL backend (owner decision, 2026-10-18). Such a mod builds
 * OpenGL backend objects itself ({@code new GlTexture}, {@code extends GlSampler}, ...) and makes the JVM abort on
 * Metal before any crash report is written. Instead of letting that happen, Metallium starts the session on OpenGL and
 * asks the player before the title screen: run on OpenGL (remembered), disable the mod, or try Metal anyway.
 * <p>Sources:
 * <ul>
 *   <li>the data list {@code assets/metallium/compat/needs-opengl.json} (plus an optional
 *       {@code config/metallium/needs-opengl.json} in the same format), with the mod versions that Metallium's own
 *       compatibility code runs on Metal ({@code metalliumCompat}, not asked about);</li>
 *   <li>the scan heuristic: {@link ModReport#buildsBackendObjects()}.</li>
 * </ul>
 * The answer is stored per mod in {@link MetalliumConfig#openGlChoices}.
 */
public final class OpenGlRequirements {
    private static final String RESOURCE = "/assets/metallium/compat/needs-opengl.json";
    private static final Gson GSON = new Gson();

    /** One entry of the data list. {@code metalliumCompat}: Fabric version predicate handled on Metal by Metallium. */
    public record KnownMod(String id, @Nullable String name, @Nullable String reason, @Nullable String metalliumCompat) {
    }

    /** A loaded mod that needs OpenGL; {@code listed}: from the data list (else from the scan heuristic). */
    public record Requirement(String id, String name, String version, String reason, boolean listed) {
    }

    /** What this session does about the requirements. */
    public record Outcome(List<Requirement> requirements, List<Requirement> unanswered, boolean offerMetal, String reason) {
        public static final Outcome NONE = new Outcome(List.of(), List.of(), true, "");

        public boolean ask() {
            return !unanswered.isEmpty();
        }
    }

    private static final class ListFile {
        List<KnownMod> mods;
    }

    private static @Nullable Outcome outcome;
    private static boolean asked;

    private OpenGlRequirements() {
    }

    /** This session's outcome (computed once, from the mod scan and the config). */
    public static synchronized Outcome outcome() {
        if (outcome == null) {
            try {
                List<Requirement> requirements = requirements(ModScan.results(), known(), OpenGlRequirements::matches);
                MetalliumConfig config = MetalliumConfig.INSTANCE;
                CrashGuard.Recovery recovery = CrashGuard.recovery();
                if (recovery != null && recovery.startup() && forgetMetalChoices(requirements, config.openGlChoices)) {
                    // "Try Metal anyway" ended in a start-up abort: ask again next time (the recovery screen covers now).
                    config.save();
                    asked = true;
                }
                outcome = decide(requirements, config.openGlChoices);
            } catch (RuntimeException e) {
                Metallium.LOGGER.warn("[metallium] OpenGL requirement check failed: {}", e.toString());
                outcome = Outcome.NONE;
            }
            for (Requirement r : outcome.requirements()) {
                Metallium.LOGGER.warn("[metallium] {} {} needs Minecraft's OpenGL backend ({}{}); choice: {}", r.name(), r.version(), r.reason(),
                        r.listed() ? "" : ", found by the mod scan", MetalliumConfig.INSTANCE.openGlChoices.getOrDefault(r.id(), null));
            }
        }
        return outcome;
    }

    /** Requirement for one mod id, if any (status screen). */
    public static @Nullable Requirement requirement(final String modId) {
        for (Requirement r : outcome().requirements()) {
            if (r.id().equals(modId)) return r;
        }
        return null;
    }

    /** Whether the choice screen still has to be shown this session (once, before the title screen). */
    public static synchronized boolean shouldAsk() {
        return !asked && outcome().ask() && dev.metallium.BackendSelection.metalSupported()
                && MetalliumConfig.INSTANCE.engine == MetalliumConfig.GraphicsEngine.METAL
                && System.getProperty("metallium.backend", "").isBlank();
    }

    public static synchronized void markAsked() {
        asked = true;
    }

    /** Stores the player's answer for each listed mod and saves the config. */
    public static void choose(final List<Requirement> mods, final @Nullable OpenGlChoice choice) {
        MetalliumConfig config = MetalliumConfig.INSTANCE;
        for (Requirement r : mods) {
            if (choice == null) {
                config.openGlChoices.remove(r.id());
            } else {
                config.openGlChoices.put(r.id(), choice);
            }
        }
        config.save();
    }

    /** Pure (unit-tested): the loaded mods that need OpenGL, from the data list and the scan heuristic. */
    static List<Requirement> requirements(final List<ModReport> reports, final List<KnownMod> known, final VersionMatcher matcher) {
        Map<String, KnownMod> byId = new LinkedHashMap<>();
        for (KnownMod mod : known) {
            if (mod != null && mod.id() != null) byId.put(mod.id(), mod);
        }
        List<Requirement> result = new ArrayList<>();
        for (ModReport report : reports) {
            KnownMod entry = byId.get(report.id());
            if (entry != null) {
                if (entry.metalliumCompat() != null && matcher.matches(report.version(), entry.metalliumCompat())) {
                    continue; // Metallium's compatibility code runs this version on Metal
                }
                result.add(new Requirement(report.id(), entry.name() != null ? entry.name() : report.name(), report.version(),
                        entry.reason() != null ? entry.reason() : "known to need Minecraft's OpenGL backend", true));
            } else if (report.buildsBackendObjects()) {
                List<String> classes = report.backendObjectClasses();
                result.add(new Requirement(report.id(), report.name(), report.version(), "builds Minecraft's OpenGL backend objects in "
                        + classes.size() + (classes.size() == 1 ? " class" : " classes") + " (e.g. " + simpleName(classes.getFirst()) + ")", false));
            }
        }
        return List.copyOf(result);
    }

    /** Pure (unit-tested): Metal only if every requirement was answered with "try Metal anyway". */
    static Outcome decide(final List<Requirement> requirements, final Map<String, OpenGlChoice> choices) {
        if (requirements.isEmpty()) return Outcome.NONE;
        List<Requirement> unanswered = new ArrayList<>();
        List<String> openGl = new ArrayList<>();
        for (Requirement r : requirements) {
            OpenGlChoice choice = choices.get(r.id());
            if (choice == null) unanswered.add(r);
            else if (choice == OpenGlChoice.OPENGL) openGl.add(r.name());
        }
        if (!openGl.isEmpty()) {
            return new Outcome(requirements, List.copyOf(unanswered), false, String.join(", ", openGl) + " needs OpenGL (your choice; config/metallium.json openGlChoices)");
        }
        if (!unanswered.isEmpty()) {
            return new Outcome(requirements, List.copyOf(unanswered), false, String.join(", ", unanswered.stream().map(Requirement::name).toList())
                    + " needs Minecraft's OpenGL backend; OpenGL until you choose");
        }
        return new Outcome(requirements, List.of(), true, "Metal tried anyway for " + String.join(", ", requirements.stream().map(Requirement::name).toList())
                + " (experimental, your choice)");
    }

    /** Drops "try Metal anyway" answers of the given mods; true if any was dropped. */
    static boolean forgetMetalChoices(final List<Requirement> requirements, final Map<String, OpenGlChoice> choices) {
        boolean changed = false;
        for (Requirement r : requirements) {
            if (choices.get(r.id()) == OpenGlChoice.METAL) {
                choices.remove(r.id());
                changed = true;
            }
        }
        return changed;
    }

    @FunctionalInterface
    interface VersionMatcher {
        boolean matches(String version, String predicate);
    }

    static boolean matches(final String version, final String predicate) {
        try {
            return VersionPredicate.parse(predicate).test(Version.parse(version));
        } catch (VersionParsingException | RuntimeException e) {
            return false;
        }
    }

    /** The bundled list plus {@code config/metallium/needs-opengl.json} (entries there replace bundled ones by id). */
    static List<KnownMod> known() {
        List<KnownMod> mods = new ArrayList<>();
        try (InputStream in = OpenGlRequirements.class.getResourceAsStream(RESOURCE)) {
            if (in != null) mods.addAll(parse(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException | JsonParseException e) {
            Metallium.LOGGER.warn("[metallium] could not read {}: {}", RESOURCE, e.toString());
        }
        try {
            Path user = FabricLoader.getInstance().getConfigDir().resolve("metallium").resolve("needs-opengl.json");
            if (Files.isRegularFile(user)) {
                try (Reader reader = Files.newBufferedReader(user)) {
                    List<KnownMod> extra = parse(reader);
                    mods.removeIf(m -> extra.stream().anyMatch(e -> e.id().equals(m.id())));
                    mods.addAll(extra);
                }
            }
        } catch (IOException | RuntimeException e) {
            Metallium.LOGGER.warn("[metallium] could not read config/metallium/needs-opengl.json: {}", e.toString());
        }
        return mods;
    }

    static List<KnownMod> parse(final Reader reader) {
        ListFile file = GSON.fromJson(reader, ListFile.class);
        if (file == null || file.mods == null) return List.of();
        return file.mods.stream().filter(m -> m != null && m.id() != null && !m.id().isBlank()).toList();
    }

    private static String simpleName(final String binary) {
        return binary.substring(binary.lastIndexOf('.') + 1);
    }
}
