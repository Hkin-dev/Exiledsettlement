package com.hkin.exiledsettlement.blueprint;

import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.citizen.ConstructionAccessPlanService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Set;

/** Persistent, resident-driven cleanup for a construction blueprint cancelled by a player. */
public final class BlueprintCancellationService {
    private BlueprintCancellationService() {
    }

    public static BlueprintPlacement nextCleanup(
            ServerLevel level, BlueprintRecord blueprint, Set<BlockPos> claimedPositions
    ) {
        if (blueprint.cancellationQueued()) {
            var template = BlueprintTemplates.create(blueprint);
            List<BlueprintPlacement> placements = template.placements();
            for (int index = placements.size() - 1; index >= 0; index--) {
                BlueprintPlacement placement = placements.get(index);
                if(blueprint.terrainProject()!=null || blueprint.region()!=null || retainedFarmSoil(blueprint,placement)) continue;
                if (!placement.mode().isFinalStructure()
                        || BlueprintPlacementGroups.claimed(template, placement, claimedPositions)
                        || supportsResident(level, placement.position())) continue;
                if (BlueprintService.matchesPlacedState(
                        level.getBlockState(placement.position()), placement.state())) return placement;
            }
        }
        for (var entry : blueprint.temporaryFootings().entrySet().stream()
                // Remove a scaffold chain from its newest unsupported leaf back
                // toward the ground. A parent remains until every child above it
                // has gone, so cleanup cannot pull the route out from under a
                // resident who is descending.
                .filter(value -> !blueprint.hasTemporaryFootingChild(value.getKey()))
                .sorted(java.util.Comparator
                        .<java.util.Map.Entry<BlockPos, Item>>comparingInt(
                                value -> blueprint.temporaryFootingOrder(value.getKey()))
                        .reversed()
                        .thenComparing(value -> value.getKey().getY(), java.util.Comparator.reverseOrder())
                        .thenComparing(value -> value.getKey().asLong()))
                .toList()) {
            BlockPos position = entry.getKey();
            Item item = entry.getValue();
            Block block = Block.byItem(item);
            if (!level.getBlockState(position).is(block)) {
                blueprint.removeTemporaryFooting(position);
                continue;
            }
            if (!claimedPositions.contains(position) && !supportsResident(level, position)
                    && ConstructionAccessPlanService.preservesResidentEgressAfterRemoval(
                    level, blueprint, position)) {
                return new BlueprintPlacement(position, block.defaultBlockState(), item,
                        BlueprintPlacementMode.STRUCTURE);
            }
        }
        return null;
    }

    public static boolean removePlacedBlock(
            ServerLevel level, BlueprintRecord blueprint, BlockPos position, Entity worker
    ) {
        var settlement=com.hkin.exiledsettlement.settlement.SettlementSavedData.get(level).findContaining(position).orElse(null);
        if(settlement!=null && com.hkin.exiledsettlement.terrain.TerrainService.protectedPublicColumn(settlement,blueprint.terrainProject(),position))return false;
        Item footingItem = blueprint.temporaryFootingItem(position);
        if (footingItem != net.minecraft.world.item.Items.AIR) {
            Block block = Block.byItem(footingItem);
            if (!level.getBlockState(position).is(block)) {
                blueprint.removeTemporaryFooting(position);
                return true;
            }
            if (!com.hkin.exiledsettlement.citizen.ResidentBlockDrops.destroy(level, position, worker)) return false;
            blueprint.removeTemporaryFooting(position);
            return true;
        }
        BlueprintPlacement placement = BlueprintTemplates.create(blueprint).placements().stream()
                .filter(candidate -> candidate.mode().isFinalStructure())
                .filter(candidate -> !retainedFarmSoil(blueprint,candidate))
                .filter(candidate -> candidate.position().equals(position))
                .filter(candidate -> BlueprintService.matchesPlacedState(
                        level.getBlockState(position), candidate.state()))
                .findFirst().orElse(null);
        if (placement == null) return false;
        return BlueprintPlacementGroups.isPaired(placement.state())
                ? BlueprintPlacementGroups.remove(level, BlueprintTemplates.create(blueprint), placement, worker)
                : com.hkin.exiledsettlement.citizen.ResidentBlockDrops.destroy(level, position, worker);
    }

    private static boolean supportsResident(ServerLevel level, BlockPos position) {
        AABB standingSpace = new AABB(position.getX(), position.getY() + 0.75D, position.getZ(),
                position.getX() + 1.0D, position.getY() + 3.25D, position.getZ() + 1.0D);
        return !level.getEntitiesOfClass(SettlementCitizenEntity.class, standingSpace,
                resident -> resident.isAlive()).isEmpty();
    }
    private static boolean retainedFarmSoil(BlueprintRecord b, BlueprintPlacement p) {
        return FarmEarthworkService.applies(b) && p.position().getY()<=b.anchor().getY()-1
                && (FarmEarthworkPlan.soil(p.state()) || p.mode()==BlueprintPlacementMode.FARM_TILL);
    }
}
