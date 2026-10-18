/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl;

import dev.metallium.shaders.GlslProgramTranslator;
import dev.metallium.shaders.GlslProgramTranslator.BlockMember;
import dev.metallium.shaders.GlslProgramTranslator.ExplicitLayout;
import dev.metallium.shaders.GlslProgramTranslator.ResourceBinding;
import dev.metallium.shaders.GlslProgramTranslator.SpirvStage;
import dev.metallium.shaders.GlslProgramTranslator.Stage;
import dev.metallium.shaders.GlslProgramTranslator.StageResult;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Links a GL program: GLSL → SPIR-V (shaderc) → MSL (SPIRV-Cross, GL clip-space depth fixed up) through
 * {@link GlslProgramTranslator}, then derives what GL exposes by name — uniform locations (std140 offsets of the folded
 * default block per stage; explicit {@code layout(location = N)} kept), sampler uniforms (Metal texture/sampler slots,
 * initial unit from {@code layout(binding = N)}), vertex attribute locations, and the GL binding points of uniform
 * blocks, storage blocks and images (→ Metal slots).
 *
 * <p>Compute programs use the same structures with the compute stage in the "vertex" position and no fragment stage.
 */
final class GlProgramLinker {
    /** MSL language version of every metallium-gl program (GLSL memory barriers need 3.2's thread-scope fences). */
    static final int MSL_VERSION = GlslProgramTranslator.MSL_VERSION_3_2;

    private static final Pattern ATTRIBUTE = Pattern.compile("(\\w+)\\s+(\\w+)\\s*\\[\\[attribute\\((\\d+)\\)\\]\\]");
    private static final Pattern SAMPLER = Pattern.compile("sampler\\s+(\\w+?)Smplr\\s*\\[\\[sampler\\((\\d+)\\)\\]\\]");
    private static final Pattern VERTEX_INPUT = Pattern.compile("(?m)^(\\s*)((?:(?:highp|mediump|lowp|flat|smooth)\\s+)*)(in|attribute)\\s+(\\w+)\\s+(\\w+)\\s*;");
    /** GL extensions whose features metallium-gl does not implement; shaders requiring them fail to compile, as in GL. */
    private static final Pattern UNSUPPORTED_EXTENSION = Pattern.compile(
            "(?m)^[ \\t]*#[ \\t]*extension[ \\t]+(GL_ARB_gpu_shader_int64|GL_NV_\\w+|GL_NVX_\\w+|GL_AMD_\\w+|GL_ARB_bindless_texture|GL_ARB_sparse_\\w+)[ \\t]*:[ \\t]*(require|enable|warn)[ \\t\\r]*$");

    private GlProgramLinker() {
    }

    /** Where a uniform lives in one stage's default block ({@code offset < 0}: not used by that stage). */
    record StagePlacement(int offset, BlockMember member) {
    }

    /** A loose (non-opaque) uniform. Arrays occupy {@code max(1, arraySize)} consecutive locations. */
    record Uniform(String name, int location, @Nullable StagePlacement vertex, @Nullable StagePlacement fragment, BlockMember type) {
        int locations() {
            return Math.max(1, type.arraySize());
        }
    }

    /**
     * A sampler uniform; its value is a texture unit ({@code unit}: initial value, from {@code layout(binding)}). Slots
     * are -1 in a stage that does not use it.
     */
    record Sampler(String name, int location, int vertexTexture, int vertexSampler, int fragmentTexture, int fragmentSampler, int unit) {
    }

    /** A uniform block (UBO) or shader storage block (SSBO) at GL binding point {@code binding}, Metal buffer {@code metalIndex}. */
    record BufferSlot(boolean storage, String name, int binding, int metalIndex) {
    }

    /** An image uniform at GL image unit {@code unit}, Metal texture {@code metalIndex}. */
    record ImageSlot(String name, int unit, int metalIndex) {
    }

    /** GL binding points one stage reads besides loose uniforms and samplers. */
    record StageInterface(List<BufferSlot> buffers, List<ImageSlot> images) {
        static final StageInterface EMPTY = new StageInterface(List.of(), List.of());
    }

    /**
     * {@code fragment == null} for compute programs ({@code vertex} is then the compute stage and {@code localSize} its
     * workgroup size).
     */
    record Linked(StageResult vertex, @Nullable StageResult fragment, int vertexBlockSize, int fragmentBlockSize,
                  int vertexBlockIndex, int fragmentBlockIndex, List<Uniform> uniforms, List<Sampler> samplers,
                  Map<String, Integer> attributes, String log, StageInterface vertexInterface, StageInterface fragmentInterface,
                  int @Nullable [] localSize) {
        boolean compute() {
            return fragment == null;
        }
    }

    static final class LinkException extends Exception {
        LinkException(final String message) {
            super(message);
        }
    }

    /** Compiles one stage alone, for glCompileShader's status and info log. Returns null on success, else the log. */
    static @Nullable String checkCompile(final String label, final Stage stage, final String source) {
        Matcher unsupported = UNSUPPORTED_EXTENSION.matcher(source);
        while (unsupported.find()) {
            if (unsupported.group(2).equals("require") && activeAfterPreprocessing(label, stage, source, unsupported.group())) {
                return "0:0: error: extension " + unsupported.group(1) + " is not supported (metallium-gl)";
            }
        }
        try {
            Map<Stage, SpirvStage> result = GlslProgramTranslator.compileToSpirv(label, Map.of(stage, glCompat(source)));
            result.values().forEach(s -> MemoryUtil.memFree(s.spirv()));
            return null;
        } catch (GlslProgramTranslator.TranslationException | RuntimeException e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    /** True if {@code directive} survives the preprocessor (is not inside a false #if). */
    private static boolean activeAfterPreprocessing(final String label, final Stage stage, final String source, final String directive) {
        try {
            String marked = glCompat(source.replace(directive, "METALLIUM_ACTIVE_REQUIRE_MARKER"));
            return GlslProgramTranslator.preprocess(label, stage, marked).contains("METALLIUM_ACTIVE_REQUIRE_MARKER");
        } catch (GlslProgramTranslator.TranslationException | RuntimeException e) {
            return true;
        }
    }

    static Linked link(final String label, final String vertexSource, final String fragmentSource,
                       final Map<String, Integer> attributeBindings) throws LinkException {
        Map<Stage, String> sources = new EnumMap<>(Stage.class);
        sources.put(Stage.VERTEX, glCompat(applyAttributeBindings(vertexSource, attributeBindings)));
        sources.put(Stage.FRAGMENT, glCompat(fragmentSource));
        return linkStages(label, sources);
    }

    static Linked linkCompute(final String label, final String computeSource) throws LinkException {
        return linkStages(label, Map.of(Stage.COMPUTE, glCompat(computeSource)));
    }

    private static Linked linkStages(final String label, final Map<Stage, String> sources) throws LinkException {
        boolean compute = sources.containsKey(Stage.COMPUTE);
        Map<String, ExplicitLayout> explicit = new LinkedHashMap<>();
        Map<Stage, SpirvStage> spirv;
        try {
            for (Map.Entry<Stage, String> entry : sources.entrySet()) {
                explicit.putAll(GlslProgramTranslator.explicitLayouts(GlslProgramTranslator.preprocess(label, entry.getKey(), entry.getValue())));
            }
            spirv = GlslProgramTranslator.compileToSpirv(label, sources);
        } catch (GlslProgramTranslator.TranslationException | RuntimeException e) {
            throw new LinkException(String.valueOf(e.getMessage()));
        }
        try {
            SpirvStage vs = spirv.get(compute ? Stage.COMPUTE : Stage.VERTEX);
            SpirvStage fs = compute ? null : spirv.get(Stage.FRAGMENT);
            StageResult vertex = GlslProgramTranslator.spirvToMsl(vs, MSL_VERSION, !compute);
            StageResult fragment = fs == null ? null : GlslProgramTranslator.spirvToMsl(fs, MSL_VERSION, true);

            Map<String, BlockMember> vsMembers = byName(vs.defaultBlock());
            Map<String, BlockMember> fsMembers = fs == null ? Map.of() : byName(fs.defaultBlock());
            Map<String, BlockMember> all = new LinkedHashMap<>(vsMembers);
            fsMembers.forEach(all::putIfAbsent);

            Map<String, int[]> samplerSlots = new LinkedHashMap<>();
            collectSamplers(vertex, 0, samplerSlots);
            if (fragment != null) {
                collectSamplers(fragment, 2, samplerSlots);
            }

            // Locations: explicit layout(location = N) first, then the rest in declaration order around them.
            Set<Integer> taken = new HashSet<>();
            for (Map.Entry<String, BlockMember> entry : all.entrySet()) {
                int at = explicitLocation(explicit, entry.getKey());
                if (at >= 0) for (int i = 0; i < Math.max(1, entry.getValue().arraySize()); i++) taken.add(at + i);
            }
            for (String name : samplerSlots.keySet()) {
                int at = explicitLocation(explicit, name);
                if (at >= 0) taken.add(at);
            }
            int[] next = {0};
            List<Uniform> uniforms = new ArrayList<>();
            for (Map.Entry<String, BlockMember> entry : all.entrySet()) {
                BlockMember v = vsMembers.get(entry.getKey());
                BlockMember f = fsMembers.get(entry.getKey());
                int count = Math.max(1, entry.getValue().arraySize());
                int location = explicitLocation(explicit, entry.getKey());
                if (location < 0) location = allocate(taken, next, count);
                uniforms.add(new Uniform(entry.getKey(), location,
                        v == null ? null : new StagePlacement(v.offset(), v), f == null ? null : new StagePlacement(f.offset(), f), entry.getValue()));
            }
            List<Sampler> samplers = new ArrayList<>();
            for (Map.Entry<String, int[]> entry : samplerSlots.entrySet()) {
                int[] s = entry.getValue();
                int location = explicitLocation(explicit, entry.getKey());
                if (location < 0) location = allocate(taken, next, 1);
                ExplicitLayout layout = explicit.get(entry.getKey());
                int unit = layout != null && layout.binding() >= 0 ? layout.binding() : 0;
                samplers.add(new Sampler(entry.getKey(), location, s[0], s[1], s[2], s[3], unit));
            }

            Map<String, Integer> attributes = new LinkedHashMap<>();
            if (!compute) {
                Matcher m = ATTRIBUTE.matcher(vertex.msl());
                while (m.find()) {
                    attributes.put(m.group(2), Integer.parseInt(m.group(3)));
                }
            }
            return new Linked(vertex, fragment, vs.defaultBlockSize(), fs == null ? 0 : fs.defaultBlockSize(),
                    blockIndex(vertex), fragment == null ? -1 : blockIndex(fragment), uniforms, samplers, attributes, "",
                    stageInterface(vertex, explicit), fragment == null ? StageInterface.EMPTY : stageInterface(fragment, explicit),
                    compute ? vertex.localSize() : null);
        } catch (GlslProgramTranslator.TranslationException | RuntimeException e) {
            throw new LinkException(String.valueOf(e.getMessage()));
        } finally {
            spirv.values().forEach(s -> MemoryUtil.memFree(s.spirv()));
        }
    }

    private static int explicitLocation(final Map<String, ExplicitLayout> explicit, final String name) {
        ExplicitLayout layout = explicit.get(name);
        return layout == null ? -1 : layout.location();
    }

    private static int allocate(final Set<Integer> taken, final int[] next, final int count) {
        outer:
        while (true) {
            for (int i = 0; i < count; i++) {
                if (taken.contains(next[0] + i)) {
                    next[0] += i + 1;
                    continue outer;
                }
            }
            int location = next[0];
            for (int i = 0; i < count; i++) taken.add(location + i);
            next[0] += count;
            return location;
        }
    }

    /** UBOs other than the default block, SSBOs and images; GL binding = explicit layout(binding), else 0 (GL default). */
    private static StageInterface stageInterface(final StageResult stage, final Map<String, ExplicitLayout> explicit) {
        List<BufferSlot> buffers = new ArrayList<>();
        List<ImageSlot> images = new ArrayList<>();
        for (ResourceBinding binding : stage.resources()) {
            if (binding.metalIndex() < 0) {
                continue; // declared but unused by the entry point: SPIRV-Cross assigns no Metal slot
            }
            ExplicitLayout layout = explicit.get(binding.name());
            int glBinding = layout != null && layout.binding() >= 0 ? layout.binding() : 0;
            switch (binding.kind()) {
                case UNIFORM_BUFFER -> {
                    if (!binding.name().equals(stage.defaultUniformBlock())) {
                        buffers.add(new BufferSlot(false, binding.name(), glBinding, binding.metalIndex()));
                    }
                }
                case STORAGE_BUFFER -> buffers.add(new BufferSlot(true, binding.name(), glBinding, binding.metalIndex()));
                case STORAGE_IMAGE -> images.add(new ImageSlot(binding.name(), glBinding, binding.metalIndex()));
                default -> {
                }
            }
        }
        return new StageInterface(List.copyOf(buffers), List.copyOf(images));
    }

    private static Map<String, BlockMember> byName(final List<BlockMember> members) {
        Map<String, BlockMember> map = new LinkedHashMap<>();
        for (BlockMember member : members) {
            map.put(member.name(), member);
        }
        return map;
    }

    private static int blockIndex(final StageResult stage) {
        if (stage.defaultUniformBlock() == null) {
            return -1;
        }
        for (ResourceBinding binding : stage.resources()) {
            if (binding.kind() == ResourceBinding.Kind.UNIFORM_BUFFER && binding.name().equals(stage.defaultUniformBlock())) {
                return binding.metalIndex();
            }
        }
        return -1;
    }

    /** slots[base] = texture index, slots[base + 1] = sampler index of one stage. */
    private static void collectSamplers(final StageResult stage, final int base, final Map<String, int[]> out) {
        Map<String, Integer> samplerIndex = new LinkedHashMap<>();
        Matcher m = SAMPLER.matcher(stage.msl());
        while (m.find()) {
            samplerIndex.put(m.group(1), Integer.parseInt(m.group(2)));
        }
        for (ResourceBinding binding : stage.resources()) {
            if (binding.kind() != ResourceBinding.Kind.SAMPLED_TEXTURE || binding.metalIndex() < 0) {
                continue;
            }
            int[] slots = out.computeIfAbsent(binding.name(), _ -> new int[]{-1, -1, -1, -1});
            slots[base] = binding.metalIndex();
            slots[base + 1] = samplerIndex.getOrDefault(binding.name(), binding.metalIndex());
        }
    }

    static final String DEPTH_RANGE_NEAR = "metallium_DepthRangeNear";
    static final String DEPTH_RANGE_FAR = "metallium_DepthRangeFar";
    static final String DEPTH_RANGE_DIFF = "metallium_DepthRangeDiff";
    private static final Pattern DEPTH_RANGE = Pattern.compile("\\bgl_DepthRange\\s*\\.\\s*(near|far|diff)\\b");
    private static final Pattern UNSUPPORTED_MACRO = Pattern.compile("\\b(GL_ARB_gpu_shader_int64|GL_NV_\\w+|GL_NVX_\\w+|GL_AMD_\\w+|GL_ARB_bindless_texture|GL_ARB_sparse_\\w+)\\b");
    private static final Pattern UNSUPPORTED_MACRO_TEST = Pattern.compile("(?m)^[ \\t]*#[ \\t]*(if|ifdef|ifndef|elif)\\b.*$");
    private static final Pattern LAST_DIRECTIVE = Pattern.compile("(?m)^[ \\t]*#[ \\t]*(version|extension)\\b.*$");

    /**
     * GL-only built-ins that Vulkan GLSL (glslang's target) lacks, rewritten to plain uniforms the context fills in:
     * {@code gl_DepthRange.near/far/diff} → {@code metallium_DepthRange{Near,Far,Diff}} (set from glDepthRange per draw).
     */
    static String glCompat(final String original) {
        // "#extension X : enable" of an unsupported extension is only a warning in GL, and the X macro stays undefined:
        // drop the directive so glslang does not enable (and define) it either.
        String source = UNSUPPORTED_EXTENSION.matcher(original).replaceAll("// metallium-gl: $1 not supported");
        // glslang predefines the macros of every extension it knows (GL only defines those it supports): make
        // "#ifdef GL_ARB_gpu_shader_int64"-style tests see unsupported extensions as undefined.
        Matcher conditional = UNSUPPORTED_MACRO_TEST.matcher(source);
        StringBuilder tests = new StringBuilder();
        while (conditional.find()) {
            conditional.appendReplacement(tests, Matcher.quoteReplacement(UNSUPPORTED_MACRO.matcher(conditional.group()).replaceAll("METALLIUM_UNSUPPORTED_$1")));
        }
        conditional.appendTail(tests);
        source = tests.toString();
        if (!source.contains("gl_DepthRange")) {
            return source;
        }
        Matcher m = DEPTH_RANGE.matcher(source);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String field = m.group(1);
            m.appendReplacement(sb, field.equals("near") ? DEPTH_RANGE_NEAR : field.equals("far") ? DEPTH_RANGE_FAR : DEPTH_RANGE_DIFF);
        }
        m.appendTail(sb);
        String rewritten = sb.toString();
        Matcher directive = LAST_DIRECTIVE.matcher(rewritten);
        int insertAt = 0;
        while (directive.find()) insertAt = directive.end();
        String declarations = "\nuniform float " + DEPTH_RANGE_NEAR + ";\nuniform float " + DEPTH_RANGE_FAR + ";\nuniform float " + DEPTH_RANGE_DIFF + ";\n";
        return rewritten.substring(0, insertAt) + declarations + rewritten.substring(insertAt);
    }

    /** glBindAttribLocation: gives matching vertex inputs without a layout qualifier an explicit location. */
    static String applyAttributeBindings(final String source, final Map<String, Integer> bindings) {
        if (bindings.isEmpty()) {
            return source;
        }
        Matcher m = VERTEX_INPUT.matcher(source);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Integer location = bindings.get(m.group(5));
            int lineStart = source.lastIndexOf('\n', Math.max(0, m.start() - 1)) + 1;
            boolean hasLayout = source.substring(lineStart, m.start(3)).contains("layout");
            if (location == null || hasLayout) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + "layout(location = " + location + ") " + m.group(2) + "in " + m.group(4) + " " + m.group(5) + ";"));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
