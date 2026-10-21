// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Crash09N – NPR Media
package de.nprmedia.nativium;

import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * The one platform gate: whether Nativium may do anything at all on this machine.
 *
 * <p>Supported = macOS on Apple Silicon (aarch64) with a Metal device the backend can use (Apple7+ GPU family and Metal 4,
 * which the backend's MSL 4.0 shaders need). Everywhere else (Windows, Linux, Intel Macs, VMs without a usable Metal
 * device) Nativium is inert: no mixin applies (see {@link NativiumMixinPlugin}), no entrypoint does anything, no
 * config or session file is written, nativium-gl hooks nothing, and exactly one log line says so.
 *
 * <p>Evaluated once, at the first mixin config load. Native code (Metal.framework through metal-jvm) is only touched
 * on macOS aarch64. {@code -Dnativium.platform.simulate=windows|linux|intel-mac|no-metal} pretends an unsupported
 * machine (tests); this class must stay free of Minecraft classes.
 */
public final class NativiumPlatform {
    /** Platform status; only {@link #SUPPORTED} lets Nativium do anything. */
    public enum Status {
        SUPPORTED("Nativium: Apple Silicon Mac with a supported Metal device"),
        NOT_MAC("Nativium: not a Mac — inactive (game runs on its normal renderer)"),
        NO_METAL_DEVICE("Nativium: no supported Metal device — inactive");

        private final String logLine;

        Status(final String logLine) {
            this.logLine = logLine;
        }

        public String logLine() {
            return logLine;
        }
    }

    public static final String SIMULATE_PROPERTY = "nativium.platform.simulate";

    private static volatile Status status;

    private NativiumPlatform() {
    }

    /** True only on a machine where the Metal backend can run. Everything Nativium does is gated on this. */
    public static boolean supported() {
        return status() == Status.SUPPORTED;
    }

    public static Status status() {
        Status s = status;
        if (s == null) {
            synchronized (NativiumPlatform.class) {
                s = status;
                if (s == null) {
                    s = detect();
                    status = s;
                    if (s != Status.SUPPORTED) {
                        Nativium.LOGGER.info(s.logLine()); // the only trace Nativium leaves on such a machine
                    }
                }
            }
        }
        return s;
    }

    private static Status detect() {
        String simulate = System.getProperty(SIMULATE_PROPERTY, "").trim().toLowerCase(Locale.ROOT);
        return switch (simulate) {
            case "windows" -> evaluate("Windows 11", "amd64", () -> false);
            case "linux" -> evaluate("Linux", "amd64", () -> false);
            case "intel-mac" -> evaluate("Mac OS X", "x86_64", () -> false);
            case "no-metal" -> evaluate("Mac OS X", "aarch64", () -> false);
            default -> evaluate(System.getProperty("os.name", ""), System.getProperty("os.arch", ""), MetalDeviceProbe::usable);
        };
    }

    /**
     * Pure decision (unit-tested). {@code metalDevice} is only called on macOS aarch64, so no native code runs on other
     * systems.
     */
    public static Status evaluate(final String osName, final String osArch, final BooleanSupplier metalDevice) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        if (!os.startsWith("mac")) {
            return Status.NOT_MAC;
        }
        if (!arch.equals("aarch64") && !arch.equals("arm64")) {
            return Status.NO_METAL_DEVICE; // Intel Mac: the backend targets Apple GPUs only
        }
        boolean usable;
        try {
            usable = metalDevice.getAsBoolean();
        } catch (Throwable t) {
            usable = false;
        }
        return usable ? Status.SUPPORTED : Status.NO_METAL_DEVICE;
    }

    /** Tests only: forces a status (null re-detects on the next call). */
    public static void overrideForTests(final Status forced) {
        status = forced;
    }
}
