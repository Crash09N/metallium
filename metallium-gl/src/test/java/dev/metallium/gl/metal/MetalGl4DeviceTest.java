/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.gl.metal;

import dev.metallium.gl.GlContext;
import dev.metallium.gl.MetalGL;
import dev.metallium.gl.MetalGL4;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static dev.metallium.gl.metal.MetalGlDeviceTest.assertPixel;
import static dev.metallium.gl.metal.MetalGlDeviceTest.program;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL46C.*;

/**
 * GL 4.x on a real Metal device (macOS only): compute (direct / indirect, chained through GPU-written buffers), SSBOs
 * with atomics, UBOs, storage images on a mip level, buffer clears / copies / readback, DSA, sampler objects, indirect
 * and indirect-count draws with {@code gl_BaseInstance}, stencil. Results are read back with glGetNamedBufferSubData,
 * glGetTextureImage and glReadPixels.
 */
class MetalGl4DeviceTest {
    static final int SIZE = MetalGlDeviceTest.SIZE;
    HeadlessMetalHost host;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(HeadlessMetalHost.available(), "needs a Metal device");
        host = new HeadlessMetalHost(SIZE, SIZE);
        GlContext.makeCurrent(new GlContext(new MetalGlDevice(host)));
    }

    @AfterEach
    void tearDown() {
        GlContext.makeCurrent(null);
        if (host != null) {
            host.close();
        }
    }

    static int computeProgram(final String source) {
        int shader = MetalGL.glCreateShader(GL_COMPUTE_SHADER);
        MetalGL.glShaderSource(shader, source);
        MetalGL.glCompileShader(shader);
        assertEquals(GL_TRUE, MetalGL.glGetShaderi(shader, GL_COMPILE_STATUS), MetalGL.glGetShaderInfoLog(shader));
        int program = MetalGL.glCreateProgram();
        MetalGL.glAttachShader(program, shader);
        MetalGL.glLinkProgram(program);
        assertEquals(GL_TRUE, MetalGL.glGetProgrami(program, GL_LINK_STATUS), MetalGL.glGetProgramInfoLog(program));
        MetalGL.glDeleteShader(shader);
        return program;
    }

    static int storageBuffer(final int bytes) {
        int buffer = MetalGL.glCreateBuffers();
        MetalGL4.glNamedBufferStorage(buffer, bytes, GL_DYNAMIC_STORAGE_BIT | GL_MAP_READ_BIT);
        return buffer;
    }

    static int[] read(final int buffer, final int ints) {
        int[] out = new int[ints];
        MetalGL4.glGetNamedBufferSubData(buffer, 0, out);
        return out;
    }

    @Test
    void computeWritesStorageBufferWithAtomicsUniformsAndUbo() {
        int program = computeProgram("""
                #version 460 core
                layout(local_size_x = 64) in;
                layout(binding = 2, std430) restrict buffer Out { uint total; uint values[]; };
                layout(binding = 1, std140) uniform Settings { uint scale; };
                layout(location = 5) uniform uint offset;
                void main() {
                    uint i = gl_GlobalInvocationID.x;
                    values[i] = i * scale + offset;
                    atomicAdd(total, 1u);
                }
                """);
        int out = storageBuffer(4 + 256 * 4);
        int settings = MetalGL.glCreateBuffers();
        MetalGL4.glNamedBufferStorage(settings, new int[]{3, 0, 0, 0}.length * 4L, GL_DYNAMIC_STORAGE_BIT);
        MetalGL4.glNamedBufferSubData(settings, 0, new int[]{3, 0, 0, 0});
        MetalGL4.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, out);
        MetalGL4.glBindBufferBase(GL_UNIFORM_BUFFER, 1, settings);
        MetalGL.glUseProgram(program);
        MetalGL.glUniform1ui(5, 7);
        MetalGL4.glDispatchCompute(4, 1, 1);
        MetalGL4.glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        int[] result = read(out, 1 + 256);
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());
        assertEquals(256, result[0], "atomic counter");
        for (int i = 0; i < 256; i++) {
            assertEquals(i * 3 + 7, result[1 + i], "value " + i);
        }
    }

    @Test
    void declaredButUnusedResourcesAreNotBound() {
        // SPIRV-Cross gives unused resources no Metal slot (index -1); binding one would crash the driver.
        int program = computeProgram("""
                #version 460 core
                layout(local_size_x = 1) in;
                layout(binding = 0, std430) buffer Used { uint value; };
                layout(binding = 1, std430) buffer Unused { uint other[]; };
                layout(binding = 2) uniform sampler2D unusedSampler;
                void main() { value = 42u; }
                """);
        int used = storageBuffer(4);
        MetalGL4.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, used);
        MetalGL4.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, storageBuffer(16));
        MetalGL.glUseProgram(program);
        MetalGL4.glDispatchCompute(1, 1, 1);
        assertEquals(42, read(used, 1)[0]);
    }

    @Test
    void indirectDispatchReadsGroupCountWrittenByAnEarlierDispatch() {
        int setup = computeProgram("""
                #version 460 core
                layout(local_size_x = 1) in;
                layout(binding = 0, std430) buffer Args { uint groups[3]; };
                void main() { groups[0] = 5u; groups[1] = 1u; groups[2] = 1u; }
                """);
        int count = computeProgram("""
                #version 460 core
                layout(local_size_x = 32) in;
                layout(binding = 1, std430) buffer Counter { uint invocations; };
                void main() { atomicAdd(invocations, 1u); }
                """);
        int args = storageBuffer(12);
        int counter = storageBuffer(4);
        MetalGL4.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, args);
        MetalGL4.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, counter);
        MetalGL.glUseProgram(setup);
        MetalGL4.glDispatchCompute(1, 1, 1);
        MetalGL4.glMemoryBarrier(GL_COMMAND_BARRIER_BIT);
        MetalGL.glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, args);
        MetalGL.glUseProgram(count);
        MetalGL4.glDispatchComputeIndirect(0);
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());
        assertEquals(5 * 32, read(counter, 1)[0]);
    }

    @Test
    void bufferClearCopyAndReadback() {
        int a = storageBuffer(64);
        int b = storageBuffer(64);
        ByteBuffer value = MemoryUtil.memAlloc(4).order(ByteOrder.nativeOrder());
        value.putInt(0, 0x12345678);
        MetalGL4.glClearNamedBufferSubData(a, GL_R32UI, 0, 64, GL_RED_INTEGER, GL_UNSIGNED_INT, value);
        value.putInt(0, 0xABABABAB);
        MetalGL4.glClearNamedBufferSubData(b, GL_R32UI, 0, 64, GL_RED_INTEGER, GL_UNSIGNED_INT, value);
        MemoryUtil.memFree(value);
        MetalGL4.glCopyNamedBufferSubData(a, b, 0, 16, 16);
        int[] result = read(b, 16);
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());
        assertEquals(0xABABABAB, result[0]);
        assertEquals(0xABABABAB, result[3]);
        for (int i = 4; i < 8; i++) assertEquals(0x12345678, result[i], "copied word " + i);
        assertEquals(0xABABABAB, result[8]);
    }

    @Test
    void computeStoresIntoOneMipLevelOfAnImage() {
        int program = computeProgram("""
                #version 460 core
                layout(local_size_x = 8, local_size_y = 8) in;
                layout(binding = 3, r32f) uniform restrict writeonly image2D target;
                void main() { imageStore(target, ivec2(gl_GlobalInvocationID.xy), vec4(float(gl_GlobalInvocationID.x + 100u * gl_GlobalInvocationID.y))); }
                """);
        int texture = MetalGL4.glCreateTextures(GL_TEXTURE_2D);
        MetalGL4.glTextureStorage2D(texture, 2, GL_R32F, 16, 16);
        MetalGL4.glBindImageTexture(3, texture, 1, false, 0, GL_WRITE_ONLY, GL_R32F);
        MetalGL.glUseProgram(program);
        MetalGL4.glDispatchCompute(1, 1, 1);
        MetalGL4.glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
        ByteBuffer out = MemoryUtil.memAlloc(8 * 8 * 4).order(ByteOrder.nativeOrder());
        MetalGL4.glGetTextureImage(texture, 1, GL_RED, GL_FLOAT, out);
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());
        assertEquals(0f, out.getFloat(0));
        assertEquals(7f, out.getFloat(7 * 4));
        assertEquals(305f, out.getFloat((3 * 8 + 5) * 4));
        MemoryUtil.memFree(out);
    }

    @Test
    void computeSamplesTextureWithExplicitBindingAndSamplerObject() {
        // 2x1 texture (red, blue); sampled at u = 0.5: LINEAR (sampler object) blends, the texture's own NEAREST would not.
        int texture = MetalGL4.glCreateTextures(GL_TEXTURE_2D);
        MetalGL4.glTextureStorage2D(texture, 1, GL_RGBA8, 2, 1);
        MetalGL4.glTextureSubImage2D(texture, 0, 0, 0, 2, 1, GL_RGBA, GL_UNSIGNED_BYTE,
                new int[]{0xFF0000FF, 0xFFFF0000});
        MetalGL4.glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        MetalGL4.glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        int sampler = MetalGL4.glCreateSamplers();
        MetalGL4.glSamplerParameteri(sampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        MetalGL4.glSamplerParameteri(sampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        MetalGL4.glSamplerParameteri(sampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        int program = computeProgram("""
                #version 460 core
                layout(local_size_x = 1) in;
                layout(binding = 4) uniform sampler2D tex;
                layout(binding = 0, std430) buffer Out { vec4 color; };
                void main() { color = texture(tex, vec2(0.5, 0.5)); }
                """);
        int out = storageBuffer(16);
        MetalGL4.glBindTextureUnit(4, texture);
        MetalGL4.glBindSampler(4, sampler);
        MetalGL4.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, out);
        MetalGL.glUseProgram(program);
        MetalGL4.glDispatchCompute(1, 1, 1);
        int[] raw = read(out, 4);
        float r = Float.intBitsToFloat(raw[0]);
        float b = Float.intBitsToFloat(raw[2]);
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());
        assertEquals(0.5f, r, 0.02f, "linear blend of red");
        assertEquals(0.5f, b, 0.02f, "linear blend of blue");
    }

    private static final String VS_INSTANCED = """
            #version 460 core
            #extension GL_ARB_shader_draw_parameters : require
            layout(binding = 0, std430) readonly buffer Quads { vec4 rects[]; };
            flat out uint quad;
            void main() {
                quad = uint(gl_BaseInstance) + uint(gl_InstanceID);
                vec4 r = rects[quad];
                vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
                gl_Position = vec4(mix(r.xy, r.zw, corner), 0.0, 1.0);
            }
            """;
    private static final String FS_INSTANCED = """
            #version 460 core
            flat in uint quad;
            layout(binding = 1, std430) readonly buffer Colors { vec4 colors[]; };
            out vec4 fragColor;
            void main() { fragColor = colors[quad]; }
            """;

    /** Index buffer for one quad as 4 vertices (0,1,3 / 0,3,2 over the corner bits). */
    private static int quadIndices() {
        int ebo = MetalGL.glCreateBuffers();
        ByteBuffer indices = MemoryUtil.memAlloc(12).order(ByteOrder.nativeOrder());
        indices.asShortBuffer().put(new short[]{0, 1, 3, 0, 3, 2});
        MetalGL4.glNamedBufferStorage(ebo, indices, 0);
        MemoryUtil.memFree(indices);
        return ebo;
    }

    private static int rectsAndColors(final int binding, final float[] data) {
        int buffer = MetalGL.glCreateBuffers();
        ByteBuffer bytes = MemoryUtil.memAlloc(data.length * 4).order(ByteOrder.nativeOrder());
        bytes.asFloatBuffer().put(data);
        MetalGL4.glNamedBufferStorage(buffer, bytes, 0);
        MemoryUtil.memFree(bytes);
        MetalGL4.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, binding, buffer);
        return buffer;
    }

    private void setUpQuads() {
        // Four quads in the four corners, each its own colour.
        rectsAndColors(0, new float[]{
                -1, -1, -0.5f, -0.5f,
                0.5f, -1, 1, -0.5f,
                -1, 0.5f, -0.5f, 1,
                0.5f, 0.5f, 1, 1});
        rectsAndColors(1, new float[]{1, 0, 0, 1, 0, 1, 0, 1, 0, 0, 1, 1, 1, 1, 0, 1});
        int vao = MetalGL4.glCreateVertexArrays();
        MetalGL4.glVertexArrayElementBuffer(vao, quadIndices());
        MetalGL.glBindVertexArray(vao);
        MetalGL.glClearColor(0, 0, 0, 1);
        MetalGL.glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        MetalGL.glUseProgram(program(VS_INSTANCED, FS_INSTANCED));
    }

    @Test
    void multiDrawIndirectCountUsesGpuWrittenCommandsCountAndBaseInstance() {
        setUpQuads();
        // A compute pass writes 4 commands (one quad each, baseInstance = quad) and count = 3: the 4th must not draw.
        int gen = computeProgram("""
                #version 460 core
                layout(local_size_x = 4) in;
                struct Cmd { uint count; uint instanceCount; uint firstIndex; int baseVertex; uint baseInstance; };
                layout(binding = 2, std430) buffer Cmds { Cmd cmds[]; };
                layout(binding = 3, std430) buffer Count { uint drawCount; };
                void main() {
                    uint i = gl_LocalInvocationID.x;
                    cmds[i] = Cmd(6u, 1u, 0u, 0, i);
                    if (i == 0u) drawCount = 3u;
                }
                """);
        int commands = storageBuffer(4 * 20);
        int count = storageBuffer(4);
        MetalGL4.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, commands);
        MetalGL4.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, count);
        MetalGL.glUseProgram(gen);
        MetalGL4.glDispatchCompute(1, 1, 1);
        MetalGL4.glMemoryBarrier(GL_COMMAND_BARRIER_BIT);
        MetalGL.glUseProgram(program(VS_INSTANCED, FS_INSTANCED));
        MetalGL.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commands);
        MetalGL.glBindBuffer(GL_PARAMETER_BUFFER, count);
        MetalGL4.glMultiDrawElementsIndirectCount(GL_TRIANGLES, GL_UNSIGNED_SHORT, 0, 0, 4, 0);
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());
        assertPixel(4, 4, 255, 0, 0, "quad 0 (bottom-left)");
        assertPixel(SIZE - 4, 4, 0, 255, 0, "quad 1 (bottom-right)");
        assertPixel(4, SIZE - 4, 0, 0, 255, "quad 2 (top-left)");
        assertPixel(SIZE - 4, SIZE - 4, 0, 0, 0, "quad 3 is beyond the GPU count");
        assertPixel(SIZE / 2, SIZE / 2, 0, 0, 0, "centre stays clear");
    }

    @Test
    void multiDrawIndirectCountWithTexturedFragmentShader() {
        // Metal does not allow textures as direct arguments of ICB pipelines: the program's ICB variant reads them from
        // an argument buffer, so the draw still runs as one indirect command buffer.
        setUpQuads();
        long icbCalls = MetalGlDevice.ICB_CALLS.get();
        long fallbackCalls = MetalGlDevice.SLOT_FALLBACK_CALLS.get();
        int texture = MetalGL4.glCreateTextures(GL_TEXTURE_2D);
        MetalGL4.glTextureStorage2D(texture, 1, GL_RGBA8, 1, 1);
        MetalGL4.glTextureSubImage2D(texture, 0, 0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, new int[]{0xFF808080});
        MetalGL4.glBindTextureUnit(3, texture);
        int commands = MetalGL.glCreateBuffers();
        MetalGL4.glNamedBufferStorage(commands, 40, GL_DYNAMIC_STORAGE_BIT);
        MetalGL4.glNamedBufferSubData(commands, 0, new int[]{6, 1, 0, 0, 1, 6, 1, 0, 0, 2});
        int count = MetalGL.glCreateBuffers();
        MetalGL4.glNamedBufferStorage(count, 4, GL_DYNAMIC_STORAGE_BIT);
        MetalGL4.glNamedBufferSubData(count, 0, new int[]{1});
        MetalGL.glUseProgram(program(VS_INSTANCED, """
                #version 460 core
                flat in uint quad;
                layout(binding = 1, std430) readonly buffer Colors { vec4 colors[]; };
                layout(binding = 3) uniform sampler2D tint;
                out vec4 fragColor;
                void main() { fragColor = colors[quad] * texture(tint, vec2(0.5)); }
                """));
        MetalGL.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commands);
        MetalGL.glBindBuffer(GL_PARAMETER_BUFFER, count);
        MetalGL4.glMultiDrawElementsIndirectCount(GL_TRIANGLES, GL_UNSIGNED_SHORT, 0, 0, 2, 0);
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());
        assertPixel(SIZE - 4, 4, 0, 128, 0, "quad 1 drawn, tinted by the texture");
        assertPixel(4, SIZE - 4, 0, 0, 0, "quad 2 beyond the GPU count");
        assertEquals(icbCalls + 1, MetalGlDevice.ICB_CALLS.get(), "executed as an indirect command buffer");
        assertEquals(fallbackCalls, MetalGlDevice.SLOT_FALLBACK_CALLS.get(), "no per-slot fallback");
    }

    @Test
    void multiDrawIndirectCountWithVertexAndSeveralFragmentTextures() {
        // Vertex-stage texelFetch (offset per quad) + two fragment textures with different samplers, all through the
        // ICB variant's argument buffers; the third texture unit stays unbound (placeholder, black).
        setUpQuads();
        long icbCalls = MetalGlDevice.ICB_CALLS.get();
        int offsets = MetalGL4.glCreateTextures(GL_TEXTURE_2D);
        MetalGL4.glTextureStorage2D(offsets, 1, GL_RGBA8, 4, 1);
        MetalGL4.glTextureSubImage2D(offsets, 0, 0, 0, 4, 1, GL_RGBA, GL_UNSIGNED_BYTE, new int[]{0, 0, 0, 0});
        int red = MetalGL4.glCreateTextures(GL_TEXTURE_2D);
        MetalGL4.glTextureStorage2D(red, 1, GL_RGBA8, 1, 1);
        MetalGL4.glTextureSubImage2D(red, 0, 0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, new int[]{0xFF0000FF});
        int blue = MetalGL4.glCreateTextures(GL_TEXTURE_2D);
        MetalGL4.glTextureStorage2D(blue, 1, GL_RGBA8, 1, 1);
        MetalGL4.glTextureSubImage2D(blue, 0, 0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, new int[]{0xFFFF0000});
        MetalGL4.glTextureParameteri(blue, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        MetalGL4.glBindTextureUnit(0, offsets);
        MetalGL4.glBindTextureUnit(2, red);
        MetalGL4.glBindTextureUnit(5, blue);
        int commands = MetalGL.glCreateBuffers();
        MetalGL4.glNamedBufferStorage(commands, 40, GL_DYNAMIC_STORAGE_BIT);
        MetalGL4.glNamedBufferSubData(commands, 0, new int[]{6, 1, 0, 0, 0, 6, 1, 0, 0, 3});
        int count = MetalGL.glCreateBuffers();
        MetalGL4.glNamedBufferStorage(count, 4, GL_DYNAMIC_STORAGE_BIT);
        MetalGL4.glNamedBufferSubData(count, 0, new int[]{2});
        MetalGL.glUseProgram(program("""
                #version 460 core
                #extension GL_ARB_shader_draw_parameters : require
                layout(binding = 0, std430) readonly buffer Quads { vec4 rects[]; };
                layout(binding = 0) uniform sampler2D offsets;
                flat out uint quad;
                void main() {
                    quad = uint(gl_BaseInstance) + uint(gl_InstanceID);
                    vec4 r = rects[quad] + texelFetch(offsets, ivec2(int(quad), 0), 0);
                    vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
                    gl_Position = vec4(mix(r.xy, r.zw, corner), 0.0, 1.0);
                }
                """, """
                #version 460 core
                flat in uint quad;
                layout(binding = 2) uniform sampler2D redTex;
                layout(binding = 5) uniform sampler2D blueTex;
                layout(binding = 7) uniform sampler2D unbound;
                out vec4 fragColor;
                void main() {
                    fragColor = vec4(texture(redTex, vec2(0.5)).r, texture(unbound, vec2(0.5)).g, texture(blueTex, vec2(0.5)).b, 1.0)
                            * (quad == 0u ? vec4(1.0, 1.0, 0.0, 1.0) : vec4(0.0, 1.0, 1.0, 1.0));
                }
                """));
        MetalGL.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commands);
        MetalGL.glBindBuffer(GL_PARAMETER_BUFFER, count);
        MetalGL4.glMultiDrawElementsIndirectCount(GL_TRIANGLES, GL_UNSIGNED_SHORT, 0, 0, 2, 0);
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());
        assertPixel(4, 4, 255, 0, 0, "quad 0: red texture");
        assertPixel(SIZE - 4, SIZE - 4, 0, 0, 255, "quad 3: blue texture");
        assertPixel(SIZE - 4, 4, 0, 0, 0, "quad 1 not drawn");
        assertEquals(icbCalls + 1, MetalGlDevice.ICB_CALLS.get(), "executed as an indirect command buffer");
    }

    @Test
    void drawElementsIndirectAndBaseInstanceDraws() {
        setUpQuads();
        int commands = MetalGL.glCreateBuffers();
        MetalGL4.glNamedBufferStorage(commands, 20, GL_DYNAMIC_STORAGE_BIT);
        MetalGL4.glNamedBufferSubData(commands, 0, new int[]{6, 1, 0, 0, 3});
        MetalGL.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commands);
        MetalGL4.glDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_SHORT, 0);
        MetalGL4.glDrawElementsInstancedBaseInstance(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0, 1, 1);
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());
        assertPixel(SIZE - 4, SIZE - 4, 255, 255, 0, "indirect draw of quad 3");
        assertPixel(SIZE - 4, 4, 0, 255, 0, "base-instance draw of quad 1");
        assertPixel(4, 4, 0, 0, 0, "quad 0 not drawn");
    }

    @Test
    void stencilMasksSecondDrawToFirstDrawsArea() {
        int fbo = MetalGL4.glCreateFramebuffers();
        int color = MetalGL4.glCreateTextures(GL_TEXTURE_2D);
        MetalGL4.glTextureStorage2D(color, 1, GL_RGBA8, SIZE, SIZE);
        int depthStencil = MetalGL4.glCreateRenderbuffers();
        MetalGL4.glNamedRenderbufferStorage(depthStencil, GL_DEPTH24_STENCIL8, SIZE, SIZE);
        MetalGL4.glNamedFramebufferTexture(fbo, GL_COLOR_ATTACHMENT0, color, 0);
        MetalGL4.glNamedFramebufferRenderbuffer(fbo, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depthStencil);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, MetalGL4.glCheckNamedFramebufferStatus(fbo, GL_FRAMEBUFFER));
        MetalGL4.glClearNamedFramebufferfv(fbo, GL_COLOR, 0, new float[]{0, 0, 0, 1});
        MetalGL4.glClearNamedFramebufferfi(fbo, GL_DEPTH_STENCIL, 0, 1f, 0);
        MetalGL.glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        int vao = MetalGlDeviceTest.quad(-1, -1, 0, 0);
        int p = program(MetalGlDeviceTest.VS, MetalGlDeviceTest.FS_COLOR);
        MetalGL.glUseProgram(p);
        // 1: bottom-left quarter writes stencil 1 (colour masked off).
        MetalGL.glEnable(GL_STENCIL_TEST);
        MetalGL4.glStencilFunc(GL_ALWAYS, 1, 0xFF);
        MetalGL4.glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
        MetalGL.glColorMask(false, false, false, false);
        MetalGL.glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0);
        // 2: full-screen green, only where stencil == 1.
        MetalGL.glColorMask(true, true, true, true);
        MetalGL4.glStencilFunc(GL_EQUAL, 1, 0xFF);
        MetalGL4.glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        MetalGL.glBindVertexArray(MetalGlDeviceTest.quad(-1, -1, 1, 1));
        MetalGL.glUniform4f(MetalGL.glGetUniformLocation(p, "uColor"), 0, 1, 0, 1);
        MetalGL.glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0);
        MetalGL.glDisable(GL_STENCIL_TEST);
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());
        assertPixel(SIZE / 4, SIZE / 4, 0, 255, 0, "inside the stencilled quarter");
        assertPixel(3 * SIZE / 4, 3 * SIZE / 4, 0, 0, 0, "outside");
    }

    @Test
    void clipControlZeroToOneSkipsTheDepthRemap() {
        int p = program(MetalGlDeviceTest.VS, MetalGlDeviceTest.FS_COLOR);
        MetalGL.glUseProgram(p);
        MetalGL.glBindVertexArray(MetalGlDeviceTest.quad(-1, -1, 1, 1));
        MetalGL.glUniform1f(MetalGL.glGetUniformLocation(p, "uDepth"), 0.5f);
        MetalGL.glUniform4f(MetalGL.glGetUniformLocation(p, "uColor"), 0, 1, 0, 1);
        MetalGL.glEnable(GL_DEPTH_TEST);
        MetalGL.glDepthFunc(GL_LESS);
        MetalGL.glClearColor(0, 0, 0, 1);
        MetalGL.glClearDepth(0.6);
        // GL default (-1..1): z 0.5 → depth 0.75, behind the cleared 0.6.
        MetalGL.glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        MetalGL.glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0);
        assertPixel(SIZE / 2, SIZE / 2, 0, 0, 0, "-1..1: depth 0.75 fails LESS 0.6");
        // GL_ZERO_TO_ONE: depth 0.5, in front.
        MetalGL4.glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE);
        MetalGL.glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        MetalGL.glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0);
        assertEquals(GL_ZERO_TO_ONE, MetalGL.glGetInteger(GL_CLIP_DEPTH_MODE));
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());
        assertPixel(SIZE / 2, SIZE / 2, 0, 255, 0, "0..1: depth 0.5 passes LESS 0.6");
    }

    @Test
    void glDepthRangeBuiltinFollowsGlDepthRange() {
        int p = program(MetalGlDeviceTest.VS, """
                #version 330 core
                out vec4 fragColor;
                void main() { fragColor = vec4(gl_DepthRange.near, gl_DepthRange.far, gl_DepthRange.diff, 1.0); }
                """);
        MetalGL.glUseProgram(p);
        MetalGL.glBindVertexArray(MetalGlDeviceTest.quad(-1, -1, 1, 1));
        MetalGL.glDepthRange(0.25, 0.75);
        MetalGL.glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0);
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());
        assertPixel(SIZE / 2, SIZE / 2, 64, 191, 128, "near, far, diff");
    }

    @Test
    void blitCopiesFramebufferAndTextureViewSharesStorage() {
        int src = MetalGL4.glCreateTextures(GL_TEXTURE_2D);
        MetalGL4.glTextureStorage2D(src, 1, GL_RGBA8, SIZE, SIZE);
        int fbo = MetalGL4.glCreateFramebuffers();
        MetalGL4.glNamedFramebufferTexture(fbo, GL_COLOR_ATTACHMENT0, src, 0);
        MetalGL4.glClearNamedFramebufferfv(fbo, GL_COLOR, 0, new float[]{1, 0, 1, 1});
        MetalGL4.glBlitNamedFramebuffer(fbo, 0, 0, 0, SIZE, SIZE, 0, 0, SIZE, SIZE, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        assertPixel(10, 10, 255, 0, 255, "blitted to framebuffer 0");
        int view = MetalGL.glGenTextures();
        MetalGL4.glTextureView(view, GL_TEXTURE_2D, src, GL_RGBA8, 0, 1, 0, 1);
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());
        ByteBuffer out = MemoryUtil.memAlloc(SIZE * SIZE * 4);
        MetalGL4.glGetTextureImage(view, 0, GL_RGBA, GL_UNSIGNED_BYTE, out);
        assertEquals((byte) 255, out.get(0));
        assertEquals((byte) 0, out.get(1));
        assertEquals((byte) 255, out.get(2));
        MemoryUtil.memFree(out);
    }
}
