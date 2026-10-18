package dev.metallium.iris.post;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.metallium.api.MetalliumRuntime;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.joml.Vector4fc;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Routes the level's render passes into the shader pack's gbuffers (Iris adapter stage 3).
 *
 * <p>On OpenGL, Iris lets vanilla/Sodium open their render passes on the main target and re-binds its own framebuffer
 * per draw. A Metal render pass has fixed attachments, so instead, while {@link MetalPostPipeline} is between
 * {@code beginLevelRendering} and {@code finalizeLevelRendering}:
 * <ol>
 *   <li>every render pass created on the main colour texture is created on the pack's gbuffer attachments instead
 *       (one colortex per attachment, the main depth stays) - {@link #retarget};</li>
 *   <li>every pipeline set on such a pass is replaced by the pack's gbuffers program for it (or by a copy of the
 *       vanilla pipeline with the gbuffer attachment layout) - {@link #substitute};</li>
 *   <li>right after {@code setPipeline}, the pack's own uniforms and samplers are bound - {@link #afterSetPipeline}.</li>
 * </ol>
 * Called from the adapter's mixins into the renderpearl frontend ({@code FrontendCommandEncoder},
 * {@code FrontendRenderPass}) and {@code RenderSystem#getCompiledPipelineNullable}; no backend changes.
 */
public final class GbufferPasses {
    @Nullable
    private static volatile MetalPostPipeline active;
    /** Compiled pipeline -> the {@link RenderPipeline} it was compiled from (for Iris's pipeline -> program mapping). */
    private static final Map<CompiledRenderPipeline, RenderPipeline> SOURCES = new IdentityHashMap<>();
    @Nullable
    private static RenderPassDescriptor lastRetargeted;
    @Nullable
    private static RenderPass capturedPass;
    private static int suspended;

    private GbufferPasses() {
    }

    static void activate(final MetalPostPipeline pipeline) {
        active = pipeline;
    }

    static void deactivate(final MetalPostPipeline pipeline) {
        if (active == pipeline) {
            active = null;
            capturedPass = null;
            lastRetargeted = null;
        }
    }

    /** Metallium's own passes (deferred/composite) run unretargeted. */
    static void suspend() {
        suspended++;
    }

    static void resume() {
        suspended--;
    }

    public static void remember(final RenderPipeline source, @Nullable final CompiledRenderPipeline compiled) {
        if (compiled != null && MetalliumRuntime.isMetalActive()) {
            synchronized (SOURCES) {
                SOURCES.putIfAbsent(compiled, source);
            }
        }
    }

    @Nullable
    static RenderPipeline sourceOf(final CompiledRenderPipeline compiled) {
        synchronized (SOURCES) {
            return SOURCES.get(compiled);
        }
    }

    /** {@code FrontendCommandEncoder#createRenderPass(RenderPassDescriptor)} HEAD. */
    public static RenderPassDescriptor retarget(final RenderPassDescriptor descriptor) {
        MetalPostPipeline pipeline = active;
        if (pipeline == null || suspended > 0 || descriptor.colorAttachments().isEmpty()) {
            return descriptor;
        }
        RenderPassDescriptor.Attachment<Optional<Vector4fc>> first = descriptor.colorAttachments().getFirst();
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (first == null || descriptor.colorAttachments().size() != 1 || first.textureView().texture() != main.getColorTexture()) {
            return descriptor;
        }
        RenderPassDescriptor replaced = pipeline.gbufferDescriptor(descriptor, first.clearValue());
        lastRetargeted = replaced;
        return replaced;
    }

    /** {@code FrontendCommandEncoder#createRenderPass(RenderPassDescriptor)} RETURN. */
    public static void created(final RenderPassDescriptor descriptor, final RenderPass pass) {
        if (descriptor == lastRetargeted && descriptor != null) {
            capturedPass = pass;
            lastRetargeted = null;
        }
    }

    /** {@code FrontendRenderPass#close} HEAD. */
    public static void closed(final RenderPass pass) {
        if (pass == capturedPass) {
            capturedPass = null;
        }
    }

    public static boolean isCaptured(final RenderPass pass) {
        return pass == capturedPass;
    }

    /** {@code FrontendRenderPass#setPipeline} HEAD: the pack's program (or gbuffer-layout copy) for a captured pass. */
    public static CompiledRenderPipeline substitute(final RenderPass pass, final CompiledRenderPipeline pipeline) {
        MetalPostPipeline owner = active;
        if (owner == null) {
            return pipeline;
        }
        return pass == capturedPass ? owner.gbufferPipeline(pipeline) : owner.uncapturedPipeline(pipeline);
    }

    /** {@code FrontendRenderPass#setPipeline} TAIL. */
    public static void afterSetPipeline(final RenderPass pass, final CompiledRenderPipeline bound) {
        MetalPostPipeline owner = active;
        if (owner != null && pass == capturedPass) {
            owner.bindGbufferResources(pass, bound);
        }
    }

    static List<CompiledRenderPipeline> knownCompiled() {
        synchronized (SOURCES) {
            return List.copyOf(SOURCES.keySet());
        }
    }
}
