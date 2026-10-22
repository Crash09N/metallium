/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.gametest;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import de.nprmedia.nativium.backend.NativiumInterop;
import io.github.kokodio.metaljvm.metal.MTLBlitCommandEncoder;
import io.github.kokodio.metaljvm.metal.MTLBuffer;
import io.github.kokodio.metaljvm.metal.MTLCommandBuffer;
import io.github.kokodio.metaljvm.metal.MTLOrigin;
import io.github.kokodio.metaljvm.metal.MTLResourceOptions;
import io.github.kokodio.metaljvm.metal.MTLSize;
import io.github.kokodio.metaljvm.metal.MTLTexture;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Test-only GPU readback on Metal (render thread): copies the start of an engine buffer, or one slice of a texture,
 * into a shared buffer within the current frame, waits for the GPU and returns the values. Used to check that a mod's
 * compute output is non-trivial (e.g. Physics Mod's liquid indirect-draw arguments).
 */
final class GpuProbe {
    private GpuProbe() {
    }

    static int[] ints(final GpuBuffer buffer, final int count) {
        long bytes = Math.min(buffer.size(), count * 4L);
        MTLBuffer readback = NativiumInterop.device().newBuffer(Math.max(4, bytes), MTLResourceOptions.StorageModeShared);
        MTLCommandBuffer commands = NativiumInterop.beginExternalEncoding(() -> { });
        MTLBlitCommandEncoder blit = commands.blitCommandEncoder();
        blit.waitForFence(NativiumInterop.frameFence());
        blit.copyFromBuffer(NativiumInterop.metalBuffer(buffer), 0, readback, 0, bytes);
        blit.updateFence(NativiumInterop.frameFence());
        blit.endEncoding();
        NativiumInterop.submitAndWait();
        int[] out = new int[(int) (bytes / 4)];
        MemorySegment contents = readback.contents().reinterpret(bytes);
        for (int i = 0; i < out.length; i++) out[i] = contents.getAtIndex(ValueLayout.JAVA_INT, i);
        readback.release();
        return out;
    }

    /** Sum and count of non-zero 32-bit words of {@code slice} (z for 3D, layer for arrays) of mip 0. */
    static long[] textureStats(final GpuTexture texture, final int slice) {
        MTLTexture metal = NativiumInterop.metalTexture(texture, false);
        boolean is3d = metal.textureType() == io.github.kokodio.metaljvm.metal.MTLTextureType.Type3D;
        int w = (int) metal.width();
        int h = (int) metal.height();
        int bpp = texture.getFormat().blockSize();
        long bytes = (long) w * h * bpp;
        MTLBuffer readback = NativiumInterop.device().newBuffer(bytes, MTLResourceOptions.StorageModeShared);
        MTLCommandBuffer commands = NativiumInterop.beginExternalEncoding(() -> { });
        MTLBlitCommandEncoder blit = commands.blitCommandEncoder();
        blit.waitForFence(NativiumInterop.frameFence());
        blit.copyFromTexture(metal, is3d ? 0 : slice, 0, new MTLOrigin(0, 0, is3d ? slice : 0), new MTLSize(w, h, 1), readback, 0, (long) w * bpp, bytes);
        blit.updateFence(NativiumInterop.frameFence());
        blit.endEncoding();
        NativiumInterop.submitAndWait();
        MemorySegment contents = readback.contents().reinterpret(bytes);
        long nonZero = 0;
        long sum = 0;
        for (long i = 0; i < bytes / 4; i++) {
            int v = contents.getAtIndex(ValueLayout.JAVA_INT, i);
            if (v != 0) nonZero++;
            sum += v & 0xFFFFFFFFL;
        }
        readback.release();
        return new long[]{nonZero, sum, bytes / 4};
    }
}
