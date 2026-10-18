package com.hkin.exiledsettlement.building;

import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;

public final class SettlementInventoryService {
    private SettlementInventoryService() {
    }

    public static List<BuildingRecord> warehouses(SettlementRecord settlement) {
        return settlement.buildings().stream()
                .filter(BuildingRecord::active)
                .filter(BuildingRecord::operational)
                .filter(building -> building.type().isStorage())
                .toList();
    }

    public static boolean hasCreativeSupply(ServerLevel level, SettlementRecord settlement) {
        return settlement.creativeCoreInstalled() || warehouses(settlement).stream()
                .anyMatch(warehouse -> WarehouseInventoryService.hasCreativeSupply(level, warehouse));
    }

    public static int countCategory(ServerLevel level, SettlementRecord settlement, WarehouseCategory category) {
        return warehouses(settlement).stream()
                .mapToInt(warehouse -> WarehouseInventoryService.countCategory(level, warehouse, category))
                .sum();
    }

    public static int countItem(ServerLevel level, SettlementRecord settlement, Item item) {
        return warehouses(settlement).stream()
                .mapToInt(warehouse -> WarehouseInventoryService.countItem(level, warehouse, item))
                .sum();
    }

    public static boolean atToolLimit(ServerLevel level, SettlementRecord settlement) {
        return countCategory(level, settlement, WarehouseCategory.TOOLS) >= settlement.toolStockLimit();
    }

    @Nullable
    public static BuildingRecord nearestWarehouse(SettlementRecord settlement, BlockPos from) {
        return warehouses(settlement).stream()
                .min(Comparator.comparingDouble(building -> building.position().distSqr(from)))
                .orElse(null);
    }

    @Nullable
    public static BuildingRecord supplyWarehouse(
            ServerLevel level, SettlementRecord settlement, BlockPos from, Item requested
    ) {
        return warehouses(settlement).stream()
                .filter(warehouse -> WarehouseInventoryService.countItem(level, warehouse, requested) > 0
                        || WarehouseInventoryService.hasCreativeSupply(level, warehouse))
                .min(Comparator.comparingDouble(building -> building.position().distSqr(from)))
                .orElse(null);
    }

    public static ItemStack extractRequested(
            ServerLevel level, SettlementRecord settlement, BlockPos from, Item requested, int count
    ) {
        BuildingRecord warehouse = supplyWarehouse(level, settlement, from, requested);
        return warehouse == null ? ItemStack.EMPTY
                : WarehouseInventoryService.extractItemOrCreative(level, warehouse, requested, count);
    }
}
