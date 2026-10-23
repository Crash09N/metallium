/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.gl.metal;

import de.nprmedia.nativium.gl.GlDevice;
import de.nprmedia.nativium.gl.MetalGLLog;
import de.nprmedia.nativium.gl.TexFormat;
import io.github.kokodio.metaljvm.foundation.NSErrorException;
import io.github.kokodio.metaljvm.metal.*;
import io.github.kokodio.metaljvm.objc.AutoreleasePool;
import io.github.kokodio.metaljvm.objc.ObjC;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL46C.*;

/**
 * {@link GlDevice} on Metal. Keeps one render encoder open while consecutive draws use the same attachments (Apple GPUs
 * are tile-based: every encoder switch stores and reloads the attachments), creates pipeline states lazily per
 * (program, attachment formats, vertex layout, blend state), and orders uploads with draws by encoding them as blits
 * in the same command buffer.
 */
public final class MetalGlDevice implements GlDevice {
    /** Vertex buffers use the top of Metal's buffer argument table; SPIRV-Cross assigns uniform buffers from 0. */
    private static final int TOP_VERTEX_BUFFER_SLOT = 30;
    private static final int MAX_SET_BYTES = 4096;

    private final MetalHost host;
    private final MTLDevice device;
    private @Nullable MTLCommandBuffer commandBuffer;
    private @Nullable MTLRenderCommandEncoder renderEncoder;
    private @Nullable MTLBlitCommandEncoder blitEncoder;
    private @Nullable MTLComputeCommandEncoder computeEncoder;
    private @Nullable IndirectCountDraws indirectCount;
    private @Nullable MTLComputePipelineState fillPipeline;
    private @Nullable RenderTargets encoderTargets;
    private @Nullable MTLFence encoderFence;
    private @Nullable MTLFence ownFence;
    private @Nullable GpuUse frameUse;
    private final Map<String, MTLRenderPipelineState> pipelines = new HashMap<>();
    private final Map<Long, MTLDepthStencilState> depthStates = new HashMap<>();
    private final Map<SamplerState, MTLSamplerState> samplers = new HashMap<>();
    private final Map<Long, MetalTexture> externalTextures = new HashMap<>();
    private @Nullable MetalTexture blackTexture;
    private @Nullable MetalProgram clearProgram;
    private int nextProgramId = 1;

    public MetalGlDevice(final MetalHost host) {
        this.host = host;
        this.device = host.device();
        this.floatFiltering = device.supports32BitFloatFiltering();
    }

    /** MTLDevice.supports32BitFloatFiltering (R32F/RG32F/RGBA32F with linear samplers). */
    private final boolean floatFiltering;

    // ---------------------------------------------------------------------------------------------- stores

    static final class MetalBuffer implements BufferStore {
        private final @Nullable MTLBuffer buffer;
        /** A view of the storage; for buffers over 2 GiB only of the first 2 GiB (callers address by memAddress + offset). */
        private final @Nullable ByteBuffer contents;
        final long size;
        @Nullable GpuUse lastUse;
        /**
         * An engine buffer (Minecraft's {@code GpuBuffer} on Nativium) given a GL name; its Metal buffer is resolved at
         * each use (the engine may swap the backing of dynamic buffers). Never released by GL, no CPU view.
         */
        final @Nullable Object engine;
        private final @Nullable MetalHost host;

        MetalBuffer(final MTLBuffer buffer, final long size) {
            this.buffer = buffer;
            this.size = size;
            this.contents = ObjC.byteBufferView(buffer.contents(), Math.min(size, Integer.MAX_VALUE & ~4095L)).order(ByteOrder.nativeOrder());
            this.engine = null;
            this.host = null;
        }

        MetalBuffer(final Object engine, final long size, final MetalHost host) {
            this.buffer = null;
            this.contents = null;
            this.size = size;
            this.engine = engine;
            this.host = host;
        }

        /** The Metal buffer (for external buffers: the engine's current one). */
        MTLBuffer mtl() {
            if (buffer != null) return buffer;
            MTLBuffer current = host.engineBuffer(engine);
            if (current == null) throw new IllegalStateException("nativium-gl: engine buffer " + engine + " has no Metal buffer");
            return current;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public ByteBuffer contents() {
            if (contents == null) {
                throw new UnsupportedOperationException("nativium-gl: CPU access to an engine buffer (GpuBuffer) through GL");
            }
            return contents;
        }

        @Override
        public boolean inUseByGpu() {
            if (engine != null) return true; // no CPU view: updates always go through the GPU
            GpuUse use = lastUse;
            if (use == null) {
                return false;
            }
            if (use.isComplete()) {
                lastUse = null;
                return false;
            }
            return true;
        }
    }

    static final class MetalTexture implements TextureStore {
        final MTLTexture texture;
        final MTLPixelFormat pixelFormat;
        final TexFormat format;
        final int width;
        final int height;
        final int levels;
        final boolean external;
        /** The engine's texture behind an external wrapper (lazy clears are applied before each use), else null. */
        @Nullable Object engine;
        /** Single-level views for storage-image bindings of mip level i (index = level). */
        @Nullable MTLTexture @Nullable [] levelViews;
        /** Views from a GL_TEXTURE_BASE_LEVEL up (key: base << 16 | last level), see {@link #sampledView}. */
        java.util.@Nullable Map<Integer, MTLTexture> baseViews;

        /** Metal texture type: 2D for GL-created textures; engine textures may also be 3D or 2D arrays. */
        MTLTextureType type = MTLTextureType.Type2D;
        /** Depth of a 3D texture (1 otherwise). */
        int depth = 1;
        /** Array slices (1 unless a 2D array / cube). */
        int slices = 1;

        MetalTexture(final MTLTexture texture, final MTLPixelFormat pixelFormat, final TexFormat format, final int width, final int height,
                     final int levels, final boolean external) {
            this.texture = texture;
            this.pixelFormat = pixelFormat;
            this.format = format;
            this.width = width;
            this.height = height;
            this.levels = levels;
            this.external = external;
        }

        @Override
        public int depth() {
            return depth;
        }

        @Override
        public int layers() {
            return slices;
        }

        @Override
        public boolean is3D() {
            return type == MTLTextureType.Type3D;
        }

        @Override
        public TexFormat format() {
            return format;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public int levels() {
            return levels;
        }
    }

    static final class MetalComputeProgram implements ProgramStore {
        final int id;
        final MTLFunction function;
        final MTLComputePipelineState pipeline;
        final MTLSize threadsPerGroup;

        MetalComputeProgram(final int id, final MTLFunction function, final MTLComputePipelineState pipeline, final int[] localSize) {
            this.id = id;
            this.function = function;
            this.pipeline = pipeline;
            this.threadsPerGroup = new MTLSize(localSize[0], localSize[1], localSize[2]);
        }
    }

    static final class MetalProgram implements ProgramStore {
        final int id;
        final MTLFunction vertex;
        final MTLFunction fragment;
        /** MSL of the vertex stage, for the GL_ZERO_TO_ONE variant (compiled on first use). */
        final @Nullable StageCode vertexCode;
        @Nullable MTLFunction vertexZeroToOne;
        /** MSL of the fragment stage, for the indirect-command-buffer variant. */
        final @Nullable StageCode fragmentCode;
        /** Variant for ICB pipelines (textures in argument buffers); built on first use, null until then. */
        @Nullable IcbVariant icb;

        MetalProgram(final int id, final MTLFunction vertex, final MTLFunction fragment, final @Nullable StageCode vertexCode,
                     final @Nullable StageCode fragmentCode) {
            this.id = id;
            this.vertex = vertex;
            this.fragment = fragment;
            this.vertexCode = vertexCode;
            this.fragmentCode = fragmentCode;
        }
    }

    /**
     * A program's functions for pipelines executed from an indirect command buffer: stages that take textures are
     * rewritten to read them from an argument buffer ({@link ArgumentBufferTextures}); a null table means the stage is
     * used as is. {@code supported == false}: the rewrite was not possible (the caller keeps the per-slot fallback).
     */
    static final class IcbVariant {
        final boolean supported;
        final @Nullable MTLFunction vertex;
        final @Nullable MTLFunction fragment;
        final ArgumentBufferTextures.@Nullable Rewritten vertexTable;
        final ArgumentBufferTextures.@Nullable Rewritten fragmentTable;
        @Nullable MTLFunction vertexZeroToOne;

        IcbVariant(final boolean supported, final @Nullable MTLFunction vertex, final @Nullable MTLFunction fragment,
                   final ArgumentBufferTextures.@Nullable Rewritten vertexTable, final ArgumentBufferTextures.@Nullable Rewritten fragmentTable) {
            this.supported = supported;
            this.vertex = vertex;
            this.fragment = fragment;
            this.vertexTable = vertexTable;
            this.fragmentTable = fragmentTable;
        }
    }

    /** MDI-count calls executed as an ICB / through the per-slot fallback (diagnostics: status line, tests). */
    public static final java.util.concurrent.atomic.AtomicLong ICB_CALLS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong SLOT_FALLBACK_CALLS = new java.util.concurrent.atomic.AtomicLong();
    /** CPU-encoded draws of the per-slot fallback (one per maxdrawcount slot), and CPU time spent in drawIndirect. */
    public static final java.util.concurrent.atomic.AtomicLong SLOT_FALLBACK_DRAWS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong INDIRECT_ENCODE_NS = new java.util.concurrent.atomic.AtomicLong();
    /** Mid-frame submit-and-wait (readbacks, glFinish, waiting fences of the frame being recorded) and time spent in them. */
    public static final java.util.concurrent.atomic.AtomicLong GPU_WAITS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong GPU_WAIT_NS = new java.util.concurrent.atomic.AtomicLong();

    /** Indirect-command-buffer pool state (diagnostics). */
    public static String icbPoolStats() {
        return IndirectCountDraws.stats();
    }

    private boolean submitAndWait() {
        long start = System.nanoTime();
        try {
            return host.submitAndWait();
        } finally {
            GPU_WAITS.incrementAndGet();
            GPU_WAIT_NS.addAndGet(System.nanoTime() - start);
        }
    }

    /** -Dnativium.gl.icbTextures=false:textured MDI-count draws use the per-slot fallback (A/B benchmark). */
    private static final boolean ICB_TEXTURES = !"false".equals(System.getProperty("nativium.gl.icbTextures"));

    /** The ICB variant of {@code program} (built once). */
    private IcbVariant icbVariant(final MetalProgram program) {
        if (program.icb != null) {
            return program.icb;
        }
        IcbVariant variant = new IcbVariant(false, null, null, null, null);
        if (ICB_TEXTURES && program.vertexCode != null && program.fragmentCode != null) {
            ArgumentBufferTextures.Rewritten vs = ArgumentBufferTextures.rewrite(program.vertexCode.msl(), program.vertexCode.entryPoint());
            ArgumentBufferTextures.Rewritten fs = ArgumentBufferTextures.rewrite(program.fragmentCode.msl(), program.fragmentCode.entryPoint());
            boolean vsTextures = program.vertexCode.msl().contains("[[texture(") || program.vertexCode.msl().contains("[[sampler(");
            boolean fsTextures = program.fragmentCode.msl().contains("[[texture(") || program.fragmentCode.msl().contains("[[sampler(");
            if ((vs != null || !vsTextures) && (fs != null || !fsTextures)) {
                try {
                    variant = new IcbVariant(true,
                            vs == null ? program.vertex : function("program" + program.id + ".vsh.icb", new StageCode(vs.msl(), program.vertexCode.entryPoint())),
                            fs == null ? program.fragment : function("program" + program.id + ".fsh.icb", new StageCode(fs.msl(), program.fragmentCode.entryPoint())),
                            vs, fs);
                } catch (DeviceException e) {
                    MetalGLLog.warn("ICB variant of program " + program.id + ": " + e.getMessage());
                }
            }
            if (!variant.supported) {
                MetalGLLog.warn("program " + program.id + ": textures cannot move to an argument buffer; MDI-count draws use the per-slot fallback");
            }
        }
        program.icb = variant;
        return variant;
    }

    /** The ICB variant's vertex function for the current glClipControl depth mode. */
    private MTLFunction icbVertexFunction(final MetalProgram program, final IcbVariant variant, final boolean zeroToOne) {
        if (variant.vertexTable == null) {
            return vertexFunction(program, zeroToOne);
        }
        if (!zeroToOne) {
            return variant.vertex;
        }
        if (variant.vertexZeroToOne == null) {
            String msl = DEPTH_FIXUP.matcher(variant.vertexTable.msl()).replaceAll("");
            try {
                variant.vertexZeroToOne = msl.equals(variant.vertexTable.msl()) ? variant.vertex
                        : function("program" + program.id + ".vsh.icb.zeroToOne", new StageCode(msl, program.vertexCode.entryPoint()));
            } catch (DeviceException e) {
                MetalGLLog.warn(e.getMessage());
                variant.vertexZeroToOne = variant.vertex;
            }
        }
        return variant.vertexZeroToOne;
    }

    /** SPIRV-Cross's GL clip-space depth remap ({@code FIXUP_DEPTH_CONVENTION}) in a vertex entry point. */
    private static final java.util.regex.Pattern DEPTH_FIXUP = java.util.regex.Pattern.compile(
            "\\n[ \\t]*out\\.gl_Position\\.z = \\(out\\.gl_Position\\.z \\+ out\\.gl_Position\\.w\\) \\* 0\\.5;[^\\n]*");

    /** The vertex function for the current glClipControl depth mode (GL_ZERO_TO_ONE: without the remap). */
    private MTLFunction vertexFunction(final MetalProgram program, final boolean zeroToOne) {
        if (!zeroToOne || program.vertexCode == null) {
            return program.vertex;
        }
        if (program.vertexZeroToOne == null) {
            String msl = DEPTH_FIXUP.matcher(program.vertexCode.msl()).replaceAll("");
            try {
                program.vertexZeroToOne = msl.equals(program.vertexCode.msl()) ? program.vertex
                        : function("program" + program.id + ".vsh.zeroToOne", new StageCode(msl, program.vertexCode.entryPoint()));
            } catch (DeviceException e) {
                MetalGLLog.warn(e.getMessage());
                program.vertexZeroToOne = program.vertex;
            }
        }
        return program.vertexZeroToOne;
    }

    @Override
    public boolean engineDepthZeroToOne() {
        return host.engineDepthZeroToOne();
    }

    @Override
    public String name() {
        return host.name();
    }

    @Override
    public BufferStore createBuffer(final long size) {
        MTLBuffer buffer = device.newBuffer(size, MTLResourceOptions.StorageModeShared);
        if (buffer == null) {
            throw new OutOfMemoryError("nativium-gl: newBuffer(" + size + ") failed");
        }
        MetalBuffer store = new MetalBuffer(buffer, size);
        if (size <= (256L << 20)) {
            // GL leaves uninitialised storage undefined; zero it anyway (cheap). Large buffers come zero-filled from the VM.
            MemoryUtil.memSet(MemoryUtil.memAddress(store.contents()), 0, size);
        }
        return store;
    }

    @Override
    public TextureStore createTexture(final TexFormat format, final int width, final int height, final int levels) {
        MTLPixelFormat pixelFormat = pixelFormat(format);
        MTLTexture texture;
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLTextureDescriptor descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat, width, height, false);
            descriptor.setMipmapLevelCount(levels);
            // ShaderWrite: storage images (glBindImageTexture); depth formats cannot be shader-written on Metal.
            descriptor.setUsage(MTLTextureUsage.ShaderRead | MTLTextureUsage.RenderTarget | (format.isDepth() ? 0 : MTLTextureUsage.ShaderWrite));
            descriptor.setStorageMode(MTLStorageMode.Private);
            texture = device.newTexture(descriptor);
        }
        if (texture == null) {
            throw new OutOfMemoryError("nativium-gl: newTexture failed");
        }
        return new MetalTexture(texture, pixelFormat, format, width, height, levels, false);
    }

    static MTLPixelFormat pixelFormat(final TexFormat format) {
        return switch (format) {
            case RGBA8 -> MTLPixelFormat.RGBA8Unorm;
            case SRGB8_ALPHA8 -> MTLPixelFormat.RGBA8Unorm_sRGB;
            case R8 -> MTLPixelFormat.R8Unorm;
            case RG8 -> MTLPixelFormat.RG8Unorm;
            case R16F -> MTLPixelFormat.R16Float;
            case RG16F -> MTLPixelFormat.RG16Float;
            case RGBA16F -> MTLPixelFormat.RGBA16Float;
            case R32F -> MTLPixelFormat.R32Float;
            case RG32F -> MTLPixelFormat.RG32Float;
            case RGBA32F -> MTLPixelFormat.RGBA32Float;
            case DEPTH32F -> MTLPixelFormat.Depth32Float;
            case DEPTH32F_STENCIL8 -> MTLPixelFormat.Depth32Float_Stencil8;
            case R32UI -> MTLPixelFormat.R32Uint;
            case R32I -> MTLPixelFormat.R32Sint;
        };
    }

    @Override
    public void writeTexture(final TextureStore store, final int level, final int x, final int y, final int width, final int height,
                             final ByteBuffer pixels, final int bytesPerRow) {
        MetalTexture texture = (MetalTexture) store;
        long size = (long) bytesPerRow * height;
        MTLBuffer staging = device.newBuffer(size, MTLResourceOptions.StorageModeShared);
        MemoryUtil.memCopy(MemoryUtil.memAddress(pixels), MemoryUtil.memAddress(ObjC.byteBufferView(staging.contents(), size)), size);
        MTLBlitCommandEncoder blit = blitEncoder();
        blit.copyFromBuffer(staging, 0, bytesPerRow, size, new MTLSize(width, height, 1), texture.texture, 0, level, new MTLOrigin(x, y, 0));
        host.releaseAfterGpu(staging::release);
    }

    @Override
    public void copyTexture(final TextureStore source, final TextureStore destination, final int levels) {
        MetalTexture src = (MetalTexture) source;
        MetalTexture dst = (MetalTexture) destination;
        MTLBlitCommandEncoder blit = blitEncoder();
        for (int level = 0; level < Math.min(levels, Math.min(src.levels, dst.levels)); level++) {
            int w = Math.max(1, src.width >> level);
            int h = Math.max(1, src.height >> level);
            blit.copyFromTexture(src.texture, 0, level, new MTLOrigin(0, 0, 0), new MTLSize(w, h, 1), dst.texture, 0, level, new MTLOrigin(0, 0, 0));
        }
    }

    @Override
    public void generateMipmaps(final TextureStore texture) {
        blitEncoder().generateMipmapsForTexture(((MetalTexture) texture).texture);
    }

    @Override
    public ProgramStore createProgram(final String label, final StageCode vertex, final StageCode fragment) throws DeviceException {
        return new MetalProgram(nextProgramId++, function(label + ".vsh", vertex), function(label + ".fsh", fragment), vertex, fragment);
    }

    private MTLFunction function(final String label, final StageCode code) throws DeviceException {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLCompileOptions options = MTLCompileOptions.alloc().init();
            options.setLanguageVersion(MTLLanguageVersion.Version3_2);
            MTLLibrary library = device.newLibraryWithSource(code.msl(), options);
            options.release();
            MTLFunction function = library.newFunctionWithName(code.entryPoint());
            library.release();
            if (function == null) {
                throw new DeviceException(label + ": MSL entry point " + code.entryPoint() + " not found");
            }
            return function;
        } catch (NSErrorException e) {
            throw new DeviceException(label + ": MSL compile failed: " + e.getMessage());
        }
    }

    @Override
    public ProgramStore createComputeProgram(final String label, final StageCode compute, final int[] localSize) throws DeviceException {
        MTLFunction function = function(label + ".csh", compute);
        try {
            MTLComputePipelineState pipeline = device.newComputePipelineStateWithFunction(function);
            if (pipeline == null) {
                throw new DeviceException(label + ": newComputePipelineState returned nil");
            }
            long threads = (long) localSize[0] * localSize[1] * localSize[2];
            if (threads > pipeline.maxTotalThreadsPerThreadgroup()) {
                throw new DeviceException(label + ": workgroup of " + threads + " threads exceeds " + pipeline.maxTotalThreadsPerThreadgroup());
            }
            return new MetalComputeProgram(nextProgramId++, function, pipeline, localSize);
        } catch (NSErrorException e) {
            throw new DeviceException(label + ": compute pipeline failed: " + e.getMessage());
        }
    }

    @Override
    public @Nullable RenderTargets defaultFramebuffer() {
        MTLTexture[] textures = host.defaultFramebuffer();
        if (textures == null || textures[0] == null) {
            return null;
        }
        MetalTexture color = external(textures[0]);
        MetalTexture depth = textures.length > 1 && textures[1] != null ? external(textures[1]) : null;
        return new RenderTargets(new TextureStore[]{color}, new int[]{0}, depth, 0,
                depth != null && depth.pixelFormat == MTLPixelFormat.Depth32Float_Stencil8);
    }

    private MetalTexture external(final MTLTexture texture) {
        MetalTexture cached = externalTextures.get(texture.handle());
        if (cached != null && cached.width == texture.width() && cached.height == texture.height()) {
            return cached;
        }
        MTLPixelFormat format = texture.pixelFormat();
        TexFormat tex = TexFormat.RGBA8;
        for (TexFormat candidate : TexFormat.values()) {
            if (pixelFormat(candidate) == format) {
                tex = candidate;
                break;
            }
        }
        MetalTexture wrapper = new MetalTexture(texture, format, tex, (int) texture.width(), (int) texture.height(), (int) texture.mipmapLevelCount(), true);
        wrapper.type = texture.textureType();
        wrapper.depth = (int) Math.max(1, texture.depth());
        wrapper.slices = (int) Math.max(1, texture.arrayLength()) * (wrapper.type == MTLTextureType.TypeCube || wrapper.type == MTLTextureType.TypeCubeArray ? 6 : 1);
        if (externalTextures.size() > 16) {
            externalTextures.clear();
        }
        externalTextures.put(texture.handle(), wrapper);
        return wrapper;
    }

    // ---------------------------------------------------------------------------------------------- encoders

    /**
     * Orders this device's consecutive encoders when the host has no fence of its own (headless). Metal's automatic
     * hazard tracking alone does not reliably order a compute pass with texture atomics against the blit passes around
     * it (clear before, readback after): without a fence a readback saw a 3D R32UI image before / while the atomics ran
     * (about 1 in 4 runs under GPU load). Every encoder waits for this fence at its start and updates it at its end.
     */
    private MTLFence ownFence() {
        if (ownFence == null) {
            ownFence = device.newFence();
        }
        return ownFence;
    }

    private MTLCommandBuffer commandBuffer() {
        if (commandBuffer == null) {
            commandBuffer = host.begin(this::interrupted);
            MTLFence hostFence = host.fence();
            encoderFence = hostFence != null ? hostFence : ownFence();
            frameUse = host.currentUse();
        }
        return commandBuffer;
    }

    /** The host is about to encode: close our encoder; the next GL operation begins again. */
    private void interrupted() {
        endEncoders();
        commandBuffer = null;
    }

    private void endEncoders() {
        if (renderEncoder != null) {
            if (encoderFence != null) {
                renderEncoder.updateFence(encoderFence, MTLRenderStages.Vertex | MTLRenderStages.Fragment);
            }
            renderEncoder.endEncoding();
            renderEncoder.release();
            renderEncoder = null;
            encoderTargets = null;
        }
        if (blitEncoder != null) {
            if (encoderFence != null) {
                blitEncoder.updateFence(encoderFence);
            }
            blitEncoder.endEncoding();
            blitEncoder.release();
            blitEncoder = null;
        }
        if (computeEncoder != null) {
            if (encoderFence != null) {
                computeEncoder.updateFence(encoderFence);
            }
            computeEncoder.endEncoding();
            computeEncoder.release();
            computeEncoder = null;
        }
    }

    /** One serial compute encoder for consecutive dispatches: each dispatch sees the previous one's writes. */
    MTLComputeCommandEncoder computeEncoder() {
        if (computeEncoder != null) {
            return computeEncoder;
        }
        endEncoders();
        MTLCommandBuffer buffer = commandBuffer();
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            computeEncoder = buffer.computeCommandEncoder();
            computeEncoder.retain();
        }
        if (encoderFence != null) {
            computeEncoder.waitForFence(encoderFence);
        }
        return computeEncoder;
    }

    private MTLBlitCommandEncoder blitEncoder() {
        if (blitEncoder != null) {
            return blitEncoder;
        }
        endEncoders();
        MTLCommandBuffer buffer = commandBuffer();
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            blitEncoder = buffer.blitCommandEncoder();
            blitEncoder.retain();
        }
        if (encoderFence != null) {
            blitEncoder.waitForFence(encoderFence);
        }
        return blitEncoder;
    }

    private static boolean sameTargets(final @Nullable RenderTargets a, final RenderTargets b) {
        if (a == null || a.colors().length != b.colors().length || a.depth() != b.depth() || a.depthLevel() != b.depthLevel()) {
            return false;
        }
        for (int i = 0; i < b.colors().length; i++) {
            if (a.colors()[i] != b.colors()[i] || a.colorLevels()[i] != b.colorLevels()[i]) {
                return false;
            }
        }
        return true;
    }

    private MTLRenderCommandEncoder renderEncoder(final RenderTargets targets, final @Nullable ClearOp clear) {
        // First: applying an engine texture's lazy clear makes the engine encode (which closes our encoder).
        for (TextureStore color : targets.colors()) {
            if (color != null) prepare((MetalTexture) color, true);
        }
        if (targets.depth() != null) prepare((MetalTexture) targets.depth(), true);
        if (renderEncoder != null && clear == null && sameTargets(encoderTargets, targets)) {
            return renderEncoder;
        }
        endEncoders();
        MTLCommandBuffer buffer = commandBuffer();
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLRenderPassDescriptor pass = MTLRenderPassDescriptor.renderPassDescriptor();
            for (int i = 0; i < targets.colors().length; i++) {
                MetalTexture color = (MetalTexture) targets.colors()[i];
                if (color == null) continue;
                MTLRenderPassColorAttachmentDescriptor attachment = pass.colorAttachments().objectAtIndexedSubscript(i);
                attachment.setTexture(color.texture);
                attachment.setLevel(targets.colorLevels()[i]);
                boolean clearIt = clear != null && clear.color();
                attachment.setLoadAction(clearIt ? MTLLoadAction.Clear : MTLLoadAction.Load);
                attachment.setStoreAction(MTLStoreAction.Store);
                if (clearIt) {
                    float[] c = clear.rgba();
                    attachment.setClearColor(new MTLClearColor(c[0], c[1], c[2], c[3]));
                }
            }
            MetalTexture depth = (MetalTexture) targets.depth();
            if (depth != null) {
                MTLRenderPassDepthAttachmentDescriptor attachment = pass.depthAttachment();
                attachment.setTexture(depth.texture);
                attachment.setLevel(targets.depthLevel());
                boolean clearIt = clear != null && clear.depth();
                attachment.setLoadAction(clearIt ? MTLLoadAction.Clear : MTLLoadAction.Load);
                attachment.setStoreAction(MTLStoreAction.Store);
                if (clearIt) {
                    attachment.setClearDepth(clear.depthValue());
                }
                if (targets.depthHasStencil()) {
                    MTLRenderPassStencilAttachmentDescriptor stencil = pass.stencilAttachment();
                    stencil.setTexture(depth.texture);
                    stencil.setLevel(targets.depthLevel());
                    boolean clearStencil = clear != null && clear.stencil();
                    stencil.setLoadAction(clearStencil ? MTLLoadAction.Clear : MTLLoadAction.Load);
                    stencil.setStoreAction(MTLStoreAction.Store);
                    if (clearStencil) {
                        stencil.setClearStencil(clear.stencilValue());
                    }
                }
            }
            renderEncoder = buffer.renderCommandEncoder(pass);
            renderEncoder.retain();
        }
        if (encoderFence != null) {
            renderEncoder.waitForFence(encoderFence, MTLRenderStages.Vertex | MTLRenderStages.Fragment);
        }
        encoderTargets = targets;
        return renderEncoder;
    }

    // ---------------------------------------------------------------------------------------------- clear

    @Override
    public void clear(final RenderTargets targets, final ClearOp op) {
        if (GlTrace.on()) GlTrace.log("clear " + GlTrace.targets(targets) + " color=" + op.color() + java.util.Arrays.toString(op.rgba())
                + " depth=" + op.depth() + "(" + op.depthValue() + ") stencil=" + op.stencil() + " scissor=" + java.util.Arrays.toString(op.scissor()));
        boolean fullMask = op.colorMask()[0] && op.colorMask()[1] && op.colorMask()[2] && op.colorMask()[3];
        boolean fullArea = op.scissor() == null || coversTarget(op.scissor(), targets);
        if (fullArea && (fullMask || !op.color())) {
            // Load-action clear: free on tile-based GPUs.
            renderEncoder(targets, op);
            return;
        }
        clearWithDraw(targets, op);
    }

    private static boolean coversTarget(final int[] scissor, final RenderTargets targets) {
        return scissor[0] <= 0 && scissor[1] <= 0 && scissor[0] + scissor[2] >= targets.width() && scissor[1] + scissor[3] >= targets.height();
    }

    /** Scissored or colour-masked clear: a full-screen triangle that writes the clear values. */
    private void clearWithDraw(final RenderTargets targets, final ClearOp op) {
        MTLRenderCommandEncoder encoder = renderEncoder(targets, null);
        MetalProgram program = clearProgram();
        StringBuilder key = new StringBuilder("clear");
        MTLRenderPipelineState pipeline = pipelines.get(pipelineKey(key, program, targets, op));
        if (pipeline == null) {
            pipeline = buildClearPipeline(program, targets, op);
            pipelines.put(key.toString(), pipeline);
        }
        encoder.setRenderPipelineState(pipeline);
        encoder.setViewport(new MTLViewport(0, 0, targets.width(), targets.height(), 0, 1));
        int[] s = op.scissor();
        encoder.setScissorRect(s == null ? new MTLScissorRect(0, 0, targets.width(), targets.height()) : clampScissor(s, targets));
        encoder.setCullMode(MTLCullMode.None);
        encoder.setTriangleFillMode(MTLTriangleFillMode.Fill);
        encoder.setDepthStencilState(depthState(op.depth(), GL_ALWAYS, op.depth()));
        try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            MemorySegment values = arena.allocate(32);
            float[] c = op.rgba();
            for (int i = 0; i < 4; i++) values.setAtIndex(java.lang.foreign.ValueLayout.JAVA_FLOAT, i, c[i]);
            values.setAtIndex(java.lang.foreign.ValueLayout.JAVA_FLOAT, 4, op.depthValue());
            encoder.setVertexBytes(values, 32, 0);
            encoder.setFragmentBytes(values, 32, 0);
        }
        encoder.drawPrimitives(MTLPrimitiveType.Triangle, 0, 3);
        if (op.stencil()) {
            MetalGLLog.unsupported("scissored stencil clear");
        }
    }

    private String pipelineKey(final StringBuilder key, final MetalProgram program, final RenderTargets targets, final ClearOp op) {
        key.append(program.id);
        for (TextureStore t : targets.colors()) key.append(':').append(t == null ? "-" : ((MetalTexture) t).pixelFormat.name());
        key.append('|').append(targets.depth() == null ? "-" : ((MetalTexture) targets.depth()).pixelFormat.name());
        boolean[] m = op.colorMask();
        key.append(op.color()).append(m[0]).append(m[1]).append(m[2]).append(m[3]);
        return key.toString();
    }

    private MTLRenderPipelineState buildClearPipeline(final MetalProgram program, final RenderTargets targets, final ClearOp op) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLRenderPipelineDescriptor descriptor = MTLRenderPipelineDescriptor.alloc().init();
            descriptor.setVertexFunction(program.vertex);
            descriptor.setFragmentFunction(program.fragment);
            long mask = !op.color() ? MTLColorWriteMask.None
                    : (op.colorMask()[0] ? MTLColorWriteMask.Red : 0) | (op.colorMask()[1] ? MTLColorWriteMask.Green : 0)
                    | (op.colorMask()[2] ? MTLColorWriteMask.Blue : 0) | (op.colorMask()[3] ? MTLColorWriteMask.Alpha : 0);
            for (int i = 0; i < targets.colors().length; i++) {
                MetalTexture color = (MetalTexture) targets.colors()[i];
                if (color == null) continue;
                MTLRenderPipelineColorAttachmentDescriptor attachment = descriptor.colorAttachments().objectAtIndexedSubscript(i);
                attachment.setPixelFormat(color.pixelFormat);
                attachment.setWriteMask(mask);
            }
            setDepthFormats(descriptor, targets);
            MTLRenderPipelineState state = newPipeline(descriptor);
            descriptor.release();
            return state;
        }
    }

    private MetalProgram clearProgram() {
        if (clearProgram == null) {
            StringBuilder msl = new StringBuilder("""
                    #include <metal_stdlib>
                    using namespace metal;
                    struct ClearValues { float4 color; float depth; };
                    struct VOut { float4 position [[position]]; };
                    vertex VOut clear_vs(uint vid [[vertex_id]], constant ClearValues& v [[buffer(0)]]) {
                        float2 p = float2((vid << 1) & 2, vid & 2);
                        VOut o; o.position = float4(p * 2.0 - 1.0, v.depth, 1.0); return o;
                    }
                    struct FOut {
                    """);
            for (int i = 0; i < 8; i++) msl.append("    float4 c").append(i).append(" [[color(").append(i).append(")]];\n");
            msl.append("""
                    };
                    fragment FOut clear_fs(constant ClearValues& v [[buffer(0)]]) {
                        FOut o;
                    """);
            for (int i = 0; i < 8; i++) msl.append("    o.c").append(i).append(" = v.color;\n");
            msl.append("    return o;\n}\n");
            try {
                clearProgram = (MetalProgram) createProgram("nativium-gl clear", new StageCode(msl.toString(), "clear_vs"), new StageCode(msl.toString(), "clear_fs"));
            } catch (DeviceException e) {
                throw new IllegalStateException(e);
            }
        }
        return clearProgram;
    }

    // ---------------------------------------------------------------------------------------------- draw

    @Override
    public void draw(final DrawOp op) {
        if (GlTrace.on()) GlTrace.log("draw " + GlTrace.draw(op) + " first=" + op.first() + " count=" + op.count() + " inst=" + op.instanceCount()
                + " idx=" + (op.indexBuffer() != null));
        MTLRenderCommandEncoder encoder = prepareDraw(op, false);
        if (encoder == null) {
            return;
        }
        MTLPrimitiveType primitive = primitive(op.glPrimitive());
        if (op.indexBuffer() == null) {
            encoder.drawPrimitives(primitive, op.first(), op.count(), op.instanceCount(), op.baseInstance());
        } else {
            MetalBuffer index = (MetalBuffer) op.indexBuffer();
            index.lastUse = frameUse;
            encoder.drawIndexedPrimitives(primitive, op.count(), op.index32() ? MTLIndexType.UInt32 : MTLIndexType.UInt16,
                    index.mtl(), op.indexOffset(), op.instanceCount(), op.baseVertex(), op.baseInstance());
        }
        if (GlTrace.dumpNow()) dumpStats(op);
    }

    /** Diagnostics ({@code -Dnativium.gl.dump}): content statistics of the draw's first colour target and inputs. */
    private void dumpStats(final DrawOp op) {
        StringBuilder sb = new StringBuilder("  stats:");
        for (int i = 0; i < op.targets().colors().length; i++) {
            if (op.targets().colors()[i] != null) sb.append(" color").append(i).append('=').append(stats((MetalTexture) op.targets().colors()[i]));
        }
        if (op.targets().depth() != null) sb.append(" depth=").append(stats((MetalTexture) op.targets().depth()));
        TextureBinding[] textures = op.fragment().textures();
        for (int i = 0; i < textures.length; i++) {
            if (textures[i].texture() != null) sb.append(" fsTex").append(i).append('=').append(stats((MetalTexture) textures[i].texture()));
        }
        MetalGLLog.LOGGER.info("[nativium-gl trace] {}", sb);
    }

    private String stats(final MetalTexture texture) {
        if (texture.format == TexFormat.DEPTH32F_STENCIL8) {
            long size = (long) texture.width * texture.height * 4;
            MTLBuffer readback = device.newBuffer(size, MTLResourceOptions.StorageModeShared);
            blitEncoder().copyFromTexture(texture.texture, 0, 0, new MTLOrigin(0, 0, 0), new MTLSize(texture.width, texture.height, 1), readback, 0,
                    texture.width * 4L, size, 1L /* MTLBlitOptionDepthFromDepthStencil */);
            endEncoders();
            commandBuffer = null;
            submitAndWait();
            ByteBuffer data = ObjC.byteBufferView(readback.contents(), size).order(ByteOrder.nativeOrder());
            float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
            int zero = 0, one = 0, pixels = texture.width * texture.height;
            for (int i = 0; i < pixels; i++) {
                float v = data.getFloat(i * 4);
                min = Math.min(min, v);
                max = Math.max(max, v);
                if (v == 0f) zero++;
                if (v == 1f) one++;
            }
            readback.release();
            return texture.format + "[min=" + min + " max=" + max + " zeros=" + zero + " ones=" + one + "/" + pixels + "]";
        }
        int bpp = texture.format.bytesPerPixel;
        ByteBuffer data = MemoryUtil.memAlloc(texture.width * texture.height * bpp).order(ByteOrder.nativeOrder());
        try {
            if (!readTexture(texture, 0, data)) return "unreadable";
            int pixels = texture.width * texture.height;
            // Diagnostics: -Dnativium.gl.dumpDir=/dir (with -Dnativium.gl.dump) also writes each dumped texture as PNG.
            String dir = System.getProperty("nativium.gl.dumpDir");
            if (dir != null && pixels <= 4096 * 4096) {
                try {
                    java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(texture.width, texture.height, java.awt.image.BufferedImage.TYPE_INT_RGB);
                    for (int y = 0; y < texture.height; y++) for (int x = 0; x < texture.width; x++) {
                        int i = y * texture.width + x;
                        int rgb;
                        if (texture.format == TexFormat.DEPTH32F) {
                            float f = data.getFloat(i * 4);
                            int g = (int) Math.min(255, Math.max(0, Math.pow(f, 0.25) * 255));
                            rgb = f == 0f ? 0xFF0000 : (g << 16) | (g << 8) | g;
                        } else if (bpp == 4) {
                            int v = data.getInt(i * 4);
                            rgb = ((v & 0xFF) << 16) | (v & 0xFF00) | ((v >>> 16) & 0xFF);
                        } else break;
                        img.setRGB(x, texture.height - 1 - y, rgb);
                    }
                    javax.imageio.ImageIO.write(img, "png", new java.io.File(dir, "dump-" + System.nanoTime() + "-" + texture.format + ".png"));
                } catch (Exception e) {
                    MetalGLLog.LOGGER.info("dump failed", e);
                }
            }
            if (texture.format == TexFormat.DEPTH32F || texture.format == TexFormat.R32F) {
                float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
                int zero = 0, one = 0;
                for (int i = 0; i < pixels; i++) {
                    float v = data.getFloat(i * 4);
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                    if (v == 0f) zero++;
                    if (v == 1f) one++;
                }
                return texture.format + "[min=" + min + " max=" + max + " zeros=" + zero + " ones=" + one + "/" + pixels + "]";
            }
            if (bpp == 4) {
                long sumR = 0, sumG = 0, sumB = 0;
                int nonZero = 0;
                for (int i = 0; i < pixels; i++) {
                    int v = data.getInt(i * 4);
                    if (v != 0) nonZero++;
                    sumR += v & 0xFF;
                    sumG += (v >>> 8) & 0xFF;
                    sumB += (v >>> 16) & 0xFF;
                }
                return texture.format + "[nonzero=" + nonZero + "/" + pixels + " avg=" + sumR / pixels + "," + sumG / pixels + "," + sumB / pixels + "]";
            }
            return texture.format + "(not summarised)";
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    @Override
    public void drawIndirect(final DrawOp op, final IndirectDraws draws) {
        long start = System.nanoTime();
        try {
            drawIndirect0(op, draws);
        } finally {
            INDIRECT_ENCODE_NS.addAndGet(System.nanoTime() - start);
        }
    }

    private void drawIndirect0(final DrawOp op, final IndirectDraws draws) {
        if (GlTrace.on()) GlTrace.log("drawIndirect " + GlTrace.draw(op) + " draws=" + draws.drawCount() + " stride=" + draws.stride()
                + (draws.countBuffer() != null ? " gpuCount(last)=" + Integer.toUnsignedString(MemoryUtil.memGetInt(MemoryUtil.memAddress(draws.countBuffer().contents()) + draws.countOffset())) : "")
                + " firstCmd=" + GlTrace.command(draws.commands(), draws.offset()));
        MetalBuffer commands = (MetalBuffer) draws.commands();
        MetalBuffer index = (MetalBuffer) op.indexBuffer();
        if (index == null) {
            MetalGLLog.unsupported("non-indexed indirect draws");
            return;
        }
        commands.lastUse = frameUse;
        index.lastUse = frameUse;
        boolean usesTextures = op.fragment().textures().length > 0 || op.vertex().textures().length > 0
                || op.fragment().images().length > 0 || op.vertex().images().length > 0;
        if (draws.countBuffer() != null && usesTextures
                && !(op.program() instanceof MetalProgram p && icbVariant(p).supported)) {
            // Metal rejects ICB pipelines whose functions bind textures directly; the ICB variant moves them into an
            // argument buffer. Where that rewrite is not possible: GPU-side compaction of the commands, then one Metal
            // indirect draw per command slot (instanceCount 0 beyond the GPU count).
            MetalBuffer count = (MetalBuffer) draws.countBuffer();
            count.lastUse = frameUse;
            if (indirectCount == null) {
                indirectCount = new IndirectCountDraws(device, host);
            }
            SLOT_FALLBACK_CALLS.incrementAndGet();
            SLOT_FALLBACK_DRAWS.addAndGet(draws.drawCount());
            MTLBuffer compacted = indirectCount.compact(computeEncoder(), commands, draws.offset(), draws.stride(), count, draws.countOffset(),
                    draws.drawCount());
            MTLRenderCommandEncoder encoder = prepareDraw(op, false);
            if (encoder == null) {
                return;
            }
            MTLPrimitiveType primitive = primitive(op.glPrimitive());
            MTLIndexType indexType = op.index32() ? MTLIndexType.UInt32 : MTLIndexType.UInt16;
            for (int i = 0; i < draws.drawCount(); i++) {
                encoder.drawIndexedPrimitives(primitive, indexType, index.mtl(), 0, compacted, i * 20L);
            }
            if (GlTrace.dumpNow()) dumpStats(op);
            return;
        }
        if (draws.countBuffer() != null) {
            // GL's draw count lives in GPU memory: a compute pass turns the commands into an indirect command buffer
            // (draws >= count are reset), which the render encoder executes; no CPU readback, no per-draw CPU cost.
            MetalBuffer count = (MetalBuffer) draws.countBuffer();
            count.lastUse = frameUse;
            if (indirectCount == null) {
                indirectCount = new IndirectCountDraws(device, host);
            }
            ICB_CALLS.incrementAndGet();
            IndirectCountDraws.Encoded icb = indirectCount.encode(computeEncoder(), commands, draws.offset(), draws.stride(), count,
                    draws.countOffset(), draws.drawCount(), index, op.index32(), op.glPrimitive());
            MTLRenderCommandEncoder encoder = prepareDraw(op, true);
            if (encoder == null) {
                return;
            }
            encoder.useResource(index.mtl(), MTLResourceUsage.Read, MTLRenderStages.Vertex);
            encoder.executeCommandsInBuffer(icb.buffer(), new io.github.kokodio.metaljvm.foundation.NSRange(0, icb.commands()));
            if (GlTrace.dumpNow()) dumpStats(op);
            return;
        }
        MTLRenderCommandEncoder encoder = prepareDraw(op, false);
        if (encoder == null) {
            return;
        }
        MTLPrimitiveType primitive = primitive(op.glPrimitive());
        for (int i = 0; i < draws.drawCount(); i++) {
            encoder.drawIndexedPrimitives(primitive, op.index32() ? MTLIndexType.UInt32 : MTLIndexType.UInt16, index.mtl(), 0,
                    commands.mtl(), draws.offset() + (long) i * draws.stride());
        }
    }

    /** Encoder with pipeline, vertex buffers, resources and fixed-function state set; null if the draw is skipped. */
    private @Nullable MTLRenderCommandEncoder prepareDraw(final DrawOp op, final boolean indirectCommandBuffer) {
        RasterState raster = op.raster();
        if (raster.cullFace() == GL_FRONT_AND_BACK && isTriangles(op.glPrimitive())) {
            return null;
        }
        // Anything that may encode a blit (the lazily uploaded placeholder texture) must happen before the render
        // encoder is acquired: a blit ends the render encoder.
        for (StageBindings stage : new StageBindings[]{op.vertex(), op.fragment()}) {
            for (TextureBinding binding : stage.textures()) {
                if (binding.texture() == null) {
                    blackTexture();
                } else {
                    prepare((MetalTexture) binding.texture(), false);
                }
            }
            for (ImageBinding image : stage.images()) {
                if (image.texture() != null) prepare((MetalTexture) image.texture(), image.written());
            }
        }
        if (indirectCommandBuffer) {
            blackTexture(); // placeholder for unbound argument-buffer members (an upload would end the render encoder)
        }
        MTLRenderCommandEncoder encoder = renderEncoder(op.targets(), null);
        if (!(op.program() instanceof MetalProgram program)) {
            return null;
        }

        // Vertex layout: attributes reading the same buffer with the same stride/divisor share one Metal buffer slot.
        List<Slot> slots = new ArrayList<>();
        int[] attributeSlot = new int[op.attributes().length];
        long[] attributeOffset = new long[op.attributes().length];
        for (int a = 0; a < op.attributes().length; a++) {
            VertexAttrib attrib = op.attributes()[a];
            Slot slot = null;
            if (attrib.buffer() != null) {
                for (Slot s : slots) {
                    if (s.buffer == attrib.buffer() && s.stride == attrib.stride() && s.divisor == attrib.divisor()
                            && attrib.offset() >= s.base && attrib.offset() - s.base + 16 <= s.stride + 16 && attrib.offset() - s.base < s.stride) {
                        slot = s;
                        break;
                    }
                }
            }
            if (slot == null) {
                slot = new Slot(TOP_VERTEX_BUFFER_SLOT - slots.size(), (MetalBuffer) attrib.buffer(), attrib.buffer() == null ? 0 : attrib.offset(),
                        attrib.stride(), attrib.divisor(), attrib.constant());
                slots.add(slot);
            }
            attributeSlot[a] = slot.index;
            attributeOffset[a] = attrib.buffer() == null ? 0 : attrib.offset() - slot.base;
        }

        StringBuilder key = new StringBuilder();
        key.append(program.id);
        for (TextureStore t : op.targets().colors()) key.append(':').append(t == null ? "-" : ((MetalTexture) t).pixelFormat.name());
        key.append('|').append(op.targets().depth() == null ? "-" : ((MetalTexture) op.targets().depth()).pixelFormat.name()).append(op.targets().depthHasStencil());
        for (int a = 0; a < op.attributes().length; a++) {
            VertexAttrib attrib = op.attributes()[a];
            key.append("|a").append(attrib.location()).append(',').append(attrib.components()).append(',').append(attrib.glType())
                    .append(',').append(attrib.normalized()).append(attrib.integer()).append(',').append(attributeSlot[a]).append(',').append(attributeOffset[a]);
        }
        for (Slot slot : slots) key.append("|s").append(slot.index).append(',').append(slot.buffer == null ? -1 : slot.stride).append(',').append(slot.divisor);
        for (BlendState b : raster.blend()) key.append("|b").append(b);
        if (indirectCommandBuffer) key.append("|icb");
        if (raster.clipDepthZeroToOne()) key.append("|z01");
        String pipelineKey = key.toString();
        MTLRenderPipelineState pipeline = pipelines.get(pipelineKey);
        if (pipeline == null) {
            pipeline = buildPipeline(program, op, slots, attributeSlot, attributeOffset, indirectCommandBuffer);
            if (pipeline == null) {
                return null;
            }
            pipelines.put(pipelineKey, pipeline);
        }
        encoder.setRenderPipelineState(pipeline);

        try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            for (Slot slot : slots) {
                if (slot.buffer != null) {
                    encoder.setVertexBuffer(slot.buffer.mtl(), slot.base, slot.index);
                    slot.buffer.lastUse = frameUse;
                } else {
                    MemorySegment constant = arena.allocate(16);
                    float[] c = slot.constant == null ? new float[]{0, 0, 0, 1} : slot.constant;
                    for (int i = 0; i < 4; i++) constant.setAtIndex(java.lang.foreign.ValueLayout.JAVA_FLOAT, i, c[i]);
                    encoder.setVertexBytes(constant, 16, slot.index);
                }
            }
            IcbVariant icb = indirectCommandBuffer && program.icb != null && program.icb.supported ? program.icb : null;
            bindStage(encoder, op.vertex(), true, arena, icb != null ? icb.vertexTable : null);
            bindStage(encoder, op.fragment(), false, arena, icb != null ? icb.fragmentTable : null);
        }

        int[] v = raster.viewport();
        encoder.setViewport(new MTLViewport(v[0], v[1], v[2], v[3], raster.depthNear(), raster.depthFar()));
        int[] s = raster.scissor();
        encoder.setScissorRect(s == null ? new MTLScissorRect(0, 0, op.targets().width(), op.targets().height()) : clampScissor(s, op.targets()));
        encoder.setCullMode(raster.cullFace() == GL_FRONT ? MTLCullMode.Front : raster.cullFace() == GL_BACK ? MTLCullMode.Back : MTLCullMode.None);
        // The vertex stage flips Y (images are stored bottom row first, like GL), which mirrors the winding.
        encoder.setFrontFacingWinding(raster.frontFaceCcw() ? MTLWinding.Clockwise : MTLWinding.CounterClockwise);
        encoder.setTriangleFillMode(raster.wireframe() ? MTLTriangleFillMode.Lines : MTLTriangleFillMode.Fill);
        boolean hasDepth = op.targets().depth() != null;
        StencilState stencil = raster.stencil();
        encoder.setDepthStencilState(depthState(hasDepth && raster.depthTest(), raster.depthFunc(), hasDepth && raster.depthWrite(), stencil));
        if (stencil.enabled()) {
            encoder.setStencilReferenceValue(stencil.ref());
        }
        encoder.setDepthBias(raster.polygonOffset() ? raster.offsetUnits() : 0f, raster.polygonOffset() ? raster.offsetFactor() : 0f, 0f);
        float[] bc = raster.blendColor();
        encoder.setBlendColorRed(bc[0], bc[1], bc[2], bc[3]);
        return encoder;
    }

    private static final class Slot {
        final int index;
        final @Nullable MetalBuffer buffer;
        final long base;
        final int stride;
        final int divisor;
        final float @Nullable [] constant;

        Slot(final int index, final @Nullable MetalBuffer buffer, final long base, final int stride, final int divisor, final float @Nullable [] constant) {
            this.index = index;
            this.buffer = buffer;
            this.base = base;
            this.stride = stride;
            this.divisor = divisor;
            this.constant = constant;
        }
    }

    /**
     * Binds a stage's textures and samplers through its argument-buffer table (ICB pipelines): writes each member's
     * {@code MTLResourceID} and declares the textures with {@code useResource} (they are referenced indirectly).
     */
    private void bindTable(final MTLRenderCommandEncoder encoder, final StageBindings stage, final boolean vertex, final java.lang.foreign.Arena arena,
                           final ArgumentBufferTextures.Rewritten table) {
        MemorySegment ids = arena.allocate(table.size(), 8);
        long stages = vertex ? MTLRenderStages.Vertex : MTLRenderStages.Fragment;
        for (int i = 0; i < table.members().size(); i++) {
            ArgumentBufferTextures.Member member = table.members().get(i);
            long id = 0;
            if (member.sampler()) {
                MTLSamplerState sampler = null;
                for (TextureBinding binding : stage.textures()) {
                    if (binding.samplerIndex() == member.index()) {
                        MetalTexture texture = binding.texture() == null ? blackTexture() : (MetalTexture) binding.texture();
                        sampler = sampler(binding.sampler(), texture);
                        break;
                    }
                }
                id = (sampler != null ? sampler : sampler(SamplerState.DEFAULT, false)).gpuResourceID()._impl();
            } else {
                MTLTexture texture = null;
                long usage = MTLResourceUsage.Read;
                for (TextureBinding binding : stage.textures()) {
                    if (binding.textureIndex() == member.index()) {
                        texture = sampledView(binding);
                        break;
                    }
                }
                if (texture == null) {
                    for (ImageBinding binding : stage.images()) {
                        if (binding.index() == member.index()) {
                            texture = binding.texture() == null ? blackTexture().texture : levelView((MetalTexture) binding.texture(), binding.level());
                            if (binding.written()) usage |= MTLResourceUsage.Write;
                            break;
                        }
                    }
                }
                if (texture == null) texture = blackTexture().texture;
                encoder.useResource(texture, usage, stages);
                id = texture.gpuResourceID()._impl();
            }
            ids.setAtIndex(java.lang.foreign.ValueLayout.JAVA_LONG, i, id);
        }
        if (vertex) encoder.setVertexBytes(ids, table.size(), table.bufferIndex());
        else encoder.setFragmentBytes(ids, table.size(), table.bufferIndex());
    }

    private void bindStage(final MTLRenderCommandEncoder encoder, final StageBindings stage, final boolean vertex, final java.lang.foreign.Arena arena,
                           final ArgumentBufferTextures.@Nullable Rewritten table) {
        ByteBuffer uniforms = stage.uniforms();
        if (uniforms != null && stage.uniformBufferIndex() >= 0) {
            int size = uniforms.capacity();
            if (size <= MAX_SET_BYTES) {
                MemorySegment segment = MemorySegment.ofAddress(MemoryUtil.memAddress(uniforms, 0)).reinterpret(size);
                if (vertex) encoder.setVertexBytes(segment, size, stage.uniformBufferIndex());
                else encoder.setFragmentBytes(segment, size, stage.uniformBufferIndex());
            } else {
                MTLBuffer copy = device.newBuffer(size, MTLResourceOptions.StorageModeShared);
                MemoryUtil.memCopy(MemoryUtil.memAddress(uniforms, 0), MemoryUtil.memAddress(ObjC.byteBufferView(copy.contents(), size)), size);
                if (vertex) encoder.setVertexBuffer(copy, 0, stage.uniformBufferIndex());
                else encoder.setFragmentBuffer(copy, 0, stage.uniformBufferIndex());
                host.releaseAfterGpu(copy::release);
            }
        }
        if (table != null) {
            bindTable(encoder, stage, vertex, arena, table);
        }
        for (TextureBinding binding : table != null ? new TextureBinding[0] : stage.textures()) {
            MetalTexture texture = binding.texture() == null ? blackTexture() : (MetalTexture) binding.texture();
            MTLSamplerState sampler = sampler(binding.sampler(), texture);
            if (vertex) {
                encoder.setVertexTexture(sampledView(binding), binding.textureIndex());
                encoder.setVertexSamplerState(sampler, binding.samplerIndex());
            } else {
                encoder.setFragmentTexture(sampledView(binding), binding.textureIndex());
                encoder.setFragmentSamplerState(sampler, binding.samplerIndex());
            }
        }
        for (BufferBinding binding : stage.buffers()) {
            MetalBuffer buffer = binding.buffer() == null ? emptyBuffer() : (MetalBuffer) binding.buffer();
            buffer.lastUse = frameUse;
            if (vertex) encoder.setVertexBuffer(buffer.mtl(), binding.offset(), binding.index());
            else encoder.setFragmentBuffer(buffer.mtl(), binding.offset(), binding.index());
        }
        for (ImageBinding binding : table != null ? new ImageBinding[0] : stage.images()) {
            MTLTexture view = binding.texture() == null ? blackTexture().texture : levelView((MetalTexture) binding.texture(), binding.level());
            if (vertex) encoder.setVertexTexture(view, binding.index());
            else encoder.setFragmentTexture(view, binding.index());
        }
    }

    private @Nullable MetalBuffer emptyBuffer;

    /** Bound where GL has no buffer (reads return zeros instead of faulting). */
    private MetalBuffer emptyBuffer() {
        if (emptyBuffer == null) {
            emptyBuffer = (MetalBuffer) createBuffer(256);
        }
        return emptyBuffer;
    }

    /**
     * The texture as GL samples it: with GL_TEXTURE_BASE_LEVEL > 0 a view of the levels base..min(max, last) - GL's
     * sampling, textureGather, textureQueryLevels and texelFetch's lod are all relative to the base level. Voxy's HiZ
     * mip chain renders level i while sampling level i-1 through BASE_LEVEL = MAX_LEVEL = i-1; binding the whole texture
     * made every level a reduction of level 0, the occlusion test culled visible LOD nodes and far terrain showed as
     * holes / "floating islands" (owner report 2026-10-23).
     */
    MTLTexture sampledView(final TextureBinding binding) {
        MetalTexture texture = binding.texture() == null ? blackTexture() : (MetalTexture) binding.texture();
        int base = Math.min(binding.baseLevel(), texture.levels - 1);
        if (base <= 0 || !BASE_LEVEL_VIEWS) {
            return texture.texture;
        }
        int last = Math.clamp(binding.maxLevel(), base, texture.levels - 1);
        int key = base << 16 | last;
        if (texture.baseViews == null) {
            texture.baseViews = new java.util.HashMap<>();
        }
        MTLTexture view = texture.baseViews.get(key);
        if (view == null) {
            boolean volume = texture.type == MTLTextureType.Type3D;
            try (AutoreleasePool _ = AutoreleasePool.push()) {
                view = texture.texture.newTextureViewWithPixelFormat(texture.pixelFormat, texture.type,
                        new io.github.kokodio.metaljvm.foundation.NSRange(base, last - base + 1),
                        new io.github.kokodio.metaljvm.foundation.NSRange(0, volume ? 1 : texture.slices));
            }
            texture.baseViews.put(key, view);
        }
        return view;
    }

    /** -Dnativium.gl.baseLevelViews=false: sample from level 0 regardless of GL_TEXTURE_BASE_LEVEL (A/B diagnostics). */
    private static final boolean BASE_LEVEL_VIEWS = !"false".equals(System.getProperty("nativium.gl.baseLevelViews"));

    /** A view of exactly one mip level (storage images bind one level). */
    static MTLTexture levelView(final MetalTexture texture, final int level) {
        if (level == 0 && texture.levels == 1) {
            return texture.texture;
        }
        MTLTextureType viewType = texture.type == MTLTextureType.Type3D || texture.type == MTLTextureType.Type2DArray ? texture.type : MTLTextureType.Type2D;
        if (texture.levelViews == null) {
            texture.levelViews = new MTLTexture[texture.levels];
        }
        int l = Math.clamp(level, 0, texture.levels - 1);
        MTLTexture view = texture.levelViews[l];
        if (view == null) {
            try (AutoreleasePool _ = AutoreleasePool.push()) {
                view = texture.texture.newTextureViewWithPixelFormat(texture.pixelFormat, viewType,
                        new io.github.kokodio.metaljvm.foundation.NSRange(l, 1),
                        new io.github.kokodio.metaljvm.foundation.NSRange(0, viewType == MTLTextureType.Type2DArray ? texture.slices : 1));
            }
            texture.levelViews[l] = view;
        }
        return view;
    }

    /** Applies the engine's lazily recorded clear to an external texture before GL reads or writes it. */
    private void prepare(final MetalTexture texture, final boolean write) {
        if (texture.engine != null) {
            host.engineTexture(texture.engine, write);
        }
    }

    // ---------------------------------------------------------------------------------------------- compute

    @Override
    public void dispatch(final DispatchOp op) {
        if (GlTrace.on()) GlTrace.log("dispatch prog" + ((MetalComputeProgram) op.program()).id + " groups=" + op.groupsX() + "," + op.groupsY() + "," + op.groupsZ()
                + (op.indirect() != null ? " indirect" : "") + " buffers=" + op.bindings().buffers().length + " images=" + op.bindings().images().length);
        MetalComputeProgram program = (MetalComputeProgram) op.program();
        StageBindings bindings = op.bindings();
        for (TextureBinding binding : bindings.textures()) {
            if (binding.texture() == null) blackTexture();
            else prepare((MetalTexture) binding.texture(), false);
        }
        for (ImageBinding image : bindings.images()) {
            if (image.texture() != null) prepare((MetalTexture) image.texture(), image.written());
        }
        MTLComputeCommandEncoder encoder = computeEncoder();
        encoder.setComputePipelineState(program.pipeline);
        ByteBuffer uniforms = bindings.uniforms();
        if (uniforms != null && bindings.uniformBufferIndex() >= 0) {
            int size = uniforms.capacity();
            if (size <= MAX_SET_BYTES) {
                encoder.setBytes(MemorySegment.ofAddress(MemoryUtil.memAddress(uniforms, 0)).reinterpret(size), size, bindings.uniformBufferIndex());
            } else {
                MTLBuffer copy = device.newBuffer(size, MTLResourceOptions.StorageModeShared);
                MemoryUtil.memCopy(MemoryUtil.memAddress(uniforms, 0), MemoryUtil.memAddress(ObjC.byteBufferView(copy.contents(), size)), size);
                encoder.setBuffer(copy, 0, bindings.uniformBufferIndex());
                host.releaseAfterGpu(copy::release);
            }
        }
        for (TextureBinding binding : bindings.textures()) {
            MetalTexture texture = binding.texture() == null ? blackTexture() : (MetalTexture) binding.texture();
            encoder.setTexture(sampledView(binding), binding.textureIndex());
            encoder.setSamplerState(sampler(binding.sampler(), texture), binding.samplerIndex());
        }
        for (BufferBinding binding : bindings.buffers()) {
            MetalBuffer buffer = binding.buffer() == null ? emptyBuffer() : (MetalBuffer) binding.buffer();
            buffer.lastUse = frameUse;
            encoder.setBuffer(buffer.mtl(), binding.offset(), binding.index());
        }
        for (ImageBinding binding : bindings.images()) {
            encoder.setTexture(binding.texture() == null ? blackTexture().texture : levelView((MetalTexture) binding.texture(), binding.level()),
                    binding.index());
        }
        if (op.indirect() != null) {
            MetalBuffer indirect = (MetalBuffer) op.indirect();
            indirect.lastUse = frameUse;
            encoder.dispatchThreadgroupsWithIndirectBuffer(indirect.mtl(), op.indirectOffset(), program.threadsPerGroup);
        } else {
            encoder.dispatchThreadgroups(new MTLSize(op.groupsX(), op.groupsY(), op.groupsZ()), program.threadsPerGroup);
        }
    }

    // ---------------------------------------------------------------------------------------------- buffer / texture transfers

    @Override
    public void copyBuffer(final BufferStore source, final long sourceOffset, final BufferStore destination, final long destinationOffset, final long size) {
        MetalBuffer src = (MetalBuffer) source;
        MetalBuffer dst = (MetalBuffer) destination;
        src.lastUse = frameUse;
        dst.lastUse = frameUse;
        if ((sourceOffset | destinationOffset | size) % 4 != 0) {
            // Metal blits on buffers need 4-byte alignment on macOS; GL allows any. Rare: do it after the GPU.
            MetalGLLog.warn("unaligned glCopyBufferSubData (" + sourceOffset + "," + destinationOffset + "," + size + ")");
        }
        blitEncoder().copyFromBuffer(src.mtl(), sourceOffset, dst.mtl(), destinationOffset, size);
    }

    @Override
    public void fillBuffer(final BufferStore destination, final long offset, final long size, final int pattern) {
        MetalBuffer dst = (MetalBuffer) destination;
        dst.lastUse = frameUse;
        byte b = (byte) pattern;
        if (pattern == (b & 0xFF) * 0x01010101) {
            blitEncoder().fillBuffer(dst.mtl(), new io.github.kokodio.metaljvm.foundation.NSRange(offset, size), b);
            return;
        }
        MTLComputeCommandEncoder encoder = computeEncoder();
        encoder.setComputePipelineState(fillPipeline());
        encoder.setBuffer(dst.mtl(), offset, 0);
        try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            MemorySegment params = arena.allocate(8);
            params.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, 0, pattern);
            params.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, 1, (int) (size / 4));
            encoder.setBytes(params, 8, 1);
        }
        long words = size / 4;
        encoder.dispatchThreadgroups(new MTLSize((words + 255) / 256, 1, 1), new MTLSize(256, 1, 1));
    }

    private MTLComputePipelineState fillPipeline() {
        if (fillPipeline == null) {
            String msl = """
                    #include <metal_stdlib>
                    using namespace metal;
                    kernel void fill_u32(device uint* data [[buffer(0)]], constant uint2& p [[buffer(1)]], uint i [[thread_position_in_grid]]) {
                        if (i < p.y) data[i] = p.x;
                    }
                    """;
            try {
                MTLFunction function = function("nativium-gl fill", new StageCode(msl, "fill_u32"));
                fillPipeline = device.newComputePipelineStateWithFunction(function);
                function.release();
            } catch (DeviceException | NSErrorException e) {
                throw new IllegalStateException(e);
            }
        }
        return fillPipeline;
    }

    @Override
    public void uploadBuffer(final BufferStore destination, final long offset, final ByteBuffer data) {
        MetalBuffer dst = (MetalBuffer) destination;
        long size = data.remaining();
        MTLBuffer staging = device.newBuffer(Math.max(4, size), MTLResourceOptions.StorageModeShared);
        MemoryUtil.memCopy(MemoryUtil.memAddress(data), MemoryUtil.memAddress(ObjC.byteBufferView(staging.contents(), size)), size);
        dst.lastUse = frameUse;
        blitEncoder().copyFromBuffer(staging, 0, dst.mtl(), offset, size);
        host.releaseAfterGpu(staging::release);
    }

    @Override
    public boolean readBuffer(final BufferStore source, final long offset, final ByteBuffer out) {
        endEncoders();
        commandBuffer = null;
        if (!submitAndWait()) {
            return false;
        }
        ((MetalBuffer) source).lastUse = null;
        MemoryUtil.memCopy(MemoryUtil.memAddress(source.contents()) + offset, MemoryUtil.memAddress(out), out.remaining());
        return true;
    }

    @Override
    public boolean readTexture(final TextureStore store, final int level, final ByteBuffer out) {
        MetalTexture texture = (MetalTexture) store;
        prepare(texture, false);
        int w = Math.max(1, texture.width >> level);
        int h = Math.max(1, texture.height >> level);
        int d = texture.is3D() ? Math.max(1, texture.depth >> level) : 1;
        long row = (long) w * texture.format.bytesPerPixel;
        long image = row * h * d;
        long size = image * texture.slices;
        if (out.remaining() < size) {
            return false;
        }
        MTLBuffer readback = device.newBuffer(size, MTLResourceOptions.StorageModeShared);
        for (int slice = 0; slice < texture.slices; slice++) {
            blitEncoder().copyFromTexture(texture.texture, slice, level, new MTLOrigin(0, 0, 0), new MTLSize(w, h, d), readback, slice * image, row,
                    row * h);
        }
        endEncoders();
        commandBuffer = null;
        boolean ok = submitAndWait();
        if (ok) {
            MemoryUtil.memCopy(MemoryUtil.memAddress(ObjC.byteBufferView(readback.contents(), size)), MemoryUtil.memAddress(out), size);
        }
        readback.release();
        return ok;
    }

    @Override
    public void clearTexture(final TextureStore store, final int level, final ByteBuffer texel) {
        MetalTexture texture = (MetalTexture) store;
        prepare(texture, true);
        int w = Math.max(1, texture.width >> level);
        int h = Math.max(1, texture.height >> level);
        int d = texture.is3D() ? Math.max(1, texture.depth >> level) : 1;
        int bpp = texture.format.bytesPerPixel;
        long size = (long) w * h * d * bpp;
        boolean uniform = true;
        for (int b = 1; b < bpp; b++) {
            uniform &= texel.get(texel.position() + b % texel.remaining()) == texel.get(texel.position());
        }
        MTLBuffer staging;
        if (uniform) {
            // One byte value everywhere (typically zero): fill on the GPU, no CPU loop over every texel.
            staging = device.newBuffer(size, MTLResourceOptions.StorageModePrivate);
            blitEncoder().fillBuffer(staging, new io.github.kokodio.metaljvm.foundation.NSRange(0, size), texel.get(texel.position()));
        } else {
            staging = device.newBuffer(size, MTLResourceOptions.StorageModeShared);
            ByteBuffer data = ObjC.byteBufferView(staging.contents(), size);
            for (long i = 0; i < size; i += bpp) {
                for (int b = 0; b < bpp; b++) data.put((int) (i + b), texel.get(texel.position() + b % texel.remaining()));
            }
        }
        for (int slice = 0; slice < texture.slices; slice++) {
            blitEncoder().copyFromBuffer(staging, 0, (long) w * bpp, (long) w * h * bpp, new MTLSize(w, h, d), texture.texture, slice, level,
                    new MTLOrigin(0, 0, 0));
        }
        host.releaseAfterGpu(staging::release);
    }

    @Override
    public void copyTextureRegion(final TextureStore source, final int sourceLevel, final int sx, final int sy, final TextureStore destination,
                                  final int destinationLevel, final int dx, final int dy, final int width, final int height) {
        MetalTexture src = (MetalTexture) source;
        MetalTexture dst = (MetalTexture) destination;
        prepare(src, false);
        prepare(dst, true);
        blitEncoder().copyFromTexture(src.texture, 0, sourceLevel, new MTLOrigin(sx, sy, 0), new MTLSize(width, height, 1),
                dst.texture, 0, destinationLevel, new MTLOrigin(dx, dy, 0));
    }

    @Override
    public @Nullable TextureStore externalTexture(final Object engineTexture) {
        MTLTexture texture = host.engineTexture(engineTexture, false);
        if (texture == null) {
            return null;
        }
        MetalTexture wrapper = external(texture);
        wrapper.engine = engineTexture;
        return wrapper;
    }

    @Override
    public @Nullable Object nativeTexture(final TextureStore texture) {
        return texture instanceof MetalTexture metal ? metal.texture : null;
    }

    @Override
    public boolean copyTextureToBuffer(final TextureStore store, final int level, final BufferStore buffer, final long offset) {
        MetalTexture texture = (MetalTexture) store;
        MetalBuffer dst = (MetalBuffer) buffer;
        prepare(texture, false);
        int w = Math.max(1, texture.width >> level);
        int h = Math.max(1, texture.height >> level);
        int d = texture.is3D() ? Math.max(1, texture.depth >> level) : 1;
        long row = (long) w * texture.format.bytesPerPixel;
        long image = row * h * d;
        if (offset + image * texture.slices > dst.size) {
            return false;
        }
        dst.lastUse = frameUse;
        for (int slice = 0; slice < texture.slices; slice++) {
            blitEncoder().copyFromTexture(texture.texture, slice, level, new MTLOrigin(0, 0, 0), new MTLSize(w, h, d), dst.mtl(),
                    offset + slice * image, row, row * h);
        }
        return true;
    }

    @Override
    public @Nullable BufferStore externalBuffer(final Object engineBuffer) {
        long size = host.engineBufferSize(engineBuffer);
        if (size < 0 || host.engineBuffer(engineBuffer) == null) {
            return null;
        }
        return new MetalBuffer(engineBuffer, size, host);
    }

    @Override
    public @Nullable TextureStore textureView(final TextureStore store, final TexFormat format, final int minLevel, final int levels) {
        MetalTexture texture = (MetalTexture) store;
        MTLPixelFormat viewFormat = pixelFormat(format);
        if (viewFormat != texture.pixelFormat) {
            MetalGLLog.unsupported("glTextureView with a different pixel format (" + texture.pixelFormat + " as " + viewFormat + ")");
            return null;
        }
        MTLTexture view;
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            view = texture.texture.newTextureViewWithPixelFormat(viewFormat, MTLTextureType.Type2D,
                    new io.github.kokodio.metaljvm.foundation.NSRange(minLevel, levels), new io.github.kokodio.metaljvm.foundation.NSRange(0, 1));
        }
        if (view == null) {
            return null;
        }
        MetalTexture wrapper = new MetalTexture(view, viewFormat, format, Math.max(1, texture.width >> minLevel), Math.max(1, texture.height >> minLevel),
                levels, false);
        wrapper.engine = texture.engine;
        return wrapper;
    }

    @Override
    public void textureBarrier() {
        // Draws after this sample what earlier draws rendered: start a new render encoder (stores the attachments).
        if (renderEncoder != null) {
            endEncoders();
        }
    }

    /** GL samples an incomplete / unbound texture as (0, 0, 0, 1). */
    private MetalTexture blackTexture() {
        if (blackTexture == null) {
            blackTexture = (MetalTexture) createTexture(TexFormat.RGBA8, 1, 1, 1);
            ByteBuffer pixel = MemoryUtil.memAlloc(4);
            pixel.put(0, (byte) 0).put(1, (byte) 0).put(2, (byte) 0).put(3, (byte) 255);
            writeTexture(blackTexture, 0, 0, 0, 1, 1, pixel, 4);
            MemoryUtil.memFree(pixel);
        }
        return blackTexture;
    }

    private static MTLScissorRect clampScissor(final int[] s, final RenderTargets targets) {
        int x = Math.clamp(s[0], 0, targets.width());
        int y = Math.clamp(s[1], 0, targets.height());
        int w = Math.clamp(s[0] + s[2], 0, targets.width()) - x;
        int h = Math.clamp(s[1] + s[3], 0, targets.height()) - y;
        return new MTLScissorRect(x, y, Math.max(0, w), Math.max(0, h));
    }

    private @Nullable MTLRenderPipelineState buildPipeline(final MetalProgram program, final DrawOp op, final List<Slot> slots,
                                                           final int[] attributeSlot, final long[] attributeOffset, final boolean indirectCommandBuffer) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLRenderPipelineDescriptor descriptor = MTLRenderPipelineDescriptor.alloc().init();
            descriptor.setSupportIndirectCommandBuffers(indirectCommandBuffer);
            IcbVariant icb = indirectCommandBuffer ? icbVariant(program) : null;
            if (icb != null && icb.supported) {
                descriptor.setVertexFunction(icbVertexFunction(program, icb, op.raster().clipDepthZeroToOne()));
                descriptor.setFragmentFunction(icb.fragment);
            } else {
                descriptor.setVertexFunction(vertexFunction(program, op.raster().clipDepthZeroToOne()));
                descriptor.setFragmentFunction(program.fragment);
            }
            MTLVertexDescriptor vertexDescriptor = MTLVertexDescriptor.alloc().init();
            for (int a = 0; a < op.attributes().length; a++) {
                VertexAttrib attrib = op.attributes()[a];
                MTLVertexFormat format = attrib.buffer() == null ? MTLVertexFormat.Float4 : vertexFormat(attrib);
                if (format == MTLVertexFormat.Invalid) {
                    MetalGLLog.unsupported("vertex attribute format size=" + attrib.components() + " type=0x" + Integer.toHexString(attrib.glType()));
                    vertexDescriptor.release();
                    descriptor.release();
                    return null;
                }
                MTLVertexAttributeDescriptor attribute = vertexDescriptor.attributes().objectAtIndexedSubscript(attrib.location());
                attribute.setFormat(format);
                attribute.setOffset(attributeOffset[a]);
                attribute.setBufferIndex(attributeSlot[a]);
            }
            for (Slot slot : slots) {
                MTLVertexBufferLayoutDescriptor layout = vertexDescriptor.layouts().objectAtIndexedSubscript(slot.index);
                if (slot.buffer == null) {
                    layout.setStride(16);
                    layout.setStepFunction(MTLVertexStepFunction.Constant);
                    layout.setStepRate(0);
                } else {
                    layout.setStride(slot.stride);
                    layout.setStepFunction(slot.divisor > 0 ? MTLVertexStepFunction.PerInstance : MTLVertexStepFunction.PerVertex);
                    layout.setStepRate(Math.max(1, slot.divisor));
                }
            }
            descriptor.setVertexDescriptor(vertexDescriptor);
            vertexDescriptor.release();
            BlendState[] blend = op.raster().blend();
            for (int i = 0; i < op.targets().colors().length; i++) {
                MetalTexture color = (MetalTexture) op.targets().colors()[i];
                if (color == null) continue;
                MTLRenderPipelineColorAttachmentDescriptor attachment = descriptor.colorAttachments().objectAtIndexedSubscript(i);
                attachment.setPixelFormat(color.pixelFormat);
                BlendState b = blend[i];
                attachment.setWriteMask((b.writeR() ? MTLColorWriteMask.Red : 0) | (b.writeG() ? MTLColorWriteMask.Green : 0)
                        | (b.writeB() ? MTLColorWriteMask.Blue : 0) | (b.writeA() ? MTLColorWriteMask.Alpha : 0));
                attachment.setBlendingEnabled(b.enabled());
                if (b.enabled()) {
                    attachment.setSourceRGBBlendFactor(blendFactor(b.srcRgb()));
                    attachment.setDestinationRGBBlendFactor(blendFactor(b.dstRgb()));
                    attachment.setSourceAlphaBlendFactor(blendFactor(b.srcAlpha()));
                    attachment.setDestinationAlphaBlendFactor(blendFactor(b.dstAlpha()));
                    attachment.setRgbBlendOperation(blendOperation(b.eqRgb()));
                    attachment.setAlphaBlendOperation(blendOperation(b.eqAlpha()));
                }
            }
            setDepthFormats(descriptor, op.targets());
            MTLRenderPipelineState state = newPipeline(descriptor);
            descriptor.release();
            return state;
        } catch (IllegalStateException e) {
            MetalGLLog.warn("pipeline creation failed: " + e.getMessage());
            return null;
        }
    }

    private static void setDepthFormats(final MTLRenderPipelineDescriptor descriptor, final RenderTargets targets) {
        MetalTexture depth = (MetalTexture) targets.depth();
        if (depth != null) {
            descriptor.setDepthAttachmentPixelFormat(depth.pixelFormat);
            if (targets.depthHasStencil()) {
                descriptor.setStencilAttachmentPixelFormat(depth.pixelFormat);
            }
        }
    }

    private MTLRenderPipelineState newPipeline(final MTLRenderPipelineDescriptor descriptor) {
        try {
            MTLRenderPipelineState state = device.newRenderPipelineState(descriptor);
            if (state == null) {
                throw new IllegalStateException("newRenderPipelineState returned nil");
            }
            return state;
        } catch (NSErrorException e) {
            throw new IllegalStateException(e.getMessage());
        }
    }

    private MTLDepthStencilState depthState(final boolean test, final int func, final boolean write) {
        return depthState(test, func, write, StencilState.DISABLED);
    }

    private final Map<String, MTLDepthStencilState> stencilStates = new HashMap<>();

    private MTLDepthStencilState depthState(final boolean test, final int func, final boolean write, final StencilState stencil) {
        if (!stencil.enabled()) {
            long key = ((long) (test ? func : GL_ALWAYS) << 1) | (write ? 1 : 0);
            MTLDepthStencilState state = depthStates.get(key);
            if (state == null) {
                MTLDepthStencilDescriptor descriptor = MTLDepthStencilDescriptor.alloc().init();
                descriptor.setDepthCompareFunction(compare(test ? func : GL_ALWAYS));
                descriptor.setDepthWriteEnabled(write);
                state = device.newDepthStencilState(descriptor);
                descriptor.release();
                depthStates.put(key, state);
            }
            return state;
        }
        String key = (test ? func : GL_ALWAYS) + "," + write + "," + stencil.func() + "," + stencil.readMask() + "," + stencil.writeMask()
                + "," + stencil.sfail() + "," + stencil.dpfail() + "," + stencil.dppass();
        MTLDepthStencilState state = stencilStates.get(key);
        if (state == null) {
            MTLDepthStencilDescriptor descriptor = MTLDepthStencilDescriptor.alloc().init();
            descriptor.setDepthCompareFunction(compare(test ? func : GL_ALWAYS));
            descriptor.setDepthWriteEnabled(write);
            MTLStencilDescriptor s = MTLStencilDescriptor.alloc().init();
            s.setStencilCompareFunction(compare(stencil.func()));
            s.setStencilFailureOperation(stencilOp(stencil.sfail()));
            s.setDepthFailureOperation(stencilOp(stencil.dpfail()));
            s.setDepthStencilPassOperation(stencilOp(stencil.dppass()));
            s.setReadMask(stencil.readMask() & 0xFF);
            s.setWriteMask(stencil.writeMask() & 0xFF);
            descriptor.setFrontFaceStencil(s);
            descriptor.setBackFaceStencil(s);
            state = device.newDepthStencilState(descriptor);
            s.release();
            descriptor.release();
            stencilStates.put(key, state);
        }
        return state;
    }

    private static MTLStencilOperation stencilOp(final int op) {
        return switch (op) {
            case GL_ZERO -> MTLStencilOperation.Zero;
            case GL_REPLACE -> MTLStencilOperation.Replace;
            case GL_INCR -> MTLStencilOperation.IncrementClamp;
            case GL_DECR -> MTLStencilOperation.DecrementClamp;
            case GL_INCR_WRAP -> MTLStencilOperation.IncrementWrap;
            case GL_DECR_WRAP -> MTLStencilOperation.DecrementWrap;
            case GL_INVERT -> MTLStencilOperation.Invert;
            default -> MTLStencilOperation.Keep;
        };
    }

    /** The sampler for {@code texture}: GL's filters, downgraded to nearest where Metal cannot filter the format. */
    private MTLSamplerState sampler(final SamplerState s, final MetalTexture texture) {
        return sampler(filterable(texture, s) ? s : nearest(s), texture.levels > 1);
    }

    /**
     * GL filters depth textures sampled without depth comparison (and 32-bit float textures) linearly; Metal cannot
     * filter depth formats (only compare-filter them) and 32-bit floats only where the device supports it. Sampling such
     * a texture through a linear sampler returns 0 on Apple GPUs. Voxy's SSAO pass reads its LOD depth through a
     * GL_LINEAR sampler at texel centres: every LOD pixel read as "far" and the LODs never reached the screen.
     */
    private boolean filterable(final MetalTexture texture, final SamplerState s) {
        if (texture.format == null) {
            return true;
        }
        if (texture.format.isDepth()) {
            return s.compareMode() == GL_COMPARE_REF_TO_TEXTURE;
        }
        if (texture.format.kind == TexFormat.Kind.FLOAT) {
            return floatFiltering;
        }
        return true;
    }

    private static SamplerState nearest(final SamplerState s) {
        int min = switch (s.minFilter()) {
            case GL_LINEAR -> GL_NEAREST;
            case GL_LINEAR_MIPMAP_NEAREST, GL_NEAREST_MIPMAP_LINEAR, GL_LINEAR_MIPMAP_LINEAR -> GL_NEAREST_MIPMAP_NEAREST;
            default -> s.minFilter();
        };
        return new SamplerState(min, GL_NEAREST, s.wrapS(), s.wrapT(), s.wrapR(), s.minLod(), s.maxLod(), s.lodBias(),
                s.compareMode(), s.compareFunc(), 1f);
    }

    private MTLSamplerState sampler(final SamplerState s, final boolean mipmapped) {
        SamplerState key = mipmapped ? s : new SamplerState(s.minFilter(), s.magFilter(), s.wrapS(), s.wrapT(), s.wrapR(), 0f, 0f,
                s.lodBias(), s.compareMode(), s.compareFunc(), s.maxAnisotropy());
        MTLSamplerState state = samplers.get(key);
        if (state == null) {
            MTLSamplerDescriptor descriptor = MTLSamplerDescriptor.alloc().init();
            descriptor.setSupportArgumentBuffers(true); // ICB pipelines read samplers from argument buffers
            boolean minLinear = s.minFilter() == GL_LINEAR || s.minFilter() == GL_LINEAR_MIPMAP_NEAREST || s.minFilter() == GL_LINEAR_MIPMAP_LINEAR;
            descriptor.setMinFilter(minLinear ? MTLSamplerMinMagFilter.Linear : MTLSamplerMinMagFilter.Nearest);
            descriptor.setMagFilter(s.magFilter() == GL_LINEAR ? MTLSamplerMinMagFilter.Linear : MTLSamplerMinMagFilter.Nearest);
            MTLSamplerMipFilter mip = !mipmapped ? MTLSamplerMipFilter.NotMipmapped : switch (s.minFilter()) {
                case GL_NEAREST_MIPMAP_NEAREST, GL_LINEAR_MIPMAP_NEAREST -> MTLSamplerMipFilter.Nearest;
                case GL_NEAREST_MIPMAP_LINEAR, GL_LINEAR_MIPMAP_LINEAR -> MTLSamplerMipFilter.Linear;
                default -> MTLSamplerMipFilter.NotMipmapped;
            };
            descriptor.setMipFilter(mip);
            descriptor.setSAddressMode(address(s.wrapS()));
            descriptor.setTAddressMode(address(s.wrapT()));
            descriptor.setRAddressMode(address(s.wrapR()));
            descriptor.setLodMinClamp(key.minLod());
            descriptor.setLodMaxClamp(Math.max(key.minLod(), key.maxLod()));
            if (s.maxAnisotropy() > 1f) {
                descriptor.setMaxAnisotropy(Math.min(16, (long) s.maxAnisotropy()));
            }
            if (s.compareMode() == GL_COMPARE_REF_TO_TEXTURE) {
                descriptor.setCompareFunction(compare(s.compareFunc()));
            }
            state = device.newSamplerState(descriptor);
            descriptor.release();
            samplers.put(key, state);
        }
        return state;
    }

    // ---------------------------------------------------------------------------------------------- readback, sync, lifetime

    @Override
    public boolean readPixels(final RenderTargets targets, final int attachment, final int x, final int y, final int width, final int height,
                              final ByteBuffer rgba8) {
        MetalTexture texture = (MetalTexture) targets.colors()[attachment];
        if (texture == null || (texture.pixelFormat != MTLPixelFormat.RGBA8Unorm && texture.pixelFormat != MTLPixelFormat.BGRA8Unorm)) {
            return false;
        }
        long size = (long) width * height * 4;
        MTLBuffer readback = device.newBuffer(size, MTLResourceOptions.StorageModeShared);
        MTLBlitCommandEncoder blit = blitEncoder();
        blit.copyFromTexture(texture.texture, 0, targets.colorLevels()[attachment], new MTLOrigin(x, y, 0), new MTLSize(width, height, 1),
                readback, 0, (long) width * 4, size);
        endEncoders();
        commandBuffer = null;
        if (!submitAndWait()) {
            readback.release();
            return false;
        }
        ByteBuffer data = ObjC.byteBufferView(readback.contents(), size);
        boolean bgra = texture.pixelFormat == MTLPixelFormat.BGRA8Unorm;
        int at = rgba8.position();
        for (int i = 0; i < width * height; i++) {
            byte r = data.get(i * 4);
            byte g = data.get(i * 4 + 1);
            byte b = data.get(i * 4 + 2);
            byte a = data.get(i * 4 + 3);
            rgba8.put(at + i * 4, bgra ? b : r).put(at + i * 4 + 1, g).put(at + i * 4 + 2, bgra ? r : b).put(at + i * 4 + 3, a);
        }
        readback.release();
        return true;
    }

    @Override
    public GpuUse fence() {
        commandBuffer();
        return frameUse;
    }

    @Override
    public void release(final Object store) {
        switch (store) {
            case MetalBuffer buffer when buffer.engine == null -> host.releaseAfterGpu(buffer.mtl()::release);
            case MetalBuffer ignored -> {
            }
            case MetalTexture texture when !texture.external -> host.releaseAfterGpu(texture.texture::release);
            case MetalProgram program -> host.releaseAfterGpu(() -> {
                if (program.vertexZeroToOne != null && program.vertexZeroToOne != program.vertex) program.vertexZeroToOne.release();
                program.vertex.release();
                program.fragment.release();
            });
            case MetalComputeProgram program -> host.releaseAfterGpu(() -> {
                program.pipeline.release();
                program.function.release();
            });
            default -> {
            }
        }
    }

    @Override
    public void flush() {
        endEncoders();
    }

    @Override
    public boolean finish() {
        endEncoders();
        commandBuffer = null;
        return submitAndWait();
    }

    // ---------------------------------------------------------------------------------------------- GL → Metal enums

    private static boolean isTriangles(final int mode) {
        return mode == GL_TRIANGLES || mode == GL_TRIANGLE_STRIP;
    }

    private static MTLPrimitiveType primitive(final int mode) {
        return switch (mode) {
            case GL_POINTS -> MTLPrimitiveType.Point;
            case GL_LINES -> MTLPrimitiveType.Line;
            case GL_LINE_STRIP -> MTLPrimitiveType.LineStrip;
            case GL_TRIANGLE_STRIP -> MTLPrimitiveType.TriangleStrip;
            default -> MTLPrimitiveType.Triangle;
        };
    }

    static MTLVertexFormat vertexFormat(final VertexAttrib a) {
        int n = a.components();
        boolean norm = a.normalized() && !a.integer();
        return switch (a.glType()) {
            case GL_FLOAT -> pick(n, MTLVertexFormat.Float, MTLVertexFormat.Float2, MTLVertexFormat.Float3, MTLVertexFormat.Float4);
            case GL_HALF_FLOAT -> pick(n, MTLVertexFormat.Half, MTLVertexFormat.Half2, MTLVertexFormat.Half3, MTLVertexFormat.Half4);
            case GL_UNSIGNED_BYTE -> norm ? pick(n, MTLVertexFormat.UCharNormalized, MTLVertexFormat.UChar2Normalized, MTLVertexFormat.UChar3Normalized, MTLVertexFormat.UChar4Normalized)
                    : pick(n, MTLVertexFormat.UChar, MTLVertexFormat.UChar2, MTLVertexFormat.UChar3, MTLVertexFormat.UChar4);
            case GL_BYTE -> norm ? pick(n, MTLVertexFormat.CharNormalized, MTLVertexFormat.Char2Normalized, MTLVertexFormat.Char3Normalized, MTLVertexFormat.Char4Normalized)
                    : pick(n, MTLVertexFormat.Char, MTLVertexFormat.Char2, MTLVertexFormat.Char3, MTLVertexFormat.Char4);
            case GL_UNSIGNED_SHORT -> norm ? pick(n, MTLVertexFormat.UShortNormalized, MTLVertexFormat.UShort2Normalized, MTLVertexFormat.UShort3Normalized, MTLVertexFormat.UShort4Normalized)
                    : pick(n, MTLVertexFormat.UShort, MTLVertexFormat.UShort2, MTLVertexFormat.UShort3, MTLVertexFormat.UShort4);
            case GL_SHORT -> norm ? pick(n, MTLVertexFormat.ShortNormalized, MTLVertexFormat.Short2Normalized, MTLVertexFormat.Short3Normalized, MTLVertexFormat.Short4Normalized)
                    : pick(n, MTLVertexFormat.Short, MTLVertexFormat.Short2, MTLVertexFormat.Short3, MTLVertexFormat.Short4);
            case GL_UNSIGNED_INT -> pick(n, MTLVertexFormat.UInt, MTLVertexFormat.UInt2, MTLVertexFormat.UInt3, MTLVertexFormat.UInt4);
            case GL_INT -> pick(n, MTLVertexFormat.Int, MTLVertexFormat.Int2, MTLVertexFormat.Int3, MTLVertexFormat.Int4);
            default -> MTLVertexFormat.Invalid;
        };
    }

    private static MTLVertexFormat pick(final int n, final MTLVertexFormat f1, final MTLVertexFormat f2, final MTLVertexFormat f3, final MTLVertexFormat f4) {
        return switch (n) {
            case 1 -> f1;
            case 2 -> f2;
            case 3 -> f3;
            default -> f4;
        };
    }

    private static MTLBlendFactor blendFactor(final int f) {
        return switch (f) {
            case GL_ZERO -> MTLBlendFactor.Zero;
            case GL_ONE -> MTLBlendFactor.One;
            case GL_SRC_COLOR -> MTLBlendFactor.SourceColor;
            case GL_ONE_MINUS_SRC_COLOR -> MTLBlendFactor.OneMinusSourceColor;
            case GL_DST_COLOR -> MTLBlendFactor.DestinationColor;
            case GL_ONE_MINUS_DST_COLOR -> MTLBlendFactor.OneMinusDestinationColor;
            case GL_SRC_ALPHA -> MTLBlendFactor.SourceAlpha;
            case GL_ONE_MINUS_SRC_ALPHA -> MTLBlendFactor.OneMinusSourceAlpha;
            case GL_DST_ALPHA -> MTLBlendFactor.DestinationAlpha;
            case GL_ONE_MINUS_DST_ALPHA -> MTLBlendFactor.OneMinusDestinationAlpha;
            case GL_CONSTANT_COLOR -> MTLBlendFactor.BlendColor;
            case GL_ONE_MINUS_CONSTANT_COLOR -> MTLBlendFactor.OneMinusBlendColor;
            case GL_CONSTANT_ALPHA -> MTLBlendFactor.BlendAlpha;
            case GL_ONE_MINUS_CONSTANT_ALPHA -> MTLBlendFactor.OneMinusBlendAlpha;
            case GL_SRC_ALPHA_SATURATE -> MTLBlendFactor.SourceAlphaSaturated;
            case GL_SRC1_COLOR -> MTLBlendFactor.Source1Color;
            case GL_ONE_MINUS_SRC1_COLOR -> MTLBlendFactor.OneMinusSource1Color;
            case GL_SRC1_ALPHA -> MTLBlendFactor.Source1Alpha;
            case GL_ONE_MINUS_SRC1_ALPHA -> MTLBlendFactor.OneMinusSource1Alpha;
            default -> MTLBlendFactor.One;
        };
    }

    private static MTLBlendOperation blendOperation(final int e) {
        return switch (e) {
            case GL_FUNC_SUBTRACT -> MTLBlendOperation.Subtract;
            case GL_FUNC_REVERSE_SUBTRACT -> MTLBlendOperation.ReverseSubtract;
            case GL_MIN -> MTLBlendOperation.Min;
            case GL_MAX -> MTLBlendOperation.Max;
            default -> MTLBlendOperation.Add;
        };
    }

    private static MTLCompareFunction compare(final int func) {
        return switch (func) {
            case GL_NEVER -> MTLCompareFunction.Never;
            case GL_LESS -> MTLCompareFunction.Less;
            case GL_EQUAL -> MTLCompareFunction.Equal;
            case GL_LEQUAL -> MTLCompareFunction.LessEqual;
            case GL_GREATER -> MTLCompareFunction.Greater;
            case GL_NOTEQUAL -> MTLCompareFunction.NotEqual;
            case GL_GEQUAL -> MTLCompareFunction.GreaterEqual;
            default -> MTLCompareFunction.Always;
        };
    }

    private static MTLSamplerAddressMode address(final int wrap) {
        return switch (wrap) {
            case GL_CLAMP_TO_EDGE -> MTLSamplerAddressMode.ClampToEdge;
            case GL_MIRRORED_REPEAT -> MTLSamplerAddressMode.MirrorRepeat;
            case GL_CLAMP_TO_BORDER -> MTLSamplerAddressMode.ClampToBorderColor;
            case GL_MIRROR_CLAMP_TO_EDGE -> MTLSamplerAddressMode.MirrorClampToEdge;
            default -> MTLSamplerAddressMode.Repeat;
        };
    }
}
