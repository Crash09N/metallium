package dev.metallium.gametest.mixin;

import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DeltaTracker.Timer.class)
public interface DeltaTrackerTimerAccessor {
    @Accessor("lastMs")
    long metallium$getLastMs();

    @Accessor("lastMs")
    void metallium$setLastMs(long value);
}
