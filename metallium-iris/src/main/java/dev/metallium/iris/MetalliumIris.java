package dev.metallium.iris;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point of the Iris adapter. The adapter is work in progress; see docs/design/iris-adapter-blueprint.md. */
public final class MetalliumIris implements ClientModInitializer {
    /**
     * F3 line / status screen: whether a shader pack renders through the Metal pipeline. Based on the pipeline that
     * actually rendered the last level frame, not only on Iris's PipelineManager (the owner's modpack showed "shaders
     * off" in F3 while a pack was visibly rendering).
     */
    static String statusLine() {
        if (!dev.metallium.api.MetalliumRuntime.isMetalActive()) {
            return "Iris via Metallium adapter: inactive (OpenGL)";
        }
        dev.metallium.iris.post.MetalPostPipeline pipeline = dev.metallium.iris.post.MetalPostPipeline.rendering();
        if (pipeline == null && net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable() instanceof dev.metallium.iris.post.MetalPostPipeline metal) {
            pipeline = metal;
        }
        String pack = packName();
        if (pipeline != null) {
            return "Iris via Metallium adapter: " + (pack == null ? "shader pack" : pack) + " on Metal"
                    + (pipeline.hasFailed() ? " (failed, vanilla fallback; see log)" : " (" + pipeline.statusSummary() + ")");
        }
        if (pack != null && net.irisshaders.iris.Iris.getIrisConfig().areShadersEnabled()) {
            // Selected and enabled but no Metal pipeline rendered (no world yet, or creation failed: see the log).
            return "Iris via Metallium adapter: " + pack + " selected, not rendering on Metal";
        }
        return "Iris via Metallium adapter: shaders off";
    }

    /** Status screen / F3: unsupported features of the running pack (null = none). */
    static String problemsLine() {
        dev.metallium.iris.post.MetalPostPipeline pipeline = dev.metallium.iris.post.MetalPostPipeline.rendering();
        if (pipeline == null || pipeline.statusProblems().isEmpty()) {
            return null;
        }
        java.util.List<String> problems = pipeline.statusProblems();
        return "Shader pack on Metal, not supported yet: " + problems.getFirst()
                + (problems.size() > 1 ? " (+" + (problems.size() - 1) + " more in the log)" : "");
    }

    private static String packName() {
        try {
            return net.irisshaders.iris.Iris.getCurrentPack().isPresent() ? net.irisshaders.iris.Iris.getCurrentPackName() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static final Logger LOGGER = LoggerFactory.getLogger("metallium-iris");

    @Override
    public void onInitializeClient() {
        if (!dev.metallium.MetalliumPlatform.supported()) {
            return; // unsupported platform: Metallium stays silent (one core log line)
        }
        if (!dev.metallium.BackendSelection.shouldOfferMetal()) {
            LOGGER.info("[metallium-iris] inactive: Metal is not used this session; Iris runs its own OpenGL pipeline");
            return;
        }
        if (!dev.metallium.compat.IrisSupport.adapterUsable()) {
            LOGGER.info("[metallium-iris] inactive: {}", dev.metallium.compat.IrisSupport.problem());
            return;
        }
        LOGGER.info("[metallium-iris] Iris adapter loaded (work in progress)");
        dev.metallium.api.MetalliumRuntime.registerStatusLine(MetalliumIris::statusLine);
        dev.metallium.api.MetalliumRuntime.registerStatusLine(MetalliumIris::problemsLine);
    }
}
