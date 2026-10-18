/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.gl.retarget;

import dev.metallium.api.MetalliumRuntime;
import dev.metallium.gl.GlContext;
import dev.metallium.gl.MetalGLLog;
import dev.metallium.gl.metal.GameMetalHost;
import dev.metallium.gl.metal.MetalGlDevice;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Start-up: discovers opted-in mods and hooks class loading. Fabric has no public class-transformer API, so the hook
 * wraps Knot's mixin transformer (the one call every mod class goes through, {@code transformClassBytes}) and post-
 * processes the classes of opted-in mods with {@link GlCallRetargeter}. Runs from the mixin config plugin, which loads
 * before any mod entrypoint, so mod classes cannot be loaded un-retargeted.
 */
public final class MetalliumGlBootstrap {
    private static boolean installed;
    private static boolean hooked;
    private static OptInMods optIn;
    private static final AtomicInteger CLASSES = new AtomicInteger();
    private static final AtomicInteger CALL_SITES = new AtomicInteger();

    private MetalliumGlBootstrap() {
    }

    public static synchronized void install() {
        if (installed) {
            if (!hooked && !optIn.mods().isEmpty()) {
                tryHook(); // the mixin plugin ran before Knot had its transformer: retry from preLaunch
            }
            return;
        }
        installed = true;
        String failFast = System.getProperty("metallium.gl.failFast");
        MetalGLLog.setFailFast(failFast != null ? Boolean.parseBoolean(failFast) : FabricLoader.getInstance().isDevelopmentEnvironment());
        GlContext.setDeviceFactory(() -> new MetalGlDevice(new GameMetalHost()));
        optIn = OptInMods.discover();
        MetalliumRuntime.registerStatusLine(MetalliumGlBootstrap::statusLine);
        dev.metallium.compat.GlRouting.setUnimplementedSource(MetalGLLog::unimplementedCalls);
        if (optIn.mods().isEmpty()) {
            MetalGLLog.LOGGER.info("[metallium-gl] no mods opted in; class loading is not hooked");
            return;
        }
        tryHook();
    }

    private static void tryHook() {
        try {
            hookKnot();
            hooked = true;
            dev.metallium.compat.GlRouting.markHooked();
        } catch (ReflectiveOperationException | RuntimeException e) {
            MetalGLLog.LOGGER.error("[metallium-gl] could not hook class loading; opted-in mods run without retargeting", e);
        }
    }

    private static void hookKnot() throws ReflectiveOperationException {
        Object launcher = FabricLauncherBase.getLauncher();
        Field loaderField = findField(launcher.getClass(), "classLoader");
        Object delegate = loaderField.get(launcher);
        Field transformerField = findField(delegate.getClass(), "mixinTransformer");
        IMixinTransformer original = (IMixinTransformer) transformerField.get(delegate);
        if (original == null) {
            throw new IllegalStateException("Knot has no mixin transformer yet");
        }
        InvocationHandler handler = (proxy, method, args) -> {
            Object result;
            try {
                result = method.invoke(original, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            if (method.getName().equals("transformClassBytes") && args != null && args.length == 3 && result instanceof byte[] bytes) {
                return retarget((String) args[0], bytes);
            }
            return result;
        };
        Object proxy = Proxy.newProxyInstance(IMixinTransformer.class.getClassLoader(), new Class<?>[]{IMixinTransformer.class}, handler);
        transformerField.set(delegate, proxy);
        MetalGLLog.LOGGER.info("[metallium-gl] class loading hooked for {}", optIn.mods().keySet());
    }

    private static byte[] retarget(final String className, final byte[] bytes) {
        String mod = optIn.modFor(className);
        if (mod == null) {
            return bytes;
        }
        try {
            GlCallRetargeter.Result result = GlCallRetargeter.transform(mod, bytes);
            if (result.methodReferences() > 0) {
                MetalGLLog.warn(className + " uses " + result.methodReferences() + " GL method references (lambdas); these are not retargeted yet");
            }
            if (result.bytes() == null) {
                return bytes;
            }
            CLASSES.incrementAndGet();
            CALL_SITES.addAndGet(result.callSites());
            MetalGLLog.LOGGER.info("[metallium-gl] {}: {} GL call sites retargeted ({} distinct functions without implementation)",
                    className, result.callSites(), result.unimplemented());
            return result.bytes();
        } catch (RuntimeException e) {
            MetalGLLog.LOGGER.error("[metallium-gl] retargeting {} failed; class left unchanged", className, e);
            return bytes;
        }
    }

    private static Field findField(final Class<?> type, final String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // keep looking in the superclass
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static String statusLine() {
        if (optIn == null || optIn.mods().isEmpty()) {
            return null;
        }
        long missing = MetalGLLog.unimplementedCalls().size();
        return "metallium-gl: " + optIn.mods().size() + " mod(s), " + CLASSES.get() + " classes / " + CALL_SITES.get()
                + " GL call sites retargeted, " + missing + " unimplemented functions seen";
    }

    public static int retargetedClasses() {
        return CLASSES.get();
    }
}
