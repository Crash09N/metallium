/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium;

import net.fabricmc.loader.api.FabricLoader;

import java.util.Locale;

/**
 * Decides whether the Metal backend is offered to vanilla's backend list.
 *
 * <p>Metal is only tried on Apple Silicon macOS. Vanilla keeps its OpenGL and Vulkan backends behind it, so a failed
 * Metal device creation falls back to OpenGL. The decision can be overridden with {@code -Dnativium.backend=off|metal}.
 */
public final class BackendSelection {
    private static final String PROPERTY = "nativium.backend";
    private static final String IRIS_ADAPTER_MOD_ID = "nativium-iris";

    private static Decision decision;

    private BackendSelection() {
    }

    public static synchronized Decision decision() {
        if (decision == null) {
            if (!NativiumPlatform.supported()) {
                // Inert platform: no crash guard, no config, no mod scan (nothing is read or written).
                decision = new Decision(false, NativiumPlatform.status().logLine());
                return decision;
            }
            decision = decide();
            de.nprmedia.nativium.compat.CrashGuard.recordMetal(decision.offerMetal());
        }
        return decision;
    }

    /** Whether the Metal choice makes sense on this machine ({@link NativiumPlatform#supported()}). */
    public static boolean metalSupported() {
        return NativiumPlatform.supported();
    }

    /** Tests only: forgets the cached decision. */
    public static synchronized void resetForTests() {
        decision = null;
    }

    public static boolean shouldOfferMetal() {
        return decision().offerMetal();
    }

    static void logDecision() {
        if (!NativiumPlatform.supported()) {
            return; // NativiumPlatform already logged its single line
        }
        Decision d = decision();
        if (d.offerMetal()) {
            Nativium.LOGGER.info("[nativium] Metal backend enabled ({})", d.reason());
        } else {
            Nativium.LOGGER.warn("[nativium] Metal backend disabled: {}", d.reason());
        }
    }

    private static Decision decide() {
        de.nprmedia.nativium.compat.CrashGuard.init(); // reads the last session's marker, starts this one's
        String override = System.getProperty(PROPERTY, "").trim().toLowerCase(Locale.ROOT);
        if (override.equals("off") || override.equals("false")) {
            return new Decision(false, "-D" + PROPERTY + "=" + override);
        }
        boolean forced = override.equals("metal");

        NativiumConfig.GraphicsEngine engine = effectiveEngine(NativiumConfig.INSTANCE.engine, NativiumConfig.INSTANCE.syncedVanillaApi,
                System.getProperty("nativium.engine", "").isBlank() ? vanillaApiFromOptionsFile() : null);
        if (engine != NativiumConfig.GraphicsEngine.METAL && !forced) {
            return new Decision(false, "Graphics Engine is " + engine);
        }

        if (!forced) {
            de.nprmedia.nativium.compat.OpenGlRequirements.outcome(); // computed early: also forgets a "try Metal anyway" that just crashed
        }
        if (!forced && de.nprmedia.nativium.compat.CrashGuard.safeMode()) {
            return new Decision(false, "crash recovery: the last session crashed in Nativium, a mod running through nativium-gl, or during start-up on Metal; OpenGL this session");
        }

        FabricLoader loader = FabricLoader.getInstance();
        if (loader.isModLoaded("iris")) {
            // Iris 1.11 hard-casts the device to vanilla's GlDevice and would crash on Metal.
            if (!loader.isModLoaded(IRIS_ADAPTER_MOD_ID)) {
                return new Decision(false, "Iris is installed without the Nativium Iris adapter; using OpenGL so Iris keeps working");
            }
            if (!de.nprmedia.nativium.compat.IrisSupport.adapterUsable()) {
                return new Decision(false, de.nprmedia.nativium.compat.IrisSupport.problem() + "; using OpenGL so Iris keeps working");
            }
        }

        if (!forced) {
            // Mods that build Minecraft's OpenGL backend objects abort the JVM on Metal: OpenGL until the player chose.
            de.nprmedia.nativium.compat.OpenGlRequirements.Outcome needsGl = de.nprmedia.nativium.compat.OpenGlRequirements.outcome();
            if (!needsGl.offerMetal()) {
                return new Decision(false, needsGl.reason());
            }
        }

        return new Decision(true, forced ? "forced by -D" + PROPERTY + "=metal" : "Apple Silicon macOS");
    }

    /**
     * The Graphics Engine this session will use, decided before vanilla loads options.txt (mixins and nativium-gl need
     * it at class-load time). Same rule as {@link EngineSync#syncOnLoad}: a vanilla Graphics API value the player
     * changed since the last sync wins over Nativium's config. Pure (unit-tested).
     *
     * @param vanillaApi vanilla's {@code preferredGraphicsBackend} enum name (DEFAULT/OPENGL/VULKAN), null if unknown
     */
    static NativiumConfig.GraphicsEngine effectiveEngine(final NativiumConfig.GraphicsEngine configured, final String synced, final String vanillaApi) {
        if (vanillaApi == null) {
            return configured;
        }
        NativiumConfig.GraphicsEngine fromVanilla = switch (vanillaApi) {
            case "DEFAULT" -> NativiumConfig.GraphicsEngine.METAL;
            case "OPENGL" -> NativiumConfig.GraphicsEngine.OPENGL;
            case "VULKAN" -> NativiumConfig.GraphicsEngine.VULKAN;
            default -> null;
        };
        if (fromVanilla == null) {
            return configured;
        }
        boolean changedByUser = synced != null ? !synced.equals(vanillaApi)
                : configured == NativiumConfig.GraphicsEngine.METAL && fromVanilla != NativiumConfig.GraphicsEngine.METAL;
        return changedByUser ? fromVanilla : configured;
    }

    /** Vanilla's Graphics API from options.txt ({@code preferredGraphicsBackend:"opengl"}), as enum name; null if absent. */
    private static String vanillaApiFromOptionsFile() {
        try {
            java.nio.file.Path options = FabricLoader.getInstance().getGameDir().resolve("options.txt");
            if (!java.nio.file.Files.isRegularFile(options)) {
                return null;
            }
            for (String line : java.nio.file.Files.readAllLines(options)) {
                if (line.startsWith("preferredGraphicsBackend:")) {
                    return line.substring(line.indexOf(':') + 1).replace("\"", "").trim().toUpperCase(Locale.ROOT);
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            Nativium.LOGGER.warn("[nativium] could not read options.txt early: {}", e.toString());
        }
        return null;
    }

    public record Decision(boolean offerMetal, String reason) {
    }
}
