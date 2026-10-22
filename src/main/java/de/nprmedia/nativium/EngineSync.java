/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium;

import de.nprmedia.nativium.NativiumConfig.GraphicsEngine;
import net.minecraft.client.Options;
import net.minecraft.client.PreferredGraphicsApi;

/**
 * Keeps Nativium's Graphics Engine and vanilla's {@code preferredGraphicsBackend} option consistent.
 * METAL maps to vanilla DEFAULT (Nativium is prepended to the default list), OPENGL and VULKAN map one to one.
 * Iris and Distant Horizons read vanilla's value, so it must reflect the selection.
 */
public final class EngineSync {
    private EngineSync() {
    }

    public static PreferredGraphicsApi toVanilla(GraphicsEngine engine) {
        return switch (engine) {
            case METAL -> PreferredGraphicsApi.DEFAULT;
            case OPENGL -> PreferredGraphicsApi.OPENGL;
            case VULKAN -> PreferredGraphicsApi.VULKAN;
        };
    }

    public static GraphicsEngine fromVanilla(PreferredGraphicsApi api) {
        return switch (api) {
            case DEFAULT -> GraphicsEngine.METAL;
            case OPENGL -> GraphicsEngine.OPENGL;
            case VULKAN -> GraphicsEngine.VULKAN;
        };
    }

    /** Called after vanilla loaded options.txt. Returns the vanilla value to use as the startup preference. */
    public static PreferredGraphicsApi syncOnLoad(Options options) {
        NativiumConfig config = NativiumConfig.INSTANCE;
        PreferredGraphicsApi vanilla = options.preferredGraphicsBackend().get();
        String synced = config.syncedVanillaApi;
        boolean vanillaChangedByUser = synced != null
                ? !synced.equals(vanilla.name())
                : (config.engine == GraphicsEngine.METAL && vanilla != PreferredGraphicsApi.DEFAULT);
        if (vanillaChangedByUser) {
            config.engine = fromVanilla(vanilla);
            Nativium.LOGGER.info("[nativium] Graphics Engine follows vanilla Graphics API option: {}", config.engine);
        } else {
            PreferredGraphicsApi target = toVanilla(config.engine);
            if (target != vanilla) {
                Nativium.LOGGER.info("[nativium] Setting vanilla Graphics API to {} for Graphics Engine {}", target, config.engine);
                options.preferredGraphicsBackend().set(target);
                options.save();
                vanilla = target;
            }
        }
        config.syncedVanillaApi = vanilla.name();
        config.save();
        Nativium.LOGGER.info("[nativium] Graphics Engine: {} (vanilla Graphics API: {})", config.engine, vanilla);
        return vanilla;
    }

    /** Called when the user picks an engine in the UI. */
    public static void apply(GraphicsEngine engine, Options options) {
        NativiumConfig config = NativiumConfig.INSTANCE;
        config.engine = engine;
        PreferredGraphicsApi target = toVanilla(engine);
        if (options != null) {
            if (options.preferredGraphicsBackend().get() != target) {
                options.preferredGraphicsBackend().set(target);
                options.save();
            }
        }
        config.syncedVanillaApi = target.name();
        config.save();
    }
}
