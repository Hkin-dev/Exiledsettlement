package com.hkin.exiledsettlement.settlement;

import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.blueprint.BlueprintRecord;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import com.hkin.exiledsettlement.citizen.CitizenProfession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SettlementRecord {
    public static final int DEFAULT_BOUNDARY_RADIUS_CHUNKS = 2;
    public static final int INITIAL_CITIZEN_COUNT = 6;

    private static final String ID_TAG = "Id";
    private static final String NAME_TAG = "Name";
    private static final String CORE_POSITION_TAG = "CorePosition";
    private static final String OWNER_TAG = "Owner";
    private static final String MEMBERS_TAG = "Members";
    private static final String ACTIVE_TAG = "Active";
    private static final String BOUNDARY_RADIUS_TAG = "BoundaryRadiusChunks";
    private static final String CITIZENS_TAG = "Citizens";
    private static final String BUILDINGS_TAG = "Buildings";
    private static final String BLUEPRINTS_TAG = "Blueprints";
    private static final String PROFESSION_TARGETS_TAG = "ProfessionTargets";
    private static final String RETIRED_BLOCKS_TAG = "RetiredBlocks";
    private static final String LEVEL_TAG = "Level";
    private static final String TOOL_STOCK_LIMIT_TAG = "ToolStockLimit";
    private static final String EQUIPMENT_STOCK_LIMIT_TAG = "EquipmentStockLimit";
    private static final String CREATIVE_CORE_INSTALLED_TAG = "CreativeCoreInstalled";
    private static final String CHUNK_LOADING_ENABLED_TAG = "ChunkLoadingEnabled";
    private static final String FORCED_CHUNKS_TAG = "ForcedChunks";
    private static final String ROAD_REPAIR_POSITIONS_TAG = "RoadRepairPositions";
    private static final String CLAIMED_CHUNKS_TAG = "ClaimedChunks";
    private static final String INVENTORY_SAMPLE_TAG = "InventorySample";
    private static final String PRODUCED_TOTAL_TAG = "ProducedTotal";
    private static final String CONSUMED_TOTAL_TAG = "ConsumedTotal";
    private static final String APPOINTMENT_PRODUCTION_DAY_TAG = "AppointmentProductionDay";
    private static final String MERCHANT_STATE_TAG = "MerchantState";
    public static final int DEFAULT_STOCK_LIMIT = 32;
    public static final int MAX_STOCK_LIMIT = 4096;

    private final com.hkin.exiledsettlement.terrain.TerrainState terrain=new com.hkin.exiledsettlement.terrain.TerrainState();
    public com.hkin.exiledsettlement.terrain.TerrainState terrain(){return terrain;}
    private final CommunityState community = new CommunityState();
    public CommunityState community() { return community; }
    private final com.hkin.exiledsettlement.decoration.DecorationState decorations = new com.hkin.exiledsettlement.decoration.DecorationState();
    public com.hkin.exiledsettlement.decoration.DecorationState decorations() { return decorations; }
    private final com.hkin.exiledsettlement.road.AutoRoadState autoRoads = new com.hkin.exiledsettlement.road.AutoRoadState();
    public com.hkin.exiledsettlement.road.AutoRoadState autoRoads() { return autoRoads; }
    private static final List<BlockPos> INITIAL_OFFSETS = List.of(
            new BlockPos(2, 0, 0),
            new BlockPos(-2, 0, 0),
            new BlockPos(0, 0, 2),
            new BlockPos(0, 0, -2),
            new BlockPos(2, 0, 2),
            new BlockPos(-2, 0, -2)
    );

    private final UUID id;
    private String name;
    private final BlockPos corePosition;
    private final UUID ownerId;
    private final Set<UUID> memberIds;
    private boolean active;
    private int boundaryRadiusChunks;
    private final Map<UUID, CitizenRecord> citizens;
    private final Map<UUID, BuildingRecord> buildings;
    private final Map<UUID, BlueprintRecord> blueprints;
    private final EnumMap<CitizenProfession, Integer> professionTargets;
    private final Set<BlockPos> retiredBlockPositions;
    private SettlementLevel level;
    private int toolStockLimit;
    private int equipmentStockLimit;
    private boolean creativeCoreInstalled;
    private boolean chunkLoadingEnabled;
    private final Set<Long> forcedChunkKeys;
    private final Set<BlockPos> roadRepairPositions;
    private final com.hkin.exiledsettlement.building.MobRecoveryState mobRecovery = new com.hkin.exiledsettlement.building.MobRecoveryState();
    public com.hkin.exiledsettlement.building.MobRecoveryState mobRecovery() { return mobRecovery; }
    private final Set<Long> claimedChunkKeys;
    private final Map<net.minecraft.resources.ResourceLocation, Integer> inventorySample;
    private final Map<net.minecraft.resources.ResourceLocation, Integer> producedTotals;
    private final Map<net.minecraft.resources.ResourceLocation, Integer> consumedTotals;
    private long appointmentProductionDay;
    private final MerchantState merchantState = new MerchantState();

    public SettlementRecord(UUID id, String name, BlockPos corePosition, UUID ownerId) {
        this(id, name, corePosition, ownerId, new LinkedHashSet<>(), true, DEFAULT_BOUNDARY_RADIUS_CHUNKS,
                new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(),
                defaultProfessionTargets(INITIAL_CITIZEN_COUNT), new LinkedHashSet<>(), SettlementLevel.CAMP,
                DEFAULT_STOCK_LIMIT, DEFAULT_STOCK_LIMIT, false, false,
                new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(),
                new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), -1L);
        initializeCitizens();
    }

    private SettlementRecord(
            UUID id,
            String name,
            BlockPos corePosition,
            UUID ownerId,
            Set<UUID> memberIds,
            boolean active,
            int boundaryRadiusChunks,
            Map<UUID, CitizenRecord> citizens,
            Map<UUID, BuildingRecord> buildings,
            Map<UUID, BlueprintRecord> blueprints,
            EnumMap<CitizenProfession, Integer> professionTargets,
            Set<BlockPos> retiredBlockPositions,
            SettlementLevel level,
            int toolStockLimit,
            int equipmentStockLimit,
            boolean creativeCoreInstalled,
            boolean chunkLoadingEnabled,
            Set<Long> forcedChunkKeys,
            Set<BlockPos> roadRepairPositions,
            Set<Long> claimedChunkKeys,
            Map<net.minecraft.resources.ResourceLocation, Integer> inventorySample,
            Map<net.minecraft.resources.ResourceLocation, Integer> producedTotals,
            Map<net.minecraft.resources.ResourceLocation, Integer> consumedTotals,
            long appointmentProductionDay
    ) {
        this.id = id;
        this.name = name;
        this.corePosition = corePosition.immutable();
        this.ownerId = ownerId;
        this.memberIds = memberIds;
        this.active = active;
        this.boundaryRadiusChunks = Math.max(boundaryRadiusChunks, level.boundaryRadiusChunks());
        this.citizens = citizens;
        this.buildings = buildings;
        this.blueprints = blueprints;
        for(var blueprint:blueprints.values())trackBlueprint(blueprint);
        this.professionTargets = professionTargets;
        this.retiredBlockPositions = retiredBlockPositions;
        this.level = level;
        this.toolStockLimit = normalizeStockLimit(toolStockLimit);
        this.equipmentStockLimit = normalizeStockLimit(equipmentStockLimit);
        this.creativeCoreInstalled = creativeCoreInstalled;
        this.chunkLoadingEnabled = chunkLoadingEnabled;
        this.forcedChunkKeys = new LinkedHashSet<>(forcedChunkKeys);
        this.roadRepairPositions = new LinkedHashSet<>();
        roadRepairPositions.stream().map(BlockPos::immutable).forEach(this.roadRepairPositions::add);
        this.claimedChunkKeys = new LinkedHashSet<>(claimedChunkKeys);
        this.inventorySample = new LinkedHashMap<>(inventorySample);
        this.producedTotals = new LinkedHashMap<>(producedTotals);
        this.consumedTotals = new LinkedHashMap<>(consumedTotals);
        this.appointmentProductionDay = appointmentProductionDay;
    }

    private void initializeCitizens() {
        for (int i = 0; i < INITIAL_CITIZEN_COUNT; i++) {
            UUID citizenId = UUID.randomUUID();
            CitizenRecord citizen = new CitizenRecord(
                    citizenId,
                    CommunityContent.newName(citizenId, citizens.values()),
                    corePosition.offset(INITIAL_OFFSETS.get(i))
            );
            citizens.put(citizen.id(), citizen);
        }
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public BlockPos corePosition() {
        return corePosition;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public boolean active() {
        return active;
    }

    public int boundaryRadiusChunks() {
        return boundaryRadiusChunks;
    }

    public SettlementLevel level() {
        return level;
    }

    public int toolStockLimit() { return toolStockLimit; }
    public int equipmentStockLimit() { return equipmentStockLimit; }
    public boolean creativeCoreInstalled() { return creativeCoreInstalled; }
    public boolean chunkLoadingEnabled() { return chunkLoadingEnabled; }
    public Set<Long> forcedChunkKeys() { return Collections.unmodifiableSet(forcedChunkKeys); }
    public Set<BlockPos> roadRepairPositions() { return Collections.unmodifiableSet(roadRepairPositions); }

    public boolean setCreativeCoreInstalled(boolean value) {
        if (creativeCoreInstalled == value) return false;
        creativeCoreInstalled = value;
        return true;
    }

    public boolean toggleChunkLoading() {
        chunkLoadingEnabled = !chunkLoadingEnabled;
        return true;
    }

    public boolean replaceForcedChunks(Set<Long> values) {
        if (forcedChunkKeys.equals(values)) return false;
        forcedChunkKeys.clear();
        forcedChunkKeys.addAll(values);
        return true;
    }

    public boolean markRoadDamaged(BlockPos position) { return roadRepairPositions.add(position.immutable()); }
    public boolean clearRoadRepair(BlockPos position) { return roadRepairPositions.remove(position); }

    public boolean setToolStockLimit(int value) {
        int normalized = normalizeStockLimit(value);
        if (toolStockLimit == normalized) return false;
        toolStockLimit = normalized;
        return true;
    }

    public boolean setEquipmentStockLimit(int value) {
        int normalized = normalizeStockLimit(value);
        if (equipmentStockLimit == normalized) return false;
        equipmentStockLimit = normalized;
        return true;
    }

    public int housingCapacity() {
        return buildings.values().stream()
                .filter(BuildingRecord::active)
                .filter(BuildingRecord::operational)
                .filter(building -> building.type().isHouse())
                .mapToInt(BuildingRecord::capacity)
                .sum();
    }

    public boolean upgradeLevel() {
        SettlementLevel next = level.next();
        if (next == null) return false;
        level = next;
        boundaryRadiusChunks = Math.max(boundaryRadiusChunks, next.boundaryRadiusChunks());
        return true;
    }

    public CitizenRecord addMigrant() {
        int sequence = citizens.size() + 1;
        UUID citizenId = UUID.randomUUID();
        CitizenRecord citizen = new CitizenRecord(citizenId, CommunityContent.newName(citizenId, citizens.values()),
                corePosition.offset(1 + sequence % 3, 0, 1 + sequence % 2));
        citizen.life().ageDays = CommunityContent.adulthoodDays;
        citizens.put(citizen.id(), citizen);
        professionTargets.put(CitizenProfession.WORKER,
                professionTargets.getOrDefault(CitizenProfession.WORKER, 0) + 1);
        return citizen;
    }

    public CitizenRecord addChild(CitizenRecord first, CitizenRecord second) {
        CitizenRecord child = addMigrant();
        child.life().ageDays = 0;
        child.life().adultReached = false;
        child.life().adulthoodAt = CommunityContent.adulthoodDays;
        child.life().parentA = first == null ? null : first.id();
        child.life().parentB = second == null ? null : second.id();
        return child;
    }

    public Collection<CitizenRecord> citizens() {
        return Collections.unmodifiableCollection(citizens.values());
    }

    public Collection<BuildingRecord> buildings() {
        return Collections.unmodifiableCollection(buildings.values());
    }

    public Collection<BlueprintRecord> blueprints() {
        return Collections.unmodifiableCollection(blueprints.values());
    }
    private final Map<UUID,BlueprintRecord> formalBlueprintIndex=new LinkedHashMap<>();
    private boolean formalBlueprintDirty=true;
    private List<BlueprintRecord> formalBlueprintView=List.of();
    /** Live records, so cancellation and geometry remain current. Terrain orders have their own guards. */
    public List<BlueprintRecord> formalBlueprints(){
        if(formalBlueprintDirty){
            formalBlueprintView=List.copyOf(formalBlueprintIndex.values());
            formalBlueprintDirty=false;
        }
        return formalBlueprintView;
    }
    private void trackBlueprint(BlueprintRecord b){
        if(b.terrainProject()==null){formalBlueprintIndex.put(b.id(),b);formalBlueprintDirty=true;}
        else if(formalBlueprintIndex.remove(b.id())!=null)formalBlueprintDirty=true;
        b.onTerrainKindChanged(()->{
            if(blueprints.get(b.id())!=b)return;
            if(b.terrainProject()!=null){
                if(formalBlueprintIndex.remove(b.id())!=null)formalBlueprintDirty=true;
            }else{
                // Rare reverse conversion retains the authoritative insertion order.
                formalBlueprintIndex.clear();
                for(var live:blueprints.values())if(live.terrainProject()==null)formalBlueprintIndex.put(live.id(),live);
                formalBlueprintDirty=true;
            }
        });
    }
    private void putBlueprint(BlueprintRecord b){blueprints.put(b.id(),b);trackBlueprint(b);}

    public int professionTarget(CitizenProfession profession) {
        if (profession.specialist()) {
            int buildingTotal = buildings.values().stream()
                    .filter(BuildingRecord::active)
                    .filter(building -> profession.acceptsWorkplace(building.type()))
                    .mapToInt(BuildingRecord::workerTarget)
                    .sum();
            if (buildingTotal > 0 || buildings.values().stream()
                    .anyMatch(building -> building.active() && profession.acceptsWorkplace(building.type()))) {
                return buildingTotal;
            }
            return 0;
        }
        return professionTargets.getOrDefault(profession, 0);
    }

    public long professionActual(CitizenProfession profession) {
        return citizens.values().stream()
                .filter(CitizenRecord::alive)
                .filter(citizen -> citizen.profession() == profession)
                .count();
    }

    public int professionCapacity(CitizenProfession profession) {
        if (!profession.specialist()) {
            return (int) aliveCitizenCount();
        }
        BuildingType workplaceType = profession.workplaceType();
        int workplaceCapacity = buildings.values().stream()
                .filter(BuildingRecord::active)
                .filter(BuildingRecord::operational)
                .filter(building -> profession.acceptsWorkplace(building.type()))
                .mapToInt(BuildingRecord::workerCapacity)
                .sum();
        return workplaceCapacity;
    }

    public boolean setProfessionTarget(CitizenProfession profession, int target) {
        if (profession.specialist()) return false;
        int normalized = Math.max(0, target);
        int previous = professionTargets.getOrDefault(profession, 0);
        if (previous == normalized) {
            return false;
        }
        professionTargets.put(profession, normalized);
        return true;
    }

    public Set<BlockPos> retiredBlockPositions() {
        return Collections.unmodifiableSet(retiredBlockPositions);
    }

    public boolean removeRetiredBlockPosition(BlockPos position) {
        return retiredBlockPositions.remove(position);
    }

    @Nullable
    public CitizenRecord citizen(UUID citizenId) {
        return citizens.get(citizenId);
    }

    @Nullable
    public BuildingRecord building(UUID buildingId) {
        return buildings.get(buildingId);
    }

    @Nullable
    public BuildingRecord buildingAt(BlockPos position) {
        return buildings.values().stream()
                .filter(BuildingRecord::active)
                .filter(building -> building.position().equals(position))
                .findFirst()
                .orElse(null);
    }

    @Nullable
    public BlueprintRecord blueprintAt(BlockPos position) {
        return blueprints.values().stream()
                .filter(blueprint -> blueprint.markerPosition().equals(position))
                .findFirst()
                .orElse(null);
    }

    @Nullable
    public BlueprintRecord blueprint(UUID blueprintId) {
        return blueprints.get(blueprintId);
    }

    public BlueprintRecord addBlueprint(
            BuildingType type,
            BlockPos anchor,
            BlockPos markerPosition,
            net.minecraft.core.Direction facing,
            UUID ownerId,
            boolean refundItem
    ) {
        BlueprintRecord existing = blueprintAt(markerPosition);
        if (existing != null) {
            return existing;
        }
        BlueprintRecord blueprint = BlueprintRecord.create(
                type,
                anchor,
                markerPosition,
                facing,
                ownerId,
                refundItem
        );
        putBlueprint(blueprint);
        return blueprint;
    }

    public BlueprintRecord addBlueprint(
            BuildingType type, BlockPos anchor, BlockPos markerPosition,
            net.minecraft.core.Direction facing, UUID ownerId, boolean refundItem,
            Map<BlockPos, BlockState> clearanceTargets
    ) {
        return addBlueprint(type, anchor, markerPosition, facing, ownerId, refundItem,
                BlueprintTemplates.currentTemplateVersion(type), clearanceTargets);
    }

    public BlueprintRecord addBlueprint(
            BuildingType type, BlockPos anchor, BlockPos markerPosition,
            net.minecraft.core.Direction facing, UUID ownerId, boolean refundItem,
            int templateVersion, Map<BlockPos, BlockState> clearanceTargets
    ) {
        BlueprintRecord existing = blueprintAt(markerPosition);
        if (existing != null) return existing;
        BlueprintRecord blueprint = BlueprintRecord.create(
                type, anchor, markerPosition, facing, ownerId, refundItem, templateVersion, clearanceTargets);
        putBlueprint(blueprint);
        return blueprint;
    }

    public BlueprintRecord addAreaBlueprint(
            BuildingType type, BlockPos anchor, BlockPos markerPosition,
            net.minecraft.core.Direction facing, UUID ownerId, boolean refundItem,
            BlockPos first, BlockPos second
    ) {
        BlueprintRecord existing = blueprintAt(markerPosition);
        if (existing != null) return existing;
        BlueprintRecord blueprint = BlueprintRecord.createArea(type, anchor, markerPosition, facing,
                ownerId, refundItem, first, second);
        putBlueprint(blueprint);
        return blueprint;
    }

    public BlueprintRecord addAreaBlueprint(
            BuildingType type, BlockPos anchor, BlockPos markerPosition,
            net.minecraft.core.Direction facing, UUID ownerId, boolean refundItem,
            BlockPos first, BlockPos second, Map<BlockPos, BlockState> clearanceTargets
    ) {
        return addAreaBlueprint(type, anchor, markerPosition, facing, ownerId, refundItem, first, second,
                BlueprintTemplates.currentTemplateVersion(type), clearanceTargets);
    }

    public BlueprintRecord addAreaBlueprint(
            BuildingType type, BlockPos anchor, BlockPos markerPosition,
            net.minecraft.core.Direction facing, UUID ownerId, boolean refundItem,
            BlockPos first, BlockPos second, int templateVersion, Map<BlockPos, BlockState> clearanceTargets
    ) {
        BlueprintRecord existing = blueprintAt(markerPosition);
        if (existing != null) return existing;
        BlueprintRecord blueprint = BlueprintRecord.createArea(type, anchor, markerPosition, facing,
                ownerId, refundItem, first, second, templateVersion, clearanceTargets);
        putBlueprint(blueprint);
        return blueprint;
    }

    public BlueprintRecord addBlueprint(
            BuildingType type,
            BlockPos anchor,
            net.minecraft.core.Direction facing,
            UUID ownerId,
            boolean refundItem
    ) {
        return addBlueprint(type, anchor, anchor, facing, ownerId, refundItem);
    }

    public boolean removeBlueprint(BlockPos anchor) {
        BlueprintRecord blueprint = blueprintAt(anchor);
        return blueprint != null && removeBlueprint(blueprint.id());
    }

    public boolean removeBlueprint(UUID blueprintId) {
        if (blueprints.remove(blueprintId) == null) return false;
        if(formalBlueprintIndex.remove(blueprintId)!=null)formalBlueprintDirty=true;
        citizens.values().forEach(citizen -> {
            if (blueprintId.equals(citizen.preferredWorksiteId())) citizen.setPreferredWorksite(null);
        });
        return true;
    }
    public BlueprintRecord resumeTerrain(com.hkin.exiledsettlement.terrain.TerrainProject project) {
        var existing=blueprint(project.id);if(existing!=null)return existing;
        var marker=project.anchor.above(96);while(blueprintAt(marker)!=null)marker=marker.above();
        var tag=BlueprintRecord.create(BuildingType.HOUSE,project.anchor,marker,net.minecraft.core.Direction.NORTH,ownerId,false).save();
        tag.putString("Id",project.id.toString());var site=BlueprintRecord.load(tag).withTerrainProject(project);putBlueprint(site);return site;
    }

    public long aliveCitizenCount() {
        return citizens.values().stream().filter(CitizenRecord::alive).count();
    }

    public long activeBuildingCount() {
        return buildings.values().stream().filter(BuildingRecord::active).count();
    }

    public long operationalBuildingCount() {
        return buildings.values().stream()
                .filter(BuildingRecord::active)
                .filter(BuildingRecord::operational)
                .count();
    }

    public boolean contains(BlockPos position) {
        ChunkPos targetChunk = new ChunkPos(position);
        return ownsChunk(targetChunk.x, targetChunk.z);
    }

    public boolean ownsChunk(int chunkX, int chunkZ) {
        ChunkPos coreChunk = new ChunkPos(corePosition);
        return (Math.abs(coreChunk.x - chunkX) <= boundaryRadiusChunks
                && Math.abs(coreChunk.z - chunkZ) <= boundaryRadiusChunks)
                || claimedChunkKeys.contains(ChunkPos.asLong(chunkX, chunkZ));
    }

    public boolean canClaimChunk(int chunkX, int chunkZ) {
        if (ownsChunk(chunkX, chunkZ)) return false;
        return ownsChunk(chunkX + 1, chunkZ) || ownsChunk(chunkX - 1, chunkZ)
                || ownsChunk(chunkX, chunkZ + 1) || ownsChunk(chunkX, chunkZ - 1);
    }

    public boolean claimChunk(int chunkX, int chunkZ) {
        return canClaimChunk(chunkX, chunkZ) && claimedChunkKeys.add(ChunkPos.asLong(chunkX, chunkZ));
    }

    public Set<Long> claimedChunkKeys() {
        return Set.copyOf(claimedChunkKeys);
    }

    public Map<net.minecraft.resources.ResourceLocation, Integer> inventorySample() {
        return Map.copyOf(inventorySample);
    }

    public Map<net.minecraft.resources.ResourceLocation, Integer> producedTotals() {
        return Map.copyOf(producedTotals);
    }

    public Map<net.minecraft.resources.ResourceLocation, Integer> consumedTotals() {
        return Map.copyOf(consumedTotals);
    }

    public boolean updateInventoryLedger(Map<net.minecraft.resources.ResourceLocation, Integer> current) {
        if (inventorySample.isEmpty()) {
            inventorySample.putAll(current);
            return !current.isEmpty();
        }
        Set<net.minecraft.resources.ResourceLocation> ids = new LinkedHashSet<>(inventorySample.keySet());
        ids.addAll(current.keySet());
        boolean changed = false;
        for (var id : ids) {
            int before = inventorySample.getOrDefault(id, 0);
            int after = current.getOrDefault(id, 0);
            if (after > before) producedTotals.merge(id, after - before, SettlementRecord::saturatingAdd);
            else if (before > after) consumedTotals.merge(id, before - after, SettlementRecord::saturatingAdd);
            changed |= before != after;
        }
        inventorySample.clear();
        inventorySample.putAll(current);
        return changed;
    }

    public long appointmentProductionDay() { return appointmentProductionDay; }

    public boolean markAppointmentProduced(long day) {
        if (day <= appointmentProductionDay) return false;
        appointmentProductionDay = day;
        return true;
    }

    private static int saturatingAdd(int left, int right) {
        long sum = (long) left + right;
        return (int)Math.min(Integer.MAX_VALUE, sum);
    }

    public boolean overlapsBoundary(BlockPos otherCorePosition, int otherRadiusChunks) {
        ChunkPos coreChunk = new ChunkPos(corePosition);
        ChunkPos otherChunk = new ChunkPos(otherCorePosition);
        int combinedRadius = boundaryRadiusChunks + otherRadiusChunks;
        return Math.abs(coreChunk.x - otherChunk.x) <= combinedRadius
                && Math.abs(coreChunk.z - otherChunk.z) <= combinedRadius;
    }

    public boolean isOwner(UUID playerId) {
        return ownerId.equals(playerId);
    }

    public boolean isMember(UUID playerId) {
        return isOwner(playerId) || memberIds.contains(playerId);
    }

    public SettlementRole roleOf(UUID playerId) {
        if (isOwner(playerId)) {
            return SettlementRole.OWNER;
        }
        return memberIds.contains(playerId) ? SettlementRole.MEMBER : SettlementRole.VISITOR;
    }

    public boolean addMember(UUID playerId) {
        return !isOwner(playerId) && memberIds.add(playerId);
    }

    public boolean removeMember(UUID playerId) {
        return memberIds.remove(playerId);
    }

    public boolean rename(String newName) {
        String normalized = newName.strip();
        if (normalized.isEmpty() || normalized.equals(name)) {
            return false;
        }
        name = normalized.substring(0, Math.min(normalized.length(), 48));
        return true;
    }

    public boolean activate() {
        if (active) {
            return false;
        }
        active = true;
        return true;
    }

    public boolean deactivate() {
        if (!active) {
            return false;
        }
        active = false;
        return true;
    }

    public BuildingRecord addBuilding(BuildingType type, BlockPos position) {
        BuildingRecord existing = buildingAt(position);
        if (existing != null) {
            return existing;
        }
        BuildingRecord building = BuildingRecord.create(type, position);
        buildings.put(building.id(), building);
        return building;
    }

    public BuildingRecord addBuilding(
            BuildingType type, BlockPos position, net.minecraft.core.Direction facing,
            BlockPos minCorner, BlockPos maxCorner, int transitionMargin
    ) {
        return addBuilding(type, position, facing, minCorner, maxCorner, transitionMargin, 1);
    }

    public BuildingRecord addBuilding(
            BuildingType type, BlockPos position, net.minecraft.core.Direction facing,
            BlockPos minCorner, BlockPos maxCorner, int transitionMargin, int templateVersion
    ) {
        BuildingRecord existing = buildingAt(position);
        if (existing != null) return existing;
        BuildingRecord building = BuildingRecord.create(
                type, position, facing, minCorner, maxCorner, transitionMargin, templateVersion);
        buildings.put(building.id(), building);
        return building;
    }

    public int lockedWorkers(UUID buildingId) {
        return (int) citizens.values().stream()
                .filter(CitizenRecord::alive)
                .filter(CitizenRecord::hasManualAssignment)
                .filter(citizen -> buildingId.equals(citizen.manualWorkBuildingId()))
                .count();
    }

    public boolean setBuildingWorkerTarget(UUID buildingId, int target, int expectedRevision) {
        BuildingRecord building = building(buildingId);
        if (building == null || !building.active()
                || CitizenProfession.forWorkplace(building.type()) == null
                || target < lockedWorkers(buildingId)) return false;
        return building.setWorkerTarget(target, expectedRevision);
    }

    public boolean removeBuilding(BlockPos position) {
        BuildingRecord building = buildingAt(position);
        if (building == null) {
            return false;
        }
        return finishBuildingDemolition(building.id());
    }

    public boolean queueBuildingDemolition(UUID buildingId) {
        BuildingRecord building = building(buildingId);
        if (building == null || building.type() == BuildingType.WAGON || !building.queueDemolition()) return false;
        return true | refreshHousingAssignments();
    }

    public boolean finishBuildingDemolition(UUID buildingId) {
        BuildingRecord building = building(buildingId);
        if (building == null || !building.active()) return false;
        if(com.hkin.exiledsettlement.building.WarehouseStock.applies(building) && !building.warehouseStock().isEmpty())return false;
        building.deactivate();
        boolean changed = true;
        for (CitizenRecord citizen : citizens.values()) {
            if (buildingId.equals(citizen.preferredWorksiteId())) {
                changed |= citizen.setPreferredWorksite(null);
            }
        }
        if (building.type().isHouse()) {
            for (CitizenRecord citizen : citizens.values()) {
                if (building.id().equals(citizen.homeBuildingId())) {
                    changed |= citizen.assignHome(null);
                }
            }
            changed |= refreshHousingAssignments();
        }
        return changed;
    }

    public boolean refreshHousingAssignments() {
        List<BuildingRecord> houses = buildings.values().stream()
                .filter(BuildingRecord::active)
                .filter(building -> building.type().isHouse())
                .filter(BuildingRecord::operational)
                .filter(building -> building.capacity() > 0)
                .toList();
        Map<UUID, List<BlockPos>> availableBeds = new LinkedHashMap<>();
        for (BuildingRecord house : houses) {
            availableBeds.put(house.id(), new ArrayList<>(house.servicePositions().stream()
                    .limit(house.capacity()).toList()));
        }
        boolean changed = false;

        // Preserve every still-valid personal bed before assigning any free bed.
        for (CitizenRecord citizen : citizens.values()) {
            UUID homeId = citizen.homeBuildingId();
            if (!citizen.alive()) {
                changed |= citizen.assignHome(null);
                continue;
            }
            if (homeId == null || citizen.bedPosition() == null) continue;
            BuildingRecord house = building(homeId);
            List<BlockPos> beds = availableBeds.get(homeId);
            if (house == null || beds == null || !beds.remove(citizen.bedPosition())) {
                changed |= citizen.assignHome(null);
            }
        }

        // A migrated resident without a bed first receives a free bed in the same home.
        for (CitizenRecord citizen : citizens.values()) {
            if (!citizen.alive() || citizen.bedPosition() != null) continue;
            UUID homeId = citizen.homeBuildingId();
            if (homeId != null) {
                List<BlockPos> beds = availableBeds.get(homeId);
                if (beds != null && !beds.isEmpty()) {
                    changed |= citizen.assignHome(homeId, beds.removeFirst());
                    continue;
                }
                changed |= citizen.assignHome(null);
            }
        }

        for (CitizenRecord citizen : citizens.values()) {
            if (!citizen.alive() || citizen.homeBuildingId() != null) continue;
            for (BuildingRecord house : houses) {
                List<BlockPos> beds = availableBeds.get(house.id());
                if (beds != null && !beds.isEmpty()) {
                    changed |= citizen.assignHome(house.id(), beds.removeFirst());
                    break;
                }
            }
        }
        return changed;
    }

    public int optimizeHousingAssignments() {
        List<BedSlot> beds = new ArrayList<>();
        buildings.values().stream()
                .filter(BuildingRecord::active)
                .filter(BuildingRecord::operational)
                .filter(building -> building.type().isHouse())
                .forEach(house -> house.servicePositions().stream().limit(house.capacity())
                        .forEach(position -> beds.add(new BedSlot(house.id(), position))));
        int changed = 0;
        for (CitizenRecord citizen : citizens.values().stream().filter(CitizenRecord::alive).toList()) {
            if (beds.isEmpty()) {
                if (citizen.assignHome(null)) changed++;
                continue;
            }
            BuildingRecord workplace = building(citizen.workBuildingId());
            BlockPos reference = workplace == null ? citizen.lastPosition() : workplace.position();
            BedSlot selected = beds.stream().min(Comparator.comparingDouble(slot -> slot.position().distSqr(reference)))
                    .orElseThrow();
            beds.remove(selected);
            if (citizen.assignHome(selected.houseId(), selected.position())) changed++;
        }
        return changed;
    }

    public long occupantsOf(UUID buildingId) {
        return citizens.values().stream()
                .filter(CitizenRecord::alive)
                .filter(citizen -> buildingId.equals(citizen.homeBuildingId()))
                .count();
    }

    public MerchantState merchantState() {
        return merchantState;
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString(ID_TAG, id.toString());
        tag.putString(NAME_TAG, name);
        tag.putLong(CORE_POSITION_TAG, corePosition.asLong());
        tag.putString(OWNER_TAG, ownerId.toString());
        tag.putBoolean(ACTIVE_TAG, active);
        tag.putInt(BOUNDARY_RADIUS_TAG, boundaryRadiusChunks);
        tag.putString(LEVEL_TAG, level.serializedName());
        tag.putInt(TOOL_STOCK_LIMIT_TAG, toolStockLimit);
        tag.putInt(EQUIPMENT_STOCK_LIMIT_TAG, equipmentStockLimit);
        tag.putBoolean(CREATIVE_CORE_INSTALLED_TAG, creativeCoreInstalled);
        tag.putBoolean(CHUNK_LOADING_ENABLED_TAG, chunkLoadingEnabled);
        tag.putLongArray(FORCED_CHUNKS_TAG, forcedChunkKeys.stream().mapToLong(Long::longValue).toArray());
        tag.putLongArray(ROAD_REPAIR_POSITIONS_TAG,
                roadRepairPositions.stream().mapToLong(BlockPos::asLong).toArray());
        tag.putLongArray(CLAIMED_CHUNKS_TAG, claimedChunkKeys.stream().mapToLong(Long::longValue).toArray());
        tag.put(INVENTORY_SAMPLE_TAG, saveItemCounts(inventorySample));
        tag.put(PRODUCED_TOTAL_TAG, saveItemCounts(producedTotals));
        tag.put(CONSUMED_TOTAL_TAG, saveItemCounts(consumedTotals));
        tag.putLong(APPOINTMENT_PRODUCTION_DAY_TAG, appointmentProductionDay);
        tag.put(MERCHANT_STATE_TAG, merchantState.save());
        tag.put("Community", community.save());
        tag.put("Terrain",terrain.save());
        tag.put("Decorations", decorations.save());
        tag.put("AutoRoads", autoRoads.save());
        tag.put("MobRecovery", mobRecovery.save());

        ListTag membersTag = new ListTag();
        memberIds.forEach(memberId -> membersTag.add(StringTag.valueOf(memberId.toString())));
        tag.put(MEMBERS_TAG, membersTag);

        ListTag citizensTag = new ListTag();
        citizens.values().forEach(citizen -> citizensTag.add(citizen.save(registries)));
        tag.put(CITIZENS_TAG, citizensTag);

        ListTag buildingsTag = new ListTag();
        buildings.values().forEach(building -> buildingsTag.add(building.save(registries)));
        tag.put(BUILDINGS_TAG, buildingsTag);

        ListTag blueprintsTag = new ListTag();
        blueprints.values().forEach(blueprint -> blueprintsTag.add(blueprint.save()));
        tag.put(BLUEPRINTS_TAG, blueprintsTag);
        CompoundTag targetsTag = new CompoundTag();
        for (CitizenProfession profession : CitizenProfession.values()) {
            targetsTag.putInt(profession.serializedName(), professionTarget(profession));
        }
        tag.put(PROFESSION_TARGETS_TAG, targetsTag);
        tag.putLongArray(RETIRED_BLOCKS_TAG, retiredBlockPositions.stream().mapToLong(BlockPos::asLong).toArray());
        return tag;
    }

    public static SettlementRecord load(CompoundTag tag, HolderLookup.Provider registries) {
        Set<UUID> members = new LinkedHashSet<>();
        ListTag membersTag = tag.getList(MEMBERS_TAG, Tag.TAG_STRING);
        for (int i = 0; i < membersTag.size(); i++) {
            members.add(UUID.fromString(membersTag.getString(i)));
        }

        Map<UUID, CitizenRecord> citizens = new LinkedHashMap<>();
        ListTag citizensTag = tag.getList(CITIZENS_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < citizensTag.size(); i++) {
            CitizenRecord citizen = CitizenRecord.load(citizensTag.getCompound(i), registries);
            citizens.put(citizen.id(), citizen);
        }

        Set<BlockPos> retiredBlockPositions = new LinkedHashSet<>();
        Arrays.stream(tag.getLongArray(RETIRED_BLOCKS_TAG))
                .mapToObj(BlockPos::of)
                .forEach(retiredBlockPositions::add);

        Map<UUID, BuildingRecord> buildings = new LinkedHashMap<>();
        ListTag buildingsTag = tag.getList(BUILDINGS_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < buildingsTag.size(); i++) {
            CompoundTag buildingTag = buildingsTag.getCompound(i);
            if ("stockpile".equals(buildingTag.getString("Type"))) {
                retiredBlockPositions.add(BlockPos.of(buildingTag.getLong("Position")));
                continue;
            }
            BuildingRecord building = BuildingRecord.load(buildingTag);
            if (!buildingTag.contains("MinCorner")) {
                retiredBlockPositions.add(building.position());
            }
            buildings.put(building.id(), building);
        }

        Map<UUID, BlueprintRecord> blueprints = new LinkedHashMap<>();
        ListTag blueprintsTag = tag.getList(BLUEPRINTS_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < blueprintsTag.size(); i++) {
            CompoundTag blueprintTag = blueprintsTag.getCompound(i);
            if ("stockpile".equals(blueprintTag.getString("Type"))) {
                retiredBlockPositions.add(BlockPos.of(blueprintTag.getLong("Anchor")));
                continue;
            }
            BlueprintRecord blueprint = BlueprintRecord.load(blueprintTag);
            blueprints.put(blueprint.id(), blueprint);
        }

        EnumMap<CitizenProfession, Integer> professionTargets = defaultProfessionTargets(
                (int) citizens.values().stream().filter(CitizenRecord::alive).count()
        );
        if (tag.contains(PROFESSION_TARGETS_TAG, Tag.TAG_COMPOUND)) {
            CompoundTag targetsTag = tag.getCompound(PROFESSION_TARGETS_TAG);
            for (CitizenProfession profession : CitizenProfession.values()) {
                if (targetsTag.contains(profession.serializedName())) {
                    professionTargets.put(
                            profession,
                            Math.max(0, targetsTag.getInt(profession.serializedName()))
                    );
                }
            }
        }

        SettlementRecord loaded = new SettlementRecord(
                UUID.fromString(tag.getString(ID_TAG)),
                tag.getString(NAME_TAG),
                BlockPos.of(tag.getLong(CORE_POSITION_TAG)),
                UUID.fromString(tag.getString(OWNER_TAG)),
                members,
                tag.getBoolean(ACTIVE_TAG),
                Math.max(1, tag.getInt(BOUNDARY_RADIUS_TAG)),
                citizens,
                buildings,
                blueprints,
                professionTargets,
                retiredBlockPositions,
                tag.contains(LEVEL_TAG, Tag.TAG_STRING)
                        ? SettlementLevel.fromSerializedName(tag.getString(LEVEL_TAG))
                        : SettlementLevel.CAMP,
                tag.contains(TOOL_STOCK_LIMIT_TAG) ? tag.getInt(TOOL_STOCK_LIMIT_TAG) : DEFAULT_STOCK_LIMIT,
                tag.contains(EQUIPMENT_STOCK_LIMIT_TAG) ? tag.getInt(EQUIPMENT_STOCK_LIMIT_TAG) : DEFAULT_STOCK_LIMIT
                , tag.getBoolean(CREATIVE_CORE_INSTALLED_TAG)
                , tag.getBoolean(CHUNK_LOADING_ENABLED_TAG)
                , Arrays.stream(tag.getLongArray(FORCED_CHUNKS_TAG)).boxed()
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                , Arrays.stream(tag.getLongArray(ROAD_REPAIR_POSITIONS_TAG)).mapToObj(BlockPos::of)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                , Arrays.stream(tag.getLongArray(CLAIMED_CHUNKS_TAG)).boxed()
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                , loadItemCounts(tag.getCompound(INVENTORY_SAMPLE_TAG))
                , loadItemCounts(tag.getCompound(PRODUCED_TOTAL_TAG))
                , loadItemCounts(tag.getCompound(CONSUMED_TOTAL_TAG))
                , tag.contains(APPOINTMENT_PRODUCTION_DAY_TAG) ? tag.getLong(APPOINTMENT_PRODUCTION_DAY_TAG) : -1L
        );
        loaded.migrateLegacyWorkerTargets();
        loaded.community.load(tag.getCompound("Community"));
        loaded.terrain.load(tag.getCompound("Terrain"));
        for(var site:loaded.blueprints())if(site.terrainProject()!=null) {
            var shared=loaded.terrain.projects.get(site.id());
            if(shared!=null)site.withTerrainProject(shared);else loaded.terrain.index(site.terrainProject());
        }
        loaded.decorations.load(tag.getCompound("Decorations"));
        loaded.autoRoads.load(tag.getCompound("AutoRoads"));
        loaded.mobRecovery.load(tag.getCompound("MobRecovery"));
        if (tag.contains(MERCHANT_STATE_TAG, Tag.TAG_COMPOUND)) {
            loaded.merchantState.load(tag.getCompound(MERCHANT_STATE_TAG));
        }
        return loaded;
    }

    private static CompoundTag saveItemCounts(Map<net.minecraft.resources.ResourceLocation, Integer> values) {
        CompoundTag tag = new CompoundTag();
        values.forEach((id, count) -> tag.putInt(id.toString(), Math.max(0, count)));
        return tag;
    }

    private static Map<net.minecraft.resources.ResourceLocation, Integer> loadItemCounts(CompoundTag tag) {
        Map<net.minecraft.resources.ResourceLocation, Integer> values = new LinkedHashMap<>();
        for (String key : tag.getAllKeys()) {
            net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(key);
            if (id != null && tag.getInt(key) > 0) values.put(id, tag.getInt(key));
        }
        return values;
    }

    private void migrateLegacyWorkerTargets() {
        for (BuildingRecord building : buildings.values()) {
            if (CitizenProfession.forWorkplace(building.type()) == null && !building.workerTargetInitialized()) {
                building.migrateWorkerTarget(0);
            }
        }
        for (CitizenProfession profession : CitizenProfession.values()) {
            if (!profession.specialist()) continue;
            List<BuildingRecord> workplaces = buildings.values().stream()
                    .filter(BuildingRecord::active)
                    .filter(building -> profession.acceptsWorkplace(building.type()))
                    .sorted(Comparator.comparing(building -> building.id().toString()))
                    .toList();
            if (workplaces.isEmpty() || workplaces.stream().allMatch(BuildingRecord::workerTargetInitialized)) continue;
            int desired = Math.max(0, professionTargets.getOrDefault(profession, 0));
            int assigned = 0;
            for (BuildingRecord workplace : workplaces) {
                int locked = (int) citizens.values().stream()
                        .filter(CitizenRecord::alive)
                        .filter(citizen -> citizen.hasManualAssignment()
                                && profession == citizen.manualProfession()
                                && workplace.id().equals(citizen.manualWorkBuildingId()))
                        .count();
                int current = (int) citizens.values().stream()
                        .filter(CitizenRecord::alive)
                        .filter(citizen -> profession == citizen.profession()
                                && workplace.id().equals(citizen.workBuildingId()))
                        .count();
                int preserved = Math.min(workplace.workerCapacity(), Math.max(locked, current));
                workplace.migrateWorkerTarget(preserved);
                assigned += preserved;
            }
            int remaining = Math.max(0, desired - assigned);
            for (BuildingRecord workplace : workplaces) {
                if (remaining <= 0) break;
                int add = Math.min(remaining, Math.max(0, workplace.workerCapacity() - workplace.workerTarget()));
                workplace.migrateWorkerTarget(workplace.workerTarget() + add);
                remaining -= add;
            }
        }
    }

    private static int normalizeStockLimit(int value) {
        return Math.max(0, Math.min(MAX_STOCK_LIMIT, value));
    }

    private record BedSlot(UUID houseId, BlockPos position) {
    }

    private static EnumMap<CitizenProfession, Integer> defaultProfessionTargets(int aliveCitizens) {
        EnumMap<CitizenProfession, Integer> targets = new EnumMap<>(CitizenProfession.class);
        for (CitizenProfession profession : CitizenProfession.values()) {
            targets.put(profession, profession == CitizenProfession.WORKER ? Math.max(0, aliveCitizens) : 0);
        }
        return targets;
    }
}
