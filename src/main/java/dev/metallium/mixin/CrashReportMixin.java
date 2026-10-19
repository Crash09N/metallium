/*
 * Copyright (c) 2026 Metallium contributors, licensed under the Apache License 2.0.
 */
package dev.metallium.mixin;

import dev.metallium.compat.CrashGuard;
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
    private void metallium$crashReportSaved(final Path file, final ReportType type, final List<String> extra,
                                            final CallbackInfoReturnable<Boolean> cir) {
        if (type == ReportType.CRASH) {
            CrashGuard.noteCrashReport();
        }
    }
}
