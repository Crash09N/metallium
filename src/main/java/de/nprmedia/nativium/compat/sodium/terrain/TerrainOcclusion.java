/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.compat.sodium.terrain;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import de.nprmedia.nativium.Nativium;
import de.nprmedia.nativium.api.NativiumRuntime;
import de.nprmedia.nativium.backend.NativiumInterop;
import de.nprmedia.nativium.backend.terrain.HiZCuller;
import io.github.kokodio.metaljvm.metal.MTLTexture;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.iterator.ByteIterator;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.lang.foreign.ValueLayout;
import java.util.Iterator;

/**
 * Main-view occlusion culling of Sodium's opaque terrain on Metal ({@code -Dnativium.terrain.gpuCull=off|count|on|verify}).
 * The shadow pass is never touched (camera Hi-Z is wrong for the light's view; Iris keeps its own culling there), and
 * translucent terrain is never culled.
 *
 * <p>{@code count}: after Sodium drew the main view's opaque terrain (solid + cutout), every section is tested against a
 * Hi-Z pyramid of that depth ({@link HiZCuller}). Nothing is culled; the log reports how many sections, sub-draws and
 * indices a two-phase culler would have skipped (best case: the depth of <em>all</em> opaque terrain).
 *
 * <p>{@code on}: two-phase culling that never drops visible geometry. Phase 1 draws every section that the latest GPU
 * result (a few frames old) did not report hidden, plus every section without a result. Then the render encoder is
 * split, the pyramid is built from phase 1's depth, <em>every</em> section is tested, and phase 2 encodes one indirect
 * draw per deferred sub-draw whose instance count the GPU sets to 1 only if its section passed the test. A deferred
 * section that became visible is therefore drawn in the same frame; one that stays hidden is skipped by the GPU. Its
 * fragments would have failed the depth test anyway: they lie inside the (grown) section box, whose nearest depth is
 * farther than everything already in the depth buffer under its screen rectangle. {@code verify}: same, but phase 2
 * draws every deferred sub-draw (cost of the scheme without its gain; screenshots must match {@code off} exactly).
 */
public final class TerrainOcclusion {
    public static String MODE = "off";
    public static boolean COUNT, VERIFY, ENABLED;
    /** Real culling (phase split) enabled: on, verify, or auto (then only at {@link #AUTO_MIN_RENDER_DISTANCE} and above). */
    public static boolean CULL;
    /** {@code auto}: cull only while the effective render distance is at least {@link #AUTO_MIN_RENDER_DISTANCE}. */
    public static boolean AUTO;
    /**
     * Measured 2026-10-21 (2560x1440, M3 Max): faster at rd32 in every view, shaders off (+3..+76%) and with
     * Complementary (+2..+21%); at rd16 neutral with Complementary and up to 8% slower with shaders off.
     */
    public static final int AUTO_MIN_RENDER_DISTANCE = Integer.getInteger("nativium.terrain.gpuCullAutoMinRd", 24);
    /** Sub-draw to section bookkeeping while Sodium fills its batches (cheap; on unless disabled, so a mode switch works at once). */
    public static final boolean RECORD = !"false".equals(System.getProperty("nativium.terrain.gpuCullRecord"));

    /** The mode at start-up (system property, else the config). */
    public static final String INITIAL_MODE = System.getProperty("nativium.terrain.gpuCull", configuredMode());

    static {
        setMode(INITIAL_MODE);
    }

    private static String configuredMode() {
        try {
            de.nprmedia.nativium.NativiumConfig.TerrainCulling setting = de.nprmedia.nativium.NativiumConfig.INSTANCE.terrainOcclusionCulling;
            return setting == null ? "auto" : setting.name().toLowerCase(java.util.Locale.ROOT);
        } catch (RuntimeException | LinkageError e) {
            return "auto";
        }
    }

    /** {@code off | auto | on | count | verify}; takes effect at the next frame (game tests compare modes in one session). */
    public static void setMode(final String mode) {
        MODE = mode;
        COUNT = "count".equals(mode);
        VERIFY = "verify".equals(mode);
        AUTO = "auto".equals(mode);
        CULL = "on".equals(mode) || VERIFY || AUTO;
        ENABLED = COUNT || CULL;
    }

    /** Whether this frame culls (auto mode: render distance threshold). */
    private static boolean cullThisFrame() {
        // Voxy renders its LODs from inside Sodium's cutout pass. Phase 2 runs the opaque layer again, so Voxy rendered
        // twice per frame: the vanilla terrain drawn in phase 1 vanished (sky with loose cave sections at rd 32) and
        // Voxy's state stayed broken afterwards (near water missing after the drop to rd 4, even with culling then off).
        // In every mode, "on" included: no phase split while Voxy renders.
        if (VoxyState.rendering()) {
            return false;
        }
        if (!AUTO) {
            return true;
        }
        // Shader packs: measured faster at rd32 too, but the on/off screenshot comparison is not exact there (waving
        // leaves, temporal effects), so auto leaves packs alone until a deterministic check exists; "on" forces it.
        if (IrisPackState.packInUse()) {
            return false;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        return mc != null && mc.options.getEffectiveRenderDistance() >= AUTO_MIN_RENDER_DISTANCE;
    }
    /** Box growth in blocks: block models reach up to one block outside their cell; shader packs may displace vertices. */
    private static final float BOX_MARGIN = Float.parseFloat(System.getProperty("nativium.terrain.gpuCullMargin", "1.0"));
    private static final float BOX_MARGIN_PACK = Float.parseFloat(System.getProperty("nativium.terrain.gpuCullPackMargin", "1.5"));
    private static final float PIXEL_MARGIN = Float.parseFloat(System.getProperty("nativium.terrain.gpuCullPixelMargin", "2.0"));
    /** Row 0 of a Nativium framebuffer is NDC y = -1 (GL memory layout, FLIP_VERTEX_Y); true flips the test's rows. */
    private static final boolean FLIP_Y = Boolean.getBoolean("nativium.terrain.gpuCullFlipY");
    /** Results older than this many frames are ignored (the section is drawn in phase 1). */
    public static int maxResultAge = Integer.getInteger("nativium.terrain.gpuCullMaxAge", 30);
    /** Test only: phase 1 draws nothing, every section goes through the GPU test and phase 2. */
    public static boolean deferAll;
    private static final long LOG_INTERVAL_NS = Long.getLong("nativium.terrain.gpuCullLogSeconds", 5L) * 1_000_000_000L;

    /** Per-frame CPU data that goes with a slot: what each box stands for. */
    private static final class FrameData {
        int[] indices = new int[0];
        int[] draws = new int[0];
        int[] local = new int[0];
        boolean[] deferred = new boolean[0];
        CullData.@Nullable Region[] regions = new CullData.Region[0];
        long frame;
        int directDraws;

        void ensure(final int n) {
            if (indices.length < n) {
                indices = new int[n];
                draws = new int[n];
                local = new int[n];
                deferred = new boolean[n];
                regions = new CullData.Region[n];
            }
        }
    }

    // Accumulated counters (render thread), reset by snapshot().
    private static long frames, sections, sectionsOccluded, sectionsOffscreen, drawsTotal, drawsOccluded, indicesTotal, indicesOccluded,
            skippedFrames, gatherNs, directDraws, deferredDraws, phaseTwoDrawn, deferredSections;
    private static long lastLog = System.nanoTime();
    private static String lastSummary = "no data";
    private static final Matrix4f VIEW_PROJ = new Matrix4f();
    private static final float[] VIEW_PROJ_ARRAY = new float[16];

    // Culling state of the frame being drawn (render thread).
    private static long frameId;
    /** 0 = not culling, 1 = phase 1 (direct draws), 2 = phase 2 (indirect draws). */
    private static int phase;
    private static HiZCuller.@Nullable Slot slot;
    private static @Nullable FrameData frame;
    private static long scratch;
    private static int scratchCapacity;

    private TerrainOcclusion() {
    }

    /** Last summary (game test / F3). */
    public static String lastSummary() {
        return lastSummary;
    }

    /** True while the outer drawChunkLayer re-enters itself for phase 2. */
    public static boolean inPhaseTwo() {
        return phase == 2;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Hooks

    /** Head of the main view's opaque {@code drawChunkLayer}: chooses which sections phase 1 draws. */
    public static void beforeOpaque(final ChunkRenderListIterable renderLists, final double x, final double y, final double z) {
        phase = 0;
        if (!CULL || !NativiumRuntime.isMetalActive() || !cullThisFrame()) {
            return;
        }
        HiZCuller culler = HiZCuller.get();
        if (culler == null) {
            return;
        }
        long start = System.nanoTime();
        frameId++;
        HiZCuller.Slot s = gather(culler, renderLists, x, y, z, true);
        if (s == null) {
            return;
        }
        FrameData data = (FrameData) s.userData;
        int draws = 0;
        for (Iterator<ChunkRenderList> it = renderLists.iterator(false); it.hasNext(); ) {
            RenderRegion region = it.next().getRegion();
            // Only passes with storage (prepare() created their batches already; getCachedBatch would allocate one).
            if (region.getStorage(DefaultTerrainRenderPasses.SOLID) != null) {
                draws += region.getCachedBatch(DefaultTerrainRenderPasses.SOLID).size;
            }
            if (region.getStorage(DefaultTerrainRenderPasses.CUTOUT) != null) {
                draws += region.getCachedBatch(DefaultTerrainRenderPasses.CUTOUT).size;
            }
        }
        s.ensureArgs(draws);
        ensureScratch(draws);
        for (int e = 0; e < s.count; e++) {
            CullData.Region region = data.regions[e];
            int local = data.local[e];
            boolean deferred = deferAll || region.hidden[local] != 0 && frameId - region.resultFrame[local] <= maxResultAge;
            data.deferred[e] = deferred;
            if (deferred) {
                deferredSections++;
            }
        }
        data.directDraws = 0;
        slot = s;
        frame = data;
        phase = 1;
        gatherNs += System.nanoTime() - start;
    }

    /**
     * Return of the main view's opaque {@code drawChunkLayer}, inside the still open render pass. Count mode: tests the
     * sections. Cull mode: tests them and returns true if the caller must run the layer again as phase 2.
     */
    public static boolean afterOpaque(final ChunkRenderListIterable renderLists, final ChunkRenderMatrices matrices,
                                      final double x, final double y, final double z) {
        if (COUNT) {
            countTest(renderLists, matrices, x, y, z);
            return false;
        }
        if (phase != 1) {
            return false;
        }
        HiZCuller culler = HiZCuller.get();
        HiZCuller.Slot s = slot;
        FrameData data = frame;
        slot = null;
        frame = null;
        phase = 0;
        if (culler == null || s == null || data == null) {
            return false;
        }
        directDraws += data.directDraws;
        deferredDraws += s.argCount;
        MTLTexture depth = NativiumInterop.currentDepthAttachment();
        if (depth == null) {
            // Nothing drawn into a depth buffer we can see: draw the deferred sub-draws unconditionally.
            for (int j = 0; j < s.argCount; j++) {
                s.args.set(ValueLayout.JAVA_INT, j * 20L + 4, 1);
            }
            culler.releaseLater(s);
            phase = 2;
            slot = s;
            return s.argCount > 0;
        }
        encodeTest(culler, s, depth, matrices, TerrainOcclusion::cullResults);
        if (s.argCount == 0) {
            return false;
        }
        phase = 2;
        slot = s;
        return true;
    }

    /** After the phase-2 pass of the layer. */
    public static void afterPhaseTwo() {
        phase = 0;
        slot = null;
    }

    /**
     * {@code GLDrawBatch.draw} while culling: true = Nativium submitted (or deliberately skipped) the batch. Only
     * opaque main-view batches that were filled with section bookkeeping are handled; anything else draws normally in
     * phase 1 and is skipped in phase 2 (it was drawn in phase 1).
     */
    public static boolean drawBatch(final Object batchObject, final long pointers, final long counts, final long bases, final RenderPass pass) {
        if (phase == 0) {
            return false;
        }
        HiZCuller.Slot s = slot;
        CullData.Batch data = ((CullData.BatchHolder) batchObject).nativium$cullBatch();
        if (phase == 2) {
            if (s != null && data.phaseTwoFrame == frameId && data.phaseTwoCount > 0) {
                GpuBuffer args = s.argsBuffer();
                pass.drawIndexedIndirect(args.slice(data.phaseTwoStart * 20L, data.phaseTwoCount * 20L), data.phaseTwoCount);
                phaseTwoBatches++;
            }
            return true;
        }
        MultiDrawBatch batch = (MultiDrawBatch) batchObject;
        FrameData f = frame;
        data.phaseTwoFrame = -1;
        int n = batch.size;
        if (s == null || f == null || data.recorded != n || data.region == null) {
            return false;
        }
        CullData.Region region = data.region.nativium$cullRegion();
        if (region.entryFrame != frameId) {
            return false;
        }
        if (n > scratchCapacity) {
            ensureScratch(n);
        }
        long sPtr = scratch;
        long sCnt = scratch + (long) scratchCapacity * 8L;
        long sBase = sCnt + (long) scratchCapacity * 4L;
        int direct = 0;
        int start = s.argCount;
        int j = start;
        int[] sections = data.sections;
        for (int i = 0; i < n; i++) {
            int e = region.entry[sections[i]];
            long ptr = MemoryUtil.memGetAddress(pointers + (long) i * 8L);
            int count = MemoryUtil.memGetInt(counts + (long) i * 4L);
            int base = MemoryUtil.memGetInt(bases + (long) i * 4L);
            if (e >= 0 && f.deferred[e] && j < s.argCapacity()) {
                s.putDraw(j++, e, count, (int) (ptr >>> 2), base);
            } else {
                MemoryUtil.memPutAddress(sPtr + (long) direct * 8L, ptr);
                MemoryUtil.memPutInt(sCnt + (long) direct * 4L, count);
                MemoryUtil.memPutInt(sBase + (long) direct * 4L, base);
                direct++;
            }
        }
        f.directDraws += direct;
        if (j == start) {
            return false; // nothing deferred: Sodium's own submission
        }
        s.argCount = j;
        data.phaseTwoFrame = frameId;
        data.phaseTwoStart = start;
        data.phaseTwoCount = j - start;
        if (direct > 0) {
            pass.multiDrawIndexed(MemoryUtil.memPointerBuffer(sPtr, direct), MemoryUtil.memIntBuffer(sCnt, direct),
                    MemoryUtil.memIntBuffer(sBase, direct), direct);
        }
        return true;
    }

    private static long phaseTwoBatches;

    private static void ensureScratch(final int n) {
        if (n <= scratchCapacity) {
            return;
        }
        int cap = Math.max(4096, Integer.highestOneBit(Math.max(1, n - 1)) << 1);
        if (scratch != 0L) {
            MemoryUtil.nmemFree(scratch);
        }
        scratch = MemoryUtil.nmemAlloc((long) cap * 16L);
        scratchCapacity = cap;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Section gathering and the GPU test

    /**
     * Writes one box per section with opaque geometry into a new slot. {@code bookkeeping}: also records each
     * section's entry index in its region (for the draw hook) and which region / section each entry is.
     */
    private static HiZCuller.@Nullable Slot gather(final HiZCuller culler, final ChunkRenderListIterable renderLists,
                                                   final double x, final double y, final double z, final boolean bookkeeping) {
        int estimate = 0;
        for (Iterator<ChunkRenderList> it = renderLists.iterator(false); it.hasNext(); ) {
            estimate += it.next().getSectionsWithGeometryCount();
        }
        if (estimate == 0) {
            return null;
        }
        HiZCuller.Slot s = culler.acquire(estimate);
        if (s == null) {
            skippedFrames++;
            return null;
        }
        FrameData data = s.userData instanceof FrameData d ? d : new FrameData();
        data.ensure(s.capacity());
        data.frame = frameId;
        s.userData = data;

        CameraTransform camera = new CameraTransform(x, y, z);
        boolean faceCulling = SodiumClientMod.options().performance.useBlockFaceCulling;
        float margin = IrisPackState.packInUse() ? BOX_MARGIN_PACK : BOX_MARGIN;
        float size = 16 + 2 * margin;
        int n = 0;
        int[] stats = new int[2];
        for (Iterator<ChunkRenderList> it = renderLists.iterator(false); it.hasNext(); ) {
            ChunkRenderList list = it.next();
            RenderRegion region = list.getRegion();
            SectionRenderDataStorage solid = region.getStorage(DefaultTerrainRenderPasses.SOLID);
            SectionRenderDataStorage cutout = region.getStorage(DefaultTerrainRenderPasses.CUTOUT);
            if (solid == null && cutout == null) {
                continue;
            }
            ByteIterator sectionsIt = list.sectionsWithGeometryIterator(false);
            if (sectionsIt == null) {
                continue;
            }
            CullData.Region regionData = null;
            if (bookkeeping) {
                regionData = ((CullData.RegionHolder) region).nativium$cullRegion();
                if (regionData.entryFrame != frameId) {
                    java.util.Arrays.fill(regionData.entry, -1);
                    regionData.entryFrame = frameId;
                }
            }
            while (sectionsIt.hasNext() && n < s.capacity()) {
                int local = sectionsIt.nextByteAsInt();
                int chunkX = region.getChunkX() + LocalSectionIndex.unpackX(local);
                int chunkY = region.getChunkY() + LocalSectionIndex.unpackY(local);
                int chunkZ = region.getChunkZ() + LocalSectionIndex.unpackZ(local);
                int faces = faceCulling
                        ? DefaultChunkRenderer.getVisibleFaces(camera.intX, camera.intY, camera.intZ, chunkX, chunkY, chunkZ)
                        : ModelQuadFacing.ALL;
                stats[0] = stats[1] = 0;
                if (solid != null) {
                    addStats(solid.getDataPointer(local), faces, stats);
                }
                if (cutout != null) {
                    addStats(cutout.getDataPointer(local), faces, stats);
                }
                if (stats[1] == 0) {
                    continue; // no opaque sub-draws for this section (its batch entries, if stale, draw in phase 1)
                }
                float minX = (float) ((chunkX << 4) - x) - margin;
                float minY = (float) ((chunkY << 4) - y) - margin;
                float minZ = (float) ((chunkZ << 4) - z) - margin;
                s.putBox(n, minX, minY, minZ, minX + size, minY + size, minZ + size);
                data.indices[n] = stats[0];
                data.draws[n] = stats[1];
                data.local[n] = local;
                data.regions[n] = regionData;
                if (regionData != null) {
                    regionData.entry[local] = n;
                }
                n++;
            }
        }
        if (n == 0) {
            culler.abandon(s);
            return null;
        }
        s.count = n;
        return s;
    }

    private static void encodeTest(final HiZCuller culler, final HiZCuller.Slot s, final MTLTexture depth,
                                   final ChunkRenderMatrices matrices, final java.util.function.Consumer<HiZCuller.Slot> done) {
        VIEW_PROJ.set(matrices.projection()).mul(matrices.modelView()).get(VIEW_PROJ_ARRAY);
        boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        // Depth convention from the projection itself: Minecraft 26.3 uses reversed depth (near = 1, GREATER_EQUAL).
        org.joml.Vector4f near = matrices.projection().transform(new org.joml.Vector4f(0, 0, -1, 1));
        org.joml.Vector4f far = matrices.projection().transform(new org.joml.Vector4f(0, 0, -1000, 1));
        boolean reversed = near.z / near.w > far.z / far.w;
        culler.encode(depth, s, VIEW_PROJ_ARRAY, zeroToOne, FLIP_Y, reversed, PIXEL_MARGIN, VERIFY, done);
    }

    private static void countTest(final ChunkRenderListIterable renderLists, final ChunkRenderMatrices matrices,
                                  final double x, final double y, final double z) {
        if (!NativiumRuntime.isMetalActive()) {
            return;
        }
        HiZCuller culler = HiZCuller.get();
        if (culler == null) {
            return;
        }
        MTLTexture depth = NativiumInterop.currentDepthAttachment();
        if (depth == null) {
            return;
        }
        long start = System.nanoTime();
        HiZCuller.Slot s = gather(culler, renderLists, x, y, z, false);
        if (s == null) {
            return;
        }
        gatherNs += System.nanoTime() - start;
        encodeTest(culler, s, depth, matrices, TerrainOcclusion::countResults);
    }

    /** Adds the section's index count and sub-draw count (runs of drawn facings) for one pass. */
    private static void addStats(final long mesh, final int faces, final int[] stats) {
        if (mesh == 0L) {
            return;
        }
        int mask = SectionRenderDataUnsafe.getSliceMask(mesh) & faces;
        if (mask == 0) {
            return;
        }
        long facingList = SectionRenderDataUnsafe.getFacingList(mesh);
        boolean inRun = false;
        for (int slotIndex = 0; slotIndex < ModelQuadFacing.COUNT; slotIndex++) {
            long vertices = SectionRenderDataUnsafe.getVertexCount(mesh, slotIndex);
            if (vertices == 0) {
                continue; // empty slots do not split a sub-draw
            }
            int facing = (int) ((facingList >>> (slotIndex * 8)) & 0xFF);
            if (((mask >>> facing) & 1) != 0) {
                stats[0] += (int) ((vertices >> 2) * 6);
                if (!inRun) {
                    stats[1]++;
                    inRun = true;
                }
            } else {
                inRun = false;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Results

    private static void countResults(final HiZCuller.Slot s) {
        FrameData data = (FrameData) s.userData;
        frames++;
        for (int i = 0; i < s.count; i++) {
            int result = s.result(i);
            sections++;
            drawsTotal += data.draws[i];
            indicesTotal += data.indices[i];
            if (result != HiZCuller.VISIBLE) {
                if (result == HiZCuller.OFFSCREEN) {
                    sectionsOffscreen++;
                } else {
                    sectionsOccluded++;
                }
                drawsOccluded += data.draws[i];
                indicesOccluded += data.indices[i];
            }
        }
        maybeLog();
    }

    /** GPU finished a culled frame: store each section's result for the next frames' phase-1 choice. */
    private static void cullResults(final HiZCuller.Slot s) {
        FrameData data = (FrameData) s.userData;
        countResults(s);
        for (int e = 0; e < s.count; e++) {
            CullData.Region region = data.regions[e];
            if (region == null) {
                continue;
            }
            int local = data.local[e];
            if (region.resultFrame[local] > data.frame) {
                continue;
            }
            region.hidden[local] = (byte) (s.result(e) == HiZCuller.VISIBLE ? 0 : 1);
            region.resultFrame[local] = data.frame;
            data.regions[e] = null;
        }
        for (int j = 0; j < s.argCount; j++) {
            phaseTwoDrawn += s.args.get(ValueLayout.JAVA_INT, j * 20L + 4);
        }
    }

    private static void maybeLog() {
        if (System.nanoTime() - lastLog >= LOG_INTERVAL_NS && frames > 0) {
            Nativium.LOGGER.info("[nativium] terrain gpuCull={}: {}", MODE, snapshot());
        }
    }

    /** Summary of the frames since the last snapshot (also resets the counters). Render thread. */
    public static String snapshot() {
        if (frames == 0) {
            return "no frames tested (skipped=" + skippedFrames + ")";
        }
        String cull = CULL ? String.format(java.util.Locale.ROOT,
                " | phase1 draws=%.0f deferred sections=%.0f deferred draws=%.0f phase2 drawn=%.0f (%.1f%% of deferred) phase2 batches=%.0f",
                directDraws / (double) frames, deferredSections / (double) frames, deferredDraws / (double) frames,
                phaseTwoDrawn / (double) frames, pct(phaseTwoDrawn, deferredDraws), phaseTwoBatches / (double) frames) : "";
        lastSummary = String.format(java.util.Locale.ROOT,
                "frames=%d sections=%.0f hidden=%.1f%% (occluded %.1f%%, offscreen %.1f%%) draws=%.0f hidden=%.1f%% indices=%.0f hidden=%.1f%% gather=%.3f ms skipped=%d",
                frames, sections / (double) frames,
                pct(sectionsOccluded + sectionsOffscreen, sections), pct(sectionsOccluded, sections), pct(sectionsOffscreen, sections),
                drawsTotal / (double) frames, pct(drawsOccluded, drawsTotal),
                indicesTotal / (double) frames, pct(indicesOccluded, indicesTotal),
                gatherNs / (double) frames / 1e6, skippedFrames) + cull;
        frames = sections = sectionsOccluded = sectionsOffscreen = drawsTotal = drawsOccluded = indicesTotal = indicesOccluded = 0;
        skippedFrames = gatherNs = directDraws = deferredDraws = phaseTwoDrawn = deferredSections = phaseTwoBatches = 0;
        lastLog = System.nanoTime();
        return lastSummary;
    }

    private static double pct(final long part, final long total) {
        return total == 0 ? 0.0 : 100.0 * part / total;
    }
}
