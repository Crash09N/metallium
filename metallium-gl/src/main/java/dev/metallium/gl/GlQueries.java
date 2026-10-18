/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl;

import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Query objects. Timer queries ({@code GL_TIMESTAMP}, {@code GL_TIME_ELAPSED}) report CPU time at the point the query
 * was issued (Metal's GPU timestamps are per command buffer / counter sample buffer, not inline); occlusion queries
 * report "samples passed" conservatively (1). Results are available immediately. Good enough for mods that use queries
 * for profiling overlays or as a hint, which is what they are used for in practice.
 */
final class GlQueries {
    private static final class Query {
        int target;
        long result;
        long start;
    }

    private final GlContext gl;
    private final Map<Integer, @Nullable Query> queries = new HashMap<>();
    private final Map<Integer, Query> active = new HashMap<>();
    private int next = 1;

    GlQueries(final GlContext gl) {
        this.gl = gl;
    }

    int gen() {
        int name = next++;
        queries.put(name, null);
        return name;
    }

    int create(final int target) {
        int name = next++;
        Query q = new Query();
        q.target = target;
        queries.put(name, q);
        return name;
    }

    void delete(final int name) {
        queries.remove(name);
    }

    boolean isQuery(final int name) {
        return queries.get(name) != null;
    }

    private @Nullable Query object(final int name, final boolean create) {
        if (!queries.containsKey(name)) {
            gl.error(GL_INVALID_OPERATION);
            return null;
        }
        Query q = queries.get(name);
        if (q == null && create) {
            q = new Query();
            queries.put(name, q);
        }
        if (q == null) {
            gl.error(GL_INVALID_OPERATION);
        }
        return q;
    }

    void queryCounter(final int name, final int target) {
        if (target != GL_TIMESTAMP) {
            gl.error(GL_INVALID_ENUM);
            return;
        }
        Query q = object(name, true);
        if (q != null) {
            q.target = target;
            q.result = System.nanoTime();
        }
    }

    void begin(final int target, final int name) {
        Query q = object(name, true);
        if (q == null) {
            return;
        }
        if (active.containsKey(target)) {
            gl.error(GL_INVALID_OPERATION);
            return;
        }
        q.target = target;
        q.start = System.nanoTime();
        active.put(target, q);
    }

    void end(final int target) {
        Query q = active.remove(target);
        if (q == null) {
            gl.error(GL_INVALID_OPERATION);
            return;
        }
        q.result = switch (target) {
            case GL_TIME_ELAPSED -> System.nanoTime() - q.start;
            case GL_SAMPLES_PASSED, GL_ANY_SAMPLES_PASSED, GL_ANY_SAMPLES_PASSED_CONSERVATIVE -> 1;
            default -> 0;
        };
    }

    long result(final int name, final int pname) {
        Query q = object(name, false);
        if (q == null) {
            return 0;
        }
        return switch (pname) {
            case GL_QUERY_RESULT, GL_QUERY_RESULT_NO_WAIT -> q.result;
            case GL_QUERY_RESULT_AVAILABLE -> 1;
            case GL_QUERY_TARGET -> q.target;
            default -> {
                gl.error(GL_INVALID_ENUM);
                yield 0;
            }
        };
    }
}
