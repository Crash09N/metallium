package de.nprmedia.nativium.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Real-instance stress test ({@code ./gradlew :runClientGameTest -Pinstance}, run directory {@code run-instance/} built by
 * {@code tools/make-run-instance.sh}): a fresh natural world with the instance's ~150 mods and resource packs, then
 * every shader pack in the instance's {@code shaderpacks/} folder (or {@code -Dnativium.gametest.instancePacks=a.zip,b.zip}).
 * Records screenshots, the compatibility-center status and per-scene frame times. It never fails on visuals: the
 * screenshots must be looked at. A scene that throws is reported and the next one runs.
 */
final class InstanceScenario {
    private static final String SEED = System.getProperty("nativium.gametest.seed", "nativium-forest");
    private static final int SETTLE_TICKS = Integer.getInteger("nativium.gametest.settleTicks", 200);
    private static final int MEASURE_SECONDS = Integer.getInteger("nativium.instance.seconds", 10);

    private final Consumer<String> line;
    private final Runnable onTimeout;

    /** @param onTimeout writes the report before the watchdog halts the JVM */
    InstanceScenario(final Consumer<String> line, final Runnable onTimeout) {
        this.line = line;
        this.onTimeout = onTimeout;
    }

    void run(final ClientGameTestContext context) {
        FabricLoader loader = FabricLoader.getInstance();
        Thread watchdog = watchdog();
        try {
            runScenes(context, loader);
        } finally {
            watchdog.interrupt();
        }
    }

    private void runScenes(final ClientGameTestContext context, final FabricLoader loader) {
        foreignScreens(context, "title screen", true);
        context.waitTicks(60); // the first title screenshot can catch the widgets' fade-in (labels still invisible)
        context.takeScreenshot("instance-title");
        line.accept("mode: instance, mods loaded=" + loader.getAllMods().size() + ", seed=" + SEED);
        context.runOnClient(client -> {
            line.accept("resource packs: " + client.getResourcePackRepository().getSelectedIds());
            // A benchmark-like setup for the FPS numbers: no VSync, no cap, no AFK limit.
            client.options.enableVsync().set(false);
            client.options.framerateLimit().set(260);
            client.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
            line.accept("render distance=" + client.options.renderDistance().get() + ", gui scale=" + client.options.guiScale().get());
        });
        try {
            line.accept("compat status:\n" + de.nprmedia.nativium.compat.CompatStatus.text());
        } catch (Throwable t) {
            line.accept("compat status unavailable: " + t);
        }

        try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(false)
                .adjustSettings(state -> state.setSeed(SEED)).create()) {
            world.getConnection().waitForChunksRender();
            foreignScreens(context, "world joined", false);
            command(world, "time set 1000");
            command(world, "gamerule advance_time false");
            command(world, "gamerule advance_weather false");
            command(world, "weather clear");
            command(world, "gamerule spawn_monsters false");
            command(world, "gamemode creative @a");
            command(world, "execute as @p at @s positioned over motion_blocking run tp @s ~ ~3 ~ ~ 25");
            // Entities and block entities for ETF/EMF, Fresh Animations, skin layers, chest/sign renderers.
            command(world, "execute as @p at @s run summon minecraft:cow ^ ^1 ^5 {NoAI:1b}");
            command(world, "execute as @p at @s run summon minecraft:villager ^-2 ^1 ^6 {NoAI:1b}");
            command(world, "execute as @p at @s run summon minecraft:zombie ^2 ^1 ^7 {NoAI:1b,PersistenceRequired:1b}");
            command(world, "execute as @p at @s positioned ^1 ^ ^4 positioned over motion_blocking run setblock ~ ~ ~ minecraft:chest");
            command(world, "execute as @p at @s positioned ^-1 ^ ^4 positioned over motion_blocking run setblock ~ ~ ~ minecraft:torch");
            command(world, "item replace entity @p weapon.mainhand with minecraft:diamond_sword");
            context.waitTicks(SETTLE_TICKS);
            foreignScreens(context, "world settled", false);
            scene(context, "vanilla", () -> {
                shot(context, "instance-natural");
                measure(context, "natural world (no shaders)");
                context.runOnClient(client -> client.debugEntries.setOverlayVisible(true));
                context.waitTicks(40); // BetterF3 slides its text in
                shot(context, "instance-f3");
                context.runOnClient(client -> client.debugEntries.setOverlayVisible(false));
                command(world, "weather rain");
                context.waitTicks(60);
                shot(context, "instance-rain");
                command(world, "weather clear");
                command(world, "time set 14000");
                context.waitTicks(40);
                shot(context, "instance-night");
                command(world, "time set 1000");
                context.waitTicks(20);
            });

            if (loader.isModLoaded("iris")) {
                for (String pack : packs(context)) {
                    String tag = pack.replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("\\.zip$", "");
                    scene(context, pack, () -> {
                        String pipeline = context.computeOnClient(client -> irisSelectPack(pack, true));
                        line.accept("pack " + pack + ": pipeline=" + pipeline);
                        context.waitTicks(SETTLE_TICKS);
                        foreignScreens(context, "pack " + pack, false);
                        shot(context, "instance-pack-" + tag);
                        measure(context, "pack " + pack);
                        command(world, "time set 14000");
                        context.waitTicks(40);
                        shot(context, "instance-pack-" + tag + "-night");
                        command(world, "time set 1000");
                    });
                }
                scene(context, "shaders off", () -> {
                    line.accept("shaders off: pipeline=" + context.computeOnClient(client -> irisSelectPack(null, false)));
                    context.waitTicks(60);
                    shot(context, "instance-shaders-off-again");
                });
            }
        }
        line.accept("result: DONE (look at the screenshots)");
    }

    /**
     * Onboarding / login / update / welcome screens of other mods must not stall the run: logs the screen and the mod
     * that owns its class, then closes it (back to the title screen before a world, no screen in a world).
     */
    private void foreignScreens(final ClientGameTestContext context, final String where, final boolean title) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String found = context.computeOnClient(client -> {
                net.minecraft.client.gui.screens.Screen screen = client.gui.screen();
                if (screen == null || title && screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
                    return null;
                }
                String description = screen.getClass().getName() + " (mod: " + ownerOf(screen.getClass()) + ")";
                client.setScreenAndShow(title ? new net.minecraft.client.gui.screens.TitleScreen() : null);
                return description;
            });
            if (found == null) {
                return;
            }
            line.accept("foreign screen at " + where + ": " + found + " -> closed");
            context.waitTicks(5);
        }
        line.accept("foreign screen at " + where + ": still open after 5 attempts");
    }

    private static String ownerOf(final Class<?> type) {
        try {
            var source = type.getProtectionDomain().getCodeSource();
            if (source == null) {
                return "?";
            }
            Path location = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
            for (var mod : FabricLoader.getInstance().getAllMods()) {
                for (Path root : mod.getOrigin().getPaths()) {
                    if (root.toAbsolutePath().normalize().equals(location)) {
                        return mod.getMetadata().getId();
                    }
                }
            }
            return location.getFileName().toString();
        } catch (Exception | Error e) {
            return "? (" + e + ")";
        }
    }

    /** Fails loudly instead of hanging: after nativium.instance.timeoutMinutes the report is written and the JVM halts. */
    private Thread watchdog() {
        long minutes = Long.getLong("nativium.instance.timeoutMinutes", 30);
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(minutes * 60_000);
            } catch (InterruptedException e) {
                return;
            }
            net.minecraft.client.gui.screens.Screen screen = Minecraft.getInstance().gui.screen();
            line.accept("TIMEOUT after " + minutes + " min; current screen: "
                    + (screen == null ? "none" : screen.getClass().getName() + " (mod: " + ownerOf(screen.getClass()) + ")"));
            line.accept("result: FAIL (timeout)");
            onTimeout.run();
            Runtime.getRuntime().halt(3);
        }, "nativium-instance-watchdog");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private interface Scene {
        void run() throws Exception;
    }

    private void scene(final ClientGameTestContext context, final String name, final Scene scene) {
        long start = System.nanoTime();
        try {
            scene.run();
            line.accept(String.format(Locale.ROOT, "scene %s: ok (%.0f s)", name, (System.nanoTime() - start) / 1e9));
        } catch (Throwable t) {
            line.accept("scene " + name + ": FAILED " + t);
            t.printStackTrace();
        }
    }

    private void shot(final ClientGameTestContext context, final String name) {
        context.runOnClient(client -> client.gui.hud.getChat().clearMessages(false));
        context.waitTick();
        context.takeScreenshot(name);
    }

    /** Frame times over MEASURE_SECONDS of wall-clock time (vanilla FPS counter sampled every second). */
    private void measure(final ClientGameTestContext context, final String scene) {
        List<Integer> fps = new ArrayList<>();
        long end = System.nanoTime() + MEASURE_SECONDS * 1_000_000_000L;
        while (System.nanoTime() < end) {
            context.waitTicks(20);
            fps.add(context.computeOnClient(Minecraft::getFps));
        }
        fps.sort(null);
        double avg = fps.stream().mapToInt(Integer::intValue).average().orElse(0);
        long frameNs = context.computeOnClient(Minecraft::getFrameTimeNs);
        line.accept(String.format(Locale.ROOT, "%s: fps avg=%.0f min=%d median=%d max=%d lastFrameMs=%.2f (samples=%d)", scene, avg,
                fps.isEmpty() ? 0 : fps.get(0), fps.isEmpty() ? 0 : fps.get(fps.size() / 2), fps.isEmpty() ? 0 : fps.get(fps.size() - 1),
                frameNs / 1e6, fps.size()));
    }

    private List<String> packs(final ClientGameTestContext context) {
        String list = System.getProperty("nativium.gametest.instancePacks", "");
        if (list.equals("-")) {
            return List.of(); // -PinstancePacks=- : no shader packs (e.g. an OpenGL/Metal FPS comparison)
        }
        if (!list.isBlank()) {
            return List.of(list.split(","));
        }
        Path dir = context.computeOnClient(client -> irisShaderpacksDirectory());
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".zip") || Files.isDirectory(dir.resolve(n).resolve("shaders")))
                    .filter(n -> !n.startsWith("nativium-"))
                    .sorted().toList();
        } catch (IOException e) {
            line.accept("could not list shader packs: " + e);
            return List.of();
        }
    }

    private void command(final TestSingleplayerContext world, final String command) {
        try {
            world.getServer().runCommand(command);
        } catch (Throwable t) {
            line.accept("command failed (ignored): /" + command + " -> " + t.getMessage());
        }
    }

    // Iris comes from the instance's mods/ folder, so it is driven by reflection.
    private static Path irisShaderpacksDirectory() {
        try {
            return (Path) Class.forName("net.irisshaders.iris.Iris").getMethod("getShaderpacksDirectory").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String irisSelectPack(final String pack, final boolean enabled) {
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Object config = iris.getMethod("getIrisConfig").invoke(null);
            if (pack != null) {
                config.getClass().getMethod("setShaderPackName", String.class).invoke(config, pack);
            }
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
}
