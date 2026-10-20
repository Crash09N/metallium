/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.compat;

import dev.metallium.MetalliumConfig.OpenGlChoice;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenGlRequirementsTest {
    private static final ModReport PHYSICS = new ModReport("physicsmod", "Physics Mod", "3.2.5", List.of("net.d.Gl"), 25,
            List.of(), List.of("net.d"), List.of("net.d.render.util.PhysicsRenderUtil$GlSampler3D"));
    private static final ModReport BUILDER = new ModReport("builder", "Builder", "1.0", List.of(), 2, List.of(), List.of(),
            List.of("com.b.MyGlTexture", "com.b.Other"));
    private static final ModReport XAERO = new ModReport("xaerolib", "XaeroLib", "1.7", List.of("x.Gl"), 1, List.of(), List.of("x"));
    private static final OpenGlRequirements.KnownMod PHYSICS_ENTRY =
            new OpenGlRequirements.KnownMod("physicsmod", "Physics Mod", "builds GL objects", null);
    private static final OpenGlRequirements.VersionMatcher NEVER = (v, p) -> false;

    @Test
    void listedAndHeuristicModsAreFound() {
        List<OpenGlRequirements.Requirement> r = OpenGlRequirements.requirements(List.of(PHYSICS, BUILDER, XAERO), List.of(PHYSICS_ENTRY), NEVER);
        assertEquals(List.of("physicsmod", "builder"), r.stream().map(OpenGlRequirements.Requirement::id).toList());
        assertTrue(r.get(0).listed());
        assertEquals("builds GL objects", r.get(0).reason());
        assertFalse(r.get(1).listed());
        assertTrue(r.get(1).reason().contains("2 classes"), r.get(1).reason());
    }

    @Test
    void versionsHandledByMetalliumAreNotRequirements() {
        OpenGlRequirements.KnownMod handled = new OpenGlRequirements.KnownMod("physicsmod", "Physics Mod", "x", ">=3.2.5 <3.3");
        OpenGlRequirements.VersionMatcher matcher = (v, p) -> v.equals("3.2.5") && p.equals(">=3.2.5 <3.3");
        assertTrue(OpenGlRequirements.requirements(List.of(PHYSICS), List.of(handled), matcher).isEmpty());
        ModReport newer = new ModReport("physicsmod", "Physics Mod", "3.4.0", List.of(), 1, List.of(), List.of(), List.of("a.B"));
        assertEquals(1, OpenGlRequirements.requirements(List.of(newer), List.of(handled), matcher).size());
    }

    @Test
    void noRequirementsOffersMetal() {
        OpenGlRequirements.Outcome o = OpenGlRequirements.decide(List.of(), Map.of());
        assertTrue(o.offerMetal());
        assertFalse(o.ask());
    }

    @Test
    void unansweredMeansOpenGlAndAsk() {
        List<OpenGlRequirements.Requirement> r = OpenGlRequirements.requirements(List.of(PHYSICS), List.of(PHYSICS_ENTRY), NEVER);
        OpenGlRequirements.Outcome o = OpenGlRequirements.decide(r, Map.of());
        assertFalse(o.offerMetal());
        assertTrue(o.ask());
        assertTrue(o.reason().contains("Physics Mod"), o.reason());
    }

    @Test
    void rememberedChoices() {
        List<OpenGlRequirements.Requirement> r = OpenGlRequirements.requirements(List.of(PHYSICS, BUILDER), List.of(PHYSICS_ENTRY), NEVER);
        OpenGlRequirements.Outcome gl = OpenGlRequirements.decide(r, Map.of("physicsmod", OpenGlChoice.OPENGL, "builder", OpenGlChoice.METAL));
        assertFalse(gl.offerMetal());
        assertFalse(gl.ask());
        OpenGlRequirements.Outcome partly = OpenGlRequirements.decide(r, Map.of("physicsmod", OpenGlChoice.METAL));
        assertFalse(partly.offerMetal());
        assertEquals(List.of("builder"), partly.unanswered().stream().map(OpenGlRequirements.Requirement::id).toList());
        OpenGlRequirements.Outcome metal = OpenGlRequirements.decide(r, Map.of("physicsmod", OpenGlChoice.METAL, "builder", OpenGlChoice.METAL));
        assertTrue(metal.offerMetal());
        assertFalse(metal.ask());
    }

    @Test
    void failedMetalAttemptIsForgotten() {
        List<OpenGlRequirements.Requirement> r = OpenGlRequirements.requirements(List.of(PHYSICS), List.of(PHYSICS_ENTRY), NEVER);
        Map<String, OpenGlChoice> choices = new HashMap<>(Map.of("physicsmod", OpenGlChoice.METAL, "other", OpenGlChoice.METAL));
        assertTrue(OpenGlRequirements.forgetMetalChoices(r, choices));
        assertEquals(Map.of("other", OpenGlChoice.METAL), choices);
        assertFalse(OpenGlRequirements.forgetMetalChoices(r, choices));
    }

    @Test
    void bundledListParsesAndNamesPhysicsMod() throws Exception {
        try (var in = OpenGlRequirements.class.getResourceAsStream("/assets/metallium/compat/needs-opengl.json")) {
            assertNotNull(in);
            List<OpenGlRequirements.KnownMod> mods = OpenGlRequirements.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            assertTrue(mods.stream().anyMatch(m -> m.id().equals("physicsmod")), mods.toString());
            // 3.2.5 runs on Metal through Metallium's Physics Mod compatibility; other versions are asked about.
            assertTrue(OpenGlRequirements.requirements(List.of(PHYSICS), mods, OpenGlRequirements::matches).isEmpty());
            ModReport other = new ModReport("physicsmod", "Physics Mod", "3.2.6", List.of(), 1, List.of(), List.of(), List.of("a.B"));
            assertEquals(1, OpenGlRequirements.requirements(List.of(other), mods, OpenGlRequirements::matches).size());
        }
        assertTrue(OpenGlRequirements.parse(new StringReader("{\"mods\": [{\"name\": \"no id\"}, null]}")).isEmpty());
    }

    @Test
    void fabricVersionPredicates() {
        assertTrue(OpenGlRequirements.matches("3.2.5", ">=3.2.5 <3.3"));
        assertFalse(OpenGlRequirements.matches("3.3.0", ">=3.2.5 <3.3"));
        assertFalse(OpenGlRequirements.matches("3.2.5", "not a predicate ((("));
    }
}
