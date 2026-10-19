package com.hkin.exiledsettlement.blueprint;

import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.core.Direction.Axis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Original, compact settlement structures used by both the preview and server build logic. */
public final class BlueprintTemplates {
    // Pure, immutable geometry only: no world, inventory, entity or completion
    // state. Bound both entry count and placement count; preview cursor motion
    // and old worlds must not retain an unbounded number of snapshots.
    private static final Map<TemplateKey, BuildingBlueprintTemplate> CACHE = new LinkedHashMap<>(16, 0.75F, true);
    private static int cachedPlacements;
    private static final Map<BlueprintRecord,BuildingBlueprintTemplate> RECORDS=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private record TemplateKey(BuildingType type, BlockPos first, BlockPos second,
                               Direction facing, int version, boolean area) { }

    private static BuildingBlueprintTemplate cached(TemplateKey key,
            java.util.function.Supplier<BuildingBlueprintTemplate> factory) {
        synchronized (CACHE) {
            BuildingBlueprintTemplate hit = CACHE.get(key);
            if (hit != null) return hit;
        }
        // Do not hold the shared cache lock while compiling a cold template.
        BuildingBlueprintTemplate result = factory.get();
        int weight = result.placements().size();
        if (weight > 131072) return result;
        synchronized (CACHE) {
            BuildingBlueprintTemplate hit = CACHE.get(key);
            if (hit != null) return hit;
            while (!CACHE.isEmpty() && (CACHE.size() >= 128 || cachedPlacements + weight > 131072)) {
                TemplateKey oldest = CACHE.keySet().iterator().next();
                cachedPlacements -= CACHE.remove(oldest).placements().size();
            }
            CACHE.put(key, result);
            cachedPlacements += weight;
        }
        return result;
    }

    private BlueprintTemplates() {
    }

    public static boolean supports(BuildingType type) {
        return type != BuildingType.WAGON;
    }

    public static BuildingBlueprintTemplate create(BuildingType type, BlockPos anchor, Direction facing) {
        return create(type, anchor, facing, currentTemplateVersion(type));
    }

    public static BuildingBlueprintTemplate create(
            BuildingType type, BlockPos anchor, Direction facing, int templateVersion
    ) {
        BlockPos frozen = anchor.immutable();
        return cached(new TemplateKey(type, frozen, frozen, facing, templateVersion, false),
                () -> ExpansionTemplateEnhancements.apply(
                        createUncached(type, frozen, facing, templateVersion), templateVersion));
    }

    private static BuildingBlueprintTemplate createUncached(
            BuildingType type, BlockPos anchor, Direction facing, int templateVersion
    ) {
        if (!supports(type)) {
            throw new IllegalArgumentException("No blueprint template for " + type);
        }
        if (fixedProduction(type, templateVersion)) {
            var plan = createArea(type, anchor.offset(-6, 0, -6), anchor.offset(6, 0, 6), facing, templateVersion);
            return new BuildingBlueprintTemplate(type, anchor, facing, 13, 13, plan.height(),
                    plan.minCorner(), plan.maxCorner(), plan.transitionMargin(), plan.placements(), plan.entrances());
        }
        if (PlayerDesignedTemplates.supports(type, templateVersion)) {
            return PlayerDesignedTemplates.create(type, anchor, facing, templateVersion);
        }
        Builder builder = new Builder(type, anchor, facing.getAxis().isHorizontal() ? facing : Direction.NORTH,
                templateVersion);
        if (templateVersion >= 2) {
            if (type == BuildingType.HOUSE) return houseV2(builder);
            if (type == BuildingType.TOWN_HALL) return townHallV2(builder);
        }
        return switch (type) {
            case HOUSE -> house(builder);
            case HOUSE_2 -> houseTwo(builder);
            case HOUSE_3 -> houseThree(builder);
            case ADVANCED_HOUSE_1 -> advancedHouseOne(builder);
            case ADVANCED_HOUSE_2 -> advancedHouseTwo(builder);
            case ADVANCED_HOUSE_3 -> advancedHouseThree(builder);
            case WAREHOUSE -> warehouse(builder);
            case FARM_FIELD -> farmField(builder);
            case PASTURE_AREA -> pastureArea(builder);
            case HUNTING_LODGE -> huntingLodge(builder);
            case TOOL_WORKSHOP -> toolWorkshop(builder);
            case FORESTER_LODGE -> foresterLodge(builder);
            case TOWN_HALL -> townHall(builder);
            case FISHING_HUT -> fishingHut(builder);
            case SAWMILL -> sawmill(builder);
            case MINE -> MineBlueprint.create(anchor, facing);
            case WAGON, TREE_FARM, MELON_FIELD, SPRUCE_HOME, GALLERY_HOME, COURTYARD_HOME, SCHOOL,
                    STARTER_CAMP, MARKET, TAVERN, RESTAURANT, LEISURE_GARDEN,
                    LAMP_SMALL, LAMP_ARM, LAMP_DOUBLE, BENCH, FLOWERBED, SIGNPOST, WELL, WOODSHED, CHURCH, BRICK_KILN, APARTMENT ->
                    throw new IllegalArgumentException(type + " has no fixed blueprint for version " + templateVersion);
        };
    }

    public static int currentTemplateVersion(BuildingType type) {
        return switch (type) {
            case STARTER_CAMP -> 4;
            case HOUSE -> 4;
            case HOUSE_2 -> 1;
            case WAREHOUSE -> 7;
            case ADVANCED_HOUSE_1, ADVANCED_HOUSE_3, HOUSE_3 -> 5;
            case ADVANCED_HOUSE_2 -> 6;
            case APARTMENT -> 2;
            case TOWN_HALL -> 7;
            case HUNTING_LODGE, FISHING_HUT -> 4;
            case TOOL_WORKSHOP, SAWMILL, SCHOOL -> 5;
            case MARKET, TAVERN -> 3;
            case FARM_FIELD, MELON_FIELD, PASTURE_AREA -> 6;
            case TREE_FARM -> 4;
            case FORESTER_LODGE -> 2;
            default -> 1;
        };
    }

    public static BuildingBlueprintTemplate create(BlueprintRecord blueprint) {
        if(blueprint.terrainProject()!=null)return blueprint.terrainProject().template();
        if(blueprint.productionSite()!=null)return blueprint.productionSite().template();
        if(blueprint.region()!=null)return blueprint.region().template();
        if (blueprint.customTemplate() != null) return ConstructionWorkArea.template(blueprint.customTemplate().template());
        return RECORDS.computeIfAbsent(blueprint,b->ConstructionWorkArea.template(b.dynamicArea()
                ? createArea(blueprint.type(), blueprint.areaMin(), blueprint.areaMax(), blueprint.facing(),
                blueprint.templateVersion())
                : create(blueprint.type(), blueprint.anchor(), blueprint.facing(), blueprint.templateVersion())));
    }

    public static BuildingBlueprintTemplate createForBuilding(BuildingRecord building) {
        if(building.productionSite()!=null)return building.productionSite().template();
        if(building.region()!=null)return building.region().template();
        if (building.customTemplate() != null) return building.customTemplate().template();
        if (flexibleProduction(building.type(), building.templateVersion())) {
            int y = building.position().getY();
            return createArea(building.type(), building.minCorner().atY(y), building.maxCorner().atY(y),
                    building.facing(), building.templateVersion());
        }
        if (fixedProduction(building.type(), building.templateVersion()))
            return create(building.type(), building.position(), building.facing(), building.templateVersion());
        if (building.type().isDynamicArea()) {
            int y = building.position().getY();
            BlockPos first = new BlockPos(building.minCorner().getX(), y, building.minCorner().getZ());
            BlockPos second = new BlockPos(building.maxCorner().getX(), y, building.maxCorner().getZ());
            return createArea(building.type(), first, second, building.facing(), building.templateVersion());
        }
        var base = create(building.type(), building.position(), building.facing(), building.templateVersion());
        return building.type()==BuildingType.MINE ? com.hkin.exiledsettlement.building.MineExpansionService.template(building,base) : base;
    }

    public static BuildingBlueprintTemplate createArea(
            BuildingType type, BlockPos first, BlockPos second, Direction facing
    ) {
        return createArea(type, first, second, facing, currentTemplateVersion(type));
    }

    /** Legacy name for ordinary production eligibility, including fixed and selectable footprints. */
    public static boolean fixedProduction(BuildingType type, int version) {
        return switch (type) {
            case FARM_FIELD, MELON_FIELD, PASTURE_AREA -> version >= 5;
            case TREE_FARM -> version >= 3;
            default -> false;
        };
    }

    /** Saved bounds define these ordinary buildings; older versions keep their original geometry. */
    public static boolean flexibleProduction(BuildingType type, int version) {
        return switch (type) {
            case FARM_FIELD, MELON_FIELD, PASTURE_AREA -> version >= 6;
            case TREE_FARM -> version >= 4;
            default -> false;
        };
    }

    public static BuildingBlueprintTemplate createArea(
            BuildingType type, BlockPos first, BlockPos second, Direction facing, int templateVersion
    ) {
        BlockPos frozenFirst = first.immutable(), frozenSecond = second.immutable();
        return cached(new TemplateKey(type, frozenFirst, frozenSecond, facing, templateVersion, true),
                () -> createAreaUncached(type, frozenFirst, frozenSecond, facing, templateVersion));
    }

    private static BuildingBlueprintTemplate createAreaUncached(
            BuildingType type, BlockPos first, BlockPos second, Direction facing, int templateVersion
    ) {
        if (!type.isDynamicArea()) {
            throw new IllegalArgumentException("Building is not a variable production area: " + type);
        }
        int minX = Math.min(first.getX(), second.getX());
        int maxX = Math.max(first.getX(), second.getX());
        int minZ = Math.min(first.getZ(), second.getZ());
        int maxZ = Math.max(first.getZ(), second.getZ());
        int y = first.getY();
        BlockPos anchor = new BlockPos((minX + maxX) / 2, y, minZ);
        List<BlueprintPlacement> placements = new ArrayList<>();
        List<BlueprintEntrance> entrances = new ArrayList<>();
        if (type == BuildingType.FARM_FIELD || type == BuildingType.MELON_FIELD) {
            Direction horizontal = facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
            Map<BlockPos, Direction> gates = areaGatePositions(
                    minX, maxX, minZ, maxZ, y, horizontal, templateVersion);
            gates.forEach((position, outward) -> entrances.add(new BlueprintEntrance(position, outward)));
            List<Integer> waterX = irrigationCoordinates(minX + 1, maxX - 1);
            List<Integer> waterZ = irrigationCoordinates(minZ + 1, maxZ - 1);
            List<Integer> roadX = templateVersion >= 3 ? gateAxisCells(minX, maxX) : List.of();
            List<Integer> roadZ = templateVersion >= 3 ? gateAxisCells(minZ, maxZ) : List.of();
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (x == minX || x == maxX || z == minZ || z == maxZ) {
                        addPastureEdge(placements, new BlockPos(x, y, z), gates);
                    } else {
                        placements.add(new BlueprintPlacement(new BlockPos(x, y, z),
                                Blocks.AIR.defaultBlockState(), null, BlueprintPlacementMode.CLEARANCE));
                        boolean internalRoad = templateVersion >= 3
                                && (roadX.contains(x) || roadZ.contains(z));
                        boolean irrigation = waterX.contains(x) && waterZ.contains(z);
                        boolean fruitLane = type == BuildingType.MELON_FIELD
                                && !irrigation && (Math.floorMod(x - minX, 3) == 0
                                || Math.floorMod(maxX - x, 3) == 0);
                        BlockState ground = irrigation && templateVersion >= 3
                                ? Blocks.OAK_SLAB.defaultBlockState()
                                .setValue(BlockStateProperties.WATERLOGGED, true)
                                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP)
                                : internalRoad ? Blocks.GRAVEL.defaultBlockState()
                                : irrigation ? Blocks.WATER.defaultBlockState()
                                : fruitLane ? Blocks.DIRT.defaultBlockState()
                                : Blocks.FARMLAND.defaultBlockState();
                        placements.add(new BlueprintPlacement(new BlockPos(x, y - 1, z),
                                ground, irrigation && templateVersion >= 3 ? Blocks.OAK_SLAB.asItem()
                                : internalRoad ? Blocks.GRAVEL.asItem() : null,
                                internalRoad || irrigation && templateVersion >= 3
                                ? BlueprintPlacementMode.STRUCTURE : irrigation
                                ? BlueprintPlacementMode.FARM_WATER : fruitLane
                                ? BlueprintPlacementMode.STRUCTURE : BlueprintPlacementMode.FARM_TILL));
                    }
                }
            }
        } else if (type == BuildingType.PASTURE_AREA) {
            Direction horizontal = facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
            Map<BlockPos, Direction> gates = areaGatePositions(
                    minX, maxX, minZ, maxZ, y, horizontal, templateVersion);
            gates.forEach((position, outward) -> entrances.add(new BlueprintEntrance(position, outward)));
            for (int x = minX; x <= maxX; x++) {
                addPastureEdge(placements, new BlockPos(x, y, minZ), gates);
                if (maxZ != minZ) addPastureEdge(placements, new BlockPos(x, y, maxZ), gates);
            }
            for (int z = minZ + 1; z < maxZ; z++) {
                addPastureEdge(placements, new BlockPos(minX, y, z), gates);
                if (maxX != minX) addPastureEdge(placements, new BlockPos(maxX, y, z), gates);
            }
            if (templateVersion >= 3) {
                List<Integer> roadX = gateAxisCells(minX, maxX);
                List<Integer> roadZ = gateAxisCells(minZ, maxZ);
                boolean decorated = templateVersion == 3 && maxX - minX >= 8 && maxZ - minZ >= 8;
                Set<BlockPos> fixtures = new HashSet<>();
                if (decorated) {
                    for (int x : List.of(minX + 2, maxX - 2)) {
                        for (int z : List.of(minZ + 2, maxZ - 2)) {
                            fixtures.add(new BlockPos(x, y, z));
                        }
                    }
                }
                for (int x = minX + 1; x < maxX; x++) {
                    for (int z = minZ + 1; z < maxZ; z++) {
                        BlockPos feet = new BlockPos(x, y, z);
                        placements.add(new BlueprintPlacement(feet, Blocks.AIR.defaultBlockState(), null,
                                BlueprintPlacementMode.CLEARANCE));
                        int edgeX = Math.min(x - minX, maxX - x);
                        int edgeZ = Math.min(z - minZ, maxZ - z);
                        BlockState ground = roadX.contains(x) || roadZ.contains(z)
                                ? Blocks.GRAVEL.defaultBlockState()
                                : edgeX <= 2 && edgeZ <= 2
                                ? Blocks.COARSE_DIRT.defaultBlockState() : null;
                        if (ground != null) placements.add(new BlueprintPlacement(feet.below(), ground,
                                ground.getBlock().asItem(), BlueprintPlacementMode.STRUCTURE));
                    }
                }
                for (BlockPos fixture : fixtures) {
                    placements.add(new BlueprintPlacement(fixture, Blocks.COMPOSTER.defaultBlockState(),
                            Blocks.COMPOSTER.asItem(), BlueprintPlacementMode.STRUCTURE));
                    BlockPos hay = new BlockPos(
                            minX + maxX - fixture.getX(), y,
                            fixture.getZ() <= (minZ + maxZ) / 2 ? minZ + 3 : maxZ - 3);
                    if (!fixtures.contains(hay)) placements.add(new BlueprintPlacement(hay,
                            Blocks.HAY_BLOCK.defaultBlockState(), Blocks.HAY_BLOCK.asItem(),
                            BlueprintPlacementMode.STRUCTURE));
                }
            }
        } else if (type == BuildingType.TREE_FARM && templateVersion >= 2) {
            Direction horizontal = facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
            Map<BlockPos, Direction> gates = areaGatePositions(minX, maxX, minZ, maxZ, y, horizontal, 2);
            gates.forEach((position, outward) -> entrances.add(new BlueprintEntrance(position, outward)));
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (x == minX || x == maxX || z == minZ || z == maxZ) {
                        addPastureEdge(placements, new BlockPos(x, y, z), gates);
                    }
                }
            }
        } else {
            for (BlockPos corner : List.of(
                    new BlockPos(minX, y, minZ), new BlockPos(maxX, y, minZ),
                    new BlockPos(minX, y, maxZ), new BlockPos(maxX, y, maxZ))) {
                placements.add(new BlueprintPlacement(corner, Blocks.OAK_FENCE.defaultBlockState(),
                        Blocks.OAK_FENCE.asItem(), BlueprintPlacementMode.STRUCTURE));
            }
        }
        placements.sort(Comparator.comparingInt((BlueprintPlacement p) -> areaPlacementOrder(type, templateVersion, p))
                .thenComparingInt(p -> p.position().getY())
                .thenComparingInt(p -> p.position().getX())
                .thenComparingInt(p -> p.position().getZ()));
        return new BuildingBlueprintTemplate(type, anchor, facing,
                maxX - minX + 1, maxZ - minZ + 1, 2,
                new BlockPos(minX, y - 1, minZ), new BlockPos(maxX, y, maxZ), 1,
                placements, entrances);
    }

    private static int areaPlacementOrder(BuildingType type, int templateVersion, BlueprintPlacement p) {
        if (p.mode() == BlueprintPlacementMode.CLEARANCE) return 0;
        if ((type == BuildingType.FARM_FIELD || type == BuildingType.MELON_FIELD) && templateVersion >= 4) {
            Block block = p.state().getBlock();
            if (block instanceof FenceBlock || block instanceof FenceGateBlock) return 1;
            if (block == Blocks.GRAVEL || block == Blocks.DIRT) return 2;
            if (p.state().hasProperty(BlockStateProperties.WATERLOGGED)
                    && p.state().getValue(BlockStateProperties.WATERLOGGED)) return 3;
            if (block == Blocks.FARMLAND || p.mode() == BlueprintPlacementMode.FARM_TILL) return 4;
            return 2;
        }
        return switch (p.mode()) {
                    case CLEARANCE -> 0;
                    case OPTIONAL_SUPPORT -> 1;
                    case FARM_WATER -> 1;
                    case FARM_TILL -> 2;
                    case STRUCTURE -> 3;
                };
    }

    private static Map<BlockPos, Direction> areaGatePositions(
            int minX, int maxX, int minZ, int maxZ, int y, Direction facing, int templateVersion
    ) {
        Map<BlockPos, Direction> gates = new LinkedHashMap<>();
        if (templateVersion < 2) {
            gates.put(gatePosition(minX, maxX, minZ, maxZ, y, facing), facing);
            return gates;
        }
        for (int x : gateAxisCells(minX, maxX)) {
            gates.put(new BlockPos(x, y, minZ), Direction.NORTH);
            gates.put(new BlockPos(x, y, maxZ), Direction.SOUTH);
        }
        for (int z : gateAxisCells(minZ, maxZ)) {
            gates.put(new BlockPos(minX, y, z), Direction.WEST);
            gates.put(new BlockPos(maxX, y, z), Direction.EAST);
        }
        return gates;
    }

    private static List<Integer> gateAxisCells(int min, int max) {
        int length = max - min + 1;
        if (length < 9) return List.of((min + max) / 2);
        List<Integer> cells = new ArrayList<>();
        if (length >= 24) {
            int first = Math.max(min + 1, Math.min(max - 2, min + length / 3));
            cells.add(first);
            cells.add(first + 1);
            cells.add(min + max - first - 1);
            cells.add(min + max - first);
        } else if ((length & 1) == 0) {
            cells.add((min + max) / 2);
            cells.add((min + max) / 2 + 1);
        } else {
            cells.add((min + max) / 2);
        }
        return cells.stream().distinct().sorted().toList();
    }

    private static BlockPos gatePosition(
            int minX, int maxX, int minZ, int maxZ, int y, Direction facing
    ) {
        return switch (facing) {
            case NORTH -> new BlockPos((minX + maxX) / 2, y, minZ);
            case SOUTH -> new BlockPos((minX + maxX) / 2, y, maxZ);
            case WEST -> new BlockPos(minX, y, (minZ + maxZ) / 2);
            case EAST -> new BlockPos(maxX, y, (minZ + maxZ) / 2);
            default -> new BlockPos((minX + maxX) / 2, y, minZ);
        };
    }

    private static List<Integer> irrigationCoordinates(int min, int max) {
        if (max < min) return List.of();
        int length = max - min + 1;
        if (length <= 9) return List.of((min + max) / 2);
        int count = Math.max(1, (length + 8) / 9);
        List<Integer> positions = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            int offset = (int) (((long) (index * 2 + 1) * length) / (count * 2L));
            positions.add(Math.min(max, min + offset));
        }
        return positions.stream().distinct().sorted().toList();
    }

    private static void addPastureEdge(
            List<BlueprintPlacement> placements, BlockPos position, Map<BlockPos, Direction> gates
    ) {
        Direction facing = gates.get(position);
        if (facing != null) {
            placements.add(new BlueprintPlacement(position,
                    Blocks.OAK_FENCE_GATE.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.FenceGateBlock.FACING, facing),
                    Blocks.OAK_FENCE_GATE.asItem(), BlueprintPlacementMode.STRUCTURE));
        } else {
            placements.add(new BlueprintPlacement(position, Blocks.OAK_FENCE.defaultBlockState(),
                    Blocks.OAK_FENCE.asItem(), BlueprintPlacementMode.STRUCTURE));
        }
    }

    private static BuildingBlueprintTemplate houseV2(Builder b) {
        int width = 9;
        foundationAndFloorV2(b, width, 1, 7, 1, 6);
        wallShellV2(b, width, 1, 7, 1, 6, 1, 3);
        for (int[] position : new int[][]{{1, 1}, {7, 1}, {1, 6}, {7, 6}, {4, 1}, {4, 6}}) {
            columnV2(b, width, position[0], position[1], 1, 3, Blocks.STRIPPED_SPRUCE_LOG);
        }
        doorV2(b, width, 3, 1, Direction.NORTH, DoorHingeSide.LEFT);
        windowV2(b, width, 5, 2, 1); windowV2(b, width, 1, 2, 3);
        windowV2(b, width, 7, 2, 3); windowV2(b, width, 6, 2, 6);
        bedV2(b, width, 2, 4); bedV2(b, width, 5, 4);
        putV2(b, width, 2, 1, 2, Blocks.CRAFTING_TABLE.defaultBlockState());
        putV2(b, width, 6, 1, 4, Blocks.FURNACE.defaultBlockState()
                .setValue(FurnaceBlock.FACING, relativeFacing(b, Direction.WEST)));
        gableRoofV2(b, width, 0, 8, 0, 7, 4, 1, 6,
                Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_SLAB);
        for (int y = 3; y < 8; y++) putV2(b, width, 6, y, 5, Blocks.STONE_BRICKS.defaultBlockState());
        putV2(b, width, 3, 0, 0, stairState(b, Blocks.STONE_BRICK_STAIRS, Direction.NORTH));
        return b.finishExact(width, 9, 8, -4, 4, 0, 7);
    }

    private static BuildingBlueprintTemplate townHallV2(Builder b) {
        int width = 15;
        foundationAndFloorV2(b, width, 1, 11, 1, 10);
        for (int x = 12; x < 14; x++) for (int z = 3; z < 11; z++) {
            boolean perimeter = x == 13 || z == 3 || z == 10;
            putV2(b, width, x, 0, z, (perimeter ? Blocks.STONE_BRICKS : Blocks.SPRUCE_PLANKS).defaultBlockState());
        }
        wallShellV2(b, width, 1, 11, 1, 10, 1, 4);
        wallShellV2(b, width, 11, 13, 3, 10, 1, 3);
        for (int[] position : new int[][]{{1, 1}, {11, 1}, {1, 10}, {11, 10},
                {4, 1}, {7, 1}, {4, 10}, {7, 10}, {13, 3}, {13, 10}}) {
            columnV2(b, width, position[0], position[1], 1, position[0] == 13 ? 3 : 4,
                    Blocks.STRIPPED_DARK_OAK_LOG);
        }
        doorV2(b, width, 5, 1, Direction.NORTH, DoorHingeSide.LEFT);
        doorV2(b, width, 6, 1, Direction.NORTH, DoorHingeSide.RIGHT);
        doorV2(b, width, 11, 6, Direction.EAST, DoorHingeSide.LEFT);
        for (int[] position : new int[][]{{2, 2, 1}, {2, 3, 1}, {9, 2, 1}, {9, 3, 1},
                {1, 2, 4}, {1, 2, 7}, {11, 2, 4}, {13, 2, 5}, {13, 2, 8}}) {
            windowV2(b, width, position[0], position[1], position[2]);
        }
        putV2(b, width, 5, 1, 4, ModBlocks.TOWN_HALL_MARKER.get().defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, relativeFacing(b, Direction.NORTH)), null);
        putV2(b, width, 3, 1, 5, Blocks.LECTERN.defaultBlockState()
                .setValue(LecternBlock.FACING, relativeFacing(b, Direction.EAST)));
        putV2(b, width, 8, 1, 5, Blocks.CARTOGRAPHY_TABLE.defaultBlockState());
        putV2(b, width, 12, 1, 4, Blocks.BARREL.defaultBlockState()
                .setValue(BarrelBlock.FACING, relativeFacing(b, Direction.SOUTH)));
        putV2(b, width, 12, 1, 8, Blocks.BARREL.defaultBlockState()
                .setValue(BarrelBlock.FACING, relativeFacing(b, Direction.NORTH)));
        gableRoofV2(b, width, 0, 12, 0, 11, 5, 1, 10,
                Blocks.DEEPSLATE_TILE_STAIRS, Blocks.DEEPSLATE_TILE_SLAB);
        for (int z = 2; z < 12; z++) {
            putV2(b, width, 13, 4, z, stairState(b, Blocks.DEEPSLATE_TILE_STAIRS,
                    correctedTownHallStairs(b) ? Direction.WEST : Direction.EAST));
            putV2(b, width, 14, 4, z, Blocks.DEEPSLATE_TILE_SLAB.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.SlabBlock.TYPE, SlabType.TOP));
        }
        for (int[] position : new int[][]{{5, 5}, {7, 5}, {5, 7}, {7, 7}}) {
            columnV2(b, width, position[0], position[1], 10, 11, Blocks.STRIPPED_DARK_OAK_LOG);
        }
        putV2(b, width, 6, 11, 6, Blocks.BELL.defaultBlockState()
                .setValue(BellBlock.ATTACHMENT, BellAttachType.CEILING)
                .setValue(BellBlock.FACING, relativeFacing(b, Direction.NORTH)));
        for (int x = 5; x < 8; x++) for (int z = 5; z < 8; z++) {
            putV2(b, width, x, 12, z, Blocks.DEEPSLATE_TILE_SLAB.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.SlabBlock.TYPE, SlabType.TOP));
        }
        for (int x : new int[]{3, 8}) columnV2(b, width, x, 0, 1, 4, Blocks.STRIPPED_DARK_OAK_LOG);
        for (int x : new int[]{5, 6}) putV2(b, width, x, 0, 0,
                stairState(b, Blocks.STONE_BRICK_STAIRS,
                        correctedTownHallStairs(b) ? Direction.SOUTH : Direction.NORTH));
        return b.finishExact(width, 13, 12, -7, 7, 0, 11);
    }

    private static void foundationAndFloorV2(Builder b, int width, int minX, int maxX, int minZ, int maxZ) {
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            boolean perimeter = x == minX || x == maxX || z == minZ || z == maxZ;
            putV2(b, width, x, 0, z,
                    (perimeter ? Blocks.STONE_BRICKS : Blocks.SPRUCE_PLANKS).defaultBlockState());
        }
    }

    private static void wallShellV2(Builder b, int width, int minX, int maxX, int minZ, int maxZ,
                                    int minY, int maxY) {
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                putV2(b, width, x, y, minZ, Blocks.MUD_BRICKS.defaultBlockState());
                putV2(b, width, x, y, maxZ, Blocks.MUD_BRICKS.defaultBlockState());
            }
            for (int z = minZ + 1; z < maxZ; z++) {
                putV2(b, width, minX, y, z, Blocks.MUD_BRICKS.defaultBlockState());
                putV2(b, width, maxX, y, z, Blocks.MUD_BRICKS.defaultBlockState());
            }
        }
    }

    private static void columnV2(Builder b, int width, int x, int z, int minY, int maxY, Block block) {
        for (int y = minY; y <= maxY; y++) putV2(b, width, x, y, z,
                block.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Axis.Y));
    }

    private static void doorV2(Builder b, int width, int x, int z,
                               Direction canonicalFacing, DoorHingeSide hinge) {
        Direction facing = relativeFacing(b, canonicalFacing);
        putV2(b, width, x, 1, z, Blocks.SPRUCE_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.HINGE, hinge).setValue(DoorBlock.OPEN, false));
        putV2(b, width, x, 2, z, Blocks.SPRUCE_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)
                .setValue(DoorBlock.HINGE, hinge).setValue(DoorBlock.OPEN, false));
    }

    private static void windowV2(Builder b, int width, int x, int y, int z) {
        putV2(b, width, x, y, z, Blocks.GLASS_PANE.defaultBlockState());
    }

    private static void bedV2(Builder b, int width, int x, int footZ) {
        Direction facing = relativeFacing(b, Direction.SOUTH);
        putV2(b, width, x, 1, footZ, Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, facing).setValue(BedBlock.PART, BedPart.FOOT));
        putV2(b, width, x, 1, footZ + 1, Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, facing).setValue(BedBlock.PART, BedPart.HEAD));
    }

    private static void gableRoofV2(Builder b, int width, int spanMinX, int spanMaxX,
                                    int minZ, int maxZ, int baseY, int gableFrontZ, int gableBackZ,
                                    Block stair, Block slab) {
        int left = spanMinX;
        int right = spanMaxX;
        int y = baseY;
        while (right - left > 1) {
            for (int z = minZ; z <= maxZ; z++) {
                putV2(b, width, left, y, z, stairState(b, stair,
                        correctedTownHallStairs(b) ? Direction.EAST : Direction.WEST));
                putV2(b, width, right, y, z, stairState(b, stair,
                        correctedTownHallStairs(b) ? Direction.WEST : Direction.EAST));
            }
            for (int z : new int[]{gableFrontZ, gableBackZ}) for (int x = left + 1; x < right; x++) {
                putV2(b, width, x, y, z, Blocks.MUD_BRICKS.defaultBlockState());
            }
            left++;
            right--;
            y++;
        }
        int ridgeX = (left + right) / 2;
        for (int z = minZ; z <= maxZ; z++) putV2(b, width, ridgeX, y, z,
                slab.defaultBlockState().setValue(net.minecraft.world.level.block.SlabBlock.TYPE, SlabType.TOP));
    }

    // Keep historic snapshots readable: never silently change an existing building's target states.
    private static boolean correctedTownHallStairs(Builder b) {
        return b.type == BuildingType.TOWN_HALL && b.templateVersion >= 3;
    }

    private static BlockState stairState(Builder b, Block stair, Direction canonicalFacing) {
        return stair.defaultBlockState()
                .setValue(StairBlock.FACING, relativeFacing(b, canonicalFacing))
                .setValue(StairBlock.HALF, Half.BOTTOM)
                .setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);
    }

    private static Axis axisFor(Builder b, Axis canonical) {
        if (canonical == Axis.Y) return Axis.Y;
        return canonical == Axis.X ? b.right.getAxis() : b.facing.getAxis();
    }

    private static Direction relativeFacing(Builder b, Direction canonical) {
        return switch (canonical) {
            case NORTH -> b.facing.getOpposite();
            case SOUTH -> b.facing;
            case EAST -> b.right;
            case WEST -> b.right.getOpposite();
            default -> canonical;
        };
    }

    private static BlockPos putV2(Builder b, int width, int x, int y, int z, BlockState state) {
        return putV2(b, width, x, y, z, state,
                state.getBlock().asItem() == Items.AIR ? null : state.getBlock().asItem());
    }

    private static BlockPos putV2(Builder b, int width, int x, int y, int z, BlockState state, Item material) {
        return b.putWorld(b.worldPosition(x - width / 2, y - 1, z), state, material);
    }

    private static BuildingBlueprintTemplate house(Builder b) {
        foundation(b, -3, 3, 0, 5, Blocks.COBBLESTONE);
        shell(b, -3, 3, 0, 5, 0, 3, Blocks.SPRUCE_PLANKS, Blocks.STRIPPED_OAK_LOG);
        doorway(b, 0, 0);
        window(b, -2, 1, 0); window(b, 2, 1, 0);
        window(b, -3, 1, 3); window(b, 3, 1, 3);
        roof(b, -3, 3, 0, 5, 4);
        bed(b, -2, 0, 4); bed(b, 2, 0, 4);
        b.putBlock(-2, 0, 2, Blocks.CRAFTING_TABLE);
        b.putBlock(2, 0, 2, Blocks.FURNACE);
        return b.finish(7, 6, 8, -3, 3, 0, 5);
    }

    private static BuildingBlueprintTemplate houseTwo(Builder b) {
        return residential(b, -4, 4, 0, 7, 4, Blocks.SPRUCE_PLANKS, Blocks.COBBLESTONE, false);
    }

    private static BuildingBlueprintTemplate houseThree(Builder b) {
        return residential(b, -5, 5, 0, 8, 6, Blocks.DARK_OAK_PLANKS, Blocks.STONE_BRICKS, false);
    }

    private static BuildingBlueprintTemplate advancedHouseOne(Builder b) {
        return residential(b, -4, 4, 0, 6, 3, Blocks.CHERRY_PLANKS, Blocks.BRICKS, true);
    }

    private static BuildingBlueprintTemplate advancedHouseTwo(Builder b) {
        return residential(b, -5, 5, 0, 8, 6, Blocks.CHERRY_PLANKS, Blocks.BRICKS, true);
    }

    private static BuildingBlueprintTemplate advancedHouseThree(Builder b) {
        return residential(b, -6, 6, 0, 10, 9, Blocks.DARK_OAK_PLANKS, Blocks.DEEPSLATE_BRICKS, true);
    }

    private static BuildingBlueprintTemplate residential(
            Builder b, int minX, int maxX, int minZ, int maxZ, int beds,
            Block wall, Block foundation, boolean advanced
    ) {
        foundation(b, minX, maxX, minZ, maxZ, foundation);
        shell(b, minX, maxX, minZ, maxZ, 0, 3, wall,
                advanced ? Blocks.STRIPPED_CHERRY_LOG : Blocks.STRIPPED_SPRUCE_LOG);
        doorway(b, 0, 0);
        for (int x = minX + 2; x <= maxX - 2; x += 3) {
            // v1 mistakenly replaced the upper door with a window. Keep its
            // historic geometry readable; only revised templates reserve doors.
            BlueprintPlacement previous = b.structures.get(b.worldPosition(x, 1, 0));
            if (b.templateVersion >= 2 && previous != null
                    && previous.state().getBlock() instanceof DoorBlock) continue;
            window(b, x, 1, 0);
        }
        window(b, minX, 1, (minZ + maxZ) / 2);
        window(b, maxX, 1, (minZ + maxZ) / 2);
        roof(b, minX, maxX, minZ, maxZ, 4);
        int placed = 0;
        for (int z = maxZ - 1; z >= minZ + 2 && placed < beds; z -= 2) {
            for (int x = minX + 1; x <= maxX - 1 && placed < beds; x += 2) {
                bed(b, x, 0, z);
                placed++;
            }
        }
        b.putBlock(minX + 1, 0, minZ + 2, advanced ? Blocks.BOOKSHELF : Blocks.CRAFTING_TABLE);
        b.putBlock(maxX - 1, 0, minZ + 2, advanced ? Blocks.BLAST_FURNACE : Blocks.FURNACE);
        return b.finish(maxX - minX + 1, maxZ - minZ + 1, 8, minX, maxX, minZ, maxZ);
    }

    private static BuildingBlueprintTemplate warehouse(Builder b) {
        foundation(b, -7, 7, 0, 10, Blocks.STONE_BRICKS);
        shell(b, -7, 7, 0, 10, 0, 4, Blocks.DARK_OAK_PLANKS, Blocks.STRIPPED_SPRUCE_LOG);
        doorway(b, 0, 0);
        doorway(b, -1, 0);
        for (int x : new int[]{-6, -4, -2, 2, 4, 6}) {
            window(b, x, 3, 0);
            for (int z : new int[]{2, 3, 5, 6, 8, 9}) {
                b.putState(x, 0, z, Blocks.BARREL.defaultBlockState()
                        .setValue(BarrelBlock.FACING, b.facing.getOpposite()));
            }
        }
        roof(b, -7, 7, 0, 10, 5);
        return b.finish(15, 11, 10, -7, 7, 0, 10);
    }

    private static BuildingBlueprintTemplate farmField(Builder b) {
        for (int x = -5; x <= 5; x++) {
            for (int z = 0; z <= 10; z++) {
                if (x == 0 && (z == 2 || z == 5 || z == 8)) {
                    b.putBlock(x, -1, z, Blocks.WATER);
                } else {
                    b.putBlock(x, -1, z, Blocks.FARMLAND);
                    b.putState(x, 0, z, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
                }
            }
        }
        return b.finish(11, 11, 2, -5, 5, 0, 10);
    }

    private static BuildingBlueprintTemplate pastureArea(Builder b) {
        foundation(b, -7, 7, 0, 14, Blocks.GRASS_BLOCK);
        for (int x = -7; x <= 7; x++) {
            if (x != 0) b.putBlock(x, 0, 0, Blocks.OAK_FENCE);
            b.putBlock(x, 0, 14, Blocks.OAK_FENCE);
        }
        for (int z = 1; z < 14; z++) {
            b.putBlock(-7, 0, z, Blocks.OAK_FENCE);
            b.putBlock(7, 0, z, Blocks.OAK_FENCE);
        }
        b.putBlock(0, 0, 0, Blocks.OAK_FENCE_GATE);
        return b.finish(15, 15, 2, -7, 7, 0, 14);
    }

    private static BuildingBlueprintTemplate huntingLodge(Builder b) {
        foundation(b, -4, 4, 0, 8, Blocks.MOSSY_COBBLESTONE);
        shell(b, -4, 4, 0, 8, 0, 3, Blocks.SPRUCE_PLANKS, Blocks.STRIPPED_SPRUCE_LOG);
        doorway(b, 0, 0);
        window(b, -2, 1, 0); window(b, 2, 1, 0);
        window(b, -4, 1, 4); window(b, 4, 1, 4);
        roof(b, -4, 4, 0, 8, 4);
        b.putBlock(-3, 0, 6, Blocks.BARREL);
        b.putBlock(3, 0, 6, Blocks.SMOKER);
        b.putBlock(0, 0, 5, Blocks.CAMPFIRE);
        return b.finish(9, 9, 8, -4, 4, 0, 8);
    }

    private static BuildingBlueprintTemplate toolWorkshop(Builder b) {
        foundation(b, -4, 4, 0, 6, Blocks.STONE_BRICKS);
        shell(b, -4, 4, 0, 6, 0, 3, Blocks.DARK_OAK_PLANKS, Blocks.STRIPPED_DARK_OAK_LOG);
        doorway(b, 0, 0);
        window(b, -2, 1, 0); window(b, 2, 1, 0);
        roof(b, -4, 4, 0, 6, 4);
        b.putBlock(-3, 0, 2, Blocks.ANVIL);
        b.putBlock(3, 0, 2, Blocks.BLAST_FURNACE);
        b.putBlock(-3, 0, 5, Blocks.BARREL);
        b.putBlock(3, 0, 5, Blocks.BARREL);
        return b.finish(9, 7, 8, -4, 4, 0, 6);
    }

    private static BuildingBlueprintTemplate sawmill(Builder b) {
        foundation(b, -4, 4, 0, 6, Blocks.COBBLESTONE);
        shell(b, -4, 4, 0, 6, 0, 3, Blocks.SPRUCE_PLANKS, Blocks.STRIPPED_SPRUCE_LOG);
        doorway(b, 0, 0);
        window(b, -2, 1, 0);
        window(b, 2, 1, 0);
        roof(b, -4, 4, 0, 6, 4);
        b.putBlock(-3, 0, 2, Blocks.STONECUTTER);
        b.putBlock(3, 0, 2, Blocks.CRAFTING_TABLE);
        b.putBlock(-3, 0, 5, Blocks.BARREL);
        b.putBlock(3, 0, 5, Blocks.BARREL);
        return b.finish(9, 7, 8, -4, 4, 0, 6);
    }

    private static BuildingBlueprintTemplate foresterLodge(Builder b) {
        foundation(b, -4, 4, 0, 7, Blocks.MOSS_BLOCK);
        shell(b, -4, 4, 0, 7, 0, 3, Blocks.SPRUCE_PLANKS, Blocks.STRIPPED_SPRUCE_LOG);
        doorway(b, 0, 0);
        window(b, -2, 1, 0); window(b, 2, 1, 0);
        roof(b, -4, 4, 0, 7, 4);
        b.putBlock(-3, 0, 5, Blocks.BARREL);
        b.putBlock(3, 0, 5, Blocks.OAK_SAPLING);
        b.putBlock(-3, 0, 2, Blocks.CRAFTING_TABLE);
        return b.finish(9, 8, 8, -4, 4, 0, 7);
    }

    private static BuildingBlueprintTemplate townHall(Builder b) {
        foundation(b, -6, 6, 0, 9, Blocks.STONE_BRICKS);
        shell(b, -6, 6, 0, 9, 0, 4, Blocks.DARK_OAK_PLANKS, Blocks.STRIPPED_DARK_OAK_LOG);
        doorway(b, -1, 0);
        BlockPos desk = b.worldPosition(0, 0, 0);
        b.putWorld(desk, ModBlocks.TOWN_HALL_MARKER.get().defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, b.facing.getOpposite()), null);
        for (int x : new int[]{-4, -2, 2, 4}) window(b, x, 2, 0);
        window(b, -6, 2, 4); window(b, 6, 2, 4);
        roof(b, -6, 6, 0, 9, 5);
        b.putBlock(-4, 0, 7, Blocks.BARREL);
        b.putBlock(4, 0, 7, Blocks.BARREL);
        b.putBlock(0, 0, 6, Blocks.LECTERN);
        b.putBlock(-2, 0, 7, Blocks.BOOKSHELF);
        b.putBlock(2, 0, 7, Blocks.BOOKSHELF);
        b.putBlock(-4, 0, 3, Blocks.CARTOGRAPHY_TABLE);
        return b.finish(13, 10, 9, -6, 6, 0, 9);
    }

    private static BuildingBlueprintTemplate fishingHut(Builder b) {
        foundation(b, -4, 4, 0, 5, Blocks.OAK_PLANKS);
        shell(b, -4, 4, 0, 5, 0, 3, Blocks.OAK_PLANKS, Blocks.STRIPPED_OAK_LOG);
        doorway(b, 0, 0);
        window(b, -2, 1, 0); window(b, 2, 1, 0);
        roof(b, -4, 4, 0, 5, 4);
        for (int z = 6; z <= 11; z++) {
            b.putBlock(-1, -1, z, Blocks.OAK_FENCE);
            b.putBlock(1, -1, z, Blocks.OAK_FENCE);
            b.putBlock(-1, 0, z, Blocks.OAK_SLAB);
            b.putBlock(0, 0, z, Blocks.OAK_SLAB);
            b.putBlock(1, 0, z, Blocks.OAK_SLAB);
        }
        b.putBlock(-3, 0, 3, Blocks.BARREL);
        b.putBlock(3, 0, 3, Blocks.SMOKER);
        return b.finish(9, 12, 8, -4, 4, 0, 11);
    }

    private static void foundation(Builder b, int minX, int maxX, int minZ, int maxZ, Block block) {
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) b.putBlock(x, -1, z, block);
    }

    private static void shell(Builder b, int minX, int maxX, int minZ, int maxZ, int minY, int maxY, Block wall, Block beam) {
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                b.putBlock(x, y, minZ, (x == minX || x == maxX) ? beam : wall);
                b.putBlock(x, y, maxZ, (x == minX || x == maxX) ? beam : wall);
            }
            for (int z = minZ + 1; z < maxZ; z++) {
                b.putBlock(minX, y, z, (z == minZ + 1 || z == maxZ - 1) ? beam : wall);
                b.putBlock(maxX, y, z, (z == minZ + 1 || z == maxZ - 1) ? beam : wall);
            }
        }
    }

    private static void doorway(Builder b, int x, int z) {
        Direction doorFacing = b.facing.getOpposite();
        b.putState(x, 0, z, Blocks.SPRUCE_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, doorFacing).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        b.putState(x, 1, z, Blocks.SPRUCE_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, doorFacing).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static void window(Builder b, int x, int y, int z) { b.putBlock(x, y, z, Blocks.GLASS_PANE); }

    private static void bed(Builder b, int x, int y, int z) {
        Direction bedFacing = b.facing.getOpposite();
        b.putState(x, y, z, Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, bedFacing).setValue(BedBlock.PART, BedPart.FOOT));
        BlockPos head = b.worldPosition(x, y, z).relative(bedFacing);
        b.putWorld(head, Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, bedFacing).setValue(BedBlock.PART, BedPart.HEAD), Blocks.RED_BED.asItem());
    }

    private static void roof(Builder b, int minX, int maxX, int minZ, int maxZ, int y) {
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) b.putBlock(x, y, z, Blocks.SPRUCE_SLAB);
        }
        for (int x : new int[]{minX, maxX}) {
            for (int z = minZ; z <= maxZ; z++) b.putBlock(x, y + 1, z, Blocks.SPRUCE_PLANKS);
        }
    }

    private static final class Builder {
        private final BuildingType type;
        private final BlockPos anchor;
        private final Direction facing;
        private final Direction right;
        private final int templateVersion;
        private final Map<BlockPos, BlueprintPlacement> structures = new LinkedHashMap<>();

        private Builder(BuildingType type, BlockPos anchor, Direction facing, int templateVersion) {
            this.type = type; this.anchor = anchor.immutable(); this.facing = facing; this.right = facing.getClockWise();
            this.templateVersion = templateVersion;
        }

        private BlockPos putBlock(int x, int y, int z, Block block) { return putState(x, y, z, block.defaultBlockState()); }

        private BlockPos putState(int x, int y, int z, BlockState state) {
            Item material = state.getBlock().asItem();
            return putWorld(worldPosition(x, y, z), state, material == Items.AIR ? null : material);
        }

        private BlockPos putWorld(BlockPos position, BlockState state, Item material) {
            structures.put(position, new BlueprintPlacement(position, state, material, BlueprintPlacementMode.STRUCTURE));
            return position;
        }

        private BlockPos worldPosition(int x, int y, int z) {
            return anchor.offset(right.getStepX() * x + facing.getStepX() * z, y,
                    right.getStepZ() * x + facing.getStepZ() * z);
        }

        private BuildingBlueprintTemplate finishExact(
                int width, int height, int depth,
                int minX, int maxX, int minZ, int maxZ
        ) {
            List<BlueprintPlacement> ordered = new ArrayList<>();
            for (int x : new int[]{minX, maxX}) for (int z : new int[]{minZ, maxZ}) {
                for (int y = -4; y <= -2; y++) {
                    BlockPos pos = worldPosition(x, y, z);
                    if (!structures.containsKey(pos)) ordered.add(new BlueprintPlacement(pos,
                            Blocks.COBBLESTONE.defaultBlockState(), Blocks.COBBLESTONE.asItem(),
                            BlueprintPlacementMode.OPTIONAL_SUPPORT));
                }
            }
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                for (int y = 0; y <= height - 2; y++) {
                    BlockPos pos = worldPosition(x, y, z);
                    if (!structures.containsKey(pos)) ordered.add(new BlueprintPlacement(pos,
                            Blocks.AIR.defaultBlockState(), null, BlueprintPlacementMode.CLEARANCE));
                }
            }
            ordered.addAll(structures.values());
            ordered.sort(Comparator.comparingInt((BlueprintPlacement p) -> switch (p.mode()) {
                        case CLEARANCE -> 0;
                        case OPTIONAL_SUPPORT -> 1;
                        case FARM_WATER -> 1;
                        case FARM_TILL -> 2;
                        case STRUCTURE -> p.state().getBlock() instanceof DoorBlock
                                || p.state().getBlock() instanceof BellBlock ? 3 : 2;
                    })
                    .thenComparingInt(p -> p.position().getY()));
            BlockPos first = worldPosition(minX, -1, minZ);
            BlockPos second = worldPosition(maxX, height - 2, maxZ);
            BlockPos minCorner = new BlockPos(Math.min(first.getX(), second.getX()), anchor.getY() - 1,
                    Math.min(first.getZ(), second.getZ()));
            BlockPos maxCorner = new BlockPos(Math.max(first.getX(), second.getX()), anchor.getY() + height - 2,
                    Math.max(first.getZ(), second.getZ()));
            return new BuildingBlueprintTemplate(type, anchor, facing, width, depth, height,
                    minCorner, maxCorner, 2, ordered);
        }

        private BuildingBlueprintTemplate finish(int width, int depth, int height,
                                                  int minX, int maxX, int minZ, int maxZ) {
            List<BlueprintPlacement> ordered = new ArrayList<>();
            for (int x : new int[]{minX, maxX}) for (int z : new int[]{minZ, maxZ}) {
                for (int y = -4; y <= -2; y++) {
                    BlockPos pos = worldPosition(x, y, z);
                    if (!structures.containsKey(pos)) ordered.add(new BlueprintPlacement(pos,
                            Blocks.COBBLESTONE.defaultBlockState(), Blocks.COBBLESTONE.asItem(), BlueprintPlacementMode.OPTIONAL_SUPPORT));
                }
            }
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                for (int y = 0; y < height; y++) {
                    BlockPos pos = worldPosition(x, y, z);
                    if (!structures.containsKey(pos)) ordered.add(new BlueprintPlacement(pos,
                            Blocks.AIR.defaultBlockState(), null, BlueprintPlacementMode.CLEARANCE));
                }
            }
            ordered.addAll(structures.values());
            ordered.sort(Comparator.comparingInt((BlueprintPlacement p) -> switch (p.mode()) {
                        case CLEARANCE -> 0;
                        case OPTIONAL_SUPPORT -> 1;
                        case FARM_WATER -> 1;
                        case FARM_TILL -> 2;
                        case STRUCTURE -> 3;
                    })
                    .thenComparingInt(p -> p.position().getY()));
            BlockPos first = worldPosition(minX, -1, minZ);
            BlockPos second = worldPosition(maxX, height - 1, maxZ);
            BlockPos minCorner = new BlockPos(Math.min(first.getX(), second.getX()), anchor.getY() - 1,
                    Math.min(first.getZ(), second.getZ()));
            BlockPos maxCorner = new BlockPos(Math.max(first.getX(), second.getX()), anchor.getY() + height - 1,
                    Math.max(first.getZ(), second.getZ()));
            return new BuildingBlueprintTemplate(type, anchor, facing, width, depth, height,
                    minCorner, maxCorner, 2, ordered);
        }
    }
}
