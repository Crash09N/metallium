package dev.metallium.compat.sodium.mixin;

import dev.metallium.GraphicsApiLabels;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tooltip of Sodium's Graphics API option while the Metal entry (vanilla DEFAULT) is selected. Interop by name only. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.config.structure.StatefulOption", remap = false)
public abstract class StatefulOptionMixin {
    @Shadow
    public abstract Object getValidatedValue();

    @Inject(method = "getTooltip", at = @At("HEAD"), cancellable = true, require = 0)
    private void metallium$metalTooltip(final CallbackInfoReturnable<Component> cir) {
        if (GraphicsApiLabels.isMetalEntry(this.getValidatedValue())) {
            cir.setReturnValue(GraphicsApiLabels.metalTooltip());
        }
    }
}
