/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.gl;

/** Lets tests in other packages create the package-private {@link RecordingDevice}. */
public final class TestDevices {
    private TestDevices() {
    }

    public static GlDevice recording() {
        return new RecordingDevice();
    }
}
