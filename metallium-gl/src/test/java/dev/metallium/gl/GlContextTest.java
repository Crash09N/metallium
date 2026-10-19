/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL46C.*;

/** GL object/state/error semantics of {@link GlContext}, through the {@link MetalGL} facade, without a GPU. */
class GlContextTest {
    static final String VS = """
            #version 330 core
            layout(location = 0) in vec2 aPos;
            in vec2 aUv;
            out vec2 vUv;
            uniform vec2 uOffset;
            void main() { vUv = aUv; gl_Position = vec4(aPos + uOffset, 0.0, 1.0); }
            """;
    static final String FS = """
            #version 330 core
            in vec2 vUv;
            out vec4 fragColor;
            uniform vec4 uColor;
            uniform mat3 uMatrix;
            uniform float uWeights[3];
            uniform sampler2D uTex;
            void main() { fragColor = uColor * texture(uTex, vUv) * vec4(uMatrix[1], uWeights[2]); }
            """;

    RecordingDevice device;

    @BeforeEach
    void setUp() {
        device = new RecordingDevice();
        GlContext.makeCurrent(new GlContext(device));
    }

    @AfterEach
    void tearDown() {
        GlContext.makeCurrent(null);
    }

    @Test
    void namesAreReservedByGenAndCreatedByBind() {
        int a = MetalGL.glGenBuffers();
        int b = MetalGL.glGenBuffers();
        assertNotEquals(0, a);
        assertNotEquals(a, b);
        assertFalse(MetalGL.glIsBuffer(a), "GL creates the object on first bind");
        MetalGL.glBindBuffer(GL_ARRAY_BUFFER, a);
        assertTrue(MetalGL.glIsBuffer(a));
        assertEquals(a, MetalGL.glGetInteger(GL_ARRAY_BUFFER_BINDING));
        MetalGL.glBindBuffer(GL_ARRAY_BUFFER, 12345);
        assertEquals(GL_INVALID_OPERATION, MetalGL.glGetError(), "core profile: binding a name never generated");
        assertEquals(GL_NO_ERROR, MetalGL.glGetError(), "glGetError clears the flag");
        MetalGL.glDeleteBuffers(a);
        assertEquals(0, MetalGL.glGetInteger(GL_ARRAY_BUFFER_BINDING), "deleting a bound buffer unbinds it");
    }

    @Test
    void firstErrorWinsUntilQueried() {
        MetalGL.glEnable(0x7777);
        MetalGL.glBindTexture(GL_TEXTURE_2D, 999);
        assertEquals(GL_INVALID_ENUM, MetalGL.glGetError());
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());
    }

    @Test
    void bufferDataSubDataAndMapping() {
        int buffer = MetalGL.glGenBuffers();
        MetalGL.glBindBuffer(GL_ARRAY_BUFFER, buffer);
        MetalGL.glBufferData(GL_ARRAY_BUFFER, new float[]{1f, 2f, 3f, 4f}, GL_STATIC_DRAW);
        FloatBuffer two = MemoryUtil.memAllocFloat(1).put(0, 9f);
        MetalGL.glBufferSubData(GL_ARRAY_BUFFER, 4, two);
        ByteBuffer mapped = MetalGL.glMapBufferRange(GL_ARRAY_BUFFER, 0, 16, GL_MAP_READ_BIT);
        assertNotNull(mapped);
        assertEquals(9f, mapped.getFloat(4));
        assertEquals(3f, mapped.getFloat(8));
        MetalGL.glMapBufferRange(GL_ARRAY_BUFFER, 0, 16, GL_MAP_READ_BIT);
        assertEquals(GL_INVALID_OPERATION, MetalGL.glGetError(), "already mapped");
        assertTrue(MetalGL.glUnmapBuffer(GL_ARRAY_BUFFER));
        MetalGL.glBufferSubData(GL_ARRAY_BUFFER, 12, MemoryUtil.memAllocFloat(2));
        assertEquals(GL_INVALID_VALUE, MetalGL.glGetError(), "range past the end");
        MemoryUtil.memFree(two);
    }

    @Test
    void subDataOnABufferTheGpuStillReadsGetsFreshStorage() {
        int buffer = MetalGL.glGenBuffers();
        MetalGL.glBindBuffer(GL_ARRAY_BUFFER, buffer);
        MetalGL.glBufferData(GL_ARRAY_BUFFER, new float[]{1f, 2f}, GL_DYNAMIC_DRAW);
        GlObjects.Buffer object = GlContext.current().buffers.get(buffer);
        GlDevice.BufferStore before = object.store;
        device.gpuBusy = true;
        FloatBuffer value = MemoryUtil.memAllocFloat(1).put(0, 5f);
        MetalGL.glBufferSubData(GL_ARRAY_BUFFER, 0, value);
        assertNotSame(before, object.store, "renamed instead of overwriting memory the GPU reads");
        assertTrue(device.released.contains(before));
        assertEquals(5f, object.store.contents().order(ByteOrder.nativeOrder()).getFloat(0));
        assertEquals(2f, object.store.contents().order(ByteOrder.nativeOrder()).getFloat(4), "old contents copied");
        assertEquals(1f, before.contents().order(ByteOrder.nativeOrder()).getFloat(0), "the GPU's copy is untouched");
        MemoryUtil.memFree(value);
    }

    @Test
    void persistentMappingReturnsTheStorageAndIsNeverRenamed() {
        int buffer = MetalGL.glGenBuffers();
        MetalGL.glBindBuffer(GL_ARRAY_BUFFER, buffer);
        int flags = GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
        MetalGL.glBufferStorage(GL_ARRAY_BUFFER, 64, flags);
        device.gpuBusy = true;
        ByteBuffer mapped = MetalGL.glMapBufferRange(GL_ARRAY_BUFFER, 16, 32, flags);
        assertNotNull(mapped);
        GlObjects.Buffer object = GlContext.current().buffers.get(buffer);
        mapped.putInt(0, 42);
        assertEquals(42, object.store.contents().order(ByteOrder.nativeOrder()).getInt(16));
        MetalGL.glBufferData(GL_ARRAY_BUFFER, 8, GL_STATIC_DRAW);
        assertEquals(GL_INVALID_OPERATION, MetalGL.glGetError(), "immutable storage");
    }

    @Test
    void texturesConvertPixelsAndValidate() {
        int texture = MetalGL.glGenTextures();
        MetalGL.glBindTexture(GL_TEXTURE_2D, texture);
        ByteBuffer rgb = MemoryUtil.memAlloc(2 * 2 * 3);
        for (int i = 0; i < 12; i++) rgb.put(i, (byte) (i + 1));
        MetalGL.glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        MetalGL.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB8, 2, 2, 0, GL_RGB, GL_UNSIGNED_BYTE, rgb);
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());
        assertEquals("RGBA8 L0 0,0 2x2 row=8 [1,2,3,255,4,5,6,255]", device.uploads.getLast(), "RGB expanded to RGBA with alpha 1");
        assertEquals(2, MetalGL.glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH));
        MetalGL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        assertEquals(GL_LINEAR, MetalGL.glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER));
        MetalGL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        assertEquals(GL_INVALID_ENUM, MetalGL.glGetError(), "mag filter cannot use mipmaps");
        MetalGL.glTexSubImage2D(GL_TEXTURE_2D, 0, 1, 1, 2, 2, GL_RGB, GL_UNSIGNED_BYTE, rgb);
        assertEquals(GL_INVALID_VALUE, MetalGL.glGetError(), "sub-image outside the level");
        ByteBuffer bgra = MemoryUtil.memAlloc(4).put(0, (byte) 10).put(1, (byte) 20).put(2, (byte) 30).put(3, (byte) 40);
        MetalGL.glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL_BGRA, GL_UNSIGNED_BYTE, bgra);
        assertEquals("RGBA8 L0 0,0 1x1 row=4 [30,20,10,40]", device.uploads.getLast(), "BGRA swizzled");
        MetalGL.glGenerateMipmap(GL_TEXTURE_2D);
        assertEquals("mipmaps 2", device.uploads.getLast());
        MetalGL.glTexImage2D(GL_TEXTURE_2D, 0, 0x8C3A /* R11F_G11F_B10F */, 2, 2, 0, GL_RGB, GL_FLOAT, (ByteBuffer) null);
        assertEquals(GL_INVALID_ENUM, MetalGL.glGetError(), "unsupported internal format is reported, not ignored");
        MemoryUtil.memFree(rgb);
        MemoryUtil.memFree(bgra);
    }

    @Test
    void framebufferCompleteness() {
        int fbo = MetalGL.glGenFramebuffers();
        MetalGL.glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        assertEquals(GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT, MetalGL.glCheckFramebufferStatus(GL_FRAMEBUFFER));
        int color = MetalGL.glGenTextures();
        MetalGL.glBindTexture(GL_TEXTURE_2D, color);
        MetalGL.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
        assertEquals(GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT, MetalGL.glCheckFramebufferStatus(GL_FRAMEBUFFER), "texture without storage");
        MetalGL.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 16, 16, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, MetalGL.glCheckFramebufferStatus(GL_FRAMEBUFFER));
        int depth = MetalGL.glGenRenderbuffers();
        MetalGL.glBindRenderbuffer(GL_RENDERBUFFER, depth);
        MetalGL.glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, 16, 16);
        MetalGL.glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depth);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, MetalGL.glCheckFramebufferStatus(GL_FRAMEBUFFER));
        MetalGL.glDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1});
        assertEquals(GL_FRAMEBUFFER_COMPLETE, MetalGL.glCheckFramebufferStatus(GL_FRAMEBUFFER), "GL 4.1+: missing draw-buffer attachments discard writes");
        MetalGL.glDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0});
        MetalGL.glClearColor(0.25f, 0.5f, 0.75f, 1f);
        MetalGL.glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        GlDevice.ClearOp clear = device.clears.getLast();
        assertTrue(clear.color() && clear.depth() && !clear.stencil());
        assertArrayEquals(new float[]{0.25f, 0.5f, 0.75f, 1f}, clear.rgba());
        MetalGL.glBindFramebuffer(GL_FRAMEBUFFER, 0);
        assertEquals(0, MetalGL.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
    }

    @Test
    void shadersCompileWithInfoLog() {
        int shader = MetalGL.glCreateShader(GL_FRAGMENT_SHADER);
        MetalGL.glShaderSource(shader, "#version 330 core\nout vec4 c; void main() { c = undefined_thing; }");
        MetalGL.glCompileShader(shader);
        assertEquals(GL_FALSE, MetalGL.glGetShaderi(shader, GL_COMPILE_STATUS));
        assertTrue(MetalGL.glGetShaderInfoLog(shader).contains("undefined_thing"), MetalGL.glGetShaderInfoLog(shader));
        assertEquals(0, MetalGL.glCreateShader(GL_GEOMETRY_SHADER));
        assertEquals(GL_INVALID_ENUM, MetalGL.glGetError());
        int compute = MetalGL.glCreateShader(GL_COMPUTE_SHADER);
        assertNotEquals(0, compute);
        MetalGL.glShaderSource(compute, "#version 430\n#extension GL_ARB_gpu_shader_int64 : require\nlayout(local_size_x=32) in;\nvoid main() { uint64_t a = 1234; }\n");
        MetalGL.glCompileShader(compute);
        assertEquals(GL_FALSE, MetalGL.glGetShaderi(compute, GL_COMPILE_STATUS), "int64 is not supported, so it must not compile");
        assertTrue(MetalGL.glGetShaderInfoLog(compute).contains("GL_ARB_gpu_shader_int64"), MetalGL.glGetShaderInfoLog(compute));
        // "enable" of an unsupported extension: a warning in GL, and its macro is undefined.
        int optional = MetalGL.glCreateShader(GL_COMPUTE_SHADER);
        MetalGL.glShaderSource(optional, "#version 450\n#extension GL_ARB_gpu_shader_int64 : enable\n#ifdef GL_ARB_gpu_shader_int64\n"
                + "#error int64 must look unsupported\n#endif\n#ifdef NOT_DEFINED\n#extension GL_NV_fragment_shader_barycentric : require\n#endif\n"
                + "layout(local_size_x=32) in;\nvoid main() { }\n");
        MetalGL.glCompileShader(optional);
        assertEquals(GL_TRUE, MetalGL.glGetShaderi(optional, GL_COMPILE_STATUS), MetalGL.glGetShaderInfoLog(optional));
    }

    static int linkTestProgram() {
        int vs = MetalGL.glCreateShader(GL_VERTEX_SHADER);
        MetalGL.glShaderSource(vs, VS);
        MetalGL.glCompileShader(vs);
        assertEquals(GL_TRUE, MetalGL.glGetShaderi(vs, GL_COMPILE_STATUS), MetalGL.glGetShaderInfoLog(vs));
        int fs = MetalGL.glCreateShader(GL_FRAGMENT_SHADER);
        MetalGL.glShaderSource(fs, FS);
        MetalGL.glCompileShader(fs);
        assertEquals(GL_TRUE, MetalGL.glGetShaderi(fs, GL_COMPILE_STATUS), MetalGL.glGetShaderInfoLog(fs));
        int program = MetalGL.glCreateProgram();
        MetalGL.glAttachShader(program, vs);
        MetalGL.glAttachShader(program, fs);
        MetalGL.glBindAttribLocation(program, 3, "aUv");
        MetalGL.glLinkProgram(program);
        assertEquals(GL_TRUE, MetalGL.glGetProgrami(program, GL_LINK_STATUS), MetalGL.glGetProgramInfoLog(program));
        MetalGL.glDeleteShader(vs);
        MetalGL.glDeleteShader(fs);
        return program;
    }

    @Test
    void programReflectionAndUniformWrites() {
        int program = linkTestProgram();
        assertEquals(0, MetalGL.glGetAttribLocation(program, "aPos"));
        assertEquals(3, MetalGL.glGetAttribLocation(program, "aUv"), "glBindAttribLocation applied");
        assertEquals(-1, MetalGL.glGetUniformLocation(program, "doesNotExist"));
        int color = MetalGL.glGetUniformLocation(program, "uColor");
        int matrix = MetalGL.glGetUniformLocation(program, "uMatrix");
        int weights2 = MetalGL.glGetUniformLocation(program, "uWeights[2]");
        int sampler = MetalGL.glGetUniformLocation(program, "uTex");
        assertTrue(color >= 0 && matrix >= 0 && weights2 >= 0 && sampler >= 0);
        assertEquals(MetalGL.glGetUniformLocation(program, "uWeights") + 2, weights2);

        MetalGL.glUniform4f(color, 1f, 0.5f, 0.25f, 1f);
        assertEquals(GL_INVALID_OPERATION, MetalGL.glGetError(), "no program in use yet");
        MetalGL.glUseProgram(program);
        MetalGL.glUniform4f(color, 1f, 0.5f, 0.25f, 1f);
        MetalGL.glUniformMatrix3fv(matrix, false, new float[]{1, 2, 3, 4, 5, 6, 7, 8, 9});
        MetalGL.glUniform1f(weights2, 7f);
        MetalGL.glUniform1i(sampler, 5);
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());
        MetalGL.glUniform1i(color, 1);
        assertEquals(GL_INVALID_OPERATION, MetalGL.glGetError(), "int setter on a vec4");
        MetalGL.glUniform3f(color, 1, 2, 3);
        assertEquals(GL_INVALID_OPERATION, MetalGL.glGetError(), "size mismatch");

        GlObjects.Program object = (GlObjects.Program) GlContext.current().shaderObjects.get(program);
        GlProgramLinker.Uniform uColor = find(object, "uColor");
        GlProgramLinker.Uniform uMatrix = find(object, "uMatrix");
        GlProgramLinker.Uniform uWeights = find(object, "uWeights");
        ByteBuffer fs = object.fragmentUniforms;
        assertEquals(0.5f, fs.getFloat(uColor.fragment().offset() + 4));
        int stride = uMatrix.fragment().member().matrixStride();
        assertEquals(16, stride, "std140 mat3 columns are vec4-aligned");
        assertEquals(4f, fs.getFloat(uMatrix.fragment().offset() + stride), "column 1 starts after one padded column");
        assertEquals(6f, fs.getFloat(uMatrix.fragment().offset() + stride + 8));
        assertEquals(7f, fs.getFloat(uWeights.fragment().offset() + 2 * uWeights.fragment().member().arrayStride()));
        assertEquals(5, object.samplerUnits[sampler]);
        assertNull(uColor.vertex(), "uColor is fragment-only");
        assertNotNull(find(object, "uOffset").vertex());
    }

    private static GlProgramLinker.Uniform find(final GlObjects.Program program, final String name) {
        return program.linkedProgram.uniforms().stream().filter(u -> u.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void drawResolvesStateIntoADrawOp() {
        int program = linkTestProgram();
        int vao = MetalGL.glGenVertexArrays();
        MetalGL.glBindVertexArray(vao);
        int vbo = MetalGL.glGenBuffers();
        MetalGL.glBindBuffer(GL_ARRAY_BUFFER, vbo);
        MetalGL.glBufferData(GL_ARRAY_BUFFER, new float[]{0, 0, 0, 0, 1, 0, 1, 0, 1, 1, 1, 1}, GL_STATIC_DRAW);
        MetalGL.glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0);
        MetalGL.glVertexAttribPointer(3, 2, GL_FLOAT, false, 16, 8);
        MetalGL.glEnableVertexAttribArray(0);
        MetalGL.glEnableVertexAttribArray(3);

        MetalGL.glDrawArrays(GL_TRIANGLES, 0, 3);
        assertEquals(GL_INVALID_OPERATION, MetalGL.glGetError(), "no program");
        assertTrue(device.draws.isEmpty());

        int texture = MetalGL.glGenTextures();
        MetalGL.glActiveTexture(GL_TEXTURE2);
        MetalGL.glBindTexture(GL_TEXTURE_2D, texture);
        MetalGL.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 4, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        MetalGL.glUseProgram(program);
        MetalGL.glUniform1i(MetalGL.glGetUniformLocation(program, "uTex"), 2);
        MetalGL.glEnable(GL_BLEND);
        MetalGL.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        MetalGL.glViewport(1, 2, 30, 20);
        MetalGL.glEnable(GL_SCISSOR_TEST);
        MetalGL.glScissor(3, 4, 5, 6);
        MetalGL.glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());

        GlDevice.DrawOp op = device.draws.getLast();
        assertEquals(GL_TRIANGLES, op.glPrimitive(), "fan converted to a triangle list");
        assertEquals(6, op.count());
        assertNotNull(op.indexBuffer());
        assertArrayEquals(new int[]{0, 1, 2, 0, 2, 3}, ints(op.indexBuffer().contents(), 6));
        assertEquals(2, op.attributes().length);
        GlDevice.VertexAttrib uv = java.util.Arrays.stream(op.attributes()).filter(a -> a.location() == 3).findFirst().orElseThrow();
        assertEquals(8, uv.offset());
        assertEquals(16, uv.stride());
        assertArrayEquals(new int[]{1, 2, 30, 20}, op.raster().viewport());
        assertArrayEquals(new int[]{3, 4, 5, 6}, op.raster().scissor());
        assertTrue(op.raster().blend()[0].enabled());
        assertEquals(GL_SRC_ALPHA, op.raster().blend()[0].srcRgb());
        assertEquals(1, op.fragment().textures().length);
        assertSame(GlContext.current().textures.get(texture).store, op.fragment().textures()[0].texture(), "unit 2 texture bound to the sampler");
        assertSame(device.defaultColor, op.targets().colors()[0], "framebuffer 0 = the host's target");

        MetalGL.glDisableVertexAttribArray(3);
        MetalGL.glVertexAttrib2f(3, 0.5f, 0.25f);
        MetalGL.glDrawArrays(GL_TRIANGLES, 0, 3);
        GlDevice.VertexAttrib constant = java.util.Arrays.stream(device.draws.getLast().attributes()).filter(a -> a.location() == 3).findFirst().orElseThrow();
        assertNull(constant.buffer());
        assertArrayEquals(new float[]{0.5f, 0.25f, 0f, 1f}, constant.constant());

        MetalGL.glDrawElements(GL_TRIANGLES, 3, GL_UNSIGNED_SHORT, 0L);
        assertEquals(GL_INVALID_OPERATION, MetalGL.glGetError(), "no element buffer bound");
    }

    private static int[] ints(final ByteBuffer buffer, final int count) {
        int[] out = new int[count];
        buffer.order(ByteOrder.nativeOrder()).asIntBuffer().get(out);
        return out;
    }

    @Test
    void queriesClaimGl46CoreButOnlyImplementedExtensions() {
        assertEquals(4, MetalGL.glGetInteger(GL_MAJOR_VERSION));
        assertEquals(6, MetalGL.glGetInteger(GL_MINOR_VERSION));
        assertTrue(MetalGL.glGetString(GL_VERSION).startsWith("4.6"));
        int n = MetalGL.glGetInteger(GL_NUM_EXTENSIONS);
        assertTrue(n > 0);
        java.util.Set<String> extensions = new java.util.HashSet<>();
        for (int i = 0; i < n; i++) {
            extensions.add(MetalGL.glGetStringi(GL_EXTENSIONS, i));
        }
        assertTrue(extensions.containsAll(java.util.Set.of("GL_ARB_compute_shader", "GL_ARB_shader_storage_buffer_object", "GL_ARB_indirect_parameters")),
                "extensions: " + extensions);
        assertTrue(extensions.stream().noneMatch(e -> e.startsWith("GL_NV_") || e.contains("sparse") || e.contains("int64")),
                "only implemented extensions may be claimed: " + extensions);
        assertTrue(MetalGL.getCapabilities().OpenGL46);
        assertTrue(MetalGL.getCapabilities().GL_ARB_buffer_storage);
        assertTrue(MetalGL.getCapabilities().GL_ARB_compute_shader);
        assertFalse(MetalGL.getCapabilities().GL_ARB_sparse_buffer);
        assertFalse(MetalGL.getCapabilities().GL_NV_mesh_shader);
        assertNotEquals(0L, MetalGL.getCapabilities().glBindBuffer);
        assertNotEquals(0L, MetalGL.getCapabilities().glDispatchComputeIndirect, "implemented in MetalGL4");
        assertNotEquals(0L, MetalGL.getCapabilities().glMultiDrawElementsIndirectCountARB);
        assertEquals(0L, MetalGL.getCapabilities().glBufferPageCommitmentARB);
    }

    @Test
    void syncObjects() {
        device.gpuBusy = true;
        long sync = MetalGL.glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        assertEquals(GL_TIMEOUT_EXPIRED, MetalGL.glClientWaitSync(sync, 0, 0));
        assertEquals(GL_UNSIGNALED, MetalGL.glGetSynci(sync, GL_SYNC_STATUS, null));
        device.gpuBusy = false;
        assertEquals(GL_ALREADY_SIGNALED, MetalGL.glClientWaitSync(sync, 0, 0));
        MetalGL.glDeleteSync(sync);
        assertFalse(MetalGL.glIsSync(sync));
    }
}
