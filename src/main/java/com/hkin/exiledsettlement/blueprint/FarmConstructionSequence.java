package com.hkin.exiledsettlement.blueprint;

import com.hkin.exiledsettlement.building.BuildingType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import java.util.*;

/** Derive construction actions from live soil. Saved geometry and placement indices stay unchanged. */
public final class FarmConstructionSequence {
    private FarmConstructionSequence() {}

    public static boolean applies(BuildingBlueprintTemplate template) {
        return template.type()==BuildingType.FARM_FIELD || template.type()==BuildingType.MELON_FIELD;
    }

    public static boolean tillGoal(BuildingBlueprintTemplate template, BlueprintPlacement goal) {
        return applies(template) && goal.state().is(Blocks.FARMLAND);
    }

    public static BlueprintPlacement stage(ServerLevel level, BuildingBlueprintTemplate template, BlueprintPlacement goal) {
        if(applies(template) && goal.state().is(Blocks.DIRT))
            return new BlueprintPlacement(goal.position(),goal.state(),Items.DIRT,goal.mode());
        if(!tillGoal(template,goal))return IrrigationConstructionService.stage(level,template,goal);
        if(!FarmEarthworkPlan.soil(level.getBlockState(goal.position())))
            return new BlueprintPlacement(goal.position(),Blocks.DIRT.defaultBlockState(),Items.DIRT,BlueprintPlacementMode.STRUCTURE);
        return new BlueprintPlacement(goal.position(),goal.state(),null,BlueprintPlacementMode.FARM_TILL);
    }

    private static int rank(BlueprintPlacement action) {
        if(action.mode()==BlueprintPlacementMode.CLEARANCE)return -1;
        if(action.mode()==BlueprintPlacementMode.FARM_TILL)return 3;
        if(action.state().getBlock() instanceof CropBlock || action.state().getBlock() instanceof StemBlock)return 4;
        if(action.state().getBlock() instanceof FenceBlock || action.state().getBlock() instanceof FenceGateBlock)return 2;
        if(action.material()==Items.WATER_BUCKET || action.mode()==BlueprintPlacementMode.FARM_WATER)return 1;
        return 0; // Soil, paths and dry irrigation lining form the construction layer.
    }

    /** Claimed, protected and unready cells still hold the phase barrier for all workers. */
    public static List<BlueprintService.PendingPlacement> pending(ServerLevel level, BlueprintRecord site,
            BuildingBlueprintTemplate template, Set<net.minecraft.core.BlockPos> claimed) {
        int phase=Integer.MAX_VALUE;
        var result=new ArrayList<BlueprintService.PendingPlacement>();
        for(int i=0;i<template.placements().size();i++) {
            var goal=template.placements().get(i);
            if(!level.hasChunkAt(goal.position()) || level.isOutsideBuildHeight(goal.position()))return List.of();
            if(BlueprintService.placementSatisfied(level,template,goal) && !site.hasTemporaryFooting(goal.position()))continue;
            var action=stage(level,template,goal);
            int next=rank(action);
            if(next<phase){phase=next;result.clear();}
            if(next==phase && !claimed.contains(action.position())
                    && !com.hkin.exiledsettlement.building.MobRecoveryService.owns(level,action.position())
                    && IrrigationConstructionService.ready(level,template,action))
                result.add(new BlueprintService.PendingPlacement(i,action));
        }
        return result;
    }

    /** Tilling transforms existing soil; it neither consumes farmland nor yields displaced dirt. */
    public static boolean till(ServerLevel level, BlueprintPlacement action, LivingEntity worker) {
        var at=action.position();
        if(!FarmEarthworkPlan.soil(level.getBlockState(at)) || !FarmEarthworkPlan.support(level,at.below())
                || !level.getBlockState(at.above()).isAir() || !action.state().canSurvive(level,at)
                || !CommonHooks.canEntityDestroy(level,at,worker))return false;
        var snapshot=BlockSnapshot.create(level.dimension(),level,at,Block.UPDATE_CLIENTS);
        if(!level.setBlock(at,action.state(),Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS))return false;
        if(EventHooks.onBlockPlace(worker,snapshot,Direction.UP) || !level.getBlockState(at).is(Blocks.FARMLAND)) {
            snapshot.restore(Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);return false;
        }
        return true;
    }

    public static boolean clearancePending(ServerLevel level, BlueprintRecord site) {
        return site!=null && applies(BlueprintTemplates.create(site)) && TimedConstructionService.clearancePending(level,site);
    }
}
