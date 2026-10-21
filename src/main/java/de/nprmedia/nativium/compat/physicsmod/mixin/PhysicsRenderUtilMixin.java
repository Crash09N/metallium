/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.compat.physicsmod.mixin;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import de.nprmedia.nativium.compat.physicsmod.PhysicsModCompat;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalDouble;

/**
 * Physics Mod's 3D sampler and 3D textures. On a non-Vulkan device the mod builds them as Minecraft OpenGL backend
 * objects ({@code GlSampler} subclass, {@code GlTexture}), which aborts the JVM on Metal (the first one is created from
 * {@code SamplerCache.initialize} during start-up). On Metal they become Metal objects instead. Interop by name only.
 */
@Pseudo
@Mixin(targets = "net.diebuddies.render.util.PhysicsRenderUtil", remap = false)
public abstract class PhysicsRenderUtilMixin {
    /** The mod's own resource reader (with its fallback for a missing file); RG8 voxels. */
    @Shadow
    private static byte[] readResourceOrFallback(final String path, final int width, final int height, final int depth) {
        throw new AssertionError();
    }

    /** The mod's own RG8 → RGBA8 expansion (the 3D texture's format). */
    @Shadow
    private static byte[] expandRg8ToRgba8(final byte[] rg, final int width, final int height, final int depth) {
        throw new AssertionError();
    }

    @Inject(method = "createSampler3D", at = @At("HEAD"), cancellable = true)
    private static void nativium$sampler3DOnMetal(final AddressMode u, final AddressMode v, final AddressMode w, final FilterMode min,
                                                   final FilterMode mag, final int maxAnisotropy, final OptionalDouble maxLod,
                                                   final CallbackInfoReturnable<GpuSampler> cir) {
        if (PhysicsModCompat.metal()) {
            cir.setReturnValue(PhysicsModCompat.sampler3D(u, v, min, mag, maxAnisotropy, maxLod));
        }
    }

    @Inject(method = "load3DTexture", at = @At("HEAD"), cancellable = true)
    private static void nativium$load3DTextureOnMetal(final String path, final int width, final int height, final int depth,
                                                       final CallbackInfoReturnable<AbstractTexture> cir) {
        if (PhysicsModCompat.metal()) {
            byte[] rgba = expandRg8ToRgba8(readResourceOrFallback(path, width, height, depth), width, height, depth);
            GpuTexture texture = PhysicsModCompat.texture3D(path, GpuFormat.RGBA8_UNORM, width, height, depth, 1, false);
            PhysicsModCompat.upload(texture, rgba, width, height, depth);
            cir.setReturnValue(PhysicsModCompat.wrap(texture));
        }
    }

    @Inject(method = "createTexture3D", at = @At("HEAD"), cancellable = true)
    private static void nativium$createTexture3DOnMetal(final String label, final int usage, final GpuFormat format, final int width,
                                                         final int height, final int depth, final int mipLevels,
                                                         final CallbackInfoReturnable<GpuTexture> cir) {
        if (PhysicsModCompat.metal()) {
            // Storage 3D textures of the volumetric smoke compute path, which reports "unsupported" on Metal
            // (SmokeVolumeRendererMixin); kept so a future caller gets a real texture instead of an abort.
            cir.setReturnValue(PhysicsModCompat.texture3D(label, format, width, height, depth, mipLevels, true));
        }
    }
}
