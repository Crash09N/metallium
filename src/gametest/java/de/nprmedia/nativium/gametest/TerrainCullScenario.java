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
            client.options.renderDistance().set(12);
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
            context.runOnClient(client -> client.options.broadcastOptions());
            if (shaders) {
                String pack = packInstaller.apply(context);
                context.waitTicks(100);
                line.accept("terrain-cull: shader pack " + pack);
            }
            int surface = world.getServer().computeOnServer(server -> server.overworld().getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0));
            // Wall 6 blocks south of the camera, top 1.4 blocks above eye level, 61 blocks wide.
            world.getServer().runCommand(String.format("fill -30 %d 6 30 %d 6 minecraft:stone", surface - 6, surface + 2));
            world.getServer().runCommand(String.format("tp @a 0.5 %d 0.5 0 0", surface));
            world.getConnection().waitForChunksRender();
            context.waitTicks(200);
            String wall = String.format("0.5 %d 0.5 0 0", surface);
            String behind = String.format("0.5 %d 0.5 180 0", surface);
            String open = String.format("0.5 %d 0.5 90 0", surface + 6);

            compare(context, world, "wall", wall, null, shaders);
            compare(context, world, "behind", behind, wall, shaders);
            compare(context, world, "open", open, null, shaders);
        }
        context.runOnClient(client -> TerrainOcclusion.setMode(System.getProperty("nativium.terrain.gpuCull", "off")));
    }

    /**
     * Screenshot of {@code camera} with culling on, then off, compared. {@code from}: with culling on, settle at this
     * view first and switch to {@code camera} directly before the screenshot (newly visible sections).
     */
    private void compare(final ClientGameTestContext context, final TestSingleplayerContext world, final String name,
                         final String camera, final String from, final boolean shaders) {
        context.runOnClient(client -> TerrainOcclusion.setMode("on"));
        world.getServer().runCommand("tp @a " + (from != null ? from : camera));
        context.waitTicks(60);
        context.computeOnClient(client -> TerrainOcclusion.snapshot()); // reset counters
        if (from != null) {
            world.getServer().runCommand("tp @a " + camera);
        }
        Path on = context.takeScreenshot("terrain-cull-" + name + "-on");
        context.waitTicks(20);
        String counters = context.computeOnClient(client -> TerrainOcclusion.snapshot());
        line.accept("terrain-cull: " + name + " culling on: " + counters);
        context.runOnClient(client -> TerrainOcclusion.setMode("off"));
        world.getServer().runCommand("tp @a " + camera);
        context.waitTicks(40);
        Path off = context.takeScreenshot("terrain-cull-" + name + "-off");

        double[] diff = difference(on, off);
        line.accept(String.format(java.util.Locale.ROOT, "terrain-cull: %s on vs off: differing pixels %.4f%% (tol 24), %.4f%% (tol 64)",
                name, diff[0] * 100, diff[1] * 100));
        // Shaders: temporal effects (TAA, clouds) differ between two frames; judge the large differences only.
        double limit = shaders ? 0.02 : 0.002;
        double measured = shaders ? diff[1] : diff[0];
        if (measured > limit) {
            failures.add(String.format(java.util.Locale.ROOT, "terrain-cull %s: culling changed %.3f%% of the pixels", name, measured * 100));
        }
        if (from == null && !counters.contains("deferred draws=") ) {
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
