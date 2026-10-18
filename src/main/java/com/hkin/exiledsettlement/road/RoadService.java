package com.hkin.exiledsettlement.road;

import com.hkin.exiledsettlement.settlement.OperationResult;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;

import java.util.ArrayList;
import java.util.List;

public final class RoadService {
    private RoadService() {
    }

    public static OperationResult<Integer> placeRoad(
            ServerLevel level, SettlementRecord settlement, ServerPlayer player, BlockPos first, BlockPos second
    ) {
        return placeRoad(level, settlement, player, first, second, RoadTier.LEVEL_1);
    }

    public static OperationResult<Integer> placeRoad(
            ServerLevel level, SettlementRecord settlement, ServerPlayer player,
            BlockPos first, BlockPos second, RoadTier tier
    ) {
        List<BlockPos> flatArea = rectangle(first, second);
        List<BlockPos> surfaces = new ArrayList<>(flatArea.size());
        int expectedY = Math.round((first.getY() + second.getY()) / 2.0F);
        for (BlockPos candidate : flatArea) {
            BlockPos surface = findSurface(level, candidate.getX(), candidate.getZ(), expectedY);
            if (surface == null || !settlement.contains(surface) || !settlement.contains(surface.above())) {
                return OperationResult.failure("ROAD_INVALID_TERRAIN");
            }
            surfaces.add(surface);
        }

        RoadSpaceData roadData = RoadSpaceData.get(level);
        List<BlockSnapshot> changedBlocks = new ArrayList<>();
        for (BlockPos surface : surfaces) {
            BlockState current = level.getBlockState(surface);
            if (!shouldConvert(current, tier, roadData.levelAt(surface))) {
                continue;
            }
            BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, surface, Block.UPDATE_CLIENTS);
            if (!level.setBlock(surface, tier.surfaceBlock().defaultBlockState(), Block.UPDATE_ALL)
                    || EventHooks.onBlockPlace(player, snapshot, Direction.UP)) {
                snapshot.restore(Block.UPDATE_ALL);
                for (int i = changedBlocks.size() - 1; i >= 0; i--) {
                    changedBlocks.get(i).restore(Block.UPDATE_ALL);
                }
                return OperationResult.failure("PROTECTED_AREA");
            }
            changedBlocks.add(snapshot);
        }
        int added = roadData.addAll(surfaces, tier);
        roadData.pinManual(surfaces);
        return OperationResult.success(added);
    }

    public static List<BlockPos> rectangle(BlockPos first, BlockPos second) {
        int minX = Math.min(first.getX(), second.getX());
        int maxX = Math.max(first.getX(), second.getX());
        int minZ = Math.min(first.getZ(), second.getZ());
        int maxZ = Math.max(first.getZ(), second.getZ());
        List<BlockPos> positions = new ArrayList<>((maxX - minX + 1) * (maxZ - minZ + 1));
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) positions.add(new BlockPos(x, first.getY(), z));
        }
        return positions;
    }

    public static List<BlockPos> line(BlockPos first, BlockPos second) {
        List<BlockPos> positions = new ArrayList<>();
        int x0 = first.getX();
        int z0 = first.getZ();
        int x1 = second.getX();
        int z1 = second.getZ();
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int error = dx - dz;
        while (true) {
            positions.add(new BlockPos(x0, first.getY(), z0));
            if (x0 == x1 && z0 == z1) break;
            int doubled = error * 2;
            if (doubled > -dz) {
                error -= dz;
                x0 += sx;
            }
            if (doubled < dx) {
                error += dx;
                z0 += sz;
            }
        }
        return positions;
    }

    public static void tickLevel(ServerLevel level) { ManualRoadPlacementService.tickLevel(level); }

    static BlockPos findSurface(ServerLevel level, int x, int z, int expectedY) {
        for (int y = expectedY + 4; y >= expectedY - 6; y--) {
            BlockPos candidate = new BlockPos(x, y, z);
            if (level.isOutsideBuildHeight(candidate) || !level.hasChunkAt(candidate)) continue;
            BlockState state = level.getBlockState(candidate);
            BlockState above = level.getBlockState(candidate.above());
            if (!state.isAir() && state.getFluidState().getType() != Fluids.WATER
                    && (state.isFaceSturdy(level, candidate, Direction.UP) || isRoadSurface(state))
                    && above.getCollisionShape(level, candidate.above()).isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    static boolean shouldConvert(BlockState state, RoadTier tier, int currentRoadLevel) {
        if (currentRoadLevel > tier.level()) return false;
        if (state.is(tier.surfaceBlock())) return false;
        if (currentRoadLevel > 0) return true;
        boolean natural = state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.PODZOL)
                || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.MYCELIUM);
        return switch (tier) {
            case LEVEL_1 -> natural;
            case LEVEL_2 -> natural || state.is(Blocks.DIRT_PATH);
            case LEVEL_3 -> natural || state.is(Blocks.DIRT_PATH) || state.is(Blocks.GRAVEL);
        };
    }

    private static boolean isRoadSurface(BlockState state) {
        return state.is(Blocks.DIRT_PATH) || state.is(Blocks.GRAVEL) || state.is(Blocks.STONE_BRICKS);
    }
}
