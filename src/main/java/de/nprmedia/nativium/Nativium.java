/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Nativium implements ClientModInitializer {
    public static final String MOD_ID = "nativium";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        if (!NativiumPlatform.supported()) {
            return; // inert: NativiumPlatform logged its single line, nothing else happens
        }
        de.nprmedia.nativium.compat.JitWorkarounds.apply(); // C2 crash in Sodium+Iris meshing (see the class)
        BackendSelection.logDecision();
        if (BackendSelection.shouldOfferMetal()) {
            de.nprmedia.nativium.power.PowerManager.start(); // the power mode only caps FPS on Metal
            de.nprmedia.nativium.api.NativiumRuntime.registerStatusLine(de.nprmedia.nativium.power.PowerManager::statusLine);
        }
        de.nprmedia.nativium.compat.GlRouting.plan(); // scans mods (cached) and logs which ones call OpenGL directly
    }
}
