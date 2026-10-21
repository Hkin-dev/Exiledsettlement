package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceGateBlock;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import javax.annotation.Nullable;

/** Shared physical and permission checks for registered enclosure gates. */
public final class FenceGateAccessService {
    private FenceGateAccessService() { }

    public static boolean occupied(ServerLevel level, BlockPos at) {
        if (!level.hasChunkAt(at)) return false;
        var state = level.getBlockState(at);
        if (!(state.getBlock() instanceof FenceGateBlock)) return false;
        for (var local : state.setValue(FenceGateBlock.OPEN, false).getCollisionShape(level, at).toAabbs()) {
            var panel = local.move(at);
            if (!level.getEntitiesOfClass(LivingEntity.class, panel,
                    body -> body.isAlive() && body.getBoundingBox().intersects(panel)).isEmpty()) return true;
        }
        return false;
    }

    public static boolean change(ServerLevel level, SettlementRecord settlement, BlockPos at,
                                 boolean requestedOpen, @Nullable SettlementCitizenEntity actor) {
        if (!level.hasChunkAt(at) || !settlement.contains(at)) return false;
        var state = level.getBlockState(at);
        if (!(state.getBlock() instanceof FenceGateBlock)) return false;
        // Redstone owns powered gates. Occupancy only holds an already open gate.
        boolean open = requestedOpen || state.getValue(FenceGateBlock.POWERED)
                || level.hasNeighborSignal(at) || state.getValue(FenceGateBlock.OPEN) && occupied(level, at);
        if (state.getValue(FenceGateBlock.OPEN) == open) return open;
        var target = state.setValue(FenceGateBlock.OPEN, open);
        var snapshot = BlockSnapshot.create(level.dimension(), level, at, Block.UPDATE_CLIENTS);
        if (!level.setBlock(at, target, Block.UPDATE_ALL)
                || EventHooks.onBlockPlace(actor, snapshot, Direction.UP)
                || !level.getBlockState(at).equals(target)) {
            snapshot.restore(Block.UPDATE_ALL);
        }
        return level.getBlockState(at).getBlock() instanceof FenceGateBlock
                && level.getBlockState(at).getValue(FenceGateBlock.OPEN);
    }
}
