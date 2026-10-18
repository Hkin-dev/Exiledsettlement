package com.hkin.exiledsettlement.blueprint;

import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.ResidentBlockDrops;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.Shapes;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;

/** A saved final irrigation cell has two real material-bearing construction actions. */
public final class IrrigationConstructionService {
    private IrrigationConstructionService() {}
    public static boolean applies(BuildingBlueprintTemplate template,BlueprintPlacement placement){
        if(template.type()!=BuildingType.FARM_FIELD && template.type()!=BuildingType.MELON_FIELD)return false;
        var state=placement.state();
        return state.is(Blocks.WATER) && placement.mode()==BlueprintPlacementMode.FARM_WATER
                || state.getBlock() instanceof SlabBlock && state.hasProperty(BlockStateProperties.WATERLOGGED)
                    && state.getValue(BlockStateProperties.WATERLOGGED);
    }
    /** Read the real block each time; no saved payment bit can create a missing slab or water. */
    public static BlueprintPlacement stage(ServerLevel level,BuildingBlueprintTemplate template,BlueprintPlacement goal){
        if(!applies(template,goal))return goal;
        if(goal.state().is(Blocks.WATER))return new BlueprintPlacement(goal.position(),goal.state(),Items.WATER_BUCKET,goal.mode());
        var dry=goal.state().setValue(BlockStateProperties.WATERLOGGED,false);
        var current=level.getBlockState(goal.position());
        if(BlueprintService.matchesPlacedState(current,dry) || BlueprintService.matchesPlacedState(current,goal.state()))
            return new BlueprintPlacement(goal.position(),goal.state(),Items.WATER_BUCKET,goal.mode());
        return new BlueprintPlacement(goal.position(),dry,dry.getBlock().asItem(),BlueprintPlacementMode.STRUCTURE);
    }
    public static boolean ready(ServerLevel level,BuildingBlueprintTemplate template,BlueprintPlacement action){
        if(!applies(template,action) || action.material()!=Items.WATER_BUCKET || action.state().is(Blocks.WATER))return true;
        var at=action.position();var dry=action.state().setValue(BlockStateProperties.WATERLOGGED,false);
        if(!BlueprintService.matchesPlacedState(level.getBlockState(at),dry)
                || !FarmEarthworkPlan.support(level,at.below()))return false;
        var shape=action.state().getCollisionShape(level,at);
        for(var side:Direction.Plane.HORIZONTAL){var adjacent=at.relative(side);
            if(!level.hasChunkAt(adjacent) || !Shapes.mergedFaceOccludes(shape,level.getBlockState(adjacent).getCollisionShape(level,adjacent),side))return false;}
        return true;
    }
    public static boolean waterlogging(BuildingBlueprintTemplate template,BlueprintPlacement action){
        return applies(template,action) && action.material()==Items.WATER_BUCKET && !action.state().is(Blocks.WATER);
    }
    /** Filling an existing slab is a state change, so it must not drop a duplicate slab. */
    public static boolean placeWater(ServerLevel level,BuildingBlueprintTemplate template,BlueprintPlacement action,LivingEntity worker){
        if(!ready(level,template,action))return false;
        var snapshot=BlockSnapshot.create(level.dimension(),level,action.position(),Block.UPDATE_CLIENTS);
        if(!level.setBlock(action.position(),action.state(),Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS))return false;
        if(EventHooks.onBlockPlace(worker,snapshot,Direction.UP) || !level.getBlockState(action.position()).equals(action.state())){
            snapshot.restore(Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);return false;
        }
        return true;
    }
    public static java.util.List<net.minecraft.world.item.Item> remainingMaterials(ServerLevel level,BuildingBlueprintTemplate template,BlueprintPlacement goal){
        if(!applies(template,goal) || BlueprintService.placementSatisfied(level,template,goal))return java.util.List.of();
        var next=stage(level,template,goal);
        return next.material()==Items.WATER_BUCKET?java.util.List.of(Items.WATER_BUCKET):java.util.List.of(next.material(),Items.WATER_BUCKET);
    }
    public static void returnBucket(ServerLevel level,BuildingBlueprintTemplate template,BlueprintPlacement action,Entity worker){
        if(action.material()!=Items.WATER_BUCKET || !applies(template,action))return;
        ResidentBlockDrops.run(level,worker,()->{Block.popResource(level,action.position(),new ItemStack(Items.BUCKET));return true;},Boolean.TRUE::equals);
    }
}
