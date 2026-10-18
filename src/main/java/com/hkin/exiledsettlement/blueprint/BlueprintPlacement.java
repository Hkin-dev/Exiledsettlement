package com.hkin.exiledsettlement.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public record BlueprintPlacement(
        BlockPos position,
        BlockState state,
        @Nullable Item material,
        BlueprintPlacementMode mode
) {
    public BlueprintPlacement(BlockPos position, BlockState state, @Nullable Item material) {
        this(position, state, material, BlueprintPlacementMode.STRUCTURE);
    }
}
