/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL46C.*;

/** GL object types tracked by {@link GlContext}. Plain data; all behaviour lives in the context. */
final class GlObjects {
    private GlObjects() {
    }

    /**
     * One GL name space. {@code glGen*} reserves a name (value {@code null}); the object is created on first bind, as
     * in GL. Name 0 is never handed out.
     */
    static final class Names<T> {
        private final Map<Integer, @Nullable T> objects = new HashMap<>();
        private int next = 1;

        int reserve() {
            while (objects.containsKey(next) || next == 0) {
                next++;
            }
            objects.put(next, null);
            return next++;
        }

        boolean isReserved(final int name) {
            return objects.containsKey(name);
        }

        @Nullable T get(final int name) {
            return objects.get(name);
        }

        void put(final int name, final T object) {
            objects.put(name, object);
        }

        @Nullable T remove(final int name) {
            return objects.remove(name);
        }

        int size() {
            return objects.size();
        }
    }

    static final class Buffer {
        final int name;
        GlDevice.@Nullable BufferStore store;
        long size;
        int usage = GL_STATIC_DRAW;
        boolean immutable;
        int storageFlags;
        boolean mapped;
        long mapOffset;
        long mapLength;
        int mapAccess;

        Buffer(final int name) {
            this.name = name;
        }

        boolean persistent() {
            return (storageFlags & GL_MAP_PERSISTENT_BIT) != 0;
        }
    }

    static final class Texture {
        final int name;
        int target;
        boolean renderbuffer;
        @Nullable TexFormat format;
        int internalFormat;
        int width;
        int height;
        /** Mip levels the storage has. */
        int levels;
        boolean immutable;
        GlDevice.@Nullable TextureStore store;
        int minFilter = GL_NEAREST_MIPMAP_LINEAR;
        int magFilter = GL_LINEAR;
        int wrapS = GL_REPEAT;
        int wrapT = GL_REPEAT;
        int wrapR = GL_REPEAT;
        float minLod = -1000f;
        float maxLod = 1000f;
        float lodBias;
        int baseLevel;
        int maxLevel = 1000;
        int compareMode = GL_NONE;
        int compareFunc = GL_LEQUAL;
        float maxAnisotropy = 1f;
        GlDevice.@Nullable SamplerState samplerCache;

        Texture(final int name) {
            this.name = name;
        }

        GlDevice.SamplerState sampler() {
            GlDevice.SamplerState cached = samplerCache;
            if (cached == null) {
                float maxLodClamped = Math.min(maxLod, maxLevel - baseLevel);
                cached = new GlDevice.SamplerState(minFilter, magFilter, wrapS, wrapT, wrapR, Math.max(minLod, 0f), Math.max(0f, maxLodClamped),
                        lodBias, compareMode, compareFunc, maxAnisotropy);
                samplerCache = cached;
            }
            return cached;
        }
    }

    record Attachment(Texture texture, int level) {
    }

    static final class Framebuffer {
        final int name;
        final @Nullable Attachment[] color = new Attachment[GlContext.MAX_COLOR_ATTACHMENTS];
        @Nullable Attachment depth;
        @Nullable Attachment stencil;
        final int[] drawBuffers = new int[GlContext.MAX_COLOR_ATTACHMENTS];
        int readBuffer = GL_COLOR_ATTACHMENT0;

        Framebuffer(final int name) {
            this.name = name;
            java.util.Arrays.fill(drawBuffers, GL_NONE);
            drawBuffers[0] = GL_COLOR_ATTACHMENT0;
        }
    }

    static final class VertexAttribState {
        boolean enabled;
        int size = 4;
        int type = GL_FLOAT;
        boolean normalized;
        boolean integer;
        int stride;
        long offset;
        @Nullable Buffer buffer;
        int divisor;
    }

    static final class VertexArray {
        final int name;
        final VertexAttribState[] attribs = new VertexAttribState[GlContext.MAX_VERTEX_ATTRIBS];
        @Nullable Buffer elementBuffer;

        VertexArray(final int name) {
            this.name = name;
            for (int i = 0; i < attribs.length; i++) {
                attribs[i] = new VertexAttribState();
            }
        }
    }

    static final class Shader {
        final int name;
        final int type;
        String source = "";
        boolean compiled;
        String log = "";
        boolean deletePending;
        int attachCount;

        Shader(final int name, final int type) {
            this.name = name;
            this.type = type;
        }
    }

    static final class Program {
        final int name;
        final List<Shader> shaders = new ArrayList<>();
        final Map<String, Integer> attributeBindings = new LinkedHashMap<>();
        boolean linked;
        boolean validated;
        boolean deletePending;
        String log = "";
        GlProgramLinker.@Nullable Linked linkedProgram;
        GlDevice.@Nullable ProgramStore store;
        @Nullable ByteBuffer vertexUniforms;
        @Nullable ByteBuffer fragmentUniforms;
        /** location → uniform (with element index); samplers have their own table. */
        GlProgramLinker.@Nullable Uniform[] uniformByLocation = new GlProgramLinker.Uniform[0];
        GlProgramLinker.@Nullable Sampler[] samplerByLocation = new GlProgramLinker.Sampler[0];
        int[] samplerUnits = new int[0];

        Program(final int name) {
            this.name = name;
        }
    }

    static final class Sync {
        final GlDevice.GpuUse use;
        boolean signaled;

        Sync(final GlDevice.GpuUse use) {
            this.use = use;
        }
    }
}
