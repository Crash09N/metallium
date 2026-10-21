/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Crash09N – NPR Media
 */
package de.nprmedia.nativium.gametest.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import de.nprmedia.nativium.gametest.LockstepStats;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Measures the render thread's wait in Fabric's client-gametest lock-step barrier (Fabric waits right after
 * {@code runAllTasks()} in {@code runTick} when the frame runs ticks) and, for benchmarks, takes that wait out of the
 * client's tick clock. See {@link LockstepStats} for why.
 */
@Mixin(Minecraft.class)
abstract class LockstepTickMixin {
    @Shadow
    @Final
    private DeltaTracker.Timer deltaTracker;

    @Unique
    private long nativium$barrierStartNs;
    @Unique
    private long nativium$shiftCarryNs;

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V"))
    private void nativium$beforeTasks(final boolean advanceGameTime, final CallbackInfo ci) {
        this.nativium$barrierStartNs = System.nanoTime();
    }

    /** First {@code profiler.pop()} after {@code runAllTasks()}: Fabric's barrier (injected after runAllTasks) is behind us. */
    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;pop()V", ordinal = 0))
    private void nativium$afterBarrier(final boolean advanceGameTime, final CallbackInfo ci, @Local(ordinal = 0) final int ticks) {
        if (ticks <= 0 || this.nativium$barrierStartNs == 0L) {
            return;
        }
        long waited = System.nanoTime() - this.nativium$barrierStartNs;
        LockstepStats.record(waited);
        if (LockstepStats.FIX && waited > 1_000_000L) {
            long ns = this.nativium$shiftCarryNs + waited;
            long ms = ns / 1_000_000L;
            this.nativium$shiftCarryNs = ns - ms * 1_000_000L;
            ((DeltaTrackerTimerAccessor) this.deltaTracker).nativium$setLastMs(
                    ((DeltaTrackerTimerAccessor) this.deltaTracker).nativium$getLastMs() + ms);
            LockstepStats.shiftedMs += ms;
        }
    }
}
