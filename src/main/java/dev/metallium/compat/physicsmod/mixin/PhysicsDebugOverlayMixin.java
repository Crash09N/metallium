/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.compat.physicsmod.mixin;

import dev.metallium.compat.physicsmod.PhysicsModCompat;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Physics Mod's debug overlay asks non-Vulkan devices for NVIDIA's GPU-memory extension via
 * {@code GL.getCapabilities()}, which throws without an OpenGL context. On Metal: no capabilities, extension absent.
 * Optional ({@code require = 0}): if metallium-gl already retargeted that call, it answers it instead.
 */
@Pseudo
@Mixin(targets = "net.diebuddies.minecraft.PhysicsDebugOverlay", remap = false)
public abstract class PhysicsDebugOverlayMixin {
    @Redirect(method = "processDebugInformation", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL;getCapabilities()Lorg/lwjgl/opengl/GLCapabilities;"),
            require = 0)
    private GLCapabilities metallium$noGlCapabilitiesOnMetal() {
        return PhysicsModCompat.metal() ? null : GL.getCapabilities();
    }

    @Redirect(method = "processDebugInformation",
            at = @At(value = "FIELD", target = "Lorg/lwjgl/opengl/GLCapabilities;GL_NVX_gpu_memory_info:Z", opcode = org.objectweb.asm.Opcodes.GETFIELD),
            require = 0)
    private boolean metallium$noNvxOnMetal(final GLCapabilities capabilities) {
        return capabilities != null && capabilities.GL_NVX_gpu_memory_info;
    }
}
