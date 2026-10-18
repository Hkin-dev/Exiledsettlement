package com.hkin.exiledsettlement.blueprint;

import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.building.WorksiteAssignmentMode;
import com.hkin.exiledsettlement.blueprint.CustomTemplateSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class BlueprintRecord {
    public static final int LEGACY_STRUCTURE_VERSION = 4;
    public static final int PREVIOUS_STRUCTURE_VERSION = 5;
    public static final int PLAYER_DESIGNED_STRUCTURE_VERSION = 6;
    public static final int STRUCTURE_VERSION = 19;

    private static final String ID_TAG = "Id";
    private static final String TYPE_TAG = "Type";
    private static final String ANCHOR_TAG = "Anchor";
    private static final String MARKER_POSITION_TAG = "MarkerPosition";
    private static final String FACING_TAG = "Facing";
    private static final String OWNER_TAG = "Owner";
    private static final String REFUND_ITEM_TAG = "RefundItem";
    private static final String STRUCTURE_VERSION_TAG = "StructureVersion";
    private static final String TEMPLATE_VERSION_TAG = "TemplateVersion";
    private static final String PROGRESS_INDEX_TAG = "ProgressIndex";
    private static final String AREA_MIN_TAG = "AreaMin";
    private static final String AREA_MAX_TAG = "AreaMax";
    private static final String CANCELLATION_QUEUED_TAG = "CancellationQueued";
    private static final String ASSIGNMENT_REVISION_TAG = "AssignmentRevision";
    private static final String ASSIGNMENT_MODE_TAG = "AssignmentMode";
    private static final String TEMPORARY_FOOTINGS_TAG = "TemporaryFootings";
    private static final String FOOTING_POSITION_TAG = "Position";
    private static final String FOOTING_ITEM_TAG = "Item";
    private static final String FOOTING_PARENT_TAG = "Parent";
    private static final String FOOTING_ORDER_TAG = "Order";
    private static final String CLEARANCE_TARGETS_TAG = "ClearanceTargets";
    private static final String CLEARANCE_POSITION_TAG = "Position";
    private static final String CLEARANCE_BLOCK_TAG = "Block";
    private static final String CLEARANCE_PROPERTIES_TAG = "Properties";

    private com.hkin.exiledsettlement.region.ProductionRegion region;
    public com.hkin.exiledsettlement.region.ProductionRegion region(){return region;}
    public BlueprintRecord withRegion(com.hkin.exiledsettlement.region.ProductionRegion value){region=value;return this;}
    private CustomTemplateSnapshot customTemplate;
    private FarmEarthworkPlan earthworkPlan;
    private com.hkin.exiledsettlement.terrain.TerrainProject terrainProject;
    public com.hkin.exiledsettlement.terrain.TerrainProject terrainProject(){return terrainProject;}
    private Runnable terrainKindChanged=()->{};
    public void onTerrainKindChanged(Runnable listener){terrainKindChanged=listener;}
    public BlueprintRecord withTerrainProject(com.hkin.exiledsettlement.terrain.TerrainProject project){
        boolean changed=(terrainProject==null)!=(project==null);
        terrainProject=project;if(changed)terrainKindChanged.run();return this;
    }
    public FarmEarthworkPlan earthworkPlan() { return earthworkPlan; }
    public BlueprintRecord withEarthworkPlan(FarmEarthworkPlan plan) { earthworkPlan=plan; return this; }
    public CustomTemplateSnapshot customTemplate() { return customTemplate; }
    public BlueprintRecord withCustomTemplate(CustomTemplateSnapshot snapshot) { customTemplate=snapshot; return this; }
    private final UUID id;
    private final BuildingType type;
    private final BlockPos anchor;
    private final BlockPos markerPosition;
    private final Direction facing;
    private final UUID ownerId;
    private final boolean refundItem;
    private final int structureVersion;
    private final int templateVersion;
    private int progressIndex;
    private final BlockPos areaMin;
    private final BlockPos areaMax;
    private final boolean dynamicArea;
    private boolean cancellationQueued;
    private int assignmentRevision;
    private WorksiteAssignmentMode assignmentMode;
    private final Map<BlockPos, Item> temporaryFootings;
    private final Map<BlockPos, BlockPos> temporaryFootingParents;
    private final Map<BlockPos, Integer> temporaryFootingOrders;
    private int nextTemporaryFootingOrder;
    // Runtime coordination only: never persist a lock across a world reload.
    private UUID scaffoldWorker;
    private final com.hkin.exiledsettlement.citizen.ConstructionSiteController constructionController =
            new com.hkin.exiledsettlement.citizen.ConstructionSiteController();

    public com.hkin.exiledsettlement.citizen.ConstructionSiteController constructionController() {
        return constructionController;
    }

    public UUID scaffoldWorker() { return scaffoldWorker; }

    public void setScaffoldWorker(UUID worker) { scaffoldWorker = worker; }
    private final Map<BlockPos, BlockState> clearanceTargets;

    public BlueprintRecord(
            UUID id,
            BuildingType type,
            BlockPos anchor,
            BlockPos markerPosition,
            Direction facing,
            UUID ownerId,
            boolean refundItem,
            int structureVersion,
            int templateVersion,
            int progressIndex,
            BlockPos areaMin,
            BlockPos areaMax,
            boolean dynamicArea,
            boolean cancellationQueued,
            int assignmentRevision,
            WorksiteAssignmentMode assignmentMode,
            Map<BlockPos, Item> temporaryFootings,
            Map<BlockPos, BlockPos> temporaryFootingParents,
            Map<BlockPos, Integer> temporaryFootingOrders,
            Map<BlockPos, BlockState> clearanceTargets
    ) {
        this.id = id;
        this.type = type;
        this.anchor = anchor.immutable();
        this.markerPosition = markerPosition.immutable();
        this.facing = facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
        this.ownerId = ownerId;
        this.refundItem = refundItem;
        this.structureVersion = structureVersion;
        this.templateVersion = Math.max(1, templateVersion);
        this.progressIndex = Math.max(0, progressIndex);
        this.areaMin = areaMin.immutable();
        this.areaMax = areaMax.immutable();
        this.dynamicArea = dynamicArea;
        this.cancellationQueued = cancellationQueued;
        this.assignmentRevision = Math.max(0, assignmentRevision);
        this.assignmentMode = assignmentMode == null ? WorksiteAssignmentMode.AUTO : assignmentMode;
        this.temporaryFootings = new LinkedHashMap<>();
        this.temporaryFootingParents = new LinkedHashMap<>();
        this.temporaryFootingOrders = new LinkedHashMap<>();
        temporaryFootings.forEach((position, item) -> {
            if (item == Items.AIR) return;
            BlockPos immutable = position.immutable();
            this.temporaryFootings.put(immutable, item);
            BlockPos parent = temporaryFootingParents.get(position);
            if (parent != null && temporaryFootings.containsKey(parent)) {
                this.temporaryFootingParents.put(immutable, parent.immutable());
            }
            int order = Math.max(0, temporaryFootingOrders.getOrDefault(position,
                    this.temporaryFootingOrders.size()));
            this.temporaryFootingOrders.put(immutable, order);
            this.nextTemporaryFootingOrder = Math.max(this.nextTemporaryFootingOrder, order + 1);
        });
        this.clearanceTargets = new LinkedHashMap<>();
        clearanceTargets.forEach((position, state) -> {
            if (!state.isAir()) this.clearanceTargets.put(position.immutable(), state);
        });
    }

    public static BlueprintRecord create(
            BuildingType type,
            BlockPos anchor,
            BlockPos markerPosition,
            Direction facing,
            UUID ownerId,
            boolean refundItem
    ) {
        return new BlueprintRecord(
                UUID.randomUUID(),
                type,
                anchor,
                markerPosition,
                facing,
                ownerId,
                refundItem,
                STRUCTURE_VERSION,
                BlueprintTemplates.currentTemplateVersion(type),
                0,
                anchor,
                anchor,
                false,
                false,
                0,
                WorksiteAssignmentMode.AUTO,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of()
        );
    }

    public static BlueprintRecord create(
            BuildingType type, BlockPos anchor, BlockPos markerPosition, Direction facing,
            UUID ownerId, boolean refundItem, Map<BlockPos, BlockState> clearanceTargets
    ) {
        return new BlueprintRecord(UUID.randomUUID(), type, anchor, markerPosition, facing, ownerId,
                refundItem, STRUCTURE_VERSION, BlueprintTemplates.currentTemplateVersion(type),
                0, anchor, anchor, false, false, 0,
                WorksiteAssignmentMode.AUTO,
                Map.of(), Map.of(), Map.of(), clearanceTargets);
    }

    public static BlueprintRecord create(
            BuildingType type, BlockPos anchor, BlockPos markerPosition, Direction facing,
            UUID ownerId, boolean refundItem, int templateVersion,
            Map<BlockPos, BlockState> clearanceTargets
    ) {
        return new BlueprintRecord(UUID.randomUUID(), type, anchor, markerPosition, facing, ownerId,
                refundItem, STRUCTURE_VERSION, templateVersion, 0, anchor, anchor, false, false, 0,
                WorksiteAssignmentMode.AUTO,
                Map.of(), Map.of(), Map.of(), clearanceTargets);
    }

    public static BlueprintRecord createArea(
            BuildingType type, BlockPos anchor, BlockPos markerPosition, Direction facing,
            UUID ownerId, boolean refundItem, BlockPos first, BlockPos second
    ) {
        BlockPos min = new BlockPos(Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()),
                Math.min(first.getZ(), second.getZ()));
        BlockPos max = new BlockPos(Math.max(first.getX(), second.getX()), Math.max(first.getY(), second.getY()),
                Math.max(first.getZ(), second.getZ()));
        return new BlueprintRecord(UUID.randomUUID(), type, anchor, markerPosition, facing, ownerId,
                refundItem, STRUCTURE_VERSION, BlueprintTemplates.currentTemplateVersion(type),
                0, min, max, true, false, 0,
                WorksiteAssignmentMode.AUTO, Map.of(), Map.of(), Map.of(), Map.of());
    }

    public static BlueprintRecord createArea(
            BuildingType type, BlockPos anchor, BlockPos markerPosition, Direction facing,
            UUID ownerId, boolean refundItem, BlockPos first, BlockPos second,
            Map<BlockPos, BlockState> clearanceTargets
    ) {
        return createArea(type, anchor, markerPosition, facing, ownerId, refundItem, first, second,
                BlueprintTemplates.currentTemplateVersion(type), clearanceTargets);
    }

    public static BlueprintRecord createArea(
            BuildingType type, BlockPos anchor, BlockPos markerPosition, Direction facing,
            UUID ownerId, boolean refundItem, BlockPos first, BlockPos second, int templateVersion,
            Map<BlockPos, BlockState> clearanceTargets
    ) {
        BlockPos min = new BlockPos(Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()),
                Math.min(first.getZ(), second.getZ()));
        BlockPos max = new BlockPos(Math.max(first.getX(), second.getX()), Math.max(first.getY(), second.getY()),
                Math.max(first.getZ(), second.getZ()));
        return new BlueprintRecord(UUID.randomUUID(), type, anchor, markerPosition, facing, ownerId,
                refundItem, STRUCTURE_VERSION, templateVersion, 0, min, max, true, false, 0,
                WorksiteAssignmentMode.AUTO, Map.of(), Map.of(), Map.of(), clearanceTargets);
    }

    public UUID id() {
        return id;
    }

    public BuildingType type() {
        return type;
    }

    public BlockPos anchor() {
        return anchor;
    }

    public BlockPos markerPosition() {
        return markerPosition;
    }

    public Direction facing() {
        return facing;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public boolean refundItem() {
        return refundItem;
    }

    public int structureVersion() {
        return structureVersion;
    }

    public int templateVersion() {
        return templateVersion;
    }

    private static int legacyTemplateVersion(BuildingType type, int structureVersion) {
        if (structureVersion == 18) {
            return switch (type) {
                case HOUSE, ADVANCED_HOUSE_2, FARM_FIELD, MELON_FIELD, PASTURE_AREA -> 4;
                case WAREHOUSE -> 7;
                case TOWN_HALL -> 5;
                case ADVANCED_HOUSE_1, ADVANCED_HOUSE_3, HOUSE_3, TOOL_WORKSHOP, SAWMILL, SCHOOL -> 3;
                case HUNTING_LODGE, FORESTER_LODGE, FISHING_HUT, TREE_FARM -> 2;
                default -> 1;
            };
        }
        if (structureVersion == 17) {
            return switch (type) {
                case HOUSE, TOWN_HALL -> 4;
                case WAREHOUSE -> 7;
                case ADVANCED_HOUSE_1, ADVANCED_HOUSE_3, HOUSE_3 -> 3;
                case ADVANCED_HOUSE_2 -> 4;
                case HUNTING_LODGE, TOOL_WORKSHOP, SAWMILL, FORESTER_LODGE, FISHING_HUT, SCHOOL -> 2;
                case FARM_FIELD, MELON_FIELD -> 4;
                case PASTURE_AREA -> 3;
                default -> 1;
            };
        }
        if (structureVersion == 16) {
            return switch (type) {
                case HOUSE, TOWN_HALL -> 4;
                case WAREHOUSE -> 7;
                case ADVANCED_HOUSE_2 -> 3;
                case HOUSE_3, ADVANCED_HOUSE_1, ADVANCED_HOUSE_3, HUNTING_LODGE, TOOL_WORKSHOP,
                        SAWMILL, FARM_FIELD, PASTURE_AREA, FORESTER_LODGE, FISHING_HUT -> 2;
                default -> 1;
            };
        }
        if (structureVersion == 15) {
            return switch (type) {
                case HOUSE, TOWN_HALL -> 4;
                case WAREHOUSE -> 7;
                case ADVANCED_HOUSE_2 -> 3;
                case HOUSE_3, ADVANCED_HOUSE_1, ADVANCED_HOUSE_3, HUNTING_LODGE,
                        TOOL_WORKSHOP, SAWMILL, FARM_FIELD, PASTURE_AREA -> 2;
                default -> 1;
            };
        }
        if (structureVersion >= STRUCTURE_VERSION) {
            return BlueprintTemplates.currentTemplateVersion(type);
        }
        if (structureVersion >= 12 && structureVersion <= 14) {
            return switch (type) {
                case WAREHOUSE -> structureVersion - 7;
                case HOUSE -> 4;
                case TOWN_HALL -> 3;
                case HOUSE_3, ADVANCED_HOUSE_2, FARM_FIELD, PASTURE_AREA -> 2;
                default -> 1;
            };
        }
        // Preserve pending 0.15.3 plans as well as completed v2 town halls.
        if (structureVersion == 11) {
            return switch (type) {
                case HOUSE -> 4;
                case WAREHOUSE -> 5;
                case TOWN_HALL, HOUSE_3, ADVANCED_HOUSE_2, FARM_FIELD, PASTURE_AREA -> 2;
                default -> 1;
            };
        }
        // 0.15.0-0.15.2 plans retain their old procedural door/window geometry.
        if (structureVersion == 10) {
            return switch (type) {
                case HOUSE -> 4;
                case WAREHOUSE -> 5;
                case TOWN_HALL, FARM_FIELD, PASTURE_AREA -> 2;
                default -> 1;
            };
        }
        // Structure version 9 was the complete 0.14.6/0.14.7 template line.
        // Preserve every one of those immutable snapshots when a 0.15.0 save is
        // loaded; only newly submitted structure-version-10 blueprints advance.
        if (structureVersion >= 9) {
            return switch (type) {
                case HOUSE -> 4;
                case HOUSE_2 -> 1;
                case WAREHOUSE -> 4;
                case TOWN_HALL, FARM_FIELD, PASTURE_AREA -> 2;
                default -> 1;
            };
        }
        if (structureVersion >= 7 && type == BuildingType.HOUSE) return 4;
        if (structureVersion >= PLAYER_DESIGNED_STRUCTURE_VERSION
                && PlayerDesignedTemplates.supports(type)) return 3;
        return structureVersion >= PREVIOUS_STRUCTURE_VERSION && isVersionedAnchor(type) ? 2 : 1;
    }

    public static boolean supportsStructureVersion(int version) {
        return version >= LEGACY_STRUCTURE_VERSION && version <= STRUCTURE_VERSION;
    }

    private static boolean isVersionedAnchor(BuildingType type) {
        return type == BuildingType.HOUSE || type == BuildingType.TOWN_HALL;
    }

    public int progressIndex() {
        return progressIndex;
    }

    public boolean dynamicArea() { return dynamicArea; }
    public BlockPos areaMin() { return region!=null && region.surveyed?region.min():customTemplate == null ? areaMin : customTemplate.template().minCorner(); }
    public BlockPos areaMax() { return region!=null && region.surveyed?region.max():customTemplate == null ? areaMax : customTemplate.template().maxCorner(); }
    /** The stored area corners are selection data; ordinary buildings store only their anchor there. */
    public boolean containsColumn(BlockPos position) {
        if(region!=null) {
            if(region.surveyed)return region.cell(position)!=null;
            return position.getX()>=Math.min(region.first.getX(),region.second.getX())-region.selectionMargin
                    && position.getX()<=Math.max(region.first.getX(),region.second.getX())+region.selectionMargin
                    && position.getZ()>=Math.min(region.first.getZ(),region.second.getZ())-region.selectionMargin
                    && position.getZ()<=Math.max(region.first.getZ(),region.second.getZ())+region.selectionMargin;
        }
        var template=BlueprintTemplates.create(this);var min=template.minCorner();var max=template.maxCorner();
        return position.getX()>=min.getX() && position.getX()<=max.getX()
                && position.getZ()>=min.getZ() && position.getZ()<=max.getZ();
    }
    public boolean cancellationQueued() { return cancellationQueued; }
    public int assignmentRevision() { return assignmentRevision; }
    public WorksiteAssignmentMode assignmentMode() { return assignmentMode; }
    public boolean assignmentRevisionMatches(int expected) { return assignmentRevision == expected; }
    public void markAssignmentChanged() { assignmentRevision++; }
    public boolean setAssignmentMode(WorksiteAssignmentMode mode, int expectedRevision) {
        WorksiteAssignmentMode normalized = mode == null ? WorksiteAssignmentMode.AUTO : mode;
        if (expectedRevision != assignmentRevision || assignmentMode == normalized) return false;
        assignmentMode = normalized;
        assignmentRevision++;
        return true;
    }
    public Map<BlockPos, Item> temporaryFootings() {
        return Collections.unmodifiableMap(temporaryFootings);
    }

    public boolean hasTemporaryFooting(BlockPos position) {
        return temporaryFootings.containsKey(position);
    }

    public Item temporaryFootingItem(BlockPos position) {
        return temporaryFootings.getOrDefault(position, Items.AIR);
    }

    public boolean addTemporaryFooting(BlockPos position, Item item) {
        return addTemporaryFooting(position, item, null);
    }

    public boolean addTemporaryFooting(BlockPos position, Item item, BlockPos parent) {
        if (item == Items.AIR || temporaryFootings.containsKey(position)) return false;
        BlockPos immutable = position.immutable();
        temporaryFootings.put(immutable, item);
        // Access supports are recovered through their own material-backed cleanup,
        // even if a pending lookup saw this cell before support registration.
        clearanceTargets.remove(immutable);
        if (parent != null && temporaryFootings.containsKey(parent)) {
            temporaryFootingParents.put(immutable, parent.immutable());
        }
        temporaryFootingOrders.put(immutable, nextTemporaryFootingOrder++);
        return true;
    }

    public boolean removeTemporaryFooting(BlockPos position) {
        if (temporaryFootings.remove(position) == null) return false;
        temporaryFootingParents.remove(position);
        // Vanilla scaffolding can collapse several cells after one support
        // changes. If the vanished cell was in the middle of a recorded access
        // chain, its still-live children are no longer physically connected to
        // the old root. Promote those children to independent roots so cleanup
        // approaches them honestly instead of traversing a phantom parent.
        temporaryFootingParents.entrySet().removeIf(entry ->
                position.equals(entry.getValue()));
        temporaryFootingOrders.remove(position);
        return true;
    }

    public BlockPos temporaryFootingParent(BlockPos position) {
        return temporaryFootingParents.get(position);
    }

    public int temporaryFootingOrder(BlockPos position) {
        return temporaryFootingOrders.getOrDefault(position, -1);
    }

    public boolean hasTemporaryFootingChild(BlockPos position) {
        return temporaryFootingParents.values().stream().anyMatch(position::equals);
    }

    public Map<BlockPos, BlockState> clearanceTargets() {
        return Collections.unmodifiableMap(clearanceTargets);
    }

    public boolean hasClearanceTarget(BlockPos position) {
        return clearanceTargets.containsKey(position);
    }

    public BlockState clearanceOriginalState(BlockPos position) {
        return clearanceTargets.get(position);
    }

    public boolean removeClearanceTarget(BlockPos position) {
        return clearanceTargets.remove(position) != null;
    }

    public boolean addClearanceTarget(BlockPos position, BlockState current) {
        if (current.isAir() || clearanceTargets.containsKey(position)) return false;
        clearanceTargets.put(position.immutable(), current);
        return true;
    }

    public boolean queueCancellation() {
        if (cancellationQueued) return false;
        cancellationQueued = true;
        return true;
    }

    public boolean setProgressIndex(int newProgressIndex) {
        int normalized = Math.max(0, newProgressIndex);
        if (progressIndex == normalized) {
            return false;
        }
        progressIndex = normalized;
        return true;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if(terrainProject!=null)tag.put("TerrainProject",terrainProject.save());
        if (earthworkPlan != null) tag.put("FarmEarthwork", earthworkPlan.save());
        if(region!=null)tag.put("ProductionRegion",region.save());
        if (customTemplate != null) tag.put("CustomTemplate", customTemplate.save());
        tag.putString(ID_TAG, id.toString());
        tag.putString(TYPE_TAG, type.serializedName());
        tag.putLong(ANCHOR_TAG, anchor.asLong());
        tag.putLong(MARKER_POSITION_TAG, markerPosition.asLong());
        tag.putString(FACING_TAG, facing.getName());
        tag.putString(OWNER_TAG, ownerId.toString());
        tag.putBoolean(REFUND_ITEM_TAG, refundItem);
        tag.putInt(STRUCTURE_VERSION_TAG, structureVersion);
        tag.putInt(TEMPLATE_VERSION_TAG, templateVersion);
        tag.putInt(PROGRESS_INDEX_TAG, progressIndex);
        if (dynamicArea) {
            tag.putLong(AREA_MIN_TAG, areaMin.asLong());
            tag.putLong(AREA_MAX_TAG, areaMax.asLong());
        }
        tag.putBoolean(CANCELLATION_QUEUED_TAG, cancellationQueued);
        tag.putInt(ASSIGNMENT_REVISION_TAG, assignmentRevision);
        tag.putString(ASSIGNMENT_MODE_TAG, assignmentMode.serializedName());
        ListTag footings = new ListTag();
        temporaryFootings.forEach((position, item) -> {
            CompoundTag footing = new CompoundTag();
            footing.putLong(FOOTING_POSITION_TAG, position.asLong());
            footing.putString(FOOTING_ITEM_TAG, BuiltInRegistries.ITEM.getKey(item).toString());
            BlockPos parent = temporaryFootingParents.get(position);
            if (parent != null) footing.putLong(FOOTING_PARENT_TAG, parent.asLong());
            footing.putInt(FOOTING_ORDER_TAG, temporaryFootingOrder(position));
            footings.add(footing);
        });
        tag.put(TEMPORARY_FOOTINGS_TAG, footings);
        ListTag clearances = new ListTag();
        clearanceTargets.forEach((position, state) -> {
            CompoundTag clearance = new CompoundTag();
            clearance.putLong(CLEARANCE_POSITION_TAG, position.asLong());
            clearance.putString(CLEARANCE_BLOCK_TAG, BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            CompoundTag properties = new CompoundTag();
            for (Property<?> property : state.getProperties()) {
                properties.putString(property.getName(), propertyValue(state, property));
            }
            clearance.put(CLEARANCE_PROPERTIES_TAG, properties);
            clearances.add(clearance);
        });
        tag.put(CLEARANCE_TARGETS_TAG, clearances);
        return tag;
    }

    public static BlueprintRecord load(CompoundTag tag) {
        Direction facing = Direction.byName(tag.getString(FACING_TAG));
        BlockPos anchor = BlockPos.of(tag.getLong(ANCHOR_TAG));
        BuildingType type = BuildingType.fromSerializedName(tag.getString(TYPE_TAG));
        int structureVersion = Math.max(1, tag.getInt(STRUCTURE_VERSION_TAG));
        int templateVersion = tag.contains(TEMPLATE_VERSION_TAG)
                ? Math.max(1, tag.getInt(TEMPLATE_VERSION_TAG))
                : legacyTemplateVersion(type, structureVersion);
        Map<BlockPos, Item> temporaryFootings = new LinkedHashMap<>();
        Map<BlockPos, BlockPos> temporaryFootingParents = new LinkedHashMap<>();
        Map<BlockPos, Integer> temporaryFootingOrders = new LinkedHashMap<>();
        ListTag footings = tag.getList(TEMPORARY_FOOTINGS_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < footings.size(); index++) {
            CompoundTag footing = footings.getCompound(index);
            ResourceLocation itemId = ResourceLocation.tryParse(footing.getString(FOOTING_ITEM_TAG));
            if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) continue;
            Item item = BuiltInRegistries.ITEM.get(itemId);
            if (item != Items.AIR) {
                BlockPos position = BlockPos.of(footing.getLong(FOOTING_POSITION_TAG));
                temporaryFootings.put(position, item);
                if (footing.contains(FOOTING_PARENT_TAG)) {
                    temporaryFootingParents.put(position, BlockPos.of(footing.getLong(FOOTING_PARENT_TAG)));
                }
                temporaryFootingOrders.put(position, footing.contains(FOOTING_ORDER_TAG)
                        ? footing.getInt(FOOTING_ORDER_TAG) : index);
            }
        }
        Map<BlockPos, BlockState> clearanceTargets = new LinkedHashMap<>();
        ListTag clearances = tag.getList(CLEARANCE_TARGETS_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < clearances.size(); index++) {
            CompoundTag clearance = clearances.getCompound(index);
            ResourceLocation blockId = ResourceLocation.tryParse(clearance.getString(CLEARANCE_BLOCK_TAG));
            if (blockId == null || !BuiltInRegistries.BLOCK.containsKey(blockId)) continue;
            BlockState state = BuiltInRegistries.BLOCK.get(blockId).defaultBlockState();
            CompoundTag properties = clearance.getCompound(CLEARANCE_PROPERTIES_TAG);
            for (String propertyName : properties.getAllKeys()) {
                state = setProperty(state, propertyName, properties.getString(propertyName));
            }
            if (!state.isAir()) {
                clearanceTargets.put(BlockPos.of(clearance.getLong(CLEARANCE_POSITION_TAG)), state);
            }
        }
        BlueprintRecord loaded = new BlueprintRecord(
                UUID.fromString(tag.getString(ID_TAG)),
                type,
                anchor,
                tag.contains(MARKER_POSITION_TAG) ? BlockPos.of(tag.getLong(MARKER_POSITION_TAG)) : anchor,
                facing == null ? Direction.NORTH : facing,
                UUID.fromString(tag.getString(OWNER_TAG)),
                tag.getBoolean(REFUND_ITEM_TAG),
                structureVersion,
                templateVersion,
                tag.getInt(PROGRESS_INDEX_TAG),
                tag.contains(AREA_MIN_TAG) ? BlockPos.of(tag.getLong(AREA_MIN_TAG)) : anchor,
                tag.contains(AREA_MAX_TAG) ? BlockPos.of(tag.getLong(AREA_MAX_TAG)) : anchor,
                tag.contains(AREA_MIN_TAG) && tag.contains(AREA_MAX_TAG),
                tag.getBoolean(CANCELLATION_QUEUED_TAG),
                tag.getInt(ASSIGNMENT_REVISION_TAG),
                tag.contains(ASSIGNMENT_MODE_TAG)
                        ? WorksiteAssignmentMode.fromSerializedName(tag.getString(ASSIGNMENT_MODE_TAG))
                        : WorksiteAssignmentMode.AUTO,
                temporaryFootings,
                temporaryFootingParents,
                temporaryFootingOrders,
                clearanceTargets
        );
        if(tag.contains("ProductionRegion"))loaded.withRegion(com.hkin.exiledsettlement.region.ProductionRegion.load(tag.getCompound("ProductionRegion")));
        if (tag.contains("CustomTemplate")) loaded.withCustomTemplate(CustomTemplateSnapshot.load(tag.getCompound("CustomTemplate")));
        if(tag.contains("TerrainProject"))loaded.withTerrainProject(com.hkin.exiledsettlement.terrain.TerrainProject.load(tag.getCompound("TerrainProject")));
        if (tag.contains("FarmEarthwork")) loaded.withEarthworkPlan(FarmEarthworkPlan.load(tag.getCompound("FarmEarthwork")));
        return loaded;
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static BlockState setProperty(BlockState state, String name, String value) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) return setPropertyValue(state, property, value);
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState setPropertyValue(
            BlockState state, Property<T> property, String value
    ) {
        return property.getValue(value).map(parsed -> state.setValue(property, parsed)).orElse(state);
    }
}
