package com.hkin.exiledsettlement.citizen;
import com.hkin.exiledsettlement.blueprint.BlueprintPlacementGroups;
import com.hkin.exiledsettlement.blueprint.ConstructionOccupancy;
import com.hkin.exiledsettlement.terrain.FoundationPlacement;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import com.hkin.exiledsettlement.decoration.*;
import com.hkin.exiledsettlement.blueprint.BlueprintRecord;
import com.hkin.exiledsettlement.blueprint.BlueprintCancellationService;
import com.hkin.exiledsettlement.blueprint.BlueprintPlacement;
import com.hkin.exiledsettlement.blueprint.BlueprintPlacementMode;
import com.hkin.exiledsettlement.blueprint.BlueprintService;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.blueprint.BuildingBlueprintTemplate;
import com.hkin.exiledsettlement.blueprint.ConstructionPlanService;
import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingDemolitionService;
import com.hkin.exiledsettlement.building.BuildingRepairService;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.building.WorksiteAssignmentMode;
import com.hkin.exiledsettlement.debug.ConstructionDebugService;
import com.hkin.exiledsettlement.building.BuildingProductionService;
import com.hkin.exiledsettlement.building.SettlementInventoryService;
import com.hkin.exiledsettlement.building.WarehouseInventoryService;
import com.hkin.exiledsettlement.building.WarehouseConstructionSupply;
import com.hkin.exiledsettlement.building.WarehouseCategory;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.common.CommonHooks;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class CitizenWorkService {
    public static final int WORK_INTERVAL_TICKS = 8;
    public static final int FARM_ACTION_INTERVAL_TICKS = 10;
    private static final int TRANSFER_STACK_LIMIT = 64;
    private static final int MAX_DEMOLISHERS_PER_SITE = 2;
    private static final int DEMOLITION_BATCH_SIZE = 1;
    private static final int MAX_LOCAL_CONSTRUCTION_ACTIONS_PER_CYCLE = 64;
    // A single stack is not enough for a large roof plus its return route.
    // Builders own a 36-slot inventory and should load a meaningful work batch
    // before climbing instead of exhausting one stack at height and spending
    // most of the day descending for another trip. Four stacks remain a bounded
    // reservation and leave ample room for the actual blueprint materials,
    // tools, armor and recovered blocks.
    private static final int CONSTRUCTION_FOOTING_RESERVE = 64;
    private static final int CONSTRUCTION_SOLID_FOOTING_RESERVE = 32;
    // Sixty-four nodes only proves a very small local fan around the resident.
    // Once a roof/eave closes part of that fan, a valid scaffold route around
    // the obstacle can exist but never enter the queue before the budget is
    // exhausted. Keep this bounded well below the old unbounded/2048-node
    // search, while giving ordinary house-sized templates enough room to prove
    // one complete, reusable route instead of growing dozens of rejected spurs.
    private static final int MAX_PLANNED_FOOTING_NODES = 128;
    private static final int MAX_FOOTING_CANDIDATES_PER_CYCLE = 4;
    private static final int MAX_PLANNED_FOOTING_ROUTE_DEPTH = 24;
    private static final int MAX_REUSED_FOOTING_ROUTE_DEPTH = 12;
    private static final int MAX_FOOTING_BASE_SEARCH_RADIUS = 16;
    private static final int SAWMILL_LOG_BATCH_LIMIT = 256;
    // Eight full stacks, rather than two, before a routine crop delivery.
    private static final double PUBLIC_ITEM_SEARCH_RADIUS = 12.0D;
    private static final long FARM_TARGET_RETRY_DELAY_TICKS = 200L;
    // Tree-farm work is an area action. Residents need a safe nearby foothold,
    // not one of the four cells at exactly the trunk base's height.
    private static final double FORESTRY_WORK_RADIUS_SQUARED = 64.0D;
    private static final long FORESTRY_TARGET_REFRESH_TICKS = 200L;
    private static final int FORESTRY_SCAN_LOG_LIMIT = 4096;
    private static final int FORESTRY_HARVEST_BATCH = 256;
    private static final Map<ServerLevel, Map<UUID, ForestryTargets>> FORESTRY_TARGETS = new java.util.WeakHashMap<>();
    private static final Map<ServerLevel, Map<UUID, Map<BlockPos, Long>>> DEFERRED_FORESTRY_TARGETS =
            new java.util.WeakHashMap<>();
    private record ForestryTargets(long expiresAt, List<BlockPos> trunks) { }
    private record ForestryPlantingPlan(BlockState state, List<BlockPos> positions) { }
    private static final long CONSTRUCTION_TARGET_RETRY_DELAY_TICKS = 200L;
    // Construction uses one normal, unobstructed four-block interaction boundary.
    // Efficiency must come from reachable stations and scaffolding, not long reach.
    private static final double MAX_CONSTRUCTION_REACH = 4.0D;
    private static final double PREFERRED_CONSTRUCTION_REACH = 4.0D;
    private static final double LOCAL_CONSTRUCTION_BATCH_REACH = 4.0D;
    private static final double WALK_SPEED = 0.65D;
    private static final double INTERACTION_DISTANCE_SQUARED = 6.25D;
    private static final long WORK_START_TICK = 1000L;
    private static final long WORK_END_TICK = 12000L;
    private static final long SLEEP_START_TICK = 14000L;
    private static final long SLEEP_END_TICK = 22000L;

    /** Shared daily rhythm for every profession; survival and self-defence remain interruptible. */
    public enum SchedulePhase { WORK, REST, SLEEP }
    private static final Set<Item> SAFE_FOOTING_ITEMS = Set.of(
            Items.SCAFFOLDING,
            Items.COBBLESTONE, Items.STONE, Items.DIRT, Items.COARSE_DIRT,
            Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS,
            Items.DARK_OAK_PLANKS, Items.CHERRY_PLANKS, Items.ACACIA_PLANKS,
            Items.JUNGLE_PLANKS, Items.MANGROVE_PLANKS, Items.STONE_BRICKS,
            Items.MOSSY_COBBLESTONE, Items.BRICKS
    );
    private static final Map<UUID, Map<BlockPos, Long>> DEFERRED_FARM_TARGETS = new LinkedHashMap<>();
    private static final Map<Item, Item> SAWMILL_RECIPES = Map.ofEntries(
            Map.entry(Items.OAK_LOG, Items.OAK_PLANKS), Map.entry(Items.OAK_WOOD, Items.OAK_PLANKS),
            Map.entry(Items.STRIPPED_OAK_LOG, Items.OAK_PLANKS), Map.entry(Items.STRIPPED_OAK_WOOD, Items.OAK_PLANKS),
            Map.entry(Items.SPRUCE_LOG, Items.SPRUCE_PLANKS), Map.entry(Items.SPRUCE_WOOD, Items.SPRUCE_PLANKS),
            Map.entry(Items.STRIPPED_SPRUCE_LOG, Items.SPRUCE_PLANKS), Map.entry(Items.STRIPPED_SPRUCE_WOOD, Items.SPRUCE_PLANKS),
            Map.entry(Items.BIRCH_LOG, Items.BIRCH_PLANKS), Map.entry(Items.BIRCH_WOOD, Items.BIRCH_PLANKS),
            Map.entry(Items.STRIPPED_BIRCH_LOG, Items.BIRCH_PLANKS), Map.entry(Items.STRIPPED_BIRCH_WOOD, Items.BIRCH_PLANKS),
            Map.entry(Items.JUNGLE_LOG, Items.JUNGLE_PLANKS), Map.entry(Items.JUNGLE_WOOD, Items.JUNGLE_PLANKS),
            Map.entry(Items.STRIPPED_JUNGLE_LOG, Items.JUNGLE_PLANKS), Map.entry(Items.STRIPPED_JUNGLE_WOOD, Items.JUNGLE_PLANKS),
            Map.entry(Items.ACACIA_LOG, Items.ACACIA_PLANKS), Map.entry(Items.ACACIA_WOOD, Items.ACACIA_PLANKS),
            Map.entry(Items.STRIPPED_ACACIA_LOG, Items.ACACIA_PLANKS), Map.entry(Items.STRIPPED_ACACIA_WOOD, Items.ACACIA_PLANKS),
            Map.entry(Items.DARK_OAK_LOG, Items.DARK_OAK_PLANKS), Map.entry(Items.DARK_OAK_WOOD, Items.DARK_OAK_PLANKS),
            Map.entry(Items.STRIPPED_DARK_OAK_LOG, Items.DARK_OAK_PLANKS), Map.entry(Items.STRIPPED_DARK_OAK_WOOD, Items.DARK_OAK_PLANKS),
            Map.entry(Items.MANGROVE_LOG, Items.MANGROVE_PLANKS), Map.entry(Items.MANGROVE_WOOD, Items.MANGROVE_PLANKS),
            Map.entry(Items.STRIPPED_MANGROVE_LOG, Items.MANGROVE_PLANKS), Map.entry(Items.STRIPPED_MANGROVE_WOOD, Items.MANGROVE_PLANKS),
            Map.entry(Items.CHERRY_LOG, Items.CHERRY_PLANKS), Map.entry(Items.CHERRY_WOOD, Items.CHERRY_PLANKS),
            Map.entry(Items.STRIPPED_CHERRY_LOG, Items.CHERRY_PLANKS), Map.entry(Items.STRIPPED_CHERRY_WOOD, Items.CHERRY_PLANKS),
            Map.entry(Items.CRIMSON_STEM, Items.CRIMSON_PLANKS), Map.entry(Items.CRIMSON_HYPHAE, Items.CRIMSON_PLANKS),
            Map.entry(Items.STRIPPED_CRIMSON_STEM, Items.CRIMSON_PLANKS), Map.entry(Items.STRIPPED_CRIMSON_HYPHAE, Items.CRIMSON_PLANKS),
            Map.entry(Items.WARPED_STEM, Items.WARPED_PLANKS), Map.entry(Items.WARPED_HYPHAE, Items.WARPED_PLANKS),
            Map.entry(Items.STRIPPED_WARPED_STEM, Items.WARPED_PLANKS), Map.entry(Items.STRIPPED_WARPED_HYPHAE, Items.WARPED_PLANKS)
    );

    private CitizenWorkService() {
    }

    @Nullable
    public static Item sawmillOutput(Item log) {
        return SAWMILL_RECIPES.get(log);
    }

    public static int sawmillLogBatchLimit() {
        return SAWMILL_LOG_BATCH_LIMIT;
    }

    public static Set<Item> carpenterPlanks() {
        return Set.copyOf(SAWMILL_RECIPES.values());
    }

    // Live scheduling is staggered; explicit single-settlement acceptance calls retain one full cycle.
    private static boolean staggerWork;
    private static boolean staggerConstruction;
    private static boolean workDue(ServerLevel level,CitizenRecord citizen) {
        return !staggerWork || com.hkin.exiledsettlement.util.TickCadence.due(level.getGameTime(),citizen.id(),WORK_INTERVAL_TICKS,0);
    }
    public static void tickLevel(ServerLevel level) {
        SettlementSavedData data = SettlementSavedData.get(level);
        staggerWork=true;
        try {
            for (SettlementRecord settlement : data.settlements()) {
                if (!settlement.active() || !level.hasChunkAt(settlement.corePosition())) continue;
                tickSettlement(level, data, settlement);
            }
        } finally {staggerWork=false;}
    }

    /** Executes one bounded work-scheduler cycle for a single loaded settlement. */
    public static void tickSettlement(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement
    ) {
        if(!staggerWork || level.getGameTime()%WORK_INTERVAL_TICKS==0) {
            com.hkin.exiledsettlement.settlement.CommunityLifeService.prepareWork(level, data, settlement);
            handoffChangedConstructionSites(level, data, settlement);
            if (ConstructionDebugService.isEnabled(level, settlement.id())) {
                suspendBlueprintWorkForDebug(data, settlement);
            }
            updateFarmGateAccess(level, settlement);
            updatePastureGateAccess(level, settlement);
        }
        releasePendingWorksiteAssignments(level, data, settlement);
        wakeResidents(level, data, settlement);
        pauseExistingForNight(level, data, settlement);
        pauseExistingForRest(level, data, settlement);
        prioritizeDemolition(level, data, settlement);
        processExisting(level, data, settlement);
        assignRestLogistics(level, data, settlement);
        assignWork(level, data, settlement);
        for(var c:settlement.citizens())if(workDue(level,c))WorkTaskService.bind(settlement,c);
        routeIdleResidents(level, data, settlement);
    }

    private static final Map<CitizenRecord, UUID> LAST_MATERIAL_SITE = new java.util.WeakHashMap<>();

    private static void handoffChangedConstructionSites(ServerLevel level, SettlementSavedData data, SettlementRecord settlement) {
        for (CitizenRecord donor : settlement.citizens()) {
            UUID current = donor.targetBlueprintId();
            if (!donor.alive() || current == null) continue;
            UUID previous = LAST_MATERIAL_SITE.put(donor, current);
            if (previous == null || previous.equals(current)) continue;
            BlueprintRecord old = settlement.blueprint(previous);
            if (old == null || old.cancellationQueued()) continue;
            Map<Item, Integer> need = new HashMap<>();
            for (var p : BlueprintService.pendingPlacements(level, old, Set.of())) {
                if (p.placement().material() != null) need.merge(p.placement().material(), 1, Integer::sum);
            }
            var receivers = settlement.citizens().stream().filter(c -> c != donor && c.alive()
                    && previous.equals(c.targetBlueprintId())).toList();
            for (CitizenRecord receiver : receivers) for (int slot = 0; slot < CitizenRecord.INVENTORY_SIZE; slot++) {
                ItemStack stack = receiver.inventoryItem(slot);
                need.computeIfPresent(stack.getItem(), (item, count) -> Math.max(0, count - stack.getCount()));
            }
            for (int slot = 0; slot < CitizenRecord.INVENTORY_SIZE; slot++) {
                ItemStack stack = donor.inventoryItem(slot);
                if (stack.isEmpty() || CitizenEquipmentService.isReservedEquipment(donor, slot)) continue;
                for (CitizenRecord receiver : receivers) {
                    int amount = Math.min(stack.getCount(), Math.min(need.getOrDefault(stack.getItem(), 0), receiver.inventoryCapacityFor(stack)));
                    if (amount <= 0) continue;
                    Item material = stack.getItem();
                    ItemStack extracted = donor.extractInventoryItem(s -> ItemStack.isSameItemSameComponents(s, stack), amount);
                    ItemStack remainder = receiver.insertIntoInventory(extracted);
                    int moved = extracted.getCount() - remainder.getCount();
                    if (!remainder.isEmpty()) donor.insertIntoInventory(remainder);
                    stack.shrink(moved);
                    need.computeIfPresent(material, (item, count) -> count - moved);
                    data.touch();
                }
            }
        }
    }

    /** Safe work-cycle boundary: never interrupts an in-flight scaffold movement or an item transfer. */
    public static void releasePendingWorksiteAssignments(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement
    ) {
        for (CitizenRecord citizen : settlement.citizens()) {
            if(!workDue(level,citizen))continue;
            if (!WorksiteAssignmentService.releasePending(settlement, citizen)) continue;
            UUID worksiteId = citizen.preferredWorksiteId();
            boolean currentSiteTask = WorksiteAssignmentService.currentTaskBelongsTo(citizen, worksiteId);
            SettlementCitizenEntity entity = CitizenProjectionService.findProjection(level, citizen);
            if (currentSiteTask && (entity == null || entity.isDefending()
                    || entity.hasActiveConstructionMovement()
                    || !entity.onGround() && !entity.onClimbable())) continue;
            if (!WorksiteAssignmentService.finishRelease(settlement, citizen)) continue;
            if (currentSiteTask) {
                clear(data, citizen, entity);
                if (assignPersonalDelivery(level, settlement, citizen)) syncHeldItem(citizen, entity);
            }
            data.touch();
        }
    }

    private static void suspendBlueprintWorkForDebug(
            SettlementSavedData data, SettlementRecord settlement
    ) {
        boolean changed = false;
        for (CitizenRecord citizen : settlement.citizens()) {
            boolean blueprintTask = citizen.workState() == CitizenWorkState.TO_BLUEPRINT
                    || citizen.workState() == CitizenWorkState.TO_CONSTRUCTION_SUPPLY
                    || citizen.workState() == CitizenWorkState.TO_FOOTING_SUPPLY;
            if (blueprintTask) changed |= citizen.clearWork();
        }
        if (changed) data.touch();
    }

    /** Keeps staffed farm entrances traversable for direct pathfinding into and out of fenced fields. */
    public static void updateFarmGateAccess(ServerLevel level, SettlementRecord settlement) {
        for (BuildingRecord farm : settlement.buildings()) {
            if (!usable(farm) || (farm.type() != BuildingType.FARM_FIELD
                    && farm.type() != BuildingType.MELON_FIELD && farm.type() != BuildingType.TREE_FARM)) continue;
            CitizenProfession farmerProfession = farm.type() == BuildingType.MELON_FIELD
                    ? CitizenProfession.MELON_FARMER : farm.type() == BuildingType.TREE_FARM
                    ? CitizenProfession.LUMBERJACK : CitizenProfession.FARMER;
            boolean staffed = settlement.citizens().stream()
                    .anyMatch(citizen -> citizen.alive()
                            && (citizen.profession() == farmerProfession && farm.id().equals(citizen.workBuildingId())
                            || farm.region()!=null && farm.region().cell(citizen.lastPosition())!=null));
            for (BlueprintPlacement placement : BlueprintTemplates.createForBuilding(farm).gatePlacements()) {
                BlockPos gatePosition = placement.position();
                if (!level.hasChunkAt(gatePosition)) continue;
                BlockState state = level.getBlockState(gatePosition);
                if (!(state.getBlock() instanceof FenceGateBlock)
                        || state.getValue(FenceGateBlock.OPEN) == staffed) continue;
                level.setBlock(gatePosition, state.setValue(FenceGateBlock.OPEN, staffed), Block.UPDATE_ALL);
            }
        }
    }

    /** Keeps at most one pasture entrance group open while an animal is being escorted inside. */
    public static void updatePastureGateAccess(ServerLevel level, SettlementRecord settlement) {
        for (BuildingRecord pasture : settlement.buildings()) {
            if (!usable(pasture, BuildingType.PASTURE_AREA)) continue;
            if(Math.floorMod(level.getGameTime()/10+pasture.id().hashCode(),20)==0)
                com.hkin.exiledsettlement.region.PastureGroundSafety.remember(level,pasture.region());
            com.hkin.exiledsettlement.region.PastureResidentService.reconcile(level,settlement,pasture);
            BuildingBlueprintTemplate template = BlueprintTemplates.createForBuilding(pasture);
            BlockPos activeGate=null;
            for(var resident:settlement.citizens())if(resident.alive() && resident.workState()!=CitizenWorkState.IDLE && resident.targetPosition()!=null){
                var goal=resident.targetPosition();
                if(resident.workState()==CitizenWorkState.TO_CAPTURE){
                    var animal=level.getEntity(resident.targetItemEntityId());var holder=CitizenProjectionService.findProjection(level,resident);
                    if(animal instanceof net.minecraft.world.entity.animal.Animal a && (!a.isLeashed() || a.getLeashHolder()!=holder))goal=a.blockPosition();
                }
                boolean from=pasture.region()!=null?pasture.region().cell(resident.lastPosition())!=null:insideBuildingArea(pasture,resident.lastPosition(),0);
                boolean into=pasture.region()!=null?pasture.region().cell(goal)!=null:insideBuildingArea(pasture,goal,0);
                if(from==into)continue;
                var destination=goal;var start=resident.lastPosition();
                activeGate=template.entrances().stream().map(com.hkin.exiledsettlement.blueprint.BlueprintEntrance::position)
                        .min(Comparator.comparingDouble(p->Math.sqrt(p.distSqr(start))+Math.sqrt(p.distSqr(destination)))).orElse(null);
                break;
            }
            for (BlueprintPlacement placement : template.gatePlacements()) {
                BlockPos gatePosition = placement.position();
                if (!level.hasChunkAt(gatePosition)) continue;
                boolean open = activeGate != null && gatePosition.distManhattan(activeGate) <= 1
                        || !level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new AABB(gatePosition).inflate(.2),a->a.isAlive()).isEmpty();
                BlockState state = level.getBlockState(gatePosition);
                if (!(state.getBlock() instanceof FenceGateBlock)
                        || state.getValue(FenceGateBlock.OPEN) == open) continue;
                level.setBlock(gatePosition, state.setValue(FenceGateBlock.OPEN, open), Block.UPDATE_ALL);
            }
        }
    }

    private static void processExisting(ServerLevel level, SettlementSavedData data, SettlementRecord settlement) {
        for (CitizenRecord citizen : settlement.citizens()) {
            if(!workDue(level,citizen))continue;
            if (!citizen.alive() || citizen.workState() == CitizenWorkState.IDLE) continue;
            if (schedulePhase(level.getDayTime(), citizen) != SchedulePhase.WORK
                    && citizen.workState() != CitizenWorkState.TO_DEMOLITION
                    && !ResidentEgressService.active(settlement,citizen)
                    && !(schedulePhase(level.getDayTime(), citizen) == SchedulePhase.REST
                         && (citizen.workState() == CitizenWorkState.TO_WAREHOUSE
                             || citizen.workState() == CitizenWorkState.TO_EQUIPMENT
                             || mineRestCollection(level,settlement,citizen)))) continue;
            if (com.hkin.exiledsettlement.settlement.CommunityLifeService.handles(citizen)) continue;
            SettlementCitizenEntity entity = CitizenProjectionService.findProjection(level, citizen);
            if (entity == null) continue;
            var decorTask = settlement.decorations().owned(citizen.targetBlueprintId());
            if (CitizenInteractionService.held(entity)) continue;
            if (releaseUnauthorizedConstruction(level, data, settlement, citizen, entity)) continue;
            var decorRepair = citizen.workState() == CitizenWorkState.TO_REPAIR || citizen.workState() == CitizenWorkState.TO_REPAIR_SUPPLY
                    ? DecorationService.forBuilding(settlement, citizen.workState() == CitizenWorkState.TO_REPAIR_SUPPLY
                            ? citizen.sourceBuildingId() : citizen.targetBuildingId()) : null;
            if ((decorTask != null && citizen.workState() != CitizenWorkState.TO_DEMOLITION && !DecorationService.allowed(settlement, decorTask))
                    || (decorRepair != null && !DecorationService.allowed(settlement, decorRepair))) {
                clear(data, citizen, entity); continue;
            }
            if (entity.isDefending()) {
                setActivity(data, citizen, entity, CitizenActivity.SELF_DEFENSE, "");
                continue;
            }
            if (com.hkin.exiledsettlement.settlement.CommunityLifeService.pausesConstruction(settlement, citizen)
                    && !entity.hasActiveConstructionMovement() && entity.onGround()) {
                clear(data, citizen, entity);
                continue;
            }
            resumeRegionPitFill(level,data,settlement,citizen,entity);
            if(PitEscapeService.tick(level,data,settlement,citizen,entity))continue;
            if (pauseForCargoDelivery(level, data, settlement, citizen, entity)) continue;
            if (yieldStalledEngineering(level, data, settlement, citizen, entity)) continue;
            if(WorkerAccessService.tick(level,data,settlement,citizen,entity))continue;
            switch (citizen.workState()) {
                case TO_EQUIPMENT -> fetchEquipment(level, data, settlement, citizen, entity);
                case TO_ROAD, TO_ROAD_TOOL -> com.hkin.exiledsettlement.road.AutoRoadService.process(level, data, settlement, citizen, entity);
                case TO_OUTPUT_CONTAINER -> collectOutput(level, data, settlement, citizen, entity);
                case TO_WAREHOUSE -> deliverWarehouse(level, data, settlement, citizen, entity);
                case TO_FARM_SUPPLY -> fetchFarmInput(level, data, settlement, citizen, entity);
                case TO_FARM -> farm(level, data, settlement, citizen, entity);
                case TO_CONSTRUCTION_SUPPLY -> fetchConstructionMaterial(level, data, settlement, citizen, entity);
                case TO_FOOTING_SUPPLY -> fetchConstructionMaterial(level, data, settlement, citizen, entity);
                case TO_BLUEPRINT -> build(level, data, settlement, citizen, entity);
                case TO_REPAIR_SUPPLY -> fetchRepairMaterial(level, data, settlement, citizen, entity);
                case TO_REPAIR -> repair(level, data, settlement, citizen, entity);
                case TO_DEMOLITION -> {
                    advanceDemolitionOnCadence(level, data, settlement, citizen, entity);
                }
                case TO_HERD -> herd(level, data, settlement, citizen, entity);
                case TO_HUNT -> hunt(level, data, settlement, citizen, entity);
                case TO_CAPTURE -> capture(level, data, settlement, citizen, entity);
                case TO_TOOL_WORKSHOP -> makeTool(level, data, settlement, citizen, entity);
                case TO_FOREST -> forestry(level, data, settlement, citizen, entity);
                case TO_FISH -> fish(level, data, settlement, citizen, entity);
                case TO_MINE -> mine(level, data, settlement, citizen, entity);
                case TO_MINE_STORAGE -> storeMineCargo(level, data, settlement, citizen, entity);
                case TO_SAWMILL_SUPPLY -> fetchSawmillInput(level, data, settlement, citizen, entity);
                case TO_SAWMILL -> sawLogs(level, data, settlement, citizen, entity);
                case TO_PUBLIC_ITEM -> collectPublicItem(level, data, settlement, citizen, entity);
                case IDLE -> {
                }
            }
        }
    }

    private static void assignWork(ServerLevel level, SettlementSavedData data, SettlementRecord settlement) {
        boolean constructionAllowed = com.hkin.exiledsettlement.debug.ForceWorkDebugService.enabled(level,settlement) || !settlement.community().policy.equals("emergency");
        // Yield an optional public goal at the real action boundary. Carried materials remain in the backpack.
        if(constructionAllowed)for(var c:settlement.citizens()) {
            if(!workDue(level,c))continue;
            var t=WorkTaskService.task(settlement,c);var worker=CitizenProjectionService.findProjection(level,c);
            boolean extra=t!=null && t.priority()==0 && settlement.blueprint(t.mother())!=null
                    && WorkTaskService.assigned(settlement,t.mother())>WorkTaskService.defaultWorkers(settlement.blueprint(t.mother()));
            if(t==null || t.priority()!=2 && com.hkin.exiledsettlement.building.MobRecoveryService.task(settlement,c)==null && !extra || !constructionCapable(c.profession()) || !c.canWork() || c.workerAccess.block!=null
                    || c.preferredWorksiteId()!=null || schedulePhase(level.getDayTime(),c)!=SchedulePhase.WORK
                    || com.hkin.exiledsettlement.settlement.CommunityLifeService.handles(c) || worker==null
                    || !worker.onGround() || worker.hasActiveConstructionMovement() || CitizenInteractionService.held(worker))continue;
            var claimed=new HashSet<BlockPos>();for(var other:settlement.citizens())if(other!=c && other.targetPosition()!=null)claimed.add(other.targetPosition());
            boolean ready=false;
            for(var b:settlement.blueprints())if(WorkTaskService.priority(settlement,b)==0
                    && !siteDeferred(level,c,b.id())
                    && b.assignmentMode()==WorksiteAssignmentMode.AUTO && com.hkin.exiledsettlement.terrain.TerrainService.allowed(settlement,b)) {
                if(WorkTaskService.building(settlement,b) && WorkTaskService.assigned(settlement,b.id())>=WorkTaskService.defaultWorkers(b))continue;
                ready=BlueprintService.pendingPlacements(level,b,claimed,c.lastPosition()).stream()
                        .anyMatch(p->WorkTaskService.priorityAt(settlement,b,p.placement().position())==0
                                && WorkTaskService.underfilled(settlement,b,p.placement().position())
                                && !worker.constructionTargetDeferred(p.placement().position())
                                && constructionSupplyAvailable(level,settlement,c,b,p.placement()));
                if(ready)break;
            }
            if(!ready && t.priority()==2)ready=com.hkin.exiledsettlement.building.MobRecoveryService.candidate(level,settlement,c,claimed)!=null;
            if(ready)clear(data,c,worker);
        }
        Set<UUID> claimedEntities = new HashSet<>();
        Set<BlockPos> claimedBlocks = new HashSet<>();
        for (CitizenRecord citizen : settlement.citizens()) {
            if (citizen.targetItemEntityId() != null) claimedEntities.add(citizen.targetItemEntityId());
            if (citizen.targetPosition() != null) claimedBlocks.add(citizen.targetPosition());
        }
        for (CitizenRecord citizen : settlement.citizens().stream()
                .filter(c->workDue(level,c))
                .filter(CitizenRecord::canWork)
                 .filter(c -> schedulePhase(level.getDayTime(), c) == SchedulePhase.WORK
                        || constructionCapable(c.profession()) && settlement.buildings().stream().anyMatch(BuildingRecord::demolitionQueued))
                .filter(c -> !com.hkin.exiledsettlement.settlement.CommunityLifeService.handles(c))
                .filter(c -> c.workState() == CitizenWorkState.IDLE)
                .toList()) {
            var interacting = CitizenProjectionService.findProjection(level, citizen);
            if (interacting != null && CitizenInteractionService.held(interacting)) continue;
            if(interacting!=null && resumeRegionPitFill(level,data,settlement,citizen,interacting))continue;
            // At a safe idle boundary, make actual room for new material types
            // before another site can monopolize a full construction backpack.
            if (interacting != null && pauseForCargoDelivery(level, data, settlement, citizen, interacting)) continue;
            if (interacting != null && constructionAllowed) {
                CitizenEquipmentService.topUpConstructionTools(level, settlement, citizen, interacting);
                if(CitizenEquipmentService.prepareConstructionDeparture(level,settlement,citizen,interacting)){
                    WorkTaskService.reserve(settlement,citizen);data.touch();continue;
                }
            }
            boolean hasPreferredWorksite = constructionAllowed && constructionCapable(citizen.profession())
                    && citizen.preferredWorksiteId() != null;
            boolean preferredWorksiteLocked = hasPreferredWorksite
                    && preferredWorksiteAvailable(settlement, citizen.preferredWorksiteId());
            boolean assigned = constructionAllowed && constructionCapable(citizen.profession())
                    && (!preferredWorksiteLocked || settlement.building(citizen.preferredWorksiteId()) != null
                    && settlement.building(citizen.preferredWorksiteId()).demolitionQueued())
                    && (preferredWorksiteLocked
                        ? assignDemolitionAt(level, data, settlement, citizen, claimedBlocks, settlement.building(citizen.preferredWorksiteId()))
                        : assignDemolition(level, data, settlement, citizen, claimedBlocks));
            if (!assigned && hasPreferredWorksite) assigned = assignPreferredWorksite(level, data, settlement, citizen, claimedBlocks);
            if (!assigned && schedulePhase(level.getDayTime(), citizen) != SchedulePhase.WORK) continue;
            if(!assigned && constructionAllowed && !preferredWorksiteLocked && constructionCapable(citizen.profession()))
                assigned=assignBlueprint(level,data,settlement,citizen,claimedBlocks,false,false,true);
            if(!assigned && constructionAllowed && !preferredWorksiteLocked && constructionCapable(citizen.profession()))
                assigned=com.hkin.exiledsettlement.road.AutoRoadService.assign(level,settlement,citizen,claimedBlocks,true);
            if (!assigned && constructionAllowed && !preferredWorksiteLocked) assigned = constructionCapable(citizen.profession())
                    && assignBlueprintCleanup(level, data, settlement, citizen, claimedBlocks, true);
            if (!assigned && citizen.profession() == CitizenProfession.FARMER
                    && CitizenEquipmentService.missingEquipment(citizen) == null
                    && !shouldFarmerDeliver(level, settlement, citizen)) {
                assigned = assignFarm(level, settlement, citizen, claimedBlocks);
            }
            if (!assigned && citizen.profession()==CitizenProfession.WAREHOUSE_KEEPER)
                assigned = assignWarehouseKeeping(level,settlement,citizen);
            if (!assigned) assigned = assignPersonalDelivery(level, settlement, citizen);
            Item missingEquipment = CitizenEquipmentService.nextEquipment(level, settlement, citizen);
            if (!assigned && missingEquipment != null) {
                BuildingRecord warehouse = CitizenEquipmentService.supplyWarehouse(
                        level, settlement, citizen, citizen.lastPosition(), missingEquipment);
                BlockPos target = warehouse == null ? citizen.lastPosition()
                        : WarehouseInventoryService.nearestContainerPosition(level, warehouse, citizen.lastPosition());
                if(warehouse!=null || settlement.creativeCoreInstalled())assigned = citizen.startEquipmentFetch(warehouse == null ? null : warehouse.id(), target);
            }
            if (!assigned) {
                assigned = assignPublicItemPickup(level, settlement, citizen, claimedEntities);
            }
            if (!assigned && constructionAllowed && !preferredWorksiteLocked && constructionCapable(citizen.profession())) {
                assigned = com.hkin.exiledsettlement.building.MobRecoveryService.assign(level,settlement,citizen,claimedBlocks)
                        || assignRepair(level, data, settlement, citizen, claimedBlocks);
            }
            if (!assigned && constructionAllowed && !preferredWorksiteLocked) assigned = constructionCapable(citizen.profession())
                    && assignDemolition(level, data, settlement, citizen, claimedBlocks);
            if (!assigned) assigned = switch (citizen.profession()) {
                case WORKER -> !preferredWorksiteLocked && ((!settlement.community().policy.equals("emergency")
                        && (assignBlueprint(level,data,settlement,citizen,claimedBlocks,false,false,true)
                            || com.hkin.exiledsettlement.road.AutoRoadService.assign(level,settlement,citizen,claimedBlocks)
                            || assignBlueprint(level, data, settlement, citizen, claimedBlocks)))
                        || assignHaul(level, settlement, citizen)
                        || constructionAllowed && assignRepair(level, data, settlement, citizen, claimedBlocks, true)
                        || assignBlueprint(level, data, settlement, citizen, claimedBlocks, true));
                case FARMER -> CitizenEquipmentService.missingEquipment(citizen) == null
                        && assignFarm(level, settlement, citizen, claimedBlocks);
                case MELON_FARMER -> CitizenEquipmentService.missingEquipment(citizen) == null
                        && assignMelonFarm(level, settlement, citizen, claimedBlocks);
                case HERDSMAN -> assignHerd(level, settlement, citizen, claimedEntities);
                case HUNTER -> assignHunt(level, settlement, citizen, claimedEntities);
                case TOOLSMITH -> assignToolmaking(level, settlement, citizen);
                case LUMBERJACK -> assignForestry(level, settlement, citizen, claimedBlocks);
                case FISHER -> assignFishing(level, settlement, citizen, claimedBlocks);
                case MINER -> assignMining(level, settlement, citizen, claimedBlocks);
                case SAWYER -> assignSawmill(level, settlement, citizen);
                case WAREHOUSE_KEEPER -> assignWarehouseKeeping(level, settlement, citizen);
                case TRAVELING_MERCHANT, CHEF, KILN_WORKER -> false;
            };
            if (assigned) {WorkTaskService.reserve(settlement,citizen);data.touch();}
            if (!assigned && constructionCapable(citizen.profession()) && !preferredWorksiteLocked && !settlement.community().evacuation) {
                assigned=assignBlueprint(level,data,settlement,citizen,claimedBlocks,false,true);
                if (!assigned) assigned=com.hkin.exiledsettlement.road.AutoRoadService.assign(level,settlement,citizen,claimedBlocks);
                if (assigned) {WorkTaskService.reserve(settlement,citizen);data.touch();}
            }
        }
    }

    public static boolean constructionCapable(CitizenProfession profession) {
        return profession == CitizenProfession.WORKER;
    }

    /** Public engineering belongs to workers; personal safety and own-farm maintenance are separate. */
    public static boolean blueprintWorkAllowed(SettlementRecord s, CitizenRecord c, BlueprintRecord b, BlockPos at) {
        if (b == null || at == null) return false;
        if (constructionCapable(c.profession())) return com.hkin.exiledsettlement.region.FarmConstructionPolicy.workerMayBuild(s,b);
        var p = b.terrainProject();
        if (p != null && p.escapeFor != null && c.id().equals(p.escapingCitizen)) return true;
        if (c.profession() != CitizenProfession.FARMER && c.profession() != CitizenProfession.MELON_FARMER) return false;
        var farm = s.building(c.workBuildingId());
        if (farm == null || !farm.active() || !farm.operational() || farm.region() == null) return false;
        if (p != null) return p.dependencies.contains(farm.id()) && p.indices(at).stream()
                .map(p.steps::get).anyMatch(step -> com.hkin.exiledsettlement.terrain.TerrainService.irrigationRetirement(s, b, step));
        if (b.region() == null || !farm.id().equals(b.region().workingBuilding)) return false;
        var goal = b.region().template().placementAt(at);
        return goal != null && farmMaintenancePlacement(b, goal);
    }

    private static boolean farmMaintenancePlacement(BlueprintRecord site, BlueprintPlacement p) {
        return p.material() == Items.WATER_BUCKET || p.state().is(Blocks.OAK_FENCE)
                || p.state().is(Blocks.OAK_FENCE_GATE) || p.state().is(Blocks.DIRT_PATH)
                || p.state().is(Blocks.COBBLESTONE_STAIRS) || p.state().is(Blocks.DIRT)
                && site.region().cell(p.position()) != null
                && (site.region().cell(p.position()).use() == com.hkin.exiledsettlement.region.ProductionRegion.Use.BOUNDARY
                || site.region().cell(p.position()).use() == com.hkin.exiledsettlement.region.ProductionRegion.Use.GATE);
    }

    /** Stop new work immediately, then surrender the claim only after a real safe exit. */
    private static boolean releaseUnauthorizedConstruction(ServerLevel level, SettlementSavedData data,
            SettlementRecord s, CitizenRecord c, SettlementCitizenEntity entity) {
        boolean road = c.workState() == CitizenWorkState.TO_ROAD || c.workState() == CitizenWorkState.TO_ROAD_TOOL;
        UUID siteId = c.targetBlueprintId() != null ? c.targetBlueprintId() : c.constructionResumeId();
        var site = s.blueprint(siteId);
        var at = c.targetBlueprintId() != null ? c.targetPosition() : c.constructionResumePosition();
        if ((!road || constructionCapable(c.profession()))
                && (site == null || blueprintWorkAllowed(s, c, site, at))) return false;
        if (leaveScaffoldingBeforeLogistics(level, data, s, c, entity)) return true;
        if (entity.hasActiveConstructionMovement() || !entity.onGround()) return true;
        SITE_STATIONS.remove(entity);
        FARM_ESCAPES.remove(entity);
        clear(data, c, entity);
        return true;
    }

    public static int worksiteWorkerCap() {
        return MAX_DEMOLISHERS_PER_SITE;
    }

    private static boolean assignPreferredWorksite(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, Set<BlockPos> claimed
    ) {
        UUID preferred = citizen.preferredWorksiteId();
        if (preferred == null) return false;
        BlueprintRecord blueprint = settlement.blueprint(preferred);
        if (blueprint != null) {
            if (blueprint.cancellationQueued() || BlueprintService.readyForFootingCleanup(level, blueprint)) {
                return assignBlueprintCleanupAt(level, data, settlement, citizen, claimed, blueprint);
            }
            if (ConstructionDebugService.isEnabled(level, settlement.id())) return false;
            // Finished soil can be tilled/enclosed while other columns still need preparation.
            // Region pending goals already enforce local soil, support and irrigation dependencies.
            if(blueprint.region()!=null && com.hkin.exiledsettlement.terrain.TerrainService.allowed(settlement,blueprint)) {
                var projection=CitizenProjectionService.findProjection(level,citizen);
                var ready=BlueprintService.pendingPlacements(level,blueprint,claimed,citizen.lastPosition()).stream()
                        .filter(p->projection==null || !projection.constructionTargetDeferred(p.placement().position()))
                        .filter(p->constructionSupplyAvailable(level,settlement,citizen,blueprint,p.placement()))
                        .findFirst().orElse(null);
                if(ready!=null && claimed.add(ready.placement().position()))
                    return citizen.startTask(CitizenWorkState.TO_BLUEPRINT,ready.placement().position(),null,blueprint.id());
            }
            // A manually assigned builder also owns this building's real preparation chain.
            if(!com.hkin.exiledsettlement.terrain.TerrainService.allowed(settlement,blueprint) || blueprint.region()!=null){
                for(var order:settlement.terrain().projects.values().stream()
                        .sorted(Comparator.comparingInt(p->"农业旧灌溉回收".equals(p.name)?0:com.hkin.exiledsettlement.region.FarmEntranceService.NAME.equals(p.name)?1:2)).toList())if(!order.cancelled && !order.completed && order.dependencies.contains(preferred)){
                    if(order.road || order.bridge)continue;
                    var preparation=settlement.blueprint(order.id);if(preparation==null || !com.hkin.exiledsettlement.terrain.TerrainService.allowed(settlement,preparation))continue;
                    var worker=CitizenProjectionService.findProjection(level,citizen);
                    var pending=BlueprintService.pendingPlacements(level,preparation,claimed,citizen.lastPosition()).stream()
                            .filter(p->worker==null || !worker.constructionTargetDeferred(p.placement().position()))
                            .sorted(Comparator.comparingDouble(p->p.placement().position().distSqr(citizen.lastPosition())))
                            .findFirst().orElse(null);
                    if(pending!=null && constructionSupplyAvailable(level,settlement,citizen,preparation,pending.placement()) && claimed.add(pending.placement().position()))return citizen.startTask(CitizenWorkState.TO_BLUEPRINT,pending.placement().position(),null,preparation.id());
                }
                if(!com.hkin.exiledsettlement.terrain.TerrainService.allowed(settlement,blueprint))return false;
            }
            if (assignedBuilderCount(settlement, blueprint.id()) >= com.hkin.exiledsettlement.terrain.PublicWorkService.builderLimit(level,settlement,blueprint)) return false;
            var worker=CitizenProjectionService.findProjection(level,citizen);
            BlueprintService.PendingPlacement pending = BlueprintService.pendingPlacements(
                    level,blueprint,claimed,citizen.lastPosition()).stream()
                    .filter(p->worker==null || !worker.constructionTargetDeferred(p.placement().position()))
                    .findFirst().orElse(null);
            return pending != null && claimed.add(pending.placement().position())
                    && citizen.startTask(CitizenWorkState.TO_BLUEPRINT,
                    pending.placement().position(), null, blueprint.id());
        }
        BuildingRecord building = settlement.building(preferred);
        if (building != null && building.active() && building.demolitionQueued()) {
            return assignDemolitionAt(level, data, settlement, citizen, claimed, building);
        }
        if (!preferredWorksiteAvailable(settlement, preferred) && citizen.setPreferredWorksite(null)) data.touch();
        return false;
    }

    private static boolean preferredWorksiteAvailable(SettlementRecord settlement, UUID worksiteId) {
        BlueprintRecord blueprint = settlement.blueprint(worksiteId);
        if (blueprint != null) return true;
        BuildingRecord building = settlement.building(worksiteId);
        return building != null && building.active() && building.demolitionQueued();
    }

    private static boolean assignHaul(ServerLevel level, SettlementRecord settlement, CitizenRecord citizen) {
        BuildingRecord warehouse = nearestBuilding(settlement, citizen.lastPosition(), BuildingType.WAREHOUSE);
        if (warehouse == null) return false;
        for (BuildingRecord source : settlement.buildings()) {
            if (!source.operational() || source.type() == BuildingType.WAREHOUSE || source.type().isHouse()
                    || source.type() == BuildingType.WAGON || source.type() == BuildingType.MINE
                    || source.id().equals(warehouse.id())) continue;
            if (WarehouseInventoryService.hasAnyItem(level, source)
                    && citizen.startContainerHaul(source.id(), warehouse.id())) return true;
        }
        return false;
    }

    private static final Map<CitizenRecord, Long> NEXT_WAREHOUSE_AUDIT = new java.util.WeakHashMap<>();

    private static boolean mineRestCollection(ServerLevel level, SettlementRecord settlement, CitizenRecord citizen) {
        var source=settlement.building(citizen.sourceBuildingId());
        return citizen.profession()==CitizenProfession.MINER
                && citizen.workState()==CitizenWorkState.TO_OUTPUT_CONTAINER
                && source!=null && source.type()==BuildingType.MINE && source.id().equals(citizen.workBuildingId())
                && schedulePhase(level.getDayTime(),citizen)==SchedulePhase.REST;
    }

    private static boolean assignMineRestHaul(ServerLevel level,SettlementRecord settlement,CitizenRecord citizen) {
        if(citizen.profession()!=CitizenProfession.MINER || schedulePhase(level.getDayTime(),citizen)!=SchedulePhase.REST)return false;
        var source=workplace(settlement,citizen,BuildingType.MINE);
        if(source==null || !source.repairPositions().isEmpty())return false;
        for(Item item:loadableOutputItems(level,source,citizen)) {
            var destination=acceptingWarehouse(level,settlement,citizen.lastPosition(),new ItemStack(item));
            if(destination!=null)return citizen.startContainerHaul(source.id(),destination.id());
        }
        return false;
    }

    private static boolean assignWarehouseKeeping(ServerLevel level, SettlementRecord settlement, CitizenRecord citizen) {
        if (level.getGameTime() < NEXT_WAREHOUSE_AUDIT.getOrDefault(citizen, 0L)) return false;
        // Ten seconds between idle audits, not a full inventory scan every work tick.
        NEXT_WAREHOUSE_AUDIT.put(citizen, level.getGameTime() + 200);
        BuildingRecord workplace = settlement.building(citizen.workBuildingId());
        if (!usable(workplace, BuildingType.WAREHOUSE)) return false;
        if(com.hkin.exiledsettlement.building.WarehouseStock.applies(workplace))return false;
        List<BuildingRecord> warehouses = settlement.buildings().stream()
                .filter(building -> usable(building, BuildingType.WAREHOUSE))
                .sorted(java.util.Comparator.comparingDouble(building -> building.position().distSqr(citizen.lastPosition())))
                .toList();
        for (BuildingRecord source : warehouses) {
            for (BuildingRecord destination : warehouses) {
                if (destination.id().equals(source.id())) continue;
                if (!warehouseTransferItems(level, source, destination, citizen).isEmpty())
                    return citizen.startContainerHaul(source.id(), destination.id());
            }
        }
        for (BuildingRecord source : warehouses) {
            if (WarehouseInventoryService.needsOrganization(level, source))
                return citizen.startContainerHaul(source.id(), source.id());
        }
        return false;
    }

    private static Set<Item> warehouseTransferItems(ServerLevel level, BuildingRecord source,
            BuildingRecord destination, CitizenRecord citizen) {
        Set<Item> items = new LinkedHashSet<>();
        for (var at : WarehouseInventoryService.loadedContainers(level, source)) {
            for (int slot = 0; slot < at.container().getContainerSize(); slot++) {
                ItemStack stack = at.container().getItem(slot);
                if (!stack.isEmpty() && !source.accepts(stack) && destination.accepts(stack)
                        && !stack.is(com.hkin.exiledsettlement.registry.ModItems.CREATIVE_SUPPLY_CORE.get())
                        && citizen.inventoryCapacityFor(stack) > 0
                        && WarehouseInventoryService.canAccept(level, destination, stack)) items.add(stack.getItem());
            }
        }
        return items;
    }

    private static boolean assignPublicItemPickup(
            ServerLevel level, SettlementRecord settlement, CitizenRecord citizen, Set<UUID> claimed
    ) {
        if (!citizen.hasInventoryCapacity()) return false;
        BuildingRecord warehouse = SettlementInventoryService.nearestWarehouse(
                settlement, citizen.lastPosition());
        if (warehouse == null || !warehouse.operational()) return false;
        ItemEntity target = level.getEntitiesOfClass(ItemEntity.class,
                        new AABB(citizen.lastPosition()).inflate(PUBLIC_ITEM_SEARCH_RADIUS),
                        item -> publicItemAvailable(settlement, item)
                                && !claimed.contains(item.getUUID())
                                && citizen.inventoryCapacityFor(item.getItem()) > 0
                                && WarehouseInventoryService.canAccept(level, warehouse, item.getItem()))
                .stream()
                .min(Comparator.comparingDouble(item -> item.distanceToSqr(
                        citizen.lastPosition().getX() + 0.5D,
                        citizen.lastPosition().getY(),
                        citizen.lastPosition().getZ() + 0.5D)))
                .orElse(null);
        return target != null && claimed.add(target.getUUID())
                && citizen.startTask(CitizenWorkState.TO_PUBLIC_ITEM,
                target.blockPosition(), target.getUUID(), null);
    }

    /** Public drops are unowned world items inside the settlement, never player-owned drops. */
    public static boolean publicItemAvailable(SettlementRecord settlement, ItemEntity item) {
        return item.isAlive() && !item.getItem().isEmpty()
                && item.getOwner() == null
                && item.getTarget() == null
                && !item.hasPickUpDelay()
                && settlement.contains(item.blockPosition());
    }

    /** Opportunity pickup has no navigation intent and cannot interrupt the current work task. */
    public static void pickupNearbyItems(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
                                        CitizenRecord citizen, SettlementCitizenEntity entity) {
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(1.5D),
                candidate -> publicItemAvailable(settlement, candidate)).stream().limit(8).toList()) {
            ItemStack original = item.getItem();
            ItemStack remainder = citizen.insertIntoInventory(original);
            if (remainder.getCount() == original.getCount()) continue;
            if (remainder.isEmpty()) item.discard(); else item.setItem(remainder);
            data.touch();
        }
    }

    private static void collectPublicItem(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        Entity targetEntity = citizen.targetItemEntityId() == null
                ? null : level.getEntity(citizen.targetItemEntityId());
        if (!(targetEntity instanceof ItemEntity item) || !publicItemAvailable(settlement, item)) {
            clear(data, citizen, entity);
            return;
        }
        BlockPos target = item.blockPosition();
        if (!target.equals(citizen.targetPosition()) && citizen.updateTargetPosition(target)) data.touch();
        if (!near(entity, target)) {
            move(data, citizen, entity, target, CitizenActivity.COLLECTING_PUBLIC_ITEM);
            return;
        }
        ItemStack remaining = citizen.insertIntoInventory(item.getItem());
        if (remaining.getCount() == item.getItem().getCount()) {
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "INVENTORY_FULL");
            return;
        }
        if (remaining.isEmpty()) item.discard();
        else item.setItem(remaining);
        clear(data, citizen, entity);
        if (assignPersonalDelivery(level, settlement, citizen)) syncHeldItem(citizen, entity);
        data.touch();
    }

    private static boolean assignFarm(
            ServerLevel level, SettlementRecord settlement, CitizenRecord citizen, Set<BlockPos> claimed
    ) {
        BuildingRecord farm = workplace(settlement, citizen, BuildingType.FARM_FIELD);
        if (farm == null) return false;
        // An entrance is access infrastructure, not seasonal crop work. Finish
        // its real support/old-fence clearance/gate before recurring farm jobs.
        if(assignFarmIrrigation(level,settlement,citizen,farm,claimed,true))return true;
        if (com.hkin.exiledsettlement.settlement.CommunityLifeService.winter(settlement,
                com.hkin.exiledsettlement.settlement.CommunityLifeService.day(level))) return false;
        List<BlockPos> groundPositions = orderedFarmGroundPositions(settlement, farm, citizen);
        var farmers=settlement.citizens().stream().filter(CitizenRecord::canWork)
                .filter(c->farm.id().equals(c.workBuildingId())
                        && (c.profession()==CitizenProfession.FARMER || c.profession()==CitizenProfession.MELON_FARMER))
                .sorted(Comparator.comparing(c->c.id().toString())).toList();
        // Reserve one of several farmers for missing irrigation. Otherwise a
        // steady mature harvest can starve the last high-level water points forever.
        if(farmers.size()>1 && farmers.getFirst().id().equals(citizen.id())
                && assignFarmIrrigation(level,settlement,citizen,farm,claimed))return true;
        if(farm.region()!=null)for(var cell:farm.region().ordered())if(cell.use()==com.hkin.exiledsettlement.region.ProductionRegion.Use.FRUIT) {
            var fruit=cell.ground().above();
            if((level.getBlockState(fruit).is(Blocks.MELON) || level.getBlockState(fruit).is(Blocks.PUMPKIN))
                    && !farmTargetDeferred(level,citizen,fruit) && claimed.add(fruit))return citizen.startTask(CitizenWorkState.TO_FARM,fruit,null,null);
        }
        for (BlockPos ground : groundPositions) {
            if (!level.getBlockState(ground).is(Blocks.FARMLAND)) continue;
            BlockPos cropPos = ground.above();
            BlockState cropState = level.getBlockState(cropPos);
            if (cropState.getBlock() instanceof CropBlock crop && crop.isMaxAge(cropState)
                    && !farmTargetDeferred(level, citizen, cropPos) && claimed.add(cropPos)) {
                return citizen.startTask(CitizenWorkState.TO_FARM, cropPos, null, null);
            }
        }
        // A working farmer can finish their own ready irrigation points instead of
        // waiting for general builders to finish unrelated houses first.
        if (assignFarmIrrigation(level,settlement,citizen,farm,claimed)) return true;
        boolean wheatPaused=com.hkin.exiledsettlement.settlement.WarehouseStewardService.pauseWheat(level,settlement);
        for (BlockPos ground : groundPositions) {
            if(BuildingProductionService.plantingSeed(farm,ground)==Items.WHEAT_SEEDS
                    && wheatPaused)continue;
            if (level.getBlockState(ground).is(Blocks.FARMLAND)
                    && (level.getBlockState(ground.above()).isAir() || ScaffoldSitePreparation.softObstacle(level.getBlockState(ground.above())))
                    && !farmTargetDeferred(level, citizen, ground) && claimed.add(ground)) {
                return citizen.startTask(CitizenWorkState.TO_FARM, ground, null, null);
            }
        }
        // Harvest and seed an existing tilled cell before repairing the next one.
        // Dry empty farmland can revert while the rest of a large field is being prepared.
        for (BlockPos ground : groundPositions) {
            BlockState state = level.getBlockState(ground);
            if(BuildingProductionService.plantingSeed(farm,ground)==Items.WHEAT_SEEDS
                    && wheatPaused)continue;
            if (repairableFarmGround(state)
                    && (level.getBlockState(ground.above()).isAir() || ScaffoldSitePreparation.softObstacle(level.getBlockState(ground.above())))
                    && !farmTargetDeferred(level, citizen, ground)
                    && claimed.add(ground)) {
                return citizen.startTask(CitizenWorkState.TO_FARM, ground, null, null);
            }
        }
        return false;
    }

    private static boolean assignFarmIrrigation(ServerLevel level,SettlementRecord settlement,CitizenRecord citizen,
                                               BuildingRecord farm,Set<BlockPos> claimed) {
        return assignFarmIrrigation(level,settlement,citizen,farm,claimed,false);
    }
    private static boolean assignFarmIrrigation(ServerLevel level,SettlementRecord settlement,CitizenRecord citizen,
                                               BuildingRecord farm,Set<BlockPos> claimed,boolean entranceOnly) {
        if (farm.region()==null || settlement.community().evacuation) return false;
        var entity=CitizenProjectionService.findProjection(level,citizen);
        // Retire raised irrigation sources before they flood locally ready rows.
        for(var site:settlement.blueprints())if(site.terrainProject()!=null && !site.cancellationQueued()
                && site.terrainProject().dependencies.contains(farm.id())) {
            for(var pending:BlueprintService.pendingPlacements(level,site,claimed,citizen.lastPosition())) {
                if(pending.placement().material()!=Items.BUCKET || entity!=null && entity.constructionTargetDeferred(pending.placement().position()))continue;
                if(constructionSupplyAvailable(level,settlement,citizen,site,pending.placement()) && claimed.add(pending.placement().position()))
                    return citizen.startTask(CitizenWorkState.TO_BLUEPRINT,pending.placement().position(),null,site.id());
            }
        }
        for (var site : settlement.blueprints()) {
            if (site.region()==null || site.cancellationQueued() || !farm.id().equals(site.region().workingBuilding)
                    || !com.hkin.exiledsettlement.terrain.TerrainService.allowed(settlement,site)
                    || site.assignmentMode()==WorksiteAssignmentMode.MANUAL && !site.id().equals(citizen.preferredWorksiteId())
                    || settlement.citizens().stream().filter(c->site.id().equals(c.targetBlueprintId()))
                        .filter(c->c.profession()==CitizenProfession.FARMER || c.profession()==CitizenProfession.MELON_FARMER).count()>=1) continue;
            var infrastructure=site.region().template().placements().stream()
                    .filter(p->!entranceOnly || site.region().template().entrances().stream()
                            .anyMatch(e->e.position().equals(p.position()) || e.position().below().equals(p.position())))
                    .filter(p->p.material()==Items.WATER_BUCKET || p.state().is(Blocks.OAK_FENCE) || p.state().is(Blocks.OAK_FENCE_GATE)
                            || p.state().is(Blocks.DIRT_PATH) || p.state().is(Blocks.COBBLESTONE_STAIRS)
                            || p.state().is(Blocks.DIRT) && (site.region().cell(p.position()).use()==com.hkin.exiledsettlement.region.ProductionRegion.Use.BOUNDARY || site.region().cell(p.position()).use()==com.hkin.exiledsettlement.region.ProductionRegion.Use.GATE))
                    .sorted(Comparator.comparingInt((BlueprintPlacement p)->site.region().cell(p.position()).use()==com.hkin.exiledsettlement.region.ProductionRegion.Use.GATE?0:p.material()==Items.WATER_BUCKET?1:p.state().is(Blocks.DIRT_PATH)?3:2)
                        .thenComparingDouble(p->p.position().distSqr(citizen.lastPosition()))).toList();
            for (var goal : infrastructure) {
                var at=goal.position();if(claimed.contains(at) || entity!=null && entity.constructionTargetDeferred(at))continue;
                var pending=BlueprintService.pendingAt(level,site,at);
                if(pending==null || !constructionSupplyAvailable(level,settlement,citizen,site,pending.placement()))continue;
                claimed.add(at);return citizen.startTask(CitizenWorkState.TO_BLUEPRINT,at,null,site.id());
            }
        }
        return false;
    }

    private static boolean assignMelonFarm(
            ServerLevel level, SettlementRecord settlement, CitizenRecord citizen, Set<BlockPos> claimed
    ) {
        BuildingRecord farm = workplace(settlement, citizen, BuildingType.MELON_FIELD);
        if (farm == null) return false;
        if(farm.region()!=null)return assignFarm(level,settlement,citizen,claimed);
        if (com.hkin.exiledsettlement.settlement.CommunityLifeService.winter(settlement,
                com.hkin.exiledsettlement.settlement.CommunityLifeService.day(level))) return false;
        for (BlockPos cursor : BlockPos.betweenClosed(farm.minCorner(), farm.maxCorner().above())) {
            if ((level.getBlockState(cursor).is(Blocks.MELON) || level.getBlockState(cursor).is(Blocks.PUMPKIN))
                    && !farmTargetDeferred(level, citizen, cursor) && claimed.add(cursor.immutable())) {
                return citizen.startTask(CitizenWorkState.TO_FARM, cursor.immutable(), null, null);
            }
        }
        for (BlockPos ground : farmGroundPositions(farm)) {
            if ((level.getBlockState(ground).is(Blocks.FARMLAND) || repairableFarmGround(level.getBlockState(ground)))
                    && (level.getBlockState(ground.above()).isAir() || ScaffoldSitePreparation.softObstacle(level.getBlockState(ground.above())))
                    && !farmTargetDeferred(level, citizen, ground) && claimed.add(ground)) {
                return citizen.startTask(CitizenWorkState.TO_FARM, ground, null, null);
            }
        }
        return false;
    }

    @Nullable
    private static BuildingRecord farmWorkplace(SettlementRecord settlement, CitizenRecord citizen) {
        return citizen.profession() == CitizenProfession.MELON_FARMER
                ? workplace(settlement, citizen, BuildingType.MELON_FIELD)
                : workplace(settlement, citizen, BuildingType.FARM_FIELD);
    }

    private static List<BlockPos> farmGroundPositions(BuildingRecord farm) {
        if(farm.region()!=null)return farm.region().ordered().stream().filter(c->c.use()==com.hkin.exiledsettlement.region.ProductionRegion.Use.CROP && farm.region().readyCrops.contains(c.ground().asLong())).map(c->c.ground()).toList();
        return BlueprintTemplates.createForBuilding(farm).placements().stream()
                .filter(placement -> placement.state().is(Blocks.FARMLAND))
                .map(placement -> placement.position().immutable())
                .toList();
    }

    private static List<BlockPos> orderedFarmGroundPositions(
            SettlementRecord settlement, BuildingRecord farm, CitizenRecord citizen
    ) {
        List<CitizenRecord> farmers = settlement.citizens().stream()
                .filter(CitizenRecord::alive)
                .filter(other -> (other.profession() == CitizenProfession.FARMER || farm.region()!=null && other.profession()==CitizenProfession.MELON_FARMER))
                .filter(other -> farm.id().equals(other.workBuildingId()))
                .sorted(Comparator.comparing(other -> other.id().toString()))
                .toList();
        int farmerCount = Math.max(1, farmers.size());
        int farmerIndex = Math.max(0, farmers.indexOf(citizen));
        List<BlockPos> positions = new ArrayList<>(farmGroundPositions(farm));
        positions.sort(Comparator
                .comparingInt((BlockPos position) -> farmWorkerZone(
                        position, farm.minCorner(), farm.maxCorner(), farmerCount) == farmerIndex ? 0 : 1)
                .thenComparingLong(position -> farmSerpentineIndex(
                        position, farm.minCorner(), farm.maxCorner()))
                .thenComparingLong(BlockPos::asLong));
        return positions;
    }

    /** Stable row work package: finish one row before turning into the next. */
    public static long farmSerpentineIndex(BlockPos position, BlockPos minCorner, BlockPos maxCorner) {
        int innerMinX = minCorner.getX() + 1;
        int innerMaxX = maxCorner.getX() - 1;
        int innerMinZ = minCorner.getZ() + 1;
        int width = Math.max(1, innerMaxX - innerMinX + 1);
        int row = Math.max(0, position.getZ() - innerMinZ);
        int column = Math.max(0, Math.min(width - 1, position.getX() - innerMinX));
        int orderedColumn = (row & 1) == 0 ? column : width - 1 - column;
        return (long) row * width + orderedColumn;
    }

    public static int farmWorkerZone(
            BlockPos position, BlockPos minCorner, BlockPos maxCorner, int workerCount
    ) {
        if (workerCount <= 1) return 0;
        int width = maxCorner.getX() - minCorner.getX() + 1;
        int depth = maxCorner.getZ() - minCorner.getZ() + 1;
        int offset = width >= depth
                ? position.getX() - minCorner.getX()
                : position.getZ() - minCorner.getZ();
        int length = Math.max(1, width >= depth ? width : depth);
        return Math.min(workerCount - 1, Math.max(0, offset) * workerCount / length);
    }

    private static boolean assignBlueprint(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, Set<BlockPos> claimed
    ) { return assignBlueprint(level, data, settlement, citizen, claimed, false); }

    private static boolean assignBlueprint(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, Set<BlockPos> claimed, boolean decoration) {
        return assignBlueprint(level,data,settlement,citizen,claimed,decoration,false);
    }
    private static boolean assignBlueprint(ServerLevel level,SettlementSavedData data,SettlementRecord settlement,
            CitizenRecord citizen,Set<BlockPos> claimed,boolean decoration,boolean publicOnly) {
        return assignBlueprint(level,data,settlement,citizen,claimed,decoration,publicOnly,false);
    }
    private static boolean assignBlueprint(ServerLevel level,SettlementSavedData data,SettlementRecord settlement,
            CitizenRecord citizen,Set<BlockPos> claimed,boolean decoration,boolean publicOnly,boolean priorityOnly) {
        if (!constructionCapable(citizen.profession())) return false;
        if (ConstructionDebugService.isEnabled(level, settlement.id())) return false;
        List<BlueprintRecord> candidates = settlement.blueprints().stream()
                .filter(blueprint -> !blueprint.cancellationQueued())
                .filter(blueprint -> !siteDeferred(level,citizen,blueprint.id()))
                .filter(blueprint -> !publicOnly || blueprint.terrainProject()!=null)
                .filter(blueprint -> !priorityOnly || WorkTaskService.priority(settlement,blueprint)==0)
                .filter(blueprint -> com.hkin.exiledsettlement.terrain.TerrainService.allowed(settlement,blueprint))
                .filter(blueprint -> DecorationService.owned(settlement, blueprint.id()) == decoration)
                .filter(blueprint -> DecorationService.allowed(settlement, settlement.decorations().owned(blueprint.id())))
                .filter(blueprint -> blueprint.assignmentMode() == WorksiteAssignmentMode.AUTO)
                .toList();
        if (candidates.isEmpty()) return false;
        var counts=WorkTaskService.counts(settlement);
        var areaCounts=com.hkin.exiledsettlement.terrain.PublicWorkAreas.assigned(settlement);
        record Rank(BlueprintRecord site,int priority,int areaLoad,int connection,int empty,double fill,double demand,double distance){}
        candidates=candidates.stream().map(b->{
            var parents=WorkTaskService.building(settlement,b)?List.of(b):b.terrainProject()==null?List.<BlueprintRecord>of():b.terrainProject().dependencies.stream().map(settlement::blueprint).filter(parent->WorkTaskService.building(settlement,parent)).toList();
            int assigned=parents.stream().mapToInt(parent->counts.getOrDefault(parent.id(),0)).min().orElse(counts.getOrDefault(b.id(),0));
            double fill=parents.stream().mapToDouble(parent->Math.min(1,(double)counts.getOrDefault(parent.id(),0)/WorkTaskService.defaultWorkers(parent))).min().orElse(1);
            if(b.terrainProject()!=null && b.terrainProject().playerBridge!=null)fill=Math.min(1,assigned/2.0);
            // Road preparation opens transport before independent public
            // tidying; both remain P2 behind building construction.
            int connection=b.terrainProject()!=null && b.terrainProject().road?0:1;
            int areaLoad=com.hkin.exiledsettlement.terrain.PublicWorkAreas.applies(settlement,b)
                    ?areaCounts.getOrDefault(com.hkin.exiledsettlement.terrain.PublicWorkAreas.area(b.anchor()),0):0;
            return new Rank(b,constructionPriority(settlement,b),areaLoad,connection,assigned==0?0:1,fill,(double)assigned/com.hkin.exiledsettlement.terrain.PublicWorkService.demand(level,b),b.anchor().distSqr(citizen.lastPosition()));
        }).sorted(Comparator.comparingInt(Rank::priority).thenComparingInt(Rank::areaLoad).thenComparingInt(Rank::connection).thenComparingInt(Rank::empty).thenComparingDouble(Rank::fill)
                .thenComparingDouble(Rank::demand).thenComparingDouble(Rank::distance).thenComparing(r->r.site().id())).map(Rank::site).toList();
        var worker=CitizenProjectionService.findProjection(level,citizen);
        for (BlueprintRecord blueprint : candidates) {
            if(WorkTaskService.building(settlement,blueprint)
                    && counts.getOrDefault(blueprint.id(),0)>=WorkTaskService.defaultWorkers(blueprint))continue;
            BlueprintService.PendingPlacement pending = BlueprintService.pendingPlacements(level,blueprint,claimed,citizen.lastPosition()).stream()
                    .filter(p->!priorityOnly || WorkTaskService.priorityAt(settlement,blueprint,p.placement().position())==0)
                    .filter(p->WorkTaskService.automaticSlotAvailable(settlement,blueprint,p.placement().position(),counts))
                    .filter(p->worker==null || !worker.constructionTargetDeferred(p.placement().position()))
                    .filter(p->constructionSupplyAvailable(level,settlement,citizen,blueprint,p.placement())).findFirst().orElse(null);
            if (pending == null || !claimed.add(pending.placement().position())) continue;
            return citizen.startTask(CitizenWorkState.TO_BLUEPRINT,
                    pending.placement().position(), null, blueprint.id());
        }
        return false;
    }
    private static int constructionPriority(SettlementRecord s,BlueprintRecord b){
        return WorkTaskService.priority(s,b);
    }

    private static int motherAssigned(SettlementRecord s,BlueprintRecord b){
        if(WorkTaskService.building(s,b))return WorkTaskService.assigned(s,b.id());
        var p=b.terrainProject();if(p==null)return assignedBuilderCount(s,b.id());
        return p.dependencies.stream().map(s::blueprint).filter(parent->WorkTaskService.building(s,parent))
                .mapToInt(parent->WorkTaskService.assigned(s,parent.id())).min().orElse(assignedBuilderCount(s,b.id()));
    }
    private static double motherFill(SettlementRecord s,BlueprintRecord b){
        if(WorkTaskService.building(s,b))return Math.min(1,(double)WorkTaskService.assigned(s,b.id())/WorkTaskService.defaultWorkers(b));
        var p=b.terrainProject();if(p==null)return 1;
        return p.dependencies.stream().map(s::blueprint).filter(parent->WorkTaskService.building(s,parent))
                .mapToDouble(parent->Math.min(1,(double)WorkTaskService.assigned(s,parent.id())/WorkTaskService.defaultWorkers(parent))).min().orElse(1);
    }
    public static boolean constructionDispatchReady(ServerLevel level, SettlementRecord s, CitizenRecord c,
            BlueprintRecord site, BlueprintPlacement placement) {
        return constructionSupplyAvailable(level, s, c, site, placement);
    }
    public static int constructionDeliveryCount(SettlementRecord s, CitizenRecord c, int slot) {
        return personalDeliveryCount(s, c, slot);
    }
    private static boolean constructionSupplyAvailable(ServerLevel level,SettlementRecord s,CitizenRecord c,BlueprintRecord site,BlueprintPlacement placement) {
        if(com.hkin.exiledsettlement.blueprint.TimedConstructionService.building(site)
                || com.hkin.exiledsettlement.blueprint.TimedConstructionService.fill(level,site,placement.position())) {
            var material=placement.material();
            return material==null || s.creativeCoreInstalled() || countInventoryItem(c,material)>0
                    || WarehouseConstructionSupply.available(level,s,material,1)
                    || s.citizens().stream().anyMatch(other->site.id().equals(other.targetBlueprintId()) && countInventoryItem(other,material)>0);
        }
        if(c.preferredWorksiteId()==null && siteDeferred(level,c,WorkTaskService.mother(s,site,placement.position())))return false;
        var item=site.hasClearanceTarget(placement.position()) || placement.state().isAir()
                || com.hkin.exiledsettlement.terrain.TerrainService.soft(level.getBlockState(placement.position())) ? null : placement.material();
        if(item!=null && site.terrainProject()!=null){var plan=site.terrainProject();for(int i:plan.indices(placement.position()))if(plan.steps.get(i).fill()){
            var chosen=com.hkin.exiledsettlement.terrain.BuriedFillMaterials.choose(level,s,plan,plan.steps.get(i),c);item=chosen.getBlock().asItem();
        }}
        final var requiredItem=item;
        if(requiredItem!=null && !c.hasInventoryItem(stack->stack.is(requiredItem))
                && c.inventoryCapacityFor(new ItemStack(requiredItem))==0
                && s.citizens().stream().noneMatch(other->site.id().equals(other.targetBlueprintId())
                    && canShareSiteMaterial(level,c,other) && other.hasInventoryItem(stack->stack.is(requiredItem))))return false;
        if(s.creativeCoreInstalled())return true;
        if(requiredItem!=null && c.findInventorySlot(stack->stack.is(requiredItem))<0 && s.citizens().stream().noneMatch(other->site.id().equals(other.targetBlueprintId()) && other.lastPosition().distSqr(c.lastPosition())<=144 && other.findInventorySlot(stack->stack.is(requiredItem))>=0)
                && !WarehouseConstructionSupply.available(level,s,requiredItem,1))return false;
        var current=level.getBlockState(placement.position());
        var tag=(site.terrainProject()!=null && !placement.state().isAir() || placement.state().is(Blocks.DIRT_PATH))?net.minecraft.tags.ItemTags.SHOVELS:placement.mode()==BlueprintPlacementMode.FARM_TILL?net.minecraft.tags.ItemTags.HOES
                : placement.state().isAir() && current.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE)?net.minecraft.tags.ItemTags.PICKAXES
                : placement.state().isAir() && current.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE)?net.minecraft.tags.ItemTags.AXES
                : placement.state().isAir() && current.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL)?net.minecraft.tags.ItemTags.SHOVELS:null;
        if(tag==null || c.findInventorySlot(stack->stack.is(tag))>=0)return true;
        return SettlementInventoryService.warehouses(s).stream().anyMatch(w->WarehouseInventoryService.hasCreativeSupply(level,w)
                || WarehouseInventoryService.summarize(level,w).keySet().stream().anyMatch(i->new ItemStack(i).is(tag)));
    }
    private static int assignedBuilderCount(SettlementRecord settlement, UUID blueprintId) {
        return (int) settlement.citizens().stream()
                .filter(resident -> blueprintId.equals(resident.targetBlueprintId()))
                .count();
    }

    private static boolean builderWithinCap(
            SettlementRecord settlement, CitizenRecord citizen, UUID blueprintId
    ) {
        BlueprintRecord blueprint = settlement.blueprint(blueprintId);
        if (blueprint == null || !blueprintWorkAllowed(settlement, citizen, blueprint, citizen.targetPosition())
                || !com.hkin.exiledsettlement.terrain.TerrainService.allowed(settlement,blueprint)) return false;
        if (blueprint.assignmentMode() == WorksiteAssignmentMode.MANUAL
                && !blueprintId.equals(citizen.preferredWorksiteId()) && !(blueprint.terrainProject()!=null && blueprint.terrainProject().dependencies.contains(citizen.preferredWorksiteId()))) return false;
        if (citizen.preferredWorksiteId() != null
                && !blueprintId.equals(citizen.preferredWorksiteId())
                && preferredWorksiteAvailable(settlement, citizen.preferredWorksiteId())
                && !(blueprint.terrainProject()!=null && blueprint.terrainProject().dependencies.contains(citizen.preferredWorksiteId()))) return false;
        return blueprintId.equals(citizen.targetBlueprintId())
                && WorkTaskService.withinBuildingCapacity(settlement,citizen);
    }

    private static boolean assignHerd(
            ServerLevel level, SettlementRecord settlement, CitizenRecord citizen, Set<UUID> claimed
    ) {
        if(com.hkin.exiledsettlement.region.PastureGroundSafety.assign(level,settlement,citizen,claimed))return true;
        BuildingRecord pasture = workplace(settlement, citizen, BuildingType.PASTURE_AREA);
        if (pasture == null) return false;
        if(com.hkin.exiledsettlement.region.PastureGroundSafety.active(pasture.region()))return false;
        List<Animal> residents = level.getEntitiesOfClass(Animal.class, buildingArea(pasture, 0),
                animal -> animal.isAlive() && insidePastureInterior(pasture,animal.blockPosition()) && com.hkin.exiledsettlement.region.ProductionRegionService.matchesAnimal(pasture,animal));
        int surplus = (int)residents.stream().filter(a->com.hkin.exiledsettlement.region.ProductionRegionService.animalSurplus(pasture,residents,a)).count();
        if (surplus > 0) {
            for (Animal animal : residents) {
                if (!com.hkin.exiledsettlement.region.ProductionRegionService.animalSurplus(pasture,residents,animal) || animal.isBaby() || animal.hasCustomName() || animal.isLeashed()
                        || animal instanceof TamableAnimal tamable && tamable.isTame()
                        || !claimed.add(animal.getUUID())) continue;
                return citizen.startTask(CitizenWorkState.TO_HERD, animal.blockPosition(), animal.getUUID(), null);
            }
        }
        if (pasture.productionId().equals(net.minecraft.resources.ResourceLocation.withDefaultNamespace("sheep")) || pasture.region()!=null && pasture.region().cells.values().stream().anyMatch(c->c.production().getPath().equals("sheep"))) {
            for (Sheep sheep : level.getEntitiesOfClass(Sheep.class, buildingArea(pasture,0),
                    value -> value.isAlive() && !value.isBaby() && !value.isSheared() && insidePastureInterior(pasture,value.blockPosition()))) {
                if (claimed.add(sheep.getUUID())) {
                    return citizen.startTask(CitizenWorkState.TO_HERD, sheep.blockPosition(), sheep.getUUID(), null);
                }
            }
        }
        // Finish a production batch before setting out to capture an animal outside the pasture.
        // Otherwise a failed capture route can strand the wool/meat already carried by this worker.
        if (deliverableSlot(settlement, citizen, false) >= 0) {
            return false;
        }
        if (nearestPastureWithCapacity(level, settlement, pasture.position()) == pasture) {
            for (Animal animal : level.getEntitiesOfClass(Animal.class, area(pasture.position(), 32, 8), CitizenWorkService::capturable)) {
                if (!com.hkin.exiledsettlement.region.ProductionRegionService.matchesAnimal(pasture,animal)
                        || insideOperationalPastureInterior(settlement, animal.blockPosition())
                        || !claimed.add(animal.getUUID())) continue;
                BlockPos destination = pastureCaptureDestination(level, pasture, animal.blockPosition());
                if (destination == null) continue;
                return citizen.startCapture(pasture.id(), destination, animal.getUUID());
            }
        }
        return false;
    }

    private static boolean assignHunt(
            ServerLevel level, SettlementRecord settlement, CitizenRecord citizen, Set<UUID> claimed
    ) {
        BuildingRecord lodge = workplace(settlement, citizen, BuildingType.HUNTING_LODGE);
        if (lodge == null) return false;
        for (Animal animal : level.getEntitiesOfClass(Animal.class, area(lodge.position(), 48, 12), CitizenWorkService::huntable)) {
            if (protectedFromHunting(level, settlement, animal)) continue;
            if (claimed.add(animal.getUUID())) {
                return citizen.startTask(CitizenWorkState.TO_HUNT, animal.blockPosition(), animal.getUUID(), null);
            }
        }
        return false;
    }

    private static boolean assignToolmaking(ServerLevel level, SettlementRecord settlement, CitizenRecord citizen) {
        BuildingRecord workshop = workplace(settlement, citizen, BuildingType.TOOL_WORKSHOP);
        return workshop != null && !toolStockAtLimit(level, settlement)
                && citizen.startTask(CitizenWorkState.TO_TOOL_WORKSHOP, workshop.position(), null, null);
    }

    private static boolean assignSawmill(
            ServerLevel level, SettlementRecord settlement, CitizenRecord citizen
    ) {
        BuildingRecord sawmill = workplace(settlement, citizen, BuildingType.SAWMILL);
        if (sawmill == null) return false;
        if (citizen.findInventorySlot(stack -> isCarpenterInput(stack.getItem())) >= 0) {
            return citizen.startTask(CitizenWorkState.TO_SAWMILL, sawmill.position(), null, null);
        }
        BuildingRecord warehouse = SettlementInventoryService.warehouses(settlement).stream()
                .filter(BuildingRecord::operational)
                .filter(candidate -> WarehouseInventoryService.hasCreativeSupply(level, candidate)
                        || WarehouseInventoryService.summarize(level, candidate).keySet().stream()
                        .anyMatch(CitizenWorkService::isCarpenterInput))
                .min(Comparator.comparingDouble(candidate -> candidate.position().distSqr(citizen.lastPosition())))
                .orElse(null);
        return warehouse != null && citizen.startSawmillSupply(warehouse.id(), sawmill.position());
    }

    private static boolean toolStockAtLimit(ServerLevel level, SettlementRecord settlement) {
        int available = SettlementInventoryService.countCategory(level, settlement, WarehouseCategory.TOOLS);
        int inTransit = 0;
        for (CitizenRecord resident : settlement.citizens()) {
            for (int slot = 0; slot < CitizenRecord.INVENTORY_SIZE; slot++) {
                ItemStack stack = resident.inventoryItem(slot);
                if (!stack.isEmpty() && WarehouseCategory.classify(stack) == WarehouseCategory.TOOLS
                        && !CitizenEquipmentService.isReservedEquipment(resident, slot)) {
                    inTransit += stack.getCount();
                }
            }
        }
        return available + inTransit >= settlement.toolStockLimit();
    }

    private static boolean assignDemolition(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, Set<BlockPos> claimedPositions
    ) {
        List<BuildingRecord> demolitionSites = settlement.buildings().stream()
                .filter(BuildingRecord::active)
                .filter(BuildingRecord::demolitionQueued)
                .filter(building -> building.worksiteAssignmentMode() == WorksiteAssignmentMode.AUTO)
                .sorted(Comparator
                        .comparingInt((BuildingRecord building) -> assignedDemolisherCount(settlement, building.id()))
                        .thenComparingDouble(building -> building.position().distSqr(citizen.lastPosition()))
                        .thenComparing(building -> building.id().toString()))
                .toList();
        for (BuildingRecord building : demolitionSites) {
            if (assignDemolitionAt(level, data, settlement, citizen, claimedPositions, building)) return true;
        }
        return assignBlueprintCleanup(level, data, settlement, citizen, claimedPositions, false);
    }

    private static boolean assignDemolitionAt(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, Set<BlockPos> claimedPositions, BuildingRecord building
    ) {
        if (!building.demolitionQueued() || !BuildingDemolitionService.areaLoaded(level, building)) return false;
        BlockPos target = BuildingDemolitionService.nextBlock(
                level, building, claimedPositions, citizen.lastPosition());
        if (target == null) {
            if (BuildingDemolitionService.nextBlock(level, building) == null
                    && settlement.finishBuildingDemolition(building.id())) data.touch();
            return false;
        }
        return claimedPositions.add(target) && citizen.startDemolition(building.id(), target);
    }

    private static boolean assignBlueprintCleanup(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, Set<BlockPos> claimedPositions, boolean completedConstructionOnly
    ) {
        for (BlueprintRecord blueprint : List.copyOf(settlement.blueprints())) {
            if (blueprint.assignmentMode() == WorksiteAssignmentMode.MANUAL) continue;
            boolean completedFootingCleanup = BlueprintService.readyForFootingCleanup(level, blueprint);
            boolean eligible = completedConstructionOnly
                    ? !blueprint.cancellationQueued() && completedFootingCleanup
                    : blueprint.cancellationQueued();
            if (!eligible) continue;
            if (assignBlueprintCleanupAt(level, data, settlement, citizen, claimedPositions, blueprint)) return true;
        }
        return false;
    }

    private static boolean assignBlueprintCleanupAt(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, Set<BlockPos> claimedPositions, BlueprintRecord blueprint
    ) {
        if (assignedBlueprintCleanupCount(settlement, blueprint.id()) >= MAX_DEMOLISHERS_PER_SITE) return false;
        BlueprintPlacement cleanup = BlueprintCancellationService.nextCleanup(level, blueprint, claimedPositions);
        if (cleanup == null) {
            if (BlueprintCancellationService.nextCleanup(level, blueprint, Set.of()) != null) return false;
            if (blueprint.cancellationQueued()) {
                if (settlement.removeBlueprint(blueprint.id())) data.touch();
            } else if (BlueprintService.completeAfterFootingCleanup(
                    level, data, settlement, blueprint).success()) {
                ProfessionAssignmentService.refresh(data, settlement);
            }
            return false;
        }
        return claimedPositions.add(cleanup.position())
                && citizen.startTask(CitizenWorkState.TO_DEMOLITION,
                cleanup.position(), null, blueprint.id());
    }

    private static int assignedDemolisherCount(SettlementRecord settlement, UUID buildingId) {
        return (int) settlement.citizens().stream()
                .filter(citizen -> citizen.workState() == CitizenWorkState.TO_DEMOLITION)
                .filter(citizen -> buildingId.equals(citizen.targetBuildingId()))
                .count();
    }

    private static int assignedBlueprintCleanupCount(SettlementRecord settlement, UUID blueprintId) {
        return (int) settlement.citizens().stream()
                .filter(citizen -> citizen.workState() == CitizenWorkState.TO_DEMOLITION)
                .filter(citizen -> blueprintId.equals(citizen.targetBlueprintId()))
                .count();
    }

    private static boolean assignRepair(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, Set<BlockPos> claimed
    ) { return assignRepair(level, data, settlement, citizen, claimed, false); }

    private static boolean assignRepair(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, Set<BlockPos> claimed, boolean decoration) {
        if (settlement.community().policy.equals("emergency")) return false;
        for (BuildingRecord building : settlement.buildings()) {
            var feature = DecorationService.forBuilding(settlement, building.id());
            if(com.hkin.exiledsettlement.region.FarmConstructionPolicy.farm(building))continue;
            if ((feature != null) != decoration) continue;
            if (feature != null && (!DecorationService.allowed(settlement, feature)
                    || settlement.blueprints().stream().anyMatch(b -> !DecorationService.owned(settlement, b.id()) && !b.cancellationQueued()))) continue;
            for (BlockPos position : List.copyOf(building.repairPositions())) {
                if(com.hkin.exiledsettlement.building.MobRecoveryService.owns(settlement,position))continue;
                BlockState expected = BuildingRepairService.expectedState(level, settlement, building, position);
                if (expected == null || BuildingRepairService.satisfied(level, building, position)) {
                    if (building.clearRepairPosition(position)) data.touch();
                    continue;
                }
                var repairTemplate = BlueprintTemplates.createForBuilding(building);
                var repairPlacement = repairTemplate.placementAt(position);
                if (repairPlacement != null && com.hkin.exiledsettlement.blueprint.BlueprintPlacementGroups
                        .claimed(repairTemplate, repairPlacement, claimed)) continue;
                if (claimed.add(position)) {
                    for (var part : com.hkin.exiledsettlement.blueprint.BlueprintPlacementGroups.parts(
                            repairTemplate, repairPlacement)) claimed.add(part.position());
                    return citizen.startRepair(building.id(), position);
                }
            }
        }
        if (decoration) return false;
        for (BlockPos position : List.copyOf(settlement.roadRepairPositions())) {
            if(com.hkin.exiledsettlement.building.MobRecoveryService.owns(settlement,position))continue;
            BlockState expected = BuildingRepairService.expectedState(level, settlement, null, position);
            if (expected == null || level.getBlockState(position).equals(expected)) {
                if (settlement.clearRoadRepair(position)) data.touch();
                continue;
            }
            // Live automatic roads already have shared, supported earthwork
            // and paid paving. Do not bypass that chain with a separate direct
            // path-block repair; keep the damage record until real restoration.
            if (settlement.autoRoads().routes.values().stream()
                    .anyMatch(route -> !route.suppressed && route.containsCell(position))) continue;
            if (claimed.add(position)) return citizen.startRepair(null, position);
        }
        return false;
    }

    private static boolean assignForestry(
            ServerLevel level, SettlementRecord settlement, CitizenRecord citizen, Set<BlockPos> claimed
    ) {
        BuildingRecord treeFarm = workplace(settlement, citizen, BuildingType.TREE_FARM);
        if (treeFarm == null) return false;
        for (BlockPos position : forestryTargets(level, treeFarm)) {
            if (claimed.contains(position) || forestryTargetDeferred(level, citizen, position)
                    || !level.hasChunkAt(position) || forestrySeed(level, treeFarm, position) == null
                    || level.getBlockState(position.below()).is(BlockTags.LOGS)) continue;
            if (citizen.startForestry(treeFarm.id(), position)) {
                claimed.add(position);
                return true;
            }
        }
        if (!CitizenEquipmentService.hasPlantingStock(citizen)) {
            citizen.setActivity(CitizenActivity.BLOCKED, "FORESTRY_SAPLINGS_MISSING");
            return false;
        }
        for (BlockPos planting : treeFarm.servicePositions()) {
            if (!isTreeFarmPlantingGrid(treeFarm, planting) || claimed.contains(planting)
                    || forestryTargetDeferred(level, citizen, planting)
                    || treeFarmPlantingPlan(level, treeFarm, planting, null) == null) continue;
            boolean spaced = settlement.citizens().stream().filter(other -> other.workState() == CitizenWorkState.TO_FOREST
                    && treeFarm.id().equals(other.targetBuildingId()) && other.targetPosition() != null)
                    .noneMatch(other -> Math.abs(other.targetPosition().getX() - planting.getX()) < 5
                            && Math.abs(other.targetPosition().getZ() - planting.getZ()) < 5);
            if (spaced && citizen.startForestry(treeFarm.id(), planting)) {
                claimed.add(planting.immutable());
                return true;
            }
        }
        citizen.setActivity(CitizenActivity.BLOCKED, "FORESTRY_PLANTING_SPACE");
        return false;
    }

    private static List<BlockPos> forestryTargets(ServerLevel level, BuildingRecord treeFarm) {
        Map<UUID, ForestryTargets> cache = FORESTRY_TARGETS.computeIfAbsent(level, ignored -> new HashMap<>());
        long now = level.getGameTime();
        ForestryTargets cached = cache.get(treeFarm.id());
        if (cached != null && cached.expiresAt() > now) return cached.trunks();
        cache.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        Set<BlockPos> trunks = new LinkedHashSet<>();
        Set<BlockPos> remainingRoots = new LinkedHashSet<>();
        int minX = treeFarm.minCorner().getX() + 1;
        int maxX = treeFarm.maxCorner().getX() - 1;
        int minZ = treeFarm.minCorner().getZ() + 1;
        int maxZ = treeFarm.maxCorner().getZ() - 1;
        int minY = treeFarm.position().getY();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!level.hasChunkAt(new BlockPos(x, minY, z))) continue;
                int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
                for (int y = minY; y < top; y++) {
                    BlockPos position = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(position);
                    BlockState below = level.getBlockState(position.below());
                    // Muddy mangrove roots are also tagged as logs in vanilla.
                    // They remain root fallbacks, never outrank the real trunk.
                    if (state.is(BlockTags.LOGS) && !isMangroveRoot(state)
                            && !(below.is(BlockTags.LOGS) && !isMangroveRoot(below))) {
                        trunks.add(forestryWorkBase(level, treeFarm, position));
                    } else if (isMangroveRoot(state) && !below.is(BlockTags.LOGS) && !isMangroveRoot(below)) {
                        remainingRoots.add(forestryWorkBase(level, treeFarm, position));
                    }
                }
            }
        }
        // Prefer the trunk's planting base; lateral roots are only fallback targets.
        trunks.addAll(remainingRoots);
        List<BlockPos> result = List.copyOf(trunks);
        cache.put(treeFarm.id(), new ForestryTargets(now + FORESTRY_TARGET_REFRESH_TICKS, result));
        return result;
    }

    private static boolean isTreeFarmPlantingGrid(BuildingRecord treeFarm, BlockPos position) {
        int originX = treeFarm.region()==null?treeFarm.minCorner().getX()+2:treeFarm.region().gridX;
        int originZ = treeFarm.region()==null?treeFarm.minCorner().getZ()+2:treeFarm.region().gridZ;
        return Math.floorMod(position.getX() - originX, 5) == 0
                && Math.floorMod(position.getZ() - originZ, 5) == 0;
    }

    private static boolean assignFishing(
            ServerLevel level, SettlementRecord settlement, CitizenRecord citizen, Set<BlockPos> claimed
    ) {
        BuildingRecord hut = workplace(settlement, citizen, BuildingType.FISHING_HUT);
        if (hut == null) return false;
        for (BlockPos water : BlockPos.betweenClosed(
                hut.position().offset(-12, -4, -12), hut.position().offset(12, 4, 12))) {
            if (!level.getFluidState(water).is(FluidTags.WATER)) continue;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos shore = water.relative(direction);
                BlockPos standing = shore.above();
                if (level.getBlockState(shore).isFaceSturdy(level, shore, Direction.UP)
                        && level.getBlockState(standing).getCollisionShape(level, standing).isEmpty()
                        && claimed.add(standing.immutable())) {
                    return citizen.startTask(CitizenWorkState.TO_FISH, standing, null, null);
                }
            }
        }
        return false;
    }

    private static void collectOutput(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        BuildingRecord source = settlement.building(citizen.sourceBuildingId());
        BuildingRecord warehouse = settlement.building(citizen.targetBuildingId());
        // Also revoke old, uncollected worker orders after upgrading. Cargo already held is preserved.
        if(source!=null && source.type()==BuildingType.MINE && !mineRestCollection(level,settlement,citizen)) {
            clear(data,citizen,entity);return;
        }
        if (!usable(source) || !usable(warehouse, BuildingType.WAREHOUSE)) {
            clear(data, citizen, entity);
            return;
        }
        boolean keeping = citizen.profession() == CitizenProfession.WAREHOUSE_KEEPER;
        boolean consolidate = keeping && source.id().equals(warehouse.id());
        if (consolidate) {
            BlockPos station = WarehouseInventoryService.reserveInteractionPosition(level, source, entity);
            if (station == null) { waitForWarehouseStation(level, data, citizen, entity, source, CitizenActivity.TO_OUTPUT_CONTAINER); return; }
            if (!WarehouseInventoryService.canInteract(level, source, entity)) {
                moveToWarehouseStation(level, data, citizen, entity, station, CitizenActivity.TO_OUTPUT_CONTAINER); return;
            }
            int changed = WarehouseInventoryService.organizeForResident(level, source, entity);
            if (changed > 0) {
                com.hkin.exiledsettlement.settlement.CommunityStock.invalidate(level, settlement);
                com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level, citizen, "warehouse_organize", source.position(), "slots_or_items", changed);
                settlement.community().stewardStatus = "仓管正在合并和分类排列整座仓库";
            }
            if (changed == 0) clear(data, citizen, entity);
            data.touch(); return;
        }
        Set<Item> loadable = keeping && !consolidate
                ? warehouseTransferItems(level, source, warehouse, citizen)
                : loadableOutputItems(level, source, citizen);
        if(source.type()==BuildingType.MINE)loadable.removeIf(item->!WarehouseInventoryService.canAccept(level,warehouse,new ItemStack(item)));
        if (loadable.isEmpty()) {
            finishOutputCollection(data, citizen, entity);
            return;
        }
        BlockPos target = WarehouseInventoryService.reserveExtractionPosition(level, source, entity, loadable);
        if (target == null) {
            waitForWarehouseStation(level, data, citizen, entity, source, CitizenActivity.TO_OUTPUT_CONTAINER);
            return;
        }
        if (nearWorkPosition(entity, target)) {
            if (!WarehouseInventoryService.canInteract(level, source, entity)) {
                WarehouseInventoryService.abandonInteractionPosition(level, entity);
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "WAREHOUSE_INACCESSIBLE");
                return;
            }
            if (consolidate) {
                WarehouseInventoryService.mergeStacksForResident(level, source, entity);
                clear(data, citizen, entity);
                return;
            }
            int loaded = 0;
            for (int attempt = 0; attempt < CitizenRecord.INVENTORY_SIZE && citizen.hasInventoryCapacity(); attempt++) {
                ItemStack extracted = ItemStack.EMPTY;
                for (Item item : loadable) {
                    extracted = WarehouseInventoryService.extractItemForResident(level, source, entity, item,
                            Math.min(TRANSFER_STACK_LIMIT, citizen.inventoryCapacityFor(new ItemStack(item))));
                    if (!extracted.isEmpty()) break;
                }
                if (extracted.isEmpty()) break;
                int offered = extracted.getCount();
                ItemStack remaining = citizen.insertIntoInventory(extracted);
                int inserted = offered - remaining.getCount();
                if (!remaining.isEmpty()) WarehouseInventoryService.insertForResident(level, source, entity, remaining);
                if (inserted <= 0) break;
                loaded += inserted;
            }
            if (loaded > 0 && !(keeping ? warehouseTransferItems(level, source, warehouse, citizen)
                    : loadableOutputItems(level, source, citizen)).isEmpty()) {
                WarehouseInventoryService.releaseInteractionPosition(level, entity);
                setActivity(data, citizen, entity, CitizenActivity.TO_OUTPUT_CONTAINER, "");
                data.touch();
                return;
            }
            finishOutputCollection(data, citizen, entity);
            return;
        }
        moveToWarehouseStation(level, data, citizen, entity, target, CitizenActivity.TO_OUTPUT_CONTAINER);
    }

    private static Set<Item> loadableOutputItems(
            ServerLevel level, BuildingRecord source, CitizenRecord citizen
    ) {
        Set<Item> items = new LinkedHashSet<>();
        for (var containerAt : WarehouseInventoryService.loadedContainers(level, source)) {
            Container container = containerAt.container();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty() && !stack.is(com.hkin.exiledsettlement.registry.ModItems.CREATIVE_SUPPLY_CORE.get())
                        && citizen.inventoryCapacityFor(stack) > 0) items.add(stack.getItem());
            }
        }
        return items;
    }

    private static void finishOutputCollection(
            SettlementSavedData data, CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        int firstLoadedSlot = deliverableSlot(citizen);
        if (firstLoadedSlot < 0) {
            clear(data, citizen, entity);
            return;
        }
        entity.stopSettlementNavigation();
        citizen.markInventoryPickedUp(firstLoadedSlot);
        syncHeldItem(citizen, entity);
        setActivity(data, citizen, entity, CitizenActivity.TO_WAREHOUSE, "");
        data.touch();
    }

    private static void finishEquipmentWork(ServerLevel level,SettlementSavedData data,SettlementRecord settlement,CitizenRecord citizen,SettlementCitizenEntity entity){
        UUID resume=citizen.constructionResumeId();BlockPos at=citizen.constructionResumePosition();
        UUID mother=citizen.motherTaskId();
        clear(data,citizen,entity);var site=settlement.blueprint(resume);
        if(site!=null && !site.cancellationQueued() && at!=null){citizen.startTask(CitizenWorkState.TO_BLUEPRINT,at,null,resume);citizen.setMotherTask(mother);}
        data.touch();
    }
    private static void fetchEquipment(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        Item required = CitizenEquipmentService.nextEquipment(level, settlement, citizen);
        if (required == null) {
            finishEquipmentWork(level,data,settlement,citizen,entity);
            return;
        }
        BuildingRecord warehouse = settlement.building(citizen.targetBuildingId());
        if (!(usable(warehouse) && warehouse.type().isStorage())
                || WarehouseInventoryService.countItem(level, warehouse, required) <= 0
                && !WarehouseInventoryService.hasCreativeSupply(level, warehouse)) {
            warehouse = CitizenEquipmentService.supplyWarehouse(level, settlement, citizen, entity.blockPosition(), required);
            UUID warehouseId = warehouse == null ? null : warehouse.id();
            if (!java.util.Objects.equals(warehouseId, citizen.targetBuildingId())) {
                UUID resume=citizen.constructionResumeId();BlockPos resumeAt=citizen.constructionResumePosition();
                UUID mother=citizen.motherTaskId();
                clear(data, citizen, entity);
                citizen.startEquipmentFetch(warehouseId,
                        warehouse == null ? settlement.corePosition() : warehouse.position());
                citizen.setConstructionResume(resume,resumeAt);
                citizen.setMotherTask(mother);
                data.touch();
            }
        }
        boolean useWarehouse = (usable(warehouse) && warehouse.type().isStorage());
        boolean coreSupply = settlement.creativeCoreInstalled();
        boolean virtualSupply = useWarehouse
                ? WarehouseInventoryService.hasCreativeSupply(level, warehouse) : coreSupply;
        if (!useWarehouse && !coreSupply) {
            releaseSupplyStation(level, entity);
            entity.stopSettlementNavigation();
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "PROFESSION_EQUIPMENT_MISSING");
            return;
        }
        BlockPos target = useWarehouse
                ? WarehouseInventoryService.reserveExtractionPosition(level, warehouse, entity, required)
                : SettlementCoreInteractionService.reserveSupplyPosition(level, settlement, entity);
        if (target == null) {
            waitForSupplyStation(level, data, settlement, citizen, entity, warehouse,
                    useWarehouse, CitizenActivity.FETCHING_EQUIPMENT);
            return;
        }
        if (!nearWorkPosition(entity, target)) {
            moveToWarehouseStation(level, data, citizen, entity, target, CitizenActivity.FETCHING_EQUIPMENT);
            return;
        }
        if (useWarehouse && !WarehouseInventoryService.canInteract(level, warehouse, entity)) {
            abandonSupplyStation(level, entity);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "WAREHOUSE_INACCESSIBLE");
            return;
        }
        if (!useWarehouse && !SettlementCoreInteractionService.canReceiveSupply(level, settlement, entity)) {
            abandonSupplyStation(level, entity);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "CORE_SUPPLY_INACCESSIBLE");
            return;
        }
        int carryLimit = Math.min(required==Items.DIRT && citizen.profession()==CitizenProfession.WORKER
                        ? PitEscapeService.RESERVE-citizen.pitReserve().getCount()
                        : CitizenEquipmentService.isForestrySeed(required) ? 16 : 1,
                citizen.equipmentCapacityFor(new ItemStack(required)));
        ItemStack extracted = useWarehouse
                ? WarehouseInventoryService.extractItemForResident(
                level, warehouse, entity, required, carryLimit) : ItemStack.EMPTY;
        if (extracted.isEmpty() && virtualSupply && carryLimit > 0) extracted = new ItemStack(required, carryLimit);
        if (extracted.isEmpty()) {
            releaseSupplyStation(level, entity);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "PROFESSION_EQUIPMENT_MISSING");
            return;
        }
        ItemStack remaining = citizen.receiveEquipment(extracted);
        if (!remaining.isEmpty()) {
            if (useWarehouse) {
                WarehouseInventoryService.insertForResident(level, warehouse, entity, remaining);
            }
            releaseSupplyStation(level, entity);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "INVENTORY_FULL");
            return;
        }
        com.hkin.exiledsettlement.settlement.CommunityLifeService.topUpRations(level, settlement, citizen, entity, warehouse);
        CitizenEquipmentService.topUpPersonalTools(level, settlement, citizen, entity, warehouse);
        CitizenEquipmentService.topUpConstructionTools(level, settlement, citizen, entity);
        SITE_SUCCESS_TICKS.put(entity,level.getGameTime());
        com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,citizen,"resident_equipment_pickup",target,
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(required).toString(),extracted.getCount());
        releaseSupplyStation(level, entity);
        finishEquipmentWork(level,data,settlement,citizen,entity);
        syncHeldItem(citizen, entity);
        data.touch();
    }

    private static void deliverWarehouse(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        if(citizen.profession()==CitizenProfession.MINER && schedulePhase(level.getDayTime(),citizen)!=SchedulePhase.REST) {
            clear(data,citizen,entity);return; // Retain carried items for the next rest period or local mine storage.
        }
        BuildingRecord warehouse = settlement.building(citizen.targetBuildingId());
        int allowed = personalDeliveryCount(settlement, citizen, citizen.activeSlot());
        if (allowed <= 0) {
            int next = deliverableSlot(settlement, citizen, true);
            if (next < 0) { clear(data, citizen, entity); syncHeldItem(citizen, entity); return; }
            citizen.selectActiveSlot(next);
            allowed = personalDeliveryCount(settlement, citizen, next);
            data.touch();
        }
        ItemStack carried = citizen.carriedItem().copyWithCount(allowed);
        if (!usable(warehouse, BuildingType.WAREHOUSE)
                || !WarehouseInventoryService.canAccept(level, warehouse, carried)) {
            warehouse = acceptingWarehouse(level, settlement, entity.blockPosition(), carried);
            if (warehouse == null) {
                entity.stopSettlementNavigation();
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, WarehouseInventoryService.deliveryBlocker(level,settlement,carried));
                return;
            }
            int carriedSlot = citizen.activeSlot();
            clear(data, citizen, entity);
            citizen.startInventoryDelivery(warehouse.id(), carriedSlot);
            data.touch();
        }
        BlockPos target = WarehouseInventoryService.reserveInsertionPosition(level, warehouse, entity, carried);
        if (target == null) {
            waitForWarehouseStation(level, data, citizen, entity, warehouse, CitizenActivity.TO_WAREHOUSE);
            return;
        }
        if (nearWorkPosition(entity, target)) {
            if (!WarehouseInventoryService.canInteract(level, warehouse, entity)) {
                if (continueWarehouseEntry(level, warehouse, entity, target)) {
                    setActivity(data, citizen, entity, CitizenActivity.TO_WAREHOUSE, "");
                    return;
                }
                WarehouseInventoryService.abandonInteractionPosition(level, entity);
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "WAREHOUSE_INACCESSIBLE");
                return;
            }
            if (citizen.sourceBuildingId() == null
                    && schedulePhase(level.getDayTime(), citizen) == SchedulePhase.REST) {
                depositRestCargo(level, data, settlement, citizen, entity, warehouse);
                return;
            }
            ItemStack remaining = WarehouseInventoryService.insertForResident(level, warehouse, entity, carried);
            if (carried.getCount() > remaining.getCount()) com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(
                    level,citizen,"warehouse_deposit",warehouse.position(),
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(carried.getItem()).toString(),carried.getCount()-remaining.getCount());
            ItemStack kept = citizen.carriedItem();
            kept.shrink(carried.getCount() - remaining.getCount());
            citizen.updateCarriedItem(kept);
            syncHeldItem(citizen, entity);
            if (!remaining.isEmpty() && WarehouseInventoryService.canAccept(level, warehouse, remaining)) {
                WarehouseInventoryService.releaseInteractionPosition(level, entity);
                setActivity(data, citizen, entity, CitizenActivity.TO_WAREHOUSE, "");
                data.touch();
                return;
            }
            if (remaining.isEmpty()) {
                int next = deliverableSlot(settlement, citizen, true);
                if (next >= 0) {
                    citizen.selectActiveSlot(next);
                    syncHeldItem(citizen, entity);
                    data.touch();
                } else {
                    com.hkin.exiledsettlement.settlement.CommunityLifeService.topUpRations(level, settlement, citizen, entity, warehouse);
                    CitizenEquipmentService.topUpPersonalTools(level, settlement, citizen, entity, warehouse);
                    clear(data, citizen, entity);
                    syncHeldItem(citizen, entity);
                }
            }
            else setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "OUTPUT_FULL");
            return;
        }
        if (!WarehouseInventoryService.canAccept(level, warehouse, carried)) {
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "OUTPUT_FULL");
            return;
        }
        moveToWarehouseStation(level, data, citizen, entity, target, CitizenActivity.TO_WAREHOUSE);
    }

    /** Finish a short physical step when the arrival radius overlaps the outside wall column. */
    private static boolean continueWarehouseEntry(ServerLevel level, BuildingRecord warehouse,
            SettlementCitizenEntity entity, BlockPos target) {
        if (warehouse.type() != BuildingType.WAREHOUSE || !entity.onGround()
                || entity.hasActiveConstructionMovement() || !nearWorkPosition(entity, target)
                || target.getY() != entity.blockPosition().getY()
                || target.getX() <= warehouse.minCorner().getX() || target.getX() >= warehouse.maxCorner().getX()
                || target.getZ() <= warehouse.minCorner().getZ() || target.getZ() >= warehouse.maxCorner().getZ()
                || target.getY() < warehouse.minCorner().getY() || target.getY() >= warehouse.maxCorner().getY()
                || !WarehouseInventoryService.canStand(level, target)) return false;
        var block = level.getBlockState(target).getBlock();
        if (block instanceof net.minecraft.world.level.block.DoorBlock
                || block instanceof FenceGateBlock) return false;
        double x = target.getX() + 0.5D, z = target.getZ() + 0.5D;
        if (!level.noCollision(entity, entity.getBoundingBox().expandTowards(x - entity.getX(), 0, z - entity.getZ()))) return false;
        // The navigator can already consider this node reached. MoveControl still
        // walks under normal collision/physics; insertion waits for canInteract.
        entity.getNavigation().stop();
        entity.getMoveControl().setWantedPosition(x, entity.getY(), z, WALK_SPEED);
        return true;
    }

    /** One bounded backpack pass; every insertion still requires physically reachable storage. */
    private static void depositRestCargo(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity, BuildingRecord warehouse) {
        for (int slot = 0; slot < CitizenRecord.INVENTORY_SIZE; slot++) {
            int count = personalDeliveryCount(settlement, citizen, slot);
            if (count <= 0) continue;
            ItemStack stack = citizen.inventoryItem(slot);
            ItemStack remaining = WarehouseInventoryService.insertForResident(level, warehouse, entity, stack.copyWithCount(count));
            int deposited = count - remaining.getCount();
            if (deposited > 0) {
                com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,citizen,"warehouse_deposit",warehouse.position(),
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),deposited);
                citizen.selectActiveSlot(slot);
                ItemStack kept = stack.copy();
                kept.shrink(deposited);
                citizen.updateCarriedItem(kept);
            }
        }
        int next = deliverableSlot(settlement, citizen, true);
        WarehouseInventoryService.releaseInteractionPosition(level, entity);
        if (next >= 0) {
            citizen.selectActiveSlot(next);
            // Remaining cargo may need a different container or warehouse. The normal
            // delivery branch resolves its route on the next scheduler cycle.
            setActivity(data, citizen, entity, CitizenActivity.TO_WAREHOUSE, "");
        } else {
            com.hkin.exiledsettlement.settlement.CommunityLifeService.topUpRations(level, settlement, citizen, entity, warehouse);
            CitizenEquipmentService.topUpPersonalTools(level, settlement, citizen, entity, warehouse);
            clear(data, citizen, entity);
        }
        syncHeldItem(citizen, entity);
        data.touch();
    }

    private static void farm(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        if(citizen.profession()!=CitizenProfession.FARMER && citizen.profession()!=CitizenProfession.MELON_FARMER){clear(data,citizen,entity);return;}
        if (com.hkin.exiledsettlement.settlement.CommunityLifeService.winter(settlement,
                com.hkin.exiledsettlement.settlement.CommunityLifeService.day(level))) {
            clear(data, citizen, entity); return;
        }
        var regionalFarm=farmWorkplace(settlement,citizen);
        var regionalCell=regionalFarm==null || regionalFarm.region()==null || citizen.targetPosition()==null?null:regionalFarm.region().cell(citizen.targetPosition());
        if (regionalCell!=null?regionalCell.type()==BuildingType.MELON_FIELD:citizen.profession()==CitizenProfession.MELON_FARMER) {
            melonFarm(level, data, settlement, citizen, entity);
            return;
        }
        BlockPos target = citizen.targetPosition();
        BuildingRecord farm = workplace(settlement, citizen, BuildingType.FARM_FIELD);
        if (target == null || farm == null) {
            clear(data, citizen, entity);
            return;
        }
        BlockState targetState = level.getBlockState(target);
        BlockPos ground = targetState.getBlock() instanceof CropBlock ? target.below() : target;
        if (!enterFarmWorkArea(level,data,citizen,entity,farm,target)) return;
        if (!farmActionReady(level, citizen)) return;
        int toolSlot = CitizenEquipmentService.toolSlotForWork(citizen);
        if (toolSlot < 0) {
            clear(data, citizen, entity);
            return;
        }

        BlockState groundState = level.getBlockState(ground);
        if ((groundState.is(Blocks.FARMLAND) || repairableFarmGround(groundState))
                && clearFarmCover(level,data,settlement,citizen,entity,ground)) return;
        if (repairableFarmGround(groundState)) {
            if (!level.getBlockState(ground.above()).isAir()) { deferFarmTarget(level,citizen,target); clear(data,citizen,entity); return; }
            BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, ground, Block.UPDATE_CLIENTS);
            if (!level.setBlock(ground, Blocks.FARMLAND.defaultBlockState(), Block.UPDATE_ALL)
                    || EventHooks.onBlockPlace(null, snapshot, Direction.UP)) {
                snapshot.restore(Block.UPDATE_ALL);
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "PROTECTED_AREA");
                return;
            }
            citizen.damageInventoryItem(toolSlot, 1);
            com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,citizen,"farm_till",ground,"minecraft:farmland",1);
            finishFarmTask(level, data, settlement, citizen, entity);
            return;
        }
        if (!groundState.is(Blocks.FARMLAND)) {
            clear(data, citizen, entity);
            return;
        }

        BlockPos cropPosition = ground.above();
        BlockState cropState = level.getBlockState(cropPosition);
        if (cropState.getBlock() instanceof CropBlock crop && crop.isMaxAge(cropState)) {
            List<ItemStack> drops = Block.getDrops(cropState, level, cropPosition,
                    level.getBlockEntity(cropPosition), entity, citizen.inventoryItem(toolSlot));
            for (ItemStack drop : drops) {
                int loss = com.hkin.exiledsettlement.settlement.CommunityLifeService.frostLoss(settlement,
                        com.hkin.exiledsettlement.settlement.CommunityLifeService.day(level), cropPosition, drop.getCount());
                if (drop.has(net.minecraft.core.component.DataComponents.FOOD) || drop.is(Items.WHEAT)) drop.shrink(loss);
            }
            BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, cropPosition, Block.UPDATE_CLIENTS);
            if (!level.setBlock(cropPosition, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
                    || EventHooks.onBlockPlace(null, snapshot, Direction.UP)) {
                snapshot.restore(Block.UPDATE_ALL);
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "PROTECTED_AREA");
                return;
            }
            boolean inventoryFull = false;
            for (ItemStack drop : drops) {
                com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,citizen,"farm_harvest",cropPosition,
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(drop.getItem()).toString(),drop.getCount());
                ItemStack remaining = citizen.insertIntoInventory(drop);
                if (!remaining.isEmpty()) {
                    inventoryFull = true;
                    Block.popResource(level, cropPosition, remaining);
                }
            }
            citizen.damageInventoryItem(toolSlot, 1);
            if (inventoryFull) citizen.updateWorkFailure("INVENTORY_FULL");
        } else if (!cropState.isAir()) {
            clear(data, citizen, entity);
            return;
        }

        Item seed = BuildingProductionService.plantingSeed(farm, citizen.targetPosition());
        if(seed==Items.WHEAT_SEEDS && com.hkin.exiledsettlement.settlement.WarehouseStewardService.pauseWheat(level,settlement)) {
            finishFarmTask(level,data,settlement,citizen,entity);return;
        }
        ItemStack plantingSeed = citizen.extractInventoryItem(stack -> stack.is(seed), 1);
        if (plantingSeed.isEmpty()) {
            BuildingRecord warehouse = SettlementInventoryService.supplyWarehouse(
                    level, settlement, entity.blockPosition(), seed);
            if (warehouse == null && !settlement.creativeCoreInstalled()) {
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "SEEDS_MISSING");
                return;
            }
            citizen.fetchFarmInput(warehouse == null ? null : warehouse.id());
            data.touch();
            return;
        }
        CropBlock desired = BuildingProductionService.crop(BuildingProductionService.productionAt(farm,citizen.targetPosition()));
        BlockSnapshot cropSnapshot = BlockSnapshot.create(level.dimension(), level, cropPosition, Block.UPDATE_CLIENTS);
        if (!level.setBlock(cropPosition, desired.getStateForAge(0), Block.UPDATE_ALL)
                || EventHooks.onBlockPlace(null, cropSnapshot, Direction.UP)) {
            cropSnapshot.restore(Block.UPDATE_ALL);
            citizen.insertIntoInventory(plantingSeed);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "PROTECTED_AREA");
            return;
        }
        com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,citizen,"farm_plant",cropPosition,
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(seed).toString(),1);
        finishFarmTask(level, data, settlement, citizen, entity);
    }

    private static void melonFarm(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        BlockPos target = citizen.targetPosition();
        BuildingRecord farm = workplace(settlement, citizen, BuildingType.MELON_FIELD);
        if (target == null || farm == null) { clear(data, citizen, entity); return; }
        if (!enterFarmWorkArea(level,data,citizen,entity,farm,target)) return;
        if (!farmActionReady(level, citizen)) return;
        int toolSlot = CitizenEquipmentService.toolSlotForWork(citizen);
        if (toolSlot < 0) { clear(data, citizen, entity); return; }
        BlockState state = level.getBlockState(target);
        if (state.is(Blocks.MELON) || state.is(Blocks.PUMPKIN)) {
            List<ItemStack> drops = Block.getDrops(state, level, target, level.getBlockEntity(target), entity,
                    citizen.inventoryItem(toolSlot));
            for (ItemStack drop : drops) if (drop.has(net.minecraft.core.component.DataComponents.FOOD))
                drop.shrink(com.hkin.exiledsettlement.settlement.CommunityLifeService.frostLoss(settlement,
                        com.hkin.exiledsettlement.settlement.CommunityLifeService.day(level), target, drop.getCount()));
            BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, target, Block.UPDATE_CLIENTS);
            if (!level.setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
                    || EventHooks.onBlockPlace(null, snapshot, Direction.UP)) {
                snapshot.restore(Block.UPDATE_ALL); setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "PROTECTED_AREA"); return;
            }
            for (ItemStack drop : drops) {
                ItemStack remaining = citizen.insertIntoInventory(drop);
                if (!remaining.isEmpty()) Block.popResource(level, target, remaining);
            }
            citizen.damageInventoryItem(toolSlot, 1);
            finishFarmTask(level, data, settlement, citizen, entity);
            return;
        }
        if ((state.is(Blocks.FARMLAND) || repairableFarmGround(state))
                && clearFarmCover(level,data,settlement,citizen,entity,target)) return;
        if (repairableFarmGround(state) && level.getBlockState(target.above()).isAir()) {
            BlockSnapshot groundSnapshot = BlockSnapshot.create(level.dimension(), level, target, Block.UPDATE_CLIENTS);
            if (!level.setBlock(target, Blocks.FARMLAND.defaultBlockState(), Block.UPDATE_ALL)
                    || EventHooks.onBlockPlace(null, groundSnapshot, Direction.UP)) {
                groundSnapshot.restore(Block.UPDATE_ALL);
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "PROTECTED_AREA"); return;
            }
            citizen.damageInventoryItem(toolSlot, 1);
            finishFarmTask(level, data, settlement, citizen, entity); return;
        }
        if (!state.is(Blocks.FARMLAND) || !level.getBlockState(target.above()).isAir()) {
            clear(data, citizen, entity); return;
        }
        Item desiredSeed = BuildingProductionService.plantingSeed(farm, target);
        ItemStack seed = citizen.extractInventoryItem(stack -> stack.is(desiredSeed), 1);
        if (seed.isEmpty()) {
            BuildingRecord warehouse = SettlementInventoryService.supplyWarehouse(
                    level, settlement, entity.blockPosition(), desiredSeed);
            if (warehouse == null && !settlement.creativeCoreInstalled()) {
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "SEEDS_MISSING"); return;
            }
            citizen.fetchFarmInput(warehouse == null ? null : warehouse.id()); data.touch(); return;
        }
        BlockPos crop = target.above();
        BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, crop, Block.UPDATE_CLIENTS);
        if (!level.setBlock(crop, (desiredSeed == Items.PUMPKIN_SEEDS ? Blocks.PUMPKIN_STEM : Blocks.MELON_STEM).defaultBlockState(), Block.UPDATE_ALL)
                || EventHooks.onBlockPlace(null, snapshot, Direction.UP)) {
            snapshot.restore(Block.UPDATE_ALL); citizen.insertIntoInventory(seed);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "PROTECTED_AREA"); return;
        }
        finishFarmTask(level, data, settlement, citizen, entity);
    }

    /** Clearing dry vegetation is its own on-site action; never replace a plant while tilling. */
    private static boolean clearFarmCover(ServerLevel level,SettlementSavedData data,SettlementRecord settlement,
            CitizenRecord citizen,SettlementCitizenEntity entity,BlockPos ground) {
        BlockPos cover=ground.above();
        if (!ScaffoldSitePreparation.softObstacle(level.getBlockState(cover))) return false;
        var cleared=com.hkin.exiledsettlement.blueprint.SiteClearance.clear(level,cover,entity,ItemStack.EMPTY,
                part -> settlement.contains(part) && !settlement.decorations().forbidden(part)
                        && !settlement.community().danger(part) && !settlement.terrain().suppressed.contains(part.asLong())
                        && ScaffoldSitePreparation.softObstacle(level.getBlockState(part)));
        if(!cleared.failure().isEmpty()) {
            setActivity(data,citizen,entity,CitizenActivity.BLOCKED,cleared.failure());return true;
        }
        for(var drop:cleared.drops()){var extra=citizen.insertIntoInventory(drop);if(!extra.isEmpty())Block.popResource(level,cover,extra);}
        setActivity(data,citizen,entity,CitizenActivity.FARMING,"");
        entity.swing(net.minecraft.world.InteractionHand.MAIN_HAND);data.touch();return true;
    }

    private static void fetchFarmInput(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        if(citizen.profession()!=CitizenProfession.FARMER && citizen.profession()!=CitizenProfession.MELON_FARMER){clear(data,citizen,entity);return;}
        BuildingRecord farm = farmWorkplace(settlement, citizen);
        BuildingRecord warehouse = settlement.building(citizen.targetBuildingId());
        if (farm == null) {
            clear(data, citizen, entity);
            return;
        }
        Item seed = BuildingProductionService.plantingSeed(farm, citizen.targetPosition());
        if (usable(warehouse,BuildingType.WAREHOUSE) && !WarehouseInventoryService.hasCreativeSupply(level,warehouse)
                && WarehouseInventoryService.countItem(level,warehouse,seed)==0) {
            // A previous trip can outlive the stock it was routed to. Resolve the
            // current real supplier again, then use an installed creative core only
            // through its normal on-site collection path when no stocked supplier exists.
            releaseSupplyStation(level,entity);
            var replacement=SettlementInventoryService.supplyWarehouse(level,settlement,entity.blockPosition(),seed);
            if (replacement==null && !settlement.creativeCoreInstalled()) {
                setActivity(data,citizen,entity,CitizenActivity.BLOCKED,"SEEDS_MISSING");return;
            }
            citizen.returnToFarm();citizen.fetchFarmInput(replacement==null?null:replacement.id());
            warehouse=replacement;
        }
        boolean useWarehouse = usable(warehouse, BuildingType.WAREHOUSE);
        boolean coreSupply = settlement.creativeCoreInstalled();
        boolean virtualSupply = useWarehouse
                ? WarehouseInventoryService.hasCreativeSupply(level, warehouse) : coreSupply;
        if (!useWarehouse && !coreSupply) {
            releaseSupplyStation(level, entity);
            entity.stopSettlementNavigation();
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "SEEDS_MISSING");
            return;
        }
        BlockPos destination = useWarehouse
                ? WarehouseInventoryService.reserveExtractionPosition(level, warehouse, entity, seed)
                : SettlementCoreInteractionService.reserveSupplyPosition(level, settlement, entity);
        if (destination == null) {
            waitForSupplyStation(level, data, settlement, citizen, entity, warehouse,
                    useWarehouse, CitizenActivity.FETCHING_FARM_INPUT);
            return;
        }
        if (!nearWorkPosition(entity, destination)) {
            moveToWarehouseStation(level, data, citizen, entity, destination,
                    CitizenActivity.FETCHING_FARM_INPUT);
            return;
        }
        if (useWarehouse && !WarehouseInventoryService.canInteract(level, warehouse, entity)) {
            abandonSupplyStation(level, entity);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "WAREHOUSE_INACCESSIBLE");
            return;
        }
        if (!useWarehouse && !SettlementCoreInteractionService.canReceiveSupply(level, settlement, entity)) {
            abandonSupplyStation(level, entity);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "CORE_SUPPLY_INACCESSIBLE");
            return;
        }
        int seedStackLimit = new ItemStack(seed).getMaxStackSize();
        ItemStack extracted = useWarehouse
                ? WarehouseInventoryService.extractItemForResident(level, warehouse, entity, seed, seedStackLimit)
                : ItemStack.EMPTY;
        if (extracted.isEmpty() && virtualSupply) extracted = new ItemStack(seed, seedStackLimit);
        if (extracted.isEmpty()) {
            releaseSupplyStation(level, entity);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "SEEDS_MISSING");
            return;
        }
        ItemStack remaining = citizen.insertIntoInventory(extracted);
        if (!remaining.isEmpty()) {
            if (useWarehouse) {
                WarehouseInventoryService.insertForResident(level, warehouse, entity, remaining);
            }
            releaseSupplyStation(level, entity);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "INVENTORY_FULL");
            return;
        }
        if (usable(warehouse, BuildingType.WAREHOUSE)
                && countInventoryItem(citizen, seed) < seedStackLimit
                && citizen.inventoryCapacityFor(new ItemStack(seed)) > 0
                && WarehouseInventoryService.countItem(level, warehouse, seed) > 0) {
            WarehouseInventoryService.releaseInteractionPosition(level, entity);
            setActivity(data, citizen, entity, CitizenActivity.FETCHING_FARM_INPUT, "");
            data.touch();
            return;
        }
        if(BuildingProductionService.productionAt(farm,citizen.targetPosition())!=null
                && farm.productionId().equals(BuildingProductionService.MIXED_CROPS)) {
            for(var crop:BuildingProductionService.FARM_CROPS) {
                Item otherSeed=BuildingProductionService.seed(crop);
                int wanted=Math.min(new ItemStack(otherSeed).getMaxStackSize()-countInventoryItem(citizen,otherSeed),
                        citizen.inventoryCapacityFor(new ItemStack(otherSeed)));
                if(wanted<=0)continue;
                ItemStack extra=useWarehouse?WarehouseInventoryService.extractItemForResident(level,warehouse,entity,otherSeed,wanted):ItemStack.EMPTY;
                if(extra.isEmpty() && virtualSupply)extra=new ItemStack(otherSeed,wanted);
                int received=extra.getCount();var overflow=citizen.insertIntoInventory(extra);received-=overflow.getCount();
                if(!overflow.isEmpty() && useWarehouse)WarehouseInventoryService.insertForResident(level,warehouse,entity,overflow);
                if(received>0)com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,citizen,"farm_seed_receive",
                        useWarehouse?warehouse.position():settlement.corePosition(),net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(otherSeed).toString(),received);
            }
        }
        releaseSupplyStation(level, entity);
        citizen.returnToFarm();
        syncHeldItem(citizen, entity);
        data.touch();
    }

    private static final Map<SettlementCitizenEntity, ScaffoldLogisticsExit> LOGISTICS_EXITS = new java.util.WeakHashMap<>();

    /** Logistics must not hand a high work pose straight to ground navigation. */
    private static boolean leaveScaffoldingBeforeLogistics(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        var previousExit = LOGISTICS_EXITS.get(entity);
        if (previousExit != null && settlement.blueprint(previousExit.siteId()) == null) {
            LOGISTICS_EXITS.remove(entity);
            entity.finishConstructionMovement();
            CitizenNavigationReservationService.releaseStation(level, entity.getUUID());
            previousExit = null;
        }
        BlockPos feet = constructionFeetPosition(entity);
        for (BlueprintRecord site : settlement.blueprints()) {
            if (previousExit != null && !previousExit.siteId().equals(site.id())) continue;
            boolean onSupport = occupiedTemporarySupport(level, site, feet) != null;
            var template=BlueprintTemplates.create(site);
            var roof=template.placementAt(feet.below());
            boolean onNearbyRoof = !ConstructionSiteController.groundWork(site)
                    && feet.getY() > template.minCorner().getY() + 2
                    && roof!=null && roof.mode().isFinalStructure() && !roof.state().isAir()
                    && BlueprintService.matchesPlacedState(level.getBlockState(feet.below()),roof.state())
                    && site.temporaryFootings().entrySet().stream().anyMatch(e -> e.getKey().distSqr(feet)<=100
                    && level.hasChunkAt(e.getKey()) && level.getBlockState(e.getKey()).is(Block.byItem(e.getValue())));
            if (!onSupport && !onNearbyRoof && (previousExit == null || !previousExit.siteId().equals(site.id()))) continue;
            if (previousExit == null || !previousExit.siteId().equals(site.id())) {
                entity.finishConstructionMovement();
                entity.getNavigation().stop();
                CitizenNavigationReservationService.releaseStation(level, entity.getUUID());
                previousExit = new ScaffoldLogisticsExit(site.id());
                LOGISTICS_EXITS.put(entity, previousExit);
            }
            var exit = previousExit;
            if (exit.advance(level, site, entity, WALK_SPEED)) {
                LOGISTICS_EXITS.remove(entity);
                return false;
            }
            if (entity.hasActiveConstructionMovement()) {
                setActivity(data, citizen, entity, CitizenActivity.FETCHING_BUILD_MATERIAL, "");
            } else {
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "SCAFFOLDING_EGRESS_BLOCKED");
            }
            return true;
        }
        LOGISTICS_EXITS.remove(entity);
        return false;
    }

    private static void fetchConstructionMaterial(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        if (releaseUnauthorizedConstruction(level, data, settlement, citizen, entity)) return;
        boolean footingRequest = citizen.workState() == CitizenWorkState.TO_FOOTING_SUPPLY;
        BlueprintRecord blueprint = settlement.blueprint(citizen.targetBlueprintId());
        BuildingRecord warehouse = settlement.building(citizen.targetBuildingId());
        if (leaveScaffoldingBeforeLogistics(level, data, settlement, citizen, entity)) return;
        if (blueprint != null && !builderWithinCap(settlement, citizen, blueprint.id())) {
            clear(data, citizen, entity);
            if (assignPersonalDelivery(level, settlement, citizen)) syncHeldItem(citizen, entity);
            data.touch();
            return;
        }
        BlueprintService.PendingPlacement pending = blueprint == null || citizen.targetPosition() == null
                ? null : BlueprintService.pendingAt(level, blueprint, citizen.targetPosition());
        // Recheck the current phase BEFORE requiring a supply source. An old structural
        // fetch can become unnecessary when live obstacles reopen clearance; retaining
        // it would occupy a building seat forever despite there being free excavation.
        boolean obsoleteFooting = footingRequest && blueprint != null
                && (ConstructionSiteController.groundWork(blueprint) || citizen.targetPosition() == null
                    || !blueprint.hasTemporaryFooting(citizen.targetPosition()));
        if (blueprint != null && (obsoleteFooting || !footingRequest && (pending == null || pending.placement().material() == null))) {
            var claimed = settlement.citizens().stream().filter(other -> other != citizen
                    && blueprint.id().equals(other.targetBlueprintId()))
                    .map(CitizenRecord::targetPosition).filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            var next = BlueprintService.nextUnclaimedPending(level,data,blueprint,claimed,entity.blockPosition());
            releaseSupplyStation(level,entity);
            clear(data,citizen,entity);
            if (next != null) citizen.startTask(CitizenWorkState.TO_BLUEPRINT,next.placement().position(),null,blueprint.id());
            data.touch();return;
        }
        Item required = footingRequest ? Items.SCAFFOLDING : pending == null ? null : pending.placement().material();
        if (!footingRequest && pending != null && blueprint.terrainProject() != null) {
            var plan = blueprint.terrainProject();
            var step = plan.steps.get(-pending.index() - 1);
            if (com.hkin.exiledsettlement.terrain.BuriedFillMaterials.hidden(level, settlement, plan, step)) {
                required = com.hkin.exiledsettlement.terrain.BuriedFillMaterials.choose(level, settlement, plan, step, citizen).getBlock().asItem();
                // A carried ordinary filler already pays for this cell. A different
                // warehouse palette must not force an unnecessary resupply trip.
                if (selectInventoryItem(citizen, required)) {
                    releaseSupplyStation(level, entity);
                    citizen.returnToBlueprint();
                    syncHeldItem(citizen, entity);
                    data.touch();
                    return;
                }
            }
        }
        if (blueprint == null) {
            clear(data, citizen, entity);
            return;
        }
        // Having one usable item is not evidence that this supply visit is done.
        // A previous station may have yielded only one item; continue filling
        // the batch at the next reachable container before returning to work.
        if (!footingRequest && required != null && countInventoryItem(citizen, required) == 0
                && shareSiteMaterial(level, settlement, citizen, required, 64) > 0) {
            releaseSupplyStation(level, entity);
            citizen.returnToBlueprint();
            syncHeldItem(citizen, entity);
            data.touch();
            return;
        }
        if (required != null && (warehouse == null || !WarehouseConstructionSupply.available(level,settlement,warehouse,required,1))) {
            var replacement=WarehouseConstructionSupply.warehouse(level,settlement,entity.blockPosition(),required);
            if(replacement!=null && (blueprint.terrainProject()==null || blueprint.terrainProject().playerBridge==null
                    || blueprint.terrainProject().playerBridge.supplyingBank(replacement.position()))) warehouse=replacement;
        }
        if (!footingRequest && required != null && DecorationService.owned(settlement, blueprint.id())
                && (warehouse==null || !WarehouseConstructionSupply.available(level,settlement,warehouse,required,1))) {
            DecorationCrafting.craft(level, settlement, citizen, Map.of(required, 1));
            if (selectInventoryItem(citizen, required)) { citizen.returnToBlueprint(); data.touch(); return; }
            warehouse = DecorationCrafting.warehouse(level, settlement, citizen, required);
        }
        if (blueprint != null && required != null && siteSupplyReservedByOther(level, settlement, blueprint, citizen, required,
                footingRequest)) {
            releaseSupplyStation(level, entity);
            entity.stopSettlementNavigation();
            setActivity(data, citizen, entity, CitizenActivity.FETCHING_BUILD_MATERIAL,
                    "SITE_SUPPLY_RESERVED");
            yieldUnreachableSite(level,data,settlement,blueprint,citizen,entity);
            return;
        }
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(blueprint.ownerId());
        boolean directCoreSupply = settlement.creativeCoreInstalled();
        // An installed creative core is the authoritative infinite construction
        // source. A warehouse that happened to become operational mid-build may
        // contain a few returned stacks, but routing the next footing refill
        // there can make a builder queue behind finished workers and then run
        // out before the warehouse has the full requested batch. Keep creative
        // construction collection at the core; ordinary settlements still use
        // real stocked warehouses exactly as before.
        boolean useWarehouse = !directCoreSupply && warehouse != null && warehouse.active()
                && warehouse.operational() && warehouse.type().isStorage();
        boolean creative = useWarehouse
                ? WarehouseInventoryService.hasCreativeSupply(level, warehouse) : directCoreSupply;
        if (!useWarehouse && owner == null && !directCoreSupply) {
            releaseSupplyStation(level, entity);
            entity.stopSettlementNavigation();
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "MATERIALS_MISSING");
            return;
        }
        Set<BlockPos> claimed = settlement.citizens().stream()
                .filter(other -> !other.id().equals(citizen.id()))
                .filter(other -> blueprint.id().equals(other.targetBlueprintId()))
                .map(CitizenRecord::targetPosition)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Item footingMaterial = Items.SCAFFOLDING;
        boolean temporaryCleanupSupply = footingRequest && blueprint != null
                && citizen.targetPosition() != null
                && blueprint.hasTemporaryFooting(citizen.targetPosition());
        if (temporaryCleanupSupply) required = footingMaterial;
        if (blueprint == null || required == null) {
            clear(data, citizen, entity);
            return;
        }
        Item requestedMaterial = required;
        Map<Item, Integer> requirements = new LinkedHashMap<>();
        requirements.put(requestedMaterial, ConstructionPlanService.relatedTerrainSupplyNeed(settlement,blueprint.terrainProject(),requestedMaterial));
        boolean irrigationRequest=blueprint.region()!=null && requestedMaterial==Items.WATER_BUCKET;
        if (pending != null && !irrigationRequest) {
            ConstructionPlanService.SupplyPlan supplyPlan = ConstructionPlanService.createSupplyPlan(
                    level, blueprint, claimed, entity.blockPosition(), pending.placement().position());
            supplyPlan.requirements().forEach((material, count) ->
                    requirements.merge(material, count, Math::max));
        }
        for (CitizenRecord other : settlement.citizens()) {
            if (other.id().equals(citizen.id()) || !blueprint.id().equals(other.targetBlueprintId())) continue;
            // Remote or unloaded coworkers cannot supply this resident.
            if (!canShareSiteMaterial(level, citizen, other)) continue;
            for (int slot = 0; slot < CitizenRecord.INVENTORY_SIZE; slot++) {
                ItemStack stack = other.inventoryItem(slot);
                if (stack.isEmpty() || CitizenEquipmentService.isReservedEquipment(other, slot)) continue;
                requirements.computeIfPresent(stack.getItem(),
                        (item, count) -> Math.max(0, count - stack.getCount()));
            }
        }
        requirements.computeIfPresent(requestedMaterial, (item, count) -> Math.max(1, count));
        if (blueprint.region()==null && blueprint.terrainProject()==null)
            requirements.merge(footingMaterial, DecorationService.owned(settlement, blueprint.id()) ? 4 : CONSTRUCTION_FOOTING_RESERVE, Integer::sum);
        boolean craftScaffolds = !creative && citizen.profession() == CitizenProfession.WORKER
                && (warehouse==null || !WarehouseConstructionSupply.available(level,settlement,warehouse,Items.SCAFFOLDING,1))
                && (useWarehouse ? WarehouseInventoryService.countItem(level, warehouse, Items.SCAFFOLDING) == 0
                    : owner != null && !owner.getInventory().contains(new ItemStack(Items.SCAFFOLDING)));
        if (craftScaffolds) {
            int missing = Math.max(0, requirements.getOrDefault(Items.SCAFFOLDING, 0)
                    - countInventoryItem(citizen, Items.SCAFFOLDING));
            requirements.put(Items.STICK, Math.min(256, missing) * 6);
        }
        boolean decorationAssembly = !footingRequest && DecorationService.owned(settlement, blueprint.id())
                && (warehouse==null || !WarehouseConstructionSupply.available(level,settlement,warehouse,requestedMaterial,1));
        Map<Item, Integer> componentOutputs = decorationAssembly ? new LinkedHashMap<>(requirements) : Map.of();
        if (decorationAssembly && !creative) {
            var rawRequirements = DecorationCrafting.ingredients(level, settlement, citizen, componentOutputs);
            requirements.clear(); requirements.putAll(rawRequirements);
        }
        Set<Item> desiredMaterials = requirements.entrySet().stream()
                .filter(entry -> constructionMaterialNeed(citizen, entry.getKey(), entry.getValue()) > 0)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
        if (desiredMaterials.isEmpty()) {
            int requiredSlot = citizen.findInventorySlot(stack -> stack.is(requestedMaterial));
            if (requiredSlot < 0 || footingRequest && findFootingSlot(citizen) < 0) {
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED,
                        requiredSlot < 0 ? "MATERIALS_MISSING" : "FOOTING_MATERIAL_MISSING");
                return;
            }
            citizen.selectActiveSlot(requiredSlot);
            citizen.returnToBlueprint();
            syncHeldItem(citizen, entity);
            data.touch();
            return;
        }
        BlockPos target = useWarehouse
                ? WarehouseInventoryService.reserveInteractionPosition(level, warehouse, entity)
                : directCoreSupply
                ? SettlementCoreInteractionService.reserveSupplyPosition(level, settlement, entity)
                : owner == null ? null : owner.blockPosition();
        if (target == null) {
            if (directCoreSupply) {
                waitForSupplyStation(level, data, settlement, citizen, entity, null,
                        false, CitizenActivity.FETCHING_BUILD_MATERIAL);
            } else {
                waitForWarehouseStation(level, data, citizen, entity, warehouse,
                        CitizenActivity.FETCHING_BUILD_MATERIAL);
            }
            return;
        }
        if (!nearWorkPosition(entity, target)) {
            moveToWarehouseStation(level, data, citizen, entity, target,
                    CitizenActivity.FETCHING_BUILD_MATERIAL);
            return;
        }
        if (useWarehouse && !WarehouseInventoryService.canInteract(level, warehouse, entity)) {
            abandonSupplyStation(level, entity);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "WAREHOUSE_INACCESSIBLE");
            return;
        }
        if (directCoreSupply
                && !SettlementCoreInteractionService.canReceiveSupply(level, settlement, entity)) {
            abandonSupplyStation(level, entity);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "CORE_SUPPLY_INACCESSIBLE");
            return;
        }
        for (Map.Entry<Item, Integer> entry : requirements.entrySet()) {
            if(entry.getKey()!=requestedMaterial && !citizen.hasInventoryItem(stack->stack.is(entry.getKey()))
                    && citizen.inventory().stream().filter(ItemStack::isEmpty).count()<=6)continue;
            int request = entry.getValue();
            if (decorationAssembly && !creative) request = Math.min(request,
                    countInventoryItem(citizen, entry.getKey()) + Math.max(0,
                    SettlementInventoryService.countItem(level, settlement, entry.getKey()) - DecorationCrafting.reserve(settlement, entry.getKey())));
            loadConstructionMaterial(level, warehouse, owner, citizen, entity,
                    entry.getKey(), request, useWarehouse, creative);
        }
        if (decorationAssembly && !creative) { DecorationCrafting.craft(level, settlement, citizen, componentOutputs); data.touch(); }
        if (craftScaffolds) {
            WorkerScaffoldCrafting.craft(citizen, Math.max(0,
                    requirements.getOrDefault(Items.SCAFFOLDING, 0) - countInventoryItem(citizen, Items.SCAFFOLDING)));
            requirements.remove(Items.STICK); // Consumed ingredients are not a refill target.
            data.touch();
        }
        int requiredSlot = citizen.findInventorySlot(stack -> stack.is(requestedMaterial));
        if (requiredSlot < 0) {
            releaseSupplyStation(level, entity);
            if(citizen.inventoryCapacityFor(new ItemStack(requestedMaterial))==0 && entity.onGround()
                    && !entity.hasActiveConstructionMovement()){
                entity.deferConstructionTarget(citizen.targetPosition(),CONSTRUCTION_TARGET_RETRY_DELAY_TICKS);
                clear(data,citizen,entity);return;
            }
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "MATERIALS_MISSING");
            return;
        }
        if (footingRequest && findFootingSlot(citizen) < 0) {
            releaseSupplyStation(level, entity);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "FOOTING_MATERIAL_MISSING");
            return;
        }
        BuildingRecord stockedWarehouse = warehouse;
        Set<Item> remainingStockedMaterials = requirements.entrySet().stream()
                .filter(entry -> constructionMaterialNeed(citizen, entry.getKey(), entry.getValue()) > 0)
                .map(Map.Entry::getKey)
                .filter(item -> creative || useWarehouse
                        && WarehouseInventoryService.countItem(level, stockedWarehouse, item) > 0)
                .collect(java.util.stream.Collectors.toSet());
        if (useWarehouse && citizen.hasInventoryCapacity() && !remainingStockedMaterials.isEmpty()) {
            releaseSupplyStation(level, entity);
            setActivity(data, citizen, entity, CitizenActivity.FETCHING_BUILD_MATERIAL, "");
            data.touch();
            return;
        }
        releaseSupplyStation(level, entity);
        citizen.selectActiveSlot(requiredSlot);
        citizen.returnToBlueprint();
        syncHeldItem(citizen, entity);
        data.touch();
    }

    /** One worker leads each in-flight site/material request; coworkers use the returned shared stock. */
    private static boolean siteSupplyReservedByOther(
            ServerLevel level, SettlementRecord settlement, BlueprintRecord blueprint,
            CitizenRecord citizen, Item required, boolean footingRequest
    ) {
        SiteSupplyKey key = new SiteSupplyKey(blueprint.id(), required, footingRequest);
        synchronized (SITE_SUPPLY_LEADERS) {
            UUID ownerId = SITE_SUPPLY_LEADERS.get(key);
            CitizenRecord owner = ownerId == null ? null : settlement.citizen(ownerId);
            boolean valid = owner != null && owner.alive()
                    && blueprint.id().equals(owner.targetBlueprintId())
                    && owner.activity() != CitizenActivity.BLOCKED
                    && !terminalNavigationFailure(owner.workFailure())
                    && owner.workState() == (footingRequest
                    ? CitizenWorkState.TO_FOOTING_SUPPLY : CitizenWorkState.TO_CONSTRUCTION_SUPPLY);
            if (!valid) {
                SITE_SUPPLY_LEADERS.put(key, citizen.id());
                return false;
            }
            return !owner.id().equals(citizen.id());
        }
    }

    private static int loadConstructionMaterial(
            ServerLevel level, @Nullable BuildingRecord warehouse, @Nullable ServerPlayer owner,
            CitizenRecord citizen, SettlementCitizenEntity entity,
            Item material, int requested, boolean useWarehouse, boolean creative
    ) {
        if(citizen.inventoryCapacityFor(new ItemStack(material))==0 && citizen.compactInventory()){
            SettlementSavedData.get(level).touch();
        }
        int requestedPickup = constructionMaterialNeed(citizen, material, requested);
        int insertedTotal = ConstructionMaterialBatch.load(citizen, material, requested,
                count -> useWarehouse ? WarehouseConstructionSupply.extract(
                        level, warehouse, entity, material, count)
                        : creative ? new ItemStack(material, count)
                        : owner == null ? ItemStack.EMPTY : extractItem(owner.getInventory(), material, count),
                excess -> {
                    if (useWarehouse) WarehouseInventoryService.insertForResident(level, warehouse, entity, excess);
                    else if (owner != null && !creative) owner.getInventory().add(excess);
                });
        if(insertedTotal>0) {
            SITE_WAITS.remove(citizen);
            SITE_SUCCESS_TICKS.put(entity, level.getGameTime());
            var supplySettlement=SettlementSavedData.get(level).findContaining(entity.blockPosition()).orElse(null);
            if(supplySettlement!=null)CitizenEquipmentService.topUpConstructionTools(level,supplySettlement,citizen,entity);
            var supply=useWarehouse && warehouse!=null?warehouse.position():owner!=null?owner.blockPosition()
                    :SettlementSavedData.get(level).findContaining(entity.blockPosition()).map(SettlementRecord::corePosition).orElse(null);
            if(supply!=null) {
                com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,citizen,
                        "construction_material_requested",supply,net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(material).toString(),requestedPickup);
                com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,citizen,
                        "construction_material_pickup",supply,net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(material).toString(),insertedTotal);
            }
        }
        return insertedTotal;
    }

    private static int constructionMaterialNeed(CitizenRecord citizen, Item material, int requested) {
        int carried = countInventoryItem(citizen, material);
        return Math.min(Math.max(0, requested - carried),
                citizen.inventoryCapacityFor(new ItemStack(material)));
    }

    /** Opposite-bank stock is not local supply, even for the same worksite. */
    private static boolean canShareSiteMaterial(ServerLevel level, CitizenRecord recipient, CitizenRecord donor) {
        var receiver = CitizenProjectionService.findProjection(level, recipient);
        var source = CitizenProjectionService.findProjection(level, donor);
        return receiver != null && source != null && source.isAlive()
                && receiver.distanceToSqr(source) <= 16.0 && receiver.hasLineOfSight(source);
    }

    /** Same-site inventory sharing uses a real extract/insert, never duplicated stock. */
    public static int shareSiteMaterial(ServerLevel level, SettlementRecord settlement, CitizenRecord recipient, Item material, int limit) {
        UUID site = recipient.targetBlueprintId();
        if (site == null || settlement.blueprint(site) == null || limit <= 0) return 0;
        int moved = 0;
        for (CitizenRecord donor : settlement.citizens()) {
            if (donor == recipient || !donor.alive() || !site.equals(donor.targetBlueprintId())) continue;
            if (!canShareSiteMaterial(level, recipient, donor)) continue;
            // Leave one unit for the donor, so simultaneous targets do not ping-pong the last item.
            int amount = Math.min(limit - moved, Math.max(0, countInventoryItem(donor, material) - 1));
            amount = Math.min(amount, recipient.inventoryCapacityFor(new ItemStack(material)));
            if (amount <= 0) continue;
            ItemStack extracted = donor.extractInventoryItem(stack -> stack.is(material), amount);
            int taken = extracted.getCount();
            ItemStack remaining = recipient.insertIntoInventory(extracted);
            moved += taken - remaining.getCount();
            if (!remaining.isEmpty()) donor.insertIntoInventory(remaining);
            if (moved >= limit) break;
        }
        return moved;
    }

    private static int countInventoryItem(CitizenRecord citizen, Item material) {
        int carried = 0;
        for (int slot = 0; slot < CitizenRecord.INVENTORY_SIZE; slot++) {
            ItemStack stack = citizen.inventoryItem(slot);
            if (!stack.isEmpty() && stack.is(material)
                    && !CitizenEquipmentService.isReservedEquipment(citizen, slot)) {
                carried += stack.getCount();
            }
        }
        return carried;
    }

    private static void build(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        // Actual actions have their own cadence, separate from the eight-tick scheduler.
        prepareSiteStation(level, data, settlement, citizen, entity);
    }

    private static final Map<SettlementCitizenEntity, SiteStation> SITE_STATIONS = new java.util.WeakHashMap<>();
    private static final Map<SettlementCitizenEntity, FarmEscape> FARM_ESCAPES = new java.util.WeakHashMap<>();
    private record FarmEscape(UUID site, BlockPos occupied, BlockPos destination) { }
    private static final Map<SettlementCitizenEntity,String> SITE_DIAGNOSTICS=new java.util.WeakHashMap<>();

    public static String constructionWorkObservation(ServerLevel level,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity entity) {
        var station=SITE_STATIONS.get(entity);var escape=FARM_ESCAPES.get(entity);var site=s.blueprint(c.targetBlueprintId());
        var target=site==null || site.terrainProject()==null || c.targetPosition()==null?null:com.hkin.exiledsettlement.terrain.TerrainService.at(level,site,c.targetPosition());
        var face=target==null?null:com.hkin.exiledsettlement.terrain.TerrainService.fillSurfaceWorkFace(level,site.terrainProject(),site.terrainProject().steps.get(-target.index()-1));
        return "stage="+SITE_DIAGNOSTICS.get(entity)+",cap="+(site!=null && builderWithinCap(s,c,site.id()))+",personal="+com.hkin.exiledsettlement.settlement.CommunityLifeService.handles(c)+",station="+station+",escape="+escape+",inWater="+entity.isInWater()+",face="+face+",faceVisible="+(face!=null && CitizenInteractionRange.visible(entity,face));
    }
    private static final Map<SettlementCitizenEntity, Long> SITE_ACTION_TICKS = new java.util.WeakHashMap<>();
    private static final Map<SettlementCitizenEntity, Long> SITE_SUCCESS_TICKS = new java.util.WeakHashMap<>();
    private static final Map<SettlementCitizenEntity, Map<BlockPos, Integer>> SITE_ROUTE_FAILURES = new java.util.WeakHashMap<>();
    private record SiteStation(UUID site, BlockPos position, long retryAt, BlockPos workTarget) {
        SiteStation(UUID site, BlockPos position, long retryAt) { this(site,position,retryAt,null); }
    }
    private record SiteWait(UUID site,long since) { }
    private static final Map<CitizenRecord,SiteWait> SITE_WAITS=new java.util.WeakHashMap<>();
    private static final Map<CitizenRecord,Map<UUID,Long>> DEFERRED_SITES=new java.util.WeakHashMap<>();
    private record SiteRetry(int failures,long failedAt) { }
    private static final Map<CitizenRecord,Map<UUID,SiteRetry>> SITE_RETRIES=new java.util.WeakHashMap<>();
    private static void deferStalledSite(ServerLevel level,SettlementRecord s,CitizenRecord c,UUID site){
        long now=level.getGameTime();var retries=SITE_RETRIES.computeIfAbsent(c,ignored->new HashMap<>());
        retries.values().removeIf(r->now-r.failedAt()>24000);
        var task=WorkTaskService.task(s,c);UUID mother=task==null?site:task.mother();
        var previous=retries.get(mother);
        int failures=previous==null?1:Math.min(5,previous.failures()+1);
        retries.put(mother,new SiteRetry(failures,now));
        long delay=Math.min(4800,400L<<(failures-1));
        var deferred=DEFERRED_SITES.computeIfAbsent(c,ignored->new HashMap<>());
        deferred.put(site,now+delay);deferred.put(mother,now+delay);
    }
    private static final class EngineeringProgress {
        final UUID mother;
        final Set<BlockPos> visited=new HashSet<>();
        long progressAt,lastSeen,lastAction,actionAt;
        EngineeringProgress(UUID mother,long now){this.mother=mother;progressAt=lastSeen=actionAt=now;}
    }
    private static final Map<CitizenRecord,EngineeringProgress> ENGINEERING_PROGRESS=new java.util.WeakHashMap<>();
    /** Only callers that changed a real engineering block may refresh this clock. */
    public static void recordActualEngineeringAction(ServerLevel level,SettlementCitizenEntity entity){
        SITE_SUCCESS_TICKS.put(entity,level.getGameTime());
    }

    /** A changed waypoint or a repeated loop is not progress on the logical construction job. */
    private static boolean yieldStalledEngineering(ServerLevel level,SettlementSavedData data,SettlementRecord s,
            CitizenRecord c,SettlementCitizenEntity e){
        var site=s.blueprint(c.targetBlueprintId()!=null?c.targetBlueprintId():c.constructionResumeId());
        if(c.profession()!=CitizenProfession.WORKER || site==null || schedulePhase(level.getDayTime(),c)!=SchedulePhase.WORK
                || c.preferredWorksiteId()!=null){ENGINEERING_PROGRESS.remove(c);return false;}
        var task=WorkTaskService.task(s,c);if(task==null)return false;
        long now=level.getGameTime();var p=ENGINEERING_PROGRESS.get(c);
        if(p==null || !p.mother.equals(task.mother()) || now-p.lastSeen>40){
            p=new EngineeringProgress(task.mother(),now);
            p.lastAction=SITE_SUCCESS_TICKS.getOrDefault(e,0L);
            ENGINEERING_PROGRESS.put(c,p);
        }
        p.lastSeen=now;
        if(p.visited.size()<2048 && p.visited.add(e.navigationFeetPosition().immutable()))p.progressAt=now;
        long action=SITE_SUCCESS_TICKS.getOrDefault(e,0L);
        if(action>p.lastAction){
            p.lastAction=action;p.progressAt=p.actionAt=now;
            var retries=SITE_RETRIES.get(c);if(retries!=null)retries.remove(task.mother());
        }
        // A real detour may visit new cells, but wandering without performing
        // any engineering action cannot reserve a worker for an entire day.
        // Allow two work minutes for travel/supply, then yield at a safe boundary.
        if(now-p.progressAt<720 && now-p.actionAt<2400 || c.workerAccess.fillSite!=null || e.hasActiveConstructionMovement()
                || e.isConstructionAccessRetreating() || !e.onGround()
                || occupiedTemporarySupport(level,site,constructionFeetPosition(e))!=null)return false;
        // Let the finite local operation reach its tool-dependent duration.
        // Recreating it every job tick would reset its real cut progress forever.
        if(c.workerAccess.block!=null && now-c.workerAccess.lastTick<400)return false;
        if(c.workerAccess.block!=null)WorkerAccessService.abandonStalledAttempt(level,c);
        var at=c.targetPosition();
        deferStalledSite(level,s,c,site.id());
        if(at!=null)e.deferConstructionTarget(at,CONSTRUCTION_TARGET_RETRY_DELAY_TICKS);
        releaseSupplyStation(level,e);SITE_STATIONS.remove(e);SITE_WAITS.remove(c);ENGINEERING_PROGRESS.remove(c);
        com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,c,"worker_task_yield",at,
                now-p.actionAt>=2400?"no_engineering_action":"no_real_progress",0);
        clear(data,c,e);return true;
    }
    private static boolean siteDeferred(ServerLevel level,CitizenRecord citizen,UUID site){
        var deferred=DEFERRED_SITES.get(citizen);if(deferred==null)return false;
        deferred.values().removeIf(until->until<=level.getGameTime());return deferred.containsKey(site);
    }
    /** Yield only at a safe ground boundary. Paid blocks and carried supplies remain real. */
    private static void yieldUnreachableSite(ServerLevel level,SettlementSavedData data,SettlementRecord settlement,
            BlueprintRecord site,CitizenRecord citizen,SettlementCitizenEntity entity){
        long now=level.getGameTime();var wait=SITE_WAITS.get(citizen);
        if(wait==null || !wait.site().equals(site.id())){SITE_WAITS.put(citizen,new SiteWait(site.id(),now));return;}
        if(now-wait.since()<400 || entity.hasActiveConstructionMovement() || entity.isConstructionAccessRetreating()
                || !entity.onGround() || occupiedTemporarySupport(level,site,constructionFeetPosition(entity))!=null)return;
        var at=citizen.targetPosition();
        if(citizen.workState()==CitizenWorkState.TO_BLUEPRINT && WorkerAccessService.request(level,entity,at,3)){
            SITE_WAITS.remove(citizen);return;
        }
        deferStalledSite(level,settlement,citizen,site.id());
        if(at!=null)entity.deferConstructionTarget(at,CONSTRUCTION_TARGET_RETRY_DELAY_TICKS);
        SITE_WAITS.remove(citizen);SITE_STATIONS.remove(entity);clear(data,citizen,entity);
    }
    private static final Map<SiteSupplyKey, UUID> SITE_SUPPLY_LEADERS = new HashMap<>();
    private record SiteSupplyKey(UUID site, Item material, boolean footing) { }

    public static boolean isGroundSiteWorking(ServerLevel level, CitizenRecord citizen, SettlementCitizenEntity entity) {
        SiteStation station = SITE_STATIONS.get(entity);
        return station != null && station.site().equals(citizen.targetBlueprintId())
                && station.position() != null && nearWorkPosition(entity, station.position())
                && entity.onGround() && isWorkTime(level, citizen)
                && level.getGameTime() - SITE_SUCCESS_TICKS.getOrDefault(entity, Long.MIN_VALUE / 2) <= 10;
    }

    /** Ground work acts every five ticks; ordinary building actions remain ten ticks apart. */
    public static void tickSiteConstructionStaggered(ServerLevel level) {
        staggerConstruction=true;
        try {tickSiteConstruction(level);}finally{staggerConstruction=false;}
    }
    public static void tickSiteConstruction(ServerLevel level) {
        SettlementSavedData data = SettlementSavedData.get(level);
        for (SettlementRecord settlement : List.copyOf(data.settlements())) {
            if (!settlement.active() || !level.hasChunkAt(settlement.corePosition())
                    || ConstructionDebugService.isEnabled(level, settlement.id())) continue;
            for(var exit:settlement.terrain().projects.values())if(!exit.cancelled && exit.escapeFor!=null && exit.escapingCitizen!=null) {
                var record=settlement.citizen(exit.escapingCitizen);var trapped=record==null?null:CitizenProjectionService.findProjection(level,record);
                if(trapped==null || !trapped.isAlive() || trapped.isDefending() || trapped.isSleeping() || CitizenInteractionService.held(trapped))continue;
                // An old cut remains in the world after its resident is free.
                // Its advisory landing must not cancel an in-flight safe step
                // or replace the personal bed route whenever the resident
                // brushes one of those already-cleared columns again.
                if(trapped.hasActiveConstructionMovement()
                        || schedulePhase(level.getDayTime(),record)==SchedulePhase.SLEEP
                        && !exit.id.equals(record.targetBlueprintId()))continue;
                boolean usingExit=trapped.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(exit.escapeFor))
                        || exit.steps.stream().anyMatch(step->trapped.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(step.position())));
                if(usingExit) {
                    var destination=terrainEscape(level,trapped,exit.escapeFor);
                    if(destination!=null)trapped.navigateTo(destination,WALK_SPEED);
                }
            }
            for (CitizenRecord citizen : settlement.citizens()) {
                if(staggerConstruction && !com.hkin.exiledsettlement.util.TickCadence.due(level.getGameTime(),citizen.id(),5,2))continue;
                if(citizen.workState()==CitizenWorkState.TO_ROAD && citizen.canWork() && isWorkTime(level,citizen)
                        && !com.hkin.exiledsettlement.settlement.CommunityLifeService.handles(citizen) && !settlement.community().evacuation) {
                    var actor=CitizenProjectionService.findProjection(level,citizen);
                    if(actor!=null && actor.isAlive() && !actor.isDefending() && !actor.isSleeping() && !CitizenInteractionService.held(actor)
                            && !pauseForCargoDelivery(level,data,settlement,citizen,actor))
                        com.hkin.exiledsettlement.road.AutoRoadService.process(level,data,settlement,citizen,actor);
                }
                if (!citizen.canWork() || !isWorkTime(level, citizen) && !ResidentEgressService.active(settlement,citizen) || citizen.workState() != CitizenWorkState.TO_BLUEPRINT
                        || com.hkin.exiledsettlement.settlement.CommunityLifeService.handles(citizen)
                        || settlement.community().evacuation || !com.hkin.exiledsettlement.debug.ForceWorkDebugService.forced(citizen) && settlement.community().policy.equals("emergency") && settlement.blueprint(citizen.targetBlueprintId())!=null && settlement.blueprint(citizen.targetBlueprintId()).terrainProject()==null) continue;
                SettlementCitizenEntity entity = CitizenProjectionService.findProjection(level, citizen);
                if (entity == null || !entity.isAlive() || entity.isDefending() || entity.isSleeping()) continue;
                if (CitizenInteractionService.held(entity)) continue;
                advanceSiteConstruction(level, data, settlement, citizen, entity);
            }
        }
    }

    private static boolean siteGround(ServerLevel level, BuildingBlueprintTemplate template,
            SettlementCitizenEntity entity, BlockPos p) {
        BlockPos min = template.minCorner(), max = template.maxCorner();
        int dx = Math.max(Math.max(min.getX() - p.getX(), p.getX() - max.getX()), 0);
        int dz = Math.max(Math.max(min.getZ() - p.getZ(), p.getZ() - max.getZ()), 0);
        int heightRange=com.hkin.exiledsettlement.blueprint.FarmEarthworkPlan.applies(template.type())
                ? Math.max(com.hkin.exiledsettlement.blueprint.FarmEarthworkConfig.DIG_HEIGHT.get(),
                    com.hkin.exiledsettlement.blueprint.FarmEarthworkConfig.FILL_DEPTH.get())+1 : 3;
        boolean hillsideLanding=Math.max(dx,dz)>0 && p.getY()>=min.getY()+1 && p.getY()<=max.getY()+2
                && level.hasChunkAt(p) && level.getFluidState(p.below()).isEmpty()
                && level.getBlockState(p.below()).isFaceSturdy(level,p.below(),Direction.UP)
                && com.hkin.exiledsettlement.terrain.TerrainService.natural(level.getBlockState(p.below()));
        return Math.max(dx, dz) <= 4
                && (template.terrainWork() || template.type().isDynamicArea()?p.getY()>=min.getY()-2 && p.getY()<=max.getY()+2:Math.abs(p.getY()-min.getY()-1)<=heightRange || hillsideLanding)
                && level.hasChunkAt(p) && !level.getBlockState(p.below()).is(Blocks.SCAFFOLDING)
                && !level.getBlockState(p).is(Blocks.SCAFFOLDING)
                && entity.canStandAtPosition(p);
    }

    private static boolean prepareSiteStation(ServerLevel level, SettlementSavedData data,
            SettlementRecord settlement, CitizenRecord citizen, SettlementCitizenEntity entity) {
        try(var slice=ConstructionPlanningBudget.begin(entity,citizen.targetBlueprintId())) {
            return prepareSiteStationSlice(level,data,settlement,citizen,entity);
        } catch(ConstructionPlanningBudget.Deferred waiting) {
            SITE_DIAGNOSTICS.put(entity,"workface search continues next slice");return false;
        }
    }
    private static boolean prepareSiteStationSlice(ServerLevel level,SettlementSavedData data,
            SettlementRecord settlement,CitizenRecord citizen,SettlementCitizenEntity entity) {
        if (releaseUnauthorizedConstruction(level, data, settlement, citizen, entity)) return false;
        BlueprintRecord site=settlement.blueprint(citizen.targetBlueprintId());
        SITE_DIAGNOSTICS.put(entity,"start");
        if (site==null || site.cancellationQueued() || !com.hkin.exiledsettlement.terrain.TerrainService.allowed(settlement,site) || !builderWithinCap(settlement,citizen,site.id())) {
            SITE_DIAGNOSTICS.put(entity,"invalid");
            SITE_STATIONS.remove(entity); FARM_ESCAPES.remove(entity); clear(data,citizen,entity); return false;
        }
        boolean bridgeSequence=site.terrainProject()!=null && site.terrainProject().playerBridge!=null;
        if(bridgeSequence && com.hkin.exiledsettlement.terrain.TerrainService.at(level,site,citizen.targetPosition())==null) {
            // Completing one cell does not complete the bridge assignment. Keep
            // the worker on the same site across row/phase publication gaps.
            var claimed=new HashSet<BlockPos>();
            for(var other:settlement.citizens())if(other!=citizen && site.id().equals(other.targetBlueprintId())
                    && other.targetPosition()!=null)claimed.add(other.targetPosition());
            var next=com.hkin.exiledsettlement.terrain.TerrainService.pending(level,site,claimed).stream()
                    .min(Comparator.comparingDouble(p->p.placement().position().distSqr(entity.blockPosition()))).orElse(null);
            if(next!=null){citizen.updateTargetPosition(next.placement().position());data.touch();}
            else {
                var plan=site.terrainProject();
                if(com.hkin.exiledsettlement.terrain.TerrainService.constructionComplete(level,plan)){
                    com.hkin.exiledsettlement.terrain.TerrainService.completed(level,settlement,site);
                    SITE_STATIONS.remove(entity);clear(data,citizen,entity);
                }else{
                    entity.stopSettlementNavigation();
                    setActivity(data,citizen,entity,CitizenActivity.BUILDING,plan.playerBridge.paused?"BRIDGE_PAUSED":"BRIDGE_FRONTIER_WAIT");
                }
                return false;
            }
        }
        if(bridgeSequence && prepareGroundSupplies(level,data,settlement,site,citizen,entity))return false;
        if(!bridgeSequence && treeClearanceTarget(level,site,citizen.targetPosition())) {
            if(treeGroundReady(level,settlement,entity,citizen.targetPosition(),entity.navigationFeetPosition())) {
                entity.stopSettlementNavigation();SITE_STATIONS.remove(entity);
                setActivity(data,citizen,entity,CitizenActivity.BUILDING,"");return true;
            }
            var cached=SITE_STATIONS.get(entity);
            var at=cached!=null && cached.site().equals(site.id()) && cached.retryAt()>level.getGameTime()
                    && Objects.equals(cached.workTarget(),citizen.targetPosition()) && cached.position()!=null
                    && treeGroundReady(level,settlement,entity,citizen.targetPosition(),cached.position())?cached.position()
                    :treeGroundApproach(level,settlement,site,entity,citizen.targetPosition());
            SITE_STATIONS.put(entity,new SiteStation(site.id(),at,level.getGameTime()+100,citizen.targetPosition()));
            String failure=at==null?"CONSTRUCTION_SITE_NO_ROUTE":entity.navigateTo(at,WALK_SPEED);
            setActivity(data,citizen,entity,CitizenActivity.BUILDING,failure);return false;
        }
        if(bridgeSequence || com.hkin.exiledsettlement.blueprint.TimedConstructionService.building(site)
                || com.hkin.exiledsettlement.blueprint.TimedConstructionService.fill(level,site,citizen.targetPosition())) {
            if(com.hkin.exiledsettlement.blueprint.TimedConstructionService.arrived(level,site,entity,citizen.targetPosition())) {
                entity.stopSettlementNavigation();setActivity(data,citizen,entity,CitizenActivity.BUILDING,"");
                SITE_DIAGNOSTICS.put(entity,"timed worksite arrived");return true;
            }
            var cached=SITE_STATIONS.get(entity);
            var at=cached!=null && cached.site().equals(site.id()) && Objects.equals(cached.workTarget(),citizen.targetPosition())
                    && cached.position()!=null && entity.canStandAtPosition(cached.position())
                    && com.hkin.exiledsettlement.blueprint.TimedConstructionService.arrivedAt(level,site,cached.position(),citizen.targetPosition()) ? cached.position()
                    : com.hkin.exiledsettlement.blueprint.TimedConstructionService.approach(level,site,entity,citizen.targetPosition());
            SITE_STATIONS.put(entity,new SiteStation(site.id(),at,level.getGameTime()+100,citizen.targetPosition()));
            String failure=at==null?"CONSTRUCTION_SITE_NO_ROUTE":entity.navigateTo(at,WALK_SPEED);
            if(at==null || terminalNavigationFailure(failure)) {
                yieldUnreachableSite(level,data,settlement,site,citizen,entity);
                setActivity(data,citizen,entity,CitizenActivity.BLOCKED,failure);
            } else setActivity(data,citizen,entity,CitizenActivity.BUILDING,failure);
            return false;
        }
        var escape=FARM_ESCAPES.get(entity);
        if(escape!=null) {
            if(!escape.site().equals(site.id()) || !entity.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(escape.occupied())))
                FARM_ESCAPES.remove(entity);
            else {
                SITE_DIAGNOSTICS.put(entity,"escape "+escape.destination());
                String failure=entity.navigateTo(escape.destination(),WALK_SPEED);
                if(terminalNavigationFailure(failure)) {
                    FARM_ESCAPES.remove(entity);entity.deferConstructionTarget(citizen.targetPosition(),CONSTRUCTION_TARGET_RETRY_DELAY_TICKS);clear(data,citizen,entity);
                }
                setActivity(data,citizen,entity,CitizenActivity.BUILDING,failure);return false;
            }
        }
        if (leaveScaffoldingBeforeLogistics(level,data,settlement,citizen,entity)) {SITE_DIAGNOSTICS.put(entity,"scaffold exit");return false;}
        if(citizen.workerAccess.block!=null)return false;
        collectSiteBatchAtCore(level,settlement,site,citizen,entity);
        if(prepareGroundSupplies(level,data,settlement,site,citizen,entity))return false;
        if(site.terrainProject()!=null && citizen.targetPosition()!=null) {
            var pending=com.hkin.exiledsettlement.terrain.TerrainService.at(level,site,citizen.targetPosition());
            if(pending==null) {
                var project=site.terrainProject();
                String blocker=project.indices(citizen.targetPosition()).stream()
                        .filter(i->i>=project.cursor && !project.finished.get(i))
                        .map(i->com.hkin.exiledsettlement.terrain.TerrainService.guard(level,settlement,site,project.steps.get(i)))
                        .filter(reason->!reason.isEmpty()).findFirst().orElse("");
                SITE_DIAGNOSTICS.put(entity,blocker.isEmpty()?"terrain target already satisfied":"terrain target blocked: "+blocker);
                SITE_STATIONS.remove(entity);clear(data,citizen,entity);
                if(!blocker.isEmpty())setActivity(data,citizen,entity,CitizenActivity.BLOCKED,blocker);
                return false;
            }
            var surface=pending==null?null:com.hkin.exiledsettlement.terrain.TerrainService.fillSurfaceWorkFace(level,site.terrainProject(),site.terrainProject().steps.get(-pending.index()-1));
            if(pending!=null && pending.placement().material()==Items.BUCKET && entity.onGround()
                    && CitizenInteractionRange.contains(entity,citizen.targetPosition())) {
                entity.stopSettlementNavigation();return true;
            }
            if(surface!=null) {
                SITE_DIAGNOSTICS.put(entity,"surface fill workface");
                if(earthworkFooting(level,entity) && (stableFarmFooting(level,entity.navigationFeetPosition()) || ResidentEgressService.active(settlement,citizen)) && localWorkRange(entity,surface)
                        && Math.abs(entity.getY()-surface.getY())<=2) {
                    entity.stopSettlementNavigation();setActivity(data,citizen,entity,CitizenActivity.BUILDING,"");return true;
                }
                // A closed perimeter requires an explicit entrance leg before
                // an interior work-face route, especially during segmented travel.
                for(var dependency:site.terrainProject().dependencies) {
                    var field=settlement.building(dependency);
                    if(field==null || field.region()==null || !field.region().agricultural()
                            || field.region().cell(surface)==null || field.region().contains(entity.navigationFeetPosition()))continue;
                    var entrance=BlueprintTemplates.createForBuilding(field).entrances().stream()
                            .filter(e->level.getBlockState(e.position()).getBlock() instanceof FenceGateBlock)
                            .min(Comparator.comparingDouble(e->e.position().distSqr(entity.blockPosition()))).orElse(null);
                    if(entrance==null)continue;
                    var inside=entrance.position().relative(entrance.outward().getOpposite(),2);
                    var insideCell=field.region().cell(inside);
                    if(insideCell==null)continue;
                    inside=insideCell.ground().above();
                    SITE_DIAGNOSTICS.put(entity,"farm gate approach "+inside);
                    String failure=entity.navigateTo(inside,WALK_SPEED);
                    if(terminalNavigationFailure(failure)) {
                        entity.deferConstructionTarget(citizen.targetPosition(),CONSTRUCTION_TARGET_RETRY_DELAY_TICKS);clear(data,citizen,entity);
                    }
                    setActivity(data,citizen,entity,CitizenActivity.BUILDING,failure);return false;
                }
                var remembered=SITE_STATIONS.get(entity);
                var station=remembered!=null && remembered.site().equals(site.id()) && remembered.retryAt()>level.getGameTime()
                        && remembered.position()!=null && remembered.position().distSqr(surface)<=5
                        && entity.canStandAtPosition(remembered.position()) ? remembered.position() : waterBankApproach(level,entity,surface,
                                com.hkin.exiledsettlement.terrain.TerrainService.groundFillSurface(level,site.terrainProject(),site.terrainProject().steps.get(-pending.index()-1))!=null);
                if(station!=null)SITE_STATIONS.put(entity,new SiteStation(site.id(),station,level.getGameTime()+600));
                SITE_DIAGNOSTICS.put(entity,"surface fill approach "+station);
                String failure=station==null?"CONSTRUCTION_SITE_NO_ROUTE":entity.navigateTo(station,WALK_SPEED);
                if(station==null || terminalNavigationFailure(failure)) {
                    // An existing dry bank may need a paid passage before it is
                    // reachable. Keep the fill target while opening that passage.
                    if(com.hkin.exiledsettlement.terrain.TerrainService.groundFillSurface(level,site.terrainProject(),site.terrainProject().steps.get(-pending.index()-1))!=null
                            && WorkerAccessService.request(level,entity,station==null?surface.above():station,3)) {
                        SITE_DIAGNOSTICS.put(entity,"paid access to dry fill surface");return false;
                    }
                    entity.deferConstructionTarget(citizen.targetPosition(),CONSTRUCTION_TARGET_RETRY_DELAY_TICKS);
                    clear(data,citizen,entity);setActivity(data,citizen,entity,CitizenActivity.BLOCKED,failure);
                }else setActivity(data,citizen,entity,CitizenActivity.BUILDING,failure);
                return false;
            }
        }
        var template=BlueprintTemplates.create(site);
        if(site.region()!=null && citizen.targetPosition()!=null && site.region().cell(citizen.targetPosition())!=null
                && site.region().cell(citizen.targetPosition()).use()==com.hkin.exiledsettlement.region.ProductionRegion.Use.WATER) {
            var farm=settlement.building(site.region().workingBuilding);
            if(farm!=null && farm.operational()) {
                if(insideFarmWorkArea(entity,farm) && entity.onGround() && CitizenInteractionRange.contains(entity,citizen.targetPosition())) {
                    SITE_STATIONS.put(entity,new SiteStation(site.id(),entity.navigationFeetPosition(),level.getGameTime()+100));
                    entity.stopSettlementNavigation();setActivity(data,citizen,entity,CitizenActivity.BUILDING,"");return true;
                }
                var station=reserveFarmWorkPosition(level,entity,farm,citizen.targetPosition());
                String failure=station==null?"CONSTRUCTION_SITE_NO_ROUTE":entity.navigateTo(station,WALK_SPEED);
                if(station==null || terminalNavigationFailure(failure)) {
                    entity.deferConstructionTarget(citizen.targetPosition(),CONSTRUCTION_TARGET_RETRY_DELAY_TICKS);
                    clear(data,citizen,entity);setActivity(data,citizen,entity,CitizenActivity.BLOCKED,failure);
                }else setActivity(data,citizen,entity,CitizenActivity.BUILDING,failure);
                return false;
            }
        }
        var feet=entity.navigationFeetPosition();
        boolean localTarget=site.region()!=null || site.terrainProject()!=null || site.hasClearanceTarget(citizen.targetPosition())
                || groundStructureTarget(site,citizen.targetPosition());
        if ((siteGround(level,template,entity,feet) && entity.onGround()
                || site.terrainProject()!=null && earthworkFooting(level,entity))
                && (stableFarmFooting(level,feet) || ResidentEgressService.active(settlement,citizen))
                && (site.terrainProject()==null || safeTerrainStanding(level,settlement,site,entity,feet))
                && safeStructureStanding(level,site,entity,feet,citizen.targetPosition())
                && (!localTarget || localWorkRange(entity,citizen.targetPosition()) && terrainWorkInRange(site,entity,citizen.targetPosition()))) {
            SITE_DIAGNOSTICS.put(entity,"ground ready");
            SITE_STATIONS.put(entity,new SiteStation(site.id(),feet,level.getGameTime()+100));
            entity.stopSettlementNavigation();setActivity(data,citizen,entity,CitizenActivity.BUILDING,"");return true;
        }
        if(entity.isInWater() && !earthworkFooting(level,entity)) {
            String failure=entity.navigateTo(citizen.targetPosition(),WALK_SPEED);
            setActivity(data,citizen,entity,CitizenActivity.BUILDING,failure);
            SITE_DIAGNOSTICS.put(entity,"leave water for dry work footing");return false;
        }
        SiteStation cached=SITE_STATIONS.get(entity);
        if(cached!=null && cached.position()!=null && !safeStructureStanding(level,site,entity,cached.position(),citizen.targetPosition())) {
            SITE_STATIONS.remove(entity);cached=null;
        }
        if(cached!=null && cached.position()!=null && localTarget
                && cached.position().distSqr(entity.blockPosition())<=1
                && !terrainWorkInRange(site,entity,citizen.targetPosition())) {
            SITE_STATIONS.remove(entity);cached=null;
        }
        // Revalidate the chosen physical workface without replacing a valid
        // destination merely because its survey cache expired. Re-selecting
        // the nearest face every 100 ticks repeatedly changed the navigation
        // intent before its 600-tick failure budget could trigger paid access.
        // The work target or actual footing changing still releases the face.
        if(cached!=null && cached.site().equals(site.id()) && cached.position()!=null
                && cached.retryAt()<=level.getGameTime()
                && (!localTarget || Objects.equals(cached.workTarget(),citizen.targetPosition()))
                && entity.canStandAtPosition(cached.position()) && stableFarmFooting(level,cached.position())
                && (site.terrainProject()==null || safeTerrainStanding(level,settlement,site,entity,cached.position()))) {
            cached=new SiteStation(cached.site(),cached.position(),level.getGameTime()+100,cached.workTarget());
            SITE_STATIONS.put(entity,cached);
        }
        if (cached==null || !cached.site().equals(site.id()) || cached.retryAt()<=level.getGameTime()
                || localTarget && !Objects.equals(cached.workTarget(),citizen.targetPosition())) {
            BlockPos approach=localTarget?localWorkApproach(level,settlement,site,entity,citizen.targetPosition(),site.terrainProject()!=null && site.terrainProject().bridge):serviceAreaApproach(level,template,entity);
            if(approach==null && (site.terrainProject()!=null || site.hasClearanceTarget(citizen.targetPosition()) || groundStructureTarget(site,citizen.targetPosition())))
                approach=terrainBankApproach(level,settlement,site,entity,citizen.targetPosition());
            cached=new SiteStation(site.id(),approach,level.getGameTime()+100,citizen.targetPosition()); SITE_STATIONS.put(entity,cached);
        }
        if(localTarget) {
            // A buried building cell can have a legal lower workface on the
            // other side of the bank. Once already above its real obstruction,
            // let paid access expose it rather than requiring arrival at that
            // inaccessible face before any local cut may start.
            var target=citizen.targetPosition();
            boolean aboveBuriedClearance=(site.hasClearanceTarget(target) || groundStructureTarget(site,target))
                    && Math.abs(feet.getX()-target.getX())<=4 && Math.abs(feet.getZ()-target.getZ())<=4
                    && feet.getY()>target.getY();
            if((aboveBuriedClearance || cached.position()!=null && entity.blockPosition().distSqr(cached.position())<=2)
                    && !localWorkRange(entity,citizen.targetPosition())) {
                if(WorkerAccessService.request(level,entity,citizen.targetPosition(),3)) {
                    SITE_DIAGNOSTICS.put(entity,"paid bank step before deep foundation");return false;
                }
                // Reaching a staging bank is not reaching the dig. Do not keep a completed
                // navigation command forever when no real local access action is possible.
                entity.deferConstructionTarget(citizen.targetPosition(),CONSTRUCTION_TARGET_RETRY_DELAY_TICKS);
                SITE_STATIONS.remove(entity);clear(data,citizen,entity);
                setActivity(data,citizen,entity,CitizenActivity.BLOCKED,"CONSTRUCTION_SITE_NO_ROUTE");return false;
            }
            SITE_DIAGNOSTICS.put(entity,"ground approach "+cached.position());
            String failure=cached.position()==null?"CONSTRUCTION_SITE_NO_ROUTE":entity.navigateTo(cached.position(),WALK_SPEED);
            if(cached.position()==null || terminalNavigationFailure(failure)) {
                entity.deferConstructionTarget(citizen.targetPosition(),CONSTRUCTION_TARGET_RETRY_DELAY_TICKS);
                SITE_STATIONS.remove(entity);clear(data,citizen,entity);
                setActivity(data,citizen,entity,CitizenActivity.BLOCKED,failure);
                ResidentEgressService.plan(level,settlement,citizen,entity);
            }else setActivity(data,citizen,entity,CitizenActivity.BUILDING,failure);
            return false;
        }
        if (cached.position()==null) {
            setActivity(data,citizen,entity,CitizenActivity.BLOCKED,"CONSTRUCTION_SITE_NO_ROUTE");
            yieldUnreachableSite(level,data,settlement,site,citizen,entity);return false;
        }
        SITE_WAITS.remove(citizen);
        move(data,citizen,entity,cached.position(),CitizenActivity.BUILDING);return false;
    }

    private static BlockPos waterBankApproach(ServerLevel level,SettlementCitizenEntity entity,BlockPos surface) {
        return waterBankApproach(level,entity,surface,false);
    }
    private static BlockPos waterBankApproach(ServerLevel level,SettlementCitizenEntity entity,BlockPos surface,boolean paidDryAccess) {
        var candidates=new ArrayList<BlockPos>();
        // Leave room for normal navigation arrival tolerance inside interaction reach.
        for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++)if(dx*dx+dz*dz<=16) {
            int x=surface.getX()+dx,z=surface.getZ()+dz;
            for(int y=surface.getY()-1;y<=surface.getY()+2;y++) {
                var at=new BlockPos(x,y,z);
                if(level.hasChunkAt(at) && entity.canStandAtPosition(at) && stableFarmFooting(level,at) && level.getFluidState(at).isEmpty()
                    && level.getFluidState(at.below()).isEmpty() && !level.getBlockState(at.below()).is(Blocks.SCAFFOLDING)
                    && CitizenInteractionRange.containsAt(at,surface)
                    && (at.distSqr(entity.blockPosition())>1 || CitizenInteractionRange.contains(entity,surface)))candidates.add(at);
            }
        }
        var ordered=candidates.stream().sorted(Comparator.comparingDouble(p->p.distSqr(entity.blockPosition()))).toList();
        var reachable=ordered.stream().filter(entity::hasReachablePathTo).findFirst().orElse(null);
        // This is only a navigation destination, never permission to place
        // remotely. Execution still requires actual arrival on stable ground.
        return reachable!=null?reachable:paidDryAccess && !ordered.isEmpty()?ordered.getFirst():null;
    }
    private static BlockPos terrainEscape(ServerLevel level,SettlementCitizenEntity entity,BlockPos occupied) {
        var candidates=new ArrayList<BlockPos>();
        for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++)if(dx*dx+dz*dz>=4 && dx*dx+dz*dz<=16) {
            int x=occupied.getX()+dx,z=occupied.getZ()+dz;
            if(!level.hasChunkAt(new BlockPos(x,occupied.getY(),z)))continue;
            int top=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
            for(int y:new int[]{top,entity.navigationFeetPosition().getY()}) {
                var at=new BlockPos(x,y,z);
                if(entity.canStandAtPosition(at) && !level.getBlockState(at.below()).is(Blocks.SCAFFOLDING))candidates.add(at);
            }
        }
        return candidates.stream().distinct().sorted(Comparator.comparingDouble(p->p.distSqr(entity.blockPosition()))).filter(entity::hasReachablePathTo).findFirst().orElse(null);
    }
    private static boolean earthworkFooting(ServerLevel level,SettlementCitizenEntity entity) {
        if(entity.onGround() && !entity.isInWater())return true;
        var feet=entity.blockPosition();
        return entity.isInWater() && !entity.isUnderWater() && com.hkin.exiledsettlement.blueprint.FarmEarthworkPlan.support(level,feet.below())
                && !level.getBlockState(feet.below()).is(Blocks.SCAFFOLDING);
    }
    private static boolean stableFarmFooting(ServerLevel level,BlockPos feet) {
        var settlement=SettlementSavedData.get(level).findContaining(feet).orElse(null);if(settlement==null)return true;
        for(var site:settlement.blueprints())if(site.region()!=null && site.region().agricultural() && site.region().flatY!=null
                && site.region().cell(feet)!=null && feet.getY()<site.region().flatY+1)return false;
        return true;
    }
    private static boolean localWorkRange(SettlementCitizenEntity entity,BlockPos target){return target!=null && CitizenInteractionRange.contains(entity,target);}
    private static boolean treeClearanceTarget(ServerLevel level,BlueprintRecord site,BlockPos target) {
        if(target==null || !level.hasChunkAt(target) || !ResidentTreeFelling.vegetation(level.getBlockState(target)))return false;
        if(site.terrainProject()!=null) {var step=com.hkin.exiledsettlement.terrain.TerrainService.at(level,site,target);
            return step!=null && !site.terrainProject().steps.get(-step.index()-1).fill();}
        return site.hasClearanceTarget(target) || BlueprintTemplates.create(site).placementAt(target)!=null;
    }
    private static boolean treeGroundReady(ServerLevel level,SettlementRecord settlement,SettlementCitizenEntity entity,BlockPos target,BlockPos feet) {
        var tree=TreeSurvey.inspect(level,target);if(tree.tree())target=tree.root();
        if(feet==null || Math.abs(feet.getX()-target.getX())>6 || Math.abs(feet.getZ()-target.getZ())>6
                || !level.hasChunkAt(feet) || !entity.canStandAtPosition(feet) || !level.getFluidState(feet).isEmpty()
                || !level.getFluidState(feet.below()).isEmpty())return false;
        var floor=level.getBlockState(feet.below());
        return !ResidentTreeFelling.vegetation(floor) && !floor.is(Blocks.SCAFFOLDING)
                && settlement.buildings().stream().noneMatch(b->b.active() && !b.type().isDynamicArea() && b.contains(feet.below()));
    }
    private static BlockPos treeGroundApproach(ServerLevel level,SettlementRecord settlement,BlueprintRecord site,SettlementCitizenEntity entity,BlockPos target) {
        var tree=TreeSurvey.inspect(level,target);if(tree.tree())target=tree.root();
        var candidates=new ArrayList<BlockPos>();
        for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++) {
            var base=target.offset(x,0,z);if(!level.hasChunkAt(base))continue;
            // Ignore trunk/canopy height maps: use real ground and reachable low work faces.
            for(int dy=-6;dy<=6;dy++) {
                var p=base.atY(target.getY()+dy);if(treeGroundReady(level,settlement,entity,target,p))candidates.add(p);
            }
        }
        candidates.sort(Comparator.comparingDouble(p->p.distSqr(entity.blockPosition())));
        return entity.findReachableBedStation(candidates.stream().distinct().toList());
    }
    /** Low final cells need a safe work position near that cell, including an exterior threshold. */
    private static boolean groundStructureTarget(BlueprintRecord site,BlockPos target){
        if(target==null || site.terrainProject()!=null || site.region()!=null || target.getY()>site.anchor().getY())return false;
        var placement=BlueprintTemplates.create(site).placementAt(target);
        return placement!=null && placement.mode().isFinalStructure();
    }
    private static boolean safeStructureStanding(ServerLevel level,BlueprintRecord site,SettlementCitizenEntity entity,BlockPos feet,BlockPos target) {
        if(!groundStructureTarget(site,target))return true;
        var template=BlueprintTemplates.create(site);var placement=template.placementAt(target);
        // Work on a low floor from its ground level or above, never in the gap under it.
        return feet.getY()>=target.getY() && ConstructionOccupancy.safeStanding(level,template,placement,entity,feet);
    }
    private static boolean terrainWorkInRange(BlueprintRecord site,SettlementCitizenEntity entity,BlockPos target) {
        if(site.terrainProject()!=null && site.terrainProject().playerBridge!=null)
            return com.hkin.exiledsettlement.road.PlayerBridgeWork.arrived((ServerLevel)entity.level(),site.terrainProject(),entity.navigationFeetPosition(),target);
        if(entity.level() instanceof ServerLevel level && treeClearanceTarget(level,site,target)) {
            var settlement=SettlementSavedData.get(level).findById(CitizenProjectionService.settlementId(entity)).orElse(null);
            if(settlement!=null)return treeGroundReady(level,settlement,entity,target,entity.navigationFeetPosition());
        }
        return CitizenInteractionRange.contains(entity,target);
    }
    private static BlockPos localWorkApproach(ServerLevel level,SettlementRecord settlement,BlueprintRecord site,SettlementCitizenEntity entity,BlockPos target,boolean bridge) {
        if(target==null)return null;var candidates=new ArrayList<BlockPos>();
        for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++) {
            int x=target.getX()+dx,z=target.getZ()+dz;var seed=new BlockPos(x,target.getY(),z);if(!level.hasChunkAt(seed))continue;
            int top=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
            for(int y:new int[]{target.getY()+1,top,entity.blockPosition().getY()}) {
                var at=new BlockPos(x,y,z);if(!level.isOutsideBuildHeight(at) && entity.canStandAtPosition(at) && safeStructureStanding(level,site,entity,at,target)
                        && stableFarmFooting(level,at) && (site.terrainProject()==null || safeTerrainStanding(level,settlement,site,entity,at)) && !level.getBlockState(at.below()).is(Blocks.SCAFFOLDING)
                        && (at.distSqr(entity.blockPosition())>1 || CitizenInteractionRange.contains(entity,target))
                        && CitizenInteractionRange.containsAt(at,target))candidates.add(at);
            }
        }
        var ordered=candidates.stream().distinct().sorted(Comparator.comparingDouble(p->p.distSqr(entity.blockPosition()))).toList();
        // Retain a physically legal workface as navigation intent after a failed
        // preview, so normal detours and the paid local access fallback can run.
        var reachable=entity.findReachableBedStation(ordered);
        return reachable!=null?reachable:ordered.isEmpty()?null:ordered.getFirst();
    }
    /** An already trapped worker finishes the registered bottom-up fill at its feet first. */
    private static boolean resumeRegionPitFill(ServerLevel level,SettlementSavedData data,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e) {
        if(c.profession()!=CitizenProfession.WORKER || !e.onGround() || e.isInWater() || e.hasActiveConstructionMovement()
                || c.workerAccess.block!=null || !isWorkTime(level,c))return false;
        var feet=e.navigationFeetPosition();
        for(var project:s.terrain().projectsAt(feet)) {
            var site=s.blueprint(project.id);if(site==null || site.cancellationQueued()
                    || !com.hkin.exiledsettlement.terrain.TerrainService.allowed(s,site))continue;
            var parent=project.dependencies.stream().map(s::blueprint).filter(b->WorkTaskService.building(s,b)).findFirst().orElse(null);
            if(parent==null)continue;
            Integer surface=parent.region()!=null && parent.region().levelled() && parent.region().flatY!=null
                    ?parent.region().flatY:WorkerAccessService.foundationColumnSurface(site,feet);
            if(surface==null)continue;
            if(feet.getY()>surface || !WorkerAccessService.canRaiseFoundation(level,s,c,e,site,feet,surface))continue;
            if(!site.id().equals(c.targetBlueprintId()) || !feet.equals(c.targetPosition())) {
                clear(data,c,e);SITE_STATIONS.remove(e);c.startTask(CitizenWorkState.TO_BLUEPRINT,feet,null,site.id());
            }
            WorkerAccessService.requestFoundationRaise(level,s,c,e,site,surface);data.touch();return true;
        }
        return false;
    }
    /** Bounded read-only view of a worker's real terrain work faces for acceptance diagnosis. */
    public static com.google.gson.JsonObject terrainWorkFaces(ServerLevel level,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e){
        var out=new com.google.gson.JsonObject();var site=s.blueprint(c.targetBlueprintId());var target=c.targetPosition();
        if(e==null || site==null || target==null)return out;
        var progress=ENGINEERING_PROGRESS.get(c);
        if(progress!=null){out.addProperty("engineeringProgressAge",level.getGameTime()-progress.progressAt);
            out.addProperty("engineeringLastSeenAge",level.getGameTime()-progress.lastSeen);out.addProperty("engineeringVisited",progress.visited.size());
            out.addProperty("engineeringActionAge",level.getGameTime()-progress.actionAt);}
        out.addProperty("actualTarget",level.getBlockState(target).toString());out.addProperty("siteDiagnostic",SITE_DIAGNOSTICS.get(e));
        out.addProperty("feet",e.navigationFeetPosition().toShortString());
        out.addProperty("onGround",e.onGround());out.addProperty("movement",e.constructionMovementMode());
        out.addProperty("retreating",e.isConstructionAccessRetreating());
        out.addProperty("localCut",String.valueOf(c.workerAccess.block));out.addProperty("localCutTicks",c.workerAccess.ticks);
        var station=SITE_STATIONS.get(e);var accessGoal=station!=null && station.position()!=null?station.position():target;
        out.add("localAccessGeometry",WorkerAccessService.observation(level,s,c,e,accessGoal));
        out.addProperty("localCutTickAge",level.getGameTime()-c.workerAccess.lastTick);
        if(site.terrainProject()==null){
            out.addProperty("clearanceTarget",site.hasClearanceTarget(target));
            out.addProperty("clearanceRemaining",site.clearanceTargets().size());
            var cuts=new com.google.gson.JsonArray();site.clearanceTargets().keySet().stream()
                .sorted(Comparator.comparingInt((BlockPos pos)->pos.getY()).reversed()).limit(8).forEach(at->{
                    var j=new com.google.gson.JsonObject();j.addProperty("at",at.toShortString());j.addProperty("actual",level.getBlockState(at).toString());cuts.add(j);
                });out.add("clearanceTop",cuts);return out;
        }
        var parent=s.blueprint(WorkTaskService.mother(s,site,target));
        out.addProperty("mother",parent==null?"":parent.id().toString());
        out.addProperty("landing",parent==null?e.blockPosition().getY()-1:parent.anchor().getY()-1);
        out.addProperty("feet",e.navigationFeetPosition().toShortString());out.addProperty("visible",terrainWorkInRange(site,e,target));
        var pending=BlueprintService.pendingAt(level,site,target);
        out.addProperty("targetPending",pending!=null);out.addProperty("goal",pending==null?"":pending.placement().state().toString());
        var faces=new com.google.gson.JsonArray();
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            int x=target.getX()+dx,z=target.getZ()+dz;
            if(!level.hasChunkAt(new BlockPos(x,target.getY(),z)))continue;
            int top=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
            for(int y:new int[]{target.getY()+1,top}){
                var at=new BlockPos(x,y,z);var face=new com.google.gson.JsonObject();face.addProperty("at",at.toShortString());
                face.addProperty("stand",e.canStandAtPosition(at));face.addProperty("safe",safeTerrainStanding(level,s,site,e,at));
                face.addProperty("visible",farmInteractionVisible(level,at,target,e));
                face.addProperty("footing",level.getBlockState(at.below()).toString());faces.add(face);
            }
        }
        out.add("faces",faces);return out;
    }
    private static boolean safeTerrainStanding(ServerLevel level,SettlementRecord s,BlueprintRecord site,SettlementCitizenEntity e,BlockPos at) {
        if(site.terrainProject()==null || site.terrainProject().bridge)return true;
        var record=s.citizen(CitizenProjectionService.citizenId(e));if(record!=null && ResidentEgressService.active(s,record))return true;
        var parent=s.blueprint(WorkTaskService.mother(s,site,at));
        if(parent==site || parent==null)parent=site.terrainProject().dependencies.stream().map(s::blueprint).filter(b->WorkTaskService.building(s,b)).findFirst().orElse(null);
        int landing=parent==null?e.blockPosition().getY()-1:parent.anchor().getY()-1;
        if(at.getY()>=landing)return true;
        Integer surface=parent!=null && parent.region()!=null && parent.region().flatY!=null
                ?parent.region().flatY:WorkerAccessService.foundationColumnSurface(site,at);
        if(record!=null && surface!=null && WorkerAccessService.canRaiseFoundation(level,s,record,e,site,at,surface))return true;
        var seen=new HashSet<BlockPos>();var queue=new ArrayDeque<BlockPos>();queue.add(at);seen.add(at);
        while(!queue.isEmpty() && seen.size()<128){var p=queue.removeFirst();
            for(var d:Direction.Plane.HORIZONTAL)for(int dy:new int[]{0,1,-1}){var next=p.relative(d).above(dy);
                if(Math.abs(next.getX()-at.getX())>4 || Math.abs(next.getZ()-at.getZ())>4 || Math.abs(next.getY()-at.getY())>4 || !seen.add(next))continue;
                if(!level.hasChunkAt(next) || !e.canStandAtPosition(next) || !level.getBlockState(next.below()).isFaceSturdy(level,next.below(),Direction.UP)
                        || !e.canTraverseRecordedConstructionEdge(p,next))continue;
                if(next.getY()>=landing)return true;queue.add(next);
            }
        }
        return TerrainWorkSafety.hasReturn(e,at,landing);
    }
    private static BlockPos terrainBankApproach(ServerLevel level,SettlementRecord s,BlueprintRecord site,SettlementCitizenEntity e,BlockPos target){
        if(target==null)return null;var choices=new ArrayList<BlockPos>();
        var worker=s.citizen(CitizenProjectionService.citizenId(e));
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)if(Math.abs(dx)+Math.abs(dz)>0){
            var p=target.offset(dx,0,dz);var top=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,p.getX(),p.getZ());p=p.atY(top);
            if(top>target.getY() && e.canStandAtPosition(p)
                    && (site.terrainProject()==null || safeTerrainStanding(level,s,site,e,p))
                    && worker!=null && WorkerAccessService.previewCut(level,s,worker,p,target)!=null)choices.add(p);
        }
        return choices.stream().sorted(Comparator.comparingDouble((BlockPos p)->p.distSqr(e.blockPosition())).thenComparingDouble(p->p.distSqr(target))).filter(e::hasReachablePathTo).findFirst().orElse(null);
    }
    /** Take a bounded real batch before departing a physically reached creative core. */
    private static void collectSiteBatchAtCore(ServerLevel level,SettlementRecord s,BlueprintRecord site,
            CitizenRecord c,SettlementCitizenEntity e){
        if(!s.creativeCoreInstalled() || site.terrainProject()!=null || site.region()!=null
                || !SettlementCoreInteractionService.canReceiveSupply(level,s,e))return;
        var requirements=new LinkedHashMap<Item,Integer>();
        var preferred=BlueprintService.pendingAt(level,site,c.targetPosition());
        if(preferred!=null && preferred.placement().material()!=null)requirements.put(preferred.placement().material(),0);
        ConstructionPlanService.createSupplyPlan(level,site,Set.of(),e.blockPosition(),c.targetPosition()).requirements()
                .forEach((item,count)->requirements.put(item,Math.min(item.getDefaultMaxStackSize(),count)));
        requirements.put(Items.SCAFFOLDING,Math.min(64,CONSTRUCTION_FOOTING_RESERVE));
        for(var entry:requirements.entrySet()){
            if(!c.hasInventoryItem(stack->stack.is(entry.getKey()))
                    && c.inventory().stream().filter(ItemStack::isEmpty).count()<=6)continue;
            loadConstructionMaterial(level,null,null,c,e,entry.getKey(),entry.getValue(),false,true);
        }
    }

    private static boolean prepareGroundSupplies(ServerLevel level,SettlementSavedData data,SettlementRecord s,BlueprintRecord site,CitizenRecord c,SettlementCitizenEntity e){
        if(site.terrainProject()==null){
            var next=BlueprintService.pendingAt(level,site,c.targetPosition());
            if(next==null || next.placement().state().isAir() || site.hasClearanceTarget(c.targetPosition()))return false;
            var material=next.placement().material();
            if(material!=null && siteMaterialHolder(level,s,c,material)==null){requestSiteMaterial(level,data,s,site,c,e,material);return true;}
            return false;
        }
        var pending=BlueprintService.pendingAt(level,site,c.targetPosition());if(pending==null)return false;
        var state=level.getBlockState(c.targetPosition());
        boolean direct=FoundationPlacement.replaces(level,site.terrainProject(),site.terrainProject().steps.get(-pending.index()-1));
        boolean replacing=direct && !state.isAir();
        boolean fill=!pending.placement().state().isAir() && !replacing;
        var tag=fill?net.minecraft.tags.ItemTags.SHOVELS:state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE)?net.minecraft.tags.ItemTags.PICKAXES
                :state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE)?net.minecraft.tags.ItemTags.AXES:state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL)?net.minecraft.tags.ItemTags.SHOVELS:null;
        if(tag!=null && c.findInventorySlot(stack->stack.is(tag) && (fill || !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state)))<0){
            requestConstructionTool(level,data,s,c,e,tag==net.minecraft.tags.ItemTags.SHOVELS?Items.IRON_SHOVEL:tag==net.minecraft.tags.ItemTags.AXES?Items.IRON_AXE:Items.DIAMOND_PICKAXE,fill?null:state);return true;
        }
        var item=pending.placement().material();
        var plan=site.terrainProject();var step=plan.steps.get(-pending.index()-1);
        if(step.fill())item=com.hkin.exiledsettlement.terrain.BuriedFillMaterials.choose(level,s,plan,step,c).getBlock().asItem();
        if(item!=null && (direct || !com.hkin.exiledsettlement.terrain.TerrainService.soft(state)) && siteMaterialHolder(level,s,c,item)==null){requestSiteMaterial(level,data,s,site,c,e,item);return true;}
        return false;
    }
    private static BlockPos serviceAreaApproach(ServerLevel level,BuildingBlueprintTemplate template,SettlementCitizenEntity entity) {
        BlockPos min=template.minCorner(),max=template.maxCorner();
        int x=Math.clamp(entity.blockPosition().getX(),min.getX(),max.getX());
        int z=Math.clamp(entity.blockPosition().getZ(),min.getZ(),max.getZ());
        List<BlockPos> candidates=new ArrayList<>();
        for (BlockPos base:List.of(new BlockPos(min.getX()-1,min.getY()+1,z),new BlockPos(max.getX()+1,min.getY()+1,z),
                new BlockPos(x,min.getY()+1,min.getZ()-1),new BlockPos(x,min.getY()+1,max.getZ()+1))) {
            int range=template.terrainWork()?template.maxCorner().getY()-template.minCorner().getY()+2:com.hkin.exiledsettlement.blueprint.FarmEarthworkPlan.applies(template.type())
                    ? Math.max(com.hkin.exiledsettlement.blueprint.FarmEarthworkConfig.DIG_HEIGHT.get(),
                        com.hkin.exiledsettlement.blueprint.FarmEarthworkConfig.FILL_DEPTH.get())+1 : 2;
            for(int i=0;i<=range*2;i++) {
                int dy=i==0 ? 0 : i%2==1 ? (i+1)/2 : -i/2;
                if(siteGround(level,template,entity,base.offset(0,dy,0))) {candidates.add(base.offset(0,dy,0));break;}
            }
            if(level.hasChunkAt(base)){
                var actual=base.atY(level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,base.getX(),base.getZ()));
                if(siteGround(level,template,entity,actual))candidates.add(actual);
            }
        }
        var ordered=candidates.stream().distinct().sorted(Comparator.comparingDouble(at->at.distSqr(entity.blockPosition()))).toList();
        // A failed preview must not erase the navigation intent that can invoke
        // ordinary detours and local paid obstacle removal. The destination
        // itself still requires real footing and body clearance.
        return ordered.stream().filter(entity::hasReachablePathTo).findFirst().orElse(ordered.isEmpty()?null:ordered.getFirst());
    }

    private static CitizenRecord siteMaterialHolder(ServerLevel level, SettlementRecord settlement, CitizenRecord worker, Item material) {
        if (countInventoryItem(worker, material) > 0) return worker;
        for (CitizenRecord other : settlement.citizens()) {
            if (other.alive() && worker.targetBlueprintId().equals(other.targetBlueprintId())
                    && canShareSiteMaterial(level, worker, other) && countInventoryItem(other, material) > 0) return other;
        }
        return null;
    }

    private static void requestSiteMaterial(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord site, CitizenRecord citizen, SettlementCitizenEntity entity, Item material) {
        BuildingRecord warehouse = WarehouseConstructionSupply.warehouse(level,settlement,entity.blockPosition(),material);
        if(warehouse==null && DecorationService.owned(settlement,site.id()))
            warehouse=DecorationCrafting.warehouse(level,settlement,citizen,material);
        if(site.terrainProject()!=null && site.terrainProject().playerBridge!=null) {
            warehouse=com.hkin.exiledsettlement.road.PlayerBridgeWork.supplier(level,settlement,citizen,entity.blockPosition(),material);
        } else if(site.terrainProject()!=null && site.terrainProject().bridge && !settlement.creativeCoreInstalled()) {
            warehouse=SettlementInventoryService.warehouses(settlement).stream()
                    .filter(w->WarehouseConstructionSupply.available(level,settlement,w,material,1))
                    .sorted(Comparator.comparingDouble(w->w.position().distSqr(entity.blockPosition())))
                    .filter(w->entity.hasReachablePathTo(WarehouseInventoryService.nearestContainerPosition(level,w,entity.blockPosition())))
                    .findFirst().orElse(null);
        }
        if (warehouse == null && material == Items.SCAFFOLDING) {
            warehouse = WarehouseConstructionSupply.warehouse(level, settlement, entity.blockPosition(), Items.STICK);
        }
        if (warehouse == null && !settlement.creativeCoreInstalled()
                && level.getServer().getPlayerList().getPlayer(site.ownerId()) == null) {
            clear(data,citizen,entity);data.touch();
            return;
        }
        SITE_STATIONS.remove(entity);
        if (material == Items.SCAFFOLDING) citizen.fetchFootingMaterial(warehouse == null ? null : warehouse.id());
        else citizen.fetchConstructionMaterial(warehouse == null ? null : warehouse.id());
        data.touch();
    }

    private static void advanceTimedConstruction(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord site, CitizenRecord citizen, SettlementCitizenEntity entity, long now) {
        var claimed=new HashSet<BlockPos>();
        for(var other:settlement.citizens())if(other!=citizen && other.workState()==CitizenWorkState.TO_BLUEPRINT
                && site.id().equals(other.targetBlueprintId()) && other.targetPosition()!=null)claimed.add(other.targetPosition());
        var pending=citizen.targetPosition()==null?null:BlueprintService.pendingAt(level,site,citizen.targetPosition());
        if(pending==null)pending=BlueprintService.nextUnclaimedPending(level,data,site,claimed,entity.blockPosition());
        if(pending==null) {
            if(com.hkin.exiledsettlement.blueprint.TimedConstructionService.building(site))
                BlueprintService.finishTimedIfReady(level,data,settlement,site);
            else if(com.hkin.exiledsettlement.terrain.TerrainService.constructionComplete(level,site.terrainProject()))
                com.hkin.exiledsettlement.terrain.TerrainService.completed(level,settlement,site);
            clear(data,citizen,entity);data.touch();return;
        }
        citizen.updateTargetPosition(pending.placement().position());
        // Reselection can change the action after dispatch. Hand it to its real
        // excavation path now, with that path's arrival/tool/drop checks.
        if(site.terrainProject()!=null && !site.terrainProject().steps.get(-pending.index()-1).fill()) {
            SITE_STATIONS.remove(entity);
            if(prepareSiteStation(level,data,settlement,citizen,entity))advanceTerrain(level,data,settlement,site,citizen,entity,now);
            return;
        }
        if(!com.hkin.exiledsettlement.blueprint.TimedConstructionService.arrived(level,site,entity,citizen.targetPosition())
                && !(treeClearanceTarget(level,site,citizen.targetPosition()) && treeGroundReady(level,settlement,entity,citizen.targetPosition(),entity.navigationFeetPosition()))) {
            SITE_STATIONS.remove(entity);
            setActivity(data,citizen,entity,CitizenActivity.BLOCKED,"CONSTRUCTION_TARGET_OUT_OF_REACH");return;
        }
        var placement=pending.placement();
        var plan=site.terrainProject();
        var current=level.getBlockState(citizen.targetPosition());
        boolean replacing=!current.isAir() && current.getBlock()!=placement.state().getBlock();
        // Direct filling can replace stone or wood. Its removal tool takes precedence over the fill's shovel.
        var toolTag=replacing && current.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE)?net.minecraft.tags.ItemTags.PICKAXES
                :replacing && current.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE)?net.minecraft.tags.ItemTags.AXES
                :replacing && current.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL)?net.minecraft.tags.ItemTags.SHOVELS
                :plan!=null || placement.state().is(Blocks.DIRT_PATH)?net.minecraft.tags.ItemTags.SHOVELS
                :placement.mode()==BlueprintPlacementMode.FARM_TILL?net.minecraft.tags.ItemTags.HOES:null;
        int toolSlot=toolTag==null?-1:citizen.findInventorySlot(stack->stack.is(toolTag)
                && (!replacing || !current.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(current)));
        if(toolTag!=null && toolSlot<0) {
            requestConstructionTool(level,data,settlement,citizen,entity,toolTag==net.minecraft.tags.ItemTags.SHOVELS?Items.IRON_SHOVEL
                    :toolTag==net.minecraft.tags.ItemTags.HOES?Items.IRON_HOE:toolTag==net.minecraft.tags.ItemTags.AXES?Items.IRON_AXE:Items.DIAMOND_PICKAXE,replacing?current:null);return;
        }
        if(toolSlot>=0){citizen.selectActiveSlot(toolSlot);syncHeldItem(citizen,entity);}
        int index=plan==null?-1:-pending.index()-1;
        var target=plan==null?placement.state():com.hkin.exiledsettlement.terrain.BuriedFillMaterials.choose(level,settlement,plan,plan.steps.get(index),citizen);
        var material=plan==null?placement.material():target.getBlock().asItem();
        var holder=material==null?citizen:siteMaterialHolder(level,settlement,citizen,material);
        if(holder==null){requestSiteMaterial(level,data,settlement,site,citizen,entity,material);return;}
        var supplied=material==null?ItemStack.EMPTY:holder.extractInventoryItem(stack->stack.is(material),1);
        String failure="";
        boolean completed=false;
        if(plan==null) {
            var result=BlueprintService.placeTimedAt(level,data,settlement,site,citizen.targetPosition(),supplied,entity);
            if(!result.success())failure=result.reason();
            completed=result.success() && settlement.blueprint(site.id())==null;
        } else {
            var step=plan.steps.get(index);
            failure=com.hkin.exiledsettlement.terrain.TerrainService.guard(level,settlement,site,step);
            if(failure.isEmpty() && supplied.isEmpty())failure="MATERIALS_MISSING";
            if(failure.isEmpty() && !com.hkin.exiledsettlement.blueprint.TimedConstructionService.put(level,step.position(),target,entity))failure="PROTECTED_AREA";
            if(failure.isEmpty()) {
                supplied.shrink(1);plan.recordFill(index,target);plan.mark(index);
                // Direct replacement performs the paired excavation as well; it must not be dug back out afterwards.
                for(int paired:plan.indices(step.position()))if(!plan.steps.get(paired).fill() && !plan.finished.get(paired))plan.mark(paired);
                if(com.hkin.exiledsettlement.terrain.TerrainService.constructionComplete(level,plan)) {
                    com.hkin.exiledsettlement.terrain.TerrainService.completed(level,settlement,site);completed=true;
                }
            }
        }
        if(!supplied.isEmpty())holder.insertIntoInventory(supplied);
        if(!failure.isEmpty()) {
            entity.deferConstructionTarget(citizen.targetPosition(),CONSTRUCTION_TARGET_RETRY_DELAY_TICKS);
            clear(data,citizen,entity);setActivity(data,citizen,entity,CitizenActivity.BLOCKED,failure);return;
        }
        if(toolSlot>=0)citizen.damageInventoryItem(toolSlot,1);
        com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,citizen,plan==null?"timed_building":"timed_fill",
                citizen.targetPosition(),net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(target.getBlock()).toString(),1);
        entity.swing(net.minecraft.world.InteractionHand.MAIN_HAND);SITE_SUCCESS_TICKS.put(entity,now);
        if(completed)clear(data,citizen,entity);else setActivity(data,citizen,entity,
                placement.mode()==BlueprintPlacementMode.CLEARANCE ? CitizenActivity.CLEARING_SITE : CitizenActivity.BUILDING,"");
        data.touch();
    }

    public static void advanceSiteConstruction(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity) {
        if (!citizen.alive() || citizen.workState() != CitizenWorkState.TO_BLUEPRINT || !isWorkTime(level, citizen) && !ResidentEgressService.active(settlement,citizen)
                || entity.isDefending() || entity.isSleeping() || ConstructionDebugService.isEnabled(level, settlement.id())) return;
        if (pauseForCargoDelivery(level, data, settlement, citizen, entity)) return;
        long now = level.getGameTime();
        var cadenceSite=settlement.blueprint(citizen.targetBlueprintId());
        if(citizen.workerAccess.block!=null)return;
        boolean timed=com.hkin.exiledsettlement.blueprint.TimedConstructionService.building(cadenceSite)
                || cadenceSite!=null && cadenceSite.terrainProject()!=null && cadenceSite.terrainProject().playerBridge!=null
                || com.hkin.exiledsettlement.blueprint.TimedConstructionService.fill(level,cadenceSite,citizen.targetPosition());
        int cadence=timed?com.hkin.exiledsettlement.blueprint.TimedConstructionService.INTERVAL
                :cadenceSite!=null && (cadenceSite.terrainProject()!=null || cadenceSite.earthworkPlan()!=null)?5:10;
        if (now - SITE_ACTION_TICKS.getOrDefault(entity, now - cadence) < cadence) return;
        SITE_ACTION_TICKS.put(entity, now); // No catch-up credit after absence or repeated invocations.
        if (!prepareSiteStation(level, data, settlement, citizen, entity)) return;
        BlueprintRecord site = settlement.blueprint(citizen.targetBlueprintId());
        if(timed) {advanceTimedConstruction(level,data,settlement,site,citizen,entity,now);return;}
        if(site.terrainProject()!=null) {advanceTerrain(level,data,settlement,site,citizen,entity,now);return;}
        if (com.hkin.exiledsettlement.blueprint.FarmEarthworkService.applies(site)
                && advanceFarmEarthwork(level,data,settlement,site,citizen,entity,now)) return;
        var pending = citizen.targetPosition()==null?null:BlueprintService.pendingAt(level,site,citizen.targetPosition());
        if(pending==null) {
            var claimed=new HashSet<BlockPos>();for(var other:settlement.citizens())if(other!=citizen && other.targetPosition()!=null)claimed.add(other.targetPosition());
            pending=BlueprintService.nextUnclaimedPending(level,data,site,claimed,entity.blockPosition());
        }
        if(com.hkin.exiledsettlement.blueprint.FarmEarthworkService.applies(site)) {
            String failure=com.hkin.exiledsettlement.blueprint.FarmEarthworkService.prepareTarget(level,settlement,site,
                    pending==null ? null : pending.placement());
            if(!failure.isEmpty()) {setActivity(data,citizen,entity,CitizenActivity.BLOCKED,failure);return;}
            if(advanceFarmEarthwork(level,data,settlement,site,citizen,entity,now)) return;
        }
        if (pending == null) {
            recoverAllTemporaryFootingsDirectly(level, site, citizen, entity, false);
            var result = BlueprintService.completeAfterFootingCleanup(level, data, settlement, site);
            data.touch();
            clear(data,citizen,entity);
            return;
        }
        citizen.updateTargetPosition(pending.placement().position());
        if(site.region()!=null && !terrainWorkInRange(site,entity,citizen.targetPosition())){SITE_STATIONS.remove(entity);return;}
        if (site.hasClearanceTarget(citizen.targetPosition())) {
            if(!terrainWorkInRange(site,entity,citizen.targetPosition())){SITE_STATIONS.remove(entity);return;}
            int clearanceTool=-1;
            var beforeClear=level.getBlockState(citizen.targetPosition());
            if(!beforeClear.isAir()) {
                var current=level.getBlockState(citizen.targetPosition());
                var tag=current.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE) ? net.minecraft.tags.ItemTags.PICKAXES
                        : current.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE) ? net.minecraft.tags.ItemTags.AXES
                        : current.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL) ? net.minecraft.tags.ItemTags.SHOVELS : null;
                clearanceTool=tag==null ? -1 : citizen.findInventorySlot(stack -> stack.is(tag)
                        && (!current.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(current)));
                if(tag!=null && clearanceTool<0) {
                    requestConstructionTool(level,data,settlement,citizen,entity,
                            tag==net.minecraft.tags.ItemTags.PICKAXES ? Items.DIAMOND_PICKAXE
                                    : tag==net.minecraft.tags.ItemTags.AXES ? Items.IRON_AXE : Items.IRON_SHOVEL,current);return;
                }
                if(!clearanceActionReady(level,citizen,citizen.targetPosition(),current,
                        clearanceTool<0?ItemStack.EMPTY:citizen.inventoryItem(clearanceTool))) return;
                if(clearanceTool>=0) citizen.selectActiveSlot(clearanceTool);
            }
            var result = BlueprintService.clearAt(level, data, settlement, site, citizen.targetPosition(), entity,
                    clearanceTool<0 ? citizen.carriedItem() : citizen.inventoryItem(clearanceTool));
            if (!result.success()) { setActivity(data, citizen, entity, CitizenActivity.BLOCKED, result.reason()); return; }
            // Reconciliation of an already satisfied target does not reset the
            // engineering watchdog or count as a physical construction action.
            if(level.getBlockState(citizen.targetPosition()).equals(beforeClear))return;
            for (ItemStack drop : result.value()) {
                ItemStack excess = citizen.insertIntoInventory(drop);
                if (!excess.isEmpty()) Block.popResource(level, entity.blockPosition(), excess);
            }
            if(clearanceTool>=0) citizen.damageInventoryItem(clearanceTool,1);
            com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,citizen,"building_clear",
                    citizen.targetPosition(),net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(beforeClear.getBlock()).toString(),1);
        } else {
            if(pending.placement().state().is(Blocks.DIRT_PATH)) {
                int shovel=citizen.findInventorySlot(stack->stack.is(net.minecraft.tags.ItemTags.SHOVELS));
                if(shovel<0){requestConstructionTool(level,data,settlement,citizen,entity,Items.IRON_SHOVEL,null);return;}
                citizen.selectActiveSlot(shovel);
            }
            if(pending.placement().mode()==BlueprintPlacementMode.FARM_TILL) {
                if(clearFarmCover(level,data,settlement,citizen,entity,citizen.targetPosition())) {
                    if(citizen.activity()!=CitizenActivity.BLOCKED) {
                        site.constructionController().recordGroundAction(now,"GROUND_CLEAR_VEGETATION",citizen.targetPosition().above());
                        SITE_SUCCESS_TICKS.put(entity,now);setActivity(data,citizen,entity,CitizenActivity.BUILDING,"");
                    }
                    return;
                }
                if(!level.getBlockState(citizen.targetPosition().above()).isAir()) {
                    setActivity(data,citizen,entity,CitizenActivity.BLOCKED,"BLUEPRINT_BLOCKED");return;
                }
                int hoe=citizen.findInventorySlot(stack -> stack.is(net.minecraft.tags.ItemTags.HOES));
                if(hoe<0) { requestConstructionTool(level,data,settlement,citizen,entity,Items.IRON_HOE,null); return; }
                citizen.selectActiveSlot(hoe);
            }
            // Ground-site construction does not require an exact standing cell at every block.
            // Its optional exterior frame must therefore never gate all final structure work.
            // Interleave one access action with two structure actions; unavailable access falls
            // through immediately, without fetching optional material or deleting solid terrain.
            if (!site.constructionController().prepareGroundAccessNext()
                    || !advanceOptionalGroundScaffold(level, data, settlement, site, citizen, entity, now)) {
                var template = BlueprintTemplates.create(site);
                var dependency = com.hkin.exiledsettlement.blueprint.ConstructionDependencies.resolve(level, template, pending.placement());
                if (dependency.cycle()) { setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "CONSTRUCTION_DEPENDENCY_CYCLE"); return; }
                if (dependency.target() != null) pending = BlueprintService.pendingAt(level, site, dependency.target());
                if (pending == null) return;
                citizen.updateTargetPosition(pending.placement().position());
                Item material = pending.placement().material();
                CitizenRecord holder = material == null ? citizen : siteMaterialHolder(level, settlement, citizen, material);
                if (holder == null) { requestSiteMaterial(level, data, settlement, site, citizen, entity, material); return; }
                ItemStack supplied = material == null ? ItemStack.EMPTY : holder.extractInventoryItem(s -> s.is(material), 1);
                var result = BlueprintService.placeAt(level, data, settlement, site, citizen.targetPosition(), supplied, entity);
                if (!supplied.isEmpty()) holder.insertIntoInventory(supplied);
                if (!result.success()) {
                    setActivity(data, citizen, entity, CitizenActivity.BLOCKED, result.reason());
                    if(ConstructionOccupancy.FAILURE.equals(result.reason()))yieldOccupiedStructure(level,data,settlement,site,citizen,entity);
                    return;
                }
                if(site.region()==null && site.terrainProject()==null && material!=null && supplied.isEmpty())
                    com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,citizen,"building_structure",
                            citizen.targetPosition(),net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(material).toString(),1);
                if(pending.placement().mode()==BlueprintPlacementMode.FARM_TILL || pending.placement().state().is(Blocks.DIRT_PATH)) citizen.damageInventoryItem(citizen.activeSlot(),1);
                site.constructionController().recordGroundAction(now, "GROUND_STRUCTURE", citizen.targetPosition());
                if (result.value() != null) clear(data, citizen, entity);
                else if (site.region()!=null && site.region().workingBuilding!=null
                        && site.region().workingBuilding.equals(citizen.workBuildingId())
                        && (citizen.profession()==CitizenProfession.FARMER || citizen.profession()==CitizenProfession.MELON_FARMER)) {
                    // Re-evaluate irrigation before taking ordinary low-layer tilling
                    // from the construction cursor; mature harvest still has priority.
                    finishFarmTask(level,data,settlement,citizen,entity);
                }
            }
        }
        entity.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        SITE_SUCCESS_TICKS.put(entity, now);
        syncHeldItem(citizen, entity);
        data.touch();
    }

    public static void requestConstructionTool(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity, Item tool, BlockState target) {
        UUID resume=citizen.targetBlueprintId();BlockPos resumeAt=citizen.targetPosition();
        UUID mother=citizen.motherTaskId();
        clear(data,citizen,entity);
        citizen.setConstructionResume(resume,resumeAt);
        citizen.setMotherTask(mother);
        citizen.requestConstructionTool(tool,target);
        Item required=CitizenEquipmentService.nextEquipment(level,settlement,citizen);
        if(required!=null) {
            var warehouse=CitizenEquipmentService.supplyWarehouse(level,settlement,citizen,entity.blockPosition(),required);
            citizen.startEquipmentFetch(warehouse==null ? null : warehouse.id(),warehouse==null ? settlement.corePosition() : warehouse.position());
            setActivity(data,citizen,entity,CitizenActivity.FETCHING_EQUIPMENT,"FARM_EARTHWORK_TOOL_MISSING");
        }
        data.touch();
    }

    /** A blocked worker may finish one already planned footing under its own feet. */
    public static void advanceAccessFill(ServerLevel level,SettlementSavedData data,SettlementRecord s,
            BlueprintRecord site,CitizenRecord c,SettlementCitizenEntity e,BlockPos feet){
        var work=com.hkin.exiledsettlement.terrain.TerrainService.at(level,site,feet);
        if(work==null || !FoundationPlacement.applies(site.terrainProject(),site.terrainProject().steps.get(-work.index()-1)))return;
        var oldTarget=c.targetPosition();var oldSite=c.targetBlueprintId();var oldState=c.workState();
        c.updateTargetPosition(feet);
        try{advanceTerrain(level,data,s,site,c,e,level.getGameTime());}
        finally{if(c.workState()==oldState && java.util.Objects.equals(c.targetBlueprintId(),oldSite))c.updateTargetPosition(oldTarget);}
    }

    /** One real paid action per resident cadence, from a physically reached ground work area. */
    private static boolean advanceTerrain(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord site, CitizenRecord citizen, SettlementCitizenEntity entity, long now) {
        com.hkin.exiledsettlement.terrain.TerrainService.pending(level,site,Set.of());
        var plan=site.terrainProject();
        if(plan.done()) {
            var result=BlueprintService.completeAfterFootingCleanup(level,data,settlement,site);
            if(result.success())clear(data,citizen,entity);
            else setActivity(data,citizen,entity,CitizenActivity.BLOCKED,result.reason());
            return true;
        }
        var selected=com.hkin.exiledsettlement.terrain.TerrainService.at(level,site,citizen.targetPosition());
        if(selected==null){clear(data,citizen,entity);return true;}
        int stepIndex=-selected.index()-1;var step=plan.steps.get(stepIndex);BlockPos p=step.position();
        var fillTarget=com.hkin.exiledsettlement.terrain.BuriedFillMaterials.choose(level,settlement,plan,step,citizen);
        var fillStep=new com.hkin.exiledsettlement.terrain.TerrainProject.Step(p,fillTarget,step.original());
        var workFace=p.equals(entity.navigationFeetPosition())?null:com.hkin.exiledsettlement.terrain.TerrainService.fillSurfaceWorkFace(level,plan,step);
        var workTarget=workFace==null?p:workFace;
        boolean drySurface=workFace!=null && com.hkin.exiledsettlement.terrain.TerrainService.groundFillSurface(level,plan,step)!=null;
        if(drySurface && (citizen.profession()!=CitizenProfession.WORKER || level.getEntities().get(entity.getUUID())!=entity
                || !entity.onGround() || entity.isInWater() || !localWorkRange(entity,workTarget)
                || Math.abs(entity.getY()-workTarget.getY())>2
                || !stableFarmFooting(level,entity.navigationFeetPosition())
                || !com.hkin.exiledsettlement.blueprint.FarmEarthworkPlan.support(level,entity.navigationFeetPosition().below()))) {
            SITE_STATIONS.remove(entity);SITE_DIAGNOSTICS.put(entity,"dry fill requires actual stable surface footing "+workTarget);return true;
        }
        if(!terrainWorkInRange(site,entity,workTarget)) {
            SITE_STATIONS.remove(entity);SITE_DIAGNOSTICS.put(entity,"terrain target outside actual reach "+workTarget);return true;
        }
        SITE_DIAGNOSTICS.put(entity,"terrain execute "+p);

        String failure=com.hkin.exiledsettlement.terrain.TerrainService.guard(level,settlement,site,step); var state=level.getBlockState(p);
        if(com.hkin.exiledsettlement.terrain.TerrainService.irrigationRetirement(settlement,site,step)
                && !state.getFluidState().isEmpty() && state.getBlock() instanceof net.minecraft.world.level.block.BucketPickup pickup) {
            if(!failure.isEmpty()){setActivity(data,citizen,entity,CitizenActivity.BLOCKED,failure);return true;}
            var holder=siteMaterialHolder(level, settlement,citizen,Items.BUCKET);
            if(holder==null){requestSiteMaterial(level,data,settlement,site,citizen,entity,Items.BUCKET);return true;}
            if(citizen.inventoryCapacityFor(new ItemStack(Items.WATER_BUCKET))<1){clear(data,citizen,entity);assignPersonalDelivery(level,settlement,citizen);return true;}
            if(!net.neoforged.neoforge.common.CommonHooks.canEntityDestroy(level,p,entity)){setActivity(data,citizen,entity,CitizenActivity.BLOCKED,"PROTECTED_AREA");return true;}
            var snapshot=net.neoforged.neoforge.common.util.BlockSnapshot.create(level.dimension(),level,p,Block.UPDATE_CLIENTS);
            var collected=pickup.pickupBlock(null,level,p,state);
            if(collected.isEmpty() || net.neoforged.neoforge.event.EventHooks.onBlockPlace(entity,snapshot,Direction.UP)) {
                snapshot.restore(Block.UPDATE_CLIENTS);setActivity(data,citizen,entity,CitizenActivity.BLOCKED,"PROTECTED_AREA");return true;
            }
            holder.extractInventoryItem(stack->stack.is(Items.BUCKET),1);citizen.insertIntoInventory(collected);
            com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,citizen,"farm_irrigation_recover",p,"minecraft:water_bucket",1);
            SITE_SUCCESS_TICKS.put(entity,now);data.touch();return true;
        }
        boolean directFoundation=FoundationPlacement.applies(plan,step);
        boolean directReplacement=FoundationPlacement.replaces(level,plan,step);
        boolean preparation=step.fill() && !directReplacement && com.hkin.exiledsettlement.terrain.TerrainService.soft(state);
        boolean filling=step.fill() && !preparation;
        if(!level.hasChunkAt(p) || level.isOutsideBuildHeight(p)) failure="BLUEPRINT_AREA_NOT_LOADED";
        
        else if(!state.getFluidState().isEmpty() && !com.hkin.exiledsettlement.terrain.TerrainService.canFillWater(plan,step,state)
                && !com.hkin.exiledsettlement.terrain.TerrainService.canClearWetNatural(step,state)) failure="FARM_EARTHWORK_FLUID";
        else if(filling && (!directReplacement && !state.isAir() && !com.hkin.exiledsettlement.terrain.TerrainService.canFillWater(plan,step,state) || !com.hkin.exiledsettlement.road.BridgeService.supported(level,plan,step))) failure="FARM_EARTHWORK_NO_SUPPORT";
        else if(!filling && state.getDestroySpeed(level,p)<0) failure="BLUEPRINT_BLOCKED";
        if(!failure.isEmpty()) {
            setActivity(data,citizen,entity,CitizenActivity.BLOCKED,
                    com.hkin.exiledsettlement.terrain.TerrainService.detail(level,p,failure)); return true;
        }
        BlockPos occupied=filling?p:p.above();
        var space=new net.minecraft.world.phys.AABB(occupied);
        if((filling || !state.getCollisionShape(level,p).isEmpty())
                && FoundationPlacement.occupied(level,occupied,filling && directFoundation)) {
            if(entity.getBoundingBox().intersects(space)) {
                BlockPos destination=terrainEscape(level,entity,occupied);
                if(destination!=null && !new net.minecraft.world.phys.AABB(destination).intersects(space)) {
                    FARM_ESCAPES.put(entity,new FarmEscape(site.id(),occupied,destination));
                    move(data,citizen,entity,destination,CitizenActivity.BUILDING);return true;
                }
                if(filling && entity.isInWater() && requestFarmWaterExit(level,settlement,site,citizen,occupied)) {
                    setActivity(data,citizen,entity,CitizenActivity.BLOCKED,"正在等待居民挖出水坑土阶出口");return true;
                }
            } else if(filling) {
                for(var other:settlement.citizens()) {
                    var trapped=CitizenProjectionService.findProjection(level,other);
                    if(trapped==null || !trapped.isInWater() || !trapped.getBoundingBox().intersects(space) || !other.canWork()
                            || other.workState()!=CitizenWorkState.IDLE && !terminalNavigationFailure(other.workFailure()) || trapped.isDefending() || trapped.isSleeping() || CitizenInteractionService.held(trapped))continue;
                    if(requestFarmWaterExit(level,settlement,site,other,occupied)) {
                        entity.deferConstructionTarget(p,CONSTRUCTION_TARGET_RETRY_DELAY_TICKS);clear(data,citizen,entity);
                        data.touch();return true;
                    }
                }
            }
            setActivity(data,citizen,entity,CitizenActivity.BLOCKED,
                    com.hkin.exiledsettlement.terrain.TerrainService.detail(level,p,"FARM_EARTHWORK_OCCUPIED"));
            entity.deferConstructionTarget(p,CONSTRUCTION_TARGET_RETRY_DELAY_TICKS);clear(data,citizen,entity);return true;
        }
        if(!citizen.hasInventoryCapacity() && !filling) {
            clear(data,citizen,entity);assignPersonalDelivery(level,settlement,citizen);data.touch();return true;
        }
        boolean replacingSolid=directReplacement && !state.isAir();
        var toolTag=filling && !replacingSolid || state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL) ? net.minecraft.tags.ItemTags.SHOVELS
                : state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE) ? net.minecraft.tags.ItemTags.PICKAXES
                : state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE) ? net.minecraft.tags.ItemTags.AXES : null;
        int toolSlot=toolTag==null ? -1 : citizen.findInventorySlot(stack -> stack.is(toolTag)
                && (!state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state)));
        if(toolTag!=null && toolSlot<0) {
            Item required=toolTag==net.minecraft.tags.ItemTags.SHOVELS ? Items.IRON_SHOVEL
                    : toolTag==net.minecraft.tags.ItemTags.AXES ? Items.IRON_AXE : Items.DIAMOND_PICKAXE;
            requestConstructionTool(level,data,settlement,citizen,entity,required,filling && !replacingSolid?null:state);return true;
        }
        ItemStack tool=toolSlot<0 ? ItemStack.EMPTY : citizen.inventoryItem(toolSlot);
        if(!filling && !clearanceActionReady(level,citizen,p,state,tool)) return true;
        if(toolSlot>=0) citizen.selectActiveSlot(toolSlot);
        if(filling) {
            if(FoundationPlacement.occupied(level,p,directFoundation)) failure="FARM_EARTHWORK_OCCUPIED";
            else {
                CitizenRecord holder=siteMaterialHolder(level, settlement,citizen,fillTarget.getBlock().asItem());
                if(holder==null) {
                    requestSiteMaterial(level,data,settlement,site,citizen,entity,fillTarget.getBlock().asItem());
                    if(citizen.workState()==CitizenWorkState.TO_BLUEPRINT) setActivity(data,citizen,entity,CitizenActivity.BLOCKED,"MATERIALS_MISSING");
                    return true;
                }
                var wetDrops=replacingSolid || com.hkin.exiledsettlement.terrain.TerrainService.replaceableWater(state)
                        ? Block.getDrops(state,level,p,level.getBlockEntity(p),entity,tool):java.util.List.<ItemStack>of();
                if(!state.isAir() && !state.is(Blocks.WATER) && !net.neoforged.neoforge.common.CommonHooks.canEntityDestroy(level,p,entity)) {
                    setActivity(data,citizen,entity,CitizenActivity.BLOCKED,"PROTECTED_AREA");return true;
                }
                var snapshot=net.neoforged.neoforge.common.util.BlockSnapshot.create(level.dimension(),level,p,Block.UPDATE_CLIENTS);
                if(!level.setBlock(p,com.hkin.exiledsettlement.terrain.TerrainService.resolve(level,fillStep),Block.UPDATE_ALL)
                        || net.neoforged.neoforge.event.EventHooks.onBlockPlace(entity,snapshot,Direction.UP)
                        || !com.hkin.exiledsettlement.terrain.TerrainService.satisfied(level.getBlockState(p),fillTarget)) {
                    snapshot.restore(Block.UPDATE_CLIENTS);failure="PROTECTED_AREA";
                } else {
                    holder.extractInventoryItem(s -> s.is(fillTarget.getBlock().asItem()),1);
                    plan.recordFill(stepIndex,level.getBlockState(p));settlement.terrain().index(plan);
                    if(directFoundation)Block.pushEntitiesUp(state,level.getBlockState(p),level,p);
                    if(com.hkin.exiledsettlement.terrain.TerrainService.canFillWater(plan,step,state))
                        com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,citizen,"water_fill",p,fillTarget.getBlock().asItem().toString(),1);
                    else if(drySurface)com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,citizen,"surface_void_fill",p,fillTarget.getBlock().asItem().toString(),1);
                    else com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,citizen,plan.bridge
                            ?plan.bridgeAnchors.contains(p)?"bridge_abutment":plan.bridgeBeams.contains(p)?"bridge_beam":plan.bridgeDeck.contains(p)?"bridge_deck":"bridge_rail"
                            :"terrain_fill",p,fillTarget.getBlock().asItem().toString(),1);
                    for(var drop:wetDrops){var extra=citizen.insertIntoInventory(drop);if(!extra.isEmpty())Block.popResource(level,p,extra);}
                }
            }
        } else {
            failure=destroyTerrainObstacle(level,settlement,site,citizen,entity,p,state,tool);
            if(failure.isEmpty())com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,citizen,"terrain_dig",p,state.getBlock().asItem().toString(),1);
        }
        if(!failure.isEmpty()) {
            setActivity(data,citizen,entity,CitizenActivity.BLOCKED,
                    com.hkin.exiledsettlement.terrain.TerrainService.detail(level,p,failure));return true;
        }
        if(!preparation)plan.mark(stepIndex);site.removeClearanceTarget(p);
        var roadSpace=com.hkin.exiledsettlement.road.RoadSpaceData.get(level);
        if(!filling && roadSpace.contains(p) && !roadSpace.manual(p))roadSpace.remove(p);
        if(filling && plan.road && !plan.roadReclaim && step.target().getBlock() instanceof net.minecraft.world.level.block.StairBlock){
            for(var route:settlement.autoRoads().routes.values())if(!route.suppressed && route.cells.contains(p) && com.hkin.exiledsettlement.road.RoadEarthworkService.surfaceReady(level,settlement,route,p)){roadSpace.addAll(List.of(p));route.mark(p);}
        }
        if(toolSlot>=0) citizen.damageInventoryItem(toolSlot,1);
        site.constructionController().recordGroundAction(now,filling?"TERRAIN_FILL":"TERRAIN_DIG",p);
        entity.swing(net.minecraft.world.InteractionHand.MAIN_HAND);SITE_SUCCESS_TICKS.put(entity,now);
        syncHeldItem(citizen,entity);
        var claimed=new HashSet<BlockPos>();for(var other:settlement.citizens())if(other!=citizen && other.targetPosition()!=null)claimed.add(other.targetPosition());
        var next=com.hkin.exiledsettlement.terrain.TerrainService.pending(level,site,claimed).stream()
                .filter(candidate->{
                    var face=com.hkin.exiledsettlement.terrain.TerrainService.fillSurfaceWorkFace(level,plan,plan.steps.get(-candidate.index()-1));
                    var work=face==null?candidate.placement().position():face;
                    return (plan.playerBridge!=null || localWorkRange(entity,work)) && terrainWorkInRange(site,entity,work);
                })
                .min(Comparator.comparingDouble(candidate->{
                    var face=com.hkin.exiledsettlement.terrain.TerrainService.fillSurfaceWorkFace(level,plan,plan.steps.get(-candidate.index()-1));
                    return (face==null?candidate.placement().position():face).distSqr(entity.blockPosition());
                })).orElse(null);
        if(next!=null)citizen.updateTargetPosition(next.placement().position());
        data.touch();return true;
    }

    /** A paid one-block bank cut lets a trapped resident walk out before filling. */
    private static boolean requestFarmWaterExit(ServerLevel level,SettlementRecord settlement,BlueprintRecord site,CitizenRecord trapped,BlockPos hole) {
        for(var order:settlement.terrain().projects.values())if(!order.cancelled && hole.equals(order.escapeFor) && trapped.id().equals(order.escapingCitizen))return true;
        for(var dependency:site.terrainProject().dependencies) {
            var field=settlement.building(dependency);
            if(field==null || field.region()==null || !field.region().agricultural() || field.region().cell(hole)==null)continue;
            for(var direction:new Direction[]{Direction.WEST,Direction.EAST,Direction.NORTH,Direction.SOUTH}) {
                var bank=hole.relative(direction).above();var cell=field.region().cell(bank);
                if(cell==null || !cell.ground().equals(bank) || cell.use()!=com.hkin.exiledsettlement.region.ProductionRegion.Use.CROP
                        || !level.hasChunkAt(bank) || !com.hkin.exiledsettlement.blueprint.FarmEarthworkPlan.soil(level.getBlockState(bank))
                        || !com.hkin.exiledsettlement.blueprint.FarmEarthworkPlan.support(level,bank.below()))continue;
                var above=level.getBlockState(bank.above());
                if(!above.isAir() && !(above.getBlock() instanceof net.minecraft.world.level.block.BushBlock))continue;
                var steps=new java.util.ArrayList<com.hkin.exiledsettlement.terrain.TerrainProject.Step>();
                if(!above.isAir())steps.add(new com.hkin.exiledsettlement.terrain.TerrainProject.Step(bank.above(),Blocks.AIR.defaultBlockState(),above));
                steps.add(new com.hkin.exiledsettlement.terrain.TerrainProject.Step(bank,Blocks.AIR.defaultBlockState(),level.getBlockState(bank)));
                var exit=com.hkin.exiledsettlement.region.ProductionRegionService.publish(level,settlement,field.id(),"施工水坑临时土阶出口",bank,steps);
                if(exit==null)continue;
                exit.dependencies.add(site.id());exit.escapeFor=hole.immutable();exit.escapingCitizen=trapped.id();
                SettlementSavedData.get(level).touch();return true;
            }
        }
        return false;
    }

    private static String destroyTerrainObstacle(ServerLevel level,SettlementRecord settlement,BlueprintRecord site,
            CitizenRecord citizen,SettlementCitizenEntity entity,BlockPos p,BlockState state,ItemStack tool) {
        var cleared=com.hkin.exiledsettlement.blueprint.SiteClearance.clear(level,p,entity,tool,part -> {
            // The authorised obstruction is one tree; its branches may overhang the engineering footprint.
            // ResidentTreeFelling still checks territory, real buildings and production trees for every part.
            if(!part.equals(p) && ResidentTreeFelling.vegetation(level.getBlockState(part))
                    && site.terrainProject().indices(part).isEmpty())return true;
            var original=level.getBlockState(part);
            return com.hkin.exiledsettlement.terrain.TerrainService.guard(level,settlement,site,
                    new com.hkin.exiledsettlement.terrain.TerrainProject.Step(part,Blocks.AIR.defaultBlockState(),original)).isEmpty();
        },com.hkin.exiledsettlement.terrain.TerrainService::clearableWetNatural);
        if(!cleared.failure().isEmpty())return cleared.failure();
        for(var removed:cleared.removed())for(var parent:settlement.blueprints())
            if(parent.hasTemporaryFooting(removed))parent.removeTemporaryFooting(removed);
        for(var drop:cleared.drops()){var extra=citizen.insertIntoInventory(drop);if(!extra.isEmpty())Block.popResource(level,p,extra);}
        return "";
    }

    private static boolean advanceFarmEarthwork(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord site, CitizenRecord citizen, SettlementCitizenEntity entity, long now) {
        var survey=com.hkin.exiledsettlement.blueprint.FarmEarthworkService.ensure(level,site);
        if(!survey.valid()) {
            setActivity(data,citizen,entity,CitizenActivity.BLOCKED,
                    com.hkin.exiledsettlement.blueprint.FarmEarthworkService.detail(level,survey.position(),survey.reason())); return true;
        }
        // Skip already satisfied frontier steps with the shared bounded scheduler.
        var work=com.hkin.exiledsettlement.blueprint.FarmEarthworkService.pending(level,site,Set.of());
        var plan=site.earthworkPlan(); if(plan.done()) return false;
        var step=plan.next(); BlockPos p=step.position(); citizen.updateTargetPosition(p);
        String failure=""; var state=level.getBlockState(p);
        if(!level.hasChunkAt(p) || level.isOutsideBuildHeight(p)) failure="BLUEPRINT_AREA_NOT_LOADED";
        else if(!com.hkin.exiledsettlement.blueprint.FarmEarthworkService.allowed(settlement,site,BlueprintTemplates.create(site),p)) failure="FARM_EARTHWORK_CONFLICT";
        else if(!state.getFluidState().isEmpty()) failure="FARM_EARTHWORK_FLUID";
        else if(step.fill() && (!state.isAir() || !com.hkin.exiledsettlement.blueprint.FarmEarthworkPlan.support(level,p.below()))) failure="FARM_EARTHWORK_NO_SUPPORT";
        else if(!step.fill() && state.getDestroySpeed(level,p)<0) failure="BLUEPRINT_BLOCKED";
        if(!failure.isEmpty()) {
            setActivity(data,citizen,entity,CitizenActivity.BLOCKED,
                    com.hkin.exiledsettlement.blueprint.FarmEarthworkService.detail(level,p,failure)); return true;
        }
        BlockPos occupied=step.fill()?p:p.above();
        var space=new net.minecraft.world.phys.AABB(occupied);
        if(!level.getEntities((Entity)null,space,e -> e.isAlive() && !e.isSpectator() && e instanceof LivingEntity).isEmpty()) {
            if(entity.getBoundingBox().intersects(space)) {
                BlockPos destination=terrainEscape(level,entity,occupied);
                if(destination!=null && !new net.minecraft.world.phys.AABB(destination).intersects(space)) {
                    FARM_ESCAPES.put(entity,new FarmEscape(site.id(),occupied,destination));
                    move(data,citizen,entity,destination,CitizenActivity.BUILDING);return true;
                }
            }
            setActivity(data,citizen,entity,CitizenActivity.BLOCKED,
                    com.hkin.exiledsettlement.blueprint.FarmEarthworkService.detail(level,p,"FARM_EARTHWORK_OCCUPIED"));return true;
        }
        if(!citizen.hasInventoryCapacity() && !step.fill()) {
            clear(data,citizen,entity);assignPersonalDelivery(level,settlement,citizen);data.touch();return true;
        }
        var toolTag=step.fill() || state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL) ? net.minecraft.tags.ItemTags.SHOVELS
                : state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE) ? net.minecraft.tags.ItemTags.PICKAXES
                : state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE) ? net.minecraft.tags.ItemTags.AXES : null;
        int toolSlot=toolTag==null ? -1 : citizen.findInventorySlot(stack -> stack.is(toolTag)
                && (!state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state)));
        if(toolTag!=null && toolSlot<0) {
            Item required=toolTag==net.minecraft.tags.ItemTags.SHOVELS ? Items.IRON_SHOVEL
                    : toolTag==net.minecraft.tags.ItemTags.AXES ? Items.IRON_AXE : Items.DIAMOND_PICKAXE;
            requestConstructionTool(level,data,settlement,citizen,entity,required,step.fill()?null:state);return true;
        }
        ItemStack tool=toolSlot<0 ? ItemStack.EMPTY : citizen.inventoryItem(toolSlot);
        if(!step.fill() && !clearanceActionReady(level,citizen,p,state,tool)) return true;
        if(toolSlot>=0) citizen.selectActiveSlot(toolSlot);
        if(step.fill()) {
            if(!level.getEntities((Entity)null,new net.minecraft.world.phys.AABB(p),e -> e.isAlive() && !e.isSpectator()
                    && e instanceof net.minecraft.world.entity.LivingEntity).isEmpty()) failure="FARM_EARTHWORK_OCCUPIED";
            else {
                CitizenRecord holder=siteMaterialHolder(level, settlement,citizen,Items.DIRT);
                if(holder==null) {
                    requestSiteMaterial(level,data,settlement,site,citizen,entity,Items.DIRT);
                    if(citizen.workState()==CitizenWorkState.TO_BLUEPRINT) setActivity(data,citizen,entity,CitizenActivity.BLOCKED,"FARM_EARTHWORK_DIRT_MISSING");
                    return true;
                }
                var snapshot=net.neoforged.neoforge.common.util.BlockSnapshot.create(level.dimension(),level,p,Block.UPDATE_CLIENTS);
                if(!level.setBlock(p,Blocks.DIRT.defaultBlockState(),Block.UPDATE_ALL)
                        || net.neoforged.neoforge.event.EventHooks.onBlockPlace(entity,snapshot,Direction.UP)
                        || !level.getBlockState(p).is(Blocks.DIRT)) {
                    snapshot.restore(Block.UPDATE_CLIENTS);failure="PROTECTED_AREA";
                } else holder.extractInventoryItem(s -> s.is(Items.DIRT),1);
            }
        } else {
            failure=destroyFarmObstacle(level,settlement,site,citizen,entity,p,state,tool);
        }
        if(!failure.isEmpty()) {
            setActivity(data,citizen,entity,CitizenActivity.BLOCKED,
                    com.hkin.exiledsettlement.blueprint.FarmEarthworkService.detail(level,p,failure));return true;
        }
        plan.advance();site.removeClearanceTarget(p);
        if(toolSlot>=0) citizen.damageInventoryItem(toolSlot,1);
        site.constructionController().recordGroundAction(now,step.fill()?"FARM_FILL":"FARM_DIG",p);
        entity.swing(net.minecraft.world.InteractionHand.MAIN_HAND);SITE_SUCCESS_TICKS.put(entity,now);
        syncHeldItem(citizen,entity);data.touch();return true;
    }

    private static String destroyFarmObstacle(ServerLevel level, SettlementRecord settlement, BlueprintRecord site,
            CitizenRecord citizen, SettlementCitizenEntity entity, BlockPos p, BlockState state, ItemStack tool) {
        var tree=ResidentTreeFelling.clear(level,p,entity,tool,part->part.equals(p)
                ?com.hkin.exiledsettlement.blueprint.FarmEarthworkService.allowed(settlement,site,BlueprintTemplates.create(site),part)
                :true,state.getFluidState().createLegacyBlock());
        if(tree!=null) {
            if(!tree.failure().isEmpty())return tree.failure();
            for(var drop:tree.drops()){var extra=citizen.insertIntoInventory(drop);if(!extra.isEmpty())Block.popResource(level,p,extra);}
            return "";
        }
        if(state.getBlock() instanceof net.minecraft.world.level.block.DoublePlantBlock
                && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            boolean lower=state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)==net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER;
            BlockPos partner=lower?p.above():p.below(); var paired=level.getBlockState(partner);
            if(paired.getBlock()==state.getBlock()) {
                var plan=site.earthworkPlan();
                if(partner.getY()<plan.soilY()-plan.fillDepth()+1 || partner.getY()>plan.soilY()+plan.digHeight()
                        || !com.hkin.exiledsettlement.blueprint.FarmEarthworkService.allowed(settlement,site,BlueprintTemplates.create(site),partner)) return "FARM_EARTHWORK_CONFLICT";
                if(!net.neoforged.neoforge.common.CommonHooks.canEntityDestroy(level,p,entity)
                        || !net.neoforged.neoforge.common.CommonHooks.canEntityDestroy(level,partner,entity)) return "PROTECTED_AREA";
                if(!level.getBlockState(p).equals(state) || !level.getBlockState(partner).equals(paired)) return "CONSTRUCTION_TARGET_CHANGED";
                int flags=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE;
                var a=net.neoforged.neoforge.common.util.BlockSnapshot.create(level.dimension(),level,p,flags);
                var b=net.neoforged.neoforge.common.util.BlockSnapshot.create(level.dimension(),level,partner,flags);
                BlockPos loot=lower?p:partner;
                var drops=Block.getDrops(level.getBlockState(loot),level,loot,null,entity,tool);
                if(!level.setBlock(p,Blocks.AIR.defaultBlockState(),flags)||!level.setBlock(partner,Blocks.AIR.defaultBlockState(),flags)) {
                    b.restore(flags);a.restore(flags);return "CONSTRUCTION_TARGET_CHANGED";
                }
                level.updateNeighborsAt(p,state.getBlock());level.updateNeighborsAt(partner,paired.getBlock());
                for(var drop:drops) {var excess=citizen.insertIntoInventory(drop);if(!excess.isEmpty()) Block.popResource(level,p,excess);}
                return "";
            }
        }
        if(!net.neoforged.neoforge.common.CommonHooks.canEntityDestroy(level,p,entity)) return "PROTECTED_AREA";
        if(!level.getBlockState(p).equals(state)) return "CONSTRUCTION_TARGET_CHANGED";
        var drops=Block.getDrops(state,level,p,level.getBlockEntity(p),entity,tool);
        if(!level.destroyBlock(p,false,entity)) return "PROTECTED_AREA";
        for(var drop:drops) {var excess=citizen.insertIntoInventory(drop);if(!excess.isEmpty()) Block.popResource(level,p,excess);}
        return "";
    }

    private static boolean advanceOptionalGroundScaffold(ServerLevel level, SettlementSavedData data,
            SettlementRecord settlement, BlueprintRecord site, CitizenRecord citizen,
            SettlementCitizenEntity entity, long now) {
        if(ConstructionSiteController.groundWork(site))return false;
        var template = BlueprintTemplates.create(site);
        var controller = site.constructionController();
        BlockPos cell = null;
        for (BlockPos p : controller.frame(level, site)) {
            if (!controller.accessReady(p, now) || !level.hasChunkAt(p)
                    || level.getBlockState(p).is(Blocks.SCAFFOLDING)) continue;
            if (site.hasTemporaryFooting(p)) { site.removeTemporaryFooting(p); data.touch(); }
            if (!ScaffoldSitePreparation.space(level, p) || !ScaffoldSitePreparation.headroom(level, p.above())
                    || !footingAllowedAt(level, settlement, site, template, p) || !scaffoldSupported(level, p)
                    || !ScaffoldSitePreparation.allowed(level, settlement, site, p)
                    || ScaffoldSitePreparation.softObstacle(level.getBlockState(p.above()))
                    && !ScaffoldSitePreparation.allowed(level, settlement, site, p.above())) continue;
            cell = p; break;
        }
        if (cell == null) return false; // Occupied or unsupported optional cells are not unfinished structure.
        CitizenRecord holder = siteMaterialHolder(level, settlement, citizen, Items.SCAFFOLDING);
        if (holder == null && WorkerScaffoldCrafting.craft(citizen, 6) > 0) {
            holder = citizen; data.touch();
        }
        if (holder == null) return false;
        var preparation = ScaffoldSitePreparation.prepare(level, data, settlement, site, citizen, entity, cell);
        if (!preparation.failure().isEmpty()) {
            controller.postponeAccess(cell, now, preparation.failure());
            entity.traceConstructionDecision("optional-access-skipped:" + preparation.failure());
            return false;
        }
        if (preparation.changed()) {
            controller.recordGroundAction(now, "GROUND_CLEAR_ACCESS", cell);
            return true;
        }
        if (holder != citizen) {
            ItemStack unit = holder.extractInventoryItem(s -> s.is(Items.SCAFFOLDING), 1);
            ItemStack remainder = citizen.insertIntoInventory(unit);
            if (!remainder.isEmpty()) { holder.insertIntoInventory(remainder); return false; }
        }
        BlockPos parent = site.hasTemporaryFooting(cell.below()) ? cell.below() : null;
        if (parent == null) for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (site.hasTemporaryFooting(cell.relative(direction))) { parent = cell.relative(direction); break; }
        }
        if (!placeAndRegisterTemporaryFooting(level, data, settlement, site, citizen, entity, cell,
                Blocks.SCAFFOLDING.defaultBlockState(), Items.SCAFFOLDING, parent)) {
            String failure = ScaffoldSitePreparation.failure(level, cell, "PROTECTED_AREA");
            controller.postponeAccess(cell, now, failure);
            entity.traceConstructionDecision("optional-access-skipped:" + failure);
            return false;
        }
        controller.recordGroundAction(now, "GROUND_SCAFFOLD", cell);
        return true;
    }

    /** Historical physical per-block builder retained, no longer the default. */
    private static void buildWithinPlanningSlice(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        BlueprintRecord blueprint = settlement.blueprint(citizen.targetBlueprintId());
        if (blueprint == null || blueprint.cancellationQueued()) {
            if (blueprint != null) blueprint.setScaffoldWorker(null);
            clear(data, citizen, entity);
            return;
        }
        if (!builderWithinCap(settlement, citizen, blueprint.id())) {
            clear(data, citizen, entity);
            if (assignPersonalDelivery(level, settlement, citizen)) syncHeldItem(citizen, entity);
            data.touch();
            return;
        }
        BlockPos assignedPosition = citizen.targetPosition();
        BlockPos currentFeet = constructionFeetPosition(entity);
        boolean onAnotherSite = settlement.blueprints().stream().anyMatch(site -> !site.id().equals(blueprint.id())
                && occupiedTemporarySupport(level, site, currentFeet) != null);
        if (onAnotherSite && leaveScaffoldingBeforeLogistics(level, data, settlement, citizen, entity)) return;
        BlueprintService.PendingPlacement pending = assignedPosition == null
                ? null : BlueprintService.pendingAt(level, blueprint, assignedPosition);
        if (pending == null) {
            if (continueBlueprintClosure(level, data, settlement, blueprint, citizen, entity)) return;
            clear(data, citizen, entity);
            return;
        }
        BlockPos target = pending.placement().position();
        BlockPos frameTarget = blueprint.constructionController().activeFrameTarget(level, entity);
        if (frameTarget != null && !blueprint.hasClearanceTarget(target)) {
            advanceFrameCell(level, data, settlement, blueprint, citizen, entity, BlueprintTemplates.create(blueprint), frameTarget);
            return;
        }
        if (!blueprint.hasClearanceTarget(target) && !canConstructFromCurrentPosition(entity, target)
                && !entity.hasActiveConstructionMovement() && entity.getNavigation().isDone()) {
            blueprint.constructionController().movement(entity).approach(level, blueprint, entity, target, WALK_SPEED);
            if (!entity.hasActiveConstructionMovement() && entity.getNavigation().isDone()
                    && advanceExteriorScaffoldFrame(level, data, settlement, blueprint, citizen, entity, target)) return;
        }
        if (!blueprint.hasClearanceTarget(target)) {
            // The site controller owns ordinary build movement once its access frame is ready.
            // The old access planner remains available for clearance/repair/cleanup only.
            if (!entity.hasActiveConstructionMovement()) {
                var dependency = com.hkin.exiledsettlement.blueprint.ConstructionDependencies.resolve(
                        level, BlueprintTemplates.create(blueprint), pending.placement());
                if (dependency.cycle()) {
                    setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "CONSTRUCTION_DEPENDENCY_CYCLE");
                    entity.traceConstructionDecision("dependency-cycle:" + target);
                    postponeSiteTarget(level, data, settlement, blueprint, citizen, entity);
                    return;
                }
                BlockPos prerequisite = dependency.target();
                if (prerequisite != null) {
                    citizen.updateTargetPosition(prerequisite);
                    setActivity(data, citizen, entity, CitizenActivity.BUILDING, "CONSTRUCTION_PREREQUISITE_PENDING");
                    data.touch();
                    return;
                }
            }
            if (!blueprint.constructionController().movement(entity).approach(level, blueprint, entity, target, WALK_SPEED)) {
                var movement = blueprint.constructionController().movement(entity);
                setActivity(data, citizen, entity, CitizenActivity.BUILDING,
                        movement.isRecovering() ? "CONSTRUCTION_SITE_RECOVERING"
                                : !entity.hasActiveConstructionMovement() && entity.getNavigation().isDone()
                                ? "CONSTRUCTION_SITE_NO_ROUTE" : "CONSTRUCTION_SITE_ROUTE_PENDING");
                // Reaching the ground is a handoff to ordinary navigation,
                // not failure of the original work item. Preserve its claim.
                if (movement.consumeRecoveryCompleted()) return;
                if (!entity.hasActiveConstructionMovement()
                        && blueprint.constructionController().movement(entity).unavailableFor(level.getGameTime(), 200)) {
                    postponeSiteTarget(level, data, settlement, blueprint, citizen, entity);
                }
                return;
            }
        }
        if (blueprint.hasClearanceTarget(target) && !prepareConstructionReach(level, data, settlement, blueprint, citizen, entity,
                target, CitizenActivity.BUILDING)) {
            if (constructionAccessFailure(citizen.workFailure())) {
                deferBlockedConstructionTarget(level, data, settlement, blueprint, citizen, entity, target);
            }
            return;
        }
        if (blueprint.hasClearanceTarget(target)) {
            if (!citizen.hasInventoryCapacity()) {
                clear(data, citizen, entity);
                if (assignPersonalDelivery(level, settlement, citizen)) syncHeldItem(citizen, entity);
                data.touch();
                return;
            }
            BlockState clearanceState = level.getBlockState(target);
            int clearanceToolSlot = bestClearanceToolSlot(citizen, clearanceState);
            ItemStack clearanceTool = clearanceToolSlot >= 0
                    ? citizen.inventoryItem(clearanceToolSlot) : ItemStack.EMPTY;
            if (!clearanceActionReady(level, citizen, target, clearanceState, clearanceTool)) {
                setActivity(data, citizen, entity, CitizenActivity.BUILDING, "");
                return;
            }
            if (clearanceToolSlot >= 0) citizen.selectActiveSlot(clearanceToolSlot);
            syncHeldItem(citizen, entity);
            var clearance = BlueprintService.clearAt(
                    level, data, settlement, blueprint, target, entity, clearanceTool);
            if (!clearance.success()) {
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, clearance.reason());
                return;
            }
            for (ItemStack drop : clearance.value()) {
                ItemStack leftover = citizen.insertIntoInventory(drop);
                if (!leftover.isEmpty()) Block.popResource(level, target, leftover);
            }
            if (clearanceToolSlot >= 0) citizen.damageInventoryItem(clearanceToolSlot, 1);
            continueConstructionAfterAction(level, data, settlement, blueprint, citizen, entity,
                    pending.placement(), true);
            return;
        }
        if (pending.placement().material() != null
                && countInventoryItem(citizen, pending.placement().material()) == 0
                && shareSiteMaterial(level, settlement, citizen, pending.placement().material(), 64) > 0) data.touch();
        if (pending.placement().material() != null
                && !selectInventoryItem(citizen, pending.placement().material())) {
            BuildingRecord warehouse = WarehouseConstructionSupply.warehouse(
                    level, settlement, entity.blockPosition(), pending.placement().material());
            if (warehouse == null && DecorationService.owned(settlement, blueprint.id()))
                warehouse = DecorationCrafting.warehouse(level, settlement, citizen, pending.placement().material());
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(blueprint.ownerId());
            if (warehouse == null && owner == null && !settlement.creativeCoreInstalled()) {
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "MATERIALS_MISSING");
            } else {
                citizen.fetchConstructionMaterial(warehouse == null ? null : warehouse.id());
                data.touch();
            }
            return;
        }
        BuildingBlueprintTemplate activeTemplate = BlueprintTemplates.create(blueprint);
        if (requiresGroundEgressProtection(activeTemplate, pending.placement())
                && !ConstructionAccessPlanService.preservesResidentEgress(
                level, blueprint, pending.placement(), entity)) {
            // Foundation and first-floor cells can behave like walls at the
            // resident's current feet level. Skip the particular closing cell
            // and keep another frontier available; elevated construction uses
            // the exterior scaffold frame and bounded drop instead of this
            // deliberately narrow protection.
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED,
                    "EXIT_ROUTE_BLOCKED");
            postponeSiteTarget(level, data, settlement, blueprint, citizen, entity);
            return;
        }
        // Do not require a complete route to ordinary ground before every
        // single placement. That conservative proof rejected valid roof and
        // upper-floor frontier cells, so workers repeatedly dismantled a usable
        // scaffold tower instead of finishing the surface beneath them. The
        // selected station is already live-reachable, every local move remains
        // collision checked, and failed access has both reversible scaffolding
        // descent and the bounded roof-edge drop recovery. Egress is therefore
        // recovered when it is actually needed, not used as a hard ordering
        // constraint on unfamiliar templates.
        ItemStack supplied = citizen.carriedItem();
        var result = BlueprintService.placeAt(level, data, settlement, blueprint, target, supplied, entity);
        citizen.updateCarriedItem(supplied);
        entity.setItemSlot(EquipmentSlot.MAINHAND, supplied);
        if (!result.success()) {
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, result.reason());
            if(ConstructionOccupancy.FAILURE.equals(result.reason())) {
                yieldOccupiedStructure(level,data,settlement,blueprint,citizen,entity);return;
            }
            if ("CONSTRUCTION_WOULD_SEAL_GAP".equals(result.reason())
                    || "CONSTRUCTION_PREREQUISITE_PENDING".equals(result.reason())) {
                BlockPos prerequisite = blueprint.constructionController().closurePrerequisite(
                        level, blueprint, pending.placement());
                if (prerequisite != null) {
                    citizen.updateTargetPosition(prerequisite);
                    entity.finishConstructionMovement();
                    data.touch();
                }
            }
            if (BlueprintService.waitsForChestPartner(level, pending) || "BLUEPRINT_UNSUPPORTED".equals(result.reason())) {
                postponeSiteTarget(level, data, settlement, blueprint, citizen, entity);
            }
        } else {
            // Reaching an interaction cell is only an intermediate movement
            // success. The route is proven viable for this construction task
            // only after the intended blueprint block has actually been placed;
            // egress validation can still reject the same station immediately
            // before this call. Clearing the failure budget at the earlier
            // station check let one physically bad scaffold branch retry forever.
            entity.completeTrackedConstructionRoute();
            int buildTool = CitizenEquipmentService.toolSlotForWork(citizen);
            if (buildTool >= 0) citizen.damageInventoryItem(buildTool, 1);
            if (CitizenEquipmentService.missingEquipment(citizen) != null) {
                clear(data, citizen, entity);
                return;
            }
            if (result.value() != null) {
                clear(data, citizen, entity);
                return;
            }
            BlueprintPlacement lastPlacement = pending.placement();
            int actions = 1;
            for (BlueprintService.PendingPlacement continuation : immediateConstructionPlacements(
                    level, settlement, blueprint, citizen, entity, lastPlacement)) {
                if (actions++ >= MAX_LOCAL_CONSTRUCTION_ACTIONS_PER_CYCLE) break;
                Item material = continuation.placement().material();
                if (material == null || !selectInventoryItem(citizen, material)) break;
                if (BlueprintService.pendingAt(level, blueprint,
                        continuation.placement().position()) == null
                        || !canContinueConstructionFromCurrentPosition(
                        entity, continuation.placement().position())
                        || !ConstructionAccessPlanService.preservesResidentEgress(
                        level, blueprint, continuation.placement(), entity)) continue;
                syncHeldItem(citizen, entity);
                ItemStack continuationSupply = citizen.carriedItem();
                var continuationResult = BlueprintService.placeAt(
                        level, data, settlement, blueprint,
                        continuation.placement().position(), continuationSupply, entity);
                citizen.updateCarriedItem(continuationSupply);
                entity.setItemSlot(EquipmentSlot.MAINHAND, continuationSupply);
                if (!continuationResult.success()) break;
                int continuationTool = CitizenEquipmentService.toolSlotForWork(citizen);
                if (continuationTool >= 0) citizen.damageInventoryItem(continuationTool, 1);
                if (CitizenEquipmentService.missingEquipment(citizen) != null) {
                    clear(data, citizen, entity);
                    return;
                }
                lastPlacement = continuation.placement();
                if (continuationResult.value() != null) {
                    clear(data, citizen, entity);
                    return;
                }
            }
            continueConstructionAfterAction(level, data, settlement, blueprint, citizen, entity,
                    lastPlacement, false);
        }
    }

    @Nullable
    private static List<BlueprintService.PendingPlacement> immediateConstructionPlacements(
            ServerLevel level, SettlementRecord settlement, BlueprintRecord blueprint,
            CitizenRecord citizen, SettlementCitizenEntity entity,
            BlueprintPlacement completedPlacement
    ) {
        Set<BlockPos> claimed = settlement.citizens().stream()
                .filter(other -> !other.id().equals(citizen.id()))
                .filter(other -> blueprint.id().equals(other.targetBlueprintId()))
                .map(CitizenRecord::targetPosition)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        ConstructionPlanService.WorkPackage workPackage = ConstructionPlanService.createWorkPackage(
                level, blueprint, claimed, entity.blockPosition(), completedPlacement.position());
        return workPackage.placements().stream()
                .filter(candidate -> !BlueprintService.waitsForChestPartner(level, candidate))
                .filter(candidate -> !candidate.placement().position().equals(completedPlacement.position()))
                .filter(candidate -> !blueprint.hasClearanceTarget(candidate.placement().position()))
                .filter(candidate -> candidate.placement().material() != null)
                .filter(candidate -> countInventoryItem(citizen, candidate.placement().material()) > 0)
                .filter(candidate -> canContinueConstructionFromCurrentPosition(
                        entity, candidate.placement().position()))
                // The execution loop proves egress immediately before each
                // placement, against the world left by the previous placement.
                // Eagerly proving the entire list here could spend the slice on
                // a later candidate before any already-safe earlier item ran.
                .toList();
    }

    private static void continueConstructionAfterAction(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord blueprint, CitizenRecord citizen, SettlementCitizenEntity entity,
            BlueprintPlacement completedPlacement, boolean completedClearance
    ) {
        Set<BlockPos> claimed = settlement.citizens().stream()
                .filter(other -> !other.id().equals(citizen.id()))
                .filter(other -> blueprint.id().equals(other.targetBlueprintId()))
                .map(CitizenRecord::targetPosition)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        List<BlueprintService.PendingPlacement> usablePlacements = BlueprintService.pendingPlacements(
                        level, blueprint, claimed, entity.blockPosition()).stream()
                .filter(candidate -> completedClearance
                        == blueprint.hasClearanceTarget(candidate.placement().position()))
                .filter(candidate -> candidate.placement().material() == null
                        || countInventoryItem(citizen, candidate.placement().material()) > 0)
                .toList();
        BlueprintService.PendingPlacement next = selectLiveConstructionTarget(
                level, settlement, blueprint, entity, usablePlacements);
        if (next == null) {
            next = BlueprintService.nextUnclaimedPending(
                    level, data, blueprint, claimed, entity.blockPosition());
        }
        if (next != null && (blueprint.hasClearanceTarget(next.placement().position())
                || next.placement().material() == null
                || selectInventoryItem(citizen, next.placement().material()))) {
            citizen.updateTargetPosition(next.placement().position());
            syncHeldItem(citizen, entity);
        } else if (next != null && next.placement().material() != null) {
            citizen.updateTargetPosition(next.placement().position());
            BuildingRecord warehouse = WarehouseConstructionSupply.warehouse(
                    level, settlement, entity.blockPosition(), next.placement().material());
            if (warehouse == null && DecorationService.owned(settlement, blueprint.id()))
                warehouse = DecorationCrafting.warehouse(level, settlement, citizen, next.placement().material());
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(blueprint.ownerId());
            if (warehouse == null && owner == null
                    && !settlement.creativeCoreInstalled()) {
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "MATERIALS_MISSING");
                return;
            }
            citizen.fetchConstructionMaterial(warehouse == null ? null : warehouse.id());
        } else {
            if (continueBlueprintClosure(level, data, settlement, blueprint, citizen, entity, claimed)) return;
            clear(data, citizen, entity);
            if (assignPersonalDelivery(level, settlement, citizen)) syncHeldItem(citizen, entity);
            return;
        }
        data.touch();
    }

    /**
     * Chooses the next placement from live world geometry instead of assuming
     * the first cell in the deterministic template order is still reachable.
     *
     * <p>Template order is a preference, never a hard stage/layer gate. The
     * resident may cross layers or functional stages whenever live geometry
     * exposes a safer reachable block. This prevents an arbitrary first cell
     * from freezing every later placement and keeps the algorithm usable for
     * unseen community templates without encoding a building-specific script.</p>
     */
    @Nullable
    private static BlueprintService.PendingPlacement selectLiveConstructionTarget(
            ServerLevel level, SettlementRecord settlement, BlueprintRecord blueprint,
            SettlementCitizenEntity entity,
            List<BlueprintService.PendingPlacement> orderedCandidates
    ) {
        BuildingBlueprintTemplate constructionTemplate = BlueprintTemplates.create(blueprint);
        List<BlueprintService.PendingPlacement> available = orderedCandidates.stream()
                // Live distance sorting must preserve the double-chest dependency.
                .filter(candidate -> !BlueprintService.waitsForChestPartner(level, candidate))
                .filter(candidate -> !entity.constructionTargetDeferred(
                        candidate.placement().position()))
                .toList();
        if (available.isEmpty()) return null;
        boolean hasClearance = available.stream().anyMatch(candidate ->
                blueprint.hasClearanceTarget(candidate.placement().position()));
        int clearanceY=available.stream().filter(p->blueprint.hasClearanceTarget(p.placement().position())).mapToInt(p->p.placement().position().getY()).max().orElse(Integer.MIN_VALUE);
        List<BlueprintService.PendingPlacement> candidates = available.stream()
                // Site clearance remains a real physical prerequisite. Once it
                // is finished, structure layers and stages are all eligible.
                .filter(candidate -> !hasClearance || blueprint.hasClearanceTarget(
                        candidate.placement().position()) && candidate.placement().position().getY()==clearanceY)
                .toList();

        List<BlueprintService.PendingPlacement> livePriority = candidates.stream()
                // Live proximity is a preference, never a mandatory layer or
                // template stage. A worker standing on a newly completed roof
                // continues across that frontier instead of abandoning it for
                // the first coordinate of a global linear scan.
                .sorted(Comparator
                        .comparingInt((BlueprintService.PendingPlacement candidate) ->
                                constructionTemplate.constructionPhase(candidate.placement()))
                        .thenComparingDouble(candidate ->
                                constructionTargetAccessScore(constructionTemplate, entity,
                                        candidate.placement().position())))
                .toList();

        for (BlueprintService.PendingPlacement candidate : livePriority) {
            if (canContinueConstructionFromCurrentPosition(
                    entity, candidate.placement().position())
                    && (!requiresGroundEgressProtection(
                    constructionTemplate, candidate.placement())
                    || ConstructionAccessPlanService.preservesResidentEgress(
                    level, blueprint, candidate.placement(), entity))) {
                return candidate;
            }
        }
        // Reachability probes are expensive, so score geometry first and prove
        // only a bounded set. Layer/stage order is merely the final tie-breaker
        // inherited from the incoming list.
        Map<BlockPos, Integer> preferredOrder = new HashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            preferredOrder.put(candidates.get(index).placement().position(), index);
        }
        List<BlueprintService.PendingPlacement> geometryCandidates = candidates.stream()
                .sorted(Comparator
                        .comparingInt((BlueprintService.PendingPlacement candidate) ->
                                constructionTemplate.constructionPhase(candidate.placement()))
                        .thenComparingDouble(candidate ->
                                constructionTargetAccessScore(constructionTemplate, entity,
                                        candidate.placement().position()))
                        .thenComparingInt(candidate -> preferredOrder.getOrDefault(
                                candidate.placement().position(), Integer.MAX_VALUE)))
                .limit(48)
                .toList();
        for (int index = 0; index < Math.min(24, geometryCandidates.size()); index++) {
            BlueprintService.PendingPlacement candidate = geometryCandidates.get(index);
            if (requiresGroundEgressProtection(constructionTemplate, candidate.placement())
                    && !ConstructionAccessPlanService.preservesResidentEgress(
                    level, blueprint, candidate.placement(), entity)) continue;
            if (findConstructionWorkPosition(level, entity,
                    candidate.placement().position(), settlement, blueprint) != null) {
                return candidate;
            }
        }
        // No ordinary route is currently available. Give the generic scaffold
        // planner the geometrically nearest safe candidate, not the first cell
        // of a fixed layer scan.
        return geometryCandidates.isEmpty() ? candidates.getFirst()
                : geometryCandidates.getFirst();
    }

    private static boolean requiresGroundEgressProtection(
            BuildingBlueprintTemplate template, BlueprintPlacement placement
    ) {
        return placement.position().getY() <= template.minCorner().getY() + 1;
    }

    private static int bestClearanceToolSlot(CitizenRecord citizen, BlockState state) {
        int selected = -1;
        float bestSpeed = 1.0F;
        for (int slot = 0; slot < CitizenRecord.INVENTORY_SIZE; slot++) {
            ItemStack stack = citizen.inventoryItem(slot);
            if (stack.isEmpty()) continue;
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                selected = slot;
            }
        }
        return selected;
    }

    private static boolean clearanceActionReady(
            ServerLevel level, CitizenRecord citizen, BlockPos target,
            BlockState state, ItemStack tool
    ) {
        float hardness = Math.max(0.1F, state.getDestroySpeed(level, target));
        float toolSpeed = Math.max(1.0F, tool.isEmpty() ? 1.0F : tool.getDestroySpeed(state));
        int interval = net.minecraft.util.Mth.clamp(
                Math.round(hardness * 30.0F / toolSpeed), WORK_INTERVAL_TICKS, 100);
        int offset = Math.floorMod(citizen.id().hashCode() ^ target.hashCode(), interval);
        return Math.floorMod(level.getGameTime() + offset, interval) < WORK_INTERVAL_TICKS;
    }

    private static boolean constructionAccessFailure(String failure) {
        return "CONSTRUCTION_POSITION_UNREACHABLE".equals(failure)
                || "NO_PATH".equals(failure)
                || "CONSTRUCTION_TARGET_OUT_OF_REACH".equals(failure)
                || "CONSTRUCTION_HEADROOM_BLOCKED".equals(failure)
                || "FOOTING_POSITION_UNAVAILABLE".equals(failure)
                || "FOOTING_ROUTE_UNAVAILABLE".equals(failure)
                || "CONSTRUCTION_ROUTE_EXHAUSTED".equals(failure)
                || "EXIT_ROUTE_BLOCKED".equals(failure)
                || "STUCK".equals(failure);
    }

    private static void yieldOccupiedStructure(ServerLevel level,SettlementSavedData data,SettlementRecord settlement,
            BlueprintRecord site,CitizenRecord citizen,SettlementCitizenEntity entity) {
        var blocked=citizen.targetPosition();SITE_STATIONS.remove(entity);
        if(blocked!=null)entity.deferConstructionTarget(blocked,CONSTRUCTION_TARGET_RETRY_DELAY_TICKS);
        if(entity.isConstructionAccessRetreating() || occupiedTemporarySupport(level,site,constructionFeetPosition(entity))!=null)
            deferBlockedConstructionTarget(level,data,settlement,site,citizen,entity,blocked);
        else clear(data,citizen,entity);
        data.touch();
    }

    private static void postponeSiteTarget(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord blueprint, CitizenRecord citizen, SettlementCitizenEntity entity) {
        BlockPos alternative = blueprint.constructionController().postponeStructureTarget(level, blueprint, settlement, citizen, entity);
        if (alternative == null) return;
        citizen.updateTargetPosition(alternative);
        entity.finishConstructionMovement();
        data.touch();
    }

    private static void deferBlockedConstructionTarget(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord blueprint, CitizenRecord citizen, SettlementCitizenEntity entity,
            BlockPos blockedTarget
    ) {
        if (entity.isConstructionAccessRetreating() || occupiedTemporarySupport(level, blueprint,
                constructionFeetPosition(entity)) != null) {
            entity.beginConstructionAccessRetreat();
            setActivity(data, citizen, entity, CitizenActivity.BUILDING, "SCAFFOLDING_EGRESS_BLOCKED");
            return;
        }
        Set<BlockPos> excluded = settlement.citizens().stream()
                .filter(other -> !other.id().equals(citizen.id()))
                .filter(other -> blueprint.id().equals(other.targetBlueprintId()))
                .map(CitizenRecord::targetPosition)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        excluded.add(blockedTarget);
        List<BlueprintService.PendingPlacement> candidates = BlueprintService.pendingPlacements(
                        level, blueprint, excluded, entity.blockPosition()).stream()
                .filter(candidate -> !entity.constructionTargetDeferred(candidate.placement().position()))
                .toList();
        BlueprintService.PendingPlacement alternative = selectLiveConstructionTarget(
                level, settlement, blueprint, entity, candidates);
        // Commit a real access failure only after the alternative search has
        // finished. Yielding that search is not another failed physical route.
        entity.recordConstructionAccessFailure(citizen.workFailure());
        entity.deferConstructionTarget(blockedTarget, CONSTRUCTION_TARGET_RETRY_DELAY_TICKS);
        if (alternative == null) return;
        citizen.updateTargetPosition(alternative.placement().position());
        entity.finishConstructionMovement();
        syncHeldItem(citizen, entity);
        setActivity(data, citizen, entity, CitizenActivity.BUILDING, "");
        data.touch();
    }

    private static boolean continueBlueprintClosure(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord blueprint, CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        Set<BlockPos> claimed = settlement.citizens().stream()
                .filter(other -> !other.id().equals(citizen.id()))
                .filter(other -> blueprint.id().equals(other.targetBlueprintId()))
                .map(CitizenRecord::targetPosition)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        return continueBlueprintClosure(level, data, settlement, blueprint, citizen, entity, claimed);
    }

    private static boolean continueBlueprintClosure(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord blueprint, CitizenRecord citizen, SettlementCitizenEntity entity,
            Set<BlockPos> claimed
    ) {
        if (BlueprintService.readyForFootingCleanup(level, blueprint)) {
            int previousFootingCount = blueprint.temporaryFootings().size();
            BlueprintPlacement cleanup = nextReachableTemporaryCleanup(
                    level, blueprint, claimed, entity);
            if (cleanup == null && blueprint.temporaryFootings().isEmpty()) {
                cleanup = BlueprintCancellationService.nextCleanup(level, blueprint, claimed);
            }
            if (blueprint.temporaryFootings().size() != previousFootingCount) data.touch();
            if (cleanup != null && citizen.continueWithBlueprintCleanup(
                    blueprint.id(), cleanup.position())) {
                entity.finishConstructionMovement();
                setActivity(data, citizen, entity, CitizenActivity.DEMOLISHING, "");
                data.touch();
                return true;
            }
        }
        if (blueprint.temporaryFootings().isEmpty()
                && BlueprintService.constructionComplete(level, blueprint)) {
            var completed = BlueprintService.completeAfterFootingCleanup(level, data, settlement, blueprint);
            if (completed.success() && completed.value() != null) {
                ProfessionAssignmentService.refresh(data, settlement);
            }
        }
        return false;
    }

    private static final Map<CitizenRecord,Long> MOB_RECOVERY_STARTED=new java.util.WeakHashMap<>();
    private static final Map<CitizenRecord,Long> MOB_RECOVERY_WORK_STARTED=new java.util.WeakHashMap<>();
    private static void releaseMobRecovery(ServerLevel level,SettlementSavedData data,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity actor,String reason) {
        var cell=com.hkin.exiledsettlement.building.MobRecoveryService.cell(s,c);
        if(cell!=null)com.hkin.exiledsettlement.building.MobRecoveryService.defer(level,s,cell,reason);
        releaseSupplyStation(level,actor);clear(data,c,actor);MOB_RECOVERY_STARTED.remove(c);MOB_RECOVERY_WORK_STARTED.remove(c);
        setActivity(data,c,actor,CitizenActivity.BLOCKED,reason);
    }
    private static void fetchMobRecoveryMaterial(ServerLevel level,SettlementSavedData data,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity actor) {
        var event=com.hkin.exiledsettlement.building.MobRecoveryService.task(s,c);
        var cell=com.hkin.exiledsettlement.building.MobRecoveryService.cell(s,c);
        if(cell==null){clear(data,c,actor);return;}
        var reason=com.hkin.exiledsettlement.building.MobRecoveryService.ready(level,s,event,cell);
        if(!reason.isEmpty()){releaseMobRecovery(level,data,s,c,actor,reason);return;}
        var item=com.hkin.exiledsettlement.building.MobRecoveryService.material(cell.original);
        int missing=com.hkin.exiledsettlement.building.MobRecoveryService.cost(cell.original)-countInventoryItem(c,item);
        if(missing<=0){selectInventoryItem(c,item);c.returnToRepair();data.touch();return;}
        var warehouse=s.building(c.targetBuildingId());
        if(warehouse!=null && (!warehouse.active() || !warehouse.operational())){releaseMobRecovery(level,data,s,c,actor,"WAREHOUSE_INACCESSIBLE");return;}
        boolean creative=warehouse!=null?WarehouseInventoryService.hasCreativeSupply(level,warehouse):s.creativeCoreInstalled();
        if(warehouse==null && !creative){releaseMobRecovery(level,data,s,c,actor,"MATERIALS_MISSING");return;}
        var station=warehouse!=null?WarehouseInventoryService.reserveInteractionPosition(level,warehouse,actor)
                :SettlementCoreInteractionService.reserveSupplyPosition(level,s,actor);
        if(station==null){releaseMobRecovery(level,data,s,c,actor,"WAREHOUSE_INACCESSIBLE");return;}
        long start=MOB_RECOVERY_STARTED.computeIfAbsent(c,k->level.getGameTime());
        if(level.getGameTime()-start>600){releaseMobRecovery(level,data,s,c,actor,"WAREHOUSE_INACCESSIBLE");return;}
        if(!nearWorkPosition(actor,station)){moveToWarehouseStation(level,data,c,actor,station,CitizenActivity.FETCHING_REPAIR_MATERIAL);return;}
        if(warehouse!=null?!WarehouseInventoryService.canInteract(level,warehouse,actor):!SettlementCoreInteractionService.canReceiveSupply(level,s,actor)){
            releaseMobRecovery(level,data,s,c,actor,"WAREHOUSE_INACCESSIBLE");return;
        }
        int batch=Math.max(missing,Math.min(32,event.cells.values().stream()
            .filter(v->v.status==com.hkin.exiledsettlement.building.MobRecoveryState.Status.PENDING
                && com.hkin.exiledsettlement.building.MobRecoveryService.material(v.original)==item)
            .mapToInt(v->com.hkin.exiledsettlement.building.MobRecoveryService.cost(v.original)).sum()));
        int amount=Math.min(batch,c.inventoryCapacityFor(new ItemStack(item,batch)));
        if(amount<missing){releaseMobRecovery(level,data,s,c,actor,"INVENTORY_FULL");return;}
        var extracted=warehouse==null?ItemStack.EMPTY:WarehouseConstructionSupply.extract(level,warehouse,actor,item,amount);
        if(extracted.isEmpty() && creative)extracted=new ItemStack(item,amount);
        int got=extracted.getCount();
        if(got==0){releaseMobRecovery(level,data,s,c,actor,"MATERIALS_MISSING");return;}
        var remainder=c.insertIntoInventory(extracted);
        if(!remainder.isEmpty()){
            if(warehouse!=null)remainder=WarehouseInventoryService.insertForResident(level,warehouse,actor,remainder);
            if(!remainder.isEmpty())net.minecraft.world.level.block.Block.popResource(level,actor.blockPosition(),remainder);
        }
        selectInventoryItem(c,item);c.returnToRepair();releaseSupplyStation(level,actor);MOB_RECOVERY_STARTED.remove(c);
        com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,c,"mob_recovery_supply",cell.pos,item.toString(),got);
        syncHeldItem(c,actor);data.touch();
    }
    private static void repairMobDamage(ServerLevel level,SettlementSavedData data,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity actor) {
        var event=com.hkin.exiledsettlement.building.MobRecoveryService.task(s,c);
        var cell=com.hkin.exiledsettlement.building.MobRecoveryService.cell(s,c);
        if(cell==null){clear(data,c,actor);return;}
        if(c.profession()!=CitizenProfession.WORKER){releaseMobRecovery(level,data,s,c,actor,"RECOVERY_WAITING");return;}
        if(com.hkin.exiledsettlement.building.MobRecoveryService.group(event,cell).stream()
                .allMatch(v->com.hkin.exiledsettlement.building.MobRecoveryService.matches(level.getBlockState(v.pos),v.original))){
            for(var v:com.hkin.exiledsettlement.building.MobRecoveryService.group(event,cell)){
                v.status=com.hkin.exiledsettlement.building.MobRecoveryState.Status.DONE;s.mobRecovery().index(event,v);
            }clear(data,c,actor);data.touch();return;
        }
        String reason=com.hkin.exiledsettlement.building.MobRecoveryService.ready(level,s,event,cell);
        if(!reason.isEmpty()){releaseMobRecovery(level,data,s,c,actor,reason);return;}
        var item=com.hkin.exiledsettlement.building.MobRecoveryService.material(cell.original);
        int cost=com.hkin.exiledsettlement.building.MobRecoveryService.cost(cell.original);
        if(countInventoryItem(c,item)<cost){
            var warehouse=WarehouseConstructionSupply.warehouse(level,s,actor.blockPosition(),item);
            if(warehouse==null && !s.creativeCoreInstalled()){releaseMobRecovery(level,data,s,c,actor,"MATERIALS_MISSING");return;}
            c.fetchRepairMaterial(warehouse==null?null:warehouse.id());MOB_RECOVERY_STARTED.remove(c);data.touch();return;
        }
        int tool=CitizenEquipmentService.toolSlotForWork(c);
        if(tool<0){releaseMobRecovery(level,data,s,c,actor,"TOOLS_MISSING");return;}
        long start=MOB_RECOVERY_STARTED.computeIfAbsent(c,k->level.getGameTime());
        if(!com.hkin.exiledsettlement.building.MobRecoveryService.arrived(event,actor)){
            MOB_RECOVERY_WORK_STARTED.remove(c);
            var station=com.hkin.exiledsettlement.building.MobRecoveryService.approach(level,event,actor,cell.pos);
            if(station==null || level.getGameTime()-start>600){releaseMobRecovery(level,data,s,c,actor,"RECOVERY_UNREACHABLE");return;}
            move(data,c,actor,station,CitizenActivity.REPAIRING);return;
        }
        actor.stopSettlementNavigation();setActivity(data,c,actor,CitizenActivity.REPAIRING,"");
        long workStart=MOB_RECOVERY_WORK_STARTED.computeIfAbsent(c,k->level.getGameTime());
        if(level.getGameTime()-workStart<com.hkin.exiledsettlement.blueprint.TimedConstructionService.INTERVAL)return;
        reason=com.hkin.exiledsettlement.building.MobRecoveryService.place(level,s,event,cell,actor);
        if(!reason.isEmpty()){releaseMobRecovery(level,data,s,c,actor,reason);return;}
        int remaining=cost;
        while(remaining>0){var paid=c.extractInventoryItem(stack->stack.is(item),remaining);if(paid.isEmpty())throw new IllegalStateException("Recovery material reservation changed within server action");remaining-=paid.getCount();}
        c.damageInventoryItem(tool,1);
        com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,c,"mob_recovery_place",cell.pos,item.toString(),cost);
        clear(data,c,actor);MOB_RECOVERY_STARTED.remove(c);MOB_RECOVERY_WORK_STARTED.remove(c);syncHeldItem(c,actor);data.touch();
    }

    private static void fetchRepairMaterial(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        if(com.hkin.exiledsettlement.building.MobRecoveryService.task(settlement,citizen)!=null) {
            fetchMobRecoveryMaterial(level,data,settlement,citizen,entity);return;
        }
        BlockPos position = citizen.targetPosition();
        BuildingRecord repairedBuilding = settlement.building(citizen.sourceBuildingId());
        if(com.hkin.exiledsettlement.region.FarmConstructionPolicy.farm(repairedBuilding)){clear(data,citizen,entity);return;}
        BlockState expected = position == null ? null
                : BuildingRepairService.expectedState(level, settlement, repairedBuilding, position);
        Item required = expected == null ? null : repairMaterial(expected);
        BuildingRecord warehouse = settlement.building(citizen.targetBuildingId());
        boolean decorationRepair = repairedBuilding != null && DecorationService.forBuilding(settlement, repairedBuilding.id()) != null
                && required!=null && !WarehouseConstructionSupply.available(level,settlement,required,1);
        if (decorationRepair && required != null) {
            DecorationCrafting.craft(level, settlement, citizen, Map.of(required, 1));
            if (selectInventoryItem(citizen, required)) { citizen.returnToRepair(); data.touch(); return; }
            warehouse = DecorationCrafting.warehouse(level, settlement, citizen, required);
        }
        if(required!=null && (warehouse==null || !WarehouseConstructionSupply.available(level,settlement,warehouse,required,1)))
            warehouse=WarehouseConstructionSupply.warehouse(level,settlement,entity.blockPosition(),required);
        boolean useWarehouse = warehouse != null && warehouse.active() && warehouse.operational() && warehouse.type().isStorage();
        boolean coreSupply = settlement.creativeCoreInstalled();
        boolean virtualSupply = useWarehouse
                ? WarehouseInventoryService.hasCreativeSupply(level, warehouse) : coreSupply;
        if (position == null || expected == null || required == null) {
            clear(data, citizen, entity);
            return;
        }
        if (!useWarehouse && !coreSupply) {
            releaseSupplyStation(level, entity);
            entity.stopSettlementNavigation();
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "MATERIALS_MISSING");
            return;
        }
        Map<Item, Integer> repairInputs = decorationRepair && !virtualSupply
                ? DecorationCrafting.ingredients(level, settlement, citizen, Map.of(required, 1)) : Map.of(required, 1);
        BlockPos target = useWarehouse
                ? WarehouseInventoryService.reserveInteractionPosition(level, warehouse, entity)
                : SettlementCoreInteractionService.reserveSupplyPosition(level, settlement, entity);
        if (target == null) {
            waitForSupplyStation(level, data, settlement, citizen, entity, warehouse,
                    useWarehouse, CitizenActivity.FETCHING_REPAIR_MATERIAL);
            return;
        }
        if (!nearWorkPosition(entity, target)) {
            moveToWarehouseStation(level, data, citizen, entity, target,
                    CitizenActivity.FETCHING_REPAIR_MATERIAL);
            return;
        }
        if (useWarehouse && !WarehouseInventoryService.canInteract(level, warehouse, entity)) {
            abandonSupplyStation(level, entity);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "WAREHOUSE_INACCESSIBLE");
            return;
        }
        if (!useWarehouse && !SettlementCoreInteractionService.canReceiveSupply(level, settlement, entity)) {
            abandonSupplyStation(level, entity);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "CORE_SUPPLY_INACCESSIBLE");
            return;
        }
        if (decorationRepair && !virtualSupply) {
            for (var in : repairInputs.entrySet()) {
                int request = Math.min(in.getValue(), countInventoryItem(citizen, in.getKey()) + Math.max(0,
                        SettlementInventoryService.countItem(level, settlement, in.getKey()) - DecorationCrafting.reserve(settlement, in.getKey())));
                loadConstructionMaterial(level, warehouse, null, citizen, entity, in.getKey(), request, true, false);
            }
            DecorationCrafting.craft(level, settlement, citizen, Map.of(required, 1)); data.touch();
            if (selectInventoryItem(citizen, required)) { citizen.returnToRepair(); releaseSupplyStation(level, entity); return; }
        }
        ItemStack extracted = useWarehouse
                ? WarehouseConstructionSupply.extract(
                level, warehouse, entity, required, 1) : ItemStack.EMPTY;
        if (extracted.isEmpty() && virtualSupply) extracted = new ItemStack(required);
        if (extracted.isEmpty()) {
            releaseSupplyStation(level, entity);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "MATERIALS_MISSING");
            return;
        }
        if (!citizen.returnToRepair(extracted)) {
            if (useWarehouse) {
                WarehouseInventoryService.insertForResident(level, warehouse, entity, extracted);
            }
            releaseSupplyStation(level, entity);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "INVENTORY_FULL");
            return;
        }
        releaseSupplyStation(level, entity);
        syncHeldItem(citizen, entity);
        data.touch();
    }

    private static void repair(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        if(com.hkin.exiledsettlement.building.MobRecoveryService.task(settlement,citizen)!=null) {
            repairMobDamage(level,data,settlement,citizen,entity);return;
        }
        BlockPos position = citizen.targetPosition();
        if(com.hkin.exiledsettlement.building.MobRecoveryService.owns(settlement,position)){clear(data,citizen,entity);return;}
        BuildingRecord building = settlement.building(citizen.targetBuildingId());
        if(com.hkin.exiledsettlement.region.FarmConstructionPolicy.farm(building)){clear(data,citizen,entity);return;}
        BlockState expected = position == null ? null
                : BuildingRepairService.expectedState(level, settlement, building, position);
        if (position == null || expected == null) {
            clear(data, citizen, entity);
            return;
        }
        if (building == null ? level.getBlockState(position).equals(expected)
                : BuildingRepairService.satisfied(level, building, position)) {
            BuildingRepairService.clearRepair(settlement, building, position);
            clear(data, citizen, entity);
            data.touch();
            return;
        }
        if (!near(entity, position)) {
            move(data, citizen, entity, position, CitizenActivity.REPAIRING);
            return;
        }
        Item required = repairMaterial(expected);
        if (required != null && !selectInventoryItem(citizen, required)) {
            BuildingRecord warehouse = WarehouseConstructionSupply.warehouse(
                    level, settlement, entity.blockPosition(), required);
            if (warehouse == null && building != null && DecorationService.forBuilding(settlement, building.id()) != null)
                warehouse = DecorationCrafting.warehouse(level, settlement, citizen, required);
            if (warehouse == null && !settlement.creativeCoreInstalled()) {
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "MATERIALS_MISSING");
                return;
            }
            citizen.fetchRepairMaterial(warehouse == null ? null : warehouse.id());
            data.touch();
            return;
        }
        boolean pairedRepair = building != null
                && com.hkin.exiledsettlement.blueprint.BlueprintPlacementGroups.isPaired(expected);
        if (!pairedRepair && !level.getBlockState(position).isAir()
                && !level.getBlockState(position).is(com.hkin.exiledsettlement.registry.ExiledSettlementTags.REPLACEABLE_FOUNDATION)) {
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "REPAIR_POSITION_OCCUPIED");
            return;
        }
        if (!workCycleReady(level, citizen)) return;
        if (pairedRepair) {
            var template = BlueprintTemplates.createForBuilding(building);
            var placed = com.hkin.exiledsettlement.blueprint.BlueprintPlacementGroups.place(
                    level, settlement, template, template.placementAt(position), entity, false);
            if (!placed.success()) {
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, placed.reason());
                return;
            }
        } else {
            BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, position, Block.UPDATE_CLIENTS);
            if (!level.setBlock(position, expected, Block.UPDATE_ALL)
                    || EventHooks.onBlockPlace(null, snapshot, Direction.UP)) {
                snapshot.restore(Block.UPDATE_ALL);
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "PROTECTED_AREA");
                return;
            }
        }
        if (required != null) {
            ItemStack supplied = citizen.carriedItem();
            supplied.shrink(1);
            citizen.updateCarriedItem(supplied);
        }
        int toolSlot = CitizenEquipmentService.toolSlotForWork(citizen);
        if (toolSlot >= 0) citizen.damageInventoryItem(toolSlot, 1);
        com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,citizen,
                building==null?"road_repair":"building_repair",position,
                required==null?"":required.toString(),required==null?0:1);
        BuildingRepairService.clearRepair(settlement, building, position);
        clear(data, citizen, entity);
        Set<BlockPos> claimed = settlement.citizens().stream()
                .filter(other -> !other.id().equals(citizen.id()))
                .map(CitizenRecord::targetPosition)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        assignRepair(level, data, settlement, citizen, claimed);
        syncHeldItem(citizen, entity);
        data.touch();
    }

    @Nullable
    private static Item repairMaterial(BlockState state) {
        if (state.is(Blocks.FARMLAND) || state.is(Blocks.DIRT_PATH)) return Items.DIRT;
        Item item = state.getBlock().asItem();
        return item == Items.AIR ? null : item;
    }

    private static final Map<SettlementCitizenEntity, SiteStation> DEMOLITION_STATIONS = new java.util.WeakHashMap<>();
    private static final Map<SettlementCitizenEntity, Integer> DEMOLITION_CREDITS = new java.util.WeakHashMap<>();
    private static final Map<SettlementCitizenEntity, Long> DEMOLITION_TICKS = new java.util.WeakHashMap<>();

    private static void prioritizeDemolition(ServerLevel level, SettlementSavedData data, SettlementRecord settlement) {
        if (settlement.community().evacuation || settlement.community().policy.equals("emergency")) return;
        boolean queued = settlement.buildings().stream().anyMatch(b -> b.active() && b.demolitionQueued());
        if (!queued) return;
        for (CitizenRecord citizen : settlement.citizens()) {
            if(!workDue(level,citizen))continue;
            if (!citizen.canWork() || !constructionCapable(citizen.profession())
                    || citizen.workState() == CitizenWorkState.TO_DEMOLITION
                    || citizen.workState() == CitizenWorkState.IDLE
                    || com.hkin.exiledsettlement.settlement.CommunityLifeService.handles(citizen)) continue;
            if (citizen.preferredWorksiteId() != null && preferredWorksiteAvailable(settlement, citizen.preferredWorksiteId())) {
                BuildingRecord preferred = settlement.building(citizen.preferredWorksiteId());
                if (preferred == null || !preferred.demolitionQueued()) continue;
            }
            SettlementCitizenEntity entity = CitizenProjectionService.findProjection(level, citizen);
            if (entity == null || entity.isSleeping() || entity.isDefending() || !entity.onGround()) continue;
            if (leaveScaffoldingBeforeLogistics(level, data, settlement, citizen, entity)) continue;
            clear(data, citizen, entity);
        }
    }

    /** One credit per five server ticks; paired blocks consume two credits atomically. */
    public static void tickDemolitionStaggered(ServerLevel level) {
        staggerConstruction=true;
        try {tickDemolition(level);}finally{staggerConstruction=false;}
    }
    public static void tickDemolition(ServerLevel level) {
        SettlementSavedData data = SettlementSavedData.get(level);
        for (SettlementRecord settlement : data.settlements()) {
            if (!settlement.active() || !level.hasChunkAt(settlement.corePosition()) || settlement.community().evacuation
                    || settlement.community().policy.equals("emergency")) continue;
            for (CitizenRecord citizen : settlement.citizens()) {
                if(staggerConstruction && !com.hkin.exiledsettlement.util.TickCadence.due(level.getGameTime(),citizen.id(),5,2))continue;
                if (!citizen.canWork() || citizen.workState() != CitizenWorkState.TO_DEMOLITION
                        || com.hkin.exiledsettlement.settlement.CommunityLifeService.handles(citizen)) continue;
                SettlementCitizenEntity entity = CitizenProjectionService.findProjection(level, citizen);
                if (entity == null || entity.isSleeping() || entity.isDefending()) continue;
                if (CitizenInteractionService.held(entity)) continue;
                advanceDemolitionOnCadence(level, data, settlement, citizen, entity);
            }
        }
    }

    private static void advanceDemolitionOnCadence(ServerLevel level, SettlementSavedData data,
            SettlementRecord settlement, CitizenRecord citizen, SettlementCitizenEntity entity) {
        if (pauseForCargoDelivery(level, data, settlement, citizen, entity)) return;
        long now = level.getGameTime();
        if (now - DEMOLITION_TICKS.getOrDefault(entity, Long.MIN_VALUE / 2) < 5) return;
        DEMOLITION_TICKS.put(entity, now);
        if (citizen.targetBlueprintId() != null) cleanupBlueprintSite(level, data, settlement, citizen, entity);
        else demolish(level, data, settlement, citizen, entity);
    }

    private static boolean prepareDemolitionStation(ServerLevel level,SettlementSavedData data,
            SettlementRecord settlement,BuildingRecord building,CitizenRecord citizen,SettlementCitizenEntity entity) {
        if (leaveScaffoldingBeforeLogistics(level,data,settlement,citizen,entity)) return false;
        var template=BlueprintTemplates.createForBuilding(building);
        if (siteGround(level,template,entity,entity.blockPosition()) && entity.onGround()
                && settlement.ownsChunk(entity.blockPosition().getX()>>4,entity.blockPosition().getZ()>>4)) {
            entity.stopSettlementNavigation(); return true;
        }
        SiteStation cached=DEMOLITION_STATIONS.get(entity);
        if (cached==null || !cached.site().equals(building.id()) || cached.retryAt()<=level.getGameTime()) {
            BlockPos approach=serviceAreaApproach(level,template,entity);
            if (approach!=null && !settlement.ownsChunk(approach.getX()>>4,approach.getZ()>>4)) approach=null;
            cached=new SiteStation(building.id(),approach,level.getGameTime()+100);DEMOLITION_STATIONS.put(entity,cached);
        }
        if (cached.position()==null) { setActivity(data,citizen,entity,CitizenActivity.BLOCKED,"DEMOLITION_POSITION_UNREACHABLE");return false; }
        move(data,citizen,entity,cached.position(),CitizenActivity.DEMOLISHING);return false;
    }

    private static void demolish(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity) {
        BuildingRecord building = settlement.building(citizen.targetBuildingId());
        if (building == null || !building.active() || !building.demolitionQueued()
                || building.worksiteAssignmentMode() == WorksiteAssignmentMode.MANUAL
                && !building.id().equals(citizen.preferredWorksiteId())) {
            DEMOLITION_STATIONS.remove(entity); DEMOLITION_CREDITS.remove(entity);
            clear(data, citizen, entity); return;
        }
        if (!BuildingDemolitionService.areaLoaded(level, building)) {
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "AREA_NOT_LOADED"); return;
        }
        if(com.hkin.exiledsettlement.building.WarehouseStock.applies(building)
                && WarehouseInventoryService.hasAnyItem(level,building)) {
            var stock=WarehouseInventoryService.stock(level,building);
            var sample=java.util.stream.IntStream.range(0,stock.getContainerSize()).mapToObj(stock::getItem)
                    .filter(i->!i.isEmpty()).findFirst().orElse(ItemStack.EMPTY);
            var destination=SettlementInventoryService.warehouses(settlement).stream()
                    .filter(b->!b.id().equals(building.id()) && WarehouseInventoryService.canAccept(level,b,sample)).findFirst().orElse(null);
            if(destination==null){setActivity(data,citizen,entity,CitizenActivity.BLOCKED,"WAREHOUSE_EVACUATION_NO_DESTINATION");return;}
            if(citizen.inventoryCapacityFor(sample)<=0){clear(data,citizen,entity);assignPersonalDelivery(level,settlement,citizen);return;}
            var station=WarehouseInventoryService.approachPosition(level,building,entity);
            if(station==null)station=WarehouseInventoryService.reserveInteractionPosition(level,building,entity);
            if(station==null){setActivity(data,citizen,entity,CitizenActivity.BLOCKED,"WAREHOUSE_STATION_BUSY");return;}
            if(!WarehouseInventoryService.canInteract(level,building,entity)) {entity.navigateTo(station,0.8D);return;}
            int n=Math.min(sample.getCount(),citizen.inventoryCapacityFor(sample));
            var taken=WarehouseInventoryService.extractItemForResident(level,building,entity,sample.getItem(),n,citizen::inventoryCapacityFor);
            if(!taken.isEmpty())citizen.insertIntoInventory(taken);
            clear(data,citizen,entity);assignPersonalDelivery(level,settlement,citizen);data.touch();return;
        }
        BlockPos target = BuildingDemolitionService.nextBlock(level, building);
        if (target == null) {
            settlement.finishBuildingDemolition(building.id());
            ProfessionAssignmentService.refresh(data, settlement);
            DEMOLITION_STATIONS.remove(entity); DEMOLITION_CREDITS.remove(entity);
            clear(data, citizen, entity);
            if (assignPersonalDelivery(level, settlement, citizen)) syncHeldItem(citizen, entity);
            return;
        }
        if (!prepareDemolitionStation(level, data, settlement, building, citizen, entity)) {
            DEMOLITION_CREDITS.remove(entity); return;
        }
        if (CitizenEquipmentService.missingEquipment(citizen) != null) {
            clear(data, citizen, entity); return;
        }
        var template = BlueprintTemplates.createForBuilding(building);
        var parts = BlueprintPlacementGroups.parts(template, template.placementAt(target));
        int cost = (int) parts.stream().filter(p -> !level.getBlockState(p.position()).isAir()).count();
        int credits = Math.min(2, DEMOLITION_CREDITS.getOrDefault(entity, 0) + 1);
        DEMOLITION_CREDITS.put(entity, credits);
        if (cost <= 0 || credits < cost) return;
        if (!BuildingDemolitionService.removeAt(level, building, target, entity)) {
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "PROTECTED_AREA"); return;
        }
        DEMOLITION_CREDITS.put(entity, credits - cost);
        citizen.updateTargetPosition(target);
        int toolSlot = CitizenEquipmentService.toolSlotForWork(citizen);
        if (toolSlot >= 0) citizen.damageInventoryItem(toolSlot, cost);
        syncHeldItem(citizen, entity);
        setActivity(data, citizen, entity, CitizenActivity.DEMOLISHING, "");
        data.touch();
        if (!citizen.hasInventoryCapacity()) {
            clear(data, citizen, entity);
            if (assignPersonalDelivery(level, settlement, citizen)) syncHeldItem(citizen, entity);
        }
    }

    private static BlueprintPlacement nextReachableTemporaryCleanup(
            ServerLevel level, BlueprintRecord blueprint, Set<BlockPos> claimed,
            SettlementCitizenEntity entity
    ) {
        return blueprint.temporaryFootings().entrySet().stream()
                .filter(entry -> !claimed.contains(entry.getKey()))
                .filter(entry -> !blueprint.hasTemporaryFootingChild(entry.getKey()))
                .filter(entry -> level.getBlockState(entry.getKey()).is(Block.byItem(entry.getValue())))
                .filter(entry -> !entity.getBoundingBox().intersects(
                        new AABB(entry.getKey()).expandTowards(0.0D, 2.5D, 0.0D)))
                .filter(entry -> ConstructionAccessPlanService.preservesResidentEgressAfterRemoval(
                        level, blueprint, entry.getKey()))
                .filter(entry -> demolitionTargetReachable(level, entity, entry.getKey()))
                .sorted(Comparator
                        .comparing((Map.Entry<BlockPos, Item> entry) ->
                                !canWorkComfortablyFromCurrentPosition(entity, entry.getKey()))
                        .thenComparingDouble(entry -> entry.getKey().distSqr(entity.blockPosition()))
                        .thenComparing(Comparator.comparingInt(
                                (Map.Entry<BlockPos, Item> entry) ->
                                        blueprint.temporaryFootingOrder(entry.getKey())).reversed()))
                .map(entry -> new BlueprintPlacement(entry.getKey(),
                        Block.byItem(entry.getValue()).defaultBlockState(), entry.getValue(),
                        BlueprintPlacementMode.STRUCTURE))
                .findFirst().orElse(null);
    }

    private static boolean demolitionTargetReachable(
            ServerLevel level, SettlementCitizenEntity entity, BlockPos target
    ) {
        return canConstructFromCurrentPosition(entity, target)
                || findConstructionWorkPosition(level, entity, target) != null;
    }

    @Nullable
    private static BlockPos nextReachableDemolitionBlock(
            ServerLevel level, BuildingRecord building, Set<BlockPos> claimed,
            SettlementCitizenEntity entity
    ) {
        for (BlockPos candidate : BuildingDemolitionService.remainingBlocks(
                level, building, claimed, entity.blockPosition())) {
            if (demolitionTargetReachable(level, entity, candidate)) return candidate;
        }
        return null;
    }

    private static void cleanupBlueprintSite(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        BlueprintRecord blueprint = settlement.blueprint(citizen.targetBlueprintId());
        if (blueprint == null || (!blueprint.cancellationQueued()
                && !BlueprintService.readyForFootingCleanup(level, blueprint))) {
            clear(data, citizen, entity);
            return;
        }
        boolean promotedFinalFooting = false;
        if (!blueprint.cancellationQueued()) {
            // A solid access block may already be exactly the final blueprint
            // block at this position (for example a cobblestone footing inside
            // a cobblestone wall course). It was placed and paid for by the
            // worker, so promote it to final structure instead of demolishing
            // and placing the identical block again. Scaffolding and every
            // state mismatch still follow the normal physical cleanup path.
            Map<BlockPos, BlueprintPlacement> finalPlacements = BlueprintTemplates.create(blueprint)
                    .placements().stream()
                    .filter(placement -> placement.mode().isFinalStructure())
                    .collect(java.util.stream.Collectors.toMap(
                            BlueprintPlacement::position, placement -> placement,
                            (first, ignored) -> first));
            for (Map.Entry<BlockPos, Item> entry : new ArrayList<>(
                    blueprint.temporaryFootings().entrySet())) {
                BlueprintPlacement placement = finalPlacements.get(entry.getKey());
                if (placement == null || entry.getValue() == Items.SCAFFOLDING
                        || blueprint.hasTemporaryFootingChild(entry.getKey())
                        || !level.getBlockState(entry.getKey()).equals(placement.state())) continue;
                // A paid solid footing may become its identical final block,
                // but only after every temporary child has been recovered.
                // Promoting a parent early removes its recorded parent edge;
                // scaffold children that vanilla still supports on the roof
                // then become unreachable high roots. Leaf-to-root cleanup must
                // retain the physical tree until that final promotion is safe.
                promotedFinalFooting |= blueprint.removeTemporaryFooting(entry.getKey());
            }
        }
        boolean reconciledCollapsedScaffolding = false;
        for (Map.Entry<BlockPos, Item> entry : new ArrayList<>(
                blueprint.temporaryFootings().entrySet())) {
            if (!level.getBlockState(entry.getKey()).is(Block.byItem(entry.getValue()))) {
                reconciledCollapsedScaffolding |= blueprint.removeTemporaryFooting(entry.getKey());
            }
        }
        if (reconciledCollapsedScaffolding || promotedFinalFooting) {
            if (citizen.targetPosition() != null
                    && !blueprint.hasTemporaryFooting(citizen.targetPosition())) {
                citizen.updateTargetPosition(null);
            }
            data.touch();
        }
        if (!blueprint.temporaryFootings().isEmpty()
                && recoverAllTemporaryFootingsDirectly(
                level, blueprint, citizen, entity)) {
            CitizenNavigationReservationService.resetStationAfterAccessTopologyChange(
                    level, entity.getUUID());
            data.touch();
            if (!blueprint.cancellationQueued()
                    && !BlueprintService.constructionComplete(level, blueprint)) {
                // Removing access can expose blueprint cells that were
                // temporarily occupied by scaffolding. Return the resident to
                // normal construction immediately; no cleanup route remains to
                // preserve or blacklist.
                entity.resetConstructionRoutesAfterAccessTeardown();
                clear(data, citizen, entity);
                syncHeldItem(citizen, entity);
                return;
            }
        }
        if(ConstructionSiteController.groundWork(blueprint) && !blueprint.temporaryFootings().isEmpty()) {
            if(leaveScaffoldingBeforeLogistics(level,data,settlement,citizen,entity))return;
            if(approachTemporaryAccessBaseForCleanup(level,data,settlement,blueprint,citizen,entity))return;
            setActivity(data,citizen,entity,CitizenActivity.BLOCKED,"FOOTING_POSITION_UNAVAILABLE");return;
        }
        Set<BlockPos> claimed = settlement.citizens().stream()
                .filter(other -> !other.id().equals(citizen.id()))
                .filter(other -> other.workState() == CitizenWorkState.TO_DEMOLITION)
                .filter(other -> blueprint.id().equals(other.targetBlueprintId()))
                .map(CitizenRecord::targetPosition)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        BlueprintPlacement retainedCleanup = null;
        if (entity.activeConstructionMovementTarget() != null) {
            if (entity.continueConstructionStep(WALK_SPEED)) {
                setActivity(data, citizen, entity, CitizenActivity.DEMOLISHING, "");
                return;
            }
            if (entity.consumeConstructionMovementFailure()) {
                entity.recordConstructionAccessFailure("CLEANUP_DESCENT_STUCK");
            }
        }
        if (!blueprint.temporaryFootings().isEmpty()) {
            BlockPos cleanupFeet = constructionFeetPosition(entity);
            BlockPos cleanupSupport = occupiedTemporarySupport(level, blueprint, cleanupFeet);
            BuildingBlueprintTemplate cleanupTemplate = BlueprintTemplates.create(blueprint);
            boolean elevatedOnFinalStructure = cleanupSupport == null
                    && cleanupFeet.getY() > cleanupTemplate.minCorner().getY() + 1;
            if ((cleanupSupport != null
                    && (beginNearbyScaffoldingDescent(
                        blueprint, entity, cleanupFeet, true)
                    || beginTemporaryFootingDescent(blueprint, entity)
                    || beginCleanupEgressToSafeSurface(blueprint, entity)))
                    || elevatedOnFinalStructure
                    && (beginNearbyScaffoldingDescent(
                    blueprint, entity, cleanupFeet, true)
                    || beginCleanupEgressToSafeSurface(blueprint, entity))) {
                // Once the final structure is complete, scaffolding is recovered
                // like a player would use it: leave the access network first,
                // then break a reachable base and let the registered scaffold
                // branch come down. Climbing back to each high leaf wastes most
                // of a day and can strand the worker on the block being removed.
                entity.traceConstructionDecision("cleanup-egress:" + cleanupSupport
                        + ":mode=" + entity.constructionMovementMode()
                        + ":movementTarget=" + entity.activeConstructionMovementTarget());
                setActivity(data, citizen, entity, CitizenActivity.DEMOLISHING, "");
                data.touch();
                return;
            }
        }
        TemporaryAccessRecovery collapsedAccess = recoverReachableTemporaryAccessFromBase(
                level, blueprint, entity);
        if (collapsedAccess != null) {
            collectDrops(level, citizen, collapsedAccess.root());
            int demolitionTool = CitizenEquipmentService.toolSlotForWork(citizen);
            if (demolitionTool >= 0) citizen.damageInventoryItem(demolitionTool, 1);
            data.touch();
            setActivity(data, citizen, entity, CitizenActivity.DEMOLISHING, "");
            return;
        }
        if (!blueprint.temporaryFootings().isEmpty()
                && approachTemporaryAccessBaseForCleanup(
                level, data, settlement, blueprint, citizen, entity)) {
            return;
        }
        if (!blueprint.cancellationQueued()
                && BlueprintService.readyForFootingCleanup(level, blueprint)
                && !blueprint.temporaryFootings().isEmpty()) {
            // Final construction cleanup is a one-way retreat through access
            // that already exists. Falling through to the legacy rescue branch
            // could build a second scaffold route merely to dismantle the first,
            // making the footing count grow after the structure was complete.
            // Keep the worker on the current real topology and report the
            // failure; the next scheduler cycle retries another existing
            // descent/base route without placing any new blocks.
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED,
                    "SCAFFOLDING_EGRESS_BLOCKED");
            return;
        }
        if (!blueprint.temporaryFootings().isEmpty()) {
            BlockPos feet = constructionFeetPosition(entity);
            BlockPos selectedCleanup = citizen.targetPosition();
            BlockPos occupiedTemporarySupport = occupiedTemporarySupport(
                    level, blueprint, feet);
            if (occupiedTemporarySupport != null
                    && (selectedCleanup == null
                    || !temporaryFootingDescendsFrom(
                    blueprint, selectedCleanup, occupiedTemporarySupport))) {
                BlueprintPlacement localBranchLeaf = temporaryLeafDescendingFrom(
                        level, blueprint, occupiedTemporarySupport, claimed, entity);
                if (localBranchLeaf != null) {
                    selectedCleanup = localBranchLeaf.position();
                    if (citizen.updateTargetPosition(selectedCleanup)) {
                        CitizenNavigationReservationService.resetStationAfterAccessTopologyChange(
                                level, entity.getUUID());
                        data.touch();
                    }
                } else if (beginTemporaryFootingDescent(blueprint, entity)) {
                    // The resident is already standing on the leaf of this
                    // branch. Backtrack through the persisted parent link before
                    // considering a different branch; otherwise a horizontal
                    // scaffold spur can keep the worker parked on its own leaf
                    // while repeatedly targeting a distant sibling.
                    entity.traceConstructionDecision("cleanup-branch-backtrack:feet=" + feet
                            + ",support=" + occupiedTemporarySupport
                            + ",selected=" + selectedCleanup
                            + ",parent=" + blueprint.temporaryFootingParent(
                            occupiedTemporarySupport));
                    setActivity(data, citizen, entity, CitizenActivity.DEMOLISHING, "");
                    data.touch();
                    return;
                }
            }
            boolean activeCleanupAccess = selectedCleanup != null
                    && blueprint.hasTemporaryFooting(selectedCleanup)
                    && occupiedTemporarySupport != null;
            boolean alreadyAtCleanupWorkHeight = selectedCleanup != null
                    && (canWorkComfortablyFromCurrentPosition(entity, selectedCleanup)
                    || canRecoverOwnedScaffoldingFromCurrentPosition(
                    level, blueprint, entity, selectedCleanup));
            boolean selectedCleanupReachable = selectedCleanup != null
                    && blueprint.hasTemporaryFooting(selectedCleanup)
                    && ConstructionAccessPlanService.preservesResidentEgressAfterRemoval(
                    level, blueprint, selectedCleanup)
                    && (alreadyAtCleanupWorkHeight || !activeCleanupAccess
                    && demolitionTargetReachable(level, entity, selectedCleanup));
            if (selectedCleanupReachable) {
                Item cleanupItem = blueprint.temporaryFootings().get(selectedCleanup);
                if (cleanupItem != null) {
                    retainedCleanup = new BlueprintPlacement(selectedCleanup,
                            Block.byItem(cleanupItem).defaultBlockState(), cleanupItem,
                            BlueprintPlacementMode.STRUCTURE);
                }
            }
            if (!selectedCleanupReachable && selectedCleanup != null
                    && blueprint.hasTemporaryFooting(selectedCleanup)
                    && beginScaffoldingWorkHeightForTarget(
                    level, blueprint, entity, selectedCleanup)) {
                // Stop at the useful height inside the column instead of always
                // descending to its base and constructing another access route.
                setActivity(data, citizen, entity, CitizenActivity.DEMOLISHING, "");
                data.touch();
                return;
            }
            if (!selectedCleanupReachable && !activeCleanupAccess
                    && beginNearbyScaffoldingDescent(blueprint, entity, feet, true)) {
                // Do not remove the roof-entry leaf while the resident is still
                // on a temporary bridge/support or completed roof. Enter the
                // reusable column first. Roof cells are deliberately not
                // registered as temporary support, so requiring an occupied
                // footing here skipped the exact roof-to-scaffold transition
                // that cleanup needs.
                // A resident already standing safely on real ground beside a
                // scaffold must not repeatedly enter and immediately exit that
                // same column; beginScaffoldingDescentAt rejects that no-op at
                // the entity boundary.
                setActivity(data, citizen, entity, CitizenActivity.DEMOLISHING, "");
                data.touch();
                return;
            }
            BlockPos descentEntry = selectedCleanupReachable || activeCleanupAccess
                    ? null : nearestScaffoldingEntry(blueprint, entity, feet);
            if (descentEntry != null && feet.getY() > descentEntry.getY() + 1) {
                BlockPos launch = findReachableDescentLaunch(
                        level, settlement, blueprint, citizen, entity, descentEntry);
                if (launch != null && !nearWorkPosition(entity, launch)) {
                    if (entity.beginLocalConstructionRoute(launch, WALK_SPEED)) {
                        setActivity(data, citizen, entity, CitizenActivity.DEMOLISHING, "");
                        data.touch();
                        return;
                    }
                    if (entity.hasReachablePathTo(launch)) {
                        moveToConstructionPosition(level, data, citizen, entity, blueprint,
                                launch, CitizenActivity.DEMOLISHING);
                        return;
                    }
                }
                // A sloped final roof may end several cells above the surviving
                // scaffold column. If neither the local full-block walk nor
                // vanilla stair/slab navigation can reach an exact drop launch,
                // extend a bounded, reversible scaffold catwalk from the real
                // current roof position toward that owned column. The generic
                // footing planner validates every placed step and retains its
                // parent, so cleanup can reverse the same route afterward.
                placeTemporaryFootingStep(level, data, settlement, blueprint,
                        citizen, entity, descentEntry, CitizenActivity.DEMOLISHING);
                return;
            }
            if (!selectedCleanupReachable && !activeCleanupAccess
                    && beginTemporaryFootingDescent(blueprint, entity)) {
                setActivity(data, citizen, entity, CitizenActivity.DEMOLISHING, "");
                data.touch();
                return;
            }
            if (!selectedCleanupReachable && !activeCleanupAccess) {
                BlueprintPlacement reachableLeaf = nextReachableTemporaryCleanup(
                        level, blueprint, claimed, entity);
                if (reachableLeaf != null
                        && !reachableLeaf.position().equals(selectedCleanup)) {
                    // Do useful, safe leaf-to-root recovery near the resident
                    // instead of keeping the entire worksite locked to one old
                    // unreachable leaf. Parents remain protected by the leaf
                    // filter, so this cannot dismantle the route under a child.
                    retainedCleanup = reachableLeaf;
                    selectedCleanupReachable = true;
                    if (citizen.updateTargetPosition(reachableLeaf.position())) {
                        CitizenNavigationReservationService.resetStationAfterAccessTopologyChange(
                                level, entity.getUUID());
                        data.touch();
                    }
                }
            }
            if (!selectedCleanupReachable && selectedCleanup != null
                    && blueprint.hasTemporaryFooting(selectedCleanup)
                    && beginTemporaryFootingTraversal(level, data, settlement, blueprint,
                    citizen, entity, selectedCleanup, CitizenActivity.DEMOLISHING)) {
                // Prefer the access chain that created this leaf. Building a new
                // rescue column while the old scaffold network is intact wastes
                // material and can leave two mutually blocking cleanup routes.
                return;
            }
            if (!selectedCleanupReachable && selectedCleanup != null
                    && blueprint.hasTemporaryFooting(selectedCleanup)) {
                // Keep the inaccessible older leaf as the objective while a new
                // scaffold route is being built toward it. Otherwise the generic
                // nearest-leaf selector immediately removes the newest root
                // scaffold and loops forever between placing and recovering the
                // same first access cell.
                prepareTemporaryCleanupAccess(level, data, settlement, blueprint,
                        citizen, entity, selectedCleanup);
                return;
            }
        }
        BlueprintPlacement cleanup = retainedCleanup != null ? retainedCleanup
                : nextReachableTemporaryCleanup(level, blueprint, claimed, entity);
        if (cleanup == null && blueprint.temporaryFootings().isEmpty()) {
            cleanup = BlueprintCancellationService.nextCleanup(level, blueprint, claimed);
        }
        if (cleanup == null) {
            // A leaf cannot be removed while it is supporting the resident. Walk
            // back down the persisted exterior footing chain first, then select
            // it again from the lower parent/ground station on the next cycle.
            if (!blueprint.temporaryFootings().isEmpty()
                    && beginTemporaryFootingDescent(blueprint, entity)) {
                setActivity(data, citizen, entity, CitizenActivity.DEMOLISHING, "");
                return;
            }
            if (!blueprint.temporaryFootings().isEmpty()
                    && beginCleanupEgressToSafeSurface(blueprint, entity)) {
                // The last root may sit below a completed roof/eave cell and have
                // no recorded parent or immediately adjacent exit. Walk across
                // the real completed surface to the nearest non-temporary
                // standing cell before trying to remove that root. This is a
                // geometry-driven escape, not a template-specific route.
                setActivity(data, citizen, entity, CitizenActivity.DEMOLISHING, "");
                data.touch();
                return;
            }
            BlueprintPlacement elevatedLeaf = BlueprintCancellationService.nextCleanup(
                    level, blueprint, claimed);
            if (elevatedLeaf != null && blueprint.hasTemporaryFooting(elevatedLeaf.position())
                    && beginTemporaryFootingTraversal(level, data, settlement, blueprint,
                    citizen, entity, elevatedLeaf.position(), CitizenActivity.DEMOLISHING)) {
                // Normal four-block interaction cannot reach every high leaf
                // from the ground. Reuse the recorded scaffold parent chain to
                // climb into a legal work position; the block is still removed
                // only after an ordinary reach check succeeds.
                return;
            }
            if (elevatedLeaf != null && blueprint.hasTemporaryFooting(elevatedLeaf.position())) {
                boolean cleanupTargetChanged = citizen.updateTargetPosition(elevatedLeaf.position());
                if (cleanupTargetChanged) {
                    data.touch();
                    CitizenNavigationReservationService.resetStationAfterAccessTopologyChange(
                            level, entity.getUUID());
                }
                // A disconnected high leaf needs a fresh, climbable access
                // column. Reuse normal construction access planning; this does
                // not remove the target or extend demolition reach.
                prepareTemporaryCleanupAccess(level, data, settlement, blueprint, citizen, entity,
                        elevatedLeaf.position());
                return;
            }
            if (BlueprintCancellationService.nextCleanup(level, blueprint, Set.of()) != null) {
                clear(data, citizen, entity);
                if (assignPersonalDelivery(level, settlement, citizen)) syncHeldItem(citizen, entity);
                return;
            }
            if (!blueprint.temporaryFootings().isEmpty()) {
                // Never try to register an operational building while temporary
                // construction blocks remain. constructionComplete deliberately
                // treats registered footings as satisfied plan cells, so it is
                // not a sufficient finalization guard by itself.
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED,
                        "SCAFFOLDING_EGRESS_BLOCKED");
                return;
            }
            if (!blueprint.cancellationQueued()
                    && !BlueprintService.constructionComplete(level, blueprint)) {
                // Cleanup can reveal final cells that were temporarily occupied
                // by scaffolding. Every old access block is now gone, so the
                // previous route identities no longer describe real paths and
                // must not prevent rebuilding the exposed final cells.
                entity.resetConstructionRoutesAfterAccessTeardown();
                clear(data, citizen, entity);
                data.touch();
                return;
            }
            boolean completed = blueprint.cancellationQueued()
                    ? settlement.removeBlueprint(blueprint.id())
                    : BlueprintService.completeAfterFootingCleanup(level, data, settlement, blueprint).success();
            if (!completed) {
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "CONSTRUCTION_INCOMPLETE");
                return;
            }
            ProfessionAssignmentService.refresh(data, settlement);
            clear(data, citizen, entity);
            if (assignPersonalDelivery(level, settlement, citizen)) syncHeldItem(citizen, entity);
            data.touch();
            return;
        }
        boolean inventoryFull = false;
        int removed = 0;
        BlueprintPlacement next = cleanup;
        while (next != null && removed < DEMOLITION_BATCH_SIZE
                && !inventoryFull && citizen.hasInventoryCapacity()
                && CitizenEquipmentService.missingEquipment(citizen) == null) {
            BlockPos target = next.position();
            if (citizen.updateTargetPosition(target)) {
                data.touch();
                CitizenNavigationReservationService.resetStationAfterAccessTopologyChange(
                        level, entity.getUUID());
            }
            if (!prepareConstructionReach(level, data, settlement, blueprint, citizen, entity,
                    target, CitizenActivity.DEMOLISHING)) return;
            BlueprintPlacement original = BlueprintTemplates.create(blueprint).placementAt(target);
            int cost = original != null && BlueprintPlacementGroups.isPaired(original.state())
                    ? Math.max(1, (int) BlueprintPlacementGroups.parts(BlueprintTemplates.create(blueprint), original).stream()
                    .filter(p -> !level.getBlockState(p.position()).isAir()).count()) : 1;
            int credits = Math.min(2, DEMOLITION_CREDITS.getOrDefault(entity, 0) + 1);
            DEMOLITION_CREDITS.put(entity, credits);
            if (credits < cost) return;
            if (!BlueprintCancellationService.removePlacedBlock(level, blueprint, target, entity)) {
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "PROTECTED_AREA");
                return;
            }
            inventoryFull |= shouldFarmerDeliver(level, settlement, citizen);
            DEMOLITION_CREDITS.put(entity, credits - cost);
            int demolitionTool = CitizenEquipmentService.toolSlotForWork(citizen);
            if (demolitionTool >= 0) citizen.damageInventoryItem(demolitionTool, 1);
            removed += cost;
            data.touch();
            next = nextReachableTemporaryCleanup(level, blueprint, claimed, entity);
            if (next == null && blueprint.temporaryFootings().isEmpty()) {
                next = BlueprintCancellationService.nextCleanup(level, blueprint, claimed);
            }
        }
        if (next == null) {
            if (!blueprint.temporaryFootings().isEmpty()
                    && beginTemporaryFootingDescent(blueprint, entity)) {
                setActivity(data, citizen, entity, CitizenActivity.DEMOLISHING, "");
                data.touch();
                return;
            }
            if (!blueprint.temporaryFootings().isEmpty()
                    && beginCleanupEgressToSafeSurface(blueprint, entity)) {
                setActivity(data, citizen, entity, CitizenActivity.DEMOLISHING, "");
                data.touch();
                return;
            }
            if (BlueprintCancellationService.nextCleanup(level, blueprint, Set.of()) != null) {
                clear(data, citizen, entity);
                if (assignPersonalDelivery(level, settlement, citizen)) syncHeldItem(citizen, entity);
                data.touch();
                return;
            }
            if (!blueprint.temporaryFootings().isEmpty()) {
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED,
                        "SCAFFOLDING_EGRESS_BLOCKED");
                data.touch();
                return;
            }
            if (!blueprint.cancellationQueued()
                    && !BlueprintService.constructionComplete(level, blueprint)) {
                clear(data, citizen, entity);
                data.touch();
                return;
            }
            boolean completed = blueprint.cancellationQueued()
                    ? settlement.removeBlueprint(blueprint.id())
                    : BlueprintService.completeAfterFootingCleanup(level, data, settlement, blueprint).success();
            if (!completed) {
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "CONSTRUCTION_INCOMPLETE");
                data.touch();
                return;
            }
            ProfessionAssignmentService.refresh(data, settlement);
        }
        if (next != null && !inventoryFull && citizen.hasInventoryCapacity()
                && CitizenEquipmentService.missingEquipment(citizen) == null) {
            if (citizen.updateTargetPosition(next.position())) {
                CitizenNavigationReservationService.resetStationAfterAccessTopologyChange(
                        level, entity.getUUID());
            }
        } else {
            clear(data, citizen, entity);
            if (assignPersonalDelivery(level, settlement, citizen)) syncHeldItem(citizen, entity);
        }
        data.touch();
    }

    /**
     * Temporary access belongs to the worksite rather than to the finished
     * structure. By design, residents recover every registered footing directly
     * into their backpack without path, reach, sightline, station, protection,
     * or leaf-order checks. World updates are deferred until the whole set has
     * gone so vanilla scaffold collapse cannot create duplicate item drops.
     */
    private static boolean recoverAllTemporaryFootingsDirectly(
            ServerLevel level, BlueprintRecord blueprint,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        return recoverAllTemporaryFootingsDirectly(
                level, blueprint, citizen, entity, true);
    }

    private static boolean recoverAllTemporaryFootingsDirectly(
            ServerLevel level, BlueprintRecord blueprint,
            CitizenRecord citizen, SettlementCitizenEntity entity,
            boolean clearAssignedTarget
    ) {
        if(ConstructionSiteController.groundWork(blueprint))
            return recoverGroundWorkScaffold(level,blueprint,citizen,entity,clearAssignedTarget);
        Map<Item, Integer> recovered = new LinkedHashMap<>();
        List<BlockPos> removedPositions = new ArrayList<>();
        boolean changed = false;
        for (Map.Entry<BlockPos, Item> entry : new ArrayList<>(
                blueprint.temporaryFootings().entrySet())) {
            BlockPos position = entry.getKey();
            Item item = entry.getValue();
            Block expected = Block.byItem(item);
            if (!level.getBlockState(position).is(expected)) {
                changed |= blueprint.removeTemporaryFooting(position);
                continue;
            }
            if (!level.setBlock(position, Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) continue;
            changed |= blueprint.removeTemporaryFooting(position);
            recovered.merge(item, 1, Integer::sum);
            removedPositions.add(position.immutable());
        }
        if (!changed) return false;

        for (BlockPos position : removedPositions) {
            level.updateNeighborsAt(position, Blocks.AIR);
        }
        for (Map.Entry<Item, Integer> entry : recovered.entrySet()) {
            int remainingCount = entry.getValue();
            int stackLimit = new ItemStack(entry.getKey()).getMaxStackSize();
            while (remainingCount > 0) {
                int batch = Math.min(stackLimit, remainingCount);
                ItemStack remaining = citizen.insertIntoInventory(
                        new ItemStack(entry.getKey(), batch));
                if (!remaining.isEmpty()) {
                    Block.popResource(level, entity.blockPosition(), remaining);
                }
                remainingCount -= batch;
            }
        }
        if (clearAssignedTarget) citizen.updateTargetPosition(null);
        entity.finishConstructionMovement();
        entity.finishConstructionAccessRetreat();
        entity.traceConstructionDecision("direct-footing-recovery:" + recovered);
        return true;
    }

    /** Old accidental area scaffolds are removed by a resident at actual reach, never on upgrade. */
    private static boolean recoverGroundWorkScaffold(ServerLevel level,BlueprintRecord site,CitizenRecord citizen,
            SettlementCitizenEntity entity,boolean clearAssignedTarget) {
        var data=SettlementSavedData.get(level);
        var settlement=data.settlements().stream().filter(s->s.blueprint(site.id())==site).findFirst().orElse(null);
        if(settlement==null)return false;
        boolean changed=false;
        for(var at:List.copyOf(site.temporaryFootings().keySet()))if(level.hasChunkAt(at)
                && !ConstructionSiteController.recordedSupport(level,site,at))changed|=site.removeTemporaryFooting(at);
        if(changed)data.touch();
        // A connected scaffold may collapse when its base is broken. Keep all occupants safe.
        for(var at:site.temporaryFootings().keySet())if(level.hasChunkAt(at)
                && !level.getEntitiesOfClass(LivingEntity.class,new AABB(at).expandTowards(0,2,0),e->e.isAlive()&&!e.isSpectator()).isEmpty())return false;
        for(var at:site.temporaryFootings().keySet().stream().sorted(Comparator.comparingDouble(p->p.distSqr(entity.blockPosition()))).toList()) {
            if(site.temporaryFootingItem(at)!=Items.SCAFFOLDING || !level.hasChunkAt(at)
                    || !settlement.contains(at) || settlement.decorations().forbidden(at) || settlement.community().danger(at)
                    || !canRecoverOwnedScaffoldingFromCurrentPosition(level,site,entity,at))continue;
            var before=level.getBlockState(at);
            if(!CommonHooks.canEntityDestroy(level,at,entity) || !level.getBlockState(at).equals(before))continue;
            if(!ResidentBlockDrops.destroy(level,at,entity))continue;
            site.removeTemporaryFooting(at);
            if(clearAssignedTarget)citizen.updateTargetPosition(null);
            entity.swing(net.minecraft.world.InteractionHand.MAIN_HAND);data.touch();return true;
        }
        return changed;
    }

    /**
     * A cleanup leaf reaches this method only after both normal demolition
     * stations and its persisted parent chain have proved unusable. Go directly
     * to a reachable scaffold base instead of spending thousands of cycles on
     * the same nominal ground path before constructing access.
     */
    private static void prepareTemporaryCleanupAccess(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord blueprint, CitizenRecord citizen,
            SettlementCitizenEntity entity, BlockPos target
    ) {
        if (entity.hasReadyVerticalPillarSupport()) {
            placeTemporaryFootingStep(level, data, settlement, blueprint, citizen,
                    entity, target, CitizenActivity.DEMOLISHING);
            return;
        }
        BlockPos currentSupport = occupiedTemporarySupport(
                level, blueprint, constructionFeetPosition(entity));
        if (currentSupport != null) {
            if (hasShortFootingRouteFromCurrentPosition(
                    level, settlement, blueprint, citizen, entity, target)) {
                placeTemporaryFootingStep(level, data, settlement, blueprint, citizen,
                        entity, target, CitizenActivity.DEMOLISHING);
                return;
            }
            if (beginTemporaryFootingDescent(blueprint, entity)) {
                setActivity(data, citizen, entity, CitizenActivity.DEMOLISHING, "");
                return;
            }
        }
        BlockPos footingBase = CitizenNavigationReservationService.stationFor(
                level, entity.getUUID(), level.getGameTime());
        if (footingBase == null || !entity.canStandAtPosition(footingBase)
                || !insideConstructionAccessBounds(BlueprintTemplates.create(blueprint), footingBase)
                || footingBase.distSqr(target) > 256.0D) {
            // Cleanup is a rescue route from the resident's real position to an
            // already completed, often disconnected support. A target-nearest
            // base can sit behind the finished wall and be selected again after
            // every path cooldown. Prefer the nearest resident-reachable base,
            // while still requiring the same bounded full scaffold-route proof.
            footingBase = findFootingBasePosition(
                    level, settlement, blueprint, entity, target, true);
        }
        if (footingBase == null) {
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED,
                    "CONSTRUCTION_POSITION_UNREACHABLE");
            return;
        }
        if (!CitizenNavigationReservationService.claimStation(
                level, entity.getUUID(), footingBase, entity.blockPosition(), level.getGameTime())) {
            setActivity(data, citizen, entity, CitizenActivity.DEMOLISHING, "WORK_STATION_BUSY");
            return;
        }
        if (!nearWorkPosition(entity, footingBase)) {
            // The base was already proved reachable by findFootingBasePosition.
            // Routing this cleanup move through the finished building's nominal
            // entrance can replace that valid path with an unrelated, blocked
            // detour and repeatedly expire the resident's own station claim.
            // Navigate to the real scaffold base directly; ordinary navigation
            // still has to pass through any physical door that is actually on
            // that path.
            moveToConstructionPosition(level, data, citizen, entity,
                    footingBase, CitizenActivity.DEMOLISHING);
            return;
        }
        placeTemporaryFootingStep(level, data, settlement, blueprint, citizen,
                entity, target, CitizenActivity.DEMOLISHING);
    }

    private static boolean beginScaffoldingWorkHeightForTarget(
            ServerLevel level, BlueprintRecord blueprint,
            SettlementCitizenEntity entity, BlockPos target
    ) {
        return blueprint.temporaryFootings().entrySet().stream()
                .filter(entry -> entry.getValue() == Items.SCAFFOLDING)
                .map(Map.Entry::getKey)
                .filter(position -> !position.equals(target))
                .filter(position -> level.getBlockState(position).is(Blocks.SCAFFOLDING))
                .filter(position -> constructionReachGeometry(position, target))
                // A player climbing inside one scaffold column can look through
                // that open column and break the registered scaffold immediately
                // above. The ordinary COLLIDER ray reports the lower scaffold as
                // the first hit, so requiring a normal solid-block sightline here
                // incorrectly rejects the column that is already in front of the
                // resident. Side approaches keep the full visibility check.
                .filter(position -> position.getX() == target.getX()
                        && position.getZ() == target.getZ()
                        || constructionStationInRange(level, position, target, entity))
                .sorted(Comparator
                        .comparingInt((BlockPos position) -> Math.abs(
                                position.getY() - constructionFeetPosition(entity).getY()))
                        .thenComparingDouble(position -> position.distSqr(entity.blockPosition())))
                .anyMatch(position -> entity.beginScaffoldingWorkHeight(position, WALK_SPEED));
    }

    private static void herd(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        BuildingRecord pasture = workplace(settlement, citizen, BuildingType.PASTURE_AREA);
        Entity targetEntity = citizen.targetItemEntityId() == null ? null : level.getEntity(citizen.targetItemEntityId());
        if (pasture == null || com.hkin.exiledsettlement.region.PastureGroundSafety.active(pasture.region()) || !(targetEntity instanceof Animal animal)
                || !com.hkin.exiledsettlement.region.ProductionRegionService.matchesAnimal(pasture,animal)) {
            clear(data, citizen, entity);
            return;
        }
        if (!animal.isAlive()) {
            collectVisibleHuntingDrops(level, citizen, entity);
            clear(data, citizen, entity);
            if (assignPersonalDelivery(level, settlement, citizen)) data.touch();
            return;
        }
        List<Animal> residents = level.getEntitiesOfClass(Animal.class, buildingArea(pasture, 0),
                resident -> resident.isAlive()
                        && insidePastureInterior(pasture,resident.blockPosition()) && com.hkin.exiledsettlement.region.ProductionRegionService.matchesAnimal(pasture,resident));
        boolean cull = com.hkin.exiledsettlement.region.ProductionRegionService.animalSurplus(pasture,residents,animal)
                && !animal.isBaby() && !animal.hasCustomName() && !animal.isLeashed()
                && (!(animal instanceof TamableAnimal tamable) || !tamable.isTame());
        if (!cull && (!(animal instanceof Sheep sheep) || sheep.isSheared())) {
            clear(data, citizen, entity);
            return;
        }
        if (!near(entity, animal.blockPosition())) {
            move(data, citizen, entity, animal.blockPosition(), CitizenActivity.HERDING);
            return;
        }
        if (!workCycleReady(level, citizen)) return;
        ItemStack remaining = ItemStack.EMPTY;
        if (cull) {
            animal.hurt(level.damageSources().mobAttack(entity), 20.0F);
            entity.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            data.touch();
            return;
        } else if (animal instanceof Sheep sheep) {
            sheep.setSheared(true);
            ItemStack wool = new ItemStack(Items.WHITE_WOOL, 1 + level.random.nextInt(3));
            remaining = citizen.insertIntoInventory(wool);
            if (!remaining.isEmpty()) Block.popResource(level, pasture.position(), remaining);
            entity.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
        clear(data, citizen, entity);
        Set<UUID> claimed = settlement.citizens().stream()
                .filter(other -> !other.id().equals(citizen.id()))
                .map(CitizenRecord::targetItemEntityId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (remaining.isEmpty() && citizen.hasInventoryCapacity()
                && assignHerd(level, settlement, citizen, claimed)) {
            data.touch();
            return;
        }
        if (assignPersonalDelivery(level, settlement, citizen)) {
            syncHeldItem(citizen, entity);
            data.touch();
        }
    }

    /**
     * Recovers one persisted temporary access tree from its reachable base.
     * Scaffolding is deliberately dismantled from the bottom, matching the
     * normal Minecraft interaction: breaking the grounded support collapses the
     * whole column/catwalk instead of sending the worker back to every high
     * leaf. Rare solid bridge cells belong to the same recorded access tree and
     * are cleared in root-to-leaf order during that same operation, so a mixed
     * scaffold route cannot strand a handful of floating temporary blocks.
     * Only the base must be visible inside the ordinary four-block reach.
     */
    @Nullable
    private static TemporaryAccessRecovery recoverReachableTemporaryAccessFromBase(
            ServerLevel level, BlueprintRecord blueprint, SettlementCitizenEntity entity
    ) {
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        BlockPos feet = constructionFeetPosition(entity);
        boolean safelyOutsideTemporaryAccess = occupiedTemporarySupport(
                level, blueprint, feet) == null
                && (ConstructionSiteController.groundWork(blueprint)?entity.canStandAtPosition(feet)
                :feet.getY() <= template.minCorner().getY() + 1);
        List<BlockPos> candidateRoots = temporaryAccessPhysicalBases(level, blueprint);
        List<BlockPos> roots = candidateRoots.stream()
                // Breaking the physical bottom of scaffolding collapses its
                // column. Do that only after the resident has reached ordinary
                // ground; graph-root solid footings retain the older egress
                // proof below.
                .filter(position -> blueprint.temporaryFootingItem(position) != Items.SCAFFOLDING
                        || safelyOutsideTemporaryAccess)
                .filter(position -> canRecoverTemporaryAccessBase(
                        level, blueprint, entity, position))
                .filter(position -> blueprint.temporaryFootingItem(position) == Items.SCAFFOLDING
                        && safelyOutsideTemporaryAccess
                        || ConstructionAccessPlanService.preservesResidentEgressAfterRemoval(
                        level, blueprint, position))
                .sorted(Comparator
                        .comparing((BlockPos position) ->
                                blueprint.temporaryFootingItem(position) != Items.SCAFFOLDING)
                        .thenComparingDouble(position ->
                                distanceToBlockSqr(entity.getEyePosition(), position)))
                .toList();
        for (BlockPos root : roots) {
            boolean scaffoldBase = blueprint.temporaryFootingItem(root) == Items.SCAFFOLDING;
            List<BlockPos> tree = scaffoldBase
                    ? connectedOwnedScaffolding(level, blueprint, root)
                    : blueprint.temporaryFootings().keySet().stream()
                    .filter(candidate -> temporaryFootingDescendsFrom(
                            blueprint, candidate, root))
                    .toList();
            if (tree.isEmpty()
                    || tree.stream().anyMatch(position ->
                    !level.getBlockState(position).is(
                            Block.byItem(blueprint.temporaryFootingItem(position))))
                    || tree.stream().anyMatch(position -> entity.getBoundingBox()
                    .intersects(new AABB(position)))) continue;

            int removed = 0;
            List<BlockPos> rootToLeaf = tree.stream()
                    .sorted(Comparator.comparingInt(
                            blueprint::temporaryFootingOrder))
                    .toList();
            // Destroy the reachable physical base first. Vanilla immediately
            // collapses the scaffold column and the next scheduler pass
            // reconciles its vanished records. Solid access trees still clear
            // their persisted root-to-leaf descendants through the same
            // protected block-interaction path.
            for (BlockPos position : rootToLeaf) {
                if (!BlueprintCancellationService.removePlacedBlock(
                        level, blueprint, position, entity)) break;
                removed++;
            }
            if (removed > 0) return new TemporaryAccessRecovery(root.immutable(), removed);
        }
        return null;
    }

    private static List<BlockPos> connectedOwnedScaffolding(
            ServerLevel level, BlueprintRecord blueprint, BlockPos root
    ) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new LinkedHashSet<>();
        queue.add(root.immutable());
        visited.add(root.immutable());
        while (!queue.isEmpty() && visited.size() <= 512) {
            BlockPos current = queue.removeFirst();
            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction).immutable();
                if (visited.contains(next)
                        || !blueprint.hasTemporaryFooting(next)
                        || blueprint.temporaryFootingItem(next) != Items.SCAFFOLDING
                        || !level.getBlockState(next).is(Blocks.SCAFFOLDING)) continue;
                visited.add(next);
                queue.addLast(next);
            }
        }
        return visited.stream()
                .sorted(Comparator
                        .comparing((BlockPos position) -> !position.equals(root))
                        .thenComparingInt(blueprint::temporaryFootingOrder))
                .toList();
    }

    private static boolean canRecoverTemporaryAccessBase(
            ServerLevel level, BlueprintRecord blueprint,
            SettlementCitizenEntity entity, BlockPos target
    ) {
        if (blueprint.temporaryFootingItem(target) == Items.SCAFFOLDING) {
            return canRecoverOwnedScaffoldingFromCurrentPosition(
                    level, blueprint, entity, target);
        }
        return canConstructFromCurrentPosition(entity, target);
    }

    /**
     * Once the resident has reached ordinary ground, approach a persisted access
     * root and dismantle it from the bottom. This deliberately uses only normal
     * ground navigation and a legal four-block interaction station: cleanup must
     * not choose a high leaf and rebuild/climb another scaffold after the worker
     * has already escaped the structure.
     */
    private static boolean approachTemporaryAccessBaseForCleanup(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord blueprint, CitizenRecord citizen,
            SettlementCitizenEntity entity
    ) {
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        BlockPos feet = constructionFeetPosition(entity);
        if (occupiedTemporarySupport(level, blueprint, feet) != null
                || !ConstructionSiteController.groundWork(blueprint) && feet.getY() > template.minCorner().getY() + 1) return false;

        List<BlockPos> roots = temporaryAccessPhysicalBases(level, blueprint).stream()
                .sorted(Comparator
                        .comparingInt((BlockPos position) -> position.getY())
                        .thenComparingDouble(position -> position.distSqr(feet))
                        .thenComparingLong(BlockPos::asLong))
                .toList();
        for (BlockPos root : roots) {
            BlockPos station = constructionWorkPositions(
                    level, entity, root, settlement, blueprint).stream()
                    .filter(position -> ConstructionSiteController.groundWork(blueprint) || position.getY() <= template.minCorner().getY() + 1)
                    .filter(position -> occupiedTemporarySupport(
                            level, blueprint, position) == null)
                    .filter(entity::hasReachablePathTo)
                    .findFirst().orElse(null);
            if (station == null) continue;
            if (!CitizenNavigationReservationService.claimStation(
                    level, entity.getUUID(), station, entity.blockPosition(), level.getGameTime())) {
                continue;
            }
            if (citizen.updateTargetPosition(root)) data.touch();
            entity.traceConstructionDecision("cleanup-root-approach:" + root + "<-" + station);
            if (!nearWorkPosition(entity, station)) {
                moveToConstructionPosition(level, data, citizen, entity,
                        station, CitizenActivity.DEMOLISHING);
            } else {
                // The root should become directly recoverable on the next work
                // refresh. Do not fall through to high-leaf selection now.
                setActivity(data, citizen, entity, CitizenActivity.DEMOLISHING, "");
                data.touch();
            }
            return true;
        }
        return false;
    }

    /**
     * Returns real removable bases rather than construction-history roots.
     * Scaffold blocks can be first placed from a roof or catwalk, so a null
     * persisted parent does not imply that the block is physically at the
     * bottom. For scaffolding the lowest contiguous live cell in each column is
     * the base a player would break; solid supports continue to use the recorded
     * root because they do not have vanilla column-collapse behaviour.
     */
    private static List<BlockPos> temporaryAccessPhysicalBases(
            ServerLevel level, BlueprintRecord blueprint
    ) {
        return blueprint.temporaryFootings().keySet().stream()
                .filter(position -> level.getBlockState(position).is(
                        Block.byItem(blueprint.temporaryFootingItem(position))))
                .filter(position -> blueprint.temporaryFootingItem(position) == Items.SCAFFOLDING
                        ? !level.getBlockState(position.below()).is(Blocks.SCAFFOLDING)
                        : blueprint.temporaryFootingParent(position) == null)
                .map(BlockPos::immutable)
                .distinct()
                .toList();
    }

    @Nullable
    private static BlueprintPlacement temporaryLeafDescendingFrom(
            ServerLevel level, BlueprintRecord blueprint, BlockPos ancestor,
            Set<BlockPos> claimed, SettlementCitizenEntity entity
    ) {
        return blueprint.temporaryFootings().entrySet().stream()
                .filter(entry -> !claimed.contains(entry.getKey()))
                .filter(entry -> !blueprint.hasTemporaryFootingChild(entry.getKey()))
                .filter(entry -> !entry.getKey().equals(ancestor))
                .filter(entry -> level.getBlockState(entry.getKey())
                        .is(Block.byItem(entry.getValue())))
                .filter(entry -> temporaryFootingDescendsFrom(
                        blueprint, entry.getKey(), ancestor))
                .sorted(Comparator
                        .comparingDouble((Map.Entry<BlockPos, Item> entry) ->
                                entry.getKey().distSqr(entity.blockPosition()))
                        .thenComparing(Comparator.comparingInt(
                                (Map.Entry<BlockPos, Item> entry) ->
                                        blueprint.temporaryFootingOrder(entry.getKey())).reversed()))
                .map(entry -> new BlueprintPlacement(entry.getKey(),
                        Block.byItem(entry.getValue()).defaultBlockState(), entry.getValue(),
                        BlueprintPlacementMode.STRUCTURE))
                .findFirst().orElse(null);
    }

    private static boolean temporaryFootingDescendsFrom(
            BlueprintRecord blueprint, BlockPos candidate, BlockPos ancestor
    ) {
        BlockPos cursor = candidate;
        int remaining = blueprint.temporaryFootings().size() + 1;
        while (cursor != null && remaining-- > 0) {
            if (cursor.equals(ancestor)) return true;
            cursor = blueprint.temporaryFootingParent(cursor);
        }
        return false;
    }

    private record TemporaryAccessRecovery(BlockPos root, int blocks) {
    }

    private static void hunt(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        BuildingRecord lodge = workplace(settlement, citizen, BuildingType.HUNTING_LODGE);
        Entity target = citizen.targetItemEntityId() == null ? null : level.getEntity(citizen.targetItemEntityId());
        if (lodge == null) {
            clear(data, citizen, entity);
            return;
        }
        if (!CitizenEquipmentService.hasWeapon(citizen)) {
            clear(data, citizen, entity);
            return;
        }
        if (!(target instanceof Animal animal) || !animal.isAlive()) {
            BlockPos dropPosition = citizen.targetPosition();
            if (dropPosition != null && !near(entity, dropPosition)) {
                move(data, citizen, entity, dropPosition, CitizenActivity.HUNTING);
                return;
            }
            boolean inventoryFull = collectVisibleHuntingDrops(level, citizen, entity);
            clear(data, citizen, entity);
            Set<UUID> claimed = settlement.citizens().stream()
                    .filter(other -> !other.id().equals(citizen.id()))
                    .map(CitizenRecord::targetItemEntityId)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            if (!inventoryFull && citizen.hasInventoryCapacity()
                    && assignHunt(level, settlement, citizen, claimed)) {
                data.touch();
                return;
            }
            if (assignPersonalDelivery(level, settlement, citizen)) {
                syncHeldItem(citizen, entity);
                data.touch();
            }
            return;
        }
        if (protectedFromHunting(level, settlement, animal)) {
            clear(data, citizen, entity);
            return;
        }
        int weaponSlot = CitizenEquipmentService.toolSlotForWork(citizen);
        boolean bow = weaponSlot >= 0 && citizen.inventoryItem(weaponSlot).getItem()
                instanceof net.minecraft.world.item.BowItem;
        double distance = entity.distanceToSqr(animal);
        if (bow && distance <= 144.0D && entity.hasLineOfSight(animal)) {
            if (!workCycleReady(level, citizen)) return;
            int arrowSlot = citizen.findInventorySlot(stack -> stack.is(Items.ARROW));
            if (arrowSlot < 0) { clear(data, citizen, entity); return; }
            net.minecraft.world.entity.projectile.Arrow projectile =
                    net.minecraft.world.entity.EntityType.ARROW.create(level);
            if (projectile == null) { clear(data, citizen, entity); return; }
            projectile.setOwner(entity);
            projectile.setPos(entity.getX(), entity.getEyeY() - 0.1D, entity.getZ());
            Vec3 delta = animal.getEyePosition().subtract(projectile.position());
            projectile.shoot(delta.x, delta.y, delta.z, 1.6F, 2.0F);
            level.addFreshEntity(projectile);
            citizen.extractInventoryItem(stack -> stack.is(Items.ARROW), 1);
            citizen.damageInventoryItem(weaponSlot, 1);
            entity.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            data.touch();
            return;
        }
        if (!near(entity, animal.blockPosition())) {
            move(data, citizen, entity, animal.blockPosition(), CitizenActivity.HUNTING);
            return;
        }
        animal.hurt(level.damageSources().mobAttack(entity), 6.0F);
        if (weaponSlot >= 0) {
            citizen.damageInventoryItem(weaponSlot, 1);
            if (CitizenEquipmentService.missingEquipment(citizen) != null) {
                clear(data, citizen, entity);
                return;
            }
        }
        if (citizen.updateTargetPosition(animal.blockPosition())) data.touch();
    }

    /** Hunting loot is a physical pickup near the hunter, never a remote harvest at the target coordinate. */
    private static boolean collectVisibleHuntingDrops(
            ServerLevel level, CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        boolean inventoryFull = false;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(2.5D),
                item -> item.isAlive() && item.getOwner() == null && item.getTarget() == null
                        && entity.distanceToSqr(item) <= 6.25D && entity.hasLineOfSight(item))) {
            ItemStack remaining = citizen.insertIntoInventory(item.getItem());
            if (remaining.isEmpty()) item.discard();
            else {
                item.setItem(remaining);
                inventoryFull = true;
            }
        }
        return inventoryFull;
    }

    private static void capture(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        BuildingRecord pasture = settlement.building(citizen.targetBuildingId());
        Entity target = citizen.targetItemEntityId() == null ? null : level.getEntity(citizen.targetItemEntityId());
        if(com.hkin.exiledsettlement.region.PastureGroundSafety.capture(level,settlement,citizen,entity))return;
        if (!usable(pasture, BuildingType.PASTURE_AREA) || !(target instanceof Animal animal)
                || !animal.isAlive()) {
            clear(data, citizen, entity);
            return;
        }
        entity.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.LEAD));
        if (!animal.isLeashed()) {
            if (!near(entity, animal.blockPosition())) {
                move(data, citizen, entity, animal.blockPosition(), CitizenActivity.CAPTURING);
                return;
            }
            animal.setLeashedTo(entity, true);
        }
        BlockPos destination = citizen.targetPosition() == null ? pasture.position() : citizen.targetPosition();
        if (!nearWorkPosition(entity, destination)
                || !insidePastureInterior(pasture, entity.blockPosition())
                || !insidePastureInterior(pasture, animal.blockPosition())) {
            move(data, citizen, entity, destination, CitizenActivity.CAPTURING);
            return;
        }
        animal.dropLeash(true, false);
        com.hkin.exiledsettlement.region.PastureResidentService.capture(level,settlement,pasture,animal);
        clear(data, citizen, entity);
    }

    @Nullable
    public static BlockPos pastureCaptureDestination(
            ServerLevel level, BuildingRecord pasture, BlockPos animalPosition
    ) {
        if(pasture.region()!=null)return pasture.region().cells.values().stream()
                .filter(c->c.use()==com.hkin.exiledsettlement.region.ProductionRegion.Use.PASTURE && pasture.region().readyPasture.contains(c.ground().asLong()))
                .map(c->c.ground().above()).filter(p->com.hkin.exiledsettlement.blueprint.FarmEarthworkPlan.support(level,p.below()) && level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir())
                .min(Comparator.comparingDouble(p->p.distSqr(animalPosition))).orElse(null);
        BuildingBlueprintTemplate template = BlueprintTemplates.createForBuilding(pasture);
        List<com.hkin.exiledsettlement.blueprint.BlueprintEntrance> entrances = template.entrances().stream()
                .sorted(Comparator.comparingDouble(entrance -> entrance.position().distSqr(animalPosition)))
                .toList();
        for (var entrance : entrances) {
            Direction inward = entrance.outward().getOpposite();
            BlockPos base = entrance.position().relative(inward, 2);
            for (int lateral : new int[]{0, 1, -1, 2, -2}) {
                Direction side = inward.getClockWise();
                BlockPos horizontal = base.relative(side, lateral);
                BlockPos standing = animalStandingPosition(level, horizontal, entrance.position().getY());
                if (standing != null && insidePastureInterior(pasture, standing)) return standing;
            }
        }
        BlockPos center = new BlockPos(
                (pasture.minCorner().getX() + pasture.maxCorner().getX()) / 2,
                pasture.position().getY(),
                (pasture.minCorner().getZ() + pasture.maxCorner().getZ()) / 2);
        return animalStandingPosition(level, center, pasture.position().getY());
    }

    private static boolean insidePastureInterior(BuildingRecord pasture, BlockPos position) {
        if(pasture.region()!=null){var c=pasture.region().cell(position);return pasture.region().contains(position) && c.use()==com.hkin.exiledsettlement.region.ProductionRegion.Use.PASTURE && pasture.region().readyPasture.contains(c.ground().asLong());}
        return position.getX() > pasture.minCorner().getX()
                && position.getX() < pasture.maxCorner().getX()
                && position.getZ() > pasture.minCorner().getZ()
                && position.getZ() < pasture.maxCorner().getZ();
    }

    private static void fetchSawmillInput(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        BuildingRecord sawmill = workplace(settlement, citizen, BuildingType.SAWMILL);
        BuildingRecord warehouse = settlement.building(citizen.targetBuildingId());
        if (sawmill == null || !usable(warehouse, BuildingType.WAREHOUSE)) {
            releaseSupplyStation(level, entity);
            clear(data, citizen, entity);
            return;
        }
        Item input = WarehouseInventoryService.summarize(level, warehouse).keySet().stream()
                .filter(CitizenWorkService::isCarpenterInput)
                .min(Comparator.comparing(item -> net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(item).toString()))
                .orElse(WarehouseInventoryService.hasCreativeSupply(level, warehouse) ? Items.OAK_LOG : null);
        if (input == null) {
            releaseSupplyStation(level, entity);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "SAWMILL_LOGS_MISSING");
            return;
        }
        BlockPos station = WarehouseInventoryService.reserveExtractionPosition(level, warehouse, entity, input);
        if (station == null) {
            waitForSupplyStation(level, data, settlement, citizen, entity, warehouse,
                    true, CitizenActivity.FETCHING_SAWMILL_INPUT);
            return;
        }
        if (!nearWorkPosition(entity, station)) {
            moveToWarehouseStation(level, data, citizen, entity, station,
                    CitizenActivity.FETCHING_SAWMILL_INPUT);
            return;
        }
        if (!WarehouseInventoryService.canInteract(level, warehouse, entity)) {
            abandonSupplyStation(level, entity);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "WAREHOUSE_INACCESSIBLE");
            return;
        }
        int capacity = Math.min(input.getDefaultMaxStackSize(),
                citizen.inventoryCapacityFor(new ItemStack(input)));
        if (capacity <= 0) {
            releaseSupplyStation(level, entity);
            citizen.returnToSawmill();
            data.touch();
            return;
        }
        ItemStack extracted = WarehouseInventoryService.extractItemForResident(
                level, warehouse, entity, input, capacity);
        if (extracted.isEmpty() && WarehouseInventoryService.hasCreativeSupply(level, warehouse)) {
            extracted = new ItemStack(input, capacity);
        }
        if (extracted.isEmpty()) {
            releaseSupplyStation(level, entity);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "SAWMILL_LOGS_MISSING");
            return;
        }
        ItemStack remaining = citizen.insertIntoInventory(extracted);
        if (!remaining.isEmpty()) WarehouseInventoryService.insertForResident(level, warehouse, entity, remaining);
        releaseSupplyStation(level, entity);
        syncHeldItem(citizen, entity);
        if (!shouldContinueSawmillLoading(level, warehouse, citizen)) {
            citizen.returnToSawmill();
        }
        data.touch();
    }

    private static boolean shouldContinueSawmillLoading(
            ServerLevel level, BuildingRecord warehouse, CitizenRecord citizen
    ) {
        int carriedInputs = citizen.inventory().stream()
                .filter(stack -> isCarpenterInput(stack.getItem()))
                .mapToInt(ItemStack::getCount)
                .sum();
        if (carriedInputs >= SAWMILL_LOG_BATCH_LIMIT) return false;
        if (WarehouseInventoryService.hasCreativeSupply(level, warehouse)) {
            return citizen.inventoryCapacityFor(new ItemStack(Items.OAK_LOG)) > 0
                    && citizen.inventoryCapacityFor(new ItemStack(Items.OAK_PLANKS, 5)) >= 5;
        }
        return WarehouseInventoryService.summarize(level, warehouse).keySet().stream()
                .filter(CitizenWorkService::isCarpenterInput)
                .anyMatch(item -> citizen.inventoryCapacityFor(new ItemStack(item)) > 0
                        && carpenterOutputFits(citizen, item));
    }

    private static void sawLogs(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        BuildingRecord sawmill = workplace(settlement, citizen, BuildingType.SAWMILL);
        if (sawmill == null) {
            clear(data, citizen, entity);
            return;
        }
        BlockPos station = WarehouseInventoryService.reserveInteractionPosition(level, sawmill, entity);
        if (station == null) {
            waitForWarehouseStation(level, data, citizen, entity, sawmill, CitizenActivity.SAWING);
            return;
        }
        if (!nearWorkPosition(entity, station)) {
            moveToWarehouseStation(level, data, citizen, entity, station, CitizenActivity.SAWING);
            return;
        }
        if (!workCycleReady(level, citizen)) {
            setActivity(data, citizen, entity, CitizenActivity.SAWING, "");
            return;
        }
        int inputSlot = citizen.findInventorySlot(stack -> SAWMILL_RECIPES.containsKey(stack.getItem()));
        CarpenterRecipes.Recipe carpenterRecipe = inputSlot < 0
                ? CarpenterRecipes.select(level, settlement, citizen) : null;
        if (inputSlot < 0 && carpenterRecipe == null) {
            WarehouseInventoryService.releaseInteractionPosition(level, entity);
            clear(data, citizen, entity);
            if (assignPersonalDelivery(level, settlement, citizen)) syncHeldItem(citizen, entity);
            data.touch();
            return;
        }
        if (carpenterRecipe != null) {
            if (!CarpenterRecipes.craft(citizen, carpenterRecipe)) return;
            int axeSlot = CitizenEquipmentService.toolSlotForWork(citizen);
            if (axeSlot >= 0) citizen.damageInventoryItem(axeSlot, 1);
            syncHeldItem(citizen, entity);
            if (CitizenEquipmentService.missingEquipment(citizen) != null
                    || (citizen.findInventorySlot(stack -> SAWMILL_RECIPES.containsKey(stack.getItem())) < 0
                    && CarpenterRecipes.select(level, settlement, citizen) == null)) {
                WarehouseInventoryService.releaseInteractionPosition(level, entity);
                clear(data, citizen, entity);
                if (assignPersonalDelivery(level, settlement, citizen)) syncHeldItem(citizen, entity);
            }
            data.touch();
            return;
        }
        Item input = citizen.inventoryItem(inputSlot).getItem();
        Item output = SAWMILL_RECIPES.get(input);
        if (output == null || citizen.inventoryCapacityFor(new ItemStack(output, 5)) < 5) {
            WarehouseInventoryService.releaseInteractionPosition(level, entity);
            clear(data, citizen, entity);
            if (assignPersonalDelivery(level, settlement, citizen)) syncHeldItem(citizen, entity);
            data.touch();
            return;
        }
        ItemStack consumed = citizen.extractInventoryItem(stack -> stack.is(input), 1);
        if (consumed.isEmpty()) return;
        ItemStack remaining = citizen.insertIntoInventory(new ItemStack(output, 5));
        if (!remaining.isEmpty()) {
            citizen.insertIntoInventory(consumed);
            return;
        }
        int axeSlot = CitizenEquipmentService.toolSlotForWork(citizen);
        if (axeSlot >= 0) citizen.damageInventoryItem(axeSlot, 1);
        syncHeldItem(citizen, entity);
        if (CitizenEquipmentService.missingEquipment(citizen) != null
                || (citizen.findInventorySlot(stack -> SAWMILL_RECIPES.containsKey(stack.getItem())) < 0
                && CarpenterRecipes.select(level, settlement, citizen) == null)) {
            WarehouseInventoryService.releaseInteractionPosition(level, entity);
            clear(data, citizen, entity);
            if (assignPersonalDelivery(level, settlement, citizen)) syncHeldItem(citizen, entity);
        }
        data.touch();
    }

    private static boolean isCarpenterInput(Item item) {
        return SAWMILL_RECIPES.containsKey(item) || CarpenterRecipes.inputs().contains(item);
    }

    private static boolean carpenterOutputFits(CitizenRecord citizen, Item input) {
        Item planks = SAWMILL_RECIPES.get(input);
        if (planks != null) {
            return citizen.inventoryCapacityFor(new ItemStack(planks, 5)) >= 5;
        }
        return CarpenterRecipes.inputs().contains(input)
                && citizen.inventoryCapacityFor(new ItemStack(Items.CHARCOAL)) > 0;
    }

    private static void makeTool(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        BuildingRecord workshop = workplace(settlement, citizen, BuildingType.TOOL_WORKSHOP);
        ArtisanRecipes.Recipe recipe = ArtisanRecipes.select(level, settlement, citizen);
        if (workshop == null || CitizenEquipmentService.missingEquipment(citizen) != null
                || toolStockAtLimit(level, settlement) || recipe == null) {
            finishToolmakingBatch(level, data, settlement, citizen, entity);
            return;
        }
        // The existing building target is only populated during the supply leg.
        // A partial batch stays in the backpack when stock runs out; it is never
        // delivered and fetched repeatedly just because an input is temporarily missing.
        if (citizen.targetBuildingId() != null
                || citizen.activity() == CitizenActivity.FETCHING_TOOLMAKING_INPUT
                || countInventoryItem(citizen, recipe.material()) < recipe.materialCount()
                || countInventoryItem(citizen, Items.STICK) < recipe.sticks()) {
            fetchToolmakingInput(level, data, settlement, workshop, citizen, entity, recipe);
            return;
        }
        BlockPos target = WarehouseInventoryService.reserveInteractionPosition(level, workshop, entity);
        if (target == null) {
            waitForWarehouseStation(level, data, citizen, entity, workshop, CitizenActivity.TOOLMAKING);
            return;
        }
        if (!nearWorkPosition(entity, target)) {
            moveToWarehouseStation(level, data, citizen, entity, target, CitizenActivity.TOOLMAKING);
            return;
        }
        if (!WarehouseInventoryService.canInteract(level, workshop, entity)) {
            WarehouseInventoryService.abandonInteractionPosition(level, entity);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "WAREHOUSE_INACCESSIBLE");
            return;
        }
        setActivity(data, citizen, entity, CitizenActivity.TOOLMAKING, "");
        if (!workCycleReady(level, citizen)) return;
        ItemStack tool = new ItemStack(recipe.output());
        if (citizen.inventoryCapacityFor(tool) < 1) {
            finishToolmakingBatch(level, data, settlement, citizen, entity);
            return;
        }
        List<ItemStack> consumed = new ArrayList<>();
        if (!consumeToolmakingInput(citizen, recipe.material(), recipe.materialCount(), consumed)
                || !consumeToolmakingInput(citizen, Items.STICK, recipe.sticks(), consumed)) {
            consumed.forEach(citizen::insertIntoInventory);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "MISSING_INPUT");
            data.touch();
            return;
        }
        ItemStack remaining = citizen.insertIntoInventory(tool);
        if (!remaining.isEmpty()) {
            consumed.forEach(citizen::insertIntoInventory);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "INVENTORY_FULL");
            data.touch();
            return;
        }
        int workshopTool = CitizenEquipmentService.toolSlotForWork(citizen);
        if (workshopTool >= 0) citizen.damageInventoryItem(workshopTool, 1);
        syncHeldItem(citizen, entity);
        if (CitizenEquipmentService.missingEquipment(citizen) == null
                && citizen.inventoryCapacityFor(new ItemStack(recipe.output())) > 0
                && !toolStockAtLimit(level, settlement)
                && countInventoryItem(citizen, recipe.material()) >= recipe.materialCount()
                && countInventoryItem(citizen, Items.STICK) >= recipe.sticks()) {
            data.touch();
            return;
        }
        finishToolmakingBatch(level, data, settlement, citizen, entity);
    }

    private static boolean consumeToolmakingInput(
            CitizenRecord citizen, Item item, int count, List<ItemStack> consumed
    ) {
        int remaining = count;
        while (remaining > 0) {
            ItemStack extracted = citizen.extractInventoryItem(stack -> stack.is(item), remaining);
            if (extracted.isEmpty()) return false;
            consumed.add(extracted);
            remaining -= extracted.getCount();
        }
        return true;
    }

    private static void finishToolmakingBatch(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        clear(data, citizen, entity);
        if (assignPersonalDelivery(level, settlement, citizen)) {
            syncHeldItem(citizen, entity);
            data.touch();
        }
    }

    private static void fetchToolmakingInput(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement, BuildingRecord workshop,
            CitizenRecord citizen, SettlementCitizenEntity entity, ArtisanRecipes.Recipe recipe
    ) {
        int material = countInventoryItem(citizen, recipe.material());
        int sticks = countInventoryItem(citizen, Items.STICK);
        // Three input slots accommodate up to 33 existing 3-iron/2-stick recipes;
        // every remaining slot is kept for a real, non-stackable output tool.
        int otherSlots = (int) citizen.inventory().stream().filter(stack -> !stack.isEmpty()
                && !stack.is(recipe.material()) && !stack.is(Items.STICK)).count();
        int bagRecipes = Math.max(0, CitizenRecord.INVENTORY_SIZE - otherSlots - 3);
        if (bagRecipes <= 0) {
            finishToolmakingBatch(level, data, settlement, citizen, entity);
            return;
        }
        boolean creative = SettlementInventoryService.hasCreativeSupply(level, settlement);
        int recipes = creative ? bagRecipes : Math.min(bagRecipes, Math.min(
                (material + SettlementInventoryService.countItem(level, settlement, recipe.material())) / recipe.materialCount(),
                (sticks + SettlementInventoryService.countItem(level, settlement, Items.STICK)) / recipe.sticks()));
        if (recipes <= 0) {
            entity.stopSettlementNavigation();
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "MISSING_INPUT");
            return;
        }
        Map<Item, Integer> needed = new LinkedHashMap<>();
        if (material < recipes * recipe.materialCount())
            needed.put(recipe.material(), recipes * recipe.materialCount() - material);
        if (sticks < recipes * recipe.sticks()) needed.put(Items.STICK, recipes * recipe.sticks() - sticks);
        if (needed.isEmpty()) {
            releaseSupplyStation(level, entity);
            if (citizen.returnToToolmaking(workshop.position())) data.touch();
            return;
        }
        BuildingRecord warehouse = settlement.building(citizen.targetBuildingId());
        if (!toolmakingSourceHasInput(level, warehouse, needed.keySet())) {
            warehouse = SettlementInventoryService.warehouses(settlement).stream()
                    .filter(candidate -> toolmakingSourceHasInput(level, candidate, needed.keySet()))
                    .min(Comparator.comparingDouble(candidate -> candidate.position().distSqr(entity.blockPosition())))
                    .orElse(null);
        }
        boolean useWarehouse = usable(warehouse, BuildingType.WAREHOUSE);
        if (!useWarehouse && !settlement.creativeCoreInstalled()) {
            entity.stopSettlementNavigation();
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "MISSING_INPUT");
            return;
        }
        UUID sourceId = useWarehouse ? warehouse.id() : null;
        if (!java.util.Objects.equals(sourceId, citizen.targetBuildingId())) entity.stopSettlementNavigation();
        if (citizen.seekToolmakingSupply(sourceId,
                useWarehouse ? warehouse.position() : settlement.corePosition())) data.touch();
        BlockPos station = useWarehouse
                ? WarehouseInventoryService.reserveExtractionPosition(level, warehouse, entity, needed.keySet())
                : SettlementCoreInteractionService.reserveSupplyPosition(level, settlement, entity);
        if (station == null) {
            waitForSupplyStation(level, data, settlement, citizen, entity, warehouse,
                    useWarehouse, CitizenActivity.FETCHING_TOOLMAKING_INPUT);
            return;
        }
        if (!nearWorkPosition(entity, station)) {
            moveToWarehouseStation(level, data, citizen, entity, station, CitizenActivity.FETCHING_TOOLMAKING_INPUT);
            return;
        }
        boolean interactable = useWarehouse ? WarehouseInventoryService.canInteract(level, warehouse, entity)
                : SettlementCoreInteractionService.canReceiveSupply(level, settlement, entity);
        if (!interactable) {
            abandonSupplyStation(level, entity);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED,
                    useWarehouse ? "WAREHOUSE_INACCESSIBLE" : "CORE_SUPPLY_INACCESSIBLE");
            return;
        }
        boolean virtual = useWarehouse ? WarehouseInventoryService.hasCreativeSupply(level, warehouse)
                : settlement.creativeCoreInstalled();
        for (var request : needed.entrySet()) {
            int limit = Math.min(request.getValue(), citizen.inventoryCapacityFor(new ItemStack(request.getKey())));
            if (limit <= 0) continue;
            ItemStack extracted = useWarehouse ? WarehouseInventoryService.extractItemForResident(
                    level, warehouse, entity, request.getKey(), limit) : ItemStack.EMPTY;
            if (extracted.isEmpty() && virtual) extracted = new ItemStack(request.getKey(), limit);
            if (extracted.isEmpty()) continue;
            ItemStack remainder = citizen.insertIntoInventory(extracted);
            if (!remainder.isEmpty() && useWarehouse) {
                WarehouseInventoryService.insertForResident(level, warehouse, entity, remainder);
            }
        }
        releaseSupplyStation(level, entity);
        syncHeldItem(citizen, entity);
        if (countInventoryItem(citizen, recipe.material()) >= recipes * recipe.materialCount()
                && countInventoryItem(citizen, Items.STICK) >= recipes * recipe.sticks()) {
            citizen.returnToToolmaking(workshop.position());
        }
        data.touch();
    }

    private static boolean toolmakingSourceHasInput(
            ServerLevel level, @Nullable BuildingRecord warehouse, Set<Item> inputs
    ) {
        return usable(warehouse, BuildingType.WAREHOUSE)
                && (WarehouseInventoryService.hasCreativeSupply(level, warehouse)
                || inputs.stream().anyMatch(item -> WarehouseInventoryService.countItem(level, warehouse, item) > 0));
    }

    private static void forestry(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        BuildingRecord treeFarm = settlement.building(citizen.targetBuildingId());
        BlockPos target = citizen.targetPosition();
        if (workplace(settlement, citizen, BuildingType.TREE_FARM) == null
                || !usable(treeFarm, BuildingType.TREE_FARM) || target == null
                || !insideTreeFarm(treeFarm, target) || !level.hasChunkAt(target)) {
            clear(data, citizen, entity);
            return;
        }
        BlockState targetState = level.getBlockState(target);
        // Targets can change while the resident is walking. Never replace a
        // player's block, a newly planted sapling, or a neighbouring worker's work.
        if (forestrySeed(level, treeFarm, target) == null && !targetState.is(BlockTags.LOGS)
                && treeFarmPlantingPlan(level, treeFarm, target, null) == null) {
            finishForestryTask(level, data, settlement, citizen, entity);
            return;
        }
        BlockPos station = forestryStation(level, entity, target);
        if (station == null) {
            deferForestryTarget(level, citizen, target);
            finishForestryTask(level, data, settlement, citizen, entity);
            if (citizen.workState() == CitizenWorkState.IDLE) {
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "FORESTRY_STATION_UNREACHABLE");
            }
            return;
        }
        if (entity.distanceToSqr(Vec3.atBottomCenterOf(target)) > FORESTRY_WORK_RADIUS_SQUARED
                || !entity.canStandAtPosition(entity.navigationFeetPosition())) {
            move(data, citizen, entity, station, CitizenActivity.FORESTRY);
            if (terminalNavigationFailure(citizen.workFailure())) {
                deferForestryTarget(level, citizen, target);
                finishForestryTask(level, data, settlement, citizen, entity);
            }
            return;
        }
        if (!workCycleReady(level, citizen)) return;
        int toolSlot = CitizenEquipmentService.toolSlotForWork(citizen);
        if (toolSlot < 0) {
            clear(data, citizen, entity);
            return;
        }
        var treeSeed=forestrySeed(level,treeFarm,target);
        var wholeTreeSapling=treeSeed==null?null:saplingFor(level.getBlockState(treeSeed));
        var wholeTree=treeSeed==null?null:ResidentTreeFelling.clear(level,treeSeed,entity,citizen.inventoryItem(toolSlot),
                part->!forestryStructureProtected(settlement,treeFarm,part),level.getBlockState(treeSeed).getFluidState().createLegacyBlock());
        if(wholeTree!=null) {
            if(!wholeTree.failure().isEmpty()) {
                deferForestryTarget(level,citizen,target);finishForestryTask(level,data,settlement,citizen,entity);
                setActivity(data,citizen,entity,CitizenActivity.BLOCKED,wholeTree.failure());return;
            }
            for(var drop:wholeTree.drops()){var extra=citizen.insertIntoInventory(drop);if(!extra.isEmpty())Block.popResource(level,target,extra);}
            citizen.damageInventoryItem(toolSlot,1);TREE_HARVESTS.remove(entity);
            if(!plantTreeFarmSapling(level,settlement,citizen,treeFarm,target,wholeTreeSapling))deferForestryTarget(level,citizen,target);
            finishForestryTask(level,data,settlement,citizen,entity);data.touch();return;
        }
        TreeHarvest plan = TREE_HARVESTS.get(entity);
        if (plan == null || !plan.root().equals(target)) {
            List<BlockPos> logs = connectedLogs(level, settlement, treeFarm, target);
            if (logs == null) {
                deferForestryTarget(level, citizen, target);
                finishForestryTask(level, data, settlement, citizen, entity);
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "FORESTRY_TREE_TOO_LARGE");
                return;
            }
            if (logs.isEmpty()) {
                if (!plantTreeFarmSapling(level, settlement, citizen, treeFarm, target, null)) {
                    deferForestryTarget(level, citizen, target);
                    finishForestryTask(level, data, settlement, citizen, entity);
                    if (citizen.workState() == CitizenWorkState.IDLE) setActivity(data, citizen, entity,
                            CitizenActivity.BLOCKED, CitizenEquipmentService.hasPlantingStock(citizen)
                                    ? "FORESTRY_PLANTING_SPACE" : "FORESTRY_SAPLINGS_MISSING");
                    return;
                }
                finishForestryTask(level, data, settlement, citizen, entity); return;
            }
            if (logs.stream().anyMatch(p -> forestryStructureProtected(settlement, treeFarm, p))) {
                deferForestryTarget(level, citizen, target);
                finishForestryTask(level, data, settlement, citizen, entity);
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "PROTECTED_AREA"); return;
            }
            LinkedHashMap<BlockPos, Block> planned = new LinkedHashMap<>();
            List<BlockPos> leaves = associatedTreeLeaves(level, settlement, treeFarm, logs);
            if (leaves == null) {
                deferForestryTarget(level, citizen, target);
                finishForestryTask(level, data, settlement, citizen, entity);
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "FORESTRY_TREE_TOO_LARGE"); return;
            }
            // Leaves first, root last: interrupted/reloaded jobs still have a discoverable trunk.
            for (BlockPos leaf : leaves) planned.put(leaf, level.getBlockState(leaf).getBlock());
            for (BlockPos log : logs) {
                for (Direction direction : Direction.values()) {
                    BlockPos nest = log.relative(direction);
                    if (level.hasChunkAt(nest) && level.getBlockState(nest).is(Blocks.BEE_NEST)
                            && settlement.ownsChunk(nest.getX() >> 4, nest.getZ() >> 4)
                            && !forestryStructureProtected(settlement, treeFarm, nest)) planned.put(nest, Blocks.BEE_NEST);
                }
            }
            for (BlockPos log : logs) planned.put(log, level.getBlockState(log).getBlock());
            BlockState species = logs.stream().map(level::getBlockState)
                    .filter(state -> state.is(BlockTags.LOGS) && !isMangroveRoot(state))
                    .findFirst().orElse(level.getBlockState(logs.getLast()));
            plan = new TreeHarvest(target.immutable(), saplingFor(species), planned);
            TREE_HARVESTS.put(entity, plan);
        }
        int harvested = 0;
        var iterator = plan.remaining().entrySet().iterator();
        while (iterator.hasNext() && harvested < FORESTRY_HARVEST_BATCH
                && !shouldFarmerDeliver(level, settlement, citizen)) {
            var entry = iterator.next(); BlockPos position = entry.getKey();
            if (!level.hasChunkAt(position)) break;
            BlockState current = level.getBlockState(position);
            if (current.getBlock() != entry.getValue() || forestryStructureProtected(settlement, treeFarm, position)
                    || current.hasProperty(BlockStateProperties.PERSISTENT) && current.getValue(BlockStateProperties.PERSISTENT)) {
                iterator.remove(); continue;
            }
            if (!net.neoforged.neoforge.common.CommonHooks.canEntityDestroy(level, position, entity)) {
                TREE_HARVESTS.remove(entity); deferForestryTarget(level, citizen, target);
                finishForestryTask(level, data, settlement, citizen, entity);
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "PROTECTED_AREA"); return;
            }
            BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, position, Block.UPDATE_CLIENTS);
            var harvestedBlockEntity = level.getBlockEntity(position);
            List<ItemStack> drops = Block.getDrops(current, level, position, harvestedBlockEntity, entity,
                    citizen.inventoryItem(toolSlot));
            BlockState replacement = current.hasProperty(BlockStateProperties.WATERLOGGED)
                    && current.getValue(BlockStateProperties.WATERLOGGED)
                    ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (!level.setBlock(position, replacement, Block.UPDATE_ALL)
                    || EventHooks.onBlockPlace(null, snapshot, Direction.UP)) {
                snapshot.restore(Block.UPDATE_ALL);
                TREE_HARVESTS.remove(entity); deferForestryTarget(level, citizen, target);
                if (harvested > 0) citizen.damageInventoryItem(toolSlot, 1);
                finishForestryTask(level, data, settlement, citizen, entity);
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "PROTECTED_AREA"); return;
            }
            if (harvestedBlockEntity instanceof net.minecraft.world.level.block.entity.BeehiveBlockEntity hive
                    && !net.minecraft.world.item.enchantment.EnchantmentHelper.hasTag(citizen.inventoryItem(toolSlot),
                            net.minecraft.tags.EnchantmentTags.PREVENTS_BEE_SPAWNS_WHEN_MINING)) {
                boolean sedated = hive.isSedated();
                hive.emptyAllLivingFromHive(null, current,
                        net.minecraft.world.level.block.entity.BeehiveBlockEntity.BeeReleaseStatus.EMERGENCY);
                // Failed entity admission must not silently discard remaining occupants.
                if (hive.getOccupantCount() > 0) {
                    hive.setRemoved();
                    level.setBlock(position, current, Block.UPDATE_ALL);
                    hive.clearRemoved();
                    level.setBlockEntity(hive);
                    if (harvested > 0) citizen.damageInventoryItem(toolSlot, 1);
                    TREE_HARVESTS.remove(entity); deferForestryTarget(level, citizen, target);
                    finishForestryTask(level, data, settlement, citizen, entity);
                    setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "FORESTRY_BEES_BLOCKED"); return;
                }
                if (!sedated) {
                    for (var bee : level.getEntitiesOfClass(net.minecraft.world.entity.animal.Bee.class,
                            new AABB(position).inflate(8.0D, 6.0D, 8.0D))) {
                        if (bee.getTarget() == null && bee.distanceToSqr(entity) <= 16.0D) {
                            bee.setTarget(entity); bee.startPersistentAngerTimer();
                        }
                    }
                }
            }
            for (ItemStack drop : drops) {
                ItemStack remaining = citizen.insertIntoInventory(drop);
                if (!remaining.isEmpty()) Block.popResource(level, target, remaining);
            }
            iterator.remove(); harvested++;
        }
        if (harvested > 0) citizen.damageInventoryItem(toolSlot, 1);
        if (!plan.remaining().isEmpty()) { syncHeldItem(citizen, entity); data.touch(); return; }
        TREE_HARVESTS.remove(entity);
        if (!level.getBlockState(target).is(BlockTags.SAPLINGS)
                && !plantTreeFarmSapling(level, settlement, citizen, treeFarm, target, plan.sapling()))
            deferForestryTarget(level, citizen, target);
        finishForestryTask(level, data, settlement, citizen, entity);
    }

    private record TreeHarvest(BlockPos root, BlockState sapling, LinkedHashMap<BlockPos, Block> remaining) {}
    private static final Map<SettlementCitizenEntity, TreeHarvest> TREE_HARVESTS = new java.util.WeakHashMap<>();

    private static boolean forestryStructureProtected(SettlementRecord settlement, BuildingRecord farm, BlockPos p) {
        return settlement.buildings().stream().anyMatch(b -> b.active() && (farm==null?b.type()!=BuildingType.TREE_FARM:!b.id().equals(farm.id()))
                && p.getX() >= b.minCorner().getX() && p.getX() <= b.maxCorner().getX()
                && p.getY() >= b.minCorner().getY() && p.getY() <= b.maxCorner().getY()
                && p.getZ() >= b.minCorner().getZ() && p.getZ() <= b.maxCorner().getZ());
    }

    /** Bounded six-neighbour traversal including the unsupported outer leaf ring (distance seven). */
    static List<BlockPos> associatedTreeLeaves(ServerLevel level, SettlementRecord settlement,
            BuildingRecord farm, List<BlockPos> logs) {
        Set<BlockPos> ownLogs = Set.copyOf(logs);
        Map<BlockPos, Integer> distance = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>(logs);
        for (BlockPos log : logs) distance.put(log, 0);
        List<BlockPos> leaves = new ArrayList<>();
        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst(); int next = distance.get(current) + 1;
            if (next > 7) continue;
            for (Direction direction : Direction.values()) {
                BlockPos p = current.relative(direction);
                if (distance.containsKey(p) || ownLogs.contains(p) || !level.hasChunkAt(p)
                        || !settlement.ownsChunk(p.getX() >> 4, p.getZ() >> 4)
                        || forestryStructureProtected(settlement, farm, p)) continue;
                BlockState state = level.getBlockState(p);
                if (!naturalTreeLeaf(state)) continue;
                distance.put(p, next); leaves.add(p); queue.addLast(p);
                if (leaves.size() > FORESTRY_SCAN_LOG_LIMIT) return null;
            }
        }
        // Read actual supporting logs through a bounded leaf halo. A stale leaf
        // DISTANCE (or an orphaned external leaf) is not proof of another tree.
        Set<BlockPos> halo = new HashSet<>(leaves);
        Map<BlockPos, Integer> haloSteps = new HashMap<>();
        queue.clear();
        for (BlockPos leaf : leaves) { queue.add(leaf); haloSteps.put(leaf, 0); }
        Map<BlockPos, Integer> otherDistance = new HashMap<>();
        while (!queue.isEmpty()) {
            BlockPos leaf = queue.removeFirst();
            int next = haloSteps.get(leaf) + 1;
            for (Direction direction : Direction.values()) {
                BlockPos adjacent = leaf.relative(direction);
                if (ownLogs.contains(adjacent) || !level.hasChunkAt(adjacent)) continue;
                BlockState state = level.getBlockState(adjacent);
                if (state.is(BlockTags.LOGS)) otherDistance.put(leaf, 1);
                else if (next <= 6 && naturalTreeLeaf(state) && halo.add(adjacent)) {
                    haloSteps.put(adjacent, next); queue.addLast(adjacent);
                    if (halo.size() > FORESTRY_SCAN_LOG_LIMIT) return null;
                }
            }
        }
        queue.addAll(otherDistance.keySet());
        while (!queue.isEmpty()) {
            BlockPos leaf = queue.removeFirst(); int next = otherDistance.get(leaf) + 1;
            if (next > 7) continue;
            for (Direction direction : Direction.values()) {
                BlockPos adjacent = leaf.relative(direction);
                if (halo.contains(adjacent)
                        && next < otherDistance.getOrDefault(adjacent, 8)) {
                    otherDistance.put(adjacent, next); queue.addLast(adjacent);
                }
            }
        }
        leaves.removeIf(leaf -> otherDistance.getOrDefault(leaf, 8) <= distance.get(leaf));
        return leaves;
    }

    private static boolean naturalTreeLeaf(BlockState state) {
        return state.is(BlockTags.LEAVES) && (!state.hasProperty(BlockStateProperties.PERSISTENT)
                || !state.getValue(BlockStateProperties.PERSISTENT));
    }

    private static void finishForestryTask(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        clear(data, citizen, entity);
        if (citizen.hasInventoryCapacity()) {
            Set<BlockPos> claimed = settlement.citizens().stream()
                    .filter(other -> !other.id().equals(citizen.id()))
                    .map(CitizenRecord::targetPosition)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            if (assignForestry(level, settlement, citizen, claimed)) {
                data.touch();
                return;
            }
        }
        if (assignPersonalDelivery(level, settlement, citizen)) {
            syncHeldItem(citizen, entity);
            data.touch();
        }
    }

    private static boolean forestryTargetDeferred(ServerLevel level, CitizenRecord citizen, BlockPos target) {
        Map<UUID, Map<BlockPos, Long>> byCitizen = DEFERRED_FORESTRY_TARGETS.get(level);
        if (byCitizen == null) return false;
        Map<BlockPos, Long> targets = byCitizen.get(citizen.id());
        if (targets == null) return false;
        long now = level.getGameTime();
        targets.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (targets.isEmpty()) byCitizen.remove(citizen.id());
        return targets.getOrDefault(target, 0L) > now;
    }

    private static void deferForestryTarget(ServerLevel level, CitizenRecord citizen, BlockPos target) {
        Map<BlockPos, Long> targets = DEFERRED_FORESTRY_TARGETS
                .computeIfAbsent(level, ignored -> new HashMap<>())
                .computeIfAbsent(citizen.id(), ignored -> new LinkedHashMap<>());
        if (targets.size() >= 64) targets.remove(targets.keySet().iterator().next());
        targets.put(target.immutable(), level.getGameTime() + FORESTRY_TARGET_REFRESH_TICKS);
    }

    private static boolean insideTreeFarm(BuildingRecord treeFarm, BlockPos position) {
        if(treeFarm.region()!=null){var c=treeFarm.region().cell(position);return c!=null && c.use()==com.hkin.exiledsettlement.region.ProductionRegion.Use.FOREST && position.getY()>=Math.min(c.ground().getY(),treeFarm.region().gradeOrigins.getOrDefault(com.hkin.exiledsettlement.region.ProductionRegion.key(position.getX(),position.getZ()),c.ground().getY()))+1;}
        return position.getX() > treeFarm.minCorner().getX() && position.getX() < treeFarm.maxCorner().getX()
                && position.getZ() > treeFarm.minCorner().getZ() && position.getZ() < treeFarm.maxCorner().getZ()
                && position.getY() >= treeFarm.position().getY();
    }

    private static BlockPos forestryWorkBase(ServerLevel level, BuildingRecord farm, BlockPos log) {
        // An old suspended crown is worked from the ground, never from its high log.
        BlockPos base = log;
        while (base.getY() > farm.position().getY()) {
            BlockState below = level.getBlockState(base.below());
            if (!below.isAir() && !below.is(BlockTags.LEAVES) && !below.is(BlockTags.SAPLINGS)
                    && !isMangroveRoot(below)) break;
            base = base.below();
        }
        return base;
    }

    @Nullable
    private static BlockPos forestrySeed(ServerLevel level, BuildingRecord treeFarm, BlockPos start) {
        BlockPos seed = start;
        while (seed.getY() < level.getMaxBuildHeight() && insideTreeFarm(treeFarm, seed)
                && level.hasChunkAt(seed)) {
            BlockState state = level.getBlockState(seed);
            if (state.is(BlockTags.LOGS) || isMangroveRoot(state)) return seed;
            if (!state.isAir() && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.SAPLINGS)) break;
            seed = seed.above();
        }
        return null;
    }

    @Nullable
    private static List<BlockPos> connectedLogs(ServerLevel level, SettlementRecord settlement,
                                               BuildingRecord treeFarm, BlockPos start) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        LinkedHashSet<BlockPos> found = new LinkedHashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        BlockPos seed = forestrySeed(level, treeFarm, start);
        if (seed == null) return List.of();
        queue.add(seed.immutable());
        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            // The root must belong to the tree farm; its connected branches may
            // overhang the fence, inside the same owned and protected territory.
            if (!visited.add(current) || current.getY() >= level.getMaxBuildHeight()
                    || current.getY() < level.getMinBuildHeight()) continue;
            if (!level.hasChunkAt(current)) return null;
            BlockState structure = level.getBlockState(current);
            if (!structure.is(BlockTags.LOGS) && !isMangroveRoot(structure)) continue;
            if (current.getX() < treeFarm.minCorner().getX() - 8
                    || current.getY() < treeFarm.position().getY() - 2
                    || current.getX() > treeFarm.maxCorner().getX() + 8
                    || current.getZ() < treeFarm.minCorner().getZ() - 8
                    || current.getZ() > treeFarm.maxCorner().getZ() + 8
                    || !settlement.ownsChunk(current.getX() >> 4, current.getZ() >> 4)
                    || forestryStructureProtected(settlement, treeFarm, current)
                    ) return null;
            found.add(current);
            // Do not cut the root of an incompletely enumerated tree.
            if (found.size() > FORESTRY_SCAN_LOG_LIMIT) return null;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx != 0 || dy != 0 || dz != 0) queue.addLast(current.offset(dx, dy, dz));
                    }
                }
            }
        }
        List<BlockPos> ordered = new ArrayList<>(found.stream().filter(position -> !position.equals(start))
                .sorted(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed()
                .thenComparing(position -> position.equals(start))
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ)).toList());
        if (found.contains(start)) ordered.add(start);
        return ordered;
    }

    private static BlockState saplingFor(BlockState log) {
        if (log.is(Blocks.SPRUCE_LOG)) return Blocks.SPRUCE_SAPLING.defaultBlockState();
        if (log.is(Blocks.BIRCH_LOG)) return Blocks.BIRCH_SAPLING.defaultBlockState();
        if (log.is(Blocks.JUNGLE_LOG)) return Blocks.JUNGLE_SAPLING.defaultBlockState();
        if (log.is(Blocks.ACACIA_LOG)) return Blocks.ACACIA_SAPLING.defaultBlockState();
        if (log.is(Blocks.DARK_OAK_LOG)) return Blocks.DARK_OAK_SAPLING.defaultBlockState();
        if (log.is(Blocks.CHERRY_LOG)) return Blocks.CHERRY_SAPLING.defaultBlockState();
        if (log.is(Blocks.MANGROVE_LOG) || isMangroveRoot(log)) return Blocks.MANGROVE_PROPAGULE.defaultBlockState();
        net.minecraft.resources.ResourceLocation logId =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(log.getBlock());
        String path = logId.getPath();
        for (String suffix : List.of("_log", "_wood", "_stem", "_hyphae")) {
            if (!path.endsWith(suffix)) continue;
            String candidate = path.substring(0, path.length() - suffix.length()) + "_sapling";
            net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation
                    .fromNamespaceAndPath(logId.getNamespace(), candidate);
            if (net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(id)) {
                Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
                if (block.defaultBlockState().is(BlockTags.SAPLINGS)) return block.defaultBlockState();
            }
        }
        return Blocks.OAK_SAPLING.defaultBlockState();
    }

    private static boolean isMangroveRoot(BlockState state) {
        return state.is(Blocks.MANGROVE_ROOTS) || state.is(Blocks.MUDDY_MANGROVE_ROOTS);
    }

    @Nullable
    private static BlockPos forestryStation(ServerLevel level, SettlementCitizenEntity entity, BlockPos log) {
        BlockPos feet = entity.navigationFeetPosition();
        if (entity.distanceToSqr(Vec3.atBottomCenterOf(log)) <= FORESTRY_WORK_RADIUS_SQUARED
                && entity.canStandAtPosition(feet)) return feet;
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos cursor : BlockPos.betweenClosed(log.offset(-8, -3, -8), log.offset(8, 3, 8))) {
            // A completed path may stop off centre: reserve one block of arrival tolerance.
            if (cursor.distSqr(log) > 49.0D || !level.hasChunkAt(cursor)
                    || !entity.canStandAtPosition(cursor)) continue;
            candidates.add(cursor.immutable());
        }
        candidates.sort(Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(feet))
                .thenComparingDouble(pos -> pos.distSqr(log)).thenComparingLong(BlockPos::asLong));
        // Bound expensive path probes; a different tree can be worked if this
        // small local search has no route. No exact standing-cell reservation.
        return candidates.stream().limit(8).filter(entity::hasReachablePathTo).findFirst().orElse(null);
    }

    @Nullable
    private static ForestryPlantingPlan treeFarmPlantingPlan(ServerLevel level, BuildingRecord farm,
                                                            BlockPos target, @Nullable BlockState preferred) {
        return treeFarmPlantingPlan(level, farm, target, preferred, null);
    }

    @Nullable
    private static ForestryPlantingPlan treeFarmPlantingPlan(ServerLevel level, BuildingRecord farm,
            BlockPos target, @Nullable BlockState preferred, @Nullable CitizenRecord citizen) {
        if (!com.hkin.exiledsettlement.region.RegionGroundService.plantReady(level,farm,target))return null;
        if (!isTreeFarmPlantingGrid(farm, target) || !insideTreeFarm(farm, target) || !level.hasChunkAt(target)
                || !level.getBlockState(target).isAir()) return null;
        List<BlockState> choices = new ArrayList<>();
        if (preferred != null) choices.add(preferred);
        net.minecraft.core.registries.BuiltInRegistries.BLOCK.stream()
                .map(Block::defaultBlockState).filter(state -> state.is(BlockTags.SAPLINGS))
                .sorted(Comparator.comparing(state ->
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()))
                .forEach(choices::add);
        choices.add(Blocks.MANGROVE_PROPAGULE.defaultBlockState());
        if (choices.isEmpty()) return null;
        Boolean singleSpaced = null;
        Boolean groupSpaced = null;
        int start = preferred == null ? Math.floorMod(target.hashCode(), choices.size()) : 0;
        for (int offset = 0; offset < choices.size(); offset++) {
            BlockState state = choices.get((start + offset) % choices.size());
            if (citizen != null && countInventoryItem(citizen, state.getBlock().asItem())
                    < CitizenEquipmentService.plantingSeedMinimum(state.getBlock().asItem())) continue;
            if (!state.canSurvive(level, target)) continue;
            if (state.is(Blocks.DARK_OAK_SAPLING)) {
                List<BlockPos> plot = List.of(target, target.east(), target.south(), target.south().east());
                if (plot.stream().anyMatch(pos -> !com.hkin.exiledsettlement.region.RegionGroundService.plantReady(level,farm,pos) || !insideTreeFarm(farm, pos) || !level.hasChunkAt(pos)
                        || !level.getBlockState(pos).isAir() || !state.canSurvive(level, pos))) continue;
                if (groupSpaced == null) groupSpaced = treePlotSpaced(level, farm, plot);
                if (groupSpaced) return new ForestryPlantingPlan(state, plot);
            } else {
                if (singleSpaced == null) singleSpaced = treePlotSpaced(level, farm, List.of(target));
                if (singleSpaced) return new ForestryPlantingPlan(state, List.of(target));
            }
        }
        return null;
    }

    private static boolean treePlotSpaced(ServerLevel level, BuildingRecord farm, List<BlockPos> plot) {
        Set<BlockPos> cells = Set.copyOf(plot);
        for (BlockPos position : plot) {
            for (BlockPos nearby : BlockPos.betweenClosed(position.offset(-4, -2, -4), position.offset(4, 16, 4))) {
                if (cells.contains(nearby)) continue;
                if (!level.hasChunkAt(nearby)) return false;
                BlockState state = level.getBlockState(nearby);
                if (state.is(BlockTags.LOGS) || state.is(BlockTags.SAPLINGS) || isMangroveRoot(state)) return false;
            }
        }
        return true;
    }

    private static boolean plantTreeFarmSapling(ServerLevel level, SettlementRecord settlement,
            CitizenRecord citizen, BuildingRecord farm, BlockPos target,
                                               @Nullable BlockState preferred) {
        ForestryPlantingPlan plan = treeFarmPlantingPlan(level, farm, target, preferred, citizen);
        if (plan == null) return false;
        if (settlement.citizens().stream().anyMatch(other -> !other.id().equals(citizen.id())
                && other.workState() == CitizenWorkState.TO_FOREST && farm.id().equals(other.targetBuildingId())
                && other.targetPosition() != null && level.getBlockState(other.targetPosition()).isAir()
                && Math.abs(other.targetPosition().getX() - target.getX()) < 5
                && Math.abs(other.targetPosition().getZ() - target.getZ()) < 5)) return false;
        Item seed = plan.state().getBlock().asItem();
        List<ItemStack> consumed = new ArrayList<>();
        if (!consumeToolmakingInput(citizen, seed, plan.positions().size(), consumed)) {
            consumed.forEach(citizen::insertIntoInventory); return false;
        }
        List<BlockSnapshot> placed = new ArrayList<>();
        for (BlockPos position : plan.positions()) {
            BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, position, Block.UPDATE_CLIENTS);
            placed.add(snapshot);
            if (!level.setBlock(position, plan.state(), Block.UPDATE_ALL)
                    || EventHooks.onBlockPlace(null, snapshot, Direction.UP)) {
                for (int index = placed.size() - 1; index >= 0; index--) placed.get(index).restore(Block.UPDATE_ALL);
                consumed.forEach(citizen::insertIntoInventory);
                return false;
            }
        }
        return true;
    }

    private static void fish(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        BuildingRecord hut = workplace(settlement, citizen, BuildingType.FISHING_HUT);
        BlockPos target = citizen.targetPosition();
        if (hut == null || target == null) {
            clear(data, citizen, entity);
            return;
        }
        int rodSlot = CitizenEquipmentService.toolSlotForWork(citizen);
        if (rodSlot < 0) {
            clear(data, citizen, entity);
            return;
        }
        if (!near(entity, target)) {
            move(data, citizen, entity, target, CitizenActivity.FISHING);
            return;
        }
        if (!workCycleReady(level, citizen)) return;
        ItemStack catchStack = new ItemStack(level.random.nextInt(4) == 0 ? Items.SALMON : Items.COD,
                1 + level.random.nextInt(2));
        ItemStack remaining = citizen.insertIntoInventory(catchStack);
        if (!remaining.isEmpty()) Block.popResource(level, hut.position(), remaining);
        citizen.damageInventoryItem(rodSlot, 1);
        clear(data, citizen, entity);
        Set<BlockPos> claimed = settlement.citizens().stream()
                .filter(other -> !other.id().equals(citizen.id()))
                .map(CitizenRecord::targetPosition)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        boolean fishCapacity = citizen.inventoryCapacityFor(new ItemStack(Items.COD)) > 0
                || citizen.inventoryCapacityFor(new ItemStack(Items.SALMON)) > 0;
        if (remaining.isEmpty() && fishCapacity
                && CitizenEquipmentService.missingEquipment(citizen) == null
                && assignFishing(level, settlement, citizen, claimed)) {
            data.touch();
            return;
        }
        if (assignPersonalDelivery(level, settlement, citizen)) {
            syncHeldItem(citizen, entity);
            data.touch();
        }
    }

    private static boolean assignMining(ServerLevel level, SettlementRecord settlement, CitizenRecord citizen, Set<BlockPos> claimed) {
        BuildingRecord mine = workplace(settlement, citizen, BuildingType.MINE);
        if(mine==null || !mine.repairPositions().isEmpty())return false;
        for(BlockPos at:com.hkin.exiledsettlement.building.MineExpansionService.workPositions(mine))
            if(level.hasChunkAt(at) && claimed.add(at))return citizen.startTask(CitizenWorkState.TO_MINE,at,null,null);
        return false;
    }

    private static void mine(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity) {
        BuildingRecord mine=workplace(settlement,citizen,BuildingType.MINE);
        BlockPos target=citizen.targetPosition();
        if(citizen.profession()!=CitizenProfession.MINER || mine==null || target==null || !mine.repairPositions().isEmpty()) {
            clear(data,citizen,entity);return;
        }
        int tool=CitizenEquipmentService.toolSlotForWork(citizen);
        if(tool<0){clear(data,citizen,entity);return;}
        int cargo=java.util.stream.IntStream.range(0,CitizenRecord.INVENTORY_SIZE).filter(i->!citizen.pitReserveSlot(i)).mapToObj(citizen::inventoryItem).filter(com.hkin.exiledsettlement.building.MineProductionService::isOutput)
                .mapToInt(ItemStack::getCount).sum();
        int empty=0;for(int i=0;i<CitizenRecord.INVENTORY_SIZE;i++)if(citizen.inventoryItem(i).isEmpty())empty++;
        if(cargo>=24 || empty<=6) {
            if(cargo>0) {
                clear(data,citizen,entity);
                citizen.startTask(CitizenWorkState.TO_MINE_STORAGE,
                        com.hkin.exiledsettlement.blueprint.MineBlueprint.storagePosition(mine.position(),mine.facing()),null,null);
                data.touch();return;
            }
            if(pauseForCargoDelivery(level,data,settlement,citizen,entity))return;
            setActivity(data,citizen,entity,CitizenActivity.BLOCKED,"WAREHOUSE_FULL");return;
        }
        if(WarehouseInventoryService.stock(level,mine).room()<12) {
            setActivity(data,citizen,entity,CitizenActivity.BLOCKED,"WAREHOUSE_FULL");return;
        }
        boolean advancing=com.hkin.exiledsettlement.building.MineExpansionService.frontWorker(mine,target);
        if(advancing) {
            var stations=com.hkin.exiledsettlement.building.MineExpansionService.frontStations(mine);
            if(!stations.contains(target)) {
                var occupied=settlement.citizens().stream().filter(c->c!=citizen && c.alive()
                        && c.workState()==CitizenWorkState.TO_MINE).map(CitizenRecord::targetPosition).collect(java.util.stream.Collectors.toSet());
                target=stations.stream().filter(p->!occupied.contains(p)).findFirst().orElse(stations.getFirst());
                citizen.updateTargetPosition(target);data.touch();
            }
            var material=com.hkin.exiledsettlement.building.MineExpansionService.needed(mine);
            if(material!=null && !citizen.hasInventoryItem(v->v.is(material))
                    && fetchMineSupport(level,data,settlement,mine,citizen,entity,material))return;
        }
        // The underground hall is mandatory. Horizontal proximity above the roof does not count.
        if(Math.abs(entity.getY()-target.getY())>1.0 || (advancing
                ? entity.distanceToSqr(target.getX()+.5,target.getY(),target.getZ()+.5)>=2.25 : !near(entity,target))) {
            move(data,citizen,entity,target,CitizenActivity.MINING);return;
        }
        setActivity(data,citizen,entity,CitizenActivity.MINING,advancing?mine.mineExpansion().reason:"");
        if(advancing)com.hkin.exiledsettlement.building.MineExpansionService.animate(level,mine,entity);
        if(!workCycleReady(level,citizen))return;
        // Keep physical expansion on its existing work cadence; only random finds slow down.
        int productionInterval=com.hkin.exiledsettlement.building.MineProductionService.PRODUCTION_INTERVAL_TICKS;
        int productionOffset=Math.floorMod(citizen.id().hashCode(),productionInterval);
        if(Math.floorMod(level.getGameTime()+productionOffset,productionInterval)<WORK_INTERVAL_TICKS) {
            for(ItemStack output:com.hkin.exiledsettlement.building.MineProductionService.roll(level.random)) {
                citizen.insertIntoInventory(output); // At least seven free slots; a batch has at most three stacks.
                com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,citizen,"mine_produce",target,
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(output.getItem()).toString(),output.getCount());
            }
            citizen.damageInventoryItem(tool,1);
        }
        if(advancing)com.hkin.exiledsettlement.building.MineExpansionService.completedCycle(level,data,settlement,mine,citizen,entity);
        data.touch();
    }

    /** Physical supply trip; absent supplies pause expansion only, never virtual ore production. */
    private static boolean fetchMineSupport(ServerLevel level,SettlementSavedData data,SettlementRecord settlement,
            BuildingRecord mine,CitizenRecord citizen,SettlementCitizenEntity entity,Item material) {
        var supplier=SettlementInventoryService.warehouses(settlement).stream()
                .filter(b->b.operational() && !settlement.community().danger(b.position())
                        && WarehouseInventoryService.countItem(level,b,material)>0)
                .min(Comparator.comparingDouble(b->b.position().distSqr(entity.blockPosition()))).orElse(null);
        if(supplier==null) {
            mine.mineExpansion().reason="矿道扩张等待 "+new ItemStack(material).getHoverName().getString()+"，采掘继续";
            return false;
        }
        if(!WarehouseInventoryService.canInteract(level,supplier,entity)) {
            BlockPos destination=WarehouseInventoryService.reserveExtractionPosition(level,supplier,entity,material);
            if(destination==null) {
                waitForWarehouseStation(level,data,citizen,entity,supplier,CitizenActivity.TO_WAREHOUSE);
                return true;
            }
            moveToWarehouseStation(level,data,citizen,entity,destination,CitizenActivity.TO_WAREHOUSE);
            return true;
        }
        int count=Math.min(16,citizen.inventoryCapacityFor(new ItemStack(material)));
        if(count<=0)return false;
        var supplied=WarehouseInventoryService.extractItemForResident(level,supplier,entity,material,count);
        var remainder=citizen.insertIntoInventory(supplied);
        if(!remainder.isEmpty())WarehouseInventoryService.insertForResident(level,supplier,entity,remainder);
        WarehouseInventoryService.releaseInteractionPosition(level,entity);
        data.touch();return true;
    }

    private static void storeMineCargo(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity) {
        BuildingRecord mine=workplace(settlement,citizen,BuildingType.MINE);
        if(mine==null || !mine.repairPositions().isEmpty()) {clear(data,citizen,entity);return;}
        BlockPos target=com.hkin.exiledsettlement.blueprint.MineBlueprint.storagePosition(mine.position(),mine.facing());
        if(!WarehouseInventoryService.canInteract(level,mine,entity)) {
            move(data,citizen,entity,target,CitizenActivity.TO_WAREHOUSE);return;
        }
        boolean remaining=false;
        for(int slot=0;slot<CitizenRecord.INVENTORY_SIZE;slot++) {
            ItemStack stack=citizen.inventoryItem(slot);
            if(citizen.pitReserveSlot(slot) || !com.hkin.exiledsettlement.building.MineProductionService.isOutput(stack))continue;
            ItemStack rest=WarehouseInventoryService.insertForResident(level,mine,entity,stack);
            int moved=stack.getCount()-rest.getCount();
            if(moved>0)citizen.extractInventoryItem(v->ItemStack.isSameItemSameComponents(v,stack),moved);
            remaining|=!rest.isEmpty();
        }
        data.touch();
        if(remaining)setActivity(data,citizen,entity,CitizenActivity.BLOCKED,"WAREHOUSE_FULL");
        else clear(data,citizen,entity);
    }

    private static boolean workCycleReady(ServerLevel level, CitizenRecord citizen) {
        int offset = Math.floorMod(citizen.id().hashCode(), 200);
        return Math.floorMod(level.getGameTime() + offset, 200L) < WORK_INTERVAL_TICKS;
    }

    private static boolean farmActionReady(ServerLevel level, CitizenRecord citizen) {
        return farmActionReadyAt(level.getGameTime(), citizen.id());
    }

    public static boolean farmActionReadyAt(long gameTime, UUID citizenId) {
        int offset = Math.floorMod(citizenId.hashCode(), FARM_ACTION_INTERVAL_TICKS);
        return Math.floorMod(gameTime + offset, FARM_ACTION_INTERVAL_TICKS) < WORK_INTERVAL_TICKS;
    }

    private static boolean collectDrops(ServerLevel level, CitizenRecord citizen, @Nullable BlockPos position) {
        if (position == null) return false;
        boolean inventoryFull = false;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(position).inflate(4.0D))) {
            if (!item.isAlive() || item.getOwner() != null || item.getTarget() != null) continue;
            ItemStack remaining = citizen.insertIntoInventory(item.getItem());
            if (remaining.isEmpty()) item.discard();
            else {
                item.setItem(remaining);
                inventoryFull = true;
            }
        }
        return inventoryFull;
    }

    private static ItemStack extractItem(Container container, Item item, int limit) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.is(item)) continue;
            ItemStack extracted = stack.split(Math.min(Math.max(1, limit), stack.getCount()));
            container.setChanged();
            return extracted;
        }
        return ItemStack.EMPTY;
    }

    private static boolean selectInventoryItem(CitizenRecord citizen, Item item) {
        int slot = citizen.findInventorySlot(stack -> stack.is(item));
        if (slot < 0) return false;
        citizen.selectActiveSlot(slot);
        return true;
    }

    /** Pause at a safe boundary; world work orders remain pending while cargo is physically delivered. */
    public static boolean pauseForCargoDelivery(ServerLevel level, SettlementSavedData data,
            SettlementRecord settlement, CitizenRecord citizen, SettlementCitizenEntity entity) {
        if (citizen.workState() == CitizenWorkState.TO_WAREHOUSE
                || citizen.workState() == CitizenWorkState.TO_EQUIPMENT
                || !shouldFarmerDeliver(level, settlement, citizen)) return false;
        if (entity.hasActiveConstructionMovement()) return true;
        if (leaveScaffoldingBeforeLogistics(level, data, settlement, citizen, entity)) return true;
        int slot = deliverableSlot(settlement, citizen, false);
        BuildingRecord warehouse = acceptingWarehouse(level, settlement, entity.blockPosition(), citizen.inventoryItem(slot));
        if (warehouse == null) {
            entity.stopSettlementNavigation();
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED,
                    WarehouseInventoryService.deliveryBlocker(level, settlement, citizen.inventoryItem(slot)));
            return true;
        }
        clear(data, citizen, entity);
        citizen.startInventoryDelivery(warehouse.id(), slot);
        syncHeldItem(citizen, entity); data.touch();
        return true;
    }

    private static boolean assignPersonalDelivery(
            ServerLevel level, SettlementRecord settlement, CitizenRecord citizen
    ) {
        if (!shouldFarmerDeliver(level, settlement, citizen)) return false;
        int slot = deliverableSlot(settlement, citizen, false);
        if (slot < 0) return false;
        BuildingRecord warehouse = acceptingWarehouse(level, settlement, citizen.lastPosition(), citizen.inventoryItem(slot));
        return warehouse != null
                && citizen.startInventoryDelivery(warehouse.id(), slot);
    }

    @Nullable
    private static BuildingRecord acceptingWarehouse(
            ServerLevel level, SettlementRecord settlement, BlockPos from, ItemStack stack
    ) {
        return SettlementInventoryService.warehouses(settlement).stream()
                .filter(warehouse -> WarehouseInventoryService.canAccept(level, warehouse, stack))
                .min(Comparator.comparingDouble(warehouse -> warehouse.position().distSqr(from)))
                .orElse(null);
    }

    private static int deliverableSlot(CitizenRecord citizen) {
        for (int slot = 0; slot < CitizenRecord.INVENTORY_SIZE; slot++) {
            if (CitizenEquipmentService.deliverableCount(citizen, slot) > 0) {
                return slot;
            }
        }
        return -1;
    }

    private static int deliverableSlot(
            SettlementRecord settlement, CitizenRecord citizen, boolean deliveryAlreadyStarted
    ) {
        for (int slot = 0; slot < CitizenRecord.INVENTORY_SIZE; slot++) {
            if (personalDeliveryCount(settlement, citizen, slot) > 0) return slot;
        }
        return -1;
    }

    private static int personalDeliveryCount(SettlementRecord settlement, CitizenRecord citizen, int slot) {
        if(citizen.pitReserveSlot(slot))return 0;
        ItemStack stack = citizen.inventoryItem(slot);
        int cargo = CitizenEquipmentService.deliverableCount(citizen, slot);
        if (cargo <= 0) return 0;
        if (protectedConstructionMaterial(settlement, citizen, stack)) {
            if (stack.getItem() instanceof net.minecraft.world.item.DiggerItem) return 0;
            // Keep one real stack of each useful construction material, rather
            // than protecting every duplicate acquired across former sites.
            int reserve = stack.getMaxStackSize();
            for (int i = 0; i < slot && reserve > 0; i++) {
                if (citizen.pitReserveSlot(i)) continue;
                var earlier = citizen.inventoryItem(i);
                if (ItemStack.isSameItemSameComponents(earlier, stack)) reserve -= earlier.getCount();
            }
            cargo = Math.min(cargo, Math.max(0, stack.getCount() - Math.max(0, reserve)));
            if (cargo <= 0) return 0;
        }
        BuildingRecord farm = farmWorkplace(settlement, citizen);
        Item protectedSeed = farm != null && BuildingProductionService.isPlantingSeed(farm, stack.getItem()) ? stack.getItem() : null;
        if (citizen.profession() == CitizenProfession.LUMBERJACK && CitizenEquipmentService.isForestrySeed(stack.getItem()))
            protectedSeed = stack.getItem();
        int seedReserve = protectedSeed == null ? 0 : citizen.profession() == CitizenProfession.LUMBERJACK
                ? 16 : new ItemStack(protectedSeed).getMaxStackSize();
        if (protectedSeed != null && stack.is(protectedSeed)) {
            for (int i = 0; i < slot && seedReserve > 0; i++) {
                ItemStack before = citizen.inventoryItem(i);
                if (before.is(protectedSeed)) seedReserve -= before.getCount();
            }
            cargo = Math.min(cargo, Math.max(0, stack.getCount() - Math.max(0, seedReserve)));
        }
        return cargo;
    }

    private record WorkMaterials(BuildingBlueprintTemplate template,Set<Item> items,Item footing) { }
    private static final Map<BlueprintRecord, WorkMaterials> WORK_MATERIALS = new java.util.WeakHashMap<>();
    private static WorkMaterials workMaterials(BlueprintRecord site) {
        var template=BlueprintTemplates.create(site);var cached=WORK_MATERIALS.get(site);
        if(cached!=null && cached.template()==template)return cached;
        var items=new HashSet<Item>();Item footing=null;
        for(var p:template.placements())if(p.material()!=null) {
            items.add(p.material());
            if(footing==null && p.material()!=Items.SCAFFOLDING && SAFE_FOOTING_ITEMS.contains(p.material()))footing=p.material();
        }
        var result=new WorkMaterials(template,Set.copyOf(items),footing==null?Items.COBBLESTONE:footing);
        WORK_MATERIALS.put(site,result);return result;
    }

    /** A short scheduler idle/warehouse phase does not surrender a builder's work stock. */
    public static boolean protectedConstructionMaterial(SettlementRecord settlement, CitizenRecord citizen, ItemStack stack) {
        if (stack.isEmpty() || !constructionCapable(citizen.profession())) return false;
        for (BlueprintRecord site : settlement.blueprints()) {
            if (site.cancellationQueued()) continue;
            if (citizen.preferredWorksiteId() != null && !citizen.preferredWorksiteId().equals(site.id())) continue;
            if (stack.getItem() instanceof net.minecraft.world.item.DiggerItem) return true;
            if (stack.is(Items.SCAFFOLDING) || stack.is(Items.DIRT) || stack.is(preferredSolidFootingMaterial(site))) return true;
            Set<Item> materials = workMaterials(site).items();
            if (materials.contains(stack.getItem())) return true;
        }
        return false;
    }

    private static boolean shouldFarmerDeliver(
            ServerLevel level, SettlementRecord settlement, CitizenRecord citizen
    ) {
        if (deliverableSlot(settlement, citizen, false) < 0) return false;
        SchedulePhase phase = schedulePhase(level.getDayTime(), citizen);
        if (phase == SchedulePhase.SLEEP) return false;
        if (phase == SchedulePhase.REST) return true;
        if (citizen.profession()==CitizenProfession.MINER) return false;
        return citizen.inventory().stream().filter(ItemStack::isEmpty).count()
                <= 6;
    }

    private static void assignRestLogistics(ServerLevel level, SettlementSavedData data, SettlementRecord settlement) {
        for (CitizenRecord citizen : settlement.citizens()) {
            if(!workDue(level,citizen))continue;
            if (!citizen.alive() || citizen.workState() != CitizenWorkState.IDLE
                    || schedulePhase(level.getDayTime(), citizen) != SchedulePhase.REST
                    || com.hkin.exiledsettlement.settlement.CommunityLifeService.handles(citizen)) continue;
            SettlementCitizenEntity entity = CitizenProjectionService.findProjection(level, citizen);
            if (entity == null || entity.isDefending()) continue;
            if (CitizenInteractionService.held(entity)) continue;
            boolean assigned = assignPersonalDelivery(level, settlement, citizen);
            if (!assigned) assigned = assignMineRestHaul(level,settlement,citizen);
            if (!assigned) {
                Item tool = CitizenEquipmentService.nextEquipment(level, settlement, citizen);
                BuildingRecord warehouse = tool == null ? null : CitizenEquipmentService.supplyWarehouse(
                        level, settlement, entity.navigationFeetPosition(), tool);
                if (warehouse != null) assigned = citizen.startEquipmentFetch(warehouse.id(), warehouse.position());
            }
            if (assigned) { syncHeldItem(citizen, entity); data.touch(); }
        }
    }

    public static boolean farmerDeliveryReady(
            ServerLevel level, SettlementRecord settlement, CitizenRecord citizen
    ) {
        return shouldFarmerDeliver(level, settlement, citizen);
    }

    public static int farmerDeliverySlot(SettlementRecord settlement, CitizenRecord citizen) {
        return deliverableSlot(settlement, citizen, false);
    }

    private static boolean farmTargetDeferred(
            ServerLevel level, CitizenRecord citizen, BlockPos target
    ) {
        Map<BlockPos, Long> deferred = DEFERRED_FARM_TARGETS.get(citizen.id());
        if (deferred == null) return false;
        long gameTime = level.getGameTime();
        deferred.entrySet().removeIf(entry -> entry.getValue() <= gameTime);
        boolean blocked = deferred.getOrDefault(target, 0L) > gameTime;
        if (deferred.isEmpty()) DEFERRED_FARM_TARGETS.remove(citizen.id());
        return blocked;
    }

    private static void deferFarmTarget(ServerLevel level, CitizenRecord citizen, BlockPos target) {
        DEFERRED_FARM_TARGETS.computeIfAbsent(citizen.id(), ignored -> new LinkedHashMap<>())
                .put(target.immutable(), level.getGameTime() + FARM_TARGET_RETRY_DELAY_TICKS);
        var entity=CitizenProjectionService.findProjection(level,citizen);
        var id=entity==null?null:CitizenProjectionService.settlementId(entity);
        var settlement=id==null?null:SettlementSavedData.get(level).findById(id).orElse(null);
        var farm=settlement==null?null:settlement.building(citizen.workBuildingId());
        if(farm!=null && farm.region()!=null){farm.region().nextReview=Math.min(farm.region().nextReview,level.getGameTime()+200);
            farm.region().reviewStatus="耕作位置不可达，已请求田面与入口复查："+target.toShortString();}
    }

    private static void finishFarmTask(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        boolean changed = citizen.clearWork();
        entity.setActivity(CitizenActivity.IDLE);
        Set<BlockPos> claimed = settlement.citizens().stream()
                .filter(other -> !other.id().equals(citizen.id()))
                .map(CitizenRecord::targetPosition)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        boolean canContinue = CitizenEquipmentService.missingEquipment(citizen) == null
                && !shouldFarmerDeliver(level, settlement, citizen) && citizen.hasInventoryCapacity();
        if (canContinue && (citizen.profession() == CitizenProfession.MELON_FARMER
                ? assignMelonFarm(level, settlement, citizen, claimed)
                : assignFarm(level, settlement, citizen, claimed))) {
            entity.setActivity(CitizenActivity.FARMING);
            data.touch();
            return;
        }
        if (assignPersonalDelivery(level, settlement, citizen)) {
            syncHeldItem(citizen, entity);
            data.touch();
            return;
        }
        entity.stopSettlementNavigation();
        if (changed) data.touch();
    }

    private static boolean repairableFarmGround(BlockState state) {
        return state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT);
    }

    private static void routeIdleResidents(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement
    ) {
        List<CitizenRecord> idleCitizens = settlement.citizens().stream()
                .filter(CitizenRecord::alive)
                .filter(citizen -> citizen.workState() == CitizenWorkState.IDLE)
                .sorted(java.util.Comparator.comparing(CitizenRecord::id))
                .toList();
        for (int idleIndex = 0; idleIndex < idleCitizens.size(); idleIndex++) {
            CitizenRecord citizen = idleCitizens.get(idleIndex);
            if(!workDue(level,citizen))continue;
            if (com.hkin.exiledsettlement.settlement.CommunityLifeService.handles(citizen)) continue;
            SettlementCitizenEntity entity = CitizenProjectionService.findProjection(level, citizen);
            if (entity == null) continue;
            if (entity.isDefending()) continue;
            if (CitizenInteractionService.held(entity)) continue;
            if (schedulePhase(level.getDayTime(), citizen) == SchedulePhase.SLEEP) {
                routeResidentToPersonalBed(level, data, settlement, citizen, entity);
                continue;
            }
            if (entity.isSleeping()) entity.stopSleeping();
            BlockPos target = idleDestination(level, settlement, citizen, entity, idleIndex);
            if (target != null && !nearWorkPosition(entity, target)) {
                move(data, citizen, entity, target, CitizenActivity.IDLE);
            } else {
                entity.stopSettlementNavigation();
                setActivity(data, citizen, entity, CitizenActivity.IDLE, "");
            }
        }
    }

    private record IdleWalk(BlockPos position, long expires, UUID workplace) { }
    private static final Map<SettlementCitizenEntity, IdleWalk> IDLE_WALKS = new java.util.WeakHashMap<>();

    @Nullable
    private static BlockPos idleDestination(
            ServerLevel level, SettlementRecord settlement, CitizenRecord citizen,
            SettlementCitizenEntity entity, int idleIndex
    ) {
        long now = level.getGameTime();
        BlockPos gathering = CitizenGatheringService.destination(level, settlement, citizen, entity);
        if (gathering != null) return gathering;
        IdleWalk cached = IDLE_WALKS.get(entity);
        if (cached != null && cached.expires() > now
                && java.util.Objects.equals(cached.workplace(), citizen.workBuildingId())
                && (cached.position() == null || settlement.contains(cached.position())
                    && entity.canStandAtPosition(cached.position()))) return cached.position();
        BuildingRecord workplace = settlement.building(citizen.workBuildingId());
        BlockPos center = idleCenter(settlement, citizen, workplace, now);
        // A stable destination for 15-20 seconds; no home-door magnet and no
        // full perimeter/path rescan every scheduler update. Failed probes also cool down.
        long phase = now / 300L;
        BlockPos roadWalk = entity.idleRoadDestination((int) phase);
        if (roadWalk != null && settlement.contains(roadWalk) && !settlement.community().danger(roadWalk)) {
            IDLE_WALKS.put(entity, new IdleWalk(roadWalk, now + 300L
                    + Math.floorMod(citizen.id().hashCode(), 100), citizen.workBuildingId()));
            return roadWalk;
        }
        int start = Math.floorMod(citizen.id().hashCode() + (int) phase * 7, 16);
        int radius = 5 + Math.floorMod(citizen.id().hashCode() + (int) phase, 8);
        for (int attempt = 0; attempt < 4; attempt++) {
            double angle = Math.PI * 2.0D * (start + attempt * 3) / 16;
            BlockPos approximate = center.offset((int)Math.round(Math.cos(angle) * radius), 0,
                    (int)Math.round(Math.sin(angle) * radius));
            if (!settlement.contains(approximate) || !level.hasChunkAt(approximate)) continue;
            BlockPos standing = findNearbyStandingPosition(entity, approximate);
            if (standing != null && standing.distSqr(entity.blockPosition()) > 4) {
                IDLE_WALKS.put(entity, new IdleWalk(standing, now + 300L
                        + Math.floorMod(citizen.id().hashCode(), 100), citizen.workBuildingId()));
                return standing;
            }
        }
        IDLE_WALKS.put(entity, new IdleWalk(null, now + 100L, citizen.workBuildingId()));
        return null;
    }

    private static BlockPos idleCenter(
            SettlementRecord settlement, CitizenRecord citizen, @Nullable BuildingRecord workplace, long now
    ) {
        // One stable leisure visit in every four idle phases. These buildings
        // offer real destinations without inventing an unapproved happiness bonus.
        if (Math.floorMod(citizen.id().hashCode() + (int)(now / 300L), 4) == 0) {
            List<BuildingRecord> leisure = settlement.buildings().stream()
                    .filter(BuildingRecord::active).filter(BuildingRecord::operational)
                    .filter(building -> building.type() == BuildingType.LEISURE_GARDEN
                            || building.type() == BuildingType.TAVERN
                            || building.type() == BuildingType.RESTAURANT)
                    .sorted(Comparator.comparing(building -> building.id().toString())).toList();
            if (!leisure.isEmpty()) {
                int index = Math.floorMod(citizen.id().hashCode() + (int)(now / 1200L), leisure.size());
                return leisure.get(index).position();
            }
        }
        return workplace != null && workplace.active() && workplace.operational()
                ? workplace.position() : settlement.corePosition();
    }

    @Nullable
    private static BlockPos findNearbyStandingPosition(
            SettlementCitizenEntity entity, BlockPos approximate
    ) {
        for (int dy : new int[]{0, 1, -1, 2, -2}) {
            BlockPos candidate = approximate.offset(0, dy, 0);
            if (entity.canStandAtPosition(candidate) && entity.hasReachablePathTo(candidate)) {
                return candidate.immutable();
            }
        }
        return null;
    }

    public static void routeResidentToPersonalBed(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        BuildingRecord home = settlement.building(citizen.homeBuildingId());
        BlockPos bed = citizen.bedPosition();
        boolean validBed = home != null && home.active() && home.operational() && bed != null
                && home.servicePositions().contains(bed)
                && level.getBlockState(bed).getBlock() instanceof BedBlock
                && level.getBlockState(bed).getValue(BedBlock.PART) == BedPart.HEAD;
        if (!validBed) {
            entity.clearBedRecovery();
            if (entity.isSleeping()) entity.stopSleeping();
            entity.stopSettlementNavigation();
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "BED_MISSING");
            return;
        }
        if(entity.hasActiveConstructionMovement()) {
            setActivity(data,citizen,entity,CitizenActivity.RETURNING_HOME,"");return;
        }
        ApartmentHomeNavigation.returning(entity, home, bed);
        if (entity.isPhysicallySleepingAt(bed)) {
            entity.clearBedRecovery();
            setActivity(data, citizen, entity, CitizenActivity.RESTING_AT_HOME, "");
            return;
        }
        if (entity.isSleeping()) entity.stopSleeping();
        if (canRestAtPersonalBed(level, home, bed, entity)) {
            entity.clearBedRecovery();
            entity.stopSettlementNavigation();
            entity.startSleeping(bed);
            setActivity(data, citizen, entity, CitizenActivity.RESTING_AT_HOME, "");
            return;
        }
        // An upstairs bed is a poor long-distance waypoint: nearby road segments
        // can be reachable while the final bed station cannot yet be resolved.
        // Enter through the home's real doorway before asking for its indoor route.
        if (entity.continueBedRecovery(bed)) {
            setActivity(data, citizen, entity, CitizenActivity.RETURNING_HOME, "");
            return;
        }
        BlockPos homeEntry = personalHomeEntry(home, entity);
        if (!entity.hasEnteredPersonalHome(bed, homeEntry)) {
            String failure = entity.navigateTo(homeEntry, WALK_SPEED);
            if((failure.equals("NO_PATH") || failure.equals("STUCK")) && (entity.beginBedRecovery(bed,WALK_SPEED) || recoverHomeApproach(entity,homeEntry)))failure="";
            setActivity(data, citizen, entity,
                    failure.isEmpty() || failure.equals("TRAFFIC_WAIT")
                            ? CitizenActivity.RETURNING_HOME : CitizenActivity.BLOCKED,
                    failure.isEmpty() ? "" : failure.equals("TRAFFIC_WAIT")
                            ? failure : "BED_UNREACHABLE");
            return;
        }
        BlockPos bedApproach = bedApproachPosition(home, bed, entity);
        if (bedApproach == null) {
            if (entity.beginBedRecovery(bed, WALK_SPEED)) {
                setActivity(data, citizen, entity, CitizenActivity.RETURNING_HOME, "");
                return;
            }
            entity.stopSettlementNavigation();
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "BED_UNREACHABLE");
            return;
        }
        if (!nearWorkPosition(entity, bedApproach)) {
            String failure = entity.navigateTo(bedApproach, WALK_SPEED);
            if (failure.equals("TRAFFIC_WAIT")) {
                setActivity(data, citizen, entity, CitizenActivity.RETURNING_HOME, "TRAFFIC_WAIT");
                return;
            }
            if ((failure.equals("NO_PATH") || failure.equals("STUCK"))
                    && entity.beginBedRecovery(bed, WALK_SPEED)) {
                setActivity(data, citizen, entity, CitizenActivity.RETURNING_HOME, "");
                return;
            }
            setActivity(data, citizen, entity,
                    failure.isEmpty() ? CitizenActivity.RETURNING_HOME : CitizenActivity.BLOCKED,
                    failure.isEmpty() ? "" : "BED_UNREACHABLE");
            return;
        }
        // Reaching a station is insufficient if a wall was placed between it and the bed.
        entity.stopSettlementNavigation();
        setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "BED_UNREACHABLE");
    }

    @Nullable
    private static BlockPos personalHomeEntry(BuildingRecord home, SettlementCitizenEntity entity) {
        BlockPos feet = entity.navigationFeetPosition();
        if (home.type() != BuildingType.APARTMENT
                && feet.getX() > home.minCorner().getX() && feet.getX() < home.maxCorner().getX()
                && feet.getZ() > home.minCorner().getZ() && feet.getZ() < home.maxCorner().getZ())
            return null;
        return BlueprintTemplates.createForBuilding(home).entrances().stream()
                .map(entrance -> entrance.position().relative(entrance.outward().getOpposite()))
                .filter(entity::canUseBedStation)
                .min(Comparator.comparingDouble(position -> position.distSqr(feet)))
                .map(BlockPos::immutable).orElse(null);
    }

    private static boolean recoverHomeApproach(SettlementCitizenEntity entity,BlockPos target) {
        if(target==null || entity.hasActiveConstructionMovement())return false;
        var feet=entity.navigationFeetPosition();var candidates=new ArrayList<BlockPos>();
        for(int dx=-5;dx<=5;dx++)for(int dz=-5;dz<=5;dz++)for(int dy=-3;dy<=3;dy++) {
            var p=feet.offset(dx,dy,dz);
            if(p.distSqr(target)+1<feet.distSqr(target) && entity.canStandAtPosition(p))candidates.add(p);
        }
        candidates.sort(Comparator.comparingDouble(p->p.distSqr(target)));
        for(var p:candidates)if(entity.beginLocalConstructionRoute(p,WALK_SPEED))return true;
        return false;
    }

    private static boolean canRestAtPersonalBed(
            ServerLevel level, BuildingRecord home, BlockPos head, SettlementCitizenEntity entity
    ) {
        if (!near(entity, head) || Math.abs(entity.getY() - head.getY()) > 1.0D
                || entity.getX() < home.minCorner().getX() || entity.getX() >= home.maxCorner().getX() + 1.0D
                || entity.getZ() < home.minCorner().getZ() || entity.getZ() >= home.maxCorner().getZ() + 1.0D)
            return false;
        BlockPos foot = head.relative(level.getBlockState(head).getValue(BedBlock.FACING).getOpposite());
        return bedVisibleFrom(level, entity, entity.getEyePosition(), head, foot);
    }

    private static boolean bedVisibleFrom(
            ServerLevel level, SettlementCitizenEntity entity, Vec3 eye, BlockPos head, BlockPos foot
    ) {
        for (BlockPos part : List.of(head, foot)) {
            BlockHitResult hit = level.clip(new ClipContext(eye,
                    Vec3.atCenterOf(part), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
            if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(head)
                    || hit.getBlockPos().equals(foot)) return true;
        }
        return false;
    }

    @Nullable
    private static BlockPos bedApproachPosition(
            BuildingRecord home, BlockPos head, SettlementCitizenEntity entity
    ) {
        BlockState bedState = entity.level().getBlockState(head);
        if (!(bedState.getBlock() instanceof BedBlock)) return null;
        BlockPos foot = head.relative(bedState.getValue(BedBlock.FACING).getOpposite());
        List<BlockPos> candidates = new ArrayList<>(List.of(head, foot));
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(head.relative(direction));
            candidates.add(foot.relative(direction));
        }
        List<BlockPos> stations = candidates.stream().distinct()
                .filter(position -> position.getX() >= home.minCorner().getX()
                        && position.getX() <= home.maxCorner().getX()
                        && position.getZ() >= home.minCorner().getZ()
                        && position.getZ() <= home.maxCorner().getZ())
                .filter(entity::canUseBedStation)
                .filter(position -> bedVisibleFrom((ServerLevel) entity.level(), entity,
                        Vec3.atBottomCenterOf(position).add(0, entity.getEyeHeight(), 0), head, foot))
                .sorted(Comparator.comparingDouble(position -> position.distSqr(entity.blockPosition())))
                .toList();
        return entity.findReachableBedStation(stations);
    }

    private static void pauseExistingForNight(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement
    ) {
        for (CitizenRecord citizen : settlement.citizens()) {
            if(!workDue(level,citizen))continue;
            if(ResidentEgressService.active(settlement,citizen))continue;
            if (schedulePhase(level.getDayTime(), citizen) != SchedulePhase.SLEEP
                    || citizen.workState() == CitizenWorkState.TO_DEMOLITION) continue;
            if (!citizen.alive() || citizen.workState() == CitizenWorkState.IDLE) continue;
            SettlementCitizenEntity entity = CitizenProjectionService.findProjection(level, citizen);
            if (entity == null) continue;
            if (citizen.targetBlueprintId() != null) {
                if (entity.hasActiveConstructionMovement()) continue;
                if (leaveScaffoldingBeforeLogistics(level, data, settlement, citizen, entity)) continue;
            }
            for (Animal animal : level.getEntitiesOfClass(Animal.class, new AABB(entity.blockPosition()).inflate(32.0D),
                    animal -> animal.isLeashed() && animal.getLeashHolder() == entity)) {
                animal.dropLeash(true, false);
            }
            // Sleeping pauses the task, not the resident's player-like inventory.
            // The active slot is cleared with the task and the item remains in its
            // original backpack slot for normal delivery after waking.
            clear(data, citizen, entity);
        }
    }

    /**
     * Midday/evening leisure hands work back at a safe boundary, but never routes
     * residents to bed. Construction movement is allowed to finish first so a
     * schedule change cannot strand somebody on temporary scaffolding.
     */
    private static void pauseExistingForRest(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement
    ) {
        for (CitizenRecord citizen : settlement.citizens()) {
            if(!workDue(level,citizen))continue;
            if (schedulePhase(level.getDayTime(), citizen) != SchedulePhase.REST
                    || citizen.workState() == CitizenWorkState.TO_DEMOLITION
                    || citizen.workState() == CitizenWorkState.TO_WAREHOUSE
                    || mineRestCollection(level,settlement,citizen)
                    || citizen.workState() == CitizenWorkState.TO_EQUIPMENT
                       && CitizenEquipmentService.missingEquipment(citizen) == null) continue;
            if (!citizen.alive() || citizen.workState() == CitizenWorkState.IDLE) continue;
            SettlementCitizenEntity entity = CitizenProjectionService.findProjection(level, citizen);
            if (entity == null || entity.isDefending()) continue;
            if (citizen.targetBlueprintId() != null) {
                if (entity.hasActiveConstructionMovement()) continue;
                if (leaveScaffoldingBeforeLogistics(level, data, settlement, citizen, entity)) continue;
            }
            for (Animal animal : level.getEntitiesOfClass(Animal.class,
                    new AABB(entity.blockPosition()).inflate(32.0D),
                    animal -> animal.isLeashed() && animal.getLeashHolder() == entity)) {
                animal.dropLeash(true, false);
            }
            clear(data, citizen, entity);
        }
    }

    private static void wakeResidents(ServerLevel level, SettlementSavedData data, SettlementRecord settlement) {
        for (CitizenRecord citizen : settlement.citizens()) {
            if(!workDue(level,citizen))continue;
            if (!citizen.alive()) continue;
            if (schedulePhase(level.getDayTime(), citizen) == SchedulePhase.SLEEP
                    && citizen.workState() != CitizenWorkState.TO_DEMOLITION) continue;
            SettlementCitizenEntity entity = CitizenProjectionService.findProjection(level, citizen);
            if (entity != null && entity.isSleeping()) {
                entity.stopSleeping();
                setActivity(data, citizen, entity, CitizenActivity.IDLE, "");
            }
        }
    }

    @Nullable
    private static BuildingRecord workplace(SettlementRecord settlement, CitizenRecord citizen, BuildingType type) {
        BuildingRecord building = settlement.building(citizen.workBuildingId());
        return usable(building, type) && !com.hkin.exiledsettlement.region.ProductionRegionService.constructionPending(settlement,building) ? building : null;
    }

    @Nullable
    private static BuildingRecord nearestBuilding(SettlementRecord settlement, BlockPos from, BuildingType type) {
        return settlement.buildings().stream().filter(b -> usable(b, type))
                .min(Comparator.comparingDouble(b -> b.position().distSqr(from))).orElse(null);
    }

    private static boolean usable(@Nullable BuildingRecord building) {
        return building != null && building.active() && building.operational();
    }

    private static boolean usable(@Nullable BuildingRecord building, BuildingType type) {
        return usable(building) && (building.type()==type || building.region()!=null && com.hkin.exiledsettlement.region.ProductionRegion.acceptsWorkplace(building.type(),type));
    }

    private static boolean prepareConstructionReach(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord blueprint, CitizenRecord citizen, SettlementCitizenEntity entity,
            BlockPos target, CitizenActivity approachActivity
    ) {
        if (reconcileCollapsedTemporaryFootings(level, blueprint)) {
            // Vanilla can collapse a whole scaffold branch after one support
            // changes. Its persisted records must be reconciled during active
            // construction, not only after the final structure block: phantom
            // scaffold cells otherwise occupy valid access positions and keep
            // the last blueprint target tied to a route that no longer exists.
            entity.finishConstructionMovement();
            CitizenNavigationReservationService.resetStationAfterAccessTopologyChange(
                    level, entity.getUUID());
            data.touch();
        }
        entity.beginConstructionRouteTask(blueprint.id(), target);
        entity.normalizeConstructionStandingPosition();
        BuildingBlueprintTemplate accessTemplate = BlueprintTemplates.create(blueprint);
        BlockPos accessFeet = constructionFeetPosition(entity);
        BlockPos occupiedAccessSupport = occupiedTemporarySupport(
                level, blueprint, accessFeet);
        boolean elevatedTargetNeedsFrameEntry = target.getY()
                >= accessTemplate.minCorner().getY() + 3
                && occupiedAccessSupport == null
                // Height alone must not force a scaffold detour when the
                // resident can already place legally from the current surface.
                && !canConstructFromCurrentPosition(entity, target);
        if (beginFoundationSurfaceRise(level, blueprint, entity, target)) {
            entity.traceConstructionDecision("foundation-surface-rise:"
                    + constructionFeetPosition(entity) + ":target=" + target);
            setActivity(data, citizen, entity, approachActivity, "");
            return false;
        }
        if (!entity.isConstructionAccessRetreating() && entity.constructionTargetAttemptTimedOut()) {
            // A retreat is still part of the current physical route attempt.
            // Checking this only after the retreat branch let a worker shuffle
            // forever along the same scaffold chain: every tiny local leg was
            // progress, but the blueprint block saw no output for thousands of
            // ticks. Retire that route, release the movement, and let the
            // generic target deferral choose another block/approach. Three such
            // failures blacklist the shared physical route for this blueprint.
            entity.recordTrackedConstructionRouteFailure();
            entity.finishConstructionMovement();
            // Retiring a target while the resident is still on its scaffold is
            // not a safe route switch. Preserve retreat mode so the next work
            // cycle walks the recorded branch back to ordinary ground before
            // planning the alternative target. Clearing this latch here let a
            // blacklisted route immediately seed the next target from halfway
            // up the same failed branch.
            entity.beginConstructionAccessRetreat();
            CitizenNavigationReservationService.resetStationAfterAccessTopologyChange(
                    level, entity.getUUID());
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED,
                    "CONSTRUCTION_TARGET_TIMEOUT");
            deferBlockedConstructionTarget(level, data, settlement, blueprint,
                    citizen, entity, target);
            return false;
        }
        if (entity.isConstructionAccessRetreating()) {
            if (entity.continueConstructionStep(WALK_SPEED)) {
                setActivity(data, citizen, entity, approachActivity, "");
                return false;
            }
            BlockPos retreatSupport = occupiedTemporarySupport(
                    level, blueprint, constructionFeetPosition(entity));
            if (retreatSupport == null) {
                entity.finishConstructionAccessRetreat();
                entity.releaseTrackedConstructionRoute();
            } else if (beginTemporaryFootingDescent(blueprint, entity, false)
                    || beginCleanupEgressToSafeSurface(blueprint, entity)) {
                entity.traceConstructionDecision("construction-retreat:" + retreatSupport);
                setActivity(data, citizen, entity, approachActivity, "");
                data.touch();
                return false;
            } else {
                entity.recordConstructionAccessFailure("FOOTING_RETREAT_UNAVAILABLE");
                entity.recordTrackedConstructionRouteFailure();
                entity.finishConstructionAccessRetreat();
                CitizenNavigationReservationService.resetStationAfterAccessTopologyChange(
                        level, entity.getUUID());
            }
        }
        if (entity.hasReadyVerticalPillarSupport()) {
            placeTemporaryFootingStep(level, data, settlement, blueprint, citizen, entity,
                    target, approachActivity);
            return false;
        }
        // A local route may enter a perfectly legal four-block interaction
        // position before reaching its originally selected station. Stop that
        // now-redundant step immediately; forcing the resident to finish a
        // descent or squeeze to the exact station creates the visible edge
        // oscillation and can place its head under the next wall course.
        if (!elevatedTargetNeedsFrameEntry
                && (canConstructFromCurrentPosition(entity, target)
                || canRecoverOwnedScaffoldingFromCurrentPosition(
                level, blueprint, entity, target))) {
            if (!CitizenNavigationReservationService.claimStation(
                    level, entity.getUUID(), entity.blockPosition(), entity.blockPosition(), level.getGameTime())) {
                entity.traceConstructionDecision("reach-current-station-busy:"
                        + entity.blockPosition() + ":target=" + target);
                setActivity(data, citizen, entity, approachActivity, "WORK_STATION_BUSY");
                return false;
            }
            entity.traceConstructionDecision("reach-current:" + entity.blockPosition()
                    + ":target=" + target);
            entity.finishConstructionMovement();
            return true;
        }
        if (entity.continueConstructionStep(WALK_SPEED)) {
            setActivity(data, citizen, entity, approachActivity, "");
            return false;
        }
        // Reaching the apex converts the in-flight vertical rise into a ready
        // support placement. Handle that transition in the same scheduler
        // cycle; otherwise the generic station search below can start another
        // local move and clear the ready scaffold before it is ever placed.
        if (entity.hasReadyVerticalPillarSupport()) {
            placeTemporaryFootingStep(level, data, settlement, blueprint, citizen, entity,
                    target, approachActivity);
            return false;
        }
        if (entity.consumeConstructionMovementFailure()) {
            CitizenNavigationReservationService.resetStationAfterAccessTopologyChange(
                    level, entity.getUUID());
            if (entity.trackedConstructionRouteExhausted()) {
                // Three physical failures retire this route for the current
                // placement task. Defer only the blocked block and immediately
                // release the worker to another generic pending placement.
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED,
                        "CONSTRUCTION_ROUTE_EXHAUSTED");
                deferBlockedConstructionTarget(level, data, settlement, blueprint,
                        citizen, entity, target);
                return false;
            }
            // A rejected one-cell scaffold/catwalk move invalidates only that
            // local station, not the selected blueprint block. Promoting it to
            // STUCK made build() defer the whole target every work cycle, so the
            // worker chased a constantly changing block around the roof. Keep
            // the target and let the generic station/footing planner below try
            // another live route. Only its full route failure may defer work.
            entity.recordConstructionAccessFailure("LOCAL_STEP_REPLAN");
            setActivity(data, citizen, entity, approachActivity, "");
        }
        if (constructionFeetPosition(entity).getY() > target.getY() + 1) {
            entity.beginConstructionAccessRetreat();
            if (beginTemporaryFootingDescent(blueprint, entity)) {
                setActivity(data, citizen, entity, approachActivity, "");
                return false;
            }
            entity.traceConstructionDecision("high-descent-rejected:feet="
                    + constructionFeetPosition(entity) + ":target=" + target
                    + ":movement=" + entity.constructionMovementDiagnostics());
        }
        if (constructionFeetPosition(entity).getY() > target.getY() + 1) {
            // A resident may be standing on a real roof block rather than on a
            // recorded footing chain. Build a bounded descending route from that
            // surface toward the nearest owned scaffold top instead of aiming
            // the catwalk at the unrelated low structure block.
            BlockPos descentTarget = nearestScaffoldingEntry(
                    blueprint, entity, constructionFeetPosition(entity));
            placeTemporaryFootingStep(level, data, settlement, blueprint, citizen,
                    entity, descentTarget == null ? target : descentTarget, approachActivity);
            return false;
        }
        if (beginTemporaryFootingTraversal(
                level, data, settlement, blueprint, citizen, entity, target, approachActivity)) {
            return false;
        }
        BlockPos currentSupport = occupiedTemporarySupport(
                level, blueprint, constructionFeetPosition(entity));
        boolean continuingReservedApproach = !entity.getNavigation().isDone()
                && CitizenNavigationReservationService.stationFor(
                level, entity.getUUID(), level.getGameTime()) != null;
        if (currentSupport != null && !continuingReservedApproach
                && hasImmediateFootingWorkStep(level, settlement, blueprint,
                citizen, entity, target)) {
            // If one adjacent, reversible support immediately creates a legal
            // four-block work station, finish that short connector before
            // retreating or searching for another ground tower. This is the
            // generic roof-edge case: the worker is already on paid access and
            // is one cell short of the target. Replaying the old parent route
            // here wastes a full climb and can blacklist every ground approach
            // without ever trying the physically obvious next scaffold.
            placeTemporaryFootingStep(level, data, settlement, blueprint,
                    citizen, entity, target, approachActivity);
            return false;
        }
        if (currentSupport != null && !continuingReservedApproach) {
            // An ordinary path may legitimately cross an owned scaffold base.
            // Treating that transient overlap as a trapped elevated work pose
            // made the worker descend to the previous cell every scheduler
            // cycle, while the still-valid approach path walked it straight
            // back into the scaffold. Only reverse an access chain after the
            // ordinary navigator has actually finished at that support. A
            // leftover vanilla path without a live station claim is not useful
            // progress and must not suppress escape/replanning.
            BlockPos currentFeet = constructionFeetPosition(entity);
            boolean scaffoldSupport = level.getBlockState(currentSupport).is(Blocks.SCAFFOLDING);
            boolean scaffoldRiseBlocked = scaffoldSupport && currentSupport.equals(currentFeet)
                    && !scaffoldingBodyCellPassable(level, currentFeet.above());
            boolean solidRiseHeadBlocked = !scaffoldSupport
                    && currentSupport.equals(currentFeet.below())
                    && !clearVerticalPillarColumn(level, currentFeet);
            boolean insideScaffoldingRoute = scaffoldSupport;
            boolean trappedAccess = scaffoldRiseBlocked || solidRiseHeadBlocked
                    || !insideScaffoldingRoute
                    && !ConstructionAccessPlanService.hasEgressAfterChange(
                    level, blueprint, entity.blockPosition(), null, null);
            if (trappedAccess) {
                // Finish reversing the live access chain before choosing any
                // other blueprint block. Changing the target while the resident
                // was still elevated made the worker chase distant placements
                // from a route built for the previous block and spend most of a
                // day oscillating between retreat and approach. The original
                // target remains valid; after reaching a safe surface its next
                // station is recomputed from live, template-agnostic geometry.
                entity.beginConstructionAccessRetreat();
            }
            if (trappedAccess && (beginTemporaryFootingDescent(blueprint, entity)
                    || beginCleanupEgressToSafeSurface(blueprint, entity))) {
                // Once a new wall/eave course closes the current platform, do
                // not keep extending scaffolding inside the now head-blocked
                // pocket. Reverse the already recorded access chain first. If a
                // dense scaffold base has no immediately adjacent ground exit,
                // cross that owned network to the nearest real standing surface
                // and then choose a fresh station. This is derived solely from
                // current geometry and works for arbitrary templates.
                entity.recordConstructionAccessFailure("TRAPPED_DESCEND_TO_REPLAN");
                CitizenNavigationReservationService.resetStationAfterAccessTopologyChange(
                        level, entity.getUUID());
                setActivity(data, citizen, entity, approachActivity, "");
                return false;
            }
            if (trappedAccess) {
                BlockPos descentEntry = scaffoldingColumnEntries(
                        blueprint, entity, currentFeet).stream()
                        .filter(entry -> entity.constructionRouteAllowed(target, entry))
                        .findFirst().orElse(null);
                if (descentEntry != null && !descentEntry.equals(currentSupport)) {
                    // A later roof course may seal the persisted parent landing.
                    // Replaying that dead edge makes the resident alternate
                    // between one pocket and new targets forever. Extend a
                    // validated, reversible escape route toward a real owned
                    // scaffold column, then retain retreat mode so the worker
                    // descends instead of immediately re-entering the pocket.
                    // The destination is derived from live access geometry and
                    // contains no template-specific coordinates or ordering.
                    entity.finishConstructionAccessRetreat();
                    entity.trackConstructionRoute(descentEntry, descentEntry);
                    placeTemporaryFootingStep(level, data, settlement, blueprint,
                            citizen, entity, descentEntry, approachActivity);
                    if (entity.hasActiveConstructionMovement()) {
                        entity.beginConstructionAccessRetreat();
                        entity.traceConstructionDecision("trapped-escape-column:"
                                + currentSupport + "->" + descentEntry);
                        return false;
                    }
                    int failures = entity.recordTrackedConstructionRouteFailure();
                    entity.traceConstructionDecision("trapped-escape-rejected:"
                            + currentSupport + "->" + descentEntry
                            + ":failures=" + failures);
                    if (failures < 3) return false;
                }
            }
            if (beginReusableScaffoldingAccess(level, data, settlement, blueprint,
                    citizen, entity, target, approachActivity)) {
                return false;
            }
            // Reusing a proven scaffold branch remains the first choice. Only
            // extend a fresh short branch when the reusable-access planner has
            // found no valid route; otherwise a resident can keep growing the
            // wrong branch and strand itself behind the closing shell.
            if (hasShortFootingRouteFromCurrentPosition(
                    level, settlement, blueprint, citizen, entity, target)) {
                placeTemporaryFootingStep(level, data, settlement, blueprint, citizen,
                        entity, target, approachActivity);
                return false;
            }
            if (beginTemporaryFootingDescent(blueprint, entity)) {
                entity.recordTrackedConstructionRouteFailure();
                entity.beginConstructionAccessRetreat();
                setActivity(data, citizen, entity, approachActivity, "");
                return false;
            }
        }
        BlockPos directWorkPosition = elevatedTargetNeedsFrameEntry ? null
                : reserveConstructionWorkPosition(
                level, entity, target, settlement, blueprint);
        if (directWorkPosition != null) {
            if (!nearWorkPosition(entity, directWorkPosition)) {
                BlockPos feet = constructionFeetPosition(entity);
                if (target.getY() > feet.getY()
                        && directWorkPosition.getY() < feet.getY()
                        && hasShortFootingRouteFromCurrentPosition(
                        level, settlement, blueprint, citizen, entity, target)) {
                    // Stay on the current scaffold or completed roof level when
                    // a legal short access route can reach the target. This must
                    // also apply after the temporary support underneath the
                    // resident has become a real roof block; requiring a live
                    // footing here sent that resident toward an unreachable
                    // ground station on every scheduler refresh.
                    CitizenNavigationReservationService.resetStationAfterAccessTopologyChange(
                            level, entity.getUUID());
                    placeTemporaryFootingStep(level, data, settlement, blueprint,
                            citizen, entity, target, approachActivity);
                    return false;
                }
                // A temporary scaffold is an access route outside/through the
                // evolving shell, not an interior structure target. Sending its
                // cleanup station through the finished building entrance can
                // replace a short valid exterior path with a blocked detour.
                moveToConstructionPosition(level, data, citizen, entity,
                        blueprint.hasTemporaryFooting(target) ? null : blueprint,
                        directWorkPosition, approachActivity);
                // A nominal path can become unusable while the same generic
                // blueprint is closing walls or roof edges around the worker.
                // For an elevated target, a confirmed navigation failure is
                // the signal to build temporary access, not to cycle through
                // every other blueprint block for the rest of the day.
                if (requiresElevatedConstructionAccess(entity, target)
                        && constructionAccessFailure(citizen.workFailure())) {
                    entity.finishConstructionMovement();
                    CitizenNavigationReservationService.abandonStation(
                            level, entity.getUUID(), level.getGameTime());
                    BlockPos recoveryBase = findFootingBasePosition(
                            level, settlement, blueprint, entity, target, true);
                    if (recoveryBase != null && CitizenNavigationReservationService.claimStation(
                            level, entity.getUUID(), recoveryBase,
                            entity.blockPosition(), level.getGameTime())) {
                        entity.traceConstructionDecision("stuck-recover-base:" + recoveryBase
                                + ":target=" + target);
                        if (!nearWorkPosition(entity, recoveryBase)) {
                            moveToConstructionPosition(level, data, citizen, entity,
                                    blueprint, recoveryBase, approachActivity);
                            return false;
                        }
                    }
                    placeTemporaryFootingStep(level, data, settlement, blueprint,
                            citizen, entity, target, approachActivity);
                }
                return false;
            }
            if (canConstructFromCurrentPosition(entity, target)) return true;
        } else {
            BlockPos unclaimedStation = findConstructionWorkPosition(
                    level, entity, target, settlement, blueprint);
            if (unclaimedStation != null
                    && CitizenNavigationReservationService.stationHeldByOther(
                    level, entity.getUUID(), unclaimedStation, level.getGameTime())) {
                setActivity(data, citizen, entity, approachActivity, "WORK_STATION_BUSY");
                return false;
            }
            if (beginReusableScaffoldingAccess(level, data, settlement, blueprint,
                    citizen, entity, target, approachActivity)) {
                entity.traceConstructionDecision("reusable-scaffolding:"
                        + entity.constructionDecisionTrace());
                return false;
            }
            if (!canUseTemporaryConstructionAccess(entity, target)) {
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED,
                        "CONSTRUCTION_POSITION_UNREACHABLE");
                return false;
            }
            BlockPos footingBase = findFootingBasePosition(
                    level, settlement, blueprint, entity, target);
            if (footingBase == null) {
                // A resident already on a finished roof/slab may have no vanilla
                // path back to a ground base, while still being able to extend a
                // perfectly safe adjacent scaffold route. Let the local planner
                // prove and place that route before declaring the target
                // unreachable.
                if (hasShortFootingRouteFromCurrentPosition(
                        level, settlement, blueprint, citizen, entity, target)) {
                    placeTemporaryFootingStep(level, data, settlement, blueprint,
                            citizen, entity, target, approachActivity);
                } else {
                    // A failed target does not prove that the shared exterior
                    // frame is stale. It may already be the valid approach for
                    // dozens of other cells. Never tear a live frame down during
                    // active construction; count this target/route failure and
                    // move to another placement after the three-attempt limit.
                    BlockPos failedRoute = currentConstructionAccessRouteIdentity(
                            blueprint, entity, target);
                    entity.trackConstructionRoute(failedRoute, failedRoute);
                    int routeFailures = entity.recordTrackedConstructionRouteFailure();
                    if (routeFailures >= 3) {
                        // Three failures retire this target/route pair, not the
                        // worksite's shared four-corner scaffold frame. Tearing
                        // every scaffold down here made the resident rebuild the
                        // same tower and ring for the next target, so a stable
                        // frame never existed. Preserve it, leave through a real
                        // bounded route when needed, and select another pending
                        // placement. Final cleanup still recovers the complete
                        // frame directly into the backpack without restrictions.
                        if (beginCleanupEgressToSafeSurface(blueprint, entity)) {
                            entity.traceConstructionDecision("base-route-exhausted-egress:"
                                    + failedRoute + ":target=" + target);
                            CitizenNavigationReservationService
                                    .resetStationAfterAccessTopologyChange(
                                    level, entity.getUUID());
                            setActivity(data, citizen, entity, approachActivity, "");
                            data.touch();
                            return false;
                        }
                        setActivity(data, citizen, entity, CitizenActivity.BLOCKED,
                                "CONSTRUCTION_ROUTE_EXHAUSTED");
                        deferBlockedConstructionTarget(level, data, settlement, blueprint,
                                citizen, entity, target);
                    } else {
                        setActivity(data, citizen, entity, CitizenActivity.BLOCKED,
                                "FOOTING_ROUTE_UNAVAILABLE");
                    }
                }
                return false;
            }
            if (!CitizenNavigationReservationService.claimStation(
                    level, entity.getUUID(), footingBase, entity.blockPosition(), level.getGameTime())) {
                setActivity(data, citizen, entity, approachActivity, "WORK_STATION_BUSY");
                return false;
            }
            if (!nearWorkPosition(entity, footingBase)) {
                moveToConstructionPosition(level, data, citizen, entity, blueprint,
                        footingBase, approachActivity);
                return false;
            }
        }
        if (!canUseTemporaryConstructionAccess(entity, target)) {
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED,
                    "CONSTRUCTION_TARGET_OUT_OF_REACH");
            return false;
        }
        placeTemporaryFootingStep(level, data, settlement, blueprint, citizen, entity, target, approachActivity);
        return false;
    }

    private static boolean reconcileCollapsedTemporaryFootings(
            ServerLevel level, BlueprintRecord blueprint
    ) {
        boolean changed = false;
        for (Map.Entry<BlockPos, Item> entry : new ArrayList<>(
                blueprint.temporaryFootings().entrySet())) {
            if (!level.getBlockState(entry.getKey()).is(Block.byItem(entry.getValue()))) {
                changed |= blueprint.removeTemporaryFooting(entry.getKey());
            }
        }
        return changed;
    }

    /**
     * Reuses an already built scaffold column before reserving another ground
     * base. The candidate is a real, currently placed scaffold cell from which
     * the ordinary four-block interaction geometry and sightline reach the
     * target. A distant worker first walks to a legal ground entry beside the
     * bottom of that continuous column; once adjacent it climbs inside the same
     * column to the useful height. This keeps the access network small without
     * encoding any building-specific coordinates or construction order.
     */
    private static boolean beginReusableScaffoldingAccess(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord blueprint, CitizenRecord citizen, SettlementCitizenEntity entity,
            BlockPos target, CitizenActivity activity
    ) {
        List<BlockPos> workCells = blueprint.temporaryFootings().entrySet().stream()
                .filter(entry -> entry.getValue() == Items.SCAFFOLDING)
                .map(Map.Entry::getKey)
                .filter(position -> level.getBlockState(position).is(Blocks.SCAFFOLDING))
                .filter(position -> !position.equals(target)
                        && !position.above().equals(target)
                        && !position.below().equals(target))
                .filter(position -> constructionReachGeometry(position, target))
                .filter(position -> constructionStationInRange(level, position, target, entity))
                .sorted(Comparator
                        .comparingDouble((BlockPos position) ->
                                constructionTargetDistanceSqr(position, target))
                        .thenComparingDouble(position -> position.distSqr(entity.blockPosition()))
                        .thenComparingLong(BlockPos::asLong))
                .toList();
        if (workCells.isEmpty()) return false;

        BlockPos feet = constructionFeetPosition(entity);
        for (BlockPos workCell : workCells) {
            int bottomY = workCell.getY();
            while (bottomY > level.getMinBuildHeight()
                    && level.getBlockState(new BlockPos(
                    workCell.getX(), bottomY - 1, workCell.getZ())).is(Blocks.SCAFFOLDING)) {
                bottomY--;
            }
            BlockPos bottom = new BlockPos(workCell.getX(), bottomY, workCell.getZ());
            boolean columnRouteAllowed = entity.constructionRouteAllowed(target, bottom);
            boolean alreadyInColumn = feet.getX() == workCell.getX()
                    && feet.getZ() == workCell.getZ()
                    && feet.getY() >= bottomY - 1
                    && feet.getY() <= workCell.getY() + 1;
            int horizontalToColumn = Math.abs(feet.getX() - workCell.getX())
                    + Math.abs(feet.getZ() - workCell.getZ());
            boolean canEnterColumn = alreadyInColumn || horizontalToColumn <= 1
                    && Math.abs(feet.getY() - bottomY) <= 1;
            if (columnRouteAllowed && canEnterColumn) {
                entity.trackConstructionRoute(bottom, bottom);
            }
            if (columnRouteAllowed && canEnterColumn
                    && entity.beginScaffoldingWorkHeight(workCell, WALK_SPEED)) {
                entity.traceConstructionDecision("reuse-direct:" + workCell);
                CitizenNavigationReservationService.resetStationAfterAccessTopologyChange(
                        level, entity.getUUID());
                setActivity(data, citizen, entity, activity, "");
                data.touch();
                return true;
            }
            // A reusable route is not necessarily one vertical column. Roof
            // access commonly ends in a short horizontal or stepped scaffold
            // branch whose parent links still describe an exact reversible
            // route from its root to this useful work cell. Follow that saved
            // topology before asking for a new ground base. The traversal
            // proves every adjacent movement against current world geometry,
            // so a branch closed by later structure blocks is rejected safely.
            if (beginTemporaryFootingTraversal(level, data, settlement, blueprint,
                    citizen, entity, workCell, activity)) {
                CitizenNavigationReservationService.resetStationAfterAccessTopologyChange(
                        level, entity.getUUID());
                return true;
            }

            if (!columnRouteAllowed) continue;
            List<BlockPos> approaches = new ArrayList<>();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos approach = bottom.relative(direction);
                if (entity.canStandAtPosition(approach)
                        && entity.hasReachablePathTo(approach)) {
                    approaches.add(approach.immutable());
                }
            }
            approaches.sort(Comparator
                    .comparingDouble((BlockPos approach) -> approach.distSqr(feet))
                    .thenComparingLong(BlockPos::asLong));
            if (!approaches.isEmpty()) {
                BlockPos approach = approaches.getFirst();
                if (nearWorkPosition(entity, approach)) {
                    entity.trackConstructionRoute(bottom, bottom);
                    if (entity.beginScaffoldingWorkHeight(workCell, WALK_SPEED)) {
                        CitizenNavigationReservationService.resetStationAfterAccessTopologyChange(
                                level, entity.getUUID());
                        setActivity(data, citizen, entity, activity, "");
                        data.touch();
                        return true;
                    }
                    continue;
                }
                moveToConstructionPosition(level, data, citizen, entity, blueprint,
                        approach, activity, bottom);
                return true;
            }
        }

        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        Map<Long, BlockPos> topsByColumn = new HashMap<>();
        for (Map.Entry<BlockPos, Item> entry : blueprint.temporaryFootings().entrySet()) {
            BlockPos position = entry.getKey();
            if (entry.getValue() != Items.SCAFFOLDING
                    || !level.getBlockState(position).is(Blocks.SCAFFOLDING)) continue;
            long key = ((long) position.getX() << 32) ^ (position.getZ() & 0xffffffffL);
            topsByColumn.merge(key, position.immutable(),
                    (first, second) -> first.getY() >= second.getY() ? first : second);
        }
        List<BlockPos> extendableTops = topsByColumn.values().stream()
                .filter(top -> {
                    BlockPos next = top.above();
                    return !blueprint.hasTemporaryFooting(next)
                            && footingAllowedAt(level, settlement, blueprint, template, next)
                            && clearScaffoldingWorkColumn(level, next)
                            && constructionReachGeometry(next, target)
                            && constructionStationInRange(level, next, target, entity);
                })
                .sorted(Comparator
                        .comparingDouble((BlockPos top) ->
                                constructionTargetDistanceSqr(top.above(), target))
                        .thenComparingDouble(top -> top.distSqr(entity.blockPosition()))
                        .thenComparingLong(BlockPos::asLong))
                .toList();
        for (BlockPos top : extendableTops) {
            int bottomY = top.getY();
            while (bottomY > level.getMinBuildHeight()
                    && level.getBlockState(new BlockPos(
                    top.getX(), bottomY - 1, top.getZ())).is(Blocks.SCAFFOLDING)) {
                bottomY--;
            }
            BlockPos bottom = new BlockPos(top.getX(), bottomY, top.getZ());
            if (!entity.constructionRouteAllowed(target, bottom)) continue;
            boolean insideColumn = feet.getX() == top.getX() && feet.getZ() == top.getZ()
                    && feet.getY() >= bottomY - 1 && feet.getY() <= top.getY() + 1;
            if (insideColumn && feet.getY() >= top.getY()) {
                entity.trackConstructionRoute(bottom, bottom);
                CitizenNavigationReservationService.resetStationAfterAccessTopologyChange(
                        level, entity.getUUID());
                placeTemporaryFootingStep(level, data, settlement, blueprint,
                        citizen, entity, target, activity);
                return true;
            }
            if (insideColumn) entity.trackConstructionRoute(bottom, bottom);
            if (insideColumn && entity.beginScaffoldingWorkHeight(top, WALK_SPEED)) {
                CitizenNavigationReservationService.resetStationAfterAccessTopologyChange(
                        level, entity.getUUID());
                setActivity(data, citizen, entity, activity, "");
                data.touch();
                return true;
            }
            List<BlockPos> approaches = new ArrayList<>();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos approach = bottom.relative(direction);
                if (entity.canStandAtPosition(approach)
                        && entity.hasReachablePathTo(approach)) approaches.add(approach.immutable());
            }
            approaches.sort(Comparator
                    .comparingDouble((BlockPos approach) -> approach.distSqr(feet))
                    .thenComparingLong(BlockPos::asLong));
            if (approaches.isEmpty()) continue;
            BlockPos approach = approaches.getFirst();
            if (nearWorkPosition(entity, approach)) {
                entity.trackConstructionRoute(bottom, bottom);
                if (!entity.beginScaffoldingWorkHeight(top, WALK_SPEED)) continue;
                CitizenNavigationReservationService.resetStationAfterAccessTopologyChange(
                        level, entity.getUUID());
                setActivity(data, citizen, entity, activity, "");
                data.touch();
                return true;
            }
            moveToConstructionPosition(level, data, citizen, entity, blueprint,
                    approach, activity, bottom);
            return true;
        }
        return false;
    }

    private static boolean hasShortFootingRouteFromCurrentPosition(
            ServerLevel level, SettlementRecord settlement, BlueprintRecord blueprint,
            CitizenRecord citizen, SettlementCitizenEntity entity, BlockPos target
    ) {
        int footingSlot = findFootingSlot(citizen);
        if (footingSlot < 0) return false;
        Item footing = citizen.inventoryItem(footingSlot).getItem();
        int available = countInventoryItem(citizen, footing);
        return hasPlannedExteriorFootingRoute(level, settlement, blueprint,
                BlueprintTemplates.create(blueprint), entity,
                constructionFeetPosition(entity), target,
                Math.min(MAX_REUSED_FOOTING_ROUTE_DEPTH, available));
    }

    /**
     * Tests only the next physical support, not a scripted template step. The
     * candidate must be adjacent to the resident, placeable with the carried
     * footing state, and make the current blueprint block legally visible inside
     * the ordinary four-block interaction boundary.
     */
    private static boolean hasImmediateFootingWorkStep(
            ServerLevel level, SettlementRecord settlement, BlueprintRecord blueprint,
            CitizenRecord citizen, SettlementCitizenEntity entity, BlockPos target
    ) {
        int footingSlot = findFootingSlot(citizen);
        if (footingSlot < 0
                || !(citizen.inventoryItem(footingSlot).getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        List<FootingStep> candidates = new ArrayList<>();
        Vec3 targetCenter = Vec3.atCenterOf(target);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            addFootingStepCandidate(level, settlement, blueprint, template, entity,
                    entity.blockPosition().below(2).relative(direction), targetCenter, candidates);
            addFootingStepCandidate(level, settlement, blueprint, template, entity,
                    entity.blockPosition().below().relative(direction), targetCenter, candidates);
            addFootingStepCandidate(level, settlement, blueprint, template, entity,
                    entity.blockPosition().relative(direction), targetCenter, candidates);
        }
        BlockState requested = blockItem.getBlock().defaultBlockState();
        return candidates.stream().anyMatch(step ->
                supportedFootingState(level, step.blockPosition(), requested) != null
                        && constructionReachGeometry(step.standingPosition(), target)
                        && constructionStationInRange(
                        level, step.standingPosition(), target, entity));
    }

    /**
     * Walks a resident back down a persisted support chain when the next generic
     * construction target is below the current catwalk. Indoor and outdoor
     * supports use the same template-agnostic rule.
     */
    private static boolean beginTemporaryFootingDescent(
            BlueprintRecord blueprint, SettlementCitizenEntity entity
    ) {
        return beginTemporaryFootingDescent(blueprint, entity, true);
    }

    private static boolean beginTemporaryFootingDescent(
            BlueprintRecord blueprint, SettlementCitizenEntity entity,
            boolean allowNearbyScaffolding
    ) {
        BlockPos feet = constructionFeetPosition(entity);
        BlockPos support = occupiedTemporarySupport(entity.level(), blueprint, feet);
        if (support == null) {
            return allowNearbyScaffolding
                    && beginNearbyScaffoldingDescent(blueprint, entity, feet);
        }
        BlockPos routeRoot = temporaryFootingRoot(blueprint, support);
        // The three-failure rule forbids another approach along a failed route;
        // it must never forbid reversing that route to safety. Applying the
        // blacklist here stranded a resident on the roof forever after the
        // third failed approach, so every later target inherited the same bad
        // standing position and immediately failed again.
        // Escape must retain the failed approach's identity, not replace it
        // with a new route pointing at the base of the same column.
        if (entity.level().getBlockState(support).is(Blocks.SCAFFOLDING)
                && beginOwnedScaffoldingDescentToSafeLanding(
                blueprint, entity, support)) {
            // A continuous scaffold column already provides the shortest safe
            // route to real ground. Prefer that full descent over replaying one
            // persisted parent edge at a time: dense horizontal scaffold
            // junctions can give two neighbouring cells mutually distracting
            // parent chains and make the worker bounce forever above the base.
            // Floating columns without a legal ground landing simply reject
            // this branch and keep the recorded-parent fallback below.
            entity.traceConstructionDecision("descent-scaffold-column:" + support);
            return true;
        }
        BlockPos parent = blueprint.temporaryFootingParent(support);
        if (parent != null) {
            boolean supportIsScaffolding = entity.level().getBlockState(support)
                    .is(Blocks.SCAFFOLDING);
            boolean parentIsScaffolding = entity.level().getBlockState(parent)
                    .is(Blocks.SCAFFOLDING);
            boolean sameColumn = support.getX() == parent.getX()
                    && support.getZ() == parent.getZ();
            if (supportIsScaffolding && parentIsScaffolding && sameColumn
                    && entity.beginScaffoldingWorkHeight(parent, WALK_SPEED)) {
                entity.traceConstructionDecision("descent-parent-column:" + support + "->" + parent);
                return true;
            }
            // Horizontal transfers use the exposed top of the neighbouring
            // support. Entering a scaffold cell is reserved for same-column
            // climbing; doing it sideways adds a needless one-block drop.
            BlockPos landing = parent.above();
            if (parentIsScaffolding
                    && entity.level().getBlockState(landing).is(Blocks.SCAFFOLDING)
                    && entity.beginRecordedConstructionRoute(
                    List.of(landing, parent), WALK_SPEED)) {
                // A dense scaffold junction can have another registered cell
                // directly above the true parent. If the scheduler stops after
                // the horizontal entry, occupiedTemporarySupport observes that
                // upper cell and follows its different parent back to the branch
                // we just left. Keep the side-entry and one-cell descent as one
                // validated route so retreat reaches the persisted parent before
                // topology is examined again.
                entity.traceConstructionDecision("descent-parent-scaffold-junction:"
                        + support + "->" + landing + "->" + parent);
                return true;
            }
            if (entity.beginAdjacentConstructionMovement(landing, WALK_SPEED)) {
                entity.traceConstructionDecision("descent-parent-step:" + support + "->" + landing);
                return true;
            }
            entity.traceConstructionDecision("descent-parent-rejected:" + support + "->" + landing
                    + ":feet=" + feet + ":supportState=" + entity.level().getBlockState(support)
                    + ":parentState=" + entity.level().getBlockState(parent)
                    + ":landingState=" + entity.level().getBlockState(landing)
                    + ":headState=" + entity.level().getBlockState(landing.above())
                    + ":movement=" + entity.constructionMovementDiagnostics());
        }
        int currentOrder = blueprint.temporaryFootingOrder(support);
        boolean olderNeighbor = blueprint.temporaryFootings().keySet().stream()
                .filter(candidate -> blueprint.temporaryFootingOrder(candidate) < currentOrder)
                .map(BlockPos::above)
                // The cell above a lower scaffold may itself be the next
                // scaffold block in a vertical column. That is not an exposed
                // standing surface and must be handled by the column descent,
                // otherwise retreat walks horizontally into the scaffold body.
                .filter(candidate -> !entity.level().getBlockState(candidate)
                        .is(Blocks.SCAFFOLDING))
                .filter(candidate -> Math.abs(candidate.getX() - feet.getX())
                        + Math.abs(candidate.getZ() - feet.getZ()) == 1)
                .filter(candidate -> candidate.getY() >= feet.getY() - 1
                        && candidate.getY() <= feet.getY())
                .sorted(Comparator.comparingInt((BlockPos candidate) ->
                                blueprint.temporaryFootingOrder(candidate.below()))
                        .reversed())
                .anyMatch(candidate -> entity.beginAdjacentConstructionMovement(candidate, WALK_SPEED));
        if (olderNeighbor) {
            entity.traceConstructionDecision("descent-older-neighbor:" + support);
            return true;
        }
        // The root footing has no recorded parent. Step from its top onto a
        // neighboring ground cell so the root is no longer supporting the
        // resident and can be selected for removal.
        for (int drop = 0; drop <= 1; drop++) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos landing = feet.relative(direction).below(drop);
                if (occupiedTemporarySupport(entity.level(), blueprint, landing) == null
                        && entity.beginAdjacentConstructionMovement(landing, WALK_SPEED)) {
                    entity.traceConstructionDecision("descent-root-exit:" + support + "->" + landing);
                    return true;
                }
            }
        }
        // A root created from a finished roof has no temporary parent. Its four
        // immediate exits can later be occupied by stair/eave geometry even
        // though another recorded scaffold column remains reachable across the
        // roof. Reuse that real column before inventing more supports or looping
        // on the same stranded root.
        boolean nearby = allowNearbyScaffolding
                && beginNearbyScaffoldingDescent(blueprint, entity, feet);
        if (nearby) entity.traceConstructionDecision("descent-nearby-scaffold:" + support);
        return nearby;
    }

    private static BlockPos temporaryFootingRoot(
            BlueprintRecord blueprint, BlockPos support
    ) {
        BlockPos root = support.immutable();
        int remaining = blueprint.temporaryFootings().size() + 1;
        while (remaining-- > 0) {
            BlockPos parent = blueprint.temporaryFootingParent(root);
            if (parent == null || parent.equals(root)) return root;
            root = parent.immutable();
        }
        return root;
    }

    /**
     * Chooses an exit that is not another temporary support. The entity-level
     * scaffold controller intentionally accepts any physically standable exit;
     * during construction that may be the neighbouring owned column and make a
     * retreat bounce forever between two scaffold roots.
     */
    private static boolean beginOwnedScaffoldingDescentToSafeLanding(
            BlueprintRecord blueprint, SettlementCitizenEntity entity, BlockPos scaffold
    ) {
        if (!entity.level().getBlockState(scaffold).is(Blocks.SCAFFOLDING)) return false;
        if (beginOwnedScaffoldingNetworkEgress(blueprint, entity)) return true;
        int bottomY = scaffold.getY();
        while (bottomY > entity.level().getMinBuildHeight()
                && entity.level().getBlockState(new BlockPos(
                scaffold.getX(), bottomY - 1, scaffold.getZ())).is(Blocks.SCAFFOLDING)) {
            bottomY--;
        }
        BlockPos bottom = new BlockPos(scaffold.getX(), bottomY, scaffold.getZ());
        if (bottomY > BlueprintTemplates.create(blueprint).anchor().getY()) {
            // This is a floating roof spur. Its adjacent roof cell is not a safe
            // ground landing, even if it is physically standable. Let the
            // persisted parent-chain/nearby grounded-column logic handle it;
            // otherwise the resident endlessly drops one block onto the roof
            // and climbs straight back into the same short column.
            return false;
        }
        List<BlockPos> landings = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos landing = bottom.relative(direction);
            if (occupiedTemporarySupport(entity.level(), blueprint, landing) == null
                    && entity.canStandAtPosition(landing)) {
                landings.add(landing.immutable());
            }
        }
        landings.sort(Comparator
                .comparingDouble((BlockPos landing) -> landing.distSqr(entity.blockPosition()))
                .thenComparingLong(BlockPos::asLong));
        if (!landings.isEmpty()) {
            return entity.beginScaffoldingDescentTo(
                    scaffold, landings.getFirst(), WALK_SPEED);
        }
        // Several access towers can form a dense scaffold carpet at ground
        // level. Requiring a non-scaffold landing immediately beside this one
        // column leaves the resident stranded at roof height even though the
        // continuous shaft itself is safe. Descend to its bottom work cell
        // first; the following scheduler cycle can traverse the persisted
        // ground-level support network to an exposed edge and then remove a
        // reachable root from below.
        return entity.getY() > bottom.getY() + 0.05D
                && entity.beginScaffoldingWorkHeight(bottom, WALK_SPEED);
    }

    /**
     * Follows the live connected scaffold body to an exposed ground-level edge.
     * This deliberately ignores placement order: dense columns and catwalks can
     * have parent links that point back upward even though their physical
     * scaffold network provides a direct route down. Every cell is still
     * revalidated by the entity's recorded-route controller before movement.
     */
    private static boolean beginOwnedScaffoldingNetworkEgress(
            BlueprintRecord blueprint, SettlementCitizenEntity entity
    ) {
        BlockPos feet = constructionFeetPosition(entity);
        BlockPos start = occupiedTemporarySupport(entity.level(), blueprint, feet);
        if (start == null || !entity.level().getBlockState(start).is(Blocks.SCAFFOLDING)) {
            return false;
        }
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(start.immutable());
        visited.add(start.immutable());
        BlockPos exitScaffold = null;
        BlockPos exitLanding = null;
        int examined = 0;
        while (!queue.isEmpty() && examined++ < 512) {
            BlockPos current = queue.removeFirst();
            if (current.getY() <= template.minCorner().getY() + 1) {
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    BlockPos landing = current.relative(direction);
                    if (occupiedTemporarySupport(entity.level(), blueprint, landing) == null
                            && entity.canStandAtPosition(landing)) {
                        exitScaffold = current.immutable();
                        exitLanding = landing.immutable();
                        queue.clear();
                        break;
                    }
                }
                if (exitLanding != null) break;
            }
            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction).immutable();
                if (visited.contains(next)
                        || !blueprint.hasTemporaryFooting(next)
                        || !entity.level().getBlockState(next).is(Blocks.SCAFFOLDING)) continue;
                visited.add(next);
                parents.put(next, current);
                queue.addLast(next);
            }
        }
        if (exitScaffold == null || exitLanding == null) return false;
        List<BlockPos> route = new ArrayList<>();
        for (BlockPos cursor = exitScaffold; !cursor.equals(start); cursor = parents.get(cursor)) {
            if (cursor == null) return false;
            route.add(cursor);
        }
        java.util.Collections.reverse(route);
        if (!feet.equals(start)) route.addFirst(start.immutable());
        route.add(exitLanding);
        if (!entity.beginRecordedConstructionRoute(route, WALK_SPEED)) return false;
        entity.traceConstructionDecision("scaffold-network-egress:" + start
                + "->" + exitScaffold + "->" + exitLanding
                + ":steps=" + route.size());
        return true;
    }

    /**
     * Leaves a temporary root support by following any short walkable route over
     * completed structure or ground. This is the cleanup counterpart of the
     * ordinary local construction planner: it only chooses a destination that is
     * not itself supported by a registered footing, and the entity still proves
     * every one-block transition before moving.
     */
    private static boolean beginCleanupEgressToSafeSurface(
            BlueprintRecord blueprint, SettlementCitizenEntity entity
    ) {
        BlockPos feet = constructionFeetPosition(entity);
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        if (occupiedTemporarySupport(entity.level(), blueprint, feet) == null
                && feet.getY() <= template.minCorner().getY() + 1) return false;
        List<BlockPos> candidates = new ArrayList<>();
        int minY = Math.max(entity.level().getMinBuildHeight(), template.minCorner().getY() - 1);
        int maxY = Math.min(entity.level().getMaxBuildHeight() - 1, template.maxCorner().getY() + 2);
        for (int y = minY; y <= maxY; y++) {
            for (int x = template.minCorner().getX() - 2; x <= template.maxCorner().getX() + 2; x++) {
                for (int z = template.minCorner().getZ() - 2; z <= template.maxCorner().getZ() + 2; z++) {
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (candidate.equals(feet)
                            // Merely walking to another cell on the same roof is
                            // not egress. It used to return success here, finish
                            // immediately, and select another same-height roof
                            // cell forever. A real egress leg must reduce the
                            // worker's elevation; otherwise the scaffold descent
                            // and reversible access planners below need control.
                            || candidate.getY() >= feet.getY()
                            || candidate.distManhattan(feet) > 18
                            || occupiedTemporarySupport(entity.level(), blueprint, candidate) != null
                            || !entity.canStandAtPosition(candidate)) continue;
                    candidates.add(candidate.immutable());
                }
            }
        }
        candidates.sort(Comparator
                // Cleanup is an egress operation. Prefer the lowest reachable
                // real surface, so a resident standing on the finished roof
                // walks down through a valid stair/door route before choosing
                // another roof cell that is merely closer.
                .comparingInt((BlockPos candidate) -> candidate.getY())
                .thenComparingDouble(candidate -> candidate.distSqr(feet))
                .thenComparingLong(candidate -> candidate.asLong()));
        // Use the collision-checked construction route here. Vanilla ground
        // navigation can return a nominal path from an elevated roof to a lower
        // interior cell even though no executable first step exists; accepting
        // that path made cleanup resubmit the same inactive command forever.
        // If no bounded physical descent exists, return false so the caller can
        // connect to an owned scaffold column instead.
        for (BlockPos candidate : candidates) {
            if (entity.beginLocalConstructionRoute(candidate, WALK_SPEED)) {
                entity.traceConstructionDecision("cleanup-egress:" + feet + "->" + candidate);
                return true;
            }
        }
        return beginSafeConstructionDrop(blueprint, entity);
    }

    /**
     * Finds a real roof edge without knowing the building type or template
     * order, then performs one bounded player-like drop. A worker already at an
     * edge drops immediately; otherwise the ordinary local route controller
     * first walks across live completed geometry to the best launch cell.
     */
    private static boolean beginSafeConstructionDrop(
            BlueprintRecord blueprint, SettlementCitizenEntity entity
    ) {
        BlockPos feet = constructionFeetPosition(entity);
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        BlockPos immediateLanding = constructionDropLanding(entity, feet);
        if (immediateLanding != null
                && entity.beginConstructionDropMovement(immediateLanding, WALK_SPEED)) {
            entity.traceConstructionDecision("cleanup-drop:" + feet + "->" + immediateLanding);
            return true;
        }

        List<ConstructionDropRoute> routes = new ArrayList<>();
        int y = feet.getY();
        int minX = template.minCorner().getX() - 2;
        int maxX = template.maxCorner().getX() + 2;
        int minZ = template.minCorner().getZ() - 2;
        int maxZ = template.maxCorner().getZ() + 2;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos launch = new BlockPos(x, y, z);
                if (launch.equals(feet) || launch.distManhattan(feet) > 18
                        || !entity.canUseConstructionSurfaceAtPosition(launch)) continue;
                BlockPos landing = constructionDropLanding(entity, launch);
                if (landing != null) routes.add(new ConstructionDropRoute(
                        launch.immutable(), landing.immutable()));
            }
        }
        routes.sort(Comparator
                .comparingInt((ConstructionDropRoute route) -> route.landing().getY())
                .thenComparingDouble(route -> route.launch().distSqr(feet))
                .thenComparingLong(route -> route.launch().asLong()));
        for (ConstructionDropRoute route : routes) {
            if (entity.beginLocalConstructionRoute(route.launch(), WALK_SPEED)) {
                entity.traceConstructionDecision("cleanup-drop-approach:" + feet
                        + "->" + route.launch() + "->" + route.landing());
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static BlockPos constructionDropLanding(
            SettlementCitizenEntity entity, BlockPos launch
    ) {
        List<BlockPos> candidates = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos adjacent = launch.relative(direction);
            for (int drop = 2; drop <= 6; drop++) {
                BlockPos landing = adjacent.below(drop);
                if (entity.canStandAtPosition(landing)) {
                    candidates.add(landing.immutable());
                    break;
                }
            }
        }
        candidates.sort(Comparator
                .comparingInt((BlockPos position) -> position.getY())
                .thenComparingLong(BlockPos::asLong));
        return candidates.stream().filter(landing ->
                entity.canBeginConstructionDropFrom(launch, landing)).findFirst().orElse(null);
    }

    private record ConstructionDropRoute(BlockPos launch, BlockPos landing) {
    }

    /**
     * A roof can be part of the final structure rather than a registered support.
     * In that case enter the nearest owned scaffolding column at the resident's
     * current height (or just below it) and descend through that column.
     */
    private static boolean beginNearbyScaffoldingDescent(
            BlueprintRecord blueprint, SettlementCitizenEntity entity, BlockPos feet
    ) {
        return beginNearbyScaffoldingDescent(blueprint, entity, feet, false);
    }

    private static boolean beginNearbyScaffoldingDescent(
            BlueprintRecord blueprint, SettlementCitizenEntity entity, BlockPos feet,
            boolean allowPartialRoofRoutes
    ) {
        List<BlockPos> entries = scaffoldingColumnEntries(blueprint, entity, feet);
        for (BlockPos scaffold : entries) {
            BlockPos routeRoot = temporaryFootingRoot(blueprint, scaffold);
            // Three failures forbid another approach along this tower, never
            // the reverse trip to safety. Cleanup and retreat must be able to
            // enter and descend an old column even after its upward route was
            // exhausted, otherwise the worker remains stranded on the roof.
            entity.trackConstructionRoute(routeRoot, routeRoot);
            if (beginOwnedScaffoldingDescentToSafeLanding(
                    blueprint, entity, scaffold)) return true;
            BlockPos dropLaunch = findReachableScaffoldingDropLaunch(
                    entity, scaffold, allowPartialRoofRoutes);
            if (dropLaunch != null && !nearWorkPosition(entity, dropLaunch)) {
                if (entity.beginLocalConstructionRoute(dropLaunch, WALK_SPEED)) return true;
                if (entity.hasReachablePathTo(dropLaunch)
                        && entity.navigateTo(dropLaunch, WALK_SPEED).isEmpty()) return true;
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                // The scaffold cell supports the roof-level standing cell. Its
                // same-height neighbour is commonly occupied by the roof itself;
                // approach from the walkable cell one block above instead.
                BlockPos approach = scaffold.relative(direction).above();
                if (!entity.canStandAtPosition(approach)) continue;
                if (entity.beginAdjacentConstructionMovement(approach, WALK_SPEED)
                        || entity.beginLocalConstructionRoute(approach, WALK_SPEED)) return true;
                if (entity.hasReachablePathTo(approach)
                        && entity.navigateTo(approach, WALK_SPEED).isEmpty()) return true;
            }
        }
        return false;
    }

    @Nullable
    private static BlockPos findReachableScaffoldingDropLaunch(
            SettlementCitizenEntity entity, BlockPos scaffold,
            boolean allowPartialRoofRoutes
    ) {
        BlockPos start = constructionFeetPosition(entity);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, Integer> depths = new HashMap<>();
        queue.add(start.immutable());
        depths.put(start.immutable(), 0);
        int examined = 0;
        while (!queue.isEmpty() && examined++ < 256) {
            BlockPos current = queue.removeFirst();
            int depth = depths.get(current);
            if (entity.canEnterScaffoldingDescentFrom(scaffold, current)) return current.immutable();
            if (depth >= 16) continue;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                for (int rise = -1; rise <= 1; rise++) {
                    BlockPos next = current.relative(direction).above(rise).immutable();
                    if (depths.containsKey(next)
                            || next.distManhattan(start) > 18
                            || Math.abs(next.getY() - start.getY()) > 8
                            || !(allowPartialRoofRoutes
                            ? entity.canOccupyConstructionPosition(next)
                            : entity.canStandAtPosition(next))) continue;
                    depths.put(next, depth + 1);
                    queue.addLast(next);
                }
            }
        }
        // Slabs and stairs are walkable to Minecraft's navigator even though
        // their standing surface is not the full-block Y assumed by the local
        // construction grid. Probe those roof cells with vanilla pathfinding,
        // then switch to the controlled scaffold descent only after arrival.
        List<BlockPos> vanillaRoofLaunches = new ArrayList<>();
        int highestLaunchY = Math.min(scaffold.getY() + 8, start.getY() + 1);
        for (int y = scaffold.getY(); y <= highestLaunchY; y++) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos candidate = new BlockPos(
                        scaffold.getX(), y, scaffold.getZ()).relative(direction);
                if (entity.canUseScaffoldingDescentLaunch(scaffold, candidate)) {
                    vanillaRoofLaunches.add(candidate.immutable());
                }
            }
        }
        vanillaRoofLaunches.sort(Comparator.comparingDouble(
                candidate -> candidate.distSqr(start)));
        for (BlockPos candidate : vanillaRoofLaunches) {
            if (entity.hasReachablePathTo(candidate)) return candidate;
        }
        return null;
    }

    @Nullable
    private static BlockPos nearestScaffoldingEntry(
            BlueprintRecord blueprint, SettlementCitizenEntity entity, BlockPos feet
    ) {
        return scaffoldingColumnEntries(blueprint, entity, feet).stream().findFirst().orElse(null);
    }

    private static List<BlockPos> scaffoldingColumnEntries(
            BlueprintRecord blueprint, SettlementCitizenEntity entity, BlockPos feet
    ) {
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        Map<Long, BlockPos> highestReachableByColumn = new HashMap<>();
        Map<Long, Integer> lowestByColumn = new HashMap<>();
        for (BlockPos position : blueprint.temporaryFootings().keySet()) {
            if (position.getY() > feet.getY()
                    || !entity.level().getBlockState(position).is(Blocks.SCAFFOLDING)) continue;
            long columnKey = ((long) position.getX() << 32) ^ (position.getZ() & 0xffffffffL);
            highestReachableByColumn.merge(columnKey, position.immutable(),
                    (first, second) -> first.getY() >= second.getY() ? first : second);
            lowestByColumn.merge(columnKey, position.getY(), Math::min);
        }
        return highestReachableByColumn.values().stream()
                // A lone scaffold beside the roof is a bridge cell, not a
                // descent route. Selecting it forever prevents the resident
                // from continuing toward the older lower column.
                .filter(position -> lowestByColumn.getOrDefault(
                        ((long) position.getX() << 32) ^ (position.getZ() & 0xffffffffL),
                        position.getY()) <= template.anchor().getY())
                .sorted(Comparator
                        // A temporary roof spur that drops only one or two cells
                        // is a work platform, not an exit. Prefer columns whose
                        // live scaffold body reaches the building's ground level,
                        // then choose the nearest useful entry on that real shaft.
                        .comparingInt((BlockPos standing) -> lowestByColumn.getOrDefault(
                                ((long) standing.getX() << 32)
                                        ^ (standing.getZ() & 0xffffffffL), standing.getY()))
                        .thenComparingDouble(standing -> standing.distSqr(feet))
                        .thenComparing(Comparator.comparingInt(
                                (BlockPos standing) -> standing.getY()).reversed()))
                .toList();
    }

    /**
     * Finds a roof/eave cell that is connected through real standable surfaces
     * and has room for the first one-block descending support. This prevents the
     * cleanup planner from trying to place a scaffold inside the finished roof.
     */
    @Nullable
    private static BlockPos findReachableDescentLaunch(
            ServerLevel level, SettlementRecord settlement, BlueprintRecord blueprint,
            CitizenRecord citizen, SettlementCitizenEntity entity, BlockPos descentEntry
    ) {
        BlockPos start = constructionFeetPosition(entity);
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, Integer> depths = new HashMap<>();
        queue.add(start.immutable());
        depths.put(start.immutable(), 0);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        int examined = 0;
        while (!queue.isEmpty() && examined++ < 256) {
            BlockPos current = queue.removeFirst();
            int depth = depths.get(current);
            if (hasDescendingFootingLaunch(
                    level, settlement, blueprint, template, citizen, current)) {
                double distance = current.distSqr(descentEntry);
                if (distance < bestDistance) {
                    best = current.immutable();
                    bestDistance = distance;
                }
            }
            if (depth >= 16) continue;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                for (int rise = -1; rise <= 1; rise++) {
                    BlockPos next = current.relative(direction).above(rise).immutable();
                    if (depths.containsKey(next)
                            || next.distManhattan(start) > 18
                            || Math.abs(next.getY() - start.getY()) > 8
                            || !entity.canStandAtPosition(next)) continue;
                    depths.put(next, depth + 1);
                    queue.addLast(next);
                }
            }
        }
        return best;
    }

    private static boolean hasDescendingFootingLaunch(
            ServerLevel level, SettlementRecord settlement, BlueprintRecord blueprint,
            BuildingBlueprintTemplate template, CitizenRecord citizen, BlockPos feet
    ) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (BlockPos support : List.of(
                    feet.below(2).relative(direction),
                    feet.below().relative(direction),
                    feet.relative(direction))) {
                BlockPos standing = support.above();
                BlockPos head = standing.above();
                if (settlement.contains(support)
                        && level.getBlockState(support).isAir()
                        && level.getFluidState(support).isEmpty()
                        && level.getBlockState(standing).getCollisionShape(level, standing).isEmpty()
                        && level.getFluidState(standing).isEmpty()
                        && level.getBlockState(head).getCollisionShape(level, head).isEmpty()
                        && level.getFluidState(head).isEmpty()
                        && footingAllowedAt(level, settlement, blueprint, template, support)
                        && citizenHasSupportedFootingAt(level, citizen, support)) return true;
            }
        }
        return false;
    }

    private static boolean citizenHasSupportedFootingAt(
            ServerLevel level, CitizenRecord citizen, BlockPos support
    ) {
        for (int slot = 0; slot < CitizenRecord.INVENTORY_SIZE; slot++) {
            ItemStack stack = citizen.inventoryItem(slot);
            if (stack.isEmpty() || !SAFE_FOOTING_ITEMS.contains(stack.getItem())
                    || !(stack.getItem() instanceof BlockItem blockItem)) continue;
            if (supportedFootingState(
                    level, support, blockItem.getBlock().defaultBlockState()) != null) return true;
        }
        return false;
    }

    /**
     * Reuses the persisted scaffold lineage while cleaning a high leaf.
     * The selected leaf is approached from its parent rather than by inventing a
     * second scaffold or trying to path through the completed building.
     */
    private static boolean beginTemporaryFootingTraversal(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord blueprint, CitizenRecord citizen, SettlementCitizenEntity entity,
            BlockPos target, CitizenActivity activity
    ) {
        BlockPos targetParent = blueprint.temporaryFootingParent(target);
        if (targetParent == null) return false;

        List<BlockPos> chain = new ArrayList<>();
        BlockPos cursor = targetParent;
        while (cursor != null && blueprint.hasTemporaryFooting(cursor)
                && chain.size() <= blueprint.temporaryFootings().size()) {
            chain.add(cursor.immutable());
            cursor = blueprint.temporaryFootingParent(cursor);
        }
        if (chain.isEmpty()) return false;
        java.util.Collections.reverse(chain);

        BlockPos feet = constructionFeetPosition(entity);
        BlockPos currentSupport = occupiedTemporarySupport(level, blueprint, feet);
        if (currentSupport != null) {
            int chainIndex = chain.indexOf(currentSupport);
            if (chainIndex >= 0) {
                if (chainIndex == chain.size() - 1) return false;
                // Once the worker has reached the useful height of a scaffold
                // branch, traverse as much of the already-built safe route as
                // possible in one continuous local movement. Advancing exactly
                // one saved parent link per work refresh made long catwalks take
                // hundreds of retries and repeatedly re-enter their root column.
                // The local controller still validates every intermediate cell
                // against live collision/headroom, so this is generic geometry
                // reuse rather than a template-specific shortcut.
                List<BlockPos> recordedRoute = new ArrayList<>();
                BlockPos previousStanding = level.getBlockState(currentSupport)
                        .is(Blocks.SCAFFOLDING) ? currentSupport : currentSupport.above();
                for (int index = chainIndex + 1; index < chain.size(); index++) {
                    BlockPos candidateSupport = chain.get(index);
                    BlockPos candidateStanding = level.getBlockState(candidateSupport)
                            .is(Blocks.SCAFFOLDING)
                            ? candidateSupport : candidateSupport.above();
                    int horizontal = Math.abs(candidateStanding.getX() - previousStanding.getX())
                            + Math.abs(candidateStanding.getZ() - previousStanding.getZ());
                    int rise = candidateStanding.getY() - previousStanding.getY();
                    boolean scaffoldColumnLeg = horizontal == 0 && Math.abs(rise) == 1
                            && level.getBlockState(previousStanding).is(Blocks.SCAFFOLDING)
                            && level.getBlockState(candidateStanding).is(Blocks.SCAFFOLDING);
                    // The entity route controller handles same-column scaffold
                    // rise/descent and ordinary adjacent steps as one continuous
                    // persisted route. Stop only when the live world no longer
                    // matches either legal transition.
                    if (!scaffoldColumnLeg && horizontal != 1
                            || rise < -1 || rise > 1) break;
                    recordedRoute.add(candidateStanding.immutable());
                    previousStanding = candidateStanding;
                }
                BlockPos routeEnd = recordedRoute.isEmpty() ? null : recordedRoute.getLast();
                // All variants that enter through the same persisted scaffold
                // root are one route branch. Otherwise the planner could evade
                // the three-failure limit by choosing a neighbouring endpoint
                // on the same unusable branch every refresh.
                BlockPos routeKey = chain.getFirst();
                boolean recordedRouteAllowed = routeEnd != null
                        && entity.constructionRouteAllowed(target, routeKey);
                if (recordedRouteAllowed) {
                    entity.trackConstructionRoute(routeKey);
                }
                if (recordedRouteAllowed
                        && entity.beginRecordedConstructionRoute(recordedRoute, WALK_SPEED)) {
                    entity.traceConstructionDecision("traverse-recorded:" + currentSupport
                            + "->" + routeEnd);
                    setActivity(data, citizen, entity, activity, "");
                    return true;
                }
                if (routeEnd != null && !recordedRouteAllowed) return false;
                BlockPos nextSupport = chain.get(chainIndex + 1);
                boolean nextIsScaffolding = level.getBlockState(nextSupport).is(Blocks.SCAFFOLDING);
                boolean movingInSameColumn = currentSupport.getX() == nextSupport.getX()
                        && currentSupport.getZ() == nextSupport.getZ();
                boolean began = nextIsScaffolding && movingInSameColumn
                        ? entity.beginScaffoldingWorkHeight(nextSupport, WALK_SPEED)
                        : entity.beginAdjacentConstructionMovement(
                        nextIsScaffolding ? nextSupport : nextSupport.above(), WALK_SPEED);
                if (began) {
                    entity.traceConstructionDecision("traverse-next:" + currentSupport
                            + "->" + nextSupport);
                    setActivity(data, citizen, entity, activity, "");
                    return true;
                }
                return false;
            }
            boolean insideUnrelatedScaffold = currentSupport.equals(feet)
                    && level.getBlockState(currentSupport).is(Blocks.SCAFFOLDING);
            if (insideUnrelatedScaffold || !entity.canStandAtPosition(feet)) {
                // During cleanup the resident must leave an unrelated access
                // branch before dismantling it. During active construction that
                // same scaffold is already a valid, paid-for platform: forcing
                // a descent before the generic short-route planner can extend
                // it creates a ground->column->ground loop at roof height.
                // Stay on it and let the caller try another reusable cell or a
                // bounded new branch. No template-specific route is introduced.
                if (activity == CitizenActivity.DEMOLISHING
                        && beginTemporaryFootingDescent(blueprint, entity, false)) {
                    entity.traceConstructionDecision("traverse-descend-from:" + currentSupport);
                    setActivity(data, citizen, entity, activity, "");
                    return true;
                }
                return false;
            }
            // A scaffold can remain immediately below/alongside a resident who
            // has already stepped onto a genuine roof or floor cell. That is a
            // safe standing surface, not an active access pose. Forcing a
            // descent through the unrelated old chain here created a silent
            // roof-to-scaffold-to-roof loop. Continue below and approach the
            // selected target chain from this real standing cell instead.
        }

        BlockPos root = chain.getFirst();
        if (!entity.constructionRouteAllowed(target, root)) return false;
        if (level.getBlockState(root).is(Blocks.SCAFFOLDING)) {
            entity.trackConstructionRoute(root, root);
            if (entity.beginScaffoldingWorkHeight(root, WALK_SPEED)) {
                entity.traceConstructionDecision("traverse-root-climb:" + root);
                setActivity(data, citizen, entity, activity, "");
                return true;
            }
            int bottomY = root.getY();
            while (bottomY > level.getMinBuildHeight()
                    && level.getBlockState(new BlockPos(
                    root.getX(), bottomY - 1, root.getZ())).is(Blocks.SCAFFOLDING)) {
                bottomY--;
            }
            BlockPos bottom = new BlockPos(root.getX(), bottomY, root.getZ());
            List<BlockPos> approaches = new ArrayList<>();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos approach = bottom.relative(direction);
                if (entity.canStandAtPosition(approach)) approaches.add(approach.immutable());
            }
            approaches.sort(Comparator.comparingDouble(
                    approach -> approach.distSqr(entity.blockPosition())));
            for (BlockPos approach : approaches) {
                if (nearWorkPosition(entity, approach)) continue;
                entity.trackConstructionRoute(root, root);
                if (entity.beginLocalConstructionRoute(approach, WALK_SPEED)) {
                    entity.traceConstructionDecision("traverse-root-local:" + approach);
                    setActivity(data, citizen, entity, activity, "");
                    return true;
                }
                if (entity.hasReachablePathTo(approach)) {
                    // The root approach was proved against the actual scaffold
                    // base. Do not reroute it through the building entrance.
                    moveToConstructionPosition(level, data, citizen, entity, null,
                            approach, activity, root);
                    entity.traceConstructionDecision("traverse-root-nav:" + approach);
                    return true;
                }
            }
            return false;
        }

        BlockPos rootStanding = root.above();
        entity.trackConstructionRoute(root, root);
        if (entity.beginAdjacentConstructionMovement(rootStanding, WALK_SPEED)) {
            setActivity(data, citizen, entity, activity, "");
            return true;
        }
        if (entity.beginLocalConstructionRoute(rootStanding, WALK_SPEED)) {
            setActivity(data, citizen, entity, activity, "");
            return true;
        }
        if (entity.hasReachablePathTo(rootStanding)) {
            moveToConstructionPosition(level, data, citizen, entity, null,
                    rootStanding, activity, root);
            return true;
        }
        BlockPos approach = findConstructionWorkPosition(
                level, entity, chain.getFirst(), settlement, blueprint);
        if (approach != null) {
            moveToConstructionPosition(level, data, citizen, entity, blueprint,
                    approach, activity, root);
            return true;
        }
        return false;
    }

    private static BlockPos constructionFeetPosition(SettlementCitizenEntity entity) {
        // Construction can replace a temporary support underneath the worker
        // between scheduler cycles. Reading the raw, slightly fractional Y in
        // that instant may report the just-replaced solid block as the feet cell,
        // while the local movement controller first normalizes the worker onto
        // its top. Plan and execute from the same real standing cell.
        return entity.normalizeConstructionStandingPosition();
    }

    /**
     * A solid temporary footing supports the resident from the cell below its
     * feet. Vanilla scaffolding is different: the resident climbs inside the
     * scaffold cell itself. Keep that distinction in one place so construction,
     * cleanup and work/rest recovery all agree on the active access chain.
     */
    @Nullable
    private static BlockPos occupiedTemporarySupport(
            net.minecraft.world.level.Level level, BlueprintRecord blueprint, BlockPos feet
    ) {
        if (blueprint.hasTemporaryFooting(feet)
                && level.getBlockState(feet).is(Blocks.SCAFFOLDING)) {
            return feet.immutable();
        }
        BlockPos below = feet.below();
        return ConstructionSiteController.recordedSupport(level,blueprint,below)
                ? below.immutable() : null;
    }

    private static boolean prepareDemolitionReach(
            ServerLevel level, SettlementSavedData data, CitizenRecord citizen,
            SettlementCitizenEntity entity, BlockPos target
    ) {
        if (!entity.hasActiveConstructionMovement()
                && canWorkComfortablyFromCurrentPosition(entity, target)) {
            if (!CitizenNavigationReservationService.claimStation(
                    level, entity.getUUID(), entity.blockPosition(), entity.blockPosition(), level.getGameTime())) {
                setActivity(data, citizen, entity, CitizenActivity.DEMOLISHING, "WORK_STATION_BUSY");
                return false;
            }
            entity.finishConstructionMovement();
            return true;
        }
        if (entity.continueConstructionStep(WALK_SPEED)) {
            setActivity(data, citizen, entity, CitizenActivity.DEMOLISHING, "");
            return false;
        }
        BlockPos workPosition = reserveConstructionWorkPosition(level, entity, target, null);
        if (workPosition == null) {
            if (findConstructionWorkPosition(level, entity, target) != null) {
                setActivity(data, citizen, entity, CitizenActivity.DEMOLISHING, "WORK_STATION_BUSY");
                return false;
            }
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED,
                    "CONSTRUCTION_POSITION_UNREACHABLE");
            return false;
        }
        if (!nearWorkPosition(entity, workPosition)) {
            moveToConstructionPosition(level, data, citizen, entity, workPosition, CitizenActivity.DEMOLISHING);
            return false;
        }
        if (canConstructFromCurrentPosition(entity, target)) return true;
        setActivity(data, citizen, entity, CitizenActivity.BLOCKED,
                "CONSTRUCTION_TARGET_OUT_OF_REACH");
        return false;
    }

    public static boolean requiresElevatedConstructionAccess(
            SettlementCitizenEntity entity, BlockPos target
    ) {
        return target.getY() >= entity.getBlockY() + 2;
    }

    private static boolean canUseTemporaryConstructionAccess(
            SettlementCitizenEntity entity, BlockPos target
    ) {
        return target.getY() >= entity.getBlockY();
    }

    /**
     * After placing foundation cells, leave the unbuilt trench and step onto a
     * neighbouring completed floor top. Remaining floor work is then approached
     * across a real walkable surface, exactly as a player would build outward,
     * instead of treating every new floor block as a wall around the worker.
     */
    private static boolean beginFoundationSurfaceRise(
            ServerLevel level, BlueprintRecord blueprint,
            SettlementCitizenEntity entity, BlockPos target
    ) {
        // Keep the in-flight physical step and its timeout intact. The work
        // scheduler revisits this branch before continueConstructionStep.
        if (entity.hasActiveConstructionMovement() || entity.isConstructionAccessRetreating()) return false;
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        BlockPos feet = constructionFeetPosition(entity);
        if (target.getY() > template.minCorner().getY() + 1
                || feet.getY() > template.minCorner().getY()) return false;
        List<BlockPos> landings = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos support = feet.relative(direction);
            BlueprintPlacement planned = template.placementAt(support);
            BlockPos landing = support.above();
            if (planned == null || !planned.mode().isFinalStructure()
                    || planned.state().isAir()
                    || level.getBlockState(support).getCollisionShape(level, support).isEmpty()
                    || !entity.canUseConstructionSurfaceAtPosition(landing)) continue;
            landings.add(landing.immutable());
        }
        landings.sort(Comparator
                .comparingDouble((BlockPos landing) -> landing.distSqr(target))
                .thenComparingLong(BlockPos::asLong));
        for (BlockPos landing : landings) {
            if (entity.beginAdjacentConstructionMovement(landing, WALK_SPEED)) return true;
        }
        return false;
    }

    @Nullable
    private static BlockPos findFootingBasePosition(
            ServerLevel level, SettlementRecord settlement, BlueprintRecord blueprint,
            SettlementCitizenEntity entity, BlockPos target
    ) {
        return findFootingBasePosition(level, settlement, blueprint, entity, target, false);
    }

    @Nullable
    private static BlockPos findFootingBasePosition(
            ServerLevel level, SettlementRecord settlement, BlueprintRecord blueprint,
            SettlementCitizenEntity entity, BlockPos target, boolean preferResidentProximity
    ) {
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        List<BlockPos> candidates = new ArrayList<>();
        int margin = Math.max(2, template.transitionMargin());
        int requiredRadius = Math.max(
                Math.max(Math.abs(target.getX() - (template.minCorner().getX() - margin)),
                        Math.abs(target.getX() - (template.maxCorner().getX() + margin))),
                Math.max(Math.abs(target.getZ() - (template.minCorner().getZ() - margin)),
                        Math.abs(target.getZ() - (template.maxCorner().getZ() + margin))));
        int maximumRadius = Math.min(MAX_FOOTING_BASE_SEARCH_RADIUS, Math.max(5, requiredRadius));
        for (BlockPos candidate : exteriorFrameBaseCandidates(template, target, maximumRadius)) {
            if (candidate.equals(target) || candidate.above().equals(target)
                    || !settlement.contains(candidate)
                    || !insideConstructionAccessBounds(template, candidate)
                    || !footingAllowedAt(level, settlement, blueprint, template, candidate)
                    || !clearVerticalPillarColumn(level, candidate)) continue;
            if (entity.canStandAtPosition(candidate)) candidates.add(candidate.immutable());
        }
        Comparator<BlockPos> targetDistance = Comparator.comparingDouble(position ->
                new Vec3(position.getX() + 0.5D, position.getY() + 1.62D,
                        position.getZ() + 0.5D).distanceToSqr(Vec3.atCenterOf(target)));
        Comparator<BlockPos> residentDistance = Comparator.comparingDouble(
                position -> position.distSqr(entity.blockPosition()));
        Comparator<BlockPos> reusableExteriorColumn = Comparator.comparing(
                position -> !isExteriorConstructionFooting(template, position));
        candidates.sort(reusableExteriorColumn.thenComparing(preferResidentProximity
                ? residentDistance.thenComparing(targetDistance)
                : targetDistance.thenComparing(residentDistance)));
        List<BlockPos> reachableFallbacks = new ArrayList<>();
        List<BlockPos> remaining = new ArrayList<>(candidates.subList(0, Math.min(32, candidates.size())));
        while (!remaining.isEmpty()) {
            // A footing base is the ordinary, grounded entrance to a temporary
            // access route. Vanilla path creation can report a route to an air
            // cell above scaffolding even though GroundPathNavigation cannot
            // execute the vertical scaffold segment. Reject those optimistic
            // paths here; the explicit scaffold controller owns every climb.
            BlockPos candidate = entity.findReachableConstructionStation(remaining);
            if (candidate == null) break;
            remaining.remove(candidate);
            BlockPos routeIdentity = entity.preparedConstructionRouteIdentity(candidate);
            if (!entity.constructionRouteAllowed(target, routeIdentity)) continue;
            if (straightScaffoldingColumnReaches(level, settlement, blueprint, template,
                    entity, candidate.above(), target, MAX_PLANNED_FOOTING_ROUTE_DEPTH)) return candidate;
            if (reachableFallbacks.size() < 4) {
                reachableFallbacks.add(candidate);
            }
        }
        for (BlockPos candidate : reachableFallbacks) {
            if (hasPlannedExteriorFootingRoute(level, settlement, blueprint, template,
                    entity, candidate.above(), target, MAX_PLANNED_FOOTING_ROUTE_DEPTH)) return candidate;
        }
        return null;
    }

    /** Enumerate only real support columns; never scan the whole footprint. */
    public static List<BlockPos> exteriorFrameBaseCandidates(
            BuildingBlueprintTemplate template, BlockPos target, int maximumRadius
    ) {
        List<BlockPos> columns = new ArrayList<>(ExteriorScaffoldFrame.columns(template, target.getY()));
        columns.removeIf(p -> Math.max(Math.abs(p.getX() - target.getX()),
                Math.abs(p.getZ() - target.getZ())) > maximumRadius);
        columns.sort(Comparator.comparingInt((BlockPos p) -> Math.max(
                Math.abs(p.getX() - target.getX()), Math.abs(p.getZ() - target.getZ())))
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        List<BlockPos> result = new ArrayList<>(44);
        for (BlockPos column : columns) {
            for (int dy = -10; dy <= 0; dy++) result.add(column.offset(0, dy, 0));
        }
        return List.copyOf(result);
    }

    /**
     * Four corner towers plus the minimum regular intermediate supports needed
     * to keep a horizontal ring within vanilla's six-link support distance.
     */
    private static boolean isExteriorScaffoldingFrameColumn(
            BuildingBlueprintTemplate template, BlockPos position
    ) {
        return ExteriorScaffoldFrame.column(template, position);
    }

    private static boolean isExteriorScaffoldingFrameCell(
            BuildingBlueprintTemplate template, BlockPos position
    ) {
        return ExteriorScaffoldFrame.cell(template, position);
    }

    /** Lower values are closer to the exterior scaffold frame/frontier. */
    private static int exteriorFrameAccessDepth(
            BuildingBlueprintTemplate template, BlockPos position
    ) {
        int dx = Math.min(Math.abs(position.getX() - template.minCorner().getX()),
                Math.abs(template.maxCorner().getX() - position.getX()));
        int dz = Math.min(Math.abs(position.getZ() - template.minCorner().getZ()),
                Math.abs(template.maxCorner().getZ() - position.getZ()));
        return Math.max(0, Math.min(dx, dz));
    }

    /**
     * Soft physical-access score derived from the resident's live position.
     * Absolute blueprint height is deliberately absent: preferring every lower
     * cell even after a resident reached a safe upper catwalk made it abandon
     * that frontier and repeatedly descend for one remote foundation cell. The
     * live vertical/horizontal cost still starts a ground resident near the
     * foundation, while allowing it to finish any reachable upper frontier.
     */
    private static double constructionTargetAccessScore(
            BuildingBlueprintTemplate template, SettlementCitizenEntity entity,
            BlockPos position
    ) {
        int verticalTravel = Math.abs(position.getY() - entity.blockPosition().getY());
        int frameDepth = exteriorFrameAccessDepth(template, position);
        return verticalTravel * 12.0D + frameDepth * 6.0D
                + Math.sqrt(position.distSqr(entity.blockPosition()));
    }

    private static boolean clearVerticalPillarColumn(ServerLevel level, BlockPos feetPosition) {
        for (BlockPos position : List.of(feetPosition, feetPosition.above(), feetPosition.above(2))) {
            BlockState state = level.getBlockState(position);
            if (!state.getCollisionShape(level, position).isEmpty() || !state.getFluidState().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Scaffolding is occupied from inside its own cell. Extending a column needs
     * only that new cell and the body cell above it; a block two cells above may
     * be the construction target and remains just beyond the resident's bounds.
     * Solid footing rises still use the three-cell check above.
     */
    private static boolean clearScaffoldingWorkColumn(ServerLevel level, BlockPos scaffoldCell) {
        return ScaffoldSitePreparation.headroom(level, scaffoldCell)
                && ScaffoldSitePreparation.headroom(level, scaffoldCell.above());
    }

    /** Existing scaffolding above the resident is a climb route, not headroom obstruction. */
    private static boolean scaffoldingBodyCellPassable(ServerLevel level, BlockPos position) {
        BlockState state = level.getBlockState(position);
        return state.is(Blocks.SCAFFOLDING)
                || state.getCollisionShape(level, position).isEmpty()
                && state.getFluidState().isEmpty();
    }

    static boolean scaffoldSupported(ServerLevel level, BlockPos p) {
        return supportedFootingState(level, p, Blocks.SCAFFOLDING.defaultBlockState()) != null;
    }

    /** Workers share frame work; the controller reserves individual block targets. */
    public static boolean claimScaffoldWork(ServerLevel level, SettlementRecord settlement,
            BlueprintRecord blueprint, CitizenRecord citizen) {
        if (blueprint.cancellationQueued()) return false;
        blueprint.setScaffoldWorker(null);
        return eligibleScaffoldWorker(level, blueprint, citizen);
    }

    private static boolean eligibleScaffoldWorker(ServerLevel level, BlueprintRecord blueprint, CitizenRecord c) {
        if (c == null || !c.alive() || !blueprint.id().equals(c.targetBlueprintId()) || c.entityId() == null) return false;
        if (c.workState() != CitizenWorkState.TO_BLUEPRINT
                && c.workState() != CitizenWorkState.TO_FOOTING_SUPPLY
                && c.workState() != CitizenWorkState.TO_CONSTRUCTION_SUPPLY) return false;
        if (c.activity() == CitizenActivity.RETURNING_HOME || c.activity() == CitizenActivity.RESTING_AT_HOME
                || c.activity() == CitizenActivity.SELF_DEFENSE) return false;
        return level.getEntity(c.entityId()) instanceof SettlementCitizenEntity e && e.isAlive();
    }

    /** Complete all access levels, not merely the arm nearest today's wall block. */
    private static boolean advanceExteriorScaffoldFrame(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord blueprint, CitizenRecord citizen, SettlementCitizenEntity entity, BlockPos target
    ) {
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        var controller = blueprint.constructionController();
        BlockPos next = controller.nextForWorker(level, blueprint, entity);
        if (next == null) return false;
        boolean result = advanceFrameCell(level, data, settlement, blueprint, citizen, entity, template, next);
        controller.diagnose(level, citizen, entity);
        return result;
    }

    private static boolean advanceFrameCell(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord blueprint, CitizenRecord citizen, SettlementCitizenEntity entity,
            BuildingBlueprintTemplate template, BlockPos p
    ) {
        if (!claimScaffoldWork(level, settlement, blueprint, citizen)) {
            entity.getNavigation().stop();
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "SCAFFOLD_WORKER_WAIT");
            return true;
        }
        // Never replace foreign blocks, route through future structure, or consume
        // material until the normal placement event and live support check succeed.
        if (blueprint.hasTemporaryFooting(p) && level.getBlockState(p).isAir()) {
            blueprint.removeTemporaryFooting(p);
            data.touch();
        }
        if (!ScaffoldSitePreparation.space(level, p)
                || !footingAllowedAt(level, settlement, blueprint, template, p)
                || !clearScaffoldingWorkColumn(level, p)
                || supportedFootingState(level, p, Blocks.SCAFFOLDING.defaultBlockState()) == null) {
            String reason = ScaffoldSitePreparation.failure(level, p, !scaffoldSupported(level, p)
                    ? "SCAFFOLD_SUPPORT_MISSING" : "BLUEPRINT_BLOCKED");
            blueprint.constructionController().postponeAccess(p, level.getGameTime(), reason);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, reason);
            entity.traceConstructionDecision("frame-blocked:" + p + ":state=" + level.getBlockState(p)
                    + ":below=" + level.getBlockState(p.below())
                    + ":head=" + level.getBlockState(p.above())
                    + ":allowed=" + footingAllowedAt(level, settlement, blueprint, template, p)
                    + ":support=" + supportedFootingState(level, p, Blocks.SCAFFOLDING.defaultBlockState()));
            return true;
        }
        if (citizen.findInventorySlot(stack -> stack.is(Items.SCAFFOLDING)) < 0) {
            if (preferredFootingMaterial(level, settlement, blueprint, entity.blockPosition()) != Items.SCAFFOLDING) {
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "SCAFFOLD_FRAME_MATERIAL_MISSING");
            } else {
                // Existing logistics includes safe descent, station reservation and batch pickup.
                placeTemporaryFootingStep(level, data, settlement, blueprint, citizen, entity,
                        p, CitizenActivity.BUILDING);
            }
            return true;
        }
        if (!blueprint.constructionController().movement(entity).approach(level, blueprint, entity, p, WALK_SPEED)) {
            setActivity(data, citizen, entity, CitizenActivity.BUILDING, "SCAFFOLD_ROUTE_PENDING");
            return true;
        }
        if (preparePhysicalScaffoldAccess(level, data, settlement, blueprint, citizen, entity, p, CitizenActivity.BUILDING)) return true;
        BlockPos parent = blueprint.hasTemporaryFooting(p.below()) ? p.below() : null;
        if (parent == null) for (Direction d : Direction.Plane.HORIZONTAL) {
            if (blueprint.hasTemporaryFooting(p.relative(d))) { parent = p.relative(d); break; }
        }
        if (placeAndRegisterTemporaryFooting(level, data, settlement, blueprint, citizen, entity,
                p, Blocks.SCAFFOLDING.defaultBlockState(), Items.SCAFFOLDING, parent)) {
            entity.completeTrackedConstructionRoute();
            setActivity(data, citizen, entity, CitizenActivity.BUILDING, "");
            entity.traceConstructionDecision("frame-placed:" + p);
        } else {
            String reason = ScaffoldSitePreparation.failure(level, p, "PROTECTED_AREA");
            blueprint.constructionController().postponeAccess(p, level.getGameTime(), reason);
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, reason);
        }
        return true;
    }

    private static boolean preparePhysicalScaffoldAccess(ServerLevel level, SettlementSavedData data,
            SettlementRecord settlement, BlueprintRecord blueprint, CitizenRecord citizen,
            SettlementCitizenEntity entity, BlockPos position, CitizenActivity activity) {
        var preparation = ScaffoldSitePreparation.prepare(level, data, settlement, blueprint, citizen, entity, position);
        if (!preparation.failure().isEmpty()) {
            blueprint.constructionController().postponeAccess(position, level.getGameTime(), preparation.failure());
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, preparation.failure());
            return true;
        }
        if (!preparation.changed()) return false;
        entity.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        setActivity(data, citizen, entity, activity, "");
        syncHeldItem(citizen, entity);
        return true;
    }

    private static void placeTemporaryFootingStep(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord blueprint, CitizenRecord citizen, SettlementCitizenEntity entity,
            BlockPos target, CitizenActivity approachActivity
    ) {
        Item preferredFooting = preferredFootingMaterial(
                level, settlement, blueprint, entity.blockPosition());
        int footingSlot = preferredFooting == Items.SCAFFOLDING
                ? citizen.findInventorySlot(stack -> stack.is(Items.SCAFFOLDING))
                : findFootingSlot(citizen);
        if (footingSlot < 0) {
            if (occupiedTemporarySupport(level, blueprint,
                    constructionFeetPosition(entity)) != null) {
                entity.beginConstructionAccessRetreat();
            }
            if (entity.isConstructionAccessRetreating()
                    && (beginTemporaryFootingDescent(blueprint, entity, false)
                    || beginCleanupEgressToSafeSurface(blueprint, entity))) {
                // Material collection is a ground logistics task. Do not switch
                // to the supply state while still stranded on an owned access
                // branch; reverse the recorded route first, then collect a full
                // replacement batch from a reachable station.
                setActivity(data, citizen, entity, approachActivity, "");
                data.touch();
                return;
            }
            BuildingRecord warehouse = WarehouseConstructionSupply.warehouse(
                    level, settlement, entity.blockPosition(), preferredFooting);
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(blueprint.ownerId());
            if (warehouse == null && owner == null
                    && !settlement.creativeCoreInstalled()) {
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "FOOTING_MATERIAL_MISSING");
            } else {
                citizen.fetchFootingMaterial(warehouse == null ? null : warehouse.id());
                data.touch();
            }
            return;
        }
        ItemStack footingStack = citizen.inventoryItem(footingSlot);
        if (!(footingStack.getItem() instanceof BlockItem blockItem)) {
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "FOOTING_MATERIAL_MISSING");
            return;
        }
        BlockState footingState = blockItem.getBlock().defaultBlockState();
        BlockPos readyVerticalSupport = entity.consumeReadyVerticalPillarSupport();
        if (readyVerticalSupport != null) {
            BlockPos parent = readyVerticalSupport.below();
            if (!placeAndRegisterTemporaryFooting(level, data, settlement, blueprint, citizen, entity,
                    readyVerticalSupport, footingState, footingStack.getItem(), parent)) {
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "FOOTING_POSITION_UNAVAILABLE");
            } else {
                setActivity(data, citizen, entity, approachActivity, "");
            }
            return;
        }
        BuildingBlueprintTemplate footingTemplate = BlueprintTemplates.create(blueprint);
        int availableFootings = countInventoryItem(citizen, footingStack.getItem());
        BlockPos feet = constructionFeetPosition(entity);
        BlockPos occupiedSupport = occupiedTemporarySupport(level, blueprint, feet);
        // Once a branch has been rejected, reverse the persisted support chain
        // all the way to an ordinary surface before considering a new target.
        // Without this small state, the next scheduler cycle sees an adjacent
        // older support that is marginally closer to the target and walks back
        // onto it, while the following cycle descends again: a permanent
        // two-cell oscillation. This is generic route-state, not a template or
        // construction-order special case.
        if (entity.isConstructionAccessRetreating()) {
            if (occupiedSupport == null) {
                entity.finishConstructionAccessRetreat();
                entity.releaseTrackedConstructionRoute();
            } else if (beginTemporaryFootingDescent(blueprint, entity, false)
                    || beginCleanupEgressToSafeSurface(blueprint, entity)) {
                entity.traceConstructionDecision("footing-retreat:" + occupiedSupport
                        + "->" + entity.constructionDecisionTrace());
                setActivity(data, citizen, entity, approachActivity, "");
                data.touch();
                return;
            } else {
                // The parent chain may be closed by a newly placed wall while a
                // different adjacent support can still provide a legal escape.
                // Leaving the retreat latch set made this branch return forever
                // before the generic candidate planner below could build that
                // rescue step. Retire the failed branch, then continue in the
                // same cycle with normal collision/headroom validation.
                entity.recordConstructionAccessFailure("FOOTING_RETREAT_UNAVAILABLE");
                entity.recordTrackedConstructionRouteFailure();
                entity.finishConstructionAccessRetreat();
                CitizenNavigationReservationService.resetStationAfterAccessTopologyChange(
                        level, entity.getUUID());
                setActivity(data, citizen, entity, approachActivity, "");
            }
        }
        boolean insideOwnedScaffolding = occupiedSupport != null
                && occupiedSupport.equals(feet)
                && level.getBlockState(occupiedSupport).is(Blocks.SCAFFOLDING);
        BlockPos verticalSupport = insideOwnedScaffolding ? feet.above() : feet;
        BlockPos verticalStanding = insideOwnedScaffolding
                ? verticalSupport : verticalSupport.above();
        BlockPos ownedSupportBelow = insideOwnedScaffolding
                ? occupiedSupport : verticalSupport.below();
        BlockPos reservedBase = CitizenNavigationReservationService.stationFor(
                level, entity.getUUID(), level.getGameTime());
        if (insideOwnedScaffolding && level.getBlockState(verticalSupport).is(Blocks.SCAFFOLDING)) {
            BlockPos columnTop = verticalSupport;
            while (columnTop.getY() < level.getMaxBuildHeight() - 1
                    && level.getBlockState(columnTop.above()).is(Blocks.SCAFFOLDING)) {
                columnTop = columnTop.above();
            }
            if (entity.beginScaffoldingWorkHeight(columnTop, WALK_SPEED)) {
                // Climb the already-built column before considering an extension.
                // Treating the next scaffold cell as solid headroom caused a
                // climb-descend-replan loop and one new column per wall block.
                entity.traceConstructionDecision("footing-climb-existing:" + feet
                        + "->" + columnTop + ":target=" + target);
                setActivity(data, citizen, entity, approachActivity, "");
                return;
            }
            entity.traceConstructionDecision("footing-climb-existing-rejected:" + feet
                    + "->" + columnTop + ":target=" + target);
        }
        if (beginAdjacentOwnedFootingTraversal(level, settlement, blueprint, footingTemplate,
                entity, target, availableFootings)) {
            setActivity(data, citizen, entity, approachActivity, "");
            data.touch();
            return;
        }
        boolean startReservedScaffoldingColumn = footingState.is(Blocks.SCAFFOLDING)
                && occupiedSupport == null
                && reservedBase != null
                && nearWorkPosition(entity, reservedBase)
                && constructionFeetPosition(entity).equals(reservedBase)
                && requiresElevatedConstructionAccess(entity, target)
                && footingAllowedAt(level, settlement, blueprint, footingTemplate, reservedBase)
                && supportedFootingState(level, reservedBase, footingState) != null;
        if (startReservedScaffoldingColumn) {
            if (preparePhysicalScaffoldAccess(level, data, settlement, blueprint, citizen, entity, reservedBase, approachActivity)) return;
            // A reserved ground base is already the validated start of the
            // generic access plan. Place the traversable scaffold around the
            // resident's feet first, as a player does, instead of raising the
            // resident through empty air and only then filling the old cell.
            // The next scheduler cycle extends the same owned column upward.
            if (placeAndRegisterTemporaryFooting(level, data, settlement, blueprint,
                    citizen, entity, reservedBase, footingState,
                    footingStack.getItem(), null)) {
                entity.beginScaffoldingWorkHeight(reservedBase, WALK_SPEED);
                setActivity(data, citizen, entity, approachActivity, "");
            } else {
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED,
                        "FOOTING_POSITION_UNAVAILABLE");
            }
            return;
        }
        boolean extendOwnedScaffolding = footingState.is(Blocks.SCAFFOLDING)
                && blueprint.hasTemporaryFooting(ownedSupportBelow)
                && level.getBlockState(ownedSupportBelow).is(Blocks.SCAFFOLDING)
                && requiresElevatedConstructionAccess(entity, target)
                && footingAllowedAt(level, settlement, blueprint, footingTemplate, verticalSupport)
                && clearScaffoldingWorkColumn(level, verticalSupport)
                && hasPlannedExteriorFootingRoute(level, settlement, blueprint, footingTemplate, entity,
                verticalSupport, target, Math.max(0, availableFootings - 1));
        if (extendOwnedScaffolding) {
            if (preparePhysicalScaffoldAccess(level, data, settlement, blueprint, citizen, entity, verticalSupport, approachActivity)) return;
            // Vanilla scaffolding is a climbable column, not a solid block used
            // for a jump-place pillar. Connect the next cell while the resident
            // is still inside the column, then climb through it. This mirrors a
            // player's scaffold extension and prevents the newly placed upper
            // cell from losing support during the jump/place tick gap.
            if (placeAndRegisterTemporaryFooting(level, data, settlement, blueprint,
                    citizen, entity, verticalSupport, footingState,
                    footingStack.getItem(), ownedSupportBelow)) {
                entity.beginScaffoldingWorkHeight(verticalSupport, WALK_SPEED);
                setActivity(data, citizen, entity, approachActivity, "");
            } else {
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED,
                        "FOOTING_POSITION_UNAVAILABLE");
            }
            return;
        }
        boolean verticalRouteAvailable = requiresElevatedConstructionAccess(entity, target)
                && footingAllowedAt(level, settlement, blueprint, footingTemplate, verticalSupport)
                && clearVerticalPillarColumn(level, verticalSupport)
                && hasPlannedExteriorFootingRoute(level, settlement, blueprint, footingTemplate, entity,
                verticalStanding, target, Math.max(0, availableFootings - 1));
        if (verticalRouteAvailable && !entity.isCenteredOnConstructionBlock(verticalSupport)) {
            if (entity.beginConstructionCentering(verticalSupport, WALK_SPEED)) {
                // Centering is an exact, collision-checked movement inside the
                // current cell. Finish it and begin the rise in this scheduler
                // cycle; leaving a tick between the two lets vanilla entity
                // physics nudge the worker off-centre and creates an endless
                // centre/retry oscillation at a scaffold base.
                if (approachActivity == CitizenActivity.DEMOLISHING) {
                    entity.continueConstructionStep(WALK_SPEED);
                }
                if (approachActivity == CitizenActivity.DEMOLISHING
                        && entity.isCenteredOnConstructionBlock(verticalSupport)
                        && entity.beginVerticalPillarRise(verticalSupport, WALK_SPEED)) {
                    setActivity(data, citizen, entity, approachActivity, "");
                    return;
                }
                setActivity(data, citizen, entity, approachActivity, "");
                return;
            }
        } else if (verticalRouteAvailable
                && entity.beginVerticalPillarRise(verticalSupport, WALK_SPEED)) {
            setActivity(data, citizen, entity, approachActivity, "");
            return;
        }
        Vec3 targetCenter = Vec3.atCenterOf(target);
        List<FootingStep> candidates = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            // Descending step: when a resident finishes a reachable roof section
            // on the wrong side of a ridge, provide a safe one-block way down.
            addFootingStepCandidate(level, settlement, blueprint, footingTemplate, entity,
                    entity.blockPosition().below(2).relative(direction), targetCenter, candidates);
            // Same-height catwalk: support is placed one block below the adjacent
            // standing cell, so the resident can move around an eave without
            // climbing higher on every single step.
            addFootingStepCandidate(level, settlement, blueprint, footingTemplate, entity,
                    entity.blockPosition().below().relative(direction), targetCenter, candidates);
            // Ascending stair: support is placed beside the current feet and the
            // resident steps onto the cell above it.
            addFootingStepCandidate(level, settlement, blueprint, footingTemplate, entity,
                    entity.blockPosition().relative(direction), targetCenter, candidates);
        }
        candidates.sort(Comparator.comparingDouble(FootingStep::targetDistance));
        boolean routeRejected = false;
        int examinedCandidates = 0;
        boolean connectingToOwnedScaffold = isOwnedScaffoldingTarget(level, blueprint, target);
        for (FootingStep step : candidates) {
            if (examinedCandidates++ >= MAX_FOOTING_CANDIDATES_PER_CYCLE) break;
            // While retreating from a roof, every adjacent scaffold step keeps
            // its parent chain and can therefore be walked back. Requiring a
            // complete proof all the way to an already owned scaffold column
            // caused the worker to reject every useful first step on complex
            // roofs. Greedy distance reduction is safe for this specific
            // connection case; ordinary target access still uses full planning.
            if (!connectingToOwnedScaffold
                    && !hasPlannedExteriorFootingRoute(level, settlement, blueprint, footingTemplate, entity,
                    step.standingPosition(), target, Math.max(0, availableFootings - 1))) {
                routeRejected = true;
                continue;
            }
            ItemStack placementStack = footingStack;
            BlockState supportedState = supportedFootingState(
                    level, step.blockPosition(), footingState);
            // Do not silently turn an unsupported scaffold step into a solid
            // bridge. Repeating that fallback produced long cobblestone
            // catwalks across a roof: every new solid block supported the next,
            // so the promised one-cell connector became a permanent route that
            // could not collapse from its base. A worker carrying scaffolding
            // must instead retreat and start/reuse another supported column.
            // Settlements that genuinely have no scaffolding still select a
            // solid footing as their primary material above and retain the
            // ordinary solid-pillar behavior.
            if (supportedState == null) {
                routeRejected = true;
                continue;
            }
            if (!footingState.is(Blocks.SCAFFOLDING) && !level.getBlockState(step.blockPosition()).isAir()) continue;
            if (footingState.is(Blocks.SCAFFOLDING) && preparePhysicalScaffoldAccess(
                    level, data, settlement, blueprint, citizen, entity, step.blockPosition(), approachActivity)) return;
            BlockSnapshot snapshot = BlockSnapshot.create(
                    level.dimension(), level, step.blockPosition(), Block.UPDATE_CLIENTS);
            if (!level.setBlock(step.blockPosition(), supportedState, Block.UPDATE_ALL)
                    || EventHooks.onBlockPlace(null, snapshot, Direction.UP)) {
                snapshot.restore(Block.UPDATE_ALL);
                continue;
            }
            Item placementItem = placementStack.getItem();
            ItemStack consumed = citizen.extractInventoryItem(stack -> stack.is(placementItem), 1);
            BlockPos parent = entity.blockPosition().below();
            if (consumed.isEmpty() || !blueprint.addTemporaryFooting(
                    step.blockPosition(), placementItem, parent)) {
                if (!consumed.isEmpty()) citizen.insertIntoInventory(consumed);
                snapshot.restore(Block.UPDATE_ALL);
                continue;
            }
            entity.noteConstructionAccessProgress();
            if (!entity.beginAdjacentConstructionMovement(step.standingPosition(), WALK_SPEED)) {
                blueprint.removeTemporaryFooting(step.blockPosition());
                citizen.insertIntoInventory(consumed);
                snapshot.restore(Block.UPDATE_ALL);
                continue;
            }
            syncHeldItem(citizen, entity);
            setActivity(data, citizen, entity, approachActivity, "");
            data.touch();
            return;
        }
        // Even a target only one block above the feet can be hidden by the wall
        // between the resident and the target. The failed direct-station search
        // above is then evidence that a centred one-block rise is needed. The
        // three-cell headroom check still forbids rising under a completed roof.
        verticalRouteAvailable = target.getY() >= verticalSupport.getY() + 1
                && footingAllowedAt(level, settlement, blueprint, footingTemplate, verticalSupport)
                && clearVerticalPillarColumn(level, verticalSupport)
                && hasPlannedExteriorFootingRoute(level, settlement, blueprint, footingTemplate, entity,
                verticalStanding, target, Math.max(0, availableFootings - 1));
        if (verticalRouteAvailable && !entity.isCenteredOnConstructionBlock(verticalSupport)) {
            if (entity.beginConstructionCentering(verticalSupport, WALK_SPEED)) {
                if (approachActivity == CitizenActivity.DEMOLISHING) {
                    entity.continueConstructionStep(WALK_SPEED);
                }
                if (approachActivity == CitizenActivity.DEMOLISHING
                        && entity.isCenteredOnConstructionBlock(verticalSupport)
                        && entity.beginVerticalPillarRise(verticalSupport, WALK_SPEED)) {
                    setActivity(data, citizen, entity, approachActivity, "");
                    return;
                }
                setActivity(data, citizen, entity, approachActivity, "");
                return;
            }
        } else if (verticalRouteAvailable && entity.beginVerticalPillarRise(verticalSupport, WALK_SPEED)) {
            setActivity(data, citizen, entity, approachActivity, "");
            return;
        }
        boolean verticalHeadroomBlocked = !clearVerticalPillarColumn(level, verticalSupport);
        boolean localRouteUnavailable = candidates.isEmpty() || routeRejected;
        if (verticalHeadroomBlocked && beginExistingFootingRepositionForHeadroom(
                level, blueprint, entity, target)) {
            entity.traceConstructionDecision("footing-reposition-headroom:" + feet
                    + "->" + entity.activeConstructionMovementTarget() + ":target=" + target);
            setActivity(data, citizen, entity, approachActivity, "");
            data.touch();
            return;
        }
        if (verticalHeadroomBlocked || localRouteUnavailable) {
            // Keep the current placement stable while reversing the access
            // branch. Deferring/selecting another blueprint block before the
            // resident has left this scaffold makes the next planner treat the
            // same physical branch as a fresh route and produces a permanent
            // climb/retreat loop. Count this failed physical route now; the
            // ordinary blocked-target handler may choose another placement only
            // after retreat has either completed or proved impossible.
            // A route can fail during planning before navigation ever receives
            // a destination. Such failures previously had no tracked route, so
            // the three-attempt insurance never advanced and the same roof
            // pocket could be planned again every work cycle. Collapse the
            // attempt to the current physical scaffold root (or the current
            // real surface when no scaffold exists) and count it exactly like
            // an already-started movement route.
            BlockPos failedRoute = currentConstructionAccessRouteIdentity(
                    blueprint, entity, target);
            entity.trackConstructionRoute(failedRoute, failedRoute);
            int routeFailures = entity.recordTrackedConstructionRouteFailure();
            if (routeFailures >= 3) {
                // The three-attempt rule blacklists only this physical route for
                // this structure target. The exterior frame is shared worksite
                // infrastructure, so keep it for other targets instead of
                // globally reclaiming and rebuilding it on every miss.
                if (beginCleanupEgressToSafeSurface(blueprint, entity)) {
                    entity.beginConstructionAccessRetreat();
                    entity.traceConstructionDecision("route-exhausted-egress:"
                            + failedRoute + ":target=" + target);
                    CitizenNavigationReservationService.resetStationAfterAccessTopologyChange(
                            level, entity.getUUID());
                    setActivity(data, citizen, entity, approachActivity, "");
                    data.touch();
                    return;
                }
                setActivity(data, citizen, entity, CitizenActivity.BLOCKED,
                        "CONSTRUCTION_ROUTE_EXHAUSTED");
                deferBlockedConstructionTarget(level, data, settlement, blueprint,
                        citizen, entity, target);
                return;
            }
            entity.beginConstructionAccessRetreat();
        }
        if ((verticalHeadroomBlocked || localRouteUnavailable)
                && (beginTemporaryFootingDescent(blueprint, entity, true)
                || beginCleanupEgressToSafeSurface(blueprint, entity))) {
            // The resident may be standing on completed roof geometry rather
            // than on a registered temporary support. In that pose the false
            // variant cannot discover the nearby owned scaffold column and the
            // worker retries FOOTING_POSITION_UNAVAILABLE from the same roof
            // cell forever. A failed local route is exactly when re-entering a
            // nearby real scaffold descent is required before replanning.
            entity.recordConstructionAccessFailure(verticalHeadroomBlocked
                    ? "HEADROOM_DESCEND_TO_REPLAN" : "NO_LOCAL_STEP_DESCEND_TO_REPLAN");
            CitizenNavigationReservationService.resetStationAfterAccessTopologyChange(
                    level, entity.getUUID());
            setActivity(data, citizen, entity, approachActivity, "");
            return;
        }
        entity.traceConstructionDecision("footing-failed:target=" + target
                + ",feet=" + feet
                + ",candidates=" + candidates.size()
                + ",routeRejected=" + routeRejected
                + ",headroomBlocked=" + verticalHeadroomBlocked
                + ",ownedTarget=" + connectingToOwnedScaffold
                + ",available=" + availableFootings);
        setActivity(data, citizen, entity, CitizenActivity.BLOCKED,
                verticalHeadroomBlocked
                        ? "CONSTRUCTION_HEADROOM_BLOCKED"
                        : routeRejected ? "FOOTING_ROUTE_UNAVAILABLE" : "FOOTING_POSITION_UNAVAILABLE");
    }

    /** Stable identity for a physical construction-access attempt. */
    private static BlockPos currentConstructionAccessRouteIdentity(
            BlueprintRecord blueprint, SettlementCitizenEntity entity,
            BlockPos target
    ) {
        BlockPos feet = constructionFeetPosition(entity);
        BlockPos support = occupiedTemporarySupport(entity.level(), blueprint, feet);
        // No placed access branch means there is no shared physical corridor to
        // blacklist. Key a pure planning failure by its requested cell; using
        // the resident's current ground square exhausted every unrelated target
        // after three misses and then repeated CONSTRUCTION_ROUTE_EXHAUSTED for
        // the rest of the day.
        return support == null ? target.immutable()
                : temporaryFootingRoot(blueprint, support).immutable();
    }

    /**
     * Reuses an already-built adjacent scaffold/catwalk cell before consuming a
     * new footing. The route planner has always been able to count an existing
     * support as a zero-cost node, but the executor previously knew only how to
     * climb a vertical column or place a fresh neighbor. On a roof this made a
     * worker climb to the top of one column, reject the blocked vertical
     * extension, descend, and repeat forever even though a connected horizontal
     * scaffold route was beside them.
     *
     * <p>Only a strictly target-approaching, one-cell move whose remaining route
     * is still generically provable is accepted. This keeps the behavior
     * template-agnostic and prevents oscillation between two existing cells.</p>
     */
    private static boolean beginAdjacentOwnedFootingTraversal(
            ServerLevel level, SettlementRecord settlement, BlueprintRecord blueprint,
            BuildingBlueprintTemplate template, SettlementCitizenEntity entity,
            BlockPos target, int availableFootings
    ) {
        BlockPos feet = constructionFeetPosition(entity);
        double currentDistance = constructionTargetDistanceSqr(feet, target);
        List<BlockPos> candidates = blueprint.temporaryFootings().keySet().stream()
                .filter(position -> level.getBlockState(position).is(Block.byItem(
                        blueprint.temporaryFootingItem(position))))
                // A vanilla scaffold catwalk is traversed inside the scaffold
                // body, including where a horizontal ring meets a taller corner
                // tower. Looking only at the air cell above each support made
                // every such junction disappear from the route graph. The
                // entity's bounded controller now suspends gravity and ignores
                // only real scaffold collision for this one-cell move, so use
                // the actual scaffold cell and retain the old top-surface form
                // for non-scaffold temporary supports.
                .map(position -> level.getBlockState(position).is(Blocks.SCAFFOLDING)
                        ? position : position.above())
                .filter(entity::canOccupyConstructionPosition)
                .filter(standing -> Math.abs(standing.getX() - feet.getX())
                        + Math.abs(standing.getZ() - feet.getZ()) == 1)
                .filter(standing -> Math.abs(standing.getY() - feet.getY()) <= 1)
                .filter(standing -> constructionTargetDistanceSqr(standing, target)
                        + 1.0E-6D < currentDistance)
                .sorted(Comparator
                        .comparingDouble((BlockPos standing) ->
                                constructionTargetDistanceSqr(standing, target))
                        // When the target block's vertical interval makes the
                        // inside-cell and top-surface distances equal, cross the
                        // higher catwalk surface first. Otherwise BlockPos's
                        // packed-coordinate order may choose an unnecessary
                        // crouching descent into the same scaffold column.
                        .thenComparing(Comparator.comparingInt(
                                (BlockPos position) -> position.getY()).reversed())
                        .thenComparingLong(BlockPos::asLong))
                .toList();
        String rejected = "";
        for (BlockPos candidate : candidates) {
            if (!hasPlannedExteriorFootingRoute(level, settlement, blueprint, template,
                    entity, candidate, target, availableFootings)) {
                if (rejected.isEmpty()) rejected = candidate + "=remaining-route-unavailable";
                continue;
            }
            if (!entity.beginAdjacentConstructionMovement(candidate, WALK_SPEED)) {
                if (rejected.isEmpty()) {
                    rejected = candidate + "=" + entity.constructionMovementDiagnostics();
                }
                continue;
            }
            entity.traceConstructionDecision("footing-traverse-existing:" + feet
                    + "->" + candidate + ":target=" + target
                    + (rejected.isEmpty() ? "" : ":firstRejected=" + rejected));
            return true;
        }
        return false;
    }

    /**
     * Greedy target-distance movement cannot leave a scaffold pocket whose roof
     * blocks the next vertical rise. Search the already paid-for access network
     * for a nearby exposed top with real two-block headroom, then follow an
     * ordinary validated local route to it. The destination may be briefly
     * lateral or farther from the target; this is generic obstacle avoidance,
     * not a template-specific construction order.
     */
    private static boolean beginExistingFootingRepositionForHeadroom(
            ServerLevel level, BlueprintRecord blueprint,
            SettlementCitizenEntity entity, BlockPos target
    ) {
        BlockPos feet = constructionFeetPosition(entity);
        List<BlockPos> candidates = blueprint.temporaryFootings().keySet().stream()
                .filter(position -> level.getBlockState(position).is(Block.byItem(
                        blueprint.temporaryFootingItem(position))))
                .map(BlockPos::above)
                .filter(position -> !position.equals(feet))
                .filter(position -> position.distManhattan(feet) <= 12)
                .filter(entity::canUseConstructionSurfaceAtPosition)
                .filter(position -> clearVerticalPillarColumn(level, position))
                .sorted(Comparator
                        .comparingDouble((BlockPos position) ->
                                constructionTargetDistanceSqr(position, target))
                        .thenComparingDouble(position -> position.distSqr(feet))
                        .thenComparingLong(BlockPos::asLong))
                .limit(32)
                .toList();
        for (BlockPos candidate : candidates) {
            if (entity.beginLocalConstructionRoute(candidate, WALK_SPEED)) return true;
        }
        return false;
    }

    private static void addFootingStepCandidate(
            ServerLevel level, SettlementRecord settlement, BlueprintRecord blueprint,
            BuildingBlueprintTemplate template, SettlementCitizenEntity entity,
            BlockPos support, Vec3 targetCenter, List<FootingStep> candidates
    ) {
        BlockPos standing = support.above();
        BlockPos head = standing.above();
        int horizontal = Math.abs(standing.getX() - entity.getBlockX())
                + Math.abs(standing.getZ() - entity.getBlockZ());
        int vertical = standing.getY() - entity.getBlockY();
        if (horizontal != 1 || vertical < -1 || vertical > 1
                || !settlement.contains(support) || level.isOutsideBuildHeight(head)
                || !ScaffoldSitePreparation.space(level, support)
                || !level.getFluidState(support).isEmpty()
                || !level.getBlockState(standing).getCollisionShape(level, standing).isEmpty()
                || !level.getFluidState(standing).isEmpty()
                || !level.getBlockState(head).getCollisionShape(level, head).isEmpty()
                || !level.getFluidState(head).isEmpty()
                || !footingAllowedAt(level, settlement, blueprint, template, support)) return;
        AABB occupied = new AABB(support).expandTowards(0.0D, 2.0D, 0.0D);
        if (!level.getEntitiesOfClass(LivingEntity.class, occupied,
                other -> other != entity && other.isAlive()).isEmpty()) return;
        Vec3 candidateEye = new Vec3(standing.getX() + 0.5D,
                standing.getY() + 1.62D, standing.getZ() + 0.5D);
        candidates.add(new FootingStep(support.immutable(), standing.immutable(),
                candidateEye.distanceToSqr(targetCenter)));
    }

    /**
     * Plans the remaining access route before committing the next support.
     * Same-height catwalk steps may route around eaves before climbing. Every step
     * remains adjacent to its predecessor, so the same chain is also a safe descent
     * route and cannot be sealed by interior/perimeter construction.
     */
    public static boolean hasPlannedExteriorFootingRoute(
            ServerLevel level, SettlementRecord settlement, BlueprintRecord blueprint,
            SettlementCitizenEntity entity, BlockPos firstStanding, BlockPos target,
            int remainingFootings
    ) {
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        return hasPlannedExteriorFootingRoute(level, settlement, blueprint, template,
                entity, firstStanding, target, remainingFootings);
    }

    private static boolean hasPlannedExteriorFootingRoute(
            ServerLevel level, SettlementRecord settlement, BlueprintRecord blueprint,
            BuildingBlueprintTemplate template, SettlementCitizenEntity entity,
            BlockPos firstStanding, BlockPos target, int remainingFootings
    ) {
        java.util.PriorityQueue<PlannedFootingNode> queue = new java.util.PriorityQueue<>(
                Comparator.comparingDouble((PlannedFootingNode node) ->
                        node.depth() * 4.0D + node.standingPosition().distSqr(target)));
        Set<BlockPos> visited = new HashSet<>();
        queue.add(new PlannedFootingNode(firstStanding.immutable(), 0));
        visited.add(firstStanding.immutable());
        int maximumDepth = Math.min(Math.max(0, remainingFootings),
                MAX_PLANNED_FOOTING_ROUTE_DEPTH);
        if (straightScaffoldingColumnReaches(level, settlement, blueprint, template,
                entity, firstStanding, target, maximumDepth)) return true;
        int examinedNodes = 0;
        while (!queue.isEmpty() && examinedNodes++ < MAX_PLANNED_FOOTING_NODES) {
            PlannedFootingNode node = queue.remove();
            boolean scaffoldTraversalTarget = isOwnedScaffoldingTarget(level, blueprint, target);
            if (scaffoldTraversalTarget && node.standingPosition().equals(target)) return true;
            if (!node.standingPosition().equals(target)
                    && !node.standingPosition().above().equals(target)
                    && !node.standingPosition().below().equals(target)
                    && constructionReachGeometry(node.standingPosition(), target)
                    && constructionStationInRange(level, node.standingPosition(), target, entity)) {
                return true;
            }
            if (node.depth() >= maximumDepth) continue;

            // A scaffold route is not limited to diagonal stair steps. The
            // resident can extend and climb inside one vertical column, then
            // leave that column on a horizontal catwalk at the useful height.
            // The old planner tested a purely straight column as a special
            // case, but its general graph had no same-column upward edge. As a
            // result, "climb first, move sideways later" was impossible to
            // prove and ordinary upper walls/roofs generated hundreds of
            // FOOTING_ROUTE_UNAVAILABLE retries despite having a simple player-
            // usable scaffold solution.
            BlockPos verticalSupport = node.standingPosition();
            BlockPos verticalStanding = verticalSupport.above();
            BlockPos verticalHead = verticalStanding.above();
            boolean existingVerticalFooting = blueprint.hasTemporaryFooting(verticalSupport)
                    && level.getBlockState(verticalSupport).is(Block.byItem(
                    blueprint.temporaryFootingItem(verticalSupport)));
            if (!level.isOutsideBuildHeight(verticalHead)
                    && (existingVerticalFooting
                    || footingAllowedAt(level, settlement, blueprint, template, verticalSupport)
                    && ScaffoldSitePreparation.space(level, verticalSupport)
                    && level.getFluidState(verticalSupport).isEmpty())
                    && level.getBlockState(verticalStanding).getCollisionShape(
                    level, verticalStanding).isEmpty()
                    && level.getFluidState(verticalStanding).isEmpty()
                    && level.getBlockState(verticalHead).getCollisionShape(
                    level, verticalHead).isEmpty()
                    && level.getFluidState(verticalHead).isEmpty()
                    && visited.add(verticalStanding.immutable())) {
                queue.add(new PlannedFootingNode(verticalStanding.immutable(),
                        node.depth() + (existingVerticalFooting ? 0 : 1)));
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                for (int rise = -1; rise <= 1; rise++) {
                    BlockPos standing = node.standingPosition().relative(direction).above(rise);
                    BlockPos support = standing.below();
                    BlockPos head = standing.above();
                    BlockPos liftHead = node.standingPosition().above(2);
                    boolean existingFooting = blueprint.hasTemporaryFooting(support)
                            && level.getBlockState(support).is(Block.byItem(
                            blueprint.temporaryFootingItem(support)));
                    boolean existingScaffoldBody = blueprint.hasTemporaryFooting(standing)
                            && level.getBlockState(standing).is(Blocks.SCAFFOLDING);
                    boolean existingStanding = entity.canOccupyConstructionPosition(standing);
                    if (!settlement.contains(support)
                            || level.isOutsideBuildHeight(head)
                            || !existingStanding && !existingFooting && (!footingAllowedAt(
                            level, settlement, blueprint, template, support)
                            || !ScaffoldSitePreparation.space(level, support)
                            || !level.getFluidState(support).isEmpty())
                            || !existingScaffoldBody
                            && (!level.getBlockState(standing)
                            .getCollisionShape(level, standing).isEmpty()
                            || !level.getFluidState(standing).isEmpty()
                            || !level.getBlockState(head)
                            .getCollisionShape(level, head).isEmpty()
                            || !level.getFluidState(head).isEmpty())
                            || rise > 0 && (!level.getBlockState(liftHead)
                            .getCollisionShape(level, liftHead).isEmpty()
                            || !level.getFluidState(liftHead).isEmpty())
                            || !visited.add(standing.immutable())) continue;
                    queue.add(new PlannedFootingNode(
                            standing.immutable(), node.depth()
                            + (existingStanding || existingFooting
                            || existingScaffoldBody ? 0 : 1)));
                }
            }
        }
        return false;
    }

    /** Cheap straight-column proof for vanilla scaffolding before bounded catwalk BFS. */
    private static boolean straightScaffoldingColumnReaches(
            ServerLevel level, SettlementRecord settlement, BlueprintRecord blueprint,
            BuildingBlueprintTemplate template, SettlementCitizenEntity entity,
            BlockPos firstStanding, BlockPos target, int maximumDepth
    ) {
        BlockPos standing = firstStanding.immutable();
        for (int depth = 0; depth <= maximumDepth; depth++) {
            boolean scaffoldTraversalTarget = isOwnedScaffoldingTarget(level, blueprint, target);
            if (scaffoldTraversalTarget && standing.equals(target)) return true;
            if (!standing.equals(target) && !standing.above().equals(target)
                    && !standing.below().equals(target)
                    && constructionReachGeometry(standing, target)
                    && constructionStationInRange(level, standing, target, entity)) return true;
            if (depth == maximumDepth) break;
            BlockPos support = standing;
            BlockPos nextStanding = standing.above();
            BlockPos nextHead = nextStanding.above();
            boolean existingFooting = blueprint.hasTemporaryFooting(support)
                    && level.getBlockState(support).is(Block.byItem(
                    blueprint.temporaryFootingItem(support)));
            if (!settlement.contains(support) || level.isOutsideBuildHeight(nextHead)
                    || !existingFooting && (!footingAllowedAt(
                    level, settlement, blueprint, template, support)
                    || !ScaffoldSitePreparation.space(level, support)
                    || !level.getFluidState(support).isEmpty())
                    || !level.getBlockState(nextStanding).getCollisionShape(
                    level, nextStanding).isEmpty()
                    || !level.getFluidState(nextStanding).isEmpty()
                    || !level.getBlockState(nextHead).getCollisionShape(
                    level, nextHead).isEmpty()
                    || !level.getFluidState(nextHead).isEmpty()) return false;
            standing = nextStanding;
        }
        return false;
    }

    /**
     * A construction access target may name either the scaffolding block that
     * the resident must enter or the ordinary standing cell directly above it.
     * Treat both forms as the same owned access route.
     */
    private static boolean isOwnedScaffoldingTarget(
            ServerLevel level, BlueprintRecord blueprint, BlockPos target
    ) {
        return blueprint.hasTemporaryFooting(target)
                && level.getBlockState(target).is(Blocks.SCAFFOLDING)
                || blueprint.hasTemporaryFooting(target.below())
                && level.getBlockState(target.below()).is(Blocks.SCAFFOLDING);
    }

    private static boolean footingAllowedAt(
            ServerLevel level, SettlementRecord settlement, BlueprintRecord blueprint,
            BuildingBlueprintTemplate template, BlockPos position
    ) {
        if(ConstructionSiteController.groundWork(blueprint))return false;
        if (blueprint.hasTemporaryFooting(position)
                || position.equals(blueprint.markerPosition())
                || !constructionFootingTemplateCellAllowed(template, position)) return false;
        if (isExteriorConstructionFooting(template, position)
                && !isExteriorScaffoldingFrameCell(template, position)) return false;
        // Interior air volume and exterior transition space may carry temporary
        // access, but a coordinate whose final blueprint state is a real block
        // is reserved for that structure from the beginning. This prevents a
        // scaffold from occupying a future floor/wall/roof cell and later hiding
        // the actual build step. Existing world blocks and all live body/head/
        // egress constraints are still checked by the callers.
        return settlement.buildings().stream()
                .filter(BuildingRecord::active)
                .noneMatch(building -> building.contains(position))
                && ScaffoldSitePreparation.allowed(level, settlement, blueprint, position);
    }

    private static boolean insideConstructionAccessBounds(
            BuildingBlueprintTemplate template, BlockPos position
    ) {
        int margin = Math.max(2, template.transitionMargin());
        return position.getX() >= template.minCorner().getX() - margin
                && position.getX() <= template.maxCorner().getX() + margin
                && position.getY() >= template.minCorner().getY() - 3
                && position.getY() <= template.maxCorner().getY() + 1
                && position.getZ() >= template.minCorner().getZ() - margin
                && position.getZ() <= template.maxCorner().getZ() + margin;
    }

    public static boolean isConstructionFootingCandidate(BlueprintRecord blueprint, BlockPos position) {
        return constructionFootingTemplateCellAllowed(
                BlueprintTemplates.create(blueprint), position);
    }

    private static boolean constructionFootingTemplateCellAllowed(
            BuildingBlueprintTemplate template, BlockPos position
    ) {
        if (!insideConstructionAccessBounds(template, position)) return false;
        if (insideTemplateHorizontalFootprint(template, position)) return false;
        BlueprintPlacement planned = template.placementAt(position);
        return planned == null || !planned.mode().isFinalStructure()
                || planned.state().isAir();
    }

    public static boolean isExteriorConstructionFooting(BlueprintRecord blueprint, BlockPos position) {
        return isExteriorConstructionFooting(BlueprintTemplates.create(blueprint), position);
    }

    public static ConstructionAccessDiagnostics constructionAccessDiagnostics(
            ServerLevel level, SettlementRecord settlement, BlueprintRecord blueprint,
            CitizenRecord citizen, SettlementCitizenEntity entity, BlockPos target
    ) {
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        BlockPos current = entity.blockPosition();
        Item footingMaterial = preferredFootingMaterial(
                level, settlement, blueprint, entity.blockPosition());
        int availableFootings = countInventoryItem(citizen, footingMaterial);
        return new ConstructionAccessDiagnostics(
                findFootingBasePosition(level, settlement, blueprint, entity, target),
                footingAllowedAt(level, settlement, blueprint, template, current),
                level.getBlockState(current),
                availableFootings,
                hasPlannedExteriorFootingRoute(level, settlement, blueprint, template, entity,
                        current.above(), target, Math.max(0, availableFootings - 1)),
                requiresElevatedConstructionAccess(entity, target));
    }

    public static ConstructionWorkabilityDiagnostics constructionWorkabilityDiagnostics(
            ServerLevel level, SettlementCitizenEntity entity, BlockPos target
    ) {
        return new ConstructionWorkabilityDiagnostics(
                entity.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)),
                constructionStationInRange(level, entity.blockPosition(), target, entity),
                entity.getBoundingBox().intersects(new AABB(target).deflate(0.01D)),
                canWorkComfortablyFromCurrentPosition(entity, target));
    }

    private static boolean isExteriorConstructionFooting(
            BuildingBlueprintTemplate template, BlockPos position
    ) {
        return position.getX() < template.minCorner().getX()
                || position.getX() > template.maxCorner().getX()
                || position.getZ() < template.minCorner().getZ()
                || position.getZ() > template.maxCorner().getZ();
    }

    public record ConstructionAccessDiagnostics(
            @Nullable BlockPos footingBase,
            boolean currentFootingAllowed,
            BlockState currentState,
            int availableFootings,
            boolean verticalRoutePlanned,
            boolean elevatedAccessRequired
    ) {
    }

    public record ConstructionWorkabilityDiagnostics(
            double eyeDistanceSquared,
            boolean visible,
            boolean intersectsTarget,
            boolean comfortable
    ) {
    }

    private static boolean placeAndRegisterTemporaryFooting(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord blueprint, CitizenRecord citizen, SettlementCitizenEntity entity,
            BlockPos position, BlockState state, Item item, BlockPos parent
    ) {
        if (state.is(Blocks.SCAFFOLDING) && !claimScaffoldWork(level, settlement, blueprint, citizen)) return false;
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        BlockState before = level.getBlockState(position);
        // Soft obstacles are cleared by a separate paid preparation action before committing material.
        if (!settlement.contains(position) || !before.isAir()
                || !level.getFluidState(position).isEmpty()
                || !footingAllowedAt(level, settlement, blueprint, template, position)) return false;
        BlockState supportedState = supportedFootingState(level, position, state);
        if (supportedState == null) return false;
        BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, position, Block.UPDATE_CLIENTS);
        if (!level.setBlock(position, supportedState, Block.UPDATE_ALL)
                || EventHooks.onBlockPlace(entity, snapshot, Direction.UP)) {
            snapshot.restore(Block.UPDATE_ALL);
            return false;
        }
        ItemStack consumed = citizen.extractInventoryItem(stack -> stack.is(item), 1);
        if (consumed.isEmpty() || !blueprint.addTemporaryFooting(position, item, parent)) {
            if (!consumed.isEmpty()) citizen.insertIntoInventory(consumed);
            snapshot.restore(Block.UPDATE_ALL);
            return false;
        }
        entity.noteConstructionAccessProgress();
        syncHeldItem(citizen, entity);
        data.touch();
        return true;
    }

    @Nullable
    private static BlockState supportedFootingState(
            ServerLevel level, BlockPos position, BlockState requested
    ) {
        if (!requested.is(Blocks.SCAFFOLDING)) return requested;
        BlockPos belowPosition = position.below();
        BlockState below = level.getBlockState(belowPosition);
        int distance = 7;
        if (below.is(Blocks.SCAFFOLDING)) {
            distance = below.getValue(ScaffoldingBlock.DISTANCE);
        } else if (below.isFaceSturdy(level, belowPosition, Direction.UP)) {
            distance = 0;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockState adjacent = level.getBlockState(position.relative(direction));
            if (adjacent.is(Blocks.SCAFFOLDING)) {
                distance = Math.min(distance,
                        adjacent.getValue(ScaffoldingBlock.DISTANCE) + 1);
            }
        }
        if (distance >= 7) return null;
        return requested.setValue(ScaffoldingBlock.DISTANCE, distance)
                .setValue(ScaffoldingBlock.BOTTOM, !below.is(Blocks.SCAFFOLDING));
    }

    private static int findFootingSlot(CitizenRecord citizen) {
        int scaffolding = citizen.findInventorySlot(stack -> stack.is(Items.SCAFFOLDING));
        return scaffolding >= 0 ? scaffolding
                : findSolidFootingSlot(citizen);
    }

    private static int findSolidFootingSlot(CitizenRecord citizen) {
        return citizen.findInventorySlot(stack -> !stack.is(Items.SCAFFOLDING)
                && SAFE_FOOTING_ITEMS.contains(stack.getItem())
                && stack.getItem() instanceof BlockItem);
    }

    private static Item preferredFootingMaterial(
            ServerLevel level, SettlementRecord settlement,
            BlueprintRecord blueprint, BlockPos origin
    ) {
        // The creative test supply represents an unlimited catalogue and may
        // provide the purpose-built construction access item. Survival workers
        // use scaffolding only when the settlement has actually stocked it.
        if (settlement.creativeCoreInstalled() || WarehouseConstructionSupply.warehouse(
                level, settlement, origin, Items.SCAFFOLDING) != null) {
            return Items.SCAFFOLDING;
        }
        return BlueprintTemplates.create(blueprint).placements().stream()
                .map(BlueprintPlacement::material)
                .filter(java.util.Objects::nonNull)
                .filter(SAFE_FOOTING_ITEMS::contains)
                .findFirst().orElse(Items.COBBLESTONE);
    }

    private static Item preferredSolidFootingMaterial(BlueprintRecord blueprint) {
        return workMaterials(blueprint).footing();
    }

    @Nullable
    public static BlockPos findConstructionWorkPosition(
            ServerLevel level, SettlementCitizenEntity entity, BlockPos target
    ) {
        return findConstructionWorkPosition(level, entity, target, null);
    }

    @Nullable
    private static BlockPos findConstructionWorkPosition(
            ServerLevel level, SettlementCitizenEntity entity, BlockPos target,
            @Nullable SettlementRecord settlement
    ) {
        return findConstructionWorkPosition(level, entity, target, settlement, null);
    }

    @Nullable
    private static BlockPos findConstructionWorkPosition(
            ServerLevel level, SettlementCitizenEntity entity, BlockPos target,
            @Nullable SettlementRecord settlement, @Nullable BlueprintRecord blueprint
    ) {
        return entity.findReachableConstructionStation(constructionWorkPositions(level, entity, target, settlement, blueprint).stream()
                .filter(position -> !locallyBlockedUpwardConstructionStep(entity, position))
                .toList());
    }

    @Nullable
    private static BlockPos reserveConstructionWorkPosition(
            ServerLevel level, SettlementCitizenEntity entity, BlockPos target,
            @Nullable SettlementRecord settlement
    ) {
        return reserveConstructionWorkPosition(level, entity, target, settlement, null);
    }

    @Nullable
    private static BlockPos reserveConstructionWorkPosition(
            ServerLevel level, SettlementCitizenEntity entity, BlockPos target,
            @Nullable SettlementRecord settlement, @Nullable BlueprintRecord blueprint
    ) {
        long gameTime = level.getGameTime();
        List<BlockPos> remaining = new ArrayList<>(constructionWorkPositions(level, entity, target, settlement, blueprint)
                .stream().filter(candidate -> !locallyBlockedUpwardConstructionStep(entity, candidate))
                .filter(candidate -> !CitizenNavigationReservationService.stationHeldByOther(
                        level, entity.getUUID(), candidate, gameTime)).toList());
        while (!remaining.isEmpty()) {
            BlockPos candidate = entity.findReachableConstructionStation(remaining);
            if (candidate == null) return null;
            remaining.remove(candidate);
            BlockPos routeIdentity = entity.preparedConstructionRouteIdentity(candidate);
            if (!entity.constructionRouteAllowed(target, routeIdentity)) continue;
            if (CitizenNavigationReservationService.claimStation(
                    level, entity.getUUID(), candidate, entity.blockPosition(), gameTime)) return candidate;
        }
        return null;
    }

    private static boolean locallyBlockedUpwardConstructionStep(
            SettlementCitizenEntity entity, BlockPos candidate
    ) {
        if (entity.isPartialConstructionSurfacePosition(candidate)) return false;
        BlockPos current = entity.blockPosition();
        int horizontal = Math.abs(candidate.getX() - current.getX())
                + Math.abs(candidate.getZ() - current.getZ());
        return horizontal == 1 && candidate.getY() == current.getY() + 1
                && !entity.canStepOntoAdjacentConstructionBlock(candidate);
    }

    private static List<BlockPos> constructionWorkPositions(
            ServerLevel level, SettlementCitizenEntity entity, BlockPos target,
            @Nullable SettlementRecord settlement, @Nullable BlueprintRecord blueprint
    ) {
        return ConstructionPlanningBudget.geometry(entity,
                new ConstructionStationQuery(target.immutable(), settlement == null ? null : settlement.id(),
                        blueprint == null ? null : blueprint.id()), target,
                () -> computeConstructionWorkPositions(level, entity, target, settlement, blueprint));
    }

    private record ConstructionStationQuery(BlockPos target, @Nullable UUID settlementId,
                                            @Nullable UUID blueprintId) { }

    private static List<BlockPos> computeConstructionWorkPositions(
            ServerLevel level, SettlementCitizenEntity entity, BlockPos target,
            @Nullable SettlementRecord settlement, @Nullable BlueprintRecord blueprint
    ) {
        List<BlockPos> candidates = new ArrayList<>();
        BuildingBlueprintTemplate workTemplate = blueprint == null
                ? null : BlueprintTemplates.create(blueprint);
        for (int radius = 0; radius <= 5; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int dy = -5; dy <= 2; dy++) {
                        BlockPos candidate = target.offset(dx, dy, dz);
                        if (candidate.equals(target) || candidate.above().equals(target)
                                || candidate.below().equals(target)) continue;
                        if (workTemplate != null
                                && target.getY() <= workTemplate.minCorner().getY() + 1
                                && insideTemplateHorizontalFootprint(workTemplate, candidate)
                                && candidate.getY() <= workTemplate.minCorner().getY()) continue;
                        if (!constructionWorkStationGeometry(candidate, target)
                                || !entity.canUseConstructionSurfaceAtPosition(candidate)
                                || settlement != null && settlement.buildings().stream()
                                .filter(BuildingRecord::active)
                                .anyMatch(building -> building.contains(candidate))
                                || !constructionStationInRange(level, candidate, target, entity)) continue;
                        candidates.add(candidate.immutable());
                    }
                }
            }
        }
        candidates.sort(Comparator
                .comparingDouble((BlockPos position) -> constructionTargetDistanceSqr(position, target))
                .thenComparingDouble(position -> position.distSqr(entity.blockPosition()))
                .thenComparingInt(position -> Math.abs(position.getY() - entity.getBlockY())));
        // Stations are ranked from live geometry and still need a real path or
        // explicit scaffold controller before use. A full ground-egress flood
        // fill here was both prohibitively expensive and stricter than player
        // movement: it discarded useful roof frontier cells even though the
        // worker could later leave through scaffolding or a checked edge drop.
        return List.copyOf(candidates.subList(0, Math.min(64, candidates.size())));
    }

    private static boolean insideTemplateHorizontalFootprint(
            BuildingBlueprintTemplate template, BlockPos position
    ) {
        return position.getX() >= template.minCorner().getX()
                && position.getX() <= template.maxCorner().getX()
                && position.getZ() >= template.minCorner().getZ()
                && position.getZ() <= template.maxCorner().getZ();
    }

    public static boolean constructionReachGeometry(BlockPos standingPosition, BlockPos target) {
        return reachGeometry(standingPosition, target, MAX_CONSTRUCTION_REACH);
    }

    public static boolean constructionWorkStationGeometry(BlockPos standingPosition, BlockPos target) {
        return reachGeometry(standingPosition, target, PREFERRED_CONSTRUCTION_REACH);
    }

    private static boolean reachGeometry(BlockPos standingPosition, BlockPos target, double reach) {
        Vec3 eye = new Vec3(standingPosition.getX() + 0.5D, standingPosition.getY() + 1.62D,
                standingPosition.getZ() + 0.5D);
        return distanceToBlockSqr(eye, target) <= reach * reach;
    }

    private static double constructionTargetDistanceSqr(BlockPos standingPosition, BlockPos target) {
        Vec3 eye = new Vec3(standingPosition.getX() + 0.5D, standingPosition.getY() + 1.62D,
                standingPosition.getZ() + 0.5D);
        return distanceToBlockSqr(eye, target);
    }

    private static double distanceToBlockSqr(Vec3 eye, BlockPos target) {
        double closestX = net.minecraft.util.Mth.clamp(
                eye.x, target.getX(), target.getX() + 1.0D);
        double closestY = net.minecraft.util.Mth.clamp(
                eye.y, target.getY(), target.getY() + 1.0D);
        double closestZ = net.minecraft.util.Mth.clamp(
                eye.z, target.getZ(), target.getZ() + 1.0D);
        return eye.distanceToSqr(closestX, closestY, closestZ);
    }

    static boolean canConstructFromCurrentPosition(SettlementCitizenEntity entity, BlockPos target) {
        return withinConstructionReach(entity, target)
                && constructionStationInRange((ServerLevel) entity.level(), entity.blockPosition(), target, entity)
                && !entity.getBoundingBox().intersects(new AABB(target).deflate(0.01D));
    }

    /**
     * Scaffolding is an occupiable construction route rather than a solid work
     * target. While inside an owned column the resident may recover a scaffold
     * above its feet, exactly as a player can, provided the whole gap is still
     * that same open column and the ordinary four-block reach is respected.
     */
    private static boolean canRecoverOwnedScaffoldingFromCurrentPosition(
            ServerLevel level, BlueprintRecord blueprint,
            SettlementCitizenEntity entity, BlockPos target
    ) {
        if (!blueprint.hasTemporaryFooting(target)
                || !level.getBlockState(target).is(Blocks.SCAFFOLDING)
                || !withinConstructionReach(entity, target)) return false;
        BlockPos feet = constructionFeetPosition(entity);
        if (feet.getX() == target.getX() && feet.getZ() == target.getZ()
                && target.getY() >= feet.getY()) {
            for (int y = feet.getY(); y <= target.getY(); y++) {
                BlockPos columnCell = new BlockPos(target.getX(), y, target.getZ());
                BlockState state = level.getBlockState(columnCell);
                if (!state.is(Blocks.SCAFFOLDING)
                        && !state.getCollisionShape(level, columnCell).isEmpty()) return false;
            }
            return true;
        }
        return constructionStationInRange(level, entity.blockPosition(), target, entity)
                && !entity.getBoundingBox().intersects(new AABB(target).deflate(0.01D));
    }

    private static boolean withinConstructionReach(SettlementCitizenEntity entity, BlockPos target) {
        return distanceToBlockSqr(entity.getEyePosition(), target)
                <= MAX_CONSTRUCTION_REACH * MAX_CONSTRUCTION_REACH;
    }

    private static boolean canWorkComfortablyFromCurrentPosition(
            SettlementCitizenEntity entity, BlockPos target
    ) {
        return distanceToBlockSqr(entity.getEyePosition(), target)
                <= PREFERRED_CONSTRUCTION_REACH * PREFERRED_CONSTRUCTION_REACH
                && constructionStationInRange((ServerLevel) entity.level(), entity.blockPosition(), target, entity)
                && !entity.getBoundingBox().intersects(new AABB(target).deflate(0.01D));
    }

    private static boolean canContinueConstructionFromCurrentPosition(
            SettlementCitizenEntity entity, BlockPos target
    ) {
        return distanceToBlockSqr(entity.getEyePosition(), target)
                <= LOCAL_CONSTRUCTION_BATCH_REACH * LOCAL_CONSTRUCTION_BATCH_REACH
                && constructionStationInRange((ServerLevel) entity.level(),
                entity.blockPosition(), target, entity)
                && !entity.getBoundingBox().intersects(new AABB(target).deflate(0.01D));
    }

    public static boolean constructionStationInRange(
            ServerLevel level, BlockPos station, BlockPos target, SettlementCitizenEntity entity
    ) {
        return constructionTargetDistanceSqr(station, target) <= MAX_CONSTRUCTION_REACH * MAX_CONSTRUCTION_REACH;
    }

    @Nullable
    public static BlockPos findFarmWorkPosition(
            ServerLevel level, SettlementCitizenEntity entity, BuildingRecord farm, BlockPos target
    ) {
        return farmWorkPositions(level, entity, farm, target).stream()
                .filter(entity::hasReachablePathTo)
                .findFirst().orElse(null);
    }

    /** Farming shares the arrived-work-area model with construction; crop cells are not navigation goals. */
    private static boolean enterFarmWorkArea(ServerLevel level,SettlementSavedData data,CitizenRecord citizen,
            SettlementCitizenEntity entity,BuildingRecord farm,BlockPos target) {
        if(insideFarmWorkArea(entity,farm)) {
            entity.stopSettlementNavigation();
            return true;
        }
        var station=reserveFarmWorkPosition(level,entity,farm,target);
        if(station==null) {
            deferFarmTarget(level,citizen,target);clear(data,citizen,entity);return false;
        }
        String failure=entity.navigateTo(station,WALK_SPEED);
        if("NO_PATH".equals(failure) || "STUCK".equals(failure)) {
            deferFarmTarget(level,citizen,target);clear(data,citizen,entity);
        } else setActivity(data,citizen,entity,CitizenActivity.FARMING,failure);
        return false;
    }

    @Nullable
    private static BlockPos reserveFarmWorkPosition(ServerLevel level, SettlementCitizenEntity entity,
            BuildingRecord farm, BlockPos target) {
        BlockPos current=entity.navigationFeetPosition();
        if(insideFarmWorkArea(entity,farm) && entity.canStandAtPosition(current))return current;
        return farmWorkPositions(level,entity,farm,target).stream()
                .filter(entity::hasReachablePathTo).findFirst().orElse(null);
    }

    private static boolean insideFarmWorkArea(SettlementCitizenEntity entity,BuildingRecord farm) {
        return insideFarmWorkArea(entity.navigationFeetPosition(),farm);
    }
    private static boolean insideFarmWorkArea(BlockPos feet,BuildingRecord farm) {
        if(farm.region()!=null) {
            var cell=farm.region().cell(feet);
            return cell!=null && cell.use()!=com.hkin.exiledsettlement.region.ProductionRegion.Use.BOUNDARY
                    && Math.abs(feet.getY()-(cell.ground().getY()+1))<=1;
        }
        return feet.getX()>farm.minCorner().getX() && feet.getX()<farm.maxCorner().getX()
                && feet.getZ()>farm.minCorner().getZ() && feet.getZ()<farm.maxCorner().getZ()
                && feet.getY()>=farm.minCorner().getY() && feet.getY()<=farm.maxCorner().getY()+1;
    }

    private static List<BlockPos> farmWorkPositions(ServerLevel level,SettlementCitizenEntity entity,
            BuildingRecord farm,BlockPos target) {
        Set<BlockPos> ground=new HashSet<>(farmGroundPositions(farm));
        if(farm.region()!=null)for(var cell:farm.region().cells.values())
            if(cell.use()!=com.hkin.exiledsettlement.region.ProductionRegion.Use.BOUNDARY)ground.add(cell.ground());
        return ground.stream().map(BlockPos::above).filter(p->insideFarmWorkArea(p,farm))
                .filter(entity::canStandAtPosition).sorted(Comparator.comparingDouble((BlockPos p)->p.distSqr(entity.blockPosition()))
                        .thenComparingLong(BlockPos::asLong)).toList();
    }

    public static boolean farmInteractionVisible(
            ServerLevel level, BlockPos station, BlockPos target, SettlementCitizenEntity entity
    ) {
        Vec3 eye = new Vec3(station.getX() + 0.5D, station.getY() + 1.62D,
                station.getZ() + 0.5D);
        double dx = Math.max(0, Math.abs(station.getX() - target.getX()) - 0.5D);
        double dz = Math.max(0, Math.abs(station.getZ() - target.getZ()) - 0.5D);
        if (dx * dx + dz * dz > 16.0D || Math.abs(station.getY() - target.getY()) > 2) return false;
        BlockHitResult hit = level.clip(new ClipContext(
                eye, Vec3.atCenterOf(target), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    private static boolean nearWorkPosition(SettlementCitizenEntity entity, BlockPos position) {
        return entity.distanceToSqr(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D) <= 0.81D;
    }

    private record FootingStep(BlockPos blockPosition, BlockPos standingPosition, double targetDistance) {
    }

    private record PlannedFootingNode(BlockPos standingPosition, int depth) {
    }

    private static void moveToConstructionPosition(
            ServerLevel level, SettlementSavedData data, CitizenRecord citizen,
            SettlementCitizenEntity entity, BlockPos workPosition,
            CitizenActivity activity
    ) {
        moveToConstructionPosition(level, data, citizen, entity, null, workPosition, activity);
    }

    private static void moveToConstructionPosition(
            ServerLevel level, SettlementSavedData data, CitizenRecord citizen,
            SettlementCitizenEntity entity, @Nullable BlueprintRecord blueprint,
            BlockPos workPosition, CitizenActivity activity
    ) {
        moveToConstructionPosition(level, data, citizen, entity, blueprint,
                workPosition, activity, null);
    }

    private static void moveToConstructionPosition(
            ServerLevel level, SettlementSavedData data, CitizenRecord citizen,
            SettlementCitizenEntity entity, @Nullable BlueprintRecord blueprint,
            BlockPos workPosition, CitizenActivity activity,
            @Nullable BlockPos stableRouteIdentity
    ) {
        BlockPos routeIdentity = stableRouteIdentity == null
                ? entity.preparedConstructionRouteIdentity(workPosition)
                : stableRouteIdentity.immutable();
        // All standing cells around one scaffold root are the same physical
        // access branch. Measure progress against that immutable root as well as
        // keying failures by it; otherwise shuffling between two adjacent root
        // approaches manufactures a fresh route and evades the three-attempt
        // insurance forever.
        entity.trackConstructionRoute(routeIdentity,
                stableRouteIdentity == null ? workPosition : routeIdentity);
        if (entity.constructionRouteApproachTimedOut()) {
            CitizenNavigationReservationService.abandonStation(
                    level, entity.getUUID(), level.getGameTime());
            entity.finishConstructionMovement();
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED,
                    entity.trackedConstructionRouteExhausted()
                            ? "CONSTRUCTION_ROUTE_EXHAUSTED" : "STUCK");
            return;
        }
        BlockPos feet = constructionFeetPosition(entity);
        boolean temporaryAccessMove = feet.getY() != workPosition.getY()
                || blueprint != null && (occupiedTemporarySupport(level, blueprint, feet) != null
                || occupiedTemporarySupport(level, blueprint, workPosition) != null);
        boolean nearbyConstructionMove = temporaryAccessMove
                || feet.distManhattan(workPosition) <= 2;
        if (nearbyConstructionMove) {
            if (entity.beginAdjacentConstructionMovement(workPosition, WALK_SPEED)) {
                entity.traceConstructionDecision("move-adjacent:" + workPosition);
                setActivity(data, citizen, entity, activity, "");
                return;
            }
            if (entity.beginLocalConstructionRoute(workPosition, WALK_SPEED)) {
                entity.traceConstructionDecision("move-local-route:" + workPosition);
                setActivity(data, citizen, entity, activity, "");
                return;
            }
            if (entity.beginDiagonalConstructionApproach(workPosition, WALK_SPEED)) {
                entity.traceConstructionDecision("move-diagonal:" + workPosition);
                setActivity(data, citizen, entity, activity, "");
                return;
            }
            if (entity.beginConstructionLeapMovement(workPosition, WALK_SPEED)) {
                entity.traceConstructionDecision("move-leap:" + workPosition);
                setActivity(data, citizen, entity, activity, "");
                return;
            }
        }
        boolean elevatedOrScaffoldLanding = workPosition.getY() > feet.getY()
                || blueprint != null
                && occupiedTemporarySupport(level, blueprint, workPosition) != null;
        if (elevatedOrScaffoldLanding
                && entity.beginExistingClimbableMovement(workPosition, WALK_SPEED)) {
            entity.traceConstructionDecision("move-existing-climb:" + workPosition);
            setActivity(data, citizen, entity, activity, "");
            return;
        }
        BlockPos entranceApproach = constructionEntranceApproach(blueprint, entity, workPosition);
        if (entranceApproach != null && !nearWorkPosition(entity, entranceApproach)) {
            String failure = entity.navigateTo(entranceApproach, WALK_SPEED);
            entity.traceConstructionDecision("move-entrance:" + entranceApproach
                    + ":" + failure + ":" + entity.settlementNavigationDiagnostics());
            if (terminalNavigationFailure(failure)) {
                entity.recordTrackedConstructionRouteFailure();
                CitizenNavigationReservationService.abandonStation(
                        level, entity.getUUID(), level.getGameTime());
            }
            setActivity(data, citizen, entity, movementActivity(activity, failure), failure);
            return;
        }
        BlockPos climbApproach = elevatedOrScaffoldLanding
                ? entity.existingClimbableApproach(workPosition) : null;
        if (climbApproach != null && !nearWorkPosition(entity, climbApproach)) {
            String failure = entity.navigateTo(climbApproach, WALK_SPEED);
            entity.traceConstructionDecision("move-climb-approach:" + climbApproach
                    + ":" + failure + ":" + entity.settlementNavigationDiagnostics());
            if (terminalNavigationFailure(failure)) {
                entity.recordTrackedConstructionRouteFailure();
                CitizenNavigationReservationService.abandonStation(
                        level, entity.getUUID(), level.getGameTime());
            }
            setActivity(data, citizen, entity, movementActivity(activity, failure), failure);
            return;
        }
        String failure = entity.navigateTo(workPosition, WALK_SPEED);
        entity.traceConstructionDecision("move-navigation:" + workPosition
                + ":" + failure + ":" + entity.settlementNavigationDiagnostics());
        if ("NO_PATH".equals(failure)) failure = "CONSTRUCTION_POSITION_UNREACHABLE";
        if (terminalNavigationFailure(failure)) {
            entity.recordTrackedConstructionRouteFailure();
            CitizenNavigationReservationService.abandonStation(level, entity.getUUID(), level.getGameTime());
        }
        setActivity(data, citizen, entity,
                movementActivity(activity, failure), failure);
    }

    /**
     * Routes a low interior work position through the entrance declared by the
     * blueprint instead of asking the navigator to rediscover a doorway through
     * an almost completed shell. This is geometry-driven and works for every
     * template that supplies entrances; it does not depend on a building type,
     * material, or fixed construction order.
     */
    @Nullable
    private static BlockPos constructionEntranceApproach(
            @Nullable BlueprintRecord blueprint, SettlementCitizenEntity entity,
            BlockPos workPosition
    ) {
        if (blueprint == null) return null;
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        if (template.entrances().isEmpty()
                || !insideHorizontalBounds(template, workPosition)
                || insideHorizontalBounds(template, entity.blockPosition())) return null;

        return template.entrances().stream()
                .filter(entrance -> workPosition.getY() <= entrance.position().getY() + 1)
                .map(entrance -> entrance.position().relative(entrance.outward()))
                .filter(entity::canStandAtPosition)
                .min(Comparator
                        .comparingDouble((BlockPos position) -> position.distSqr(entity.blockPosition()))
                        .thenComparingDouble(position -> position.distSqr(workPosition)))
                .map(BlockPos::immutable)
                .orElse(null);
    }

    private static boolean insideHorizontalBounds(
            BuildingBlueprintTemplate template, BlockPos position
    ) {
        return position.getX() >= template.minCorner().getX()
                && position.getX() <= template.maxCorner().getX()
                && position.getZ() >= template.minCorner().getZ()
                && position.getZ() <= template.maxCorner().getZ();
    }

    private static boolean near(SettlementCitizenEntity entity, BlockPos pos) {
        return CitizenInteractionRange.contains(entity, pos);
    }

    private static void moveToWarehouseStation(
            ServerLevel level, SettlementSavedData data, CitizenRecord citizen,
            SettlementCitizenEntity entity, BlockPos target, CitizenActivity activity
    ) {
        String failure = entity.navigateTo(target, WALK_SPEED);
        if (terminalNavigationFailure(failure)) {
            WarehouseInventoryService.abandonInteractionPosition(level, entity);
            SettlementCoreInteractionService.abandon(level,entity);
        }
        setActivity(data, citizen, entity,
                movementActivity(activity, failure), failure);
    }

    private static void waitForWarehouseStation(
            ServerLevel level, SettlementSavedData data, CitizenRecord citizen,
            SettlementCitizenEntity entity, @Nullable BuildingRecord building, CitizenActivity activity
    ) {
        if (building == null) {
            setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "WAREHOUSE_STATION_BUSY");
            return;
        }
        BlockPos approach = WarehouseInventoryService.approachPosition(level, building, entity);
        if (approach != null) {
            String failure = entity.navigateTo(approach, WALK_SPEED);
            setActivity(data, citizen, entity, movementActivity(activity, failure), failure);
            return;
        }
        BlockPos waiting = WarehouseInventoryService.waitingPosition(level, building, entity);
        if (waiting != null && !nearWorkPosition(entity, waiting)) {
            String failure = entity.navigateTo(waiting, WALK_SPEED * 0.85D);
            setActivity(data, citizen, entity,
                    movementActivity(activity, failure),
                    failure.isEmpty() ? "WAREHOUSE_STATION_BUSY" : failure);
            return;
        }
        setActivity(data, citizen, entity, CitizenActivity.BLOCKED, "WAREHOUSE_STATION_BUSY");
    }

    private static void waitForSupplyStation(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity,
            @Nullable BuildingRecord warehouse, boolean useWarehouse, CitizenActivity activity
    ) {
        if (useWarehouse) {
            waitForWarehouseStation(level, data, citizen, entity, warehouse, activity);
            return;
        }
        BlockPos waiting = SettlementCoreInteractionService.reserveWaitingPosition(level, settlement, entity);
        if (waiting != null && !nearWorkPosition(entity, waiting)) {
            String failure = entity.navigateTo(waiting, WALK_SPEED * 0.85D);
            setActivity(data, citizen, entity, movementActivity(activity, failure),
                    failure.isEmpty() ? "CORE_SUPPLY_STATION_BUSY" : failure);
            return;
        }
        String reason = SettlementCoreInteractionService.hasPhysicalStation(level, settlement, entity)
                ? "CORE_SUPPLY_STATION_BUSY" : "CORE_SUPPLY_INACCESSIBLE";
        setActivity(data, citizen, entity, CitizenActivity.BLOCKED, reason);
    }

    private static void releaseSupplyStation(ServerLevel level, SettlementCitizenEntity entity) {
        CitizenNavigationReservationService.releaseStation(level, entity.getUUID());
    }

    private static void abandonSupplyStation(ServerLevel level, SettlementCitizenEntity entity) {
        SettlementCoreInteractionService.abandon(level,entity);
        CitizenNavigationReservationService.abandonStation(level, entity.getUUID(), level.getGameTime());
    }

    private static void move(
            SettlementSavedData data, CitizenRecord citizen, SettlementCitizenEntity entity,
            BlockPos target, CitizenActivity activity
    ) {
        String failure = entity.navigateTo(target, WALK_SPEED);
        setActivity(data, citizen, entity, movementActivity(activity, failure), failure);
    }

    private static CitizenActivity movementActivity(CitizenActivity intended, String failure) {
        return failure.isEmpty() || "TRAFFIC_WAIT".equals(failure) ? intended : CitizenActivity.BLOCKED;
    }

    private static boolean terminalNavigationFailure(String failure) {
        return "NO_PATH".equals(failure) || "STUCK".equals(failure);
    }

    private static void setActivity(
            SettlementSavedData data, CitizenRecord citizen, SettlementCitizenEntity entity,
            CitizenActivity activity, String failure
    ) {
        boolean changed = citizen.updateActivity(activity) | citizen.updateWorkFailure(failure);
        entity.setActivity(activity);
        if (changed) data.touch();
    }

    private static void clear(SettlementSavedData data, CitizenRecord citizen, SettlementCitizenEntity entity) {
        LOGISTICS_EXITS.remove(entity);
        TREE_HARVESTS.remove(entity);
        boolean changed = citizen.clearWork();
        entity.stopSettlementNavigation();
        syncHeldItem(citizen, entity);
        entity.setActivity(CitizenActivity.IDLE);
        if (changed) data.touch();
    }

    private static void recover(
            ServerLevel level, SettlementSavedData data, CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        ItemStack carried = citizen.carriedItem();
        if (!carried.isEmpty()) Block.popResource(level, entity.blockPosition(), citizen.removeCarriedItem());
        clear(data, citizen, entity);
    }

    public static void recoverCarriedItem(
            ServerLevel level, SettlementSavedData data, CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        // Death-only handoff: the record owns every item; vanilla equipment is
        // merely a projection. Drain ownership before spawning drops so a second
        // death notification or a reloaded dead record cannot emit another copy.
        List<ItemStack> drops = new ArrayList<>();
        for (int slot = 0; slot < CitizenRecord.INVENTORY_SIZE; slot++) {
            if (citizen.inventoryItem(slot).isEmpty()) continue;
            citizen.selectActiveSlot(slot);
            drops.add(citizen.removeCarriedItem());
        }
        for (net.minecraft.world.entity.EquipmentSlot slot : CitizenRecord.EQUIPMENT_SLOTS) {
            ItemStack stack = citizen.equipmentItem(slot);
            if (!stack.isEmpty()) drops.add(stack);
            citizen.updateEquipment(slot, ItemStack.EMPTY);
        }
        clear(data, citizen, entity);
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            entity.setItemSlot(slot, ItemStack.EMPTY);
        }
        data.touch();
        // Resident belongings are entity drops, not block drops: doTileDrops
        // must not silently destroy the contents of a dead resident's backpack.
        for (ItemStack stack : drops) entity.spawnAtLocation(stack);
    }

    private static boolean huntable(Animal animal) {
        return animal.isAlive() && !animal.isBaby() && !animal.hasCustomName() && !animal.isLeashed()
                && (!(animal instanceof TamableAnimal tamable) || !tamable.isTame());
    }

    /**
     * Conservative protection: managed pasture animals and animals inside a
     * closed player-built pen are never valid hunting targets. The bounded
     * flood runs only while choosing/revalidating one target, not every tick
     * for every animal in the settlement.
     */
    public static boolean protectedFromHunting(
            ServerLevel level, SettlementRecord settlement, Animal animal
    ) {
        if (!huntable(animal) || insidePasture(settlement, animal.blockPosition())) return true;
        BlockPos start = animal.blockPosition();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        open.add(start);
        seen.add(start);
        while (!open.isEmpty() && seen.size() <= 256) {
            BlockPos current = open.removeFirst();
            if (Math.abs(current.getX() - start.getX()) >= 12
                    || Math.abs(current.getZ() - start.getZ()) >= 12) return false;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos next = current.relative(direction);
                if (seen.contains(next) || !level.hasChunkAt(next)) continue;
                BlockPos standing = animalStandingPosition(level, next, current.getY());
                if (standing == null) continue;
                seen.add(standing);
                open.addLast(standing);
            }
        }
        return open.isEmpty();
    }

    @Nullable
    private static BlockPos animalStandingPosition(ServerLevel level, BlockPos horizontal, int fromY) {
        for (int dy : new int[]{0, 1, -1}) {
            BlockPos feet = new BlockPos(horizontal.getX(), fromY + dy, horizontal.getZ());
            if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                    || !level.getFluidState(feet).isEmpty()
                    || !level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)) continue;
            return feet.immutable();
        }
        return null;
    }

    private static boolean capturable(Animal animal) {
        return huntable(animal) && animal.canBeLeashed();
    }

    @Nullable
    private static BuildingRecord nearestPastureWithCapacity(
            ServerLevel level, SettlementRecord settlement, BlockPos from
    ) {
        return settlement.buildings().stream()
                .filter(building -> usable(building, BuildingType.PASTURE_AREA))
                .filter(building -> level.getEntitiesOfClass(Animal.class, buildingArea(building, 2),
                        animal -> insidePastureInterior(building,animal.blockPosition()) && com.hkin.exiledsettlement.region.ProductionRegionService.matchesAnimal(building,animal)).size()
                        < BuildingProductionService.animalCapacity(building))
                .min(Comparator.comparingDouble(building -> building.position().distSqr(from)))
                .orElse(null);
    }

    private static boolean insidePasture(SettlementRecord settlement, BlockPos position) {
        return settlement.buildings().stream()
                .filter(building -> usable(building, BuildingType.PASTURE_AREA))
                .anyMatch(building -> insideBuildingArea(building, position, 1));
    }

    private static boolean insideOperationalPastureInterior(SettlementRecord settlement, BlockPos position) {
        return settlement.buildings().stream()
                .filter(building -> usable(building, BuildingType.PASTURE_AREA))
                .anyMatch(building -> insidePastureInterior(building, position));
    }

    private static boolean insideBuildingArea(BuildingRecord building, BlockPos position, int margin) {
        if(building.region()!=null)return building.region().contains(position);
        return buildingArea(building, margin).contains(position.getX() + 0.5D,
                position.getY() + 0.5D, position.getZ() + 0.5D);
    }

    private static AABB buildingArea(BuildingRecord building, int margin) {
        if (building.minCorner().equals(building.maxCorner())) return area(building.position(), 16, 6);
        BlockPos min = building.minCorner();
        BlockPos max = building.maxCorner().offset(1, 1, 1);
        return new AABB(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ())
                .inflate(margin, 3, margin);
    }

    private static AABB area(BlockPos center, int horizontal, int vertical) {
        return new AABB(center).inflate(horizontal, vertical, horizontal);
    }

    public static SchedulePhase schedulePhase(long dayTime) {
        long time = Math.floorMod(dayTime, 24000L);
        if (time >= SLEEP_START_TICK && time < SLEEP_END_TICK) return SchedulePhase.SLEEP;
        if (time >= WORK_START_TICK && time < WORK_END_TICK) return SchedulePhase.WORK;
        return SchedulePhase.REST;
    }

    /** Stable personal offsets do not need another save field or a new daily random draw. */
    public static int scheduleOffset(CitizenRecord citizen) {
        int spread = com.hkin.exiledsettlement.settlement.CommunityContent.scheduleOffsetTicks;
        return Math.floorMod(citizen.id().hashCode(), spread * 2 + 1) - spread;
    }

    public static SchedulePhase schedulePhase(long dayTime, CitizenRecord citizen) {
        if(com.hkin.exiledsettlement.debug.ForceWorkDebugService.forced(citizen))return SchedulePhase.WORK;
        return naturalSchedulePhase(dayTime,citizen);
    }
    public static SchedulePhase naturalSchedulePhase(long dayTime,CitizenRecord citizen){
        long time = Math.floorMod(dayTime - scheduleOffset(citizen), 24000L);
        if (time >= SLEEP_START_TICK && time < SLEEP_END_TICK) return SchedulePhase.SLEEP;
        boolean farmer = citizen.profession() == CitizenProfession.FARMER || citizen.profession() == CitizenProfession.MELON_FARMER;
        long start = farmer ? com.hkin.exiledsettlement.settlement.CommunityContent.farmerWorkStartTick : WORK_START_TICK;
        long end = farmer ? com.hkin.exiledsettlement.settlement.CommunityContent.farmerWorkEndTick : WORK_END_TICK;
        return time >= start && time < end ? SchedulePhase.WORK : SchedulePhase.REST;
    }

    private static boolean isWorkTime(ServerLevel level, CitizenRecord citizen) {
        return schedulePhase(level.getDayTime(), citizen) == SchedulePhase.WORK;
    }

    private static boolean isNight(ServerLevel level) {
        return schedulePhase(level.getDayTime()) == SchedulePhase.SLEEP;
    }

    private static void syncHeldItem(CitizenRecord citizen, SettlementCitizenEntity entity) {
        CitizenEquipmentService.syncVisibleEquipment(citizen, entity);
    }
}
