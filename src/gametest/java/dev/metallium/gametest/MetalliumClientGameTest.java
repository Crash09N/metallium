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

        String expected = System.getProperty("metallium.gametest.expectBackend", "");
        try {
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                world.getConnection().waitForChunksRender();
                // Sodium builds sections asynchronously after vanilla reports chunks as rendered; give it time.
                context.waitTicks(SETTLE_TICKS);
                checkTerrainVisible(context.takeScreenshot("metallium-flat-world"), "flat world");
                measure(context, "flat world");
            }

            try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(false).create()) {
                world.getConnection().waitForChunksRender();
                context.waitTicks(SETTLE_TICKS);
                checkTerrainVisible(context.takeScreenshot("metallium-natural-world"), "natural world");
                measure(context, "natural world");
            }
            if (!failures.isEmpty()) {
                throw new AssertionError(String.join("; ", failures));
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
