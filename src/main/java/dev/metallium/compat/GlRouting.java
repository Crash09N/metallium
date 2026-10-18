/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.compat;

import dev.metallium.BackendSelection;
import dev.metallium.Metallium;
import dev.metallium.MetalliumConfig;
import dev.metallium.MetalliumConfig.ModOverride;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Decides, per mod, whether its direct OpenGL calls run through metallium-gl. Policy (owner, 2026-10-18):
 * <ol>
 *   <li>a mod that only uses Minecraft's graphics layer runs natively on Metal;</li>
 *   <li>a mod that calls OpenGL directly is routed through metallium-gl automatically (no opt-in needed), unless the
 *       user (config override {@code NATIVE}) or the mod author ({@code "custom": {"metallium-gl": false}}) opts out;</li>
 *   <li>if that crashes, {@link CrashGuard} offers "disable the mod / use OpenGL / try again" on the next start.</li>
 * </ol>
 * Explicit opt-ins (the mod's {@code "metallium-gl": true | {"packages": [...]}}, {@code -Dmetallium.gl.mods},
 * {@code config/metallium-gl.properties}, override {@code METALLIUM_GL}) are routed even when Metal is not active; the
 * retargeted calls then fall back to real OpenGL. Automatic routing only happens when Metal will be tried.
 */
public final class GlRouting {
    public static final String GL_MOD_ID = "metallium-gl";

    /** Routing state of one mod before the game runs (the status screen adds runtime findings). */
    public enum Status {
        /** Uses only Minecraft's graphics layer: runs on Metal as is. */
        NATIVE,
        /** Its GL calls are retargeted to metallium-gl. */
        ROUTED,
        /** Calls OpenGL directly but was opted out (user or author): those calls fail on Metal. */
        OPTED_OUT,
        /** Calls OpenGL directly and metallium-gl is not installed. */
        NEEDS_METALLIUM_GL,
        /** Calls OpenGL directly; automatic routing is switched off in the settings. */
        AUTO_ROUTING_OFF,
        /** Calls OpenGL directly; Metallium is not using Metal this session, so real OpenGL serves the calls. */
        OPENGL_ENGINE
    }

    /** What the mod author declared in fabric.mod.json under {@code custom.metallium-gl}. */
    public record AuthorChoice(boolean optIn, List<String> packages) {
        public static final AuthorChoice OPT_OUT = new AuthorChoice(false, List.of());
    }

    /**
     * @param classes  binary names to retarget (automatic routing: exactly the classes the scan found)
     * @param packages internal-name prefixes ("com/example/render/") to retarget all classes in; empty with
     *                 {@code allClasses} = the whole mod
     */
    public record Decision(String modId, Status status, String reason, List<String> classes, List<String> packages,
                           boolean allClasses, boolean automatic) {
        public boolean routed() {
            return status == Status.ROUTED;
        }
    }

    /** Excluded from the scan and automatic routing, but routable on explicit request. */
    static final Set<String> EXPLICIT_ONLY = Set.of("voxy");

    private static @Nullable Map<String, Decision> plan;
    private static volatile boolean hooked;
    private static volatile Supplier<Map<String, Long>> unimplemented = Map::of;

    private GlRouting() {
    }

    /** The routing plan for this session (computed once). */
    public static synchronized Map<String, Decision> plan() {
        if (plan == null) {
            FabricLoader loader = FabricLoader.getInstance();
            boolean glInstalled = loader.isModLoaded(GL_MOD_ID);
            boolean metal = BackendSelection.shouldOfferMetal();
            MetalliumConfig config = MetalliumConfig.INSTANCE;
            plan = decide(ModScan.results(), authorChoices(), userList(), config.modOverrides, config.glAutoRouting, glInstalled, metal);
            // Mods excluded from automatic routing because Metallium has dedicated compat for them (Voxy is disabled on
            // Metal) can still be routed explicitly (-Dmetallium.gl.mods / override METALLIUM_GL): experimental.
            for (String id : EXPLICIT_ONLY) {
                if (loader.isModLoaded(id) && glInstalled
                        && (userList().contains(id) || config.modOverrides.getOrDefault(id, ModOverride.AUTO) == ModOverride.METALLIUM_GL)) {
                    plan.put(id, new Decision(id, Status.ROUTED, "routed explicitly (experimental)", List.of(), List.of(), true, false));
                }
            }
            List<CrashGuard.RoutedMod> routed = new ArrayList<>();
            for (Decision d : plan.values()) {
                if (d.routed()) {
                    ModReport report = ModScan.report(d.modId());
                    routed.add(new CrashGuard.RoutedMod(d.modId(), report != null ? report.name() : d.modId(),
                            report != null ? report.packages() : List.of(), jarOf(d.modId())));
                }
                if (d.status() != Status.NATIVE) {
                    Metallium.LOGGER.info("[metallium] {}: {} ({})", d.modId(), d.status(), d.reason());
                }
            }
            CrashGuard.recordRouted(routed);
        }
        return plan;
    }

    /** Pure policy (unit-tested). Reports of excluded mods must not be passed in. */
    static Map<String, Decision> decide(final List<ModReport> reports, final Map<String, AuthorChoice> authors, final Set<String> userList,
                                        final Map<String, ModOverride> overrides, final boolean autoRouting, final boolean glInstalled,
                                        final boolean metal) {
        Map<String, Decision> result = new LinkedHashMap<>();
        for (ModReport report : reports) {
            String id = report.id();
            ModOverride override = overrides.getOrDefault(id, ModOverride.AUTO);
            AuthorChoice author = authors.get(id);
            boolean explicit = override == ModOverride.METALLIUM_GL || userList.contains(id) || author != null && author.optIn();
            Decision decision;
            if (override == ModOverride.NATIVE) {
                decision = new Decision(id, report.rawGl() ? Status.OPTED_OUT : Status.NATIVE, "override: native", List.of(), List.of(), false, false);
            } else if (explicit) {
                String why = override == ModOverride.METALLIUM_GL ? "override: metallium-gl"
                        : userList.contains(id) ? "listed in metallium.gl.mods" : "mod opts in (fabric.mod.json)";
                List<String> packages = author != null && author.optIn() && override != ModOverride.METALLIUM_GL ? author.packages() : List.of();
                decision = glInstalled
                        ? new Decision(id, Status.ROUTED, why, List.of(), packages, packages.isEmpty(), false)
                        : new Decision(id, Status.NEEDS_METALLIUM_GL, why + ", but metallium-gl is not installed", List.of(), List.of(), false, false);
            } else if (!report.rawGl()) {
                decision = new Decision(id, Status.NATIVE, report.usesBackendInternals()
                        ? "uses Minecraft's OpenGL backend classes in " + report.backendClasses() + " classes" : "Minecraft graphics layer only",
                        List.of(), List.of(), false, false);
            } else if (author != null) {
                decision = new Decision(id, Status.OPTED_OUT, "mod opts out (fabric.mod.json)", List.of(), List.of(), false, false);
            } else if (!metal) {
                decision = new Decision(id, Status.OPENGL_ENGINE, "Metal not active; real OpenGL", List.of(), List.of(), false, false);
            } else if (!autoRouting) {
                decision = new Decision(id, Status.AUTO_ROUTING_OFF, "automatic metallium-gl routing is off", List.of(), List.of(), false, false);
            } else if (!glInstalled) {
                decision = new Decision(id, Status.NEEDS_METALLIUM_GL, "calls OpenGL directly; install metallium-gl", List.of(), List.of(), false, false);
            } else {
                decision = new Decision(id, Status.ROUTED, "calls OpenGL directly (" + report.glClasses().size() + " classes)",
                        report.glClasses(), List.of(), false, true);
            }
            result.put(id, decision);
        }
        return result;
    }

    private static Map<String, AuthorChoice> authorChoices() {
        Map<String, AuthorChoice> choices = new LinkedHashMap<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            CustomValue value = mod.getMetadata().getCustomValue(GL_MOD_ID);
            if (value == null) continue;
            String id = mod.getMetadata().getId();
            if (value.getType() == CustomValue.CvType.BOOLEAN) {
                choices.put(id, value.getAsBoolean() ? new AuthorChoice(true, List.of()) : AuthorChoice.OPT_OUT);
            } else if (value.getType() == CustomValue.CvType.OBJECT) {
                List<String> packages = new ArrayList<>();
                CustomValue list = value.getAsObject().get("packages");
                if (list != null && list.getType() == CustomValue.CvType.ARRAY) {
                    for (CustomValue entry : list.getAsArray()) packages.add(entry.getAsString().replace('.', '/') + "/");
                }
                CustomValue enabled = value.getAsObject().get("enabled");
                boolean optIn = enabled == null || enabled.getType() != CustomValue.CvType.BOOLEAN || enabled.getAsBoolean();
                choices.put(id, optIn ? new AuthorChoice(true, List.copyOf(packages)) : AuthorChoice.OPT_OUT);
            }
        }
        return choices;
    }

    /** Legacy explicit list: {@code -Dmetallium.gl.mods=a,b} and {@code mods=} in {@code config/metallium-gl.properties}. */
    private static Set<String> userList() {
        Set<String> ids = new LinkedHashSet<>();
        String property = System.getProperty("metallium.gl.mods", "");
        Path config = FabricLoader.getInstance().getConfigDir().resolve("metallium-gl.properties");
        if (Files.isRegularFile(config)) {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(config)) {
                properties.load(in);
                property = property + "," + properties.getProperty("mods", "");
            } catch (IOException e) {
                Metallium.LOGGER.warn("[metallium] could not read {}: {}", config, e.toString());
            }
        }
        for (String id : property.split(",")) {
            if (!id.isBlank()) ids.add(id.trim());
        }
        return ids;
    }

    /** The mod's jar if it is a plain jar file (not nested, not a dev directory), else null. */
    public static @Nullable String jarOf(final String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .filter(mod -> mod.getOrigin().getKind() == net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH)
                .flatMap(mod -> mod.getOrigin().getPaths().stream().filter(p -> p.toString().endsWith(".jar") && Files.isRegularFile(p)).findFirst())
                .map(Path::toString)
                .orElse(null);
    }

    /** Set by metallium-gl once class loading is hooked (routing actually takes effect). */
    public static void markHooked() {
        hooked = true;
    }

    public static boolean hooked() {
        return hooked;
    }

    /** metallium-gl's "mod function" → call count map of unimplemented GL functions. */
    public static void setUnimplementedSource(final Supplier<Map<String, Long>> source) {
        unimplemented = source;
    }

    /** Unimplemented GL functions seen this session for one mod. */
    public static List<String> unimplementedFor(final String modId) {
        List<String> functions = new ArrayList<>();
        try {
            unimplemented.get().forEach((key, calls) -> {
                if (key.startsWith(modId + " ")) functions.add(key.substring(modId.length() + 1) + " ×" + calls);
            });
        } catch (RuntimeException ignored) {
            // status only
        }
        return functions;
    }
}
