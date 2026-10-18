package com.hkin.exiledsettlement.settlement;

import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.blueprint.BlueprintRecord;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

public final class SettlementChunkLoadingService {
    private SettlementChunkLoadingService() {
    }

    public static void reconcile(ServerLevel level) {
        SettlementSavedData data = SettlementSavedData.get(level);
        boolean changed = false;
        for (SettlementRecord settlement : data.settlements()) {
            Set<Long> desired = desiredChunks(settlement);
            for (long key : Set.copyOf(settlement.forcedChunkKeys())) {
                if (desired.contains(key)) continue;
                ChunkPos chunk = new ChunkPos(key);
                level.setChunkForced(chunk.x, chunk.z, false);
            }
            for (long key : desired) {
                if (settlement.forcedChunkKeys().contains(key)) continue;
                ChunkPos chunk = new ChunkPos(key);
                level.setChunkForced(chunk.x, chunk.z, true);
            }
            changed |= settlement.replaceForcedChunks(desired);
        }
        if (changed) data.touch();
    }

    private static Set<Long> desiredChunks(SettlementRecord settlement) {
        if (!settlement.active() || !settlement.chunkLoadingEnabled()) return Set.of();
        ChunkPos core = new ChunkPos(settlement.corePosition());
        Stream<ChunkPos> coreArea = Stream.of(
                core,
                new ChunkPos(core.x + 1, core.z), new ChunkPos(core.x - 1, core.z),
                new ChunkPos(core.x, core.z + 1), new ChunkPos(core.x, core.z - 1),
                new ChunkPos(core.x + 1, core.z + 1), new ChunkPos(core.x + 1, core.z - 1),
                new ChunkPos(core.x - 1, core.z + 1), new ChunkPos(core.x - 1, core.z - 1));
        Stream<ChunkPos> buildings = settlement.buildings().stream().filter(BuildingRecord::active)
                .flatMap(building -> Stream.of(new ChunkPos(building.position()),
                        new ChunkPos(building.minCorner()), new ChunkPos(building.maxCorner())));
        Stream<ChunkPos> blueprints = settlement.blueprints().stream()
                .flatMap(blueprint -> Stream.of(new ChunkPos(blueprint.anchor()),
                        new ChunkPos(blueprint.markerPosition())));
        Stream<ChunkPos> residents = settlement.citizens().stream().filter(citizen -> citizen.alive())
                .map(citizen -> new ChunkPos(citizen.lastPosition()));
        LinkedHashSet<Long> selected = new LinkedHashSet<>();
        Stream.concat(Stream.concat(coreArea, buildings), Stream.concat(blueprints, residents))
                .filter(chunk -> Math.abs(chunk.x - core.x) <= settlement.boundaryRadiusChunks()
                        && Math.abs(chunk.z - core.z) <= settlement.boundaryRadiusChunks())
                .distinct()
                .sorted(Comparator.comparingInt(chunk -> Math.abs(chunk.x - core.x) + Math.abs(chunk.z - core.z)))
                .limit(settlement.level().chunkLoadingLimit())
                .forEach(chunk -> selected.add(chunk.toLong()));
        return selected;
    }
}
