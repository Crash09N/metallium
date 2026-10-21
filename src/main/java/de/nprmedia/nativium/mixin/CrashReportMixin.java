/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.mixin;

import de.nprmedia.nativium.compat.CrashGuard;
import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.List;

/** Crash guard: a saved crash report makes this session a crash even though Minecraft then exits through System.exit(-1). */
@Mixin(CrashReport.class)
abstract class CrashReportMixin {
    @Inject(method = "saveToFile(Ljava/nio/file/Path;Lnet/minecraft/ReportType;Ljava/util/List;)Z", at = @At("RETURN"))
    private void nativium$crashReportSaved(final Path file, final ReportType type, final List<String> extra,
                                            final CallbackInfoReturnable<Boolean> cir) {
        if (type == ReportType.CRASH) {
            CrashGuard.noteCrashReport();
        }
    }
}
