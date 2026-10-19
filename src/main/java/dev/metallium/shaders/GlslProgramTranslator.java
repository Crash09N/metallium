package dev.metallium.shaders;

import dev.metallium.shadercache.CacheKey;
import dev.metallium.shadercache.ShaderCache;
import dev.metallium.shadercache.ShaderCacheStats;
import dev.metallium.shadercache.ShaderDiskCache;
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
    /**
     * MSL 3.2 in SPIRV-Cross's encoding (major * 10000 + minor * 100). Note {@link #DEFAULT_MSL_VERSION} is a hex literal
     * SPIRV-Cross reads as a far newer version, so it emits the newest constructs (kept for existing callers).
     * metallium-gl uses this one and compiles with {@code MTLLanguageVersion.Version3_2} (memory-order / thread-scope
     * fences for GLSL memory barriers need 3.2).
     */
    public static final int MSL_VERSION_3_2 = 30200;

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

    /**
     * A bound resource (texture, sampler, buffer, image) with its Metal index per stage. {@code glBinding} is the
     * SPIR-V {@code Binding} decoration: the {@code layout(binding = N)} value if the source has one, otherwise a number
     * glslang assigned (see {@link #explicitLayouts} to tell them apart). -1 if the resource has no binding.
     */
    public record ResourceBinding(String name, Kind kind, int metalIndex, int glBinding) {
        public enum Kind { UNIFORM_BUFFER, STORAGE_BUFFER, SAMPLED_TEXTURE, STORAGE_IMAGE }

        public ResourceBinding(final String name, final Kind kind, final int metalIndex) {
            this(name, kind, metalIndex, -1);
        }
    }

    public record StageResult(
            Stage stage,
            String msl,
            String entryPoint,
            @Nullable String defaultUniformBlock,
            int defaultUniformBlockSize,
            List<UniformMember> uniforms,
            List<ResourceBinding> resources,
            int[] localSize
    ) {
        /** Without a workgroup size (graphics stages). */
        public StageResult(final Stage stage, final String msl, final String entryPoint, final @Nullable String defaultUniformBlock,
                           final int defaultUniformBlockSize, final List<UniformMember> uniforms, final List<ResourceBinding> resources) {
            this(stage, msl, entryPoint, defaultUniformBlock, defaultUniformBlockSize, uniforms, resources, new int[]{1, 1, 1});
        }
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

    /** Scalar kind of a default-uniform-block member. {@code BOOL} members are stored as 32-bit integers. */
    public enum ScalarType { FLOAT, INT, UINT, BOOL, OTHER }

    /**
     * A default-uniform-block member with the std140 layout details a host needs to write values from the CPU.
     * {@code columns} is 1 for scalars/vectors; {@code arraySize} is 0 for non-arrays.
     */
    public record BlockMember(String name, int offset, int size, ScalarType scalar, int vectorSize, int columns,
                              int arraySize, int arrayStride, int matrixStride) {
    }

    /**
     * One stage compiled to SPIR-V (Vulkan rules relaxed) for a backend that consumes SPIR-V itself, e.g. a renderpearl
     * {@code BackendRenderPipeline.CreateInfo}. {@code spirv} is allocated with {@link MemoryUtil#memAlloc} and owned by
     * the caller. {@code defaultBlockName} is the reflected resource name of the folded loose-uniform block, if any.
     */
    public record SpirvStage(Stage stage, ByteBuffer spirv, @Nullable String defaultBlockName, int defaultBlockSize,
                             List<BlockMember> defaultBlock) {
    }

    /**
     * Compiles all stages to SPIR-V and reflects the default uniform block layout of each stage. Varyings are matched
     * by name between the vertex and fragment stage by rewriting their {@code Location} decorations in the SPIR-V
     * (robust against comma-separated declarations, interface qualifiers and macros, unlike a source rewrite).
     * On failure no buffers are leaked.
     */
    public static Map<Stage, SpirvStage> compileToSpirv(final String programName, final Map<Stage, String> sources) throws TranslationException {
        Map<Stage, SpirvStage> results = new EnumMap<>(Stage.class);
        try {
            for (Map.Entry<Stage, String> entry : sources.entrySet()) {
                ByteBuffer spirv = compileToSpirv(programName, entry.getKey(), entry.getValue());
                try {
                    results.put(entry.getKey(), reflectDefaultBlock(entry.getKey(), spirv));
                } catch (TranslationException | RuntimeException e) {
                    MemoryUtil.memFree(spirv);
                    throw e;
                }
            }
            if (results.containsKey(Stage.VERTEX) && results.containsKey(Stage.FRAGMENT)) {
                SpirvStage vertex = results.get(Stage.VERTEX);
                String missing = missingVertexOutputs(vertex.spirv(), results.get(Stage.FRAGMENT).spirv());
                if (!missing.isEmpty()) {
                    // GL tolerates fragment inputs the vertex stage never writes (undefined value): declare them,
                    // unwritten, in the vertex stage so by-name location matching (and Metal linking) still works.
                    System.getLogger("metallium.shaders").log(System.Logger.Level.DEBUG,
                            "{0}: fragment inputs not written by the vertex stage, declaring them there:{1}", programName, missing.replace('\n', ' '));
                    ByteBuffer patched = compileToSpirv(programName, Stage.VERTEX, sources.get(Stage.VERTEX) + "\n" + missing);
                    try {
                        results.put(Stage.VERTEX, reflectDefaultBlock(Stage.VERTEX, patched));
                    } catch (TranslationException | RuntimeException e) {
                        MemoryUtil.memFree(patched);
                        throw e;
                    }
                    MemoryUtil.memFree(vertex.spirv());
                }
                matchVaryingsByName(programName, results.get(Stage.VERTEX).spirv(), results.get(Stage.FRAGMENT).spirv());
            }
        } catch (TranslationException | RuntimeException e) {
            results.values().forEach(stage -> MemoryUtil.memFree(stage.spirv()));
            throw e;
        }
        return results;
    }

    /**
     * GLSL declarations ({@code [flat] [noperspective] [centroid] out <type> name[N];}, one per line) for every
     * fragment input that has no vertex output of the same name; empty if none.
     */
    static String missingVertexOutputs(final ByteBuffer vertex, final ByteBuffer fragment) throws TranslationException {
        java.util.Set<String> written = new java.util.HashSet<>();
        for (Varying v : varyings(vertex, Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT)) {
            written.add(v.name());
        }
        StringBuilder out = new StringBuilder();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer words = fragment.asIntBuffer();
            PointerBuffer p = stack.mallocPointer(1);
            check(Spvc.spvc_context_create(p), "context_create");
            long context = p.get(0);
            try {
                check(Spvc.spvc_context_parse_spirv(context, words, words.remaining(), p), "parse_spirv");
                long ir = p.get(0);
                check(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_NONE, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, p), "create_compiler");
                long compiler = p.get(0);
                check(Spvc.spvc_compiler_create_shader_resources(compiler, p), "create_shader_resources");
                for (SpvcReflectedResource resource : list(stack, p.get(0), Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT)) {
                    String name = resource.nameString();
                    if (written.contains(name)) {
                        continue;
                    }
                    long type = Spvc.spvc_compiler_get_type_handle(compiler, resource.type_id());
                    int size = Spvc.spvc_type_get_vector_size(type);
                    int columns = Spvc.spvc_type_get_columns(type);
                    int base = Spvc.spvc_type_get_basetype(type);
                    boolean integer = base == Spvc.SPVC_BASETYPE_INT32 || base == Spvc.SPVC_BASETYPE_UINT32 || base == Spvc.SPVC_BASETYPE_BOOLEAN;
                    String glslType;
                    if (columns > 1) {
                        glslType = (base == Spvc.SPVC_BASETYPE_FP64 ? "dmat" : "mat") + columns + (columns == size ? "" : "x" + size);
                    } else {
                        String prefix = switch (base) {
                            case Spvc.SPVC_BASETYPE_INT32 -> "i";
                            case Spvc.SPVC_BASETYPE_UINT32 -> "u";
                            case Spvc.SPVC_BASETYPE_BOOLEAN -> "b";
                            case Spvc.SPVC_BASETYPE_FP64 -> "d";
                            default -> "";
                        };
                        glslType = size == 1 ? (prefix.isEmpty() ? "float" : switch (prefix) {
                            case "i" -> "int";
                            case "u" -> "uint";
                            case "b" -> "bool";
                            default -> "double";
                        }) : prefix + "vec" + size;
                    }
                    StringBuilder dims = new StringBuilder();
                    for (int d = 0; d < Spvc.spvc_type_get_num_array_dimensions(type); d++) {
                        dims.append('[').append(Math.max(1, Spvc.spvc_type_get_array_dimension(type, d))).append(']');
                    }
                    boolean flat = integer || Spvc.spvc_compiler_get_decoration(compiler, resource.id(), Spv.SpvDecorationFlat) != 0;
                    out.append(flat ? "flat " : "")
                            .append(Spvc.spvc_compiler_get_decoration(compiler, resource.id(), Spv.SpvDecorationNoPerspective) != 0 ? "noperspective " : "")
                            .append(!flat && Spvc.spvc_compiler_get_decoration(compiler, resource.id(), Spv.SpvDecorationCentroid) != 0 ? "centroid " : "")
                            .append("out ").append(glslType).append(' ').append(name).append(dims).append(";\n");
                }
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
        return out.toString();
    }

    private record Varying(String name, int locationWordOffset, int slots) {
    }

    /** Gives each vertex output / fragment input pair one unique location chosen by name, patching both modules. */
    static void matchVaryingsByName(final String programName, final ByteBuffer vertex, final ByteBuffer fragment) throws TranslationException {
        List<Varying> outputs = varyings(vertex, Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT);
        List<Varying> inputs = varyings(fragment, Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT);
        Map<String, Integer> slots = new java.util.TreeMap<>();
        for (Varying v : outputs) {
            slots.merge(v.name(), v.slots(), Math::max);
        }
        for (Varying v : inputs) {
            if (!slots.containsKey(v.name())) {
                throw new TranslationException(programName + ": fragment input '" + v.name() + "' is not written by the vertex stage");
            }
            slots.merge(v.name(), v.slots(), Math::max);
        }
        Map<String, Integer> locations = new java.util.HashMap<>();
        int next = 0;
        for (Map.Entry<String, Integer> entry : slots.entrySet()) {
            locations.put(entry.getKey(), next);
            next += entry.getValue();
        }
        IntBuffer vertexWords = vertex.asIntBuffer();
        for (Varying v : outputs) {
            vertexWords.put(v.locationWordOffset(), locations.get(v.name()));
        }
        IntBuffer fragmentWords = fragment.asIntBuffer();
        for (Varying v : inputs) {
            fragmentWords.put(v.locationWordOffset(), locations.get(v.name()));
        }
    }

    /**
     * Rewrites the {@code Location} of every stage output of a module in place: new location = {@code mapping(old)}.
     * Used for fragment outputs, e.g. to move a shader pack's {@code DRAWBUFFERS} slot {@code i} to the index of that
     * buffer in a render pass with a different attachment list.
     */
    public static void remapOutputLocations(final ByteBuffer spirv, final java.util.function.IntUnaryOperator mapping) throws TranslationException {
        IntBuffer words = spirv.asIntBuffer();
        for (Varying v : varyings(spirv, Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT)) {
            words.put(v.locationWordOffset(), mapping.applyAsInt(words.get(v.locationWordOffset())));
        }
    }

    /**
     * Names of the uniform buffers, textures and images the entry point statically uses. glslang keeps declared but
     * unused samplers at -O0; shader packs declare many (behind option macros), and Metal allows only 16 samplers per
     * stage, so hosts should bind only these.
     */
    public static java.util.Set<String> activeResourceNames(final ByteBuffer spirv) throws TranslationException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer words = spirv.asIntBuffer();
            PointerBuffer p = stack.mallocPointer(1);
            check(Spvc.spvc_context_create(p), "context_create");
            long context = p.get(0);
            try {
                check(Spvc.spvc_context_parse_spirv(context, words, words.remaining(), p), "parse_spirv");
                long ir = p.get(0);
                check(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_NONE, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, p), "create_compiler");
                long compiler = p.get(0);
                check(Spvc.spvc_compiler_get_active_interface_variables(compiler, p), "get_active_interface_variables");
                long activeSet = p.get(0);
                check(Spvc.spvc_compiler_create_shader_resources_for_active_variables(compiler, p, activeSet), "create_shader_resources_for_active_variables");
                long resources = p.get(0);
                java.util.Set<String> names = new java.util.HashSet<>();
                for (int type : new int[]{Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE,
                        Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE, Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE}) {
                    for (SpvcReflectedResource resource : list(stack, resources, type)) {
                        names.add(resource.nameString());
                    }
                }
                return names;
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    /**
     * Names of the combined image samplers whose image type is a depth image ({@code sampler2DShadow} etc.): they
     * sample with a reference compare ({@code sample_compare} in MSL) and need a sampler with a compare function.
     */
    public static java.util.Set<String> depthCompareSamplerNames(final ByteBuffer spirv) throws TranslationException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer words = spirv.asIntBuffer();
            PointerBuffer p = stack.mallocPointer(1);
            check(Spvc.spvc_context_create(p), "context_create");
            long context = p.get(0);
            try {
                check(Spvc.spvc_context_parse_spirv(context, words, words.remaining(), p), "parse_spirv");
                long ir = p.get(0);
                check(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_NONE, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, p), "create_compiler");
                long compiler = p.get(0);
                check(Spvc.spvc_compiler_create_shader_resources(compiler, p), "create_shader_resources");
                long resources = p.get(0);
                java.util.Set<String> names = new java.util.HashSet<>();
                for (SpvcReflectedResource resource : list(stack, resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE)) {
                    long type = Spvc.spvc_compiler_get_type_handle(compiler, resource.base_type_id());
                    if (Spvc.spvc_type_get_image_is_depth(type)) {
                        names.add(resource.nameString());
                    }
                }
                return names;
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    /** Test hook: varying name -> SPIR-V word offset of its Location operand. */
    static List<Map.Entry<String, Integer>> varyingsForTest(final ByteBuffer spirv, final int resourceType) throws TranslationException {
        return varyings(spirv, resourceType).stream().map(v -> Map.entry(v.name(), v.locationWordOffset())).toList();
    }

    private static List<Varying> varyings(final ByteBuffer spirv, final int resourceType) throws TranslationException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer words = spirv.asIntBuffer();
            PointerBuffer p = stack.mallocPointer(1);
            check(Spvc.spvc_context_create(p), "context_create");
            long context = p.get(0);
            try {
                check(Spvc.spvc_context_parse_spirv(context, words, words.remaining(), p), "parse_spirv");
                long ir = p.get(0);
                check(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_NONE, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, p), "create_compiler");
                long compiler = p.get(0);
                check(Spvc.spvc_compiler_create_shader_resources(compiler, p), "create_shader_resources");
                long shaderResources = p.get(0);
                List<Varying> out = new ArrayList<>();
                IntBuffer offset = stack.mallocInt(1);
                for (SpvcReflectedResource resource : list(stack, shaderResources, resourceType)) {
                    if (!Spvc.spvc_compiler_get_binary_offset_for_decoration(compiler, resource.id(), Spv.SpvDecorationLocation, offset)) {
                        throw new TranslationException("varying " + resource.nameString() + " has no Location decoration");
                    }
                    long type = Spvc.spvc_compiler_get_type_handle(compiler, resource.type_id());
                    out.add(new Varying(resource.nameString(), offset.get(0), locationSlots(compiler, type)));
                }
                return out;
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    /** Interface locations a varying of this type occupies: matrix columns x array sizes, struct members summed. */
    private static int locationSlots(final long compiler, final long type) {
        int slots;
        if (Spvc.spvc_type_get_basetype(type) == Spvc.SPVC_BASETYPE_STRUCT) {
            slots = 0;
            for (int i = 0; i < Spvc.spvc_type_get_num_member_types(type); i++) {
                slots += locationSlots(compiler, Spvc.spvc_compiler_get_type_handle(compiler, Spvc.spvc_type_get_member_type(type, i)));
            }
            slots = Math.max(1, slots);
        } else {
            slots = Math.max(1, Spvc.spvc_type_get_columns(type));
        }
        for (int d = 0; d < Spvc.spvc_type_get_num_array_dimensions(type); d++) {
            slots *= Math.max(1, Spvc.spvc_type_get_array_dimension(type, d));
        }
        return slots;
    }

    private static SpirvStage reflectDefaultBlock(final Stage stage, final ByteBuffer spirv) throws TranslationException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer words = spirv.asIntBuffer();
            PointerBuffer p = stack.mallocPointer(1);
            check(Spvc.spvc_context_create(p), "context_create");
            long context = p.get(0);
            try {
                check(Spvc.spvc_context_parse_spirv(context, words, words.remaining(), p), "parse_spirv");
                long ir = p.get(0);
                check(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_NONE, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, p), "create_compiler");
                long compiler = p.get(0);
                check(Spvc.spvc_compiler_create_shader_resources(compiler, p), "create_shader_resources");
                long shaderResources = p.get(0);
                for (SpvcReflectedResource ubo : list(stack, shaderResources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER)) {
                    String blockName = ubo.nameString();
                    if (!blockName.endsWith("gl_DefaultUniformBlock") && !Spvc.spvc_compiler_get_name(compiler, ubo.base_type_id()).endsWith("gl_DefaultUniformBlock")) {
                        continue;
                    }
                    long type = Spvc.spvc_compiler_get_type_handle(compiler, ubo.base_type_id());
                    PointerBuffer size = stack.mallocPointer(1);
                    check(Spvc.spvc_compiler_get_declared_struct_size(compiler, type, size), "get_declared_struct_size");
                    List<BlockMember> members = new ArrayList<>();
                    IntBuffer value = stack.mallocInt(1);
                    PointerBuffer memberSize = stack.mallocPointer(1);
                    int memberCount = Spvc.spvc_type_get_num_member_types(type);
                    for (int i = 0; i < memberCount; i++) {
                        long memberType = Spvc.spvc_compiler_get_type_handle(compiler, Spvc.spvc_type_get_member_type(type, i));
                        check(Spvc.spvc_compiler_type_struct_member_offset(compiler, type, i, value), "struct_member_offset");
                        int offset = value.get(0);
                        check(Spvc.spvc_compiler_get_declared_struct_member_size(compiler, type, i, memberSize), "declared_struct_member_size");
                        int arraySize = 0;
                        int arrayStride = 0;
                        if (Spvc.spvc_type_get_num_array_dimensions(memberType) > 0) {
                            arraySize = Spvc.spvc_type_get_array_dimension(memberType, 0);
                            check(Spvc.spvc_compiler_type_struct_member_array_stride(compiler, type, i, value), "struct_member_array_stride");
                            arrayStride = value.get(0);
                        }
                        int columns = Spvc.spvc_type_get_columns(memberType);
                        int matrixStride = 0;
                        if (columns > 1) {
                            check(Spvc.spvc_compiler_type_struct_member_matrix_stride(compiler, type, i, value), "struct_member_matrix_stride");
                            matrixStride = value.get(0);
                        }
                        members.add(new BlockMember(Spvc.spvc_compiler_get_member_name(compiler, ubo.base_type_id(), i), offset, (int) memberSize.get(0),
                                scalarType(Spvc.spvc_type_get_basetype(memberType)), Spvc.spvc_type_get_vector_size(memberType), columns,
                                arraySize, arrayStride, matrixStride));
                    }
                    return new SpirvStage(stage, spirv, blockName, (int) size.get(0), List.copyOf(members));
                }
                return new SpirvStage(stage, spirv, null, 0, List.of());
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static ScalarType scalarType(final int baseType) {
        return switch (baseType) {
            case Spvc.SPVC_BASETYPE_FP32 -> ScalarType.FLOAT;
            case Spvc.SPVC_BASETYPE_INT32 -> ScalarType.INT;
            case Spvc.SPVC_BASETYPE_UINT32 -> ScalarType.UINT;
            case Spvc.SPVC_BASETYPE_BOOLEAN -> ScalarType.BOOL;
            default -> ScalarType.OTHER;
        };
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

    /** Explicit {@code layout(binding = N)} / {@code layout(location = N)} of one uniform, buffer block or opaque uniform. */
    public record ExplicitLayout(int binding, int location) {
    }

    private static final Pattern LAYOUT_DECLARATION = Pattern.compile(
            "layout\\s*\\(([^)]*)\\)\\s*((?:\\w+\\s+)*?)(uniform|buffer)\\s+((?:(?:restrict|readonly|writeonly|coherent|volatile|highp|mediump|lowp)\\s+)*)(\\w+)\\s*(\\{|\\w+)");
    private static final Pattern LAYOUT_INT = Pattern.compile("\\b(binding|location)\\s*=\\s*(\\d+)");

    /**
     * Explicit bindings and locations declared in {@code preprocessed} GLSL (see {@link #preprocess}), keyed by the name
     * reflection reports: the block name for uniform/storage blocks, the variable name otherwise. GL needs to know which
     * bindings are explicit: unbound blocks and samplers default to binding / unit 0, while glslang (Vulkan rules)
     * assigns them other numbers.
     */
    public static Map<String, ExplicitLayout> explicitLayouts(final String preprocessed) {
        Map<String, ExplicitLayout> result = new LinkedHashMap<>();
        Matcher m = LAYOUT_DECLARATION.matcher(preprocessed);
        while (m.find()) {
            int binding = -1;
            int location = -1;
            Matcher q = LAYOUT_INT.matcher(m.group(1));
            while (q.find()) {
                if (q.group(1).equals("binding")) binding = Integer.parseInt(q.group(2));
                else location = Integer.parseInt(q.group(2));
            }
            if (binding < 0 && location < 0) {
                continue;
            }
            String name = m.group(6).equals("{") ? m.group(5) : m.group(6);
            result.put(name, new ExplicitLayout(binding, location));
        }
        return result;
    }

    /** Runs only the GLSL preprocessor (macros, {@code #if}); used to read layout qualifiers that use macros. */
    public static String preprocess(final String programName, final Stage stage, final String source) throws TranslationException {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        try {
            Shaderc.shaderc_compile_options_set_source_language(options, Shaderc.shaderc_source_language_glsl);
            ByteBuffer sourceText = MemoryUtil.memUTF8(source, false);
            ByteBuffer fileName = MemoryUtil.memUTF8(programName + "." + stage.name().toLowerCase());
            ByteBuffer entryPoint = MemoryUtil.memUTF8("main");
            long result;
            try {
                result = Shaderc.shaderc_compile_into_preprocessed_text(compiler, sourceText, stage.shadercKind, fileName, entryPoint, options);
            } finally {
                MemoryUtil.memFree(sourceText);
                MemoryUtil.memFree(fileName);
                MemoryUtil.memFree(entryPoint);
            }
            try {
                if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                    throw new TranslationException(programName + "/" + stage + ": " + Shaderc.shaderc_result_get_error_message(result));
                }
                ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                return bytes == null ? "" : MemoryUtil.memUTF8(bytes, bytes.remaining());
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static boolean isSpirv(final byte[] bytes) {
        // SPIR-V magic 0x07230203 in either byte order, whole words, at least the 5-word header.
        if (bytes.length < 20 || bytes.length % 4 != 0) {
            return false;
        }
        int little = (bytes[0] & 0xFF) | (bytes[1] & 0xFF) << 8 | (bytes[2] & 0xFF) << 16 | (bytes[3] & 0xFF) << 24;
        return little == 0x07230203 || Integer.reverseBytes(little) == 0x07230203;
    }

    /**
     * GL identifiers that cannot survive the trip: {@code sampler}/{@code samplerShadow} are reserved words only in
     * Vulkan GLSL, which glslang applies even with relaxed rules (Photon: {@code sampler2D sampler}); the others are
     * Metal standard-library functions that packs define themselves, which become ambiguous overloads in MSL
     * ({@code using namespace metal}; Photon: {@code length_squared}), or C++/MSL keywords that are plain GLSL names
     * (BSL: {@code bool new}).
     */
    private static final Pattern VULKAN_ONLY_KEYWORDS = Pattern.compile(
            "\\b(sampler|samplerShadow|length_squared|distance_squared|saturate|max3|min3|median3|powr|rsqrt|new|delete|this|template|typename|namespace|operator|friend|virtual|explicit|mutable|private|protected|public|kernel|device|thread|threadgroup|register|bias|level|min_lod_clamp|nullptr|constexpr|decltype|noexcept|throw|try|catch|alignas|alignof|static_assert|thread_local|export|typeid|dynamic_cast|static_cast|const_cast|reinterpret_cast)\\b");

    private static final Pattern EXPLICIT_OUT = Pattern.compile(
            "\\blayout\\s*\\([^)]*\\blocation\\s*=\\s*(\\d+)[^)]*\\)\\s*(?:(?:flat|smooth|noperspective|centroid|invariant)\\s+)*out\\s+[A-Za-z_][A-Za-z0-9_]*\\s+[A-Za-z_][A-Za-z0-9_]*\\s*(\\[\\s*(\\d+)\\s*\\])?\\s*;");

    /**
     * Fragment outputs without a layout next to outputs with an explicit location: the GL linker gives the unqualified
     * ones free locations (declaration order, lowest first), while shaderc's auto-mapping numbers them from 0 and so
     * collides with the explicit ones (superDuperVanilla composite1: {@code layout(location = 0) out vec3 a; out vec4 b;}
     * -> MSL "duplicated color(0)"). Assigns those free locations explicitly; other sources are returned unchanged.
     */
    static String assignFragmentOutputLocations(final String source) {
        java.util.BitSet used = new java.util.BitSet();
        Matcher explicit = EXPLICIT_OUT.matcher(source);
        while (explicit.find()) {
            int location = Integer.parseInt(explicit.group(1));
            used.set(location, location + (explicit.group(3) != null ? Math.max(1, Integer.parseInt(explicit.group(3))) : 1));
        }
        if (used.isEmpty()) {
            return source;
        }
        Matcher m = GLOBAL_IO.matcher(source);
        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        while (m.find()) {
            if (!m.group(3).equals("out") || hasLayoutBefore(source, m.start())) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            int slots = 1;
            if (m.group(6) != null) {
                String size = m.group(6).replaceAll("[\\[\\]\\s]", "");
                slots = size.matches("\\d+") ? Math.max(1, Integer.parseInt(size)) : 1;
            }
            int location = 0;
            while (!used.get(location, location + slots).isEmpty()) {
                location++;
            }
            used.set(location, location + slots);
            String replacement = m.group(1) + "layout(location = " + location + ") " + m.group(2) + "out " + m.group(4) + " " + m.group(5)
                    + (m.group(6) != null ? m.group(6) : "") + ";";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            changed = true;
        }
        m.appendTail(sb);
        return changed ? sb.toString() : source;
    }

    /**
     * Built-in functions (name -> number of genType parameters) that old ({@code #version 120}) packs define themselves
     * because their GLSL version lacks them: Bliss has {@code float tanh(float)} and {@code float fma(float, float, float)}.
     * Iris raises such programs to {@code #version 330}, where the definition duplicates the built-in and glslang rejects
     * it ("'highp' : overloaded functions must have the same parameter precision qualifiers").
     */
    private static final Map<String, Integer> REDEFINABLE_BUILTINS = Map.of(
            "tanh", 1, "sinh", 1, "cosh", 1, "asinh", 1, "acosh", 1, "atanh", 1, "round", 1, "roundEven", 1, "trunc", 1, "fma", 3);
    private static final Pattern BUILTIN_REDEFINITION = Pattern.compile(
            "\\b(float|vec[234])\\s+(" + String.join("|", REDEFINABLE_BUILTINS.keySet()) + ")\\s*\\(([^)]*)\\)\\s*\\{");
    private static final List<String> GEN_TYPES = List.of("float", "vec2", "vec3", "vec4");

    /**
     * Renames a pack's own definitions of built-ins ({@link #REDEFINABLE_BUILTINS}) and every call of that name to
     * {@code metallium_<name>}; overloads the pack does not define forward to the built-in, so calls with other types
     * still reach it. Only definitions whose signature equals a built-in overload ({@code T f(T, ...)}) are renamed.
     */
    static String renameBuiltinRedefinitions(final String source) {
        Map<String, java.util.Set<String>> defined = new LinkedHashMap<>();
        Map<String, Integer> firstDefinition = new LinkedHashMap<>();
        Matcher m = BUILTIN_REDEFINITION.matcher(source);
        while (m.find()) {
            String type = m.group(1);
            String name = m.group(2);
            String[] params = m.group(3).split(",");
            if (params.length != REDEFINABLE_BUILTINS.get(name)) {
                continue;
            }
            boolean sameType = true;
            for (String param : params) {
                String[] words = param.trim().split("\\s+");
                // [const] [in] T name
                if (words.length < 2 || !words[words.length - 2].equals(type)) {
                    sameType = false;
                    break;
                }
            }
            if (sameType) {
                defined.computeIfAbsent(name, k -> new java.util.LinkedHashSet<>()).add(type);
                firstDefinition.putIfAbsent(name, m.start());
            }
        }
        if (defined.isEmpty()) {
            return source;
        }
        // Forwarders go right before the first renamed definition (global scope, before any use of the new name).
        java.util.TreeMap<Integer, StringBuilder> inserts = new java.util.TreeMap<>();
        for (Map.Entry<String, java.util.Set<String>> entry : defined.entrySet()) {
            String name = entry.getKey();
            int arity = REDEFINABLE_BUILTINS.get(name);
            StringBuilder forwards = inserts.computeIfAbsent(firstDefinition.get(name), k -> new StringBuilder());
            for (String type : GEN_TYPES) {
                if (entry.getValue().contains(type)) {
                    continue;
                }
                StringBuilder params = new StringBuilder();
                StringBuilder args = new StringBuilder();
                for (int i = 0; i < arity; i++) {
                    params.append(i > 0 ? ", " : "").append(type).append(" a").append(i);
                    args.append(i > 0 ? ", " : "").append('a').append(i);
                }
                // fma is a built-in only from #version 400 (or gpu_shader5): forward to the expression instead.
                String body = name.equals("fma") ? "a0 * a1 + a2" : name + "(" + args + ")";
                forwards.append(type).append(" metallium_").append(name).append('(').append(params).append(") { return ").append(body).append("; }\n");
            }
        }
        String renamed = source;
        // Insert from the back so earlier offsets stay valid, then rename (forwarders call the built-in by its own name).
        StringBuilder out = new StringBuilder(source);
        for (Map.Entry<Integer, StringBuilder> insert : inserts.descendingMap().entrySet()) {
            out.insert(insert.getKey(), "\u0000" + insert.getValue() + "\u0001");
        }
        renamed = out.toString();
        Pattern calls = Pattern.compile("\\b(" + String.join("|", defined.keySet()) + ")(\\s*\\()");
        StringBuilder result = new StringBuilder();
        int from = 0;
        while (from < renamed.length()) {
            int start = renamed.indexOf('\u0000', from);
            int end = start < 0 ? renamed.length() : start;
            result.append(calls.matcher(renamed.substring(from, end)).replaceAll("metallium_$1$2"));
            if (start < 0) {
                break;
            }
            int close = renamed.indexOf('\u0001', start);
            result.append(renamed, start + 1, close);
            from = close + 1;
        }
        return result.toString();
    }

    private static final Pattern VERSION_NUMBER = Pattern.compile("^\\s*#version\\s+(\\d+).*$", Pattern.MULTILINE);
    private static final Pattern SSBO_EXTENSION = Pattern.compile("#extension\\s+GL_ARB_shader_storage_buffer_object\\b");
    private static final Pattern BINDING_QUALIFIER = Pattern.compile("layout\\s*\\([^)]*\\bbinding\\s*=");

    /**
     * {@code layout(binding = N)} needs GLSL 4.20 or {@code GL_ARB_shading_language_420pack}; desktop drivers accept it in
     * older versions with the SSBO/image extensions, glslang does not (Complementary Unbound's colored lighting:
     * {@code #version 330} + {@code GL_ARB_shader_storage_buffer_object} + {@code layout(std430, binding = 0) buffer}).
     */
    static String enableBindingQualifiers(final String source) {
        Matcher version = VERSION_NUMBER.matcher(source);
        if (version.find() && Integer.parseInt(version.group(1)) < 430 && SSBO_EXTENSION.matcher(source).find()) {
            // glslang accepts buffer blocks only from 4.30 (desktop drivers take the extension in 3.30): raise the version.
            String line = version.group();
            return source.substring(0, version.start()) + line.replaceFirst("\\d+", "430") + source.substring(version.end());
        }
        version.reset();
        if (!version.find() || Integer.parseInt(version.group(1)) >= 420 || !BINDING_QUALIFIER.matcher(source).find()
                || source.contains("GL_ARB_shading_language_420pack")) {
            return source;
        }
        return source.substring(0, version.end()) + "\n#extension GL_ARB_shading_language_420pack : enable" + source.substring(version.end());
    }

    static ByteBuffer compileToSpirv(final String programName, final Stage stage, final String glslSource) throws TranslationException {
        String outputsPlaced = stage == Stage.FRAGMENT ? assignFragmentOutputLocations(glslSource) : glslSource;
        String source = enableBindingQualifiers(renameBuiltinRedefinitions(VULKAN_ONLY_KEYWORDS.matcher(outputsPlaced).replaceAll("metallium_$1")));
        if (!VERSION.matcher(source).find()) {
            throw new TranslationException(programName + "/" + stage + ": missing #version directive");
        }
        // Persistent GLSL -> SPIR-V cache (only once the Metal device opened it). The shaderc options below are fixed;
        // the cache environment includes a hash of this class, so changing them invalidates old entries.
        ShaderDiskCache cache = ShaderCache.disk();
        String cacheKey = null;
        if (cache != null) {
            cacheKey = CacheKey.of("glsl").add(stage.name()).add(source).hex();
            byte[] cached = cache.get("glsl", cacheKey);
            if (cached != null && isSpirv(cached)) {
                ShaderCacheStats.glslCacheHits.increment();
                ByteBuffer copy = MemoryUtil.memAlloc(cached.length);
                copy.put(cached).flip();
                return copy;
            }
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

            // Heap-allocated strings: the CharSequence overload copies the source onto LWJGL's thread-local MemoryStack
            // (64 KiB), which large shader packs overflow when this runs deep inside a renderer that also uses it.
            ByteBuffer sourceText = MemoryUtil.memUTF8(source, false);
            ByteBuffer fileName = MemoryUtil.memUTF8(programName + "." + stage.name().toLowerCase());
            ByteBuffer entryPoint = MemoryUtil.memUTF8("main");
            long result;
            long start = System.nanoTime();
            try {
                result = Shaderc.shaderc_compile_into_spv(compiler, sourceText, stage.shadercKind, fileName, entryPoint, options);
            } finally {
                ShaderCacheStats.glslCompiles.increment();
                ShaderCacheStats.glslNs.add(System.nanoTime() - start);
                MemoryUtil.memFree(sourceText);
                MemoryUtil.memFree(fileName);
                MemoryUtil.memFree(entryPoint);
            }
            try {
                if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                    throw new TranslationException(programName + "/" + stage + ": " + Shaderc.shaderc_result_get_error_message(result));
                }
                ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
                copy.put(bytes).flip();
                if (cache != null) {
                    byte[] stored = new byte[copy.remaining()];
                    copy.duplicate().get(stored);
                    cache.put("glsl", cacheKey, stored);
                }
                return copy;
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    /**
     * Translates one stage produced by {@link #compileToSpirv(String, Map)} to MSL (the SPIR-V buffer stays owned by the
     * caller). {@code glClipSpace}: the program has OpenGL semantics — it writes GL clip-space depth (-w..w), which the
     * vertex stage remaps to Metal's 0..w, and its {@code gl_InstanceID} excludes the base instance
     * ({@link #glInstanceId}). Used by hosts that run unmodified OpenGL programs (metallium-gl).
     */
    public static StageResult spirvToMsl(final SpirvStage stage, final int mslVersion, final boolean glClipSpace) throws TranslationException {
        return spirvToMsl(stage.stage(), stage.spirv().duplicate().order(stage.spirv().order()), mslVersion, glClipSpace);
    }

    private static StageResult spirvToMsl(final Stage stage, final ByteBuffer spirv, final int mslVersion) throws TranslationException {
        return spirvToMsl(stage, spirv, mslVersion, false);
    }

    private static StageResult spirvToMsl(final Stage stage, final ByteBuffer spirv, final int mslVersion, final boolean glClipSpace) throws TranslationException {
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
                if (glClipSpace && stage == Stage.VERTEX) {
                    check(Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FIXUP_DEPTH_CONVENTION, true), "FIXUP_DEPTH_CONVENTION");
                }
                check(Spvc.spvc_compiler_install_compiler_options(compiler, options), "install_compiler_options");

                check(Spvc.spvc_compiler_compile(compiler, p), "compile");
                String msl = MemoryUtil.memUTF8(p.get(0));
                if (glClipSpace && stage == Stage.VERTEX) {
                    msl = glInstanceId(msl);
                }

                List<UniformMember> members = new ArrayList<>();
                List<ResourceBinding> resources = new ArrayList<>();
                String[] defaultBlock = {null};
                int[] defaultBlockSize = {0};
                reflect(stack, compiler, members, resources, defaultBlock, defaultBlockSize);

                PointerBuffer entries = stack.mallocPointer(1);
                PointerBuffer entryCount = stack.mallocPointer(1);
                check(Spvc.spvc_compiler_get_entry_points(compiler, entries, entryCount), "get_entry_points");
                String entryPoint = Spvc.spvc_compiler_get_cleansed_entry_point_name(compiler, "main", executionModel(stage));
                int[] localSize = {1, 1, 1};
                if (stage == Stage.COMPUTE) {
                    for (int i = 0; i < 3; i++) {
                        localSize[i] = Math.max(1, Spvc.spvc_compiler_get_execution_mode_argument_by_index(compiler, Spv.SpvExecutionModeLocalSize, i));
                    }
                }
                return new StageResult(stage, msl, entryPoint != null ? entryPoint : "main0", defaultBlock[0], defaultBlockSize[0], members, resources, localSize);
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static final Pattern INSTANCE_INDEX_PARAM = Pattern.compile("uint gl_InstanceIndex \\[\\[instance_id\\]\\]");
    private static final Pattern BASE_INSTANCE_PARAM = Pattern.compile("uint (\\w+) \\[\\[base_instance\\]\\]");

    /**
     * OpenGL's {@code gl_InstanceID} does not include the draw's base instance; Metal's {@code [[instance_id]]} (and
     * Vulkan's {@code gl_InstanceIndex}, which glslang maps {@code gl_InstanceID} to) does. For programs with GL
     * semantics the vertex entry point therefore receives Metal's value under another name and recomputes
     * {@code gl_InstanceIndex = instance_id - base_instance}. Matters for {@code glDraw*BaseInstance} and indirect draws.
     */
    static String glInstanceId(final String msl) {
        Matcher param = INSTANCE_INDEX_PARAM.matcher(msl);
        if (!param.find()) {
            return msl;
        }
        Matcher base = BASE_INSTANCE_PARAM.matcher(msl);
        String baseName = base.find() ? base.group(1) : "spvGlBaseInstance";
        String replacement = "uint spvMetalInstanceId [[instance_id]]" + (baseName.equals("spvGlBaseInstance") ? ", uint spvGlBaseInstance [[base_instance]]" : "");
        String patched = msl.substring(0, param.start()) + replacement + msl.substring(param.end());
        int body = patched.indexOf('{', param.start() + replacement.length());
        if (body < 0) {
            return msl;
        }
        return patched.substring(0, body + 1) + "\n    uint gl_InstanceIndex = spvMetalInstanceId - " + baseName + ";" + patched.substring(body + 1);
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
            resources.add(new ResourceBinding(blockName, ResourceBinding.Kind.UNIFORM_BUFFER, index, binding(compiler, ubo.id())));
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
                    Spvc.spvc_compiler_msl_get_automatic_resource_binding(compiler, ssbo.id()), binding(compiler, ssbo.id())));
        }
        for (SpvcReflectedResource sampled : list(stack, shaderResources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE)) {
            resources.add(new ResourceBinding(sampled.nameString(), ResourceBinding.Kind.SAMPLED_TEXTURE,
                    Spvc.spvc_compiler_msl_get_automatic_resource_binding(compiler, sampled.id()), binding(compiler, sampled.id())));
        }
        for (SpvcReflectedResource image : list(stack, shaderResources, Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE)) {
            resources.add(new ResourceBinding(image.nameString(), ResourceBinding.Kind.STORAGE_IMAGE,
                    Spvc.spvc_compiler_msl_get_automatic_resource_binding(compiler, image.id()), binding(compiler, image.id())));
        }
    }

    private static int binding(final long compiler, final int id) {
        return Spvc.spvc_compiler_has_decoration(compiler, id, Spv.SpvDecorationBinding)
                ? Spvc.spvc_compiler_get_decoration(compiler, id, Spv.SpvDecorationBinding) : -1;
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
