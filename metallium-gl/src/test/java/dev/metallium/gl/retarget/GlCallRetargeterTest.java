/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl.retarget;

import dev.metallium.api.MetalliumRuntime;
import dev.metallium.gl.GlContext;
import dev.metallium.gl.MetalGL;
import dev.metallium.gl.MetalGLLog;
import dev.metallium.gl.TestDevices;
import dev.metallium.gl.fixture.GlUser;
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
        MetalliumRuntime.markMetalActive();
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
        assertTrue(GlContext.current().isEnabled(org.lwjgl.opengl.GL11C.GL_BLEND), "the call changed metallium-gl's state");
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
}
