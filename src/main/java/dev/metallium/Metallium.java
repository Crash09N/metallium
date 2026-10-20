package dev.metallium;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Metallium implements ClientModInitializer {
    public static final String MOD_ID = "metallium";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        if (!MetalliumPlatform.supported()) {
            return; // inert: MetalliumPlatform logged its single line, nothing else happens
        }
        BackendSelection.logDecision();
        if (BackendSelection.shouldOfferMetal()) {
            dev.metallium.power.PowerManager.start(); // the power mode only caps FPS on Metal
            dev.metallium.api.MetalliumRuntime.registerStatusLine(dev.metallium.power.PowerManager::statusLine);
        }
        dev.metallium.compat.GlRouting.plan(); // scans mods (cached) and logs which ones call OpenGL directly
    }
}
