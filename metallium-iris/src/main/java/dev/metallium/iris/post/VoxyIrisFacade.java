package dev.metallium.iris.post;

import com.google.common.collect.ImmutableSet;
import net.irisshaders.iris.gl.image.ImageHolder;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;

import java.util.function.Supplier;

/**
 * The part of Iris's OpenGL {@link IrisRenderingPipeline} that Voxy's shader-pack integration reads when it builds its
 * pipeline data ({@code IrisVoxyRenderPipelineData.buildPipeline}): the flip state after the prepare passes, the
 * gbuffer sampler set ({@link #addGbufferOrShadowSamplers}) and the colour buffers ({@code renderTargets}, filled by
 * {@link VoxyIrisBridge} with GL names of the Metal textures). Allocated without running a constructor (Iris's would
 * create GL resources) and never registered with Iris's pipeline manager.
 */
final class VoxyIrisFacade extends IrisRenderingPipeline {
    VoxyIrisBridge bridge;

    @SuppressWarnings("unused")
    private VoxyIrisFacade(final ProgramSet programSet) {
        super(programSet); // never called: instances come from Unsafe.allocateInstance
    }

    @Override
    public ImmutableSet<Integer> getFlippedAfterPrepare() {
        return bridge.opaqueWritesAlt();
    }

    @Override
    public void addGbufferOrShadowSamplers(final SamplerHolder samplers, final ImageHolder images, final Supplier<ImmutableSet<Integer>> flipped,
                                           final boolean isShadowPass, final boolean hasTexture, final boolean hasLightmap, final boolean hasOverlay) {
        bridge.addSamplers(samplers, hasTexture, hasLightmap, hasOverlay);
    }
}
