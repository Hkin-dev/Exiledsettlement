package com.hkin.exiledsettlement.building;

import com.hkin.exiledsettlement.blueprint.BlueprintPlacement;
import com.hkin.exiledsettlement.blueprint.BlueprintPlacementMode;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.blueprint.BlueprintPlacementGroups;
import com.hkin.exiledsettlement.blueprint.BlueprintService;
import com.hkin.exiledsettlement.road.RoadSpaceData;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class BuildingRepairService {
    private BuildingRepairService() {
    }

    public static void recordExplosionDamage(ServerLevel level, List<BlockPos> affectedBlocks) {
        SettlementSavedData data = SettlementSavedData.get(level);
        RoadSpaceData roads = RoadSpaceData.get(level);
        boolean changed = false;
        for (BlockPos position : affectedBlocks) {
            SettlementRecord settlement = data.findContaining(position).orElse(null);
            if (settlement == null) continue;
            BuildingRecord building = settlement.buildings().stream()
                    .filter(BuildingRecord::active)
                    .filter(candidate -> candidate.contains(position))
                    .filter(candidate -> expectedBuildingState(candidate, position) != null)
                    .findFirst().orElse(null);
            if (building != null) {
                changed |= building.markDamaged(position);
            } else if (roads.contains(position)) {
                changed |= settlement.markRoadDamaged(position);
            }
        }
        if (changed) data.touch();
    }

    @Nullable
    public static BlockState expectedState(
            ServerLevel level, SettlementRecord settlement, @Nullable BuildingRecord building, BlockPos position
    ) {
        if (building != null) return expectedBuildingState(building, position);
        return settlement.roadRepairPositions().contains(position) && RoadSpaceData.get(level).contains(position)
                ? RoadSpaceData.get(level).tierAt(position).surfaceBlock().defaultBlockState() : null;
    }

    public static boolean clearRepair(
            SettlementRecord settlement, @Nullable BuildingRecord building, BlockPos position
    ) {
        if (building == null) return settlement.clearRoadRepair(position);
        boolean changed = building.clearRepairPosition(position);
        var template = BlueprintTemplates.createForBuilding(building);
        var placement = template.placementAt(position);
        if (placement != null && BlueprintPlacementGroups.isPaired(placement.state())) {
            for (var part : BlueprintPlacementGroups.parts(template, placement)) {
                changed |= building.clearRepairPosition(part.position());
            }
        }
        return changed;
    }

    public static boolean satisfied(ServerLevel level, BuildingRecord building, BlockPos position) {
        var template = BlueprintTemplates.createForBuilding(building);
        var placement = template.placementAt(position);
        if (placement == null) return true;
        return BlueprintPlacementGroups.isPaired(placement.state())
                ? BlueprintPlacementGroups.satisfied(level, template, placement)
                : BlueprintService.matchesPlacedState(level.getBlockState(position), placement.state());
    }

    @Nullable
    private static BlockState expectedBuildingState(BuildingRecord building, BlockPos position) {
        if (!BlueprintTemplates.supports(building.type())) return null;
        for (BlueprintPlacement placement : BlueprintTemplates.createForBuilding(building).placements()) {
            if (placement.position().equals(position) && placement.mode().isFinalStructure()
                    && !placement.state().isAir()) return placement.state();
        }
        return null;
    }
}
