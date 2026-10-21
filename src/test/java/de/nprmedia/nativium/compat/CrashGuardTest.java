/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashGuardTest {
    private static final CrashGuard.RoutedMod MAP = new CrashGuard.RoutedMod("minimap", "Mini Map", List.of("com.example.minimap.render"), "/mods/minimap.jar");
    private static final CrashGuard.RoutedMod OTHER = new CrashGuard.RoutedMod("other", "Other", List.of("org.other.gl"), null);

    private static final String CRASH_REPORT = """
            ---- Minecraft Crash Report ----
            Description: Rendering overlay

            java.lang.IllegalStateException: bad buffer
            \tat de.nprmedia.nativium.gl.GlContext.bindBuffer(GlContext.java:120)
            \tat com.example.minimap.render.MapRenderer.nativium$gl$3$glBindBuffer(MapRenderer.java)
            \tat com.example.minimap.render.MapRenderer.draw(MapRenderer.java:88)
            \tat net.minecraft.client.renderer.GameRenderer.render(GameRenderer.java:1)
            """;

    @Test
    void routedModInStackTraceIsSuspect() {
        CrashGuard.Recovery r = CrashGuard.match(List.of(MAP, OTHER), CRASH_REPORT, "crash-reports/crash-1.txt");
        assertNotNull(r);
        assertEquals(List.of(MAP), r.suspects());
        assertTrue(r.nativiumGl());
        assertFalse(r.metalBackend());
        assertTrue(r.safeMode());
    }

    @Test
    void ordinaryLogLinesDoNotCount() {
        String log = """
                [12:00:01] [main/INFO]: [nativium-gl] com.example.minimap.render.MapRenderer: 12 GL call sites retargeted
                [12:00:02] [Render thread/INFO]: [nativium] Metal backend enabled (de.nprmedia.nativium.backend.MetalBackend)
                """;
        assertNull(CrashGuard.match(List.of(MAP), log, "logs/x.log.gz"));
    }

    @Test
    void unrelatedCrashIsIgnored() {
        String report = """
                java.lang.NullPointerException
                \tat com.unrelated.Thing.tick(Thing.java:1)
                """;
        assertNull(CrashGuard.match(List.of(MAP), report, "crash"));
    }

    @Test
    void nativeMetalCrashInHsErr() {
        String hsErr = """
                # A fatal error has been detected by the Java Runtime Environment:
                #  SIGSEGV (0xb) at pc=0x0000000190c4f2a8, pid=123, tid=259
                # Problematic frame:
                # C  [AGXMetalG15X_M3+0x1f2a8]
                C  [AGXMetalG15X_M3+0x1f2a8]
                j  de.nprmedia.nativium.backend.MetalCommandEncoder.submit()V+12
                """;
        CrashGuard.Recovery r = CrashGuard.match(List.of(MAP), hsErr, "hs_err_pid123.log");
        assertNotNull(r);
        assertTrue(r.metalBackend());
        assertTrue(r.suspects().isEmpty());
    }

    private static final CrashGuard.RoutedMod PHYSICS = new CrashGuard.RoutedMod("physics", "Physics", List.of("com.example.physics"), "/mods/physics.jar", true);

    private static CrashGuard.Marker marker(final boolean metal, final boolean started) {
        CrashGuard.Marker marker = new CrashGuard.Marker();
        marker.start = System.currentTimeMillis() - 60_000;
        marker.routed = List.of(MAP, PHYSICS);
        marker.metal = metal;
        marker.started = started;
        return marker;
    }

    @Test
    void silentDeathDuringMetalStartupGivesSafeMode(@TempDir final Path game) {
        // e.g. LWJGL "No context is current" abort: no crash report, no hs_err, start-up never finished.
        CrashGuard.Recovery r = CrashGuard.evaluate(marker(true, false), game);
        assertNotNull(r);
        assertTrue(r.startup());
        assertTrue(r.safeMode());
        assertEquals(List.of(PHYSICS), r.suspects(), "routed mods that also use Minecraft's OpenGL backend are the likely cause");
    }

    @Test
    void silentDeathAfterStartupOrWithoutMetalIsIgnored(@TempDir final Path game) {
        assertNull(CrashGuard.evaluate(marker(true, true), game), "killed after start-up (e.g. force quit) is not a Metal problem");
        assertNull(CrashGuard.evaluate(marker(false, false), game), "OpenGL session");
    }

    @Test
    void startupCrashWithUnrelatedCrashReportIsIgnored(@TempDir final Path game) throws IOException {
        Files.createDirectories(game.resolve("crash-reports"));
        Files.writeString(game.resolve("crash-reports/crash-startup.txt"), """
                ---- Minecraft Crash Report ----
                Description: Initializing game

                java.lang.RuntimeException: broken config
                \tat org.unrelated.ConfigLoader.load(ConfigLoader.java:10)
                """);
        assertNull(CrashGuard.evaluate(marker(true, false), game), "a Java crash with a report names its cause itself");
    }

    @Test
    void evaluateFindsNewestEvidenceSinceStart(@TempDir final Path game) throws IOException {
        CrashGuard.Marker marker = new CrashGuard.Marker();
        marker.start = System.currentTimeMillis() - 60_000;
        marker.routed = List.of(MAP);
        Files.createDirectories(game.resolve("crash-reports"));
        Path old = game.resolve("crash-reports/crash-old.txt");
        Files.writeString(old, CRASH_REPORT);
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(marker.start - 3_600_000));
        assertNull(CrashGuard.evaluate(marker, game), "a crash report from before the session is not evidence");

        Files.createDirectories(game.resolve("logs"));
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(game.resolve("logs/2026-10-18-1.log.gz")))) {
            out.write(("[12:00] [Render thread/ERROR]: crashed\n" + CRASH_REPORT).getBytes(StandardCharsets.UTF_8));
        }
        CrashGuard.Recovery r = CrashGuard.evaluate(marker, game);
        assertNotNull(r);
        assertEquals("minimap", r.suspects().getFirst().id());
        assertTrue(r.evidence().endsWith(".log.gz"), r.evidence());
    }

    @Test
    void orderlyShutdownRemovesMarkerUnlessCrashReportWasWritten() {
        assertFalse(CrashGuard.keepMarkerOnShutdown(false), "Cmd+Q, window close, SIGTERM, System.exit(0)");
        assertTrue(CrashGuard.keepMarkerOnShutdown(true), "Minecraft crash path ends in System.exit(-1) after saving a report");
    }

    @Test
    void ranFineNeedsStartupAndMinutesOfHeartbeats() {
        CrashGuard.Marker m = marker(true, true);
        m.startedAt = 1_000_000;
        m.lastAlive = m.startedAt + CrashGuard.RAN_FINE_MS;
        assertTrue(CrashGuard.ranFine(m));
        m.lastAlive = m.startedAt + CrashGuard.RAN_FINE_MS - 1;
        assertFalse(CrashGuard.ranFine(m));
        m.started = false;
        m.lastAlive = m.startedAt + 10 * CrashGuard.RAN_FINE_MS;
        assertFalse(CrashGuard.ranFine(m), "never finished start-up");
    }

    @Test
    void longRunningKilledSessionStaysOnMetalEvenWithMatchingLog(@TempDir final Path game) throws IOException {
        CrashGuard.Marker m = marker(true, true);
        m.routed = List.of(MAP);
        m.startedAt = m.start + 20_000;
        m.lastAlive = m.startedAt + 10 * 60_000;
        Files.createDirectories(game.resolve("logs"));
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(game.resolve("logs/2026-10-18-1.log.gz")))) {
            out.write(CRASH_REPORT.getBytes(StandardCharsets.UTF_8));
        }
        assertNull(CrashGuard.evaluate(m, game), "ran fine for 10 minutes, no crash report / hs_err: user kill");
        // ... but a real crash report from that session still counts.
        Files.createDirectories(game.resolve("crash-reports"));
        Files.writeString(game.resolve("crash-reports/crash-x.txt"), CRASH_REPORT);
        assertNotNull(CrashGuard.evaluate(m, game));
    }

    @Test
    void shortKilledSessionStillChecksLogs(@TempDir final Path game) throws IOException {
        CrashGuard.Marker m = marker(true, true);
        m.routed = List.of(MAP);
        m.startedAt = m.start + 20_000;
        m.lastAlive = m.startedAt + 30_000;
        Files.createDirectories(game.resolve("logs"));
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(game.resolve("logs/2026-10-18-1.log.gz")))) {
            out.write(CRASH_REPORT.getBytes(StandardCharsets.UTF_8));
        }
        assertNotNull(CrashGuard.evaluate(m, game));
    }
}
