/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package dev.metallium.compat.betterf3.mixin;

import dev.metallium.debug.MetalliumF3Lines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.ArrayList;
import java.util.List;

/**
 * BetterF3 (MIT, compatibility only) replaces the vanilla F3 text with its own modules, so the "Metallium" lines that
 * {@code DebugEntrySystemSpecsMixin} adds to the vanilla system-specs block never reach its HUD: its "misc_right" module
 * receives the vanilla right-hand lines and filters the system-specs part out. BetterF3 has no API for other mods, so
 * this appends Metallium's lines to the list that module hands to its line list (right column, below the
 * "BetterF3 Version" line). {@code @Pseudo}: does nothing without BetterF3, and no BetterF3 class is referenced by code.
 */
@Pseudo
@Mixin(targets = "me.cominixo.betterf3.modules.MiscRightModule", remap = false)
public abstract class MiscRightModuleMixin {
    @ModifyArg(
            method = "update(Ljava/util/List;)V",
            at = @At(value = "INVOKE", target = "Lme/cominixo/betterf3/utils/DebugLineList;values(Ljava/util/List;)V", remap = false),
            index = 0,
            remap = false,
            require = 0
    )
    private List<String> metallium$appendLines(final List<String> values) {
        List<String> result = new ArrayList<>(values);
        result.addAll(MetalliumF3Lines.lines());
        return result;
    }
}
