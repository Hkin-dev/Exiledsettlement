package com.hkin.exiledsettlement.settlement;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import com.hkin.exiledsettlement.citizen.CitizenProfession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class SettlementSavedData extends SavedData {
    // 26: frozen agricultural excavation/fill plans and pending real construction tools.
    public static final int SCHEMA_VERSION = 32; // independent P1 mob-damage evidence and recovery goals
    /**
     * Schema 14 is the last released save line before the 0.14.5 additions.
     * New schema-15 fields all have conservative load defaults, so these saves
     * can be upgraded in place and are written back as schema 15 on the next save.
     */
    public static final int MIN_SUPPORTED_SCHEMA_VERSION = 14;
    private static final String DATA_NAME = ExiledSettlementMod.MOD_ID + "_settlements";
    private static final String SCHEMA_VERSION_TAG = "SchemaVersion";
    private static final String SETTLEMENTS_TAG = "Settlements";
    private static final Factory<SettlementSavedData> FACTORY = new Factory<>(SettlementSavedData::new, SettlementSavedData::load);

    private final Map<UUID, SettlementRecord> settlements = new LinkedHashMap<>();

    public static SettlementSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public static SettlementSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        SettlementSavedData data = new SettlementSavedData();
        int schemaVersion = tag.getInt(SCHEMA_VERSION_TAG);
        if (schemaVersion < MIN_SUPPORTED_SCHEMA_VERSION || schemaVersion > SCHEMA_VERSION) {
            ExiledSettlementMod.LOGGER.error(
                    "Settlement data schema {} is outside the supported range {}-{}. "
                            + "The settlement data was not loaded to avoid silent corruption.",
                    schemaVersion, MIN_SUPPORTED_SCHEMA_VERSION, SCHEMA_VERSION);
            return data;
        }
        if (schemaVersion < SCHEMA_VERSION) {
            ExiledSettlementMod.LOGGER.info(
                    "Migrating Exiled Settlement save data from schema {} to schema {}. "
                    + "Missing newer fields use safe automatic defaults.",
                    schemaVersion, SCHEMA_VERSION);
        }

        ListTag settlementsTag = tag.getList(SETTLEMENTS_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < settlementsTag.size(); i++) {
            try {
                SettlementRecord settlement = SettlementRecord.load(settlementsTag.getCompound(i), registries);
                data.settlements.put(settlement.id(), settlement);
            } catch (RuntimeException exception) {
                ExiledSettlementMod.LOGGER.error("Skipped an invalid settlement record at index {}.", i, exception);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt(SCHEMA_VERSION_TAG, SCHEMA_VERSION);
        ListTag settlementsTag = new ListTag();
        settlements.values().forEach(settlement -> settlementsTag.add(settlement.save(registries)));
        tag.put(SETTLEMENTS_TAG, settlementsTag);
        return tag;
    }

    public OperationResult<SettlementRecord> createOrRecover(BlockPos corePosition, ServerPlayer owner) {
        String name = owner.getGameProfile().getName() + " Settlement";
        return createOrRecover(corePosition, owner.getUUID(), name);
    }

    public OperationResult<SettlementRecord> createOrRecover(BlockPos corePosition, UUID ownerId, String name) {
        SettlementRecord existing = findByCore(corePosition).orElse(null);
        if (existing != null) {
            if (!existing.isOwner(ownerId)) {
                return OperationResult.failure("NO_PERMISSION");
            }
            if (existing.active()) {
                return OperationResult.failure("ALREADY_REGISTERED");
            }
            if (existing.activate()) {
                setDirty();
            }
            return OperationResult.success(existing);
        }

        boolean overlaps = settlements.values().stream()
                .filter(SettlementRecord::active)
                .anyMatch(settlement -> overlapsProposedBoundary(settlement, corePosition,
                        SettlementRecord.DEFAULT_BOUNDARY_RADIUS_CHUNKS));
        if (overlaps) {
            return OperationResult.failure("OVERLAPS_OTHER_SETTLEMENT");
        }

        SettlementRecord settlement = new SettlementRecord(UUID.randomUUID(), name, corePosition, ownerId);
        settlements.put(settlement.id(), settlement);
        setDirty();
        return OperationResult.success(settlement);
    }

    public OperationResult<BuildingRecord> registerBuilding(BlockPos position, BuildingType type, UUID actorId) {
        SettlementRecord settlement = findContaining(position).orElse(null);
        if (settlement == null) {
            return OperationResult.failure("OUTSIDE_SETTLEMENT_BOUNDARY");
        }
        if (!settlement.isMember(actorId)) {
            return OperationResult.failure("NO_PERMISSION");
        }
        BuildingRecord existing = settlement.buildingAt(position);
        if (existing != null) {
            return existing.type() == type
                    ? OperationResult.success(existing)
                    : OperationResult.failure("ALREADY_REGISTERED");
        }
        BuildingRecord building = settlement.addBuilding(type, position);
        setDirty();
        return OperationResult.success(building);
    }

    public OperationResult<BuildingRecord> registerBuilding(
            BlockPos position, BuildingType type, UUID actorId, net.minecraft.core.Direction facing,
            BlockPos minCorner, BlockPos maxCorner, int transitionMargin
    ) {
        return registerBuilding(position, type, actorId, facing, minCorner, maxCorner, transitionMargin, 1);
    }

    public OperationResult<BuildingRecord> registerBuilding(
            BlockPos position, BuildingType type, UUID actorId, net.minecraft.core.Direction facing,
            BlockPos minCorner, BlockPos maxCorner, int transitionMargin, int templateVersion
    ) {
        SettlementRecord settlement = findContaining(position).orElse(null);
        if (settlement == null) return OperationResult.failure("OUTSIDE_SETTLEMENT_BOUNDARY");
        if (!settlement.isMember(actorId)) return OperationResult.failure("NO_PERMISSION");
        BuildingRecord existing = settlement.buildingAt(position);
        if (existing != null) {
            return existing.type() == type ? OperationResult.success(existing)
                    : OperationResult.failure("ALREADY_REGISTERED");
        }
        BuildingRecord building = settlement.addBuilding(
                type, position, facing, minCorner, maxCorner, transitionMargin, templateVersion);
        setDirty();
        return OperationResult.success(building);
    }

    public boolean renameBuilding(SettlementRecord settlement, UUID actorId, UUID buildingId, String newName) {
        if (!settlement.isMember(actorId) || newName == null || newName.strip().isEmpty()) return false;
        BuildingRecord building = settlement.building(buildingId);
        if (building == null || !building.active() || !building.rename(newName)) return false;
        setDirty();
        return true;
    }

    public Optional<SettlementRecord> unregisterBuilding(BlockPos position) {
        Optional<SettlementRecord> settlement = findByBuildingPosition(position);
        if (settlement.isPresent() && settlement.get().removeBuilding(position)) {
            setDirty();
        }
        return settlement;
    }

    public Optional<SettlementRecord> deactivateByCore(BlockPos corePosition) {
        Optional<SettlementRecord> settlement = findByCore(corePosition);
        if (settlement.isPresent() && settlement.get().deactivate()) {
            setDirty();
        }
        return settlement;
    }

    public boolean addMember(SettlementRecord settlement, UUID actorId, UUID newMemberId) {
        if (!settlement.isOwner(actorId)) {
            return false;
        }
        boolean changed = settlement.addMember(newMemberId);
        if (changed) {
            setDirty();
        }
        return changed;
    }

    public OperationResult<SettlementRecord> claimChunk(BlockPos position, UUID actorId) {
        net.minecraft.world.level.ChunkPos chunk = new net.minecraft.world.level.ChunkPos(position);
        if (settlements.values().stream().filter(SettlementRecord::active)
                .anyMatch(settlement -> settlement.ownsChunk(chunk.x, chunk.z))) {
            return OperationResult.failure("LAND_ALREADY_CLAIMED");
        }
        SettlementRecord settlement = settlements.values().stream()
                .filter(SettlementRecord::active)
                .filter(candidate -> candidate.isOwner(actorId))
                .filter(candidate -> candidate.canClaimChunk(chunk.x, chunk.z))
                .min(Comparator.comparingDouble(candidate -> candidate.corePosition().distSqr(position)))
                .orElse(null);
        if (settlement == null) return OperationResult.failure("LAND_NOT_ADJACENT");
        if (!settlement.claimChunk(chunk.x, chunk.z)) return OperationResult.failure("LAND_CLAIM_FAILED");
        setDirty();
        return OperationResult.success(settlement);
    }

    private static boolean overlapsProposedBoundary(
            SettlementRecord existing, BlockPos proposedCore, int radius
    ) {
        net.minecraft.world.level.ChunkPos core = new net.minecraft.world.level.ChunkPos(proposedCore);
        for (int x = core.x - radius; x <= core.x + radius; x++) {
            for (int z = core.z - radius; z <= core.z + radius; z++) {
                if (existing.ownsChunk(x, z)) return true;
            }
        }
        return false;
    }

    public boolean removeMember(SettlementRecord settlement, UUID actorId, UUID memberId) {
        if (!settlement.isOwner(actorId)) {
            return false;
        }
        boolean changed = settlement.removeMember(memberId);
        if (changed) {
            setDirty();
        }
        return changed;
    }

    public boolean rename(SettlementRecord settlement, UUID actorId, String newName) {
        if (!settlement.isOwner(actorId)) {
            return false;
        }
        boolean changed = settlement.rename(newName);
        if (changed) {
            setDirty();
        }
        return changed;
    }

    public boolean updateProjection(SettlementRecord settlement, CitizenRecord citizen, UUID entityId, BlockPos position) {
        boolean changed = citizen.updateProjection(entityId, position);
        if (changed) {
            setDirty();
        }
        return changed;
    }

    public void touch() {
        setDirty();
    }

    public boolean changeProfessionTarget(
            SettlementRecord settlement,
            UUID actorId,
            CitizenProfession profession,
            int delta
    ) {
        if (!settlement.isMember(actorId) || !profession.specialist() || delta == 0) {
            return false;
        }
        int current = settlement.professionTarget(profession);
        int workers = settlement.professionTarget(CitizenProfession.WORKER);
        java.util.List<BuildingRecord> workplaces = settlement.buildings().stream()
                .filter(BuildingRecord::active)
                .filter(building -> profession.acceptsWorkplace(building.type()))
                .sorted(java.util.Comparator.comparing(building -> building.id().toString()))
                .toList();
        if (workplaces.isEmpty()) return false;
        if (delta > 0) {
            if (workers <= 0) {
                return false;
            }
            BuildingRecord workplace = workplaces.stream()
                    .filter(building -> building.workerTarget() < building.workerCapacity())
                    .min(java.util.Comparator.comparingInt(BuildingRecord::workerTarget)
                            .thenComparing(building -> building.id().toString()))
                    .orElse(null);
            if (workplace == null || !workplace.setWorkerTarget(
                    workplace.workerTarget() + 1, workplace.configurationRevision())) return false;
            settlement.setProfessionTarget(CitizenProfession.WORKER, workers - 1);
        } else {
            if (current <= 0) {
                return false;
            }
            BuildingRecord workplace = workplaces.stream()
                    .filter(building -> building.workerTarget() > settlement.lockedWorkers(building.id()))
                    .max(java.util.Comparator.comparingInt(BuildingRecord::workerTarget)
                            .thenComparing(building -> building.id().toString()))
                    .orElse(null);
            if (workplace == null || !workplace.setWorkerTarget(
                    workplace.workerTarget() - 1, workplace.configurationRevision())) return false;
            settlement.setProfessionTarget(CitizenProfession.WORKER, workers + 1);
        }
        setDirty();
        return true;
    }

    public boolean setBuildingWorkerTarget(
            SettlementRecord settlement, UUID actorId, UUID buildingId,
            int target, int expectedRevision
    ) {
        if (!settlement.isMember(actorId)) return false;
        BuildingRecord building = settlement.building(buildingId);
        if (building == null) return false;
        int previous = building.workerTarget();
        int delta = target - previous;
        int workers = settlement.professionTarget(CitizenProfession.WORKER);
        if (delta > workers || !settlement.setBuildingWorkerTarget(buildingId, target, expectedRevision)) {
            return false;
        }
        if (delta != 0) settlement.setProfessionTarget(CitizenProfession.WORKER, workers - delta);
        setDirty();
        return true;
    }

    public boolean setCitizenManualAssignment(
            SettlementRecord settlement, UUID actorId, UUID citizenId,
            @Nullable CitizenProfession profession, @Nullable UUID workBuildingId
    ) {
        if (!settlement.isMember(actorId)) return false;
        CitizenRecord citizen = settlement.citizen(citizenId);
        if (citizen == null || !citizen.alive()) return false;
        boolean changed;
        if (profession == null) {
            changed = citizen.clearManualAssignment();
        } else if (!profession.specialist()) {
            changed = citizen.setManualAssignment(profession, null);
        } else {
            BuildingRecord workplace = settlement.building(workBuildingId);
            if (workplace == null || !workplace.active() || !profession.acceptsWorkplace(workplace.type())) return false;
            changed = citizen.setManualAssignment(profession, workplace.id());
        }
        if (changed) setDirty();
        return changed;
    }

    public boolean requestCitizenManualRelease(
            SettlementRecord settlement, UUID actorId, UUID citizenId
    ) {
        if (!settlement.isMember(actorId)) return false;
        CitizenRecord citizen = settlement.citizen(citizenId);
        if (citizen == null || !citizen.alive()) return false;
        boolean changed = citizen.requestManualRelease();
        if (changed) setDirty();
        return changed;
    }

    public Optional<SettlementRecord> markCitizenDead(UUID settlementId, UUID citizenId, BlockPos deathPosition) {
        SettlementRecord settlement = settlements.get(settlementId);
        if (settlement == null) {
            return Optional.empty();
        }
        CitizenRecord citizen = settlement.citizen(citizenId);
        if (citizen == null) {
            return Optional.empty();
        }
        if (citizen.markDead(deathPosition)) {
            setDirty();
        }
        if (settlement.refreshHousingAssignments()) {
            setDirty();
        }
        return Optional.of(settlement);
    }

    public Optional<SettlementRecord> findById(UUID settlementId) {
        return Optional.ofNullable(settlements.get(settlementId));
    }

    public Optional<SettlementRecord> findByCore(BlockPos corePosition) {
        return settlements.values().stream()
                .filter(settlement -> settlement.corePosition().equals(corePosition))
                .findFirst();
    }

    public Optional<SettlementRecord> findContaining(BlockPos position) {
        return settlements.values().stream()
                .filter(SettlementRecord::active)
                .filter(settlement -> settlement.contains(position))
                .min(Comparator.comparingDouble(settlement -> settlement.corePosition().distSqr(position)));
    }

    public Optional<SettlementRecord> findByBuildingPosition(BlockPos position) {
        return settlements.values().stream()
                .filter(settlement -> settlement.buildingAt(position) != null)
                .findFirst();
    }

    public Optional<SettlementRecord> findByBlueprintPosition(BlockPos position) {
        return settlements.values().stream()
                .filter(settlement -> settlement.blueprintAt(position) != null)
                .findFirst();
    }

    public Optional<SettlementRecord> findNearestForPlayer(UUID playerId, BlockPos position, boolean includeInactive) {
        return settlements.values().stream()
                .filter(settlement -> includeInactive || settlement.active())
                .filter(settlement -> settlement.isMember(playerId))
                .min(Comparator.comparingDouble(settlement -> settlement.corePosition().distSqr(position)));
    }

    public Collection<SettlementRecord> settlements() {
        return Collections.unmodifiableCollection(settlements.values());
    }

    @Nullable
    public SettlementRecord settlementForCitizen(UUID settlementId) {
        return settlements.get(settlementId);
    }
}
