/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.gl;

import org.lwjgl.opengl.GLCapabilities;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/**
 * {@code GL.getCapabilities()} for retargeted mods. There is no GL context, so the object is allocated without running
 * LWJGL's constructor; it claims OpenGL 1.1-4.6 core, the extensions in {@link MetalGLStrings#EXTENSIONS}, and a non-zero
 * "function address" for every entry point {@link MetalGL} / {@link MetalGL4} implement (mods sometimes test {@code caps.glFoo != 0}).
 * Everything else reads as unsupported.
 */
final class MetalGLCapabilities {
    private static GLCapabilities instance;

    private MetalGLCapabilities() {
    }

    static synchronized GLCapabilities get() {
        if (instance == null) {
            instance = create();
        }
        return instance;
    }

    private static GLCapabilities create() {
        try {
            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            GLCapabilities caps = (GLCapabilities) ((Unsafe) unsafeField.get(null)).allocateInstance(GLCapabilities.class);
            Set<String> implemented = new HashSet<>();
            for (Class<?> sink : new Class<?>[]{MetalGL.class, MetalGL4.class}) {
                for (Method method : sink.getDeclaredMethods()) {
                    if (Modifier.isPublic(method.getModifiers()) && method.getName().startsWith("gl")) {
                        implemented.add(method.getName());
                    }
                }
            }
            for (Field field : GLCapabilities.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String name = field.getName();
                if (field.getType() == boolean.class) {
                    boolean value = name.matches("OpenGL[1-4][0-9]") || name.equals("forwardCompatible") || MetalGLStrings.EXTENSIONS.contains(name);
                    if (value) {
                        field.setAccessible(true);
                        field.setBoolean(caps, true);
                    }
                } else if (field.getType() == long.class && implemented.contains(name)) {
                    field.setAccessible(true);
                    field.setLong(caps, 1L);
                }
            }
            return caps;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("nativium-gl: could not create GLCapabilities", e);
        }
    }
}
