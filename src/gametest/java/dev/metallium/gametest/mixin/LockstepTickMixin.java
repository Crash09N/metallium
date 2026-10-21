package dev.metallium.gametest.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.metallium.gametest.LockstepStats;
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
    private long metallium$barrierStartNs;
    @Unique
    private long metallium$shiftCarryNs;

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V"))
    private void metallium$beforeTasks(final boolean advanceGameTime, final CallbackInfo ci) {
        this.metallium$barrierStartNs = System.nanoTime();
    }

    /** First {@code profiler.pop()} after {@code runAllTasks()}: Fabric's barrier (injected after runAllTasks) is behind us. */
    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;pop()V", ordinal = 0))
    private void metallium$afterBarrier(final boolean advanceGameTime, final CallbackInfo ci, @Local(ordinal = 0) final int ticks) {
        if (ticks <= 0 || this.metallium$barrierStartNs == 0L) {
            return;
        }
        long waited = System.nanoTime() - this.metallium$barrierStartNs;
        LockstepStats.record(waited);
        if (LockstepStats.FIX && waited > 1_000_000L) {
            long ns = this.metallium$shiftCarryNs + waited;
            long ms = ns / 1_000_000L;
            this.metallium$shiftCarryNs = ns - ms * 1_000_000L;
            ((DeltaTrackerTimerAccessor) this.deltaTracker).metallium$setLastMs(
                    ((DeltaTrackerTimerAccessor) this.deltaTracker).metallium$getLastMs() + ms);
            LockstepStats.shiftedMs += ms;
        }
    }
}
