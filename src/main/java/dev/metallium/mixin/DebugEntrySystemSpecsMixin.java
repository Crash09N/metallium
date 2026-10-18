package dev.metallium.mixin;

import dev.metallium.BackendSelection;
import dev.metallium.Metallium;
import dev.metallium.api.MetalliumRuntime;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.debug.DebugEntrySystemSpecs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Adds a "Metallium" line to the F3 system-specs block (right side, next to the GPU/backend lines). */
@Mixin(DebugEntrySystemSpecs.class)
abstract class DebugEntrySystemSpecsMixin {
    @ModifyArg(
            method = "display",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/debug/DebugScreenDisplayer;addToGroup(Lnet/minecraft/resources/Identifier;Ljava/util/Collection;)V"),
            index = 1
    )
    private Collection<String> metallium$addStatusLine(final Collection<String> lines) {
        List<String> result = new ArrayList<>(lines);
        result.add(metallium$statusLine());
        result.addAll(MetalliumRuntime.statusLines());
        return result;
    }

    private static String metallium$statusLine() {
        String version = FabricLoader.getInstance().getModContainer(Metallium.MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
        if (MetalliumRuntime.isMetalActive()) {
            return "Metallium " + version + ": Metal active";
        }
        dev.metallium.MetalliumConfig.GraphicsEngine engine = dev.metallium.MetalliumConfig.INSTANCE.engine;
        if (engine != dev.metallium.MetalliumConfig.GraphicsEngine.METAL) {
            return "Metallium " + version + ": inactive (Graphics Engine: " + engine + ")";
        }
        BackendSelection.Decision decision = BackendSelection.decision();
        return "Metallium " + version + ": inactive (" + (decision.offerMetal() ? "Metal init failed, using fallback" : decision.reason()) + ")";
    }
}
