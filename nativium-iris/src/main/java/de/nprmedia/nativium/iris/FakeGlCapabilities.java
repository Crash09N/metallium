/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.iris;

import de.nprmedia.nativium.api.NativiumRuntime;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * On Metal there is no OpenGL context, so {@link GL#getCapabilities()} throws. Iris only reads boolean/function-pointer
 * fields from the result to choose code paths, so an instance with every field false/0 ("nothing supported") lets those
 * checks pick their fallback paths.
 */
public final class FakeGlCapabilities {
    private static GLCapabilities instance;

    private FakeGlCapabilities() {
    }

    public static GLCapabilities getCapabilities() {
        return NativiumRuntime.isMetalActive() ? get() : GL.getCapabilities();
    }

    private static synchronized GLCapabilities get() {
        if (instance == null) {
            try {
                Field field = Unsafe.class.getDeclaredField("theUnsafe");
                field.setAccessible(true);
                instance = (GLCapabilities) ((Unsafe) field.get(null)).allocateInstance(GLCapabilities.class);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not create placeholder GLCapabilities", e);
            }
        }
        return instance;
    }
}
