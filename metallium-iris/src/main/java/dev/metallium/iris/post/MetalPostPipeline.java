package dev.metallium.iris.post;

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
import dev.metallium.iris.MetalliumIris;
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
            out vec2 metallium_uv;
            void main() {
                gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
                metallium_uv = UV0;
            }
            """;
    private static final String COPY_FRAGMENT = """
            #version 330 core
            uniform sampler2D metallium_source;
            in vec2 metallium_uv;
            layout(location = 0) out vec4 metallium_color;
            void main() {
                metallium_color = texture(metallium_source, metallium_uv);
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

    private record Pass(String name, PostProgram program, int[] drawBuffers, ImmutableSet<Integer> readsAlt) {
    }

    public MetalPostPipeline(final ProgramSet programSet) {
        this.programSet = programSet;
        this.directives = programSet.getPackDirectives();
        this.targets = new PostTargets(directives.getRenderTargetDirectives());
        this.buffersToBeCleared = directives.getRenderTargetDirectives().getBuffersToBeCleared();
        this.customUniforms = programSet.getPack().customUniforms.build(
                holder -> CommonUniforms.addNonDynamicUniforms(holder, programSet.getPack().getIdMap(), directives, updateNotifier));

        var device = RenderSystem.getDevice();
        this.linear = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
        this.nearest = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
        this.linearRepeat = device.createSampler(AddressMode.REPEAT, AddressMode.REPEAT, FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
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

        boolean useGbuffers = !Boolean.getBoolean("metallium.iris.noGbuffers") && hasGbufferPrograms(programSet);
        var shadowEnabled = directives.getShadowDirectives().isShadowEnabled();
        // Like IrisRenderingPipeline: a shadow pass when the pack has shadow programs (or forces shadow.enabled), unless
        // shadow.enabled=false. It needs the gbuffers capture (Iris calls renderShadows only while a pack is in use).
        boolean useShadows = useGbuffers && !Boolean.getBoolean("metallium.iris.noShadows")
                && shadowEnabled != net.irisshaders.iris.helpers.OptionalBoolean.FALSE
                && (hasShadowPrograms(programSet) || shadowEnabled == net.irisshaders.iris.helpers.OptionalBoolean.TRUE);
        reportUnsupportedPrograms(useGbuffers, useShadows);

        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        targets.resize(main.width, main.height);

        BufferFlipper flipper = new BufferFlipper();
        build(flipper, ProgramArrayId.Begin, TextureStage.BEGIN, "begin_pre", begin);
        build(flipper, ProgramArrayId.Prepare, TextureStage.PREPARE, "prepare_pre", prepare);
        this.sceneWritesAlt = flipper.snapshot();
        build(flipper, ProgramArrayId.Deferred, TextureStage.DEFERRED, "deferred_pre", deferred);
        this.translucentWritesAlt = flipper.snapshot();
        build(flipper, ProgramArrayId.Composite, TextureStage.COMPOSITE_AND_FINAL, "composite_pre", composite);
        this.finalReadsAlt = flipper.snapshot();
        this.finalPass = programSet.get(ProgramId.Final)
                .map(source -> compilePass(source, TextureStage.COMPOSITE_AND_FINAL, finalReadsAlt, new int[0], List.of(main.getColorTexture().getFormat())))
                .orElse(null);

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
                + (begin.size() + prepare.size() + deferred.size() + composite.size()) + " fullscreen passes" + (finalPass != null ? " + final" : "");
        MetalliumIris.LOGGER.info("[metallium-iris] {}", summary);
        reportToChat(summary);
    }

    // ---------------------------------------------------------------------------------------------- construction

    private void build(final BufferFlipper flipper, final ProgramArrayId id, final TextureStage stage, final String preFlipsName, final List<Pass> out) {
        directives.getExplicitFlips(preFlipsName).forEach((buffer, flip) -> {
            if (flip) {
                flipper.flip(buffer);
            }
        });
        ProgramSource[] sources = programSet.getComposite(id);
        for (ProgramSource source : sources) {
            if (source == null || !source.isValid()) {
                continue;
            }
            ImmutableSet<Integer> flipped = flipper.snapshot();
            ProgramDirectives programDirectives = source.getDirectives();
            int[] drawBuffers = programDirectives.getDrawBuffers();
            List<GpuFormat> formats = new ArrayList<>();
            for (int buffer : drawBuffers) {
                formats.add(targets.format(buffer));
            }
            Pass pass = compilePass(source, stage, flipped, drawBuffers, formats);
            if (pass == null) {
                continue;
            }
            out.add(pass);
            ImmutableMap<Integer, Boolean> explicitFlips = programDirectives.getExplicitFlips();
            for (int buffer : drawBuffers) {
                if (explicitFlips.get(buffer) != Boolean.FALSE) {
                    flipper.flip(buffer);
                }
            }
            explicitFlips.forEach((buffer, flip) -> {
                if (flip) {
                    flipper.flip(buffer);
                }
            });
        }
    }

    private Pass compilePass(final ProgramSource source, final TextureStage stage, final ImmutableSet<Integer> readsAlt, final int[] drawBuffers, final List<GpuFormat> formats) {
        String name = source.getName();
        if (source.getGeometrySource().isPresent() || source.getTessControlSource().isPresent() || source.getTessEvalSource().isPresent()) {
            problems.add(name + ": geometry/tessellation shaders are not supported on Metal (pass skipped)");
            return null;
        }
        ProgramDirectives programDirectives = source.getDirectives();
        if (!programDirectives.getMipmappedBuffers().isEmpty()) {
            problems.add(name + ": mipmapped buffers not supported yet (sampled without mipmaps)");
        }
        if (programDirectives.getViewportScale().scale() != 1.0f) {
            problems.add(name + ": viewport scale not supported yet (rendered at full size)");
        }
        if (programDirectives.getBlendModeOverride().isPresent()) {
            problems.add(name + ": blend override not supported yet (blending off)");
        }
        try {
            Map<PatchShaderType, String> transformed = TransformPatcher.patchComposite(name,
                    source.getVertexSource().orElseThrow(), null, source.getFragmentSource().orElseThrow(), stage, getTextureMap(), getTextureOverrides(stage));
            PostProgram program = PostProgram.compile(name, transformed.get(PatchShaderType.VERTEX), transformed.get(PatchShaderType.FRAGMENT), formats);
            CommonUniforms.addDynamicUniforms(program.uniforms, FogMode.OFF);
            customUniforms.assignTo(program.uniforms);
            customUniforms.mapholderToPass(program.uniforms, program);
            return new Pass(name, program, drawBuffers, readsAlt);
        } catch (Exception | LinkageError e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage().lines().findFirst().orElse(e.toString());
            problems.add(name + ": failed to translate (" + message + ")");
            MetalliumIris.LOGGER.warn("[metallium-iris] Program {} could not be translated for Metal", name, e);
            return null;
        }
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
        List<String> skipped = new ArrayList<>();
        for (ProgramId id : ProgramId.values()) {
            ProgramGroup group = id.getGroup();
            boolean unsupported = (group == ProgramGroup.Shadow && !shadowsRun) || group == ProgramGroup.Dh || (group == ProgramGroup.Gbuffers && !gbuffersRun);
            if (unsupported && programSet.get(id).isPresent()) {
                skipped.add(id.getSourceName());
            }
        }
        if (!skipped.isEmpty()) {
            problems.add(skipped.size() + (shadowsRun ? " DH programs are not executed yet on Metal"
                    : gbuffersRun ? " shadow/DH programs are not executed yet on Metal (no shadow pass)"
                    : " gbuffers/shadow programs are not executed yet on Metal (the vanilla scene is used)"));
            MetalliumIris.LOGGER.warn("[metallium-iris] programs not executed: {}", String.join(", ", skipped));
        }
        if (programSet.getComposite(ProgramArrayId.ShadowComposite).length > 0
                && java.util.Arrays.stream(programSet.getComposite(ProgramArrayId.ShadowComposite)).anyMatch(s -> s != null && s.isValid())) {
            problems.add("shadowcomp passes are not executed yet on Metal");
        }
        int computes = countComputes(programSet.getSetup()) + countComputes(programSet.getShadowCompute()) + countComputes(programSet.getFinalCompute());
        for (ProgramArrayId id : ProgramArrayId.values()) {
            for (ComputeSource[] array : programSet.getCompute(id)) {
                computes += countComputes(array);
            }
        }
        if (computes > 0) {
            problems.add(computes + " compute shaders are not supported yet on Metal (skipped)");
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

    private void reportToChat(final String summary) {
        for (String problem : problems) {
            MetalliumIris.LOGGER.warn("[metallium-iris] Unsupported shader pack feature: {}", problem);
        }
        try {
            var chat = Minecraft.getInstance().gui.hud.getChat();
            chat.addClientSystemMessage(Component.literal("[Metallium] " + summary).withStyle(ChatFormatting.GOLD));
            if (!problems.isEmpty()) {
                String first = problems.getFirst();
                chat.addClientSystemMessage(Component.literal("[Metallium] Not supported yet: " + first
                        + (problems.size() > 1 ? " (+" + (problems.size() - 1) + " more, see log)" : "")).withStyle(ChatFormatting.YELLOW));
            }
        } catch (RuntimeException e) {
            MetalliumIris.LOGGER.debug("[metallium-iris] chat not available", e);
        }
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
            targets.resize(main.width, main.height);
            if (Boolean.getBoolean("metallium.iris.test.failGbuffers")) {
                throw new IllegalStateException("simulated gbuffers failure (game test)");
            }
            updateNotifier.onNewFrame();
            state.ensureBlockIds(); // before customUniforms.update(): currentSelectedBlockId reads the block ID map
            customUniforms.update();
            state.beginFrame(main);
            clearBuffers();
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
        MetalliumIris.LOGGER.error("[metallium-iris] Metal shader pack: {}; showing the vanilla scene from now on", what, e);
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
        MetalliumIris.LOGGER.info("[metallium-iris] vanilla fallback: chunk vertex format reset, chunks rebuilt");
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
            Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("[Metallium] " + message).withStyle(ChatFormatting.RED));
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
                    MetalliumIris.LOGGER.debug("[metallium-iris] post targets resized to {}x{}", main.width, main.height);
                }
                updateNotifier.onNewFrame();
                customUniforms.update();
                clearBuffers();
                for (Pass pass : begin) {
                    draw(pass, main);
                }
                for (Pass pass : prepare) {
                    draw(pass, main);
                }
                // The vanilla/Sodium scene takes the place of the gbuffers output in colortex0.
                copy(main.getColorTexture(), main.getColorTextureView(), targets.get(0).texture(sceneWritesAlt.contains(0)), targets.get(0).view(sceneWritesAlt.contains(0)));
                for (Pass pass : deferred) {
                    draw(pass, main);
                }
            }
            for (Pass pass : composite) {
                draw(pass, main);
            }
            PostTargets.Target colortex0 = targets.get(0);
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
                    encoder.copyTextureToTexture(target.alt, target.main, 0, 0, 0, 0, 0, targets.width(), targets.height());
                }
            }
        } finally {
            GbufferPasses.resume();
        }
    }

    private void draw(final Pass pass, final RenderTarget main) {
        PostProgram program = pass.program;
        program.uniforms.update();
        customUniforms.push(program);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        Map<String, GpuBufferSlice> blocks = uploadBlocks(encoder, program);
        // Create (and clear) buffers used for the first time before the render pass opens.
        for (int buffer : pass.drawBuffers) {
            targets.get(buffer);
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
                descriptor.withColorAttachment(targets.get(buffer).view(!pass.readsAlt.contains(buffer)));
            }
        }
        try (RenderPass renderPass = encoder.createRenderPass(descriptor.build())) {
            renderPass.setPipeline(program.pipeline);
            renderPass.setVertexBuffer(0, quad.slice());
            blocks.forEach(renderPass::setUniform);
            for (String sampler : program.samplers) {
                if (!bindShadowSampler(renderPass, sampler, program.compareSamplers.contains(sampler), program.samplers.contains("watershadow"), false)) {
                    bindSampler(renderPass, sampler, pass.readsAlt, main);
                }
            }
            renderPass.draw(6, 1, 0, 0);
        }
    }

    private static Map<String, GpuBufferSlice> uploadBlocks(final CommandEncoder encoder, final PostProgram program) {
        Map<String, GpuBufferSlice> slices = new HashMap<>();
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        program.blocks.forEach((name, block) ->
                slices.put(name, encoder.transientMemory().uploadStaging(block.data.duplicate().clear(), alignment, GpuBuffer.USAGE_UNIFORM)));
        return slices;
    }

    private void bindSampler(final RenderPass renderPass, final String name, final ImmutableSet<Integer> readsAlt, final RenderTarget main) {
        int colortex = colortexIndex(name);
        if (colortex >= 0) {
            PostTargets.Target target = targets.get(colortex);
            renderPass.setUniform(name, target.view(readsAlt.contains(colortex)), target.integer ? nearest : linear);
            return;
        }
        switch (name) {
            case "depthtex0", "gdepthtex" -> renderPass.setUniform(name, main.getDepthTextureView(), nearest);
            case "depthtex1" -> renderPass.setUniform(name, gbuffers != null ? gbuffers.depthNoTranslucents() : main.getDepthTextureView(), nearest);
            case "depthtex2" -> renderPass.setUniform(name, gbuffers != null ? gbuffers.depthNoHand() : main.getDepthTextureView(), nearest);
            case "noisetex" -> renderPass.setUniform(name, noiseView, linearRepeat);
            case "metallium_source" -> throw new IllegalStateException("copy sampler outside the copy pass");
            default -> {
                if (name.startsWith("shadow") || name.equals("watershadow")) {
                    // No shadow pass yet: a white (depth 1 / fully lit) placeholder means "no shadow".
                    if (warnedSamplers.add(name)) {
                        MetalliumIris.LOGGER.warn("[metallium-iris] Sampler '{}' needs the shadow pass (not on Metal yet); a white placeholder is bound", name);
                    }
                    renderPass.setUniform(name, whiteView, nearest);
                } else {
                    // In GL an unassigned sampler reads texture unit 0, which Iris fills with colortex0 in fullscreen
                    // passes (IrisSamplers.addRenderTargetSamplers "default sampler").
                    if (warnedSamplers.add(name)) {
                        MetalliumIris.LOGGER.info("[metallium-iris] Sampler '{}' has no Iris binding in fullscreen passes; colortex0 is bound like in GL", name);
                    }
                    PostTargets.Target target = targets.get(0);
                    renderPass.setUniform(name, target.view(readsAlt.contains(0)), linear);
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
        if (source.getFormat() == destination.getFormat()) {
            encoder.copyTextureToTexture(source, destination, 0, 0, 0, 0, 0, width, height);
            return;
        }
        PostProgram program = copyPrograms.computeIfAbsent(destination.getFormat(), format -> {
            try {
                return PostProgram.compile("metallium_copy_" + format.name().toLowerCase(), COPY_VERTEX, COPY_FRAGMENT, List.of(format));
            } catch (Exception e) {
                throw new IllegalStateException("could not build the copy program for " + format, e);
            }
        });
        try (RenderPass renderPass = encoder.createRenderPass(() -> "iris copy", destinationView, Optional.empty())) {
            renderPass.setPipeline(program.pipeline);
            renderPass.setVertexBuffer(0, quad.slice());
            renderPass.setUniform("metallium_source", sourceView, linear);
            renderPass.draw(6, 1, 0, 0);
        }
    }

    // ---------------------------------------------------------------------------------------------- pipeline queries

    @Override
    public Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> getTextureMap() {
        return directives.getTextureMap();
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
            MetalliumIris.LOGGER.error("[metallium-iris] gbuffers pipeline substitution failed", e);
            return original;
        }
    }

    com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline uncapturedPipeline(final com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline original) {
        try {
            return gbuffers.uncaptured(original);
        } catch (RuntimeException e) {
            MetalliumIris.LOGGER.error("[metallium-iris] recompiling a pipeline for Iris's extended vertex format failed", e);
            return original;
        }
    }

    /** True while the pack's gbuffers programs render the world (Iris's {@code isPackInUseQuick} on Metal). */
    public boolean rendersGbuffers() {
        return gbuffers != null && !failed;
    }

    void bindGbufferResources(final RenderPass pass, final com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline bound) {
        try {
            gbuffers.bind(pass, bound);
        } catch (RuntimeException e) {
            // Never throw inside a vanilla render pass: the draw goes ahead with whatever was bound before.
            if (warnedSamplers.add("bind failure " + e)) {
                MetalliumIris.LOGGER.error("[metallium-iris] binding gbuffers resources failed", e);
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

    List<String> problems() {
        return problems;
    }

    @Override
    public void destroy() {
        if (gbuffers != null) {
            gbuffers.close();
        }
        begin.forEach(pass -> pass.program.close());
        prepare.forEach(pass -> pass.program.close());
        deferred.forEach(pass -> pass.program.close());
        composite.forEach(pass -> pass.program.close());
        if (finalPass != null) {
            finalPass.program.close();
        }
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
