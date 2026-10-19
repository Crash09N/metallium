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

    private static Object field(final Object target, final String name) throws ReflectiveOperationException {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name) || f.getName().endsWith("$" + name)) {
                    f.setAccessible(true);
                    return f.get(target);
                }
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object call(final Object target, final String name) throws ReflectiveOperationException {
        java.lang.reflect.Method m = target.getClass().getMethod(name);
        m.setAccessible(true);
        return m.invoke(target);
    }

    /** Liquid compute output: particle count and the indirect draw arguments the compute shaders wrote. */
    private void liquidProbe(final ClientGameTestContext context) {
        line.accept("physics: liquid probe " + context.computeOnClient(client -> {
            try {
                Object mod = Class.forName("net.diebuddies.physics.PhysicsMod")
                        .getMethod("getInstance", net.minecraft.client.multiplayer.ClientLevel.class).invoke(null, client.level);
                Object system = call(call(mod, "getPhysicsWorld"), "getLiquidComputeSystem");
                if (system == null) return "no liquid system";
                Object indirect = call(system, "indirectDrawBuffer");
                return "hasParticles=" + call(system, "hasParticles") + " renderable=" + call(system, "hasRenderableParticles")
                        + " arena=" + call(system, "particleArenaSize") + " indirectArgs="
                        + (indirect == null ? "null" : java.util.Arrays.toString(GpuProbe.ints((com.mojang.renderpearl.api.buffers.GpuBuffer) indirect, 5)));
            } catch (Throwable t) {
                return "failed: " + (t.getCause() != null ? t.getCause() : t);
            }
        }));
    }

    private static Object mainRenderer(final net.minecraft.client.Minecraft client) throws ReflectiveOperationException {
        return field(client.levelRenderer, "mainRenderer");
    }

    /** Volumetric smoke: how much the compute passes wrote into the density volumes. */
    private void smokeProbe(final ClientGameTestContext context) {
        line.accept("physics: smoke probe " + context.computeOnClient(client -> {
            try {
                Object volume = field(field(mainRenderer(client), "smokeVolumeRenderer"), "smokeVolume");
                if (volume == null) return "no volume effect";
                Object targets = field(volume, "volumeTargets");
                if (targets == null) return "no volume targets";
                StringBuilder out = new StringBuilder();
                for (String name : new String[] {"densityAccTextures", "densityTextures"}) {
                    Object[] textures = (Object[]) field(targets, name);
                    com.mojang.renderpearl.api.textures.GpuTexture t = (com.mojang.renderpearl.api.textures.GpuTexture) textures[0];
                    long nonZero = 0;
                    long sum = 0;
                    for (int z = 0; z < t.getDepthOrLayers(); z += Math.max(1, t.getDepthOrLayers() / 8)) {
                        long[] stats = GpuProbe.textureStats(t, z);
                        nonZero += stats[0];
                        sum += stats[1];
                    }
                    out.append(name).append("[0] ").append(t.getFormat()).append(' ').append(t.getWidth(0)).append('x').append(t.getHeight(0))
                            .append('x').append(t.getDepthOrLayers()).append(": nonzero=").append(nonZero).append(" sum=").append(sum).append("; ");
                }
                return out.toString();
            } catch (Throwable t) {
                return "failed: " + (t.getCause() != null ? t.getCause() : t);
            }
        }));
    }

    /** FFT ocean renderer state (is it running, did it disable itself). */
    private void oceanProbe(final ClientGameTestContext context) {
        line.accept("physics: ocean probe " + context.computeOnClient(client -> {
            try {
                Object fft = field(mainRenderer(client), "fftOceanRenderer");
                if (fft == null) return "no FFT ocean renderer";
                Object physicsMod = Class.forName("net.diebuddies.physics.PhysicsMod")
                        .getMethod("getInstance", net.minecraft.client.multiplayer.ClientLevel.class).invoke(null, client.level);
                Object oceanWorld = null;
                try {
                    oceanWorld = call(call(physicsMod, "getPhysicsWorld"), "getOceanWorld");
                } catch (ReflectiveOperationException ignored) {
                    // name differs
                }
                return "simulation=" + field(fft, "simulation") + " unavailable=" + field(fft, "unavailable") + " lastFrame="
                        + field(fft, "lastSimulationFrame") + " time=" + field(fft, "simulationTime") + " oceanWorld=" + oceanWorld;
            } catch (Throwable t) {
                return "failed: " + (t.getCause() != null ? t.getCause() : t);
            }
        }));
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
        liquidProbe(context);
        // Diagnostic: Physics Mod 3.2.5 never calls LiquidComputeSystem.prepareRender (the only writer of the indirect
        // draw arguments). Call it once here to see whether the rest of the GPU liquid pipeline renders on Metal.
        line.accept("physics: liquid prepareRender (diagnostic) " + context.computeOnClient(client -> {
            try {
                Object mod = Class.forName("net.diebuddies.physics.PhysicsMod")
                        .getMethod("getInstance", net.minecraft.client.multiplayer.ClientLevel.class).invoke(null, client.level);
                Object system = call(call(mod, "getPhysicsWorld"), "getLiquidComputeSystem");
                int indexCount = (int) call(field(field(mainRenderer(client), "liquidDeferredRenderer"), "instancedRenderer"), "getIndexCount");
                system.getClass().getMethod("prepareRender", int.class).invoke(system, indexCount);
                return "indexCount=" + indexCount;
            } catch (Throwable t) {
                return "failed: " + (t.getCause() != null ? t.getCause() : t);
            }
        }));
        context.waitTicks(3);
        liquidProbe(context);
        context.takeScreenshot("physics-" + tag + "-liquid-prepared");
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

    /**
     * FFT ocean, volumetric smoke: whether Metallium makes them available, and whether this Physics Mod build can show
     * them at all (its free build compiles some feature gates to a constant {@code false}).
     */
    private void oceanScene(final ClientGameTestContext context, final String tag) {
        setConfig("oceanPhysics", true);
        setConfig("oceanRenderer", 1);
        line.accept("physics: FFT ocean availability (null = available)=" + context.computeOnClient(c ->
                String.valueOf(callStatic("net.diebuddies.render.fft.FftOceanRenderer", "fftAvailabilityReason"))));
        line.accept("physics: mod gates: areVolumetricSmokePhysicsEnabled=" + callStatic("net.diebuddies.config.ConfigClient", "areVolumetricSmokePhysicsEnabled")
                + " areSmokePhysicsEnabled=" + callStatic("net.diebuddies.config.ConfigClient", "areSmokePhysicsEnabled")
                + " areFireVfxEnabled=" + callStatic("net.diebuddies.config.ConfigClient", "areFireVfxEnabled")
                + " areOceanPhysicsEnabled=" + callStatic("net.diebuddies.config.ConfigClient", "areOceanPhysicsEnabled"));
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
