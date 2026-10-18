package com.hkin.exiledsettlement.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hkin.exiledsettlement.building.BuildingType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.Rotation;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable runtime snapshots of structures hand-built by the player. */
public final class PlayerDesignedTemplates {
    public static final int TEMPLATE_VERSION = 4;
    public static final int FIRST_PLAYER_DESIGNED_TEMPLATE_VERSION = 3;
    private static final Snapshot HOUSE_V3 = load("house_t1_player_v1");
    private static final Snapshot HOUSE_V4 = load("house_t1_player_v2");
    private static final Snapshot WAREHOUSE_V3 = load("warehouse_player_v1");
    private static final Snapshot HOUSE_T2_V1 = load("house_t2_player_v1");
    private static final Snapshot WAREHOUSE_V4 = load("warehouse_player_v2");
    private static final Snapshot WAREHOUSE_V5 = load("warehouse_player_v3");
    private static final Snapshot SCHOOL_V2 = load("school_approved_v2");
    private static final java.util.Map<BuildingType, Snapshot> EXPANSION = java.util.Map.ofEntries(
            java.util.Map.entry(BuildingType.HOUSE_3, load("house_3_redesign_v1")),
            java.util.Map.entry(BuildingType.STARTER_CAMP, load("starter_camp_v1")),
            java.util.Map.entry(BuildingType.MARKET, load("market_v1")),
            java.util.Map.entry(BuildingType.TAVERN, load("tavern_v1")),
            java.util.Map.entry(BuildingType.RESTAURANT, load("restaurant_v1")),
            java.util.Map.entry(BuildingType.LEISURE_GARDEN, load("leisure_garden_v1")));
    private static final java.util.Map<BuildingType, Snapshot> DECORATIONS = java.util.Map.ofEntries(
            java.util.Map.entry(BuildingType.LAMP_SMALL, load("lamp_small_v1")),
            java.util.Map.entry(BuildingType.LAMP_ARM, load("lamp_arm_v1")),
            java.util.Map.entry(BuildingType.LAMP_DOUBLE, load("lamp_double_v1")),
            java.util.Map.entry(BuildingType.BENCH, load("bench_v1")),
            java.util.Map.entry(BuildingType.FLOWERBED, load("flowerbed_v1")),
            java.util.Map.entry(BuildingType.SIGNPOST, load("signpost_v1")),
            java.util.Map.entry(BuildingType.WELL, load("well_v1")),
            java.util.Map.entry(BuildingType.WOODSHED, load("woodshed_v1")));
    private static final java.util.Map<String, Snapshot> DECOR_COMPONENTS = java.util.Map.of(
            "woodshed:2", load("decor_woodpile_v1"), "bench:2", load("decor_workbench_v1"),
            "signpost:2", load("decor_toolrack_v1"), "signpost:3", load("decor_laundry_v1"),
            "flowerbed:2", load("decor_natural_patch_v1"),
            "woodshed:3", load("decor_composter_v1"), "woodshed:4", load("decor_hay_v1"));
    private static final java.util.Map<BuildingType, Snapshot> APPROVED_ADDITIONS = java.util.Map.of(
            BuildingType.SCHOOL, load("school_approved_v1"),
            BuildingType.SPRUCE_HOME, load("spruce_home_approved_v1"),
            BuildingType.GALLERY_HOME, load("gallery_home_approved_v1"),
            BuildingType.COURTYARD_HOME, load("courtyard_home_approved_v1"),
            BuildingType.FORESTER_LODGE, load("forester_lodge_approved_v1"),
            BuildingType.FISHING_HUT, load("fishing_hut_approved_v1"));

    private static boolean usesApprovedAddition(BuildingType type, int version) {
        return APPROVED_ADDITIONS.containsKey(type)
                && version >= ((type == BuildingType.FORESTER_LODGE || type == BuildingType.FISHING_HUT) ? 2 : 1);
    }
    private static final java.util.Map<BuildingType, Snapshot> REDESIGNS = java.util.Map.of(
            BuildingType.ADVANCED_HOUSE_1, load("advanced_house_1_redesign_v1"),
            BuildingType.ADVANCED_HOUSE_2, load("advanced_house_2_redesign_v1"),
            BuildingType.ADVANCED_HOUSE_3, load("advanced_house_3_redesign_v1"),
            BuildingType.HUNTING_LODGE, load("hunting_lodge_redesign_v1"),
            BuildingType.TOOL_WORKSHOP, load("tool_workshop_redesign_v1"),
            BuildingType.SAWMILL, load("sawmill_redesign_v1"),
            BuildingType.TOWN_HALL, load("town_hall_redesign_v1"));
    private static final java.util.Map<BuildingType, Snapshot> CIVIC_PRODUCTION_ROUND2 = java.util.Map.of(
            BuildingType.TOWN_HALL, load("town_hall_round2_v1"),
            BuildingType.SAWMILL, load("sawmill_round2_v1"),
            BuildingType.SCHOOL, load("school_round2_v1"),
            BuildingType.TOOL_WORKSHOP, load("tool_workshop_round2_v1"));
    private static final java.util.Map<BuildingType, Snapshot> NEW_ADVANCED_HOMES = java.util.Map.of(
            BuildingType.ADVANCED_HOUSE_1, load("advanced_house_1_redesign_v2"),
            BuildingType.ADVANCED_HOUSE_2, load("advanced_house_2_redesign_v2"),
            BuildingType.ADVANCED_HOUSE_3, load("advanced_house_3_redesign_v2"));

    private static final java.util.Map<BuildingType, Snapshot> HOMES_CAMP_20261017 = java.util.Map.of(
            BuildingType.STARTER_CAMP, load("starter_camp_approved_20261017"),
            BuildingType.HOUSE_3, load("house_3_approved_20261017"),
            BuildingType.ADVANCED_HOUSE_1, load("advanced_house_1_approved_20261017"),
            BuildingType.ADVANCED_HOUSE_2, load("advanced_house_2_approved_20261017"),
            BuildingType.ADVANCED_HOUSE_3, load("advanced_house_3_approved_20261017"));

    private static final java.util.Map<BuildingType, Snapshot> PLAYER_HOMES_20261018 = java.util.Map.of(
            BuildingType.STARTER_CAMP, load("starter_camp_player_20261018"),
            BuildingType.HOUSE_3, load("house_3_player_20261018"),
            BuildingType.ADVANCED_HOUSE_1, load("advanced_house_1_player_20261018"),
            BuildingType.ADVANCED_HOUSE_2, load("advanced_house_2_player_20261018"),
            BuildingType.ADVANCED_HOUSE_3, load("advanced_house_3_player_20261018"),
            BuildingType.APARTMENT, load("apartment_player_20261018"));

    private static final Snapshot APARTMENT = load("apartment_v1");
    private static final Snapshot BRICK_KILN = load("brick_kiln_v1");
    private static final Snapshot CHURCH = load("church_facade_v1");
    private static final Snapshot FOUNDING_CAMP = load("starter_camp_founding_v2");
    private static final java.util.Map<BuildingType, Snapshot> DISTINCT_EIGHT = java.util.Map.of(
            BuildingType.HUNTING_LODGE, load("hunting_lodge_distinct_v1"),
            BuildingType.TOOL_WORKSHOP, load("tool_workshop_distinct_v1"),
            BuildingType.TOWN_HALL, load("town_hall_distinct_v1"),
            BuildingType.FISHING_HUT, load("fishing_hut_distinct_v1"),
            BuildingType.SAWMILL, load("sawmill_distinct_v1"),
            BuildingType.SCHOOL, load("school_distinct_v1"),
            BuildingType.MARKET, load("market_distinct_v1"),
            BuildingType.TAVERN, load("tavern_distinct_v1"));

    private static final java.util.Map<BuildingType, Snapshot> PLAYER_EDITED_20261015 = java.util.Map.of(
            BuildingType.HUNTING_LODGE, load("hunting_lodge_player_20261015"),
            BuildingType.TOOL_WORKSHOP, load("tool_workshop_player_20261015"),
            BuildingType.TOWN_HALL, load("town_hall_player_20261015"),
            BuildingType.FISHING_HUT, load("fishing_hut_player_20261015"),
            BuildingType.SAWMILL, load("sawmill_player_20261015"),
            BuildingType.SCHOOL, load("school_player_20261015"),
            BuildingType.MARKET, load("market_player_20261015"),
            BuildingType.TAVERN, load("tavern_player_20261015"));

    public static int playerHomesVersion(BuildingType type) {
        if (type == BuildingType.STARTER_CAMP) return 4;
        if (type == BuildingType.HOUSE_3 || type == BuildingType.ADVANCED_HOUSE_1
                || type == BuildingType.ADVANCED_HOUSE_3) return 5;
        if (type == BuildingType.ADVANCED_HOUSE_2) return 6;
        if (type == BuildingType.APARTMENT) return 2;
        return Integer.MAX_VALUE;
    }

    public static int homesCampVersion(BuildingType type) {
        return switch (type) {
            case STARTER_CAMP -> 3;
            case HOUSE_3, ADVANCED_HOUSE_1, ADVANCED_HOUSE_3 -> 4;
            case ADVANCED_HOUSE_2 -> 5;
            default -> Integer.MAX_VALUE;
        };
    }

    public static int playerEditedVersion(BuildingType type) {
        return DISTINCT_EIGHT.containsKey(type) ? distinctVersion(type) + 1 : Integer.MAX_VALUE;
    }

    public static int distinctVersion(BuildingType type) {
        return switch (type) {
            case TOWN_HALL -> 6;
            case TOOL_WORKSHOP, SAWMILL, SCHOOL -> 4;
            case HUNTING_LODGE, FISHING_HUT -> 3;
            case MARKET, TAVERN -> 2;
            default -> Integer.MAX_VALUE;
        };
    }

    public static int redesignVersion(BuildingType type) {
        return switch (type) {
            case TOWN_HALL -> 4;
            case ADVANCED_HOUSE_2 -> 3;
            case ADVANCED_HOUSE_1, ADVANCED_HOUSE_3, HUNTING_LODGE, TOOL_WORKSHOP, SAWMILL -> 2;
            default -> Integer.MAX_VALUE;
        };
    }

    public static int civicProductionRound2Version(BuildingType type) {
        return switch (type) {
            case TOWN_HALL -> 5;
            case SAWMILL, SCHOOL, TOOL_WORKSHOP -> 3;
            default -> Integer.MAX_VALUE;
        };
    }

    private PlayerDesignedTemplates() {
    }

    public static boolean supports(BuildingType type) {
        return type == BuildingType.HOUSE || type == BuildingType.HOUSE_2 || type == BuildingType.WAREHOUSE;
    }

    public static boolean supports(BuildingType type, int templateVersion) {
        if (type == BuildingType.APARTMENT && templateVersion >= 1) return true;
        if (type == BuildingType.BRICK_KILN && templateVersion >= 1) return true;
        if (type == BuildingType.CHURCH && templateVersion >= 1) return true;
        if (DISTINCT_EIGHT.containsKey(type) && templateVersion >= distinctVersion(type)) return true;
        if (templateVersion >= civicProductionRound2Version(type)) return true;
        if (DECORATIONS.containsKey(type) && templateVersion >= 1) return true;
        if (EXPANSION.containsKey(type) && templateVersion >= (type == BuildingType.HOUSE_3 ? 3 : 1)) return true;
        if (usesApprovedAddition(type, templateVersion)) return true;
        if (templateVersion >= redesignVersion(type)) return true;
        return switch (type) {
            case HOUSE -> templateVersion >= FIRST_PLAYER_DESIGNED_TEMPLATE_VERSION;
            case HOUSE_2 -> templateVersion >= 1;
            case WAREHOUSE -> templateVersion >= FIRST_PLAYER_DESIGNED_TEMPLATE_VERSION;
            default -> false;
        };
    }

    public static BuildingBlueprintTemplate create(BuildingType type, BlockPos anchor, Direction facing) {
        return create(type, anchor, facing, TEMPLATE_VERSION);
    }

    public static BuildingBlueprintTemplate create(
            BuildingType type, BlockPos anchor, Direction facing, int templateVersion
    ) {
        Direction horizontal = facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
        if (PLAYER_HOMES_20261018.containsKey(type) && templateVersion >= playerHomesVersion(type))
            return PLAYER_HOMES_20261018.get(type).instantiate(anchor, horizontal);
        if (HOMES_CAMP_20261017.containsKey(type) && templateVersion >= homesCampVersion(type))
            return HOMES_CAMP_20261017.get(type).instantiate(anchor, horizontal);
        if (type == BuildingType.STARTER_CAMP && templateVersion >= 2) return FOUNDING_CAMP.instantiate(anchor, horizontal);
        if (type == BuildingType.APARTMENT && templateVersion >= 1) return APARTMENT.instantiate(anchor, horizontal);
        if (type == BuildingType.BRICK_KILN && templateVersion >= 1) return BRICK_KILN.instantiate(anchor, horizontal);
        if (type == BuildingType.CHURCH && templateVersion >= 1) return CHURCH.instantiate(anchor, horizontal);
        if (PLAYER_EDITED_20261015.containsKey(type) && templateVersion >= playerEditedVersion(type)) {
            return PLAYER_EDITED_20261015.get(type).instantiate(anchor, horizontal);
        }
        if (DISTINCT_EIGHT.containsKey(type) && templateVersion >= distinctVersion(type)) {
            return DISTINCT_EIGHT.get(type).instantiate(anchor, horizontal);
        }
        if (templateVersion >= civicProductionRound2Version(type)) {
            return CIVIC_PRODUCTION_ROUND2.get(type).instantiate(anchor, horizontal);
        }
        Snapshot component = DECOR_COMPONENTS.get(type.serializedName() + ":" + templateVersion);
        if (component != null) return component.instantiate(anchor, horizontal);
        if (DECORATIONS.containsKey(type) && templateVersion >= 1) {
            return DECORATIONS.get(type).instantiate(anchor, horizontal);
        }
        if (type == BuildingType.SCHOOL && templateVersion >= 2) return SCHOOL_V2.instantiate(anchor, horizontal);
        int newAdvancedVersion = type == BuildingType.ADVANCED_HOUSE_2 ? 4 : 3;
        if (NEW_ADVANCED_HOMES.containsKey(type) && templateVersion >= newAdvancedVersion) {
            return NEW_ADVANCED_HOMES.get(type).instantiate(anchor, horizontal);
        }
        if (EXPANSION.containsKey(type) && templateVersion >= (type == BuildingType.HOUSE_3 ? 3 : 1)) {
            return EXPANSION.get(type).instantiate(anchor, horizontal);
        }
        if (usesApprovedAddition(type, templateVersion)) return APPROVED_ADDITIONS.get(type).instantiate(anchor, horizontal);
        if (templateVersion >= redesignVersion(type)) return REDESIGNS.get(type).instantiate(anchor, horizontal);
        return switch (type) {
            case HOUSE -> (templateVersion >= TEMPLATE_VERSION ? HOUSE_V4 : HOUSE_V3)
                    .instantiate(anchor, horizontal);
            case HOUSE_2 -> HOUSE_T2_V1.instantiate(anchor, horizontal);
            case WAREHOUSE -> (templateVersion >= 5 ? WAREHOUSE_V5
                    : templateVersion >= 4 ? WAREHOUSE_V4 : WAREHOUSE_V3)
                    .instantiate(anchor, horizontal, templateVersion);
            default -> throw new IllegalArgumentException("No player-designed template for " + type);
        };
    }

    private static Snapshot load(String id) {
        String path = "/data/exiledsettlement/building_templates/" + id + ".json";
        try (InputStream stream = PlayerDesignedTemplates.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing player-designed template " + path);
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("schema_version").getAsInt() != 1
                    || !"approved_runtime_snapshot".equals(root.get("status").getAsString())) {
                throw new IllegalStateException("Unsupported player-designed template metadata in " + path);
            }
            BuildingType type = BuildingType.fromSerializedName(root.get("building_type").getAsString());
            Direction sourceFacing = requiredHorizontal(root.get("source_interior_facing").getAsString());
            BlockPos sourceAnchor = position(root.getAsJsonArray("anchor"));
            JsonArray size = root.getAsJsonArray("size");
            int sizeX = size.get(0).getAsInt();
            int sizeY = size.get(1).getAsInt();
            int sizeZ = size.get(2).getAsInt();
            int transitionMargin = root.get("transition_margin").getAsInt();
            List<SourceBlock> blocks = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("blocks")) {
                JsonObject entry = element.getAsJsonObject();
                ResourceLocation blockId = ResourceLocation.parse(entry.get("block").getAsString());
                Block block = BuiltInRegistries.BLOCK.get(blockId);
                if (block == net.minecraft.world.level.block.Blocks.AIR
                        && !blockId.equals(ResourceLocation.withDefaultNamespace("air"))) {
                    throw new IllegalStateException("Unknown block " + blockId + " in " + path);
                }
                BlockState state = block.defaultBlockState();
                if (entry.has("state")) {
                    for (var propertyEntry : entry.getAsJsonObject("state").entrySet()) {
                        state = setProperty(state, propertyEntry.getKey(), propertyEntry.getValue().getAsString(), path);
                    }
                }
                Item materialOverride = null;
                if (entry.has("material")) {
                    ResourceLocation materialId = ResourceLocation.parse(entry.get("material").getAsString());
                    materialOverride = BuiltInRegistries.ITEM.get(materialId);
                    if (materialOverride == Items.AIR) throw new IllegalStateException("Unknown material " + materialId + " in " + path);
                }
                blocks.add(new SourceBlock(position(entry.getAsJsonArray("pos")), state, materialOverride));
            }
            List<SourceEntrance> entrances = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("entrances")) {
                JsonObject entry = element.getAsJsonObject();
                entrances.add(new SourceEntrance(position(entry.getAsJsonArray("pos")),
                        requiredHorizontal(entry.get("outward").getAsString())));
            }
            List<BlockPos> supportCorners = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("support_corners")) {
                supportCorners.add(position(element.getAsJsonArray()));
            }
            return new Snapshot(type, sourceFacing, sourceAnchor, sizeX, sizeY, sizeZ,
                    transitionMargin, List.copyOf(blocks), List.copyOf(entrances), List.copyOf(supportCorners));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to close player-designed template " + path, exception);
        }
    }

    private static BlockPos position(JsonArray array) {
        if (array.size() != 3) throw new IllegalStateException("Template position must have three coordinates");
        return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
    }

    private static Direction requiredHorizontal(String name) {
        Direction direction = Direction.byName(name);
        if (direction == null || !direction.getAxis().isHorizontal()) {
            throw new IllegalStateException("Invalid horizontal direction " + name);
        }
        return direction;
    }

    private static BlockState setProperty(BlockState state, String name, String value, String path) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) return setPropertyValue(state, property, value, path);
        }
        throw new IllegalStateException("Unknown property " + name + " for "
                + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " in " + path);
    }

    private static <T extends Comparable<T>> BlockState setPropertyValue(
            BlockState state, Property<T> property, String value, String path
    ) {
        T parsed = property.getValue(value).orElseThrow(() -> new IllegalStateException(
                "Invalid value " + value + " for property " + property.getName() + " in " + path));
        return state.setValue(property, parsed);
    }

    private static Rotation rotation(Direction source, Direction target) {
        int turns = Math.floorMod(target.get2DDataValue() - source.get2DDataValue(), 4);
        return switch (turns) {
            case 0 -> Rotation.NONE;
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> throw new IllegalStateException();
        };
    }

    private static BlockPos rotateOffset(BlockPos offset, Rotation rotation) {
        return switch (rotation) {
            case NONE -> offset;
            case CLOCKWISE_90 -> new BlockPos(-offset.getZ(), offset.getY(), offset.getX());
            case CLOCKWISE_180 -> new BlockPos(-offset.getX(), offset.getY(), -offset.getZ());
            case COUNTERCLOCKWISE_90 -> new BlockPos(offset.getZ(), offset.getY(), -offset.getX());
        };
    }

    private record SourceBlock(BlockPos position, BlockState state, Item materialOverride) {
    }

    private record SourceEntrance(BlockPos position, Direction outward) {
    }

    private record Snapshot(
            BuildingType type,
            Direction sourceFacing,
            BlockPos sourceAnchor,
            int sizeX,
            int sizeY,
            int sizeZ,
            int transitionMargin,
            List<SourceBlock> blocks,
            List<SourceEntrance> entrances,
            List<BlockPos> supportCorners
    ) {
        private BuildingBlueprintTemplate instantiate(BlockPos anchor, Direction facing) {
            return instantiate(anchor, facing, 0);
        }

        private BuildingBlueprintTemplate instantiate(BlockPos anchor, Direction facing, int chestRevision) {
            Rotation rotation = rotation(sourceFacing, facing);
            List<BlueprintPlacement> placements = new ArrayList<>();
            Set<BlockPos> structurePositions = new HashSet<>(blocks.size());
            for (SourceBlock source : blocks) {
                BlockPos offset = source.position().subtract(sourceAnchor);
                BlockPos position = anchor.offset(rotateOffset(offset, rotation));
                BlockState state = source.state().rotate(rotation);
                // v6 explicitly derives a corrected snapshot; v3-v5 keep their
                // historical states for existing building/blueprint records.
                if (chestRevision >= 6 && state.getBlock() instanceof ChestBlock) {
                    state = state.setValue(ChestBlock.TYPE, state.getValue(ChestBlock.TYPE).getOpposite());
                }
                // v7 faces the central aisle. Reversing facing also reverses
                // left/right, otherwise the two halves connect away from each other.
                if (chestRevision >= 7 && state.getBlock() instanceof ChestBlock) {
                    state = state.setValue(ChestBlock.FACING, state.getValue(ChestBlock.FACING).getOpposite())
                            .setValue(ChestBlock.TYPE, state.getValue(ChestBlock.TYPE).getOpposite());
                }
                Item material = source.materialOverride() != null ? source.materialOverride()
                        : state.is(com.hkin.exiledsettlement.registry.ModBlocks.TOWN_HALL_MARKER.get())
                        ? Items.AIR : state.is(net.minecraft.world.level.block.Blocks.WATER)
                        ? Items.WATER_BUCKET : state.getBlock().asItem();
                placements.add(new BlueprintPlacement(position, state,
                        material == Items.AIR ? null : material, BlueprintPlacementMode.STRUCTURE));
                structurePositions.add(position);
            }

            for (BlockPos sourceCorner : supportCorners) {
                BlockPos base = anchor.offset(rotateOffset(sourceCorner.subtract(sourceAnchor), rotation));
                for (int depth = 1; depth <= 3; depth++) {
                    BlockPos position = base.below(depth);
                    if (!structurePositions.contains(position)) {
                        placements.add(new BlueprintPlacement(position,
                                net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState(),
                                net.minecraft.world.level.block.Blocks.COBBLESTONE.asItem(),
                                BlueprintPlacementMode.OPTIONAL_SUPPORT));
                    }
                }
            }

            for (int x = 0; x < sizeX; x++) {
                for (int y = 1; y < sizeY; y++) {
                    for (int z = 0; z < sizeZ; z++) {
                        BlockPos source = new BlockPos(x, y, z);
                        BlockPos position = anchor.offset(rotateOffset(source.subtract(sourceAnchor), rotation));
                        if (!structurePositions.contains(position)) {
                            placements.add(new BlueprintPlacement(position,
                                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), null,
                                    BlueprintPlacementMode.CLEARANCE));
                        }
                    }
                }
            }

            placements.sort(Comparator
                    .comparingInt((BlueprintPlacement placement) -> stage(placement))
                    .thenComparingInt(placement -> placement.position().getY()));

            List<BlueprintEntrance> rotatedEntrances = entrances.stream().map(source -> {
                BlockPos position = anchor.offset(rotateOffset(source.position().subtract(sourceAnchor), rotation));
                return new BlueprintEntrance(position, rotation.rotate(source.outward()));
            }).toList();

            List<BlockPos> corners = List.of(
                    new BlockPos(0, 0, 0), new BlockPos(sizeX - 1, 0, 0),
                    new BlockPos(0, sizeY - 1, sizeZ - 1), new BlockPos(sizeX - 1, sizeY - 1, sizeZ - 1));
            List<BlockPos> rotatedCorners = corners.stream()
                    .map(source -> anchor.offset(rotateOffset(source.subtract(sourceAnchor), rotation))).toList();
            int minX = rotatedCorners.stream().mapToInt(BlockPos::getX).min().orElse(anchor.getX());
            int maxX = rotatedCorners.stream().mapToInt(BlockPos::getX).max().orElse(anchor.getX());
            int minY = anchor.getY() - sourceAnchor.getY();
            int maxY = minY + sizeY - 1;
            int minZ = rotatedCorners.stream().mapToInt(BlockPos::getZ).min().orElse(anchor.getZ());
            int maxZ = rotatedCorners.stream().mapToInt(BlockPos::getZ).max().orElse(anchor.getZ());
            int width = sourceFacing.getAxis() == Direction.Axis.Z ? sizeX : sizeZ;
            int depth = sourceFacing.getAxis() == Direction.Axis.Z ? sizeZ : sizeX;
            return new BuildingBlueprintTemplate(type, anchor, facing, width, depth, sizeY,
                    new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ),
                    transitionMargin, placements, rotatedEntrances);
        }

        private static int stage(BlueprintPlacement placement) {
            if (placement.mode() == BlueprintPlacementMode.CLEARANCE) return 0;
            if (placement.mode() == BlueprintPlacementMode.OPTIONAL_SUPPORT) return 1;
            Block block = placement.state().getBlock();
            return block instanceof DoorBlock || block instanceof FenceGateBlock
                    || placement.state().hasBlockEntity() || block instanceof LanternBlock ? 3 : 2;
        }
    }
}
