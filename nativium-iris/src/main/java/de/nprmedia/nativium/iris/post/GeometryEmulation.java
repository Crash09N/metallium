/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.iris.post;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import de.nprmedia.nativium.backend.NativiumInterop;
import io.github.kokodio.metaljvm.metal.MTLBuffer;
import io.github.kokodio.metaljvm.metal.MTLPrimitiveType;
import io.github.kokodio.metaljvm.metal.MTLRenderCommandEncoder;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 6: geometry shaders on Metal (which has no geometry stage), emulated in the vertex stage.
 *
 * <p>The pack's vertex shader (VS) and geometry shader (GS) are merged into one vertex shader that is drawn
 * non-indexed with {@code slots = 3 * (max_vertices - 2)} vertices per input triangle (the most triangles a
 * {@code triangle_strip} of {@code max_vertices} can produce), as a triangle list. Each invocation
 * <ol>
 *   <li>pulls the three corners of its input triangle itself (index buffer, vertex buffer and draw parameters bound
 *       as buffers by the {@link Interceptor}, attributes decoded from the {@link VertexFormat} in GLSL),</li>
 *   <li>runs the pack's VS once per corner and collects its outputs as the GS input arrays,</li>
 *   <li>runs the GS body; {@code EmitVertex()} keeps the last three emitted vertices of the current strip and, when
 *       the strip completes output triangle {@code slot / 3}, copies corner {@code slot % 3} (GL strip winding) to the
 *       real outputs. Slots whose triangle is never emitted collapse to one point (no rasterization).</li>
 * </ol>
 * Side effects of the GS (image/buffer atomics, {@code imageStore}) must happen once per primitive: only slot 0 runs
 * the GS as written; the other slots run a copy in which those calls are macros that do nothing (the Metal compiler
 * then also drops the work that only fed them, e.g. Rethinking Voxels' texture samples for the voxel colour).
 *
 * <p>Limits: {@code layout(triangles) in} and {@code triangle_strip} output only, no {@code invocations},
 * {@code EmitStreamVertex}, interface blocks or array outputs; triangle-list draws only (indirect draws and
 * triangle fans are skipped); side effects in VS code or in functions the GS main calls run once per slot.
 * Ideas from Mesa's geometry-shader lowering for Apple GPUs (run the GS per output vertex, count primitives;
 * MIT, no code taken).
 */
final class GeometryEmulation {
    /** {@code -Dnativium.iris.geometryShaders=false} turns the emulation off (programs with a GS use the vanilla shader). */
    static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("nativium.iris.geometryShaders", "true"));

    /** {@code -Dnativium.iris.dumpGeometry=true}: writes merged VS+GS sources to {@code nativium-iris-debug/}. */
    static final boolean DUMP = !"false".equalsIgnoreCase(System.getProperty("nativium.iris.dumpGeometry", "true"));

    /** Metal buffer indices of the pulled vertices, indices and draw parameters (below StorageBindings' 26-30). */
    static final int VERTEX_INDEX = 22;
    static final int INDEX_INDEX = 23;
    static final int PARAMS_INDEX = 24;
    static final String PREFIX = "nativium_gs_";

    private static final Pattern VERSION = Pattern.compile("#version\\s+(\\d+)");
    private static final Pattern INPUT_LAYOUT = Pattern.compile("^layout\\s*\\(([^)]*)\\)\\s*in\\s*;$");
    private static final Pattern OUTPUT_LAYOUT = Pattern.compile("^layout\\s*\\(([^)]*)\\)\\s*out\\s*;$");
    private static final Pattern IO_DECLARATION = Pattern.compile(
            "^(?:layout\\s*\\([^)]*\\)\\s*)?((?:(?:flat|smooth|noperspective|centroid|invariant|highp|mediump|lowp)\\s+)*)(in|out|attribute|varying)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(\\[[^\\]]*\\])?\\s+([^;{]+);$");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern GL_IN_POSITION = Pattern.compile("\\bgl_in\\s*\\[([^\\]]*)\\]\\s*\\.\\s*gl_Position\\b");
    private static final Pattern MAX_VERTICES = Pattern.compile("max_vertices\\s*=\\s*(\\d+)");
    /** Side-effect calls neutralized in the non-leading copy of the GS: name -> argument count. */
    private static final Map<String, Integer> SIDE_EFFECTS = new LinkedHashMap<>();

    static {
        for (String op : List.of("Add", "Min", "Max", "And", "Or", "Xor", "Exchange")) {
            SIDE_EFFECTS.put("imageAtomic" + op, 3);
            SIDE_EFFECTS.put("atomic" + op, 2);
        }
        SIDE_EFFECTS.put("imageAtomicCompSwap", 4);
        SIDE_EFFECTS.put("atomicCompSwap", 3);
        SIDE_EFFECTS.put("imageStore", 3);
    }

    /** The merged vertex shader and the number of output vertices per input triangle. */
    record Merged(String vertex, int slots) {
    }

    private GeometryEmulation() {
    }

    private enum Kind { DIRECTIVE, FUNCTION, DECLARATION }

    private record Item(String text, Kind kind, String name) {
        String normalized() {
            return text.replaceAll("\\s+", " ").trim();
        }
    }

    private record Varying(String qualifiers, String type, String name) {
    }

    /** Merges the Iris-patched VS and GS into one vertex shader that emulates the GS (see the class comment). */
    static Merged merge(final String vertex, final String geometry, final VertexFormat format) throws PostProgram.UnsupportedProgramException {
        List<Item> vsItems = split(vertex);
        List<Item> gsItems = split(geometry);
        int version = Math.max(version(vertex), Math.max(version(geometry), 430));
        Set<String> directives = new LinkedHashSet<>();
        StringBuilder out = new StringBuilder();

        // ---- vertex shader: inputs pulled, outputs become globals, main renamed ----
        Map<String, String> vsInputs = new LinkedHashMap<>();
        Map<String, Varying> vsOutputs = new LinkedHashMap<>();
        List<String[]> vsOutputDeclarations = new ArrayList<>();
        StringBuilder vsBody = new StringBuilder();
        Map<String, String> vsDeclarations = new LinkedHashMap<>();
        Set<String> vsFunctions = new HashSet<>();
        for (Item item : vsItems) {
            if (item.kind == Kind.DIRECTIVE) {
                if (!item.text.startsWith("#version")) {
                    directives.add(item.text.trim());
                }
                continue;
            }
            String text = item.text;
            if (item.kind == Kind.DECLARATION) {
                Matcher io = IO_DECLARATION.matcher(text.trim());
                if (io.matches()) {
                    String direction = io.group(2);
                    String type = io.group(3) + (io.group(4) == null ? "" : io.group(4));
                    if (direction.equals("in") || direction.equals("attribute")) {
                        for (String name : names(io.group(5))) {
                            vsInputs.put(name, type);
                        }
                        vsBody.append(type).append(' ').append(io.group(5)).append(";\n");
                        continue;
                    }
                    for (String name : names(io.group(5))) {
                        vsOutputs.put(name, new Varying(io.group(1), type, name));
                    }
                    // resolved after the GS is parsed: a global if the GS consumes it, else still an output
                    vsBody.append("@@VSOUT").append(vsOutputDeclarations.size()).append("@@\n");
                    vsOutputDeclarations.add(new String[]{io.group(1), type, io.group(5)});
                    continue;
                }
                vsDeclarations.put(item.name, item.normalized());
            } else {
                vsFunctions.add(item.normalized());
                vsDeclarations.putIfAbsent("function " + item.name, item.normalized());
                if (item.name.equals("main")) {
                    text = text.replaceFirst("\\bmain\\s*\\(", PREFIX + "vs(");
                }
            }
            text = text.replaceAll("\\bgl_VertexID\\b|\\bgl_VertexIndex\\b", PREFIX + "vertexId");
            vsBody.append(text).append('\n');
        }

        // ---- geometry shader ----
        int maxVertices = -1;
        Map<String, Varying> gsInputs = new LinkedHashMap<>();
        Map<String, Varying> gsOutputs = new LinkedHashMap<>();
        List<Item> gsCode = new ArrayList<>();
        for (Item item : gsItems) {
            String trimmed = item.text.trim();
            if (item.kind == Kind.DIRECTIVE) {
                if (!trimmed.startsWith("#version")) {
                    directives.add(trimmed);
                }
                continue;
            }
            if (item.kind == Kind.DECLARATION) {
                if (trimmed.contains("gl_PerVertex")) {
                    continue;
                }
                Matcher inLayout = INPUT_LAYOUT.matcher(trimmed);
                if (inLayout.matches()) {
                    String parts = inLayout.group(1).replaceAll("\\s+", "");
                    if (!parts.contains("triangles") || parts.contains("adjacency")) {
                        throw new PostProgram.UnsupportedProgramException("geometry shader input '" + parts + "' (only triangles are emulated)");
                    }
                    Matcher invocations = Pattern.compile("invocations=(\\d+)").matcher(parts);
                    if (invocations.find() && Integer.parseInt(invocations.group(1)) > 1) {
                        throw new PostProgram.UnsupportedProgramException("geometry shader invocations (instancing) are not emulated");
                    }
                    continue;
                }
                Matcher outLayout = OUTPUT_LAYOUT.matcher(trimmed);
                if (outLayout.matches()) {
                    String parts = outLayout.group(1).replaceAll("\\s+", "");
                    if (parts.contains("points") || parts.contains("line_strip")) {
                        throw new PostProgram.UnsupportedProgramException("geometry shader output '" + parts + "' (only triangle_strip is emulated)");
                    }
                    Matcher max = MAX_VERTICES.matcher(parts);
                    if (max.find()) {
                        maxVertices = Integer.parseInt(max.group(1));
                    }
                    continue;
                }
                Matcher io = IO_DECLARATION.matcher(trimmed);
                if (io.matches()) {
                    String direction = io.group(2);
                    String type = io.group(3);
                    if (direction.equals("in")) {
                        for (String name : names(io.group(5))) {
                            gsInputs.put(name, new Varying(io.group(1), type, name));
                        }
                        continue;
                    }
                    if (direction.equals("out")) {
                        if (io.group(4) != null || io.group(5).contains("[")) {
                            throw new PostProgram.UnsupportedProgramException("geometry shader array output '" + trimmed + "'");
                        }
                        for (String name : names(io.group(5))) {
                            gsOutputs.put(name, new Varying(io.group(1), type, name));
                        }
                        continue;
                    }
                }
                if (trimmed.matches("(?s)^(?:layout\\s*\\([^)]*\\)\\s*)?(?:flat\\s+)?(in|out)\\s+[A-Za-z_]\\w*\\s*\\{.*")) {
                    throw new PostProgram.UnsupportedProgramException("geometry shader interface block '" + trimmed.lines().findFirst().orElse("") + "'");
                }
                String existing = vsDeclarations.get(item.name);
                if (existing != null) {
                    if (existing.equals(item.normalized())) {
                        continue;
                    }
                    if (vsOutputs.containsKey(item.name) || vsInputs.containsKey(item.name)) {
                        // a GS global named like a VS varying: renamed below with the GS's other identifiers
                    } else {
                        throw new PostProgram.UnsupportedProgramException("vertex and geometry shader declare '" + item.name + "' differently");
                    }
                }
            } else {
                if (vsFunctions.contains(item.normalized())) {
                    continue;
                }
                String existing = vsDeclarations.get("function " + item.name);
                if (existing != null && !item.name.equals("main") && sameSignature(existing, item.normalized())) {
                    throw new PostProgram.UnsupportedProgramException("vertex and geometry shader define function '" + item.name + "' differently");
                }
            }
            gsCode.add(item);
        }
        if (maxVertices < 3) {
            throw new PostProgram.UnsupportedProgramException("geometry shader without a usable max_vertices");
        }
        int slots = 3 * (maxVertices - 2);

        // Renames inside GS code: inputs -> nativium_gs_in_*, outputs -> nativium_gs_cur_*, built-ins.
        List<String> gsTexts = new ArrayList<>();
        String gsMain = null;
        for (Item item : gsCode) {
            String text = GL_IN_POSITION.matcher(item.text).replaceAll(PREFIX + "inPosition[$1]");
            for (String name : gsInputs.keySet()) {
                text = text.replaceAll("\\b" + Pattern.quote(name) + "\\b", PREFIX + "in_" + name);
            }
            for (String name : gsOutputs.keySet()) {
                text = text.replaceAll("\\b" + Pattern.quote(name) + "\\b", PREFIX + "cur_" + name);
            }
            text = text.replaceAll("\\bgl_Position\\b", PREFIX + "curPosition")
                    .replaceAll("\\bgl_PrimitiveIDIn\\b", PREFIX + "primitiveId")
                    .replaceAll("\\bgl_InvocationID\\b", "0")
                    .replaceAll("\\bgl_PrimitiveID\\b", PREFIX + "unusedPrimitiveId")
                    .replaceAll("\\bEmitVertex\\s*\\(\\s*\\)", PREFIX + "emit()")
                    .replaceAll("\\bEndPrimitive\\s*\\(\\s*\\)", PREFIX + "endPrimitive()");
            if (item.kind == Kind.FUNCTION && item.name.equals("main")) {
                gsMain = text;
                continue;
            }
            gsTexts.add(text);
        }
        if (gsMain == null) {
            throw new PostProgram.UnsupportedProgramException("geometry shader without main");
        }

        out.append("#version ").append(version).append(" core\n");
        directives.forEach(d -> out.append(d).append('\n'));
        out.append("layout(std430, binding = ").append(VERTEX_INDEX).append(") readonly buffer ").append(PREFIX).append("Vertices { uint ")
                .append(PREFIX).append("vtx[]; };\n");
        out.append("layout(std430, binding = ").append(INDEX_INDEX).append(") readonly buffer ").append(PREFIX).append("Indices { uint ")
                .append(PREFIX).append("idx[]; };\n");
        out.append("layout(std430, binding = ").append(PARAMS_INDEX).append(") readonly buffer ").append(PREFIX).append("Params { int ")
                .append(PREFIX).append("param[]; };\n");
        out.append("int ").append(PREFIX).append("vertexId;\nint ").append(PREFIX).append("primitiveId;\nint ").append(PREFIX).append("unusedPrimitiveId;\n");
        String vsText = vsBody.toString();
        for (int i = 0; i < vsOutputDeclarations.size(); i++) {
            String[] declaration = vsOutputDeclarations.get(i);
            boolean consumed = true;
            for (String name : names(declaration[2])) {
                if (gsOutputs.containsKey(name)) {
                    throw new PostProgram.UnsupportedProgramException("vertex output '" + name + "' is also a geometry shader output");
                }
                consumed &= gsInputs.containsKey(name);
            }
            // Not read by the GS (e.g. iris_FogFragCoord for the fragment stage): passed through from the last corner.
            String replacement = consumed ? declaration[1] + " " + declaration[2] + ";" : declaration[0] + "out " + declaration[1] + " " + declaration[2] + ";";
            vsText = vsText.replace("@@VSOUT" + i + "@@", replacement);
        }
        out.append(vsText);
        // GS state
        out.append("vec4 ").append(PREFIX).append("inPosition[3];\n");
        for (Varying input : gsInputs.values()) {
            out.append(input.type).append(' ').append(PREFIX).append("in_").append(input.name).append("[3];\n");
        }
        out.append("vec4 ").append(PREFIX).append("curPosition;\nvec4 ").append(PREFIX).append("ringPosition[3];\n");
        for (Varying output : gsOutputs.values()) {
            out.append(output.qualifiers).append("out ").append(output.type).append(' ').append(output.name).append(";\n");
            out.append(output.type).append(' ').append(PREFIX).append("cur_").append(output.name).append(";\n");
            out.append(output.type).append(' ').append(PREFIX).append("ring_").append(output.name).append("[3];\n");
        }
        out.append("int ").append(PREFIX).append("strip;\nint ").append(PREFIX).append("tri;\nint ").append(PREFIX).append("targetTri;\nint ")
                .append(PREFIX).append("targetCorner;\nbool ").append(PREFIX).append("found;\n");
        // EmitVertex / EndPrimitive
        out.append("void ").append(PREFIX).append("emit() {\n");
        out.append("    int r = ").append(PREFIX).append("strip % 3;\n");
        out.append("    ").append(PREFIX).append("ringPosition[r] = ").append(PREFIX).append("curPosition;\n");
        for (Varying output : gsOutputs.values()) {
            out.append("    ").append(PREFIX).append("ring_").append(output.name).append("[r] = ").append(PREFIX).append("cur_").append(output.name).append(";\n");
        }
        out.append("    ").append(PREFIX).append("strip++;\n");
        out.append("    if (").append(PREFIX).append("strip >= 3) {\n");
        out.append("        if (").append(PREFIX).append("tri == ").append(PREFIX).append("targetTri) {\n");
        out.append("            int m = ").append(PREFIX).append("strip - 3;\n");
        out.append("            int k = ").append(PREFIX).append("targetCorner;\n");
        out.append("            if ((m & 1) == 1 && k < 2) k = 1 - k;\n");
        out.append("            int s = (m + k) % 3;\n");
        out.append("            gl_Position = ").append(PREFIX).append("ringPosition[s];\n");
        for (Varying output : gsOutputs.values()) {
            out.append("            ").append(output.name).append(" = ").append(PREFIX).append("ring_").append(output.name).append("[s];\n");
        }
        out.append("            ").append(PREFIX).append("found = true;\n");
        out.append("        }\n");
        out.append("        ").append(PREFIX).append("tri++;\n");
        out.append("    }\n}\n");
        out.append("void ").append(PREFIX).append("endPrimitive() {\n    ").append(PREFIX).append("strip = 0;\n}\n");
        gsTexts.forEach(t -> out.append(t).append('\n'));
        // leading copy (side effects) and the other slots' copy (side effects removed)
        out.append(gsMain.replaceFirst("\\bmain\\s*\\(", PREFIX + "lead(")).append('\n');
        out.append("int ").append(PREFIX).append("noAtomic(int v) { return 0; }\n");
        out.append("uint ").append(PREFIX).append("noAtomic(uint v) { return 0u; }\n");
        out.append("float ").append(PREFIX).append("noAtomic(float v) { return 0.0; }\n");
        out.append("void ").append(PREFIX).append("noStore() {}\n");
        for (Map.Entry<String, Integer> effect : SIDE_EFFECTS.entrySet()) {
            String args = argList(effect.getValue());
            String last = "a" + (effect.getValue() - 1);
            String body = effect.getKey().equals("imageStore") ? PREFIX + "noStore()" : PREFIX + "noAtomic(" + last + ")";
            out.append("#define ").append(effect.getKey()).append('(').append(args).append(") ").append(body).append('\n');
        }
        out.append(gsMain.replaceFirst("\\bmain\\s*\\(", PREFIX + "follow(")).append('\n');
        for (String effect : SIDE_EFFECTS.keySet()) {
            out.append("#undef ").append(effect).append('\n');
        }

        // vertex pulling
        int stride = format.getVertexSize();
        out.append("uint ").append(PREFIX).append("u8(uint a) { return (").append(PREFIX).append("vtx[a >> 2u] >> ((a & 3u) * 8u)) & 0xFFu; }\n");
        out.append("uint ").append(PREFIX).append("u16(uint a) { return (").append(PREFIX).append("vtx[a >> 2u] >> ((a & 2u) * 8u)) & 0xFFFFu; }\n");
        out.append("uint ").append(PREFIX).append("u32(uint a) { return ").append(PREFIX).append("vtx[a >> 2u]; }\n");
        out.append("void ").append(PREFIX).append("load(uint v) {\n");
        out.append("    uint base = v * ").append(stride).append("u;\n");
        for (Map.Entry<String, String> input : vsInputs.entrySet()) {
            VertexFormatElement element = element(format, input.getKey());
            if (element == null) {
                out.append("    ").append(input.getKey()).append(" = ").append(GbufferProgram.defaultValue(input.getValue(), input.getKey())).append(";\n");
                continue;
            }
            out.append("    ").append(input.getKey()).append(" = ").append(decode(element, input.getValue())).append(";\n");
        }
        out.append("}\n");
        out.append("uint ").append(PREFIX).append("vertex(int k) {\n");
        out.append("    int mode = ").append(PREFIX).append("param[1];\n");
        out.append("    uint i = uint(").append(PREFIX).append("param[2] + k);\n");
        out.append("    if (mode == 0) return i;\n");
        out.append("    uint index = mode == 1 ? (").append(PREFIX).append("idx[i >> 1u] >> ((i & 1u) * 16u)) & 0xFFFFu : ").append(PREFIX).append("idx[i];\n");
        out.append("    return uint(int(index) + ").append(PREFIX).append("param[0]);\n}\n");

        // entry point
        out.append("void main() {\n");
        out.append("    int slot = gl_VertexIndex % ").append(slots).append(";\n");
        out.append("    int prim = gl_VertexIndex / ").append(slots).append(";\n");
        out.append("    for (int c = 0; c < 3; c++) {\n");
        out.append("        uint v = ").append(PREFIX).append("vertex(prim * 3 + c);\n");
        out.append("        ").append(PREFIX).append("vertexId = int(v);\n");
        out.append("        ").append(PREFIX).append("load(v);\n");
        out.append("        ").append(PREFIX).append("vs();\n");
        out.append("        ").append(PREFIX).append("inPosition[c] = gl_Position;\n");
        for (Varying input : gsInputs.values()) {
            if (vsOutputs.containsKey(input.name)) {
                out.append("        ").append(PREFIX).append("in_").append(input.name).append("[c] = ").append(input.name).append(";\n");
            }
        }
        out.append("    }\n");
        out.append("    ").append(PREFIX).append("primitiveId = prim;\n");
        out.append("    ").append(PREFIX).append("targetTri = slot / 3;\n");
        out.append("    ").append(PREFIX).append("targetCorner = slot % 3;\n");
        out.append("    ").append(PREFIX).append("strip = 0;\n    ").append(PREFIX).append("tri = 0;\n    ").append(PREFIX).append("found = false;\n");
        out.append("    if (slot == 0) ").append(PREFIX).append("lead(); else ").append(PREFIX).append("follow();\n");
        out.append("    if (!").append(PREFIX).append("found) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);\n");
        out.append("}\n");
        return new Merged(out.toString(), slots);
    }

    private static boolean sameSignature(final String a, final String b) {
        return a.substring(0, Math.max(0, a.indexOf('{'))).replaceAll("\\s+", "").equals(b.substring(0, Math.max(0, b.indexOf('{'))).replaceAll("\\s+", ""));
    }

    private static String argList(final int count) {
        StringBuilder args = new StringBuilder();
        for (int i = 0; i < count; i++) {
            args.append(i == 0 ? "" : ", ").append('a').append(i);
        }
        return args.toString();
    }

    private static int version(final String source) {
        Matcher m = VERSION.matcher(source);
        return m.find() ? Integer.parseInt(m.group(1)) : 330;
    }

    /** Declared names of a declarator list ({@code a, b[2] = x}). */
    private static List<String> names(final String declarators) {
        List<String> names = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : declarators.toCharArray()) {
            if (c == '(' || c == '[') depth++;
            if (c == ')' || c == ']') depth--;
            if (c == ',' && depth == 0) {
                names.add(firstIdentifier(current.toString()));
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        names.add(firstIdentifier(current.toString()));
        return names;
    }

    private static String firstIdentifier(final String text) {
        Matcher m = IDENTIFIER.matcher(text);
        return m.find() ? m.group() : text.trim();
    }

    @Nullable
    private static VertexFormatElement element(final VertexFormat format, final String input) {
        for (VertexFormatElement element : format.getElements()) {
            if (input.equals(element.name()) || input.equals("iris_" + element.name())) {
                return element;
            }
        }
        return null;
    }

    /** GLSL expression decoding one vertex element at {@code base} into the shader input type. */
    static String decode(final VertexFormatElement element, final String type) throws PostProgram.UnsupportedProgramException {
        GpuFormat format = element.format();
        String name = format.name();
        Matcher m = Pattern.compile("^([RGBA]+)(\\d+)_(UNORM|SNORM|UINT|SINT|FLOAT)$").matcher(name);
        if (!m.matches()) {
            throw new PostProgram.UnsupportedProgramException("vertex element format " + name + " cannot be pulled");
        }
        int count = m.group(1).length();
        int bits = Integer.parseInt(m.group(2));
        String kind = m.group(3);
        int bytes = bits / 8;
        boolean integerTarget = type.startsWith("i") || type.startsWith("u");
        String[] components = new String[4];
        for (int i = 0; i < 4; i++) {
            if (i >= count) {
                components[i] = i == 3 ? (integerTarget ? "1" : "1.0") : (integerTarget ? "0" : "0.0");
                continue;
            }
            String address = "base + " + (element.offset() + i * bytes) + "u";
            String raw = PREFIX + "u" + bits + "(" + address + ")";
            String value = switch (kind) {
                case "UNORM" -> "(float(" + raw + ") / " + ((1L << bits) - 1) + ".0)";
                case "SNORM" -> "max(float((int(" + raw + ") << " + (32 - bits) + ") >> " + (32 - bits) + ") / " + ((1L << (bits - 1)) - 1) + ".0, -1.0)";
                case "UINT" -> raw;
                case "SINT" -> "((int(" + raw + ") << " + (32 - bits) + ") >> " + (32 - bits) + ")";
                default -> bits == 16 ? "unpackHalf2x16(" + raw + ").x" : "uintBitsToFloat(" + raw + ")";
            };
            boolean integerSource = kind.equals("UINT") || kind.equals("SINT");
            if (integerTarget && !integerSource) {
                value = "int(" + value + ")";
            } else if (!integerTarget && integerSource) {
                value = "float(" + value + ")";
            } else if (integerTarget) {
                value = "int(" + value + ")";
            }
            components[i] = value;
        }
        String vec4 = (integerTarget ? "ivec4(" : "vec4(") + String.join(", ", components) + ")";
        return type + "(" + vec4 + ")";
    }

    /** Splits translation-unit text into top-level directives, declarations and function definitions. */
    private static List<Item> split(final String source) {
        List<Item> items = new ArrayList<>();
        int n = source.length();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '#') {
                int end = i;
                while (end < n && source.charAt(end) != '\n') {
                    end += source.charAt(end) == '\\' && end + 1 < n ? 2 : 1;
                }
                items.add(new Item(source.substring(i, end), Kind.DIRECTIVE, ""));
                i = end;
                continue;
            }
            if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? n : end;
                continue;
            }
            if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            int start = i;
            int depth = 0;
            boolean function = false;
            while (i < n) {
                char ch = source.charAt(i);
                if (source.startsWith("//", i)) {
                    int end = source.indexOf('\n', i);
                    i = end < 0 ? n : end;
                    continue;
                }
                if (source.startsWith("/*", i)) {
                    int end = source.indexOf("*/", i + 2);
                    i = end < 0 ? n : end + 2;
                    continue;
                }
                if (ch == '{' || ch == '(' || ch == '[') {
                    depth++;
                } else if (ch == '}' || ch == ')' || ch == ']') {
                    depth--;
                    if (ch == '}' && depth == 0) {
                        int j = i + 1;
                        while (j < n && Character.isWhitespace(source.charAt(j))) j++;
                        boolean instance = j < n && source.charAt(j) == ';';
                        if (!instance) {
                            Matcher id = IDENTIFIER.matcher(source).region(j, n);
                            if (id.lookingAt()) {
                                int k = id.end();
                                while (k < n && Character.isWhitespace(source.charAt(k))) k++;
                                instance = k < n && (source.charAt(k) == ';' || source.charAt(k) == '[');
                            }
                        }
                        if (!instance) {
                            function = true;
                            i++;
                            break;
                        }
                    }
                } else if (ch == ';' && depth == 0) {
                    i++;
                    break;
                }
                i++;
            }
            String text = source.substring(start, i);
            if (function) {
                String header = text.substring(0, text.indexOf('{'));
                int paren = header.indexOf('(');
                String before = paren < 0 ? header : header.substring(0, paren);
                Matcher id = IDENTIFIER.matcher(before);
                String name = "";
                while (id.find()) name = id.group();
                boolean block = header.contains("struct") || paren < 0;
                items.add(new Item(text, block ? Kind.DECLARATION : Kind.FUNCTION, name));
            } else {
                items.add(new Item(text, Kind.DECLARATION, declaredName(text)));
            }
        }
        return items;
    }

    private static String declaredName(final String text) {
        String t = text.trim();
        int brace = t.indexOf('{');
        if (brace >= 0) {
            // block: the block name (identifier before '{')
            Matcher id = IDENTIFIER.matcher(t.substring(0, brace));
            String name = "";
            while (id.find()) name = id.group();
            return name;
        }
        int end = t.indexOf('=');
        String head = (end >= 0 ? t.substring(0, end) : t.substring(0, Math.max(0, t.length() - 1))).replaceAll("\\[[^\\]]*\\]", " ");
        Matcher id = IDENTIFIER.matcher(head);
        String name = "";
        while (id.find()) name = id.group();
        return name;
    }

    /**
     * Replaces the draws of a merged pipeline: binds the vertex/index buffers and draw parameters and draws
     * {@code slots} vertices per input triangle.
     */
    static final class Interceptor implements NativiumInterop.DrawInterceptor {
        private final String name;
        private final int slots;
        private final MemorySegment params = Arena.ofAuto().allocate(16, 16);
        private final Set<String> warned = new HashSet<>();

        Interceptor(final String name, final int slots) {
            this.name = name;
            this.slots = slots;
        }

        @Override
        public void drawIndexed(final MTLRenderCommandEncoder encoder, final MTLBuffer indexBuffer, final boolean index16, final int firstIndex,
                                final int indexCount, final int baseVertex, final MTLBuffer vertexBuffer, final long vertexOffset) {
            int triangles = indexCount / 3;
            if (triangles <= 0) {
                return;
            }
            params.set(ValueLayout.JAVA_INT, 0, baseVertex);
            params.set(ValueLayout.JAVA_INT, 4, index16 ? 1 : 2);
            params.set(ValueLayout.JAVA_INT, 8, firstIndex);
            params.set(ValueLayout.JAVA_INT, 12, 0);
            encoder.setVertexBuffer(vertexBuffer, vertexOffset, VERTEX_INDEX);
            encoder.setVertexBuffer(indexBuffer, 0, INDEX_INDEX);
            encoder.setVertexBytes(params, 16, PARAMS_INDEX);
            encoder.drawPrimitives(MTLPrimitiveType.Triangle, 0, (long) triangles * slots);
        }

        @Override
        public void draw(final MTLRenderCommandEncoder encoder, final int firstVertex, final int vertexCount, final MTLBuffer vertexBuffer, final long vertexOffset) {
            int triangles = vertexCount / 3;
            if (triangles <= 0) {
                return;
            }
            params.set(ValueLayout.JAVA_INT, 0, 0);
            params.set(ValueLayout.JAVA_INT, 4, 0);
            params.set(ValueLayout.JAVA_INT, 8, firstVertex);
            params.set(ValueLayout.JAVA_INT, 12, 0);
            encoder.setVertexBuffer(vertexBuffer, vertexOffset, VERTEX_INDEX);
            encoder.setVertexBuffer(vertexBuffer, vertexOffset, INDEX_INDEX);
            encoder.setVertexBytes(params, 16, PARAMS_INDEX);
            encoder.drawPrimitives(MTLPrimitiveType.Triangle, 0, (long) triangles * slots);
        }

        @Override
        public void unsupportedDraw(final String kind) {
            if (warned.add(kind)) {
                de.nprmedia.nativium.iris.NativiumIris.LOGGER.warn("[nativium-iris] {}: {} draw skipped (geometry-shader emulation draws triangle lists only)", name, kind);
            }
        }
    }
}
