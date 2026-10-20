// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Crash09N – NPR Media
package dev.metallium.gl.retarget;

import dev.metallium.MetalliumPlatform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** On an unsupported platform metallium-gl hooks nothing and routes nothing (no FabricLoader needed). */
class MetalliumGlBootstrapTest {
    @Test
    void inertOnUnsupportedPlatforms() {
        MetalliumPlatform.overrideForTests(MetalliumPlatform.Status.NOT_MAC);
        try {
            System.clearProperty(MetalliumGlBootstrap.ROUTED_MODS_PROPERTY);
            new MetalliumGlMixinPlugin().onLoad("dev.metallium.gl.mixin");
            new MetalliumGlPreLaunch().onPreLaunch();
            assertNull(System.getProperty(MetalliumGlBootstrap.ROUTED_MODS_PROPERTY));
            assertFalse(dev.metallium.compat.GlRouting.hooked());
            assertEquals(0, MetalliumGlBootstrap.retargetedClasses());
            assertEquals("metallium-gl: inactive", MetalliumGlBootstrap.diagnostics());
        } finally {
            MetalliumPlatform.overrideForTests(null);
        }
    }
}
