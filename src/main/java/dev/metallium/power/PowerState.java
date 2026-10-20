/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.power;

/**
 * macOS power state.
 *
 * @param available  false if it could not be read (not macOS, or the native calls failed)
 * @param source     IOKit's providing power source ("AC Power", "Battery Power", ...)
 * @param thermal    NSProcessInfoThermalState: 0 nominal, 1 fair, 2 serious, 3 critical
 */
public record PowerState(boolean available, boolean lowPowerMode, boolean onBattery, String source, int thermal) {
    public static final PowerState UNKNOWN = new PowerState(false, false, false, "unknown", 0);

    public String thermalName() {
        return switch (thermal) {
            case 0 -> "nominal";
            case 1 -> "fair";
            case 2 -> "serious";
            case 3 -> "critical";
            default -> "unknown";
        };
    }
}
