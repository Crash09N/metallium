/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.power;

import de.nprmedia.nativium.Nativium;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

/**
 * macOS thread quality-of-service class of the render thread (ROADMAP Phase 2). The scheduler then prefers the
 * performance cores for it and keeps it there under load (idea from mcopt, which measured gains with the render thread at
 * user-interactive; own code). A thread can only set its own class, so {@link #applyToRenderThread()} runs on the render
 * thread itself (Metal device creation).
 *
 * <p>{@code -Dnativium.qos.render=interactive|initiated|default|utility|off} (default {@code interactive}).
 */
public final class ThreadQos {
    private static final String PROPERTY = "nativium.qos.render";
    private static final int QOS_CLASS_USER_INTERACTIVE = 0x21;
    private static final int QOS_CLASS_USER_INITIATED = 0x19;
    private static final int QOS_CLASS_DEFAULT = 0x15;
    private static final int QOS_CLASS_UTILITY = 0x11;

    private ThreadQos() {
    }

    /** The configured class, or 0 for "leave the thread alone". */
    static int configuredRenderClass() {
        String value = System.getProperty(PROPERTY, "interactive").trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "interactive", "user-interactive", "true", "on" -> QOS_CLASS_USER_INTERACTIVE;
            case "initiated", "user-initiated" -> QOS_CLASS_USER_INITIATED;
            case "default" -> QOS_CLASS_DEFAULT;
            case "utility" -> QOS_CLASS_UTILITY;
            default -> 0;
        };
    }

    /** Puts the calling thread (the render thread) into the configured QoS class. macOS only; never throws. */
    public static void applyToRenderThread() {
        int qosClass = configuredRenderClass();
        if (qosClass == 0) {
            Nativium.LOGGER.info("[nativium] render thread QoS: unchanged ({}=off)", PROPERTY);
            return;
        }
        try {
            Linker linker = Linker.nativeLinker();
            MethodHandle setSelf = linker.downcallHandle(
                    linker.defaultLookup().find("pthread_set_qos_class_self_np").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            int result = (int) setSelf.invokeExact(qosClass, 0);
            if (result == 0) {
                Nativium.LOGGER.info("[nativium] render thread QoS class 0x{} ({}; {})", Integer.toHexString(qosClass),
                        Thread.currentThread().getName(), PROPERTY);
            } else {
                Nativium.LOGGER.warn("[nativium] pthread_set_qos_class_self_np failed: {}", result);
            }
        } catch (Throwable t) {
            Nativium.LOGGER.warn("[nativium] render thread QoS not set: {}", t.toString());
        }
    }
}
