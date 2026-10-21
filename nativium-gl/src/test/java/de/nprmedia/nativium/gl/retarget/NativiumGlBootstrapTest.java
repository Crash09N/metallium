// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Crash09N – NPR Media
package de.nprmedia.nativium.gl.retarget;

import de.nprmedia.nativium.NativiumPlatform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** On an unsupported platform nativium-gl hooks nothing and routes nothing (no FabricLoader needed). */
class NativiumGlBootstrapTest {
    @Test
    void inertOnUnsupportedPlatforms() {
        NativiumPlatform.overrideForTests(NativiumPlatform.Status.NOT_MAC);
        try {
            System.clearProperty(NativiumGlBootstrap.ROUTED_MODS_PROPERTY);
            new NativiumGlMixinPlugin().onLoad("de.nprmedia.nativium.gl.mixin");
            new NativiumGlPreLaunch().onPreLaunch();
            assertNull(System.getProperty(NativiumGlBootstrap.ROUTED_MODS_PROPERTY));
            assertFalse(de.nprmedia.nativium.compat.GlRouting.hooked());
            assertEquals(0, NativiumGlBootstrap.retargetedClasses());
            assertEquals("nativium-gl: inactive", NativiumGlBootstrap.diagnostics());
        } finally {
            NativiumPlatform.overrideForTests(null);
        }
    }
}
