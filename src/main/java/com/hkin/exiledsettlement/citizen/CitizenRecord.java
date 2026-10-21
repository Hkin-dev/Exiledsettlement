package com.hkin.exiledsettlement.citizen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EquipmentSlot;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

public final class CitizenRecord {
    private net.minecraft.world.item.Item constructionTool;
    private net.minecraft.world.level.block.state.BlockState constructionToolTarget;
    public net.minecraft.world.item.Item constructionTool() { return constructionTool; }
    public net.minecraft.world.level.block.state.BlockState constructionToolTarget() { return constructionToolTarget; }
    public void requestConstructionTool(net.minecraft.world.item.Item tool, net.minecraft.world.level.block.state.BlockState target) {
        constructionTool=tool; constructionToolTarget=target;
    }
    private static final String ID_TAG = "Id";
    private static final String NAME_TAG = "Name";
    private static final String ENTITY_ID_TAG = "EntityId";
    private static final String HOME_ID_TAG = "HomeId";
    private static final String BED_POSITION_TAG = "BedPosition";
    private static final String PROFESSION_TAG = "Profession";
    private static final String WORK_BUILDING_ID_TAG = "WorkBuildingId";
    private static final String MANUAL_PROFESSION_TAG = "ManualProfession";
    private static final String MANUAL_WORK_BUILDING_ID_TAG = "ManualWorkBuildingId";
    private static final String MANUAL_RELEASE_PENDING_TAG = "ManualReleasePending";
    private static final String PREFERRED_WORKSITE_ID_TAG = "PreferredWorksiteId";
    private static final String WORKSITE_RELEASE_PENDING_TAG = "WorksiteReleasePending";
    private static final String NUTRITION_TAG = "Nutrition";
    private static final String WARMTH_TAG = "Warmth";
    private static final String HEALTH_TAG = "Health";
    private static final String LAST_POSITION_TAG = "LastPosition";
    private static final String ALIVE_TAG = "Alive";
    private static final String WORK_STATE_TAG = "WorkState";
    private static final String SOURCE_BUILDING_ID_TAG = "SourceBuildingId";
    private static final String TARGET_BUILDING_ID_TAG = "TargetBuildingId";
    private static final String TARGET_ITEM_ENTITY_ID_TAG = "TargetItemEntityId";
    private static final String TARGET_POSITION_TAG = "TargetPosition";
    private static final String TARGET_BLUEPRINT_ID_TAG = "TargetBlueprintId";
    private static final String CARRIED_ITEM_TAG = "CarriedItem";
    private static final String INVENTORY_TAG = "Inventory";
    private static final String EQUIPMENT_TAG = "Equipment";
    /** Main hand refers to an inventory item; it is not a second stored stack. */
    public static final List<EquipmentSlot> EQUIPMENT_SLOTS = List.of(
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND);
    private static final String INVENTORY_SLOT_TAG = "Slot";
    private static final String INVENTORY_STACK_TAG = "Stack";
    private static final String ACTIVE_SLOT_TAG = "ActiveSlot";
    private static final String WORK_FAILURE_TAG = "WorkFailure";
    private static final String ACTIVITY_TAG = "Activity";
    private static final String YSM_MODEL_ID_TAG = "YsmModelId";
    private static final String YSM_TEXTURE_NAME_TAG = "YsmTextureName";
    public static final int MAX_YSM_MODEL_ID_LENGTH = 256;
    public static final int MAX_YSM_TEXTURE_NAME_LENGTH = 128;
    public static final int INVENTORY_SIZE = 36;

    private final UUID id;
    private String name;
    @Nullable
    private UUID entityId;
    @Nullable
    private UUID homeBuildingId;
    @Nullable
    private BlockPos bedPosition;
    private CitizenProfession profession;
    @Nullable
    private UUID workBuildingId;
    @Nullable
    private CitizenProfession manualProfession;
    @Nullable
    private UUID manualWorkBuildingId;
    private boolean manualReleasePending;
    @Nullable
    private UUID preferredWorksiteId;
    private boolean worksiteReleasePending;
    private int nutrition;
    private int warmth;
    private int health;
    private BlockPos lastPosition;
    private boolean alive;
    private CitizenWorkState workState;
    @Nullable
    private UUID sourceBuildingId;
    @Nullable
    private UUID targetBuildingId;
    @Nullable
    private UUID targetItemEntityId;
    @Nullable
    private BlockPos targetPosition;
    @Nullable
    private UUID targetBlueprintId;
    public final WorkerAccessService.State workerAccess=new WorkerAccessService.State();
    private UUID constructionResumeId;
    private BlockPos constructionResumePosition;
    public UUID constructionResumeId(){return constructionResumeId;}
    public BlockPos constructionResumePosition(){return constructionResumePosition;}
    public void setConstructionResume(UUID id,BlockPos at){constructionResumeId=id;constructionResumePosition=at==null?null:at.immutable();}
    private CitizenProfessionExperience professionExperience;
    public CitizenProfessionExperience professionExperience() {
        if (professionExperience == null) professionExperience = new CitizenProfessionExperience();
        return professionExperience;
    }
    private UUID motherTaskId;
    private UUID roadTaskId;
    public UUID roadTaskId(){return roadTaskId;}
    public void setRoadTaskId(UUID id){roadTaskId=id;}
    public UUID motherTaskId(){return motherTaskId;}
    public void setMotherTask(UUID id){motherTaskId=id;}
    private final NonNullList<ItemStack> inventory;
    private int pitReserveSlot = -1;
    public final PitEscapeService.State pitEscape = new PitEscapeService.State();
    public final PastureBreedingService.State pastureJob = new PastureBreedingService.State();
    public boolean pitReserveSlot(int slot) { return slot >= 0 && slot == pitReserveSlot; }
    public ItemStack pitReserve() { return inventoryItem(pitReserveSlot); }
    /** Moves actual backpack stock into one protected slot; never creates blocks. */
    public int reservePitBlocks(Predicate<ItemStack> ordinary, int maximum) {
        if (profession != CitizenProfession.WORKER) return 0;
        if (pitReserveSlot >= 0 && !inventory.get(pitReserveSlot).isEmpty() && !ordinary.test(inventory.get(pitReserveSlot))) pitReserveSlot = -1;
        if (pitReserveSlot < 0) {
            for (int slot = INVENTORY_SIZE - 1; slot >= 0; slot--) if (inventory.get(slot).isEmpty()) {
                pitReserveSlot = slot; break;
            }
        }
        if (pitReserveSlot < 0) return 0;
        int moved = 0;
        while (inventory.get(pitReserveSlot).getCount() < maximum) {
            var reserve = inventory.get(pitReserveSlot);
            var taken = extractInventoryItem(stack -> ordinary.test(stack)
                    && (reserve.isEmpty() || ItemStack.isSameItemSameComponents(stack, reserve)),
                    maximum - reserve.getCount());
            if (taken.isEmpty()) break;
            if (reserve.isEmpty()) inventory.set(pitReserveSlot, taken);
            else reserve.grow(taken.getCount());
            moved += taken.getCount();
        }
        return moved;
    }
    public ItemStack takePitBlock() {
        return pitReserveSlot < 0 ? ItemStack.EMPTY : inventory.get(pitReserveSlot).split(1);
    }
    public void refundPitBlock(ItemStack stack) {
        if (stack.isEmpty()) return;
        if (pitReserveSlot >= 0 && inventory.get(pitReserveSlot).isEmpty()) inventory.set(pitReserveSlot, stack.copy());
        else if (pitReserveSlot >= 0 && ItemStack.isSameItemSameComponents(inventory.get(pitReserveSlot), stack)) inventory.get(pitReserveSlot).grow(stack.getCount());
        else insertIntoInventory(stack);
    }
    private final java.util.EnumMap<EquipmentSlot, ItemStack> equipment = new java.util.EnumMap<>(EquipmentSlot.class);
    private int activeSlot;
    private String workFailure;
    private CitizenActivity activity;
    private String ysmModelId = "";
    private String ysmTextureName = "";
    private final CitizenLife life = new CitizenLife();
    private final CitizenThermalState thermal = new CitizenThermalState();
    public CitizenThermalState thermal() { return thermal; }

    public CitizenLife life() { return life; }
    public void setActivity(CitizenActivity activity, String failure) {
        updateActivity(activity); updateWorkFailure(failure);
    }
    public boolean canWork() { return alive && life.adult(com.hkin.exiledsettlement.settlement.CommunityContent.adulthoodDays); }
    public void adjustNeeds(int food, int heat, int condition) {
        nutrition = Math.max(0, Math.min(100, nutrition + food));
        warmth = Math.max(0, Math.min(100, warmth + heat));
        health = Math.max(0, Math.min(100, health + condition));
    }

    public CitizenRecord(UUID id, String name, BlockPos lastPosition) {
        this(id, name, null, null, null, CitizenProfession.WORKER, null, null, null, null,
                100, 100, 100, lastPosition, true,
                CitizenWorkState.IDLE, null, null, null, null, null,
                List.of(), -1, "", CitizenActivity.IDLE);
    }

    private CitizenRecord(
            UUID id,
            String name,
            @Nullable UUID entityId,
            @Nullable UUID homeBuildingId,
            @Nullable BlockPos bedPosition,
            CitizenProfession profession,
            @Nullable UUID workBuildingId,
            @Nullable CitizenProfession manualProfession,
            @Nullable UUID manualWorkBuildingId,
            @Nullable UUID preferredWorksiteId,
            int nutrition,
            int warmth,
            int health,
            BlockPos lastPosition,
            boolean alive,
            CitizenWorkState workState,
            @Nullable UUID sourceBuildingId,
            @Nullable UUID targetBuildingId,
            @Nullable UUID targetItemEntityId,
            @Nullable BlockPos targetPosition,
            @Nullable UUID targetBlueprintId,
            List<ItemStack> inventory,
            int activeSlot,
            String workFailure,
            CitizenActivity activity
    ) {
        this.id = id;
        this.name = name;
        this.entityId = entityId;
        this.homeBuildingId = homeBuildingId;
        this.bedPosition = bedPosition == null ? null : bedPosition.immutable();
        this.profession = profession;
        this.workBuildingId = workBuildingId;
        this.manualProfession = manualProfession;
        this.manualWorkBuildingId = manualProfession != null && manualProfession.specialist() ? manualWorkBuildingId : null;
        this.manualReleasePending = false;
        this.preferredWorksiteId = preferredWorksiteId;
        this.nutrition = nutrition;
        this.warmth = warmth;
        this.health = health;
        this.lastPosition = lastPosition.immutable();
        this.alive = alive;
        this.workState = workState;
        this.sourceBuildingId = sourceBuildingId;
        this.targetBuildingId = targetBuildingId;
        this.targetItemEntityId = targetItemEntityId;
        this.targetPosition = targetPosition == null ? null : targetPosition.immutable();
        this.targetBlueprintId = targetBlueprintId;
        // Older forestry tasks stored their tree-farm id in the blueprint slot.
        // This correction is specific to forestry and reuses existing save fields.
        if (this.workState == CitizenWorkState.TO_FOREST && this.targetBuildingId == null
                && this.targetBlueprintId != null) {
            this.targetBuildingId = this.targetBlueprintId;
            this.targetBlueprintId = null;
        }
        this.inventory = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
        for (int slot = 0; slot < Math.min(INVENTORY_SIZE, inventory.size()); slot++) {
            this.inventory.set(slot, inventory.get(slot).copy());
        }
        this.activeSlot = activeSlot >= 0 && activeSlot < INVENTORY_SIZE
                && !this.inventory.get(activeSlot).isEmpty() ? activeSlot : -1;
        this.workFailure = workFailure;
        this.activity = alive ? activity : CitizenActivity.DEAD;
        if (this.workState == CitizenWorkState.IDLE) {
            this.sourceBuildingId = null;
            this.targetBuildingId = null;
            this.targetItemEntityId = null;
            this.targetPosition = null;
            this.targetBlueprintId = null;
            this.activeSlot = -1;
            this.workFailure = "";
            if (alive) this.activity = CitizenActivity.IDLE;
        }
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public boolean rename(String requestedName) {
        String normalized = requestedName.strip();
        if (normalized.isEmpty() || normalized.length() > 64
                || normalized.codePoints().anyMatch(Character::isISOControl)) return false;
        name = normalized;
        return true;
    }

    public String ysmModelId() {
        return ysmModelId;
    }

    public String ysmTextureName() {
        return ysmTextureName;
    }

    public boolean usesYsmModel() {
        return !ysmModelId.isBlank();
    }

    public static boolean isValidYsmSelection(String modelId, String textureName) {
        return validYsmValue(modelId, MAX_YSM_MODEL_ID_LENGTH)
                && validYsmValue(textureName, MAX_YSM_TEXTURE_NAME_LENGTH);
    }

    public boolean setYsmModel(String requestedModelId, String requestedTextureName) {
        if (!isValidYsmSelection(requestedModelId, requestedTextureName)) return false;
        String model = requestedModelId.strip();
        String texture = model.isEmpty() ? "" : requestedTextureName.strip();
        if (ysmModelId.equals(model) && ysmTextureName.equals(texture)) return false;
        ysmModelId = model;
        ysmTextureName = texture;
        return true;
    }

    private static boolean validYsmValue(String value, int maximumLength) {
        return value != null && value.length() <= maximumLength
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    @Nullable
    public UUID entityId() {
        return entityId;
    }

    @Nullable
    public UUID homeBuildingId() {
        return homeBuildingId;
    }

    @Nullable
    public BlockPos bedPosition() {
        return bedPosition;
    }

    public CitizenProfession profession() {
        return profession;
    }

    @Nullable
    public UUID workBuildingId() {
        return workBuildingId;
    }

    @Nullable public CitizenProfession manualProfession() { return manualProfession; }
    @Nullable public UUID manualWorkBuildingId() { return manualWorkBuildingId; }
    public boolean hasManualAssignment() { return manualProfession != null; }
    public boolean manualReleasePending() { return manualReleasePending; }
    @Nullable public UUID preferredWorksiteId() { return preferredWorksiteId; }
    public boolean worksiteReleasePending() { return worksiteReleasePending; }

    public boolean requestWorksiteRelease() {
        if (preferredWorksiteId == null || worksiteReleasePending) return false;
        worksiteReleasePending = true;
        return true;
    }

    public boolean cancelWorksiteRelease() {
        if (!worksiteReleasePending) return false;
        worksiteReleasePending = false;
        return true;
    }

    public boolean setPreferredWorksite(@Nullable UUID worksiteId) {
        if (Objects.equals(preferredWorksiteId, worksiteId)) return false;
        preferredWorksiteId = worksiteId;
        worksiteReleasePending = false;
        return true;
    }

    public int nutrition() {
        return nutrition;
    }

    public int warmth() {
        return Math.clamp(Math.round((thermal.body-32)*20),0,100);
    }

    public int health() {
        return health;
    }

    public BlockPos lastPosition() {
        return lastPosition;
    }

    public boolean alive() {
        return alive;
    }

    public CitizenWorkState workState() {
        return workState;
    }

    @Nullable
    public UUID sourceBuildingId() {
        return sourceBuildingId;
    }

    @Nullable
    public UUID targetBuildingId() {
        return targetBuildingId;
    }

    @Nullable
    public UUID targetItemEntityId() {
        return targetItemEntityId;
    }

    @Nullable
    public BlockPos targetPosition() {
        return targetPosition;
    }

    @Nullable
    public UUID targetBlueprintId() {
        return targetBlueprintId;
    }

    public ItemStack carriedItem() {
        return activeSlot >= 0 && activeSlot < INVENTORY_SIZE
                ? inventory.get(activeSlot).copy() : ItemStack.EMPTY;
    }

    public int activeSlot() { return activeSlot; }

    public List<ItemStack> inventory() {
        return inventory.stream().map(ItemStack::copy).toList();
    }

    public ItemStack equipmentItem(EquipmentSlot slot) {
        return equipment.getOrDefault(slot, ItemStack.EMPTY).copy();
    }

    /** Used by the authoritative equipment damage path, not by client intents. */
    public boolean updateEquipment(EquipmentSlot slot, ItemStack stack) {
        if (!EQUIPMENT_SLOTS.contains(slot)) return false;
        ItemStack previous = equipment.getOrDefault(slot, ItemStack.EMPTY);
        if (ItemStack.matches(previous, stack)) return false;
        equipment.put(slot, stack.copy());
        return true;
    }

    public static EquipmentSlot wearableSlot(ItemStack stack) {
        if (stack.getItem() instanceof ArmorItem armor && EQUIPMENT_SLOTS.contains(armor.getEquipmentSlot())) {
            return armor.getEquipmentSlot();
        }
        return stack.is(Items.ELYTRA) ? EquipmentSlot.CHEST : stack.is(Items.SHIELD) ? EquipmentSlot.OFFHAND : null;
    }

    /** Moves, never copies, one item per empty wearable slot out of the work backpack. */
    public boolean equipAvailableInventory() {
        boolean changed = false;
        for (EquipmentSlot equipmentSlot : EQUIPMENT_SLOTS) {
            if (!equipmentItem(equipmentSlot).isEmpty()) continue;
            int best = -1;
            for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
                ItemStack candidate = inventory.get(slot);
                if (candidate.isEmpty() || wearableSlot(candidate) != equipmentSlot) continue;
                if (com.hkin.exiledsettlement.settlement.PriestProductionService.reservedCount(this, slot) >= candidate.getCount()) continue;
                if (best < 0 || compareEquipment(candidate, inventory.get(best)) > 0) best = slot;
            }
            if (best < 0) continue;
            equipment.put(equipmentSlot, inventory.get(best).split(1));
            if (inventory.get(best).isEmpty() && activeSlot == best) activeSlot = -1;
            changed = true;
        }
        return changed;
    }

    private static int compareEquipment(ItemStack first, ItemStack second) {
        int firstDefense = first.getItem() instanceof ArmorItem armor ? armor.getDefense() : 0;
        int secondDefense = second.getItem() instanceof ArmorItem armor ? armor.getDefense() : 0;
        int result = Integer.compare(firstDefense, secondDefense);
        if (result != 0) return result;
        float firstToughness = first.getItem() instanceof ArmorItem armor ? armor.getToughness() : 0;
        float secondToughness = second.getItem() instanceof ArmorItem armor ? armor.getToughness() : 0;
        result = Float.compare(firstToughness, secondToughness);
        if (result != 0) return result;
        return Integer.compare(first.getMaxDamage() - first.getDamageValue(), second.getMaxDamage() - second.getDamageValue());
    }

    public int equipmentCapacityFor(ItemStack offered) {
        EquipmentSlot slot = wearableSlot(offered);
        return (slot != null && equipmentItem(slot).isEmpty() ? 1 : 0) + inventoryCapacityFor(offered);
    }

    public ItemStack receiveEquipment(ItemStack offered) {
        ItemStack remaining = offered.copy();
        EquipmentSlot slot = wearableSlot(remaining);
        if (!remaining.isEmpty() && slot != null && equipmentItem(slot).isEmpty()) {
            equipment.put(slot, remaining.split(1));
        }
        return insertIntoInventory(remaining);
    }

    public ItemStack inventoryItem(int slot) {
        return slot >= 0 && slot < INVENTORY_SIZE ? inventory.get(slot).copy() : ItemStack.EMPTY;
    }

    public boolean hasInventoryItem(Predicate<ItemStack> predicate) {
        return findInventorySlot(predicate) >= 0;
    }

    public int findInventorySlot(Predicate<ItemStack> predicate) {
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            if (pitReserveSlot(slot)) continue;
            ItemStack stack = inventory.get(slot);
            if (!stack.isEmpty() && predicate.test(stack)) return slot;
        }
        return -1;
    }

    public ItemStack insertIntoInventory(ItemStack offered) {
        if (offered.isEmpty()) return ItemStack.EMPTY;
        ItemStack remaining = offered.copy();
        for (int slot = 0; slot < INVENTORY_SIZE && !remaining.isEmpty(); slot++) {
            if (pitReserveSlot(slot)) continue;
            ItemStack existing = inventory.get(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, remaining)) continue;
            int moved = Math.min(existing.getMaxStackSize() - existing.getCount(), remaining.getCount());
            if (moved > 0) {
                existing.grow(moved);
                remaining.shrink(moved);
            }
        }
        for (int slot = 0; slot < INVENTORY_SIZE && !remaining.isEmpty(); slot++) {
            if (pitReserveSlot(slot)) continue;
            if (!inventory.get(slot).isEmpty()) continue;
            int moved = Math.min(remaining.getMaxStackSize(), remaining.getCount());
            inventory.set(slot, remaining.copyWithCount(moved));
            remaining.shrink(moved);
        }
        return remaining;
    }

    public boolean hasInventoryCapacity() {
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            if (pitReserveSlot(slot)) continue;
            var stack = inventory.get(slot);
            if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) return true;
        }
        return false;
    }

    /** Merge equal component stacks without losing stock or changing the selected item. */
    public boolean compactInventory() {
        boolean changed=false;
        for(int to=0;to<INVENTORY_SIZE;to++){
            if(pitReserveSlot(to))continue;
            var target=inventory.get(to);
            if(target.isEmpty() || target.getCount()>=target.getMaxStackSize())continue;
            for(int from=to+1;from<INVENTORY_SIZE && target.getCount()<target.getMaxStackSize();from++){
                if(pitReserveSlot(from))continue;
                var source=inventory.get(from);
                if(source.isEmpty() || !ItemStack.isSameItemSameComponents(target,source))continue;
                int moved=Math.min(target.getMaxStackSize()-target.getCount(),source.getCount());
                target.grow(moved);source.shrink(moved);changed=true;
                if(source.isEmpty()){
                    inventory.set(from,ItemStack.EMPTY);
                    if(activeSlot==from)activeSlot=to;
                }
            }
        }
        return changed;
    }

    public int inventoryCapacityFor(ItemStack offered) {
        if (offered.isEmpty()) return 0;
        long capacity = 0L;
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            if (pitReserveSlot(slot)) continue;
            var existing = inventory.get(slot);
            if (existing.isEmpty()) {
                capacity += offered.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(existing, offered)) {
                capacity += Math.max(0, existing.getMaxStackSize() - existing.getCount());
            }
        }
        return (int)Math.min(Integer.MAX_VALUE, capacity);
    }

    public ItemStack extractInventoryItem(Predicate<ItemStack> predicate, int count) {
        if (count <= 0) return ItemStack.EMPTY;
        int slot = findInventorySlot(predicate);
        if (slot < 0) return ItemStack.EMPTY;
        ItemStack extracted = inventory.get(slot).split(Math.min(count, inventory.get(slot).getCount()));
        if (inventory.get(slot).isEmpty() && activeSlot == slot) activeSlot = -1;
        return extracted;
    }

    public boolean selectActiveSlot(int slot) {
        int normalized = slot >= 0 && slot < INVENTORY_SIZE && !inventory.get(slot).isEmpty() ? slot : -1;
        if (activeSlot == normalized) return false;
        activeSlot = normalized;
        return true;
    }

    public ItemStack removeCarriedItem() {
        if (activeSlot < 0 || activeSlot >= INVENTORY_SIZE) return ItemStack.EMPTY;
        ItemStack removed = inventory.get(activeSlot).copy();
        inventory.set(activeSlot, ItemStack.EMPTY);
        activeSlot = -1;
        return removed;
    }

    public boolean damageInventoryItem(int slot, int amount) {
        if (slot < 0 || slot >= INVENTORY_SIZE || amount <= 0) return false;
        ItemStack stack = inventory.get(slot);
        if (stack.isEmpty() || !stack.isDamageableItem()) return false;
        int damage = stack.getDamageValue() + amount;
        if (damage >= stack.getMaxDamage()) {
            stack.shrink(1);
            if (stack.isEmpty()) inventory.set(slot, ItemStack.EMPTY);
            if (activeSlot == slot && inventory.get(slot).isEmpty()) activeSlot = -1;
        } else {
            stack.setDamageValue(damage);
        }
        return true;
    }

    public String workFailure() {
        return workFailure;
    }

    public CitizenActivity activity() {
        return activity;
    }

    public boolean startContainerHaul(UUID sourceId, UUID targetId) {
        if (workState != CitizenWorkState.IDLE || activeSlot >= 0) {
            return false;
        }
        workState = CitizenWorkState.TO_OUTPUT_CONTAINER;
        sourceBuildingId = sourceId;
        targetBuildingId = targetId;
        targetItemEntityId = null;
        workFailure = "";
        activity = CitizenActivity.TO_OUTPUT_CONTAINER;
        return true;
    }

    public boolean startTask(CitizenWorkState state, BlockPos position, @Nullable UUID entityId, @Nullable UUID blueprintId) {
        if (workState != CitizenWorkState.IDLE || state == CitizenWorkState.IDLE) return false;
        workState = state;
        targetPosition = position.immutable();
        targetItemEntityId = entityId;
        targetBlueprintId = blueprintId;
        workFailure = "";
        activity = switch (state) {
            case TO_EQUIPMENT -> CitizenActivity.FETCHING_EQUIPMENT;
            case TO_ROAD_TOOL -> CitizenActivity.FETCHING_EQUIPMENT;
            case TO_ROAD -> CitizenActivity.ROAD_BUILDING;
            case TO_FARM -> CitizenActivity.FARMING;
            case TO_FARM_SUPPLY -> CitizenActivity.FETCHING_FARM_INPUT;
            case TO_BLUEPRINT -> CitizenActivity.BUILDING;
            case TO_REPAIR_SUPPLY -> CitizenActivity.FETCHING_REPAIR_MATERIAL;
            case TO_REPAIR -> CitizenActivity.REPAIRING;
            case TO_DEMOLITION -> CitizenActivity.DEMOLISHING;
            case TO_HERD -> CitizenActivity.HERDING;
            case TO_PASTURE_SUPPLY -> CitizenActivity.FETCHING_FARM_INPUT;
            case TO_PASTURE_FEED -> CitizenActivity.HERDING;
            case TO_HUNT -> CitizenActivity.HUNTING;
            case TO_CAPTURE -> CitizenActivity.CAPTURING;
            case TO_TOOL_WORKSHOP -> CitizenActivity.TOOLMAKING;
            case TO_FOREST -> CitizenActivity.FORESTRY;
            case TO_FISH -> CitizenActivity.FISHING;
            case TO_MINE -> CitizenActivity.MINING;
            case TO_MINE_STORAGE -> CitizenActivity.TO_WAREHOUSE;
            case TO_CONSTRUCTION_SUPPLY -> CitizenActivity.FETCHING_BUILD_MATERIAL;
            case TO_FOOTING_SUPPLY -> CitizenActivity.FETCHING_BUILD_MATERIAL;
            case TO_SAWMILL_SUPPLY -> CitizenActivity.FETCHING_SAWMILL_INPUT;
            case TO_SAWMILL -> CitizenActivity.SAWING;
            case TO_PUBLIC_ITEM -> CitizenActivity.COLLECTING_PUBLIC_ITEM;
            case TO_OUTPUT_CONTAINER -> CitizenActivity.TO_OUTPUT_CONTAINER;
            case TO_WAREHOUSE -> CitizenActivity.TO_WAREHOUSE;
            case IDLE -> CitizenActivity.IDLE;
        };
        return true;
    }

    public boolean startEquipmentFetch(@Nullable UUID warehouseId, BlockPos position) {
        if (workState != CitizenWorkState.IDLE) return false;
        workState = CitizenWorkState.TO_EQUIPMENT;
        targetBuildingId = warehouseId;
        targetPosition = position.immutable();
        workFailure = "";
        activity = CitizenActivity.FETCHING_EQUIPMENT;
        return true;
    }


    public boolean startForestry(UUID treeFarmId, BlockPos position) {
        if (!startTask(CitizenWorkState.TO_FOREST, position, null, null)) return false;
        targetBuildingId = treeFarmId;
        return true;
    }

    /** Toolmaking retains its existing work state; the optional building target is its current supply source. */
    public boolean seekToolmakingSupply(@Nullable UUID warehouseId, BlockPos supplyPosition) {
        if (workState != CitizenWorkState.TO_TOOL_WORKSHOP) return false;
        boolean changed = !Objects.equals(targetBuildingId, warehouseId)
                || !Objects.equals(targetPosition, supplyPosition)
                || activity != CitizenActivity.FETCHING_TOOLMAKING_INPUT || !workFailure.isEmpty();
        targetBuildingId = warehouseId;
        targetPosition = supplyPosition.immutable();
        activity = CitizenActivity.FETCHING_TOOLMAKING_INPUT;
        workFailure = "";
        return changed;
    }

    public boolean returnToToolmaking(BlockPos workshopPosition) {
        if (workState != CitizenWorkState.TO_TOOL_WORKSHOP) return false;
        boolean changed = targetBuildingId != null || !Objects.equals(targetPosition, workshopPosition)
                || activity != CitizenActivity.TOOLMAKING || !workFailure.isEmpty();
        targetBuildingId = null;
        targetPosition = workshopPosition.immutable();
        activity = CitizenActivity.TOOLMAKING;
        workFailure = "";
        return changed;
    }

    public boolean fetchFarmInput(@Nullable UUID warehouseId) {
        if (workState != CitizenWorkState.TO_FARM || targetPosition == null) return false;
        workState = CitizenWorkState.TO_FARM_SUPPLY;
        targetBuildingId = warehouseId;
        workFailure = "";
        activity = CitizenActivity.FETCHING_FARM_INPUT;
        return true;
    }

    public boolean returnToFarm() {
        if (workState != CitizenWorkState.TO_FARM_SUPPLY || targetPosition == null) return false;
        workState = CitizenWorkState.TO_FARM;
        targetBuildingId = null;
        workFailure = "";
        activity = CitizenActivity.FARMING;
        return true;
    }

    public boolean startSawmillSupply(UUID warehouseId, BlockPos millPosition) {
        if (workState != CitizenWorkState.IDLE || warehouseId == null) return false;
        workState = CitizenWorkState.TO_SAWMILL_SUPPLY;
        targetBuildingId = warehouseId;
        targetPosition = millPosition.immutable();
        targetItemEntityId = null;
        targetBlueprintId = null;
        workFailure = "";
        activity = CitizenActivity.FETCHING_SAWMILL_INPUT;
        return true;
    }

    public boolean returnToSawmill() {
        if (workState != CitizenWorkState.TO_SAWMILL_SUPPLY || targetPosition == null) return false;
        workState = CitizenWorkState.TO_SAWMILL;
        targetBuildingId = null;
        workFailure = "";
        activity = CitizenActivity.SAWING;
        return true;
    }

    public boolean startDemolition(UUID buildingId, BlockPos position) {
        if (workState != CitizenWorkState.IDLE) return false;
        workState = CitizenWorkState.TO_DEMOLITION;
        targetBuildingId = buildingId;
        targetPosition = position.immutable();
        workFailure = "";
        activity = CitizenActivity.DEMOLISHING;
        return true;
    }

    public boolean continueWithBlueprintCleanup(UUID blueprintId, BlockPos position) {
        if (workState != CitizenWorkState.TO_BLUEPRINT
                || !blueprintId.equals(targetBlueprintId)) return false;
        workState = CitizenWorkState.TO_DEMOLITION;
        targetBuildingId = null;
        targetItemEntityId = null;
        targetPosition = position.immutable();
        workFailure = "";
        activity = CitizenActivity.DEMOLISHING;
        return true;
    }

    public boolean startRepair(@Nullable UUID buildingId, BlockPos position) {
        if (workState != CitizenWorkState.IDLE) return false;
        workState = CitizenWorkState.TO_REPAIR;
        targetBuildingId = buildingId;
        targetPosition = position.immutable();
        workFailure = "";
        activity = CitizenActivity.REPAIRING;
        return true;
    }

    public boolean fetchRepairMaterial(@Nullable UUID warehouseId) {
        if (workState != CitizenWorkState.TO_REPAIR || targetPosition == null) return false;
        sourceBuildingId = targetBuildingId;
        targetBuildingId = warehouseId;
        workState = CitizenWorkState.TO_REPAIR_SUPPLY;
        workFailure = "";
        activity = CitizenActivity.FETCHING_REPAIR_MATERIAL;
        return true;
    }

    public boolean returnToRepair(ItemStack material) {
        if (workState != CitizenWorkState.TO_REPAIR_SUPPLY || targetPosition == null || material.isEmpty()) return false;
        if (!setActiveItem(material)) return false;
        return returnToRepair();
    }

    public boolean returnToRepair() {
        if (workState != CitizenWorkState.TO_REPAIR_SUPPLY || targetPosition == null
                || activeSlot < 0 || activeSlot >= INVENTORY_SIZE || inventory.get(activeSlot).isEmpty()) return false;
        targetBuildingId = sourceBuildingId;
        sourceBuildingId = null;
        workState = CitizenWorkState.TO_REPAIR;
        workFailure = "";
        activity = CitizenActivity.REPAIRING;
        return true;
    }

    public boolean startCapture(UUID pastureId, BlockPos pasturePosition, UUID animalId) {
        if (workState != CitizenWorkState.IDLE) return false;
        workState = CitizenWorkState.TO_CAPTURE;
        targetBuildingId = pastureId;
        targetPosition = pasturePosition.immutable();
        targetItemEntityId = animalId;
        workFailure = "";
        activity = CitizenActivity.CAPTURING;
        return true;
    }

    public boolean fetchConstructionMaterial(@Nullable UUID warehouseId) {
        if (targetBlueprintId == null || targetPosition == null) return false;
        workState = CitizenWorkState.TO_CONSTRUCTION_SUPPLY;
        targetBuildingId = warehouseId;
        workFailure = "";
        activity = CitizenActivity.FETCHING_BUILD_MATERIAL;
        return true;
    }

    public boolean fetchFootingMaterial(@Nullable UUID warehouseId) {
        if (targetBlueprintId == null || targetPosition == null) return false;
        workState = CitizenWorkState.TO_FOOTING_SUPPLY;
        targetBuildingId = warehouseId;
        workFailure = "";
        activity = CitizenActivity.FETCHING_BUILD_MATERIAL;
        return true;
    }

    public boolean returnToBlueprint(ItemStack material) {
        if (targetBlueprintId == null || targetPosition == null || material.isEmpty()) return false;
        if (!setActiveItem(material)) return false;
        return returnToBlueprint();
    }

    public boolean returnToBlueprint() {
        if (targetBlueprintId == null || targetPosition == null
                || activeSlot < 0 || activeSlot >= INVENTORY_SIZE || inventory.get(activeSlot).isEmpty()) return false;
        workState = CitizenWorkState.TO_BLUEPRINT;
        activity = CitizenActivity.BUILDING;
        workFailure = "";
        return true;
    }

    public boolean markPickedUp(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (!setActiveItem(stack)) return false;
        workState = CitizenWorkState.TO_WAREHOUSE;
        targetItemEntityId = null;
        targetPosition = null;
        targetBlueprintId = null;
        workFailure = "";
        activity = CitizenActivity.TO_WAREHOUSE;
        return true;
    }

    public boolean markInventoryPickedUp(int slot) {
        if (slot < 0 || slot >= INVENTORY_SIZE || inventory.get(slot).isEmpty()) return false;
        activeSlot = slot;
        workState = CitizenWorkState.TO_WAREHOUSE;
        targetItemEntityId = null;
        targetPosition = null;
        targetBlueprintId = null;
        workFailure = "";
        activity = CitizenActivity.TO_WAREHOUSE;
        return true;
    }

    public boolean updateCarriedItem(ItemStack stack) {
        ItemStack current = carriedItem();
        if (ItemStack.matches(current, stack)) return false;
        if (activeSlot >= 0 && activeSlot < INVENTORY_SIZE) {
            inventory.set(activeSlot, stack.copy());
            if (stack.isEmpty()) activeSlot = -1;
            return true;
        }
        return !stack.isEmpty() && setActiveItem(stack);
    }

    public boolean startInventoryDelivery(UUID warehouseId, int slot) {
        if (workState != CitizenWorkState.IDLE || slot < 0 || slot >= INVENTORY_SIZE
                || inventory.get(slot).isEmpty()) return false;
        targetBuildingId = warehouseId;
        activeSlot = slot;
        workState = CitizenWorkState.TO_WAREHOUSE;
        workFailure = "";
        activity = CitizenActivity.TO_WAREHOUSE;
        return true;
    }

    public boolean updateWorkFailure(String failure) {
        String normalized = failure == null ? "" : failure;
        if (workFailure.equals(normalized)) {
            return false;
        }
        workFailure = normalized;
        return true;
    }

    public boolean updateTargetPosition(@Nullable BlockPos position) {
        BlockPos normalized = position == null ? null : position.immutable();
        if (Objects.equals(targetPosition, normalized)) return false;
        targetPosition = normalized;
        return true;
    }

    public boolean updateActivity(CitizenActivity newActivity) {
        if (activity == newActivity) {
            return false;
        }
        activity = newActivity;
        return true;
    }

    public boolean clearWork() {
        boolean changed = workState != CitizenWorkState.IDLE
                || pastureJob.active()
                || sourceBuildingId != null
                || targetBuildingId != null
                || targetItemEntityId != null
                || targetPosition != null
                || targetBlueprintId != null
                || activeSlot >= 0
                || !workFailure.isEmpty();
        workState = CitizenWorkState.IDLE;
        pastureJob.clear();
        motherTaskId = null;
        roadTaskId = null;
        constructionResumeId=null;constructionResumePosition=null;workerAccess.clear();
        sourceBuildingId = null;
        targetBuildingId = null;
        targetItemEntityId = null;
        targetPosition = null;
        targetBlueprintId = null;
        activeSlot = -1;
        workFailure = "";
        changed |= activity != CitizenActivity.IDLE;
        activity = CitizenActivity.IDLE;
        return changed;
    }

    /** A breeding stage changes without cancelling its animal and food reservation. */
    public void advancePastureTask(CitizenWorkState state, BlockPos position, @Nullable UUID animalId) {
        if (state != CitizenWorkState.TO_PASTURE_SUPPLY && state != CitizenWorkState.TO_PASTURE_FEED) {
            throw new IllegalArgumentException("Not a pasture task");
        }
        workState = state;
        targetPosition = position.immutable();
        targetItemEntityId = animalId;
        activity = state == CitizenWorkState.TO_PASTURE_SUPPLY
                ? CitizenActivity.FETCHING_FARM_INPUT : CitizenActivity.HERDING;
        workFailure = "";
    }

    public boolean updateProjection(UUID newEntityId, BlockPos newPosition) {
        boolean changed = !Objects.equals(entityId, newEntityId) || !lastPosition.equals(newPosition);
        entityId = newEntityId;
        lastPosition = newPosition.immutable();
        return changed;
    }

    public boolean assignHome(@Nullable UUID buildingId) {
        return assignHome(buildingId, null);
    }

    public boolean assignHome(@Nullable UUID buildingId, @Nullable BlockPos newBedPosition) {
        BlockPos normalizedBed = buildingId == null || newBedPosition == null ? null : newBedPosition.immutable();
        if (Objects.equals(homeBuildingId, buildingId) && Objects.equals(bedPosition, normalizedBed)) {
            return false;
        }
        homeBuildingId = buildingId;
        bedPosition = normalizedBed;
        return true;
    }

    public void redirectBlueprint(UUID from,UUID to){if(from.equals(targetBlueprintId))targetBlueprintId=to;if(from.equals(preferredWorksiteId))preferredWorksiteId=to;}
    public boolean redirectWorkplace(UUID from,UUID to) {
        boolean changed=false;
        if(from.equals(workBuildingId)){workBuildingId=to;changed=true;}
        if(from.equals(preferredWorksiteId)){preferredWorksiteId=to;changed=true;}
        if(from.equals(manualWorkBuildingId)){manualWorkBuildingId=to;changed=true;}
        return changed;
    }
    public boolean assignProfession(CitizenProfession newProfession, @Nullable UUID newWorkBuildingId) {
        UUID normalizedWorkplace = newProfession.specialist() ? newWorkBuildingId : null;
        boolean changed = profession != newProfession || !Objects.equals(workBuildingId, normalizedWorkplace);
        profession = newProfession;
        workBuildingId = normalizedWorkplace;
        if (changed) {
            clearWork();
        }
        return changed;
    }

    public boolean setManualAssignment(CitizenProfession newProfession, @Nullable UUID newWorkBuildingId) {
        if (!canWork()) return false;
        UUID normalized = newProfession.specialist() ? newWorkBuildingId : null;
        boolean changed = manualProfession != newProfession || !Objects.equals(manualWorkBuildingId, normalized)
                || manualReleasePending;
        manualProfession = newProfession;
        manualWorkBuildingId = normalized;
        manualReleasePending = false;
        changed |= assignProfession(newProfession, normalized);
        return changed;
    }

    public boolean clearManualAssignment() {
        if (manualProfession == null && manualWorkBuildingId == null && !manualReleasePending) return false;
        manualProfession = null;
        manualWorkBuildingId = null;
        manualReleasePending = false;
        return true;
    }

    /**
     * Records the player's unlock intent without interrupting an in-flight work
     * action. Assignment refresh completes it after the citizen reaches IDLE.
     */
    public boolean requestManualRelease() {
        if (!hasManualAssignment() || manualReleasePending) return false;
        manualReleasePending = true;
        return true;
    }

    public boolean completeManualReleaseIfSafe() {
        if (!manualReleasePending || workState != CitizenWorkState.IDLE || life.task.startsWith("cook:") || life.task.startsWith("kiln:") || life.brewing != null) return false;
        return clearManualAssignment();
    }

    public boolean markDead(BlockPos deathPosition) {
        boolean changed = alive || !lastPosition.equals(deathPosition);
        alive = false;
        lastPosition = deathPosition.immutable();
        changed |= activity != CitizenActivity.DEAD;
        activity = CitizenActivity.DEAD;
        changed |= setPreferredWorksite(null);
        return changed;
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        if (constructionTool!=null) {
            tag.putString("ConstructionTool",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(constructionTool).toString());
            if(constructionToolTarget!=null) tag.putString("ConstructionToolTarget",net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(constructionToolTarget.getBlock()).toString());
        }
        tag.put("Life", life.save());
        tag.put("ProfessionExperience", professionExperience().save());
        tag.put("Thermal",thermal.save(registries));
        tag.putString(ID_TAG, id.toString());
        tag.putString(NAME_TAG, name);
        if (entityId != null) {
            tag.putString(ENTITY_ID_TAG, entityId.toString());
        }
        if (homeBuildingId != null) {
            tag.putString(HOME_ID_TAG, homeBuildingId.toString());
        }
        if (bedPosition != null) tag.putLong(BED_POSITION_TAG, bedPosition.asLong());
        tag.putString(PROFESSION_TAG, profession.serializedName());
        putOptionalUuid(tag, WORK_BUILDING_ID_TAG, workBuildingId);
        if (manualProfession != null) tag.putString(MANUAL_PROFESSION_TAG, manualProfession.serializedName());
        putOptionalUuid(tag, MANUAL_WORK_BUILDING_ID_TAG, manualWorkBuildingId);
        tag.putBoolean(MANUAL_RELEASE_PENDING_TAG, manualReleasePending);
        putOptionalUuid(tag, PREFERRED_WORKSITE_ID_TAG, preferredWorksiteId);
        tag.putBoolean(WORKSITE_RELEASE_PENDING_TAG, worksiteReleasePending);
        tag.putInt(NUTRITION_TAG, nutrition);
        tag.putInt(WARMTH_TAG, warmth());
        tag.putInt(HEALTH_TAG, health);
        tag.putLong(LAST_POSITION_TAG, lastPosition.asLong());
        tag.putBoolean(ALIVE_TAG, alive);
        tag.putString(WORK_STATE_TAG, workState.serializedName());
        putOptionalUuid(tag, SOURCE_BUILDING_ID_TAG, sourceBuildingId);
        putOptionalUuid(tag, TARGET_BUILDING_ID_TAG, targetBuildingId);
        putOptionalUuid(tag, TARGET_ITEM_ENTITY_ID_TAG, targetItemEntityId);
        if (targetPosition != null) tag.putLong(TARGET_POSITION_TAG, targetPosition.asLong());
        putOptionalUuid(tag, TARGET_BLUEPRINT_ID_TAG, targetBlueprintId);
        putOptionalUuid(tag, "MotherTaskId", motherTaskId);
        putOptionalUuid(tag,"RoadTaskId",roadTaskId);
        putOptionalUuid(tag,"ConstructionResumeId",constructionResumeId);
        if(constructionResumePosition!=null)tag.putLong("ConstructionResumePosition",constructionResumePosition.asLong());
        tag.put("WorkerAccess",workerAccess.save());
        tag.putInt("PitReserveSlot", pitReserveSlot);
        tag.put("PitEscape", pitEscape.save());
        tag.put("PastureJob", pastureJob.save());
        ListTag inventoryTag = new ListTag();
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.get(slot);
            if (stack.isEmpty()) continue;
            CompoundTag entry = new CompoundTag();
            entry.putInt(INVENTORY_SLOT_TAG, slot);
            entry.put(INVENTORY_STACK_TAG, stack.saveOptional(registries));
            inventoryTag.add(entry);
        }
        tag.put(INVENTORY_TAG, inventoryTag);
        CompoundTag equipmentTag = new CompoundTag();
        for (EquipmentSlot slot : EQUIPMENT_SLOTS) {
            equipmentTag.put(slot.getName(), equipmentItem(slot).saveOptional(registries));
        }
        tag.put(EQUIPMENT_TAG, equipmentTag);
        tag.putInt(ACTIVE_SLOT_TAG, activeSlot);
        tag.putString(WORK_FAILURE_TAG, workFailure);
        tag.putString(ACTIVITY_TAG, activity.serializedName());
        if (!ysmModelId.isBlank()) {
            tag.putString(YSM_MODEL_ID_TAG, ysmModelId);
            tag.putString(YSM_TEXTURE_NAME_TAG, ysmTextureName);
        }
        return tag;
    }

    public static CitizenRecord load(CompoundTag tag, HolderLookup.Provider registries) {
        NonNullList<ItemStack> inventory = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
        int activeSlot = -1;
        if (tag.contains(INVENTORY_TAG, Tag.TAG_LIST)) {
            ListTag inventoryTag = tag.getList(INVENTORY_TAG, Tag.TAG_COMPOUND);
            for (int index = 0; index < inventoryTag.size(); index++) {
                CompoundTag entry = inventoryTag.getCompound(index);
                int slot = entry.getInt(INVENTORY_SLOT_TAG);
                if (slot >= 0 && slot < INVENTORY_SIZE) {
                    inventory.set(slot, ItemStack.parseOptional(registries, entry.getCompound(INVENTORY_STACK_TAG)));
                }
            }
            activeSlot = tag.getInt(ACTIVE_SLOT_TAG);
        } else {
            ItemStack legacyCarried = ItemStack.parseOptional(registries, tag.getCompound(CARRIED_ITEM_TAG));
            if (!legacyCarried.isEmpty()) {
                inventory.set(0, legacyCarried);
                activeSlot = 0;
            }
        }
        CitizenRecord record = new CitizenRecord(
                UUID.fromString(tag.getString(ID_TAG)),
                tag.getString(NAME_TAG),
                readOptionalUuid(tag, ENTITY_ID_TAG),
                readOptionalUuid(tag, HOME_ID_TAG),
                tag.contains(BED_POSITION_TAG) ? BlockPos.of(tag.getLong(BED_POSITION_TAG)) : null,
                CitizenProfession.fromSerializedName(tag.getString(PROFESSION_TAG)),
                readOptionalUuid(tag, WORK_BUILDING_ID_TAG),
                tag.contains(MANUAL_PROFESSION_TAG)
                        ? CitizenProfession.fromSerializedName(tag.getString(MANUAL_PROFESSION_TAG)) : null,
                readOptionalUuid(tag, MANUAL_WORK_BUILDING_ID_TAG),
                readOptionalUuid(tag, PREFERRED_WORKSITE_ID_TAG),
                tag.getInt(NUTRITION_TAG),
                tag.getInt(WARMTH_TAG),
                tag.getInt(HEALTH_TAG),
                BlockPos.of(tag.getLong(LAST_POSITION_TAG)),
                tag.getBoolean(ALIVE_TAG),
                CitizenWorkState.fromSerializedName(tag.getString(WORK_STATE_TAG)),
                readOptionalUuid(tag, SOURCE_BUILDING_ID_TAG),
                readOptionalUuid(tag, TARGET_BUILDING_ID_TAG),
                readOptionalUuid(tag, TARGET_ITEM_ENTITY_ID_TAG),
                tag.contains(TARGET_POSITION_TAG) ? BlockPos.of(tag.getLong(TARGET_POSITION_TAG)) : null,
                readOptionalUuid(tag, TARGET_BLUEPRINT_ID_TAG),
                inventory,
                activeSlot,
                tag.getString(WORK_FAILURE_TAG),
                CitizenActivity.fromSerializedName(tag.getString(ACTIVITY_TAG))
        );
        record.motherTaskId=readOptionalUuid(tag,"MotherTaskId");
        record.roadTaskId=readOptionalUuid(tag,"RoadTaskId");
        record.constructionResumeId=readOptionalUuid(tag,"ConstructionResumeId");
        if(tag.contains("ConstructionResumePosition"))record.constructionResumePosition=BlockPos.of(tag.getLong("ConstructionResumePosition"));
        record.workerAccess.load(tag.getCompound("WorkerAccess"));
        int reserveSlot = tag.contains("PitReserveSlot") ? tag.getInt("PitReserveSlot") : -1;
        record.pitReserveSlot = reserveSlot >= 0 && reserveSlot < INVENTORY_SIZE ? reserveSlot : -1;
        if(record.pitReserveSlot>=0 && !record.pitReserve().isEmpty() && !PitEscapeService.ordinary(record.pitReserve()))record.pitReserveSlot=-1;
        record.pitEscape.load(tag.getCompound("PitEscape"));
        record.pastureJob.load(tag.getCompound("PastureJob"));
        record.manualReleasePending = record.hasManualAssignment() && tag.getBoolean(MANUAL_RELEASE_PENDING_TAG);
        var requested=net.minecraft.resources.ResourceLocation.tryParse(tag.getString("ConstructionTool"));
        var requestedBlock=net.minecraft.resources.ResourceLocation.tryParse(tag.getString("ConstructionToolTarget"));
        if(requested!=null && net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(requested)) {
            record.requestConstructionTool(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(requested),
                    requestedBlock==null ? null : net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(requestedBlock).defaultBlockState());
        }
        record.worksiteReleasePending = record.preferredWorksiteId != null && tag.getBoolean(WORKSITE_RELEASE_PENDING_TAG);
        record.setYsmModel(tag.getString(YSM_MODEL_ID_TAG), tag.getString(YSM_TEXTURE_NAME_TAG));
        record.life.load(tag.getCompound("Life"));
        record.professionExperience().load(tag.getCompound("ProfessionExperience"));
        record.thermal.load(tag.getCompound("Thermal"),registries,record.warmth);
        if (tag.contains(EQUIPMENT_TAG, Tag.TAG_COMPOUND)) {
            CompoundTag equipmentTag = tag.getCompound(EQUIPMENT_TAG);
            for (EquipmentSlot slot : EQUIPMENT_SLOTS) {
                record.equipment.put(slot, ItemStack.parseOptional(registries, equipmentTag.getCompound(slot.getName())));
            }
        } else {
            // Schema 14/15 legacy saves stored wearable items only in Inventory.
            // A new Equipment compound marks migration, so reload never duplicates them.
            record.equipAvailableInventory();
        }
        return record;
    }

    private boolean setActiveItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ItemStack remaining = insertIntoInventory(stack);
        int inserted = stack.getCount() - remaining.getCount();
        if (inserted <= 0) return false;
        activeSlot = findInventorySlot(candidate -> ItemStack.isSameItemSameComponents(candidate, stack));
        return activeSlot >= 0;
    }

    private static void putOptionalUuid(CompoundTag tag, String key, @Nullable UUID value) {
        if (value != null) {
            tag.putString(key, value.toString());
        }
    }

    @Nullable
    private static UUID readOptionalUuid(CompoundTag tag, String key) {
        String value = tag.getString(key);
        return value.isBlank() ? null : UUID.fromString(value);
    }
}
