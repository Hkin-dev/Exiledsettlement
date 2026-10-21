package com.hkin.exiledsettlement.blueprint;

import com.hkin.exiledsettlement.building.BuildingType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Approved layered mine. Old mine geometry is deliberately not retained. */
public final class MineBlueprint {
    private MineBlueprint() {}
    public static BlockPos position(BlockPos anchor, Direction facing, int x, int y, int z) {
        Direction right = facing.getCounterClockWise();
        return anchor.offset(right.getStepX()*x + facing.getStepX()*z, y,
                right.getStepZ()*x + facing.getStepZ()*z);
    }
    public static BlockPos local(BlockPos anchor, Direction facing, BlockPos world) {
        var d=world.subtract(anchor);var right=facing.getCounterClockWise();
        return new BlockPos(d.getX()*right.getStepX()+d.getZ()*right.getStepZ(),d.getY(),
                d.getX()*facing.getStepX()+d.getZ()*facing.getStepZ());
    }
    public static Rotation rotation(Direction facing) {
        return switch(facing){case WEST -> Rotation.CLOCKWISE_90;case NORTH -> Rotation.CLOCKWISE_180;
            case EAST -> Rotation.COUNTERCLOCKWISE_90;default -> Rotation.NONE;};
    }
    public static BuildingBlueprintTemplate create(BlockPos anchor, Direction facing) {
        var placements=new ArrayList<BlueprintPlacement>();
        for(var cell:com.hkin.exiledsettlement.building.LayeredMinePlan.initial()) {
            var p=cell.position();var state=cell.state().rotate(rotation(facing));
            placements.add(new BlueprintPlacement(position(anchor,facing,p.getX(),p.getY(),p.getZ()),state,
                    state.isAir() || state.getBlock().asItem()==Items.AIR?null:state.getBlock().asItem(),
                    state.isAir()?BlueprintPlacementMode.CLEARANCE:BlueprintPlacementMode.STRUCTURE));
        }
        placements.sort(Comparator.comparingInt((BlueprintPlacement p)->p.state().isAir()?0:1)
                .thenComparingInt(p->p.state().isAir()?-p.position().getY():p.position().getY()));
        int minX=Integer.MAX_VALUE,minY=minX,minZ=minX,maxX=Integer.MIN_VALUE,maxY=maxX,maxZ=maxX;
        for(var p:placements){var q=p.position();minX=Math.min(minX,q.getX());minY=Math.min(minY,q.getY());minZ=Math.min(minZ,q.getZ());
            maxX=Math.max(maxX,q.getX());maxY=Math.max(maxY,q.getY());maxZ=Math.max(maxZ,q.getZ());}
        return new BuildingBlueprintTemplate(BuildingType.MINE,anchor,facing,maxX-minX+1,maxZ-minZ+1,maxY-minY+1,
                new BlockPos(minX,minY,minZ),new BlockPos(maxX,maxY,maxZ),0,placements,
                List.of(new BlueprintEntrance(position(anchor,facing,-3,0,5),facing),
                        new BlueprintEntrance(position(anchor,facing,-2,0,5),facing)));
    }
    public static List<BlockPos> workPositions(BlockPos anchor,Direction facing) {
        var positions=new ArrayList<BlockPos>();
        for(int z:new int[]{5,6,10,11})for(int x:new int[]{-3,2})positions.add(position(anchor,facing,x,-6,z));
        positions.add(position(anchor,facing,-1,-6,6));positions.add(position(anchor,facing,0,-6,6));
        return List.copyOf(positions);
    }
    public static BlockPos storagePosition(BlockPos anchor,Direction facing) {
        return position(anchor,facing,6,-6,9);
    }
    public static boolean insideStorage(BlockPos anchor,Direction facing,BlockPos at) {
        var p=local(anchor,facing,at);
        return p.getY()==-6 && p.getX()>=4 && p.getX()<=7 && p.getZ()>=8 && p.getZ()<=11;
    }
    public static List<BlockPos> storageStations(BlockPos anchor,Direction facing) {
        var out=new ArrayList<BlockPos>();
        for(int x=4;x<=7;x++)for(int z=8;z<=11;z++)out.add(position(anchor,facing,x,-6,z));
        return List.copyOf(out);
    }
}
