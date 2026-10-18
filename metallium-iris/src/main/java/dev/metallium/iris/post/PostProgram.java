package dev.metallium.iris.post;

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
import dev.metallium.iris.mixin.FrontendGpuDeviceAccessor;
import dev.metallium.shaders.GlslProgramTranslator;
import dev.metallium.shaders.GlslProgramTranslator.SpirvStage;
import dev.metallium.shaders.GlslProgramTranslator.Stage;
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
 * One fullscreen pass program (composite/deferred/prepare/begin/final, or Metallium's own copy program) compiled for
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
        return compile(name, vertex, fragment, colorFormats, null);
    }

    static PostProgram compile(final String name, final String vertex, final String fragment, final List<GpuFormat> colorFormats,
                               final StorageBindings.@org.jspecify.annotations.Nullable Validator validator)
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
                            uniformName = "metallium_defaults_" + type.getName();
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
                }
                shaders.add(new BackendRenderPipeline.CreateInfo.Shader(name + "." + type.getName(), "main", module));
            }

            List<BackendRenderPipeline.CreateInfo.AttribBinding> attribs = new ArrayList<>();
            for (SpvModule.Reflection.InterfaceVariable input : vertexModule.reflect().inputs()) {
                VertexFormatElement element = QUAD_FORMAT.getElements().stream().filter(e -> e.name().equals(input.name())).findFirst()
                        .orElseThrow(() -> new UnsupportedProgramException("vertex input '" + input.name() + "' (fullscreen passes only provide Position and UV0)"));
                attribs.add(new BackendRenderPipeline.CreateInfo.AttribBinding(0, input.location(), element.offset(), element.format()));
            }

            List<ColorTargetState> colorTargets = colorFormats.stream().map(f -> new ColorTargetState(java.util.Optional.empty(), f, ColorTargetState.WRITE_ALL)).toList();
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

            GpuDeviceBackend backend = ((FrontendGpuDeviceAccessor) RenderSystem.getDevice()).metallium$getBackend();
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

    @Override
    public void close() {
        uniforms.release();
        pipeline.close();
        blocks.values().forEach(PostUniforms.Block::free);
    }
}
