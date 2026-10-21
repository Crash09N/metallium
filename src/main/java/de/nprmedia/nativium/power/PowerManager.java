/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.power;

import de.nprmedia.nativium.BackendSelection;
import de.nprmedia.nativium.Nativium;
import de.nprmedia.nativium.NativiumConfig;

/**
 * Applies the power mode live (no restart). The macOS power state is polled every few seconds on a daemon thread; the
 * effective profile is recomputed on every query, so a mode change in the settings takes effect on the next frame.
 *
 * <p>Measures today: an FPS cap (through vanilla's frame limiter, {@code FramerateLimitTrackerMixin}). Planned (ROADMAP
 * Phase 6b): chunk-builder threads at a lower QoS class, fewer frames in flight, MetalFX upscaling.
 */
public final class PowerManager {
    public static final int LOW_POWER_FPS = 30;
    private static final long POLL_MS = 5000;
    private static volatile PowerState state = PowerState.UNKNOWN;
    private static volatile PowerMode lastEffective;
    private static boolean started;

    private PowerManager() {
    }

    /** Starts polling (macOS only). Idempotent. */
    public static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        if (!BackendSelection.metalSupported()) {
            return;
        }
        Thread thread = new Thread(PowerManager::poll, "Nativium power monitor");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void poll() {
        MacPowerSource source;
        try {
            source = new MacPowerSource();
        } catch (Throwable t) {
            Nativium.LOGGER.warn("[nativium] power state unavailable: {}", t.toString());
            return;
        }
        while (true) {
            try {
                PowerState next = source.read();
                if (!next.equals(state)) {
                    Nativium.LOGGER.info("[nativium] power: Low Power Mode {}, {}, thermal {}", next.lowPowerMode() ? "on" : "off",
                            next.source(), next.thermalName());
                }
                state = next;
            } catch (Throwable t) {
                Nativium.LOGGER.warn("[nativium] reading the power state failed; stopping the power monitor: {}", t.toString());
                state = PowerState.UNKNOWN;
                return;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    public static PowerState state() {
        return state;
    }

    public static PowerMode mode() {
        return NativiumConfig.INSTANCE.powerMode;
    }

    /** Live switch from the settings tab or tests. */
    public static void setMode(final PowerMode mode) {
        NativiumConfig.INSTANCE.powerMode = mode;
    }

    /** The profile in force now: PERFORMANCE, BALANCED or LOW_POWER (never AUTOMATIC). */
    public static PowerMode effective() {
        PowerMode effective = effective(mode(), state);
        if (effective != lastEffective) {
            lastEffective = effective;
            Nativium.LOGGER.info("[nativium] power mode {} → {} (FPS cap {})", mode(), effective, fpsCap(effective, NativiumConfig.INSTANCE.batteryFpsLimit));
        }
        return effective;
    }

    /** Pure mapping (unit-tested). */
    public static PowerMode effective(final PowerMode mode, final PowerState state) {
        if (mode != PowerMode.AUTOMATIC) {
            return mode;
        }
        if (state.lowPowerMode() || state.thermal() >= 2) {
            return PowerMode.LOW_POWER;
        }
        return state.onBattery() ? PowerMode.BALANCED : PowerMode.PERFORMANCE;
    }

    /** FPS cap for a profile; 0 = none. */
    public static int fpsCap(final PowerMode effective, final int batteryLimit) {
        return switch (effective) {
            case BALANCED -> batteryLimit;
            case LOW_POWER -> Math.min(batteryLimit, LOW_POWER_FPS);
            default -> 0;
        };
    }

    /** Current FPS cap; 0 = none. */
    public static int fpsCap() {
        return fpsCap(effective(), NativiumConfig.INSTANCE.batteryFpsLimit);
    }

    /** One status line, e.g. "Power: Automatic → Balanced (FPS cap 60) · macOS Low Power Mode off · Battery Power · thermal nominal". */
    public static String statusLine() {
        PowerMode effective = effective();
        int cap = fpsCap();
        StringBuilder line = new StringBuilder("Power: ").append(name(mode()));
        if (mode() == PowerMode.AUTOMATIC) line.append(" → ").append(name(effective));
        line.append(cap > 0 ? " (FPS cap " + cap + ")" : " (no FPS cap)");
        PowerState s = state;
        if (s.available()) {
            line.append(" · macOS Low Power Mode ").append(s.lowPowerMode() ? "on" : "off")
                    .append(" · ").append(s.source())
                    .append(" · thermal ").append(s.thermalName());
        } else {
            line.append(" · macOS power state unavailable");
        }
        return line.toString();
    }

    public static String name(final PowerMode mode) {
        return switch (mode) {
            case AUTOMATIC -> "Automatic";
            case PERFORMANCE -> "Performance";
            case BALANCED -> "Balanced";
            case LOW_POWER -> "Low Power";
        };
    }
}
