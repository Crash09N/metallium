/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.compat.sodium.terrain;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Whether an Iris shader pack is in use (looked up by name; false without Iris). */
public final class IrisPackState {
    private static final MethodHandle PACK_IN_USE = lookup();

    private IrisPackState() {
    }

    private static MethodHandle lookup() {
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            return MethodHandles.publicLookup().findStatic(iris, "isPackInUseQuick", MethodType.methodType(boolean.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    public static boolean packInUse() {
        if (PACK_IN_USE == null) {
            return false;
        }
        try {
            return (boolean) PACK_IN_USE.invokeExact();
        } catch (Throwable t) {
            return false;
        }
    }
}
