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

    private static final MethodHandle CURRENT_PACK = currentPackLookup();
    private static Object checkedPack;
    private static boolean checkedResult;

    private static MethodHandle currentPackLookup() {
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            return MethodHandles.publicLookup().findStatic(iris, "getCurrentPack", MethodType.methodType(java.util.Optional.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /**
     * Whether the current pack enables an option that moves terrain vertices far from their block (world curvature /
     * "planet" bending, or a waving intensity of 10 or more, e.g. Complementary's {@code WAVING_I 50.0}): the occlusion
     * culler's section boxes (grown by 1.5 blocks) would then not contain the drawn geometry. Checked once per loaded
     * pack (Iris reloads the pack object when options change); false without a pack, true (stay off) if the options
     * cannot be read.
     */
    public static synchronized boolean displacesTerrain() {
        if (CURRENT_PACK == null) {
            return true;
        }
        Object pack;
        try {
            pack = ((java.util.Optional<?>) CURRENT_PACK.invokeExact()).orElse(null);
        } catch (Throwable t) {
            return true;
        }
        try {
            if (pack == null) {
                return false;
            }
            if (pack == checkedPack) {
                return checkedResult;
            }
            Object options = pack.getClass().getMethod("getShaderPackOptions").invoke(pack);
            Object values = options.getClass().getMethod("getOptionValues").invoke(options);
            Object set = options.getClass().getMethod("getOptionSet").invoke(options);
            Class<?> valuesType = Class.forName("net.irisshaders.iris.shaderpack.option.values.OptionValues");
            java.lang.reflect.Method bool = valuesType.getMethod("getBooleanValueOrDefault", String.class);
            java.lang.reflect.Method string = valuesType.getMethod("getStringValueOrDefault", String.class);
            String found = null;
            for (Object key : ((java.util.Map<?, ?>) set.getClass().getMethod("getBooleanOptions").invoke(set)).keySet()) {
                String name = key.toString();
                if (CURVATURE.matcher(name).find() && (boolean) bool.invoke(values, name)) {
                    found = name;
                    break;
                }
            }
            if (found == null) {
                for (Object key : ((java.util.Map<?, ?>) set.getClass().getMethod("getStringOptions").invoke(set)).keySet()) {
                    String name = key.toString();
                    if (WAVING_INTENSITY.matcher(name).matches()) {
                        try {
                            if (Float.parseFloat(String.valueOf(string.invoke(values, name))) >= 10) {
                                found = name;
                                break;
                            }
                        } catch (NumberFormatException ignored) {
                            // not a number: not an intensity
                        }
                    }
                }
            }
            checkedPack = pack;
            checkedResult = found != null;
            if (found != null) {
                de.nprmedia.nativium.Nativium.LOGGER.info("[Nativium] Terrain occlusion culling (auto) stays off with this shader pack: option {} displaces terrain", found);
            }
            return checkedResult;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            checkedPack = pack;
            checkedResult = true;
            de.nprmedia.nativium.Nativium.LOGGER.warn("[Nativium] Terrain occlusion culling (auto) stays off with this shader pack: cannot read its options ({})", e.toString());
            return true;
        }
    }

    private static final java.util.regex.Pattern CURVATURE = java.util.regex.Pattern.compile("(?i)CURVATURE|CURVED|PLANET|WORLD_BEND");
    private static final java.util.regex.Pattern WAVING_INTENSITY = java.util.regex.Pattern.compile("(?i)\\w*WAV\\w*(_I|INTENSITY|STRENGTH|AMPLITUDE)");
}
