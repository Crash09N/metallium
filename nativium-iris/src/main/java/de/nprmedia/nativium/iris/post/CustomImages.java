package de.nprmedia.nativium.iris.post;

import com.mojang.renderpearl.api.GpuFormat;
import de.nprmedia.nativium.backend.NativiumInterop;
import de.nprmedia.nativium.iris.NativiumIris;
import io.github.kokodio.metaljvm.metal.MTLStorageMode;
import io.github.kokodio.metaljvm.metal.MTLTexture;
import io.github.kokodio.metaljvm.metal.MTLTextureDescriptor;
import io.github.kokodio.metaljvm.metal.MTLTextureType;
import io.github.kokodio.metaljvm.metal.MTLTextureUsage;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.shaderpack.ImageInformation;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 5: Iris custom images ({@code image.<name> = <sampler> <format> <internalFormat> <type> <clear> <relative> w h [d]},
 * Iris's {@code GlImage}) as Metal textures. Bound as read-write images by {@code <name>} and as sampled textures by
 * {@code <sampler>} in compute passes. 1D images are 2D textures of height 1 (the translator maps 1D to 2D), 3D images
 * are 3D textures. Images with {@code clear = true} are zeroed at the start of every frame (Iris's ImageClearPass).
 */
final class CustomImages implements AutoCloseable {
    static final class Image {
        final ImageInformation info;
        final GpuFormat format;
        /** 'f', 'u' or 'i': component type for clearing and sampler choice. */
        final char kind;
        MTLTexture texture;
        /** The same texture for renderpearl render passes (owns the Metal reference). */
        com.mojang.renderpearl.api.textures.GpuTexture gpuTexture;
        com.mojang.renderpearl.api.textures.GpuTextureView view;

        void wrap() {
            gpuTexture = NativiumInterop.wrapTexture(texture, format, "iris image " + info.name());
            view = com.mojang.blaze3d.systems.RenderSystem.getDevice().createTextureView(gpuTexture);
        }

        void release() {
            view.close();
            gpuTexture.close();
        }

        Image(final ImageInformation info) {
            this.info = info;
            this.format = PostTargets.toGpuFormat(info.internalTextureFormat());
            String name = format.name();
            this.kind = name.contains("UINT") ? 'u' : name.contains("SINT") ? 'i' : 'f';
        }

        boolean integer() {
            return kind != 'f';
        }
    }

    private final Map<String, Image> byImageName = new LinkedHashMap<>();
    private final Map<String, Image> bySamplerName = new LinkedHashMap<>();
    private final List<Image> images = new ArrayList<>();

    CustomImages(final List<ImageInformation> infos, final int screenWidth, final int screenHeight) {
        for (ImageInformation info : infos) {
            Image image = new Image(info);
            image.texture = create(image, screenWidth, screenHeight);
            image.wrap();
            images.add(image);
            byImageName.put(info.name(), image);
            if (info.samplerName() != null && !info.samplerName().equals("none")) {
                bySamplerName.put(info.samplerName(), image);
            }
            NativiumIris.LOGGER.info("[nativium-iris] custom image {} ({}, sampler {}): {} {}x{}x{}", info.name(), info.target(), info.samplerName(),
                    image.format, image.texture.width(), image.texture.height(), image.texture.depth());
        }
    }

    private static MTLTexture create(final Image image, final int screenWidth, final int screenHeight) {
        ImageInformation info = image.info;
        int width = info.isRelative() ? Math.max(1, (int) (screenWidth * info.relativeWidth())) : Math.max(1, info.width());
        int height = info.isRelative() ? Math.max(1, (int) (screenHeight * info.relativeHeight())) : Math.max(1, info.height());
        boolean is3d = info.target() == TextureType.TEXTURE_3D;
        if (info.target() == TextureType.TEXTURE_1D) {
            height = 1;
        }
        MTLTextureDescriptor descriptor = MTLTextureDescriptor.alloc().init();
        descriptor.setTextureType(is3d ? MTLTextureType.Type3D : MTLTextureType.Type2D);
        descriptor.setPixelFormat(NativiumInterop.pixelFormat(image.format));
        descriptor.setWidth(width);
        descriptor.setHeight(height);
        descriptor.setDepth(is3d ? Math.max(1, info.depth()) : 1);
        descriptor.setMipmapLevelCount(1);
        long usage = MTLTextureUsage.ShaderRead | MTLTextureUsage.ShaderWrite;
        if (image.format == GpuFormat.R32_UINT || image.format == GpuFormat.R32_SINT) {
            usage |= MTLTextureUsage.ShaderAtomic;
        }
        descriptor.setUsage(usage);
        descriptor.setStorageMode(MTLStorageMode.Private);
        descriptor.setHazardTrackingMode(NativiumInterop.hazardTrackingMode());
        MTLTexture texture = NativiumInterop.device().newTexture(descriptor);
        descriptor.release();
        if (texture == null) {
            throw new IllegalStateException("could not create custom image " + info.name());
        }
        texture.setLabel("iris image " + info.name());
        return texture;
    }

    /** New images start undefined in Metal: zero all of them once (GL zero-initialises texture storage in practice). */
    void clearAll(final MetalCompute compute) {
        for (Image image : images) {
            compute.clear(image.texture, image.kind);
        }
    }

    /** Per frame: images with {@code clear = true}. */
    void clearPerFrame(final MetalCompute compute) {
        for (Image image : images) {
            if (image.info.clear()) {
                compute.clear(image.texture, image.kind);
            }
        }
    }

    /** Screen-relative images follow the screen size (recreated and zeroed, as in Iris). */
    void resize(final int width, final int height, final MetalCompute compute) {
        for (Image image : images) {
            if (image.info.isRelative()) {
                image.release();
                image.texture = create(image, width, height);
                image.wrap();
                compute.clear(image.texture, image.kind);
            }
        }
    }

    @Nullable
    Image image(final String name) {
        return byImageName.get(name);
    }

    @Nullable
    Image sampler(final String name) {
        return bySamplerName.get(name);
    }

    boolean isEmpty() {
        return images.isEmpty();
    }

    @Override
    public void close() {
        for (Image image : images) {
            image.release();
        }
        images.clear();
        byImageName.clear();
        bySamplerName.clear();
    }
}
