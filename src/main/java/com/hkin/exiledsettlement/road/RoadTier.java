package com.hkin.exiledsettlement.road;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public enum RoadTier {
    LEVEL_1(1, 0.10D, Blocks.DIRT_PATH),
    LEVEL_2(2, 0.15D, Blocks.GRAVEL),
    LEVEL_3(3, 0.20D, Blocks.STONE_BRICKS);

    private final int level;
    private final double speedBonus;
    private final Block surfaceBlock;

    RoadTier(int level, double speedBonus, Block surfaceBlock) {
        this.level = level;
        this.speedBonus = speedBonus;
        this.surfaceBlock = surfaceBlock;
    }

    public int level() { return level; }
    public double speedBonus() { return speedBonus; }
    public Block surfaceBlock() { return surfaceBlock; }

    public static RoadTier fromLevel(int value) {
        if (value >= 3) return LEVEL_3;
        if (value == 2) return LEVEL_2;
        return LEVEL_1;
    }
}
