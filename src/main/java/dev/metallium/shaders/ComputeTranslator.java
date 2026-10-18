/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.shaders;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * SPIR-V compute stage (from {@link GlslProgramTranslator#compileToSpirv}) to an MSL kernel with a fixed, host-known
 * binding layout, for hosts that create their own {@code MTLComputePipelineState} (the Iris adapter's compute passes).
 *
 * <p>Layout (only resources the entry point statically uses are emitted):
 * <ul>
 *   <li>buffers: the folded default uniform block at {@code [[buffer(0)]]} (if any), then every storage buffer and any
 *       other uniform block in reflection order; then the SPIRV-Cross buffer-size buffer (for {@code .length()} on
 *       runtime arrays, a {@code uint} per buffer index) if the kernel needs one;</li>
 *   <li>textures: sampled textures first, then storage images; each sampled texture also gets its own sampler slot.</li>
 * </ul>
 * MSL 3.1 is targeted, so image atomics are native texture atomics.
 */
public final class ComputeTranslator {
    /** MSL 3.1 in SPIRV-Cross's {@code make_msl_version} encoding (needed for native texture atomics). */
    public static final int MSL_VERSION_3_1 = 30100;

    private ComputeTranslator() {
    }

    public enum Kind { UNIFORM_BUFFER, STORAGE_BUFFER, SAMPLED_TEXTURE, STORAGE_IMAGE }

    /**
     * One resource of the kernel. {@code glBinding}: the GLSL {@code layout(binding = N)} (for storage buffers the Iris
     * {@code bufferObject.N} index). {@code mslSampler} is -1 except for sampled textures. {@code dimension} is the SPIR-V
     * {@code Dim} (0 = 1D, 1 = 2D, 2 = 3D, 3 = cube, 4 = rect).
     */
    public record Resource(String name, Kind kind, int glBinding, int mslIndex, int mslSampler, int dimension, boolean depth,
                           boolean arrayed, boolean defaultBlock) {
    }

    /** {@code bufferSizeIndex} is -1 if the kernel does not need the buffer-size buffer. */
    public record Kernel(String msl, String entryPoint, int[] localSize, List<Resource> resources, int bufferSizeIndex) {
        public int bufferCount() {
            int count = 0;
            for (Resource r : resources) {
                if (r.kind == Kind.UNIFORM_BUFFER || r.kind == Kind.STORAGE_BUFFER) {
                    count = Math.max(count, r.mslIndex + 1);
                }
            }
            return count;
        }
    }

    public static Kernel toMsl(final GlslProgramTranslator.SpirvStage stage) throws GlslProgramTranslator.TranslationException {
        if (stage.stage() != GlslProgramTranslator.Stage.COMPUTE) {
            throw new IllegalArgumentException("not a compute stage: " + stage.stage());
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer words = stage.spirv().duplicate().order(stage.spirv().order()).asIntBuffer();
            PointerBuffer p = stack.mallocPointer(1);
            check(Spvc.spvc_context_create(p), "context_create");
            long context = p.get(0);
            try {
                check(Spvc.spvc_context_parse_spirv(context, words, words.remaining(), p), "parse_spirv");
                long ir = p.get(0);
                check(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, p), "create_compiler");
                long compiler = p.get(0);

                check(Spvc.spvc_compiler_get_active_interface_variables(compiler, p), "get_active_interface_variables");
                long activeSet = p.get(0);
                check(Spvc.spvc_compiler_set_enabled_interface_variables(compiler, activeSet), "set_enabled_interface_variables");
                check(Spvc.spvc_compiler_create_shader_resources_for_active_variables(compiler, p, activeSet), "create_shader_resources_for_active_variables");
                long shaderResources = p.get(0);

                List<Resource> resources = new ArrayList<>();
                int[] nextBuffer = {0};
                int[] nextTexture = {0};
                int[] nextSampler = {0};
                int[] uniqueBinding = {0};
                List<SpvcReflectedResource> ubos = list(stack, shaderResources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER);
                // Default block first so it always lands on buffer(0).
                ubos.sort((a, b) -> Boolean.compare(!isDefaultBlock(compiler, b, stage), !isDefaultBlock(compiler, a, stage)));
                for (SpvcReflectedResource ubo : ubos) {
                    boolean isDefault = isDefaultBlock(compiler, ubo, stage);
                    resources.add(bind(stack, compiler, ubo, Kind.UNIFORM_BUFFER, ubo.nameString(), nextBuffer[0]++, -1, uniqueBinding, isDefault));
                }
                for (SpvcReflectedResource ssbo : list(stack, shaderResources, Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER)) {
                    String name = Spvc.spvc_compiler_get_name(compiler, ssbo.base_type_id());
                    resources.add(bind(stack, compiler, ssbo, Kind.STORAGE_BUFFER, name == null || name.isEmpty() ? ssbo.nameString() : name, nextBuffer[0]++, -1, uniqueBinding, false));
                }
                for (SpvcReflectedResource sampled : list(stack, shaderResources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE)) {
                    resources.add(bind(stack, compiler, sampled, Kind.SAMPLED_TEXTURE, sampled.nameString(), nextTexture[0]++, nextSampler[0]++, uniqueBinding, false));
                }
                for (SpvcReflectedResource image : list(stack, shaderResources, Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE)) {
                    resources.add(bind(stack, compiler, image, Kind.STORAGE_IMAGE, image.nameString(), nextTexture[0]++, -1, uniqueBinding, false));
                }
                if (!list(stack, shaderResources, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE).isEmpty()
                        || !list(stack, shaderResources, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS).isEmpty()) {
                    throw new GlslProgramTranslator.TranslationException("separate images/samplers are not supported in compute kernels");
                }
                int bufferSizeIndex = nextBuffer[0];

                check(Spvc.spvc_compiler_create_compiler_options(compiler, p), "create_compiler_options");
                long options = p.get(0);
                check(Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS), "MSL_PLATFORM");
                check(Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION_3_1), "MSL_VERSION");
                check(Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_1D_AS_2D, true), "TEXTURE_1D_AS_2D");
                check(Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true), "TEXTURE_BUFFER_NATIVE");
                check(Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_BUFFER_SIZE_BUFFER_INDEX, bufferSizeIndex), "BUFFER_SIZE_BUFFER_INDEX");
                check(Spvc.spvc_compiler_install_compiler_options(compiler, options), "install_compiler_options");

                check(Spvc.spvc_compiler_compile(compiler, p), "compile");
                String msl = MemoryUtil.memUTF8(p.get(0));
                boolean needsSizes = Spvc.spvc_compiler_msl_needs_buffer_size_buffer(compiler);

                int[] localSize = new int[3];
                for (int i = 0; i < 3; i++) {
                    localSize[i] = Math.max(1, Spvc.spvc_compiler_get_execution_mode_argument_by_index(compiler, Spv.SpvExecutionModeLocalSize, i));
                }
                String entryPoint = Spvc.spvc_compiler_get_cleansed_entry_point_name(compiler, "main", Spv.SpvExecutionModelGLCompute);
                return new Kernel(msl, entryPoint != null ? entryPoint : "main0", localSize, List.copyOf(resources), needsSizes ? bufferSizeIndex : -1);
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static final java.util.regex.Pattern THREADGROUP_TYPE =
            java.util.regex.Pattern.compile("threadgroup\\s+((?:spvUnsafeArray<[^;&()]*>)|(?:float3|int3|uint3|half3))(\\s*&?\\s*\\w)");
    private static final java.util.regex.Pattern VECTOR3 = java.util.regex.Pattern.compile("\\b(float3|int3|uint3|half3)\\b");

    /**
     * GLSL {@code shared vec3} arrays become {@code float3} in MSL, which has a 16-byte stride, so packs sized for GL's
     * 32 KiB of shared memory with 12-byte vectors (Photon's sky SH reduction: {@code vec3[256][9]}) exceed Metal's
     * 32 KiB threadgroup limit. This rewrites the 3-component vectors of threadgroup declarations (and of threadgroup
     * parameters, which use the same type) to {@code packed_*3}, which MSL supports in loads, stores and arithmetic.
     * Hosts call it when the Metal compiler reports too much threadgroup memory.
     */
    public static String packThreadgroupVectors(final String msl) {
        java.util.regex.Matcher m = THREADGROUP_TYPE.matcher(msl);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String type = VECTOR3.matcher(m.group(1)).replaceAll("packed_$1");
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement("threadgroup " + type + m.group(2)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static boolean isDefaultBlock(final long compiler, final SpvcReflectedResource ubo, final GlslProgramTranslator.SpirvStage stage) {
        String name = ubo.nameString();
        return name.equals(stage.defaultBlockName()) || name.endsWith("gl_DefaultUniformBlock")
                || String.valueOf(Spvc.spvc_compiler_get_name(compiler, ubo.base_type_id())).endsWith("gl_DefaultUniformBlock");
    }

    /** Gives the resource a unique (set 0, binding) and maps it to the chosen MSL slots. */
    private static Resource bind(final MemoryStack stack, final long compiler, final SpvcReflectedResource resource, final Kind kind, final String name,
                                 final int mslIndex, final int mslSampler, final int[] uniqueBinding, final boolean defaultBlock) throws GlslProgramTranslator.TranslationException {
        int id = resource.id();
        int glBinding = Spvc.spvc_compiler_get_decoration(compiler, id, Spv.SpvDecorationBinding);
        int binding = uniqueBinding[0]++;
        Spvc.spvc_compiler_set_decoration(compiler, id, Spv.SpvDecorationDescriptorSet, 0);
        Spvc.spvc_compiler_set_decoration(compiler, id, Spv.SpvDecorationBinding, binding);
        SpvcMslResourceBinding msl = SpvcMslResourceBinding.malloc(stack);
        Spvc.spvc_msl_resource_binding_init(msl);
        msl.stage(Spv.SpvExecutionModelGLCompute).desc_set(0).binding(binding)
                .msl_buffer(mslIndex).msl_texture(mslIndex).msl_sampler(Math.max(0, mslSampler));
        check(Spvc.spvc_compiler_msl_add_resource_binding(compiler, msl), "msl_add_resource_binding");

        int dimension = -1;
        boolean depth = false;
        boolean arrayed = false;
        if (kind == Kind.SAMPLED_TEXTURE || kind == Kind.STORAGE_IMAGE) {
            long type = Spvc.spvc_compiler_get_type_handle(compiler, resource.base_type_id());
            dimension = Spvc.spvc_type_get_image_dimension(type);
            depth = Spvc.spvc_type_get_image_is_depth(type);
            arrayed = Spvc.spvc_type_get_image_arrayed(type);
        }
        return new Resource(name, kind, glBinding, mslIndex, mslSampler, dimension, depth, arrayed, defaultBlock);
    }

    private static List<SpvcReflectedResource> list(final MemoryStack stack, final long shaderResources, final int type) throws GlslProgramTranslator.TranslationException {
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

    private static void check(final int result, final String what) throws GlslProgramTranslator.TranslationException {
        if (result != Spvc.SPVC_SUCCESS) {
            throw new GlslProgramTranslator.TranslationException("SPIRV-Cross " + what + " failed (" + result + ")");
        }
    }
}
