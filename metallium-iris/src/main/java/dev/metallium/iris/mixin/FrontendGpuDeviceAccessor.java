package dev.metallium.iris.mixin;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The frontend device's backend, to compile pre-built SPIR-V pipelines (shader-pack passes) without the GLSL front end. */
@Mixin(value = FrontendGpuDevice.class, remap = false)
public interface FrontendGpuDeviceAccessor {
    @Accessor("backend")
    GpuDeviceBackend metallium$getBackend();
}
