/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl.metal;

import dev.metallium.gl.GlDevice;
import dev.metallium.gl.MetalGLLog;
import org.lwjgl.system.MemoryUtil;

/**
 * Diagnostics: {@code -Dmetallium.gl.trace=N} logs the first N device operations (draws, indirect draws, dispatches,
 * clears) after {@code -Dmetallium.gl.traceAfter=S} seconds, with targets and fixed-function state.
 */
final class GlTrace {
    private static final int LIMIT = Integer.getInteger("metallium.gl.trace", 0);
    private static final long START = System.nanoTime() + Long.getLong("metallium.gl.traceAfter", 0L) * 1_000_000_000L;
    private static int logged;

    private GlTrace() {
    }

    static boolean on() {
        return LIMIT > 0 && logged < LIMIT && System.nanoTime() > START;
    }

    static void log(final String line) {
        logged++;
        MetalGLLog.LOGGER.info("[metallium-gl trace {}] {}", logged, line);
    }

    static String targets(final GlDevice.RenderTargets t) {
        StringBuilder sb = new StringBuilder("[");
        for (GlDevice.TextureStore c : t.colors()) sb.append(c == null ? "-" : c.format() + " " + c.width() + "x" + c.height()
                + (((MetalGlDevice.MetalTexture) c).external ? " ext" : "")).append(';');
        sb.append(" depth=").append(t.depth() == null ? "-" : t.depth().format() + " " + t.depth().width() + "x" + t.depth().height()
                + (((MetalGlDevice.MetalTexture) t.depth()).external ? " ext" : ""));
        return sb.append(']').toString();
    }

    static String draw(final GlDevice.DrawOp op) {
        GlDevice.RasterState r = op.raster();
        return "prog" + (op.program() instanceof MetalGlDevice.MetalProgram p ? p.id : -1) + " prim=" + op.glPrimitive() + " " + targets(op.targets())
                + " vp=" + java.util.Arrays.toString(r.viewport()) + " depthTest=" + r.depthTest() + "/0x" + Integer.toHexString(r.depthFunc())
                + " write=" + r.depthWrite() + " cull=0x" + Integer.toHexString(r.cullFace()) + " z01=" + r.clipDepthZeroToOne()
                + " blend0=" + (r.blend().length > 0 ? r.blend()[0].enabled() + "/" + r.blend()[0].writeR() : "-")
                + " stencil=" + r.stencil().enabled() + "/0x" + Integer.toHexString(r.stencil().func()) + "," + r.stencil().ref()
                + " vsBufs=" + op.vertex().buffers().length + " fsTex=" + op.fragment().textures().length;
    }

    static String command(final GlDevice.BufferStore buffer, final long offset) {
        long a = MemoryUtil.memAddress(buffer.contents()) + offset;
        return "{" + MemoryUtil.memGetInt(a) + "," + MemoryUtil.memGetInt(a + 4) + "," + MemoryUtil.memGetInt(a + 8) + ","
                + MemoryUtil.memGetInt(a + 12) + "," + MemoryUtil.memGetInt(a + 16) + "}";
    }
}
