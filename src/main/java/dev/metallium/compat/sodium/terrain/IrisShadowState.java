/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.compat.sodium.terrain;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Whether Iris is currently rendering its shadow pass (Sodium terrain is drawn through the same renderer for the shadow
 * map). Looked up by name so Metallium does not depend on Iris; false without Iris.
 */
public final class IrisShadowState {
    private static final MethodHandle IN_SHADOW_PASS = lookup();

    private IrisShadowState() {
    }

    private static MethodHandle lookup() {
        try {
            Class<?> state = Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState");
            return MethodHandles.publicLookup().findStatic(state, "areShadowsCurrentlyBeingRendered", MethodType.methodType(boolean.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    public static boolean inShadowPass() {
        if (IN_SHADOW_PASS == null) {
            return false;
        }
        try {
            return (boolean) IN_SHADOW_PASS.invokeExact();
        } catch (Throwable t) {
            return false;
        }
    }
}
