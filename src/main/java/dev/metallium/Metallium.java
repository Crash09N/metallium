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
    }
}
