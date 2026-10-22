/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.gl.retarget;

import de.nprmedia.nativium.api.NativiumRuntime;
import de.nprmedia.nativium.gl.GlContext;
import de.nprmedia.nativium.gl.MetalGLLog;
import de.nprmedia.nativium.gl.metal.GameMetalHost;
import de.nprmedia.nativium.gl.metal.MetalGlDevice;
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
public final class NativiumGlBootstrap {
    /** System property listing the mod ids whose GL calls are retargeted (comma separated). */
    public static final String ROUTED_MODS_PROPERTY = "nativium.gl.routedMods";
    private static boolean installed;
    private static boolean hooked;
    private static OptInMods optIn;
    private static final AtomicInteger CLASSES = new AtomicInteger();
    private static final AtomicInteger CALL_SITES = new AtomicInteger();

    private NativiumGlBootstrap() {
    }

    public static synchronized void install() {
        if (installed) {
            if (!hooked && !optIn.mods().isEmpty()) {
                tryHook(); // the mixin plugin ran before Knot had its transformer: retry from preLaunch
            }
            return;
        }
        installed = true;
        de.nprmedia.nativium.LegacyMigration.aliasLegacyProperties(); // -Dmetallium.* fallback, before the platform check
        if (!de.nprmedia.nativium.NativiumPlatform.supported()) {
            optIn = OptInMods.none();
            return; // unsupported platform: no hook, no routing, no status line, nothing written
        }
        if (!de.nprmedia.nativium.BackendSelection.shouldOfferMetal()) {
            // Metal is not used this session: route nothing (other mods, e.g. Voxy, run on real OpenGL untouched).
            optIn = OptInMods.none();
            MetalGLLog.LOGGER.info("[nativium-gl] inactive: Metal is not used this session; class loading is not hooked");
            return;
        }
        String failFast = System.getProperty("nativium.gl.failFast");
        MetalGLLog.setFailFast(failFast != null ? Boolean.parseBoolean(failFast) : FabricLoader.getInstance().isDevelopmentEnvironment());
        GlContext.setDeviceFactory(() -> new MetalGlDevice(new GameMetalHost()));
        optIn = OptInMods.discover();
        // Read by core compat code (e.g. the Voxy safe-disable mixin) that must step aside for routed mods.
        System.setProperty(ROUTED_MODS_PROPERTY, String.join(",", optIn.mods().keySet()));
        NativiumRuntime.registerStatusLine(NativiumGlBootstrap::statusLine);
        de.nprmedia.nativium.compat.GlRouting.setUnimplementedSource(MetalGLLog::unimplementedCalls);
        if (optIn.mods().isEmpty()) {
            MetalGLLog.LOGGER.info("[nativium-gl] no mods opted in; class loading is not hooked");
            return;
        }
        tryHook();
    }

    private static void tryHook() {
        try {
            hookKnot();
            hooked = true;
            de.nprmedia.nativium.compat.GlRouting.markHooked();
        } catch (ReflectiveOperationException | RuntimeException e) {
            MetalGLLog.LOGGER.error("[nativium-gl] could not hook class loading; opted-in mods run without retargeting", e);
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
        MetalGLLog.LOGGER.info("[nativium-gl] class loading hooked for {}", optIn.mods().keySet());
    }

    private static byte[] retarget(final String className, final byte[] bytes) {
        String mod = optIn.modFor(className);
        if (mod == null && (className.startsWith("de.nprmedia.nativium.") || className.startsWith("org.objectweb.asm.")
                || className.startsWith("org.spongepowered.") || className.startsWith("java"))) {
            return bytes; // never other classes of the hook itself (circular class loading)
        }
        try {
            // Classes of other mods: only methods an opted-in mod's mixins merged into them (e.g. Voxy's Sodium hooks).
            GlCallRetargeter.Result result = mod != null ? GlCallRetargeter.transform(mod, bytes)
                    : GlCallRetargeter.transformMerged(bytes, optIn::modFor);
            if (result.methodReferences() > 0) {
                MetalGLLog.warn(className + " uses " + result.methodReferences() + " GL method references (lambdas); these are not retargeted yet");
            }
            if (result.bytes() == null) {
                return bytes;
            }
            CLASSES.incrementAndGet();
            CALL_SITES.addAndGet(result.callSites());
            // Per class only at debug (a mod like Voxy has dozens); classes with unimplemented functions stay visible.
            if (result.unimplemented() > 0) {
                MetalGLLog.LOGGER.info("[nativium-gl] {}: {} GL call sites retargeted ({} distinct functions without implementation)",
                        className, result.callSites(), result.unimplemented());
            } else {
                MetalGLLog.LOGGER.debug("[nativium-gl] {}: {} GL call sites retargeted", className, result.callSites());
            }
            return result.bytes();
        } catch (RuntimeException e) {
            MetalGLLog.LOGGER.error("[nativium-gl] retargeting {} failed; class left unchanged", className, e);
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

    /** Short F3 line (also shown by BetterF3): which mods are translated and whether any GL function is missing. */
    private static String statusLine() {
        if (optIn == null || optIn.mods().isEmpty()) {
            return null;
        }
        long missing = MetalGLLog.unimplementedCalls().size();
        return "nativium-gl: " + optIn.mods().size() + " mods translated"
                + (missing == 0 ? "" : ", " + missing + " GL functions missing");
    }

    /** Detailed counters for logs and game tests (too long for F3). */
    public static String diagnostics() {
        if (optIn == null || optIn.mods().isEmpty()) {
            return "nativium-gl: inactive";
        }
        long missing = MetalGLLog.unimplementedCalls().size();
        return "nativium-gl: " + optIn.mods().size() + " mod(s), " + CLASSES.get() + " classes / " + CALL_SITES.get()
                + " GL call sites retargeted, " + missing + " unimplemented functions seen; dispatches=" + de.nprmedia.nativium.gl.GlCompute.DISPATCHES.get()
                + " indirect draw calls=" + de.nprmedia.nativium.gl.GlCompute.INDIRECT_DRAW_CALLS.get() + " (GPU counts seen: "
                + de.nprmedia.nativium.gl.GlCompute.INDIRECT_COUNT_SUM.get() + "; MDI-count as ICB=" + de.nprmedia.nativium.gl.metal.MetalGlDevice.ICB_CALLS.get()
                + ", per-slot fallback=" + de.nprmedia.nativium.gl.metal.MetalGlDevice.SLOT_FALLBACK_CALLS.get()
                + " (" + de.nprmedia.nativium.gl.metal.MetalGlDevice.SLOT_FALLBACK_DRAWS.get() + " CPU draws); indirect encode CPU ms="
                + de.nprmedia.nativium.gl.metal.MetalGlDevice.INDIRECT_ENCODE_NS.get() / 1_000_000 + "); GPU waits="
                + de.nprmedia.nativium.gl.metal.MetalGlDevice.GPU_WAITS.get() + " (" + de.nprmedia.nativium.gl.metal.MetalGlDevice.GPU_WAIT_NS.get() / 1_000_000
                + " ms); " + de.nprmedia.nativium.gl.metal.MetalGlDevice.icbPoolStats();
    }

    public static int retargetedClasses() {
        return CLASSES.get();
    }
}
