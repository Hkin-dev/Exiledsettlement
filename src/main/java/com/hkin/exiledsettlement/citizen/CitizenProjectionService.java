package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import com.hkin.exiledsettlement.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

public final class CitizenProjectionService {
    public static final String SETTLEMENT_ID_TAG = "exiledsettlement.settlement_id";
    public static final String CITIZEN_ID_TAG = "exiledsettlement.citizen_id";
    private record Loaded(int tick,List<SettlementCitizenEntity> entities) { }
    private static final Map<ServerLevel,Loaded> LOADED=new WeakHashMap<>();

    private CitizenProjectionService() {
    }

    public static void ensureSettlementCitizens(ServerLevel level, SettlementRecord settlement) {
        if (!settlement.active() || !level.hasChunkAt(settlement.corePosition())) {
            return;
        }
        SettlementSavedData data = SettlementSavedData.get(level);
        for (CitizenRecord citizen : settlement.citizens()) {
            ensureCitizen(level, data, settlement, citizen);
        }
    }

    public static void tickLevel(ServerLevel level) {
        SettlementSavedData data = SettlementSavedData.get(level);
        for (SettlementRecord settlement : data.settlements()) {
            ensureSettlementCitizens(level, settlement);
        }
    }

    private static void ensureCitizen(
            ServerLevel level,
            SettlementSavedData data,
            SettlementRecord settlement,
            CitizenRecord citizen
    ) {
        if (!citizen.alive() || !level.hasChunkAt(citizen.lastPosition())) {
            return;
        }

        SettlementCitizenEntity projection = findProjection(level, citizen);
        if (projection == null) {
            // Block chunks may be ready before their persisted entities arrive.
            // Wait for entity IO instead of creating a replacement at the surface.
            if(!level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(citizen.lastPosition())))return;
            projection = spawnProjection(level, settlement, citizen);
        }
        if (projection != null) {
            syncName(citizen, projection);
            projection.setSkinVariant(citizen.id());
            projection.setYsmModelSelection(citizen.ysmModelId(), citizen.ysmTextureName());
            CitizenEquipmentService.syncVisibleEquipment(citizen, projection);
            projection.setActivity(citizen.activity());
            discardDuplicateProjections(level, settlement, citizen, projection);
            data.updateProjection(settlement, citizen, projection.getUUID(), projection.blockPosition());
        }
    }

    @Nullable
    public static SettlementCitizenEntity findProjection(ServerLevel level, CitizenRecord citizen) {
        UUID entityId = citizen.entityId();
        if (entityId != null) {
            Entity entity = level.getEntity(entityId);
            if (entity instanceof SettlementCitizenEntity projection && matches(projection, citizen.id())) {
                return projection;
            }
        }

        // Claimed land and long construction routes can extend beyond the old
        // core radius. Reuse a loaded identity there instead of spawning another
        // resident when the persisted entity UUID has not yet been rebound.
        return loadedCitizens(level).stream().filter(projection->projection.isAlive() && matches(projection,citizen.id()))
                .min(java.util.Comparator.comparingDouble(projection->projection.blockPosition().distSqr(citizen.lastPosition())))
                .orElse(null);
    }

    private static List<SettlementCitizenEntity> loadedCitizens(ServerLevel level) {
        int tick=level.getServer().getTickCount();var cached=LOADED.get(level);
        if(cached!=null && cached.tick()==tick)return cached.entities();
        var entities=new ArrayList<SettlementCitizenEntity>();
        for(var entity:level.getAllEntities())if(entity instanceof SettlementCitizenEntity citizen && isManaged(entity))entities.add(citizen);
        LOADED.put(level,new Loaded(tick,entities));return entities;
    }

    @Nullable
    private static SettlementCitizenEntity spawnProjection(
            ServerLevel level,
            SettlementRecord settlement,
            CitizenRecord citizen
    ) {
        SettlementCitizenEntity projection = createProjection(level, settlement, citizen);
        if (projection == null) {
            return null;
        }

        BlockPos spawnPosition = resolveSpawnPosition(level, citizen.lastPosition());
        projection.moveTo(
                spawnPosition.getX() + 0.5D,
                spawnPosition.getY(),
                spawnPosition.getZ() + 0.5D,
                level.random.nextFloat() * 360.0F,
                0.0F
        );
        if(!level.addFreshEntity(projection))return null;
        LOADED.remove(level);return projection;
    }

    @Nullable
    private static SettlementCitizenEntity createProjection(
            ServerLevel level,
            SettlementRecord settlement,
            CitizenRecord citizen
    ) {
        SettlementCitizenEntity projection = ModEntities.SETTLEMENT_CITIZEN.get().create(level);
        if (projection == null) {
            return null;
        }
        projection.setPersistenceRequired();
        projection.setCanPickUpLoot(false);
        projection.restrictTo(settlement.corePosition(), settlement.boundaryRadiusChunks() * 16);
        syncName(citizen, projection);
        projection.setSkinVariant(citizen.id());
        projection.setYsmModelSelection(citizen.ysmModelId(), citizen.ysmTextureName());
        projection.setCustomNameVisible(true);
        projection.getPersistentData().putString(SETTLEMENT_ID_TAG, settlement.id().toString());
        projection.getPersistentData().putString(CITIZEN_ID_TAG, citizen.id().toString());
        CitizenEquipmentService.syncVisibleEquipment(citizen, projection);
        projection.setActivity(citizen.activity());
        return projection;
    }

    public static void syncName(CitizenRecord citizen, SettlementCitizenEntity projection) {
        projection.setVisibleNutrition(citizen.nutrition());
        Component label = Component.literal("[")
                .append(Component.translatable(citizen.canWork() ? citizen.profession().translationKey()
                        : "citizen_activity.exiledsettlement.growing"))
                .append("] ").append(Component.literal(citizen.name()));
        if (!label.equals(projection.getCustomName())) {
            projection.setCustomName(label);
        }
    }

    private static void discardDuplicateProjections(
            ServerLevel level,
            SettlementRecord settlement,
            CitizenRecord citizen,
            SettlementCitizenEntity retained
    ) {
        loadedCitizens(level).stream()
                .filter(projection->projection!=retained && projection.isAlive() && matches(projection,citizen.id())
                        && settlement.id().equals(settlementId(projection)))
                .forEach(Entity::discard);
    }

    private static BlockPos resolveSpawnPosition(ServerLevel level, BlockPos requested) {
        // Preserve valid saved indoor/underground feet positions when recreating a projection.
        if(level.hasChunkAt(requested)
                && level.getBlockState(requested).getCollisionShape(level,requested).isEmpty()
                && level.getBlockState(requested.above()).getCollisionShape(level,requested.above()).isEmpty()
                && level.getFluidState(requested).isEmpty()
                && level.getBlockState(requested.below()).isFaceSturdy(level,requested.below(),net.minecraft.core.Direction.UP))
            return requested;
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, requested.getX(), requested.getZ());
        return new BlockPos(requested.getX(), Math.max(requested.getY(), surfaceY), requested.getZ());
    }

    public static boolean isManaged(Entity entity) {
        return entity.getPersistentData().contains(CITIZEN_ID_TAG)
                && entity.getPersistentData().contains(SETTLEMENT_ID_TAG);
    }

    public static boolean matches(Entity entity, UUID citizenId) {
        return citizenId.toString().equals(entity.getPersistentData().getString(CITIZEN_ID_TAG));
    }

    @Nullable
    public static UUID settlementId(Entity entity) {
        return readUuid(entity, SETTLEMENT_ID_TAG);
    }

    @Nullable
    public static UUID citizenId(Entity entity) {
        return readUuid(entity, CITIZEN_ID_TAG);
    }

    @Nullable
    private static UUID readUuid(Entity entity, String key) {
        String value = entity.getPersistentData().getString(key);
        if (value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
