package com.hkin.exiledsettlement.mixin;

import com.hkin.exiledsettlement.citizen.ConstructionPlanningBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observation only: successful block/state writes invalidate nearby active planning memos. */
@Mixin(Level.class)
public abstract class ConstructionPlanningLevelMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"))
    private void exiledsettlement$invalidateConstructionQueries(
            BlockPos position, BlockState state, int flags, int recursionLeft,
            CallbackInfoReturnable<Boolean> result
    ) {
        if (result.getReturnValueZ()) {
            ConstructionPlanningBudget.blockChanged((Level) (Object) this, position);
            com.hkin.exiledsettlement.settlement.SeasonWeatherService.onBlockChanged((Level) (Object) this, position);
        }
    }
}
