/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.power;

/** User-selected power mode (Metallium settings tab). */
public enum PowerMode {
    /** Follows macOS: Low Power Mode or a serious thermal state → Low Power, on battery → Balanced, else Performance. */
    AUTOMATIC,
    /** Full performance, also on battery and with macOS Low Power Mode on. */
    PERFORMANCE,
    /** FPS capped to the "FPS limit on battery" setting. */
    BALANCED,
    /** FPS capped to min(battery limit, 30). */
    LOW_POWER
}
