/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.gametest;

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
 */
final class TerrainCullScenario {
    private final Consumer<String> line;
    private final List<String> failures;
    private final java.util.function.Function<ClientGameTestContext, String> packInstaller;

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
                client.options.renderDistance().set(12);
                client.options.broadcastOptions();
            });
            world.getServer().runOnServer(server -> server.getPlayerList().setViewDistance(12));
            if (shaders) {
                String pack = packInstaller.apply(context);
                context.waitTicks(100);
                line.accept("terrain-cull: shader pack " + pack);
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

            compare(context, world, "wall", ground, 0, Float.NaN, false, shaders);
            compare(context, world, "behind", ground, 180, 0, false, shaders);    // settle facing the wall, turn round, shoot
            compare(context, world, "turnback", ground, 0, 180, false, shaders);  // and back: old results for the wall view
            compare(context, world, "open", high, 90, Float.NaN, false, shaders);
            compare(context, world, "under", under, 90, 270, false, shaders);      // inside the ground (caves, solid rock)
            // Stress: phase 1 draws nothing; everything visible must come from the GPU-decided phase-2 draws.
            compare(context, world, "deferall-wall", ground, 0, Float.NaN, true, shaders);
            compare(context, world, "deferall-open", high, 90, Float.NaN, true, shaders);
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

    /**
     * Screenshot at {@code pos} facing {@code yaw} with culling on, then off, compared. {@code fromYaw} (not NaN): with
     * culling on, settle facing {@code fromYaw} first and turn to {@code yaw} directly before the screenshot (sections
     * that were off screen / hidden in the latest results must be drawn in that very frame).
     */
    private void compare(final ClientGameTestContext context, final TestSingleplayerContext world, final String name,
                         final String pos, final float yaw, final float fromYaw, final boolean deferAll, final boolean shaders) {
        boolean turn = !Float.isNaN(fromYaw);
        context.runOnClient(client -> {
            TerrainOcclusion.setMode("on");
            TerrainOcclusion.deferAll = deferAll;
            // Keep results across the slow game-test frames, so a turn defers sections with old results (worst case).
            TerrainOcclusion.maxResultAge = turn ? 1_000_000 : 30;
        });
        world.getServer().runCommand("tp @a " + pos + " " + (turn ? fromYaw : yaw) + " 0");
        context.waitTicks(40);
        look(context, turn ? fromYaw : yaw);
        context.waitTicks(60);
        context.computeOnClient(client -> TerrainOcclusion.snapshot()); // reset counters
        look(context, yaw);
        Path on = context.takeScreenshot("terrain-cull-" + name + "-on");
        context.waitTicks(20);
        String counters = context.computeOnClient(client -> TerrainOcclusion.snapshot());
        line.accept("terrain-cull: " + name + " culling on: " + counters);
        context.runOnClient(client -> {
            TerrainOcclusion.setMode("off");
            TerrainOcclusion.deferAll = false;
            TerrainOcclusion.maxResultAge = 30;
        });
        context.waitTicks(40);
        look(context, yaw);
        context.waitTicks(5);
        Path off = context.takeScreenshot("terrain-cull-" + name + "-off");

        double[] diff = difference(on, off);
        line.accept(String.format(java.util.Locale.ROOT, "terrain-cull: %s on vs off: differing pixels %.4f%% (tol 24), %.4f%% (tol 64)",
                name, diff[0] * 100, diff[1] * 100));
        // Shaders: temporal effects (TAA, clouds) differ between two frames; judge the large differences only.
        // Missing sections show as large differences; small ones (sky shading, animated textures) are reported only.
        double limit = shaders ? 0.02 : 0.0005;
        double measured = diff[1];
        if (measured > limit) {
            failures.add(String.format(java.util.Locale.ROOT, "terrain-cull %s: culling changed %.3f%% of the pixels", name, measured * 100));
        }
        if (deferAll && counters.contains("phase2 drawn=0 ")) {
            failures.add("terrain-cull " + name + ": phase 2 drew nothing although every section was deferred (" + counters + ")");
        }
        if (!counters.contains("deferred draws=")) {
            failures.add("terrain-cull " + name + ": no culling counters (" + counters + ")");
        }
    }

    /** Fractions of pixels whose largest channel difference exceeds 24 and 64. */
    private double[] difference(final Path a, final Path b) {
        try {
            java.awt.image.BufferedImage imageA = javax.imageio.ImageIO.read(a.toFile());
            java.awt.image.BufferedImage imageB = javax.imageio.ImageIO.read(b.toFile());
            int w = Math.min(imageA.getWidth(), imageB.getWidth());
            int h = Math.min(imageA.getHeight(), imageB.getHeight());
            long total = 0;
            long small = 0;
            long large = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int pa = imageA.getRGB(x, y);
                    int pb = imageB.getRGB(x, y);
                    int d = 0;
                    for (int shift = 0; shift <= 16; shift += 8) {
                        d = Math.max(d, Math.abs(((pa >> shift) & 0xFF) - ((pb >> shift) & 0xFF)));
                    }
                    total++;
                    if (d > 24) small++;
                    if (d > 64) large++;
                }
            }
            return new double[] {(double) small / Math.max(1, total), (double) large / Math.max(1, total)};
        } catch (IOException e) {
            failures.add("terrain-cull: could not compare screenshots: " + e);
            return new double[] {1, 1};
        }
    }
}
