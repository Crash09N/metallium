package dev.metallium.iris.post;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import org.joml.Vector4f;

import java.util.Map;

/**
 * Iris's {@code colortex0..31} on Metal: each buffer is a pair of frontend textures (main / alt) that the pack's
 * passes ping-pong between, sized like the main render target. Formats come from the pack's
 * {@code const int colortexNFormat} directives; three-component formats are padded to four (Metal has no RGB render
 * targets).
 */
final class PostTargets implements AutoCloseable {
    static final int COUNT = net.irisshaders.iris.gl.IrisLimits.MAX_COLOR_BUFFERS;

    private final Map<Integer, PackRenderTargetDirectives.RenderTargetSettings> settings;
    private final Target[] targets = new Target[COUNT];
    private int width;
    private int height;

    PostTargets(final PackRenderTargetDirectives directives) {
        this.settings = directives.getRenderTargetSettings();
    }

    static final class Target {
        final GpuFormat format;
        final boolean integer;
        GpuTexture main;
        GpuTexture alt;
        GpuTextureView mainView;
        GpuTextureView altView;

        Target(final GpuFormat format) {
            this.format = format;
            GpuFormat.ComponentType type = format.componentType();
            this.integer = type.name().contains("INT") || format == GpuFormat.RGB10A2_UINT;
        }

        GpuTextureView view(final boolean alt) {
            return alt ? altView : mainView;
        }

        GpuTexture texture(final boolean alt) {
            return alt ? this.alt : main;
        }

        void close() {
            mainView.close();
            altView.close();
            main.close();
            alt.close();
        }
    }

    /** Recreates all buffers when the main render target size changed. Returns true on (re)allocation. */
    boolean resize(final int newWidth, final int newHeight) {
        if (newWidth == width && newHeight == height) {
            return false;
        }
        close();
        width = newWidth;
        height = newHeight;
        return true;
    }

    Target get(final int index) {
        Target target = targets[index];
        if (target == null) {
            GpuFormat format = format(index);
            target = new Target(format);
            GpuDevice device = RenderSystem.getDevice();
            int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC;
            target.main = device.createTexture("iris colortex" + index, usage, format, width, height, 1, 1);
            target.alt = device.createTexture("iris colortex" + index + " alt", usage, format, width, height, 1, 1);
            target.mainView = device.createTextureView(target.main);
            target.altView = device.createTextureView(target.alt);
            targets[index] = target;
            clear(index, true);
        }
        return target;
    }

    /** The buffer if it exists already (creating one clears it, which is not possible inside a render pass). */
    @org.jspecify.annotations.Nullable
    Target getIfPresent(final int index) {
        return index >= 0 && index < COUNT ? targets[index] : null;
    }

    /** Clears both textures of a buffer to its pack-defined (or Iris default) clear colour. */
    void clear(final int index, final boolean both) {
        Target target = get(index);
        Vector4f color = clearColor(index);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.clearColorTexture(target.main, color);
        if (both) {
            encoder.clearColorTexture(target.alt, color);
        }
    }

    /**
     * Per-frame clear (like Iris, only buffers that exist are cleared; new buffers start cleared). colortex0 without a
     * pack-defined clear colour is cleared to {@code fogColor} (Iris's ClearPassCreator).
     */
    void clearIfPresent(final int index, final Vector4f fogColor) {
        if (index >= 0 && index < COUNT && targets[index] != null) {
            PackRenderTargetDirectives.RenderTargetSettings s = settings.get(index);
            Vector4f color = index == 0 && (s == null || s.getClearColor().isEmpty()) ? fogColor : clearColor(index);
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.clearColorTexture(targets[index].main, color);
            encoder.clearColorTexture(targets[index].alt, color);
        }
    }

    Vector4f clearColor(final int index) {
        PackRenderTargetDirectives.RenderTargetSettings s = settings.get(index);
        if (s != null && s.getClearColor().isPresent()) {
            return new Vector4f(s.getClearColor().get());
        }
        // Iris defaults: colortex1 white, others transparent black (colortex0 is overwritten with the scene anyway).
        return index == 1 ? new Vector4f(1, 1, 1, 1) : new Vector4f(0, 0, 0, 0);
    }

    GpuFormat format(final int index) {
        PackRenderTargetDirectives.RenderTargetSettings s = settings.get(index);
        return toGpuFormat(s != null ? s.getInternalFormat() : InternalTextureFormat.RGBA8);
    }

    static GpuFormat toGpuFormat(final InternalTextureFormat format) {
        return switch (format) {
            case RGBA, RGBA8, RGB8, RGBA2, RGBA4, R3_G3_B2, RGB5_A1, RGB565 -> GpuFormat.RGBA8_UNORM;
            case R8 -> GpuFormat.R8_UNORM;
            case RG8 -> GpuFormat.RG8_UNORM;
            case R8_SNORM -> GpuFormat.R8_SNORM;
            case RG8_SNORM -> GpuFormat.RG8_SNORM;
            case RGB8_SNORM, RGBA8_SNORM -> GpuFormat.RGBA8_SNORM;
            case R16 -> GpuFormat.R16_UNORM;
            case RG16 -> GpuFormat.RG16_UNORM;
            case RGB16, RGBA16 -> GpuFormat.RGBA16_UNORM;
            case R16_SNORM -> GpuFormat.R16_SNORM;
            case RG16_SNORM -> GpuFormat.RG16_SNORM;
            case RGB16_SNORM, RGBA16_SNORM -> GpuFormat.RGBA16_SNORM;
            case R16F -> GpuFormat.R16_FLOAT;
            case RG16F -> GpuFormat.RG16_FLOAT;
            case RGB16F, RGBA16F, RGB9_E5 -> GpuFormat.RGBA16_FLOAT;
            case R32F -> GpuFormat.R32_FLOAT;
            case RG32F -> GpuFormat.RG32_FLOAT;
            case RGB32F, RGBA32F -> GpuFormat.RGBA32_FLOAT;
            case R8I -> GpuFormat.R8_SINT;
            case RG8I -> GpuFormat.RG8_SINT;
            case RGB8I, RGBA8I -> GpuFormat.RGBA8_SINT;
            case R8UI -> GpuFormat.R8_UINT;
            case RG8UI -> GpuFormat.RG8_UINT;
            case RGB8UI, RGBA8UI -> GpuFormat.RGBA8_UINT;
            case R16I -> GpuFormat.R16_SINT;
            case RG16I -> GpuFormat.RG16_SINT;
            case RGB16I, RGBA16I -> GpuFormat.RGBA16_SINT;
            case R16UI -> GpuFormat.R16_UINT;
            case RG16UI -> GpuFormat.RG16_UINT;
            case RGB16UI, RGBA16UI -> GpuFormat.RGBA16_UINT;
            case R32I -> GpuFormat.R32_SINT;
            case RG32I -> GpuFormat.RG32_SINT;
            case RGB32I, RGBA32I -> GpuFormat.RGBA32_SINT;
            case R32UI -> GpuFormat.R32_UINT;
            case RG32UI -> GpuFormat.RG32_UINT;
            case RGB32UI, RGBA32UI -> GpuFormat.RGBA32_UINT;
            case RGB10_A2 -> GpuFormat.RGB10A2_UNORM;
            case RGB10_A2UI -> GpuFormat.RGB10A2_UINT;
            case R11F_G11F_B10F -> GpuFormat.RG11B10_FLOAT;
        };
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    @Override
    public void close() {
        for (int i = 0; i < COUNT; i++) {
            if (targets[i] != null) {
                targets[i].close();
                targets[i] = null;
            }
        }
    }
}
