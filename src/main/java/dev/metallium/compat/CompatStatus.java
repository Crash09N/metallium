/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.compat;

import dev.metallium.BackendSelection;
import dev.metallium.Metallium;
import dev.metallium.MetalliumConfig;
import dev.metallium.api.MetalliumRuntime;
import dev.metallium.power.PowerManager;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Builds the compatibility report shown on the Metallium status screen (and logged / copied as text). */
public final class CompatStatus {
    public enum Tone { HEADER, OK, WARN, BAD, INFO }

    /** One line; {@code modId} set for rows of the per-mod table (the screen adds an override button). */
    public record Row(String text, Tone tone, @Nullable String modId) {
        static Row of(final String text, final Tone tone) {
            return new Row(text, tone, null);
        }
    }

    /** Per-mod status including runtime findings. */
    public enum ModState { NATIVE, VIA_METALLIUM_GL, PARTIAL, NOT_WORKING_ON_METAL, OPENGL, DISABLED }

    private CompatStatus() {
    }

    public static List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        FabricLoader loader = FabricLoader.getInstance();
        boolean metal = MetalliumRuntime.isMetalActive();

        rows.add(Row.of("Engine", Tone.HEADER));
        String version = loader.getModContainer(Metallium.MOD_ID).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
        if (metal) {
            String details = MetalliumRuntime.metalDescription();
            rows.add(Row.of("Metallium " + version + ": Metal active" + (details.isEmpty() ? "" : " · " + details), Tone.OK));
        } else {
            BackendSelection.Decision d = BackendSelection.decision();
            rows.add(Row.of("Metallium " + version + ": Metal inactive (" + (d.offerMetal() ? "Metal init failed, fallback in use" : d.reason()) + ")", Tone.WARN));
        }
        rows.add(Row.of("Graphics Engine setting: " + MetalliumConfig.INSTANCE.engine, Tone.INFO));
        CrashGuard.Recovery recovery = CrashGuard.recovery();
        if (recovery != null) {
            rows.add(Row.of("Crash recovery: last session crashed (" + recovery.evidence() + "); OpenGL this session", Tone.BAD));
        }
        String power = PowerManager.statusLine();
        int macos = power.indexOf(" · macOS");
        rows.add(Row.of(macos > 0 ? power.substring(0, macos) : power, Tone.INFO));
        if (macos > 0) rows.add(Row.of("    " + power.substring(macos + 3), Tone.INFO));

        rows.add(Row.of("Key mods", Tone.HEADER));
        rows.add(keyMod(loader, "sodium", "Sodium", metal ? "on Metal (Metallium's Sodium draw backend)" : "on " + engineName(), Tone.OK));
        if (loader.isModLoaded("iris")) {
            boolean adapter = loader.isModLoaded("metallium-iris");
            rows.add(keyMod(loader, "iris", "Iris", adapter ? (metal ? "on Metal via the Metallium Iris adapter" : "adapter installed; Metal inactive")
                    : "no Metallium Iris adapter: Metallium uses OpenGL", adapter ? Tone.OK : Tone.WARN));
        } else {
            rows.add(Row.of("Iris: not installed", Tone.INFO));
        }
        if (loader.isModLoaded("distanthorizons")) {
            rows.add(keyMod(loader, "distanthorizons", "Distant Horizons", metal ? "native on Metal (no shader packs)" : "on " + engineName(), Tone.OK));
        }
        if (loader.isModLoaded("voxy")) {
            rows.add(keyMod(loader, "voxy", "Voxy", metal ? "disabled on Metal (needs OpenGL 4.6)" : "on " + engineName(), metal ? Tone.WARN : Tone.OK));
        }
        boolean gl = loader.isModLoaded(GlRouting.GL_MOD_ID);
        rows.add(Row.of("metallium-gl: " + (gl ? "installed" + (GlRouting.hooked() ? ", class loading hooked" : "") : "not installed")
                + " · automatic routing " + (MetalliumConfig.INSTANCE.glAutoRouting ? "on" : "off"), gl ? Tone.OK : Tone.INFO));

        rows.add(Row.of("Mods (" + ModScan.summary() + ")", Tone.HEADER));
        List<String> nativeMods = new ArrayList<>();
        for (ModReport report : ModScan.results()) {
            GlRouting.Decision decision = GlRouting.plan().get(report.id());
            ModState state = state(report, decision, metal);
            boolean overridden = MetalliumConfig.INSTANCE.override(report.id()) != MetalliumConfig.ModOverride.AUTO;
            if (state == ModState.NATIVE && !overridden && !report.usesBackendInternals()) {
                nativeMods.add(report.name());
                continue;
            }
            rows.add(new Row(report.name() + " (" + report.id() + "): " + describe(state) + (overridden ? " [override]" : ""), tone(state), report.id()));
            if (decision != null && state != ModState.NATIVE) rows.add(Row.of("    " + decision.reason(), Tone.INFO));
            if (report.rawGl()) {
                rows.add(Row.of("    " + report.glClasses().size() + " classes call OpenGL: " + String.join(", ", report.functions()), Tone.INFO));
            }
            if (report.usesBackendInternals()) {
                rows.add(Row.of("    uses Minecraft's OpenGL backend classes in " + report.backendClasses()
                        + " classes (metallium-gl cannot translate these)", Tone.WARN));
            }
            if (report.usesBackendInternals() && OpenGlRequirements.handledByMetallium(report)) {
                rows.add(Row.of("    Metallium's compatibility code covers this version: its OpenGL-only parts run on Metal or are"
                        + " switched off", Tone.OK));
            }
            OpenGlRequirements.Requirement needsGl = OpenGlRequirements.requirement(report.id());
            if (needsGl != null) {
                MetalliumConfig.OpenGlChoice choice = MetalliumConfig.INSTANCE.openGlChoices.get(report.id());
                rows.add(Row.of("    needs OpenGL: " + needsGl.reason() + " · your choice: "
                        + (choice == null ? "not asked yet (OpenGL)" : choice == MetalliumConfig.OpenGlChoice.OPENGL ? "OpenGL" : "try Metal anyway")
                        + " (config/metallium.json openGlChoices)", Tone.WARN));
            }
            List<String> missing = GlRouting.unimplementedFor(report.id());
            if (!missing.isEmpty()) {
                rows.add(Row.of("    unimplemented in metallium-gl: " + String.join(", ", missing), Tone.WARN));
            }
        }
        for (String jar : disabledJars(loader.getGameDir().resolve("mods"))) {
            rows.add(Row.of(jar + ": disabled (rename to .jar to re-enable)", Tone.INFO));
        }
        if (!nativeMods.isEmpty()) {
            rows.add(Row.of(nativeMods.size() + " mods native on Metal (Minecraft graphics layer only):", Tone.OK));
            StringBuilder line = new StringBuilder("    ");
            for (String name : nativeMods) {
                if (line.length() > 90) {
                    rows.add(Row.of(line.toString(), Tone.INFO));
                    line = new StringBuilder("    ");
                }
                line.append(name).append(", ");
            }
            rows.add(Row.of(line.substring(0, line.length() - 2), Tone.INFO));
        }

        List<String> extra = new ArrayList<>(MetalliumRuntime.statusLines());
        extra.removeIf(line -> line.startsWith("Power:")); // already shown above
        if (!extra.isEmpty()) {
            rows.add(Row.of("Adapters", Tone.HEADER));
            for (String line : extra) rows.add(Row.of(line, Tone.INFO));
        }
        return rows;
    }

    static ModState state(final ModReport report, final GlRouting.@Nullable Decision decision, final boolean metal) {
        if (decision == null) {
            return report.rawGl() ? ModState.OPENGL : ModState.NATIVE;
        }
        return switch (decision.status()) {
            case NATIVE -> report.usesBackendInternals() && metal ? ModState.NOT_WORKING_ON_METAL : ModState.NATIVE;
            case ROUTED -> !metal ? ModState.OPENGL
                    : !GlRouting.hooked() ? ModState.NOT_WORKING_ON_METAL
                    : !GlRouting.unimplementedFor(report.id()).isEmpty() || report.usesBackendInternals() ? ModState.PARTIAL
                    : ModState.VIA_METALLIUM_GL;
            case OPTED_OUT, NEEDS_METALLIUM_GL, AUTO_ROUTING_OFF -> metal ? ModState.NOT_WORKING_ON_METAL : ModState.OPENGL;
            case OPENGL_ENGINE -> ModState.OPENGL;
        };
    }

    static String describe(final ModState state) {
        return switch (state) {
            case NATIVE -> "native on Metal";
            case VIA_METALLIUM_GL -> "via metallium-gl";
            case PARTIAL -> "via metallium-gl, partially supported";
            case NOT_WORKING_ON_METAL -> "calls OpenGL directly: will not render on Metal";
            case OPENGL -> "runs on OpenGL (Metal inactive)";
            case DISABLED -> "disabled";
        };
    }

    private static Tone tone(final ModState state) {
        return switch (state) {
            case NATIVE, VIA_METALLIUM_GL, OPENGL -> Tone.OK;
            case PARTIAL, DISABLED -> Tone.WARN;
            case NOT_WORKING_ON_METAL -> Tone.BAD;
        };
    }

    private static Row keyMod(final FabricLoader loader, final String id, final String name, final String state, final Tone tone) {
        return loader.getModContainer(id)
                .map(m -> Row.of(name + " " + m.getMetadata().getVersion().getFriendlyString() + ": " + state, tone))
                .orElse(Row.of(name + ": not installed", Tone.INFO));
    }

    private static String engineName() {
        return MetalliumConfig.INSTANCE.engine == MetalliumConfig.GraphicsEngine.VULKAN ? "Vulkan" : "OpenGL";
    }

    private static List<String> disabledJars(final Path mods) {
        if (!Files.isDirectory(mods)) return List.of();
        try (Stream<Path> files = Files.list(mods)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".jar.disabled")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Plain-text report (for "Copy report" and the log). */
    public static String text() {
        StringBuilder out = new StringBuilder();
        for (Row row : rows()) {
            out.append(row.tone() == Tone.HEADER ? "\n== " + row.text() + " ==" : row.text()).append('\n');
        }
        return out.toString().strip();
    }
}
