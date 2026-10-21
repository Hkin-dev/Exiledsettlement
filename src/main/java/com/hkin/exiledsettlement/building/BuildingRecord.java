package com.hkin.exiledsettlement.building;

import com.hkin.exiledsettlement.blueprint.CustomTemplateSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.LinkedHashSet;

public final class BuildingRecord {
    private final MineExpansionState mineExpansion = new MineExpansionState();
    public MineExpansionState mineExpansion() { return mineExpansion; }
    private final WarehouseStock warehouseStock = new WarehouseStock();
    public WarehouseStock warehouseStock() { return warehouseStock; }
    private static final String ID_TAG = "Id";
    private static final String TYPE_TAG = "Type";
    private static final String POSITION_TAG = "Position";
    private static final String ACTIVE_TAG = "Active";
    private static final String OPERATIONAL_TAG = "Operational";
    private static final String STATUS_TAG = "Status";
    private static final String CAPACITY_TAG = "Capacity";
    private static final String SERVICE_POSITIONS_TAG = "ServicePositions";
    private static final String CUSTOM_NAME_TAG = "CustomName";
    private static final String FACING_TAG = "Facing";
    private static final String MIN_CORNER_TAG = "MinCorner";
    private static final String MAX_CORNER_TAG = "MaxCorner";
    private static final String TRANSITION_MARGIN_TAG = "TransitionMargin";
    private static final String DEMOLITION_QUEUED_TAG = "DemolitionQueued";
    private static final String WAREHOUSE_CATEGORY_MASK_TAG = "WarehouseCategoryMask";
    private static final String PRODUCTION_ID_TAG = "ProductionId";
    private static final String BREW_STOCK_TARGET_TAG = "BrewStockTarget";
    public static final int DEFAULT_BREW_STOCK_TARGET = 12;
    public static final int MAX_BREW_STOCK_TARGET = 192;
    private static final String REPAIR_POSITIONS_TAG = "RepairPositions";
    private static final String TEMPLATE_VERSION_TAG = "TemplateVersion";
    private static final String WORKER_TARGET_TAG = "WorkerTarget";
    private static final String CONFIGURATION_REVISION_TAG = "ConfigurationRevision";
    private static final String WORKSITE_ASSIGNMENT_MODE_TAG = "WorksiteAssignmentMode";

    private com.hkin.exiledsettlement.region.ProductionSiteSnapshot productionSite;
    public com.hkin.exiledsettlement.region.ProductionSiteSnapshot productionSite(){return productionSite;}
    public BuildingRecord withProductionSite(com.hkin.exiledsettlement.region.ProductionSiteSnapshot value){productionSite=value;return this;}
    private com.hkin.exiledsettlement.region.ProductionRegion region;
    public com.hkin.exiledsettlement.region.ProductionRegion region(){return region;}
    public BuildingRecord withRegion(com.hkin.exiledsettlement.region.ProductionRegion value){region=value;return this;}
    private CustomTemplateSnapshot customTemplate;
    public CustomTemplateSnapshot customTemplate() { return customTemplate; }
    public BuildingRecord withCustomTemplate(CustomTemplateSnapshot snapshot) { customTemplate=snapshot; return this; }
    private final UUID id;
    private final BuildingType type;
    private final BlockPos position;
    private boolean active;
    private boolean operational;
    private BuildingStatus status;
    private int capacity;
    private List<BlockPos> servicePositions;
    private String customName;
    private final Direction facing;
    private final BlockPos minCorner;
    private final BlockPos maxCorner;
    private final int transitionMargin;
    private boolean demolitionQueued;
    private int warehouseCategoryMask;
    private ResourceLocation productionId;
    private int brewStockTarget = DEFAULT_BREW_STOCK_TARGET;
    private final Set<BlockPos> repairPositions;
    private final int templateVersion;
    private int workerTarget;
    private int configurationRevision;
    private boolean workerTargetInitialized;
    private WorksiteAssignmentMode worksiteAssignmentMode;

    public BuildingRecord(
            UUID id,
            BuildingType type,
            BlockPos position,
            boolean active,
            boolean operational,
            BuildingStatus status,
            int capacity,
            List<BlockPos> servicePositions,
            String customName,
            Direction facing,
            BlockPos minCorner,
            BlockPos maxCorner,
            int transitionMargin,
            boolean demolitionQueued,
            int warehouseCategoryMask,
            ResourceLocation productionId,
            Set<BlockPos> repairPositions,
            int templateVersion,
            int workerTarget,
            int configurationRevision,
            boolean workerTargetInitialized,
            WorksiteAssignmentMode worksiteAssignmentMode
    ) {
        this.id = id;
        this.type = type;
        this.position = position.immutable();
        this.active = active;
        this.operational = operational;
        this.status = status;
        this.capacity = Math.max(0, capacity);
        this.servicePositions = servicePositions.stream().map(BlockPos::immutable).toList();
        this.customName = normalizeName(customName);
        this.facing = facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
        this.minCorner = minCorner.immutable();
        this.maxCorner = maxCorner.immutable();
        this.transitionMargin = Math.max(0, transitionMargin);
        this.demolitionQueued = demolitionQueued;
        this.warehouseCategoryMask = warehouseCategoryMask & WarehouseCategory.allMask();
        this.productionId = BuildingProductionService.supported(type, productionId)
                ? productionId : BuildingProductionService.defaultFor(type);
        this.repairPositions = new LinkedHashSet<>();
        repairPositions.stream().map(BlockPos::immutable).forEach(this.repairPositions::add);
        this.templateVersion = Math.max(1, templateVersion);
        this.workerTarget = Math.max(0, workerTarget);
        this.configurationRevision = Math.max(0, configurationRevision);
        this.workerTargetInitialized = workerTargetInitialized;
        this.worksiteAssignmentMode = worksiteAssignmentMode == null
                ? WorksiteAssignmentMode.AUTO : worksiteAssignmentMode;
        if (this.warehouseCategoryMask == 0 && !type.isStorage()) {
            this.warehouseCategoryMask = WarehouseCategory.allMask();
        }
        if (demolitionQueued) {
            this.operational = false;
            this.status = BuildingStatus.DEMOLITION_QUEUED;
        }
    }

    public static BuildingRecord create(BuildingType type, BlockPos position) {
        return new BuildingRecord(
                UUID.randomUUID(),
                type,
                position,
                true,
                false,
                BuildingStatus.PENDING_VALIDATION,
                0,
                List.of(),
                "",
                Direction.NORTH,
                position,
                position,
                2,
                false,
                WarehouseCategory.allMask(), BuildingProductionService.defaultFor(type), Set.of(),
                1, 0, 0, true, WorksiteAssignmentMode.AUTO
        );
    }

    public static BuildingRecord create(
            BuildingType type, BlockPos position, Direction facing,
            BlockPos minCorner, BlockPos maxCorner, int transitionMargin
    ) {
        return create(type, position, facing, minCorner, maxCorner, transitionMargin, 1);
    }

    public static BuildingRecord create(
            BuildingType type, BlockPos position, Direction facing,
            BlockPos minCorner, BlockPos maxCorner, int transitionMargin, int templateVersion
    ) {
        return new BuildingRecord(UUID.randomUUID(), type, position, true, false,
                BuildingStatus.PENDING_VALIDATION, 0, List.of(), "", facing,
                minCorner, maxCorner, transitionMargin, false, WarehouseCategory.allMask(),
                BuildingProductionService.defaultFor(type), Set.of(), templateVersion, 0, 0, true,
                WorksiteAssignmentMode.AUTO);
    }

    public UUID id() {
        return id;
    }

    public BuildingType type() {
        return type;
    }

    public BlockPos position() {
        return position;
    }

    public boolean active() {
        return active;
    }

    public boolean operational() {
        return operational;
    }

    public BuildingStatus status() {
        return status;
    }

    public int capacity() {
        if (type.isStorage() && WarehouseStock.applies(this)) return WarehouseStock.CAPACITY;
        return capacity;
    }

    /** Storage slots are not employment slots. One keeper slot per registered container. */
    public int workerCapacity() {
        return type.isStorage() ? (operational() ? (WarehouseStock.applies(this) ? 1 : servicePositions.size()) : 0) : capacity;
    }

    public List<BlockPos> servicePositions() {
        return Collections.unmodifiableList(servicePositions);
    }

    public String customName() { return customName; }
    public Direction facing() { return facing; }
    public BlockPos minCorner() { if(type==BuildingType.MINE && customTemplate==null)return minCorner.atY(Math.min(minCorner.getY(),MineExpansionService.lowestY(this))); return productionSite!=null?productionSite.template().minCorner():region!=null?region.min():minCorner; }
    public BlockPos maxCorner() { return productionSite!=null?productionSite.template().maxCorner():region!=null?region.max():maxCorner; }
    public int transitionMargin() { return transitionMargin; }
    public boolean demolitionQueued() { return demolitionQueued; }
    public int warehouseCategoryMask() { return warehouseCategoryMask; }
    public ResourceLocation productionId() { return productionId; }
    public int brewStockTarget() { return brewStockTarget; }
    public Set<BlockPos> repairPositions() { return Collections.unmodifiableSet(repairPositions); }
    public int templateVersion() { return templateVersion; }
    public int workerTarget() { return workerTarget; }
    public int configurationRevision() { return configurationRevision; }
    public boolean workerTargetInitialized() { return workerTargetInitialized; }
    public WorksiteAssignmentMode worksiteAssignmentMode() { return worksiteAssignmentMode; }
    public boolean configurationMatches(int expectedRevision) { return configurationRevision == expectedRevision; }

    public void markConfigurationChanged() {
        configurationRevision++;
    }

    public boolean setWorksiteAssignmentMode(WorksiteAssignmentMode mode, int expectedRevision) {
        WorksiteAssignmentMode normalized = mode == null ? WorksiteAssignmentMode.AUTO : mode;
        if (expectedRevision != configurationRevision || worksiteAssignmentMode == normalized) return false;
        worksiteAssignmentMode = normalized;
        configurationRevision++;
        return true;
    }

    public boolean setWorkerTarget(int target, int expectedRevision) {
        int normalized = Math.max(0, target);
        if (expectedRevision != configurationRevision || workerTarget == normalized
                || normalized > workerCapacity() && normalized >= workerTarget) {
            return false;
        }
        workerTarget = normalized;
        workerTargetInitialized = true;
        configurationRevision++;
        return true;
    }

    public boolean migrateWorkerTarget(int target) {
        // Preserve the requested staffing level while a building is damaged or
        // temporarily disabled. Runtime assignment still respects live capacity.
        int normalized = Math.max(0, target);
        boolean changed = !workerTargetInitialized || workerTarget != normalized;
        workerTarget = normalized;
        workerTargetInitialized = true;
        return changed;
    }

    public boolean setProductionId(ResourceLocation value) {
        if (!BuildingProductionService.supported(type, value) || productionId.equals(value) && (productionSite==null || productionSite.productions().stream().allMatch(value::equals)) && (region==null || region.productions().stream().allMatch(value::equals))) return false;
        productionId = value;
        if(productionSite!=null)productionSite=productionSite.withProduction(value);
        if(region!=null){region.cells.replaceAll((key,cell)->new com.hkin.exiledsettlement.region.ProductionRegion.Cell(cell.ground(),cell.use(),BuildingProductionService.supported(cell.type(),value)?value:cell.production(),cell.type()));region.invalidate();}
        return true;
    }

    public boolean setBrewStockTarget(int target, int expectedRevision) {
        if (type != BuildingType.CHURCH || expectedRevision != configurationRevision
                || target < 0 || target > MAX_BREW_STOCK_TARGET || brewStockTarget == target) return false;
        brewStockTarget = target;
        configurationRevision++;
        return true;
    }

    public boolean markDamaged(BlockPos position) {
        return active && contains(position) && repairPositions.add(position.immutable());
    }

    public boolean clearRepairPosition(BlockPos position) {
        return repairPositions.remove(position);
    }

    public boolean accepts(ItemStack stack) {
        if (WarehouseStock.applies(this)) return true;
        return !type.isStorage()
                || stack.isEmpty()
                || (warehouseCategoryMask & WarehouseCategory.classify(stack).bit()) != 0;
    }

    public boolean toggleWarehouseCategory(WarehouseCategory category) {
        if(WarehouseStock.applies(this))return false;
        if (!type.isStorage()) return false;
        warehouseCategoryMask ^= category.bit();
        return true;
    }

    public boolean queueDemolition() {
        if (!active || demolitionQueued) return false;
        demolitionQueued = true;
        operational = false;
        status = BuildingStatus.DEMOLITION_QUEUED;
        capacity = 0;
        return true;
    }

    public boolean rename(String newName) {
        String normalized = normalizeName(newName);
        if (normalized.equals(customName)) return false;
        customName = normalized;
        return true;
    }

    public boolean contains(BlockPos blockPos) {
        if (type == BuildingType.MINE && MineExpansionService.owns(this, blockPos)) return true;
        if(productionSite!=null)return productionSite.contains(blockPos);
        if(region!=null)return region.contains(blockPos);
        return blockPos.getX() >= minCorner.getX() && blockPos.getX() <= maxCorner.getX()
                && blockPos.getY() >= minCorner().getY() - 4 && blockPos.getY() <= maxCorner.getY()
                && blockPos.getZ() >= minCorner.getZ() && blockPos.getZ() <= maxCorner.getZ();
    }

    public boolean updateFunction(
            boolean newOperational,
            BuildingStatus newStatus,
            int newCapacity,
            List<BlockPos> newServicePositions
    ) {
        if (demolitionQueued) return false;
        List<BlockPos> normalizedPositions = newServicePositions.stream().map(BlockPos::immutable).toList();
        int normalizedCapacity = Math.max(0, newCapacity);
        boolean changed = operational != newOperational
                || status != newStatus
                || capacity != normalizedCapacity
                || !servicePositions.equals(normalizedPositions);
        operational = newOperational;
        status = Objects.requireNonNull(newStatus);
        capacity = normalizedCapacity;
        servicePositions = normalizedPositions;
        return changed;
    }

    public void deactivate() {
        active = false;
        operational = false;
        demolitionQueued = false;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (type == BuildingType.MINE) tag.put("MineExpansion", mineExpansion.save());
        if (WarehouseStock.applies(this)) tag.put("WarehouseStock", warehouseStock.snapshot());
        if(productionSite!=null)tag.put("ProductionSite",productionSite.save());
        else if(region!=null)tag.put("ProductionRegion",region.save());
        if (customTemplate != null) tag.put("CustomTemplate", customTemplate.save());
        tag.putString(ID_TAG, id.toString());
        tag.putString(TYPE_TAG, type.serializedName());
        tag.putLong(POSITION_TAG, position.asLong());
        tag.putBoolean(ACTIVE_TAG, active);
        tag.putBoolean(OPERATIONAL_TAG, operational);
        tag.putString(STATUS_TAG, status.serializedName());
        tag.putInt(CAPACITY_TAG, capacity());
        tag.putLongArray(SERVICE_POSITIONS_TAG, servicePositions.stream().mapToLong(BlockPos::asLong).toArray());
        tag.putString(CUSTOM_NAME_TAG, customName);
        tag.putString(FACING_TAG, facing.getName());
        tag.putLong(MIN_CORNER_TAG, minCorner.asLong());
        tag.putLong(MAX_CORNER_TAG, maxCorner.asLong());
        tag.putInt(TRANSITION_MARGIN_TAG, transitionMargin);
        tag.putBoolean(DEMOLITION_QUEUED_TAG, demolitionQueued);
        tag.putInt(WAREHOUSE_CATEGORY_MASK_TAG, warehouseCategoryMask);
        tag.putString(PRODUCTION_ID_TAG, productionId.toString());
        if (type == BuildingType.CHURCH) tag.putInt(BREW_STOCK_TARGET_TAG, brewStockTarget);
        tag.putLongArray(REPAIR_POSITIONS_TAG, repairPositions.stream().mapToLong(BlockPos::asLong).toArray());
        tag.putInt(TEMPLATE_VERSION_TAG, templateVersion);
        tag.putInt(WORKER_TARGET_TAG, workerTarget);
        tag.putInt(CONFIGURATION_REVISION_TAG, configurationRevision);
        tag.putString(WORKSITE_ASSIGNMENT_MODE_TAG, worksiteAssignmentMode.serializedName());
        return tag;
    }

    public CompoundTag save(net.minecraft.core.HolderLookup.Provider registries) {
        var tag=save();
        if(WarehouseStock.applies(this))tag.put("WarehouseStock",warehouseStock.save(registries));
        return tag;
    }

    public static BuildingRecord load(CompoundTag tag) {
        boolean hasFunctionalData = tag.contains(STATUS_TAG);
        BlockPos position = BlockPos.of(tag.getLong(POSITION_TAG));
        Direction facing = Direction.byName(tag.getString(FACING_TAG));
        BuildingRecord loaded = new BuildingRecord(
                UUID.fromString(tag.getString(ID_TAG)),
                BuildingType.fromSerializedName(tag.getString(TYPE_TAG)),
                position,
                tag.getBoolean(ACTIVE_TAG),
                hasFunctionalData && tag.getBoolean(OPERATIONAL_TAG),
                hasFunctionalData
                        ? BuildingStatus.fromSerializedName(tag.getString(STATUS_TAG))
                        : BuildingStatus.PENDING_VALIDATION,
                hasFunctionalData ? tag.getInt(CAPACITY_TAG) : 0,
                Arrays.stream(tag.getLongArray(SERVICE_POSITIONS_TAG)).mapToObj(BlockPos::of).toList(),
                tag.getString(CUSTOM_NAME_TAG),
                facing == null ? Direction.NORTH : facing,
                tag.contains(MIN_CORNER_TAG) ? BlockPos.of(tag.getLong(MIN_CORNER_TAG)) : position,
                tag.contains(MAX_CORNER_TAG) ? BlockPos.of(tag.getLong(MAX_CORNER_TAG)) : position,
                tag.contains(TRANSITION_MARGIN_TAG) ? tag.getInt(TRANSITION_MARGIN_TAG) : 2,
                tag.getBoolean(DEMOLITION_QUEUED_TAG),
                tag.contains(WAREHOUSE_CATEGORY_MASK_TAG)
                        ? tag.getInt(WAREHOUSE_CATEGORY_MASK_TAG) : WarehouseCategory.allMask(),
                tag.contains(PRODUCTION_ID_TAG)
                        ? ResourceLocation.parse(tag.getString(PRODUCTION_ID_TAG))
                        : BuildingProductionService.defaultFor(BuildingType.fromSerializedName(tag.getString(TYPE_TAG))),
                Arrays.stream(tag.getLongArray(REPAIR_POSITIONS_TAG)).mapToObj(BlockPos::of)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                tag.contains(TEMPLATE_VERSION_TAG) ? tag.getInt(TEMPLATE_VERSION_TAG) : 1,
                tag.contains(WORKER_TARGET_TAG) ? tag.getInt(WORKER_TARGET_TAG) : 0,
                tag.getInt(CONFIGURATION_REVISION_TAG),
                tag.contains(WORKER_TARGET_TAG),
                tag.contains(WORKSITE_ASSIGNMENT_MODE_TAG)
                        ? WorksiteAssignmentMode.fromSerializedName(tag.getString(WORKSITE_ASSIGNMENT_MODE_TAG))
                        : WorksiteAssignmentMode.AUTO
        );
        if(tag.contains("ProductionSite"))loaded.withProductionSite(com.hkin.exiledsettlement.region.ProductionSiteSnapshot.load(tag.getCompound("ProductionSite")));
        else if(tag.contains("ProductionRegion"))loaded.withRegion(com.hkin.exiledsettlement.region.ProductionRegion.load(tag.getCompound("ProductionRegion")));
        if (tag.contains("CustomTemplate")) loaded.withCustomTemplate(CustomTemplateSnapshot.load(tag.getCompound("CustomTemplate")));
        if(tag.contains("WarehouseStock"))loaded.warehouseStock.load(tag.getCompound("WarehouseStock"));
        if(tag.contains("MineExpansion") && loaded.type()==BuildingType.MINE)loaded.mineExpansion.load(tag.getCompound("MineExpansion"));
        if (loaded.type() == BuildingType.CHURCH && tag.contains(BREW_STOCK_TARGET_TAG))
            loaded.brewStockTarget = Math.max(0, Math.min(MAX_BREW_STOCK_TARGET, tag.getInt(BREW_STOCK_TARGET_TAG)));
        return loaded;
    }

    /** Production membership can be L-shaped; height does not exempt its support columns. */
    public boolean containsColumn(BlockPos at) {
        if(productionSite!=null)return productionSite.containsColumn(at);
        if(region()!=null)return region().cell(at)!=null;
        return at.getX()>=minCorner().getX() && at.getX()<=maxCorner().getX()
                && at.getZ()>=minCorner().getZ() && at.getZ()<=maxCorner().getZ();
    }

    private static String normalizeName(String value) {
        if (value == null) return "";
        String normalized = value.strip();
        return normalized.substring(0, Math.min(32, normalized.length()));
    }
}
