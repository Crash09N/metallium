/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.iris.post;

import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import de.nprmedia.nativium.iris.NativiumIris;
import de.nprmedia.nativium.iris.mixin.BlendModeOverrideAccessor;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.BufferBlendInformation;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.IdMap;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.ProgramDirectives;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.VanillaUniforms;
import net.irisshaders.iris.uniforms.builtin.BuiltinReplacementUniforms;
import net.irisshaders.iris.vertices.sodium.terrain.FormatAnalyzer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Iris adapter stage 3: the gbuffers half of {@link MetalPostPipeline}. Owns the gbuffer attachment layout, the
 * compiled gbuffers programs (one per replaced vanilla/Sodium pipeline), the vanilla fallbacks with the gbuffer layout,
 * the depth copies ({@code depthtex1/2}) and the per-draw resource binding. Program selection reuses Iris unchanged:
 * {@link IrisPipelines} (vanilla/Sodium pipeline -> {@link ShaderKey}), {@link ProgramFallbackResolver} (missing
 * program -> fallback chain) and {@link TransformPatcher#patchVanilla}/{@link TransformPatcher#patchSodium} (GLSL).
 * Like Iris's {@code ShaderCreator}/{@code ExtendedShader} (LGPL-3.0), whose behaviour this class follows.
 */
final class GbufferState implements AutoCloseable {
    private static final Object NO_KEY = new Object();
    private static final Object NO_KEY_SHADOW = new Object();
    private static final Object DH_KEY = new Object();
    private static final Object DH_SHADOW_KEY = new Object();

    /** What a DH program replaces: DH's terrain (gbuffers or shadow pass) or its generic objects. */
    enum DhKind { TERRAIN, SHADOW, GENERIC }

    /** Programs compiled from dh_terrain/dh_water/dh_shadow/dh_generic (their matrices are set per draw). */
    private final Map<GbufferProgram, DhKind> dhPrograms = new IdentityHashMap<>();

    private final MetalPostPipeline owner;
    private final ProgramSet programSet;
    private final ProgramFallbackResolver resolver;
    /** colortex index of each colour attachment of a captured pass (colortex0 first unless 8 other buffers are written). */
    private final int[] attachments;
    private final List<GpuFormat> attachmentFormats = new ArrayList<>();
    /** Stage 4: shadowcolor index of each colour attachment of a captured shadow pass (always starts with shadowcolor0). */
    private final int[] shadowAttachments;
    private final List<GpuFormat> shadowAttachmentFormats = new ArrayList<>();
    private final Map<CompiledRenderPipeline, Map<Object, CompiledRenderPipeline>> substitutes = new IdentityHashMap<>();
    private final Map<CompiledRenderPipeline, GbufferProgram> programs = new IdentityHashMap<>();
    private final Map<CompiledRenderPipeline, CompiledRenderPipeline> formatFixes = new IdentityHashMap<>();
    private final Set<String> reported = new LinkedHashSet<>();
    private final Set<String> compiledNames = new TreeSet<>();
    private final Set<Integer> neededTargets = new TreeSet<>();
    private final ByteBuffer zeroBlock = MemoryUtil.memCalloc(256);
    private final Matrix4f tempMatrix = new Matrix4f();
    private final Matrix3f tempMatrix3 = new Matrix3f();
    private final float[] floats16 = new float[16];
    private final float[] floats9 = new float[9];
    private ImmutableSet<Integer> writesAlt = ImmutableSet.of();
    private boolean blockIdsInitialized;
    @Nullable
    private GpuTexture depthNoTranslucents;
    @Nullable
    private GpuTextureView depthNoTranslucentsView;
    @Nullable
    private GpuTexture depthNoHand;
    @Nullable
    private GpuTextureView depthNoHandView;
    private final GpuTexture defaultNormals;
    private final GpuTextureView defaultNormalsView;
    private final GpuTexture defaultSpecular;
    private final GpuTextureView defaultSpecularView;

    private static final java.util.regex.Pattern FRAGMENT_OUTPUT = java.util.regex.Pattern.compile("\\bgl_Frag(Data|Color)\\b|\\bout\\s+\\w+\\s+\\w+");

    GbufferState(final MetalPostPipeline owner, final ProgramSet programSet) {
        this.owner = owner;
        this.programSet = programSet;
        this.resolver = new ProgramFallbackResolver(programSet);
        TreeSet<Integer> used = new TreeSet<>();
        for (ProgramId id : ProgramId.values()) {
            if (id.getGroup() == ProgramGroup.Gbuffers || id.getGroup() == ProgramGroup.Dh && id != ProgramId.DhShadow && DhIrisBridge.installed()) {
                programSet.get(id).ifPresent(source -> {
                    // A program without any fragment output (Bliss's gbuffers_skybasic: "discard;") writes nothing,
                    // even though its draw buffers default to colortex0.
                    if (source.getFragmentSource().map(text -> !FRAGMENT_OUTPUT.matcher(text).find()).orElse(false)) {
                        return;
                    }
                    for (int buffer : source.getDirectives().getDrawBuffers()) {
                        used.add(buffer);
                    }
                });
            }
        }
        // colortex0 is the target of vanilla shaders without a pack program. When the pack's own programs never write it
        // and need all 8 Metal attachments (Bliss: colortex1,2,7,8,9,11,14,15), it is left out instead of a pack buffer.
        if (!used.contains(0) && used.size() < ColorTargetState.MAX_COLOR_TARGETS) {
            used.add(0);
        } else if (!used.contains(0)) {
            NativiumIris.LOGGER.info("[nativium-iris] gbuffers write {} buffers but not colortex0; vanilla shaders without a pack program draw nothing", used.size());
        }
        List<Integer> list = new ArrayList<>(used);
        if (list.size() > ColorTargetState.MAX_COLOR_TARGETS) {
            owner.problems().add("gbuffers write " + list.size() + " buffers; Metal render passes have 8 attachments, writes to colortex"
                    + list.subList(ColorTargetState.MAX_COLOR_TARGETS, list.size()) + " are dropped");
            list = list.subList(0, ColorTargetState.MAX_COLOR_TARGETS);
        }
        this.attachments = list.stream().mapToInt(Integer::intValue).toArray();
        for (int buffer : attachments) {
            attachmentFormats.add(owner.targets().format(buffer));
        }

        TreeSet<Integer> shadowUsed = new TreeSet<>();
        shadowUsed.add(0);
        for (ProgramId id : ProgramId.values()) {
            if (id.getGroup() == ProgramGroup.Shadow || id == ProgramId.DhShadow && DhIrisBridge.installed()) {
                programSet.get(id).ifPresent(source -> {
                    for (int buffer : source.getDirectives().getDrawBuffers()) {
                        if (buffer >= 0 && buffer < ShadowTargets.COLOR_COUNT) {
                            shadowUsed.add(buffer);
                        }
                    }
                });
            }
        }
        this.shadowAttachments = shadowUsed.stream().limit(ColorTargetState.MAX_COLOR_TARGETS).mapToInt(Integer::intValue).toArray();
        for (int buffer : shadowAttachments) {
            shadowAttachmentFormats.add(owner.shadowTargets().colorFormat(buffer));
        }

        var device = RenderSystem.getDevice();
        int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
        this.defaultNormals = device.createTexture("iris default normals", usage, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
        this.defaultNormalsView = device.createTextureView(defaultNormals);
        this.defaultSpecular = device.createTexture("iris default specular", usage, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
        this.defaultSpecularView = device.createTextureView(defaultSpecular);
        ByteBuffer pixel = MemoryUtil.memAlloc(4);
        try {
            CommandEncoder encoder = device.createCommandEncoder();
            // RGBA in memory order: a flat tangent-space normal (0.5, 0.5, 1) like Iris's default normal texture.
            pixel.put(0, (byte) 127).put(1, (byte) 127).put(2, (byte) 255).put(3, (byte) 255);
            encoder.writeToTexture(defaultNormals, pixel, 0, 0, 0, 0, 1, 1);
            pixel.putInt(0, 0);
            encoder.writeToTexture(defaultSpecular, pixel, 0, 0, 0, 0, 1, 1);
        } finally {
            MemoryUtil.memFree(pixel);
        }
    }

    String attachmentSummary() {
        StringBuilder out = new StringBuilder("colortex");
        for (int i = 0; i < attachments.length; i++) {
            out.append(i == 0 ? "" : ",").append(attachments[i]);
        }
        return out.toString();
    }

    String shadowAttachmentSummary() {
        StringBuilder out = new StringBuilder("shadowcolor");
        for (int i = 0; i < shadowAttachments.length; i++) {
            out.append(i == 0 ? "" : ",").append(shadowAttachments[i]);
        }
        return out.toString();
    }

    /** Before the shadow pass opens its render passes: creates (and clears) the shadowcolor attachments. */
    void prepareShadowAttachments() {
        for (int buffer : shadowAttachments) {
            owner.shadowTargets().color(buffer);
        }
    }

    private static boolean shadowPass() {
        return net.irisshaders.iris.shadows.ShadowRenderingState.areShadowsCurrentlyBeingRendered();
    }

    /** Like IrisRenderingPipeline's constructor: Iris's extended Sodium vertex format, ID maps and lighting settings. */
    void applyWorldRenderingSettings() {
        PackDirectives directives = programSet.getPackDirectives();
        IdMap idMap = programSet.getPack().getIdMap();
        WorldRenderingSettings settings = WorldRenderingSettings.INSTANCE;
        settings.setVertexFormat(FormatAnalyzer.createFormat(true, true, true, true));
        settings.setEntityIds(idMap.getEntityIdMap());
        settings.setItemIds(idMap.getItemIdMap());
        settings.setAmbientOcclusionLevel(directives.getAmbientOcclusionLevel());
        settings.setDisableDirectionalShading(!directives.isOldLighting());
        settings.setUseSeparateAo(directives.shouldUseSeparateAo());
        settings.setBreaksAnisotropy(directives.breaksAnisotropy());
        settings.setVoxelizeLightBlocks(directives.shouldVoxelizeLightBlocks());
        settings.setSeparateEntityDraws(directives.shouldUseSeparateEntityDraws());
    }

    // ---------------------------------------------------------------------------------------------- frame

    /**
     * Same lazy ID-map setup as Iris's own pipeline. Must run before the first custom-uniform update: uniforms such as
     * {@code currentSelectedBlockId} read the block-state map (null before) as soon as the crosshair is on a block.
     */
    void ensureBlockIds() {
        if (!blockIdsInitialized) {
            IdMap idMap = programSet.getPack().getIdMap();
            WorldRenderingSettings.INSTANCE.setBlockStateIds(BlockMaterialMapping.createBlockStateIdMap(idMap.getBlockProperties(), idMap.getTagEntries()));
            WorldRenderingSettings.INSTANCE.setBlockTypeIds(BlockMaterialMapping.createBlockTypeMap(idMap.getBlockRenderTypeMap()));
            Minecraft.getInstance().levelExtractor.allChanged();
            blockIdsInitialized = true;
        }
    }

    void beginFrame(final RenderTarget main) {
        ensureBlockIds();
        GpuTexture depth = main.getDepthTexture();
        if (depthNoTranslucents == null || depthNoTranslucents.getWidth(0) != main.width || depthNoTranslucents.getHeight(0) != main.height
                || depthNoTranslucents.getFormat() != depth.getFormat()) {
            closeDepthCopies();
            var device = RenderSystem.getDevice();
            int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
            depthNoTranslucents = device.createTexture("iris depthtex1", usage, depth.getFormat(), main.width, main.height, 1, 1);
            depthNoTranslucentsView = device.createTextureView(depthNoTranslucents);
            depthNoHand = device.createTexture("iris depthtex2", usage, depth.getFormat(), main.width, main.height, 1, 1);
            depthNoHandView = device.createTextureView(depthNoHand);
        }
        // Create (and clear) every attachment and sampled buffer before the level's passes open.
        for (int buffer : attachments) {
            owner.targets().get(buffer);
        }
        for (int buffer : neededTargets) {
            owner.targets().get(buffer);
        }
    }

    void beginCapture(final ImmutableSet<Integer> writesAlt) {
        this.writesAlt = writesAlt;
        GbufferPasses.activate(owner);
    }

    void endCapture() {
        GbufferPasses.deactivate(owner);
    }

    void copyDepthNoTranslucents() {
        copyDepth(depthNoTranslucents);
    }

    void copyDepthNoHand() {
        copyDepth(depthNoHand);
    }

    private void copyDepth(@Nullable final GpuTexture destination) {
        if (destination == null) {
            return;
        }
        GpuTexture depth = Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTexture();
        int width = Math.min(depth.getWidth(0), destination.getWidth(0));
        int height = Math.min(depth.getHeight(0), destination.getHeight(0));
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(depth, destination, 0, 0, 0, 0, 0, width, height);
    }

    GpuTextureView depthNoTranslucents() {
        return depthNoTranslucentsView != null ? depthNoTranslucentsView : Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView();
    }

    GpuTextureView depthNoHand() {
        return depthNoHandView != null ? depthNoHandView : Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView();
    }

    // ---------------------------------------------------------------------------------------------- passes

    RenderPassDescriptor descriptor(final RenderPassDescriptor original, final Optional<Vector4fc> clearColor) {
        if (shadowPass()) {
            // Stage 4: the shadow pass draws into shadowcolor (main textures; shadowcomp flips come later) and
            // shadowtex0 (cleared to 1.0 at the start of the shadow pass). The viewport follows the attachment size.
            ShadowTargets shadow = owner.shadowTargets();
            RenderPassDescriptor.Builder builder = RenderPassDescriptor.builder(original.label());
            for (int buffer : shadowAttachments) {
                builder.withColorAttachment(shadow.color(buffer).attachment(false), Optional.empty());
            }
            builder.withDepthAttachment(shadow.depthView, java.util.OptionalDouble.empty());
            return builder.build();
        }
        RenderPassDescriptor.Builder builder = RenderPassDescriptor.builder(original.label());
        for (int buffer : attachments) {
            PostTargets.Target target = owner.targets().get(buffer);
            builder.withColorAttachment(target.attachment(writesAlt.contains(buffer)), buffer == 0 ? clearColor : Optional.empty());
        }
        if (original.depthAttachment() != null) {
            builder.withDepthAttachment(original.depthAttachment().textureView(), original.depthAttachment().clearValue());
        }
        builder.withRenderArea(original.renderArea());
        return builder.build();
    }

    CompiledRenderPipeline substitute(final CompiledRenderPipeline original) {
        Map<Object, CompiledRenderPipeline> byKey = substitutes.computeIfAbsent(original, unused -> new HashMap<>());
        RenderPipeline source = GbufferPasses.sourceOf(original);
        if (source == null) {
            report("unknown", "a pipeline that was not compiled through RenderSystem cannot be redirected into the gbuffers");
            return original;
        }
        boolean dhTerrain = isDhTerrain(source);
        boolean dhGeneric = isDhGeneric(source);
        if (dhTerrain || dhGeneric) {
            // DhIrisBridge only captures DH's passes it redirects: terrain (gbuffers or, with dh_shadow, the shadow
            // map) and the generic objects (gbuffers only).
            boolean shadow = shadowPass();
            Object dhKey = shadow ? DH_SHADOW_KEY : DH_KEY;
            CompiledRenderPipeline cached = byKey.get(dhKey);
            if (cached != null && !cached.isClosed()) {
                return cached;
            }
            boolean water = source.getLocation().getPath().contains("transparent");
            DhKind kind = shadow ? DhKind.SHADOW : dhGeneric ? DhKind.GENERIC : DhKind.TERRAIN;
            ProgramId id = switch (kind) {
                case SHADOW -> ProgramId.DhShadow;
                case GENERIC -> ProgramId.DhGeneric;
                case TERRAIN -> water ? ProgramId.DhWater : ProgramId.DhTerrain;
            };
            ProgramSource dhSource = resolver.resolveNullable(id);
            GbufferProgram program = dhSource == null || shadow && dhGeneric ? null : compileDhProgram(dhSource, source, kind);
            if (program == null && shadow) {
                // DH's own shader would draw the camera view into the shadow map: no LOD shadows from now on.
                owner.dhBridge().disableShadow();
            }
            CompiledRenderPipeline result = program != null ? program.pipeline : shadow ? shadowVanillaVariant(source) : vanillaVariant(source);
            if (program != null) {
                programs.put(program.pipeline, program);
                dhPrograms.put(program, kind);
            }
            byKey.put(dhKey, result);
            return result;
        }
        ShaderKey key = null;
        try {
            key = IrisPipelines.getPipeline(null, source);
        } catch (RuntimeException e) {
            NativiumIris.LOGGER.debug("[nativium-iris] no Iris program key for {}", source.getLocation(), e);
        }
        boolean shadow = shadowPass();
        if (key != null && key.isShadow() != shadow) {
            key = null;
        }
        Object cacheKey = key == null ? (shadow ? NO_KEY_SHADOW : NO_KEY) : key;
        CompiledRenderPipeline cached = byKey.get(cacheKey);
        if (cached != null && !cached.isClosed()) {
            return cached;
        }
        CompiledRenderPipeline result = null;
        if (key != null) {
            ProgramSource programSource = resolver.resolveNullable(key.getProgram());
            if (programSource != null) {
                GbufferProgram program = compileProgram(key, programSource, source, shadow);
                if (program != null) {
                    programs.put(program.pipeline, program);
                    result = program.pipeline;
                }
            }
        }
        if (result == null) {
            result = shadow ? shadowVanillaVariant(source) : vanillaVariant(source);
        }
        byKey.put(cacheKey, result);
        return result;
    }

    @Nullable
    private GbufferProgram compileProgram(final ShaderKey key, final ProgramSource source, final RenderPipeline replaced, final boolean shadow) {
        String name = source.getName() + "/" + key.getName() + "/" + replaced.getLocation().getNamespace() + "/" + replaced.getLocation().getPath();
        if (source.getTessControlSource().isPresent() || source.getTessEvalSource().isPresent()) {
            report(source.getName(), "tessellation shaders are not supported on Metal (vanilla shader used)");
            return null;
        }
        String geometrySource = source.getGeometrySource().orElse(null);
        if (geometrySource != null && !GeometryEmulation.ENABLED) {
            report(source.getName(), "geometry shaders are not supported on Metal (vanilla shader used; emulation off)");
            return null;
        }
        if (geometrySource != null && replaced.getPrimitiveTopology() != com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES
                && replaced.getPrimitiveTopology() != com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS) {
            report(source.getName(), "geometry shader for " + replaced.getPrimitiveTopology() + " draws is not emulated (vanilla shader used)");
            return null;
        }
        Map<PatchShaderType, String> transformed = null;
        try {
            ProgramDirectives directives = source.getDirectives();
            AlphaTest alpha = directives.getAlphaTestOverride().orElse(key.getAlphaTest());
            boolean sodium = key.patch == Patch.SODIUM;
            var textureMap = owner.getTextureMap();
            var overrides = owner.getTextureOverrides(TextureStage.GBUFFERS_AND_SHADOW);
            String vertexSource = source.getVertexSource().orElseThrow();
            String fragmentSource = source.getFragmentSource().orElseThrow();
            if (sodium) {
                transformed = TransformPatcher.patchSodium(key.getName(), vertexSource, geometrySource, null, null, fragmentSource, alpha, textureMap, overrides, shadow);
            } else {
                boolean isLines = key.getProgram() == ProgramId.Line && resolver.has(ProgramId.Line);
                ShaderAttributeInputs inputs = new ShaderAttributeInputs(key.getVertexFormat(), key.shouldIgnoreLightmap(), isLines, key.isGlint(), key.isText(), false);
                transformed = TransformPatcher.patchVanilla(key.getName(), vertexSource, geometrySource, null, null, fragmentSource, alpha, isLines,
                        key == ShaderKey.CLOUDS, true, inputs, textureMap, overrides);
            }
            VertexFormat vertexFormat = sodium ? WorldRenderingSettings.INSTANCE.getVertexFormat().getVertexFormat() : replaced.getVertexFormatBinding(0);

            BlendModeOverride override = directives.getBlendModeOverride().orElse(key.getProgram().getBlendModeOverride());
            Optional<BlendFunction> blend = override != null ? toBlend(override)
                    : replaced.getColorTargetStates().isEmpty() || replaced.getColorTargetStates().getFirst() == null ? Optional.empty()
                    : replaced.getColorTargetStates().getFirst().blendFunction();
            Map<Integer, Optional<BlendFunction>> bufferBlends = new HashMap<>();
            for (BufferBlendInformation information : directives.getBufferBlendOverrides()) {
                bufferBlends.put(information.index(), toBlend(information.blendMode()));
            }

            String vertexStage = transformed.get(PatchShaderType.VERTEX);
            int geometrySlots = 0;
            if (geometrySource != null) {
                GeometryEmulation.Merged merged = GeometryEmulation.merge(vertexStage, transformed.get(PatchShaderType.GEOMETRY), vertexFormat);
                vertexStage = merged.vertex();
                geometrySlots = merged.slots();
                if (GeometryEmulation.DUMP) {
                    dumpSources(name + ".gs-merged", Map.of(PatchShaderType.VERTEX, vertexStage, PatchShaderType.GEOMETRY, transformed.get(PatchShaderType.GEOMETRY)));
                }
            }
            GbufferProgram program = GbufferProgram.compile(new GbufferProgram.Spec(name, vertexStage,
                    transformed.get(PatchShaderType.FRAGMENT), replaced, vertexFormat, directives.getDrawBuffers(),
                    shadow ? shadowAttachments : attachments, shadow ? shadowAttachmentFormats : attachmentFormats, blend, bufferBlends,
                    alpha.reference(), shadow, geometrySlots), owner.storageValidator());
            var customUniforms = owner.customUniforms();
            CommonUniforms.addDynamicUniforms(program.uniforms, FogMode.PER_VERTEX);
            customUniforms.assignTo(program.uniforms);
            BuiltinReplacementUniforms.addBuiltinReplacementUniforms(program.uniforms);
            VanillaUniforms.addVanillaUniforms(program.uniforms);
            customUniforms.mapholderToPass(program.uniforms, program);
            compiledNames.add(source.getName());
            NativiumIris.LOGGER.info("[nativium-iris] gbuffers program {} replaces {} ({}){}", source.getName(), replaced.getLocation(), key,
                    geometrySlots > 0 ? " with its geometry shader emulated (" + geometrySlots + " vertices per triangle)" : "");
            return program;
        } catch (Exception | LinkageError e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage().lines().findFirst().orElse(e.toString());
            report(source.getName(), "failed to translate (" + message + "); vanilla shader used");
            NativiumIris.LOGGER.warn("[nativium-iris] gbuffers program {} for {} could not be translated for Metal", source.getName(), replaced.getLocation(), e);
            dumpSources(name, transformed);
            return null;
        }
    }

    /** DH's Blaze3D terrain pipelines ({@code opaque_terrain}, {@code transparent_terrain}). */
    private static boolean isDhTerrain(final RenderPipeline source) {
        Identifier location = source.getLocation();
        return location.getNamespace().equals("distanthorizons") && location.getPath().contains("terrain");
    }

    /** DH's Blaze3D generic-object pipeline (clouds, beacon beams: {@code generic_objects}). */
    private static boolean isDhGeneric(final RenderPipeline source) {
        Identifier location = source.getLocation();
        return location.getNamespace().equals("distanthorizons") && location.getPath().contains("generic");
    }

    /** Like Iris's IrisLodRenderProgram.createProgram, for DH's Blaze3D terrain pipeline (see {@link DhIrisBridge}). */
    @Nullable
    private GbufferProgram compileDhProgram(final ProgramSource source, final RenderPipeline replaced, final DhKind kind) {
        boolean shadow = kind == DhKind.SHADOW;
        String name = source.getName() + "/dh/" + replaced.getLocation().getPath();
        if (source.getGeometrySource().isPresent() || source.getTessControlSource().isPresent() || source.getTessEvalSource().isPresent()) {
            report(source.getName(), "geometry/tessellation shaders are not supported on Metal (DH's own shader used)");
            return null;
        }
        Map<PatchShaderType, String> transformed = null;
        try {
            ProgramDirectives directives = source.getDirectives();
            // Iris: dh_terrain/dh_water/dh_shadow through patchDHTerrain (IrisLodRenderProgram), dh_generic through
            // patchDHGeneric (IrisGenericRenderProgram).
            transformed = kind == DhKind.GENERIC
                    ? TransformPatcher.patchDHGeneric(source.getName(), source.getVertexSource().orElseThrow(), null, null, null,
                    source.getFragmentSource().orElseThrow(), owner.getTextureMap(), owner.getTextureOverrides(TextureStage.GBUFFERS_AND_SHADOW))
                    : TransformPatcher.patchDHTerrain(source.getName(), source.getVertexSource().orElseThrow(), null, null, null,
                    source.getFragmentSource().orElseThrow(), owner.getTextureMap(), owner.getTextureOverrides(TextureStage.GBUFFERS_AND_SHADOW));
            String vertex = kind == DhKind.GENERIC ? DhIrisBridge.adaptGeneric(transformed.get(PatchShaderType.VERTEX))
                    : DhIrisBridge.adaptVertex(transformed.get(PatchShaderType.VERTEX));
            BlendModeOverride override = directives.getBlendModeOverride().orElse(null);
            Optional<BlendFunction> blend = override != null ? toBlend(override)
                    : replaced.getColorTargetStates().isEmpty() || replaced.getColorTargetStates().getFirst() == null ? Optional.empty()
                    : replaced.getColorTargetStates().getFirst().blendFunction();
            Map<Integer, Optional<BlendFunction>> bufferBlends = new HashMap<>();
            for (BufferBlendInformation information : directives.getBufferBlendOverrides()) {
                bufferBlends.put(information.index(), toBlend(information.blendMode()));
            }
            GbufferProgram program = GbufferProgram.compile(new GbufferProgram.Spec(name, vertex, transformed.get(PatchShaderType.FRAGMENT), replaced,
                    replaced.getVertexFormatBinding(0), directives.getDrawBuffers(), shadow ? shadowAttachments : attachments,
                    shadow ? shadowAttachmentFormats : attachmentFormats, blend, bufferBlends, 0.0f, shadow),
                    owner.storageValidator());
            var customUniforms = owner.customUniforms();
            CommonUniforms.addDynamicUniforms(program.uniforms, FogMode.PER_VERTEX);
            customUniforms.assignTo(program.uniforms);
            BuiltinReplacementUniforms.addBuiltinReplacementUniforms(program.uniforms);
            customUniforms.mapholderToPass(program.uniforms, program);
            compiledNames.add(source.getName());
            NativiumIris.LOGGER.info("[nativium-iris] DH program {} replaces {}", source.getName(), replaced.getLocation());
            return program;
        } catch (Exception | LinkageError e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage().lines().findFirst().orElse(e.toString());
            report(source.getName(), "failed to translate (" + message + "); DH's own shader used");
            NativiumIris.LOGGER.warn("[nativium-iris] DH program {} for {} could not be translated for Metal", source.getName(), replaced.getLocation(), e);
            dumpSources(name, transformed);
            return null;
        }
    }

    /**
     * Iris's per-draw matrices of a DH program (IrisLodRenderProgram/IrisGenericRenderProgram.fillUniformData): camera
     * model-view and DH projection, or in the shadow pass the shadow model-view and projection (LodRendererEvents:
     * on Metal the same {@code undoRevZ(ShadowRenderer.PROJECTION)} the pack's other shadow programs see, so LOD and
     * terrain depths in shadowtex agree; Iris's GL path uses {@code toMinusOneToOne}).
     */
    private void bindDhMatrices(final GbufferProgram program, final DhKind kind) {
        boolean shadow = kind == DhKind.SHADOW;
        Matrix4f modelView = new Matrix4f(shadow ? net.irisshaders.iris.shadows.ShadowRenderer.MODELVIEW : CapturedRenderingState.INSTANCE.getGbufferModelView());
        Matrix4f projection = shadow ? net.irisshaders.iris.helpers.MatrixUtils.undoRevZ(net.irisshaders.iris.shadows.ShadowRenderer.PROJECTION)
                : net.irisshaders.iris.compat.dh.DHCompat.getProjection();
        setMatrix(program, "iris_ModelViewMatrix", modelView);
        setMatrix(program, "iris_ModelViewMatrixInverse", new Matrix4f(modelView).invert());
        setMatrix(program, "iris_ProjectionMatrix", projection);
        setMatrix(program, "iris_ProjectionMatrixInverse", new Matrix4f(projection).invert());
        program.uniforms.location("iris_NormalMatrix", net.irisshaders.iris.gl.uniform.UniformType.MAT3)
                .ifPresent(location -> PostUniforms.setMatrix(location, 3, new Matrix4f(modelView).invert().transpose3x3(tempMatrix3).get(floats9)));
        program.uniforms.location("clipDistance", net.irisshaders.iris.gl.uniform.UniformType.FLOAT)
                .ifPresent(location -> PostUniforms.setFloats(location, DhIrisBridge.nearClipDistance()));
    }

    private void setMatrix(final GbufferProgram program, final String name, final Matrix4f matrix) {
        program.uniforms.location(name, net.irisshaders.iris.gl.uniform.UniformType.MAT4)
                .ifPresent(location -> PostUniforms.setMatrix(location, 4, matrix.get(floats16)));
    }

    /** The replaced pipeline itself, rebuilt with the gbuffer attachment layout (writes colortex0 only). */
    private CompiledRenderPipeline vanillaVariant(final RenderPipeline source) {
        ColorTargetState base = source.getColorTargetStates().isEmpty() || source.getColorTargetStates().getFirst() == null
                ? ColorTargetState.DEFAULT : source.getColorTargetStates().getFirst();
        ColorTargetState[] states = new ColorTargetState[ColorTargetState.MAX_COLOR_TARGETS];
        for (int i = 0; i < attachments.length; i++) {
            GpuFormat format = attachmentFormats.get(i);
            boolean integer = GbufferProgram.isInteger(format);
            // attachments[0] is colortex0 unless the pack's programs need all 8 attachments for other buffers.
            states[i] = i == 0 && attachments[0] == 0
                    ? new ColorTargetState(integer ? Optional.empty() : base.blendFunction(), format, base.writeMask() & ColorTargetState.WRITE_ALL)
                    : new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_NONE);
        }
        NativiumIris.LOGGER.info("[nativium-iris] {} has no gbuffers program; its vanilla shader writes colortex0", source.getLocation());
        return copyPipeline(source, states, attachments.length, "gbuffer/");
    }

    /**
     * Shadow pass without a pack program for this pipeline: the vanilla shader writes depth only (Iris's fallback
     * shadow shader writes no shadowcolor either), cull off, forward-Z depth test.
     */
    private CompiledRenderPipeline shadowVanillaVariant(final RenderPipeline source) {
        ColorTargetState[] states = new ColorTargetState[ColorTargetState.MAX_COLOR_TARGETS];
        for (int i = 0; i < shadowAttachments.length; i++) {
            states[i] = new ColorTargetState(Optional.empty(), shadowAttachmentFormats.get(i), ColorTargetState.WRITE_NONE);
        }
        NativiumIris.LOGGER.info("[nativium-iris] {} has no shadow program; its vanilla shader writes shadow depth only", source.getLocation());
        return copyPipeline(source, states, shadowAttachments.length, "shadow/", true);
    }

    /**
     * A pass that is not captured (e.g. the entity outline target) while Iris's extended vertex formats are active: the
     * vanilla pipeline was compiled for the vanilla layout, but the buffers now carry the extended one, so it is
     * recompiled for the current layout (same shaders and colour targets).
     */
    CompiledRenderPipeline uncaptured(final CompiledRenderPipeline original) {
        if (!net.irisshaders.iris.vertices.ImmediateState.isRenderingLevel || !(original instanceof com.mojang.renderpearl.frontend.FrontendRenderPipeline compiled)) {
            return original;
        }
        CompiledRenderPipeline fixed = formatFixes.get(original);
        if (fixed != null && !fixed.isClosed()) {
            return fixed;
        }
        RenderPipeline source = GbufferPasses.sourceOf(original);
        if (source == null || source.getLocation().getNamespace().equals("nativium")) {
            return original;
        }
        VertexFormat current = source.getVertexFormatBinding(0);
        VertexFormat built = compiled.vertexFormats().isEmpty() ? null : compiled.vertexFormats().getFirst();
        if (current == null || current.equals(built)) {
            return original;
        }
        ColorTargetState[] states = new ColorTargetState[ColorTargetState.MAX_COLOR_TARGETS];
        List<ColorTargetState> targets = source.getColorTargetStates();
        for (int i = 0; i < targets.size(); i++) {
            states[i] = targets.get(i);
        }
        fixed = copyPipeline(source, states, targets.size(), "extended/");
        formatFixes.put(original, fixed);
        return fixed;
    }

    private static CompiledRenderPipeline copyPipeline(final RenderPipeline source, final ColorTargetState[] states, final int stateCount, final String prefix) {
        return copyPipeline(source, states, stateCount, prefix, false);
    }

    private static CompiledRenderPipeline copyPipeline(final RenderPipeline source, final ColorTargetState[] states, final int stateCount, final String prefix,
                                                       final boolean shadow) {
        VertexFormat[] formats = new VertexFormat[16];
        List<VertexFormat> bindings = source.getVertexFormatBindings();
        for (int i = 0; i < Math.min(16, bindings.size()); i++) {
            formats[i] = bindings.get(i);
        }
        boolean sodium = source.getLocation().getNamespace().contains("sodium");
        if (sodium) {
            formats[0] = WorldRenderingSettings.INSTANCE.getVertexFormat().getVertexFormat();
        }
        RenderPipeline.Snippet snippet = new RenderPipeline.Snippet(source.getShaders(), Optional.of(source.getShaderDefines()),
                Optional.of(source.getBindGroupLayouts()), states, stateCount,
                Optional.ofNullable(shadow ? GbufferProgram.shadowDepth(source.getDepthStencilState()) : source.getDepthStencilState()),
                Optional.of(source.getPolygonMode()), Optional.of(!shadow && source.isCull()), formats, Optional.of(source.getPrimitiveTopology()),
                source.pushConstantSize());
        RenderPipeline copy = RenderPipeline.builder(snippet)
                .withLocation(Identifier.fromNamespaceAndPath("nativium", prefix + source.getLocation().getNamespace() + "/" + source.getLocation().getPath()))
                .build();
        return RenderSystem.getCompiledPipeline(copy);
    }

    private void uploadUniforms(final RenderPass pass, final GbufferProgram program, final CommandEncoder encoder, final int alignment) {
        program.uniforms.update();
        owner.customUniforms().push(program);
        program.blocks.forEach((name, block) ->
                pass.setUniform(name, encoder.transientMemory().uploadStaging(block.data.duplicate().clear(), alignment, GpuBuffer.USAGE_UNIFORM)));
    }

    /**
     * {@code Sampler0}/{@code u_BlockTex} changed after {@code setPipeline} (the pipeline's owner already pointed
     * {@link MetalPostPipeline#getAlbedoTex()} at it): programs reading {@code atlasSize}/{@code gtextureSize} get their
     * blocks again, so e.g. block-atlas particles are not taken for particle-atlas ones.
     */
    void albedoChanged(final RenderPass pass, final CompiledRenderPipeline bound) {
        GbufferProgram program = programs.get(bound);
        if (program == null || !program.readsAlbedo) {
            return;
        }
        uploadUniforms(pass, program, RenderSystem.getDevice().createCommandEncoder(),
                RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment());
    }

    /** {@code FrontendRenderPass#setPipeline} TAIL: loose uniforms (per draw like Iris's ExtendedShader) and pack samplers. */
    void bind(final RenderPass pass, final CompiledRenderPipeline bound) {
        GbufferProgram program = programs.get(bound);
        if (program == null) {
            return;
        }
        CapturedRenderingState.INSTANCE.setCurrentAlphaTest(program.alphaReference);
        Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
        if (program.modelViewInverse >= 0) {
            PostUniforms.setMatrix(program.modelViewInverse, 4, modelView.invert(tempMatrix).get(floats16));
        }
        if (program.normalMatrix >= 0) {
            PostUniforms.setMatrix(program.normalMatrix, 3, modelView.invert(tempMatrix).transpose3x3(tempMatrix3).get(floats9));
        }
        if (program.projectionInverse >= 0) {
            Matrix4f projection = shadowPass() ? net.irisshaders.iris.helpers.MatrixUtils.undoRevZ(net.irisshaders.iris.shadows.ShadowRenderer.PROJECTION)
                    : new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferProjection());
            PostUniforms.setMatrix(program.projectionInverse, 4, projection.invert().get(floats16));
        }
        DhKind dhKind = dhPrograms.get(program);
        if (dhKind != null) {
            bindDhMatrices(program, dhKind);
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        uploadUniforms(pass, program, encoder, alignment);
        for (String block : program.extraBlocks) {
            com.mojang.renderpearl.api.buffers.GpuBufferSlice slice = switch (block) {
                case "Projection" -> RenderSystem.getProjectionMatrixBuffer();
                case "Fog" -> RenderSystem.getShaderFog();
                case "Globals" -> RenderSystem.getGlobalSettingsUniform() == null ? null : RenderSystem.getGlobalSettingsUniform().slice();
                default -> null;
            };
            if (slice == null) {
                // Never leave a Metal buffer slot unbound: zeros, like an unwritten GL uniform block.
                slice = encoder.transientMemory().uploadStaging(zeroBlock.duplicate().clear(), alignment, GpuBuffer.USAGE_UNIFORM);
            }
            pass.setUniform(block, slice);
        }
        for (String sampler : program.ownSamplers) {
            bindSampler(pass, sampler, program);
        }
        owner.bindStorage(pass, program.storage, writesAlt);
        if (program.geometry != null) {
            de.nprmedia.nativium.backend.NativiumInterop.setDrawInterceptor(
                    ((de.nprmedia.nativium.iris.mixin.FrontendRenderPassAccessor) pass).nativium$backend(), program.geometry);
        }
    }

    private void bindSampler(final RenderPass pass, final String name, final GbufferProgram program) {
        if (owner.bindCustomTexture(pass, TextureStage.GBUFFERS_AND_SHADOW, name)) {
            return;
        }
        if (owner.bindShadowSampler(pass, name, program.compareSamplers.contains(name), program.ownSamplers.contains("watershadow"), shadowPass())) {
            return;
        }
        if (owner.bindCustomImageSampler(pass, name)) {
            return;
        }
        int colortex = MetalPostPipeline.colortexIndex(name);
        if (colortex >= 0) {
            PostTargets.Target target = owner.targets().getIfPresent(colortex);
            if (target == null) {
                // Created (and cleared) before the next frame's passes; until then it reads as cleared/white.
                neededTargets.add(colortex);
                pass.setUniform(name, owner.whiteView(), owner.nearestSampler());
                return;
            }
            pass.setUniform(name, target.view(writesAlt.contains(colortex)), target.integer ? owner.nearestSampler() : owner.linearSampler());
            return;
        }
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        switch (name) {
            case "depthtex0", "gdepthtex" -> pass.setUniform(name, main.getDepthTextureView(), owner.nearestSampler());
            case "depthtex1" -> pass.setUniform(name, depthNoTranslucents(), owner.nearestSampler());
            case "depthtex2" -> pass.setUniform(name, depthNoHand(), owner.nearestSampler());
            case "noisetex" -> pass.setUniform(name, owner.noiseView(), owner.linearRepeatSampler());
            case "vxDepthTexOpaque", "vxDepthTexTrans", "dhDepthTex", "dhDepthTex0", "dhDepthTex1" -> {
                GpuTextureView lod = owner.farTerrainDepth(name);
                pass.setUniform(name, lod != null ? lod : owner.whiteView(), owner.nearestSampler());
            }
            case "normals" -> pass.setUniform(name, defaultNormalsView, owner.nearestSampler());
            case "specular" -> pass.setUniform(name, defaultSpecularView, owner.nearestSampler());
            // The replaced pipeline has no overlay/lightmap sampler (e.g. Physics Mod debris): bind the real textures
            // like Iris does for every entity program. Iris's entity patch texelFetches the overlay at iris_UV1 (default
            // 0,10); on the 1x1 white placeholder that fetch is out of range, reads 0 and becomes entityColor
            // (0,0,0,1): packs mixing entityColor in (Photon, Rethinking Voxels) drew Physics Mod debris black.
            case "iris_overlay" -> pass.setUniform(name, Minecraft.getInstance().gameRenderer.overlayTexture().getTextureView(), owner.nearestSampler());
            case "lightmap" -> pass.setUniform(name, Minecraft.getInstance().gameRenderer.levelLightmap(), owner.linearSampler());
            default -> {
                if (!name.startsWith("shadow") && !name.equals("watershadow") && !Set.of("gtexture", "tex", "texture", "lightmap", "iris_overlay", "u_MainSampler", "glintTexture").contains(name)) {
                    report("sampler " + name, "no source on Metal yet; a white texture is bound");
                }
                // Shadow maps: no shadow pass yet, white = depth 1 / fully lit. Vanilla textures the replaced pipeline
                // does not have: white, like Iris's whitePixel (also glintTexture on a pipeline without GlintSampler,
                // as Iris's getGlintSamplerFor does for non-glint keys; glint pipelines bind it as GlintSampler).
                pass.setUniform(name, owner.whiteView(), owner.nearestSampler());
            }
        }
    }

    /** Writes the Iris-patched GLSL of a program that failed to translate to {@code <game dir>/nativium-iris-debug/}. */
    static void dumpSources(final String name, @Nullable final Map<PatchShaderType, String> transformed) {
        if (transformed == null) {
            return;
        }
        try {
            java.nio.file.Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("nativium-iris-debug");
            java.nio.file.Files.createDirectories(dir);
            String file = name.replaceAll("[^A-Za-z0-9_.-]", "_");
            for (Map.Entry<PatchShaderType, String> entry : transformed.entrySet()) {
                if (entry.getValue() != null) {
                    java.nio.file.Files.writeString(dir.resolve(file + "." + entry.getKey().name().toLowerCase(Locale.ROOT) + ".glsl"), entry.getValue());
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            NativiumIris.LOGGER.debug("[nativium-iris] could not dump shader sources", e);
        }
    }

    static Optional<BlendFunction> toBlend(final BlendModeOverride override) {
        BlendMode mode = ((BlendModeOverrideAccessor) override).nativium$getBlendMode();
        return toBlend(mode);
    }

    static Optional<BlendFunction> toBlend(@Nullable final BlendMode mode) {
        if (mode == null) {
            return Optional.empty();
        }
        return Optional.of(new BlendFunction(factor(mode.srcRgb()), factor(mode.dstRgb()), factor(mode.srcAlpha()), factor(mode.dstAlpha())));
    }

    private static BlendFactor factor(final int gl) {
        return switch (gl) {
            case 0 -> BlendFactor.ZERO;
            case 1 -> BlendFactor.ONE;
            case 0x300 -> BlendFactor.SRC_COLOR;
            case 0x301 -> BlendFactor.ONE_MINUS_SRC_COLOR;
            case 0x302 -> BlendFactor.SRC_ALPHA;
            case 0x303 -> BlendFactor.ONE_MINUS_SRC_ALPHA;
            case 0x304 -> BlendFactor.DST_ALPHA;
            case 0x305 -> BlendFactor.ONE_MINUS_DST_ALPHA;
            case 0x306 -> BlendFactor.DST_COLOR;
            case 0x307 -> BlendFactor.ONE_MINUS_DST_COLOR;
            case 0x308 -> BlendFactor.SRC_ALPHA_SATURATE;
            default -> BlendFactor.ONE;
        };
    }

    private void report(final String what, final String problem) {
        if (reported.add(what + ": " + problem)) {
            NativiumIris.LOGGER.warn("[nativium-iris] Unsupported shader pack feature: {}: {}", what, problem);
        }
    }

    private void closeDepthCopies() {
        if (depthNoTranslucentsView != null) {
            depthNoTranslucentsView.close();
            depthNoTranslucents.close();
            depthNoHandView.close();
            depthNoHand.close();
            depthNoTranslucentsView = null;
            depthNoTranslucents = null;
            depthNoHandView = null;
            depthNoHand = null;
        }
    }

    @Override
    public void close() {
        endCapture();
        NativiumIris.LOGGER.info("[nativium-iris] gbuffers programs used on Metal: {}", compiledNames);
        programs.values().forEach(GbufferProgram::close);
        programs.clear();
        dhPrograms.clear();
        substitutes.clear();
        formatFixes.clear();
        closeDepthCopies();
        defaultNormalsView.close();
        defaultNormals.close();
        defaultSpecularView.close();
        defaultSpecular.close();
        MemoryUtil.memFree(zeroBlock);
    }
}
