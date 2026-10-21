package de.nprmedia.nativium.iris.post;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import com.mojang.renderpearl.util.ShaderCompileException;
import de.nprmedia.nativium.iris.mixin.FrontendGpuDeviceAccessor;
import de.nprmedia.nativium.shaders.GlslProgramTranslator;
import de.nprmedia.nativium.shaders.GlslProgramTranslator.SpirvStage;
import de.nprmedia.nativium.shaders.GlslProgramTranslator.Stage;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spvc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One fullscreen pass program (composite/deferred/prepare/begin/final, or Nativium's own copy program) compiled for
 * the active renderpearl backend without OpenGL: post-Iris GLSL -> SPIR-V (core {@link GlslProgramTranslator}) ->
 * {@link BackendRenderPipeline.CreateInfo} -> the backend's own SPIR-V -> MSL path. The result is an ordinary
 * {@link FrontendRenderPipeline}, so passes are drawn through the public {@code RenderPass} API.
 */
final class PostProgram implements AutoCloseable {
    /** Vertex layout of Iris's fullscreen quad: {@code Position} (vec3) + {@code UV0} (vec2), as in Iris's ProgramCreator. */
    static final VertexFormat QUAD_FORMAT = DefaultVertexFormat.POSITION_TEX;

    final String name;
    final FrontendRenderPipeline pipeline;
    /** Combined image samplers the program uses (union over stages). */
    final Set<String> samplers;
    /** {@code sampler2DShadow}s among them: need a depth-compare sampler. */
    final Set<String> compareSamplers;
    /** Uniform-buffer name (as bound with {@code RenderPass.setUniform}) -> CPU block of that stage. */
    final Map<String, PostUniforms.Block> blocks;
    final PostUniforms.Holder uniforms;
    /** Stage 5: storage images/SSBOs outside the renderpearl binding table (bound per draw). */
    final StorageBindings storage;
    /** Iris texture stage the program belongs to (selects {@code texture.<stage>.*} custom textures). */
    net.irisshaders.iris.shaderpack.texture.TextureStage stage = net.irisshaders.iris.shaderpack.texture.TextureStage.COMPOSITE_AND_FINAL;
    /**
     * colortex buffers an earlier pass of the same stage wrote (Iris's {@code flippedAtLeastOnce}): their
     * {@code texture.<stage>.colortexN} override no longer applies, the pass samples the buffer itself.
     */
    java.util.Set<Integer> writtenBefore = java.util.Set.of();

    private PostProgram(final String name, final FrontendRenderPipeline pipeline, final Set<String> samplers, final Set<String> compareSamplers,
                        final Map<String, PostUniforms.Block> blocks, final StorageBindings storage) {
        this.name = name;
        this.storage = storage;
        this.compareSamplers = compareSamplers;
        this.pipeline = pipeline;
        this.samplers = samplers;
        this.blocks = blocks;
        this.uniforms = new PostUniforms.Holder(name, List.copyOf(blocks.values()));
    }

    static final class UnsupportedProgramException extends Exception {
        UnsupportedProgramException(final String message) {
            super(message);
        }
    }

    static PostProgram compile(final String name, final String vertex, final String fragment, final List<GpuFormat> colorFormats)
            throws GlslProgramTranslator.TranslationException, UnsupportedProgramException, ShaderCompileException {
        return compile(name, vertex, fragment, colorFormats, null, null, null);
    }

    /** Metal's sampler-state limit per shader stage (the backend numbers textures per pipeline). */
    static final int MAX_SAMPLERS = 16;

    /** The sampler a texture is always bound with in this program, or null if it varies. */
    @FunctionalInterface
    interface FixedSamplers {
        com.mojang.renderpearl.api.textures.@org.jspecify.annotations.Nullable GpuSampler fixedSampler(String name);
    }

    static PostProgram compile(final String name, final String vertex, final String fragment, final List<GpuFormat> colorFormats,
                               final StorageBindings.@org.jspecify.annotations.Nullable Validator validator,
                               final @org.jspecify.annotations.Nullable FixedSamplers fixedSamplers)
            throws GlslProgramTranslator.TranslationException, UnsupportedProgramException, ShaderCompileException {
        return compile(name, vertex, fragment, colorFormats, validator, fixedSamplers, null);
    }

    /**
     * @param blends per colour target (same order as {@code colorFormats}): the pack's {@code blend.<program>} /
     *               {@code blend.<program>.<buffer>} override, or {@code null} for no blending on every target (Iris's
     *               default for fullscreen passes)
     */
    static PostProgram compile(final String name, final String vertex, final String fragment, final List<GpuFormat> colorFormats,
                               final StorageBindings.@org.jspecify.annotations.Nullable Validator validator,
                               final @org.jspecify.annotations.Nullable FixedSamplers fixedSamplers,
                               final @org.jspecify.annotations.Nullable List<java.util.Optional<com.mojang.renderpearl.api.pipeline.BlendFunction>> blends)
            throws GlslProgramTranslator.TranslationException, UnsupportedProgramException, ShaderCompileException {
        StorageBindings storage = new StorageBindings();
        Map<Stage, String> sources = new EnumMap<>(Stage.class);
        sources.put(Stage.VERTEX, vertex);
        sources.put(Stage.FRAGMENT, fragment);
        Map<Stage, SpirvStage> stages = GlslProgramTranslator.compileToSpirv(name, sources);

        List<SPIRVModule> modules = new ArrayList<>();
        Map<String, PostUniforms.Block> blocks = new java.util.LinkedHashMap<>();
        try {
            List<BackendRenderPipeline.CreateInfo.Shader> shaders = new ArrayList<>();
            Object2IntOpenHashMap<String> bindings = new Object2IntOpenHashMap<>();
            List<BindGroupLayout.UniformDescription> uniforms = new ArrayList<>();
            Set<String> samplers = new LinkedHashSet<>();
            Set<String> compareSamplers = new LinkedHashSet<>();
            List<java.util.Map.Entry<SpvModule.Reflection.Descriptor, String>> assigned = new ArrayList<>();
            SPIRVModule vertexModule = null;
            // Fragment first: Metal allows 16 samplers per stage but the backend numbers textures per pipeline, so the
            // stage with most samplers gets the low indices.
            List<SpirvStage> ordered = new ArrayList<>(stages.values());
            java.util.Collections.reverse(ordered);
            for (SpirvStage stage : ordered) {
                ShaderType type = stage.stage() == Stage.VERTEX ? ShaderType.VERTEX : ShaderType.FRAGMENT;
                compareSamplers.addAll(GlslProgramTranslator.depthCompareSamplerNames(stage.spirv()));
                SPIRVModule module = new SPIRVModule(stage.spirv(), type);
                modules.add(module);
                if (type == ShaderType.VERTEX) {
                    vertexModule = module;
                }
                for (SpvModule.Reflection.Descriptor descriptor : module.reflect().descriptors()) {
                    String uniformName;
                    UniformType uniformType;
                    switch (descriptor.resourceType()) {
                        case Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER -> {
                            if (!descriptor.name().equals(stage.defaultBlockName())) {
                                throw new UnsupportedProgramException("uniform block '" + descriptor.name() + "' (explicit UBOs are not supported yet)");
                            }
                            uniformName = "nativium_defaults_" + type.getName();
                            uniformType = UniformType.UNIFORM_BUFFER;
                            blocks.put(uniformName, new PostUniforms.Block(stage.defaultBlockSize(), stage.defaultBlock()));
                        }
                        case Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE -> {
                            int dim = descriptor.type().dimensions();
                            boolean customImage = validator != null && validator.isCustomSampler(descriptor.name());
                            if (dim != 1 && dim != 4 && !customImage) { // SpvDim2D, SpvDimRect
                                throw new UnsupportedProgramException("sampler '" + descriptor.name() + "' has dimension " + dim + " (only 2D textures are supported yet)");
                            }
                            uniformName = descriptor.name();
                            uniformType = UniformType.COMBINED_IMAGE_SAMPLER;
                            samplers.add(uniformName);
                        }
                        case Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE -> {
                            storage.assign(descriptor, StorageBindings.Kind.IMAGE, validator);
                            continue;
                        }
                        case Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER -> {
                            storage.assign(descriptor, StorageBindings.Kind.BUFFER, validator);
                            continue;
                        }
                        default -> throw new UnsupportedProgramException("resource '" + descriptor.name() + "' of type " + descriptor.resourceType());
                    }
                    int binding = bindings.computeIfAbsent(uniformName, key -> bindings.size());
                    if (binding == uniforms.size()) {
                        uniforms.add(new BindGroupLayout.UniformDescription(uniformName, uniformType));
                    }
                    descriptor.binding(binding);
                    descriptor.descriptorSetIndex(0);
                    assigned.add(java.util.Map.entry(descriptor, uniformName));
                }
                shaders.add(new BackendRenderPipeline.CreateInfo.Shader(name + "." + type.getName(), "main", module));
            }

            uniforms = shareSamplers(name, uniforms, bindings, assigned, fixedSamplers);

            List<BackendRenderPipeline.CreateInfo.AttribBinding> attribs = new ArrayList<>();
            for (SpvModule.Reflection.InterfaceVariable input : vertexModule.reflect().inputs()) {
                VertexFormatElement element = QUAD_FORMAT.getElements().stream().filter(e -> e.name().equals(input.name())).findFirst()
                        .orElseThrow(() -> new UnsupportedProgramException("vertex input '" + input.name() + "' (fullscreen passes only provide Position and UV0)"));
                attribs.add(new BackendRenderPipeline.CreateInfo.AttribBinding(0, input.location(), element.offset(), element.format()));
            }

            List<ColorTargetState> colorTargets = new ArrayList<>();
            for (int i = 0; i < colorFormats.size(); i++) {
                GpuFormat format = colorFormats.get(i);
                java.util.Optional<com.mojang.renderpearl.api.pipeline.BlendFunction> blend = blends == null || GbufferProgram.isInteger(format)
                        ? java.util.Optional.empty() : blends.get(i);
                colorTargets.add(new ColorTargetState(blend, format, ColorTargetState.WRITE_ALL));
            }
            colorTargets = List.copyOf(colorTargets);
            BackendRenderPipeline.CreateInfo info = new BackendRenderPipeline.CreateInfo(
                    "iris/" + name,
                    List.copyOf(shaders),
                    List.of(new BackendRenderPipeline.CreateInfo.VertexBuffer(0, QUAD_FORMAT.getVertexSize(), QUAD_FORMAT.getStepRate())),
                    List.copyOf(attribs),
                    List.copyOf(uniforms),
                    0,
                    null,
                    PolygonMode.FILL,
                    false,
                    colorTargets,
                    PrimitiveTopology.TRIANGLES);

            GpuDeviceBackend backend = ((FrontendGpuDeviceAccessor) RenderSystem.getDevice()).nativium$getBackend();
            BackendRenderPipeline compiled = backend.compilePipeline(info).finishCompile();
            if (compiled == null) {
                throw new ShaderCompileException("the backend could not compile the translated program (see the log for the MSL error)");
            }
            VertexFormat[] formats = new VertexFormat[16];
            formats[0] = QUAD_FORMAT;
            FrontendRenderPipeline pipeline = new FrontendRenderPipeline("iris/" + name, compiled, Arrays.asList(formats),
                    Object2IntMaps.unmodifiable(bindings), List.copyOf(uniforms), colorTargets, false, 0);
            PostProgram program = new PostProgram(name, pipeline, samplers, Set.copyOf(compareSamplers), blocks, storage);
            blocks = null; // owned by the program now
            return program;
        } finally {
            if (blocks != null) {
                blocks.values().forEach(PostUniforms.Block::free);
            }
            // The backend has translated the SPIR-V to MSL by now. Closing a module frees its SPIR-V buffer; stages
            // without a module yet (early failure) are freed directly.
            Set<java.nio.ByteBuffer> owned = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            modules.forEach(module -> owned.add(module.spv()));
            modules.forEach(SPIRVModule::close);
            stages.values().stream().filter(stage -> !owned.contains(stage.spirv())).forEach(stage -> MemoryUtil.memFree(stage.spirv()));
        }
    }

    /**
     * Metal has 16 sampler states per stage but more texture slots (Bliss's composite4 samples 20 textures). With more
     * than 16 textures, the backend compiles the sampler of every texture with a fixed sampler into the shader as a
     * {@code constexpr sampler} (identical ones count once); they move to the end of the binding list so the textures
     * with varying samplers keep sampler slots below 16.
     *
     * @return the (possibly reordered) uniform list; {@code bindings} and the SPIR-V decorations are updated in place
     */
    private static List<BindGroupLayout.UniformDescription> shareSamplers(final String name, final List<BindGroupLayout.UniformDescription> uniforms,
                                                                          final Object2IntOpenHashMap<String> bindings,
                                                                          final List<java.util.Map.Entry<SpvModule.Reflection.Descriptor, String>> assigned,
                                                                          final @org.jspecify.annotations.Nullable FixedSamplers fixedSamplers)
            throws UnsupportedProgramException {
        String pipelineName = "iris/" + name;
        long textures = uniforms.stream().filter(u -> u.type() != UniformType.UNIFORM_BUFFER).count();
        if (textures <= MAX_SAMPLERS) {
            de.nprmedia.nativium.backend.NativiumInterop.setConstexprSamplers(pipelineName, Map.of());
            return uniforms;
        }
        Map<String, com.mojang.renderpearl.api.textures.GpuSampler> fixed = new java.util.HashMap<>();
        List<BindGroupLayout.UniformDescription> first = new ArrayList<>();
        List<BindGroupLayout.UniformDescription> last = new ArrayList<>();
        for (BindGroupLayout.UniformDescription uniform : uniforms) {
            var sampler = uniform.type() == UniformType.UNIFORM_BUFFER || fixedSamplers == null ? null : fixedSamplers.fixedSampler(uniform.name());
            if (sampler != null) {
                fixed.put(uniform.name(), sampler);
                last.add(uniform);
            } else {
                first.add(uniform);
            }
        }
        long varying = first.stream().filter(u -> u.type() != UniformType.UNIFORM_BUFFER).count();
        if (varying > MAX_SAMPLERS) {
            throw new UnsupportedProgramException("uses " + textures + " textures, " + varying + " of them with varying samplers; Metal allows "
                    + MAX_SAMPLERS + " samplers per stage");
        }
        List<BindGroupLayout.UniformDescription> ordered = new ArrayList<>(first);
        ordered.addAll(last);
        bindings.clear();
        for (int i = 0; i < ordered.size(); i++) {
            bindings.put(ordered.get(i).name(), i);
        }
        for (java.util.Map.Entry<SpvModule.Reflection.Descriptor, String> entry : assigned) {
            entry.getKey().binding(bindings.getInt(entry.getValue()));
        }
        de.nprmedia.nativium.backend.NativiumInterop.setConstexprSamplers(pipelineName, fixed);
        return ordered;
    }

    @Override
    public void close() {
        uniforms.release();
        pipeline.close();
        blocks.values().forEach(PostUniforms.Block::free);
    }
}
