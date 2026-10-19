/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl.metal;

import io.github.kokodio.metaljvm.foundation.NSErrorException;
import io.github.kokodio.metaljvm.metal.MTLArgumentEncoder;
import io.github.kokodio.metaljvm.metal.MTLBuffer;
import io.github.kokodio.metaljvm.metal.MTLCompileOptions;
import io.github.kokodio.metaljvm.metal.MTLComputeCommandEncoder;
import io.github.kokodio.metaljvm.metal.MTLComputePipelineState;
import io.github.kokodio.metaljvm.metal.MTLDevice;
import io.github.kokodio.metaljvm.metal.MTLFunction;
import io.github.kokodio.metaljvm.metal.MTLIndirectCommandBuffer;
import io.github.kokodio.metaljvm.metal.MTLIndirectCommandBufferDescriptor;
import io.github.kokodio.metaljvm.metal.MTLIndirectCommandType;
import io.github.kokodio.metaljvm.metal.MTLLanguageVersion;
import io.github.kokodio.metaljvm.metal.MTLLibrary;
import io.github.kokodio.metaljvm.metal.MTLResourceOptions;
import io.github.kokodio.metaljvm.metal.MTLResourceUsage;
import io.github.kokodio.metaljvm.metal.MTLSize;
import io.github.kokodio.metaljvm.objc.AutoreleasePool;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.lwjgl.opengl.GL46C.*;

/**
 * {@code glMultiDrawElementsIndirectCount} on Metal. GL reads the draw count from GPU memory; Metal has no draw call
 * taking a GPU count, so a small compute kernel converts the GL {@code DrawElementsIndirectCommand}s into an
 * {@link MTLIndirectCommandBuffer} (indexed draws; commands at or beyond the count, or with zero instances, are reset),
 * and the render encoder executes the whole ICB. The ICB inherits pipeline state and buffer bindings from the render
 * encoder, so the GL program's SSBOs / uniforms are bound exactly as for a direct draw. No CPU readback, and the CPU cost
 * is independent of the number of draws.
 *
 * <p>Each call gets its own ICB (several MDIC calls per frame must not overwrite each other's commands before the GPU
 * executes them); ICBs come from a {@link SizeClassPool} (power-of-two capacities) and return to it once the GPU
 * finished the frame, so a steady scene allocates none per frame.
 */
final class IndirectCountDraws {
    private static final String MSL = """
            #include <metal_stdlib>
            using namespace metal;
            struct GlDrawElementsIndirectCommand { uint count; uint instanceCount; uint firstIndex; int baseVertex; uint baseInstance; };
            struct Icb { command_buffer commands [[id(0)]]; };
            struct Params { uint maxDraws; uint stride; uint primitive; uint pad; };

            template <typename I>
            static void encode(device const uchar* commands, device const uint* count, constant Params& p, device const I* indices,
                               device Icb& icb, uint i) {
                if (i >= p.maxDraws) return;
                render_command cmd(icb.commands, i);
                device const GlDrawElementsIndirectCommand& c = *(device const GlDrawElementsIndirectCommand*) (commands + i * p.stride);
                if (i >= min(count[0], p.maxDraws) || c.count == 0u || c.instanceCount == 0u) {
                    cmd.reset();
                    return;
                }
                cmd.draw_indexed_primitives(primitive_type(p.primitive), c.count, indices + c.firstIndex, c.instanceCount, c.baseVertex, c.baseInstance);
            }

            kernel void encode_u16(device const uchar* commands [[buffer(0)]], device const uint* count [[buffer(1)]],
                                   constant Params& p [[buffer(2)]], device const ushort* indices [[buffer(3)]],
                                   device Icb& icb [[buffer(4)]], uint i [[thread_position_in_grid]]) {
                encode(commands, count, p, indices, icb, i);
            }

            kernel void encode_u32(device const uchar* commands [[buffer(0)]], device const uint* count [[buffer(1)]],
                                   constant Params& p [[buffer(2)]], device const uint* indices [[buffer(3)]],
                                   device Icb& icb [[buffer(4)]], uint i [[thread_position_in_grid]]) {
                encode(commands, count, p, indices, icb, i);
            }

            // Fallback for programs Metal does not allow in ICBs (fragment / vertex textures): plain Metal indirect
            // arguments, commands at or beyond the GPU count get instanceCount 0.
            kernel void compact(device const uchar* commands [[buffer(0)]], device const uint* count [[buffer(1)]],
                                constant Params& p [[buffer(2)]], device GlDrawElementsIndirectCommand* out [[buffer(3)]],
                                uint i [[thread_position_in_grid]]) {
                if (i >= p.maxDraws) return;
                GlDrawElementsIndirectCommand c = *(device const GlDrawElementsIndirectCommand*) (commands + i * p.stride);
                if (i >= min(count[0], p.maxDraws)) c.instanceCount = 0u;
                out[i] = c;
            }
            """;

    /** An encoded ICB and the number of commands to execute. */
    record Encoded(MTLIndirectCommandBuffer buffer, long commands) {
    }

    private record Icb(MTLIndirectCommandBuffer icb, MTLBuffer arguments) {
    }

    private final MTLDevice device;
    private final MetalHost host;
    private final MTLComputePipelineState pipeline16;
    private final MTLComputePipelineState pipeline32;
    private final MTLComputePipelineState compactPipeline;
    private final MTLArgumentEncoder argumentEncoder;
    /** ICBs by capacity in commands. */
    private final SizeClassPool<Icb> icbs;
    /** Compaction-fallback scratch, capacity in commands (20 bytes each). */
    private final SizeClassPool<MTLBuffer> compactBuffers;

    IndirectCountDraws(final MTLDevice device, final MetalHost host) {
        this.device = device;
        this.host = host;
        this.icbs = new SizeClassPool<>(1024, this::newIcb, IndirectCountDraws::freeIcb);
        this.compactBuffers = new SizeClassPool<>(1024,
                capacity -> device.newBuffer(capacity * 20L, MTLResourceOptions.StorageModePrivate), MTLBuffer::release);
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLCompileOptions options = MTLCompileOptions.alloc().init();
            options.setLanguageVersion(MTLLanguageVersion.Version3_2);
            MTLLibrary library = device.newLibraryWithSource(MSL, options);
            options.release();
            MTLFunction f16 = library.newFunctionWithName("encode_u16");
            MTLFunction f32 = library.newFunctionWithName("encode_u32");
            MTLFunction compact = library.newFunctionWithName("compact");
            compactPipeline = device.newComputePipelineStateWithFunction(compact);
            compact.release();
            pipeline16 = device.newComputePipelineStateWithFunction(f16);
            pipeline32 = device.newComputePipelineStateWithFunction(f32);
            argumentEncoder = f16.newArgumentEncoder(4);
            argumentEncoder.retain();
            f16.release();
            f32.release();
            library.release();
        } catch (NSErrorException e) {
            throw new IllegalStateException("metallium-gl: indirect-count kernel failed to compile: " + e.getMessage(), e);
        }
    }

    private Icb newIcb(final int capacity) {
        MTLIndirectCommandBufferDescriptor descriptor = MTLIndirectCommandBufferDescriptor.alloc().init();
        descriptor.setCommandTypes(MTLIndirectCommandType.DrawIndexed);
        descriptor.setInheritPipelineState(true);
        descriptor.setInheritBuffers(true);
        descriptor.setInheritDepthStencilState(true);
        descriptor.setInheritCullMode(true);
        descriptor.setInheritFrontFacingWinding(true);
        descriptor.setInheritDepthBias(true);
        descriptor.setInheritTriangleFillMode(true);
        descriptor.setMaxVertexBufferBindCount(0);
        descriptor.setMaxFragmentBufferBindCount(0);
        MTLIndirectCommandBuffer icb = device.newIndirectCommandBuffer(descriptor, capacity, 0);
        descriptor.release();
        if (icb == null) {
            throw new OutOfMemoryError("metallium-gl: newIndirectCommandBuffer(" + capacity + ") failed");
        }
        MTLBuffer arguments = device.newBuffer(Math.max(16, argumentEncoder.encodedLength()), MTLResourceOptions.StorageModeShared);
        argumentEncoder.setArgumentBuffer(arguments, 0);
        argumentEncoder.setIndirectCommandBuffer(icb, 0);
        // icbs' counters are updated after this returns: report the state including this allocation.
        dev.metallium.gl.MetalGLLog.LOGGER.info("[metallium-gl] indirect command buffer for {} draws ({} MiB; {} ICBs alive, {} allocated so far)",
                capacity, icb.allocatedSize() >> 20, icbs.live() + 1, icbs.allocations() + 1);
        return new Icb(icb, arguments);
    }

    private static void freeIcb(final Icb icb) {
        icb.arguments().release();
        icb.icb().release();
    }

    /** Pool statistics for diagnostics and tests: ICBs alive, ICB allocations so far, compaction buffers alive. */
    long[] poolStats() {
        return new long[]{icbs.live(), icbs.allocations(), icbs.liveCapacity(), compactBuffers.live(), compactBuffers.allocations()};
    }

    /**
     * Encodes the conversion of {@code maxDraws} commands into a fresh ICB on {@code encoder}; the caller executes the
     * returned ICB in its render encoder.
     */
    Encoded encode(final MTLComputeCommandEncoder encoder, final MetalGlDevice.MetalBuffer commands, final long offset, final int stride,
                   final MetalGlDevice.MetalBuffer count, final long countOffset, final int maxDraws, final MetalGlDevice.MetalBuffer indices,
                   final boolean index32, final int glPrimitive) {
        SizeClassPool.Entry<Icb> pooled = icbs.acquire(maxDraws);
        Icb icb = pooled.value();
        encoder.setComputePipelineState(index32 ? pipeline32 : pipeline16);
        encoder.setBuffer(commands.mtl(), offset, 0);
        encoder.setBuffer(count.mtl(), countOffset, 1);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment params = arena.allocate(16);
            params.setAtIndex(ValueLayout.JAVA_INT, 0, maxDraws);
            params.setAtIndex(ValueLayout.JAVA_INT, 1, stride);
            params.setAtIndex(ValueLayout.JAVA_INT, 2, metalPrimitive(glPrimitive));
            encoder.setBytes(params, 16, 2);
        }
        encoder.setBuffer(indices.mtl(), 0, 3);
        encoder.setBuffer(icb.arguments, 0, 4);
        encoder.useResource(icb.icb, MTLResourceUsage.Write);
        long groups = (maxDraws + 63) / 64;
        encoder.dispatchThreadgroups(new MTLSize(groups, 1, 1), new MTLSize(64, 1, 1));
        host.releaseAfterGpu(() -> icbs.recycle(pooled));
        return new Encoded(icb.icb, maxDraws);
    }

    /**
     * Fallback: writes {@code maxDraws} Metal indirect arguments (instanceCount 0 beyond the GPU count) into a scratch
     * buffer on {@code encoder}; the caller encodes {@code maxDraws} indirect draws from it (CPU cost per draw slot).
     */
    MTLBuffer compact(final MTLComputeCommandEncoder encoder, final MetalGlDevice.MetalBuffer commands, final long offset, final int stride,
                      final MetalGlDevice.MetalBuffer count, final long countOffset, final int maxDraws) {
        SizeClassPool.Entry<MTLBuffer> pooled = compactBuffers.acquire(Math.max(1, maxDraws));
        MTLBuffer out = pooled.value();
        encoder.setComputePipelineState(compactPipeline);
        encoder.setBuffer(commands.mtl(), offset, 0);
        encoder.setBuffer(count.mtl(), countOffset, 1);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment params = arena.allocate(16);
            params.setAtIndex(ValueLayout.JAVA_INT, 0, maxDraws);
            params.setAtIndex(ValueLayout.JAVA_INT, 1, stride);
            encoder.setBytes(params, 16, 2);
        }
        encoder.setBuffer(out, 0, 3);
        encoder.dispatchThreadgroups(new MTLSize((maxDraws + 63) / 64, 1, 1), new MTLSize(64, 1, 1));
        host.releaseAfterGpu(() -> compactBuffers.recycle(pooled));
        return out;
    }

    /** MTLPrimitiveType raw values: Point 0, Line 1, LineStrip 2, Triangle 3, TriangleStrip 4. */
    private static int metalPrimitive(final int mode) {
        return switch (mode) {
            case GL_POINTS -> 0;
            case GL_LINES -> 1;
            case GL_LINE_STRIP -> 2;
            case GL_TRIANGLE_STRIP -> 4;
            default -> 3;
        };
    }
}
