package dev.metallium.iris.post;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.metallium.iris.MetalliumIris;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Diagnostics: {@code -Dmetallium.iris.dumpBuffers=<dir>} writes every existing colortex buffer (main and alt texture,
 * every mip level) as PNG once {@code -Dmetallium.iris.dumpFrame} (default 300) frames of the pipeline have rendered,
 * plus a line per image with NaN/Inf counts and the largest value. Values are clamped to 0..1 (no tone mapping).
 */
final class DebugDump {
    private static final String DIR = System.getProperty("metallium.iris.dumpBuffers", "");
    private static final int FRAME = Integer.getInteger("metallium.iris.dumpFrame", 300);
    private int frames;

    boolean due() {
        return !DIR.isEmpty() && ++frames == FRAME;
    }

    void dump(final PostTargets targets) {
        for (int i = 0; i < PostTargets.COUNT; i++) {
            PostTargets.Target target = targets.getIfPresent(i);
            if (target == null) {
                continue;
            }
            for (int alt = 0; alt < 2; alt++) {
                GpuTexture texture = target.texture(alt == 1);
                for (int level = 0; level < texture.getMipLevels(); level++) {
                    String name = "colortex" + i + (alt == 1 ? "-alt" : "-main") + "-mip" + level;
                    try {
                        dump(texture, level, name);
                    } catch (RuntimeException e) {
                        MetalliumIris.LOGGER.warn("[metallium-iris] dump: {} failed", name, e);
                    }
                }
            }
        }
    }

    private static void dump(final GpuTexture texture, final int level, final String name) {
        GpuFormat format = texture.getFormat();
        if (format != GpuFormat.RGBA8_UNORM && format != GpuFormat.RGBA16_FLOAT && format != GpuFormat.RG11B10_FLOAT
                && format != GpuFormat.RGBA32_FLOAT && format != GpuFormat.RGBA16_UNORM) {
            MetalliumIris.LOGGER.info("[metallium-iris] dump: {} has format {} (not dumped)", name, format);
            return;
        }
        int width = Math.max(1, texture.getWidth(level));
        int height = Math.max(1, texture.getHeight(level));
        int size = format.blockSize();
        var device = RenderSystem.getDevice();
        GpuBuffer buffer = device.createBuffer(() -> "iris dump " + name, GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) width * height * size);
        var encoder = device.createCommandEncoder();
        encoder.copyTextureToBuffer(texture, buffer, 0, () -> {
            try (var view = buffer.map(true, false)) {
                write(view.data().order(ByteOrder.LITTLE_ENDIAN), format, width, height, name);
            } finally {
                buffer.close();
            }
        }, level);
    }

    private static void write(final ByteBuffer data, final GpuFormat format, final int width, final int height, final String name) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int nan = 0;
        int inf = 0;
        float max = 0;
        float[] px = new float[4];
        int stride = format.blockSize();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int o = (y * width + x) * stride;
                switch (format) {
                    case RGBA8_UNORM -> {
                        for (int c = 0; c < 4; c++) {
                            px[c] = (data.get(o + c) & 0xFF) / 255f;
                        }
                    }
                    case RGBA16_UNORM -> {
                        for (int c = 0; c < 4; c++) {
                            px[c] = (data.getShort(o + 2 * c) & 0xFFFF) / 65535f;
                        }
                    }
                    case RGBA16_FLOAT -> {
                        for (int c = 0; c < 4; c++) {
                            px[c] = Float.float16ToFloat(data.getShort(o + 2 * c));
                        }
                    }
                    case RGBA32_FLOAT -> {
                        for (int c = 0; c < 4; c++) {
                            px[c] = data.getFloat(o + 4 * c);
                        }
                    }
                    default -> { // RG11B10_FLOAT
                        int v = data.getInt(o);
                        px[0] = small(v & 0x7FF, 6);
                        px[1] = small((v >>> 11) & 0x7FF, 6);
                        px[2] = small((v >>> 22) & 0x3FF, 5);
                        px[3] = 1;
                    }
                }
                int rgb = 0;
                for (int c = 0; c < 3; c++) {
                    float f = px[c];
                    if (Float.isNaN(f)) {
                        nan++;
                        f = 0;
                    } else if (Float.isInfinite(f)) {
                        inf++;
                        f = 1;
                    } else {
                        max = Math.max(max, f);
                    }
                    rgb = (rgb << 8) | Math.round(Math.max(0, Math.min(1, f)) * 255);
                }
                // Row 0 of a render target is the bottom of the GL image.
                image.setRGB(x, height - 1 - y, rgb);
            }
        }
        try {
            Path dir = Path.of(DIR);
            Files.createDirectories(dir);
            javax.imageio.ImageIO.write(image, "png", dir.resolve(name + ".png").toFile());
            MetalliumIris.LOGGER.info("[metallium-iris] dump: {} {}x{} {} NaN={} Inf={} max={}", name, width, height, format, nan, inf, max);
        } catch (IOException e) {
            MetalliumIris.LOGGER.warn("[metallium-iris] dump: could not write {}", name, e);
        }
    }

    /** An unsigned small float with a 5-bit exponent ({@code mantissaBits} = 6 for R/G, 5 for B). */
    private static float small(final int bits, final int mantissaBits) {
        int exponent = bits >>> mantissaBits;
        int mantissa = bits & ((1 << mantissaBits) - 1);
        if (exponent == 31) {
            return mantissa == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        }
        if (exponent == 0) {
            return (float) (mantissa / (double) (1 << mantissaBits) * Math.pow(2, -14));
        }
        return (float) ((1 + mantissa / (double) (1 << mantissaBits)) * Math.pow(2, exponent - 15));
    }
}
