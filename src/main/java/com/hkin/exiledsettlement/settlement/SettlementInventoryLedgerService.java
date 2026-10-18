package com.hkin.exiledsettlement.settlement;

import com.hkin.exiledsettlement.building.SettlementInventoryService;
import com.hkin.exiledsettlement.building.WarehouseInventoryService;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/** Low-frequency settlement-wide stock deltas. Moving items internally has zero net delta. */
public final class SettlementInventoryLedgerService {
    private SettlementInventoryLedgerService() { }

    public static void tickLevel(ServerLevel level) {
        SettlementSavedData data = SettlementSavedData.get(level);
        boolean changed = false;
        for (SettlementRecord settlement : data.settlements()) {
            if (!settlement.active()) continue;
            changed |= settlement.updateInventoryLedger(snapshot(level, settlement));
        }
        if (changed) data.touch();
    }

    public static Map<ResourceLocation, Integer> snapshot(ServerLevel level, SettlementRecord settlement) {
        Map<ResourceLocation, Integer> result = new LinkedHashMap<>();
        for (var storage : SettlementInventoryService.warehouses(settlement)) {
            WarehouseInventoryService.summarize(level, storage).forEach((item, count) ->
                    result.merge(BuiltInRegistries.ITEM.getKey(item), count, Integer::sum));
        }
        for (CitizenRecord citizen : settlement.citizens()) {
            for (ItemStack stack : citizen.inventory()) add(result, stack);
            for (var slot : CitizenRecord.EQUIPMENT_SLOTS) add(result, citizen.equipmentItem(slot));
        }
        return result;
    }

    private static void add(Map<ResourceLocation, Integer> result, ItemStack stack) {
        if (!stack.isEmpty()) result.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getCount(), Integer::sum);
    }
}
