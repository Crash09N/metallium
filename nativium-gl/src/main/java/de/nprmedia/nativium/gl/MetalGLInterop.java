/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.gl;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

/**
 * Minecraft-side GL interop for retargeted mods: {@code GlTexture.glId()} / {@code GlTextureView.glId()} on Nativium's
 * textures (main render target colour / depth, lightmap, atlases) return a nativium-gl texture name that wraps the same
 * Metal texture, so the mod can sample it, attach it to its framebuffers or copy from it with GL calls.
 */
public final class MetalGLInterop {
    private MetalGLInterop() {
    }

    /**
     * Called by retargeted code right before a cast to one of Minecraft's OpenGL backend types ({@code GlTexture},
     * {@code GlTextureView}, {@code GlBuffer}, {@code GlSampler}; {@code glType} is its internal name): on Metal an
     * engine object is replaced by a stand-in of that type carrying a nativium-gl name ({@link GlShadowObjects}). On
     * OpenGL, and for objects that already are of the type, returns {@code object} unchanged.
     */
    public static Object shadow(final Object object, final String glType) {
        if (object == null || !MetalGL.isActive()) {
            return object;
        }
        if (glType.startsWith("com/mojang/renderpearl/backend/opengl/")) {
            return GlShadowObjects.of(object, glType);
        }
        // A mod type (Mixin package): if it is an accessor interface that Mixin added to a GL backend class, the object
        // needs the stand-in of that class.
        String backend = ACCESSOR_TARGETS.computeIfAbsent(glType, MetalGLInterop::accessorTarget);
        return backend.isEmpty() ? object : GlShadowObjects.of(object, backend);
    }

    private static final java.util.Map<String, String> ACCESSOR_TARGETS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Class<?>[] SHADOW_TYPES = {com.mojang.renderpearl.backend.opengl.GlBuffer.class,
            com.mojang.renderpearl.backend.opengl.GlTexture.class, com.mojang.renderpearl.backend.opengl.GlTextureView.class,
            com.mojang.renderpearl.backend.opengl.GlSampler.class};

    /** The GL backend class (internal name) that implements the interface {@code type}, or "" if none. */
    private static String accessorTarget(final String type) {
        try {
            Class<?> cls = Class.forName(type.replace('/', '.'), false, MetalGLInterop.class.getClassLoader());
            if (cls.isInterface()) {
                for (Class<?> backend : SHADOW_TYPES) {
                    if (cls.isAssignableFrom(backend)) return backend.getName().replace('.', '/');
                }
            }
        } catch (ClassNotFoundException | LinkageError e) {
            // not loadable: leave casts to it alone
        }
        return "";
    }

    /** Called by retargeting bridges in place of {@code ((GlTexture) texture).glId()} on Metal. */
    public static int glId(final Object texture) {
        if (texture instanceof com.mojang.renderpearl.backend.opengl.GlTexture standIn) {
            return standIn.glId(); // a stand-in from shadow(): already a nativium-gl name
        }
        if (texture instanceof com.mojang.renderpearl.backend.opengl.GlTextureView standIn) {
            return standIn.glId();
        }
        Object engine = texture instanceof GpuTextureView view ? view.texture() : texture;
        if (texture instanceof GpuTextureView view && view.baseMipLevel() != 0) {
            MetalGLLog.unsupported("glId() of a texture view with base mip level " + view.baseMipLevel() + " (whole texture used)");
        }
        if (!(engine instanceof GpuTexture)) {
            MetalGLLog.unsupported("glId() of " + (engine == null ? "null" : engine.getClass().getName()));
            return 0;
        }
        return GlContext.current().externalTextures.nameOf(engine, o -> o instanceof GpuTexture t && t.isClosed());
    }

    /**
     * For engine-side adapters that hand GL names to a retargeted mod (nativium-iris → Voxy's shader-pack pipeline): a
     * GL texture name that stays valid while the engine re-creates the texture behind {@code key} (resize). Call it again
     * with the current texture whenever it may have changed; the name, its parameters and framebuffer attachments of it
     * are kept, only the storage is re-pointed (like GL storage re-specification). 0 if {@code texture} has no GL name.
     */
    public static int stableTextureName(final Object key, final GpuTexture texture) {
        return GlContext.current().externalTextures.stableName(key, texture);
    }

    /** The Metal texture behind a nativium-gl texture name (e.g. a retargeted mod's own depth buffer), or null. */
    public static io.github.kokodio.metaljvm.metal.@org.jspecify.annotations.Nullable MTLTexture metalTexture(final int glName) {
        return GlContext.current().externalTextures.nativeTexture(glName) instanceof io.github.kokodio.metaljvm.metal.MTLTexture texture ? texture : null;
    }

    /**
     * Sampling state of a nativium-gl texture name for adapters that hand textures to a retargeted mod without a sampler
     * object (GL texture parameters): clamp-to-edge or repeat, {@code linear} or nearest filtering without mips,
     * {@code compare} = {@code GL_COMPARE_REF_TO_TEXTURE}/{@code LEQUAL} (for {@code sampler2DShadow}).
     */
    public static void setSampling(final int textureName, final boolean linear, final boolean repeat, final boolean compare) {
        int filter = linear ? org.lwjgl.opengl.GL11C.GL_LINEAR : org.lwjgl.opengl.GL11C.GL_NEAREST;
        int wrap = repeat ? org.lwjgl.opengl.GL11C.GL_REPEAT : org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
        MetalGL4.glTextureParameteri(textureName, org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER, filter);
        MetalGL4.glTextureParameteri(textureName, org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER, filter);
        MetalGL4.glTextureParameteri(textureName, org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_S, wrap);
        MetalGL4.glTextureParameteri(textureName, org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_T, wrap);
        MetalGL4.glTextureParameteri(textureName, org.lwjgl.opengl.GL14C.GL_TEXTURE_COMPARE_MODE,
                compare ? org.lwjgl.opengl.GL30C.GL_COMPARE_REF_TO_TEXTURE : org.lwjgl.opengl.GL11C.GL_NONE);
        MetalGL4.glTextureParameteri(textureName, org.lwjgl.opengl.GL14C.GL_TEXTURE_COMPARE_FUNC, org.lwjgl.opengl.GL11C.GL_LEQUAL);
    }
}
