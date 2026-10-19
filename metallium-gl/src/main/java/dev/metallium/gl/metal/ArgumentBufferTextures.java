/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl.metal;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes a translated stage usable in pipelines that run from an indirect command buffer. Metal rejects ICB pipelines
 * whose functions take textures or samplers as direct arguments ("Fragment shader cannot be used with indirect command
 * buffers"), but an ICB inherits <em>buffer</em> bindings. So the entry point's {@code [[texture(n)]]} and
 * {@code [[sampler(n)]]} arguments are moved into one argument buffer ({@code constant MlTextureTable& [[buffer(b)]]},
 * Metal 3 / tier-2 layout: one 8-byte {@code MTLResourceID} per member, in declaration order) and copied into locals of
 * the same names at the top of the entry point, so the rest of the generated code is unchanged. The CPU writes the
 * resource ids per draw ({@code setFragmentBytes}) and declares the textures with {@code useResource}.
 *
 * <p>This is a text rewrite of SPIRV-Cross's output (the entry signature has one argument per resource); arrays of
 * textures are not handled (null: the caller keeps the per-slot fallback).
 */
final class ArgumentBufferTextures {
    /** Size of one member (MTLResourceID / tier-2 argument-buffer slot). */
    static final int SLOT_BYTES = 8;
    private static final String TABLE_TYPE = "MlTextureTable";
    private static final String TABLE_NAME = "mlTextureTable";
    private static final Pattern RESOURCE = Pattern.compile("(?s)^\\s*(.+?)\\s+(\\w+)\\s*\\[\\[\\s*(texture|sampler)\\s*\\(\\s*(\\d+)\\s*\\)\\s*\\]\\]\\s*$");
    private static final Pattern BUFFER_INDEX = Pattern.compile("\\[\\[\\s*buffer\\s*\\(\\s*(\\d+)\\s*\\)\\s*\\]\\]");
    /** Highest buffer index the table may use (vertex buffers for attributes are bound from 30 downwards). */
    private static final int MAX_TABLE_INDEX = 20;

    /** One table member: a texture (Metal texture index) or a sampler (Metal sampler index). */
    record Member(boolean sampler, int index) {
    }

    /**
     * @param msl         the rewritten source
     * @param bufferIndex the buffer index of the table
     * @param members     table members in layout order (member i at byte offset {@code i * SLOT_BYTES})
     */
    record Rewritten(String msl, int bufferIndex, List<Member> members) {
        int size() {
            return members.size() * SLOT_BYTES;
        }
    }

    private ArgumentBufferTextures() {
    }

    /**
     * Rewrites {@code entryPoint} of {@code msl}; returns null when the entry takes no textures/samplers (nothing to do)
     * or the signature has a form this rewrite does not handle.
     */
    static @Nullable Rewritten rewrite(final String msl, final String entryPoint) {
        Matcher header = Pattern.compile("\\b(?:vertex|fragment)\\b[^;{()]*?\\b" + Pattern.quote(entryPoint) + "\\s*\\(").matcher(msl);
        if (!header.find()) {
            return null;
        }
        int open = header.end() - 1;
        int close = matching(msl, open);
        if (close < 0) {
            return null;
        }
        int body = msl.indexOf('{', close);
        if (body < 0 || !msl.substring(close + 1, body).isBlank()) {
            return null;
        }
        List<String> params = split(msl.substring(open + 1, close));
        List<String> kept = new ArrayList<>();
        List<Member> members = new ArrayList<>();
        StringBuilder struct = new StringBuilder("struct " + TABLE_TYPE + "\n{\n");
        StringBuilder locals = new StringBuilder();
        int maxBuffer = -1;
        for (String param : params) {
            Matcher m = RESOURCE.matcher(param);
            if (!m.matches()) {
                Matcher b = BUFFER_INDEX.matcher(param);
                while (b.find()) maxBuffer = Math.max(maxBuffer, Integer.parseInt(b.group(1)));
                kept.add(param.trim());
                continue;
            }
            String type = m.group(1).trim();
            String name = m.group(2);
            if (type.contains("array<") || type.contains("*") || type.contains("&")) {
                return null;
            }
            boolean sampler = m.group(3).equals("sampler");
            members.add(new Member(sampler, Integer.parseInt(m.group(4))));
            struct.append("    ").append(type).append(' ').append(name).append(";\n");
            locals.append("\n    ").append(type).append(' ').append(name).append(" = ").append(TABLE_NAME).append('.').append(name).append(';');
        }
        if (members.isEmpty()) {
            return null;
        }
        int index = maxBuffer + 1;
        if (index > MAX_TABLE_INDEX) {
            return null;
        }
        struct.append("};\n\n");
        kept.add("constant " + TABLE_TYPE + "& " + TABLE_NAME + " [[buffer(" + index + ")]]");
        // The struct goes right before the entry point's declaration (after any attributes such as [[early_fragment_tests]]).
        int declaration = msl.lastIndexOf('\n', header.start()) + 1;
        while (declaration > 0) {
            int previous = msl.lastIndexOf('\n', declaration - 2) + 1;
            if (!msl.substring(previous, declaration).trim().startsWith("[[")) break;
            declaration = previous;
        }
        String out = msl.substring(0, declaration) + struct + msl.substring(declaration, open + 1) + String.join(", ", kept) + ")"
                + msl.substring(close + 1, body + 1) + locals + msl.substring(body + 1);
        return new Rewritten(out, index, List.copyOf(members));
    }

    /** Index of the ')' closing the '(' at {@code open}, or -1. */
    private static int matching(final String s, final int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    /** Splits a parameter list at top-level commas ({@code <>}, {@code ()} and {@code []} nest). */
    private static List<String> split(final String list) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            switch (c) {
                case '<', '(', '[' -> depth++;
                case '>', ')', ']' -> depth--;
                case ',' -> {
                    if (depth == 0) {
                        out.add(list.substring(start, i));
                        start = i + 1;
                    }
                }
                default -> {
                }
            }
        }
        if (!list.substring(start).isBlank()) out.add(list.substring(start));
        return out;
    }
}
