package com.hkin.exiledsettlement.mixin;

import com.hkin.exiledsettlement.region.PastureGroundSafety;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.ai.goal.Goal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only replaces the vanilla leash navigation command during a real pasture transfer. */
@Mixin(PathfinderMob.class)
public abstract class PastureLeashNavigationMixin {
    @Inject(method="closeRangeLeashBehaviour",at=@At("HEAD"),cancellable=true)
    private void exiledSettlement$controlledAnimalRoute(Entity holder,CallbackInfo ci){
        if((Object)this instanceof Animal animal && PastureGroundSafety.controlsAi(animal)){
            animal.goalSelector.enableControlFlag(Goal.Flag.MOVE);
            ci.cancel();
        }
    }
}
