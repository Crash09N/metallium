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
    private static final String BENCH_SEED = System.getProperty("metallium.bench.seed", "metallium");
    private static final int BENCH_SECONDS = Integer.getInteger("metallium.bench.seconds", 30);

    private static final String BENCH_SCENE = System.getProperty("metallium.bench.scene", "light");

    // Per-frame timing: a HUD element is extracted exactly once per rendered frame, so the gap between two calls is the
    // frame time (frame start to next frame start; includes any wait for the GPU/present). This counts RENDERED
    // frames, not frames the display actually showed.
    private static final Object FRAME_LOCK = new Object();
    private static final List<Double> FRAME_MS = new ArrayList<>();
    private static volatile boolean recording;
    private static long lastFrameNs;

    static {
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("metallium-gametest", "frame_timer"),
                (graphics, delta) -> {
                    long now = System.nanoTime();
                    if (recording && lastFrameNs != 0) {
                        synchronized (FRAME_LOCK) {
                            FRAME_MS.add((now - lastFrameNs) / 1e6);
                        }
                    }
                    lastFrameNs = now;
                });
    }

    private static void startRecording() {
        synchronized (FRAME_LOCK) {
            FRAME_MS.clear();
        }
        recording = true;
    }

    private static List<Double> stopRecording() {
        recording = false;
        synchronized (FRAME_LOCK) {
            List<Double> copy = new ArrayList<>(FRAME_MS);
            copy.sort(null);
            return copy;
        }
    }

    /**
     * Reproducible benchmark (scene = -Dmetallium.bench.scene: light | rd32 | shaders | flight): fixed seed, spectator
     * camera, VSync off, no FPS cap, chunk fade off. Waits until section building settles, then records every rendered
     * frame for BENCH_SECONDS.
     */
    private void benchmark(ClientGameTestContext context, String backend) {
        line("mode: benchmark, scene=" + BENCH_SCENE + ", seed=" + BENCH_SEED + ", seconds=" + BENCH_SECONDS);
        if (!BENCH_SCENE.matches("light|rd32|shaders|flight")) {
            throw new IllegalArgumentException("unknown bench scene " + BENCH_SCENE);
        }
        boolean shaders = "shaders".equals(BENCH_SCENE);
        boolean flight = "flight".equals(BENCH_SCENE);
        int renderDistance = "rd32".equals(BENCH_SCENE) ? 32 : 12;
        int settleTicks = Integer.getInteger("metallium.bench.settleTicks", flight ? 100 : 300);
        int settleSeconds = Integer.getInteger("metallium.bench.settleSeconds", "rd32".equals(BENCH_SCENE) ? 150 : flight ? 20 : 60);
        String pack = System.getProperty("metallium.gametest.shaderpack", "");
        if (shaders && (pack.isEmpty() || !Files.isRegularFile(Path.of(pack))
                || !net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris"))) {
            line("bench: SKIPPED shaders scene (needs -PwithIris and an existing -Pshaderpack)");
            line("result: SKIP");
            return;
        }
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
            tryCommand(world, "gamerule spawn_monsters false");
            tryCommand(world, "gamemode spectator @a");
            tryCommand(world, "time set 6000");
            tryCommand(world, "gamerule advance_time false");
            tryCommand(world, "weather clear");
            // Raise the render distance only after the initial load (waitForChunksRender at a high RD times out).
            int rd = renderDistance;
            context.runOnClient(client -> client.options.renderDistance().set(rd));
            // The integrated server caps the view distance independently of the client option.
            world.getServer().runOnServer(server -> {
                server.getPlayerList().setViewDistance(rd);
                server.getPlayerList().setSimulationDistance(Math.min(rd, 12));
            });
            // Camera: above the terrain at the origin so the view shows land to the horizon, not mostly sky.
            String cameraSetting = System.getProperty("metallium.bench.camera", "");
            int surface = context.computeOnClient(client -> client.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0));
            String camera = !cameraSetting.isEmpty() ? cameraSetting : "0 " + (surface + 25) + " 0 " + (flight ? "-90 10" : "110 15");
            line("bench: surface y at origin=" + surface + ", camera=" + camera);
            tryCommand(world, "tp @a " + camera);

            if (shaders) {
                try {
                    Path source = Path.of(pack);
                    Path target = context.computeOnClient(client -> irisShaderpacksDirectory()).resolve(source.getFileName().toString());
                    Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    line("bench: shader pack " + source.getFileName() + ": pipeline="
                            + context.computeOnClient(client -> irisSelectPack(source.getFileName().toString(), true)));
                } catch (IOException e) {
                    throw new IllegalStateException("could not install shader pack", e);
                }
            }
            // Ticks run faster than 20/s in the game test, so settle on wall-clock time as well as ticks.
            long settleUntil = System.nanoTime() + settleSeconds * 1_000_000_000L;
            context.waitTicks(settleTicks);
            while (System.nanoTime() < settleUntil) {
                context.waitTicks(20);
            }
            if (!flight) {
                checkTerrainVisible(context.takeScreenshot("metallium-bench-view-" + BENCH_SCENE), "bench view");
            }
            sodiumStats(context, "bench");

            List<Integer> fpsSamples = new ArrayList<>();
            String[] camParts = camera.split(" ");
            double startY = Double.parseDouble(camParts[1]);
            double x = 0;
            double blocksPerTick = Double.parseDouble(System.getProperty("metallium.bench.flightSpeed", "60")) / 20.0;
            long startNs = System.nanoTime();
            startRecording();
            for (int second = 0; second < BENCH_SECONDS; second++) {
                for (int tick = 0; tick < 20; tick++) {
                    if (flight) {
                        x += blocksPerTick; // straight line east through chunks that are not loaded yet
                        tryCommand(world, String.format(java.util.Locale.ROOT, "tp @a %.2f %.2f 0 -90 10", x, startY));
                    }
                    context.waitTick();
                }
                fpsSamples.add(context.computeOnClient(Minecraft::getFps));
            }
            List<Double> frameMs = stopRecording();
            double seconds = (System.nanoTime() - startNs) / 1e9;
            if (frameMs.isEmpty()) {
                throw new AssertionError("no frames recorded");
            }
            fpsSamples.sort(null);
            double sum = frameMs.stream().mapToDouble(Double::doubleValue).sum();
            double avgFps = frameMs.size() / (sum / 1000.0);
            int worst = Math.max(1, frameMs.size() / 100);
            double worstMean = frameMs.subList(frameMs.size() - worst, frameMs.size()).stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double low1 = 1000.0 / worstMean;
            long heapMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20;
            line(String.format("bench: Minecraft fps counter: avg=%.1f min=%d median=%d",
                    fpsSamples.stream().mapToInt(Integer::intValue).average().orElse(0), fpsSamples.get(0), fpsSamples.get(fpsSamples.size() / 2)));
            line(String.format("bench: rendered frames counted=%d in %.1f s (per rendered frame via HUD hook; presented/displayed frames are not counted)", frameMs.size(), seconds));
            line(String.format("bench-result: scene=%s avg_fps=%.1f low1_fps=%.1f p50_ms=%.2f p95_ms=%.2f p99_ms=%.2f max_ms=%.2f frames=%d heap_mb=%d",
                    BENCH_SCENE, avgFps, low1, pct(frameMs, 0.50), pct(frameMs, 0.95), pct(frameMs, 0.99), frameMs.get(frameMs.size() - 1), frameMs.size(), heapMb));
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
        // Test instances run silently (owner request): master volume 0 and music off.
        context.runOnClient(client -> {
            client.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);
            client.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC).set(0.0);
        });
        String backend = context.computeOnClient(client -> {
            DeviceInfo info = RenderSystem.getDevice().getDeviceInfo();
            return info.backendName() + " | " + info.name() + " | " + info.driverInfo();
        });
        line("backend: " + backend);
        line("sodium loaded: " + net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium"));
        line("iris loaded: " + net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris")
                + ", adapter: " + net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("metallium-iris"));
        for (String mod : new String[] {"distanthorizons", "voxy", "reglass", "cullleaves", "moreculling"}) {
            line(mod + " loaded: " + net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(mod));
        }
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
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("distanthorizons")) {
            distantHorizonsTest(context);
            return;
        }
        try {
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                world.getConnection().waitForChunksRender();
                // Sodium builds sections asynchronously after vanilla reports chunks as rendered; give it time.
                context.waitTicks(SETTLE_TICKS);
                checkTerrainVisible(context.takeScreenshot("metallium-flat-world"), "flat world");
                measure(context, "flat world");
                if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("reglass")) {
                    reglassScreens(context);
                }
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
                f3Screenshot(context);
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

    /**
     * Distant Horizons (needs -PwithDH): raises DH's LOD render distance through its public API (reflection, DH is
     * optional), waits for LODs to generate and screenshots from a high spectator position. Whether far terrain is
     * visible is judged by looking at the screenshot; DH's renderer choice is in latest.log.
     */
    private void distantHorizonsScenario(ClientGameTestContext context, TestSingleplayerContext world) {
        tryCommand(world, "gamemode spectator @a");
        tryCommand(world, "tp @a 0 120 0 30 10");
        tryCommand(world, "time set 6000");
        tryCommand(world, "gamerule advance_time false");
        int vanillaRd = context.computeOnClient(client -> client.options.getEffectiveRenderDistance());
        line("dh: vanilla effective render distance=" + vanillaRd);
        line("dh: set chunkRenderDistance(256) -> " + context.computeOnClient(client -> dhSetRenderDistance(256)));
        for (int i = 1; i <= 4; i++) {
            context.waitTicks(400);
            line("dh: after " + (i * 20) + " s: " + context.computeOnClient(client -> dhState()));
        }
        clearChat(context);
        checkTerrainVisible(context.takeScreenshot("metallium-dh-far-lods"), "dh far LODs");
        tryCommand(world, "tp @a 0 200 0 30 20");
        context.waitTicks(60);
        context.takeScreenshot("metallium-dh-far-lods-high");
        line("dh: final state: " + context.computeOnClient(client -> dhState()));
    }

    /**
     * DH variant of the test: one natural world only. Closing a world while DH is loaded deadlocks the Fabric
     * client-game-test threading (client waits in IntegratedServer.halt, server thread waits at the phase barrier), so
     * the report is written first and the JVM is halted afterwards instead of closing the world.
     */
    private void distantHorizonsTest(ClientGameTestContext context) {
        try {
            TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(false)
                    .adjustSettings(state -> state.setSeed(System.getProperty("metallium.gametest.seed", "metallium-forest"))).create();
            world.getConnection().waitForChunksRender();
            context.waitTicks(SETTLE_TICKS);
            checkTerrainVisible(context.takeScreenshot("metallium-natural-world"), "natural world");
            measure(context, "natural world");
            distantHorizonsScenario(context, world);
            line(failures.isEmpty() ? "result: PASS" : "result: FAIL " + failures);
        } catch (Throwable t) {
            line("result: FAIL " + t);
            writeReport();
            Runtime.getRuntime().halt(1);
        }
        writeReport();
        Runtime.getRuntime().halt(failures.isEmpty() ? 0 : 1);
    }

    /** F3 overlay screenshot; with Sodium also the draw-path label Sodium prints ("Terrain Rendering: ..."). */
    private void f3Screenshot(ClientGameTestContext context) {
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium")) {
            String label = context.computeOnClient(client -> {
                try {
                    Class<?> type = Class.forName("net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend");
                    Object backend = type.getField("BACKEND").get(null);
                    return (String) type.getMethod("getName").invoke(backend);
                } catch (ReflectiveOperationException e) {
                    return "unavailable: " + e;
                }
            });
            line("sodium draw backend label: " + label);
            if (backendIsMetal(context) && !label.startsWith("metal_multidraw")) {
                failures.add("Sodium draw backend label not Metal: " + label);
            }
        }
        context.runOnClient(client -> client.debugEntries.setOverlayVisible(true));
        context.waitTicks(20);
        context.takeScreenshot("metallium-f3");
        context.runOnClient(client -> client.debugEntries.setOverlayVisible(false));
    }

    private static boolean backendIsMetal(ClientGameTestContext context) {
        return context.computeOnClient(client -> RenderSystem.getDevice().getDeviceInfo().backendName().equals("Metal"));
    }

    private static String dhSetRenderDistance(int chunks) {
        try {
            Object configs = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed").getField("configs").get(null);
            if (configs == null) {
                return "DhApi.Delayed.configs is null (DH not initialised)";
            }
            Object graphics = configs.getClass().getMethod("graphics").invoke(configs);
            Object value = graphics.getClass().getMethod("chunkRenderDistance").invoke(graphics);
            java.lang.reflect.Method set = null;
            for (var m : value.getClass().getMethods()) {
                if (m.getName().equals("setValue") && m.getParameterCount() == 1) {
                    set = m;
                }
            }
            Object ok = set.invoke(value, chunks);
            return "setValue=" + ok + ", now " + value.getClass().getMethod("getValue").invoke(value);
        } catch (ReflectiveOperationException e) {
            return "error: " + (e.getCause() != null ? e.getCause() : e);
        }
    }

    private static String dhState() {
        try {
            Object proxy = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed").getField("renderProxy").get(null);
            return proxy == null ? "renderProxy null" : proxy.getClass().getName();
        } catch (ReflectiveOperationException e) {
            return "error: " + e;
        }
    }

    /** ReGlass restyles the hotbar, inventory and pause/options screens: open them and screenshot. */
    private void reglassScreens(ClientGameTestContext context) {
        context.takeScreenshot("metallium-reglass-hud");
        context.runOnClient(client -> client.setScreenAndShow(new net.minecraft.client.gui.screens.inventory.InventoryScreen(client.player)));
        context.waitTicks(30);
        context.takeScreenshot("metallium-reglass-inventory");
        context.runOnClient(client -> client.setScreenAndShow(null));
        context.runOnClient(client -> client.setScreenAndShow(new net.minecraft.client.gui.screens.PauseScreen(true)));
        context.waitTicks(30);
        context.takeScreenshot("metallium-reglass-pause");
        context.runOnClient(client -> client.setScreenAndShow(new net.minecraft.client.gui.screens.options.OptionsScreen(
                new net.minecraft.client.gui.screens.PauseScreen(true), client.options)));
        context.waitTicks(30);
        context.takeScreenshot("metallium-reglass-options");
        context.runOnClient(client -> client.setScreenAndShow(null));
        context.waitTicks(10);
        line("reglass screens: opened HUD, inventory, pause, options");
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
