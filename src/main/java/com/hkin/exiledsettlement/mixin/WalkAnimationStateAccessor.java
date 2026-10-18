package com.hkin.exiledsettlement.mixin;

import net.minecraft.world.entity.WalkAnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Copies the exact vanilla walk phase into client-side YSM render proxies. */
@Mixin(WalkAnimationState.class)
public interface WalkAnimationStateAccessor {
    @Accessor("speedOld")
    float exiledSettlement$getSpeedOld();

    @Accessor("speedOld")
    void exiledSettlement$setSpeedOld(float value);

    @Accessor("speed")
    float exiledSettlement$getSpeed();

    @Accessor("speed")
    void exiledSettlement$setSpeed(float value);

    @Accessor("position")
    float exiledSettlement$getPosition();

    @Accessor("position")
    void exiledSettlement$setPosition(float value);
}
