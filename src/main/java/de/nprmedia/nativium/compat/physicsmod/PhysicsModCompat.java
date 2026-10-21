/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.compat.physicsmod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import de.nprmedia.nativium.Nativium;
import de.nprmedia.nativium.api.NativiumRuntime;
import de.nprmedia.nativium.backend.NativiumInterop;
import de.nprmedia.nativium.compat.GlRouting;
import io.github.kokodio.metaljvm.metal.MTLOrigin;
import io.github.kokodio.metaljvm.metal.MTLRegion;
import io.github.kokodio.metaljvm.metal.MTLSize;
import io.github.kokodio.metaljvm.metal.MTLStorageMode;
import io.github.kokodio.metaljvm.metal.MTLTexture;
import io.github.kokodio.metaljvm.metal.MTLTextureDescriptor;
import io.github.kokodio.metaljvm.metal.MTLTextureType;
import io.github.kokodio.metaljvm.metal.MTLTextureUsage;
import net.minecraft.client.renderer.texture.AbstractTexture;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.OptionalDouble;

/**
 * Physics Mod (closed source, all rights reserved; compatibility only, none of its code is used) on Metal.
 * <p>Found by bytecode inspection of 3.2.5: the mod tells backends apart with {@code backend instanceof VulkanDevice} and
 * takes its OpenGL path for every other device. That path constructs Minecraft's OpenGL backend objects
 * ({@code GlSampler} subclass for a 3D sampler, {@code GlTexture} for 3D textures) and calls OpenGL, which aborts the
 * JVM on Metal. Its Vulkan path is no better (it constructs {@code VulkanGpuTexture}s). Everything else it renders -
 * block debris, ragdolls, cloth, smoke, snow, non-FFT water - goes through Minecraft's backend-neutral graphics layer
 * ({@code RenderPass}, {@code RenderPipeline}) and runs on Metal unchanged.
 * <p>The compatibility mixins ({@code de.nprmedia.nativium.compat.physicsmod.mixin}) therefore only replace the
 * backend-specific pieces while Metal is active:
 * <ul>
 *   <li>the 3D sampler and 3D textures are created as Metal objects through {@link NativiumInterop};</li>
 *   <li>the GPU-compute features (GPU liquids, volumetric smoke, FFT ocean) take the mod's own OpenGL 4.3 compute
 *       path, run through nativium-gl ({@link #gpuCompute()}: Nativium routes every Physics Mod class, and
 *       nativium-gl hands the GL code GL names for the Metal textures, buffers and samplers it casts to Minecraft's
 *       OpenGL backend types). The FFT ocean is pointed at that path explicitly (it rejects unknown backends).
 *       Without nativium-gl they report "unsupported", the answer the mod gives on macOS OpenGL 4.1.</li>
 * </ul>
 */
public final class PhysicsModCompat {
    public static final String MOD_ID = "physicsmod";
    public static final String METAL_REASON = "not available on Metal (needs Vulkan or OpenGL 4.3 compute)";
    private static boolean statusRegistered;

    private PhysicsModCompat() {
    }

    /** The compatibility code acts only while Nativium renders with Metal; on OpenGL/Vulkan the mod runs as is. */
    public static boolean metal() {
        if (!NativiumRuntime.isMetalActive()) return false;
        if (!statusRegistered) {
            statusRegistered = true;
            boolean compute = gpuCompute();
            NativiumRuntime.registerStatusLine(() -> compute ? "Physics Mod: on Metal (GPU compute via nativium-gl)"
                    : "Physics Mod: on Metal (GPU liquids/volumetric smoke/FFT ocean off: nativium-gl not routing it)");
            Nativium.LOGGER.info("[nativium] Physics Mod on Metal: 3D textures/sampler via Metal; GPU-compute features {}",
                    compute ? "through nativium-gl" : "reported unsupported (nativium-gl does not route Physics Mod)");
        }
        return true;
    }

    /** Metal, and nativium-gl retargets all of Physics Mod: its OpenGL compute paths can run translated. */
    public static boolean gpuCompute() {
        if (!NativiumRuntime.isMetalActive()) return false;
        GlRouting.Decision decision = GlRouting.plan().get(MOD_ID);
        return decision != null && decision.routed() && decision.allClasses() && GlRouting.hooked();
    }

    private static final String OCEAN_GL_BACKEND = "net.diebuddies.render.fft.GlOceanComputeBackend";

    /** {@code new GlOceanComputeBackend()} (the mod's OpenGL FFT backend; its GL calls are retargeted to nativium-gl). */
    public static Object newOceanGlBackend() {
        try {
            java.lang.reflect.Constructor<?> constructor = Class.forName(OCEAN_GL_BACKEND, true, PhysicsModCompat.class.getClassLoader())
                    .getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Nativium: could not create Physics Mod's OpenGL ocean backend on Metal", e);
        }
    }

    /** {@code GlOceanComputeBackend.unavailableReason()}: null when the (translated) GL device has what the FFT needs. */
    public static String oceanGlUnavailableReason() {
        try {
            java.lang.reflect.Method method = Class.forName(OCEAN_GL_BACKEND, true, PhysicsModCompat.class.getClassLoader())
                    .getDeclaredMethod("unavailableReason");
            method.setAccessible(true);
            return (String) method.invoke(null);
        } catch (ReflectiveOperationException e) {
            Nativium.LOGGER.warn("[nativium] Physics Mod FFT ocean availability check failed on Metal: {}", e.toString());
            return "Nativium could not query the OpenGL ocean backend on Metal";
        }
    }

    /** A sampler for 3D textures: Metal samplers have no texture-type dimension, so the regular one serves (R repeats as U). */
    public static GpuSampler sampler3D(final AddressMode u, final AddressMode v, final FilterMode min, final FilterMode mag,
                                       final int maxAnisotropy, final OptionalDouble maxLod) {
        return RenderSystem.getDevice().createSampler(u, v, min, mag, maxAnisotropy, maxLod);
    }

    /**
     * A real Metal 3D texture wrapped as a renderpearl texture (shared storage: uploaded directly from the CPU).
     * {@code storage}: also writable from shaders.
     */
    public static GpuTexture texture3D(final String label, final GpuFormat format, final int width, final int height, final int depth,
                                       final int mipLevels, final boolean storage) {
        MTLTextureDescriptor descriptor = MTLTextureDescriptor.alloc().init();
        descriptor.setTextureType(MTLTextureType.Type3D);
        descriptor.setPixelFormat(NativiumInterop.pixelFormat(format));
        descriptor.setWidth(Math.max(1, width));
        descriptor.setHeight(Math.max(1, height));
        descriptor.setDepth(Math.max(1, depth));
        descriptor.setMipmapLevelCount(Math.max(1, mipLevels));
        boolean integer = format == GpuFormat.R32_UINT || format == GpuFormat.R32_SINT;
        // Storage images are written by compute shaders; integer ones with atomics, which Metal only performs on
        // textures created with ShaderAtomic usage (without it the writes of a 3D texture land in its first slice).
        descriptor.setUsage(MTLTextureUsage.ShaderRead | (storage ? MTLTextureUsage.ShaderWrite : 0)
                | (storage && integer ? MTLTextureUsage.ShaderAtomic : 0));
        descriptor.setStorageMode(MTLStorageMode.Shared);
        descriptor.setHazardTrackingMode(NativiumInterop.hazardTrackingMode());
        MTLTexture texture = NativiumInterop.device().newTexture(descriptor);
        descriptor.release();
        if (texture == null) {
            throw new IllegalStateException("could not create Metal 3D texture " + label);
        }
        texture.setLabel(label);
        return NativiumInterop.wrapTexture(texture, format, label);
    }

    /** Uploads tightly packed RGBA8 voxels ({@code width * height * depth * 4} bytes) into mip 0 of a {@link #texture3D}. */
    public static void upload(final GpuTexture texture, final byte[] rgba, final int width, final int height, final int depth) {
        MTLTexture metal = NativiumInterop.metalTexture(texture, true);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(rgba.length);
            MemorySegment.copy(rgba, 0, data, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, rgba.length);
            metal.replaceRegion(new MTLRegion(new MTLOrigin(0, 0, 0), new MTLSize(width, height, depth)), 0, 0, data,
                    (long) width * 4, (long) width * height * 4);
        }
    }

    /** What the mod's 3D texture loader returns: an {@link AbstractTexture} holding the texture and a view of it. */
    public static AbstractTexture wrap(final GpuTexture texture) {
        return new Texture3D(texture);
    }

    private static final class Texture3D extends AbstractTexture {
        Texture3D(final GpuTexture texture) {
            this.texture = texture;
            this.textureView = RenderSystem.getDevice().createTextureView(texture);
        }
    }
}
