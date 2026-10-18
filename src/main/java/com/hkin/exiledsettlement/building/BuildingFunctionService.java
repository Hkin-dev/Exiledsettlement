package com.hkin.exiledsettlement.building;

import com.hkin.exiledsettlement.blueprint.BlueprintBlockStateResolver;
import com.hkin.exiledsettlement.blueprint.BlueprintPlacementMode;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.citizen.ProfessionAssignmentService;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.FluidTags;

import java.util.ArrayList;
import java.util.List;

public final class BuildingFunctionService {
    public static final int VALIDATION_INTERVAL_TICKS = 100;
    public static final int HOUSE_SCAN_RADIUS = 12;
    public static final int WAREHOUSE_SCAN_RADIUS = 12;
    public static final int WORKPLACE_SCAN_RADIUS = 8;
    private static final int VERTICAL_SCAN_RADIUS = 4;

    private BuildingFunctionService() {
    }

    public static void tickLevel(ServerLevel level) {
        SettlementSavedData data = SettlementSavedData.get(level);
        for (SettlementRecord settlement : data.settlements()) {
            if (settlement.active()) {
                validateSettlement(level, data, settlement);
            }
        }
    }

    public static void validateSettlement(ServerLevel level, SettlementSavedData data, SettlementRecord settlement) {
        boolean changed = false;
        for (BuildingRecord building : settlement.buildings()) {
            if (building.active() && !building.demolitionQueued()) {
                changed |= validateBuilding(level, building);
            }
        }
        changed |= settlement.refreshHousingAssignments();
        ProfessionAssignmentService.refresh(data, settlement);
        if (changed) {
            data.touch();
        }
    }

    public static void validateSingle(
            ServerLevel level,
            SettlementSavedData data,
            SettlementRecord settlement,
            BuildingRecord building
    ) {
        boolean changed = building.demolitionQueued() ? false : validateBuilding(level, building);
        changed |= settlement.refreshHousingAssignments();
        ProfessionAssignmentService.refresh(data, settlement);
        if (changed) {
            data.touch();
        }
    }

    private static boolean validateBuilding(ServerLevel level, BuildingRecord building) {
        BlockPos anchor = building.position();
        if (!level.hasChunkAt(anchor)) {
            return false;
        }

        if(building.region()!=null)return com.hkin.exiledsettlement.region.ProductionRegionService.validate(level,building);
        refreshDynamicBlockShapes(level, building);

        return switch (building.type()) {
            case HOUSE, HOUSE_2, HOUSE_3, ADVANCED_HOUSE_1, ADVANCED_HOUSE_2, ADVANCED_HOUSE_3,
                    SPRUCE_HOME, GALLERY_HOME, COURTYARD_HOME, STARTER_CAMP, APARTMENT -> validateHouse(level, building);
            case WAREHOUSE, MARKET -> validateWarehouse(level, building);
            case SCHOOL, CHURCH -> building.updateFunction(false, BuildingStatus.REGISTERED_ONLY, 0, List.of());
            case FARM_FIELD, MELON_FIELD -> validateFarmField(level, building);
            case PASTURE_AREA -> validatePasture(level, building);
            case HUNTING_LODGE -> validateWorkplace(level, building, 2);
            case TOOL_WORKSHOP -> validateWorkplace(level, building, 2);
            case SAWMILL -> validateWorkplace(level, building, 2);
            case FORESTER_LODGE -> validateWorkplace(level, building, 2);
            case TREE_FARM -> validateTreeFarm(level, building);
            case TOWN_HALL -> validateWorkplace(level, building, 0);
            case FISHING_HUT -> validateFishingHut(level, building);
            case BRICK_KILN -> validateKiln(level, building);
            case MINE -> validateWorkplace(level, building, 10);
            case TAVERN, RESTAURANT -> validateWorkplace(level, building,
                    com.hkin.exiledsettlement.settlement.CommunityCookingService.offersMeals(building) ? 1 : 0);
            case LEISURE_GARDEN, LAMP_SMALL, LAMP_ARM, LAMP_DOUBLE, BENCH, FLOWERBED,
                    SIGNPOST, WELL, WOODSHED ->
                    building.updateFunction(true, BuildingStatus.OPERATIONAL, 0, List.of());
            case WAGON -> building.templateVersion() >= 3 ? validateCampStorage(level, building)
                    : level.getBlockEntity(building.position()) instanceof net.minecraft.world.Container container
                    ? building.updateFunction(true, BuildingStatus.OPERATIONAL, WarehouseStock.applies(building)?WarehouseStock.CAPACITY:container.getContainerSize(), List.of(building.position()))
                    : building.updateFunction(false, BuildingStatus.MISSING_CONTAINER, 0, List.of());
        };
    }

    private static void refreshDynamicBlockShapes(ServerLevel level, BuildingRecord building) {
        if (!BlueprintTemplates.supports(building.type())) return;
        var template = BlueprintTemplates.createForBuilding(building);
        BlueprintBlockStateResolver.refreshCompleted(level,template);
    }

    private static boolean validateHouse(ServerLevel level, BuildingRecord building) {
        List<BlockPos> entrances = new ArrayList<>();
        List<BlockPos> beds = new ArrayList<>();
        for (BlockPos cursor : scanBuildingPositions(building, HOUSE_SCAN_RADIUS)) {
            if (!level.hasChunkAt(cursor)) {
                continue;
            }
            BlockState state = level.getBlockState(cursor);
            if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD) {
                beds.add(cursor.immutable());
            }
            if (state.is(BlockTags.DOORS)
                    && (!state.hasProperty(DoorBlock.HALF)
                    || state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER)) {
                entrances.add(cursor.immutable());
            }
        }

        int capacity = Math.min(building.type().housingLimit(), beds.size());
        if (beds.isEmpty()) {
            return building.updateFunction(false, BuildingStatus.MISSING_BED, 0, List.of());
        }
        if (building.type() == BuildingType.STARTER_CAMP && building.templateVersion() >= 3) {
            // Open tents use a real, supported entrance instead of an artificial door.
            entrances.clear();
            for (var entrance : BlueprintTemplates.createForBuilding(building).entrances()) {
                boolean clear = true;
                for (int step = -1; step <= 1; step++) {
                    var p = entrance.position().relative(entrance.outward(), step);
                    var box = new net.minecraft.world.phys.AABB(p.getX()+.2,p.getY(),p.getZ()+.2,
                            p.getX()+.8,p.getY()+1.95,p.getZ()+.8);
                    clear &= level.hasChunkAt(p) && level.getBlockState(p.below()).isFaceSturdy(level,p.below(),net.minecraft.core.Direction.UP)
                            && level.noBlockCollision(null,box);
                }
                if (clear) entrances.add(entrance.position());
            }
        }
        if (entrances.isEmpty()) {
            return building.updateFunction(false, building.type() == BuildingType.STARTER_CAMP && building.templateVersion() >= 3
                    ? BuildingStatus.ENTRANCE_BLOCKED : BuildingStatus.MISSING_DOOR, capacity, List.of());
        }
        return building.updateFunction(true, BuildingStatus.OPERATIONAL, capacity,
                beds.stream().limit(capacity).toList());
    }

    private static boolean validateCampStorage(ServerLevel level, BuildingRecord building) {
        List<BlockPos> containers = new ArrayList<>();
        // Version 4 spans the player's stacked barrel arrangement. Its bounding
        // box also contains a furnace, which must not become shared storage.
        // Keep version 3's historical container scan unchanged.
        for (var p : BlockPos.betweenClosed(building.minCorner(), building.maxCorner()))
            if (level.hasChunkAt(p) && level.getBlockEntity(p) instanceof Container
                    && (building.templateVersion() < 4 || level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.BARREL)))
                containers.add(p.immutable());
        return containers.isEmpty()
                ? building.updateFunction(false, BuildingStatus.MISSING_CONTAINER, 0, List.of())
                : building.updateFunction(true, BuildingStatus.OPERATIONAL, WarehouseStock.CAPACITY, containers);
    }

    private static boolean validateFarmField(ServerLevel level, BuildingRecord building) {
        List<BlockPos> farmland = new ArrayList<>();
        List<BlockPos> water = new ArrayList<>();
        int fenceParts = 0;
        int gates = 0;
        int minX = building.minCorner().getX();
        int maxX = building.maxCorner().getX();
        int minZ = building.minCorner().getZ();
        int maxZ = building.maxCorner().getZ();
        int groundY = building.position().getY() - 1;
        int edgeY = building.position().getY();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos ground = new BlockPos(x, groundY, z);
                BlockPos edge = new BlockPos(x, edgeY, z);
                boolean perimeter = x == minX || x == maxX || z == minZ || z == maxZ;
                if (perimeter) {
                    BlockState edgeState = level.getBlockState(edge);
                    if (edgeState.is(BlockTags.FENCES)) fenceParts++;
                    if (edgeState.is(BlockTags.FENCE_GATES)) {
                        fenceParts++;
                        gates++;
                    }
                } else if (level.getBlockState(ground).is(net.minecraft.world.level.block.Blocks.FARMLAND)) {
                    farmland.add(ground.immutable());
                } else if (level.getFluidState(ground).is(FluidTags.WATER)) {
                    water.add(ground.immutable());
                }
            }
        }
        int expectedPerimeter = Math.max(0, (maxX - minX + maxZ - minZ) * 2);
        boolean hydrated = !water.isEmpty() && farmland.stream().allMatch(soil -> water.stream().anyMatch(source ->
                Math.abs(source.getX() - soil.getX()) <= 4 && Math.abs(source.getZ() - soil.getZ()) <= 4));
        int workerCapacity = Math.max(1, Math.min(16, (farmland.size() + 63) / 64));
        return farmland.size() < 9 || !hydrated || gates == 0 || fenceParts < expectedPerimeter
                ? building.updateFunction(false, BuildingStatus.REGISTERED_ONLY, 0, farmland)
                : building.updateFunction(true, BuildingStatus.OPERATIONAL, workerCapacity, farmland);
    }

    private static boolean validatePasture(ServerLevel level, BuildingRecord building) {
        List<BlockPos> gates = new ArrayList<>();
        int width = building.maxCorner().getX() - building.minCorner().getX() + 1;
        int depth = building.maxCorner().getZ() - building.minCorner().getZ() + 1;
        int edgeY = building.position().getY();
        int perimeterParts = 0;
        for (int x = building.minCorner().getX(); x <= building.maxCorner().getX(); x++) {
            for (int z : new int[]{building.minCorner().getZ(), building.maxCorner().getZ()}) {
                BlockPos cursor = new BlockPos(x, edgeY, z);
                BlockState state = level.getBlockState(cursor);
                if (state.is(BlockTags.FENCES) || state.is(BlockTags.FENCE_GATES)) perimeterParts++;
                if (state.is(BlockTags.FENCE_GATES)) gates.add(cursor.immutable());
            }
        }
        for (int z = building.minCorner().getZ() + 1; z < building.maxCorner().getZ(); z++) {
            for (int x : new int[]{building.minCorner().getX(), building.maxCorner().getX()}) {
                BlockPos cursor = new BlockPos(x, edgeY, z);
                BlockState state = level.getBlockState(cursor);
                if (state.is(BlockTags.FENCES) || state.is(BlockTags.FENCE_GATES)) perimeterParts++;
                if (state.is(BlockTags.FENCE_GATES)) gates.add(cursor.immutable());
            }
        }
        int expectedMinimum = Math.max(8, (width + depth) * 2 - 4);
        int workerCapacity = Math.max(1, Math.min(8, (width * depth + 127) / 128));
        return perimeterParts < expectedMinimum || gates.isEmpty()
                ? building.updateFunction(false, BuildingStatus.REGISTERED_ONLY, 0, gates)
                : building.updateFunction(true, BuildingStatus.OPERATIONAL, workerCapacity, gates);
    }

    private static boolean validateTreeFarm(ServerLevel level, BuildingRecord building) {
        int fences = 0;
        List<BlockPos> servicePositions = new ArrayList<>();
        int y = building.position().getY();
        int minX = building.minCorner().getX();
        int maxX = building.maxCorner().getX();
        int minZ = building.minCorner().getZ();
        int maxZ = building.maxCorner().getZ();
        for (BlockPos corner : List.of(
                new BlockPos(minX, y, minZ), new BlockPos(maxX, y, minZ),
                new BlockPos(minX, y, maxZ), new BlockPos(maxX, y, maxZ))) {
            if (level.getBlockState(corner).is(BlockTags.FENCES)) fences++;
        }
        for (int x = minX + 1; x < maxX; x++) {
            for (int z = minZ + 1; z < maxZ; z++) {
                BlockPos ground = new BlockPos(x, y - 1, z);
                if (level.getBlockState(ground).isFaceSturdy(level, ground, net.minecraft.core.Direction.UP)) {
                    servicePositions.add(ground.above());
                }
            }
        }
        int usableWidth = Math.max(1, maxX - minX - 1);
        int usableDepth = Math.max(1, maxZ - minZ - 1);
        // Tree farming is deliberately land intensive: one lumberjack for every
        // complete 100 horizontal square metres, with one minimum slot for a
        // valid small plot.
        int capacity = Math.max(1, Math.min(8, usableWidth * usableDepth / 100));
        return fences < 4 || servicePositions.isEmpty()
                ? building.updateFunction(false, BuildingStatus.REGISTERED_ONLY, 0, servicePositions)
                : building.updateFunction(true, BuildingStatus.OPERATIONAL, capacity, servicePositions);
    }

    private static boolean validateWarehouse(ServerLevel level, BuildingRecord building) {
        List<BlockPos> containers = new ArrayList<>();
        int slots = 0;
        for (BlockPos cursor : scanBuildingPositions(building, WAREHOUSE_SCAN_RADIUS)) {
            if (!level.hasChunkAt(cursor)) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(cursor);
            if (blockEntity instanceof Container container) {
                containers.add(cursor.immutable());
                slots += container.getContainerSize();
            }
        }
        if(WarehouseStock.applies(building))return building.updateFunction(true,BuildingStatus.OPERATIONAL,WarehouseStock.CAPACITY,containers);
        return containers.isEmpty()
                ? building.updateFunction(false, BuildingStatus.MISSING_CONTAINER, 0, List.of())
                : building.updateFunction(true, BuildingStatus.OPERATIONAL, slots, containers);
    }

    private static boolean validateKiln(ServerLevel level, BuildingRecord building) {
        var stations = BlueprintTemplates.createForBuilding(building).placements().stream()
                .filter(p -> p.mode().isFinalStructure() && p.state().is(net.minecraft.world.level.block.Blocks.FURNACE))
                .filter(p -> level.hasChunkAt(p.position()) && level.getBlockState(p.position()).is(net.minecraft.world.level.block.Blocks.FURNACE))
                .map(p -> p.position()).toList();
        return building.updateFunction(stations.size() == 2,
                stations.size() == 2 ? BuildingStatus.OPERATIONAL : BuildingStatus.MISSING_CONTAINER,
                stations.size() == 2 ? 1 : 0, stations);
    }

    private static boolean validateWorkplace(ServerLevel level, BuildingRecord building, int workerCapacity) {
        List<BlockPos> containers = new ArrayList<>();
        for (BlockPos cursor : scanBuildingPositions(building, WORKPLACE_SCAN_RADIUS)) {
            if (!level.hasChunkAt(cursor)) {
                continue;
            }
            if (level.getBlockEntity(cursor) instanceof Container) {
                containers.add(cursor.immutable());
            }
        }
        return containers.isEmpty()
                ? building.updateFunction(false, BuildingStatus.MISSING_CONTAINER, 0, List.of())
                : building.updateFunction(true, BuildingStatus.OPERATIONAL, workerCapacity, containers);
    }

    private static boolean validateFishingHut(ServerLevel level, BuildingRecord building) {
        boolean waterNearby = false;
        for (BlockPos cursor : BlockPos.betweenClosed(
                building.position().offset(-10, -3, -10), building.position().offset(10, 3, 10))) {
            if (level.hasChunkAt(cursor) && level.getFluidState(cursor).is(FluidTags.WATER)) {
                waterNearby = true;
                break;
            }
        }
        if (!waterNearby) {
            return building.updateFunction(false, BuildingStatus.MISSING_WATER, 0, List.of());
        }
        return validateWorkplace(level, building, 2);
    }

    private static Iterable<BlockPos> scanPositions(BlockPos center, int horizontalRadius) {
        return BlockPos.betweenClosed(
                center.offset(-horizontalRadius, -VERTICAL_SCAN_RADIUS, -horizontalRadius),
                center.offset(horizontalRadius, VERTICAL_SCAN_RADIUS, horizontalRadius)
        );
    }

    private static Iterable<BlockPos> scanBuildingPositions(BuildingRecord building, int legacyRadius) {
        if (building.customTemplate() != null) return building.customTemplate().template().placements().stream()
                .map(com.hkin.exiledsettlement.blueprint.BlueprintPlacement::position).toList();
        if (building.minCorner().equals(building.maxCorner())) return scanPositions(building.position(), legacyRadius);
        return BlockPos.betweenClosed(building.minCorner().offset(0, -1, 0), building.maxCorner().offset(0, 1, 0));
    }
}
