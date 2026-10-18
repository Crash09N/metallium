package dev.metallium.iris.post;

import dev.metallium.backend.MetalliumInterop;
import io.github.kokodio.metaljvm.foundation.NSErrorException;
import io.github.kokodio.metaljvm.metal.MTLBlitCommandEncoder;
import io.github.kokodio.metaljvm.metal.MTLCommandBuffer;
import io.github.kokodio.metaljvm.metal.MTLCompareFunction;
import io.github.kokodio.metaljvm.metal.MTLComputeCommandEncoder;
import io.github.kokodio.metaljvm.metal.MTLComputePipelineState;
import io.github.kokodio.metaljvm.metal.MTLDevice;
import io.github.kokodio.metaljvm.metal.MTLFunction;
import io.github.kokodio.metaljvm.metal.MTLLibrary;
import io.github.kokodio.metaljvm.metal.MTLSamplerAddressMode;
import io.github.kokodio.metaljvm.metal.MTLSamplerDescriptor;
import io.github.kokodio.metaljvm.metal.MTLSamplerMinMagFilter;
import io.github.kokodio.metaljvm.metal.MTLSamplerMipFilter;
import io.github.kokodio.metaljvm.metal.MTLSamplerState;
import io.github.kokodio.metaljvm.metal.MTLSize;
import io.github.kokodio.metaljvm.metal.MTLTexture;
import io.github.kokodio.metaljvm.metal.MTLTextureType;
import io.github.kokodio.metaljvm.objc.AutoreleasePool;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Stage 5: the Metal side of the Iris adapter's compute work. Owns the compute (and mipmap blit) encoders that are
 * interleaved with Metallium's own encoders through {@link MetalliumInterop}'s external-encoder contract: an encoder
 * is opened lazily on the frame's command buffer, waits for and updates Metallium's frame fence, and is closed as soon
 * as Metallium encodes anything else, so compute dispatches are ordered exactly where Iris issues them between render
 * passes. Dispatches inside one compute encoder run serially (the default dispatch type), which gives GL's
 * {@code glMemoryBarrier} ordering between consecutive dispatches.
 */
final class MetalCompute implements MetalliumInterop.ExternalEncoderClient, AutoCloseable {
    private static final String CLEAR_KERNELS = """
            #include <metal_stdlib>
            using namespace metal;
            kernel void clear2d_f(texture2d<float, access::write> t [[texture(0)]], uint2 g [[thread_position_in_grid]]) {
                if (g.x < t.get_width() && g.y < t.get_height()) t.write(float4(0.0), g);
            }
            kernel void clear2d_u(texture2d<uint, access::write> t [[texture(0)]], uint2 g [[thread_position_in_grid]]) {
                if (g.x < t.get_width() && g.y < t.get_height()) t.write(uint4(0), g);
            }
            kernel void clear2d_i(texture2d<int, access::write> t [[texture(0)]], uint2 g [[thread_position_in_grid]]) {
                if (g.x < t.get_width() && g.y < t.get_height()) t.write(int4(0), g);
            }
            kernel void clear3d_f(texture3d<float, access::write> t [[texture(0)]], uint3 g [[thread_position_in_grid]]) {
                if (g.x < t.get_width() && g.y < t.get_height() && g.z < t.get_depth()) t.write(float4(0.0), g);
            }
            kernel void clear3d_u(texture3d<uint, access::write> t [[texture(0)]], uint3 g [[thread_position_in_grid]]) {
                if (g.x < t.get_width() && g.y < t.get_height() && g.z < t.get_depth()) t.write(uint4(0), g);
            }
            kernel void clear3d_i(texture3d<int, access::write> t [[texture(0)]], uint3 g [[thread_position_in_grid]]) {
                if (g.x < t.get_width() && g.y < t.get_height() && g.z < t.get_depth()) t.write(int4(0), g);
            }
            """;

    final MTLDevice device;
    final MTLSamplerState linearClamp;
    final MTLSamplerState nearestClamp;
    final MTLSamplerState linearRepeat;
    final MTLSamplerState compareLinear;
    final MTLSamplerState compareNearest;
    private final Map<String, MTLComputePipelineState> clearPipelines = new HashMap<>();
    private @Nullable MTLComputeCommandEncoder compute;
    private @Nullable MTLBlitCommandEncoder blit;
    private int dispatches;
    private io.github.kokodio.metaljvm.metal.@Nullable MTLBuffer zero;

    MetalCompute() {
        this.device = MetalliumInterop.device();
        this.linearClamp = sampler(MTLSamplerMinMagFilter.Linear, MTLSamplerAddressMode.ClampToEdge, null);
        this.nearestClamp = sampler(MTLSamplerMinMagFilter.Nearest, MTLSamplerAddressMode.ClampToEdge, null);
        this.linearRepeat = sampler(MTLSamplerMinMagFilter.Linear, MTLSamplerAddressMode.Repeat, null);
        this.compareLinear = sampler(MTLSamplerMinMagFilter.Linear, MTLSamplerAddressMode.ClampToEdge, MTLCompareFunction.LessEqual);
        this.compareNearest = sampler(MTLSamplerMinMagFilter.Nearest, MTLSamplerAddressMode.ClampToEdge, MTLCompareFunction.LessEqual);
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLLibrary library = device.newLibraryWithSource(CLEAR_KERNELS, null);
            for (String name : new String[]{"clear2d_f", "clear2d_u", "clear2d_i", "clear3d_f", "clear3d_u", "clear3d_i"}) {
                clearPipelines.put(name, pipeline(library, name));
            }
            library.release();
        } catch (NSErrorException e) {
            throw new IllegalStateException("Metallium's image clear kernels failed to compile: " + e.getMessage(), e);
        }
    }

    private MTLSamplerState sampler(final MTLSamplerMinMagFilter filter, final MTLSamplerAddressMode address, final @Nullable MTLCompareFunction compare) {
        MTLSamplerDescriptor descriptor = MTLSamplerDescriptor.alloc().init();
        descriptor.setMinFilter(filter);
        descriptor.setMagFilter(filter);
        descriptor.setMipFilter(filter == MTLSamplerMinMagFilter.Linear ? MTLSamplerMipFilter.Linear : MTLSamplerMipFilter.Nearest);
        descriptor.setSAddressMode(address);
        descriptor.setTAddressMode(address);
        descriptor.setRAddressMode(address);
        if (compare != null) {
            descriptor.setCompareFunction(compare);
        }
        MTLSamplerState state = device.newSamplerState(descriptor);
        descriptor.release();
        return state;
    }

    /** Compiles one kernel of an MSL library into a pipeline state (throws with the Metal compiler message). */
    MTLComputePipelineState pipeline(final MTLLibrary library, final String entryPoint) {
        MTLFunction function = library.newFunctionWithName(entryPoint);
        if (function == null) {
            throw new IllegalStateException("MSL entry point " + entryPoint + " not found");
        }
        try {
            return device.newComputePipelineStateWithFunction(function);
        } finally {
            function.release();
        }
    }

    /** The open compute encoder on the frame's command buffer (opening one ends whatever Metallium had open). */
    MTLComputeCommandEncoder computeEncoder() {
        if (compute != null) {
            return compute;
        }
        endEncoders();
        MTLCommandBuffer buffer = MetalliumInterop.beginExternalEncoding(this);
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            compute = buffer.computeCommandEncoder();
            compute.retain();
        }
        compute.waitForFence(MetalliumInterop.frameFence());
        return compute;
    }

    private MTLBlitCommandEncoder blitEncoder() {
        if (blit != null) {
            return blit;
        }
        endEncoders();
        MTLCommandBuffer buffer = MetalliumInterop.beginExternalEncoding(this);
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            blit = buffer.blitCommandEncoder();
            blit.retain();
        }
        blit.waitForFence(MetalliumInterop.frameFence());
        return blit;
    }

    /** Mipmap chain of a colour buffer (Iris's {@code colortexNMipmapEnabled}); level 0 must be final already. */
    void generateMipmaps(final MTLTexture texture) {
        if (texture.mipmapLevelCount() > 1) {
            blitEncoder().generateMipmapsForTexture(texture);
        }
    }

    /** Zeroes a custom image ({@code image.<name> ... clear=true}), like GL's {@code glClearTexImage(.., null)}. */
    void clear(final MTLTexture texture, final char componentKind) {
        boolean is3d = texture.textureType() == MTLTextureType.Type3D;
        MTLComputePipelineState state = clearPipelines.get((is3d ? "clear3d_" : "clear2d_") + componentKind);
        MTLComputeCommandEncoder encoder = computeEncoder();
        encoder.setComputePipelineState(state);
        encoder.setTexture(texture, 0);
        MTLSize group = is3d ? new MTLSize(4, 4, 4) : new MTLSize(8, 8, 1);
        encoder.dispatchThreadgroups(new MTLSize(
                (texture.width() + group.width() - 1) / group.width(),
                (texture.height() + group.height() - 1) / group.height(),
                (Math.max(1, texture.depth()) + group.depth() - 1) / group.depth()), group);
    }

    /** A small zero-filled buffer for SSBO bindings the pack declares but never defines (GL reads zeros). */
    io.github.kokodio.metaljvm.metal.MTLBuffer zeroBuffer() {
        if (zero == null) {
            zero = device.newBuffer(256, io.github.kokodio.metaljvm.metal.MTLResourceOptions.StorageModeShared
                    | io.github.kokodio.metaljvm.metal.MTLResourceOptions.HazardTrackingModeUntracked);
            zero.contents().reinterpret(256).fill((byte) 0);
        }
        return zero;
    }

    void countDispatch() {
        dispatches++;
    }

    int dispatches() {
        return dispatches;
    }

    @Override
    public void endExternalEncoding() {
        endEncoders();
    }

    private void endEncoders() {
        if (compute != null) {
            compute.updateFence(MetalliumInterop.frameFence());
            compute.endEncoding();
            compute.release();
            compute = null;
        }
        if (blit != null) {
            blit.updateFence(MetalliumInterop.frameFence());
            blit.endEncoding();
            blit.release();
            blit = null;
        }
    }

    @Override
    public void close() {
        // Only called between frames; an encoder left open has been ended by Metallium already.
        clearPipelines.values().forEach(state -> MetalliumInterop.releaseAfterGpu(state::release));
        clearPipelines.clear();
        if (zero != null) {
            io.github.kokodio.metaljvm.metal.MTLBuffer released = zero;
            MetalliumInterop.releaseAfterGpu(released::release);
            zero = null;
        }
        for (MTLSamplerState state : new MTLSamplerState[]{linearClamp, nearestClamp, linearRepeat, compareLinear, compareNearest}) {
            MetalliumInterop.releaseAfterGpu(state::release);
        }
    }
}
