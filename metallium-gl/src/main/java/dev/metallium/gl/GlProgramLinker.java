/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl;

import dev.metallium.shaders.GlslProgramTranslator;
import dev.metallium.shaders.GlslProgramTranslator.BlockMember;
import dev.metallium.shaders.GlslProgramTranslator.ResourceBinding;
import dev.metallium.shaders.GlslProgramTranslator.SpirvStage;
import dev.metallium.shaders.GlslProgramTranslator.Stage;
import dev.metallium.shaders.GlslProgramTranslator.StageResult;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Links a GL program: GLSL → SPIR-V (shaderc) → MSL (SPIRV-Cross, GL clip-space depth fixed up) through
 * {@link GlslProgramTranslator}, then derives what GL exposes by name — uniform locations (std140 offsets of the folded
 * default block per stage), sampler uniforms (Metal texture/sampler slots) and vertex attribute locations.
 */
final class GlProgramLinker {
    private static final Pattern ATTRIBUTE = Pattern.compile("(\\w+)\\s+(\\w+)\\s*\\[\\[attribute\\((\\d+)\\)\\]\\]");
    private static final Pattern SAMPLER = Pattern.compile("sampler\\s+(\\w+?)Smplr\\s*\\[\\[sampler\\((\\d+)\\)\\]\\]");
    private static final Pattern VERTEX_INPUT = Pattern.compile("(?m)^(\\s*)((?:(?:highp|mediump|lowp|flat|smooth)\\s+)*)(in|attribute)\\s+(\\w+)\\s+(\\w+)\\s*;");

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

    /** A sampler uniform; its value is a texture unit. Slots are -1 in a stage that does not use it. */
    record Sampler(String name, int location, int vertexTexture, int vertexSampler, int fragmentTexture, int fragmentSampler) {
    }

    record Linked(StageResult vertex, StageResult fragment, int vertexBlockSize, int fragmentBlockSize,
                  int vertexBlockIndex, int fragmentBlockIndex, List<Uniform> uniforms, List<Sampler> samplers,
                  Map<String, Integer> attributes, String log) {
    }

    static final class LinkException extends Exception {
        LinkException(final String message) {
            super(message);
        }
    }

    /** Compiles one stage alone, for glCompileShader's status and info log. Returns null on success, else the log. */
    static @Nullable String checkCompile(final String label, final Stage stage, final String source) {
        try {
            Map<Stage, SpirvStage> result = GlslProgramTranslator.compileToSpirv(label, Map.of(stage, source));
            result.values().forEach(s -> MemoryUtil.memFree(s.spirv()));
            return null;
        } catch (GlslProgramTranslator.TranslationException | RuntimeException e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    static Linked link(final String label, final String vertexSource, final String fragmentSource,
                       final Map<String, Integer> attributeBindings) throws LinkException {
        Map<Stage, String> sources = new EnumMap<>(Stage.class);
        sources.put(Stage.VERTEX, applyAttributeBindings(vertexSource, attributeBindings));
        sources.put(Stage.FRAGMENT, fragmentSource);
        Map<Stage, SpirvStage> spirv;
        try {
            spirv = GlslProgramTranslator.compileToSpirv(label, sources);
        } catch (GlslProgramTranslator.TranslationException | RuntimeException e) {
            throw new LinkException(String.valueOf(e.getMessage()));
        }
        try {
            SpirvStage vs = spirv.get(Stage.VERTEX);
            SpirvStage fs = spirv.get(Stage.FRAGMENT);
            StageResult vertex = GlslProgramTranslator.spirvToMsl(vs, GlslProgramTranslator.DEFAULT_MSL_VERSION, true);
            StageResult fragment = GlslProgramTranslator.spirvToMsl(fs, GlslProgramTranslator.DEFAULT_MSL_VERSION, true);

            List<Uniform> uniforms = new ArrayList<>();
            Map<String, BlockMember> vsMembers = byName(vs.defaultBlock());
            Map<String, BlockMember> fsMembers = byName(fs.defaultBlock());
            Map<String, BlockMember> all = new LinkedHashMap<>(vsMembers);
            fsMembers.forEach(all::putIfAbsent);
            int location = 0;
            for (Map.Entry<String, BlockMember> entry : all.entrySet()) {
                BlockMember v = vsMembers.get(entry.getKey());
                BlockMember f = fsMembers.get(entry.getKey());
                Uniform uniform = new Uniform(entry.getKey(), location,
                        v == null ? null : new StagePlacement(v.offset(), v), f == null ? null : new StagePlacement(f.offset(), f), entry.getValue());
                uniforms.add(uniform);
                location += uniform.locations();
            }

            Map<String, int[]> samplerSlots = new LinkedHashMap<>();
            collectSamplers(vertex, 0, samplerSlots);
            collectSamplers(fragment, 2, samplerSlots);
            List<Sampler> samplers = new ArrayList<>();
            for (Map.Entry<String, int[]> entry : samplerSlots.entrySet()) {
                int[] s = entry.getValue();
                samplers.add(new Sampler(entry.getKey(), location++, s[0], s[1], s[2], s[3]));
            }

            Map<String, Integer> attributes = new LinkedHashMap<>();
            Matcher m = ATTRIBUTE.matcher(vertex.msl());
            while (m.find()) {
                attributes.put(m.group(2), Integer.parseInt(m.group(3)));
            }
            return new Linked(vertex, fragment, vs.defaultBlockSize(), fs.defaultBlockSize(),
                    blockIndex(vertex), blockIndex(fragment), uniforms, samplers, attributes, "");
        } catch (GlslProgramTranslator.TranslationException | RuntimeException e) {
            throw new LinkException(String.valueOf(e.getMessage()));
        } finally {
            spirv.values().forEach(s -> MemoryUtil.memFree(s.spirv()));
        }
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
            if (binding.kind() != ResourceBinding.Kind.SAMPLED_TEXTURE) {
                continue;
            }
            int[] slots = out.computeIfAbsent(binding.name(), _ -> new int[]{-1, -1, -1, -1});
            slots[base] = binding.metalIndex();
            slots[base + 1] = samplerIndex.getOrDefault(binding.name(), binding.metalIndex());
        }
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
