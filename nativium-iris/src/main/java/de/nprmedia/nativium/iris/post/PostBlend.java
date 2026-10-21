package de.nprmedia.nativium.iris.post;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import net.irisshaders.iris.gl.blending.BufferBlendInformation;
import net.irisshaders.iris.shaderpack.properties.ProgramDirectives;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Blend state of a fullscreen pass (composite/deferred/prepare/begin/final) from the pack's {@code shaders.properties}:
 * {@code blend.<program>=<srcRGB> <dstRGB> <srcA> <dstA>|off} for every target, {@code blend.<program>.<colortexN>=...}
 * for one buffer (wins over the program-wide value). Without either, fullscreen passes do not blend (Iris's
 * CompositeRenderer disables blending unless the program has an override).
 */
final class PostBlend {
    private PostBlend() {
    }

    /** One entry per draw buffer, in {@code drawBuffers} order; empty = blending off for that target. */
    static List<Optional<BlendFunction>> forDrawBuffers(final ProgramDirectives directives, final int[] drawBuffers) {
        Optional<BlendFunction> program = directives.getBlendModeOverride().map(GbufferState::toBlend).orElse(Optional.empty());
        List<Optional<BlendFunction>> result = new ArrayList<>(drawBuffers.length);
        for (int buffer : drawBuffers) {
            Optional<BlendFunction> blend = program;
            for (BufferBlendInformation information : directives.getBufferBlendOverrides()) {
                if (information.index() == buffer) {
                    blend = GbufferState.toBlend(information.blendMode());
                }
            }
            result.add(blend);
        }
        return result;
    }
}
