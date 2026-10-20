/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.compat;

import dev.metallium.MetalliumConfig.ModOverride;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlRoutingTest {
    private static final ModReport NATIVE = new ModReport("nat", "Native", "1", List.of(), 0, List.of(), List.of());
    private static final ModReport RAW = new ModReport("raw", "Raw", "1", List.of("com.raw.gl.Quads"), 0, List.of("GL11.glEnable"), List.of("com.raw.gl"));

    private static GlRouting.Decision decide(final ModReport report, final Map<String, GlRouting.AuthorChoice> authors, final Set<String> users,
                                             final Map<String, ModOverride> overrides, final boolean auto, final boolean gl, final boolean metal) {
        return GlRouting.decide(List.of(report), authors, users, overrides, auto, gl, metal).get(report.id());
    }

    @Test
    void nativeModStaysNative() {
        assertEquals(GlRouting.Status.NATIVE, decide(NATIVE, Map.of(), Set.of(), Map.of(), true, true, true).status());
    }

    @Test
    void rawGlModIsRoutedAutomaticallyWithExactlyItsGlClasses() {
        GlRouting.Decision d = decide(RAW, Map.of(), Set.of(), Map.of(), true, true, true);
        assertEquals(GlRouting.Status.ROUTED, d.status());
        assertTrue(d.automatic());
        assertEquals(List.of("com.raw.gl.Quads"), d.classes());
        assertFalse(d.allClasses());
    }

    @Test
    void noAutomaticRoutingWithoutMetalOrModuleOrWhenSwitchedOff() {
        assertEquals(GlRouting.Status.OPENGL_ENGINE, decide(RAW, Map.of(), Set.of(), Map.of(), true, true, false).status());
        assertEquals(GlRouting.Status.NEEDS_METALLIUM_GL, decide(RAW, Map.of(), Set.of(), Map.of(), true, false, true).status());
        assertEquals(GlRouting.Status.AUTO_ROUTING_OFF, decide(RAW, Map.of(), Set.of(), Map.of(), false, true, true).status());
    }

    @Test
    void optOuts() {
        assertEquals(GlRouting.Status.OPTED_OUT, decide(RAW, Map.of(), Set.of(), Map.of("raw", ModOverride.NATIVE), true, true, true).status());
        assertEquals(GlRouting.Status.OPTED_OUT, decide(RAW, Map.of("raw", GlRouting.AuthorChoice.OPT_OUT), Set.of(), Map.of(), true, true, true).status());
        // the user's NATIVE override beats the author's opt-in
        assertEquals(GlRouting.Status.OPTED_OUT, decide(RAW, Map.of("raw", new GlRouting.AuthorChoice(true, List.of())), Set.of(),
                Map.of("raw", ModOverride.NATIVE), true, true, true).status());
    }

    @Test
    void explicitOptInsRouteEvenWithoutMetal() {
        GlRouting.Decision author = decide(NATIVE, Map.of("nat", new GlRouting.AuthorChoice(true, List.of("com/nat/render/"))), Set.of(), Map.of(),
                false, true, false);
        assertEquals(GlRouting.Status.ROUTED, author.status());
        assertEquals(List.of("com/nat/render/"), author.packages());
        assertFalse(author.allClasses());

        GlRouting.Decision user = decide(NATIVE, Map.of(), Set.of("nat"), Map.of(), true, true, true);
        assertEquals(GlRouting.Status.ROUTED, user.status());
        assertTrue(user.allClasses());

        GlRouting.Decision forced = decide(NATIVE, Map.of(), Set.of(), Map.of("nat", ModOverride.METALLIUM_GL), true, true, true);
        assertTrue(forced.routed());
        assertTrue(forced.allClasses());

        assertEquals(GlRouting.Status.NEEDS_METALLIUM_GL, decide(NATIVE, Map.of(), Set.of("nat"), Map.of(), true, false, true).status());
    }

    @Test
    void voxyIsRoutedAutomaticallyAsAWholeOnMetal() {
        assertTrue(GlRouting.WHOLE_MOD.contains("voxy"));
        GlRouting.Decision auto = GlRouting.decideWholeMod("voxy", Set.of(), Map.of(), true, true, true);
        assertEquals(GlRouting.Status.ROUTED, auto.status());
        assertTrue(auto.automatic());
        assertTrue(auto.allClasses());
        assertEquals(GlRouting.Status.NEEDS_METALLIUM_GL, GlRouting.decideWholeMod("voxy", Set.of(), Map.of(), true, false, true).status());
        assertEquals(GlRouting.Status.AUTO_ROUTING_OFF, GlRouting.decideWholeMod("voxy", Set.of(), Map.of(), false, true, true).status());
        assertEquals(GlRouting.Status.OPENGL_ENGINE, GlRouting.decideWholeMod("voxy", Set.of(), Map.of(), true, true, false).status());
        assertEquals(GlRouting.Status.OPTED_OUT, GlRouting.decideWholeMod("voxy", Set.of(), Map.of("voxy", ModOverride.NATIVE), true, true, true).status());
        // explicit requests route even with automatic routing off or without Metal (calls then fall back to OpenGL)
        GlRouting.Decision listed = GlRouting.decideWholeMod("voxy", Set.of("voxy"), Map.of(), false, true, false);
        assertEquals(GlRouting.Status.ROUTED, listed.status());
        assertFalse(listed.automatic());
    }
}
