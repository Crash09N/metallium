/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.iris;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point of the Iris adapter. The adapter is work in progress; see docs/design/iris-adapter-blueprint.md. */
public final class NativiumIris implements ClientModInitializer {
    /**
     * F3 line / status screen: whether a shader pack renders through the Metal pipeline. Based on the pipeline that
     * actually rendered the last level frame, not only on Iris's PipelineManager (the owner's modpack showed "shaders
     * off" in F3 while a pack was visibly rendering).
     */
    static String statusLine() {
        if (!de.nprmedia.nativium.api.NativiumRuntime.isMetalActive()) {
            return "Iris via Nativium adapter: inactive (OpenGL)";
        }
        de.nprmedia.nativium.iris.post.MetalPostPipeline pipeline = de.nprmedia.nativium.iris.post.MetalPostPipeline.rendering();
        if (pipeline == null && net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable() instanceof de.nprmedia.nativium.iris.post.MetalPostPipeline metal) {
            pipeline = metal;
        }
        String pack = packName();
        if (pipeline != null) {
            return "Iris via Nativium adapter: " + (pack == null ? "shader pack" : pack) + " on Metal"
                    + (pipeline.hasFailed() ? " (failed, vanilla fallback; see log)" : " (" + pipeline.statusSummary()
                    + (pipeline.voxyLodsActive() ? "; Voxy LODs drawn with the pack" : "")
                    + (pipeline.dhLodsActive() ? "; Distant Horizons LODs drawn with the pack" : "") + ")");
        }
        if (pack != null && net.irisshaders.iris.Iris.getIrisConfig().areShadersEnabled()) {
            // Selected and enabled but no Metal pipeline rendered (no world yet, or creation failed: see the log).
            return "Iris via Nativium adapter: " + pack + " selected, not rendering on Metal";
        }
        return "Iris via Nativium adapter: shaders off";
    }

    /** Status screen / F3: unsupported features of the running pack (null = none). */
    static String problemsLine() {
        de.nprmedia.nativium.iris.post.MetalPostPipeline pipeline = de.nprmedia.nativium.iris.post.MetalPostPipeline.rendering();
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

    public static final Logger LOGGER = LoggerFactory.getLogger("nativium-iris");

    @Override
    public void onInitializeClient() {
        if (!de.nprmedia.nativium.NativiumPlatform.supported()) {
            return; // unsupported platform: Nativium stays silent (one core log line)
        }
        if (!de.nprmedia.nativium.BackendSelection.shouldOfferMetal()) {
            LOGGER.info("[nativium-iris] inactive: Metal is not used this session; Iris runs its own OpenGL pipeline");
            return;
        }
        if (!de.nprmedia.nativium.compat.IrisSupport.adapterUsable()) {
            LOGGER.info("[nativium-iris] inactive: {}", de.nprmedia.nativium.compat.IrisSupport.problem());
            return;
        }
        LOGGER.info("[nativium-iris] Iris adapter loaded (work in progress)");
        // Voxy draws its LODs with the pack through VoxyIrisBridge (packs with voxy.json); otherwise (no Voxy patch in the
        // pack, bridge failure) core pauses Voxy's LODs while a pack renders on Metal.
        de.nprmedia.nativium.compat.voxy.VoxyCompat.registerShaderPackProbe(() ->
                net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable() instanceof de.nprmedia.nativium.iris.post.MetalPostPipeline pipeline
                        && !pipeline.voxyLodsActive());
        de.nprmedia.nativium.api.NativiumRuntime.registerStatusLine(NativiumIris::statusLine);
        de.nprmedia.nativium.api.NativiumRuntime.registerStatusLine(NativiumIris::problemsLine);
    }
}
