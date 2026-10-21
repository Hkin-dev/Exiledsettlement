package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import java.util.*;

/** Runtime-only budget selection; never resets a solver or changes its constraints. */
public final class TerrainComputeDebugService {
    public static final int MAX_MULTIPLIER = 8;
    public static final long BASE_BUDGET_NANOS = 2_000_000L;
    private record Key(ResourceKey<Level> dimension, UUID settlement) {}
    private static final Map<MinecraftServer,Map<Key,Integer>> VALUES = new WeakHashMap<>();
    private TerrainComputeDebugService() {}

    public static synchronized int multiplier(ServerLevel level,SettlementRecord settlement) {
        return VALUES.getOrDefault(level.getServer(),Map.of())
                .getOrDefault(new Key(level.dimension(),settlement.id()),1);
    }
    public static synchronized void set(ServerLevel level,SettlementRecord settlement,int multiplier) {
        if(multiplier<1 || multiplier>MAX_MULTIPLIER)throw new IllegalArgumentException("Terrain compute multiplier must be 1..8");
        var values=VALUES.computeIfAbsent(level.getServer(),ignored->new HashMap<>());
        var key=new Key(level.dimension(),settlement.id());
        if(multiplier==1)values.remove(key);else values.put(key,multiplier);
        if(values.isEmpty())VALUES.remove(level.getServer());
    }
    public static long budgetNanos(int multiplier) {
        if(multiplier<1 || multiplier>MAX_MULTIPLIER)throw new IllegalArgumentException("Terrain compute multiplier must be 1..8");
        return BASE_BUDGET_NANOS*multiplier;
    }
}
