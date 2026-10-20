/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.compat;

import java.util.List;

/**
 * Scan result for one mod: which of its classes call raw OpenGL or use Minecraft's OpenGL backend internals.
 *
 * @param glClasses       binary class names (dots) that call {@code org.lwjgl.opengl.*}
 * @param backendClasses  number of classes that use {@code com.mojang.renderpearl.backend.opengl.*}
 * @param functions       a sample of the GL functions called, e.g. "GL11.glEnable"
 * @param packages        packages (dots) of the raw-GL classes, used to attribute crashes to the mod
 * @param backendObjectClasses binary names of the classes that construct or subclass classes of Minecraft's OpenGL
 *                        backend ({@link GlUsageScanner.ClassUsage#constructsBackend()}): such a mod most likely needs
 *                        the OpenGL backend ({@link OpenGlRequirements})
 */
public record ModReport(String id, String name, String version, List<String> glClasses, int backendClasses,
                        List<String> functions, List<String> packages, List<String> backendObjectClasses) {

    public ModReport(final String id, final String name, final String version, final List<String> glClasses, final int backendClasses,
                     final List<String> functions, final List<String> packages) {
        this(id, name, version, glClasses, backendClasses, functions, packages, List.of());
    }

    public boolean rawGl() {
        return !glClasses.isEmpty();
    }

    public boolean usesBackendInternals() {
        return backendClasses > 0;
    }

    /** Builds its own objects of Minecraft's OpenGL backend (new/extends): "likely needs OpenGL". */
    public boolean buildsBackendObjects() {
        return backendObjectClasses != null && !backendObjectClasses.isEmpty();
    }
}
