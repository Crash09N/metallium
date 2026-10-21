package de.nprmedia.nativium.backend;

import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;

import java.util.OptionalDouble;

/**
 * Nativium extension (not part of the renderpearl API, which has no depth-compare samplers): a sampler with a depth
 * compare function, the Metal equivalent of GL's {@code GL_TEXTURE_COMPARE_MODE = GL_COMPARE_REF_TO_TEXTURE}. Shader
 * {@code sampler2DShadow}s become {@code depth2d} + {@code sample_compare} in MSL, which takes the compare function
 * from the bound sampler state. Used by the Iris adapter for shadow maps with hardware filtering.
 */
public final class MetalDepthCompareSamplers {
    private MetalDepthCompareSamplers() {
    }

    /** True if {@code backend} is the Metal backend (only then {@link #create} works). */
    public static boolean supported(final GpuDeviceBackend backend) {
        return backend instanceof MetalDevice;
    }

    public static GpuSampler create(final GpuDeviceBackend backend, final AddressMode addressMode, final FilterMode filter, final CompareOp compare) {
        if (!(backend instanceof MetalDevice device)) {
            throw new IllegalStateException("depth compare samplers need the Metal backend");
        }
        return new MetalGpuSampler(device, addressMode, addressMode, filter, filter, 1, OptionalDouble.of(0.0), compare);
    }
}
