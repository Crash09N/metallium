/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.gl;

import dev.metallium.gl.metal.HeadlessMetalHost;
import dev.metallium.gl.metal.MetalGlDevice;
import io.github.kokodio.metaljvm.metal.MTLPixelFormat;
import io.github.kokodio.metaljvm.metal.MTLResourceOptions;
import io.github.kokodio.metaljvm.metal.MTLStorageMode;
import io.github.kokodio.metaljvm.metal.MTLTexture;
import io.github.kokodio.metaljvm.metal.MTLTextureDescriptor;
import io.github.kokodio.metaljvm.metal.MTLTextureType;
import io.github.kokodio.metaljvm.metal.MTLTextureUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL46C.*;

/**
 * Engine-owned resources used through GL names on a real Metal device (macOS only), the way GPU-compute mods use
 * Minecraft's textures and buffers on the OpenGL backend (Physics Mod's volumetric smoke, FFT ocean, GPU liquids):
 * 3D R32UI storage images with atomics, 2D-array textures sampled in compute, clears of all layers, readback of an
 * array into a pixel-pack buffer, and an engine buffer written by a compute shader.
 */
class GlEngineResourcesTest {
    HeadlessMetalHost host;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(HeadlessMetalHost.available(), "needs a Metal device");
        host = new HeadlessMetalHost(16, 16);
        GlContext.makeCurrent(new GlContext(new MetalGlDevice(host)));
    }

    @AfterEach
    void tearDown() {
        GlContext.makeCurrent(null);
        if (host != null) host.close();
    }

    private MTLTexture engineTexture(final MTLTextureType type, final MTLPixelFormat format, final int size, final int depthOrLayers) {
        MTLTextureDescriptor d = MTLTextureDescriptor.alloc().init();
        d.setTextureType(type);
        d.setPixelFormat(format);
        d.setWidth(size);
        d.setHeight(size);
        if (type == MTLTextureType.Type3D) d.setDepth(depthOrLayers);
        else d.setArrayLength(depthOrLayers);
        // Metal: texture atomics need ShaderAtomic usage (as Metallium gives integer storage textures)
        d.setUsage(MTLTextureUsage.ShaderRead | MTLTextureUsage.ShaderWrite | MTLTextureUsage.RenderTarget
                | (format == MTLPixelFormat.R32Uint ? MTLTextureUsage.ShaderAtomic : 0));
        d.setStorageMode(MTLStorageMode.Private);
        MTLTexture texture = host.device().newTexture(d);
        d.release();
        return texture;
    }

    private static int compute(final String source) {
        int shader = MetalGL.glCreateShader(GL_COMPUTE_SHADER);
        MetalGL.glShaderSource(shader, source);
        MetalGL.glCompileShader(shader);
        assertEquals(GL_TRUE, MetalGL.glGetShaderi(shader, GL_COMPILE_STATUS), MetalGL.glGetShaderInfoLog(shader));
        int program = MetalGL.glCreateProgram();
        MetalGL.glAttachShader(program, shader);
        MetalGL.glLinkProgram(program);
        assertEquals(GL_TRUE, MetalGL.glGetProgrami(program, GL_LINK_STATUS), MetalGL.glGetProgramInfoLog(program));
        return program;
    }

    @Test
    void volumeImageWithAtomicsClearAndReadback() {
        MTLTexture volume = engineTexture(MTLTextureType.Type3D, MTLPixelFormat.R32Uint, 8, 8);
        GlContext gl = GlContext.current();
        int name = gl.externalTextures.nameOf(volume, o -> false);
        assertEquals(GL_TEXTURE_3D, gl.textures.get(name).target);
        // Clear to 5 everywhere (all 8 slices), then every invocation adds its index + 1 twice.
        java.nio.IntBuffer five = MemoryUtil.memAllocInt(1).put(0, 5);
        MetalGL4.glClearTexImage(name, 0, GL_RED_INTEGER, GL_UNSIGNED_INT, five);
        MemoryUtil.memFree(five);
        int program = compute("""
                #version 430 core
                layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
                layout(r32ui, binding = 3) uniform uimage3D volume;
                void main() {
                    ivec3 p = ivec3(gl_GlobalInvocationID);
                    uint v = uint(p.x + p.y * 8 + p.z * 64) + 1u;
                    imageAtomicAdd(volume, p, v);
                    imageAtomicAdd(volume, p, v);
                }
                """);
        MetalGL4.glBindImageTexture(3, name, 0, true, 0, GL_READ_WRITE, GL_R32UI);
        MetalGL.glUseProgram(program);
        MetalGL4.glDispatchCompute(1, 1, 8);
        MetalGL4.glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
        ByteBuffer out = MemoryUtil.memAlloc(8 * 8 * 8 * 4).order(ByteOrder.nativeOrder());
        try {
            MetalGL4.glGetTextureImage(name, 0, GL_RED_INTEGER, GL_UNSIGNED_INT, out);
            assertEquals(GL_NO_ERROR, MetalGL.glGetError());
            for (int i = 0; i < 512; i++) {
                assertEquals(5 + 2 * (i + 1), out.getInt(i * 4), "voxel " + i);
            }
        } finally {
            MemoryUtil.memFree(out);
        }
    }

    @Test
    void arrayTextureSampledInComputeAndPackedIntoEngineBuffer() {
        MTLTexture array = engineTexture(MTLTextureType.Type2DArray, MTLPixelFormat.RGBA32Float, 4, 3);
        GlContext gl = GlContext.current();
        int name = gl.externalTextures.nameOf(array, o -> false);
        assertEquals(GL_TEXTURE_2D_ARRAY, gl.textures.get(name).target);
        // Layer l gets value l + 1 everywhere: write it with a layered image store.
        int writer = compute("""
                #version 430 core
                layout(local_size_x = 4, local_size_y = 4, local_size_z = 1) in;
                layout(rgba32f, binding = 0) uniform writeonly image2DArray layers;
                void main() {
                    ivec3 p = ivec3(gl_GlobalInvocationID);
                    imageStore(layers, p, vec4(float(p.z + 1)));
                }
                """);
        MetalGL4.glBindImageTexture(0, name, 0, true, 0, GL_WRITE_ONLY, GL_RGBA32F);
        MetalGL.glUseProgram(writer);
        MetalGL4.glDispatchCompute(1, 1, 3);
        // Sample layer 2 through a sampler2DArray on unit 1 and store into an engine buffer (as an SSBO).
        io.github.kokodio.metaljvm.metal.MTLBuffer engineBuffer = host.device().newBuffer(16 + 4 * 4 * 3 * 16, MTLResourceOptions.StorageModeShared);
        int buffer = gl.externalBuffers.nameOf(engineBuffer, o -> false);
        int reader = compute("""
                #version 430 core
                layout(local_size_x = 1) in;
                layout(binding = 1) uniform sampler2DArray layers;
                layout(binding = 4, std430) buffer Out { vec4 sampled; };
                void main() { sampled = texelFetch(layers, ivec3(1, 2, 2), 0); }
                """);
        MetalGL.glActiveTexture(GL_TEXTURE1);
        MetalGL.glBindTexture(GL_TEXTURE_2D_ARRAY, name);
        MetalGL4.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, buffer);
        MetalGL.glUseProgram(reader);
        MetalGL4.glDispatchCompute(1, 1, 1);
        // The whole array (3 layers of 4x4 RGBA32F) packed into the same engine buffer after the first 16 bytes.
        MetalGL.glBindBuffer(GL_PIXEL_PACK_BUFFER, buffer);
        MetalGL.glGetTexImage(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA, GL_FLOAT, 16);
        MetalGL.glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());
        MetalGL.glFinish();
        ByteBuffer contents = MemoryUtil.memByteBuffer(engineBuffer.contents().address(), (int) engineBuffer.length()).order(ByteOrder.nativeOrder());
        assertEquals(3f, contents.getFloat(0), "sampled layer 2");
        for (int layer = 0; layer < 3; layer++) {
            float value = contents.getFloat(16 + layer * 4 * 4 * 16 + 5 * 16);
            assertEquals(layer + 1f, value, "packed layer " + layer);
        }
        assertTrue(MetalGL.glIsBuffer(buffer));
        engineBuffer.release();
    }

    /**
     * Stable names (metallium-iris hands a shader pack's colour buffers to Voxy as GL names fixed at pipeline creation):
     * when the engine re-creates the texture (resize), the same name and the framebuffer attachment of it now write the
     * new texture, like GL storage re-specification.
     */
    @Test
    void stableNameFollowsTheEngineTextureThroughARecreation() {
        MTLTexture small = engineTexture(MTLTextureType.Type2D, MTLPixelFormat.RGBA32Float, 4, 1);
        MTLTexture large = engineTexture(MTLTextureType.Type2D, MTLPixelFormat.RGBA32Float, 8, 1);
        GlContext gl = GlContext.current();
        Object key = "colortex6 main";
        int name = gl.externalTextures.stableName(key, small);
        assertEquals(name, gl.externalTextures.stableName(key, small), "same texture: same name");
        int fb = MetalGL4.glCreateFramebuffers();
        MetalGL4.glNamedFramebufferTexture(fb, GL_COLOR_ATTACHMENT0, name, 0);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, MetalGL4.glCheckNamedFramebufferStatus(fb, GL_FRAMEBUFFER));
        MetalGL4.glClearNamedFramebufferfv(fb, GL_COLOR, 0, new float[]{0.25f, 0.5f, 0.75f, 1f});

        assertEquals(name, gl.externalTextures.stableName(key, large), "re-created texture: the name is kept");
        assertEquals(8, gl.textures.get(name).width);
        assertEquals(large.handle(), ((MTLTexture) gl.externalTextures.nativeTexture(name)).handle());
        MetalGL4.glClearNamedFramebufferfv(fb, GL_COLOR, 0, new float[]{1f, 0f, 0f, 1f});
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());

        ByteBuffer out = MemoryUtil.memAlloc(8 * 8 * 16).order(ByteOrder.nativeOrder());
        try {
            MetalGL4.glGetTextureImage(name, 0, GL_RGBA, GL_FLOAT, out);
            assertEquals(1f, out.getFloat(63 * 16), "the attachment now writes the new texture");
            int old = gl.externalTextures.nameOf(small, o -> false);
            out.clear();
            MetalGL4.glGetTextureImage(old, 0, GL_RGBA, GL_FLOAT, out);
            assertEquals(0.25f, out.getFloat(15 * 16), "the old texture keeps its contents");
        } finally {
            MemoryUtil.memFree(out);
        }
        small.release();
        large.release();
    }
}
