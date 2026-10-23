/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Enchantment glint drawn in the entity/item pass ({@code ./gradlew :runClientGameTest -PwithIris -Pshaderpack=...
 * -PglintTest}). MC 26.3 draws glint in the same pipeline as the item/armor ({@code *_GLINT}); packs like Photon 1.4
 * sample Iris's {@code glintTexture} there ({@code mc_sampleGlint()}). A white texture in its place painted enchanted
 * armor and items solid white.
 * <p>Scene: a green concrete wall, two armor stands in full diamond armor facing the camera: the left one enchanted
 * (glint override on every piece and its sword), the right one plain. Item frames above them hold an enchanted
 * pickaxe (item glint) and an enchanted compass (special glint) on the left, the same items plain on the right. A
 * second shot adds an enchanted sword in the first-person hand (hand glint keys).
 * <p>Check: near-white pixels (all channels &gt;= 240) and near-black pixels (all &lt;= 8) in the left (enchanted) half
 * must not clearly exceed the right (plain) half; the hand shot's lower-right quadrant must not gain many white pixels.
 */
final class GlintScenario {
    private static final int WHITE = 240;
    private static final int BLACK = 8;

    private final Consumer<String> line;
    private final List<String> failures;
    private final Function<ClientGameTestContext, String> packInstaller;
    private ClientGameTestContext context;
    private TestSingleplayerContext world;

    GlintScenario(final Consumer<String> line, final List<String> failures, final Function<ClientGameTestContext, String> packInstaller) {
        this.line = line;
        this.failures = failures;
        this.packInstaller = packInstaller;
    }

    void run(final ClientGameTestContext context) {
        this.context = context;
        boolean shaders = !System.getProperty("nativium.gametest.shaderpack", "").isEmpty();
        context.runOnClient(client -> {
            client.options.bobView().set(false);
            client.options.glintSpeed().set(0.0);
            client.options.particles().set(net.minecraft.server.level.ParticleStatus.MINIMAL);
            client.options.fov().set(70);
        });
        try (TestSingleplayerContext w = context.worldBuilder().create()) {
            this.world = w;
            w.getConnection().waitForChunksRender();
            for (String c : new String[] {"gamerule advance_time false", "gamerule advance_weather false", "gamerule spawn_mobs false",
                    "gamerule spawn_monsters false", "gamerule send_command_feedback false", "time set 6000", "weather clear", "gamemode creative @a"}) {
                cmd(c);
            }
            if (shaders) {
                String pack = packInstaller.apply(context);
                context.waitTicks(100);
                line.accept("glint: shader pack " + pack);
            }
            cmd("fill -6 -60 4 6 -54 4 minecraft:green_concrete");
            cmd("summon minecraft:armor_stand -0.9 -60 2.5 {Rotation:[180f,0f],ShowArms:1b,NoGravity:1b,equipment:{"
                    + armor("head", "diamond_helmet", true) + "," + armor("chest", "diamond_chestplate", true) + ","
                    + armor("legs", "diamond_leggings", true) + "," + armor("feet", "diamond_boots", true) + ","
                    + armor("mainhand", "diamond_sword", true) + "}}");
            cmd("summon minecraft:armor_stand 0.9 -60 2.5 {Rotation:[180f,0f],ShowArms:1b,NoGravity:1b,equipment:{"
                    + armor("head", "diamond_helmet", false) + "," + armor("chest", "diamond_chestplate", false) + ","
                    + armor("legs", "diamond_leggings", false) + "," + armor("feet", "diamond_boots", false) + ","
                    + armor("mainhand", "diamond_sword", false) + "}}");
            frame(-1.5, "diamond_pickaxe", true);
            frame(-0.5, "compass", true);
            frame(0.5, "compass", false);
            frame(1.5, "diamond_pickaxe", false);
            cmd("item replace entity @p weapon.mainhand with minecraft:air");
            // HUD stays on: F1 would hide the first-person hand as well (creative: no hearts; the crosshair and the
            // hotbar are the same in both shots and halves within the floors below).
            camera(0.0, -59.0, -1.2, 0f, 5f);
            Path stands = shot("glint-stands", 60);
            cmd("item replace entity @p weapon.mainhand with minecraft:diamond_sword[enchantment_glint_override=true]");
            Path hand = shot("glint-hand", 20);
            check(stands, hand);
            cmd("tick unfreeze");
        }
    }

    private static String armor(final String slot, final String item, final boolean glint) {
        return slot + ":{id:\"minecraft:" + item + "\"" + (glint ? ",components:{\"minecraft:enchantment_glint_override\":true}" : "") + "}";
    }

    private void frame(final double x, final String item, final boolean glint) {
        cmd(String.format(Locale.ROOT, "summon minecraft:item_frame %.1f -57.5 3.5 {Facing:2b,Fixed:1b,Item:{id:\"minecraft:%s\",count:1%s}}",
                x, item, glint ? ",components:{\"minecraft:enchantment_glint_override\":true}" : ""));
    }

    private void camera(final double x, final double y, final double z, final float yaw, final float pitch) {
        context.runOnClient(client -> {
            client.player.getAbilities().flying = true;
            client.player.onUpdateAbilities();
        });
        cmd(String.format(Locale.ROOT, "tp @p %.3f %.3f %.3f %.2f %.2f", x, y, z, yaw, pitch));
        context.waitTicks(2);
        context.runOnClient(client -> {
            client.player.getAbilities().flying = true;
            client.player.setDeltaMovement(Vec3.ZERO);
            client.player.snapTo(x, y, z, yaw, pitch);
        });
    }

    private Path shot(final String name, final int settleTicks) {
        cmd("tick freeze");
        context.waitTicks(settleTicks);
        context.runOnClient(client -> {
            client.gui.hud.getChat().clearMessages(false);
            client.gui.toastManager().clear();
            client.player.setDeltaMovement(Vec3.ZERO);
        });
        context.waitTick();
        return context.takeScreenshot(name);
    }

    /** Counts near-white and near-black pixels in {@code [x0,x1) x [y0,y1)} (fractions of the image size). */
    private static int[] count(final BufferedImage image, final double x0, final double x1, final double y0, final double y1) {
        int w = image.getWidth();
        int h = image.getHeight();
        int white = 0;
        int black = 0;
        for (int y = (int) (y0 * h); y < (int) (y1 * h); y++) {
            for (int x = (int) (x0 * w); x < (int) (x1 * w); x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r >= WHITE && g >= WHITE && b >= WHITE) white++;
                if (r <= BLACK && g <= BLACK && b <= BLACK) black++;
            }
        }
        return new int[] {white, black, Math.max(1, ((int) (x1 * w) - (int) (x0 * w)) * ((int) (y1 * h) - (int) (y0 * h)))};
    }

    private void check(final Path stands, final Path hand) {
        try {
            BufferedImage a = javax.imageio.ImageIO.read(stands.toFile());
            BufferedImage b = javax.imageio.ImageIO.read(hand.toFile());
            // Upper 80 %: stands and frames (the hand shot adds the first-person sword at the bottom right).
            int[] left = count(a, 0.0, 0.5, 0.0, 0.8);
            int[] right = count(a, 0.5, 1.0, 0.0, 0.8);
            line.accept(String.format(Locale.ROOT, "glint: stands %dx%d enchanted(left) white=%d black=%d, plain(right) white=%d black=%d (of %d px per half)",
                    a.getWidth(), a.getHeight(), left[0], left[1], right[0], right[1], left[2]));
            int floor = left[2] / 200; // 0.5 % of the half
            if (left[0] > Math.max(3 * right[0], floor)) {
                failures.add("glint: enchanted armor/items mostly white (" + left[0] + " white px vs " + right[0] + " plain)");
            }
            if (left[1] > Math.max(3 * right[1], floor)) {
                failures.add("glint: enchanted armor/items have black patches (" + left[1] + " black px vs " + right[1] + " plain)");
            }
            int[] before = count(a, 0.5, 1.0, 0.5, 1.0);
            int[] after = count(b, 0.5, 1.0, 0.5, 1.0);
            line.accept(String.format(Locale.ROOT, "glint: hand quadrant white %d -> %d, black %d -> %d (of %d px)",
                    before[0], after[0], before[1], after[1], after[2]));
            int handFloor = after[2] / 100; // 1 % of the quadrant
            if (after[0] - before[0] > handFloor) {
                failures.add("glint: enchanted sword in hand mostly white (+" + (after[0] - before[0]) + " white px)");
            }
            if (after[1] - before[1] > handFloor) {
                failures.add("glint: enchanted sword in hand has black patches (+" + (after[1] - before[1]) + " black px)");
            }
        } catch (Exception e) {
            failures.add("glint: could not read screenshots: " + e);
        }
    }

    private void cmd(final String command) {
        try {
            world.getServer().runCommand(command);
        } catch (Throwable t) {
            failures.add("glint: command failed: /" + command + " -> " + t.getMessage());
        }
    }
}
