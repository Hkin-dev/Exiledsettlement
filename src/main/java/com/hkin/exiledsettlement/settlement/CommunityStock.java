package com.hkin.exiledsettlement.settlement;
import com.hkin.exiledsettlement.building.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import java.util.*;
/** Short-lived read index. Actual transfers always recheck the physical container. */
public final class CommunityStock {
    private record Snapshot(long expires, Map<UUID, Map<Item,Integer>> buildings) { }
    private static final Map<ServerLevel, Map<UUID,Snapshot>> CACHE = new WeakHashMap<>();
    private CommunityStock() { }
    public static Map<Item,Integer> items(ServerLevel level, SettlementRecord settlement, BuildingRecord building) {
        if(building.type().isHouse())return WarehouseInventoryService.summarize(level,building);
        return snapshot(level, settlement).getOrDefault(building.id(), Map.of());
    }
    public static int count(ServerLevel level, SettlementRecord settlement, Item item) {
        if(settlement.creativeCoreInstalled())return 1_000_000;
        return snapshot(level, settlement).values().stream().mapToInt(m -> m.getOrDefault(item, 0)).sum();
    }
    public static int food(ServerLevel level, SettlementRecord settlement) {
        if(settlement.creativeCoreInstalled())return 1_000_000;
        return snapshot(level, settlement).values().stream().flatMap(m -> m.entrySet().stream())
                .filter(e -> CommunityLifeService.food(new ItemStack(e.getKey()))).mapToInt(Map.Entry::getValue).sum();
    }
    public static void invalidate(ServerLevel level, SettlementRecord settlement) {
        var entries = CACHE.get(level); if (entries != null) entries.remove(settlement.id());
    }
    private static Map<UUID, Map<Item,Integer>> snapshot(ServerLevel level, SettlementRecord settlement) {
        var entries = CACHE.computeIfAbsent(level, ignored -> new HashMap<>());
        var old = entries.get(settlement.id()); long now = level.getGameTime();
        if (old != null && now < old.expires && now >= old.expires - 100) return old.buildings;
        Map<UUID, Map<Item,Integer>> stock = new LinkedHashMap<>();
        for (var b : SettlementInventoryService.warehouses(settlement)) if (!settlement.community().danger(b.position()))
            stock.put(b.id(), Map.copyOf(WarehouseInventoryService.summarize(level, b)));
        entries.put(settlement.id(), new Snapshot(now + 100, Map.copyOf(stock))); return stock;
    }
}
