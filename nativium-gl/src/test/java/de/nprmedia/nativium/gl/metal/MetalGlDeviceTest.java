/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.gl.metal;

import de.nprmedia.nativium.gl.GlContext;
import de.nprmedia.nativium.gl.MetalGL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL46C.*;

/**
 * End to end on a real Metal device (macOS only): GLSL 330 programs, buffers, VAOs, textures, FBOs, clears and state go
 * through nativium-gl into an offscreen 64x64 target, then glReadPixels checks the pixels. GL conventions are checked
 * explicitly: window origin bottom-left, texture row 0 at the bottom, CCW front faces, GL clip-space depth.
 */
class MetalGlDeviceTest {
    static final int SIZE = 64;
    static final String VS = """
            #version 330 core
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aUv;
            out vec2 vUv;
            uniform float uDepth;
            void main() { vUv = aUv; gl_Position = vec4(aPos, uDepth, 1.0); }
            """;
    static final String FS_COLOR = """
            #version 330 core
            out vec4 fragColor;
            uniform vec4 uColor;
            void main() { fragColor = uColor; }
            """;
    static final String FS_TEXTURE = """
            #version 330 core
            in vec2 vUv;
            out vec4 fragColor;
            uniform sampler2D uTex;
            void main() { fragColor = texture(uTex, vUv); }
            """;

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

    static int program(final String vs, final String fs) {
        int v = MetalGL.glCreateShader(GL_VERTEX_SHADER);
        MetalGL.glShaderSource(v, vs);
        MetalGL.glCompileShader(v);
        int f = MetalGL.glCreateShader(GL_FRAGMENT_SHADER);
        MetalGL.glShaderSource(f, fs);
        MetalGL.glCompileShader(f);
        int p = MetalGL.glCreateProgram();
        MetalGL.glAttachShader(p, v);
        MetalGL.glAttachShader(p, f);
        MetalGL.glLinkProgram(p);
        assertEquals(GL_TRUE, MetalGL.glGetProgrami(p, GL_LINK_STATUS), MetalGL.glGetProgramInfoLog(p));
        return p;
    }

    /** A quad from (x0,y0) to (x1,y1) in NDC with UVs 0..1, counter-clockwise, drawn as indexed triangles. */
    static int quad(final float x0, final float y0, final float x1, final float y1) {
        int vao = MetalGL.glGenVertexArrays();
        MetalGL.glBindVertexArray(vao);
        int vbo = MetalGL.glGenBuffers();
        MetalGL.glBindBuffer(GL_ARRAY_BUFFER, vbo);
        MetalGL.glBufferData(GL_ARRAY_BUFFER, new float[]{x0, y0, 0, 0, x1, y0, 1, 0, x1, y1, 1, 1, x0, y1, 0, 1}, GL_STATIC_DRAW);
        MetalGL.glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0);
        MetalGL.glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8);
        MetalGL.glEnableVertexAttribArray(0);
        MetalGL.glEnableVertexAttribArray(1);
        int ebo = MetalGL.glGenBuffers();
        MetalGL.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        ByteBuffer indices = MemoryUtil.memAlloc(12);
        indices.asShortBuffer().put(new short[]{0, 1, 2, 0, 2, 3});
        MetalGL.glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
        MemoryUtil.memFree(indices);
        return vao;
    }

    static int[] pixel(final int x, final int y) {
        ByteBuffer out = MemoryUtil.memAlloc(4);
        MetalGL.glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, out);
        int[] rgba = {out.get(0) & 0xFF, out.get(1) & 0xFF, out.get(2) & 0xFF, out.get(3) & 0xFF};
        MemoryUtil.memFree(out);
        return rgba;
    }

    static void assertPixel(final int x, final int y, final int r, final int g, final int b, final String what) {
        int[] p = pixel(x, y);
        assertTrue(Math.abs(p[0] - r) <= 2 && Math.abs(p[1] - g) <= 2 && Math.abs(p[2] - b) <= 2,
                what + " at " + x + "," + y + ": expected " + r + "," + g + "," + b + " got " + java.util.Arrays.toString(p));
    }

    @Test
    void clearAndColoredQuadWithViewportInWindowCoordinates() {
        MetalGL.glClearColor(0f, 0f, 1f, 1f);
        MetalGL.glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        int p = program(VS, FS_COLOR);
        MetalGL.glUseProgram(p);
        MetalGL.glUniform4f(MetalGL.glGetUniformLocation(p, "uColor"), 1f, 0f, 1f, 1f);
        MetalGL.glBindVertexArray(quad(-1, -1, 1, 1));
        MetalGL.glViewport(0, 0, SIZE / 2, SIZE / 2); // GL: bottom-left quarter
        MetalGL.glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0L);
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());
        assertPixel(8, 8, 255, 0, 255, "bottom-left quarter (GL window origin)");
        assertPixel(8, SIZE - 8, 0, 0, 255, "top-left stays clear colour");
        assertPixel(SIZE - 8, 8, 0, 0, 255, "bottom-right stays clear colour");
    }

    @Test
    void textureRowZeroIsTheBottomRow() {
        int texture = MetalGL.glGenTextures();
        MetalGL.glBindTexture(GL_TEXTURE_2D, texture);
        MetalGL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        MetalGL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        ByteBuffer texels = MemoryUtil.memAlloc(16);
        // Row 0 (bottom in GL): red, green; row 1 (top): blue, yellow.
        texels.put(new byte[]{(byte) 255, 0, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255, 0, 0, (byte) 255, (byte) 255, (byte) 255, (byte) 255, 0, (byte) 255}).flip();
        MetalGL.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 2, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, texels);
        MemoryUtil.memFree(texels);
        int p = program(VS, FS_TEXTURE);
        MetalGL.glUseProgram(p);
        MetalGL.glUniform1i(MetalGL.glGetUniformLocation(p, "uTex"), 0);
        MetalGL.glBindVertexArray(quad(-1, -1, 1, 1));
        MetalGL.glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0L);
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());
        assertPixel(8, 8, 255, 0, 0, "bottom-left texel");
        assertPixel(SIZE - 8, 8, 0, 255, 0, "bottom-right texel");
        assertPixel(8, SIZE - 8, 0, 0, 255, "top-left texel");
        assertPixel(SIZE - 8, SIZE - 8, 255, 255, 0, "top-right texel");
    }

    /**
     * GL samples from GL_TEXTURE_BASE_LEVEL up (Voxy's HiZ chain reads level i-1 through BASE_LEVEL = MAX_LEVEL = i-1
     * while it renders level i); texelFetch's lod is relative to the base level too.
     */
    @Test
    void samplingStartsAtTheBaseLevel() {
        int texture = MetalGL.glGenTextures();
        MetalGL.glBindTexture(GL_TEXTURE_2D, texture);
        MetalGL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
        MetalGL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        byte[][] colours = {{(byte) 255, 0, 0, (byte) 255}, {0, (byte) 255, 0, (byte) 255}, {0, 0, (byte) 255, (byte) 255}};
        for (int level = 0; level < 3; level++) {
            int size = 4 >> level;
            ByteBuffer texels = MemoryUtil.memAlloc(size * size * 4);
            for (int i = 0; i < size * size; i++) texels.put(colours[level]);
            texels.flip();
            MetalGL.glTexImage2D(GL_TEXTURE_2D, level, GL_RGBA8, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, texels);
            MemoryUtil.memFree(texels);
        }
        int p = program(VS, FS_TEXTURE);
        MetalGL.glUseProgram(p);
        MetalGL.glUniform1i(MetalGL.glGetUniformLocation(p, "uTex"), 0);
        MetalGL.glBindVertexArray(quad(-1, -1, 1, 1));
        MetalGL.glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0L);
        assertPixel(SIZE / 2, SIZE / 2, 255, 0, 0, "base level 0 (magnified)");
        MetalGL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 1);
        MetalGL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 1);
        MetalGL.glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0L);
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());
        assertPixel(SIZE / 2, SIZE / 2, 0, 255, 0, "base level 1");
        MetalGL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 2);
        MetalGL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 1000);
        MetalGL.glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0L);
        assertPixel(SIZE / 2, SIZE / 2, 0, 0, 255, "base level 2");
    }

    @Test
    void unboundSamplerReadsOpaqueBlack() {
        MetalGL.glClearColor(1f, 1f, 1f, 1f);
        MetalGL.glClear(GL_COLOR_BUFFER_BIT);
        int p = program(VS, FS_TEXTURE);
        MetalGL.glUseProgram(p);
        MetalGL.glBindVertexArray(quad(-1, -1, 1, 1));
        MetalGL.glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0L); // first use of the placeholder uploads it mid-frame
        assertPixel(32, 32, 0, 0, 0, "GL samples an unbound texture as (0,0,0,1)");
    }

    @Test
    void backFaceCullingUsesGlWinding() {
        MetalGL.glClearColor(0f, 0f, 0f, 1f);
        MetalGL.glClear(GL_COLOR_BUFFER_BIT);
        int p = program(VS, FS_COLOR);
        MetalGL.glUseProgram(p);
        MetalGL.glUniform4f(MetalGL.glGetUniformLocation(p, "uColor"), 0f, 1f, 0f, 1f);
        MetalGL.glEnable(GL_CULL_FACE);
        MetalGL.glCullFace(GL_BACK);
        MetalGL.glBindVertexArray(quad(-1, -1, 0, 1)); // counter-clockwise = front: visible
        MetalGL.glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0L);
        MetalGL.glBindVertexArray(quad(1, -1, 0, 1)); // x mirrored: clockwise = back: culled
        MetalGL.glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0L);
        assertPixel(16, 32, 0, 255, 0, "CCW quad is front-facing");
        assertPixel(48, 32, 0, 0, 0, "CW quad is culled");
    }

    @Test
    void depthTestUsesGlClipSpace() {
        MetalGL.glClearDepth(1.0);
        MetalGL.glClearColor(0f, 0f, 0f, 1f);
        MetalGL.glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        int p = program(VS, FS_COLOR);
        MetalGL.glUseProgram(p);
        MetalGL.glEnable(GL_DEPTH_TEST);
        MetalGL.glDepthFunc(GL_LESS);
        MetalGL.glBindVertexArray(quad(-1, -1, 1, 1));
        int color = MetalGL.glGetUniformLocation(p, "uColor");
        int depth = MetalGL.glGetUniformLocation(p, "uDepth");
        MetalGL.glUniform1f(depth, -0.5f); // GL NDC z -0.5 → window depth 0.25 (would be clipped without the GL clip-space fixup)
        MetalGL.glUniform4f(color, 1f, 0f, 0f, 1f);
        MetalGL.glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0L);
        MetalGL.glUniform1f(depth, 0.5f); // farther: fails the depth test
        MetalGL.glUniform4f(color, 0f, 1f, 0f, 1f);
        MetalGL.glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0L);
        assertPixel(32, 32, 255, 0, 0, "near quad drawn, far quad depth-rejected");
    }

    /**
     * A depth-stencil texture attached as GL_DEPTH_ATTACHMENT only: the framebuffer has no stencil buffer, so an
     * enabled stencil test passes (Voxy's HiZ chain draws with the LOD pass's stencil test still on).
     */
    @Test
    void stencilTestPassesWithoutStencilAttachment() {
        int colour = MetalGL.glGenTextures();
        MetalGL.glBindTexture(GL_TEXTURE_2D, colour);
        MetalGL.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 32, 32, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        int depth = MetalGL.glGenTextures();
        MetalGL.glBindTexture(GL_TEXTURE_2D, depth);
        MetalGL.glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH32F_STENCIL8, 32, 32, 0, GL_DEPTH_STENCIL, GL_FLOAT_32_UNSIGNED_INT_24_8_REV, (ByteBuffer) null);
        int fbo = MetalGL.glGenFramebuffers();
        MetalGL.glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        MetalGL.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colour, 0);
        MetalGL.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, MetalGL.glCheckFramebufferStatus(GL_FRAMEBUFFER));
        MetalGL.glViewport(0, 0, 32, 32);
        MetalGL.glClearColor(0f, 0f, 1f, 1f);
        MetalGL.glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        MetalGL.glEnable(GL_STENCIL_TEST);
        de.nprmedia.nativium.gl.MetalGL4.glStencilFunc(GL_EQUAL, 1, 0xFF);
        int p = program(VS, FS_COLOR);
        MetalGL.glUseProgram(p);
        MetalGL.glUniform4f(MetalGL.glGetUniformLocation(p, "uColor"), 1f, 0f, 0f, 1f);
        MetalGL.glBindVertexArray(quad(-1, -1, 1, 1));
        MetalGL.glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0L);
        MetalGL.glDisable(GL_STENCIL_TEST);
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());
        assertPixel(16, 16, 255, 0, 0, "drawn: no stencil attachment, so the stencil test passes");
        // With the same texture also attached as the stencil buffer (stencil 0 after the clear), EQUAL 1 rejects.
        MetalGL.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
        MetalGL.glClearStencil(0);
        MetalGL.glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
        MetalGL.glEnable(GL_STENCIL_TEST);
        MetalGL.glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0L);
        MetalGL.glDisable(GL_STENCIL_TEST);
        assertPixel(16, 16, 0, 0, 255, "stencil attached: EQUAL 1 against 0 rejects");
    }

    @Test
    void framebufferObjectScissoredClearAndBlending() {
        MetalGL.glClearColor(0f, 0f, 0f, 1f);
        MetalGL.glClear(GL_COLOR_BUFFER_BIT);
        int target = MetalGL.glGenTextures();
        MetalGL.glBindTexture(GL_TEXTURE_2D, target);
        MetalGL.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 32, 32, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        int fbo = MetalGL.glGenFramebuffers();
        MetalGL.glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        MetalGL.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, MetalGL.glCheckFramebufferStatus(GL_FRAMEBUFFER));
        MetalGL.glViewport(0, 0, 32, 32);
        MetalGL.glClearColor(1f, 1f, 1f, 1f);
        MetalGL.glClear(GL_COLOR_BUFFER_BIT);
        MetalGL.glEnable(GL_SCISSOR_TEST);
        MetalGL.glScissor(0, 0, 16, 32);
        MetalGL.glClearColor(0f, 0f, 0f, 1f);
        MetalGL.glClear(GL_COLOR_BUFFER_BIT); // left half black
        MetalGL.glDisable(GL_SCISSOR_TEST);
        int p = program(VS, FS_COLOR);
        MetalGL.glUseProgram(p);
        MetalGL.glUniform4f(MetalGL.glGetUniformLocation(p, "uColor"), 1f, 0f, 0f, 0.5f);
        MetalGL.glEnable(GL_BLEND);
        MetalGL.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        MetalGL.glBindVertexArray(quad(-1, -1, 1, 1));
        MetalGL.glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0L);
        assertEquals(GL_NO_ERROR, MetalGL.glGetError());
        assertPixel(4, 16, 128, 0, 0, "half red over black");
        assertPixel(28, 16, 255, 128, 128, "half red over white");
        MetalGL.glBindFramebuffer(GL_FRAMEBUFFER, 0);
        assertPixel(4, 4, 0, 0, 0, "default framebuffer untouched by the FBO draws");
    }

    @Test
    void persistentMappedBufferFeedsDraws() {
        int p = program(VS, FS_COLOR);
        MetalGL.glUseProgram(p);
        MetalGL.glUniform4f(MetalGL.glGetUniformLocation(p, "uColor"), 1f, 1f, 1f, 1f);
        int vao = MetalGL.glGenVertexArrays();
        MetalGL.glBindVertexArray(vao);
        int vbo = MetalGL.glGenBuffers();
        MetalGL.glBindBuffer(GL_ARRAY_BUFFER, vbo);
        int flags = GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
        MetalGL.glBufferStorage(GL_ARRAY_BUFFER, 3 * 16, flags);
        ByteBuffer mapped = MetalGL.glMapBufferRange(GL_ARRAY_BUFFER, 0, 3 * 16, flags);
        mapped.asFloatBuffer().put(new float[]{-1, -1, 0, 0, 1, -1, 0, 0, -1, 1, 0, 0});
        MetalGL.glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0);
        MetalGL.glEnableVertexAttribArray(0);
        MetalGL.glClearColor(0f, 0f, 0f, 1f);
        MetalGL.glClear(GL_COLOR_BUFFER_BIT);
        MetalGL.glDrawArrays(GL_TRIANGLES, 0, 3);
        long sync = MetalGL.glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        assertPixel(4, 4, 255, 255, 255, "triangle from persistently mapped memory");
        assertEquals(GL_ALREADY_SIGNALED, MetalGL.glClientWaitSync(sync, 0, 0), "readback waited for the GPU");
    }
}
