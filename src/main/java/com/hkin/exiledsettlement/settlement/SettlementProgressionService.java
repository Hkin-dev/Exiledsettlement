package com.hkin.exiledsettlement.settlement;

import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.building.WarehouseInventoryService;
import com.hkin.exiledsettlement.citizen.CitizenProjectionService;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import com.hkin.exiledsettlement.citizen.ProfessionAssignmentService;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public final class SettlementProgressionService {
    private SettlementProgressionService() {
    }

    public static OperationResult<SettlementLevel> upgrade(
            SettlementSavedData data, SettlementRecord settlement, ServerPlayer player
    ) {
        if (!settlement.isMember(player.getUUID())) return OperationResult.failure("NO_PERMISSION");
        SettlementLevel next = settlement.level().next();
        if (next == null) return OperationResult.failure("SETTLEMENT_MAX_LEVEL");
        if (settlement.operationalBuildingCount() < settlement.level().buildingsForNextLevel()) {
            return OperationResult.failure("SETTLEMENT_UPGRADE_BUILDINGS_MISSING");
        }
        settlement.upgradeLevel();
        data.touch();
        return OperationResult.success(next);
    }

    public static OperationResult<CitizenRecord> recruit(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement, ServerPlayer player
    ) {
        if (!settlement.isMember(player.getUUID())) return OperationResult.failure("NO_PERMISSION");
        if (settlement.aliveCitizenCount() >= settlement.level().populationCap()) {
            return OperationResult.failure("POPULATION_CAP_REACHED");
        }
        if (settlement.housingCapacity() <= settlement.aliveCitizenCount()) {
            return OperationResult.failure("NO_HOUSING_CAPACITY");
        }
        int cost = settlement.level().migrantFoodCost();
        if (availableFood(level, settlement) < cost + settlement.community().foodReserve) {
            return OperationResult.failure("NOT_ENOUGH_FOOD");
        }
        consumeFood(level, settlement, cost);
        CitizenRecord citizen = settlement.addMigrant();
        settlement.refreshHousingAssignments();
        ProfessionAssignmentService.refresh(data, settlement);
        data.touch();
        CitizenProjectionService.tickLevel(level);
        return OperationResult.success(citizen);
    }

    public static int availableFood(ServerLevel level, SettlementRecord settlement) {
        if (com.hkin.exiledsettlement.building.SettlementInventoryService.hasCreativeSupply(level, settlement)) {
            return Integer.MAX_VALUE;
        }
        int total = 0;
        for (BuildingRecord warehouse : warehouses(settlement)) {
            for (WarehouseInventoryService.ContainerAt containerAt
                    : WarehouseInventoryService.loadedContainers(level, warehouse)) {
                Container container = containerAt.container();
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    ItemStack stack = container.getItem(slot);
                    if (isFood(stack)) total += stack.getCount();
                }
            }
        }
        return total;
    }

    private static void consumeFood(ServerLevel level, SettlementRecord settlement, int requested) {
        if (com.hkin.exiledsettlement.building.SettlementInventoryService.hasCreativeSupply(level, settlement)) {
            return;
        }
        int remaining = requested;
        for (BuildingRecord warehouse : warehouses(settlement)) {
            for (WarehouseInventoryService.ContainerAt containerAt
                    : WarehouseInventoryService.loadedContainers(level, warehouse)) {
                Container container = containerAt.container();
                for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
                    ItemStack stack = container.getItem(slot);
                    if (!isFood(stack)) continue;
                    int removed = Math.min(stack.getCount(), remaining);
                    stack.shrink(removed);
                    remaining -= removed;
                    container.setChanged();
                }
                if (remaining == 0) return;
            }
        }
    }

    private static boolean isFood(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.FOOD);
    }

    private static List<BuildingRecord> warehouses(SettlementRecord settlement) {
        return settlement.buildings().stream()
                .filter(BuildingRecord::active)
                .filter(BuildingRecord::operational)
                .filter(building -> building.type().isStorage())
                .toList();
    }
}
