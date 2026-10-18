package com.hkin.exiledsettlement.building;

import com.hkin.exiledsettlement.blueprint.BlueprintPlacement;
import com.hkin.exiledsettlement.blueprint.BlueprintPlacementMode;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.blueprint.BlueprintPlacementGroups;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class BuildingDemolitionService {
    private BuildingDemolitionService() {
    }

    @Nullable
    public static BlockPos nextBlock(ServerLevel level, BuildingRecord building) {
        return nextBlock(level, building, Set.of(), building.position());
    }

    @Nullable
    public static BlockPos nextBlock(
            ServerLevel level, BuildingRecord building, Set<BlockPos> excludedPositions, BlockPos workerPosition
    ) {
        if (!building.active() || !building.demolitionQueued() || building.type() == BuildingType.WAGON) return null;
        return remainingBlocks(level, building, excludedPositions, workerPosition).stream()
                .findFirst().orElse(null);
    }

    public static List<BlockPos> remainingBlocks(
            ServerLevel level, BuildingRecord building, Set<BlockPos> excludedPositions, BlockPos workerPosition
    ) {
        if (!building.active() || !building.demolitionQueued() || building.type() == BuildingType.WAGON) {
            return List.of();
        }
        var template = BlueprintTemplates.createForBuilding(building);
        return template.placements().stream()
                .filter(placement -> placement.mode() != BlueprintPlacementMode.CLEARANCE)
                .filter(placement -> matchesTemplateBlock(level, placement))
                .filter(placement -> !BlueprintPlacementGroups.claimed(template, placement, excludedPositions))
                .map(BlueprintPlacement::position)
                .sorted(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed()
                        .thenComparingDouble(position -> position.distSqr(workerPosition))
                        .thenComparingLong(BlockPos::asLong))
                .toList();
    }

    public static boolean isRemovable(ServerLevel level, BuildingRecord building, BlockPos position) {
        if (!building.active() || !building.demolitionQueued() || building.type() == BuildingType.WAGON) return false;
        return BlueprintTemplates.createForBuilding(building).placements().stream()
                .filter(placement -> placement.mode() != BlueprintPlacementMode.CLEARANCE)
                .filter(placement -> placement.position().equals(position))
                .anyMatch(placement -> matchesTemplateBlock(level, placement));
    }

    public static boolean removeAt(
            ServerLevel level, BuildingRecord building, BlockPos position,
            net.minecraft.world.entity.Entity worker
    ) {
        return com.hkin.exiledsettlement.citizen.ResidentBlockDrops.run(level, worker, () -> removeAtDirect(level, building, position, worker), Boolean.TRUE::equals);
    }

    private static boolean removeAtDirect(
            ServerLevel level, BuildingRecord building, BlockPos position,
            net.minecraft.world.entity.Entity worker
    ) {
        if (!isRemovable(level, building, position)) return false;
        var template = BlueprintTemplates.createForBuilding(building);
        var placement = template.placementAt(position);
        if (BlueprintPlacementGroups.isPaired(placement.state())) {
            return BlueprintPlacementGroups.remove(level, template, placement, worker);
        }
        if (worker instanceof net.minecraft.world.entity.LivingEntity living
                && !net.neoforged.neoforge.common.CommonHooks.canEntityDestroy(level, position, living)) return false;
        var current = level.getBlockState(position);
        var snapshot = net.neoforged.neoforge.common.util.BlockSnapshot.create(level.dimension(), level, position,
                net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
        var drops = net.minecraft.world.level.block.Block.getDrops(current, level, position,
                level.getBlockEntity(position), worker, net.minecraft.world.item.ItemStack.EMPTY);
        if (!level.setBlock(position, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE)
                || net.neoforged.neoforge.event.EventHooks.onBlockPlace(worker, snapshot, net.minecraft.core.Direction.UP)) {
            snapshot.restore(net.minecraft.world.level.block.Block.UPDATE_ALL); return false;
        }
        level.sendBlockUpdated(position, current, level.getBlockState(position), net.minecraft.world.level.block.Block.UPDATE_ALL);
        level.updateNeighborsAt(position, current.getBlock());
        for (var drop : drops) net.minecraft.world.level.block.Block.popResource(level, position, drop);
        return true;
    }

    public static boolean areaLoaded(ServerLevel level, BuildingRecord building) {
        int minChunkX = building.minCorner().getX() >> 4;
        int maxChunkX = building.maxCorner().getX() >> 4;
        int minChunkZ = building.minCorner().getZ() >> 4;
        int maxChunkZ = building.maxCorner().getZ() >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!level.hasChunkAt(new BlockPos(chunkX << 4, building.position().getY(), chunkZ << 4))) return false;
            }
        }
        return true;
    }

    public static boolean removeNext(ServerLevel level, BuildingRecord building,
                                     net.minecraft.world.entity.Entity worker) {
        BlockPos target = nextBlock(level, building);
        return target != null && removeAt(level, building, target, worker);
    }

    private static boolean matchesTemplateBlock(ServerLevel level, BlueprintPlacement placement) {
        if (placement.state().is(com.hkin.exiledsettlement.registry.ModBlocks.SETTLEMENT_CORE.get())) return false;
        if (!level.hasChunkAt(placement.position())) return false;
        var current = level.getBlockState(placement.position());
        return !current.isAir() && current.getBlock() == placement.state().getBlock();
    }
}
