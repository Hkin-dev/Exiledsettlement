package com.hkin.exiledsettlement.road;

import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/** Actual collision-surface heights, including stair facing/shape and slab half. No world edits. */
public final class RoadSurfaceProfile {
    private RoadSurfaceProfile(){ }
    public static double edgeHeight(ServerLevel level,BlockPos p,BlockState state,Direction side){
        double x=.5+side.getStepX()*.49,z=.5+side.getStepZ()*.49,top=0;
        for(var box:state.getCollisionShape(level,p).toAabbs())
            if(x>=box.minX && x<=box.maxX && z>=box.minZ && z<=box.maxZ)top=Math.max(top,box.maxY);
        return p.getY()+top;
    }
    public static int landingGround(ServerLevel level,BlockPos p,Direction side){
        return (int)Math.floor(edgeHeight(level,p,level.getBlockState(p),side)+.125)-1;
    }
    public static boolean equivalent(ServerLevel level,BlockPos p,BlockState actual,BlockState expected){
        for(var d:Direction.Plane.HORIZONTAL)
            if(Math.abs(edgeHeight(level,p,actual,d)-edgeHeight(level,p,expected,d))>.0626)return false;
        return actual.getCollisionShape(level,p).toAabbs().equals(expected.getCollisionShape(level,p).toAabbs());
    }
    public static boolean usable(ServerLevel level,BlockPos p){
        var state=level.getBlockState(p);
        if(state.hasBlockEntity() || !state.getFluidState().isEmpty() || state.isAir())return false;
        // Vanilla paths have a real 15/16-high walking surface but their upper
        // face is not a full sturdy cube. Keep its measured edge height below.
        if(state.is(Blocks.DIRT_PATH))return true;
        if(state.getBlock() instanceof StairBlock || state.getBlock() instanceof SlabBlock)return true;
        return state.isFaceSturdy(level,p,Direction.UP) && state.getCollisionShape(level,p).max(Direction.Axis.Y)<=1;
    }
    public static boolean connected(ServerLevel level,AutoRoadState.Route route,BlockPos p){
        for(var d:Direction.Plane.HORIZONTAL){var n=p.relative(d);
            var q=route.cellInColumn(n);
            if(q==null)continue;
            if(!level.hasChunkAt(q) || !usable(level,q))return false;
            if(!joins(level,p,level.getBlockState(p),q,level.getBlockState(q),d))return false;
        }
        return true;
    }
    public static boolean joins(ServerLevel level,BlockPos p,BlockState a,BlockPos q,BlockState b,Direction d){
        return Math.abs(edgeHeight(level,p,a,d)-edgeHeight(level,q,b,d.getOpposite()))<=.5626;
    }
    /** Aim into actual solid faces: a stair's block center can be empty. */
    public static boolean visibleSurface(ServerLevel level,net.minecraft.world.entity.LivingEntity worker,BlockPos p){
        if(!com.hkin.exiledsettlement.citizen.CitizenInteractionRange.contains(worker,p))return false;
        for(var box:level.getBlockState(p).getCollisionShape(level,p).toAabbs()){
            var point=new net.minecraft.world.phys.Vec3(p.getX()+(box.minX+box.maxX)*.5,
                    p.getY()+box.maxY-.01,p.getZ()+(box.minZ+box.maxZ)*.5);
            var hit=level.clip(new net.minecraft.world.level.ClipContext(worker.getEyePosition(),point,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.ANY,worker));
            if(hit.getType()==net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(p))return true;
        }
        return false;
    }
}
