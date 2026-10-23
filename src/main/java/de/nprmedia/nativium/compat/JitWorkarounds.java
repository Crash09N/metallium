/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.compat;

import de.nprmedia.nativium.Nativium;
import net.fabricmc.loader.api.FabricLoader;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Works around a HotSpot C2 miscompilation that crashes the JVM (SIGSEGV, "Problematic frame: J c2
 * ...FlatLightPipeline.getShade") while Sodium meshes chunks with Iris installed.
 *
 * <p>Iris injects a {@code @ModifyVariable} at the head of {@code CardinalLighting.byFace(Direction)} that swaps the
 * direction for {@code Direction.UP} when a shader pack disables directional shading. When C2 inlines that into Sodium's
 * {@code getShade(level, lightFace, shadeDirectionOverride)} it emits {@code csel(disable ? UP : override)} and loads
 * {@code .ordinal} from the result <em>before</em> the null check of {@code override} (which is null for most quads).
 * With shaders off (disable = false) that is a load from address 0xc that is not a registered implicit null check,
 * so the VM dies instead of the Java code taking its {@code lightFace} branch. Seen on Temurin 25.0.4.1 (bsd-aarch64),
 * typically right after a world loads once a shader pack has been switched off; the hs_err registers show the
 * override argument null and the faulting load at {@code [csel(UP, override) + 0xc]}.
 *
 * <p>Workaround: a compiler directive (the runtime equivalent of
 * {@code -XX:CompileCommand=dontinline,net.minecraft.world.level.CardinalLighting::byFace}) so C2 calls
 * {@code byFace} instead of inlining it into its callers; the miscompiled shape cannot form. One extra call per shaded
 * quad. Only applied when both Sodium and Iris are loaded; {@code -Dnativium.jitWorkaround=false} turns it off.
 */
public final class JitWorkarounds {
    static final String DIRECTIVE = "[{ match: \"*.*\", inline: \"-net/minecraft/world/level/CardinalLighting.byFace\" }]";

    private JitWorkarounds() {
    }

    public static void apply() {
        if (!Boolean.parseBoolean(System.getProperty("nativium.jitWorkaround", "true"))) {
            return;
        }
        FabricLoader loader = FabricLoader.getInstance();
        if (!loader.isModLoaded("sodium") || !loader.isModLoaded("iris")) {
            return;
        }
        try {
            Path file = Files.createTempFile("nativium-c2-directive", ".json");
            try {
                Files.writeString(file, DIRECTIVE, StandardCharsets.UTF_8);
                MBeanServer server = ManagementFactory.getPlatformMBeanServer();
                Object result = server.invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
                        "compilerDirectivesAdd", new Object[]{new String[]{file.toString()}},
                        new String[]{String[].class.getName()});
                Nativium.LOGGER.info("[nativium] C2 workaround for the Sodium+Iris getShade crash: CardinalLighting.byFace not inlined ({})",
                        String.valueOf(result).trim());
            } finally {
                Files.deleteIfExists(file);
            }
        } catch (Throwable t) {
            // No jdk.management module / diagnostic commands: the JVM flag in the README does the same.
            Nativium.LOGGER.warn("[nativium] could not add the C2 workaround directive ({}); if the game crashes in "
                    + "FlatLightPipeline.getShade, add -XX:CompileCommand=dontinline,net.minecraft.world.level.CardinalLighting::byFace "
                    + "to the JVM arguments", t.toString());
        }
    }
}
