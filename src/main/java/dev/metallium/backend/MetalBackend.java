/*
 * Portions of this file are derived from Metallum (https://github.com/kokodio/metallum),
 * Copyright (c) 2026 kokodio, licensed under the MIT License (see LICENSES/MIT-metallum.txt).
 * Modifications Copyright (c) 2026 Crash09N – NPR Media, licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */
package dev.metallium.backend;

import dev.metallium.Metallium;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import io.github.kokodio.metaljvm.metal.MTLDevice;
import io.github.kokodio.metaljvm.metal.Metal;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;

@Environment(EnvType.CLIENT)
public class MetalBackend implements GpuBackend {
    @Override
    public @NonNull String getName() {
        return "Metal";
    }

    @Override
    public void loadLibrary() {
    }

    @Override
    public void unloadLibrary() {
    }

    @Override
    public long createWindow(@Nullable final String title, final int width, final int height, final long flags) {
        return SDLVideo.SDL_CreateWindow(title, width, height, SDLVideo.SDL_WINDOW_METAL | flags);
    }

    @Override
    public @NonNull GpuDevice createDevice(final @NonNull GpuDebugOptions debugOptions) throws BackendCreationException {
        MTLDevice metalDevice = Metal.MTLCreateSystemDefaultDevice();
        if (metalDevice == null) {
            throw new BackendCreationException("MTLCreateSystemDefaultDevice returned null", BackendCreationException.Reason.PLATFORM_ERROR);
        }

        String deviceName = metalDevice.name();
        if (deviceName.isBlank()) deviceName = "<unknown Metal device>";

        Metallium.LOGGER.info("Metal device: {}", deviceName);

        try {
            GpuDevice device = new FrontendGpuDevice(new MetalDevice(debugOptions, metalDevice, deviceName));
            dev.metallium.api.MetalliumRuntime.markMetalActive();
            dev.metallium.api.MetalliumRuntime.setMetalDescription(describe(metalDevice));
            return device;
        } catch (Throwable throwable) {
            throw new BackendCreationException("Metal device initialization failed: " + throwable.getMessage(), BackendCreationException.Reason.OTHER);
        }
    }

    /** Highest Metal API level and Apple GPU family the device supports, plus the MSL version shaders are compiled for. */
    private static String describe(final MTLDevice device) {
        String api = device.supportsFamily(io.github.kokodio.metaljvm.metal.MTLGPUFamily.Metal4) ? "Metal 4"
                : device.supportsFamily(io.github.kokodio.metaljvm.metal.MTLGPUFamily.Metal3) ? "Metal 3" : "Metal";
        String family = "";
        io.github.kokodio.metaljvm.metal.MTLGPUFamily[] apple = {
                io.github.kokodio.metaljvm.metal.MTLGPUFamily.Apple11, io.github.kokodio.metaljvm.metal.MTLGPUFamily.Apple10,
                io.github.kokodio.metaljvm.metal.MTLGPUFamily.Apple9, io.github.kokodio.metaljvm.metal.MTLGPUFamily.Apple8,
                io.github.kokodio.metaljvm.metal.MTLGPUFamily.Apple7};
        for (io.github.kokodio.metaljvm.metal.MTLGPUFamily f : apple) {
            if (device.supportsFamily(f)) {
                family = " · GPU family " + f.name();
                break;
            }
        }
        return api + family + " · MSL " + MetalCrossShaderCompiler.MSL_VERSION_STRING;
    }
}
