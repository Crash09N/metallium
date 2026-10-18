package dev.metallium;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Metallium implements ClientModInitializer {
    public static final String MOD_ID = "metallium";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        BackendSelection.logDecision();
        dev.metallium.power.PowerManager.start();
        dev.metallium.api.MetalliumRuntime.registerStatusLine(dev.metallium.power.PowerManager::statusLine);
        dev.metallium.compat.GlRouting.plan(); // scans mods (cached) and logs which ones call OpenGL directly
    }
}
