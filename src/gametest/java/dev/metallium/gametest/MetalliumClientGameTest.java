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
    private static final boolean BENCH_VSYNC = Boolean.getBoolean("metallium.bench.vsync");

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
        // metallium-gl test mod: raw OpenGL calls at the end of level rendering (only active in the metallium-gl
        // scenario). Not in a HUD element: 26.3 extracts the HUD before the frame is rendered, so GL drawn there would be
        // overwritten by the world (on OpenGL as well).
        net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.END_MAIN.register(
                context -> dev.metallium.gametest.gl.RawGlQuads.render());
    }

    private static void startRecording() {
        synchronized (FRAME_LOCK) {
            FRAME_MS.clear();
        }
        recording = true;
    }

    /** Metal surface present counters: submitted, presented (shown), dropped, skipped (cumulative). */
    private static long[] presentCounters() {
        return new long[]{
                dev.metallium.backend.PresentStats.submitted(),
                dev.metallium.backend.PresentStats.presented(),
                dev.metallium.backend.PresentStats.dropped(),
                dev.metallium.backend.PresentStats.skipped()};
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
            client.options.enableVsync().set(BENCH_VSYNC);
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
            long[] present0 = presentCounters();
            java.util.Set<String> windowStates = new java.util.TreeSet<>(); // sampled once per second
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
                windowStates.add(context.computeOnClient(c -> dev.metallium.backend.PresentStats.windowState()));
            }
            List<Double> frameMs = stopRecording();
            long[] present1 = presentCounters();
            double seconds = (System.nanoTime() - startNs) / 1e9;
            float refreshHz = context.computeOnClient(c -> dev.metallium.backend.PresentStats.displayRefreshHz());
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
            line(String.format("bench: rendered frames counted=%d in %.1f s (per rendered frame via HUD hook)", frameMs.size(), seconds));
            line(String.format(java.util.Locale.ROOT,
                    "bench-present: vsync=%s display_refresh_hz=%.1f rendered_fps=%.1f submitted_fps=%.1f presented_fps=%.1f dropped=%d skipped=%d window=%s (Metal surface counters; 0 on OpenGL)",
                    BENCH_VSYNC, refreshHz, frameMs.size() / seconds, (present1[0] - present0[0]) / seconds, (present1[1] - present0[1]) / seconds,
                    present1[2] - present0[2], present1[3] - present0[3], String.join("|", windowStates)));
            line(String.format("bench-result: scene=%s avg_fps=%.1f low1_fps=%.1f p50_ms=%.2f p95_ms=%.2f p99_ms=%.2f max_ms=%.2f frames=%d heap_mb=%d",
                    BENCH_SCENE, avgFps, low1, pct(frameMs, 0.50), pct(frameMs, 0.95), pct(frameMs, 0.99), frameMs.get(frameMs.size() - 1), frameMs.size(), heapMb));
            if (Boolean.getBoolean("metallium.bench.power")) {
                powerBench(context);
            }
            line("result: PASS");
        }
    }

    private static final String TEST_PACK = "metallium-testpack";
    private static final String[] TEST_PACK_FILES = {"composite.vsh", "composite.fsh", "final.vsh", "final.fsh"};
    private static final String GBUFFER_PACK = "metallium-gbufferpack";
    private static final String[] GBUFFER_PACK_FILES = {"gbuffers_terrain.vsh", "gbuffers_terrain.fsh", "composite.vsh", "composite.fsh"};
    private static final String SHADOW_PACK = "metallium-shadowpack";
    private static final String[] SHADOW_PACK_FILES = {"gbuffers_terrain.vsh", "gbuffers_terrain.fsh", "shadow.vsh", "shadow.fsh", "composite.vsh", "composite.fsh"};
    private static final String COMPUTE_PACK = "metallium-computepack";
    private static final String[] COMPUTE_PACK_FILES = {"shaders.properties", "setup.csh", "composite.csh", "composite1.csh", "composite2.vsh", "composite2.fsh",
            "composite3.vsh", "composite3.fsh"};

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
            installTestPack(context, SHADOW_PACK, SHADOW_PACK_FILES);
            installTestPack(context, COMPUTE_PACK, COMPUTE_PACK_FILES);
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
        // Pack loaded while the crosshair is on a block (creative, looking down): Iris's currentSelectedBlockId uniform reads
        // the block ID map on the pipeline's first frame (real-instance finding: gbuffers setup failed with an NPE).
        tryCommand(world, "gamemode creative @a");
        tryCommand(world, "execute as @p at @s run tp @s ~ ~ ~ ~ 50");
        context.waitTicks(5);
        line("iris gbuffers test pack, block selected: pipeline=" + context.computeOnClient(client -> {
            // As on the first pack of a session: no block ID map yet (the earlier packs of this test have set it).
            try {
                Class<?> settings = Class.forName("net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings");
                settings.getMethod("setBlockStateIds", it.unimi.dsi.fastutil.objects.Object2IntMap.class)
                        .invoke(settings.getField("INSTANCE").get(null), (Object) null);
            } catch (ReflectiveOperationException e) {
                line("could not reset Iris block IDs: " + e);
            }
            return irisSelectPack(GBUFFER_PACK, true);
        }));
        context.waitTicks(60);
        clearChat(context);
        try {
            double ground = magentaFraction(javax.imageio.ImageIO.read(
                    context.takeScreenshot("metallium-iris-gbufferpack-block-selected").toFile()), 0.40, 0.85);
            line(String.format("iris gbuffers test pack, block selected: magenta ground fraction=%.2f", ground));
            if (ground < 0.80) {
                failures.add(String.format("gbuffers pack with a selected block: marker not visible (%.2f)", ground));
            }
        } catch (IOException e) {
            failures.add("could not analyse block-selected screenshot: " + e);
        }
        tryCommand(world, "execute as @p at @s run tp @s ~ ~ ~ ~ 0");
        tryCommand(world, "gamemode spectator @a");

        // Vanilla fallback after a gbuffers failure (simulated): the chunks must be rebuilt in the vanilla vertex format,
        // so the scene must look like the shaders-off screenshot (real-instance finding: exploded terrain).
        System.setProperty("metallium.iris.test.failGbuffers", "true");
        try {
            line("iris gbuffers test pack, simulated failure: pipeline=" + context.computeOnClient(client -> irisSelectPack(GBUFFER_PACK, true)));
            context.waitTicks(100);
        } finally {
            System.clearProperty("metallium.iris.test.failGbuffers");
        }
        clearChat(context);
        double fallback = matchFraction(off, context.takeScreenshot("metallium-iris-gbufferpack-failed-fallback"), false);
        line(String.format("iris gbuffers failure fallback: match with shaders-off screenshot=%.2f", fallback));
        if (fallback < 0.80) {
            failures.add(String.format("vanilla fallback after a gbuffers failure differs from vanilla (match %.2f)", fallback));
        }

        shadowPackScenario(context, world);
        computePackScenario(context);

        String external = externalPack(context);
        if (external != null) {
            long packStart = System.nanoTime();
            long[] beforePack = dev.metallium.shadercache.ShaderCacheStats.snapshot();
            line("external pack " + external + ": pipeline=" + context.computeOnClient(client -> irisSelectPack(external, true)));
            context.waitTicks(1);
            line(String.format("timing: external pack load (reload + first frame) ms=%.0f", (System.nanoTime() - packStart) / 1e6));
            line("shader work during external pack load: " + dev.metallium.shadercache.ShaderCacheStats.summary(dev.metallium.shadercache.ShaderCacheStats.delta(beforePack)));
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
            // Shadow comparison scene (stage 4): a small "tree" (log + leaf canopy) and a wall in the morning sun, which
            // stands low in the east, so the shadows fall long across the flat ground. Same scene with -Pengine=OPENGL.
            tryCommand(world, "time set 1500");
            tryCommand(world, "execute as @p at @s run fill ^-2 ^ ^8 ^-2 ^3 ^8 minecraft:oak_log");
            tryCommand(world, "execute as @p at @s run fill ^-4 ^3 ^6 ^0 ^5 ^10 minecraft:oak_leaves[persistent=true]");
            tryCommand(world, "execute as @p at @s run fill ^3 ^ ^10 ^5 ^2 ^10 minecraft:stone_bricks");
            context.waitTicks(80);
            clearChat(context);
            context.takeScreenshot("metallium-iris-external-pack-shadows");
            tryCommand(world, "execute as @p at @s run fill ^-4 ^ ^6 ^5 ^5 ^10 minecraft:air");
            // Night with coloured light sources (stage 5: packs' voxel/floodfill coloured lighting runs in compute).
            tryCommand(world, "time set 18000");
            tryCommand(world, "execute as @p at @s run setblock ^-2 ^-1 ^5 minecraft:redstone_lamp[lit=true]");
            tryCommand(world, "execute as @p at @s run setblock ^2 ^-1 ^5 minecraft:soul_lantern");
            tryCommand(world, "execute as @p at @s run setblock ^ ^-1 ^7 minecraft:glowstone");
            context.waitTicks(80);
            clearChat(context);
            context.takeScreenshot("metallium-iris-external-pack-night-lights");
            tryCommand(world, "execute as @p at @s run fill ^-2 ^-1 ^5 ^2 ^-1 ^7 minecraft:air");
            tryCommand(world, "time set 6000");
            context.waitTicks(40);
            playerModelScene(context, world);
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

    /**
     * External pack, local player model (entity_translucent pipeline -> gbuffers_entities / gbuffers_hand): third person
     * (F5) from behind, then first person with the empty hand. The player's skin must be visible: the body/hand area must
     * not be mostly near-white (white texture / overlay) nor show the sky through it. Same scene with -Pengine=OPENGL.
     */
    private void playerModelScene(ClientGameTestContext context, TestSingleplayerContext world) {
        tryCommand(world, "gamemode creative @a");
        tryCommand(world, "clear @a");
        tryCommand(world, "execute as @p at @s run tp @s ~ ~ ~ ~ 10");
        tryCommand(world, "execute as @p at @s run summon minecraft:mannequin ^1.5 ^ ^2 {profile:{texture:\"minecraft:entity/player/slim/alex\",model:\"slim\"}}");
        tryCommand(world, "execute as @p at @s run summon minecraft:mannequin ^-1.5 ^ ^2 {profile:{texture:\"minecraft:entity/player/wide/steve\"}}");
        context.runOnClient(client -> client.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK));
        context.waitTicks(40);
        clearChat(context);
        Path third = context.takeScreenshot("metallium-iris-external-pack-player-f5");
        context.runOnClient(client -> client.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
        context.waitTicks(20);
        clearChat(context);
        Path first = context.takeScreenshot("metallium-iris-external-pack-player-hand");
        tryCommand(world, "kill @e[type=minecraft:mannequin]");
        tryCommand(world, "execute as @p at @s run tp @s ~ ~ ~ ~ 0");
        tryCommand(world, "gamemode spectator @a");
        try {
            java.awt.image.BufferedImage body = javax.imageio.ImageIO.read(third.toFile());
            java.awt.image.BufferedImage hand = javax.imageio.ImageIO.read(first.toFile());
            // Third person from behind: the body is the central column of the screen; first person: the right arm
            // reaches into the lower right corner.
            double[] bodyStats = paleAndSkyFraction(body, 0.47, 0.75, 0.47, 0.53);
            double[] handStats = paleAndSkyFraction(hand, 0.72, 0.95, 0.70, 0.90);
            line(String.format("external pack player model: f5 body near-white=%.2f sky-blue=%.2f; first-person hand near-white=%.2f sky-blue=%.2f",
                    bodyStats[0], bodyStats[1], handStats[0], handStats[1]));
            if (bodyStats[0] > 0.35 || handStats[0] > 0.35 || bodyStats[1] > 0.20) {
                failures.add(String.format("player model looks white/untextured or see-through with the external pack (f5 %.2f near-white, %.2f sky; hand %.2f near-white)",
                        bodyStats[0], bodyStats[1], handStats[0]));
            }
        } catch (IOException e) {
            failures.add("could not analyse player model screenshots: " + e);
        }
    }

    /** Fractions of {near-white (all channels > 200, low saturation), sky-like blue} pixels in a screen rectangle. */
    private static double[] paleAndSkyFraction(java.awt.image.BufferedImage image, double fromY, double toY, double fromX, double toX) {
        int w = image.getWidth();
        int h = image.getHeight();
        int total = 0;
        int pale = 0;
        int sky = 0;
        for (int y = (int) (h * fromY); y < (int) (h * toY); y += 2) {
            for (int x = (int) (w * fromX); x < (int) (w * toX); x += 2) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                total++;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                if (min > 200 && max - min < 30) {
                    pale++;
                } else if (b > r + 40 && b > 150) {
                    sky++;
                }
            }
        }
        return new double[] {(double) pale / Math.max(1, total), (double) sky / Math.max(1, total)};
    }

    /**
     * Stage 4: the shadow test pack marks every pixel that its composite pass finds in the shadow map's shadow magenta.
     * At noon the sun is overhead, so a floating stone platform in front of the camera shadows the ground right below
     * it: that ground (centre of the lower screen half) must turn magenta, and without the platform it must not.
     */
    private void shadowPackScenario(ClientGameTestContext context, TestSingleplayerContext world) {
        line("iris shadow test pack: pipeline=" + context.computeOnClient(client -> irisSelectPack(SHADOW_PACK, true)));
        context.waitTicks(100);
        clearChat(context);
        Path open = context.takeScreenshot("metallium-iris-shadowpack-open");
        tryCommand(world, "execute as @p at @s run fill ^-3 ^3 ^4 ^3 ^3 ^12 minecraft:stone");
        context.waitTicks(60);
        clearChat(context);
        Path shaded = context.takeScreenshot("metallium-iris-shadowpack-platform");
        tryCommand(world, "execute as @p at @s run fill ^-3 ^3 ^4 ^3 ^3 ^12 minecraft:air");
        try {
            java.awt.image.BufferedImage before = javax.imageio.ImageIO.read(open.toFile());
            java.awt.image.BufferedImage after = javax.imageio.ImageIO.read(shaded.toFile());
            // Ground 4..12 blocks ahead and +-3 blocks to the side, seen from eye height with pitch 0 (FOV 70).
            double openGround = magentaFraction(before, 0.62, 0.76, 0.38, 0.62);
            double shadedGround = magentaFraction(after, 0.62, 0.76, 0.38, 0.62);
            double farGround = magentaFraction(after, 0.52, 0.56, 0.05, 0.95);
            line(String.format("iris shadow test pack: magenta (in shadow) fraction under the platform=%.2f, same area without platform=%.2f, far ground=%.2f",
                    shadedGround, openGround, farGround));
            if (shadedGround < 0.60 || openGround > 0.20 || farGround > 0.30) {
                failures.add(String.format("shadow pass -> composite shadow test not visible (shadowed %.2f, open %.2f, far %.2f)", shadedGround, openGround, farGround));
            }
        } catch (IOException e) {
            failures.add("could not analyse shadow screenshots: " + e);
        }
    }

    /**
     * Stage 5: the compute test pack's setup pass fills an SSBO, composite.csh writes its colour into the left half of
     * colortex2 (colorimg2) and counts every pixel with an image atomic, the compute-only composite1.csh checks counter,
     * SSBO and {@code .length()} and paints colortex3 green or red, composite2.fsh (colortex2 mipmapped) shows: top band
     * = that verdict (left third), its own check of SSBO + a 3D custom image read from a fragment program (middle) and
     * an image it stored from the fragment program, shown by composite3.fsh (right); bottom band = green if the
     * smallest mip of colortex2 averages to half magenta; middle = magenta on the left half only.
     */
    private void computePackScenario(ClientGameTestContext context) {
        line("iris compute test pack: pipeline=" + context.computeOnClient(client -> irisSelectPack(COMPUTE_PACK, true)));
        context.waitTicks(60);
        clearChat(context);
        Path shot = context.takeScreenshot("metallium-iris-computepack");
        try {
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(shot.toFile());
            double verdict = colorFraction(image, 0.02, 0.08, 0.02, 0.31, 0x00FF00);
            double fragmentReads = colorFraction(image, 0.02, 0.08, 0.36, 0.64, 0x00FF00);
            double fragmentStores = colorFraction(image, 0.02, 0.08, 0.69, 0.98, 0x00FF00);
            double mips = colorFraction(image, 0.92, 0.98, 0.05, 0.95, 0x00FF00);
            double left = magentaFraction(image, 0.30, 0.70, 0.05, 0.45);
            double right = magentaFraction(image, 0.30, 0.70, 0.55, 0.95);
            line(String.format("iris compute test pack: compute verdict green=%.2f, fragment SSBO/3D-image reads green=%.2f, fragment image stores green=%.2f, "
                    + "mipmap band green=%.2f, magenta left half=%.2f, right half=%.2f", verdict, fragmentReads, fragmentStores, mips, left, right));
            if (verdict < 0.90 || fragmentReads < 0.90 || fragmentStores < 0.90 || mips < 0.90 || left < 0.90 || right > 0.10) {
                failures.add(String.format("compute/SSBO/image/mipmap test pack check failed (verdict %.2f, fragment reads %.2f, fragment stores %.2f, mips %.2f, left %.2f, right %.2f)",
                        verdict, fragmentReads, fragmentStores, mips, left, right));
            }
        } catch (IOException e) {
            failures.add("could not analyse compute pack screenshot: " + e);
        }
    }

    /** Fraction of pixels (in a screen-relative box, y from the top) within 40 per channel of {@code rgb}. */
    private static double colorFraction(java.awt.image.BufferedImage image, double fromY, double toY, double fromX, double toX, int rgb) {
        int w = image.getWidth();
        int h = image.getHeight();
        int total = 0;
        int match = 0;
        for (int y = (int) (h * fromY); y < (int) (h * toY); y += 2) {
            for (int x = (int) (w * fromX); x < (int) (w * toX); x += 2) {
                int c = image.getRGB(x, y);
                total++;
                if (Math.abs(((c >> 16) & 0xFF) - ((rgb >> 16) & 0xFF)) < 40 && Math.abs(((c >> 8) & 0xFF) - ((rgb >> 8) & 0xFF)) < 40
                        && Math.abs((c & 0xFF) - (rgb & 0xFF)) < 40) {
                    match++;
                }
            }
        }
        return (double) match / Math.max(1, total);
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
            // Pack options (Iris reads <pack>.txt next to the pack), e.g. to turn on a pack's compute/voxel features.
            String options = System.getProperty("metallium.gametest.shaderpackOptions", "");
            Path optionsFile = target.resolveSibling(source.getFileName() + ".txt");
            if (!options.isEmpty()) {
                Files.writeString(optionsFile, String.join("\n", options.split(",")) + "\n");
                line("external pack options: " + options);
            } else {
                Files.deleteIfExists(optionsFile);
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
        return magentaFraction(image, fromY, toY, 0.05, 0.70);
    }

    private static double magentaFraction(java.awt.image.BufferedImage image, double fromY, double toY, double fromX, double toX) {
        int w = image.getWidth();
        int h = image.getHeight();
        int total = 0;
        int magenta = 0;
        for (int y = (int) (h * fromY); y < (int) (h * toY); y += 2) {
            for (int x = (int) (w * fromX); x < (int) (w * toX); x += 2) {
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

    /**
     * metallium-gl (needs -PwithGl): the test mod in {@code dev.metallium.gametest.gl} issues raw GL calls after level
     * rendering; its classes are retargeted to metallium-gl, which must draw them into the main render target on Metal.
     * On the OpenGL backend the same retargeted calls fall through to real OpenGL (reported, not failing).
     */
    private void metalliumGlScenario(ClientGameTestContext context) {
        boolean metal = backendIsMetal(context);
        // No opt-in in fabric.mod.json: the start-up scan must find the raw-GL classes and route them automatically.
        dev.metallium.compat.GlRouting.Decision routing = dev.metallium.compat.GlRouting.plan().get("metallium-gametest");
        line("metallium-gl: gametest mod routing=" + (routing == null ? "none" : routing.status() + (routing.automatic() ? " (automatic)" : "")
                + " classes=" + routing.classes() + " reason=" + routing.reason()) + ", hooked=" + dev.metallium.compat.GlRouting.hooked());
        if (metal && (routing == null || !routing.routed() || !routing.automatic() || !dev.metallium.compat.GlRouting.hooked())) {
            failures.add("metallium-gl: gametest mod was not routed automatically: " + routing);
        }
        dev.metallium.gametest.gl.RawGlQuads.enabled = true;
        context.waitTicks(20);
        Path shot = context.takeScreenshot("metallium-gl-hud");
        dev.metallium.gametest.gl.RawGlQuads.enabled = false;
        line("metallium-gl: test mod status=" + dev.metallium.gametest.gl.RawGlQuads.status + ", frames drawn=" + dev.metallium.gametest.gl.RawGlQuads.frames);
        for (String status : dev.metallium.api.MetalliumRuntime.statusLines()) {
            if (status.startsWith("metallium-gl")) line(status);
        }
        List<String> problems = new ArrayList<>();
        if (!"ok".equals(dev.metallium.gametest.gl.RawGlQuads.status)) {
            problems.add("test mod: " + dev.metallium.gametest.gl.RawGlQuads.status);
        }
        try {
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(shot.toFile());
            int[][] expected = {
                    // quad, fx, fy (GL-up fraction inside the quad), r, g, b
                    {0, 50, 50, 255, 0, 255},
                    {1, 25, 25, 255, 0, 0}, {1, 75, 25, 0, 255, 0}, {1, 25, 75, 0, 0, 255}, {1, 75, 75, 255, 255, 0},
                    {2, 50, 50, 0, 255, 255}};
            String[] names = {"uniform colour", "texture bottom-left", "texture bottom-right", "texture top-left", "texture top-right", "FBO texture"};
            int w = image.getWidth();
            int h = image.getHeight();
            StringBuilder seen = new StringBuilder();
            for (int i = 0; i < expected.length; i++) {
                int[] e = expected[i];
                double x0 = dev.metallium.gametest.gl.RawGlQuads.X0 + e[0] * dev.metallium.gametest.gl.RawGlQuads.STEP;
                int x = (int) (w * (x0 + dev.metallium.gametest.gl.RawGlQuads.W * e[1] / 100.0));
                double glY = dev.metallium.gametest.gl.RawGlQuads.Y0 + dev.metallium.gametest.gl.RawGlQuads.H * e[2] / 100.0;
                int y = Math.clamp((int) (h * (1.0 - glY)), 0, h - 1);
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                seen.append(String.format(" %s=(%d,%d,%d)", names[i], r, g, b));
                if (Math.abs(r - e[3]) > 8 || Math.abs(g - e[4]) > 8 || Math.abs(b - e[5]) > 8) {
                    problems.add(names[i] + " expected (" + e[3] + "," + e[4] + "," + e[5] + ") got (" + r + "," + g + "," + b + ")");
                }
            }
            line("metallium-gl pixels:" + seen);
        } catch (IOException e) {
            problems.add("could not analyse screenshot: " + e);
        }
        if (problems.isEmpty()) {
            line("metallium-gl: raw GL quads rendered correctly on " + (metal ? "Metal" : "OpenGL"));
        } else if (metal) {
            failures.add("metallium-gl: " + String.join("; ", problems));
        } else {
            line("metallium-gl on OpenGL (informational): " + String.join("; ", problems));
        }
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
    /** Minimum play time in the first world before the shader-work counters are reported (-PplaySeconds=60). */
    private static final int PLAY_SECONDS = Integer.getInteger("metallium.gametest.playSeconds", 0);
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
        line("timing: startup-to-title ms=" + java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime());
        line("shader work until title: " + dev.metallium.shadercache.ShaderCacheStats.summary());
        line("sodium loaded: " + net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium"));
        line("iris loaded: " + net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris")
                + ", adapter: " + net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("metallium-iris"));
        for (String mod : new String[] {"distanthorizons", "voxy", "reglass", "cullleaves", "moreculling"}) {
            line(mod + " loaded: " + net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(mod));
        }
        if (dev.metallium.compat.CrashGuard.recovery() != null) {
            crashRecoveryRun(context, backend);
            return;
        }
        context.takeScreenshot("metallium-title");
        graphicsApiLabelsTest(context);

        if ("instance".equals(System.getProperty("metallium.gametest.mode"))) {
            try {
                new InstanceScenario(this::line, this::writeReport).run(context);
            } finally {
                writeReport();
            }
            return;
        }
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
            long joinStart = System.nanoTime();
            long[] beforeJoin = dev.metallium.shadercache.ShaderCacheStats.snapshot();
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                world.getConnection().waitForChunksRender();
                long joined = System.nanoTime();
                line(String.format("timing: first world join (create + chunks rendered) ms=%.0f", (joined - joinStart) / 1e6));
                line("shader work during join: " + dev.metallium.shadercache.ShaderCacheStats.summary(dev.metallium.shadercache.ShaderCacheStats.delta(beforeJoin)));
                long[] afterJoin = dev.metallium.shadercache.ShaderCacheStats.snapshot();
                // Sodium builds sections asynchronously after vanilla reports chunks as rendered; give it time.
                context.waitTicks(SETTLE_TICKS);
                checkTerrainVisible(context.takeScreenshot("metallium-flat-world"), "flat world");
                measure(context, "flat world");
                compatCenterScenario(context);
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
                long playNs = PLAY_SECONDS * 1_000_000_000L - (System.nanoTime() - joined);
                if (playNs > 0) {
                    context.waitTicks((int) (playNs / 50_000_000L));
                }
                line(String.format("shader work during first %.0f s of play: %s", (System.nanoTime() - joined) / 1e9,
                        dev.metallium.shadercache.ShaderCacheStats.summary(dev.metallium.shadercache.ShaderCacheStats.delta(afterJoin))));
                if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("metallium-gl")) {
                    metalliumGlScenario(context);
                }
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
                        packCoverage(context.takeScreenshot("metallium-iris-external-pack-natural"));
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
        checkTerrainVisible(screenshot, scene, true);
    }

    /** Pack sweep: logs "pack terrain coverage=" for an external shader pack screenshot; never fails the test. */
    private void packCoverage(Path screenshot) {
        checkTerrainVisible(screenshot, "pack", false);
    }

    private void checkTerrainVisible(Path screenshot, String scene, boolean failIfEmpty) {
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
            if (failIfEmpty && nonSky < 0.30) {
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

    /** Graphics API labels: vanilla caption() and (with Sodium) Sodium's option element names / tooltip + screenshot. */
    private void graphicsApiLabelsTest(ClientGameTestContext context) {
        boolean relabel = dev.metallium.GraphicsApiLabels.relabelDefault();
        String metal = "Metal (via Metallium)";
        for (net.minecraft.client.PreferredGraphicsApi api : net.minecraft.client.PreferredGraphicsApi.values()) {
            String vanilla = context.computeOnClient(client -> api.caption().getString());
            line("graphicsApi vanilla caption " + api + ": " + vanilla);
            boolean expectMetal = relabel && api == net.minecraft.client.PreferredGraphicsApi.DEFAULT;
            if (expectMetal != vanilla.equals(metal)) {
                failures.add("vanilla Graphics API caption for " + api + " wrong: " + vanilla);
            }
        }
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium")) {
            return;
        }
        String result = context.computeOnClient(client -> {
            try {
                Class<?> manager = Class.forName("net.caffeinemc.mods.sodium.client.config.ConfigManager");
                Object config = manager.getField("CONFIG").get(null);
                Object id = net.minecraft.resources.Identifier.parse("sodium:general.graphics_api");
                Object option = config.getClass().getMethod("getOption", id.getClass()).invoke(config, id);
                java.lang.reflect.Method name = null;
                for (java.lang.reflect.Method method : option.getClass().getMethods()) {
                    if (method.getName().equals("getElementName")) {
                        name = method;
                    }
                }
                StringBuilder out = new StringBuilder();
                for (net.minecraft.client.PreferredGraphicsApi api : net.minecraft.client.PreferredGraphicsApi.values()) {
                    out.append(api).append('=').append(((net.minecraft.network.chat.Component) name.invoke(option, api)).getString()).append(';');
                }
                Object tip = option.getClass().getMethod("getTooltip").invoke(option);
                out.append("tooltip=").append(((net.minecraft.network.chat.Component) tip).getString());
                return out.toString();
            } catch (ReflectiveOperationException | RuntimeException e) {
                return "unavailable: " + e;
            }
        });
        line("graphicsApi sodium option: " + result);
        String defaultName = result.contains("DEFAULT=") ? result.split("DEFAULT=")[1].split(";")[0] : "";
        if (result.startsWith("unavailable") || relabel != defaultName.equals(metal)
                || !result.contains("OPENGL=Prefer OpenGL;") || !result.contains("VULKAN=Prefer Vulkan;")) {
            failures.add("Sodium Graphics API option names wrong: " + result);
        }
        context.runOnClient(client -> {
            try {
                Class<?> screen = Class.forName("net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen");
                client.setScreenAndShow((net.minecraft.client.gui.screens.Screen) screen
                        .getMethod("createScreen", net.minecraft.client.gui.screens.Screen.class).invoke(null, (Object) null));
            } catch (ReflectiveOperationException e) {
                line("sodium video settings screen unavailable: " + e);
            }
        });
        context.waitTicks(20);
        context.takeScreenshot("metallium-sodium-video-settings");
        context.runOnClient(client -> client.setScreenAndShow(null));
        context.waitTicks(5);
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

    /**
     * Compatibility center: the start-up scan must classify the gametest mod (RawGlQuads calls GL11 directly) as raw GL;
     * screenshots of the status screen and of the crash-recovery screen (synthetic recovery); power mode FPS cap.
     */
    private void compatCenterScenario(ClientGameTestContext context) {
        dev.metallium.compat.ModReport self = dev.metallium.compat.ModScan.report("metallium-gametest");
        line("compat: scan " + dev.metallium.compat.ModScan.summary() + "; gametest mod rawGl=" + (self != null && self.rawGl())
                + " classes=" + (self == null ? "-" : self.glClasses()) + " functions=" + (self == null ? "-" : self.functions().size()));
        if (self == null || !self.glClasses().contains("dev.metallium.gametest.gl.RawGlQuads")) {
            failures.add("compat: scan did not find RawGlQuads as raw GL: " + self);
        }
        for (String row : dev.metallium.compat.CompatStatus.text().split("\n")) {
            line("compat-status: " + row);
        }
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium")) {
            sodiumMetalliumTab(context);
        } else {
            // Vanilla Video Settings: Metallium section (power mode + status button) at the end of the list.
            context.runOnClient(client -> client.gui.setScreen(
                    new net.minecraft.client.gui.screens.options.VideoSettingsScreen(null, client, client.options)));
            context.waitTicks(5);
            context.runOnClient(client -> client.gui.screen().mouseScrolled(client.gui.screen().width / 2.0,
                    client.gui.screen().height / 2.0, 0, -1000));
            context.waitTicks(5);
            context.takeScreenshot("metallium-vanilla-video-settings");
        }
        context.runOnClient(client -> client.gui.setScreen(new dev.metallium.screen.MetalliumStatusScreen(null)));
        context.waitTicks(10);
        context.takeScreenshot("metallium-status-screen");
        context.runOnClient(client -> client.gui.setScreen(new dev.metallium.screen.CrashRecoveryScreen(null,
                new dev.metallium.compat.CrashGuard.Recovery(java.util.List.of(new dev.metallium.compat.CrashGuard.RoutedMod(
                        "metallium-gametest", "Metallium client game tests", java.util.List.of("dev.metallium.gametest.gl"), null)),
                        true, false, "crash-reports/crash-example.txt (synthetic, game test)"))));
        context.waitTicks(10);
        context.takeScreenshot("metallium-crash-recovery-screen");
        context.runOnClient(client -> client.gui.setScreen(null));
        context.waitTicks(5);

        // Power mode: Low Power must cap the frame rate live; Performance must lift the cap again.
        dev.metallium.power.PowerMode before = dev.metallium.power.PowerManager.mode();
        line("compat: " + dev.metallium.power.PowerManager.statusLine());
        try {
            dev.metallium.power.PowerManager.setMode(dev.metallium.power.PowerMode.LOW_POWER);
            context.waitTicks(60);
            int low = context.computeOnClient(Minecraft::getFps);
            dev.metallium.power.PowerManager.setMode(dev.metallium.power.PowerMode.PERFORMANCE);
            context.waitTicks(60);
            int perf = context.computeOnClient(Minecraft::getFps);
            line("compat: power fps Low Power=" + low + " (cap " + dev.metallium.power.PowerManager.fpsCap(
                    dev.metallium.power.PowerMode.LOW_POWER, dev.metallium.MetalliumConfig.INSTANCE.batteryFpsLimit) + "), Performance=" + perf);
            if (low > dev.metallium.power.PowerManager.LOW_POWER_FPS + 5) {
                failures.add("power: Low Power did not cap the frame rate (fps " + low + ")");
            }
        } finally {
            dev.metallium.power.PowerManager.setMode(before);
        }
    }

    /** -PcrashRecovery: a seeded crash in a routed mod must give OpenGL (safe mode) and the recovery screen. */
    private void crashRecoveryRun(ClientGameTestContext context, String backend) {
        try {
            dev.metallium.compat.CrashGuard.Recovery recovery = dev.metallium.compat.CrashGuard.recovery();
            line("crash recovery: suspects=" + recovery.suspects().stream().map(dev.metallium.compat.CrashGuard.RoutedMod::id).toList()
                    + " metallium-gl=" + recovery.metalliumGl() + " startup=" + recovery.startup() + " evidence=" + recovery.evidence());
            String screen = context.computeOnClient(client -> String.valueOf(client.gui.screen()));
            line("crash recovery: screen=" + screen);
            context.takeScreenshot("metallium-crash-recovery-real");
            if (!backend.startsWith("OpenGL")) failures.add("crash recovery: expected OpenGL safe mode, got " + backend);
            if (!screen.contains("CrashRecoveryScreen")) failures.add("crash recovery: recovery screen not shown");
            if (!recovery.startup() && recovery.suspects().stream().noneMatch(m -> m.id().equals("metallium-gametest"))) failures.add("crash recovery: suspect missing");
            // "Use OpenGL for this session" -> title screen
            context.runOnClient(client -> client.gui.screen().onClose());
            context.waitTicks(10);
            String after = context.computeOnClient(client -> String.valueOf(client.gui.screen()));
            line("crash recovery: after 'Use OpenGL for this session': " + after);
            if (!after.contains("TitleScreen")) failures.add("crash recovery: did not continue to the title screen");
            line(failures.isEmpty() ? "result: PASS" : "result: FAIL " + failures);
        } finally {
            writeReport();
        }
        if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
    }

    /** Screenshots of the Metallium tab's pages in Sodium's video settings (via reflection; Sodium is optional). */
    private void sodiumMetalliumTab(ClientGameTestContext context) {
        List<Object> pages = context.computeOnClient(client -> {
            try {
                Class<?> manager = Class.forName("net.caffeinemc.mods.sodium.client.config.ConfigManager");
                Object config = manager.getField("CONFIG").get(null);
                for (Object mod : (List<?>) config.getClass().getMethod("getModOptions").invoke(config)) {
                    if ("metallium".equals(mod.getClass().getMethod("configId").invoke(mod))) {
                        return new ArrayList<Object>((List<?>) mod.getClass().getMethod("pages").invoke(mod));
                    }
                }
                return List.<Object>of();
            } catch (ReflectiveOperationException e) {
                line("sodium tab: unavailable: " + e);
                return List.<Object>of();
            }
        });
        line("sodium tab: metallium pages=" + pages.size());
        if (pages.size() < 2) failures.add("sodium tab: expected General + Power pages, got " + pages.size());
        for (int i = 0; i < pages.size(); i++) {
            Object page = pages.get(i);
            boolean opened = context.computeOnClient(client -> {
                try {
                    Class<?> screen = Class.forName("net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen");
                    Class<?> optionPage = Class.forName("net.caffeinemc.mods.sodium.client.config.structure.OptionPage");
                    if (!optionPage.isInstance(page)) return false;
                    client.gui.setScreen((net.minecraft.client.gui.screens.Screen) screen.getMethod("createScreen",
                            net.minecraft.client.gui.screens.Screen.class, optionPage).invoke(null, null, page));
                    return true;
                } catch (ReflectiveOperationException e) {
                    line("sodium tab: could not open page: " + e);
                    return false;
                }
            });
            if (opened) {
                context.waitTicks(10);
                context.takeScreenshot("metallium-sodium-tab-" + i);
            }
        }
        context.runOnClient(client -> client.gui.setScreen(null));
    }

    /** Bench add-on (-Dmetallium.bench.power=true): 10 s each in Performance and Low Power, same scene. */
    private void powerBench(ClientGameTestContext context) {
        dev.metallium.power.PowerMode before = dev.metallium.power.PowerManager.mode();
        int seconds = Integer.getInteger("metallium.bench.powerSeconds", 10);
        try {
            for (dev.metallium.power.PowerMode mode : new dev.metallium.power.PowerMode[] {
                    dev.metallium.power.PowerMode.PERFORMANCE, dev.metallium.power.PowerMode.LOW_POWER}) {
                dev.metallium.power.PowerManager.setMode(mode);
                context.waitTicks(40);
                long start = System.nanoTime();
                startRecording();
                while (System.nanoTime() - start < seconds * 1_000_000_000L) {
                    context.waitTicks(20);
                }
                List<Double> frames = stopRecording();
                double elapsed = (System.nanoTime() - start) / 1e9;
                line(String.format("bench-power: mode=%s frames=%d seconds=%.1f avg_fps=%.1f p50_ms=%.2f",
                        mode, frames.size(), elapsed, frames.size() / elapsed, frames.isEmpty() ? 0 : pct(frames, 0.5)));
            }
        } finally {
            dev.metallium.power.PowerManager.setMode(before);
        }
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
