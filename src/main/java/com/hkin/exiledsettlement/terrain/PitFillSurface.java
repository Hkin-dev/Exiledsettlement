package com.hkin.exiledsettlement.terrain;

import net.minecraft.core.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;

/** Selection filters shared by planning tools; clearing authority remains separate. */
public final class PitFillSurface {
    private PitFillSurface() {}
    /** Small wild vegetation is clearable; cultivated plants and saplings are not. */
    public static boolean vegetation(BlockState s) {
        var b=s.getBlock();
        return b instanceof BushBlock && !(b instanceof CropBlock) && !(b instanceof StemBlock)
                && !(b instanceof AttachedStemBlock) && !(b instanceof SaplingBlock)
                && !(b instanceof SweetBerryBushBlock) && !(b instanceof PitcherCropBlock);
    }
    public static boolean ground(Level level,BlockPos at) {
        return TerrainSurface.ground(level,at);
    }
    /** Selection sees through attachments, but this never grants permission to remove them. */
    public static boolean attachment(BlockState s,BlockGetter level,BlockPos at) {
        if(s.hasBlockEntity())return false;
        var shape=s.getCollisionShape(level,at);
        return shape.isEmpty() || shape.max(Direction.Axis.Y)<=0.5 && shape.max(Direction.Axis.Y)-shape.min(Direction.Axis.Y)<0.5;
    }
    public static BlockHitResult pick(Level level,Vec3 start,Vec3 end,Entity camera) {
        return level.clip(new ClipContext(start,end,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,camera) {
            @Override public VoxelShape getBlockShape(BlockState state,BlockGetter world,BlockPos at) {
                return vegetation(state)||attachment(state,world,at)?Shapes.empty():super.getBlockShape(state,world,at);
            }
        });
    }
    public static int top(BlockPos ground) { return ground.getY()+1; }
    public static BlockPos corner(Vec3 start,Vec3 end,BlockPos first) {
        var at=com.hkin.exiledsettlement.blueprint.AreaSelectionPlane.corner(start,end,top(first));
        return at==null?null:at.atY(first.getY());
    }
}
