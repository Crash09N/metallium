// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Crash09N – NPR Media
package de.nprmedia.nativium;

import io.github.kokodio.metaljvm.metal.MTLDevice;
import io.github.kokodio.metaljvm.metal.MTLGPUFamily;
import io.github.kokodio.metaljvm.metal.Metal;

/**
 * Touches Metal.framework (metal-jvm, FFM): only ever called by {@link NativiumPlatform} on macOS aarch64, so the
 * metal-jvm bindings are never initialised on other systems.
 */
final class MetalDeviceProbe {
    private MetalDeviceProbe() {
    }

    /** A system default Metal device exists, is an Apple7+ GPU and supports Metal 4 (MSL 4.0 shaders). */
    static boolean usable() {
        MTLDevice device = Metal.MTLCreateSystemDefaultDevice();
        if (device == null) {
            return false;
        }
        try {
            return device.supportsFamily(MTLGPUFamily.Apple7) && device.supportsFamily(MTLGPUFamily.Metal4);
        } finally {
            device.release();
        }
    }
}
