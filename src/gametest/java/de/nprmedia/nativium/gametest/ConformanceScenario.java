/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Conformance scenes ({@code ./gradlew :runClientGameTest -PconformanceTest}, and the same with {@code -PbackendOff}
 * for the OpenGL reference): deterministic scenes covering vanilla's render pipelines, one fixed camera per shot, so the
 * Metal and OpenGL screenshots can be compared pixel by pixel ({@code tools/conformance-compare.sh}).
 * <p>
 * Determinism: flat world, time and weather rules off, mobs without AI, the server's tick clock frozen
 * ({@code /tick freeze}) for every shot (block-entity animations, banners, portals, beacon beams, particles and
 * entities keep one phase), enchantment glint speed 0, chunk fade-in 0, view bobbing off, particles minimal while a scene
 * is built (no random ambient particles), and the client runs in lock step with the test. Every world scene is shot in
 * fancy and again in fabulous graphics (improved transparency: the transparency post chain, the OIT-like path).
 */
final class ConformanceScenario {
    /** {@code -Dnativium.gametest.conformanceScenes=terrain,water}: only these scenes (default all). */
    private static final String ONLY = System.getProperty("nativium.gametest.conformanceScenes", "");
    /** {@code -Dnativium.gametest.conformanceFabulous=false}: skip the fabulous pass. */
    private static final boolean FABULOUS = !"false".equals(System.getProperty("nativium.gametest.conformanceFabulous"));

    private static final String CLEAR_BOX = "-14 -63 -8 14 -30 20";

    private final Consumer<String> line;
    private final List<String> failures;
    private final List<String> commandFailures = new ArrayList<>();
    private ClientGameTestContext context;
    private TestSingleplayerContext world;
    private String mode = "fancy";

    ConformanceScenario(final Consumer<String> line, final List<String> failures) {
        this.line = line;
        this.failures = failures;
    }

    void run(final ClientGameTestContext context, final String backend) {
        this.context = context;
        line.accept("conformance: backend " + backend);
        context.runOnClient(client -> {
            client.options.bobView().set(false);
            client.options.chunkSectionFadeInTime().set(0.0);
            client.options.glintSpeed().set(0.0);
            client.options.cloudStatus().set(CloudStatus.FANCY);
            client.options.particles().set(ParticleStatus.MINIMAL);
            client.options.improvedTransparency().set(false);
            client.options.entityShadows().set(true);
            client.options.fov().set(70);
        });
        try (TestSingleplayerContext w = context.worldBuilder().create()) {
            this.world = w;
            w.getConnection().waitForChunksRender();
            for (String rule : new String[] {"advance_time false", "advance_weather false", "spawn_mobs false", "spawn_monsters false",
                    "send_command_feedback false", "command_block_output false", "random_tick_speed 0", "locator_bar false",
                    "mob_griefing false", "show_advancement_messages false", "block_drops false", "entity_drops false", "mob_drops false"}) {
                cmd("gamerule " + rule);
            }
            cmd("difficulty easy");
            cmd("time set 6000");
            cmd("weather clear");
            cmd("gamemode creative @a");
            context.waitTicks(40);
            passes();
            if (FABULOUS) {
                mode = "fabulous";
                context.runOnClient(client -> client.options.improvedTransparency().set(true));
                context.waitTicks(20);
                line.accept("conformance: improvedTransparency=" + context.computeOnClient(c -> c.options.improvedTransparency().get()));
                worldScenes();
                context.runOnClient(client -> client.options.improvedTransparency().set(false));
            }
            cmd("tick unfreeze");
        }
        if (!commandFailures.isEmpty()) {
            failures.add("conformance: " + commandFailures.size() + " setup command(s) failed: " + commandFailures);
        }
    }

    private void passes() {
        worldScenes();
        // GUI/HUD scenes: once (graphics mode does not change them).
        scene("hud", this::hud);
        scene("gui-inventory", this::guiInventory);
    }

    private void worldScenes() {
        scene("terrain", this::terrain);
        scene("water", this::water);
        scene("underwater", this::underwater);
        scene("entities", this::entities);
        scene("block-entities", this::blockEntities);
        scene("portals-beacon", this::portalsBeacon);
        scene("particles", this::particles);
        scene("sky-day", this::skyDay);
        scene("sky-sunset", this::skySunset);
        scene("sky-night", this::skyNight);
        scene("weather", this::weather);
        scene("outline-breaking", this::outlineBreaking);
        scene("hand-glint", this::handGlint);
        scene("leash-fishing-map", this::leashFishingMap);
    }

    private void scene(final String name, final Runnable body) {
        if (!ONLY.isEmpty() && !List.of(ONLY.split(",")).contains(name)) return;
        line.accept("conformance: scene " + mode + "/" + name);
        try {
            reset();
            body.run();
        } catch (Throwable t) {
            failures.add("conformance " + mode + "/" + name + ": " + t);
            line.accept("conformance: scene " + name + " threw " + t);
        } finally {
            cleanup();
        }
    }

    // ---- scene helpers ---------------------------------------------------------------------------------------------

    /** Unfrozen, empty flat area, creative, flying, GUI hidden, noon, clear weather. */
    private void reset() {
        cmd("tick unfreeze");
        context.runOnClient(client -> {
            client.gui.setScreen(null);
            hud(client, true);
            client.options.particles().set(ParticleStatus.MINIMAL);
            client.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
        });
        cmd("gamemode creative @a");
        cmd("clear @a");
        cmd("effect clear @a");
        cmd("time set 6000");
        discardEntities();
        cmd("fill " + CLEAR_BOX + " minecraft:air strict");
        cmd("fill -14 -63 -8 14 -62 20 minecraft:dirt strict");
        cmd("fill -14 -61 -8 14 -61 20 minecraft:grass_block strict");
        discardEntities();
        context.runOnClient(client -> client.particleEngine.clearParticles());
    }

    private void cleanup() {
        context.runOnClient(client -> {
            client.gui.setScreen(null);
            hud(client, false);
            client.gui.hud.getChat().clearMessages(false);
        });
        cmd("tick unfreeze");
        cmd("title @a clear");
        cmd("bossbar remove nativium:conformance");
        cmd("scoreboard objectives remove conformance");
        discardEntities();
        context.waitTick();
    }

    /** Fixed camera: creative flight at an exact position and rotation. */
    private void camera(final double x, final double y, final double z, final float yaw, final float pitch) {
        context.runOnClient(client -> {
            client.player.getAbilities().flying = true;
            client.player.onUpdateAbilities();
        });
        cmd(String.format(java.util.Locale.ROOT, "tp @p %.3f %.3f %.3f %.2f %.2f", x, y, z, yaw, pitch));
        context.waitTicks(2);
        context.runOnClient(client -> {
            client.player.getAbilities().flying = true;
            client.player.setDeltaMovement(Vec3.ZERO);
            client.player.snapTo(x, y, z, yaw, pitch);
        });
    }

    /** Freeze the server clock, let the frame settle, take the shot. */
    private void shot(final String name, final int settleTicks) {
        cmd("tick freeze");
        context.waitTicks(settleTicks);
        context.runOnClient(client -> {
            client.gui.hud.getChat().clearMessages(false);
            client.player.setDeltaMovement(Vec3.ZERO);
        });
        context.waitTick();
        snap(name);
    }

    /** Screenshot without toasts (recipe unlocks from the scene's items). */
    private void snap(final String name) {
        context.runOnClient(client -> client.gui.toastManager().clear());
        context.waitTick();
        context.takeScreenshot("conformance-" + mode + "-" + name);
    }

    /** Removes every non-player entity without death animation, drops or particles. */
    private void discardEntities() {
        world.getServer().runOnServer(server -> {
            List<net.minecraft.world.entity.Entity> all = new ArrayList<>();
            server.overworld().getAllEntities().forEach(all::add);
            for (net.minecraft.world.entity.Entity e : all) {
                if (!(e instanceof net.minecraft.world.entity.player.Player)) e.discard();
            }
        });
    }

    private void cmd(final String command) {
        try {
            world.getServer().runCommand(command);
        } catch (Throwable t) {
            commandFailures.add("/" + command + " -> " + t.getMessage());
            line.accept("conformance: command failed: /" + command + " -> " + t.getMessage());
        }
    }

    private void setblock(final int x, final int y, final int z, final String block) {
        cmd("setblock " + x + " " + y + " " + z + " " + block);
    }

    // ---- scenes ----------------------------------------------------------------------------------------------------

    /** Solid, cutout (mipped) and translucent terrain layers in front of a coloured wool wall. */
    private void terrain() {
        String[] wool = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown"};
        for (int i = 0; i < wool.length; i++) {
            cmd("fill " + (i - 6) + " -60 7 " + (i - 6) + " -55 7 minecraft:" + wool[i] + "_wool");
        }
        String[] front = {"stone", "oak_leaves[persistent=true]", "glass", "white_stained_glass", "red_stained_glass", "blue_stained_glass",
                "ice", "slime_block", "honey_block", "tinted_glass", "green_stained_glass_pane", "iron_bars", "oak_planks"};
        for (int i = 0; i < front.length; i++) {
            setblock(i - 6, -59, 4, "minecraft:" + front[i]);
            setblock(i - 6, -58, 4, "minecraft:" + front[(i + 5) % front.length]);
        }
        String[] plants = {"poppy", "dandelion", "short_grass", "fern", "sugar_cane", "wheat[age=7]", "oak_sapling", "blue_orchid",
                "sweet_berry_bush[age=3]", "cobweb", "red_mushroom", "lily_of_the_valley", "dead_bush"};
        for (int i = 0; i < plants.length; i++) {
            if (plants[i].startsWith("wheat")) setblock(i - 6, -61, 2, "minecraft:farmland[moisture=7]");
            setblock(i - 6, -60, 2, "minecraft:" + plants[i]);
        }
        setblock(-3, -60, 0, "minecraft:oak_stairs[facing=west]");
        setblock(-2, -60, 0, "minecraft:stone_slab");
        setblock(2, -60, 0, "minecraft:lantern");
        setblock(3, -60, 0, "minecraft:oak_fence");
        setblock(4, -60, 0, "minecraft:redstone_wire");
        camera(0.5, -58.0, -4.5, 0, 12);
        shot("terrain", 30);
    }

    /** Water (translucent, animated) over a coloured floor, stained glass behind it, translucent particles above it. */
    private void water() {
        cmd("fill -7 -63 1 7 -63 9 minecraft:sand");
        cmd("fill -7 -62 1 -1 -62 9 minecraft:red_concrete");
        cmd("fill -7 -62 1 7 -61 9 minecraft:water");
        setblock(-3, -62, 4, "minecraft:stone");
        setblock(2, -62, 3, "minecraft:kelp_plant");
        setblock(2, -61, 3, "minecraft:kelp");
        setblock(4, -62, 5, "minecraft:sea_pickle[pickles=3,waterlogged=true]");
        setblock(0, -61, 6, "minecraft:ice");
        cmd("fill -7 -61 10 7 -57 10 minecraft:light_blue_stained_glass");
        cmd("fill -3 -60 11 3 -57 11 minecraft:yellow_wool");
        setblock(-5, -61, 2, "minecraft:lily_pad");
        cmd("summon minecraft:oak_boat -4.5 -61.0 6.5 {Rotation:[30f,0f]}");
        camera(0.5, -57.5, -2.5, 0, 28);
        particlesOn();
        cmd("particle minecraft:campfire_cosy_smoke 0.5 -59.5 4.5 0 0 0 0 1 force");
        cmd("particle minecraft:campfire_cosy_smoke -1.5 -59.8 5.5 0 0 0 0 1 force");
        cmd("particle minecraft:white_smoke 2.5 -60.2 4.0 0 0 0 0 1 force");
        cmd("particle minecraft:bubble_pop 1.0 -60.8 3.5 0 0 0 0 1 force");
        cmd("particle minecraft:entity_effect{color:[0.2,0.4,1.0,0.6]} -0.5 -60.3 3.0 0 0 0 0 1 force");
        for (int i = 0; i < 5; i++) { // a row of smoke puffs close to the camera, over the water
            cmd(String.format(java.util.Locale.ROOT, "particle minecraft:campfire_signal_smoke %.1f -60.0 1.8 0 0 0 0 1 force", -1.5 + i));
        }
        context.waitTicks(2);
        shot("water", 30);
    }

    /** Camera under water: water fog, the underside of the surface, kelp. */
    private void underwater() {
        cmd("fill -7 -63 -6 7 -63 12 minecraft:sand");
        cmd("fill -7 -62 -6 7 -58 12 minecraft:water");
        setblock(0, -62, 5, "minecraft:kelp_plant");
        setblock(0, -61, 5, "minecraft:kelp_plant");
        setblock(0, -60, 5, "minecraft:kelp");
        cmd("fill -3 -62 9 3 -59 9 minecraft:orange_wool");
        setblock(-2, -62, 3, "minecraft:brain_coral_block");
        camera(0.5, -60.5, -3.5, 0, -5);
        shot("underwater", 30);
    }

    /** Mobs without AI (incl. translucent slime, emissive enderman eyes, a glowing outline), armor stand, item frames. */
    private void entities() {
        cmd("fill -7 -60 8 7 -56 8 minecraft:stone_bricks");
        String noai = "NoAI:1b,Silent:1b,Rotation:[180f,0f]";
        cmd("summon minecraft:cow -5.5 -60 4.5 {" + noai + "}");
        cmd("summon minecraft:sheep -3.5 -60 4.5 {" + noai + ",Color:14b}");
        cmd("summon minecraft:husk -1.5 -60 4.5 {" + noai + ",CustomName:\"Zed\",CustomNameVisible:1b}");
        cmd("summon minecraft:creeper 0.5 -60 4.5 {" + noai + ",Glowing:1b}");
        cmd("summon minecraft:slime 2.5 -60 4.0 {" + noai + ",Size:1}");
        cmd("summon minecraft:enderman 4.5 -60 5.0 {" + noai + "}");
        cmd("summon minecraft:armor_stand -3.0 -60 2.0 {Rotation:[200f,0f],ShowArms:1b,equipment:{head:{id:\"minecraft:diamond_helmet\"},"
                + "chest:{id:\"minecraft:golden_chestplate\",components:{\"minecraft:enchantment_glint_override\":true}},"
                + "legs:{id:\"minecraft:leather_leggings\"},feet:{id:\"minecraft:netherite_boots\"},offhand:{id:\"minecraft:shield\"},"
                + "mainhand:{id:\"minecraft:diamond_sword\",components:{\"minecraft:enchantment_glint_override\":true}}}}");
        cmd("summon minecraft:item_frame -1.5 -57.5 7.5 {Facing:2b,Item:{id:\"minecraft:diamond_pickaxe\",count:1,components:{\"minecraft:enchantment_glint_override\":true}}}");
        cmd("summon minecraft:glow_item_frame 0.5 -57.5 7.5 {Facing:2b,Item:{id:\"minecraft:grass_block\",count:1}}");
        cmd("summon minecraft:item_frame 2.5 -57.5 7.5 {Facing:2b,Item:{id:\"minecraft:chest\",count:1}}");
        cmd("summon minecraft:painting 5.0 -58 7.5 {facing:2b,variant:\"minecraft:kebab\"}");
        cmd("summon minecraft:minecart 4.5 -60 1.5 {Rotation:[90f,0f]}");
        camera(0.5, -58.3, -3.5, 0, 8);
        shot("entities", 30);
    }

    /** Block entity renderers: chests, signs with text, banner, bed, shulker box, skulls, pot, lectern, bell, spawner. */
    private void blockEntities() {
        setblock(-6, -60, 4, "minecraft:chest[facing=north,type=right]");
        setblock(-5, -60, 4, "minecraft:chest[facing=north,type=left]");
        setblock(-4, -60, 4, "minecraft:ender_chest[facing=north]");
        setblock(-3, -60, 4, "minecraft:trapped_chest[facing=north]");
        setblock(-2, -60, 4, "minecraft:oak_sign[rotation=8]{front_text:{messages:[\"Conformance\",\"Metal vs GL\",\"line three\",\"0123456789\"]}}");
        setblock(-1, -60, 4, "minecraft:purple_shulker_box[facing=up]");
        setblock(0, -60, 4, "minecraft:white_banner[rotation=8]{patterns:[{pattern:\"minecraft:cross\",color:\"red\"},{pattern:\"minecraft:border\",color:\"blue\"}]}");
        setblock(1, -60, 4, "minecraft:red_bed[facing=west,part=head]");
        setblock(2, -60, 4, "minecraft:red_bed[facing=west,part=foot]");
        setblock(3, -60, 4, "minecraft:decorated_pot[facing=north]");
        setblock(4, -60, 4, "minecraft:lectern[facing=north,has_book=true]");
        setblock(5, -60, 4, "minecraft:bell[attachment=floor,facing=north]");
        setblock(6, -60, 4, "minecraft:enchanting_table");
        setblock(-4, -59, 6, "minecraft:skeleton_skull[rotation=8]");
        setblock(-3, -59, 6, "minecraft:creeper_head[rotation=8]");
        setblock(-2, -59, 6, "minecraft:player_head[rotation=8]");
        setblock(-1, -60, 6, "minecraft:spawner{SpawnData:{entity:{id:\"minecraft:pig\"}}}");
        setblock(1, -60, 6, "minecraft:conduit");
        setblock(3, -60, 6, "minecraft:copper_chest[facing=north]");
        cmd("fill -4 -60 6 -2 -60 6 minecraft:stone");
        setblock(5, -59, 6, "minecraft:oak_hanging_sign[rotation=8,attached=false]{front_text:{messages:[\"\",\"Hanging\",\"\",\"\"]}}");
        setblock(5, -58, 6, "minecraft:stone");
        camera(0.5, -57.8, -1.5, 0, 22);
        shot("block-entities", 30);
    }

    /** End portal and gateway (screen-space portal shader), nether portal (translucent), beacon beam (beacon ticks once). */
    private void portalsBeacon() {
        cmd("fill -6 -61 3 -4 -61 5 minecraft:end_portal");
        setblock(-2, -59, 4, "minecraft:end_gateway");
        cmd("fill 3 -63 3 7 -63 7 minecraft:iron_block");
        cmd("fill 4 -62 4 6 -62 6 minecraft:iron_block");
        setblock(5, -61, 5, "minecraft:beacon");
        setblock(5, -60, 5, "minecraft:red_stained_glass");
        cmd("fill -1 -60 7 2 -56 7 minecraft:obsidian");
        cmd("fill 0 -59 7 1 -57 7 minecraft:nether_portal[axis=x]");
        camera(0.5, -57.0, -4.5, 0, 0);
        context.waitTicks(90); // the beacon checks its base every 80 game ticks
        shot("portals-beacon", 20);
    }

    /** Opaque, emissive and translucent particles at fixed positions (one tick of motion, then frozen). */
    private void particles() {
        cmd("fill -6 -60 8 6 -56 8 minecraft:black_concrete");
        camera(0.5, -58.5, 2.5, 0, 0);
        particlesOn();
        String[] types = {"flame", "heart", "note", "crit", "end_rod", "soul_fire_flame", "glow", "happy_villager", "angry_villager",
                "enchanted_hit", "lava", "dust{color:[1.0,0.0,0.0],scale:2.0}", "block{block_state:\"minecraft:red_wool\"}",
                "item{item:\"minecraft:diamond\"}", "campfire_cosy_smoke", "witch", "totem_of_undying", "dripping_water"};
        for (int i = 0; i < types.length; i++) {
            double x = -3.0 + (i % 6) * 1.2;
            double y = -59.0 + (i / 6) * 1.0;
            cmd(String.format(java.util.Locale.ROOT, "particle minecraft:%s %.2f %.2f 6.0 0 0 0 0 1 force", types[i], x + 0.5, y));
        }
        context.waitTicks(2);
        shot("particles", 20);
    }

    private void skyDay() {
        cmd("time set 2500");
        camera(0.5, -50.0, 0.5, -90, -35);
        shot("sky-day", 30);
    }

    private void skySunset() {
        cmd("time set 12600");
        camera(0.5, -50.0, 0.5, 90, -8);
        shot("sky-sunset", 30);
    }

    private void skyNight() {
        cmd("time set 18000");
        camera(0.5, -50.0, 0.5, 0, -70);
        shot("sky-night", 30);
    }

    /**
     * Rain and snow side by side, away from the other scenes (the biome change stays there): snowy plains for x < 40
     * (right half of the view), plains for x >= 40 (left half), a wall behind the precipitation.
     */
    private void weather() {
        cmd("fill 26 -63 -8 54 -62 20 minecraft:dirt strict");
        cmd("fill 26 -61 -8 54 -61 20 minecraft:grass_block strict");
        cmd("fill 26 -60 -8 54 -30 20 minecraft:air strict");
        cmd("fillbiome 26 -64 -8 39 0 20 minecraft:snowy_plains");
        cmd("fillbiome 40 -64 -8 54 0 20 minecraft:plains");
        cmd("fill 34 -60 9 46 -57 9 minecraft:dark_oak_planks");
        cmd("weather rain");
        camera(40.5, -59.0, -2.5, 0, -5);
        context.waitTicks(120); // rain level ramps up
        shot("weather", 10);
        cmd("tick unfreeze");
        cmd("weather clear");
        context.waitTicks(120);
    }

    /** Block outline (crosshair on a block) and the crumbling overlay at three stages; HUD shown. */
    private void outlineBreaking() {
        cmd("fill -2 -60 3 2 -58 3 minecraft:stone");
        setblock(0, -59, 2, "minecraft:oak_log");
        camera(0.5, -58.9, -0.5, 0, 0);
        context.runOnClient(client -> hud(client, false));
        world.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            level.destroyBlockProgress(424242, new net.minecraft.core.BlockPos(-1, -59, 3), 2);
            level.destroyBlockProgress(424243, new net.minecraft.core.BlockPos(1, -59, 3), 5);
            level.destroyBlockProgress(424244, new net.minecraft.core.BlockPos(0, -60, 3), 9);
        });
        shot("outline-breaking", 20);
        world.getServer().runOnServer(server -> {
            for (int i = 0; i < 3; i++) server.overworld().destroyBlockProgress(424242 + i, net.minecraft.core.BlockPos.ZERO, -1);
        });
    }

    /** First-person hand with an enchanted sword (glint) and a shield, HUD hidden. */
    private void handGlint() {
        cmd("item replace entity @p weapon.mainhand with minecraft:diamond_sword[enchantments={sharpness:3}]");
        cmd("item replace entity @p weapon.offhand with minecraft:enchanted_golden_apple");
        cmd("fill -4 -60 5 4 -57 5 minecraft:gray_concrete");
        camera(0.5, -59.0, 0.5, 0, 5);
        context.runOnClient(client -> hud(client, false)); // the hotbar shows the item glint in the GUI as well
        context.waitTicks(20); // equip animation
        shot("hand-glint", 10);
    }

    /** Leash (cow to fence), fishing line (rod in hand, bobber in front), filled map in the off hand and in a frame. */
    private void leashFishingMap() {
        cmd("fill -6 -60 9 6 -56 9 minecraft:spruce_planks");
        setblock(-3, -60, 5, "minecraft:oak_fence");
        cmd("summon minecraft:cow -0.5 -60 4.5 {NoAI:1b,Silent:1b,Rotation:[150f,0f],leash:[I;-3,-60,5]}");
        cmd("item replace entity @p weapon.mainhand with minecraft:fishing_rod");
        world.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            ServerLevel level = server.overworld();
            ItemStack map = MapItem.create(level, 0, 0, (byte) 0, true, true);
            MapItem.renderBiomePreviewMap(level, map);
            player.setItemInHand(InteractionHand.OFF_HAND, map);
            ItemStack framed = map.copy();
            net.minecraft.world.entity.decoration.ItemFrame frame = new net.minecraft.world.entity.decoration.ItemFrame(level,
                    new net.minecraft.core.BlockPos(3, -58, 8), net.minecraft.core.Direction.NORTH);
            frame.setItem(framed, false);
            level.addFreshEntity(frame);
        });
        camera(0.5, -58.5, -1.5, 0, 10);
        context.runOnClient(client -> hud(client, false));
        context.waitTicks(30); // leash restore, map data, equip animation
        cmd("tick freeze");
        world.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            FishingHook hook = new FishingHook(player, server.overworld(), 0, 0);
            hook.setPos(1.8, -59.2, 4.0);
            hook.setDeltaMovement(Vec3.ZERO);
            server.overworld().addFreshEntity(hook);
        });
        shot("leash-fishing-map", 20);
    }

    /** Survival HUD: hotbar items (glint, block items), hearts, food, XP, title, boss bar, sidebar, chat. */
    private void hud() {
        cmd("gamemode survival @a");
        cmd("fill -6 -60 6 6 -56 6 minecraft:smooth_stone");
        cmd("tp @p 0.5 -60 0.5 0 0");
        cmd("item replace entity @p hotbar.0 with minecraft:diamond_sword[enchantments={sharpness:3}]");
        cmd("item replace entity @p hotbar.1 with minecraft:grass_block 64");
        cmd("item replace entity @p hotbar.2 with minecraft:chest");
        cmd("item replace entity @p hotbar.3 with minecraft:potion[potion_contents={potion:\"minecraft:healing\"}]");
        cmd("item replace entity @p hotbar.4 with minecraft:red_stained_glass 12");
        cmd("item replace entity @p hotbar.5 with minecraft:bow[damage=200]");
        cmd("item replace entity @p hotbar.6 with minecraft:white_banner[banner_patterns=[{pattern:\"minecraft:cross\",color:\"red\"}]]");
        cmd("item replace entity @p hotbar.7 with minecraft:shield");
        cmd("item replace entity @p hotbar.8 with minecraft:player_head");
        cmd("item replace entity @p weapon.offhand with minecraft:totem_of_undying");
        cmd("xp set @p 17 levels");
        cmd("xp set @p 40 points");
        cmd("bossbar add nativium:conformance \"Conformance Boss\"");
        cmd("bossbar set nativium:conformance players @a");
        cmd("bossbar set nativium:conformance value 60");
        cmd("bossbar set nativium:conformance color pink");
        cmd("scoreboard objectives add conformance dummy \"Scores\"");
        cmd("scoreboard objectives setdisplay sidebar conformance");
        cmd("scoreboard players set Metal conformance 26");
        cmd("scoreboard players set OpenGL conformance 3");
        cmd("title @a times 0 100000 0");
        cmd("title @a title \"Conformance\"");
        cmd("title @a subtitle \"HUD text\"");
        context.runOnClient(client -> {
            hud(client, false);
            client.player.setDeltaMovement(Vec3.ZERO);
            client.player.snapTo(0.5, -60.0, 0.5, 0, 0);
        });
        cmd("tellraw @a [{\"text\":\"Chat line \",\"color\":\"yellow\"},{\"text\":\"bold\",\"bold\":true},{\"text\":\" and §cred\"}]");
        context.waitTicks(30);
        cmd("tick freeze");
        context.waitTicks(5);
        snap("hud");
    }

    /** Survival inventory: item models (3D blocks, block entities, glint, durability bar) and a tooltip. */
    private void guiInventory() {
        cmd("gamemode survival @a");
        cmd("tp @p 0.5 -60 0.5 0 0");
        cmd("item replace entity @p hotbar.0 with minecraft:diamond_sword[enchantments={sharpness:3,unbreaking:2}]");
        cmd("item replace entity @p hotbar.1 with minecraft:grass_block 64");
        cmd("item replace entity @p hotbar.2 with minecraft:chest");
        cmd("item replace entity @p hotbar.3 with minecraft:red_bed");
        cmd("item replace entity @p hotbar.4 with minecraft:shield");
        cmd("item replace entity @p hotbar.5 with minecraft:bow[damage=200]");
        cmd("item replace entity @p inventory.0 with minecraft:glass");
        cmd("item replace entity @p inventory.1 with minecraft:blue_stained_glass 3");
        cmd("item replace entity @p inventory.2 with minecraft:oak_leaves");
        cmd("item replace entity @p inventory.3 with minecraft:enchanted_book[stored_enchantments={mending:1}]");
        cmd("item replace entity @p inventory.4 with minecraft:potion[potion_contents={potion:\"minecraft:swiftness\"}]");
        cmd("item replace entity @p inventory.5 with minecraft:conduit");
        cmd("item replace entity @p inventory.6 with minecraft:decorated_pot");
        cmd("item replace entity @p inventory.7 with minecraft:creeper_head");
        cmd("item replace entity @p inventory.8 with minecraft:white_banner[banner_patterns=[{pattern:\"minecraft:border\",color:\"blue\"}]]");
        cmd("item replace entity @p inventory.9 with minecraft:trident");
        cmd("item replace entity @p inventory.10 with minecraft:leather_chestplate[dyed_color=16711680]");
        cmd("item replace entity @p armor.head with minecraft:iron_helmet");
        cmd("item replace entity @p armor.chest with minecraft:netherite_chestplate[enchantment_glint_override=true]");
        context.runOnClient(client -> hud(client, false));
        context.waitTicks(10);
        cmd("tick freeze");
        context.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
        context.waitTicks(5);
        // Hover the sword in hotbar slot 0 (tooltip). Window coordinates from the screen's GUI coordinates.
        double[] pos = context.computeOnClient(client -> {
            if (!(client.gui.screen() instanceof AbstractContainerScreen<?> screen)) return null;
            Slot target = null;
            for (Slot slot : screen.getMenu().slots) {
                if (slot.container == client.player.getInventory() && slot.getContainerSlot() == 0) target = slot;
            }
            if (target == null) return null;
            int left;
            int top;
            try {
                java.lang.reflect.Field lf = AbstractContainerScreen.class.getDeclaredField("leftPos");
                java.lang.reflect.Field tf = AbstractContainerScreen.class.getDeclaredField("topPos");
                lf.setAccessible(true);
                tf.setAccessible(true);
                left = lf.getInt(screen);
                top = tf.getInt(screen);
            } catch (ReflectiveOperationException e) {
                return null;
            }
            double scale = (double) client.getWindow().getScreenWidth() / client.getWindow().getGuiScaledWidth();
            return new double[] {(left + target.x + 8) * scale, (top + target.y + 8) * scale};
        });
        if (pos == null) {
            failures.add("conformance: could not locate the inventory sword slot");
        } else {
            context.getInput().setCursorPos(pos[0], pos[1]);
        }
        context.waitTicks(5);
        snap("gui-inventory");
        context.runOnClient(client -> client.gui.setScreen(null));
    }

    /** F1: the HUD (and first-person hand) hidden or shown. */
    private static void hud(final Minecraft client, final boolean hidden) {
        if (client.gui.hud.isHidden() != hidden) client.gui.hud.toggle();
    }

    private void particlesOn() {
        context.runOnClient(client -> client.options.particles().set(ParticleStatus.ALL));
    }
}
