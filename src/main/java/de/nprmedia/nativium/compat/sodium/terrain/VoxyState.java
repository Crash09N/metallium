/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.compat.sodium.terrain;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Whether Voxy's LOD renderer is active (looked up by name; false without Voxy). Voxy draws its LODs from inside
 * Sodium's cutout terrain pass (an injection into {@code DefaultChunkRenderer.render}), so anything that runs Sodium's
 * opaque layer twice in one frame also runs Voxy's whole per-frame render twice.
 */
public final class VoxyState {
    private static final MethodHandle GET_NULLABLE = lookup();

    private VoxyState() {
    }

    private static MethodHandle lookup() {
        try {
            Class<?> holder = Class.forName("me.cortex.voxy.client.core.IVoxyRenderSystemHolder");
            Class<?> system = Class.forName("me.cortex.voxy.client.core.VoxyRenderSystem");
            return MethodHandles.publicLookup().findStatic(holder, "getNullable", MethodType.methodType(system))
                    .asType(MethodType.methodType(Object.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** True while Voxy has a render system (it renders LODs this frame). */
    public static boolean rendering() {
        if (GET_NULLABLE == null) {
            return false;
        }
        try {
            return (Object) GET_NULLABLE.invokeExact() != null;
        } catch (Throwable t) {
            return true; // Voxy is there but the lookup failed: assume it renders
        }
    }
}
