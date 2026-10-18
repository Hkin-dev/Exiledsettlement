package com.hkin.exiledsettlement.blueprint;

import com.hkin.exiledsettlement.building.BuildingType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Version 1: a nine-block square headhouse over a lined switchback and mining hall. */
public final class MineBlueprint {
    private MineBlueprint() {}

    public static BlockPos position(BlockPos anchor, Direction facing, int x, int y, int z) {
        Direction right = facing.getClockWise();
        return anchor.offset(right.getStepX()*x + facing.getStepX()*z, y,
                right.getStepZ()*x + facing.getStepZ()*z);
    }

    public static BuildingBlueprintTemplate create(BlockPos anchor, Direction facing) {
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        // A paid retaining shell, including solid backing around the stairwell.
        for(int x=-4;x<=4;x++) for(int z=-4;z<=4;z++) for(int y=-17;y<=-1;y++)
            cells.put(new BlockPos(x,y,z), Blocks.COBBLESTONE.defaultBlockState());
        for(int x=-3;x<=3;x++) for(int z=-3;z<=3;z++) for(int y=-10;y<=-8;y++)
            cells.put(new BlockPos(x,y,z), Blocks.AIR.defaultBlockState());
        for(int x=-3;x<=3;x++) for(int z=-3;z<=3;z++) for(int y=-16;y<=-14;y++)
            cells.put(new BlockPos(x,y,z), Blocks.AIR.defaultBlockState());
        // Two-wide stairs: enter on the west, cross the north landing, exit east.
        for(int i=0;i<5;i++) for(int x=-3;x<=-2;x++) {
            int z=2-i,y=-1-i;
            cells.put(new BlockPos(x,y,z), Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,facing));
            for(int h=1;h<=3;h++) cells.put(new BlockPos(x,y+h,z),Blocks.AIR.defaultBlockState());
        }
        for(int x=-3;x<=3;x++) {
            cells.put(new BlockPos(x,-6,-3),Blocks.STONE_BRICKS.defaultBlockState());
            for(int y=-5;y<=-3;y++) cells.put(new BlockPos(x,y,-3),Blocks.AIR.defaultBlockState());
        }
        for(int i=0;i<5;i++) for(int x=2;x<=3;x++) {
            int z=-2+i,y=-6-i;
            cells.put(new BlockPos(x,y,z), Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,facing.getOpposite()));
            for(int h=1;h<=3;h++) cells.put(new BlockPos(x,y+h,z),Blocks.AIR.defaultBlockState());
        }
        // A third, two-wide flight connects the mining hall to the bulk ore cellar.
        for(int i=0;i<6;i++) for(int x=-3;x<=-2;x++) {
            int z=2-i,y=-11-i;
            cells.put(new BlockPos(x,y,z),Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,facing));
            for(int h=1;h<=3;h++)cells.put(new BlockPos(x,y+h,z),Blocks.AIR.defaultBlockState());
        }
        // The headhouse has an open two-wide loading portal and a low gable roof.
        for(int x=-3;x<=3;x++) for(int z=-3;z<=3;z++) for(int y=0;y<=2;y++)
            cells.put(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
        for(int y=0;y<=2;y++) for(int i=-4;i<=4;i++) {
            for(int side:new int[]{-4,4}) {
                cells.put(new BlockPos(side,y,i),Blocks.SPRUCE_PLANKS.defaultBlockState());
                cells.put(new BlockPos(i,y,side),Blocks.SPRUCE_PLANKS.defaultBlockState());
            }
        }
        for(int x:new int[]{-4,4}) for(int z:new int[]{-4,4}) for(int y=0;y<=3;y++)
            cells.put(new BlockPos(x,y,z),Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
        for(int x=0;x<=1;x++) for(int y=0;y<=2;y++) cells.put(new BlockPos(x,y,4),Blocks.AIR.defaultBlockState());
        for(int z:new int[]{-1,1}) for(int x:new int[]{-4,4})
            cells.put(new BlockPos(x,1,z),Blocks.GLASS_PANE.defaultBlockState());
        for(int x=-4;x<=4;x++) for(int z=-4;z<=4;z++) {
            int roof=3+(4-Math.abs(x));
            // Fill the inaccessible roof wedge; no dark hidden attic.
            for(int y=3;y<roof;y++) cells.put(new BlockPos(x,y,z),Blocks.SPRUCE_PLANKS.defaultBlockState());
            cells.put(new BlockPos(x,roof,z), x==0 ? Blocks.SPRUCE_SLAB.defaultBlockState()
                    : Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,
                        x<0?facing.getClockWise():facing.getCounterClockWise()));
        }
        // Surface equipment and underground sorting bench, kept off the stairs.
        cells.put(new BlockPos(3,0,1),Blocks.BARREL.defaultBlockState());
        cells.put(new BlockPos(3,1,1),Blocks.LANTERN.defaultBlockState());
        cells.put(new BlockPos(3,0,2),Blocks.GRINDSTONE.defaultBlockState());
        cells.put(new BlockPos(1,-10,-3),Blocks.CRAFTING_TABLE.defaultBlockState());
        for(int z=-1;z<=2;z++)for(int y=-16;y<=-15;y++)
            cells.put(new BlockPos(3,y,z),Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING,facing.getCounterClockWise()));
        for(int z:new int[]{-3,3}) {
            cells.put(new BlockPos(0,-16,z),Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
            cells.put(new BlockPos(0,-15,z),Blocks.LANTERN.defaultBlockState());
        }
        cells.put(new BlockPos(0,-10,-3),Blocks.COBBLESTONE_WALL.defaultBlockState());
        cells.put(new BlockPos(0,-9,-3),Blocks.LANTERN.defaultBlockState());
        // A wall torch illuminates the intermediate landing without narrowing it.
        cells.put(new BlockPos(0,-4,-3),Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING,facing));
        List<BlueprintPlacement> placements=new ArrayList<>();
        cells.forEach((p,state)->placements.add(new BlueprintPlacement(position(anchor,facing,p.getX(),p.getY(),p.getZ()),state,
                state.isAir() || state.getBlock().asItem()==Items.AIR ? null : state.getBlock().asItem(),
                state.isAir()?BlueprintPlacementMode.CLEARANCE:BlueprintPlacementMode.STRUCTURE)));
        placements.sort(Comparator.comparingInt((BlueprintPlacement p)->p.state().isAir()?0:1)
                .thenComparingInt(p->p.state().isAir()?-p.position().getY():p.position().getY()));
        return new BuildingBlueprintTemplate(BuildingType.MINE,anchor,facing,9,9,25,
                anchor.offset(-4,-17,-4),anchor.offset(4,7,4),0,placements,
                List.of(new BlueprintEntrance(position(anchor,facing,0,0,4),facing),
                        new BlueprintEntrance(position(anchor,facing,1,0,4),facing)));
    }

    public static List<BlockPos> workPositions(BlockPos anchor,Direction facing) {
        List<BlockPos> positions=new ArrayList<>();
        for(int z=-2;z<=0;z++)for(int x=-1;x<=1;x++)positions.add(position(anchor,facing,x,-10,z));
        positions.add(position(anchor,facing,0,-10,1));
        return List.copyOf(positions);
    }
    public static BlockPos storagePosition(BlockPos anchor,Direction facing) {
        return position(anchor,facing,0,-16,1);
    }
}
