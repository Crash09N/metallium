/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gametest.gl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * A tiny "legacy mod" for the metallium-gl game test: it draws with raw LWJGL OpenGL calls at the end of level rendering
 * (Fabric's {@code LevelRenderEvents.END_MAIN}), like old overlay/minimap mods do. This package is opted into metallium-gl (see the gametest fabric.mod.json), so on Metal its
 * GL calls are retargeted at class-load time and must end up as pixels in the main render target.
 *
 * <p>Per frame: clears a 4x4 FBO texture to cyan, then draws three quads into framebuffer 0 with a GLSL 330 program:
 * solid magenta (uniform colour), a 2x2 NEAREST texture (red, green / blue, yellow from the bottom row up) and the FBO
 * texture. Each quad is placed with glViewport, in GL window coordinates (origin bottom-left).
 */
public final class RawGlQuads {
    /** Quad i covers x in [X0 + i * STEP, X0 + i * STEP + W) and GL y in [Y0, Y0 + H), as fractions of the target. */
    public static final double X0 = 0.55;
    public static final double STEP = 0.14;
    public static final double W = 0.12;
    public static final double Y0 = 0.62;
    public static final double H = 0.20;

    public static volatile boolean enabled;
    public static volatile String status = "not run";
    public static volatile int frames;

    private static int program;
    private static int vao;
    private static int texture;
    private static int fboTexture;
    private static int fbo;
    private static int uMode;
    private static int uColor;

    private static final String VERTEX = """
            #version 330 core
            layout(location = 0) in vec2 aPos;
            in vec2 aUv;
            out vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
            """;
    private static final String FRAGMENT = """
            #version 330 core
            in vec2 vUv;
            out vec4 fragColor;
            uniform int uMode;
            uniform vec4 uColor;
            uniform sampler2D uTex;
            void main() {
                fragColor = uMode == 0 ? uColor : texture(uTex, vUv);
            }
            """;

    private RawGlQuads() {
    }

    /** Called on the render thread after the level is drawn. */
    public static void render() {
        if (!enabled) {
            return;
        }
        try {
            if (program == 0) {
                init();
            }
            draw();
            frames++;
            int error = glGetError();
            status = error == GL_NO_ERROR ? "ok" : "GL error 0x" + Integer.toHexString(error);
        } catch (Throwable t) {
            status = "failed: " + t;
            enabled = false;
        }
    }

    private static int shader(final int type, final String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE) {
            throw new IllegalStateException("compile: " + glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private static void init() {
        int vs = shader(GL_VERTEX_SHADER, VERTEX);
        int fs = shader(GL_FRAGMENT_SHADER, FRAGMENT);
        program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glBindAttribLocation(program, 1, "aUv");
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
            throw new IllegalStateException("link: " + glGetProgramInfoLog(program));
        }
        glDeleteShader(vs);
        glDeleteShader(fs);
        uMode = glGetUniformLocation(program, "uMode");
        uColor = glGetUniformLocation(program, "uColor");

        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        FloatBuffer vertices = MemoryUtil.memAllocFloat(16);
        vertices.put(new float[]{-1, -1, 0, 0, 1, -1, 1, 0, 1, 1, 1, 1, -1, 1, 0, 1}).flip();
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
        MemoryUtil.memFree(vertices);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0L);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8L);
        glEnableVertexAttribArray(0);
        glEnableVertexAttribArray(1);
        int ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        ByteBuffer indices = MemoryUtil.memAlloc(12);
        indices.asShortBuffer().put(new short[]{0, 1, 2, 0, 2, 3});
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
        MemoryUtil.memFree(indices);
        glBindVertexArray(0);

        texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        ByteBuffer texels = MemoryUtil.memAlloc(16);
        texels.put(new byte[]{(byte) 255, 0, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255,
                0, 0, (byte) 255, (byte) 255, (byte) 255, (byte) 255, 0, (byte) 255}).flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 2, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, texels);
        MemoryUtil.memFree(texels);

        fboTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, fboTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 4, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fboTexture, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("FBO incomplete");
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    private static void draw() {
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        int width = main.width;
        int height = main.height;

        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, 4, 4);
        glClearColor(0f, 1f, 1f, 1f);
        glClear(GL_COLOR_BUFFER_BIT);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);
        glUseProgram(program);
        glBindVertexArray(vao);
        glActiveTexture(GL_TEXTURE0);
        for (int i = 0; i < 3; i++) {
            int x = (int) Math.round(width * (X0 + i * STEP));
            int y = (int) Math.round(height * Y0);
            glViewport(x, y, (int) Math.round(width * W), (int) Math.round(height * H));
            if (i == 0) {
                glUniform1i(uMode, 0);
                glUniform4f(uColor, 1f, 0f, 1f, 1f);
            } else {
                glUniform1i(uMode, 1);
                glBindTexture(GL_TEXTURE_2D, i == 1 ? texture : fboTexture);
            }
            glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0L);
        }
        glBindVertexArray(0);
        glUseProgram(0);
        glBindTexture(GL_TEXTURE_2D, 0);
    }
}
