package dev.metallium.iris;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point of the Iris adapter. The adapter is work in progress; see docs/design/iris-adapter-blueprint.md. */
public final class MetalliumIris implements ClientModInitializer {
    /** F3 line: shows that Iris runs through this adapter and which pipeline/pack is active. */
    private static String statusLine() {
        if (!dev.metallium.api.MetalliumRuntime.isMetalActive()) {
            return "Iris via Metallium adapter: inactive (OpenGL)";
        }
        var pipeline = net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
        if (pipeline instanceof dev.metallium.iris.post.MetalPostPipeline) {
            String pack = net.irisshaders.iris.Iris.getCurrentPackName();
            return "Iris via Metallium adapter: Metal post pipeline (" + pack + ")";
        }
        return "Iris via Metallium adapter: shaders off";
    }

    public static final Logger LOGGER = LoggerFactory.getLogger("metallium-iris");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[metallium-iris] Iris adapter loaded (work in progress)");
        dev.metallium.api.MetalliumRuntime.registerStatusLine(MetalliumIris::statusLine);
    }
}
