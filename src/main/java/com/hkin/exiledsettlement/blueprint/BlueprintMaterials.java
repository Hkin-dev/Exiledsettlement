package com.hkin.exiledsettlement.blueprint;

import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.building.WarehouseInventoryService;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BlueprintMaterials {
    private BlueprintMaterials() {
    }

    public static Map<Item, Integer> missing(
            ServerLevel level,
            SettlementRecord settlement,
            ServerPlayer player,
            Map<Item, Integer> requirements
    ) {
        List<Container> sources = sources(level, settlement, player);
        Map<Item, Integer> missing = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : requirements.entrySet()) {
            int available = count(sources, entry.getKey());
            if (available < entry.getValue()) {
                missing.put(entry.getKey(), entry.getValue() - available);
            }
        }
        return missing;
    }

    public static void consume(
            ServerLevel level,
            SettlementRecord settlement,
            ServerPlayer player,
            Map<Item, Integer> requirements
    ) {
        List<Container> sources = sources(level, settlement, player);
        for (Map.Entry<Item, Integer> entry : requirements.entrySet()) {
            int remaining = entry.getValue();
            for (Container source : sources) {
                for (int slot = 0; slot < source.getContainerSize() && remaining > 0; slot++) {
                    ItemStack stack = source.getItem(slot);
                    if (!stack.is(entry.getKey())) {
                        continue;
                    }
                    int removed = Math.min(remaining, stack.getCount());
                    stack.shrink(removed);
                    remaining -= removed;
                    source.setChanged();
                }
                if (remaining == 0) {
                    break;
                }
            }
            if (remaining != 0) {
                throw new IllegalStateException("Blueprint materials changed during a server-thread transaction");
            }
        }
    }

    private static int count(List<Container> sources, Item item) {
        int count = 0;
        for (Container source : sources) {
            for (int slot = 0; slot < source.getContainerSize(); slot++) {
                ItemStack stack = source.getItem(slot);
                if (stack.is(item)) {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }

    private static List<Container> sources(ServerLevel level, SettlementRecord settlement, ServerPlayer player) {
        List<Container> sources = new ArrayList<>();
        Set<Container> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        sources.add(player.getInventory());
        seen.add(player.getInventory());

        for (BuildingRecord building : settlement.buildings()) {
            if (!building.active() || !building.operational() || !building.type().isStorage()) {
                continue;
            }
            for (WarehouseInventoryService.ContainerAt containerAt
                    : WarehouseInventoryService.loadedContainers(level, building)) {
                if (seen.add(containerAt.container())) {
                    sources.add(containerAt.container());
                }
            }
        }
        return sources;
    }
}
