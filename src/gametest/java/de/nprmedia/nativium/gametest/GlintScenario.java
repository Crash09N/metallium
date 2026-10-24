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
 * must not clearly exceed the right (plain) half; the hand shot's lower-right quadrant must not gain many white pixels
 * over a shot with a plain sword. Glint must also be visible: each frame is shot again with {@code glintStrength} 0,
 * and each enchanted region (stand, armor, framed items, sword in hand) must get measurably more purple (mean
 * {@code (dr + db) / 2 - dg}) with glint on, its plain twin (control) must not. Measured shift (2026-10-24, 854x480):
 * vanilla Metal = GL stand 2.6 / armor 1.9 / frames 2.8 / hand 27; Reimagined Metal = GL 1.6 / 1.1 / 2.1 / 12-15;
 * Photon 1.4 Metal 1.4 / 1.0 / 1.2 / 14; controls within 0.2. (Packs blend glint more weakly than vanilla; Photon
 * does not run on macOS OpenGL.) The pixel-change fraction is only logged: TAA in packs changes pixels everywhere.
 */
final class GlintScenario {
    private static final int WHITE = 240;
    private static final int BLACK = 8;
    /** Default {@code glintStrength}; the "off" shots use 0. */
    private static final double STRENGTH = 0.75;
    /** Per-pixel change (sum of |dr|+|dg|+|db|) that counts as "changed" between glint on and off. */
    private static final int CHANGED = 6;
    /** Minimum glint-on minus glint-off purple shift per enchanted region (see the class comment for measured values). */
    private static final double MIN_STAND = 0.5;
    private static final double MIN_ARMOR = 0.4;
    private static final double MIN_FRAMES = 0.5;
    private static final double MIN_HAND = 5.0;

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
            client.options.glintSpeed().set(Double.parseDouble(System.getProperty("nativium.gametest.glintSpeed", "0")));
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
            cmd("summon minecraft:armor_stand 0.9 -60 2.5 {Rotation:[180f,0f],ShowArms:1b,NoGravity:1b,equipment:{"
                    + armor("head", "diamond_helmet", true) + "," + armor("chest", "diamond_chestplate", true) + ","
                    + armor("legs", "diamond_leggings", true) + "," + armor("feet", "diamond_boots", true) + ","
                    + armor("mainhand", "diamond_sword", true) + "}}");
            cmd("summon minecraft:armor_stand -0.9 -60 2.5 {Rotation:[180f,0f],ShowArms:1b,NoGravity:1b,equipment:{"
                    + armor("head", "diamond_helmet", false) + "," + armor("chest", "diamond_chestplate", false) + ","
                    + armor("legs", "diamond_leggings", false) + "," + armor("feet", "diamond_boots", false) + ","
                    + armor("mainhand", "diamond_sword", false) + "}}");
            // The camera looks south (+Z): +X is on the left of the screen, so the enchanted side has positive X.
            frame(1.5, "diamond_pickaxe", true);
            frame(0.5, "compass", true);
            frame(-0.5, "compass", false);
            frame(-1.5, "diamond_pickaxe", false);
            cmd("item replace entity @p weapon.mainhand with minecraft:air");
            // HUD stays on: F1 would hide the first-person hand as well (creative: no hearts; the crosshair and the
            // hotbar are the same in both shots and halves within the floors below).
            camera(0.0, -59.0, -1.2, 0f, 5f);
            Path stands = shot("glint-stands", 60);
            glintStrength(0.0);
            Path standsOff = shot("glint-stands-off", 20);
            glintStrength(STRENGTH);
            cmd("item replace entity @p weapon.mainhand with minecraft:diamond_sword");
            Path plainHand = shot("glint-hand-plain", 20);
            cmd("item replace entity @p weapon.mainhand with minecraft:diamond_sword[enchantment_glint_override=true]");
            Path hand = shot("glint-hand", 20);
            glintStrength(0.0);
            Path handOff = shot("glint-hand-off", 20);
            glintStrength(STRENGTH);
            check(stands, standsOff, plainHand, hand, handOff);
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

    private void glintStrength(final double strength) {
        context.runOnClient(client -> client.options.glintStrength().set(strength));
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

    /**
     * Glint-on vs glint-off shot of the same frame ({@code glintStrength} 0.75 vs 0): fraction of pixels in
     * {@code [x0,x1) x [y0,y1)} that changed by at least {@link #CHANGED} (sum of channel differences), and the mean
     * purple shift {@code ((dr + db) / 2 - dg)} there.
     */
    private static double[] glintDelta(final BufferedImage on, final BufferedImage off, final double x0, final double x1, final double y0, final double y1) {
        int w = on.getWidth();
        int h = on.getHeight();
        int changed = 0;
        long purple = 0;
        int n = 0;
        for (int y = (int) (y0 * h); y < (int) (y1 * h); y++) {
            for (int x = (int) (x0 * w); x < (int) (x1 * w); x++) {
                int a = on.getRGB(x, y);
                int b = off.getRGB(x, y);
                int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
                int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
                int db = (a & 0xFF) - (b & 0xFF);
                if (Math.abs(dr) + Math.abs(dg) + Math.abs(db) >= CHANGED) changed++;
                purple += dr + db - 2L * dg;
                n++;
            }
        }
        return new double[] {n == 0 ? 0 : (double) changed / n, n == 0 ? 0 : purple / (2.0 * n)};
    }

    /** The enchanted region must get more purple with glint on, its plain twin (control) must not. */
    private void glintVisible(final String what, final double minPurple, final double[] enchanted, final double[] control) {
        line.accept(String.format(Locale.ROOT, "glint: %s glint on-off purple %+.2f (%.1f %% px changed), plain control %+.2f (%.1f %% px) (need >= %.1f and > 3x |control|)",
                what, enchanted[1], 100 * enchanted[0], control[1], 100 * control[0], minPurple));
        if (enchanted[1] < minPurple || enchanted[1] <= 3 * Math.abs(control[1])) {
            failures.add(String.format(Locale.ROOT, "glint: no visible glint on the enchanted %s (purple shift %+.2f, control %+.2f)",
                    what, enchanted[1], control[1]));
        }
    }

    private void check(final Path stands, final Path standsOff, final Path plainHand, final Path hand, final Path handOff) {
        try {
            BufferedImage a = javax.imageio.ImageIO.read(stands.toFile());
            BufferedImage p = javax.imageio.ImageIO.read(plainHand.toFile());
            BufferedImage b = javax.imageio.ImageIO.read(hand.toFile());
            BufferedImage aOff = javax.imageio.ImageIO.read(standsOff.toFile());
            BufferedImage bOff = javax.imageio.ImageIO.read(handOff.toFile());
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
            int[] before = count(p, 0.5, 1.0, 0.5, 1.0);
            int[] after = count(b, 0.5, 1.0, 0.5, 1.0);
            line.accept(String.format(Locale.ROOT, "glint: hand quadrant plain sword -> enchanted sword white %d -> %d, black %d -> %d (of %d px)",
                    before[0], after[0], before[1], after[1], after[2]));
            int handFloor = after[2] / 100; // 1 % of the quadrant
            if (after[0] - before[0] > handFloor) {
                failures.add("glint: enchanted sword in hand mostly white (+" + (after[0] - before[0]) + " white px)");
            }
            if (after[1] - before[1] > handFloor) {
                failures.add("glint: enchanted sword in hand has black patches (+" + (after[1] - before[1]) + " black px)");
            }
            // Glint must actually be there: the same frame with glint strength 0 must differ on the enchanted side
            // (stand, framed items, sword in hand) and not on the plain twins / the wall.
            glintVisible("armor stand", MIN_STAND, glintDelta(a, aOff, 0.30, 0.49, 0.53, 0.90), glintDelta(a, aOff, 0.51, 0.70, 0.53, 0.90));
            glintVisible("armor", MIN_ARMOR, glintDelta(a, aOff, 0.392, 0.439, 0.62, 0.83), glintDelta(a, aOff, 0.568, 0.615, 0.62, 0.83));
            glintVisible("framed items", MIN_FRAMES, glintDelta(a, aOff, 0.37, 0.49, 0.41, 0.505), glintDelta(a, aOff, 0.51, 0.63, 0.41, 0.505));
            glintVisible("sword in hand", MIN_HAND, glintDelta(b, bOff, 0.70, 1.0, 0.60, 1.0), glintDelta(b, bOff, 0.0, 0.30, 0.0, 0.40));
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
