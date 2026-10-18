package com.hkin.exiledsettlement.settlement;

import com.hkin.exiledsettlement.registry.ModBlocks;
import com.hkin.exiledsettlement.block.SettlementBuildingBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

import java.util.List;

public final class SettlementMigrationService {
    private SettlementMigrationService() {
    }

    public static void tickLevel(ServerLevel level) {
        SettlementSavedData data = SettlementSavedData.get(level);
        boolean changed = false;
        for (SettlementRecord settlement : data.settlements()) {
            for (BlockPos position : List.copyOf(settlement.retiredBlockPositions())) {
                if (!level.hasChunkAt(position)) {
                    continue;
                }
                if (level.getBlockState(position).is(ModBlocks.LEGACY_STOCKPILE_MARKER.get())
                        || (level.getBlockState(position).getBlock() instanceof SettlementBuildingBlock
                        && !level.getBlockState(position).is(ModBlocks.EXILE_WAGON.get()))) {
                    level.setBlock(position, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
                changed |= settlement.removeRetiredBlockPosition(position);
            }
        }
        if (changed) {
            data.touch();
        }
    }
}
