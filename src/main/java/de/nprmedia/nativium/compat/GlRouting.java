/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.compat;

import de.nprmedia.nativium.BackendSelection;
import de.nprmedia.nativium.Nativium;
import de.nprmedia.nativium.NativiumConfig;
import de.nprmedia.nativium.NativiumConfig.ModOverride;
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
 * Decides, per mod, whether its direct OpenGL calls run through nativium-gl. Policy (owner, 2026-10-18):
 * <ol>
 *   <li>a mod that only uses Minecraft's graphics layer runs natively on Metal;</li>
 *   <li>a mod that calls OpenGL directly is routed through nativium-gl automatically (no opt-in needed), unless the
 *       user (config override {@code NATIVE}) or the mod author ({@code "custom": {"nativium-gl": false}}) opts out;</li>
 *   <li>if that crashes, {@link CrashGuard} offers "disable the mod / use OpenGL / try again" on the next start.</li>
 * </ol>
 * Explicit opt-ins (the mod's {@code "nativium-gl": true | {"packages": [...]}}, {@code -Dnativium.gl.mods},
 * {@code config/nativium-gl.properties}, override {@code NATIVIUM_GL}) route a mod as well. Nothing at all is routed
 * when Metal is not used this session (or on an unsupported platform): those mods then run on real OpenGL untouched.
 */
public final class GlRouting {
    public static final String GL_MOD_ID = "nativium-gl";

    /** Routing state of one mod before the game runs (the status screen adds runtime findings). */
    public enum Status {
        /** Uses only Minecraft's graphics layer: runs on Metal as is. */
        NATIVE,
        /** Its GL calls are retargeted to nativium-gl. */
        ROUTED,
        /** Calls OpenGL directly but was opted out (user or author): those calls fail on Metal. */
        OPTED_OUT,
        /** Calls OpenGL directly and nativium-gl is not installed. */
        NEEDS_NATIVIUM_GL,
        /** Calls OpenGL directly; automatic routing is switched off in the settings. */
        AUTO_ROUTING_OFF,
        /** Calls OpenGL directly; Nativium is not using Metal this session, so real OpenGL serves the calls. */
        OPENGL_ENGINE
    }

    /** What the mod author declared in fabric.mod.json under {@code custom.nativium-gl}. */
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

    /**
     * Mods excluded from the scan that nativium-gl routes as a whole (every class) by the same policy as scanned raw-GL
     * mods: Voxy (OpenGL 4.6 renderer, verified on Metal 2026-10-18; without nativium-gl Nativium's safe-disable
     * mixin keeps the game running with Voxy off).
     */
    static final Set<String> WHOLE_MOD = Set.of("voxy");

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
            NativiumConfig config = NativiumConfig.INSTANCE;
            plan = decide(ModScan.results(), authorChoices(), userList(), config.modOverrides, config.glAutoRouting, glInstalled, metal);
            for (String id : WHOLE_MOD) {
                if (loader.isModLoaded(id)) {
                    plan.put(id, decideWholeMod(id, userList(), config.modOverrides, config.glAutoRouting, glInstalled, metal));
                }
            }
            // Mods whose Nativium compatibility code runs their OpenGL paths translated (needs-opengl.json
            // "nativiumGl": "all", e.g. Physics Mod's GPU compute): every class is retargeted, also the ones that only
            // cast engine objects to Minecraft's GL backend types.
            if (metal && glInstalled && config.glAutoRouting) {
                for (OpenGlRequirements.KnownMod known : OpenGlRequirements.known()) {
                    if (!"all".equals(known.nativiumGl()) || known.nativiumCompat() == null) continue;
                    String version = loader.getModContainer(known.id()).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse(null);
                    if (version == null || !OpenGlRequirements.matches(version, known.nativiumCompat())
                            || config.modOverrides.getOrDefault(known.id(), ModOverride.AUTO) == ModOverride.NATIVE) {
                        continue;
                    }
                    plan.put(known.id(), new Decision(known.id(), Status.ROUTED, "all classes, for Nativium's "
                            + (known.name() != null ? known.name() : known.id()) + " compatibility", List.of(), List.of(), true, true));
                }
            }
            List<CrashGuard.RoutedMod> routed = new ArrayList<>();
            for (Decision d : plan.values()) {
                if (d.routed()) {
                    ModReport report = ModScan.report(d.modId());
                    routed.add(new CrashGuard.RoutedMod(d.modId(), report != null ? report.name() : d.modId(),
                            report != null ? report.packages() : List.of(), jarOf(d.modId()), report != null && report.usesBackendInternals()));
                }
                if (d.status() != Status.NATIVE) {
                    Nativium.LOGGER.info("[nativium] {}: {} ({})", d.modId(), d.status(), d.reason());
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
            boolean explicit = override == ModOverride.NATIVIUM_GL || userList.contains(id) || author != null && author.optIn();
            Decision decision;
            if (override == ModOverride.NATIVE) {
                decision = new Decision(id, report.rawGl() ? Status.OPTED_OUT : Status.NATIVE, "override: native", List.of(), List.of(), false, false);
            } else if (!metal) {
                // Metal not used this session: nothing is routed, explicit opt-ins included (owner rule 2026-10-20).
                decision = new Decision(id, report.rawGl() || explicit ? Status.OPENGL_ENGINE : Status.NATIVE,
                        "Metal not active; real OpenGL", List.of(), List.of(), false, false);
            } else if (explicit) {
                String why = override == ModOverride.NATIVIUM_GL ? "override: nativium-gl"
                        : userList.contains(id) ? "listed in nativium.gl.mods" : "mod opts in (fabric.mod.json)";
                List<String> packages = author != null && author.optIn() && override != ModOverride.NATIVIUM_GL ? author.packages() : List.of();
                decision = glInstalled
                        ? new Decision(id, Status.ROUTED, why, List.of(), packages, packages.isEmpty(), false)
                        : new Decision(id, Status.NEEDS_NATIVIUM_GL, why + ", but nativium-gl is not installed", List.of(), List.of(), false, false);
            } else if (!report.rawGl()) {
                decision = new Decision(id, Status.NATIVE, report.usesBackendInternals()
                        ? "uses Minecraft's OpenGL backend classes in " + report.backendClasses() + " classes" : "Minecraft graphics layer only",
                        List.of(), List.of(), false, false);
            } else if (author != null) {
                decision = new Decision(id, Status.OPTED_OUT, "mod opts out (fabric.mod.json)", List.of(), List.of(), false, false);
            } else if (!autoRouting) {
                decision = new Decision(id, Status.AUTO_ROUTING_OFF, "automatic nativium-gl routing is off", List.of(), List.of(), false, false);
            } else if (!glInstalled) {
                decision = new Decision(id, Status.NEEDS_NATIVIUM_GL, "calls OpenGL directly; install nativium-gl", List.of(), List.of(), false, false);
            } else {
                decision = new Decision(id, Status.ROUTED, "calls OpenGL directly (" + report.glClasses().size() + " classes)",
                        report.glClasses(), List.of(), false, true);
            }
            result.put(id, decision);
        }
        return result;
    }

    /** Policy for a {@link #WHOLE_MOD} mod (unit-tested): like a scanned raw-GL mod, but all classes are retargeted. */
    static Decision decideWholeMod(final String id, final Set<String> userList, final Map<String, ModOverride> overrides, final boolean autoRouting,
                                   final boolean glInstalled, final boolean metal) {
        ModOverride override = overrides.getOrDefault(id, ModOverride.AUTO);
        if (override == ModOverride.NATIVE) {
            return new Decision(id, Status.OPTED_OUT, "override: native (Nativium disables it on Metal)", List.of(), List.of(), false, false);
        }
        if (!metal) {
            return new Decision(id, Status.OPENGL_ENGINE, "Metal not active; real OpenGL", List.of(), List.of(), false, false);
        }
        if (override == ModOverride.NATIVIUM_GL || userList.contains(id)) {
            String why = override == ModOverride.NATIVIUM_GL ? "override: nativium-gl" : "listed in nativium.gl.mods";
            return glInstalled ? new Decision(id, Status.ROUTED, why, List.of(), List.of(), true, false)
                    : new Decision(id, Status.NEEDS_NATIVIUM_GL, why + ", but nativium-gl is not installed", List.of(), List.of(), false, false);
        }
        if (!autoRouting) {
            return new Decision(id, Status.AUTO_ROUTING_OFF, "automatic nativium-gl routing is off", List.of(), List.of(), false, false);
        }
        if (!glInstalled) {
            return new Decision(id, Status.NEEDS_NATIVIUM_GL, "OpenGL 4.6 renderer; install nativium-gl to run it on Metal", List.of(), List.of(), false, false);
        }
        return new Decision(id, Status.ROUTED, "OpenGL 4.6 renderer, all classes routed automatically", List.of(), List.of(), true, true);
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

    /** Legacy explicit list: {@code -Dnativium.gl.mods=a,b} and {@code mods=} in {@code config/nativium-gl.properties}. */
    private static Set<String> userList() {
        Set<String> ids = new LinkedHashSet<>();
        String property = System.getProperty("nativium.gl.mods", "");
        Path config = FabricLoader.getInstance().getConfigDir().resolve("nativium-gl.properties");
        if (Files.isRegularFile(config)) {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(config)) {
                properties.load(in);
                property = property + "," + properties.getProperty("mods", "");
            } catch (IOException e) {
                Nativium.LOGGER.warn("[nativium] could not read {}: {}", config, e.toString());
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

    /** Set by nativium-gl once class loading is hooked (routing actually takes effect). */
    public static void markHooked() {
        hooked = true;
    }

    public static boolean hooked() {
        return hooked;
    }

    /** nativium-gl's "mod function" → call count map of unimplemented GL functions. */
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
