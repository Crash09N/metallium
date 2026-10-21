// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Crash09N – NPR Media
package de.nprmedia.nativium;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.nprmedia.nativium.NativiumPlatform.Status;
import de.nprmedia.nativium.compat.physicsmod.PhysicsModMixinPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Nativium must be inert on every machine where the Metal backend cannot run. */
class NativiumPlatformTest {
    private static final String[] CONFIGS = {"nativium.mixins.json", "nativium.sodium.mixins.json", "nativium.voxy.mixins.json",
            "nativium.betterf3.mixins.json", "nativium.distanthorizons.mixins.json", "nativium.physicsmod.mixins.json"};

    @AfterEach
    void reset() {
        NativiumPlatform.overrideForTests(null);
        BackendSelection.resetForTests();
    }

    private static BooleanSupplier countingDevice(final AtomicInteger calls, final boolean result) {
        return () -> {
            calls.incrementAndGet();
            return result;
        };
    }

    @Test
    void unsupportedSystemsNeverTouchMetal() {
        AtomicInteger calls = new AtomicInteger();
        assertEquals(Status.NOT_MAC, NativiumPlatform.evaluate("Windows 11", "amd64", countingDevice(calls, true)));
        assertEquals(Status.NOT_MAC, NativiumPlatform.evaluate("Windows 10", "aarch64", countingDevice(calls, true)));
        assertEquals(Status.NOT_MAC, NativiumPlatform.evaluate("Linux", "amd64", countingDevice(calls, true)));
        assertEquals(Status.NOT_MAC, NativiumPlatform.evaluate("Linux", "aarch64", countingDevice(calls, true)));
        assertEquals(Status.NOT_MAC, NativiumPlatform.evaluate("FreeBSD", "amd64", countingDevice(calls, true)));
        assertEquals(Status.NOT_MAC, NativiumPlatform.evaluate(null, null, countingDevice(calls, true)));
        assertEquals(Status.NO_METAL_DEVICE, NativiumPlatform.evaluate("Mac OS X", "x86_64", countingDevice(calls, true)));
        assertEquals(0, calls.get(), "the Metal device probe (native code) must only run on macOS aarch64");
    }

    @Test
    void appleSiliconNeedsAUsableMetalDevice() {
        AtomicInteger calls = new AtomicInteger();
        assertEquals(Status.NO_METAL_DEVICE, NativiumPlatform.evaluate("Mac OS X", "aarch64", countingDevice(calls, false)));
        assertEquals(Status.NO_METAL_DEVICE, NativiumPlatform.evaluate("Mac OS X", "aarch64", () -> {
            throw new UnsatisfiedLinkError("no Metal.framework");
        }));
        assertEquals(Status.SUPPORTED, NativiumPlatform.evaluate("Mac OS X", "aarch64", countingDevice(calls, true)));
        assertEquals(Status.SUPPORTED, NativiumPlatform.evaluate("Mac OS X", "arm64", countingDevice(calls, true)));
        assertEquals(3, calls.get());
    }

    @Test
    void logLinesAreTheOwnersWording() {
        assertEquals("Nativium: not a Mac — inactive (game runs on its normal renderer)", Status.NOT_MAC.logLine());
        assertEquals("Nativium: no supported Metal device — inactive", Status.NO_METAL_DEVICE.logLine());
    }

    /** Every mixin of every Nativium mixin config. */
    private static List<String> allMixins() throws IOException {
        List<String> mixins = new ArrayList<>();
        for (String config : CONFIGS) {
            try (InputStream in = NativiumPlatformTest.class.getClassLoader().getResourceAsStream(config)) {
                assertNotNull(in, config);
                JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                assertTrue(json.has("plugin"), config + " must have a mixin plugin that gates it");
                String pkg = json.get("package").getAsString();
                for (String side : new String[]{"mixins", "client"}) {
                    if (json.has(side)) json.getAsJsonArray(side).forEach(e -> mixins.add(pkg + "." + e.getAsString()));
                }
            }
        }
        assertTrue(mixins.size() > 20, "mixin configs not found: " + mixins);
        return mixins;
    }

    @Test
    void noMixinAppliesOnUnsupportedPlatforms() throws IOException {
        List<String> mixins = allMixins();
        for (Status status : new Status[]{Status.NOT_MAC, Status.NO_METAL_DEVICE}) {
            NativiumPlatform.overrideForTests(status);
            BackendSelection.resetForTests();
            NativiumMixinPlugin core = new NativiumMixinPlugin();
            PhysicsModMixinPlugin physics = new PhysicsModMixinPlugin();
            for (String mixin : mixins) {
                assertFalse(core.shouldApplyMixin("target", mixin), status + ": " + mixin);
                assertFalse(physics.shouldApplyMixin("target", mixin), status + ": " + mixin);
            }
            // No crash guard, config or mod scan behind the decision (FabricLoader is not even needed here).
            assertFalse(BackendSelection.shouldOfferMetal());
            assertFalse(BackendSelection.metalSupported());
        }
    }

    @Test
    void onlyTheEngineSelectorAppliesWhenMetalIsNotUsed() throws IOException {
        List<String> applied = new ArrayList<>();
        for (String mixin : allMixins()) {
            assertFalse(NativiumMixinPlugin.policy(mixin, false, true), mixin);
            assertTrue(NativiumMixinPlugin.policy(mixin, true, true), mixin);
            if (NativiumMixinPlugin.policy(mixin, true, false)) applied.add(mixin.substring(mixin.lastIndexOf('.') + 1));
        }
        assertEquals(NativiumMixinPlugin.ENGINE_SELECTOR.size(), applied.size(), "every selector mixin exists: " + applied);
        for (String rendering : new String[]{"FramerateLimitTrackerMixin", "DrawBackendMixin", "DefaultChunkRendererPerfMixin",
                "GLDrawBatchDrawMixin", "MinecraftGpuUtilizationMixin", "MinecraftRenderWrapperMixin", "DhIrisEngineMixin",
                "LiquidComputeSupportMixin", "PhysicsRenderUtilMixin"}) {
            assertFalse(applied.contains(rendering), rendering);
        }
    }

    @Test
    void earlyEngineFollowsVanillaLikeEngineSync() {
        NativiumConfig.GraphicsEngine metal = NativiumConfig.GraphicsEngine.METAL;
        NativiumConfig.GraphicsEngine gl = NativiumConfig.GraphicsEngine.OPENGL;
        // fresh install: no options.txt
        assertEquals(metal, BackendSelection.effectiveEngine(metal, null, null));
        // the player picked OpenGL in vanilla's Graphics API option (Nativium's config still says Metal)
        assertEquals(gl, BackendSelection.effectiveEngine(metal, "DEFAULT", "OPENGL"));
        assertEquals(gl, BackendSelection.effectiveEngine(metal, null, "OPENGL"));
        assertEquals(NativiumConfig.GraphicsEngine.VULKAN, BackendSelection.effectiveEngine(metal, "DEFAULT", "VULKAN"));
        // in sync: Nativium's config wins (e.g. engine changed on the Nativium page)
        assertEquals(gl, BackendSelection.effectiveEngine(gl, "DEFAULT", "DEFAULT"));
        assertEquals(metal, BackendSelection.effectiveEngine(metal, "OPENGL", "OPENGL"));
        // back to vanilla "Default" (= Metal) after OpenGL
        assertEquals(metal, BackendSelection.effectiveEngine(gl, "OPENGL", "DEFAULT"));
        assertEquals(metal, BackendSelection.effectiveEngine(metal, null, "garbage"));
    }
}
