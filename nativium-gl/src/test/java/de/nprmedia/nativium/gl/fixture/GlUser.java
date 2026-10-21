/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.gl.fixture;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15;

/** Stand-in for a mod class that calls LWJGL's GL directly; the retargeting test transforms and loads it. */
public final class GlUser {
    private GlUser() {
    }

    public static boolean enableBlendAndQuery() {
        GL11C.glEnable(GL11C.GL_BLEND);
        return GL11C.glIsEnabled(GL11C.GL_BLEND);
    }

    /** glAccum is a compatibility-profile function nativium-gl does not implement. */
    public static int unimplementedCall() {
        GL11.glAccum(GL11.GL_ACCUM, 1f);
        return 7;
    }

    public static boolean claimsGl46() {
        return GL.getCapabilities().OpenGL46;
    }

    public static int genBuffer() {
        return GL15.glGenBuffers();
    }
}
