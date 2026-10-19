package com.hkin.exiledsettlement.terrain;

import net.minecraft.core.*;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;

/** Read-only ground geometry. Recognition never authorizes clearing or changing a block. */
public final class TerrainSurface {
    private TerrainSurface() {}
    public static boolean ground(LevelReader level, BlockPos at) {
        if (!level.hasChunkAt(at) || level.isOutsideBuildHeight(at)) return false;
        return ground(level,at,level.getBlockState(at));
    }
    public static boolean ground(LevelReader level, BlockPos at, net.minecraft.world.level.block.state.BlockState state) {
        if (!level.hasChunkAt(at) || level.isOutsideBuildHeight(at)) return false;
        if (state.hasBlockEntity() || !state.getFluidState().isEmpty()
                || PitFillSurface.vegetation(state) || state.is(net.minecraft.tags.BlockTags.LEAVES)) return false;
        var shape = state.getCollisionShape(level, at);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y)-shape.min(Direction.Axis.Y)>=0.5
                && (Block.isFaceFull(shape, Direction.DOWN) || Block.isFaceFull(shape, Direction.UP));
    }
}
