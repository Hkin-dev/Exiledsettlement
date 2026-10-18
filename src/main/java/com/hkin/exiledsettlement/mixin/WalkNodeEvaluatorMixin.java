package com.hkin.exiledsettlement.mixin;

import com.hkin.exiledsettlement.road.RoadPathPreference;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin extends NodeEvaluator {

    @Inject(method = "getNeighbors", at = @At("HEAD"))
    private void exiledsettlement$restoreAcceptedRouteDistance(
            Node[] outputArray, Node node, CallbackInfoReturnable<Integer> cir
    ) {
        RoadPathPreference.restoreAcceptedRouteDistance(mob, node);
    }

    @Inject(method = "findAcceptedNode", at = @At("RETURN"))
    private void exiledsettlement$preferSettlementRoads(
            int x, int y, int z, int verticalDeltaLimit, double nodeFloorLevel,
            Direction direction, PathType pathType, CallbackInfoReturnable<Node> cir
    ) {
        RoadPathPreference.apply(mob, cir.getReturnValue());
    }
}
