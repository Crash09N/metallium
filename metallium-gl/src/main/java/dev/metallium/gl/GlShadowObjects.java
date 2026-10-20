/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.gl;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import com.mojang.renderpearl.backend.opengl.FrameBufferCache;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import com.mojang.renderpearl.backend.opengl.GlConst;
import com.mojang.renderpearl.backend.opengl.GlSampler;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
import org.jspecify.annotations.Nullable;
import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Stand-ins of Minecraft's OpenGL backend objects for engine objects on Metal. A mod written for the OpenGL backend
 * casts renderpearl objects to {@code GlTexture}, {@code GlTextureView}, {@code GlBuffer} or {@code GlSampler} to get
 * their GL names ({@code glId()}, {@code handle}, {@code getId()}) and then uses them with GL calls. In retargeted classes
 * those casts go through {@link MetalGLInterop#shadow}: a Metal engine object is replaced by a stand-in of the GL type
 * whose name is a metallium-gl name for the same Metal storage (textures and buffers wrap the engine's Metal objects;
 * samplers are GL sampler objects with the same state). Stand-ins are plain data: their constructors issue no GL calls
 * (the sampler and buffer ones are allocated without constructor), and they are never closed by Metallium.
 */
final class GlShadowObjects {
    private static final Unsafe UNSAFE = unsafe();
    private static final Map<Object, Object> TEXTURES = new IdentityHashMap<>();
    private static final Map<Object, Object> VIEWS = new IdentityHashMap<>();
    private static final Map<Object, Object> BUFFERS = new IdentityHashMap<>();
    private static final Map<Object, Object> SAMPLERS = new IdentityHashMap<>();
    private static final Constructor<GlTexture> TEXTURE = constructor(GlTexture.class, int.class, String.class, GpuFormat.class, int.class, int.class,
            int.class, int.class, int.class, FrameBufferCache.class);
    private static final Constructor<GlTextureView> VIEW = constructor(GlTextureView.class, GlTexture.class, int.class, int.class, FrameBufferCache.class);
    private static final String PACKAGE = "com/mojang/renderpearl/backend/opengl/";

    private GlShadowObjects() {
    }

    /** The stand-in of {@code engine} for a cast to {@code glType} (internal name), or {@code engine} if there is none. */
    static synchronized Object of(final Object engine, final String glType) {
        Object shadow = switch (glType.substring(glType.lastIndexOf('/') + 1)) {
            case "GlTexture" -> engine instanceof GlTexture ? engine : engine instanceof GpuTexture t ? texture(t) : null;
            case "GlTextureView" -> engine instanceof GlTextureView ? engine : engine instanceof GpuTextureView v ? cached(VIEWS, v, () -> view(v)) : null;
            case "GlBuffer" -> engine instanceof GlBuffer ? engine : engine instanceof GpuBuffer b ? cached(BUFFERS, b, () -> buffer(b)) : null;
            case "GlSampler" -> engine instanceof GlSampler ? engine : engine instanceof GpuSampler s ? cached(SAMPLERS, s, () -> sampler(s)) : null;
            default -> null;
        };
        return shadow != null ? shadow : engine; // no stand-in: the cast fails as it would have
    }

    private static @Nullable Object cached(final Map<Object, Object> map, final Object engine, final java.util.function.Supplier<@Nullable Object> create) {
        map.keySet().removeIf(GlShadowObjects::closed);
        Object shadow = map.get(engine);
        if (shadow == null) {
            shadow = create.get();
            if (shadow != null) map.put(engine, shadow);
        }
        return shadow;
    }

    private static boolean closed(final Object engine) {
        return engine instanceof GpuTexture t && t.isClosed() || engine instanceof GpuTextureView v && v.isClosed()
                || engine instanceof GpuBuffer b && b.isClosed() || engine instanceof GpuSampler s && s.isClosed();
    }

    private static GlTexture texture(final GpuTexture engine) {
        return (GlTexture) cached(TEXTURES, engine, () -> {
            int name = MetalGLInterop.glId(engine);
            try {
                return TEXTURE.newInstance(engine.usage(), engine.getLabel(), engine.getFormat(), engine.getWidth(0), engine.getHeight(0),
                        engine.getDepthOrLayers(), engine.getMipLevels(), name, null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("metallium-gl: GlTexture stand-in", e);
            }
        });
    }

    private static GlTextureView view(final GpuTextureView engine) {
        try {
            return VIEW.newInstance(texture(engine.texture()), engine.baseMipLevel(), engine.mipLevels(), null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("metallium-gl: GlTextureView stand-in", e);
        }
    }

    private static @Nullable GlBuffer buffer(final GpuBuffer engine) {
        int name = GlContext.current().externalBuffers.nameOf(engine, o -> o instanceof GpuBuffer b && b.isClosed());
        if (name == 0) return null;
        try {
            GlBuffer buffer = (GlBuffer) UNSAFE.allocateInstance(GlBuffer.Direct.class);
            put(GlBuffer.class, "handle", buffer, name);
            put(BaseGpuBuffer.class, "usage", buffer, engine.usage());
            putLong(BaseGpuBuffer.class, "size", buffer, engine.size());
            return buffer;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("metallium-gl: GlBuffer stand-in", e);
        }
    }

    private static GlSampler sampler(final GpuSampler engine) {
        int name = MetalGL4.glGenSamplers();
        int wrapU = GlConst.toGl(engine.getAddressModeU());
        int wrapV = GlConst.toGl(engine.getAddressModeV());
        MetalGL4.glSamplerParameteri(name, GL_TEXTURE_WRAP_S, wrapU);
        MetalGL4.glSamplerParameteri(name, GL_TEXTURE_WRAP_T, wrapV);
        MetalGL4.glSamplerParameteri(name, GL_TEXTURE_WRAP_R, wrapU);
        boolean mipmaps = engine.getMaxLod().isEmpty() || engine.getMaxLod().getAsDouble() > 0;
        boolean linearMin = engine.getMinFilter() == FilterMode.LINEAR;
        MetalGL4.glSamplerParameteri(name, GL_TEXTURE_MIN_FILTER, mipmaps ? (linearMin ? GL_LINEAR_MIPMAP_LINEAR : GL_NEAREST_MIPMAP_LINEAR)
                : (linearMin ? GL_LINEAR : GL_NEAREST));
        MetalGL4.glSamplerParameteri(name, GL_TEXTURE_MAG_FILTER, engine.getMagFilter() == FilterMode.LINEAR ? GL_LINEAR : GL_NEAREST);
        if (engine.getMaxLod().isPresent()) {
            MetalGL4.glSamplerParameterf(name, GL_TEXTURE_MAX_LOD, (float) engine.getMaxLod().getAsDouble());
        }
        try {
            GlSampler sampler = (GlSampler) UNSAFE.allocateInstance(GlSampler.class);
            put(GlSampler.class, "id", sampler, name);
            putObject(GlSampler.class, "addressModeU", sampler, engine.getAddressModeU());
            putObject(GlSampler.class, "addressModeV", sampler, engine.getAddressModeV());
            putObject(GlSampler.class, "minFilter", sampler, engine.getMinFilter());
            putObject(GlSampler.class, "magFilter", sampler, engine.getMagFilter());
            put(GlSampler.class, "maxAnisotropy", sampler, engine.getMaxAnisotropy());
            putObject(GlSampler.class, "maxLod", sampler, engine.getMaxLod());
            return sampler;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("metallium-gl: GlSampler stand-in", e);
        }
    }

    @SuppressWarnings("deprecation")
    private static void put(final Class<?> owner, final String field, final Object target, final int value) throws NoSuchFieldException {
        UNSAFE.putInt(target, UNSAFE.objectFieldOffset(owner.getDeclaredField(field)), value);
    }

    @SuppressWarnings("deprecation")
    private static void putLong(final Class<?> owner, final String field, final Object target, final long value) throws NoSuchFieldException {
        UNSAFE.putLong(target, UNSAFE.objectFieldOffset(owner.getDeclaredField(field)), value);
    }

    @SuppressWarnings("deprecation")
    private static void putObject(final Class<?> owner, final String field, final Object target, final Object value) throws NoSuchFieldException {
        UNSAFE.putObject(target, UNSAFE.objectFieldOffset(owner.getDeclaredField(field)), value);
    }

    private static <T> Constructor<T> constructor(final Class<T> type, final Class<?>... parameters) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor(parameters);
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
