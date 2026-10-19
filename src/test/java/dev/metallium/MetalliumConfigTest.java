/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium;

import dev.metallium.MetalliumConfig.GraphicsEngine;
import dev.metallium.MetalliumConfig.ModOverride;
import dev.metallium.power.PowerManager;
import dev.metallium.power.PowerMode;
import dev.metallium.power.PowerState;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetalliumConfigTest {
    @Test
    void legacyEnabledFalseBecomesOpenGl() {
        MetalliumConfig c = MetalliumConfig.parse("{\"enabled\": false, \"displayP3\": true}");
        assertEquals(GraphicsEngine.OPENGL, c.engine);
        assertNull(c.enabled);
        assertTrue(c.displayP3);
        assertFalse(c.toJson().contains("\"enabled\""));
    }

    @Test
    void preCompatCenterConfigGetsDefaults() {
        MetalliumConfig c = MetalliumConfig.parse("{\"engine\": \"METAL\", \"syncedVanillaApi\": \"DEFAULT\", \"displayP3\": false}");
        assertEquals(GraphicsEngine.METAL, c.engine);
        assertTrue(c.glAutoRouting);
        assertEquals(PowerMode.AUTOMATIC, c.powerMode);
        assertEquals(60, c.batteryFpsLimit);
        assertTrue(c.modOverrides.isEmpty());
    }

    @Test
    void unknownOrNullValuesAreRepaired() {
        MetalliumConfig c = MetalliumConfig.parse("""
                {"engine": "METAL", "powerMode": "TURBO", "batteryFpsLimit": 5000, "modOverrides": {"a": "NATIVE", "b": "SOMETHING", "c": "AUTO"}}""");
        assertEquals(PowerMode.AUTOMATIC, c.powerMode);
        assertEquals(240, c.batteryFpsLimit);
        assertEquals(Map.of("a", ModOverride.NATIVE), c.modOverrides);
        assertEquals(ModOverride.AUTO, c.override("b"));
    }

    @Test
    void openGlChoicesSurviveAndUnknownValuesAreDropped() {
        MetalliumConfig old = MetalliumConfig.parse("{\"engine\": \"METAL\"}");
        assertTrue(old.openGlChoices.isEmpty());
        MetalliumConfig c = MetalliumConfig.parse("{\"openGlChoices\": {\"physicsmod\": \"OPENGL\", \"x\": \"MAYBE\"}}");
        assertEquals(Map.of("physicsmod", MetalliumConfig.OpenGlChoice.OPENGL), c.openGlChoices);
        c.openGlChoices.put("y", MetalliumConfig.OpenGlChoice.METAL);
        assertEquals(MetalliumConfig.OpenGlChoice.METAL, MetalliumConfig.parse(c.toJson()).openGlChoices.get("y"));
    }

    @Test
    void brokenFileYieldsDefaults() {
        MetalliumConfig c = MetalliumConfig.parse("{ not json");
        assertEquals(GraphicsEngine.METAL, c.engine);
        assertTrue(c.glAutoRouting);
    }

    @Test
    void roundTrip() {
        MetalliumConfig c = MetalliumConfig.parse("{}");
        c.setOverride("mod", ModOverride.METALLIUM_GL);
        c.powerMode = PowerMode.LOW_POWER;
        MetalliumConfig back = MetalliumConfig.parse(c.toJson());
        assertEquals(ModOverride.METALLIUM_GL, back.override("mod"));
        assertEquals(PowerMode.LOW_POWER, back.powerMode);
        back.setOverride("mod", ModOverride.AUTO);
        assertTrue(back.modOverrides.isEmpty());
    }

    @Test
    void powerModeMapping() {
        PowerState ac = new PowerState(true, false, false, "AC Power", 0);
        PowerState battery = new PowerState(true, false, true, "Battery Power", 0);
        PowerState lowPower = new PowerState(true, true, true, "Battery Power", 0);
        PowerState hot = new PowerState(true, false, false, "AC Power", 2);
        assertEquals(PowerMode.PERFORMANCE, PowerManager.effective(PowerMode.AUTOMATIC, ac));
        assertEquals(PowerMode.BALANCED, PowerManager.effective(PowerMode.AUTOMATIC, battery));
        assertEquals(PowerMode.LOW_POWER, PowerManager.effective(PowerMode.AUTOMATIC, lowPower));
        assertEquals(PowerMode.LOW_POWER, PowerManager.effective(PowerMode.AUTOMATIC, hot));
        // Performance ignores macOS Low Power Mode
        assertEquals(PowerMode.PERFORMANCE, PowerManager.effective(PowerMode.PERFORMANCE, lowPower));
        assertEquals(0, PowerManager.fpsCap(PowerMode.PERFORMANCE, 60));
        assertEquals(60, PowerManager.fpsCap(PowerMode.BALANCED, 60));
        assertEquals(30, PowerManager.fpsCap(PowerMode.LOW_POWER, 60));
        assertEquals(24, PowerManager.fpsCap(PowerMode.LOW_POWER, 24));
    }
}
