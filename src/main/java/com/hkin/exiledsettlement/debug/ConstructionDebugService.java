package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.blueprint.BlueprintRecord;
import com.hkin.exiledsettlement.blueprint.BlueprintService;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Explicit, administrator-controlled construction bypass for diagnosing blueprint lifecycle problems.
 * The switch is deliberately runtime-only: a server restart always restores normal resident AI.
 */
public final class ConstructionDebugService {
    public static final int ADVANCE_INTERVAL_TICKS = 10;

    private static final Map<MinecraftServer, Set<EnabledSettlement>> ENABLED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ConstructionDebugService() {
    }

    public static boolean shouldAdvance(int serverTick) {
        return Math.floorMod(serverTick, ADVANCE_INTERVAL_TICKS) == 0;
    }

    public static boolean isEnabled(ServerLevel level, UUID settlementId) {
        synchronized (ENABLED) {
            Set<EnabledSettlement> settlements = ENABLED.get(level.getServer());
            return settlements != null && settlements.contains(new EnabledSettlement(level.dimension(), settlementId));
        }
    }

    public static boolean setEnabled(ServerLevel level, UUID settlementId, boolean enabled) {
        EnabledSettlement key = new EnabledSettlement(level.dimension(), settlementId);
        synchronized (ENABLED) {
            Set<EnabledSettlement> settlements = ENABLED.computeIfAbsent(
                    level.getServer(), ignored -> new HashSet<>());
            boolean changed = enabled ? settlements.add(key) : settlements.remove(key);
            if (settlements.isEmpty()) ENABLED.remove(level.getServer());
            return changed;
        }
    }

    /** Advances every active blueprint in each enabled settlement by at most one real work step. */
    public static void tickLevel(ServerLevel level) {
        SettlementSavedData data = SettlementSavedData.get(level);
        for (SettlementRecord settlement : List.copyOf(data.settlements())) {
            if (!settlement.active() || !isEnabled(level, settlement.id())) continue;
            for (BlueprintRecord blueprint : List.copyOf(settlement.blueprints())) {
                if (blueprint.cancellationQueued()) continue;
                BlueprintService.debugAdvanceOne(level, data, settlement, blueprint);
            }
        }
    }

    private record EnabledSettlement(ResourceKey<Level> dimension, UUID settlementId) {
    }
}
