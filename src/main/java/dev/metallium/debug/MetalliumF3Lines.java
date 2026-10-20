/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.debug;

import dev.metallium.BackendSelection;
import dev.metallium.Metallium;
import dev.metallium.MetalliumConfig;
import dev.metallium.api.MetalliumRuntime;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * The Metallium lines of the F3 overlay: shared by the vanilla debug screen ({@code DebugEntrySystemSpecsMixin}) and by
 * the BetterF3 compatibility mixin, which replaces the vanilla text with its own modules.
 */
public final class MetalliumF3Lines {
    private MetalliumF3Lines() {
    }

    /** The main "Metallium x.y: Metal active ..." line followed by the registered status lines (Iris adapter, power, ...). */
    public static List<String> lines() {
        List<String> result = new ArrayList<>();
        result.add(mainLine());
        if (MetalliumRuntime.isMetalActive()) {
            String present = dev.metallium.backend.PresentStats.f3Line();
            if (present != null) {
                result.add(present);
            }
        }
        result.addAll(MetalliumRuntime.statusLines());
        return result;
    }

    public static String mainLine() {
        String version = FabricLoader.getInstance().getModContainer(Metallium.MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
        if (MetalliumRuntime.isMetalActive()) {
            String details = MetalliumRuntime.metalDescription();
            return "Metallium " + version + ": Metal active" + (details.isEmpty() ? "" : " · " + details);
        }
        MetalliumConfig.GraphicsEngine engine = MetalliumConfig.INSTANCE.engine;
        if (engine != MetalliumConfig.GraphicsEngine.METAL) {
            return "Metallium " + version + ": inactive (Graphics Engine: " + engine + ")";
        }
        BackendSelection.Decision decision = BackendSelection.decision();
        return "Metallium " + version + ": inactive (" + (decision.offerMetal() ? "Metal init failed, using fallback" : decision.reason()) + ")";
    }
}
