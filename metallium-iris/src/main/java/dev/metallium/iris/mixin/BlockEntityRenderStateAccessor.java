package dev.metallium.iris.mixin;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Stage 4 shadow pass: "light block entities only" filter (Iris reads the field through its access widener). */
@Mixin(BlockEntityRenderState.class)
public interface BlockEntityRenderStateAccessor {
    @Accessor("blockState")
    BlockState metallium$getBlockState();
}
