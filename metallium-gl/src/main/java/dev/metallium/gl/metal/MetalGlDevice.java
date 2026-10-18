/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl.metal;

import dev.metallium.gl.GlDevice;
import dev.metallium.gl.MetalGLLog;
import dev.metallium.gl.TexFormat;
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
    private @Nullable RenderTargets encoderTargets;
    private @Nullable MTLFence encoderFence;
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
    }

    // ---------------------------------------------------------------------------------------------- stores

    static final class MetalBuffer implements BufferStore {
        final MTLBuffer buffer;
        final ByteBuffer contents;
        @Nullable GpuUse lastUse;

        MetalBuffer(final MTLBuffer buffer, final long size) {
            this.buffer = buffer;
            this.contents = ObjC.byteBufferView(buffer.contents(), size).order(ByteOrder.nativeOrder());
        }

        @Override
        public long size() {
            return contents.capacity();
        }

        @Override
        public ByteBuffer contents() {
            return contents;
        }

        @Override
        public boolean inUseByGpu() {
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

    static final class MetalProgram implements ProgramStore {
        final int id;
        final MTLFunction vertex;
        final MTLFunction fragment;

        MetalProgram(final int id, final MTLFunction vertex, final MTLFunction fragment) {
            this.id = id;
            this.vertex = vertex;
            this.fragment = fragment;
        }
    }

    @Override
    public String name() {
        return host.name();
    }

    @Override
    public BufferStore createBuffer(final long size) {
        MTLBuffer buffer = device.newBuffer(size, MTLResourceOptions.StorageModeShared);
        if (buffer == null) {
            throw new OutOfMemoryError("metallium-gl: newBuffer(" + size + ") failed");
        }
        MetalBuffer store = new MetalBuffer(buffer, size);
        MemoryUtil.memSet(MemoryUtil.memAddress(store.contents), 0, size);
        return store;
    }

    @Override
    public TextureStore createTexture(final TexFormat format, final int width, final int height, final int levels) {
        MTLPixelFormat pixelFormat = pixelFormat(format);
        MTLTexture texture;
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLTextureDescriptor descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat, width, height, false);
            descriptor.setMipmapLevelCount(levels);
            descriptor.setUsage(MTLTextureUsage.ShaderRead | MTLTextureUsage.RenderTarget);
            descriptor.setStorageMode(MTLStorageMode.Private);
            texture = device.newTexture(descriptor);
        }
        if (texture == null) {
            throw new OutOfMemoryError("metallium-gl: newTexture failed");
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
        return new MetalProgram(nextProgramId++, function(label + ".vsh", vertex), function(label + ".fsh", fragment));
    }

    private MTLFunction function(final String label, final StageCode code) throws DeviceException {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLLibrary library = device.newLibraryWithSource(code.msl(), null);
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
        TexFormat tex = format == MTLPixelFormat.Depth32Float ? TexFormat.DEPTH32F
                : format == MTLPixelFormat.Depth32Float_Stencil8 ? TexFormat.DEPTH32F_STENCIL8
                : format == MTLPixelFormat.RGBA16Float ? TexFormat.RGBA16F : TexFormat.RGBA8;
        MetalTexture wrapper = new MetalTexture(texture, format, tex, (int) texture.width(), (int) texture.height(), (int) texture.mipmapLevelCount(), true);
        if (externalTextures.size() > 16) {
            externalTextures.clear();
        }
        externalTextures.put(texture.handle(), wrapper);
        return wrapper;
    }

    // ---------------------------------------------------------------------------------------------- encoders

    private MTLCommandBuffer commandBuffer() {
        if (commandBuffer == null) {
            commandBuffer = host.begin(this::interrupted);
            encoderFence = host.fence();
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
                clearProgram = (MetalProgram) createProgram("metallium-gl clear", new StageCode(msl.toString(), "clear_vs"), new StageCode(msl.toString(), "clear_fs"));
            } catch (DeviceException e) {
                throw new IllegalStateException(e);
            }
        }
        return clearProgram;
    }

    // ---------------------------------------------------------------------------------------------- draw

    @Override
    public void draw(final DrawOp op) {
        RasterState raster = op.raster();
        if (raster.cullFace() == GL_FRONT_AND_BACK && isTriangles(op.glPrimitive())) {
            return;
        }
        MTLRenderCommandEncoder encoder = renderEncoder(op.targets(), null);
        MetalProgram program = (MetalProgram) op.program();

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
        String pipelineKey = key.toString();
        MTLRenderPipelineState pipeline = pipelines.get(pipelineKey);
        if (pipeline == null) {
            pipeline = buildPipeline(program, op, slots, attributeSlot, attributeOffset);
            if (pipeline == null) {
                return;
            }
            pipelines.put(pipelineKey, pipeline);
        }
        encoder.setRenderPipelineState(pipeline);

        try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            for (Slot slot : slots) {
                if (slot.buffer != null) {
                    encoder.setVertexBuffer(slot.buffer.buffer, slot.base, slot.index);
                    slot.buffer.lastUse = frameUse;
                } else {
                    MemorySegment constant = arena.allocate(16);
                    float[] c = slot.constant == null ? new float[]{0, 0, 0, 1} : slot.constant;
                    for (int i = 0; i < 4; i++) constant.setAtIndex(java.lang.foreign.ValueLayout.JAVA_FLOAT, i, c[i]);
                    encoder.setVertexBytes(constant, 16, slot.index);
                }
            }
            bindStage(encoder, op.vertex(), true, arena);
            bindStage(encoder, op.fragment(), false, arena);
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
        encoder.setDepthStencilState(depthState(hasDepth && raster.depthTest(), raster.depthFunc(), hasDepth && raster.depthWrite()));
        encoder.setDepthBias(raster.polygonOffset() ? raster.offsetUnits() : 0f, raster.polygonOffset() ? raster.offsetFactor() : 0f, 0f);
        float[] bc = raster.blendColor();
        encoder.setBlendColorRed(bc[0], bc[1], bc[2], bc[3]);

        MTLPrimitiveType primitive = primitive(op.glPrimitive());
        if (op.indexBuffer() == null) {
            encoder.drawPrimitives(primitive, op.first(), op.count(), op.instanceCount(), op.baseInstance());
        } else {
            MetalBuffer index = (MetalBuffer) op.indexBuffer();
            index.lastUse = frameUse;
            encoder.drawIndexedPrimitives(primitive, op.count(), op.index32() ? MTLIndexType.UInt32 : MTLIndexType.UInt16,
                    index.buffer, op.indexOffset(), op.instanceCount(), op.baseVertex(), op.baseInstance());
        }
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

    private void bindStage(final MTLRenderCommandEncoder encoder, final StageBindings stage, final boolean vertex, final java.lang.foreign.Arena arena) {
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
        for (TextureBinding binding : stage.textures()) {
            MetalTexture texture = binding.texture() == null ? blackTexture() : (MetalTexture) binding.texture();
            MTLSamplerState sampler = sampler(binding.sampler(), texture.levels > 1);
            if (vertex) {
                encoder.setVertexTexture(texture.texture, binding.textureIndex());
                encoder.setVertexSamplerState(sampler, binding.samplerIndex());
            } else {
                encoder.setFragmentTexture(texture.texture, binding.textureIndex());
                encoder.setFragmentSamplerState(sampler, binding.samplerIndex());
            }
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
                                                           final int[] attributeSlot, final long[] attributeOffset) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLRenderPipelineDescriptor descriptor = MTLRenderPipelineDescriptor.alloc().init();
            descriptor.setVertexFunction(program.vertex);
            descriptor.setFragmentFunction(program.fragment);
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

    private MTLSamplerState sampler(final SamplerState s, final boolean mipmapped) {
        SamplerState key = mipmapped ? s : new SamplerState(s.minFilter(), s.magFilter(), s.wrapS(), s.wrapT(), s.wrapR(), 0f, 0f,
                s.lodBias(), s.compareMode(), s.compareFunc(), s.maxAnisotropy());
        MTLSamplerState state = samplers.get(key);
        if (state == null) {
            MTLSamplerDescriptor descriptor = MTLSamplerDescriptor.alloc().init();
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
        if (!host.submitAndWait()) {
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
            case MetalBuffer buffer -> host.releaseAfterGpu(buffer.buffer::release);
            case MetalTexture texture when !texture.external -> host.releaseAfterGpu(texture.texture::release);
            case MetalProgram program -> host.releaseAfterGpu(() -> {
                program.vertex.release();
                program.fragment.release();
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
    public void finish() {
        endEncoders();
        commandBuffer = null;
        host.submitAndWait();
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
