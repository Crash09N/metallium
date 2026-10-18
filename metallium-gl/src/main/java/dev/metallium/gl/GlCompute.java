/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl;

import dev.metallium.gl.GlObjects.Buffer;
import dev.metallium.gl.GlObjects.Program;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.opengl.GL46C.*;

/**
 * GL 4.3+ GPU-driven work on top of {@link GlContext}: compute dispatches (direct and indirect), memory barriers,
 * indirect and indirect-count draws, and GPU-ordered buffer copies / clears / reads.
 *
 * <p>Ordering: everything is encoded into the same command stream as draws, in call order. Metal tracks hazards for
 * metallium-gl's own buffers and textures across encoders, and dispatches inside one compute encoder run serially, so
 * GL's {@code glMemoryBarrier} needs no extra work (it is accepted and counted).
 */
public final class GlCompute {
    private final GlContext gl;
    /** Totals since start (diagnostics: status line / game-test report). */
    public static final java.util.concurrent.atomic.AtomicLong DISPATCHES = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong INDIRECT_DRAW_CALLS = new java.util.concurrent.atomic.AtomicLong();
    /** Sum of the GPU-written draw counts seen by indirect-count draws (read from the count buffer when called: the value
     *  the GPU wrote last, usually the previous frame's). */
    public static final java.util.concurrent.atomic.AtomicLong INDIRECT_COUNT_SUM = new java.util.concurrent.atomic.AtomicLong();
    long dispatches;
    long barriers;

    GlCompute(final GlContext gl) {
        this.gl = gl;
    }

    private @Nullable Program computeProgram() {
        Program program = gl.program;
        if (program == null || !program.linked || program.store == null || !program.linkedProgram.compute()) {
            gl.error(GL_INVALID_OPERATION);
            return null;
        }
        return program;
    }

    private GlDevice.StageBindings bindings(final Program program) {
        GlProgramLinker.Linked linked = program.linkedProgram;
        return gl.bindings.resolve(program, linked.vertexInterface(), program.vertexUniforms, linked.vertexBlockIndex(),
                gl.bindings.textures(program, true));
    }

    void dispatch(final int x, final int y, final int z) {
        Program program = computeProgram();
        if (program == null) {
            return;
        }
        if (x < 0 || y < 0 || z < 0 || x > 65535 || y > 65535 || z > 65535) {
            gl.error(GL_INVALID_VALUE);
            return;
        }
        if (x == 0 || y == 0 || z == 0) {
            return;
        }
        dispatches++;
        DISPATCHES.incrementAndGet();
        gl.device.dispatch(new GlDevice.DispatchOp(program.store, x, y, z, null, 0, bindings(program)));
    }

    void dispatchIndirect(final long offset) {
        Program program = computeProgram();
        if (program == null) {
            return;
        }
        Buffer buffer = gl.otherBuffers.get(GL_DISPATCH_INDIRECT_BUFFER);
        if (buffer == null || buffer.store == null || offset < 0 || (offset & 3) != 0 || offset + 12 > buffer.size) {
            gl.error(buffer == null || buffer.store == null || offset + 12 > buffer.size ? GL_INVALID_OPERATION : GL_INVALID_VALUE);
            return;
        }
        dispatches++;
        DISPATCHES.incrementAndGet();
        gl.device.dispatch(new GlDevice.DispatchOp(program.store, 0, 0, 0, buffer.store, offset, bindings(program)));
    }

    void memoryBarrier(final int bits) {
        barriers++;
    }

    // ---------------------------------------------------------------------------------------------- indirect draws

    /**
     * glDrawElementsIndirect / glMultiDrawElementsIndirect(Count): commands from GL_DRAW_INDIRECT_BUFFER at
     * {@code offset}; {@code countOffset >= 0}: the draw count is read by the GPU from GL_PARAMETER_BUFFER.
     */
    void drawElementsIndirect(final int mode, final int type, final long offset, final int drawCount, final int stride, final long countOffset) {
        if (type != GL_UNSIGNED_SHORT && type != GL_UNSIGNED_INT && type != GL_UNSIGNED_BYTE) {
            gl.error(GL_INVALID_ENUM);
            return;
        }
        if (mode == GL_TRIANGLE_FAN || mode == GlContext.GL_QUADS || mode == GL_LINE_LOOP) {
            MetalGLLog.unsupported("indirect draws of primitive 0x" + Integer.toHexString(mode));
            gl.error(GL_INVALID_ENUM);
            return;
        }
        if (drawCount < 0 || stride < 0 || (stride != 0 && stride % 4 != 0) || offset < 0 || (offset & 3) != 0) {
            gl.error(GL_INVALID_VALUE);
            return;
        }
        if (drawCount == 0) {
            return;
        }
        Buffer commands = gl.otherBuffers.get(GL_DRAW_INDIRECT_BUFFER);
        Buffer elements = gl.vertexArray.elementBuffer;
        int effectiveStride = stride == 0 ? 20 : stride;
        if (commands == null || commands.store == null || elements == null || elements.store == null
                || offset + (long) ((countOffset >= 0 ? 1 : drawCount) - 1) * effectiveStride + 20 > commands.size) {
            gl.error(GL_INVALID_OPERATION);
            return;
        }
        Buffer counts = null;
        if (countOffset >= 0) {
            counts = gl.otherBuffers.get(GL_PARAMETER_BUFFER);
            if (counts == null || counts.store == null || (countOffset & 3) != 0 || countOffset + 4 > counts.size) {
                gl.error(counts == null || counts.store == null ? GL_INVALID_OPERATION : GL_INVALID_VALUE);
                return;
            }
        }
        // Metal has no 8-bit indices: widen to 16 bits (firstIndex counts indices, so commands stay valid).
        GlDevice.BufferStore indices = type == GL_UNSIGNED_BYTE ? widened(elements) : elements.store;
        INDIRECT_DRAW_CALLS.incrementAndGet();
        if (counts != null) {
            INDIRECT_COUNT_SUM.addAndGet(Integer.toUnsignedLong(MemoryUtil.memGetInt(MemoryUtil.memAddress(counts.store.contents()) + countOffset)));
        }
        // GL reads at most the commands that fit in the buffer; maxdrawcount may be far larger (Voxy passes capacities).
        int draws = (int) Math.min(drawCount, (commands.size - offset - 20) / effectiveStride + 1);
        GlDevice.DrawOp op = gl.resolveDraw(mode, indices, 0, type == GL_UNSIGNED_INT, 0, 0, 1, 0, 0);
        if (op != null) {
            gl.device.drawIndirect(op, new GlDevice.IndirectDraws(commands.store, offset, draws, effectiveStride,
                    counts == null ? null : counts.store, Math.max(0, countOffset)));
        }
        if (indices != elements.store) {
            gl.device.release(indices);
        }
    }

    private GlDevice.BufferStore widened(final Buffer elements) {
        int count = (int) elements.size;
        GlDevice.BufferStore wide = gl.device.createBuffer(Math.max(4, count * 2L));
        long src = MemoryUtil.memAddress(elements.store.contents());
        long dst = MemoryUtil.memAddress(wide.contents());
        for (int i = 0; i < count; i++) {
            MemoryUtil.memPutShort(dst + i * 2L, (short) (MemoryUtil.memGetByte(src + i) & 0xFF));
        }
        return wide;
    }

    // ---------------------------------------------------------------------------------------------- buffer operations

    void copyBufferSubData(final @Nullable Buffer source, final @Nullable Buffer destination, final long readOffset, final long writeOffset, final long size) {
        if (source == null || destination == null || source.store == null || destination.store == null) {
            gl.error(GL_INVALID_OPERATION);
            return;
        }
        if (readOffset < 0 || writeOffset < 0 || size < 0 || readOffset + size > source.size || writeOffset + size > destination.size
                || (source == destination && Math.abs(readOffset - writeOffset) < size)) {
            gl.error(GL_INVALID_VALUE);
            return;
        }
        if (size == 0) {
            return;
        }
        destination.gpuWritten = true;
        gl.device.copyBuffer(source.store, readOffset, destination.store, writeOffset, size);
    }

    /** glClearBufferSubData: {@code data} = one element of {@code format}/{@code type} (null: zeros). */
    void clearBufferSubData(final @Nullable Buffer buffer, final int internalFormat, final long offset, final long size, final int format,
                            final int type, final @Nullable ByteBuffer data) {
        if (buffer == null || buffer.store == null) {
            gl.error(GL_INVALID_OPERATION);
            return;
        }
        int elementBytes = elementBytes(internalFormat);
        if (elementBytes < 0) {
            MetalGLLog.unsupported("glClearBufferSubData internal format 0x" + Integer.toHexString(internalFormat));
            gl.error(GL_INVALID_ENUM);
            return;
        }
        if (offset < 0 || size < 0 || offset + size > buffer.size || offset % elementBytes != 0 || size % elementBytes != 0) {
            gl.error(GL_INVALID_VALUE);
            return;
        }
        if (size == 0) {
            return;
        }
        int pattern = 0;
        if (data != null) {
            ByteBuffer d = data.duplicate().order(ByteOrder.nativeOrder());
            int clientBytes = GlPixels.clientPixelBytes(format, type);
            if (clientBytes != elementBytes) {
                // Same-size client data is copied bit for bit (GL converts; identical for matching formats).
                MetalGLLog.unsupported("glClearBufferSubData conversion 0x" + Integer.toHexString(format) + "/0x" + Integer.toHexString(type)
                        + " to 0x" + Integer.toHexString(internalFormat));
                gl.error(GL_INVALID_OPERATION);
                return;
            }
            int at = d.position();
            pattern = switch (elementBytes) {
                case 1 -> (d.get(at) & 0xFF) * 0x01010101;
                case 2 -> (d.getShort(at) & 0xFFFF) * 0x00010001;
                default -> d.getInt(at);
            };
        }
        buffer.gpuWritten = true;
        gl.device.fillBuffer(buffer.store, offset, size, pattern);
    }

    private static int elementBytes(final int internalFormat) {
        return switch (internalFormat) {
            case GL_R8, GL_R8UI, GL_R8I -> 1;
            case GL_R16, GL_R16UI, GL_R16I, GL_R16F, GL_RG8, GL_RG8UI, GL_RG8I -> 2;
            case GL_R32UI, GL_R32I, GL_R32F, GL_RGBA8, GL_RGBA8UI, GL_RGBA8I, GL_RG16UI, GL_RG16I, GL_RG16F -> 4;
            default -> -1;
        };
    }

    /** glGetBufferSubData: waits for all GPU work so far, then copies. */
    void getBufferSubData(final @Nullable Buffer buffer, final long offset, final ByteBuffer out) {
        if (buffer == null || buffer.store == null) {
            gl.error(GL_INVALID_OPERATION);
            return;
        }
        long size = out.remaining();
        if (offset < 0 || offset + size > buffer.size) {
            gl.error(GL_INVALID_VALUE);
            return;
        }
        if (buffer.mapped && !buffer.persistent()) {
            gl.error(GL_INVALID_OPERATION);
            return;
        }
        if (!buffer.store.inUseByGpu()) {
            MemoryUtil.memCopy(MemoryUtil.memAddress(buffer.store.contents()) + offset, MemoryUtil.memAddress(out), size);
            return;
        }
        if (!gl.device.readBuffer(buffer.store, offset, out)) {
            MetalGLLog.unsupported("glGetBufferSubData of a buffer the GPU uses (cannot wait here); returning current contents");
            MemoryUtil.memCopy(MemoryUtil.memAddress(buffer.store.contents()) + offset, MemoryUtil.memAddress(out), size);
        }
    }
}
