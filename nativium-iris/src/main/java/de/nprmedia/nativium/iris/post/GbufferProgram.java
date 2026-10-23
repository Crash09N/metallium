/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.iris.post;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
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
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spvc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A shader pack's gbuffers program (after Iris's {@code patchVanilla}/{@code patchSodium}) compiled for the Metal
 * backend as a replacement for one vanilla or Sodium {@link RenderPipeline}: same vertex layout, depth, cull and
 * topology as the replaced pipeline, but with the pack's shaders and one colour target per gbuffer attachment of the
 * captured render pass (see {@link GbufferPasses}). Outputs of the pack's {@code DRAWBUFFERS} slot {@code i} are moved
 * to the attachment index of that colortex; attachments the program does not write get an empty write mask.
 *
 * <p>Uniform names are translated to what the frontend sets on the pass: Iris renames vanilla's uniform blocks to
 * {@code iris_<Name>} and the vanilla samplers to {@code gtexture/lightmap/iris_overlay}; those are bound under the
 * vanilla/Sodium names ({@code DynamicTransforms}, {@code Sampler0}, {@code u_BlockTex}, ...), everything else (loose
 * uniforms, colortex/depthtex/noise/shadow samplers) is bound by {@link GbufferPasses} after {@code setPipeline}.
 */
final class GbufferProgram implements AutoCloseable {
    private static final Pattern VERTEX_INPUT = Pattern.compile(
            "^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?(?:(?:flat|smooth|noperspective|centroid|invariant|highp|mediump|lowp)\\s+)*in\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;",
            Pattern.MULTILINE);
    private static final Pattern SODIUM_PUSH_CONSTANTS = Pattern.compile("layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+iris_SodiumPushConstants\\b");
    /** Pack sampler name -> vanilla/Sodium names the frontend binds for the same texture unit (Iris's IrisBindings). */
    private static final Map<String, List<String>> SAMPLER_ALIASES = Map.of(
            "gtexture", List.of("Sampler0", "u_BlockTex"),
            "tex", List.of("Sampler0", "u_BlockTex"),
            "texture", List.of("Sampler0", "u_BlockTex"),
            "u_MainSampler", List.of("Sampler0", "u_BlockTex"),
            "lightmap", List.of("Sampler2", "u_LightTex", "uLightMap"),
            // Distant Horizons' Blaze3D terrain pipeline (DhIrisBridge): DH's own sampler names.
            "dhBlockAtlas", List.of("uBlockAtlas"),
            "iris_overlay", List.of("Sampler1"));
    /** Iris prefixes vanilla's uniform blocks with {@code iris_} in gbuffers programs. */
    private static final Set<String> VANILLA_BLOCKS = Set.of("DynamicTransforms", "Projection", "Globals", "Fog", "Lighting", "CloudInfo");
    private static final int UNUSED_BINDING = 1000;
    /** Metal: 16 sampler states per stage; the backend numbers textures per pipeline. */
    private static final int MAX_TEXTURES = 16;

    final String name;
    final FrontendRenderPipeline pipeline;
    /** Combined image samplers bound by Nativium (not delivered by the vanilla/Sodium renderer). */
    final Set<String> ownSamplers;
    /** Vanilla uniform blocks the replaced pipeline does not have (bound from RenderSystem by Nativium). */
    final Set<String> extraBlocks;
    /** {@code sampler2DShadow}s: need a depth-compare sampler. */
    final Set<String> compareSamplers;
    /** Uniform-buffer name -> CPU default block of one stage. */
    final Map<String, PostUniforms.Block> blocks;
    final PostUniforms.Holder uniforms;
    /** Alpha-test reference for {@code iris_currentAlphaTest} (Iris's CapturedRenderingState). */
    final float alphaReference;
    final int normalMatrix;
    final int modelViewInverse;
    final int projectionInverse;
    /**
     * Reads a uniform that depends on the bound albedo texture ({@code atlasSize}, {@code gtextureSize},
     * {@code gtextureId}): vanilla binds {@code Sampler0} after {@code setPipeline}, so the blocks are uploaded again
     * when it changes (Iris's GL path evaluates uniforms at draw time).
     */
    final boolean readsAlbedo;
    /** Stage 5: storage images/SSBOs outside the renderpearl binding table (bound per draw). */
    StorageBindings storage = new StorageBindings();
    /** Stage 6: replaces the draws of a program with an emulated geometry shader; null otherwise. */
    GeometryEmulation.@Nullable Interceptor geometry;

    private GbufferProgram(final String name, final FrontendRenderPipeline pipeline, final Set<String> ownSamplers, final Set<String> extraBlocks,
                           final Map<String, PostUniforms.Block> blocks, final float alphaReference, final Set<String> compareSamplers) {
        this.name = name;
        this.compareSamplers = compareSamplers;
        this.pipeline = pipeline;
        this.ownSamplers = ownSamplers;
        this.extraBlocks = extraBlocks;
        this.blocks = blocks;
        this.uniforms = new PostUniforms.Holder(name, List.copyOf(blocks.values()));
        this.alphaReference = alphaReference;
        this.normalMatrix = uniforms.location("iris_NormalMat", net.irisshaders.iris.gl.uniform.UniformType.MAT3).orElse(-1);
        this.modelViewInverse = uniforms.location("iris_ModelViewMatInverse", net.irisshaders.iris.gl.uniform.UniformType.MAT4).orElse(-1);
        this.projectionInverse = uniforms.location("iris_ProjMatInverse", net.irisshaders.iris.gl.uniform.UniformType.MAT4).orElse(-1);
        this.readsAlbedo = blocks.values().stream().anyMatch(block ->
                block.find("atlasSize") != null || block.find("gtextureSize") != null || block.find("gtextureId") != null);
    }

    /**
     * What to build.
     *
     * @param source        the replaced vanilla/Sodium pipeline (vertex layout, depth, cull, topology, frontend uniform names)
     * @param vertexFormat  actual layout of vertex buffer slot 0 (for Sodium: Iris's extended terrain format)
     * @param drawBuffers   the program's {@code DRAWBUFFERS} (colortex indices, by output location)
     * @param attachments   colortex index of each colour attachment of the captured pass
     * @param blend         blend for written float attachments (pack override or the replaced pipeline's blend)
     * @param bufferBlends  per-colortex blend overrides ({@code blend.<program>.<buffer>})
     * @param shadow        shadow pass: no culling and the depth test flipped to forward Z (Iris's MixinGlRenderPipeline)
     * @param geometrySlots stage 6: {@code vertex} is a {@link GeometryEmulation} merge that pulls its vertices itself
     *                      ({@code slots} output vertices per triangle); 0 for a plain vertex shader
     */
    record Spec(String name, String vertex, String fragment, RenderPipeline source, VertexFormat vertexFormat,
                int[] drawBuffers, int[] attachments, List<GpuFormat> attachmentFormats,
                Optional<BlendFunction> blend, Map<Integer, Optional<BlendFunction>> bufferBlends, float alphaReference, boolean shadow,
                int geometrySlots) {
        Spec(String name, String vertex, String fragment, RenderPipeline source, VertexFormat vertexFormat,
             int[] drawBuffers, int[] attachments, List<GpuFormat> attachmentFormats,
             Optional<BlendFunction> blend, Map<Integer, Optional<BlendFunction>> bufferBlends, float alphaReference, boolean shadow) {
            this(name, vertex, fragment, source, vertexFormat, drawBuffers, attachments, attachmentFormats, blend, bufferBlends, alphaReference, shadow, 0);
        }
    }

    /**
     * The shadow pass uses forward Z (cleared to 1, Iris's shadow projection and {@code DepthTransformer}), the level
     * pass reversed Z: Iris flips the depth comparison of every pipeline while shadows render.
     */
    static com.mojang.renderpearl.api.pipeline.@Nullable DepthStencilState shadowDepth(final com.mojang.renderpearl.api.pipeline.@Nullable DepthStencilState state) {
        if (state == null) {
            return null;
        }
        com.mojang.renderpearl.api.pipeline.CompareOp flipped = switch (state.depthTest()) {
            case LESS_THAN -> com.mojang.renderpearl.api.pipeline.CompareOp.GREATER_THAN;
            case LESS_THAN_OR_EQUAL -> com.mojang.renderpearl.api.pipeline.CompareOp.GREATER_THAN_OR_EQUAL;
            case GREATER_THAN -> com.mojang.renderpearl.api.pipeline.CompareOp.LESS_THAN;
            case GREATER_THAN_OR_EQUAL -> com.mojang.renderpearl.api.pipeline.CompareOp.LESS_THAN_OR_EQUAL;
            default -> state.depthTest();
        };
        return new com.mojang.renderpearl.api.pipeline.DepthStencilState(flipped, state.writeDepth(), state.depthBiasScaleFactor(), state.depthBiasConstant());
    }

    private static final java.util.regex.Pattern OUTPUT_DECLARATION =
            java.util.regex.Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*out\\s+(\\w+\\s+\\w+\\s*;)");

    /**
     * Fragment outputs whose colortex is not an attachment of the captured pass (more than 8 buffers in the pack's
     * gbuffers) become plain globals: Metal has colour locations 0-7 only, so they cannot be parked past the last
     * attachment like the others.
     */
    static String withoutUnmappedOutputs(final String fragment, final int[] drawBuffers, final int[] attachments) {
        java.util.regex.Matcher m = OUTPUT_DECLARATION.matcher(fragment);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int location = Integer.parseInt(m.group(1));
            boolean mapped = location < drawBuffers.length && indexOf(attachments, drawBuffers[location]) >= 0;
            boolean fits = mapped || attachments.length + location < ColorTargetState.MAX_COLOR_TARGETS;
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(fits ? m.group() : m.group(2)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Output locations the fragment shader declares but never assigns (Bliss's weather writes only gl_FragData[1]). GL
     * leaves such attachments unchanged in practice; in Metal the unwritten output is undefined and, blended, paints
     * garbage (green rain streaks), so their attachments get an empty write mask.
     */
    /** An array fragment output ({@code out vec4 color[2];}) spans several locations from one declared base location. */
    private static final java.util.regex.Pattern ARRAY_OUTPUT = java.util.regex.Pattern.compile("\\bout\\s+\\w+\\s+\\w+\\s*\\[");

    static Set<Integer> unwrittenOutputs(final String fragment) {
        Set<Integer> unwritten = new java.util.HashSet<>();
        java.util.regex.Matcher m = OUTPUT_DECLARATION.matcher(fragment);
        while (m.find()) {
            String[] words = m.group(2).replace(";", " ").trim().split("\\s+");
            String variable = words[words.length - 1];
            java.util.regex.Matcher uses = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(variable) + "\\b").matcher(fragment);
            int count = 0;
            while (uses.find() && count < 2) {
                count++;
            }
            if (count < 2) {
                unwritten.add(Integer.parseInt(m.group(1)));
            }
        }
        return unwritten;
    }

    static GbufferProgram compile(final Spec spec, final StorageBindings.@Nullable Validator validator) throws Exception {
        StorageBindings storage = new StorageBindings();
        Set<String> available = new LinkedHashSet<>();
        if (spec.vertexFormat != null) {
            for (VertexFormatElement element : spec.vertexFormat.getElements()) {
                available.add(element.name());
            }
        }
        Map<Stage, String> sources = new EnumMap<>(Stage.class);
        String vertex = SODIUM_PUSH_CONSTANTS.matcher(spec.vertex).replaceAll("layout(push_constant) uniform iris_SodiumPushConstants");
        if (isPhysicsModPipeline(spec.source, available)) {
            String patched = physicsModInputs(vertex);
            de.nprmedia.nativium.iris.NativiumIris.LOGGER.info("[nativium-iris] {}: Physics Mod layout, atlas UVs/light {}", spec.name,
                    patched.equals(vertex) ? "NOT patched (no iris_UV0 input): " + vertex.lines().filter(l -> l.contains("UV0") || l.contains("MultiTexCoord0")).toList() : "patched");
            vertex = patched;
        }
        sources.put(Stage.VERTEX, spec.geometrySlots > 0 ? vertex : defaultMissingInputs(vertex, available));
        sources.put(Stage.FRAGMENT, withoutUnmappedOutputs(spec.fragment, spec.drawBuffers, spec.attachments));
        Set<Integer> unwritten = unwrittenOutputs(spec.fragment);
        Map<Stage, SpirvStage> stages = GlslProgramTranslator.compileToSpirv(spec.name, sources);
        Set<String> compareSamplers = new LinkedHashSet<>();

        List<SPIRVModule> modules = new ArrayList<>();
        Map<String, PostUniforms.Block> blocks = new LinkedHashMap<>();
        try {
            int[] outputMap = new int[16];
            java.util.Arrays.fill(outputMap, -1);
            for (int i = 0; i < spec.drawBuffers.length && i < outputMap.length; i++) {
                outputMap[i] = indexOf(spec.attachments, spec.drawBuffers[i]);
            }
            // Draw-buffer slots without any fragment output (Bliss's weather: gl_FragData[0] is never referenced, so
            // Iris declares only iris_FragData1) are not written either; Metal would store an undefined value there.
            Set<Integer> declared = new java.util.HashSet<>();
            GlslProgramTranslator.remapOutputLocations(stages.get(Stage.FRAGMENT).spirv(), location -> {
                declared.add(location);
                return location;
            });
            if (!ARRAY_OUTPUT.matcher(spec.fragment).find()) {
                for (int i = 0; i < spec.drawBuffers.length; i++) {
                    if (!declared.contains(i)) {
                        unwritten.add(i);
                    }
                }
            }
            int unused = spec.attachments.length;
            GlslProgramTranslator.remapOutputLocations(stages.get(Stage.FRAGMENT).spirv(), location -> {
                int mapped = location >= 0 && location < outputMap.length ? outputMap[location] : -1;
                // Outputs without an attachment go past the last attachment (Metal ignores them).
                return mapped >= 0 ? mapped : unused + location;
            });

            Map<String, BindGroupLayout.UniformDescription> sourceUniforms = new HashMap<>();
            for (BindGroupLayout.UniformDescription description : BindGroupLayout.flattenUniforms(spec.source.getBindGroupLayouts())) {
                sourceUniforms.put(description.name(), description);
            }

            List<BackendRenderPipeline.CreateInfo.Shader> shaders = new ArrayList<>();
            Object2IntOpenHashMap<String> bindings = new Object2IntOpenHashMap<>();
            List<BindGroupLayout.UniformDescription> uniforms = new ArrayList<>();
            Set<String> ownSamplers = new LinkedHashSet<>();
            Set<String> extraBlocks = new LinkedHashSet<>();
            int unusedBindings = 0;
            SPIRVModule vertexModule = null;
            List<SpirvStage> ordered = new ArrayList<>(stages.values());
            Collections.reverse(ordered); // fragment first: it gets the low texture indices (16 samplers per stage)
            for (SpirvStage stage : ordered) {
                ShaderType type = stage.stage() == Stage.VERTEX ? ShaderType.VERTEX : ShaderType.FRAGMENT;
                compareSamplers.addAll(GlslProgramTranslator.depthCompareSamplerNames(stage.spirv()));
                SPIRVModule module = new SPIRVModule(stage.spirv(), type);
                modules.add(module);
                if (type == ShaderType.VERTEX) {
                    vertexModule = module;
                }
                Set<String> active = GlslProgramTranslator.activeResourceNames(stage.spirv());
                for (SpvModule.Reflection.Descriptor descriptor : module.reflect().descriptors()) {
                    if (!active.contains(descriptor.name())) {
                        // Declared but unused: out of the backend's binding table (not emitted in MSL).
                        descriptor.binding(UNUSED_BINDING + unusedBindings++);
                        descriptor.descriptorSetIndex(0);
                        continue;
                    }
                    BindGroupLayout.UniformDescription description;
                    switch (descriptor.resourceType()) {
                        case Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER -> {
                            String block = descriptor.name();
                            if (block.equals(stage.defaultBlockName())) {
                                String uniformName = "nativium_defaults_" + type.getName();
                                blocks.put(uniformName, new PostUniforms.Block(stage.defaultBlockSize(), stage.defaultBlock()));
                                description = new BindGroupLayout.UniformDescription(uniformName, UniformType.UNIFORM_BUFFER);
                            } else {
                                String frontendName = block.startsWith("iris_") && VANILLA_BLOCKS.contains(block.substring(5)) ? block.substring(5) : block;
                                description = sourceUniforms.get(frontendName);
                                if (description == null && VANILLA_BLOCKS.contains(frontendName)) {
                                    // Iris declares Fog/Projection/Globals in every vanilla-patched program; when the
                                    // replaced pipeline does not use them, GbufferState binds RenderSystem's buffers.
                                    description = new BindGroupLayout.UniformDescription(frontendName, UniformType.UNIFORM_BUFFER);
                                    extraBlocks.add(frontendName);
                                }
                                if (description == null) {
                                    throw new PostProgram.UnsupportedProgramException("uniform block '" + block + "' is not provided by " + spec.source.getLocation());
                                }
                            }
                        }
                        case Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE -> {
                            String sampler = descriptor.name();
                            description = sourceUniforms.get(sampler);
                            if (description == null) {
                                for (String alias : SAMPLER_ALIASES.getOrDefault(sampler, List.of())) {
                                    if (sourceUniforms.containsKey(alias)) {
                                        description = sourceUniforms.get(alias);
                                        break;
                                    }
                                }
                            }
                            if (description == null) {
                                int dim = descriptor.type().dimensions();
                                if (dim == 5) { // SpvDimBuffer
                                    throw new PostProgram.UnsupportedProgramException("texel buffer '" + sampler + "' is not provided by " + spec.source.getLocation());
                                }
                                if (dim != 1 && dim != 4 && (validator == null || !validator.isCustomSampler(sampler))) { // SpvDim2D, SpvDimRect
                                    throw new PostProgram.UnsupportedProgramException("sampler '" + sampler + "' has dimension " + dim + " (only 2D textures are supported yet)");
                                }
                                description = new BindGroupLayout.UniformDescription(sampler, UniformType.COMBINED_IMAGE_SAMPLER);
                                ownSamplers.add(sampler);
                            }
                        }
                        case Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE -> {
                            storage.assign(descriptor, StorageBindings.Kind.IMAGE, validator);
                            continue;
                        }
                        case Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER -> {
                            if (spec.geometrySlots > 0 && descriptor.name().startsWith(GeometryEmulation.PREFIX)) {
                                descriptor.descriptorSetIndex(0); // fixed Metal index (layout binding), bound by the draw interceptor
                                continue;
                            }
                            storage.assign(descriptor, StorageBindings.Kind.BUFFER, validator);
                            continue;
                        }
                        default -> throw new PostProgram.UnsupportedProgramException("resource '" + descriptor.name() + "' of type " + descriptor.resourceType());
                    }
                    String uniformName = description.name();
                    int binding = bindings.computeIfAbsent(uniformName, key -> bindings.size());
                    if (binding == uniforms.size()) {
                        uniforms.add(description);
                    }
                    descriptor.binding(binding);
                    descriptor.descriptorSetIndex(0);
                }
                shaders.add(new BackendRenderPipeline.CreateInfo.Shader(spec.name + "." + type.getName(), "main", module));
            }

            long textures = uniforms.stream().filter(u -> u.type() != UniformType.UNIFORM_BUFFER).count();
            if (textures > MAX_TEXTURES) {
                throw new PostProgram.UnsupportedProgramException("uses " + textures + " textures; Metal allows " + MAX_TEXTURES + " samplers per stage");
            }

            List<BackendRenderPipeline.CreateInfo.AttribBinding> attribs = new ArrayList<>();
            for (SpvModule.Reflection.InterfaceVariable input : vertexModule.reflect().inputs()) {
                VertexFormatElement element = spec.vertexFormat == null ? null : element(spec.vertexFormat, input.name());
                if (element == null) {
                    throw new PostProgram.UnsupportedProgramException("vertex input '" + input.name() + "' has no vertex element");
                }
                attribs.add(new BackendRenderPipeline.CreateInfo.AttribBinding(0, input.location(), element.offset(), element.format()));
            }

            List<ColorTargetState> colorTargets = new ArrayList<>();
            int sourceMask = spec.source.getColorTargetStates().isEmpty() ? ColorTargetState.WRITE_ALL : spec.source.getColorTargetStates().getFirst().writeMask() & ColorTargetState.WRITE_ALL;
            for (int i = 0; i < spec.attachments.length; i++) {
                int buffer = spec.attachments[i];
                GpuFormat format = spec.attachmentFormats.get(i);
                int location = indexOf(spec.drawBuffers, buffer);
                boolean written = location >= 0 && !unwritten.contains(location);
                Optional<BlendFunction> blend = spec.bufferBlends.getOrDefault(buffer, spec.blend);
                if (isInteger(format)) {
                    blend = Optional.empty();
                }
                colorTargets.add(new ColorTargetState(blend, format, written ? sourceMask : ColorTargetState.WRITE_NONE));
            }

            List<VertexFormat> frontendFormats = new ArrayList<>(Collections.nCopies(16, null));
            frontendFormats.set(0, spec.vertexFormat);
            BackendRenderPipeline.CreateInfo info = new BackendRenderPipeline.CreateInfo(
                    "iris/" + spec.name,
                    List.copyOf(shaders),
                    spec.vertexFormat == null || spec.geometrySlots > 0 ? List.of()
                            : List.of(new BackendRenderPipeline.CreateInfo.VertexBuffer(0, spec.vertexFormat.getVertexSize(), spec.vertexFormat.getStepRate())),
                    List.copyOf(attribs),
                    List.copyOf(uniforms),
                    spec.source.pushConstantSize(),
                    spec.shadow ? shadowDepth(spec.source.getDepthStencilState()) : spec.source.getDepthStencilState(),
                    spec.source.getPolygonMode(),
                    !spec.shadow && spec.source.isCull(),
                    List.copyOf(colorTargets),
                    spec.source.getPrimitiveTopology());

            GpuDeviceBackend backend = ((FrontendGpuDeviceAccessor) RenderSystem.getDevice()).nativium$getBackend();
            BackendRenderPipeline compiled = backend.compilePipeline(info).finishCompile();
            if (compiled == null) {
                throw new ShaderCompileException("the backend could not compile the translated program (see the log for the MSL error)");
            }
            FrontendRenderPipeline pipeline = new FrontendRenderPipeline("iris/" + spec.name, compiled, Collections.unmodifiableList(frontendFormats),
                    Object2IntMaps.unmodifiable(bindings), List.copyOf(uniforms), List.copyOf(colorTargets), spec.source.wantsDepthTexture(), spec.source.pushConstantSize());
            GbufferProgram program = new GbufferProgram(spec.name, pipeline, ownSamplers, extraBlocks, blocks, spec.alphaReference, Set.copyOf(compareSamplers));
            program.storage = storage;
            if (spec.geometrySlots > 0) {
                program.geometry = new GeometryEmulation.Interceptor(spec.name, spec.geometrySlots);
            }
            blocks = null; // owned by the program now
            return program;
        } finally {
            if (blocks != null) {
                blocks.values().forEach(PostUniforms.Block::free);
            }
            Set<ByteBuffer> owned = Collections.newSetFromMap(new IdentityHashMap<>());
            modules.forEach(module -> owned.add(module.spv()));
            modules.forEach(SPIRVModule::close);
            stages.values().stream().filter(stage -> !owned.contains(stage.spirv())).forEach(stage -> MemoryUtil.memFree(stage.spirv()));
        }
    }

    /** Vertex element for a shader input: Iris names vanilla attributes {@code iris_<Element>}, Sodium's keep their names. */
    @Nullable
    private static VertexFormatElement element(final VertexFormat format, final String input) {
        for (VertexFormatElement element : format.getElements()) {
            if (input.equals(element.name()) || input.equals("iris_" + element.name())) {
                return element;
            }
        }
        return null;
    }

    private static final Pattern UV0_INPUT = Pattern.compile("^([ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+vec2\\s+)iris_UV0(\\s*;)", Pattern.MULTILINE);
    private static final Pattern UV2_INPUT = Pattern.compile("^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?in\\s+(i?vec2)\\s+iris_UV2\\s*;", Pattern.MULTILINE);

    /**
     * Physics Mod's own entity pipelines ({@code physics_entity*}, compatibility only: Physics Mod is closed source). Its
     * Iris path (atlas UVs and lightmap/entity attributes in an extended vertex format) only runs on its OpenGL backend
     * check; on Metal it draws its plain layout (Position, Color, UV0, Normal): face-local UVs that its
     * {@code DynamicTransforms.TextureMat} maps into the block atlas, and the light in its {@code PhysicsBrightness}
     * uniform block. Packs read {@code gl_MultiTexCoord0} without the texture matrix (Photon, Rethinking Voxels, ...),
     * so debris sampled the whole atlas and rendered black.
     */
    static boolean isPhysicsModPipeline(final RenderPipeline source, final Set<String> available) {
        return source != null && source.getLocation().getPath().startsWith("pipeline/physics_")
                && available.contains("UV0") && !available.contains("UV2");
    }

    /**
     * For {@link #isPhysicsModPipeline}: UV0 arrives already in atlas space (the texture matrix is applied here and
     * {@code gl_TextureMatrix[0]} becomes identity), and the lightmap input reads Physics Mod's light uniform, like its
     * own shader and its OpenGL Iris path.
     */
    static String physicsModInputs(final String vertex) {
        if (!UV0_INPUT.matcher(vertex).find()) {
            return vertex;
        }
        String out = vertex.replace("iris_transforms.TextureMat", "mat4(1.0)");
        // The input keeps its name: defaultMissingInputs and the vertex layout match inputs by name, a renamed input
        // became the constant (0, 0) and every piece sampled one atlas texel (single-colour debris).
        out = UV0_INPUT.matcher(out).replaceAll("$1nativium_physicsUV0_input$2");
        out = out.replaceAll("\\biris_UV0\\b", "(iris_transforms.TextureMat * vec4(iris_UV0, 0.0, 1.0)).xy");
        out = out.replace("nativium_physicsUV0_input", "iris_UV0");
        Matcher uv2 = UV2_INPUT.matcher(out);
        if (uv2.find()) {
            String type = uv2.group(1);
            out = uv2.replaceAll(Matcher.quoteReplacement(
                    "layout(std140) uniform PhysicsBrightness { int LightU; int LightV; int OverlayU; int OverlayV; } nativium_physicsLight;"));
            out = out.replaceAll("\\biris_UV2\\b", type + "(nativium_physicsLight.LightU, nativium_physicsLight.LightV)");
        }
        return out;
    }

    /**
     * Inputs the replaced pipeline's vertex layout does not provide (e.g. {@code mc_Entity}, {@code at_tangent} for
     * vanilla formats without Iris's extensions) become constants, like an unbound GL attribute with a default value.
     */
    static String defaultMissingInputs(final String vertex, final Set<String> available) {
        Matcher matcher = VERTEX_INPUT.matcher(vertex);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            boolean present = available.contains(name) || (name.startsWith("iris_") && available.contains(name.substring(5)));
            String replacement = present ? matcher.group() : type + " " + name + " = " + defaultValue(type, name) + ";";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static String defaultValue(final String type, final String name) {
        boolean unsigned = type.startsWith("u");
        return switch (name) {
            case "mc_Entity", "iris_Entity" -> unsigned ? type + "(0)" : type + "(-1)";
            case "iris_Color" -> type + "(1)";
            case "iris_UV2" -> type + "(240)";
            case "iris_UV1" -> type.endsWith("2") ? type + "(0, 10)" : type + "(0)";
            case "iris_LineWidth" -> type + "(1)";
            case "at_tangent" -> type.equals("vec4") ? "vec4(1.0, 0.0, 0.0, 1.0)" : type + "(0)";
            case "iris_Normal" -> type.equals("vec3") ? "vec3(0.0, 1.0, 0.0)" : type + "(0)";
            default -> type + "(0)";
        };
    }

    static int indexOf(final int[] values, final int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return -1;
    }

    static boolean isInteger(final GpuFormat format) {
        String type = format.componentType().name();
        return type.contains("INT") || format == GpuFormat.RGB10A2_UINT;
    }

    @Override
    public void close() {
        uniforms.release();
        pipeline.close();
        blocks.values().forEach(PostUniforms.Block::free);
    }
}
