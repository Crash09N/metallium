package dev.metallium.iris.post;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.metallium.backend.MetalliumInterop;
import dev.metallium.iris.MetalliumIris;
import dev.metallium.shaders.RawTexels;
import io.github.kokodio.metaljvm.metal.MTLOrigin;
import io.github.kokodio.metaljvm.metal.MTLRegion;
import io.github.kokodio.metaljvm.metal.MTLSize;
import io.github.kokodio.metaljvm.metal.MTLStorageMode;
import io.github.kokodio.metaljvm.metal.MTLTexture;
import io.github.kokodio.metaljvm.metal.MTLTextureDescriptor;
import io.github.kokodio.metaljvm.metal.MTLTextureType;
import io.github.kokodio.metaljvm.metal.MTLTextureUsage;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Shader-pack custom textures on Metal, like Iris's {@code CustomTextureManager}:
 * <ul>
 *   <li>{@code texture.<stage>.<sampler> = file.png} / {@code minecraft:path} / raw data: replaces {@code <sampler>} in
 *       the programs of that stage ({@link #overrides});</li>
 *   <li>raw textures ({@code file.dat TEXTURE_3D RGB16F 32 64 32 RGB HALF_FLOAT}) and {@code customTexture.<name>}:
 *       Iris's patcher renames the sampler to {@code customtexN} / {@code <name>} ({@link #named});</li>
 *   <li>{@code texture.noise}: replaces the generated {@code noisetex}.</li>
 * </ul>
 * PNG and raw textures become Metal textures (raw data converted with {@link RawTexels}; 1D textures are 2D textures of
 * height 1, as the translator maps 1D samplers to 2D). Sampling follows the texture's blur/clamp flags.
 */
final class CustomTextures implements AutoCloseable {
    /** One custom texture: owned ({@link #texture}) or looked up per bind (vanilla resource / lightmap). */
    static final class Texture {
        final String label;
        @Nullable
        final GpuTexture texture;
        @Nullable
        final GpuTextureView view;
        @Nullable
        final Identifier resource;
        final boolean lightmap;
        final boolean blur;
        final boolean clamp;
        final boolean integer;

        Texture(final String label, @Nullable final GpuTexture texture, @Nullable final Identifier resource, final boolean lightmap,
                final boolean blur, final boolean clamp, final boolean integer) {
            this.label = label;
            this.texture = texture;
            this.view = texture != null ? RenderSystem.getDevice().createTextureView(texture) : null;
            this.resource = resource;
            this.lightmap = lightmap;
            this.blur = blur && !integer;
            this.clamp = clamp;
            this.integer = integer;
        }

        GpuTextureView view() {
            if (view != null) {
                return view;
            }
            if (lightmap) {
                return Minecraft.getInstance().gameRenderer.levelLightmap();
            }
            return resourceTexture().getTextureView();
        }

        GpuTexture gpuTexture() {
            if (texture != null) {
                return texture;
            }
            if (lightmap) {
                return Minecraft.getInstance().gameRenderer.levelLightmap().texture();
            }
            return resourceTexture().getTexture();
        }

        /** Re-queried every time, like Iris (resource reloads replace the texture objects). */
        private AbstractTexture resourceTexture() {
            var manager = Minecraft.getInstance().getTextureManager();
            AbstractTexture found = manager.getTexture(resource);
            return found != null ? found : manager.getTexture(MissingTextureAtlasSprite.getLocation());
        }

        void close() {
            if (view != null) {
                view.close();
            }
            if (texture != null) {
                texture.close();
            }
        }
    }

    private final EnumMap<TextureStage, Map<String, Texture>> stages = new EnumMap<>(TextureStage.class);
    private final Map<String, Texture> named = new HashMap<>();
    @Nullable
    private final Texture noise;
    private final List<Texture> owned = new ArrayList<>();
    /** [blur][clamp] */
    private final GpuSampler[][] samplers = new GpuSampler[2][2];

    CustomTextures(final ShaderPack pack) {
        var device = RenderSystem.getDevice();
        for (int blur = 0; blur < 2; blur++) {
            for (int clamp = 0; clamp < 2; clamp++) {
                FilterMode filter = blur == 1 ? FilterMode.LINEAR : FilterMode.NEAREST;
                AddressMode address = clamp == 1 ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT;
                samplers[blur][clamp] = device.createSampler(address, address, filter, filter, 1, OptionalDouble.of(0.0));
            }
        }
        pack.getCustomTextureDataMap().forEach((stage, textures) -> {
            Map<String, Texture> byName = new HashMap<>();
            textures.forEach((sampler, data) -> {
                Texture texture = load(stage + "/" + sampler, data);
                if (texture != null) {
                    byName.put(sampler, texture);
                }
            });
            stages.put(stage, byName);
        });
        pack.getIrisCustomTextureDataMap().forEach((name, data) -> {
            Texture texture = load(name, data);
            if (texture != null) {
                named.put(name, texture);
            }
        });
        this.noise = pack.getCustomNoiseTexture() != null ? load("noisetex", pack.getCustomNoiseTexture()) : null;
        if (!stages.isEmpty() || !named.isEmpty() || noise != null) {
            MetalliumIris.LOGGER.info("[metallium-iris] custom textures: {} per-stage, {} named ({}), custom noise: {}",
                    stages.values().stream().mapToInt(Map::size).sum(), named.size(), named.keySet(), noise != null);
        }
    }

    @Nullable
    private Texture load(final String name, final CustomTextureData data) {
        try {
            Texture texture = create(name, data);
            if (texture.texture != null) {
                owned.add(texture);
            }
            return texture;
        } catch (Exception e) {
            // Iris logs and leaves the sampler unbound; a missing texture must not take the whole pack down.
            MetalliumIris.LOGGER.error("[metallium-iris] custom texture {} could not be loaded: {}", name, e.toString());
            return null;
        }
    }

    private static Texture create(final String name, final CustomTextureData data) throws Exception {
        if (data instanceof CustomTextureData.PngData png) {
            ByteBuffer buffer = MemoryUtil.memAlloc(png.getContent().length);
            try {
                buffer.put(png.getContent()).flip();
                try (NativeImage image = NativeImage.read(buffer)) {
                    var device = RenderSystem.getDevice();
                    GpuTexture texture = device.createTexture("iris custom texture " + name,
                            GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, image.getWidth(), image.getHeight(), 1, 1);
                    device.createCommandEncoder().writeToTexture(texture, image);
                    return new Texture(name, texture, null, false, png.getFilteringData().shouldBlur(), png.getFilteringData().shouldClamp(), false);
                }
            } finally {
                MemoryUtil.memFree(buffer);
            }
        }
        if (data instanceof CustomTextureData.LightmapMarker) {
            return new Texture(name, null, null, true, true, true, false);
        }
        if (data instanceof CustomTextureData.ResourceData resource) {
            // Iris samples these with MIPPED_NEAREST_REPEAT (TextureWrapper); PBR suffixes (_n/_s) are not resolved here.
            return new Texture(name, null, Identifier.fromNamespaceAndPath(resource.getNamespace(), resource.getLocation()), false, false, false, false);
        }
        if (data instanceof CustomTextureData.RawData raw) {
            int width;
            int height = 1;
            int depth = 1;
            boolean is3d = false;
            if (raw instanceof CustomTextureData.RawData1D d1) {
                width = d1.getSizeX();
            } else if (raw instanceof CustomTextureData.RawData2D d2) {
                width = d2.getSizeX();
                height = d2.getSizeY();
            } else if (raw instanceof CustomTextureData.RawData3D d3) {
                width = d3.getSizeX();
                height = d3.getSizeY();
                depth = d3.getSizeZ();
                is3d = true;
            } else {
                throw new IllegalArgumentException("unknown raw texture " + raw.getClass().getSimpleName());
            }
            GpuFormat format = PostTargets.toGpuFormat(raw.getInternalFormat());
            String pixelFormat = raw.getPixelFormat().name();
            ByteBuffer texels = RawTexels.convert(raw.getContent(), width, height, depth, raw.getPixelFormat().getComponentCount(),
                    pixelFormat.startsWith("BGR"), RawTexels.SourceType.of(raw.getPixelType().name()), format);
            try {
                MTLTextureDescriptor descriptor = MTLTextureDescriptor.alloc().init();
                descriptor.setTextureType(is3d ? MTLTextureType.Type3D : MTLTextureType.Type2D);
                descriptor.setPixelFormat(MetalliumInterop.pixelFormat(format));
                descriptor.setWidth(width);
                descriptor.setHeight(height);
                descriptor.setDepth(depth);
                descriptor.setMipmapLevelCount(1);
                descriptor.setUsage(MTLTextureUsage.ShaderRead);
                descriptor.setStorageMode(MTLStorageMode.Shared);
                MTLTexture texture = MetalliumInterop.device().newTexture(descriptor);
                descriptor.release();
                if (texture == null) {
                    throw new IllegalStateException("could not create a " + width + "x" + height + "x" + depth + " " + format + " texture");
                }
                texture.setLabel("iris custom texture " + name);
                long rowBytes = (long) width * format.blockSize();
                texture.replaceRegion(new MTLRegion(new MTLOrigin(0, 0, 0), new MTLSize(width, height, depth)), 0, 0,
                        java.lang.foreign.MemorySegment.ofBuffer(texels), rowBytes, rowBytes * height);
                GpuTexture wrapped = MetalliumInterop.wrapTexture(texture, format, "iris custom texture " + name);
                String kind = format.name();
                boolean integer = kind.endsWith("_UINT") || kind.endsWith("_SINT");
                MetalliumIris.LOGGER.info("[metallium-iris] custom texture {}: {}x{}x{} {} ({} {} {})", name, width, height, depth, format,
                        raw.getInternalFormat(), raw.getPixelFormat(), raw.getPixelType());
                return new Texture(name, wrapped, null, false, raw.getFilteringData().shouldBlur(), raw.getFilteringData().shouldClamp(), integer);
            } finally {
                MemoryUtil.memFree(texels);
            }
        }
        throw new IllegalArgumentException("unknown custom texture " + data.getClass().getSimpleName());
    }

    /** Sampler names a stage's {@code texture.<stage>.<name>} directives replace (Iris's {@code getTextureOverrides}). */
    Set<String> overrides(final TextureStage stage) {
        Map<String, Texture> byName = stages.get(stage);
        return byName == null ? Set.of() : Set.copyOf(byName.keySet());
    }

    /**
     * The texture bound to sampler {@code name} in {@code stage}: a stage override, a named custom texture
     * ({@code customtexN}) or the custom noise texture; null if {@code name} is none of these.
     */
    @Nullable
    Texture lookup(final TextureStage stage, final String name) {
        Map<String, Texture> byName = stages.get(stage);
        Texture texture = byName != null ? byName.get(name) : null;
        if (texture == null) {
            texture = named.get(name);
        }
        if (texture == null && noise != null && name.equals("noisetex")) {
            texture = noise;
        }
        return texture;
    }

    /** Any custom texture by this sampler name in any stage (for the translator's sampler-type checks). */
    boolean isCustom(final String name) {
        if (named.containsKey(name) || (noise != null && name.equals("noisetex"))) {
            return true;
        }
        for (Map<String, Texture> byName : stages.values()) {
            if (byName.containsKey(name)) {
                return true;
            }
        }
        return false;
    }

    GpuSampler sampler(final Texture texture) {
        return samplers[texture.blur ? 1 : 0][texture.clamp ? 1 : 0];
    }

    boolean isEmpty() {
        return stages.isEmpty() && named.isEmpty() && noise == null;
    }

    @Override
    public void close() {
        owned.forEach(Texture::close);
        owned.clear();
        for (GpuSampler[] row : samplers) {
            for (GpuSampler sampler : row) {
                sampler.close();
            }
        }
    }
}
