package dev.metallium.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.util.List;
import java.util.function.Consumer;

/**
 * Physics Mod scenario ({@code ./gradlew :runClientGameTest -PwithPhysics=/abs/physics-mod.jar}): Physics Mod is closed
 * source; the jar is only loaded at test time and never committed. Checks Metallium's handling of it:
 * <ul>
 *   <li>if Metallium asks "needs OpenGL" ({@code NeedsOpenGlScreen}, no compatibility for this version): the session is
 *       on OpenGL and the screen is shown before the title screen; "run this session on OpenGL" continues;</li>
 *   <li>otherwise the game must run on Metal.</li>
 * </ul>
 * Then, on whichever backend runs: blocks are broken ({@code fill ... air destroy}: Physics Mod turns them into physics
 * debris) and mobs are killed (ragdolls), with screenshots. The screenshots must be looked at.
 */
final class PhysicsScenario {
    private final Consumer<String> line;
    private final List<String> failures;

    PhysicsScenario(final Consumer<String> line, final List<String> failures) {
        this.line = line;
        this.failures = failures;
    }

    void run(final ClientGameTestContext context, final String backend) {
        dev.metallium.compat.OpenGlRequirements.Outcome outcome = dev.metallium.compat.OpenGlRequirements.outcome();
        line.accept("physics: requirements=" + outcome.requirements().stream().map(r -> r.id() + " " + r.version() + " (" + r.reason() + ")").toList()
                + " offerMetal=" + outcome.offerMetal() + " reason=" + outcome.reason());
        String screen = context.computeOnClient(client -> String.valueOf(client.gui.screen()));
        line.accept("physics: first screen=" + screen);
        if (outcome.ask()) {
            context.takeScreenshot("physics-needs-opengl-screen");
            if (!backend.startsWith("OpenGL")) failures.add("physics: asked about OpenGL but the backend is " + backend);
            if (!screen.contains("NeedsOpenGlScreen")) failures.add("physics: NeedsOpenGlScreen not shown before the title screen");
            context.runOnClient(client -> client.gui.screen().onClose()); // "Run this session on OpenGL (ask again)"
            context.waitTicks(10);
            String after = context.computeOnClient(client -> String.valueOf(client.gui.screen()));
            line.accept("physics: after 'run this session on OpenGL': " + after);
            // The title screen, or Physics Mod's own first-start QuickSetupScreen on top of it.
            if (after.contains("NeedsOpenGlScreen")) failures.add("physics: the needs-OpenGL screen did not close");
        } else if (!backend.startsWith("Metal")) {
            failures.add("physics: Metallium did not ask, but the backend is " + backend);
        }
        closeForeignScreens(context);
        try {
            line.accept("physics: compat status:\n" + dev.metallium.compat.CompatStatus.text());
        } catch (Throwable t) {
            line.accept("physics: compat status unavailable: " + t);
        }
        String tag = context.computeOnClient(client -> RenderSystem.getDevice().getDeviceInfo().backendName()).toLowerCase(java.util.Locale.ROOT);
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            closeForeignScreens(context);
            for (String command : new String[] {
                    "time set 6000", "gamerule advance_time false", "gamerule advance_weather false", "weather clear",
                    "gamerule spawn_monsters false", "gamemode creative @a", "tp @p 0.5 -60 0.5 0 15",
                    "fill -2 -60 5 2 -57 6 minecraft:bricks"}) {
                command(world, command);
            }
            context.waitTicks(80);
            context.takeScreenshot("physics-" + tag + "-wall");
            command(world, "fill -2 -60 5 2 -57 6 minecraft:air destroy");
            context.waitTicks(4);
            context.takeScreenshot("physics-" + tag + "-blocks-breaking");
            context.waitTicks(30);
            context.takeScreenshot("physics-" + tag + "-blocks-debris");
            command(world, "kill @e[type=item]");
            command(world, "summon minecraft:zombie 0.5 -60 4.5 {NoAI:1b,Rotation:[180f,0f]}");
            command(world, "summon minecraft:cow -1.5 -60 5.5 {NoAI:1b}");
            command(world, "summon minecraft:pig 2.5 -60 5.5 {NoAI:1b}");
            context.waitTicks(40);
            context.takeScreenshot("physics-" + tag + "-mobs");
            command(world, "kill @e[type=!player]");
            context.waitTicks(6);
            context.takeScreenshot("physics-" + tag + "-ragdolls");
            context.waitTicks(30);
            context.takeScreenshot("physics-" + tag + "-ragdolls-later");
            command(world, "summon minecraft:tnt 0.5 -60 6.5 {fuse:1}");
            context.waitTicks(8);
            context.takeScreenshot("physics-" + tag + "-explosion");
            context.waitTicks(40);
            context.takeScreenshot("physics-" + tag + "-explosion-later");
            line.accept("physics: world scenario done on " + backend);
            gpuFeatures(context, world, tag);
        }
        oceanScene(context, tag);
    }

    /**
     * Average FPS over {@code seconds} (Minecraft's FPS counter, sampled once per second), uncapped and without VSync.
     * Only meaningful with the GPU to itself ({@code gpu-lock.sh --exclusive}).
     */
    private double fps(final ClientGameTestContext context, final int seconds) {
        context.runOnClient(client -> {
            client.options.enableVsync().set(false);
            client.options.framerateLimit().set(260);
            client.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
        });
        context.waitTicks(20);
        double sum = 0;
        for (int i = 0; i < seconds; i++) {
            context.waitTicks(20);
            sum += context.computeOnClient(client -> client.getFps());
        }
        return sum / seconds;
    }

    /** FPS of the current scene with Physics Mod's GPU feature {@code field} on and off. */
    private void benchFeature(final ClientGameTestContext context, final String scene, final String field) {
        if (!Boolean.getBoolean("metallium.gametest.physicsBench")) return;
        double on = fps(context, 8);
        setConfig(field, field.equals("oceanRenderer") ? (Object) 0 : (Object) false);
        context.waitTicks(40);
        double off = fps(context, 8);
        setConfig(field, field.equals("oceanRenderer") ? (Object) 1 : (Object) true);
        context.waitTicks(40);
        line.accept(String.format("physics: bench %s: %s on %.0f fps, off %.0f fps", scene, field, on, off));
    }

    /** Metallium's status lines (metallium-gl dispatch / draw counters) after a scene. */
    private void status(final ClientGameTestContext context, final String scene) {
        line.accept("physics: status after " + scene + ": " + context.computeOnClient(c -> String.join(" | ", dev.metallium.api.MetalliumRuntime.statusLines())));
    }

    /** Physics Mod's static config field (closed source: reached by name, test code only). */
    private void setConfig(final String field, final Object value) {
        try {
            Class.forName("net.diebuddies.config.ConfigClient").getField(field).set(null, value);
        } catch (ReflectiveOperationException e) {
            line.accept("physics: could not set ConfigClient." + field + ": " + e);
        }
    }

    private Object callStatic(final String type, final String method) {
        try {
            java.lang.reflect.Method m = Class.forName(type).getDeclaredMethod(method);
            m.setAccessible(true);
            return m.invoke(null);
        } catch (ReflectiveOperationException e) {
            return "error: " + (e.getCause() != null ? e.getCause() : e);
        }
    }

    /** GPU liquids (a liquid source, as with Physics Mod's stick tool) and volumetric campfire smoke. */
    private void gpuFeatures(final ClientGameTestContext context, final TestSingleplayerContext world, final String tag) {
        setConfig("liquidPhysics", true);
        setConfig("smokePhysics", true);
        setConfig("smokeVolumetricPhysics", true);
        line.accept("physics: liquid compute availability=" + context.computeOnClient(c ->
                String.valueOf(callStatic("net.diebuddies.physics.liquid.compute.LiquidComputeSupport", "availability"))));
        line.accept("physics: volumetric smoke supported=" + context.computeOnClient(c ->
                String.valueOf(callStatic("net.diebuddies.render.SmokeVolumeRenderer", "supportsVolumetricSmoke"))));
        command(world, "kill @e[type=!player]");
        command(world, "fill 24 -61 3 36 -50 12 minecraft:air");
        command(world, "fill 24 -61 3 36 -61 12 minecraft:grass_block");
        command(world, "tp @p 30.5 -60 0.5 0 10");
        context.waitTicks(40);
        // Liquid source a few blocks up, in front of the player (what Physics Mod's stick tool does on right-click).
        String toggled = context.computeOnClient(client -> {
            try {
                Object mod = Class.forName("net.diebuddies.physics.PhysicsMod")
                        .getMethod("getInstance", net.minecraft.client.multiplayer.ClientLevel.class).invoke(null, client.level);
                Object physicsWorld = mod.getClass().getMethod("getPhysicsWorld").invoke(mod);
                physicsWorld.getClass().getMethod("toggleLiquidSource", net.minecraft.core.BlockPos.class)
                        .invoke(physicsWorld, new net.minecraft.core.BlockPos(30, -57, 6));
                return "ok";
            } catch (ReflectiveOperationException e) {
                return "failed: " + (e.getCause() != null ? e.getCause() : e);
            }
        });
        line.accept("physics: liquid source " + toggled);
        context.waitTicks(40);
        context.takeScreenshot("physics-" + tag + "-liquid");
        context.waitTicks(100);
        context.takeScreenshot("physics-" + tag + "-liquid-later");
        status(context, "liquid");
        benchFeature(context, "liquid", "liquidPhysics");
        command(world, "setblock 28 -60 8 minecraft:campfire");
        command(world, "setblock 32 -60 8 minecraft:campfire");
        context.waitTicks(160);
        context.takeScreenshot("physics-" + tag + "-smoke");
        command(world, "setblock 30 -60 7 minecraft:fire");
        context.waitTicks(60);
        context.takeScreenshot("physics-" + tag + "-smoke-fire");
        status(context, "smoke");
        benchFeature(context, "smoke", "smokeVolumetricPhysics");
    }

    /** FFT ocean: a natural world, above the nearest ocean. */
    private void oceanScene(final ClientGameTestContext context, final String tag) {
        setConfig("oceanPhysics", true);
        setConfig("oceanRenderer", 1);
        line.accept("physics: FFT ocean availability (null = available)=" + context.computeOnClient(c ->
                String.valueOf(callStatic("net.diebuddies.render.fft.FftOceanRenderer", "fftAvailabilityReason"))));
        try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(false)
                .adjustSettings(state -> state.setSeed(System.getProperty("metallium.gametest.oceanSeed", "metallium-forest"))).create()) {
            world.getConnection().waitForChunksRender();
            closeForeignScreens(context);
            net.minecraft.core.BlockPos ocean = world.getServer().computeOnServer(server -> {
                var found = server.overworld().findClosestBiome3d(b -> b.is(net.minecraft.tags.BiomeTags.IS_OCEAN),
                        net.minecraft.core.BlockPos.ZERO, 6400, 32, 64);
                return found == null ? null : found.getFirst();
            });
            line.accept("physics: nearest ocean " + ocean);
            if (ocean == null) return;
            for (String command : new String[] {"time set 6000", "gamerule advance_time false", "weather clear", "gamemode spectator @a",
                    "tp @p " + ocean.getX() + " 72 " + ocean.getZ() + " 0 25"}) {
                command(world, command);
            }
            context.waitTicks(20);
            world.getConnection().waitForChunksRender();
            context.waitTicks(200);
            context.takeScreenshot("physics-" + tag + "-ocean");
            context.waitTicks(40);
            context.takeScreenshot("physics-" + tag + "-ocean-later");
            command(world, "weather thunder");
            context.waitTicks(200);
            context.takeScreenshot("physics-" + tag + "-ocean-storm");
            status(context, "ocean");
            command(world, "weather clear");
            benchFeature(context, "ocean", "oceanRenderer");
            line.accept("physics: ocean scene done");
        }
    }

    private void command(final TestSingleplayerContext world, final String command) {
        try {
            world.getServer().runCommand(command);
        } catch (Throwable t) {
            line.accept("physics: command failed (ignored): /" + command + " -> " + t.getMessage());
        }
    }

    /** Closes screens opened by other mods (Physics Mod onboarding etc.), logging them. */
    private void closeForeignScreens(final ClientGameTestContext context) {
        for (int i = 0; i < 5; i++) {
            String name = context.computeOnClient(client -> {
                Screen s = client.gui.screen();
                if (s == null || s instanceof TitleScreen) return null;
                String n = s.getClass().getName();
                client.gui.setScreen(null); // QuickSetupScreen's onClose() reopens itself
                return n;
            });
            if (name == null) return;
            line.accept("physics: closed foreign screen " + name);
            context.waitTicks(5);
        }
    }
}
