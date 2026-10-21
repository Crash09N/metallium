/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.debug;

import de.nprmedia.nativium.BackendSelection;
import de.nprmedia.nativium.Nativium;
import de.nprmedia.nativium.NativiumConfig;
import de.nprmedia.nativium.api.NativiumRuntime;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * The Nativium lines of the F3 overlay: shared by the vanilla debug screen ({@code DebugEntrySystemSpecsMixin}) and by
 * the BetterF3 compatibility mixin, which replaces the vanilla text with its own modules.
 */
public final class NativiumF3Lines {
    private NativiumF3Lines() {
    }

    /** The main "Nativium x.y: Metal active ..." line followed by the registered status lines (Iris adapter, power, ...). */
    public static List<String> lines() {
        List<String> result = new ArrayList<>();
        result.add(mainLine());
        if (NativiumRuntime.isMetalActive()) {
            String present = de.nprmedia.nativium.backend.PresentStats.f3Line();
            if (present != null) {
                result.add(present);
            }
        }
        result.addAll(NativiumRuntime.statusLines());
        return result;
    }

    public static String mainLine() {
        String version = FabricLoader.getInstance().getModContainer(Nativium.MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
        if (NativiumRuntime.isMetalActive()) {
            String details = NativiumRuntime.metalDescription();
            return "Nativium " + version + ": Metal active" + (details.isEmpty() ? "" : " · " + details);
        }
        NativiumConfig.GraphicsEngine engine = NativiumConfig.INSTANCE.engine;
        if (engine != NativiumConfig.GraphicsEngine.METAL) {
            return "Nativium " + version + ": inactive (Graphics Engine: " + engine + ")";
        }
        BackendSelection.Decision decision = BackendSelection.decision();
        return "Nativium " + version + ": inactive (" + (decision.offerMetal() ? "Metal init failed, using fallback" : decision.reason()) + ")";
    }
}
