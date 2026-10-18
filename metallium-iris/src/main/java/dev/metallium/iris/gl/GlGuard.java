package dev.metallium.iris.gl;

import dev.metallium.api.MetalliumRuntime;
import dev.metallium.iris.post.PostUniforms;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Answers vanilla {@code GlStateManager} calls when Metal is active (there is no GL context; a real GL call would abort
 * the JVM). {@link GlStateManagerGuard} prepends a call to {@link #active()} to every {@code GlStateManager} method; on
 * Metal the method then returns here (special cases below) or with a zero/empty default, and never reaches LWJGL.
 * Callers on Metal are only Iris code paths that do not render (state resets, macro setup, GUI); Iris's composite and
 * final passes run through {@link dev.metallium.iris.post.MetalPostPipeline} instead.
 */
public final class GlGuard {
    private static final AtomicInteger NAMES = new AtomicInteger(0x5000_0000);

    private GlGuard() {
    }

    public static boolean active() {
        return MetalliumRuntime.isMetalActive();
    }

    public static String getString(final int name) {
        return switch (name) {
            case GL11C.GL_VERSION -> "4.1.0 Metallium (Metal)";
            case GL20C.GL_SHADING_LANGUAGE_VERSION -> "4.10";
            case GL11C.GL_VENDOR -> "Apple";
            case GL11C.GL_RENDERER -> "Apple GPU (Metal)";
            default -> "";
        };
    }

    public static int getInteger(final int name) {
        return switch (name) {
            case GL20C.GL_MAX_TEXTURE_IMAGE_UNITS, GL20C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS -> 16;
            case GL20C.GL_MAX_DRAW_BUFFERS, GL30C.GL_MAX_COLOR_ATTACHMENTS -> 8;
            case GL11C.GL_MAX_TEXTURE_SIZE -> 16384;
            default -> 0;
        };
    }

    public static int newName() {
        return NAMES.getAndIncrement();
    }

    public static int newShader(final int type) {
        return NAMES.getAndIncrement();
    }

    public static int uniformLocation(final int program, final CharSequence name) {
        return -1;
    }

    public static String infoLog(final int object, final int maxLength) {
        return "";
    }

    public static void uniform1i(final int location, final int value) {
        PostUniforms.setInts(location, value);
    }
}
