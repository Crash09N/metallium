package dev.metallium.shaders;

import com.mojang.renderpearl.api.GpuFormat;
import dev.metallium.shaders.RawTexels.SourceType;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/** Shader-pack raw texture data (GL format/type, unpack alignment 4) to Metal pixel layouts. */
class RawTexelsTest {
    @Test
    void rgbHalfFloatWidensToRgbaWithAlphaOne() {
        // Photon's scattering LUT: TEXTURE_3D RGB16F ... RGB HALF_FLOAT. 1x1x2 texels, rows of 6 bytes padded to 8.
        ByteBuffer src = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        src.putShort(0, Float.floatToFloat16(0.25f)).putShort(2, Float.floatToFloat16(0.5f)).putShort(4, Float.floatToFloat16(2.0f));
        src.putShort(8, Float.floatToFloat16(-1f)).putShort(10, Float.floatToFloat16(0f)).putShort(12, Float.floatToFloat16(8f));
        ByteBuffer out = RawTexels.convert(src.array(), 1, 1, 2, 3, false, SourceType.HALF_FLOAT, GpuFormat.RGBA16_FLOAT);
        try {
            assertEquals(16, out.remaining());
            float[] expected = {0.25f, 0.5f, 2f, 1f, -1f, 0f, 8f, 1f};
            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[i], Float.float16ToFloat(out.getShort(i * 2)), 1e-3, "component " + i);
            }
        } finally {
            MemoryUtil.memFree(out);
        }
    }

    @Test
    void redBytesKeepTheirRowPadding() {
        // R8 UNSIGNED_BYTE, width 3: GL reads rows of 4 bytes (unpack alignment 4).
        byte[] src = {1, 2, 3, 99, 4, 5, 6, 99};
        ByteBuffer out = RawTexels.convert(src, 3, 2, 1, 1, false, SourceType.UNSIGNED_BYTE, GpuFormat.R8_UNORM);
        try {
            byte[] bytes = new byte[out.remaining()];
            out.get(bytes);
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, bytes);
        } finally {
            MemoryUtil.memFree(out);
        }
    }

    @Test
    void bytesIntoFloatAreNormalisedAndBgrIsSwizzled() {
        byte[] src = {(byte) 255, 0, (byte) 51, 0};
        ByteBuffer out = RawTexels.convert(src, 1, 1, 1, 3, true, SourceType.UNSIGNED_BYTE, GpuFormat.RGBA32_FLOAT);
        try {
            assertEquals(0.2f, out.getFloat(0), 1e-4);
            assertEquals(0f, out.getFloat(4), 1e-4);
            assertEquals(1f, out.getFloat(8), 1e-4);
            assertEquals(1f, out.getFloat(12), 1e-4);
        } finally {
            MemoryUtil.memFree(out);
        }
    }

    @Test
    void integerTargetsKeepRawValues() {
        byte[] src = {(byte) 200, 0, 0, 0};
        ByteBuffer out = RawTexels.convert(src, 1, 1, 1, 1, false, SourceType.UNSIGNED_BYTE, GpuFormat.R32_UINT);
        try {
            assertEquals(200, out.getInt(0));
        } finally {
            MemoryUtil.memFree(out);
        }
    }

    @Test
    void shortDataIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RawTexels.convert(new byte[3], 2, 2, 1, 1, false, SourceType.UNSIGNED_BYTE, GpuFormat.R8_UNORM));
    }
}
