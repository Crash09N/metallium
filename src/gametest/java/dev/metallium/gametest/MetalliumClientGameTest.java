package dev.metallium.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end check that the game boots and renders worlds on the Metal backend.
 * Writes {@code metallium-gametest-report.txt} into the run directory and screenshots into {@code screenshots/}.
 * Set {@code -Dmetallium.gametest.expectBackend=Metal} to fail when a different backend was chosen.
 */
public final class MetalliumClientGameTest implements FabricClientGameTest {
    private static final String BENCH_SEED = "metallium";
    /** x y z yaw pitch, overridable with -Dmetallium.bench.camera="...". */
    private static final String BENCH_CAMERA = System.getProperty("metallium.bench.camera", "0 140 0 30 25");
    private static final int BENCH_SECONDS = Integer.getInteger("metallium.bench.seconds", 30);

    /**
     * Reproducible benchmark: fixed seed, fixed spawn view, VSync off, no FPS cap, render distance 16, chunk fade off.
     * Waits until section building settles, then samples FPS once per second and frame times once per tick.
     */
    private void benchmark(ClientGameTestContext context, String backend) {
        line("mode: benchmark, seed=" + BENCH_SEED + ", seconds=" + BENCH_SECONDS);
        context.runOnClient(client -> {
            client.options.enableVsync().set(false);
            client.options.framerateLimit().set(260); // 260 = unlimited
            client.options.chunkSectionFadeInTime().set(0.0);
            // Vanilla drops to 30 fps when there is no input for a while ("AFK"); a benchmark has no input.
            client.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
        });
        try (TestSingleplayerContext world = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(state -> state.setSeed(BENCH_SEED))
                .create()) {
            world.getConnection().waitForChunksRender();
            // Identical camera for every run: spectator at a fixed position and angle.
            tryCommand(world, "gamerule spawn_monsters false");
            tryCommand(world, "gamemode spectator @a");
            tryCommand(world, "tp @a " + BENCH_CAMERA);
            tryCommand(world, "time set 6000");
            tryCommand(world, "gamerule advance_time false");
            tryCommand(world, "weather clear");
            // Raise the render distance only after the initial load (waitForChunksRender at RD 16 times out).
            context.runOnClient(client -> client.options.renderDistance().set(16));
            context.waitTicks(900); // ~45 s: let chunk loading and section building finish at RD 16
            checkTerrainVisible(context.takeScreenshot("metallium-bench-view"), "bench view");
            sodiumStats(context, "bench");

            List<Integer> fpsSamples = new ArrayList<>();
            List<Double> frameMs = new ArrayList<>();
            for (int second = 0; second < BENCH_SECONDS; second++) {
                for (int tick = 0; tick < 20; tick++) {
                    context.waitTick();
                    frameMs.add(context.computeOnClient(Minecraft::getFrameTimeNs) / 1e6);
                }
                fpsSamples.add(context.computeOnClient(Minecraft::getFps));
            }
            fpsSamples.sort(null);
            frameMs.sort(null);
            double avgFps = fpsSamples.stream().mapToInt(Integer::intValue).average().orElse(0);
            line(String.format("bench: avg fps=%.1f, min fps=%d, median fps=%d", avgFps, fpsSamples.get(0), fpsSamples.get(fpsSamples.size() / 2)));
            line(String.format("bench: frame ms p50=%.2f p95=%.2f p99=%.2f max=%.2f (sampled per tick)",
                    pct(frameMs, 0.50), pct(frameMs, 0.95), pct(frameMs, 0.99), frameMs.get(frameMs.size() - 1)));
            line("result: PASS");
        }
    }

    private static final String TEST_PACK = "metallium-testpack";
    private static final String[] TEST_PACK_FILES = {"composite.vsh", "composite.fsh", "final.vsh", "final.fsh"};
    private static final String GBUFFER_PACK = "metallium-gbufferpack";
    private static final String[] GBUFFER_PACK_FILES = {"gbuffers_terrain.vsh", "gbuffers_terrain.fsh", "composite.vsh", "composite.fsh"};

    /**
     * Iris shader packs (needs -PwithIris): with a fixed view, compares a shaders-off screenshot with one taken with the
     * bundled test pack, whose composite pass inverts the image. Optionally (-Dmetallium.gametest.shaderpack=/path.zip)
     * also loads an external pack and records what happens (no pixel check: it only must not crash). The external pack
     * is copied into the run directory at runtime and never into the repository.
     */
    private void shaderPackScenario(ClientGameTestContext context, TestSingleplayerContext world) {
        tryCommand(world, "time set 6000");
        tryCommand(world, "gamerule advance_time false");
        tryCommand(world, "weather clear");
        tryCommand(world, "gamemode spectator @a");
        // Entities in view for the gbuffers (entities program) - NoAI so every screenshot sees the same scene.
        tryCommand(world, "execute as @p at @s run summon minecraft:pig ^-1 ^-1 ^7 {NoAI:1b,Rotation:[90f,0f]}");
        tryCommand(world, "execute as @p at @s run summon minecraft:sheep ^2 ^-1 ^8 {NoAI:1b,Rotation:[90f,0f]}");
        context.waitTicks(40);
        clearChat(context);
        Path off = context.takeScreenshot("metallium-iris-shaders-off");

        try {
            installTestPack(context, TEST_PACK, TEST_PACK_FILES);
            installTestPack(context, GBUFFER_PACK, GBUFFER_PACK_FILES);
        } catch (IOException e) {
            failures.add("could not install test shader pack: " + e);
            return;
        }

        line("iris test pack: pipeline=" + context.computeOnClient(client -> irisSelectPack(TEST_PACK, true)));
        context.waitTicks(60);
        clearChat(context);
        Path on = context.takeScreenshot("metallium-iris-testpack");
        checkInverted(off, on);

        // Stage 3: gbuffers_terrain writes a marker into colortex1, the composite pass turns marked pixels magenta.
        line("iris gbuffers test pack: pipeline=" + context.computeOnClient(client -> irisSelectPack(GBUFFER_PACK, true)));
        context.waitTicks(100); // the pack switches Sodium to Iris's extended vertex format: chunks are rebuilt
        clearChat(context);
        checkGbufferMarker(context.takeScreenshot("metallium-iris-gbufferpack"));

        String external = externalPack(context);
        if (external != null) {
            line("external pack " + external + ": pipeline=" + context.computeOnClient(client -> irisSelectPack(external, true)));
            context.waitTicks(100);
            context.takeScreenshot("metallium-iris-external-pack");
            line("external pack: still running after 100 ticks");
            // More gbuffers programs: block entities (chest, sign), weather (rain). Removed again before the
            // shaders-off comparison below.
            tryCommand(world, "execute as @p at @s positioned ^ ^-1 ^4 run setblock ~ ~ ~ minecraft:chest");
            tryCommand(world, "execute as @p at @s positioned ^1 ^-1 ^4 run setblock ~ ~ ~ minecraft:oak_sign");
            tryCommand(world, "weather rain");
            context.waitTicks(80);
            clearChat(context);
            context.takeScreenshot("metallium-iris-external-pack-rain");
            tryCommand(world, "weather clear");
            tryCommand(world, "execute as @p at @s positioned ^ ^-1 ^4 run setblock ~ ~ ~ minecraft:air");
            tryCommand(world, "execute as @p at @s positioned ^1 ^-1 ^4 run setblock ~ ~ ~ minecraft:air");
            context.waitTicks(40);
        }

        line("iris shaders off: pipeline=" + context.computeOnClient(client -> irisSelectPack(TEST_PACK, false)));
        context.waitTicks(40);
        clearChat(context);
        Path offAgain = context.takeScreenshot("metallium-iris-shaders-off-again");
        double same = matchFraction(off, offAgain, false);
        line(String.format("iris shaders off again: match with first shaders-off screenshot=%.2f", same));
        if (same < 0.80) {
            failures.add(String.format("shaders off after a pack differs from vanilla (match %.2f)", same));
        }
    }

    private static void installTestPack(ClientGameTestContext context, String pack, String[] files) throws IOException {
        Path packDir = context.computeOnClient(client -> irisShaderpacksDirectory()).resolve(pack).resolve("shaders");
        Files.createDirectories(packDir);
        for (String file : files) {
            try (var in = MetalliumClientGameTest.class.getResourceAsStream("/" + pack + "/shaders/" + file)) {
                Files.copy(java.util.Objects.requireNonNull(in, file), packDir.resolve(file), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Copies -Dmetallium.gametest.shaderpack (a local pack, never in the repository) into the run's pack folder. */
    private String externalPack(ClientGameTestContext context) {
        String external = System.getProperty("metallium.gametest.shaderpack", "");
        if (external.isEmpty()) {
            return null;
        }
        try {
            Path source = Path.of(external);
            Path target = context.computeOnClient(client -> irisShaderpacksDirectory()).resolve(source.getFileName().toString());
            if (!Files.exists(target)) {
                Files.copy(source, target);
            }
            return source.getFileName().toString();
        } catch (IOException e) {
            line("external pack could not be copied: " + e);
            return null;
        }
    }

    /**
     * Gbuffers test pack: terrain (lower band of the flat-world view) must be magenta, i.e. gbuffers_terrain ran on
     * Metal and wrote colortex1, which the composite pass read; the sky (upper band) must not be.
     */
    private void checkGbufferMarker(Path screenshot) {
        try {
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(screenshot.toFile());
            double terrain = magentaFraction(image, 0.60, 0.85);
            double sky = magentaFraction(image, 0.05, 0.30);
            line(String.format("iris gbuffers test pack: magenta (gbuffer marker) fraction terrain band=%.2f, sky band=%.2f", terrain, sky));
            if (terrain < 0.80 || sky > 0.20) {
                failures.add(String.format("gbuffers -> composite data flow not visible (terrain %.2f, sky %.2f)", terrain, sky));
            }
        } catch (IOException e) {
            failures.add("could not analyse gbuffers screenshot: " + e);
        }
    }

    private static double magentaFraction(java.awt.image.BufferedImage image, double fromY, double toY) {
        int w = image.getWidth();
        int h = image.getHeight();
        int total = 0;
        int magenta = 0;
        for (int y = (int) (h * fromY); y < (int) (h * toY); y += 2) {
            for (int x = (int) (w * 0.05); x < (int) (w * 0.70); x += 2) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                total++;
                if (r > 200 && g < 60 && b > 200) {
                    magenta++;
                }
            }
        }
        return (double) magenta / Math.max(1, total);
    }

    /** Chat messages (e.g. Metallium's shader-pack notices) would cover part of the compared area. */
    private static void clearChat(ClientGameTestContext context) {
        context.runOnClient(client -> client.gui.hud.getChat().clearMessages(false));
        context.waitTick();
    }

    /** The test pack inverts colours: most pixels of {@code on} must be 255 minus the same pixel of {@code off}. */
    private void checkInverted(Path off, Path on) {
        double inverted = matchFraction(off, on, true);
        double unchanged = matchFraction(off, on, false);
        line(String.format("iris test pack: inverted-pixel fraction=%.2f, unchanged-pixel fraction=%.2f", inverted, unchanged));
        if (inverted < 0.80) {
            failures.add(String.format("test shader pack effect not visible (inverted fraction %.2f)", inverted));
        }
    }

    /** Fraction of sampled pixels where {@code b} equals {@code a} (or its inverse) within a tolerance per channel. */
    private double matchFraction(Path a, Path b, boolean inverse) {
        try {
            java.awt.image.BufferedImage imageA = javax.imageio.ImageIO.read(a.toFile());
            java.awt.image.BufferedImage imageB = javax.imageio.ImageIO.read(b.toFile());
            int w = Math.min(imageA.getWidth(), imageB.getWidth());
            int h = Math.min(imageA.getHeight(), imageB.getHeight());
            int total = 0;
            int match = 0;
            for (int y = h / 20; y < h * 7 / 10; y += 3) { // above chat and hotbar
                for (int x = w / 20; x < w - w / 20; x += 3) {
                    int pa = imageA.getRGB(x, y);
                    int pb = imageB.getRGB(x, y);
                    boolean ok = true;
                    for (int shift = 0; shift <= 16; shift += 8) {
                        int ca = (pa >> shift) & 0xFF;
                        int cb = (pb >> shift) & 0xFF;
                        if (Math.abs((inverse ? 255 - ca : ca) - cb) > 40) {
                            ok = false;
                        }
                    }
                    total++;
                    if (ok) {
                        match++;
                    }
                }
            }
            return (double) match / Math.max(1, total);
        } catch (IOException e) {
            line("could not compare screenshots: " + e);
            return 0;
        }
    }

    // Iris is only on the runtime classpath of -PwithIris runs, so it is driven by reflection.
    private static Path irisShaderpacksDirectory() {
        try {
            return (Path) Class.forName("net.irisshaders.iris.Iris").getMethod("getShaderpacksDirectory").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Selects a pack (or turns shaders off), reloads Iris and returns the class name of the resulting pipeline. */
    private static String irisSelectPack(String pack, boolean enabled) {
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Object config = iris.getMethod("getIrisConfig").invoke(null);
            config.getClass().getMethod("setShaderPackName", String.class).invoke(config, pack);
            config.getClass().getMethod("setShadersEnabled", boolean.class).invoke(config, enabled);
            config.getClass().getMethod("save").invoke(config);
            iris.getMethod("reload").invoke(null);
            Object manager = iris.getMethod("getPipelineManager").invoke(null);
            Object pipeline = manager.getClass().getMethod("preparePipeline", Class.forName("net.irisshaders.iris.shaderpack.materialmap.NamespacedId"))
                    .invoke(manager, iris.getMethod("getCurrentDimension").invoke(null));
            return pipeline == null ? "none" : pipeline.getClass().getName();
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return "error: " + cause;
        }
    }

    private void tryCommand(TestSingleplayerContext world, String command) {
        try {
            world.getServer().runCommand(command);
        } catch (Throwable t) {
            line("command failed (ignored): /" + command + " -> " + t.getMessage());
        }
    }

    private static double pct(List<Double> sorted, double p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(p * sorted.size())));
    }

    private static final int SETTLE_TICKS = Integer.getInteger("metallium.gametest.settleTicks", 200);
    private final List<String> report = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        String backend = context.computeOnClient(client -> {
            DeviceInfo info = RenderSystem.getDevice().getDeviceInfo();
            return info.backendName() + " | " + info.name() + " | " + info.driverInfo();
        });
        line("backend: " + backend);
        line("sodium loaded: " + net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium"));
        line("iris loaded: " + net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris")
                + ", adapter: " + net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("metallium-iris"));
        context.takeScreenshot("metallium-title");

        if ("bench".equals(System.getProperty("metallium.gametest.mode"))) {
            try {
                benchmark(context, backend);
            } finally {
                writeReport();
            }
            return;
        }

        String expected = System.getProperty("metallium.gametest.expectBackend", "");
        try {
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                world.getConnection().waitForChunksRender();
                // Sodium builds sections asynchronously after vanilla reports chunks as rendered; give it time.
                context.waitTicks(SETTLE_TICKS);
                checkTerrainVisible(context.takeScreenshot("metallium-flat-world"), "flat world");
                measure(context, "flat world");
                // Diagnostic A/B: chunk fade-in off (Sodium reads per-section fade times from a texel buffer).
                context.runOnClient(client -> client.options.chunkSectionFadeInTime().set(0.0));
                context.waitTicks(20);
                Path noFade = context.takeScreenshot("metallium-flat-world-nofade");
                int before = failures.size();
                checkTerrainVisible(noFade, "flat world (no chunk fade)");
                while (failures.size() > before) {
                    failures.remove(failures.size() - 1); // diagnostic only
                }
                context.runOnClient(client -> client.options.chunkSectionFadeInTime().set(0.75));
                if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris")) {
                    shaderPackScenario(context, world);
                }
            }

            try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(false)
                    .adjustSettings(state -> state.setSeed(System.getProperty("metallium.gametest.seed", "metallium-forest"))).create()) {
                world.getConnection().waitForChunksRender();
                context.waitTicks(SETTLE_TICKS);
                checkTerrainVisible(context.takeScreenshot("metallium-natural-world"), "natural world");
                measure(context, "natural world");
                if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris")) {
                    String external = externalPack(context);
                    if (external != null) {
                        tryCommand(world, "time set 1000");
                        // Stand on top of the terrain/canopy, looking slightly down, with an animal in front.
                        tryCommand(world, "execute as @p at @s positioned over motion_blocking run tp @s ~ ~ ~ ~ 20");
                        tryCommand(world, "execute as @p at @s run summon minecraft:cow ^ ^1 ^5 {NoAI:1b}");
                        line("external pack (natural world): pipeline=" + context.computeOnClient(client -> irisSelectPack(external, true)));
                        context.waitTicks(SETTLE_TICKS);
                        clearChat(context);
                        context.takeScreenshot("metallium-iris-external-pack-natural");
                        measure(context, "natural world, external pack");
                        context.computeOnClient(client -> irisSelectPack(external, false));
                        context.waitTicks(20);
                    }
                }
            }
            if (!failures.isEmpty()) {
                if (backend.contains("Paravirtual")) {
                    // GitHub's virtual Mac GPU garbles Sodium terrain; real Apple GPUs render it correctly (M3 Max verified).
                    line("WARNING (virtual GPU, not failing): " + String.join("; ", failures));
                } else {
                    throw new AssertionError(String.join("; ", failures));
                }
            }
            line("result: PASS");
        } catch (Throwable t) {
            line("result: FAIL " + t);
            throw t;
        } finally {
            writeReport();
        }

        if (!expected.isEmpty() && !backend.startsWith(expected)) {
            throw new AssertionError("Expected backend " + expected + " but got " + backend);
        }
    }

    /**
     * Terrain check on the screenshot: in the band between 60% and 85% of the height (below the horizon, above the
     * hotbar) most pixels must not look like sky. Catches "world renders as empty sky" regressions.
     */
    private void checkTerrainVisible(Path screenshot, String scene) {
        try {
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(screenshot.toFile());
            int w = image.getWidth();
            int h = image.getHeight();
            int total = 0;
            int sky = 0;
            for (int y = (int) (h * 0.60); y < (int) (h * 0.85); y += 2) {
                for (int x = (int) (w * 0.05); x < (int) (w * 0.70); x += 2) {
                    int rgb = image.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                    total++;
                    boolean blueSky = b > 150 && b >= r + 10 && b >= g && Math.abs(r - g) < 60;
                    boolean fog = Math.min(r, Math.min(g, b)) > 165 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 45;
                    if (blueSky || fog) {
                        sky++;
                    }
                }
            }
            double nonSky = 1.0 - (double) sky / Math.max(1, total);
            line(scene + ": terrain coverage=" + String.format("%.2f", nonSky));
            if (nonSky < 0.30) {
                failures.add(scene + " looks empty (terrain coverage " + String.format("%.2f", nonSky) + ")");
            }
        } catch (IOException e) {
            line(scene + ": could not analyse screenshot: " + e);
        }
    }

    /** Sodium's own section statistics (via reflection, Sodium is optional): built vs visible sections. */
    private void sodiumStats(ClientGameTestContext context, String scene) {
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium")) {
            return;
        }
        String stats = context.computeOnClient(client -> {
            try {
                Class<?> type = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
                Object renderer = type.getMethod("instanceNullable").invoke(null);
                if (renderer == null) {
                    return "no SodiumWorldRenderer";
                }
                Object chunks = type.getMethod("getChunksDebugString").invoke(renderer);
                Object debug = type.getMethod("getDebugStrings", boolean.class).invoke(renderer, true);
                return chunks + " | " + debug;
            } catch (ReflectiveOperationException e) {
                return "unavailable: " + e;
            }
        });
        line(scene + ": sodium " + stats);
    }

    private void measure(ClientGameTestContext context, String scene) {
        sodiumStats(context, scene);
        // The gametest harness limits ticks per frame and the CI GPU is virtualised: a sanity number, not a benchmark.
        context.waitTicks(100);
        int fps = context.computeOnClient(Minecraft::getFps);
        long frameNs = context.computeOnClient(Minecraft::getFrameTimeNs);
        line(scene + ": fps=" + fps + " lastFrameMs=" + String.format("%.2f", frameNs / 1e6));
    }

    private void line(String text) {
        System.out.println("[metallium-gametest] " + text);
        report.add(text);
    }

    private void writeReport() {
        try {
            Files.write(Path.of("metallium-gametest-report.txt"), report);
        } catch (IOException e) {
            System.out.println("[metallium-gametest] could not write report: " + e);
        }
    }
}
