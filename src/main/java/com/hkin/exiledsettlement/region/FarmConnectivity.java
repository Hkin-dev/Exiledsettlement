package com.hkin.exiledsettlement.region;

import com.hkin.exiledsettlement.blueprint.FarmEarthworkPlan;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import java.util.*;

/** Actual local access, distinct from planned soil and construction completion. */
public final class FarmConnectivity {
    private FarmConnectivity() { }
    public static boolean standing(ServerLevel level, BlockPos ground) {
        return level.hasChunkAt(ground) && (FarmEarthworkPlan.soil(level.getBlockState(ground)) || level.getBlockState(ground).is(Blocks.DIRT_PATH) || FarmEarthworkPlan.support(level,ground) || level.getBlockState(ground).getBlock() instanceof StairBlock || level.getBlockState(ground).getBlock() instanceof SlabBlock)
                && clear(level,ground.above()) && clear(level,ground.above(2));
    }
    private static boolean clear(ServerLevel level,BlockPos at) {
        var state=level.getBlockState(at);
        return state.getFluidState().isEmpty() && (state.getCollisionShape(level,at).isEmpty()
                || state.getBlock() instanceof FenceGateBlock);
    }
    public static Set<Long> accessible(ServerLevel level,ProductionRegion r) {
        var seen=new HashSet<Long>();var queue=new ArrayDeque<ProductionRegion.Cell>();
        for(var c:r.cells.values())if(c.use()==ProductionRegion.Use.GATE) {
            var p=c.ground();var gate=level.getBlockState(p.above());
            if(!(gate.getBlock() instanceof FenceGateBlock) || !standing(level,p))continue;
            var direction=r.gateFacing(p);boolean exit=false;
            for(int dy:new int[]{0,-1,1})if(standing(level,p.relative(direction).offset(0,dy,0))){exit=true;break;}
            if(exit && seen.add(ProductionRegion.key(p.getX(),p.getZ())))queue.add(c);
        }
        while(!queue.isEmpty()) {
            var c=queue.removeFirst();
            for(var d:Direction.Plane.HORIZONTAL) {
                var n=r.cell(c.ground().relative(d));
                if(n==null || n.use()==ProductionRegion.Use.BOUNDARY || Math.abs(n.ground().getY()-c.ground().getY())>1
                        || !standing(level,n.ground()))continue;
                long key=ProductionRegion.key(n.ground().getX(),n.ground().getZ());
                if(seen.add(key))queue.add(n);
            }
        }
        return seen;
    }
}
