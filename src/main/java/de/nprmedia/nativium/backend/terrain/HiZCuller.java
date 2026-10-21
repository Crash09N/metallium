/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.backend.terrain;

import de.nprmedia.nativium.Nativium;
import de.nprmedia.nativium.backend.NativiumInterop;
import io.github.kokodio.metaljvm.objc.AutoreleasePool;
import io.github.kokodio.metaljvm.foundation.NSErrorException;
import io.github.kokodio.metaljvm.foundation.NSRange;
import io.github.kokodio.metaljvm.metal.MTLBuffer;
import io.github.kokodio.metaljvm.metal.MTLCommandBuffer;
import io.github.kokodio.metaljvm.metal.MTLComputeCommandEncoder;
import io.github.kokodio.metaljvm.metal.MTLComputePipelineState;
import io.github.kokodio.metaljvm.metal.MTLDevice;
import io.github.kokodio.metaljvm.metal.MTLFunction;
import io.github.kokodio.metaljvm.metal.MTLLibrary;
import io.github.kokodio.metaljvm.metal.MTLPixelFormat;
import io.github.kokodio.metaljvm.metal.MTLResourceOptions;
import io.github.kokodio.metaljvm.metal.MTLSize;
import io.github.kokodio.metaljvm.metal.MTLStorageMode;
import io.github.kokodio.metaljvm.metal.MTLTexture;
import io.github.kokodio.metaljvm.metal.MTLTextureDescriptor;
import io.github.kokodio.metaljvm.metal.MTLTextureType;
import io.github.kokodio.metaljvm.metal.MTLTextureUsage;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * GPU occlusion test for terrain sections (own implementation; the two-phase Hi-Z idea is the standard technique, also
 * used by mcopt). Builds a max-depth pyramid from a depth attachment with compute, then tests camera-relative boxes
 * against it: a box is occluded when its nearest depth is farther than the farthest depth in the pyramid texels
 * covering its (pixel-expanded) screen rectangle. Depth convention: standard (0 near, 1 far), depth test LESS/LEQUAL.
 *
 * <p>Per frame the caller {@link #acquire acquires} a {@link Slot}, writes boxes into it, {@link #encode encodes} the
 * test and gets the slot back on the render thread once the GPU finished it. Render thread only.
 */
public final class HiZCuller implements NativiumInterop.ExternalEncoderClient {
    /** Result values in {@link Slot#vis}. */
    public static final int OCCLUDED = 0, VISIBLE = 1, OFFSCREEN = 2;
    private static final int SLOTS = 6;

    private static final String MSL = """
            #include <metal_stdlib>
            using namespace metal;

            // Level 0 of the pyramid: each texel is the max of the 2x2 depth pixels under it; the last texel of a row or
            // column also covers the remainder of an odd size, so every depth pixel is covered by exactly one texel.
            kernel void hiz_from_depth(depth2d<float, access::read> src [[texture(0)]],
                                       texture2d<float, access::write> dst [[texture(1)]],
                                       uint2 gid [[thread_position_in_grid]]) {
                uint dw = dst.get_width(), dh = dst.get_height();
                if (gid.x >= dw || gid.y >= dh) return;
                uint sw = src.get_width(), sh = src.get_height();
                uint x0 = min(gid.x * 2, sw - 1), y0 = min(gid.y * 2, sh - 1);
                uint x1 = gid.x == dw - 1 ? sw - 1 : min(x0 + 1, sw - 1);
                uint y1 = gid.y == dh - 1 ? sh - 1 : min(y0 + 1, sh - 1);
                float m = 0.0;
                for (uint y = y0; y <= y1; y++) {
                    for (uint x = x0; x <= x1; x++) {
                        m = max(m, src.read(uint2(x, y)));
                    }
                }
                dst.write(float4(m), gid);
            }

            kernel void hiz_down(texture2d<float, access::read> src [[texture(0)]],
                                 texture2d<float, access::write> dst [[texture(1)]],
                                 uint2 gid [[thread_position_in_grid]]) {
                uint dw = dst.get_width(), dh = dst.get_height();
                if (gid.x >= dw || gid.y >= dh) return;
                uint sw = src.get_width(), sh = src.get_height();
                uint x0 = min(gid.x * 2, sw - 1), y0 = min(gid.y * 2, sh - 1);
                uint x1 = gid.x == dw - 1 ? sw - 1 : min(x0 + 1, sw - 1);
                uint y1 = gid.y == dh - 1 ? sh - 1 : min(y0 + 1, sh - 1);
                float m = 0.0;
                for (uint y = y0; y <= y1; y++) {
                    for (uint x = x0; x <= x1; x++) {
                        m = max(m, src.read(uint2(x, y)).r);
                    }
                }
                dst.write(float4(m), gid);
            }

            struct CullParams {
                float4x4 viewProj;
                float2 screen;      // depth attachment size in pixels
                uint levels;        // pyramid levels
                uint count;         // boxes
                uint zeroToOne;     // clip z already in [0, w]
                float pixelMargin;  // rectangle growth in pixels (rasterisation, TAA jitter)
                uint flipY;         // 1: framebuffer row 0 is NDC y = +1
                uint pad;
            };

            kernel void hiz_test(constant CullParams& p [[buffer(0)]],
                                 const device float4* boxes [[buffer(1)]],
                                 device uint* vis [[buffer(2)]],
                                 texture2d<float, access::read> hiz [[texture(0)]],
                                 uint id [[thread_position_in_grid]]) {
                if (id >= p.count) return;
                float3 mn = boxes[id * 2].xyz;
                float3 mx = boxes[id * 2 + 1].xyz;
                float2 lo = float2(INFINITY);
                float2 hi = float2(-INFINITY);
                float zmin = INFINITY;
                for (uint c = 0; c < 8; c++) {
                    float3 v = float3((c & 1) ? mx.x : mn.x, (c & 2) ? mx.y : mn.y, (c & 4) ? mx.z : mn.z);
                    float4 clip = p.viewProj * float4(v, 1.0);
                    if (clip.w <= 0.05) { vis[id] = 1; return; } // reaches the near plane or behind the camera
                    float3 ndc = clip.xyz / clip.w;
                    lo = min(lo, ndc.xy);
                    hi = max(hi, ndc.xy);
                    zmin = min(zmin, p.zeroToOne != 0 ? ndc.z : ndc.z * 0.5 + 0.5);
                }
                if (zmin <= 0.0) { vis[id] = 1; return; }
                float2 uvLo = lo * 0.5 + 0.5;
                float2 uvHi = hi * 0.5 + 0.5;
                if (p.flipY != 0) {
                    float t = 1.0 - uvHi.y;
                    uvHi.y = 1.0 - uvLo.y;
                    uvLo.y = t;
                }
                float2 pxLo = uvLo * p.screen - p.pixelMargin;
                float2 pxHi = uvHi * p.screen + p.pixelMargin;
                if (pxHi.x < 0.0 || pxHi.y < 0.0 || pxLo.x >= p.screen.x || pxLo.y >= p.screen.y) { vis[id] = 2; return; }
                uint2 d0 = uint2(hiz.get_width(0), hiz.get_height(0));
                uint2 a = min(uint2(clamp(pxLo, float2(0.0), p.screen - 1.0)) >> 1, d0 - 1);
                uint2 b = min(uint2(clamp(pxHi, float2(0.0), p.screen - 1.0)) >> 1, d0 - 1);
                uint level = 0;
                while ((b.x - a.x > 3 || b.y - a.y > 3) && level + 1 < p.levels) {
                    level++;
                    uint2 dl = uint2(hiz.get_width(level), hiz.get_height(level));
                    a = min(a >> 1, dl - 1);
                    b = min(b >> 1, dl - 1);
                }
                float dmax = 0.0;
                for (uint y = a.y; y <= b.y; y++) {
                    for (uint x = a.x; x <= b.x; x++) {
                        dmax = max(dmax, hiz.read(uint2(x, y), level).r);
                    }
                }
                vis[id] = zmin <= dmax ? 1u : 0u;
            }

            // Phase-2 indirect draws (MTLDrawIndexedPrimitivesIndirectArguments, 5 uints): instance count 1 only if the
            // draw's section passed the test. forceAll: draw them all (measures the split without culling).
            kernel void hiz_args(const device uint* vis [[buffer(0)]],
                                 const device uint* drawEntry [[buffer(1)]],
                                 device uint* args [[buffer(2)]],
                                 constant uint2& n [[buffer(3)]],
                                 uint id [[thread_position_in_grid]]) {
                if (id >= n.x) return;
                args[id * 5 + 1] = (n.y != 0u || vis[drawEntry[id]] == 1u) ? 1u : 0u;
            }
            """;

    /** Per-frame buffers; reused once the GPU finished the frame that used them. */
    public static final class Slot {
        private MTLBuffer boxBuffer;
        private MTLBuffer visBuffer;
        private int capacity;
        /** Boxes written this frame. */
        public int count;
        /** Two float4 per box (min.xyz, max.xyz), camera-relative. Valid until {@link #encode}. */
        public MemorySegment boxes;
        /** Results (one uint per box), valid in the completion callback. */
        public MemorySegment vis;
        /** Caller's per-frame data (e.g. which section each box is). */
        public @Nullable Object userData;
        long frame;
        private com.mojang.renderpearl.api.buffers.@Nullable GpuBuffer argsBuffer;
        private @Nullable MTLBuffer drawEntryBuffer;
        private int argCapacity;
        /** Phase-2 draws written this frame. */
        public int argCount;
        /** Phase-2 indirect args, 20 bytes each: index count, instance count (set by the GPU), first index, base vertex, base instance. */
        public MemorySegment args;
        /** Test entry (box index) of each phase-2 draw. */
        public MemorySegment drawEntries;

        /** Room for {@code wanted} phase-2 draws (call before writing any this frame). */
        public void ensureArgs(final int wanted) {
            if (wanted <= argCapacity) {
                return;
            }
            int cap = Math.max(1024, Integer.highestOneBit(Math.max(1, wanted - 1)) << 1);
            releaseArgs();
            argsBuffer = com.mojang.blaze3d.systems.RenderSystem.getDevice().createBuffer(() -> "Nativium terrain cull args",
                    com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_INDIRECT_PARAMETERS | com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_MAP_WRITE,
                    (long) cap * 20L);
            args = NativiumInterop.metalBuffer(argsBuffer).contents().reinterpret((long) cap * 20L);
            drawEntryBuffer = NativiumInterop.device().newBuffer((long) cap * 4L, MTLResourceOptions.StorageModeShared);
            drawEntries = drawEntryBuffer.contents().reinterpret((long) cap * 4L);
            argCapacity = cap;
        }

        public int argCapacity() {
            return argCapacity;
        }

        /** The renderpearl buffer holding the phase-2 args (for {@code RenderPass.drawIndexedIndirect}). */
        public com.mojang.renderpearl.api.buffers.@Nullable GpuBuffer argsBuffer() {
            return argsBuffer;
        }

        /** Writes phase-2 draw {@code j} (instance count 0 until the GPU test sets it). */
        public void putDraw(final int j, final int entry, final int indexCount, final int firstIndex, final int baseVertex) {
            long o = j * 20L;
            args.set(ValueLayout.JAVA_INT, o, indexCount);
            args.set(ValueLayout.JAVA_INT, o + 4, 0);
            args.set(ValueLayout.JAVA_INT, o + 8, firstIndex);
            args.set(ValueLayout.JAVA_INT, o + 12, baseVertex);
            args.set(ValueLayout.JAVA_INT, o + 16, 0);
            drawEntries.set(ValueLayout.JAVA_INT, j * 4L, entry);
        }

        private void releaseArgs() {
            com.mojang.renderpearl.api.buffers.GpuBuffer oldArgs = argsBuffer;
            MTLBuffer oldEntries = drawEntryBuffer;
            argsBuffer = null;
            drawEntryBuffer = null;
            argCapacity = 0;
            if (oldArgs != null) {
                NativiumInterop.releaseAfterGpu(() -> {
                    oldArgs.close();
                    oldEntries.release();
                });
            }
        }

        void ensure(final MTLDevice device, final int wanted) {
            if (wanted <= capacity) {
                return;
            }
            int cap = Math.max(1024, Integer.highestOneBit(Math.max(1, wanted - 1)) << 1);
            if (boxBuffer != null) {
                boxBuffer.release();
                visBuffer.release();
            }
            long options = MTLResourceOptions.StorageModeShared;
            boxBuffer = device.newBuffer((long) cap * 32L, options);
            visBuffer = device.newBuffer((long) cap * 4L, options);
            boxes = boxBuffer.contents().reinterpret((long) cap * 32L);
            vis = visBuffer.contents().reinterpret((long) cap * 4L);
            capacity = cap;
        }

        public int capacity() {
            return capacity;
        }

        /** Writes box {@code i} (camera-relative min / max). */
        public void putBox(final int i, final float minX, final float minY, final float minZ, final float maxX, final float maxY, final float maxZ) {
            long o = i * 32L;
            boxes.set(ValueLayout.JAVA_FLOAT, o, minX);
            boxes.set(ValueLayout.JAVA_FLOAT, o + 4, minY);
            boxes.set(ValueLayout.JAVA_FLOAT, o + 8, minZ);
            boxes.set(ValueLayout.JAVA_FLOAT, o + 16, maxX);
            boxes.set(ValueLayout.JAVA_FLOAT, o + 20, maxY);
            boxes.set(ValueLayout.JAVA_FLOAT, o + 24, maxZ);
        }

        public int result(final int i) {
            return vis.get(ValueLayout.JAVA_INT, i * 4L);
        }

        void release() {
            if (boxBuffer != null) {
                boxBuffer.release();
                visBuffer.release();
                boxBuffer = visBuffer = null;
                capacity = 0;
            }
        }
    }

    private final MTLDevice device;
    private final MTLComputePipelineState fromDepth;
    private final MTLComputePipelineState down;
    private final MTLComputePipelineState test;
    private final MTLComputePipelineState argsPipeline;
    private final ArrayDeque<Slot> free = new ArrayDeque<>();
    private final Arena arena = Arena.ofAuto();
    private final MemorySegment params = arena.allocate(112, 16);
    private @Nullable MTLTexture pyramid;
    private MTLTexture @Nullable [] levelViews;
    private long pyramidWidth, pyramidHeight;
    private @Nullable MTLComputeCommandEncoder openEncoder;
    private long frames;
    /** GPU dispatches encoded since the last {@link #takeDispatches()}. */
    private int dispatches;

    private HiZCuller() {
        this.device = NativiumInterop.device();
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLLibrary library = device.newLibraryWithSource(MSL, null);
            fromDepth = pipeline(library, "hiz_from_depth");
            down = pipeline(library, "hiz_down");
            test = pipeline(library, "hiz_test");
            argsPipeline = pipeline(library, "hiz_args");
            library.release();
        } catch (NSErrorException e) {
            throw new IllegalStateException("Nativium's Hi-Z kernels failed to compile: " + e.getMessage(), e);
        }
        for (int i = 0; i < SLOTS; i++) {
            free.add(new Slot());
        }
    }

    private static @Nullable HiZCuller instance;
    private static boolean failed;

    /** The culler, created on first use; null if Metal is not active or the kernels failed to compile. */
    public static @Nullable HiZCuller get() {
        if (instance == null && !failed) {
            try {
                if (!NativiumInterop.isAvailable()) {
                    return null;
                }
                instance = new HiZCuller();
            } catch (RuntimeException | LinkageError e) {
                failed = true;
                Nativium.LOGGER.error("[nativium] terrain occlusion culling disabled: {}", e.toString());
            }
        }
        return instance;
    }

    private MTLComputePipelineState pipeline(final MTLLibrary library, final String name) {
        MTLFunction function = library.newFunctionWithName(name);
        if (function == null) {
            throw new IllegalStateException("MSL entry point " + name + " not found");
        }
        try {
            return device.newComputePipelineStateWithFunction(function);
        } finally {
            function.release();
        }
    }

    /** A free slot with room for {@code count} boxes, or null if all slots are still in flight. */
    public @Nullable Slot acquire(final int count) {
        Slot slot = free.poll();
        if (slot == null) {
            return null;
        }
        slot.ensure(device, count);
        slot.count = 0;
        slot.argCount = 0;
        slot.userData = null;
        return slot;
    }

    /** Returns a slot whose buffers the current frame uses but that was not tested, once the GPU finished the frame. */
    public void releaseLater(final Slot slot) {
        NativiumInterop.releaseAfterGpu(() -> free.add(slot));
    }

    /** Returns a slot that was acquired but not encoded. */
    public void abandon(final Slot slot) {
        free.add(slot);
    }

    /**
     * Ends the open render encoder (its attachments are stored; the next draw re-opens it with Load), builds the
     * pyramid from {@code depth} and tests the slot's boxes. {@code viewProj}: column-major camera-relative
     * view-projection. {@code done} runs on the render thread once the GPU finished (results in {@link Slot#vis});
     * the slot is free again after it returns.
     */
    public void encode(final MTLTexture depth, final Slot slot, final float[] viewProj, final boolean zeroToOne,
                       final boolean flipY, final float pixelMargin, final boolean drawAll, final Consumer<Slot> done) {
        long w = depth.width();
        long h = depth.height();
        ensurePyramid(w, h);
        MTLCommandBuffer buffer = NativiumInterop.beginExternalEncoding(this);
        MTLComputeCommandEncoder enc;
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            enc = buffer.computeCommandEncoder();
            enc.retain();
        }
        openEncoder = enc;
        enc.waitForFence(NativiumInterop.frameFence());

        MTLTexture[] views = levelViews;
        enc.setComputePipelineState(fromDepth);
        enc.setTexture(depth, 0);
        enc.setTexture(views[0], 1);
        dispatch2d(enc, views[0].width(), views[0].height());
        enc.setComputePipelineState(down);
        for (int level = 1; level < views.length; level++) {
            enc.setTexture(views[level - 1], 0);
            enc.setTexture(views[level], 1);
            dispatch2d(enc, views[level].width(), views[level].height());
        }

        for (int i = 0; i < 16; i++) {
            params.set(ValueLayout.JAVA_FLOAT, i * 4L, viewProj[i]);
        }
        params.set(ValueLayout.JAVA_FLOAT, 64, (float) w);
        params.set(ValueLayout.JAVA_FLOAT, 68, (float) h);
        params.set(ValueLayout.JAVA_INT, 72, views.length);
        params.set(ValueLayout.JAVA_INT, 76, slot.count);
        params.set(ValueLayout.JAVA_INT, 80, zeroToOne ? 1 : 0);
        params.set(ValueLayout.JAVA_FLOAT, 84, pixelMargin);
        params.set(ValueLayout.JAVA_INT, 88, flipY ? 1 : 0);
        params.set(ValueLayout.JAVA_INT, 92, 0);
        enc.setComputePipelineState(test);
        enc.setBytes(params, 96, 0);
        enc.setBuffer(slot.boxBuffer, 0, 1);
        enc.setBuffer(slot.visBuffer, 0, 2);
        enc.setTexture(pyramid, 0);
        enc.dispatchThreadgroups(new MTLSize((slot.count + 63) / 64, 1, 1), new MTLSize(64, 1, 1));
        dispatches += views.length + 1;
        if (slot.argCount > 0) {
            params.set(ValueLayout.JAVA_INT, 96, slot.argCount);
            params.set(ValueLayout.JAVA_INT, 100, drawAll ? 1 : 0);
            enc.setComputePipelineState(argsPipeline);
            enc.setBuffer(slot.visBuffer, 0, 0);
            enc.setBuffer(slot.drawEntryBuffer, 0, 1);
            enc.setBuffer(NativiumInterop.metalBuffer(slot.argsBuffer), 0, 2);
            enc.setBytes(params.asSlice(96, 8), 8, 3);
            enc.dispatchThreadgroups(new MTLSize((slot.argCount + 63) / 64, 1, 1), new MTLSize(64, 1, 1));
            dispatches++;
        }
        endExternalEncoding();

        slot.frame = ++frames;
        NativiumInterop.releaseAfterGpu(() -> {
            try {
                done.accept(slot);
            } finally {
                free.add(slot);
            }
        });
    }

    private static void dispatch2d(final MTLComputeCommandEncoder enc, final long w, final long h) {
        enc.dispatchThreadgroups(new MTLSize((w + 7) / 8, (h + 7) / 8, 1), new MTLSize(8, 8, 1));
    }

    private void ensurePyramid(final long depthWidth, final long depthHeight) {
        long w = Math.max(1, depthWidth / 2);
        long h = Math.max(1, depthHeight / 2);
        if (pyramid != null && w == pyramidWidth && h == pyramidHeight) {
            return;
        }
        releasePyramid();
        int levels = 64 - Long.numberOfLeadingZeros(Math.max(w, h));
        MTLTextureDescriptor descriptor = MTLTextureDescriptor.alloc().init();
        descriptor.setTextureType(MTLTextureType.Type2D);
        descriptor.setPixelFormat(MTLPixelFormat.R32Float);
        descriptor.setWidth(w);
        descriptor.setHeight(h);
        descriptor.setMipmapLevelCount(levels);
        descriptor.setUsage(MTLTextureUsage.ShaderRead | MTLTextureUsage.ShaderWrite | MTLTextureUsage.PixelFormatView);
        descriptor.setStorageMode(MTLStorageMode.Private);
        pyramid = device.newTexture(descriptor);
        descriptor.release();
        levelViews = new MTLTexture[levels];
        for (int level = 0; level < levels; level++) {
            levelViews[level] = pyramid.newTextureViewWithPixelFormat(MTLPixelFormat.R32Float, MTLTextureType.Type2D,
                    new NSRange(level, 1), new NSRange(0, 1));
        }
        pyramidWidth = w;
        pyramidHeight = h;
    }

    private void releasePyramid() {
        MTLTexture old = pyramid;
        MTLTexture[] oldViews = levelViews;
        if (old == null) {
            return;
        }
        pyramid = null;
        levelViews = null;
        NativiumInterop.releaseAfterGpu(() -> {
            for (MTLTexture view : oldViews) {
                view.release();
            }
            old.release();
        });
    }

    public int takeDispatches() {
        int d = dispatches;
        dispatches = 0;
        return d;
    }

    @Override
    public void endExternalEncoding() {
        MTLComputeCommandEncoder enc = openEncoder;
        if (enc != null) {
            openEncoder = null;
            enc.updateFence(NativiumInterop.frameFence());
            enc.endEncoding();
            enc.release();
        }
    }
}
