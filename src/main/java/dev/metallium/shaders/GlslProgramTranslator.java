package dev.metallium.shaders;

import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates an OpenGL-style GLSL program (as produced by a shader-pack front end such as Iris's transformer:
 * GLSL 330+ core, loose uniforms, unlocated varyings, unbound samplers) into Metal Shading Language.
 *
 * <p>Pipeline: varying locations assigned by name across stages → shaderc (glslang) with Vulkan rules relaxed, which
 * folds loose uniforms into one std140 block → SPIRV-Cross MSL backend → reflection of the uniform block layout and
 * of texture/buffer/image bindings, so the host can write uniforms and bind resources by name.
 *
 * <p>Uses only the shaderc and SPIRV-Cross natives that Minecraft 26.3 already ships through LWJGL.
 */
public final class GlslProgramTranslator {
    /** MSL version for generated code. 3.1 = macOS 14+, required for native image atomics. */
    public static final int DEFAULT_MSL_VERSION = 0x030100;

    private static final Pattern VERSION = Pattern.compile("^\\s*#version\\s+\\d+.*$", Pattern.MULTILINE);
    private static final Pattern GLOBAL_IO = Pattern.compile(
            "^(\\s*)((?:flat|smooth|noperspective|centroid|invariant|\\s)*)\\b(in|out|varying)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(\\[[^\\]]*\\])?\\s*;",
            Pattern.MULTILINE);

    private GlslProgramTranslator() {
    }

    public enum Stage {
        VERTEX(Shaderc.shaderc_glsl_vertex_shader),
        FRAGMENT(Shaderc.shaderc_glsl_fragment_shader),
        COMPUTE(Shaderc.shaderc_glsl_compute_shader);

        final int shadercKind;

        Stage(final int shadercKind) {
            this.shadercKind = shadercKind;
        }
    }

    /** One member of the folded default uniform block ({@code gl_DefaultUniformBlock}). */
    public record UniformMember(String name, int offset, int size) {
    }

    /** A bound resource (texture, sampler, buffer, image) with its Metal index per stage. */
    public record ResourceBinding(String name, Kind kind, int metalIndex) {
        public enum Kind { UNIFORM_BUFFER, STORAGE_BUFFER, SAMPLED_TEXTURE, STORAGE_IMAGE }
    }

    public record StageResult(
            Stage stage,
            String msl,
            String entryPoint,
            @Nullable String defaultUniformBlock,
            int defaultUniformBlockSize,
            List<UniformMember> uniforms,
            List<ResourceBinding> resources
    ) {
    }

    public record Program(String name, Map<Stage, StageResult> stages) {
    }

    public static final class TranslationException extends Exception {
        public TranslationException(final String message) {
            super(message);
        }
    }

    public static Program translate(final String programName, final Map<Stage, String> sources) throws TranslationException {
        return translate(programName, sources, DEFAULT_MSL_VERSION);
    }

    public static Program translate(final String programName, final Map<Stage, String> sources, final int mslVersion) throws TranslationException {
        Map<Stage, String> located = assignVaryingLocations(sources);
        Map<Stage, StageResult> results = new EnumMap<>(Stage.class);
        for (Map.Entry<Stage, String> entry : located.entrySet()) {
            ByteBuffer spirv = compileToSpirv(programName, entry.getKey(), entry.getValue());
            try {
                results.put(entry.getKey(), spirvToMsl(entry.getKey(), spirv, mslVersion));
            } finally {
                MemoryUtil.memFree(spirv);
            }
        }
        return new Program(programName, results);
    }

    /**
     * Gives every user varying an explicit location chosen by name, identically in all stages, so separately compiled
     * stages link correctly. Fragment outputs and declarations that already carry a layout qualifier are untouched.
     */
    static Map<Stage, String> assignVaryingLocations(final Map<Stage, String> sources) {
        String vertex = sources.get(Stage.VERTEX);
        String fragment = sources.get(Stage.FRAGMENT);
        if (vertex == null || fragment == null) {
            return sources;
        }
        TreeSet<String> names = new TreeSet<>();
        collectIo(vertex, "out", names);
        collectIo(fragment, "in", names);
        Map<String, Integer> locations = new LinkedHashMap<>();
        int next = 0;
        for (String name : names) {
            locations.put(name, next++);
        }
        Map<Stage, String> result = new EnumMap<>(Stage.class);
        result.putAll(sources);
        result.put(Stage.VERTEX, enableSeparateShaderObjects(applyLocations(vertex, "out", locations)));
        result.put(Stage.FRAGMENT, enableSeparateShaderObjects(applyLocations(fragment, "in", locations)));
        return result;
    }

    /** Explicit varying locations need GLSL 410 or this extension; packs commonly target 120-330. */
    private static String enableSeparateShaderObjects(final String source) {
        if (source.contains("GL_ARB_separate_shader_objects")) {
            return source;
        }
        Matcher version = VERSION.matcher(source);
        if (!version.find()) {
            return source;
        }
        return source.substring(0, version.end()) + "\n#extension GL_ARB_separate_shader_objects : enable" + source.substring(version.end());
    }

    private static void collectIo(final String source, final String direction, final TreeSet<String> out) {
        Matcher m = GLOBAL_IO.matcher(source);
        while (m.find()) {
            if (isDirection(m.group(3), direction) && !hasLayoutBefore(source, m.start())) {
                out.add(m.group(5));
            }
        }
    }

    private static String applyLocations(final String source, final String direction, final Map<String, Integer> locations) {
        Matcher m = GLOBAL_IO.matcher(source);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Integer location = locations.get(m.group(5));
            if (!isDirection(m.group(3), direction) || location == null || hasLayoutBefore(source, m.start())) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            String keyword = m.group(3).equals("varying") ? direction : m.group(3);
            String replacement = m.group(1) + "layout(location = " + location + ") " + m.group(2) + keyword + " " + m.group(4) + " " + m.group(5)
                    + (m.group(6) != null ? m.group(6) : "") + ";";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean isDirection(final String keyword, final String direction) {
        return keyword.equals(direction) || keyword.equals("varying");
    }

    private static boolean hasLayoutBefore(final String source, final int declarationStart) {
        int lineStart = source.lastIndexOf('\n', Math.max(0, declarationStart - 1)) + 1;
        return source.substring(lineStart, Math.min(source.length(), declarationStart + 1)).contains("layout");
    }

    static ByteBuffer compileToSpirv(final String programName, final Stage stage, final String source) throws TranslationException {
        if (!VERSION.matcher(source).find()) {
            throw new TranslationException(programName + "/" + stage + ": missing #version directive");
        }
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        try {
            Shaderc.shaderc_compile_options_set_source_language(options, Shaderc.shaderc_source_language_glsl);
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_vulkan_rules_relaxed(options, true);
            Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
            Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
            // -O0 like vanilla: keeps resource names, which reflection-based binding relies on.
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_zero);

            long result = Shaderc.shaderc_compile_into_spv(compiler, source, stage.shadercKind, programName + "." + stage.name().toLowerCase(), "main", options);
            try {
                if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                    throw new TranslationException(programName + "/" + stage + ": " + Shaderc.shaderc_result_get_error_message(result));
                }
                ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
                copy.put(bytes).flip();
                return copy;
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static StageResult spirvToMsl(final Stage stage, final ByteBuffer spirv, final int mslVersion) throws TranslationException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer words = spirv.asIntBuffer();
            PointerBuffer p = stack.mallocPointer(1);
            check(Spvc.spvc_context_create(p), "context_create");
            long context = p.get(0);
            try {
                check(Spvc.spvc_context_parse_spirv(context, words, words.remaining(), p), "parse_spirv");
                long ir = p.get(0);
                check(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, p), "create_compiler");
                long compiler = p.get(0);

                check(Spvc.spvc_compiler_create_compiler_options(compiler, p), "create_compiler_options");
                long options = p.get(0);
                check(Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS), "MSL_PLATFORM");
                check(Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, mslVersion), "MSL_VERSION");
                // Same convention as vanilla pipelines on the Metal backend.
                check(Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, stage == Stage.VERTEX), "FLIP_VERTEX_Y");
                check(Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true), "TEXTURE_BUFFER_NATIVE");
                check(Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_PAD_FRAGMENT_OUTPUT_COMPONENTS, true), "PAD_FRAGMENT_OUTPUT");
                check(Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_1D_AS_2D, true), "TEXTURE_1D_AS_2D");
                check(Spvc.spvc_compiler_install_compiler_options(compiler, options), "install_compiler_options");

                check(Spvc.spvc_compiler_compile(compiler, p), "compile");
                String msl = MemoryUtil.memUTF8(p.get(0));

                List<UniformMember> members = new ArrayList<>();
                List<ResourceBinding> resources = new ArrayList<>();
                String[] defaultBlock = {null};
                int[] defaultBlockSize = {0};
                reflect(stack, compiler, members, resources, defaultBlock, defaultBlockSize);

                PointerBuffer entries = stack.mallocPointer(1);
                PointerBuffer entryCount = stack.mallocPointer(1);
                check(Spvc.spvc_compiler_get_entry_points(compiler, entries, entryCount), "get_entry_points");
                String entryPoint = Spvc.spvc_compiler_get_cleansed_entry_point_name(compiler, "main", executionModel(stage));
                return new StageResult(stage, msl, entryPoint != null ? entryPoint : "main0", defaultBlock[0], defaultBlockSize[0], members, resources);
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static int executionModel(final Stage stage) {
        return switch (stage) {
            case VERTEX -> Spv.SpvExecutionModelVertex;
            case FRAGMENT -> Spv.SpvExecutionModelFragment;
            case COMPUTE -> Spv.SpvExecutionModelGLCompute;
        };
    }

    private static void reflect(
            final MemoryStack stack,
            final long compiler,
            final List<UniformMember> members,
            final List<ResourceBinding> resources,
            final String[] defaultBlock,
            final int[] defaultBlockSize
    ) throws TranslationException {
        PointerBuffer p = stack.mallocPointer(1);
        check(Spvc.spvc_compiler_create_shader_resources(compiler, p), "create_shader_resources");
        long shaderResources = p.get(0);

        for (SpvcReflectedResource ubo : list(stack, shaderResources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER)) {
            String blockName = ubo.nameString();
            int index = Spvc.spvc_compiler_msl_get_automatic_resource_binding(compiler, ubo.id());
            resources.add(new ResourceBinding(blockName, ResourceBinding.Kind.UNIFORM_BUFFER, index));
            if (blockName.endsWith("gl_DefaultUniformBlock") || Spvc.spvc_compiler_get_name(compiler, ubo.base_type_id()).endsWith("gl_DefaultUniformBlock")) {
                defaultBlock[0] = blockName;
                long type = Spvc.spvc_compiler_get_type_handle(compiler, ubo.base_type_id());
                PointerBuffer size = stack.mallocPointer(1);
                check(Spvc.spvc_compiler_get_declared_struct_size(compiler, type, size), "get_declared_struct_size");
                defaultBlockSize[0] = (int) size.get(0);
                int memberCount = Spvc.spvc_type_get_num_member_types(type);
                IntBuffer offset = stack.mallocInt(1);
                PointerBuffer memberSize = stack.mallocPointer(1);
                for (int i = 0; i < memberCount; i++) {
                    check(Spvc.spvc_compiler_type_struct_member_offset(compiler, type, i, offset), "struct_member_offset");
                    check(Spvc.spvc_compiler_get_declared_struct_member_size(compiler, type, i, memberSize), "declared_struct_member_size");
                    members.add(new UniformMember(Spvc.spvc_compiler_get_member_name(compiler, ubo.base_type_id(), i), offset.get(0), (int) memberSize.get(0)));
                }
            }
        }
        for (SpvcReflectedResource ssbo : list(stack, shaderResources, Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER)) {
            resources.add(new ResourceBinding(Spvc.spvc_compiler_get_name(compiler, ssbo.base_type_id()), ResourceBinding.Kind.STORAGE_BUFFER,
                    Spvc.spvc_compiler_msl_get_automatic_resource_binding(compiler, ssbo.id())));
        }
        for (SpvcReflectedResource sampled : list(stack, shaderResources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE)) {
            resources.add(new ResourceBinding(sampled.nameString(), ResourceBinding.Kind.SAMPLED_TEXTURE,
                    Spvc.spvc_compiler_msl_get_automatic_resource_binding(compiler, sampled.id())));
        }
        for (SpvcReflectedResource image : list(stack, shaderResources, Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE)) {
            resources.add(new ResourceBinding(image.nameString(), ResourceBinding.Kind.STORAGE_IMAGE,
                    Spvc.spvc_compiler_msl_get_automatic_resource_binding(compiler, image.id())));
        }
    }

    private static List<SpvcReflectedResource> list(final MemoryStack stack, final long shaderResources, final int type) throws TranslationException {
        PointerBuffer pList = stack.mallocPointer(1);
        PointerBuffer pCount = stack.mallocPointer(1);
        check(Spvc.spvc_resources_get_resource_list_for_type(shaderResources, type, pList, pCount), "get_resource_list_for_type");
        int count = (int) pCount.get(0);
        List<SpvcReflectedResource> out = new ArrayList<>(count);
        if (count > 0) {
            SpvcReflectedResource.Buffer buffer = SpvcReflectedResource.create(pList.get(0), count);
            for (int i = 0; i < count; i++) {
                out.add(buffer.get(i));
            }
        }
        return out;
    }

    private static void check(final int result, final String what) throws TranslationException {
        if (result != Spvc.SPVC_SUCCESS) {
            throw new TranslationException("SPIRV-Cross " + what + " failed (" + result + ")");
        }
    }
}
