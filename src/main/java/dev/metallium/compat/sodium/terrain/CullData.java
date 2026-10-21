/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.compat.sodium.terrain;

/** Per-object state that the occlusion culler attaches to Sodium's draw batches and render regions (via mixin ducks). */
public final class CullData {
    private CullData() {
    }

    /** Implemented by Sodium's {@code MultiDrawBatch} (mixin). */
    public interface BatchHolder {
        Batch metallium$cullBatch();
    }

    /** Implemented by Sodium's {@code RenderRegion} (mixin). */
    public interface RegionHolder {
        Region metallium$cullRegion();
    }

    /** Which section (region-local index) each sub-draw of a cached batch belongs to, recorded while Sodium fills it. */
    public static final class Batch {
        public int[] sections = new int[64];
        /** Region the batch was filled for. */
        public @org.jspecify.annotations.Nullable RegionHolder region;
        /** Sub-draws recorded so far (== batch size after a fill). */
        public int recorded;
        /** Phase-2 draws of this batch in the current frame's indirect-args buffer. */
        public long phaseTwoFrame = -1;
        public int phaseTwoStart;
        public int phaseTwoCount;

        void record(final int from, final int to, final int section) {
            if (to > sections.length) {
                int[] grown = new int[Math.max(to, sections.length * 2)];
                System.arraycopy(sections, 0, grown, 0, sections.length);
                sections = grown;
            }
            for (int i = from; i < to; i++) {
                sections[i] = section;
            }
            recorded = to;
        }
    }

    /** Per render region (256 sections): this frame's test entry per section, and the latest test result. */
    public static final class Region {
        public final int[] entry = new int[256];
        public long entryFrame = -1;
        /** 1 = occluded or off screen in the result of frame {@link #resultFrame}. */
        public final byte[] hidden = new byte[256];
        public final long[] resultFrame = new long[256];
    }

    /** Fill bookkeeping (render thread): the section whose draw commands Sodium is generating. */
    static int fillSection = -1;

    public static void beginFill(final Object batch, final Object region) {
        if (batch instanceof BatchHolder holder) {
            Batch data = holder.metallium$cullBatch();
            data.region = region instanceof RegionHolder r ? r : null;
            data.recorded = ((net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch) batch).size;
        }
        fillSection = -1;
    }

    public static void section(final int localSection) {
        fillSection = localSection;
    }

    public static void afterSectionCommands(final Object batch) {
        if (batch instanceof BatchHolder holder) {
            Batch data = holder.metallium$cullBatch();
            int size = ((net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch) batch).size;
            if (size > data.recorded) {
                data.record(data.recorded, size, fillSection);
            }
        }
    }
}
