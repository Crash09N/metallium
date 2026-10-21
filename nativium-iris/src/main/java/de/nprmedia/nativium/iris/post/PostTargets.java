package de.nprmedia.nativium.iris.post;

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
    /** Stage 5: buffers bound as {@code colorimgN} by a compute pass (need shader-write usage). */
    private final java.util.BitSet storage = new java.util.BitSet();
    /** Stage 5: buffers some pass lists in {@code colortexNMipmapEnabled} (allocated with a full mip chain). */
    private final java.util.BitSet mipmapped = new java.util.BitSet();

    /** {@code size.buffer.<name> = w h} overrides (Iris's {@code PackDirectives.getTextureScaleOverride}), or null. */
    private final net.irisshaders.iris.shaderpack.properties.@org.jspecify.annotations.Nullable PackDirectives sizes;

    PostTargets(final PackRenderTargetDirectives directives) {
        this(directives, null);
    }

    PostTargets(final PackRenderTargetDirectives directives, final net.irisshaders.iris.shaderpack.properties.@org.jspecify.annotations.Nullable PackDirectives sizes) {
        this.settings = directives.getRenderTargetSettings();
        this.sizes = sizes;
    }

    /** Size of buffer {@code index}: the screen size unless the pack sets {@code size.buffer.colortexN} (absolute or relative). */
    org.joml.Vector2i size(final int index) {
        if (sizes == null) {
            return new org.joml.Vector2i(width, height);
        }
        org.joml.Vector2i size = sizes.getTextureScaleOverride(index, width, height);
        return new org.joml.Vector2i(Math.max(1, size.x), Math.max(1, size.y));
    }

    static final class Target {
        final GpuFormat format;
        final boolean integer;
        GpuTexture main;
        GpuTexture alt;
        GpuTextureView mainView;
        GpuTextureView altView;
        /** Mip level 0 only (render attachments); the same as the views when the buffer has no mipmaps. */
        GpuTextureView mainAttachment;
        GpuTextureView altAttachment;
        /** Stage 5: mipmaps were generated for main/alt this frame (Iris's {@code turnOnMips}). */
        boolean mainMips;
        boolean altMips;

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

        GpuTextureView attachment(final boolean alt) {
            return alt ? altAttachment : mainAttachment;
        }

        boolean mipmapped() {
            return main.getMipLevels() > 1;
        }

        boolean mipsOn(final boolean alt) {
            return alt ? altMips : mainMips;
        }

        void close() {
            if (mainAttachment != mainView) {
                mainAttachment.close();
                altAttachment.close();
            }
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

    /** Must be called before the buffer is first used (at pipeline construction). */
    void requireStorage(final int index) {
        if (index >= 0 && index < COUNT) {
            storage.set(index);
        }
    }

    void requireMipmaps(final int index) {
        if (index >= 0 && index < COUNT) {
            mipmapped.set(index);
        }
    }

    /** Creates (and clears) every buffer used as an image; must run outside render passes (frame start). */
    void createStorageTargets() {
        for (int i = storage.nextSetBit(0); i >= 0; i = storage.nextSetBit(i + 1)) {
            get(i);
        }
    }

    Target get(final int index) {
        Target target = targets[index];
        if (target == null) {
            org.joml.Vector2i size = size(index);
            target = create("iris colortex" + index, format(index), size.x, size.y, storage.get(index), mipmapped.get(index));
            targets[index] = target;
            clear(index, true);
        }
        return target;
    }

    /** A main/alt pair; {@code storage}: also bindable as a compute image, {@code mips}: with a full mip chain. */
    static Target create(final String label, final GpuFormat format, final int width, final int height, final boolean storage, final boolean mips) {
        Target target = new Target(format);
        GpuDevice device = RenderSystem.getDevice();
        int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC;
        if (storage) {
            usage |= de.nprmedia.nativium.backend.NativiumInterop.TEXTURE_USAGE_STORAGE;
        }
        int levels = mips ? 32 - Integer.numberOfLeadingZeros(Math.max(1, Math.max(width, height))) : 1;
        target.main = device.createTexture(label, usage, format, width, height, 1, levels);
        target.alt = device.createTexture(label + " alt", usage, format, width, height, 1, levels);
        target.mainView = device.createTextureView(target.main);
        target.altView = device.createTextureView(target.alt);
        if (levels > 1) {
            target.mainAttachment = device.createTextureView(target.main, 0, 1);
            target.altAttachment = device.createTextureView(target.alt, 0, 1);
        } else {
            target.mainAttachment = target.mainView;
            target.altAttachment = target.altView;
        }
        return target;
    }

    /** Start of a frame: mipmaps of the previous frame are stale (Iris resets the sampling mode per frame). */
    void resetMips() {
        for (Target target : targets) {
            if (target != null) {
                target.mainMips = false;
                target.altMips = false;
            }
        }
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

    /** An integer-format buffer (sampled with nearest filtering). */
    boolean integer(final int index) {
        return new Target(format(index)).integer;
    }

    /** Some pass asked for mipmaps of this buffer ({@code colortexNMipmapEnabled}) so far. */
    boolean mipmapsRequested(final int index) {
        return index >= 0 && index < COUNT && mipmapped.get(index);
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
