/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
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
 */
public record ModReport(String id, String name, String version, List<String> glClasses, int backendClasses,
                        List<String> functions, List<String> packages) {

    public boolean rawGl() {
        return !glClasses.isEmpty();
    }

    public boolean usesBackendInternals() {
        return backendClasses > 0;
    }
}
