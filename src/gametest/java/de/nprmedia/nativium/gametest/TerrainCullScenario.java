/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.gametest;

import de.nprmedia.nativium.compat.ShaderClock;
import de.nprmedia.nativium.compat.sodium.terrain.TerrainOcclusion;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Terrain occlusion culling must never drop visible geometry ({@code ./gradlew :runClientGameTest -PwithSodium
 * -PterrainCullTest}, also with {@code -PwithIris -Pshaderpack=...}). Each view is rendered with culling off and on in
 * the same session and the screenshots are compared pixel by pixel:
 * <ul>
 *   <li>{@code wall}: a stone wall in front of the camera hides most terrain behind it; hills above its top edge stay
 *       visible (a wrong row order or depth convention in the Hi-Z test would cull them).</li>
 *   <li>{@code behind}: the camera turns 180 degrees and the screenshot is taken at once: everything now in view was
 *       off screen (hidden) in the latest culling result, so it is drawn only if phase 2 re-tests it correctly.</li>
 *   <li>{@code open}: eye level over open land (terrain coverage, distant sections).</li>
 * </ul>
 * With a shader pack, Iris's frame clock is frozen ({@link ShaderClock}): waving plants, clouds, TAA jitter and dithered
 * shadows keep one phase, so the pack renders deterministically and on vs off must match exactly as well. Two
 * {@code baseline} views (off vs off through the same steps) show the noise floor.
 */
final class TerrainCullScenario {
    /** {@code -Dnativium.gametest.cullRd} (default 12): render distance of the views. */
    private static final int RENDER_DISTANCE = Integer.getInteger("nativium.gametest.cullRd", 12);
    /** {@code -Dnativium.gametest.cullFreezeClock=false}: keep the shader pack's clock running (old, noisy comparison). */
    private static final boolean FREEZE_CLOCK = !"false".equals(System.getProperty("nativium.gametest.cullFreezeClock"));
    /** Ticks a view settles before the screenshot (a shader pack's TAA history and smoothed exposure converge). */
    private static final int SETTLE_TICKS = Integer.getInteger("nativium.gametest.cullSettleTicks", 60);

    private final Consumer<String> line;
    private final List<String> failures;
    private final java.util.function.Function<ClientGameTestContext, String> packInstaller;
    /** Voxy loaded: culling must stay off (it draws its LODs inside Sodium's opaque layer). */
    private static final boolean VOXY = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("voxy");
    /** The current view is the negative control (phase 2 drops batches on purpose). */
    private boolean control;
    /** Largest allowed mean difference of an 8x8 tile (a missing block covers whole tiles; noise is scattered). */
    private static final double TILE_LIMIT = Double.parseDouble(System.getProperty("nativium.gametest.cullTileLimit", "32"));

    TerrainCullScenario(final Consumer<String> line, final List<String> failures,
                        final java.util.function.Function<ClientGameTestContext, String> packInstaller) {
        this.line = line;
        this.failures = failures;
        this.packInstaller = packInstaller;
    }

    void run(final ClientGameTestContext context) {
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium")) {
            line.accept("terrain-cull: SKIPPED (needs Sodium)");
            return;
        }
        boolean shaders = !System.getProperty("nativium.gametest.shaderpack", "").isEmpty();
        context.runOnClient(client -> {
            client.options.chunkSectionFadeInTime().set(0.0);
            client.options.particles().set(net.minecraft.server.level.ParticleStatus.MINIMAL); // falling leaves differ per frame
        });
        try (TestSingleplayerContext world = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(state -> state.setSeed("nativium"))
                .create()) {
            world.getConnection().waitForChunksRender();
            for (String c : new String[] {"gamerule spawn_monsters false", "gamerule spectators_generate_chunks true", "gamemode spectator @a",
                    "time set 6000", "gamerule advance_time false", "weather clear", "gamerule advance_weather false"}) {
                world.getServer().runCommand(c);
            }
            // Raise the render distance only after the initial load (waitForChunksRender at a high RD times out).
            context.runOnClient(client -> {
                client.options.renderDistance().set(RENDER_DISTANCE);
                client.options.broadcastOptions();
            });
            world.getServer().runOnServer(server -> server.getPlayerList().setViewDistance(RENDER_DISTANCE));
            line.accept("terrain-cull: render distance " + RENDER_DISTANCE);
            if (shaders) {
                String pack = packInstaller.apply(context);
                context.waitTicks(100);
                line.accept("terrain-cull: shader pack " + pack);
                if (FREEZE_CLOCK) {
                    // 2 s per frame: eye-brightness smoothing (half-life ~10 s) settles in ~100 frames after a teleport.
                    ShaderClock.frameTime = Float.parseFloat(System.getProperty("nativium.gametest.cullFrameTime", "2.0"));
                    ShaderClock.frozen = true;
                }
                line.accept("terrain-cull: shader clock frozen=" + ShaderClock.frozen);
            }
            // Around the player's spawn position (loaded on the client); heights from the client level.
            int[] spawn = context.computeOnClient(c -> new int[] {c.player.getBlockX(), c.player.getBlockZ(),
                    c.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.player.getBlockX(), c.player.getBlockZ())});
            int x = spawn[0];
            int z = spawn[1];
            int surface = spawn[2];
            line.accept("terrain-cull: camera column " + x + " " + z + ", surface y=" + surface);
            // Wall 6 blocks south of the camera (yaw 0 looks +z), top 1.4 blocks above eye level, 61 blocks wide.
            world.getServer().runCommand(String.format("fill %d %d %d %d %d %d minecraft:stone", x - 30, surface - 6, z + 6, x + 30, surface + 2, z + 6));
            String ground = String.format("%d.5 %d %d.5", x, surface, z);
            String high = String.format("%d.5 %d %d.5", x, surface + 6, z);
            String under = String.format("%d.5 %d %d.5", x, surface - 30, z);
            world.getServer().runCommand("tp @a " + ground + " 0 0");
            context.waitTicks(20);
            try {
                world.getConnection().waitForChunksDownload(20 * 120);
            } catch (Throwable t) {
                line.accept("terrain-cull: waitForChunksDownload failed (continuing): " + t.getMessage());
            }
            context.waitTicks(400);
            // Moving entities (bees) would differ between the two screenshots: remove them and freeze game ticks.
            world.getServer().runCommand("kill @e[type=!player]");
            world.getServer().runCommand("tick freeze");

            if (shaders) {
                // Noise floor: off vs off through the same steps (exact when the pack renders deterministically).
                compare(context, world, "baseline", ground, 0, Float.NaN, false, shaders, "off");
                compare(context, world, "baseline-turn", ground, 180, 0, false, shaders, "off");
            }
            compare(context, world, "wall", ground, 0, Float.NaN, false, shaders);
            compare(context, world, "behind", ground, 180, 0, false, shaders);    // settle facing the wall, turn round, shoot
            compare(context, world, "turnback", ground, 0, 180, false, shaders);  // and back: old results for the wall view
            compare(context, world, "open", high, 90, Float.NaN, false, shaders);
            compare(context, world, "under", under, 90, 270, false, shaders);      // inside the ground (caves, solid rock)
            // Stress: phase 1 draws nothing; everything visible must come from the GPU-decided phase-2 draws.
            compare(context, world, "deferall-wall", ground, 0, Float.NaN, true, shaders);
            compare(context, world, "deferall-open", high, 90, Float.NaN, true, shaders);
            // Negative control: defer everything and drop every 4th phase-2 batch; the comparison must catch it.
            control = true;
            compare(context, world, "control-drop", ground, 0, Float.NaN, true, shaders);
            control = false;
            // AUTO: culls at rd >= 24, with or without a pack (unless the pack's options displace terrain).
            context.runOnClient(client -> TerrainOcclusion.setMode("auto"));
            context.waitTicks(20);
            context.computeOnClient(client -> TerrainOcclusion.snapshot());
            context.waitTicks(20);
            String auto = context.computeOnClient(client -> TerrainOcclusion.snapshot());
            line.accept("terrain-cull: auto at rd " + RENDER_DISTANCE + ": " + auto);
            boolean autoCulls = !auto.contains("no frames tested");
            if (autoCulls != (RENDER_DISTANCE >= TerrainOcclusion.AUTO_MIN_RENDER_DISTANCE && !VOXY)) {
                failures.add("terrain-cull: auto mode " + (autoCulls ? "culls" : "does not cull") + " at rd " + RENDER_DISTANCE + " (" + auto + ")");
            }
            // A frozen server did not shut down with Distant Horizons loaded (client waited for the halt forever).
            world.getServer().runCommand("tick unfreeze");
            context.waitTicks(5);
        } finally {
            ShaderClock.frozen = false;
            ShaderClock.frameTime = 0;
        }
        context.runOnClient(client -> TerrainOcclusion.setMode(TerrainOcclusion.INITIAL_MODE));
    }

    private static void look(final ClientGameTestContext context, final float yaw) {
        context.runOnClient(c -> {
            c.player.setYRot(yaw);
            c.player.setXRot(0);
            c.player.yRotO = yaw;
            c.player.xRotO = 0;
        });
    }

    private void compare(final ClientGameTestContext context, final TestSingleplayerContext world, final String name,
                         final String pos, final float yaw, final float fromYaw, final boolean deferAll, final boolean shaders) {
        compare(context, world, name, pos, yaw, fromYaw, deferAll, shaders, "on");
    }

    /**
     * Screenshot at {@code pos} facing {@code yaw} with culling {@code modeA} (normally on), then off, compared. Both
     * renderings go through the same steps (teleport, settle, turn, shoot), so with a shader pack and its frozen clock
     * the temporal state (TAA history after a turn) is the same in both. {@code fromYaw} (not NaN): settle facing
     * {@code fromYaw} first and turn to {@code yaw} directly before the screenshot (sections that were off screen /
     * hidden in the latest results must be drawn in that very frame).
     */
    private void compare(final ClientGameTestContext context, final TestSingleplayerContext world, final String name,
                         final String pos, final float yaw, final float fromYaw, final boolean deferAll, final boolean shaders,
                         final String modeA) {
        String[] counters = new String[1];
        Path on = capture(context, world, name + "-" + ("on".equals(modeA) ? "on" : modeA + "A"), modeA, pos, yaw, fromYaw, deferAll, counters);
        line.accept("terrain-cull: " + name + " culling " + modeA + ": " + counters[0]);
        Path off = capture(context, world, name + "-off", "off", pos, yaw, fromYaw, false, null);

        double[] diff = difference(on, off, name);
        line.accept(String.format(java.util.Locale.ROOT, "terrain-cull: %s %s vs off: differing pixels %.4f%% (any), %.4f%% (tol 24), %.4f%% (tol 64), max channel diff %d, max 8x8 tile mean above image mean %.1f",
                name, modeA, diff[2] * 100, diff[0] * 100, diff[1] * 100, (int) diff[3], diff[4]));
        // Missing sections show as large differences. Without a shader pack, and with a pack whose clock is frozen, the
        // renderings are (nearly) deterministic; small differences (in-block overlay fade, TAA after a turn) are
        // reported only. A running pack clock (waving leaves, TAA) differs between any two frames: judge the large
        // differences only.
        double limit = shaders && !ShaderClock.frozen ? 0.02 : 0.0005;
        if (control && VOXY) {
            return; // nothing is culled with Voxy: no control possible
        }
        if (control) {
            // Negative control: the comparison must notice the terrain that phase 2 left out.
            boolean detected = diff[1] > limit || diff[4] > TILE_LIMIT;
            line.accept("terrain-cull: " + name + " negative control " + (detected ? "detected" : "NOT detected"));
            if (!detected) {
                failures.add("terrain-cull " + name + ": the comparison did not notice deliberately missing terrain");
            }
            return;
        }
        if (diff[1] > limit) {
            failures.add(String.format(java.util.Locale.ROOT, "terrain-cull %s: culling changed %.3f%% of the pixels", name, diff[1] * 100));
        }
        // Coherent differences (a missing section or block covers whole 8x8 tiles); scattered temporal noise does not.
        if (diff[4] > TILE_LIMIT) {
            failures.add(String.format(java.util.Locale.ROOT, "terrain-cull %s: coherent difference, 8x8 tile mean %.1f", name, diff[4]));
        }
        if (VOXY) {
            // Culling is off with Voxy (TerrainOcclusion.cullThisFrame): "on" must not have run.
            if (!counters[0].contains("no frames tested")) {
                failures.add("terrain-cull " + name + ": culled although Voxy is loaded (" + counters[0] + ")");
            }
            return;
        }
        if (!"on".equals(modeA)) {
            return;
        }
        if (deferAll && counters[0].contains("phase2 drawn=0 ")) {
            failures.add("terrain-cull " + name + ": phase 2 drew nothing although every section was deferred (" + counters[0] + ")");
        }
        if (!counters[0].contains("deferred draws=")) {
            failures.add("terrain-cull " + name + ": no culling counters (" + counters[0] + ")");
        }
    }

    /** One rendering: culling mode, teleport, settle (facing {@code fromYaw} if set), turn to {@code yaw}, screenshot. */
    private Path capture(final ClientGameTestContext context, final TestSingleplayerContext world, final String shot,
                         final String mode, final String pos, final float yaw, final float fromYaw, final boolean deferAll,
                         final String[] counters) {
        boolean turn = !Float.isNaN(fromYaw);
        context.runOnClient(client -> {
            TerrainOcclusion.setMode(mode);
            TerrainOcclusion.deferAll = deferAll;
            TerrainOcclusion.testDropPhaseTwo = control && !"off".equals(mode);
            // Keep results across the slow game-test frames, so a turn defers sections with old results (worst case).
            TerrainOcclusion.maxResultAge = turn ? 1_000_000 : 30;
        });
        world.getServer().runCommand("tp @a " + pos + " " + (turn ? fromYaw : yaw) + " 0");
        context.waitTicks(40);
        look(context, turn ? fromYaw : yaw);
        context.waitTicks(SETTLE_TICKS);
        context.computeOnClient(client -> TerrainOcclusion.snapshot()); // reset counters
        look(context, yaw);
        Path path = context.takeScreenshot("terrain-cull-" + shot);
        context.waitTicks(20);
        if (counters != null) {
            counters[0] = context.computeOnClient(client -> TerrainOcclusion.snapshot());
        }
        context.runOnClient(client -> {
            TerrainOcclusion.setMode("off");
            TerrainOcclusion.deferAll = false;
            TerrainOcclusion.testDropPhaseTwo = false;
            TerrainOcclusion.maxResultAge = 30;
        });
        return path;
    }

    /**
     * Fractions of pixels whose largest channel difference exceeds 24 and 64, of pixels that differ at all, and the
     * largest difference. A difference mask (red > 64, yellow > 24, grey > 0) is written next to the screenshots.
     */
    private double[] difference(final Path a, final Path b, final String name) {
        try {
            java.awt.image.BufferedImage imageA = javax.imageio.ImageIO.read(a.toFile());
            java.awt.image.BufferedImage imageB = javax.imageio.ImageIO.read(b.toFile());
            int w = Math.min(imageA.getWidth(), imageB.getWidth());
            int h = Math.min(imageA.getHeight(), imageB.getHeight());
            java.awt.image.BufferedImage mask = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            long total = 0;
            long any = 0;
            long small = 0;
            long large = 0;
            int max = 0;
            int tilesX = (w + TILE - 1) / TILE;
            long[] tiles = new long[tilesX * ((h + TILE - 1) / TILE)];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int pa = imageA.getRGB(x, y);
                    int pb = imageB.getRGB(x, y);
                    int d = 0;
                    for (int shift = 0; shift <= 16; shift += 8) {
                        d = Math.max(d, Math.abs(((pa >> shift) & 0xFF) - ((pb >> shift) & 0xFF)));
                    }
                    total++;
                    max = Math.max(max, d);
                    tiles[(y / TILE) * tilesX + x / TILE] += d;
                    if (d > 0) any++;
                    if (d > 24) small++;
                    if (d > 64) large++;
                    mask.setRGB(x, y, d == 0 ? 0 : d > 64 ? 0xFF0000 : d > 24 ? 0xFFFF00 : 0x606060);
                }
            }
            if (any > 0) {
                javax.imageio.ImageIO.write(mask, "png", a.resolveSibling("terrain-cull-" + name + "-diff.png").toFile());
            }
            long maxTile = 0;
            long sum = 0;
            for (long t : tiles) {
                maxTile = Math.max(maxTile, t);
                sum += t;
            }
            double n = Math.max(1, total);
            // Above the image-wide mean: a uniform shift (in-block overlay fade underground) is not a missing block.
            double tile = Math.max(0, maxTile / (double) (TILE * TILE) - sum / n);
            return new double[] {small / n, large / n, any / n, max, tile};
        } catch (IOException e) {
            failures.add("terrain-cull: could not compare screenshots: " + e);
            return new double[] {1, 1, 1, 255, 255};
        }
    }

    /**
     * Tile size for the coherent-difference measure: a missing section or block covers whole tiles (high mean
     * difference), a pack's temporal noise is scattered single pixels (low mean per tile).
     */
    private static final int TILE = 8;
}
