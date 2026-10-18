/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Logging for metallium-gl. Every GL function or feature that is not implemented is logged once per mod (with a call
 * count kept for the summary), so coverage can grow from what real mods call instead of from guesses.
 */
public final class MetalGLLog {
    public static final Logger LOGGER = LoggerFactory.getLogger("metallium-gl");
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    private static final Map<String, AtomicLong> UNIMPLEMENTED = new ConcurrentHashMap<>();
    /** Dev default: fail fast on unimplemented functions so gaps are found immediately. */
    private static volatile boolean failFast = Boolean.getBoolean("metallium.gl.failFast");

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
            LOGGER.warn("[metallium-gl] {} called unimplemented {} (no-op, default result)", mod, function);
        }
        if (failFast) {
            throw new UnsupportedOperationException("metallium-gl: unimplemented " + function + " (called by " + mod + ")");
        }
    }

    /** An implemented function was called with a parameter value / feature that is not supported yet. */
    public static void unsupported(final String what) {
        UNIMPLEMENTED.computeIfAbsent("* " + what, _ -> new AtomicLong()).incrementAndGet();
        if (SEEN.add("feature " + what)) {
            LOGGER.warn("[metallium-gl] unsupported: {}", what);
        }
    }

    public static void warn(final String message) {
        if (SEEN.add("warn " + message)) {
            LOGGER.warn("[metallium-gl] {}", message);
        }
    }

    /** "mod function" → calls, sorted; for the game-test report and the F3 line. */
    public static Map<String, Long> unimplementedCalls() {
        Map<String, Long> sorted = new TreeMap<>();
        UNIMPLEMENTED.forEach((k, v) -> sorted.put(k, v.get()));
        return sorted;
    }
}
