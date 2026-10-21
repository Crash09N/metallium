/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.gl.retarget;

import de.nprmedia.nativium.api.NativiumRuntime;
import de.nprmedia.nativium.gl.GlContext;
import de.nprmedia.nativium.gl.MetalGL;
import de.nprmedia.nativium.gl.MetalGLLog;
import de.nprmedia.nativium.gl.TestDevices;
import de.nprmedia.nativium.gl.fixture.GlUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GlCallRetargeterTest {
    @BeforeEach
    void setUp() {
        NativiumRuntime.markMetalActive();
        MetalGL.setEnabled(true);
        MetalGLLog.setFailFast(false);
        GlContext.makeCurrent(new GlContext(TestDevices.recording()));
    }

    @AfterEach
    void tearDown() {
        MetalGL.setEnabled(true);
        MetalGLLog.setFailFast(false);
        GlContext.makeCurrent(null);
    }

    private static Class<?> retargeted(final GlCallRetargeter.Result[] out) throws IOException, ClassNotFoundException {
        String resource = GlUser.class.getName().replace('.', '/') + ".class";
        byte[] original;
        try (InputStream in = GlUser.class.getClassLoader().getResourceAsStream(resource)) {
            original = in.readAllBytes();
        }
        GlCallRetargeter.Result result = GlCallRetargeter.transform("testmod", original);
        out[0] = result;
        assertNotNull(result.bytes());
        byte[] bytes = result.bytes();
        ClassLoader loader = new ClassLoader(GlCallRetargeterTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                if (name.equals(GlUser.class.getName())) {
                    Class<?> loaded = findLoadedClass(name);
                    return loaded != null ? loaded : defineClass(name, bytes, 0, bytes.length);
                }
                return super.loadClass(name, resolve);
            }
        };
        return loader.loadClass(GlUser.class.getName());
    }

    private static Object call(final Class<?> type, final String method) throws Throwable {
        try {
            return type.getMethod(method).invoke(null);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void retargetedCallsReachMetalGl() throws Throwable {
        GlCallRetargeter.Result[] result = new GlCallRetargeter.Result[1];
        Class<?> user = retargeted(result);
        assertEquals(5, result[0].callSites(), "glEnable, glIsEnabled, glAccum, getCapabilities, glGenBuffers");
        assertEquals(1, result[0].unimplemented(), "only glAccum has no implementation");
        assertEquals(true, call(user, "enableBlendAndQuery"));
        assertTrue(GlContext.current().isEnabled(org.lwjgl.opengl.GL11C.GL_BLEND), "the call changed nativium-gl's state");
        assertEquals(true, call(user, "claimsGl46"));
        assertTrue((int) call(user, "genBuffer") > 0);
    }

    @Test
    void unimplementedFunctionsAreLoggedAndReturnDefaults() throws Throwable {
        Class<?> user = retargeted(new GlCallRetargeter.Result[1]);
        assertEquals(7, call(user, "unimplementedCall"));
        assertTrue(MetalGLLog.unimplementedCalls().containsKey("testmod GL11.glAccum(IF)V"), MetalGLLog.unimplementedCalls().toString());
        MetalGLLog.setFailFast(true);
        assertThrows(UnsupportedOperationException.class, () -> call(user, "unimplementedCall"));
    }

    @Test
    void inactiveBridgesFallBackToLwjgl() throws Throwable {
        Class<?> user = retargeted(new GlCallRetargeter.Result[1]);
        MetalGL.setEnabled(false);
        // No GL context in a unit test: LWJGL's GL.getCapabilities() throws, which proves the bridge called the original.
        // (Real GL functions cannot be used here: without a context LWJGL aborts the JVM.)
        Throwable thrown = assertThrows(IllegalStateException.class, () -> call(user, "claimsGl46"));
        boolean fromLwjgl = false;
        for (StackTraceElement frame : thrown.getStackTrace()) {
            fromLwjgl |= frame.getClassName().startsWith("org.lwjgl.");
        }
        assertTrue(fromLwjgl, () -> "expected LWJGL in the stack: " + thrown);
    }

    @Test
    void minecraftGlBackendInternalsAreRetargeted() throws Throwable {
        String resource = de.nprmedia.nativium.gl.fixture.McGlUser.class.getName().replace('.', '/') + ".class";
        byte[] original;
        try (InputStream in = GlCallRetargeterTest.class.getClassLoader().getResourceAsStream(resource)) {
            original = in.readAllBytes();
        }
        GlCallRetargeter.Result result = GlCallRetargeter.transform("testmod", original);
        assertEquals(3, result.callSites(), "_enableDepthTest + two glId() casts");
        assertEquals(0, result.unimplemented());
        byte[] bytes = result.bytes();
        ClassLoader loader = new ClassLoader(GlCallRetargeterTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                if (name.equals(de.nprmedia.nativium.gl.fixture.McGlUser.class.getName())) {
                    Class<?> loaded = findLoadedClass(name);
                    return loaded != null ? loaded : defineClass(name, bytes, 0, bytes.length);
                }
                return super.loadClass(name, resolve);
            }
        };
        Class<?> user = loader.loadClass(de.nprmedia.nativium.gl.fixture.McGlUser.class.getName());
        user.getMethod("enableDepthTest").invoke(null);
        assertTrue(GlContext.current().isEnabled(org.lwjgl.opengl.GL11C.GL_DEPTH_TEST), "GlStateManager call reached nativium-gl");
        // Nativium's textures are not GlTexture: without the rewrite the cast would throw ClassCastException.
        com.mojang.renderpearl.api.textures.GpuTexture texture = (com.mojang.renderpearl.api.textures.GpuTexture) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.mojang.renderpearl.api.textures.GpuTexture.class},
                (proxy, method, args) -> method.getName().equals("isClosed") ? Boolean.FALSE : method.getReturnType() == int.class ? (Object) 0 : null);
        com.mojang.renderpearl.api.textures.GpuTextureView view = (com.mojang.renderpearl.api.textures.GpuTextureView) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.mojang.renderpearl.api.textures.GpuTextureView.class},
                (proxy, method, args) -> method.getName().equals("texture") ? texture : method.getReturnType() == int.class ? (Object) 0
                        : method.getReturnType() == boolean.class ? Boolean.FALSE : null);
        int fromTexture = (int) user.getMethod("textureId", com.mojang.renderpearl.api.textures.GpuTexture.class).invoke(null, texture);
        int fromView = (int) user.getMethod("viewId", com.mojang.renderpearl.api.textures.GpuTextureView.class).invoke(null, view);
        assertTrue(fromTexture > 0, "a nativium-gl texture name");
        assertEquals(fromTexture, fromView, "the view names its texture; one name per engine texture");
        assertTrue(MetalGL.glIsTexture(fromTexture));
    }

    @Test
    void untouchedWithoutGlCalls() {
        assertNull(GlCallRetargeter.transform("testmod", new byte[]{1, 2, 3}).bytes());
    }

    /** Every implemented entry point must exactly mirror an LWJGL function, or retargeting would never select it. */
    @Test
    void metalGlMirrorsLwjglSignatures() throws ClassNotFoundException {
        List<Class<?>> lwjgl = new ArrayList<>();
        for (String version : new String[]{"11", "12", "13", "14", "15", "20", "21", "30", "31", "32", "33", "40", "41", "42", "43", "44", "45", "46"}) {
            lwjgl.add(Class.forName("org.lwjgl.opengl.GL" + version + "C", false, getClass().getClassLoader()));
        }
        List<String> missing = new ArrayList<>();
        int count = 0;
        for (Method method : MetalGL.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || !method.getName().startsWith("gl")) continue;
            count++;
            boolean found = false;
            for (Class<?> type : lwjgl) {
                try {
                    Method twin = type.getMethod(method.getName(), method.getParameterTypes());
                    found = twin.getReturnType() == method.getReturnType();
                } catch (NoSuchMethodException ignored) {
                    // try the next class
                }
                if (found) break;
            }
            if (!found) missing.add(method.toString());
        }
        assertTrue(missing.isEmpty(), "no LWJGL twin: " + missing);
        assertTrue(count > 150, "implemented entry points: " + count);
    }

    @Test
    void metalGl4AndStateManagerMirrorTheirOriginals() throws ClassNotFoundException {
        List<Class<?>> lwjgl = new ArrayList<>();
        for (String version : new String[]{"11", "15", "20", "30", "31", "32", "33", "40", "41", "42", "43", "44", "45", "46"}) {
            lwjgl.add(Class.forName("org.lwjgl.opengl.GL" + version + "C", false, getClass().getClassLoader()));
        }
        for (String ext : new String[]{"ARBIndirectParameters", "ARBDrawBuffersBlend", "ARBDirectStateAccess", "ARBTimerQuery", "ARBShaderImageLoadStore"}) {
            lwjgl.add(Class.forName("org.lwjgl.opengl." + ext, false, getClass().getClassLoader()));
        }
        List<String> missing = new ArrayList<>();
        int count = 0;
        for (Method method : de.nprmedia.nativium.gl.MetalGL4.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || !(method.getName().startsWith("gl") || method.getName().startsWith("ngl"))) continue;
            count++;
            if (!hasTwin(lwjgl, method)) missing.add(method.toString());
        }
        assertTrue(missing.isEmpty(), "no LWJGL twin: " + missing);
        assertTrue(count > 150, "MetalGL4 entry points: " + count);
        Class<?> stateManager = Class.forName("com.mojang.renderpearl.backend.opengl.GlStateManager", false, getClass().getClassLoader());
        for (Method method : de.nprmedia.nativium.gl.MetalGLStateManager.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !hasTwin(List.of(stateManager), method)) missing.add(method.toString());
        }
        assertTrue(missing.isEmpty(), "no GlStateManager twin: " + missing);
    }

    private static boolean hasTwin(final List<Class<?>> classes, final Method method) {
        for (Class<?> type : classes) {
            try {
                if (type.getMethod(method.getName(), method.getParameterTypes()).getReturnType() == method.getReturnType()) {
                    return true;
                }
            } catch (NoSuchMethodException ignored) {
                // try the next class
            }
        }
        return false;
    }
}
