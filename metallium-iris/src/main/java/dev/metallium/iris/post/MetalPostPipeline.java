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
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
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
    private final List<Pass> beforeScene = new ArrayList<>();
    private final List<Pass> afterScene = new ArrayList<>();
    private final Pass finalPass;
    private final ImmutableSet<Integer> sceneWritesAlt;
    private final ImmutableSet<Integer> finalReadsAlt;
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

        reportUnsupportedPrograms();

        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        targets.resize(main.width, main.height);

        BufferFlipper flipper = new BufferFlipper();
        build(flipper, ProgramArrayId.Begin, TextureStage.BEGIN, "begin_pre", beforeScene);
        build(flipper, ProgramArrayId.Prepare, TextureStage.PREPARE, "prepare_pre", beforeScene);
        this.sceneWritesAlt = flipper.snapshot();
        build(flipper, ProgramArrayId.Deferred, TextureStage.DEFERRED, "deferred_pre", afterScene);
        build(flipper, ProgramArrayId.Composite, TextureStage.COMPOSITE_AND_FINAL, "composite_pre", afterScene);
        this.finalReadsAlt = flipper.snapshot();
        this.finalPass = programSet.get(ProgramId.Final)
                .map(source -> compilePass(source, TextureStage.COMPOSITE_AND_FINAL, finalReadsAlt, new int[0], List.of(main.getColorTexture().getFormat())))
                .orElse(null);

        String summary = "Metal post-processing pipeline: " + (beforeScene.size() + afterScene.size()) + " fullscreen passes"
                + (finalPass != null ? " + final" : "");
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

    private void reportUnsupportedPrograms() {
        List<String> skipped = new ArrayList<>();
        for (ProgramId id : ProgramId.values()) {
            ProgramGroup group = id.getGroup();
            if ((group == ProgramGroup.Gbuffers || group == ProgramGroup.Shadow || group == ProgramGroup.Dh) && programSet.get(id).isPresent()) {
                skipped.add(id.getSourceName());
            }
        }
        if (!skipped.isEmpty()) {
            problems.add(skipped.size() + " gbuffers/shadow programs are not executed yet on Metal (vanilla scene is used): " + String.join(", ", skipped));
        }
        if (programSet.getComposite(ProgramArrayId.ShadowComposite).length > 0
                && java.util.Arrays.stream(programSet.getComposite(ProgramArrayId.ShadowComposite)).anyMatch(s -> s != null && s.isValid())) {
            problems.add("shadowcomp passes are not executed yet on Metal (no shadow pass)");
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
        // No GL program to unbind; per-frame work happens in finalizeLevelRendering.
    }

    @Override
    public void finalizeLevelRendering() {
        if (failed) {
            return;
        }
        try {
            render();
        } catch (RuntimeException e) {
            failed = true;
            MetalliumIris.LOGGER.error("[metallium-iris] Metal post-processing failed; showing the vanilla scene from now on", e);
            reportProblem("post-processing failed: " + e);
        }
    }

    private void reportProblem(final String message) {
        try {
            Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("[Metallium] " + message).withStyle(ChatFormatting.RED));
        } catch (RuntimeException ignored) {
            // chat not available
        }
    }

    private void render() {
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (targets.resize(main.width, main.height)) {
            MetalliumIris.LOGGER.debug("[metallium-iris] post targets resized to {}x{}", main.width, main.height);
        }
        updateNotifier.onNewFrame();
        customUniforms.update();

        for (int buffer : buffersToBeCleared) {
            if (buffer != 0) {
                targets.clearIfPresent(buffer);
            }
        }
        for (Pass pass : beforeScene) {
            draw(pass, main);
        }
        // The vanilla/Sodium scene takes the place of the gbuffers output in colortex0.
        copy(main.getColorTexture(), main.getColorTextureView(), targets.get(0).texture(sceneWritesAlt.contains(0)), targets.get(0).view(sceneWritesAlt.contains(0)));
        for (Pass pass : afterScene) {
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
    }

    private void draw(final Pass pass, final RenderTarget main) {
        PostProgram program = pass.program;
        program.uniforms.update();
        customUniforms.push(program);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        Map<String, GpuBufferSlice> blocks = uploadBlocks(encoder, program);

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
                bindSampler(renderPass, sampler, pass.readsAlt, main);
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
            case "depthtex0", "gdepthtex", "depthtex1", "depthtex2" -> renderPass.setUniform(name, main.getDepthTextureView(), nearest);
            case "noisetex" -> renderPass.setUniform(name, noiseView, linearRepeat);
            case "metallium_source" -> throw new IllegalStateException("copy sampler outside the copy pass");
            default -> {
                if (warnedSamplers.add(name)) {
                    MetalliumIris.LOGGER.warn("[metallium-iris] Sampler '{}' is not available on Metal yet; a white placeholder is bound", name);
                }
                renderPass.setUniform(name, whiteView, nearest);
            }
        }
    }

    private static int colortexIndex(final String name) {
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

    @Override
    public void destroy() {
        beforeScene.forEach(pass -> pass.program.close());
        afterScene.forEach(pass -> pass.program.close());
        if (finalPass != null) {
            finalPass.program.close();
        }
        copyPrograms.values().forEach(PostProgram::close);
        targets.close();
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
