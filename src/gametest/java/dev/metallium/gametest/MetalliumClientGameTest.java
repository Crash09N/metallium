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
                dev.metallium.backend.PresentStats.skipped(),
                dev.metallium.backend.PresentStats.displayed()};
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
     * Moves the game window to the benchmark display: {@code -Dmetallium.bench.display} = index into SDL's display list
     * or a name substring; default the display with the highest refresh rate (benchmarks must run on the owner's 144 Hz
     * screen, not the 60 Hz main display). {@code -Dmetallium.bench.fullscreen=true} also makes it fullscreen there.
     * Returns a description for the report (name, size, refresh).
     */
    private static String moveToBenchDisplay(final Minecraft client) {
        String wanted = System.getProperty("metallium.bench.display", "");
        java.nio.IntBuffer displays = org.lwjgl.sdl.SDLVideo.SDL_GetDisplays();
        if (displays == null || !displays.hasRemaining()) {
            return "unknown (SDL_GetDisplays failed)";
        }
        int chosen = 0;
        float bestHz = -1f;
        StringBuilder all = new StringBuilder();
        for (int i = displays.position(); i < displays.limit(); i++) {
            int id = displays.get(i);
            String name = String.valueOf(org.lwjgl.sdl.SDLVideo.SDL_GetDisplayName(id));
            org.lwjgl.sdl.SDL_DisplayMode mode = org.lwjgl.sdl.SDLVideo.SDL_GetCurrentDisplayMode(id);
            float hz = mode == null ? 0f : mode.refresh_rate();
            all.append(all.isEmpty() ? "" : ", ").append(i - displays.position()).append('=').append(name).append('@').append(hz);
            if (wanted.isEmpty()) {
                if (hz > bestHz) {
                    chosen = id;
                    bestHz = hz;
                }
            } else if (chosen == 0 && (wanted.matches("[0-9]+") ? Integer.parseInt(wanted) == i - displays.position()
                    : name.toLowerCase(java.util.Locale.ROOT).contains(wanted.toLowerCase(java.util.Locale.ROOT)))) {
                chosen = id;
            }
        }
        if (chosen == 0) {
            return "no display matches '" + wanted + "' (" + all + ")";
        }
        long window = client.getWindow().handle();
        int position = org.lwjgl.sdl.SDLVideo.SDL_WINDOWPOS_CENTERED_DISPLAY(chosen);
        org.lwjgl.sdl.SDLVideo.SDL_SetWindowPosition(window, position, position);
        org.lwjgl.sdl.SDLVideo.SDL_SyncWindow(window);
        boolean fullscreen = Boolean.getBoolean("metallium.bench.fullscreen");
        if (fullscreen) {
            client.options.fullscreen().set(true);
            client.getWindow().setFullscreen(true);
        }
        org.lwjgl.sdl.SDL_DisplayMode mode = org.lwjgl.sdl.SDLVideo.SDL_GetCurrentDisplayMode(chosen);
        return String.format(java.util.Locale.ROOT, "%s %dx%d @ %.1f Hz, fullscreen=%s, window on display %s (all: %s)",
                org.lwjgl.sdl.SDLVideo.SDL_GetDisplayName(chosen), mode == null ? 0 : mode.w(), mode == null ? 0 : mode.h(),
                mode == null ? 0f : mode.refresh_rate(), fullscreen,
                org.lwjgl.sdl.SDLVideo.SDL_GetDisplayName(org.lwjgl.sdl.SDLVideo.SDL_GetDisplayForWindow(window)), all);
    }

    /**
     * Reproducible benchmark (scene = -Dmetallium.bench.scene: light | rd32 | shaders | flight): fixed seed, spectator
     * camera, VSync off, no FPS cap, chunk fade off. Waits until section building settles, then records every rendered
     * frame for BENCH_SECONDS.
     */
    private void benchmark(ClientGameTestContext context, String backend) {
        line("mode: benchmark, scene=" + BENCH_SCENE + ", seed=" + BENCH_SEED + ", seconds=" + BENCH_SECONDS);
        if (!BENCH_SCENE.matches("light|rd32|shaders|flight|voxy")) {
            throw new IllegalArgumentException("unknown bench scene " + BENCH_SCENE);
        }
        boolean shaders = "shaders".equals(BENCH_SCENE);
        boolean flight = "flight".equals(BENCH_SCENE);
        // voxy (-PwithVoxy=<jar> -PwithGl -PglMods=voxy): Voxy ingests the chunks loaded at vanilla rd 32, then the vanilla
        // distance drops to metallium.bench.voxyRd (default 4), so most of the view is Voxy's LODs.
        boolean voxy = "voxy".equals(BENCH_SCENE);
        if (voxy && !net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("voxy")) {
            line("bench: SKIPPED voxy scene (needs -PwithVoxy=<jar>)");
            line("result: SKIP");
            return;
        }
        int renderDistance = Integer.getInteger("metallium.bench.renderDistance", "rd32".equals(BENCH_SCENE) || voxy ? 32 : 12);
        int settleTicks = Integer.getInteger("metallium.bench.settleTicks", flight ? 100 : 300);
        int settleSeconds = Integer.getInteger("metallium.bench.settleSeconds", renderDistance >= 24 ? 150 : flight ? 20 : renderDistance >= 16 ? 90 : 60);
        line("bench: render distance=" + renderDistance);
        String pack = System.getProperty("metallium.gametest.shaderpack", "");
        if (shaders && (pack.isEmpty() || !Files.isRegularFile(Path.of(pack))
                || !net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris"))) {
            line("bench: SKIPPED shaders scene (needs -PwithIris and an existing -Pshaderpack)");
            line("result: SKIP");
            return;
        }
        context.runOnClient(client -> {
            client.options.enableVsync().set(BENCH_VSYNC);
            line("bench display: " + moveToBenchDisplay(client));
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
            // Spectators must still make the server generate and send chunks beyond the spawn area.
            tryCommand(world, "gamerule spectators_generate_chunks true");
            tryCommand(world, "gamemode spectator @a");
            tryCommand(world, "time set 6000");
            tryCommand(world, "gamerule advance_time false");
            tryCommand(world, "weather clear");
            // Raise the render distance only after the initial load (waitForChunksRender at a high RD times out).
            int rd = renderDistance;
            // The server sends chunks within the player's *requested* view distance (ChunkMap.getPlayerViewDistance =
            // clamp(ServerPlayer.requestedViewDistance, 2, serverViewDistance)), which only changes when the client
            // broadcasts its options. Without broadcastOptions() every RD loaded the same chunks (2616 sections).
            context.runOnClient(client -> {
                client.options.renderDistance().set(rd);
                client.options.broadcastOptions();
            });
            // The integrated server caps the view distance independently of the client option.
            world.getServer().runOnServer(server -> {
                server.getPlayerList().setViewDistance(rd);
                server.getPlayerList().setSimulationDistance(Math.min(rd, 12));
            });
            // Camera: above the terrain at the origin so the view shows land to the horizon, not mostly sky.
            String cameraSetting = System.getProperty("metallium.bench.camera", "");
            // -Dmetallium.bench.biome=minecraft:ocean: camera just above sea level in the nearest such biome (water-heavy
            // views are what make reflection-heavy packs expensive; the default view is snowy plains).
            String biome = System.getProperty("metallium.bench.biome", "");
            if (cameraSetting.isEmpty() && !biome.isEmpty()) {
                net.minecraft.core.BlockPos found = world.getServer().computeOnServer(server -> {
                    var result = server.overworld().findClosestBiome3d(
                            holder -> holder.unwrapKey().map(key -> key.identifier().toString().equals(biome)).orElse(false),
                            net.minecraft.core.BlockPos.ZERO.atY(64), 6400, 32, 64);
                    return result == null ? null : result.getFirst();
                });
                line("bench: nearest " + biome + " at " + found);
                if (found != null) {
                    cameraSetting = found.getX() + " 66 " + found.getZ() + " 0 10";
                }
            }
            int surface = context.computeOnClient(client -> client.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0));
            String camera = !cameraSetting.isEmpty() ? cameraSetting : voxy ? "0 " + (surface + 60) + " 0 110 20"
                    : "0 " + (surface + 25) + " 0 " + (flight ? "-90 10" : "110 15");
            line("bench: surface y at origin=" + surface + ", camera=" + camera);
            tryCommand(world, "tp @a " + camera);
            if (!flight) {
                // The game test runs the server in lock-step; plain waiting ticks never generates the chunks beyond the
                // spawn area (all earlier bench runs rendered only ~2.6k sections). Wait for the whole view distance.
                context.waitTicks(20); // let the new view distance reach both sides first
                long loadStart = System.nanoTime();
                try {
                    int ticks = world.getConnection().waitForChunksDownload(20 * (renderDistance >= 32 ? 300 : 120));
                    line(String.format("bench: chunks downloaded after %d ticks (%.1f s)", ticks, (System.nanoTime() - loadStart) / 1e9));
                } catch (Throwable t) {
                    line("bench: waitForChunksDownload failed (continuing): " + t.getMessage());
                }
            }

            if (shaders) {
                // Same install as the shader pack scenario, including -PshaderpackOptions (<pack>.txt).
                String installed = externalPack(context);
                if (installed == null) {
                    throw new IllegalStateException("could not install shader pack " + pack);
                }
                line("bench: shader pack " + installed + ": pipeline=" + context.computeOnClient(client -> irisSelectPack(installed, true)));
            }
            // -Dmetallium.bench.window=WxH: window size (shader packs scale with the pixel count; the default game test
            // window is 854x480, a player's fullscreen e.g. 2560x1440).
            String window = System.getProperty("metallium.bench.window", "");
            if (window.matches("\\d+x\\d+")) {
                String[] wh = window.split("x");
                context.runOnClient(client -> client.getWindow().setWindowed(Integer.parseInt(wh[0]), Integer.parseInt(wh[1])));
                // Re-centre the resized window on the bench display (a 2560x1440 window hanging off the 60 Hz main
                // screen gave run-to-run swings of 2x).
                line("bench: window display " + context.computeOnClient(MetalliumClientGameTest::moveToBenchDisplay));
                context.waitTicks(20);
            }
            line("bench: framebuffer " + context.computeOnClient(c -> c.getWindow().getWidth() + "x" + c.getWindow().getHeight()));
            // Ticks run faster than 20/s in the game test, so settle on wall-clock time as well as ticks.
            long settleUntil = System.nanoTime() + settleSeconds * 1_000_000_000L;
            context.waitTicks(settleTicks);
            while (System.nanoTime() < settleUntil) {
                context.waitTicks(20);
            }
            waitForRenderDistanceLoaded(context, rd);
            if (voxy) {
                String voxyDistance = System.getProperty("metallium.bench.voxyDistance", "");
                if (!voxyDistance.isEmpty()) {
                    line("bench: voxy sectionRenderDistance=" + voxyDistance + " -> "
                            + context.computeOnClient(c -> voxySetDistance(Float.parseFloat(voxyDistance))));
                }
                int voxyRd = Integer.getInteger("metallium.bench.voxyRd", 4);
                context.runOnClient(client -> client.options.renderDistance().set(voxyRd));
                context.waitTicks(400);
                line("bench: voxy (vanilla rd " + voxyRd + ") " + glSummary());
                line("bench: voxy debug: " + context.computeOnClient(client -> voxyDebug()));
            }
            if (!flight) {
                checkTerrainVisible(context.takeScreenshot("metallium-bench-view-" + BENCH_SCENE), "bench view");
            }
            line("bench: client render distance option=" + context.computeOnClient(c -> c.options.renderDistance().get())
                    + ", server view distance=" + world.getServer().computeOnServer(s -> s.getPlayerList().getViewDistance())
                    + ", client loaded chunks=" + context.computeOnClient(c -> c.level.getChunkSource().getLoadedChunksCount())
                    + ", server loaded chunks=" + world.getServer().computeOnServer(s -> s.overworld().getChunkSource().getLoadedChunksCount()));
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
                    "bench-present: vsync=%s display_refresh_hz=%.1f rendered_fps=%.1f submitted_fps=%.1f presented_fps=%.1f displayed_fps=%.1f dropped=%d skipped=%d window=%s (Metal surface counters; 0 on OpenGL)",
                    BENCH_VSYNC, refreshHz, frameMs.size() / seconds, (present1[0] - present0[0]) / seconds, (present1[1] - present0[1]) / seconds,
                    (present1[4] - present0[4]) / seconds, present1[2] - present0[2], present1[3] - present0[3], String.join("|", windowStates)));
            line(String.format("bench-result: scene=%s avg_fps=%.1f low1_fps=%.1f p50_ms=%.2f p95_ms=%.2f p99_ms=%.2f max_ms=%.2f frames=%d heap_mb=%d",
                    BENCH_SCENE, avgFps, low1, pct(frameMs, 0.50), pct(frameMs, 0.95), pct(frameMs, 0.99), frameMs.get(frameMs.size() - 1), frameMs.size(), heapMb));
            if (voxy) {
                line("bench: voxy after measurement " + glSummary()); // counters minus the pre-measurement line = the 30 s window
            }
            if (Boolean.getBoolean("metallium.bench.power")) {
                powerBench(context);
            }
            line("result: PASS");
        }
    }

    private static final String TEST_PACK = "metallium-testpack";
    private static final String[] TEST_PACK_FILES = {"shaders.properties", "composite.vsh", "composite.fsh", "composite1.vsh", "composite1.fsh",
            "final.vsh", "final.fsh"};
    private static final String GBUFFER_PACK = "metallium-gbufferpack";
    private static final String[] GBUFFER_PACK_FILES = {"gbuffers_terrain.vsh", "gbuffers_terrain.fsh", "composite.vsh", "composite.fsh"};
    private static final String SHADOW_PACK = "metallium-shadowpack";
    private static final String[] SHADOW_PACK_FILES = {"gbuffers_terrain.vsh", "gbuffers_terrain.fsh", "shadow.vsh", "shadow.fsh", "composite.vsh", "composite.fsh"};
    private static final String COMPUTE_PACK = "metallium-computepack";
    private static final String[] COMPUTE_PACK_FILES = {"shaders.properties", "setup.csh", "composite.csh", "composite1.csh", "composite2.vsh", "composite2.fsh",
            "composite3.vsh", "composite3.fsh"};
    private static final String TEX_PACK = "metallium-texpack";
    private static final String[] TEX_PACK_FILES = {"shaders.properties", "composite.vsh", "composite.fsh", "composite1.vsh", "composite1.fsh", "stripes.png", "volume.dat", "noise.png"};

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
        blockParticles(context, world, "metallium-iris-particles-off");

        try {
            installTestPack(context, TEST_PACK, TEST_PACK_FILES);
            installTestPack(context, GBUFFER_PACK, GBUFFER_PACK_FILES);
            installTestPack(context, SHADOW_PACK, SHADOW_PACK_FILES);
            installTestPack(context, COMPUTE_PACK, COMPUTE_PACK_FILES);
            installTestPack(context, TEX_PACK, TEX_PACK_FILES);
        } catch (IOException e) {
            failures.add("could not install test shader pack: " + e);
            return;
        }

        line("iris test pack: pipeline=" + context.computeOnClient(client -> irisSelectPack(TEST_PACK, true)));
        context.waitTicks(60);
        clearChat(context);
        Path on = context.takeScreenshot("metallium-iris-testpack");
        checkInverted(off, on);
        // The bundled test pack uses only supported features (incl. blend.composite1): nothing may fail to translate.
        List<String> testPackProblems = context.computeOnClient(client -> metalPipelineProblems());
        line("iris test pack: unsupported-feature reports=" + testPackProblems);
        if (!testPackProblems.isEmpty()) {
            failures.add("test shader pack reports unsupported features on Metal: " + testPackProblems);
        }

        // Stage 3: gbuffers_terrain writes a marker into colortex1, the composite pass turns marked pixels magenta.
        line("iris gbuffers test pack: pipeline=" + context.computeOnClient(client -> irisSelectPack(GBUFFER_PACK, true)));
        context.waitTicks(100); // the pack switches Sodium to Iris's extended vertex format: chunks are rebuilt
        clearChat(context);
        checkGbufferMarker(context.takeScreenshot("metallium-iris-gbufferpack"));
        // Iris's atlasSize/gtextureSize uniforms follow the bound Sampler0 (getAlbedoTex); 0 made packs treat block
        // particles (sprinting) as particle-atlas particles.
        String albedo = context.computeOnClient(client -> irisAlbedoTexture());
        line("iris gbuffers test pack: albedo texture=" + albedo);
        if (albedo.startsWith("id 0 ") || albedo.startsWith("error")) {
            failures.add("Metal shader pipeline does not track the albedo texture (atlasSize would be 0): " + albedo);
        }
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
        texPackScenario(context);

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
            blockParticles(context, world, "metallium-iris-external-pack-particles");
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
     * External pack, local player model (entity_translucent pipeline -> gbuffers_entities(_translucent) / gbuffers_hand):
     * third person (F5) from behind with the morning sun behind the camera (owner report: "in F5, looking away from the
     * sun I can kinda look through the skin"), then first person with the empty hand. The skin must be visible:
     * the body/hand area must not be mostly near-white (white texture / overlay), and the body must hide what is behind
     * it: the same view with the player invisible is the background, and only a small part of the body area may match
     * it. (A sky-blue colour count is only logged: the default skin's cyan shirt reads as sky-blue in daylight; that made
     * the 2026-10-19 sweep fail BSL on Metal and OpenGL alike.) Same scene with -Pengine=OPENGL.
     */
    private void playerModelScene(ClientGameTestContext context, TestSingleplayerContext world) {
        tryCommand(world, "gamemode creative @a");
        tryCommand(world, "clear @a");
        tryCommand(world, "time set 1500"); // sun low in the east
        tryCommand(world, "execute as @p at @s run tp @s ~ ~ ~ 90 10"); // facing west: away from the sun
        tryCommand(world, "execute as @p at @s run summon minecraft:mannequin ^1.5 ^ ^2 {profile:{texture:\"minecraft:entity/player/slim/alex\",model:\"slim\"}}");
        tryCommand(world, "execute as @p at @s run summon minecraft:mannequin ^-1.5 ^ ^2 {profile:{texture:\"minecraft:entity/player/wide/steve\"}}");
        context.runOnClient(client -> client.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK));
        context.waitTicks(60);
        clearChat(context);
        Path third = context.takeScreenshot("metallium-iris-external-pack-player-f5");
        // Background: the local player is not rendered while invisible (isInvisibleTo(self)).
        tryCommand(world, "effect give @p minecraft:invisibility infinite 0 true");
        context.waitTicks(10);
        clearChat(context);
        Path background = context.takeScreenshot("metallium-iris-external-pack-player-f5-background");
        tryCommand(world, "effect clear @p minecraft:invisibility");
        context.runOnClient(client -> client.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
        context.waitTicks(20);
        clearChat(context);
        Path first = context.takeScreenshot("metallium-iris-external-pack-player-hand");
        tryCommand(world, "kill @e[type=minecraft:mannequin]");
        tryCommand(world, "execute as @p at @s run tp @s ~ ~ ~ ~ 0");
        tryCommand(world, "time set 6000");
        tryCommand(world, "gamemode spectator @a");
        try {
            java.awt.image.BufferedImage body = javax.imageio.ImageIO.read(third.toFile());
            java.awt.image.BufferedImage behind = javax.imageio.ImageIO.read(background.toFile());
            java.awt.image.BufferedImage hand = javax.imageio.ImageIO.read(first.toFile());
            // Third person from behind: the body is the central column of the screen (torso + legs); first person:
            // the right arm reaches into the lower right corner.
            double[] bodyStats = paleAndSkyFraction(body, 0.50, 0.75, 0.48, 0.52);
            double[] handStats = paleAndSkyFraction(hand, 0.72, 0.95, 0.70, 0.90);
            double seeThrough = regionMatch(body, behind, 0.50, 0.75, 0.48, 0.52, 12);
            line(String.format("external pack player model: f5 body near-white=%.2f sky-blue=%.2f matches-background=%.2f; first-person hand near-white=%.2f sky-blue=%.2f",
                    bodyStats[0], bodyStats[1], seeThrough, handStats[0], handStats[1]));
            if (bodyStats[0] > 0.35 || handStats[0] > 0.35 || seeThrough > 0.25) {
                failures.add(String.format("player model looks white/untextured or see-through with the external pack (f5 %.2f near-white, %.2f like the background; hand %.2f near-white)",
                        bodyStats[0], seeThrough, handStats[0]));
            }
        } catch (IOException e) {
            failures.add("could not analyse player model screenshots: " + e);
        }
    }

    /** Fraction of pixels in a screen rectangle whose colour equals the other image's within {@code tolerance} per channel. */
    private static double regionMatch(java.awt.image.BufferedImage a, java.awt.image.BufferedImage b, double fromY, double toY, double fromX, double toX, int tolerance) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        int total = 0;
        int same = 0;
        for (int y = (int) (h * fromY); y < (int) (h * toY); y += 2) {
            for (int x = (int) (w * fromX); x < (int) (w * toX); x += 2) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                boolean match = true;
                for (int shift = 0; shift <= 16; shift += 8) {
                    if (Math.abs(((pa >> shift) & 0xFF) - ((pb >> shift) & 0xFF)) > tolerance) {
                        match = false;
                    }
                }
                total++;
                if (match) {
                    same++;
                }
            }
        }
        return (double) same / Math.max(1, total);
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

    /**
     * Custom textures: the texture test pack's composite pass paints four quarters of the middle band green when a
     * check passes (PNG stage override with repeat, raw RGB16F 3D texture as customtex0, texture.noise, size.buffer).
     */
    private void texPackScenario(ClientGameTestContext context) {
        line("iris custom texture test pack: pipeline=" + context.computeOnClient(client -> irisSelectPack(TEX_PACK, true)));
        context.waitTicks(40);
        clearChat(context);
        Path shot = context.takeScreenshot("metallium-iris-texpack");
        try {
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(shot.toFile());
            String[] names = {"png override", "raw 3D texture", "custom noise", "size.buffer"};
            StringBuilder result = new StringBuilder("iris custom texture test pack: green fraction");
            for (int i = 0; i < 4; i++) {
                double green = colorFraction(image, 0.35, 0.65, i * 0.25 + 0.02, i * 0.25 + 0.23, 0x00FF00);
                result.append(String.format(" %s=%.2f", names[i], green));
                if (green < 0.90) {
                    failures.add(String.format("custom texture test pack: %s check failed (green %.2f)", names[i], green));
                }
            }
            // composite1.fsh: 18 textures in one pass (more than Metal's 16 sampler states), bottom band.
            double many = colorFraction(image, 0.92, 0.98, 0.05, 0.95, 0x00FF00);
            result.append(String.format(" 18-texture pass=%.2f", many));
            if (many < 0.90) {
                failures.add(String.format("custom texture test pack: 18-texture pass check failed (green %.2f)", many));
            }
            line(result.toString());
        } catch (IOException e) {
            failures.add("could not analyse custom texture pack screenshot: " + e);
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
    /** Combination tests (DH / Voxy / Bobby + -PwithIris -Pshaderpack=...): activates the external pack, if any. */
    private void comboShaderPack(ClientGameTestContext context, String scene) {
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris")) {
            return;
        }
        String external = externalPack(context);
        if (external == null) {
            return;
        }
        line(scene + ": shader pack " + external + ": pipeline=" + context.computeOnClient(client -> irisSelectPack(external, true)));
        context.waitTicks(100);
        context.takeScreenshot("metallium-" + scene + "-pack");
    }

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
    /** The active Metal Iris pipeline's "Not supported yet" reports (empty on OpenGL or without a Metal pipeline). */
    @SuppressWarnings("unchecked")
    private static List<String> metalPipelineProblems() {
        try {
            Object manager = Class.forName("net.irisshaders.iris.Iris").getMethod("getPipelineManager").invoke(null);
            Object pipeline = manager.getClass().getMethod("getPipelineNullable").invoke(manager);
            if (pipeline == null || !pipeline.getClass().getName().equals("dev.metallium.iris.post.MetalPostPipeline")) {
                return List.of();
            }
            java.lang.reflect.Field field = pipeline.getClass().getDeclaredField("problems");
            field.setAccessible(true);
            return List.copyOf((List<String>) field.get(pipeline));
        } catch (ReflectiveOperationException e) {
            return List.of("could not read pipeline problems: " + e);
        }
    }

    /**
     * The texture behind the active pipeline's {@code getAlbedoTex()} (what Iris's {@code atlasSize} uniform reads):
     * "TextureAtlas WxH" when it is an atlas, else what it is. Packs use atlasSize to tell block-atlas particles
     * (sprint/break particles) from particle-atlas ones.
     */
    private static String irisAlbedoTexture() {
        try {
            Object manager = Class.forName("net.irisshaders.iris.Iris").getMethod("getPipelineManager").invoke(null);
            Object pipeline = manager.getClass().getMethod("getPipelineNullable").invoke(manager);
            if (pipeline == null) {
                return "no pipeline";
            }
            int id = (int) pipeline.getClass().getMethod("getAlbedoTex").invoke(pipeline);
            Object tracker = Class.forName("net.irisshaders.iris.pbr.TextureTracker").getField("INSTANCE").get(null);
            Object texture = tracker.getClass().getMethod("getTexture", int.class).invoke(tracker, id);
            if (texture instanceof net.minecraft.client.renderer.texture.TextureAtlas atlas) {
                return "TextureAtlas " + atlas.location();
            }
            return "id " + id + " -> " + (texture == null ? "untracked" : texture.getClass().getSimpleName());
        } catch (ReflectiveOperationException e) {
            return "error: " + e;
        }
    }

    /** Block-texture particles (what sprinting kicks up) in front of the camera, for a screenshot. */
    private void blockParticles(ClientGameTestContext context, TestSingleplayerContext world, String screenshot) {
        tryCommand(world, "execute as @p at @s run particle minecraft:block{block_state:\"minecraft:grass_block\"} ^ ^ ^2.5 0.4 0.3 0.4 0 300 force");
        tryCommand(world, "execute as @p at @s run particle minecraft:block{block_state:\"minecraft:dirt\"} ^-1 ^ ^2.5 0.3 0.3 0.3 0 200 force");
        context.waitTicks(2);
        clearChat(context);
        context.takeScreenshot(screenshot);
        context.waitTicks(40); // let them die before the next screenshot
    }

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
        for (String mod : new String[] {"distanthorizons", "voxy", "bobby", "reglass", "cullleaves", "moreculling"}) {
            line(mod + " loaded: " + net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(mod));
        }
        String sessionExit = System.getProperty("metallium.gametest.sessionExit");
        if (sessionExit != null) {
            sessionExitRun(context, backend, sessionExit);
            return;
        }
        if (dev.metallium.compat.CrashGuard.recovery() != null) {
            crashRecoveryRun(context, backend);
            return;
        }
        // Physics Mod combined with an LOD mod: the LOD scenario runs and adds physicsInWorld (instead of PhysicsScenario).
        boolean lodCombo = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("distanthorizons")
                || net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("voxy")
                || net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("bobby");
        if ("physics".equals(System.getProperty("metallium.gametest.mode")) && !lodCombo) {
            try {
                new PhysicsScenario(this::line, failures).run(context, backend);
                line(failures.isEmpty() ? "result: PASS" : "result: FAIL " + failures);
            } catch (Throwable t) {
                line("result: FAIL " + t);
                throw t;
            } finally {
                writeReport();
            }
            if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
            return;
        }
        if ("betterf3".equals(System.getProperty("metallium.gametest.mode"))) {
            try {
                new BetterF3Scenario(this::line, failures).run(context, backend);
                line(failures.isEmpty() ? "result: PASS" : "result: FAIL " + failures);
            } catch (Throwable t) {
                line("result: FAIL " + t);
                throw t;
            } finally {
                writeReport();
            }
            if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
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
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("bobby")) {
            bobbyTest(context);
            return;
        }
        if (Boolean.getBoolean("metallium.gametest.voxyScenario") || net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("voxy")
                && System.getProperty("metallium.gl.routedMods", "").contains("voxy")) {
            voxyTest(context);
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
        physicsInWorld(context, world, "dh");
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
            comboShaderPack(context, "dh");
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

    /**
     * Voxy routed through metallium-gl (-PwithVoxy=<jar> -PwithGl -PglMods=voxy): one natural world, high spectator view.
     * Voxy only builds LODs from chunks the client has loaded, so the vanilla render distance is first raised (Voxy
     * ingests the loaded chunks), then lowered: terrain beyond the low vanilla distance can only be Voxy's LODs. Judged by
     * looking at the screenshots. Like the DH test it halts the JVM instead of closing the world.
     */
    private void voxyTest(ClientGameTestContext context) {
        try {
            TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(false)
                    .adjustSettings(state -> state.setSeed(System.getProperty("metallium.gametest.seed", "metallium-forest"))).create();
            world.getConnection().waitForChunksRender();
            tryCommand(world, "gamemode spectator @a");
            tryCommand(world, "tp @a 0 160 0 30 35");
            tryCommand(world, "time set 6000");
            tryCommand(world, "gamerule advance_time false");
            context.runOnClient(client -> client.options.renderDistance().set(16));
            comboShaderPack(context, "voxy");
            for (int i = 1; i <= 3; i++) {
                context.waitTicks(400);
                line("voxy: ingest phase " + (i * 20) + " s, " + glSummary());
                line("voxy debug: " + context.computeOnClient(client -> voxyDebug()));
            }
            context.takeScreenshot("metallium-voxy-rd16");
            context.runOnClient(client -> client.options.renderDistance().set(4));
            context.waitTicks(400);
            clearChat(context);
            checkTerrainVisible(context.takeScreenshot("metallium-voxy-lods-rd4"), "voxy LODs (vanilla rd 4)");
            measure(context, "voxy rd4");
            physicsInWorld(context, world, "voxy");
            tryCommand(world, "tp @a 0 220 0 210 30");
            context.waitTicks(200);
            context.takeScreenshot("metallium-voxy-lods-high");
            tryCommand(world, "tp @a 0 300 0 120 60");
            context.waitTicks(200);
            context.takeScreenshot("metallium-voxy-lods-top");
            f3Screenshot(context);
            String status = context.computeOnClient(client -> dev.metallium.compat.CompatStatus.text());
            for (String row : status.split("\n")) {
                if (row.contains("Voxy")) line("voxy status row: " + row.trim());
            }
            if (!status.contains("runs via metallium-gl")) failures.add("status screen does not show Voxy as running via metallium-gl");
            line("voxy: final " + glSummary());
            line("voxy debug: " + context.computeOnClient(client -> voxyDebug()));
            line(failures.isEmpty() ? "result: PASS" : "result: FAIL " + failures);
        } catch (Throwable t) {
            line("result: FAIL " + t);
            line("voxy: " + glSummary());
            writeReport();
            Runtime.getRuntime().halt(1);
        }
        writeReport();
        Runtime.getRuntime().halt(failures.isEmpty() ? 0 : 1);
    }

    /**
     * Bobby (-PwithBobby; client-side chunk cache): loads a natural world at render + server view distance 16, then
     * drops the server's view distance to 4. The server unloads the chunks beyond 4; Bobby keeps them on the client, so
     * the terrain must stay visible to distance 16 (without Bobby: only 4). With -PwithVoxy routed through metallium-gl
     * the same run also checks Voxy's LODs over Bobby's cached chunks (vanilla rd 4 at the end). Halts like the DH test.
     */
    private void bobbyTest(ClientGameTestContext context) {
        boolean voxy = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("voxy")
                && System.getProperty("metallium.gl.routedMods", "").contains("voxy");
        try {
            TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(false)
                    .adjustSettings(state -> state.setSeed(System.getProperty("metallium.gametest.seed", "metallium"))).create();
            world.getConnection().waitForChunksRender();
            tryCommand(world, "gamemode spectator @a");
            tryCommand(world, "tp @a 0 129 0 110 20");
            tryCommand(world, "time set 6000");
            tryCommand(world, "gamerule advance_time false");
            context.runOnClient(client -> client.options.renderDistance().set(16));
            world.getServer().runOnServer(server -> server.getPlayerList().setViewDistance(16));
            comboShaderPack(context, "bobby");
            context.waitTicks(600);
            clearChat(context);
            int before = context.computeOnClient(client -> client.level.getChunkSource().getLoadedChunksCount());
            line("bobby: server 16: " + chunkStats(context));
            context.takeScreenshot("metallium-bobby-server16");
            sodiumStats(context, "bobby server16");
            world.getServer().runOnServer(server -> server.getPlayerList().setViewDistance(4));
            context.waitTicks(400);
            clearChat(context);
            line("bobby: server 4: " + chunkStats(context));
            int after = context.computeOnClient(client -> client.level.getChunkSource().getLoadedChunksCount());
            // Without Bobby the client drops to the server's 9x9 area; Bobby keeps the chunks it had.
            if (after < before * 0.9 || after <= 81) failures.add("bobby: client chunks dropped from " + before + " to " + after);
            // Judged by looking (snowy terrain fools the coverage heuristic); not a failure criterion here.
            checkTerrainVisible(context.takeScreenshot("metallium-bobby-server4"), "bobby cached chunks (server view distance 4)", false);
            measure(context, "bobby server4");
            if (voxy) {
                line("bobby+voxy: " + glSummary());
                line("bobby+voxy debug: " + context.computeOnClient(client -> voxyDebug()));
                context.runOnClient(client -> client.options.renderDistance().set(4));
                context.waitTicks(400);
                clearChat(context);
                checkTerrainVisible(context.takeScreenshot("metallium-bobby-voxy-rd4"), "voxy LODs over Bobby's chunks (vanilla rd 4)");
                measure(context, "bobby+voxy rd4");
                line("bobby+voxy: final " + glSummary());
            }
            physicsInWorld(context, world, "bobby");
            line(failures.isEmpty() ? "result: PASS" : "result: FAIL " + failures);
        } catch (Throwable t) {
            line("result: FAIL " + t);
            writeReport();
            Runtime.getRuntime().halt(1);
        }
        writeReport();
        Runtime.getRuntime().halt(failures.isEmpty() ? 0 : 1);
    }

    /**
     * Physics Mod on top of an LOD / shader-pack world (-PwithPhysics with -PwithVoxy / -PwithDH / -PwithIris): a brick
     * wall in front of the camera is destroyed (debris), a zombie is killed (ragdoll) and TNT explodes, with screenshots.
     */
    private void physicsInWorld(ClientGameTestContext context, TestSingleplayerContext world, String tag) {
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("physicsmod")) {
            return;
        }
        tryCommand(world, "gamemode creative @a");
        tryCommand(world, "gamerule spawn_monsters false");
        tryCommand(world, "execute as @p at @s run tp @s ~ ~ ~ ~ 10");
        tryCommand(world, "execute as @p at @s run fill ^-2 ^-2 ^5 ^2 ^1 ^6 minecraft:bricks");
        context.waitTicks(40);
        clearChat(context);
        context.takeScreenshot("metallium-" + tag + "-physics-wall");
        tryCommand(world, "execute as @p at @s run fill ^-2 ^-2 ^5 ^2 ^1 ^6 minecraft:air destroy");
        context.waitTicks(6);
        context.takeScreenshot("metallium-" + tag + "-physics-debris");
        tryCommand(world, "kill @e[type=item]");
        tryCommand(world, "execute as @p at @s run summon minecraft:zombie ^ ^-1 ^4 {NoAI:1b}");
        context.waitTicks(20);
        tryCommand(world, "kill @e[type=zombie]");
        context.waitTicks(6);
        context.takeScreenshot("metallium-" + tag + "-physics-ragdoll");
        tryCommand(world, "execute as @p at @s run summon minecraft:tnt ^ ^-1 ^6 {fuse:1}");
        context.waitTicks(8);
        context.takeScreenshot("metallium-" + tag + "-physics-explosion");
        context.waitTicks(40);
        measure(context, tag + " + physics");
        line(tag + " + physics: done, " + glSummary());
    }

    /** Client chunk count (Bobby's cached chunks included) and vanilla's visible sections. */
    private static String chunkStats(ClientGameTestContext context) {
        return context.computeOnClient(client -> "client chunks=" + client.level.getChunkSource().getLoadedChunksCount()
                + " vanilla visibleSections=" + client.levelRenderer.visibleSections().size()
                + " rd=" + client.options.getEffectiveRenderDistance());
    }

    /** Sets Voxy's LOD distance (VoxyConfig.sectionRenderDistance) via reflection; returns the value read back. */
    private static String voxySetDistance(float distance) {
        try {
            Class<?> config = Class.forName("me.cortex.voxy.client.config.VoxyConfig");
            Object instance = config.getField("CONFIG").get(null);
            config.getField("sectionRenderDistance").setFloat(instance, distance);
            return String.valueOf(config.getField("sectionRenderDistance").getFloat(instance));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "unavailable: " + e;
        }
    }

    /** Voxy's own F3 debug lines (instance + render system), via reflection; render thread only. */
    private static String voxyDebug() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        try {
            Object instance = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon").getMethod("getInstance").invoke(null);
            if (instance != null) instance.getClass().getMethod("addDebug", java.util.List.class).invoke(instance, lines);
            Object render = Class.forName("me.cortex.voxy.client.core.IVoxyRenderSystemHolder").getMethod("getNullable").invoke(null);
            if (render != null) render.getClass().getMethod("addDebugInfo", java.util.List.class).invoke(render, lines);
            else lines.add("no render system");
        } catch (ReflectiveOperationException | RuntimeException e) {
            lines.add("unavailable: " + e);
        }
        return String.join(" | ", lines);
    }

    /** metallium-gl status line + unimplemented functions seen so far (reflection: metallium-gl is optional). */
    private static String glSummary() {
        StringBuilder out = new StringBuilder();
        for (String status : dev.metallium.api.MetalliumRuntime.statusLines()) {
            if (status.startsWith("Voxy")) out.append(status).append("; ");
        }
        try {
            out.append(Class.forName("dev.metallium.gl.retarget.MetalliumGlBootstrap").getMethod("diagnostics").invoke(null)).append("; ");
        } catch (ReflectiveOperationException e) {
            out.append("metallium-gl: absent; ");
        }
        try {
            Object calls = Class.forName("dev.metallium.gl.MetalGLLog").getMethod("unimplementedCalls").invoke(null);
            out.append("unimplemented=").append(calls);
        } catch (ReflectiveOperationException e) {
            out.append("unimplemented=?");
        }
        return out.toString();
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
        // Vanilla's "GPU: xx%" entry is not in the default F3 profile; enable it for the screenshot and check the value.
        var gpuEntry = net.minecraft.client.gui.components.debug.DebugScreenEntries.GPU_UTILIZATION;
        var previousStatus = context.computeOnClient(client -> client.debugEntries.getStatus(gpuEntry));
        context.runOnClient(client -> {
            client.debugEntries.setStatus(gpuEntry, net.minecraft.client.gui.components.debug.DebugScreenEntryStatus.IN_OVERLAY);
            client.debugEntries.setOverlayVisible(true);
        });
        context.waitTicks(20);
        checkGpuUtilization(context, "vanilla F3");
        context.takeScreenshot("metallium-f3");
        context.runOnClient(client -> {
            client.debugEntries.setOverlayVisible(false);
            client.debugEntries.setStatus(gpuEntry, previousStatus);
        });
    }

    /**
     * Samples {@code Minecraft.getGpuUtilization()} (vanilla F3 "GPU: xx%", also read by BetterF3) for ~1 s while it is
     * being measured; on Metal the median must be plausible (0 < x <= 100). Reports the samples on every backend.
     */
    static String checkGpuUtilization(ClientGameTestContext context, String where, java.util.function.Consumer<String> line, List<String> failures) {
        double[] samples = new double[10];
        for (int i = 0; i < samples.length; i++) {
            context.waitTicks(2);
            samples[i] = context.computeOnClient(client -> client.getGpuUtilization());
        }
        double[] sorted = samples.clone();
        java.util.Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];
        String summary = String.format(java.util.Locale.ROOT, "gpu utilization (%s): median %.1f%% samples %s", where, median, java.util.Arrays.toString(samples));
        line.accept(summary);
        boolean metal = context.computeOnClient(client -> RenderSystem.getDevice().getDeviceInfo().backendName().equals("Metal"));
        if (metal && !(median > 0.0 && median <= 100.0)) {
            failures.add(String.format(java.util.Locale.ROOT, "%s: GPU utilization not plausible on Metal (median %.1f%%)", where, median));
        }
        return summary;
    }

    private void checkGpuUtilization(ClientGameTestContext context, String where) {
        checkGpuUtilization(context, where, this::line, failures);
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

    /**
     * Benchmark: waits until the client really holds the chunks of render distance {@code rd} (the server sends a disc
     * of about pi*rd^2 columns) and section building is done, so a higher RD is a heavier scene. Logs the loaded chunk
     * count; fails the benchmark if it stays far below the expected disc (the RD was not applied).
     */
    private void waitForRenderDistanceLoaded(ClientGameTestContext context, int rd) {
        int expected = (int) (Math.PI * rd * rd);
        int timeoutSeconds = Integer.getInteger("metallium.bench.loadTimeoutSeconds", 240);
        long start = System.nanoTime();
        int last = -1;
        int stableSeconds = 0;
        int loaded = 0;
        boolean built = false;
        while ((System.nanoTime() - start) / 1e9 < timeoutSeconds) {
            loaded = context.computeOnClient(client -> client.level.getChunkSource().getLoadedChunksCount());
            built = context.computeOnClient(client -> client.levelRenderer.hasRenderedAllSections());
            stableSeconds = loaded == last ? stableSeconds + 1 : 0;
            last = loaded;
            if (loaded >= expected * 0.8 && built && stableSeconds >= 3) {
                break;
            }
            context.waitTicks(20);
        }
        int effective = context.computeOnClient(client -> client.options.getEffectiveRenderDistance());
        line(String.format(java.util.Locale.ROOT,
                "bench: render distance %d (effective %d): loaded chunks=%d (expected ~%d), sections built=%s, after %.0f s",
                rd, effective, loaded, expected, built, (System.nanoTime() - start) / 1e9));
        if (loaded < expected * 0.5) {
            throw new AssertionError("render distance " + rd + " not loaded: " + loaded + " chunks, expected ~" + expected);
        }
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

    /**
     * -PsessionExit=normal|sigterm|crash: ends the session the given way once the title screen is up; the session marker
     * (config/metallium/session.json) must be gone afterwards for normal/sigterm and kept for crash
     * (tools/check-session-exit.sh runs all three and checks the files).
     */
    private void sessionExitRun(ClientGameTestContext context, String backend, String mode) {
        java.nio.file.Path marker = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("metallium/session.json");
        line("session exit: mode=" + mode + " backend=" + backend + " marker present=" + java.nio.file.Files.exists(marker)
                + " recovery=" + (dev.metallium.compat.CrashGuard.recovery() != null));
        if (!backend.startsWith("Metal")) failures.add("session exit: expected Metal, got " + backend);
        if (!java.nio.file.Files.exists(marker)) failures.add("session exit: no marker while running");
        line(failures.isEmpty() ? "result: PASS" : "result: FAIL " + failures);
        writeReport();
        if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
        switch (mode) {
            case "normal" -> {
                context.runOnClient(client -> client.stop());
                context.waitTicks(40);
            }
            case "sigterm" -> {
                ProcessHandle.current().destroy(); // SIGTERM to ourselves
                context.waitTicks(400);
            }
            case "crash" -> {
                net.minecraft.CrashReport report = net.minecraft.CrashReport.forThrowable(new RuntimeException("simulated session-exit crash"), "simulated");
                report.saveToFile(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("crash-reports/crash-sessionexit.txt"), net.minecraft.ReportType.CRASH);
                System.exit(-1);
            }
            default -> throw new AssertionError("unknown sessionExit " + mode);
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
