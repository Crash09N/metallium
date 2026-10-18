/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
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
