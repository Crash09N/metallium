/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.iris.post;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import de.nprmedia.nativium.iris.NativiumIris;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.shaderpack.properties.CloudSetting;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.joml.Vector3d;
import org.joml.Vector4f;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.shaderpack.properties.ProgramDirectives;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.targets.BufferFlipper;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.minecraft.ChatFormatting;
import net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.Set;

/**
 * Iris adapter stage 2: runs a shader pack's fullscreen passes (begin, prepare, deferred, composite, final) on Metal
 * over the scene that vanilla/Sodium rendered. gbuffers, shadow, compute and geometry programs are not executed yet;
 * they are reported once in the log and in chat. Used by {@code Iris.createPipeline} on Metal in place of
 * {@code IrisRenderingPipeline}, which is OpenGL-bound; see docs/design/iris-stage2-post-pipeline.md.
 *
 * <p>Pass order, buffer flipping and draw-buffer semantics follow Iris's {@code CompositeRenderer} /
 * {@code FinalPassRenderer} (LGPL-3.0); this module is LGPL-3.0 as well.
 */
public final class MetalPostPipeline extends VanillaRenderingPipeline {
    private static final String COPY_VERTEX = """
            #version 330 core
            in vec3 Position;
            in vec2 UV0;
            out vec2 nativium_uv;
            void main() {
                gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
                nativium_uv = UV0;
            }
            """;
    private static final String COPY_FRAGMENT = """
            #version 330 core
            uniform sampler2D nativium_source;
            in vec2 nativium_uv;
            layout(location = 0) out vec4 nativium_color;
            void main() {
                nativium_color = texture(nativium_source, nativium_uv);
            }
            """;

    private final ProgramSet programSet;
    private final PackDirectives directives;
    private final FrameUpdateNotifier updateNotifier = new FrameUpdateNotifier();
    private final CustomUniforms customUniforms;
    private final PostTargets targets;
    private final List<Pass> begin = new ArrayList<>();
    /** Iris runs these right after the shadow pass ({@code renderShadows}); without gbuffers, before the deferred passes. */
    private final List<Pass> prepare = new ArrayList<>();
    private final List<Pass> deferred = new ArrayList<>();
    private final List<Pass> composite = new ArrayList<>();
    private final Pass finalPass;
    private final ImmutableSet<Integer> sceneWritesAlt;
    /** Flip state after the deferred passes: what translucent gbuffers write to (Iris's flippedAfterTranslucent). */
    private final ImmutableSet<Integer> translucentWritesAlt;
    private final ImmutableSet<Integer> finalReadsAlt;
    /** Stage 3: gbuffers programs render the level into the pack's colortex buffers (null = stage-2 mode). */
    @org.jspecify.annotations.Nullable
    private final GbufferState gbuffers;
    /** Stage 4: shadowtex0/1 and shadowcolor (always allocated; cleared = "fully lit" when no shadow pass runs). */
    private final ShadowTargets shadowTargets;
    @org.jspecify.annotations.Nullable
    private final MetalShadowRenderer shadowRenderer;
    private boolean prepareDone;
    private final IntList buffersToBeCleared;
    private final Map<GpuFormat, PostProgram> copyPrograms = new HashMap<>();
    private final List<String> problems = new ArrayList<>();
    /**
     * The subset of {@link #problems} worth a chat message: programs that are skipped (the pack visibly renders
     * wrong) and DH programs while Distant Horizons is installed. Everything else goes to the log and the status
     * screen only (owner decision 2026-10-19: chat only for problems the player can act on).
     */
    private final List<String> chatProblems = new ArrayList<>();
    /** Short pipeline description for the F3 line / status screen; the full summary is logged. */
    private String statusSummary = "";
    /** The pipeline that rendered the last level frame (independent of Iris's PipelineManager, for the F3 line). */
    private static volatile @org.jspecify.annotations.Nullable MetalPostPipeline lastRendering;
    private static volatile long lastRenderingNanos;
    private final GpuBuffer quad;
    private final GpuSampler linear;
    private final GpuSampler nearest;
    private final GpuSampler linearRepeat;
    private final GpuTexture noise;
    private final GpuTextureView noiseView;
    private final GpuTexture white;
    private final GpuTextureView whiteView;
    private final Set<String> warnedSamplers = new LinkedHashSet<>();
    private boolean failed;
    /** Set when gbuffers fail: chunk meshes are still in Iris's extended vertex format and must be rebuilt. */
    private boolean revertWorldSettings;
    private WorldRenderingPhase phase = WorldRenderingPhase.NONE;
    /** Voxy's far terrain under this pack (docs/design/iris-voxy-dh.md); null until Voxy asks for its pipeline. */
    private @org.jspecify.annotations.Nullable VoxyIrisBridge voxyBridge;

    /**
     * One entry of a pass list. {@code program} is null for a compute-only entry ({@code compositeN.csh} without
     * {@code compositeN.fsh}); {@code computes} run before the fragment pass with the same flip state (Iris's
     * {@code CompositeRenderer}); {@code mipmapped}: buffers whose mip chain is generated right before the pass;
     * {@code shadow}: a shadowcomp pass (draw buffers are shadowcolor buffers).
     */
    private record Pass(String name, @org.jspecify.annotations.Nullable PostProgram program, int[] drawBuffers, ImmutableSet<Integer> readsAlt,
                        List<ComputePass> computes, ImmutableSet<Integer> mipmapped, boolean shadow) {
        Pass(final String name, final PostProgram program, final int[] drawBuffers, final ImmutableSet<Integer> readsAlt) {
            this(name, program, drawBuffers, readsAlt, List.of(), ImmutableSet.of(), false);
        }

        Pass with(final List<ComputePass> computes, final ImmutableSet<Integer> mipmapped, final boolean shadow) {
            return new Pass(name, program, drawBuffers, readsAlt, computes, mipmapped, shadow);
        }

        void close() {
            if (program != null) {
                program.close();
            }
            computes.forEach(ComputePass::close);
        }
    }

    // ---- stage 5: compute passes, images, SSBOs
    /** Iris's {@code setup} programs: once after pipeline creation and after every resize. */
    private final List<ComputePass> setupComputes = new ArrayList<>();
    /** {@code shadow.csh} etc. ({@code ProgramSet.getShadowCompute}): every frame before the shadow pass. */
    private final List<ComputePass> shadowComputes = new ArrayList<>();
    /** {@code final.csh}: before the final pass. */
    private final List<ComputePass> finalComputes = new ArrayList<>();
    /** shadowcomp passes (fragment and compute), after the shadow pass. */
    private final List<Pass> shadowcomp = new ArrayList<>();
    private ImmutableSet<Integer> shadowcompFlipped = ImmutableSet.of();
    @org.jspecify.annotations.Nullable
    private MetalCompute compute;
    @org.jspecify.annotations.Nullable
    private final StorageBuffers storageBuffers;
    @org.jspecify.annotations.Nullable
    private final CustomImages customImages;
    /** {@code texture.<stage>.<sampler>}, raw {@code customtexN}, {@code customTexture.<name>} and {@code texture.noise}. */
    private final CustomTextures customTextures;
    private boolean setupPending = true;
    private boolean imagesFresh = true;
    private int computeCount;
    private final GpuSampler linearNoMip;
    private final GpuSampler nearestNoMip;

    public MetalPostPipeline(final ProgramSet programSet) {
        this.programSet = programSet;
        this.directives = programSet.getPackDirectives();
        this.targets = new PostTargets(directives.getRenderTargetDirectives(), directives);
        this.buffersToBeCleared = directives.getRenderTargetDirectives().getBuffersToBeCleared();
        this.customUniforms = programSet.getPack().customUniforms.build(
                holder -> CommonUniforms.addNonDynamicUniforms(holder, programSet.getPack().getIdMap(), directives, updateNotifier));

        var device = RenderSystem.getDevice();
        this.linear = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
        this.nearest = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
        this.linearRepeat = device.createSampler(AddressMode.REPEAT, AddressMode.REPEAT, FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
        this.linearNoMip = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.of(0.0));
        this.nearestNoMip = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.of(0.0));
        ByteBuffer vertices = quadVertices();
        try {
            this.quad = device.createBuffer(() -> "iris fullscreen quad", GpuBuffer.USAGE_VERTEX, vertices);
        } finally {
            MemoryUtil.memFree(vertices);
        }
        int noiseSize = Math.max(1, directives.getNoiseTextureResolution());
        this.noise = device.createTexture("iris noisetex", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, noiseSize, noiseSize, 1, 1);
        this.noiseView = device.createTextureView(noise);
        this.white = device.createTexture("iris placeholder", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
        this.whiteView = device.createTextureView(white);
        uploadPlaceholders(noiseSize);
        this.shadowTargets = new ShadowTargets(directives.getShadowDirectives());

        boolean useGbuffers = !Boolean.getBoolean("nativium.iris.noGbuffers") && hasGbufferPrograms(programSet);
        var shadowEnabled = directives.getShadowDirectives().isShadowEnabled();
        // Like IrisRenderingPipeline: a shadow pass when the pack has shadow programs (or forces shadow.enabled), unless
        // shadow.enabled=false. It needs the gbuffers capture (Iris calls renderShadows only while a pack is in use).
        boolean useShadows = useGbuffers && !Boolean.getBoolean("nativium.iris.noShadows")
                && shadowEnabled != net.irisshaders.iris.helpers.OptionalBoolean.FALSE
                && (hasShadowPrograms(programSet) || shadowEnabled == net.irisshaders.iris.helpers.OptionalBoolean.TRUE);
        reportUnsupportedPrograms(useGbuffers, useShadows);

        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        targets.resize(main.width, main.height);

        var bufferObjects = programSet.getPack().getBufferObjects();
        this.storageBuffers = bufferObjects.isEmpty() ? null : new StorageBuffers(bufferObjects, main.width, main.height);
        var imageInfos = programSet.getPack().getIrisCustomImages();
        this.customImages = imageInfos.isEmpty() ? null : new CustomImages(imageInfos, main.width, main.height);
        this.customTextures = new CustomTextures(programSet.getPack());

        markImagesUsedByRenderPrograms();

        BufferFlipper flipper = new BufferFlipper();
        build(flipper, ProgramArrayId.Begin, TextureStage.BEGIN, "begin_pre", begin, false);
        build(flipper, ProgramArrayId.Prepare, TextureStage.PREPARE, "prepare_pre", prepare, false);
        this.sceneWritesAlt = flipper.snapshot();
        build(flipper, ProgramArrayId.Deferred, TextureStage.DEFERRED, "deferred_pre", deferred, false);
        this.translucentWritesAlt = flipper.snapshot();
        build(flipper, ProgramArrayId.Composite, TextureStage.COMPOSITE_AND_FINAL, "composite_pre", composite, false);
        this.finalReadsAlt = flipper.snapshot();
        this.finalPass = programSet.get(ProgramId.Final)
                .map(source -> compilePass(source, TextureStage.COMPOSITE_AND_FINAL, finalReadsAlt, new int[0], List.of(main.getColorTexture().getFormat())))
                .orElse(null);
        programSet.get(ProgramId.Final).ifPresent(source -> source.getDirectives().getMipmappedBuffers().forEach(targets::requireMipmaps));
        finalComputes.addAll(compileComputes(programSet.getFinalCompute(), TextureStage.COMPOSITE_AND_FINAL));
        writtenBefore = Set.of();
        setupComputes.addAll(compileComputes(programSet.getSetup(), TextureStage.SETUP));
        if (useShadows) {
            shadowComputes.addAll(compileComputes(programSet.getShadowCompute(), TextureStage.GBUFFERS_AND_SHADOW));
            BufferFlipper shadowFlipper = new BufferFlipper();
            build(shadowFlipper, ProgramArrayId.ShadowComposite, TextureStage.SHADOWCOMP, "shadowcomp_pre", shadowcomp, true);
            this.shadowcompFlipped = shadowFlipper.snapshot();
        }

        this.gbuffers = useGbuffers ? new GbufferState(this, programSet) : null;
        if (gbuffers != null) {
            gbuffers.applyWorldRenderingSettings();
        }
        this.shadowRenderer = useShadows
                ? new MetalShadowRenderer(this, new ProgramFallbackResolver(programSet).resolveNullable(ProgramId.ShadowSolid), directives, shadowTargets)
                : null;

        String summary = "Metal shader pipeline: "
                + (shadowRenderer != null ? "shadow " + shadowTargets.resolution + "px (" + gbuffers.shadowAttachmentSummary() + ") + " : "")
                + (gbuffers != null ? "gbuffers (" + gbuffers.attachmentSummary() + ") + " : "")
                + (shadowcomp.size() + begin.size() + prepare.size() + deferred.size() + composite.size()) + " fullscreen passes" + (finalPass != null ? " + final" : "")
                + (computeCount > 0 ? " + " + computeCount + " compute" : "")
                + (storageBuffers != null ? " + SSBOs" : "") + (customImages != null ? " + custom images" : "");
        NativiumIris.LOGGER.info("[nativium-iris] {}", summary);
        this.statusSummary = (shadowRenderer != null ? "shadow " + shadowTargets.resolution + "px, " : "")
                + (gbuffers != null ? "gbuffers, " : "")
                + (shadowcomp.size() + begin.size() + prepare.size() + deferred.size() + composite.size()) + " fullscreen passes"
                + (finalPass != null ? " + final" : "") + (computeCount > 0 ? ", " + computeCount + " compute" : "");
        reportToChat();
    }

    // ---------------------------------------------------------------------------------------------- construction

    /**
     * While passes are compiled: colortex buffers an earlier pass of the stage being built wrote (Iris's
     * {@code flippedAtLeastOnce}, per CompositeRenderer; the final pass gets the composite stage's set). Iris's
     * {@code CustomTextureSamplerInterceptor} ignores a {@code texture.<stage>.colortexN} override for those buffers:
     * superDuperVanilla overrides colortex0 with its cloud map in the composite stage but its bloom passes read the
     * colortex0 that composite4 wrote; Bliss does the same with colortex6 (blue noise, then its bloom chain).
     */
    private Set<Integer> writtenBefore = Set.of();

    /** The custom texture for sampler {@code name}, unless it overrides a colortex buffer in {@code written}. */
    CustomTextures.@org.jspecify.annotations.Nullable Texture customTexture(final TextureStage stage, final String name, final Set<Integer> written) {
        if (!written.isEmpty()) {
            int colortex = colortexIndex(name);
            if (colortex >= 0 && written.contains(colortex)) {
                return null;
            }
        }
        return customTextures.lookup(stage, name);
    }

    private void build(final BufferFlipper flipper, final ProgramArrayId id, final TextureStage stage, final String preFlipsName, final List<Pass> out,
                       final boolean shadow) {
        directives.getExplicitFlips(preFlipsName).forEach((buffer, flip) -> {
            if (flip) {
                flipper.flip(buffer);
            }
        });
        java.util.Set<Integer> written = new java.util.HashSet<>();
        writtenBefore = Set.of();
        ProgramSource[] sources = programSet.getComposite(id);
        ComputeSource[][] computes = programSet.getCompute(id);
        int count = Math.max(sources.length, computes == null ? 0 : computes.length);
        for (int i = 0; i < count; i++) {
            ProgramSource source = i < sources.length ? sources[i] : null;
            ImmutableSet<Integer> flipped = flipper.snapshot();
            writtenBefore = Set.copyOf(written);
            List<ComputePass> passComputes = compileComputes(computes != null && i < computes.length ? computes[i] : null, stage);
            if (source == null || !source.isValid()) {
                // Iris's ComputeOnlyPass: the computes run, nothing is drawn or flipped.
                if (!passComputes.isEmpty()) {
                    out.add(new Pass(passComputes.getFirst().name, null, new int[0], flipped, passComputes, ImmutableSet.of(), shadow));
                }
                continue;
            }
            ProgramDirectives programDirectives = source.getDirectives();
            int[] drawBuffers = programDirectives.getDrawBuffers();
            List<GpuFormat> formats = new ArrayList<>();
            for (int buffer : drawBuffers) {
                formats.add(shadow ? shadowTargets.colorFormat(buffer) : targets.format(buffer));
            }
            ImmutableSet<Integer> mipmapped = shadow ? ImmutableSet.of() : programDirectives.getMipmappedBuffers();
            mipmapped.forEach(targets::requireMipmaps);
            if (shadow && !programDirectives.getMipmappedBuffers().isEmpty()) {
                problems.add(source.getName() + ": mipmapped shadowcolor buffers not supported yet (sampled without mipmaps)");
            }
            Pass pass = compilePass(source, stage, flipped, drawBuffers, formats);
            if (pass == null) {
                if (!passComputes.isEmpty()) {
                    out.add(new Pass(source.getName(), null, new int[0], flipped, passComputes, ImmutableSet.of(), shadow));
                }
                continue;
            }
            out.add(pass.with(passComputes, mipmapped, shadow));
            ImmutableMap<Integer, Boolean> explicitFlips = programDirectives.getExplicitFlips();
            for (int buffer : drawBuffers) {
                if (explicitFlips.get(buffer) != Boolean.FALSE) {
                    flipper.flip(buffer);
                    written.add(buffer);
                }
            }
            explicitFlips.forEach((buffer, flip) -> {
                if (flip) {
                    flipper.flip(buffer);
                    written.add(buffer);
                }
            });
        }
        writtenBefore = Set.copyOf(written);
    }

    private Pass compilePass(final ProgramSource source, final TextureStage stage, final ImmutableSet<Integer> readsAlt, final int[] drawBuffers, final List<GpuFormat> formats) {
        String name = source.getName();
        if (source.getGeometrySource().isPresent() || source.getTessControlSource().isPresent() || source.getTessEvalSource().isPresent()) {
            problems.add(name + ": geometry/tessellation shaders are not supported on Metal (pass skipped)");
            chatProblems.add(problems.getLast());
            return null;
        }
        ProgramDirectives programDirectives = source.getDirectives();
        if (programDirectives.getViewportScale().scale() != 1.0f) {
            problems.add(name + ": viewport scale not supported yet (rendered at full size)");
        }
        // The final pass (no draw buffers, writes the main target) never blends, like Iris's FinalPassRenderer.
        List<Optional<BlendFunction>> blends = drawBuffers.length == formats.size() && drawBuffers.length > 0
                ? PostBlend.forDrawBuffers(programDirectives, drawBuffers) : null;
        Map<PatchShaderType, String> transformed = null;
        try {
            transformed = TransformPatcher.patchComposite(name,
                    source.getVertexSource().orElseThrow(), null, source.getFragmentSource().orElseThrow(), stage, getTextureMap(), getTextureOverrides(stage));
            Set<Integer> written = writtenBefore;
            PostProgram program = PostProgram.compile(name, transformed.get(PatchShaderType.VERTEX), transformed.get(PatchShaderType.FRAGMENT), formats,
                    storageValidator(), sampler -> fixedSampler(stage, sampler, written), blends);
            program.stage = stage;
            program.writtenBefore = written;
            CommonUniforms.addDynamicUniforms(program.uniforms, FogMode.OFF);
            customUniforms.assignTo(program.uniforms);
            customUniforms.mapholderToPass(program.uniforms, program);
            return new Pass(name, program, drawBuffers, readsAlt);
        } catch (Exception | LinkageError e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage().lines().findFirst().orElse(e.toString());
            problems.add(name + ": failed to translate (" + message + ")");
            chatProblems.add(problems.getLast());
            NativiumIris.LOGGER.warn("[nativium-iris] Program {} could not be translated for Metal", name, e);
            GbufferState.dumpSources(name, transformed);
            return null;
        }
    }

    private static final java.util.regex.Pattern IMAGE_NAME = java.util.regex.Pattern.compile("\\b(shadowcolorimg|colorimg)(\\d+)\\b");

    /**
     * Render programs (gbuffers/shadow are compiled lazily, mid-frame) may bind colortex/shadowcolor buffers as images;
     * those buffers need shader-write usage from creation on, so the pack's sources are scanned up front.
     */
    private void markImagesUsedByRenderPrograms() {
        List<ProgramSource> sources = new ArrayList<>();
        for (ProgramId id : ProgramId.values()) {
            programSet.get(id).ifPresent(sources::add);
        }
        for (ProgramArrayId id : ProgramArrayId.values()) {
            for (ProgramSource source : programSet.getComposite(id)) {
                if (source != null) {
                    sources.add(source);
                }
            }
        }
        for (ProgramSource source : sources) {
            for (Optional<String> text : List.of(source.getVertexSource(), source.getGeometrySource(), source.getFragmentSource())) {
                if (text.isEmpty()) {
                    continue;
                }
                java.util.regex.Matcher m = IMAGE_NAME.matcher(text.get());
                while (m.find()) {
                    int index = parseIndex(m.group(2));
                    if (m.group(1).equals("colorimg")) {
                        targets.requireStorage(index);
                    } else {
                        shadowTargets.requireStorage(index);
                    }
                }
            }
        }
    }

    /** Stage 5: which images/samplers render programs may use outside renderpearl's binding table. */
    StorageBindings.Validator storageValidator() {
        return new StorageBindings.Validator() {
            @Override
            public boolean knowsImage(final String name) {
                int color = suffixIndex(name, "colorimg");
                if (color >= 0 && color < PostTargets.COUNT && name.startsWith("colorimg")) {
                    targets.requireStorage(color);
                    return true;
                }
                int shadowColor = suffixIndex(name, "shadowcolorimg");
                if (shadowColor >= 0 && shadowColor < ShadowTargets.COLOR_COUNT) {
                    shadowTargets.requireStorage(shadowColor);
                    return true;
                }
                return customImages != null && customImages.image(name) != null;
            }

            @Override
            public boolean isCustomSampler(final String name) {
                return (customImages != null && customImages.sampler(name) != null) || customTextures.isCustom(name);
            }
        };
    }

    /**
     * A custom texture ({@code texture.<stage>.<name>}, {@code customtexN}, custom {@code noisetex}) in a render pass of
     * {@code stage}; false if {@code name} is none. Checked before the built-in samplers, as Iris's sampler interceptor does.
     */
    boolean bindCustomTexture(final RenderPass pass, final TextureStage stage, final String name) {
        return bindCustomTexture(pass, stage, name, name, Set.of());
    }

    private boolean bindCustomTexture(final RenderPass pass, final TextureStage stage, final String uniform, final String name, final Set<Integer> written) {
        CustomTextures.Texture texture = customTexture(stage, name, written);
        if (texture == null) {
            return false;
        }
        pass.setUniform(uniform, texture.view(), customTextures.sampler(texture));
        return true;
    }

    /**
     * The sampler {@link #bindSampler} always uses for {@code name} in {@code stage}, or null if it can vary (mipmapped
     * or integer buffers, shadow maps): such textures can share a Metal sampler state when a pass has more than 16.
     */
    private @org.jspecify.annotations.Nullable GpuSampler fixedSampler(final TextureStage stage, final String name, final Set<Integer> written) {
        CustomTextures.Texture custom = customTexture(stage, name, written);
        if (custom != null) {
            return customTextures.sampler(custom);
        }
        CustomImages.Image image = customImages != null ? customImages.sampler(name) : null;
        if (image != null) {
            return image.integer() ? nearest : linear;
        }
        int colortex = colortexIndex(name);
        if (colortex >= 0) {
            return targets.integer(colortex) || targets.mipmapsRequested(colortex) ? null : linear;
        }
        return switch (name) {
            case "depthtex0", "gdepthtex", "depthtex1", "depthtex2" -> nearest;
            case "vxDepthTexOpaque", "vxDepthTexTrans", "dhDepthTex", "dhDepthTex0", "dhDepthTex1" -> nearest;
            case "noisetex" -> linearRepeat;
            default -> name.startsWith("shadow") || name.equals("watershadow") || name.equals("nativium_source") ? null : linear;
        };
    }

    /** A custom image's sampler ({@code image.<name> = <sampler> ...}) in a render pass; false if {@code name} is none. */
    boolean bindCustomImageSampler(final RenderPass pass, final String name) {
        return bindCustomImageSampler(pass, name, name);
    }

    private boolean bindCustomImageSampler(final RenderPass pass, final String uniform, final String name) {
        CustomImages.Image image = customImages != null ? customImages.sampler(name) : null;
        if (image == null) {
            return false;
        }
        pass.setUniform(uniform, image.view, image.integer() ? nearest : linear);
        return true;
    }

    /**
     * Stage 5: binds a render program's storage images/SSBOs for the draws after {@code setPipeline} (Metal indices
     * chosen by {@link StorageBindings}); {@code readsAlt}: flip state of colortex images (shadowcolor images follow
     * the shadowcomp flips).
     */
    void bindStorage(final RenderPass pass, final StorageBindings storage, final ImmutableSet<Integer> readsAlt) {
        com.mojang.renderpearl.backend.api.RenderPassBackend backend = ((de.nprmedia.nativium.iris.mixin.FrontendRenderPassAccessor) pass).nativium$backend();
        if (storage.isEmpty()) {
            de.nprmedia.nativium.backend.NativiumInterop.setDrawBindings(backend, null);
            return;
        }
        List<StorageBindings.Entry> entries = storage.entries();
        Object[] resources = new Object[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            StorageBindings.Entry entry = entries.get(i);
            if (entry.kind() == StorageBindings.Kind.BUFFER) {
                io.github.kokodio.metaljvm.metal.MTLBuffer buffer = storageBuffers != null ? storageBuffers.get(entry.glBinding()) : null;
                resources[i] = buffer != null ? buffer : compute().zeroBuffer();
                continue;
            }
            String name = entry.name();
            int color = suffixIndex(name, "colorimg");
            int shadowColor = suffixIndex(name, "shadowcolorimg");
            if (color >= 0 && name.startsWith("colorimg")) {
                resources[i] = de.nprmedia.nativium.backend.NativiumInterop.metalTexture(targets.get(color).texture(readsAlt.contains(color)), true);
            } else if (shadowColor >= 0) {
                resources[i] = de.nprmedia.nativium.backend.NativiumInterop.metalTexture(shadowTargets.color(shadowColor).texture(shadowTargets.isFlipped(shadowColor)), true);
            } else {
                resources[i] = java.util.Objects.requireNonNull(customImages).image(name).texture;
            }
        }
        de.nprmedia.nativium.backend.NativiumInterop.setDrawBindings(backend, encoder -> {
            for (int i = 0; i < resources.length; i++) {
                int index = entries.get(i).metalIndex();
                if (resources[i] instanceof io.github.kokodio.metaljvm.metal.MTLBuffer buffer) {
                    encoder.setVertexBuffer(buffer, 0, index);
                    encoder.setFragmentBuffer(buffer, 0, index);
                } else {
                    io.github.kokodio.metaljvm.metal.MTLTexture texture = (io.github.kokodio.metaljvm.metal.MTLTexture) resources[i];
                    encoder.setVertexTexture(texture, index);
                    encoder.setFragmentTexture(texture, index);
                }
            }
        });
    }

    /** Stage 5: the lazily created Metal compute host (also used for mipmap generation). */
    private MetalCompute compute() {
        if (compute == null) {
            compute = new MetalCompute();
        }
        return compute;
    }

    private List<ComputePass> compileComputes(final ComputeSource @org.jspecify.annotations.Nullable [] sources, final TextureStage stage) {
        List<ComputePass> out = new ArrayList<>();
        if (sources == null) {
            return out;
        }
        for (ComputeSource source : sources) {
            if (source == null || source.getSource().isEmpty()) {
                continue;
            }
            String name = source.getName();
            try {
                String glsl = TransformPatcher.patchCompute(name, source.getSource().get(), stage, getTextureMap(), getTextureOverrides(stage));
                ComputePass pass = ComputePass.compile(source, glsl, compute(), this::validateComputeResource);
                pass.stage = stage;
                pass.writtenBefore = writtenBefore;
                CommonUniforms.addDynamicUniforms(pass.uniforms, FogMode.OFF);
                customUniforms.assignTo(pass.uniforms);
                customUniforms.mapholderToPass(pass.uniforms, pass);
                out.add(pass);
                computeCount++;
            } catch (Exception | LinkageError e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage().lines().findFirst().orElse(e.toString());
                problems.add(name + ": compute shader failed to translate (" + message + ")");
                chatProblems.add(problems.getLast());
                NativiumIris.LOGGER.warn("[nativium-iris] Compute program {} could not be translated for Metal", name, e);
            }
        }
        return out;
    }

    /** Compile-time check that a kernel resource has an Iris binding; marks colortex/shadowcolor buffers as images. */
    private void validateComputeResource(final de.nprmedia.nativium.shaders.ComputeTranslator.Resource resource) {
        String name = resource.name();
        switch (resource.kind()) {
            case STORAGE_IMAGE -> {
                int color = suffixIndex(name, "colorimg");
                if (color >= 0 && color < PostTargets.COUNT && name.startsWith("colorimg")) {
                    targets.requireStorage(color);
                    return;
                }
                int shadowColor = suffixIndex(name, "shadowcolorimg");
                if (shadowColor >= 0 && shadowColor < ShadowTargets.COLOR_COUNT) {
                    shadowTargets.requireStorage(shadowColor);
                    return;
                }
                if (customImages != null && customImages.image(name) != null) {
                    return;
                }
                throw new IllegalArgumentException("image '" + name + "' has no Iris binding (colorimgN, shadowcolorimgN or a custom image)");
            }
            case SAMPLED_TEXTURE -> {
                boolean custom = (customImages != null && customImages.sampler(name) != null) || customTextures.isCustom(name);
                if (!custom && resource.dimension() != 0 && resource.dimension() != 1 && resource.dimension() != 4) {
                    throw new IllegalArgumentException("sampler '" + name + "' has dimension " + resource.dimension() + " (custom textures are not supported yet)");
                }
            }
            default -> {
            }
        }
    }

    private static int suffixIndex(final String name, final String prefix) {
        return name.startsWith(prefix) ? parseIndex(name.substring(prefix.length())) : -1;
    }

    /** Resolves kernel resources for one dispatch: colortex/colorimg by the pass's flip state, shadowcolor by {@code shadowFlipped}. */
    private final class ComputeBindings implements ComputePass.Resolver {
        private final ImmutableSet<Integer> readsAlt;
        private final ImmutableSet<Integer> shadowFlipped;
        /** Texture stage of the pass being dispatched (custom textures). */
        TextureStage stage = TextureStage.COMPOSITE_AND_FINAL;
        /** colortex buffers whose custom-texture override is off for the pass being dispatched. */
        Set<Integer> written = Set.of();

        ComputeBindings(final ImmutableSet<Integer> readsAlt, final ImmutableSet<Integer> shadowFlipped) {
            this.readsAlt = readsAlt;
            this.shadowFlipped = shadowFlipped;
        }

        @Override
        public io.github.kokodio.metaljvm.metal.MTLTexture image(final de.nprmedia.nativium.shaders.ComputeTranslator.Resource resource) {
            String name = resource.name();
            int color = suffixIndex(name, "colorimg");
            if (color >= 0 && name.startsWith("colorimg")) {
                return de.nprmedia.nativium.backend.NativiumInterop.metalTexture(targets.get(color).texture(readsAlt.contains(color)), true);
            }
            int shadowColor = suffixIndex(name, "shadowcolorimg");
            if (shadowColor >= 0) {
                return de.nprmedia.nativium.backend.NativiumInterop.metalTexture(shadowTargets.color(shadowColor).texture(shadowFlipped.contains(shadowColor)), true);
            }
            return java.util.Objects.requireNonNull(customImages).image(name).texture;
        }

        @Override
        public io.github.kokodio.metaljvm.metal.MTLTexture texture(final de.nprmedia.nativium.shaders.ComputeTranslator.Resource resource) {
            String name = resource.name();
            CustomTextures.Texture custom = customTexture(stage, name, written);
            if (custom != null) {
                return de.nprmedia.nativium.backend.NativiumInterop.metalTexture(custom.gpuTexture(), false);
            }
            if (customImages != null && customImages.sampler(name) != null) {
                return customImages.sampler(name).texture;
            }
            return de.nprmedia.nativium.backend.NativiumInterop.metalTexture(sampledTexture(name), false);
        }

        private GpuTexture sampledTexture(final String name) {
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            int colortex = colortexIndex(name);
            if (colortex >= 0) {
                return targets.get(colortex).texture(readsAlt.contains(colortex));
            }
            switch (name) {
                case "depthtex0", "gdepthtex" -> {
                    return main.getDepthTexture();
                }
                case "depthtex1" -> {
                    return gbuffers != null ? gbuffers.depthNoTranslucents().texture() : main.getDepthTexture();
                }
                case "depthtex2" -> {
                    return gbuffers != null ? gbuffers.depthNoHand().texture() : main.getDepthTexture();
                }
                case "noisetex" -> {
                    return noise;
                }
                case "shadowtex0", "shadowtex0HW", "shadow", "watershadow" -> {
                    return shadowTargets.depth;
                }
                case "shadowtex1", "shadowtex1HW" -> {
                    return shadowTargets.depthNoTranslucents;
                }
                case "vxDepthTexOpaque", "vxDepthTexTrans", "dhDepthTex", "dhDepthTex0", "dhDepthTex1" -> {
                    GpuTextureView lod = farTerrainDepth(name);
                    return lod != null ? lod.texture() : white;
                }
                default -> {
                }
            }
            int shadowColor = name.equals("shadowcolor") ? 0 : suffixIndex(name, "shadowcolor");
            if (shadowColor >= 0 && shadowColor < ShadowTargets.COLOR_COUNT) {
                PostTargets.Target target = shadowTargets.colorIfPresent(shadowColor);
                return target != null ? target.texture(shadowFlipped.contains(shadowColor)) : white;
            }
            if (warnedSamplers.add("compute " + name)) {
                NativiumIris.LOGGER.info("[nativium-iris] Compute sampler '{}' has no Iris binding; colortex0 is bound like in GL", name);
            }
            return targets.get(0).texture(readsAlt.contains(0));
        }

        @Override
        public io.github.kokodio.metaljvm.metal.MTLSamplerState sampler(final de.nprmedia.nativium.shaders.ComputeTranslator.Resource resource) {
            MetalCompute host = compute();
            String name = resource.name();
            if (resource.depth()) {
                return host.compareLinear;
            }
            CustomTextures.Texture custom = customTexture(stage, name, written);
            if (custom != null) {
                return custom.blur ? (custom.clamp ? host.linearClamp : host.linearRepeat) : (custom.clamp ? host.nearestClamp : host.nearestRepeat);
            }
            if (customImages != null && customImages.sampler(name) != null) {
                return customImages.sampler(name).integer() ? host.nearestClamp : host.linearClamp;
            }
            if (name.equals("noisetex")) {
                return host.linearRepeat;
            }
            int colortex = colortexIndex(name);
            if (colortex >= 0) {
                return targets.get(colortex).integer ? host.nearestClamp : host.linearClamp;
            }
            if (name.startsWith("depthtex") || name.equals("gdepthtex") || name.startsWith("shadowtex")) {
                return host.nearestClamp;
            }
            return host.linearClamp;
        }

        @Override
        public io.github.kokodio.metaljvm.metal.@org.jspecify.annotations.Nullable MTLBuffer storageBuffer(final int index) {
            return storageBuffers != null ? storageBuffers.get(index) : null;
        }
    }

    private void runComputes(final List<ComputePass> passes, final ComputeBindings bindings, final float width, final float height) {
        for (ComputePass pass : passes) {
            pass.uniforms.update();
            customUniforms.push(pass);
            bindings.stage = pass.stage;
            bindings.written = pass.writtenBefore;
            pass.dispatch(compute(), bindings, width, height);
        }
    }

    /**
     * Stage 5, start of a frame (Iris's beginLevelRendering): custom image clears, shadow computes, and the setup
     * programs once after creation or a resize.
     */
    private void beginComputeFrame(final RenderTarget main) {
        targets.resetMips();
        // Image targets of render programs are bound inside render passes, where they cannot be created any more.
        targets.createStorageTargets();
        shadowTargets.createStorageTargets();
        if (customImages != null) {
            if (imagesFresh) {
                customImages.clearAll(compute());
                imagesFresh = false;
            } else {
                customImages.clearPerFrame(compute());
            }
        }
        if (shadowRenderer != null && !shadowComputes.isEmpty()) {
            runComputes(shadowComputes, new ComputeBindings(ImmutableSet.of(), ImmutableSet.of()), shadowTargets.resolution, shadowTargets.resolution);
        }
        if (setupPending) {
            setupPending = false;
            runComputes(setupComputes, new ComputeBindings(ImmutableSet.of(), ImmutableSet.of()), 1, 1);
        }
    }

    /** The main target changed size: relative SSBOs/images follow, setup programs run again (as in Iris). */
    private void onResize(final RenderTarget main) {
        if (storageBuffers != null) {
            storageBuffers.resize(main.width, main.height);
        }
        if (customImages != null) {
            customImages.resize(main.width, main.height, compute());
        }
        setupPending = true;
    }

    static boolean hasShadowPrograms(final ProgramSet programSet) {
        for (ProgramId id : ProgramId.values()) {
            if (id.getGroup() == ProgramGroup.Shadow && programSet.get(id).isPresent()) {
                return true;
            }
        }
        return false;
    }

    static boolean hasGbufferPrograms(final ProgramSet programSet) {
        for (ProgramId id : ProgramId.values()) {
            if (id.getGroup() == ProgramGroup.Gbuffers && programSet.get(id).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private void reportUnsupportedPrograms(final boolean gbuffersRun, final boolean shadowsRun) {
        // Iris itself only uses a pack's dh_* programs when Distant Horizons is installed: without DH they are not a gap.
        boolean dhInstalled = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("distanthorizons");
        List<String> skipped = new ArrayList<>();
        List<String> dhSkipped = new ArrayList<>();
        for (ProgramId id : ProgramId.values()) {
            ProgramGroup group = id.getGroup();
            if (!programSet.get(id).isPresent()) {
                continue;
            }
            if (group == ProgramGroup.Dh) {
                dhSkipped.add(id.getSourceName());
            } else if ((group == ProgramGroup.Shadow && !shadowsRun) || (group == ProgramGroup.Gbuffers && !gbuffersRun)) {
                skipped.add(id.getSourceName());
            }
        }
        if (!dhSkipped.isEmpty()) {
            if (dhInstalled) {
                problems.add(dhSkipped.size() + " DH programs are not executed yet on Metal (Distant Horizons LODs are drawn without the pack)");
                chatProblems.add(problems.getLast());
                NativiumIris.LOGGER.warn("[nativium-iris] DH programs not executed: {}", String.join(", ", dhSkipped));
            } else {
                NativiumIris.LOGGER.debug("[nativium-iris] DH programs unused (Distant Horizons not installed): {}", String.join(", ", dhSkipped));
            }
        }
        if (!skipped.isEmpty()) {
            problems.add(skipped.size() + (gbuffersRun ? " shadow programs are not executed on Metal (no shadow pass)"
                    : " gbuffers/shadow programs are not executed on Metal (the vanilla scene is used)"));
            NativiumIris.LOGGER.warn("[nativium-iris] programs not executed: {}", String.join(", ", skipped));
        }
        if (!shadowsRun && (java.util.Arrays.stream(programSet.getComposite(ProgramArrayId.ShadowComposite)).anyMatch(s -> s != null && s.isValid())
                || countComputes(programSet.getShadowCompute()) > 0
                || java.util.Arrays.stream(programSet.getCompute(ProgramArrayId.ShadowComposite)).anyMatch(a -> countComputes(a) > 0))) {
            problems.add("shadowcomp/shadow compute passes need the shadow pass (not executed)");
        }
    }

    private static int countComputes(final ComputeSource[] sources) {
        int count = 0;
        if (sources != null) {
            for (ComputeSource source : sources) {
                if (source != null && source.getSource().isPresent()) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Problems always go to the log (and the status screen through {@link #statusProblems()}); the chat only gets the
     * ones in {@link #chatProblems}. The pipeline summary is never sent to the chat.
     */
    private void reportToChat() {
        for (String problem : problems) {
            NativiumIris.LOGGER.warn("[nativium-iris] Unsupported shader pack feature: {}", problem);
        }
        if (chatProblems.isEmpty()) {
            return;
        }
        try {
            String first = chatProblems.getFirst();
            Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("[Nativium] Shader pack: " + first
                    + (chatProblems.size() > 1 ? " (+" + (chatProblems.size() - 1) + " more, see the Nativium status screen)" : ""))
                    .withStyle(ChatFormatting.YELLOW));
        } catch (RuntimeException e) {
            NativiumIris.LOGGER.debug("[nativium-iris] chat not available", e);
        }
    }

    /** The Metal pipeline that rendered a level frame within the last second, or null (for the F3/status line). */
    public static @org.jspecify.annotations.Nullable MetalPostPipeline rendering() {
        MetalPostPipeline pipeline = lastRendering;
        return pipeline != null && System.nanoTime() - lastRenderingNanos < 1_000_000_000L ? pipeline : null;
    }

    /** Short description of the passes this pipeline runs (status line). */
    public String statusSummary() {
        return statusSummary;
    }

    /** Unsupported-feature reports of this pipeline (status screen; also logged). */
    public List<String> statusProblems() {
        return List.copyOf(problems);
    }

    private static ByteBuffer quadVertices() {
        // Two triangles over (0,0)-(1,1), like Iris's FullScreenQuadRenderer; composite.vsh maps them to clip space.
        float[] v = {
                0, 0, 0, 0, 0,
                1, 0, 0, 1, 0,
                1, 1, 0, 1, 1,
                0, 0, 0, 0, 0,
                1, 1, 0, 1, 1,
                0, 1, 0, 0, 1};
        ByteBuffer buffer = MemoryUtil.memAlloc(v.length * 4).order(ByteOrder.nativeOrder());
        for (float f : v) {
            buffer.putFloat(f);
        }
        buffer.flip();
        return buffer;
    }

    private void uploadPlaceholders(final int noiseSize) {
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        ByteBuffer pixels = MemoryUtil.memAlloc(noiseSize * noiseSize * 4);
        try {
            Random random = new Random(0);
            for (int i = 0; i < noiseSize * noiseSize; i++) {
                pixels.putInt(random.nextInt() | 0xFF000000);
            }
            pixels.flip();
            encoder.writeToTexture(noise, pixels, 0, 0, 0, 0, noiseSize, noiseSize);
            pixels.clear().limit(4);
            pixels.putInt(0, 0xFFFFFFFF);
            encoder.writeToTexture(white, pixels, 0, 0, 0, 0, 1, 1);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    // ---------------------------------------------------------------------------------------------- per frame

    @Override
    public void beginLevelRendering() {
        lastRendering = this;
        lastRenderingNanos = System.nanoTime();
        if (revertWorldSettings) {
            revertWorldSettings = false;
            revertToVanillaWorldSettings();
        }
        // Same lazy id-map setup as Iris's own pipeline: uniforms such as currentSelectedBlockId read these maps.
        // (With gbuffers, GbufferState.beginFrame does this and also rebuilds chunks for the block IDs.)
        if (!initializedBlockIds && gbuffers == null) {
            WorldRenderingSettings.INSTANCE.setBlockStateIds(BlockMaterialMapping.createBlockStateIdMap(
                    programSet.getPack().getIdMap().getBlockProperties(), programSet.getPack().getIdMap().getTagEntries()));
            WorldRenderingSettings.INSTANCE.setBlockTypeIds(
                    BlockMaterialMapping.createBlockTypeMap(programSet.getPack().getIdMap().getBlockRenderTypeMap()));
            WorldRenderingSettings.INSTANCE.setEntityIds(programSet.getPack().getIdMap().getEntityIdMap());
            WorldRenderingSettings.INSTANCE.setItemIds(programSet.getPack().getIdMap().getItemIdMap());
            initializedBlockIds = true;
        }
        GbufferState state = gbuffers;
        if (state == null || failed) {
            return; // stage-2 mode: all work happens in finalizeLevelRendering
        }
        try {
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            if (targets.resize(main.width, main.height)) {
                onResize(main);
            }
            if (voxyBridge != null) {
                voxyBridge.beginFrame();
            }
            if (Boolean.getBoolean("nativium.iris.test.failGbuffers")) {
                throw new IllegalStateException("simulated gbuffers failure (game test)");
            }
            updateNotifier.onNewFrame();
            state.ensureBlockIds(); // before customUniforms.update(): currentSelectedBlockId reads the block ID map
            customUniforms.update();
            state.beginFrame(main);
            clearBuffers();
            beginComputeFrame(main);
            for (Pass pass : begin) {
                draw(pass, main);
            }
            prepareDone = false;
            if (shadowRenderer == null) {
                runPrepare(main);
            }
            state.beginCapture(sceneWritesAlt);
        } catch (RuntimeException e) {
            fail("gbuffers setup failed", e);
        }
    }

    /**
     * Stage 4. Iris's MixinLevelRenderer calls this before the main pass is added to the frame graph, so the shadow
     * maps are rendered (recorded) before the level's passes; then the prepare passes run, as in Iris.
     */
    @Override
    public void renderShadows(final net.irisshaders.iris.mixin.LevelRendererAccessor levelRenderer, final net.minecraft.client.Camera camera,
                              final net.minecraft.client.renderer.state.level.CameraRenderState renderState) {
        GbufferState state = gbuffers;
        if (state == null || failed) {
            return;
        }
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (shadowRenderer != null) {
            try {
                state.prepareShadowAttachments();
                shadowRenderer.renderShadows(levelRenderer, camera);
                runShadowcomp(main);
            } catch (RuntimeException e) {
                net.irisshaders.iris.shadows.ShadowRenderer.ACTIVE = false;
                fail("shadow pass failed", e);
                return;
            }
        }
        try {
            runPrepare(main);
        } catch (RuntimeException e) {
            fail("prepare passes failed", e);
        }
    }

    /** Iris's ShadowCompositeRenderer: fullscreen/compute passes over the shadow targets after the shadow pass. */
    private void runShadowcomp(final RenderTarget main) {
        if (shadowcomp.isEmpty()) {
            return;
        }
        GbufferPasses.suspend();
        try {
            for (Pass pass : shadowcomp) {
                draw(pass, main);
            }
        } finally {
            GbufferPasses.resume();
            shadowTargets.setFlipped(shadowcompFlipped);
        }
    }

    private void runPrepare(final RenderTarget main) {
        if (prepareDone) {
            return;
        }
        prepareDone = true;
        GbufferPasses.suspend();
        try {
            for (Pass pass : prepare) {
                draw(pass, main);
            }
        } finally {
            GbufferPasses.resume();
        }
    }

    @Override
    public void beginHand() {
        if (gbuffers != null && !failed) {
            gbuffers.copyDepthNoHand();
        }
    }

    @Override
    public void beginTranslucents() {
        GbufferState state = gbuffers;
        if (state == null || failed) {
            return;
        }
        try {
            state.copyDepthNoTranslucents();
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            runPrepare(main); // only if renderShadows did not run this frame
            GbufferPasses.suspend();
            try {
                for (Pass pass : deferred) {
                    draw(pass, main);
                }
            } finally {
                GbufferPasses.resume();
            }
            state.beginCapture(translucentWritesAlt);
        } catch (RuntimeException e) {
            fail("deferred passes failed", e);
        }
    }

    private boolean initializedBlockIds;
    private final DebugDump debugDump = new DebugDump();

    @Override
    public void finalizeLevelRendering() {
        if (gbuffers != null) {
            gbuffers.endCapture();
        }
        if (failed) {
            return;
        }
        try {
            render();
        } catch (RuntimeException e) {
            fail("post-processing failed", e);
        }
    }

    void fail(final String what, final RuntimeException e) {
        failed = true;
        if (gbuffers != null) {
            gbuffers.endCapture();
            // The vanilla fallback draws chunks with vanilla shaders: without this, meshes built in Iris's extended
            // vertex format are read with the compact layout (exploded, garbled terrain).
            revertWorldSettings = true;
        }
        NativiumIris.LOGGER.error("[nativium-iris] Metal shader pack: {}; showing the vanilla scene from now on", what, e);
        reportProblem(what + ": " + e);
    }

    /** What {@link VanillaRenderingPipeline}'s constructor sets, then a chunk rebuild (start of a level frame). */
    private void revertToVanillaWorldSettings() {
        WorldRenderingSettings settings = WorldRenderingSettings.INSTANCE;
        settings.setDisableDirectionalShading(shouldDisableDirectionalShading());
        settings.setUseSeparateAo(false);
        settings.setSeparateEntityDraws(false);
        settings.setAmbientOcclusionLevel(1.0f);
        settings.setVertexFormat(net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats.COMPACT);
        settings.setVoxelizeLightBlocks(false);
        settings.setBreaksAnisotropy(false);
        settings.setBlockTypeIds(it.unimi.dsi.fastutil.objects.Object2ObjectMaps.emptyMap());
        Minecraft.getInstance().levelExtractor.allChanged();
        NativiumIris.LOGGER.info("[nativium-iris] vanilla fallback: chunk vertex format reset, chunks rebuilt");
    }

    @Override
    public WorldRenderingPhase getPhase() {
        return phase;
    }

    @Override
    public void setPhase(final WorldRenderingPhase phase) {
        this.phase = phase;
    }

    private void reportProblem(final String message) {
        try {
            Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("[Nativium] " + message).withStyle(ChatFormatting.RED));
        } catch (RuntimeException ignored) {
            // chat not available
        }
    }

    /** Iris's clear passes: buffers with clearing enabled; colortex0 defaults to the fog colour. */
    private void clearBuffers() {
        Vector3d fog = CapturedRenderingState.INSTANCE.getFogColor();
        Vector4f fogColor = new Vector4f((float) fog.x, (float) fog.y, (float) fog.z, 1.0f);
        for (int buffer : buffersToBeCleared) {
            if (buffer != 0 || gbuffers != null) {
                targets.clearIfPresent(buffer, fogColor);
            }
        }
    }

    private void render() {
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GbufferPasses.suspend();
        try {
            if (gbuffers == null) {
                if (targets.resize(main.width, main.height)) {
                    NativiumIris.LOGGER.debug("[nativium-iris] post targets resized to {}x{}", main.width, main.height);
                    onResize(main);
                }
                updateNotifier.onNewFrame();
                customUniforms.update();
                clearBuffers();
                beginComputeFrame(main);
                for (Pass pass : begin) {
                    draw(pass, main);
                }
                for (Pass pass : prepare) {
                    draw(pass, main);
                }
                // The vanilla/Sodium scene takes the place of the gbuffers output in colortex0.
                copy(main.getColorTexture(), main.getColorTextureView(), targets.get(0).texture(sceneWritesAlt.contains(0)), targets.get(0).attachment(sceneWritesAlt.contains(0)));
                for (Pass pass : deferred) {
                    draw(pass, main);
                }
            }
            for (Pass pass : composite) {
                draw(pass, main);
            }
            PostTargets.Target colortex0 = targets.get(0);
            if (!finalComputes.isEmpty()) {
                runComputes(finalComputes, new ComputeBindings(finalReadsAlt, shadowTargets.flipped()), main.width, main.height);
            }
            if (finalPass != null) {
                draw(finalPass, main);
            } else {
                copy(colortex0.texture(finalReadsAlt.contains(0)), colortex0.view(finalReadsAlt.contains(0)), main.getColorTexture(), main.getColorTextureView());
            }
            // Like Iris's swap passes: buffers that ended on their alt texture are copied back so the next frame starts
            // from the main texture again (history buffers keep their content).
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            for (int buffer : finalReadsAlt) {
                if (!buffersToBeCleared.contains(buffer)) {
                    PostTargets.Target target = targets.get(buffer);
                    encoder.copyTextureToTexture(target.alt, target.main, 0, 0, 0, 0, 0, target.main.getWidth(0), target.main.getHeight(0));
                }
            }
            if (debugDump.due()) {
                debugDump.dump(targets);
            }
        } finally {
            GbufferPasses.resume();
        }
    }

    private void draw(final Pass pass, final RenderTarget main) {
        if (!pass.computes.isEmpty()) {
            ComputeBindings bindings = pass.shadow
                    ? new ComputeBindings(ImmutableSet.of(), pass.readsAlt)
                    : new ComputeBindings(pass.readsAlt, shadowTargets.flipped());
            float size = shadowTargets.resolution;
            runComputes(pass.computes, bindings, pass.shadow ? size : main.width, pass.shadow ? size : main.height);
        }
        PostProgram program = pass.program;
        if (program == null) {
            return;
        }
        for (int buffer : pass.mipmapped) {
            generateMipmaps(buffer, pass.readsAlt.contains(buffer));
        }
        if (pass.shadow) {
            // shadowcolor samplers of this pass read by its own flip state (bindShadowSampler asks ShadowTargets).
            shadowTargets.setFlipped(pass.readsAlt);
        }
        program.uniforms.update();
        customUniforms.push(program);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        Map<String, GpuBufferSlice> blocks = uploadBlocks(encoder, program);
        // Create (and clear) buffers used for the first time before the render pass opens.
        for (int buffer : pass.drawBuffers) {
            if (pass.shadow) {
                shadowTargets.color(buffer);
            } else {
                targets.get(buffer);
            }
        }
        for (String sampler : program.samplers) {
            int colortex = colortexIndex(sampler);
            if (colortex >= 0) {
                targets.get(colortex);
            }
        }

        RenderPassDescriptor.Builder descriptor = RenderPassDescriptor.builder(() -> "iris " + pass.name);
        if (pass.drawBuffers.length == 0) {
            descriptor.withColorAttachment(main.getColorTextureView());
        } else {
            for (int buffer : pass.drawBuffers) {
                // Writes go to the texture the pass does not read from (Iris's flip model).
                PostTargets.Target target = pass.shadow ? shadowTargets.color(buffer) : targets.get(buffer);
                descriptor.withColorAttachment(target.attachment(!pass.readsAlt.contains(buffer)));
            }
        }
        try (RenderPass renderPass = encoder.createRenderPass(descriptor.build())) {
            renderPass.setPipeline(program.pipeline);
            if (!program.storage.isEmpty()) {
                bindStorage(renderPass, program.storage, pass.shadow ? ImmutableSet.of() : pass.readsAlt);
            }
            renderPass.setVertexBuffer(0, quad.slice());
            blocks.forEach(renderPass::setUniform);
            for (String sampler : program.samplers) {
                if (bindCustomTexture(renderPass, program.stage, sampler, sampler, program.writtenBefore)) {
                    continue;
                }
                if (!bindShadowSampler(renderPass, sampler, program.compareSamplers.contains(sampler), program.samplers.contains("watershadow"), false)) {
                    bindSampler(renderPass, program.stage, sampler, sampler, pass.shadow ? ImmutableSet.of() : pass.readsAlt, main, program.writtenBefore);
                }
            }
            renderPass.draw(6, 1, 0, 0);
        }
    }

    /** Iris's {@code setupMipmapping}: mip chain of the texture the pass reads, then mipmapped sampling for it. */
    private void generateMipmaps(final int buffer, final boolean alt) {
        PostTargets.Target target = targets.get(buffer);
        if (!target.mipmapped()) {
            return;
        }
        compute().generateMipmaps(de.nprmedia.nativium.backend.NativiumInterop.metalTexture(target.texture(alt), true));
        if (alt) {
            target.altMips = true;
        } else {
            target.mainMips = true;
        }
    }

    private static Map<String, GpuBufferSlice> uploadBlocks(final CommandEncoder encoder, final PostProgram program) {
        Map<String, GpuBufferSlice> slices = new HashMap<>();
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        program.blocks.forEach((name, block) ->
                slices.put(name, encoder.transientMemory().uploadStaging(block.data.duplicate().clear(), alignment, GpuBuffer.USAGE_UNIFORM)));
        return slices;
    }

    private void bindSampler(final RenderPass renderPass, final TextureStage stage, final String uniform, final String name, final ImmutableSet<Integer> readsAlt, final RenderTarget main, final Set<Integer> written) {
        if (bindCustomTexture(renderPass, stage, uniform, name, written) || bindCustomImageSampler(renderPass, uniform, name)) {
            return;
        }
        int colortex = colortexIndex(name);
        if (colortex >= 0) {
            PostTargets.Target target = targets.get(colortex);
            boolean alt = readsAlt.contains(colortex);
            GpuSampler sampler = target.integer ? nearest : linear;
            if (target.mipmapped() && (target.integer || !target.mipsOn(alt))) {
                // GL: no mipmap filtering until the buffer's mips were generated this frame (Iris's turnOnMips).
                sampler = target.integer ? nearestNoMip : linearNoMip;
            }
            renderPass.setUniform(uniform, target.view(alt), sampler);
            return;
        }
        switch (name) {
            case "depthtex0", "gdepthtex" -> renderPass.setUniform(uniform, main.getDepthTextureView(), nearest);
            case "depthtex1" -> renderPass.setUniform(uniform, gbuffers != null ? gbuffers.depthNoTranslucents() : main.getDepthTextureView(), nearest);
            case "depthtex2" -> renderPass.setUniform(uniform, gbuffers != null ? gbuffers.depthNoHand() : main.getDepthTextureView(), nearest);
            case "noisetex" -> renderPass.setUniform(uniform, noiseView, linearRepeat);
            case "vxDepthTexOpaque", "vxDepthTexTrans", "dhDepthTex", "dhDepthTex0", "dhDepthTex1" -> {
                GpuTextureView lod = farTerrainDepth(name);
                renderPass.setUniform(uniform, lod != null ? lod : whiteView, nearest);
            }
            case "nativium_source" -> throw new IllegalStateException("copy sampler outside the copy pass");
            default -> {
                if (name.startsWith("shadow") || name.equals("watershadow")) {
                    // No shadow pass yet: a white (depth 1 / fully lit) placeholder means "no shadow".
                    if (warnedSamplers.add(name)) {
                        NativiumIris.LOGGER.warn("[nativium-iris] Sampler '{}' needs the shadow pass (not on Metal yet); a white placeholder is bound", name);
                    }
                    renderPass.setUniform(uniform, whiteView, nearest);
                } else {
                    // In GL an unassigned sampler reads texture unit 0, which Iris fills with colortex0 in fullscreen
                    // passes (IrisSamplers.addRenderTargetSamplers "default sampler").
                    if (warnedSamplers.add(name)) {
                        NativiumIris.LOGGER.info("[nativium-iris] Sampler '{}' has no Iris binding in fullscreen passes; colortex0 is bound like in GL", name);
                    }
                    PostTargets.Target target = targets.get(0);
                    renderPass.setUniform(uniform, target.view(readsAlt.contains(0)), linear);
                }
            }
        }
    }

    /**
     * Iris's {@code IrisSamplers.addShadowSamplers}: {@code shadowtex0}/{@code shadow} = all casters,
     * {@code shadowtex1} = opaque casters (with {@code watershadow} declared: {@code watershadow}/{@code shadowtex0} and
     * {@code shadow}/{@code shadowtex1}), {@code shadowtexNHW} = the same with hardware compare, {@code shadowcolorN} =
     * shadow colour buffers. {@code sampler2DShadow}s get a depth-compare sampler (Metal {@code sample_compare}).
     * During the shadow pass itself shadowtex0 is the attachment, so reads get shadowtex1 / white instead.
     *
     * @return false if {@code name} is not a shadow sampler
     */
    boolean bindShadowSampler(final RenderPass pass, final String name, final boolean compare, final boolean waterShadow, final boolean inShadowPass) {
        int depth = switch (name) {
            case "shadowtex0", "shadowtex0HW" -> 0;
            case "watershadow" -> 0;
            case "shadowtex1", "shadowtex1HW" -> 1;
            case "shadow" -> waterShadow ? 1 : 0;
            default -> -1;
        };
        if (depth >= 0) {
            boolean hardware = compare || name.endsWith("HW");
            GpuTextureView view = depth == 0 && !inShadowPass ? shadowTargets.depthView : shadowTargets.depthNoTranslucentsView;
            pass.setUniform(name, view, shadowTargets.depthSampler(depth, hardware));
            return true;
        }
        int color = name.equals("shadowcolor") ? 0 : name.startsWith("shadowcolor") ? parseIndex(name.substring("shadowcolor".length())) : -1;
        if (color >= 0 && color < ShadowTargets.COLOR_COUNT) {
            PostTargets.Target target = shadowTargets.colorIfPresent(color);
            if (target == null || inShadowPass) {
                // Not written by any shadow program: Iris creates it cleared (default clear colour white).
                pass.setUniform(name, whiteView, nearest);
            } else {
                pass.setUniform(name, target.view(shadowTargets.isFlipped(color)), shadowTargets.colorSampler(color));
            }
            return true;
        }
        return false;
    }

    private static int parseIndex(final String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static int colortexIndex(final String name) {
        if (name.startsWith("colortex")) {
            try {
                int index = Integer.parseInt(name.substring("colortex".length()));
                return index >= 0 && index < PostTargets.COUNT ? index : -1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return PackRenderTargetDirectives.LEGACY_RENDER_TARGETS.indexOf(name);
    }

    /** Copies a whole colour texture; a blit when formats match, otherwise a fullscreen draw. */
    private void copy(final GpuTexture source, final GpuTextureView sourceView, final GpuTexture destination, final GpuTextureView destinationView) {
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        int width = Math.min(source.getWidth(0), destination.getWidth(0));
        int height = Math.min(source.getHeight(0), destination.getHeight(0));
        if (source.getFormat() == destination.getFormat() && source.getMipLevels() == destination.getMipLevels()) {
            encoder.copyTextureToTexture(source, destination, 0, 0, 0, 0, 0, width, height);
            return;
        }
        PostProgram program = copyPrograms.computeIfAbsent(destination.getFormat(), format -> {
            try {
                return PostProgram.compile("nativium_copy_" + format.name().toLowerCase(), COPY_VERTEX, COPY_FRAGMENT, List.of(format));
            } catch (Exception e) {
                throw new IllegalStateException("could not build the copy program for " + format, e);
            }
        });
        try (RenderPass renderPass = encoder.createRenderPass(() -> "iris copy", destinationView, Optional.empty())) {
            renderPass.setPipeline(program.pipeline);
            renderPass.setVertexBuffer(0, quad.slice());
            renderPass.setUniform("nativium_source", sourceView, linear);
            renderPass.draw(6, 1, 0, 0);
        }
    }

    // ---------------------------------------------------------------------------------------------- pipeline queries

    @Override
    public Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> getTextureMap() {
        return directives.getTextureMap();
    }

    @Override
    public Set<String> getTextureOverrides(final TextureStage stage) {
        return customTextures.overrides(stage);
    }

    @Override
    public FrameUpdateNotifier getFrameUpdateNotifier() {
        return updateNotifier;
    }

    @Override
    public float getSunPathRotation() {
        return directives.getSunPathRotation();
    }

    // Pack directives that Iris's mixins query (sun/moon/stars/weather...); only once the pack's gbuffers draw the world.

    @Override
    public boolean shouldRenderSun() {
        return gbuffers != null ? directives.shouldRenderSun() : super.shouldRenderSun();
    }

    @Override
    public boolean shouldRenderMoon() {
        return gbuffers != null ? directives.shouldRenderMoon() : super.shouldRenderMoon();
    }

    @Override
    public boolean shouldRenderStars() {
        return gbuffers != null ? directives.shouldRenderStars() : super.shouldRenderStars();
    }

    @Override
    public boolean shouldRenderSkyDisc() {
        return gbuffers != null ? directives.shouldRenderSkyDisc() : super.shouldRenderSkyDisc();
    }

    /** Iris's horizon cone (its own class, Blaze3D-only), drawn like {@code IrisRenderingPipeline.onBeginClear}. */
    private net.irisshaders.iris.pathways.@org.jspecify.annotations.Nullable HorizonRenderer horizon;

    /**
     * Like Iris's pipeline: before the sky, an inverted cone around the camera between the sky planes is drawn with
     * the sky pipeline, so the pack's {@code gbuffers_skybasic} computes the sky colour below the vanilla sky disc too.
     * Without it the band between the horizon and the end of the render distance kept the clear (fog) colour: a
     * visible "wall" at the render distance with Complementary (owner report; OpenGL shows a smooth horizon).
     * The pass targets the main target, so the gbuffers capture redirects it into the pack's buffers.
     */
    @Override
    public void onBeginClear() {
        if (gbuffers == null || failed) {
            return;
        }
        setPhase(WorldRenderingPhase.SKY);
        var level = Minecraft.getInstance().level;
        if (level == null || !directives.shouldRenderSkyDisc()
                || level.dimensionType().skybox() != net.minecraft.world.level.dimension.DimensionType.Skybox.OVERWORLD
                && !level.dimensionType().hasSkyLight()) {
            return;
        }
        try {
            if (horizon == null) {
                horizon = new net.irisshaders.iris.pathways.HorizonRenderer();
            }
            Vector3d fog = CapturedRenderingState.INSTANCE.getFogColor();
            // Alpha 1 like Iris (Sildur's gives pink reflections with 0).
            horizon.renderHorizon(CapturedRenderingState.INSTANCE.getGbufferModelView(), CapturedRenderingState.INSTANCE.getGbufferProjection(),
                    new Vector4f((float) fog.x, (float) fog.y, (float) fog.z, 1.0f));
        } catch (RuntimeException e) {
            if (warnedSamplers.add("horizon " + e)) {
                NativiumIris.LOGGER.error("[nativium-iris] horizon rendering failed", e);
            }
        }
    }

    @Override
    public boolean shouldRenderWeather() {
        return gbuffers != null ? directives.shouldRenderWeather() : super.shouldRenderWeather();
    }

    @Override
    public boolean shouldRenderWeatherParticles() {
        return gbuffers != null ? directives.shouldRenderWeatherParticles() : super.shouldRenderWeatherParticles();
    }

    @Override
    public boolean shouldRenderUnderwaterOverlay() {
        return gbuffers != null ? directives.underwaterOverlay() : super.shouldRenderUnderwaterOverlay();
    }

    @Override
    public boolean shouldRenderVignette() {
        return gbuffers != null ? directives.vignette() : super.shouldRenderVignette();
    }

    @Override
    public CloudSetting getCloudSetting() {
        return gbuffers != null ? directives.getCloudSetting() : super.getCloudSetting();
    }

    // ---------------------------------------------------------------------------------------------- gbuffers (stage 3)

    /** {@link GbufferPasses#retarget}: the pack's gbuffer attachments in place of the main colour attachment. */
    RenderPassDescriptor gbufferDescriptor(final RenderPassDescriptor original, final Optional<org.joml.Vector4fc> clearColor) {
        return gbuffers.descriptor(original, clearColor);
    }

    com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline gbufferPipeline(final com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline original) {
        try {
            return gbuffers.substitute(original);
        } catch (RuntimeException e) {
            NativiumIris.LOGGER.error("[nativium-iris] gbuffers pipeline substitution failed", e);
            return original;
        }
    }

    com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline uncapturedPipeline(final com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline original) {
        try {
            return gbuffers.uncaptured(original);
        } catch (RuntimeException e) {
            NativiumIris.LOGGER.error("[nativium-iris] recompiling a pipeline for Iris's extended vertex format failed", e);
            return original;
        }
    }

    /** True while the pack's gbuffers programs render the world (Iris's {@code isPackInUseQuick} on Metal). */
    /** GL-style name of the texture last bound as {@code Sampler0}/{@code u_BlockTex} (Iris's {@code albedoTex}). */
    private int albedoTex;

    /**
     * Iris's {@code atlasSize}/{@code gtextureSize}/{@code gtextureId} uniforms read this: which atlas the current draw
     * samples. Packs tell block-atlas particles (sprint/break particles) from particle-atlas ones by it; with 0 every
     * particle was treated as a particle-atlas one (Complementary: wrong colours/emission on block particles).
     */
    @Override
    public int getAlbedoTex() {
        return albedoTex;
    }

    @Override
    public void onSetAlbedoTex(final com.mojang.renderpearl.api.textures.GpuTextureView view) {
        if (view != null) {
            albedoTex = ((net.irisshaders.iris.mixinterface.GpuTextureInterface) view.texture()).iris$getGlId();
        }
    }

    /** {@code FrontendRenderPass#setUniform(Sampler0|u_BlockTex)} on a captured pass: refresh albedo-dependent uniforms. */
    void albedoBound(final RenderPass pass, final com.mojang.renderpearl.api.textures.GpuTextureView view,
                     final com.mojang.renderpearl.api.pipeline.@org.jspecify.annotations.Nullable CompiledRenderPipeline bound) {
        int previous = albedoTex;
        onSetAlbedoTex(view);
        if (bound != null && albedoTex != previous && gbuffers != null && !failed) {
            try {
                gbuffers.albedoChanged(pass, bound);
            } catch (RuntimeException e) {
                if (warnedSamplers.add("albedo uniforms " + e)) {
                    NativiumIris.LOGGER.error("[nativium-iris] updating albedo uniforms failed", e);
                }
            }
        }
    }

    /** True after a runtime failure switched this pipeline to the vanilla fallback. */
    public boolean hasFailed() {
        return failed;
    }

    public boolean rendersGbuffers() {
        return gbuffers != null && !failed;
    }

    void bindGbufferResources(final RenderPass pass, final com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline bound) {
        try {
            gbuffers.bind(pass, bound);
        } catch (RuntimeException e) {
            // Never throw inside a vanilla render pass: the draw goes ahead with whatever was bound before.
            if (warnedSamplers.add("bind failure " + e)) {
                NativiumIris.LOGGER.error("[nativium-iris] binding gbuffers resources failed", e);
            }
        }
    }

    PostTargets targets() {
        return targets;
    }

    CustomUniforms customUniforms() {
        return customUniforms;
    }

    ProgramSet programSet() {
        return programSet;
    }

    GpuSampler nearestSampler() {
        return nearest;
    }

    GpuSampler linearSampler() {
        return linear;
    }

    GpuSampler linearRepeatSampler() {
        return linearRepeat;
    }

    GpuTextureView noiseView() {
        return noiseView;
    }

    GpuTextureView whiteView() {
        return whiteView;
    }

    ShadowTargets shadowTargets() {
        return shadowTargets;
    }

    /** Flip state the opaque gbuffers write (Iris's flippedAfterPrepare). */
    ImmutableSet<Integer> sceneWritesAlt() {
        return sceneWritesAlt;
    }

    GpuTextureView depthNoTranslucents() {
        return gbuffers != null ? gbuffers.depthNoTranslucents() : Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView();
    }

    GpuTextureView depthNoHand() {
        return gbuffers != null ? gbuffers.depthNoHand() : Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView();
    }

    /** Voxy's shader-pack pipeline on Metal (created on first use, by Voxy's pipeline factory). */
    public VoxyIrisBridge voxyBridge() {
        VoxyIrisBridge bridge = voxyBridge;
        if (bridge == null) {
            bridge = voxyBridge = new VoxyIrisBridge(this);
        }
        return bridge;
    }

    /** True while Voxy draws its LODs with this pack (its shader-pack pipeline exists). */
    public boolean voxyLodsActive() {
        return voxyBridge != null && voxyBridge.active();
    }

    /**
     * Far-terrain depth for {@code vxDepthTexOpaque/Trans} (Voxy's LOD depth buffers) and {@code dhDepthTex0/1}
     * (Distant Horizons: not hooked on Metal), or null: the caller binds white = depth 1, "no far terrain here".
     */
    @org.jspecify.annotations.Nullable GpuTextureView farTerrainDepth(final String name) {
        return switch (name) {
            case "vxDepthTexOpaque" -> voxyBridge != null ? voxyBridge.voxyDepthView(true) : null;
            case "vxDepthTexTrans" -> voxyBridge != null ? voxyBridge.voxyDepthView(false) : null;
            default -> null;
        };
    }

    List<String> problems() {
        return problems;
    }

    @Override
    public void destroy() {
        if (lastRendering == this) {
            lastRendering = null;
        }
        if (voxyBridge != null) {
            voxyBridge.close();
        }
        if (horizon != null) {
            horizon.destroy();
        }
        if (gbuffers != null) {
            gbuffers.close();
        }
        begin.forEach(Pass::close);
        prepare.forEach(Pass::close);
        deferred.forEach(Pass::close);
        composite.forEach(Pass::close);
        shadowcomp.forEach(Pass::close);
        if (finalPass != null) {
            finalPass.close();
        }
        setupComputes.forEach(ComputePass::close);
        shadowComputes.forEach(ComputePass::close);
        finalComputes.forEach(ComputePass::close);
        if (storageBuffers != null) {
            storageBuffers.close();
        }
        if (customImages != null) {
            customImages.close();
        }
        customTextures.close();
        if (compute != null) {
            compute.close();
        }
        linearNoMip.close();
        nearestNoMip.close();
        copyPrograms.values().forEach(PostProgram::close);
        targets.close();
        shadowTargets.close();
        if (shadowRenderer != null) {
            shadowRenderer.close();
        }
        quad.close();
        noiseView.close();
        noise.close();
        whiteView.close();
        white.close();
        linear.close();
        nearest.close();
        linearRepeat.close();
    }
}
