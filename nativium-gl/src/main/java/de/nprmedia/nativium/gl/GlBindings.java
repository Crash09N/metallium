/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.gl;

import de.nprmedia.nativium.gl.GlObjects.Buffer;
import de.nprmedia.nativium.gl.GlObjects.Program;
import de.nprmedia.nativium.gl.GlObjects.Texture;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Indexed GL binding points (GL 4.x): uniform and shader-storage buffer bindings (glBindBufferBase/Range), image units
 * (glBindImageTexture) and sampler objects (glGenSamplers / glBindSampler). Resolves a program stage's reflected
 * interface ({@link GlProgramLinker.StageInterface}) into the device's per-stage bindings at draw / dispatch time.
 */
final class GlBindings {
    static final int MAX_BUFFER_BINDINGS = 32;
    static final int MAX_IMAGE_UNITS = 16;

    /** One indexed buffer binding; {@code size <= 0}: the whole buffer (glBindBufferBase). */
    record Range(Buffer buffer, long offset, long size) {
    }

    record ImageUnit(Texture texture, int level, int access, int format) {
    }

    /** A sampler object; overrides the bound texture's sampling state on its unit. */
    static final class SamplerObject {
        final int name;
        int minFilter = GL_NEAREST_MIPMAP_LINEAR;
        int magFilter = GL_LINEAR;
        int wrapS = GL_REPEAT;
        int wrapT = GL_REPEAT;
        int wrapR = GL_REPEAT;
        float minLod = -1000f;
        float maxLod = 1000f;
        float lodBias;
        int compareMode = GL_NONE;
        int compareFunc = GL_LEQUAL;
        float maxAnisotropy = 1f;
        GlDevice.@Nullable SamplerState cache;

        SamplerObject(final int name) {
            this.name = name;
        }

        GlDevice.SamplerState state() {
            GlDevice.SamplerState cached = cache;
            if (cached == null) {
                cached = new GlDevice.SamplerState(minFilter, magFilter, wrapS, wrapT, wrapR, Math.max(minLod, 0f), Math.max(0f, maxLod),
                        lodBias, compareMode, compareFunc, maxAnisotropy);
                cache = cached;
            }
            return cached;
        }
    }

    private final GlContext gl;
    final @Nullable Range[] uniformBuffers = new Range[MAX_BUFFER_BINDINGS];
    final @Nullable Range[] storageBuffers = new Range[MAX_BUFFER_BINDINGS];
    final @Nullable ImageUnit[] images = new ImageUnit[MAX_IMAGE_UNITS];
    final @Nullable SamplerObject[] samplerUnits = new SamplerObject[GlContext.MAX_TEXTURE_UNITS];
    private final Map<Integer, SamplerObject> samplers = new HashMap<>();
    private int nextSampler = 1;

    GlBindings(final GlContext gl) {
        this.gl = gl;
    }

    // ---------------------------------------------------------------------------------------------- buffers

    /** glBindBufferBase / glBindBufferRange; also sets the generic binding as GL does. */
    void bindBufferRange(final int target, final int index, final int name, final long offset, final long size, final boolean range) {
        @Nullable Range[] table = switch (target) {
            case GL_UNIFORM_BUFFER -> uniformBuffers;
            case GL_SHADER_STORAGE_BUFFER -> storageBuffers;
            default -> null;
        };
        if (table == null) {
            if (target == GL_ATOMIC_COUNTER_BUFFER || target == GL_TRANSFORM_FEEDBACK_BUFFER) {
                MetalGLLog.unsupported("glBindBufferBase target 0x" + Integer.toHexString(target));
            }
            gl.error(GL_INVALID_ENUM);
            return;
        }
        if (index < 0 || index >= table.length) {
            gl.error(GL_INVALID_VALUE);
            return;
        }
        if (range && (offset < 0 || size <= 0)) {
            gl.error(GL_INVALID_VALUE);
            return;
        }
        gl.bindBuffer(target, name);
        if (name == 0) {
            table[index] = null;
            return;
        }
        Buffer buffer = gl.buffers.get(name);
        if (buffer == null) {
            return; // bindBuffer raised the error
        }
        table[index] = new Range(buffer, offset, range ? size : 0);
    }

    int bufferBinding(final int target, final int index) {
        @Nullable Range[] table = target == GL_UNIFORM_BUFFER_BINDING ? uniformBuffers : storageBuffers;
        if (index < 0 || index >= table.length) {
            gl.error(GL_INVALID_VALUE);
            return 0;
        }
        Range r = table[index];
        return r == null ? 0 : r.buffer.name;
    }

    void forget(final Buffer buffer) {
        for (@Nullable Range[] table : List.of(uniformBuffers, storageBuffers)) {
            for (int i = 0; i < table.length; i++) {
                if (table[i] != null && table[i].buffer == buffer) table[i] = null;
            }
        }
    }

    void forget(final Texture texture) {
        for (int i = 0; i < images.length; i++) {
            if (images[i] != null && images[i].texture == texture) images[i] = null;
        }
    }

    // ---------------------------------------------------------------------------------------------- images

    void bindImageTexture(final int unit, final int name, final int level, final boolean layered, final int layer, final int access, final int format) {
        if (unit < 0 || unit >= images.length || level < 0) {
            gl.error(GL_INVALID_VALUE);
            return;
        }
        if (access != GL_READ_ONLY && access != GL_WRITE_ONLY && access != GL_READ_WRITE) {
            gl.error(GL_INVALID_ENUM);
            return;
        }
        if (name == 0) {
            images[unit] = null;
            return;
        }
        Texture texture = gl.textures.get(name);
        if (texture == null) {
            gl.error(GL_INVALID_VALUE);
            return;
        }
        // 3D / 2D-array textures bind all layers (layered); one layer of them is not supported (the whole texture binds).
        if (!layered && texture.target != GL_TEXTURE_2D) {
            MetalGLLog.unsupported("glBindImageTexture of one layer (layer " + layer + ") of a layered texture");
        }
        TexFormat view = TexFormat.fromInternalFormat(format);
        if (texture.format != null && view != null && view != texture.format) {
            MetalGLLog.unsupported("glBindImageTexture format reinterpretation " + texture.format + " as " + view);
        }
        images[unit] = new ImageUnit(texture, level, access, format);
    }

    // ---------------------------------------------------------------------------------------------- samplers

    int genSampler() {
        int name = nextSampler++;
        samplers.put(name, new SamplerObject(name));
        return name;
    }

    void deleteSampler(final int name) {
        SamplerObject sampler = samplers.remove(name);
        if (sampler == null) {
            return;
        }
        for (int i = 0; i < samplerUnits.length; i++) {
            if (samplerUnits[i] == sampler) samplerUnits[i] = null;
        }
    }

    boolean isSampler(final int name) {
        return samplers.containsKey(name);
    }

    void bindSampler(final int unit, final int name) {
        if (unit < 0 || unit >= samplerUnits.length) {
            gl.error(GL_INVALID_VALUE);
            return;
        }
        if (name == 0) {
            samplerUnits[unit] = null;
            return;
        }
        SamplerObject sampler = samplers.get(name);
        if (sampler == null) {
            gl.error(GL_INVALID_OPERATION);
            return;
        }
        samplerUnits[unit] = sampler;
    }

    void samplerParameter(final int name, final int pname, final float value) {
        SamplerObject s = samplers.get(name);
        if (s == null) {
            gl.error(GL_INVALID_OPERATION);
            return;
        }
        int v = (int) value;
        switch (pname) {
            case GL_TEXTURE_MIN_FILTER -> s.minFilter = v;
            case GL_TEXTURE_MAG_FILTER -> s.magFilter = v;
            case GL_TEXTURE_WRAP_S -> s.wrapS = v;
            case GL_TEXTURE_WRAP_T -> s.wrapT = v;
            case GL_TEXTURE_WRAP_R -> s.wrapR = v;
            case GL_TEXTURE_MIN_LOD -> s.minLod = value;
            case GL_TEXTURE_MAX_LOD -> s.maxLod = value;
            case GL_TEXTURE_LOD_BIAS -> s.lodBias = value;
            case GL_TEXTURE_COMPARE_MODE -> s.compareMode = v;
            case GL_TEXTURE_COMPARE_FUNC -> s.compareFunc = v;
            case GL_TEXTURE_MAX_ANISOTROPY -> s.maxAnisotropy = value;
            default -> {
                gl.error(GL_INVALID_ENUM);
                return;
            }
        }
        s.cache = null;
    }

    /** Sampling state for a texture on {@code unit}: the bound sampler object wins over the texture's own state. */
    GlDevice.SamplerState samplerState(final int unit, final @Nullable Texture texture) {
        SamplerObject sampler = unit >= 0 && unit < samplerUnits.length ? samplerUnits[unit] : null;
        if (sampler != null) {
            return sampler.state();
        }
        return texture == null ? GlDevice.SamplerState.DEFAULT : texture.sampler();
    }

    // ---------------------------------------------------------------------------------------------- resolution

    /** Buffers and images of one stage of {@code program}, as the device binds them. */
    GlDevice.StageBindings resolve(final Program program, final GlProgramLinker.StageInterface stage, final java.nio.@Nullable ByteBuffer uniforms,
                                   final int uniformBlockIndex, final GlDevice.TextureBinding[] textures) {
        GlDevice.BufferBinding[] buffers = GlDevice.StageBindings.NO_BUFFERS;
        if (!stage.buffers().isEmpty()) {
            buffers = new GlDevice.BufferBinding[stage.buffers().size()];
            for (int i = 0; i < buffers.length; i++) {
                GlProgramLinker.BufferSlot slot = stage.buffers().get(i);
                @Nullable Range[] table = slot.storage() ? storageBuffers : uniformBuffers;
                Range r = slot.binding() < table.length ? table[slot.binding()] : null;
                if (r == null || r.buffer.store == null) {
                    MetalGLLog.warn("program " + program.name + ": nothing bound to " + (slot.storage() ? "SSBO" : "UBO") + " binding "
                            + slot.binding() + " (" + slot.name() + ")");
                    buffers[i] = new GlDevice.BufferBinding(slot.metalIndex(), null, 0, false);
                } else {
                    if (slot.storage()) r.buffer.gpuWritten = true;
                    buffers[i] = new GlDevice.BufferBinding(slot.metalIndex(), r.buffer.store, r.offset, slot.storage());
                }
            }
        }
        GlDevice.ImageBinding[] imageBindings = GlDevice.StageBindings.NO_IMAGES;
        if (!stage.images().isEmpty()) {
            imageBindings = new GlDevice.ImageBinding[stage.images().size()];
            for (int i = 0; i < imageBindings.length; i++) {
                GlProgramLinker.ImageSlot slot = stage.images().get(i);
                ImageUnit unit = slot.unit() < images.length ? images[slot.unit()] : null;
                if (unit == null || unit.texture.store == null) {
                    imageBindings[i] = new GlDevice.ImageBinding(slot.metalIndex(), null, 0, false);
                } else {
                    imageBindings[i] = new GlDevice.ImageBinding(slot.metalIndex(), unit.texture.store, unit.level, unit.access != GL_READ_ONLY);
                }
            }
        }
        return new GlDevice.StageBindings(uniforms, uniformBlockIndex, textures, buffers, imageBindings);
    }

    /** Texture bindings of the sampler uniforms of one stage ({@code vertexStage}: the vertex / compute stage). */
    GlDevice.TextureBinding[] textures(final Program program, final boolean vertexStage) {
        GlProgramLinker.Linked linked = program.linkedProgram;
        List<GlDevice.TextureBinding> out = new ArrayList<>();
        for (GlProgramLinker.Sampler sampler : linked.samplers()) {
            int textureIndex = vertexStage ? sampler.vertexTexture() : sampler.fragmentTexture();
            if (textureIndex < 0) continue;
            int unit = program.samplerUnits[sampler.location()];
            Texture texture = gl.bound2D[unit];
            GlDevice.TextureStore store = texture == null ? null : texture.store;
            out.add(new GlDevice.TextureBinding(textureIndex, vertexStage ? sampler.vertexSampler() : sampler.fragmentSampler(), store,
                    samplerState(unit, texture)));
        }
        return out.toArray(GlDevice.TextureBinding[]::new);
    }

    @Override
    public String toString() {
        return "GlBindings" + Arrays.toString(storageBuffers);
    }
}
