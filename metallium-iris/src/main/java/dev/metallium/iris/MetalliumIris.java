package dev.metallium.iris;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point of the Iris adapter. The adapter is work in progress; see docs/design/iris-adapter-blueprint.md. */
public final class MetalliumIris implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("metallium-iris");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[metallium-iris] Iris adapter loaded (work in progress)");
    }
}
