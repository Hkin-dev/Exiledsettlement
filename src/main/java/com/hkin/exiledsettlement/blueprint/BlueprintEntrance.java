package com.hkin.exiledsettlement.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** A player-visible, traversable entrance cell and its outward direction. */
public record BlueprintEntrance(BlockPos position, Direction outward) {
    public BlueprintEntrance {
        position = position.immutable();
        if (!outward.getAxis().isHorizontal()) {
            throw new IllegalArgumentException("Blueprint entrance must face horizontally");
        }
    }
}
