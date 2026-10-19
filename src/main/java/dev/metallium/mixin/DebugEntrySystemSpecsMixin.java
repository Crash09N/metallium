package dev.metallium.mixin;

import dev.metallium.debug.MetalliumF3Lines;
import net.minecraft.client.gui.components.debug.DebugEntrySystemSpecs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Adds a "Metallium" line to the F3 system-specs block (right side, next to the GPU/backend lines). BetterF3 does not
 * draw this block; see {@code dev.metallium.compat.betterf3.mixin.MiscRightModuleMixin}.
 */
@Mixin(DebugEntrySystemSpecs.class)
abstract class DebugEntrySystemSpecsMixin {
    @ModifyArg(
            method = "display",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/debug/DebugScreenDisplayer;addToGroup(Lnet/minecraft/resources/Identifier;Ljava/util/Collection;)V"),
            index = 1
    )
    private Collection<String> metallium$addStatusLine(final Collection<String> lines) {
        List<String> result = new ArrayList<>(lines);
        result.addAll(MetalliumF3Lines.lines());
        return result;
    }
}
