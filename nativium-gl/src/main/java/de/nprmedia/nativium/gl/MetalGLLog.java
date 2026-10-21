/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.gl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Logging for nativium-gl. Every GL function or feature that is not implemented is logged once per mod (with a call
 * count kept for the summary), so coverage can grow from what real mods call instead of from guesses.
 */
public final class MetalGLLog {
    public static final Logger LOGGER = LoggerFactory.getLogger("nativium-gl");
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    private static final Map<String, AtomicLong> UNIMPLEMENTED = new ConcurrentHashMap<>();
    /** Dev default: fail fast on unimplemented functions so gaps are found immediately. */
    private static volatile boolean failFast = Boolean.getBoolean("nativium.gl.failFast");

    private MetalGLLog() {
    }

    public static void setFailFast(final boolean value) {
        failFast = value;
    }

    public static boolean failFast() {
        return failFast;
    }

    /** A GL entry point without a Metal implementation was called by {@code mod}. */
    public static void unimplemented(final String mod, final String function) {
        String key = mod + " " + function;
        UNIMPLEMENTED.computeIfAbsent(key, _ -> new AtomicLong()).incrementAndGet();
        if (SEEN.add(key)) {
            LOGGER.warn("[nativium-gl] {} called unimplemented {} (no-op, default result)", mod, function);
        }
        if (failFast) {
            throw new UnsupportedOperationException("nativium-gl: unimplemented " + function + " (called by " + mod + ")");
        }
    }

    /** An implemented function was called with a parameter value / feature that is not supported yet. */
    public static void unsupported(final String what) {
        UNIMPLEMENTED.computeIfAbsent("* " + what, _ -> new AtomicLong()).incrementAndGet();
        if (SEEN.add("feature " + what)) {
            LOGGER.warn("[nativium-gl] unsupported: {}", what);
        }
    }

    public static void warn(final String message) {
        if (SEEN.add("warn " + message)) {
            LOGGER.warn("[nativium-gl] {}", message);
        }
    }

    /** "mod function" → calls, sorted; for the game-test report and the F3 line. */
    public static Map<String, Long> unimplementedCalls() {
        Map<String, Long> sorted = new TreeMap<>();
        UNIMPLEMENTED.forEach((k, v) -> sorted.put(k, v.get()));
        return sorted;
    }
}
