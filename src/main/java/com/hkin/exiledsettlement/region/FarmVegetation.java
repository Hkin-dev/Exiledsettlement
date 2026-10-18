package com.hkin.exiledsettlement.region;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/** Recognise natural canopy without authorising removal of persistent player leaves or timber buildings. */
public final class FarmVegetation {
    private FarmVegetation() { }
    public static boolean leaf(BlockState state) {
        return state.getBlock() instanceof LeavesBlock && !state.getValue(LeavesBlock.PERSISTENT);
    }
    public static boolean tree(Level level,BlockPos top) {
        if(!level.getBlockState(top).is(BlockTags.LOGS))return false;
        for(int y=0;y<=8;y++)for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++) {
            var p=top.offset(x,y,z);if(level.hasChunkAt(p) && leaf(level.getBlockState(p)))return true;
        }
        return false;
    }
    public static boolean paidGround(Level level,BlockPos p,BlockState state,ProductionRegion r) {
        if(!(level instanceof net.minecraft.server.level.ServerLevel server)
                || com.hkin.exiledsettlement.road.RoadSpaceData.get(server).manual(p))return false;
        var settlement=com.hkin.exiledsettlement.settlement.SettlementSavedData.get(server).findContaining(p).orElse(null);
        if(settlement==null)return false;
        for(var project:settlement.terrain().projects.values())for(int i:project.indices(p)) {
            var step=project.steps.get(i);
            if(step.fill() && (i<project.cursor || project.finished.get(i))
                    && com.hkin.exiledsettlement.terrain.TerrainService.satisfied(state,step.target())) {
                if(state.is(Blocks.COBBLESTONE) || state.is(Blocks.COBBLESTONE_STAIRS))r.oldFences.add(p.asLong());
                return true;
            }
        }
        return false;
    }
    public static BlockPos ground(Level level,BlockPos at,ProductionRegion region) {
        boolean tree=tree(level,at);long key=ProductionRegion.key(at.getX(),at.getZ());
        if(tree)region.naturalTrees.add(key);
        while(at.getY()>level.getMinBuildHeight()) {
            var state=level.getBlockState(at);
            if(!(state.getBlock() instanceof BushBlock || state.is(Blocks.SNOW) || state.getBlock() instanceof FenceBlock
                    || state.getBlock() instanceof FenceGateBlock || leaf(state) || tree && (state.is(BlockTags.LOGS) || state.isAir())))break;
            at=at.below();
        }
        return at;
    }
}
