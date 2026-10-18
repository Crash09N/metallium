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
    private final List<String> report = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        String backend = context.computeOnClient(client -> {
            DeviceInfo info = RenderSystem.getDevice().getDeviceInfo();
            return info.backendName() + " | " + info.name() + " | " + info.driverInfo();
        });
        line("backend: " + backend);
        context.takeScreenshot("metallium-title");

        String expected = System.getProperty("metallium.gametest.expectBackend", "");
        try {
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                world.getConnection().waitForChunksRender();
                context.takeScreenshot("metallium-flat-world");
                measure(context, "flat world");
            }

            try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(false).create()) {
                world.getConnection().waitForChunksRender();
                context.waitTicks(40);
                context.takeScreenshot("metallium-natural-world");
                measure(context, "natural world");
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

    private void measure(ClientGameTestContext context, String scene) {
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
