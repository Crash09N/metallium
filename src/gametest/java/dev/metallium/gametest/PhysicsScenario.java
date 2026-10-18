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
