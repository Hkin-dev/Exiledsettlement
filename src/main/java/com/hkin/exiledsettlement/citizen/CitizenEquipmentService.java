package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.building.SettlementInventoryService;
import com.hkin.exiledsettlement.building.WarehouseInventoryService;
import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.registry.ModItems;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.SwordItem;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Profession loadout policy. Inventory is persistent; tools are optional work accelerators. */
public final class CitizenEquipmentService {
    private static final long STOCK_SNAPSHOT_TICKS = 20L;
    private static final Map<ServerLevel, Map<UUID, EquipmentStock>> STOCK_CACHE = new WeakHashMap<>();
    private static final Map<CitizenRecord,Long> RETRY_AFTER = new WeakHashMap<>();
    private static final Map<CitizenRecord,Long> TRIP_STARTED = new WeakHashMap<>();

    public static boolean optionalTripExpired(ServerLevel level,CitizenRecord citizen) {
        return level.getGameTime()-TRIP_STARTED.computeIfAbsent(citizen,c->level.getGameTime())>=240;
    }
    public static void deferOptionalTrip(ServerLevel level,CitizenRecord citizen) {
        RETRY_AFTER.put(citizen,level.getGameTime()+200);TRIP_STARTED.remove(citizen);
    }
    public static void finishOptionalTrip(CitizenRecord citizen) { TRIP_STARTED.remove(citizen); }

    private CitizenEquipmentService() {
    }

    @Nullable
    public static Item missingEquipment(CitizenRecord citizen) {
        return null;
    }

    @Nullable
    public static Item preferredEquipment(CitizenRecord citizen) {
        return switch (citizen.profession()) {
            case FARMER, MELON_FARMER -> has(citizen, HoeItem.class) ? null : Items.WOODEN_HOE;
            case WORKER, WAREHOUSE_KEEPER, TRAVELING_MERCHANT, CHEF, KILN_WORKER, TEACHER, PRIEST -> null;
            case HERDSMAN -> !has(citizen, Items.SHEARS) ? Items.SHEARS
                    : has(citizen, Items.LEAD) ? null : Items.LEAD;
            case HUNTER -> !hasWeapon(citizen) ? Items.WOODEN_SWORD
                    : has(citizen, BowItem.class) && !has(citizen, Items.ARROW) ? Items.ARROW : null;
            case TOOLSMITH, MINER -> has(citizen, PickaxeItem.class) ? null : Items.WOODEN_PICKAXE;
            case LUMBERJACK -> has(citizen, AxeItem.class) ? null : Items.WOODEN_AXE;
            case FISHER -> has(citizen, FishingRodItem.class) ? null : Items.FISHING_ROD;
            case SAWYER -> has(citizen, AxeItem.class) ? null : Items.WOODEN_AXE;
            default -> null;
        };
    }

    /** Available optional profession gear first, then one wearable armor piece that is actually available. */
    @Nullable
    public static Item nextEquipment(ServerLevel level, SettlementRecord settlement, CitizenRecord citizen) {
        if(level.getGameTime()<RETRY_AFTER.getOrDefault(citizen,Long.MIN_VALUE))return null;
        if (citizen.constructionTool()!=null) {
            Item requested=citizen.constructionTool();
            if(citizen.hasInventoryItem(stack -> interchangeableTool(requested,stack.getItem()))) {
                citizen.requestConstructionTool(null,null);
                com.hkin.exiledsettlement.settlement.SettlementSavedData.get(level).touch();
            } else return equipmentStock(level,settlement).items().stream()
                    .filter(item -> interchangeableTool(requested,item))
                    .min(Comparator.<Item>comparingDouble(item -> CitizenToolSpeed.multiplier(new ItemStack(item))).thenComparing(item -> BuiltInRegistries.ITEM.getKey(item).toString())).orElse(null);
        }
        Item mandatory = preferredEquipment(citizen);
        EquipmentStock stock = equipmentStock(level, settlement);
        if (mandatory != null) {
            // Prefer the basic tool category when stock is available.
            // Match the same policy used for tools already in the resident backpack.
            Item available = stock.items().stream()
                    .filter(item -> interchangeableTool(mandatory, item))
                    .distinct()
                    .min(java.util.Comparator.<Item>comparingDouble(item -> CitizenToolSpeed.multiplier(new ItemStack(item))).thenComparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()))
                    .orElse(null);
            if(available!=null)return available;
        }
        if(citizen.profession()==CitizenProfession.WORKER && citizen.pitReserve().getCount()<PitEscapeService.RESERVE
                && citizen.inventoryCapacityFor(new ItemStack(Items.DIRT))>=PitEscapeService.RESERVE-citizen.pitReserve().getCount()
                && (settlement.creativeCoreInstalled() || stock.items().contains(Items.DIRT)))return Items.DIRT;
        if (citizen.profession() == CitizenProfession.LUMBERJACK && !hasPlantingStock(citizen)) {
            Item seed = stock.items().stream().filter(CitizenEquipmentService::isForestrySeed)
                    .filter(item -> stock.byWarehouse().keySet().stream().map(settlement::building)
                            .anyMatch(warehouse -> warehouse != null && WarehouseInventoryService.countItem(level, warehouse, item)
                                    >= plantingSeedMinimum(item)))
                    .sorted(Comparator.<Item>comparingDouble(item -> CitizenToolSpeed.multiplier(new ItemStack(item))).thenComparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()))
                    .findFirst().orElse(null);
            if (seed != null && citizen.inventoryCapacityFor(new ItemStack(seed)) >= plantingSeedMinimum(seed)) return seed;
            if (settlement.creativeCoreInstalled() || stock.items().contains(ModItems.CREATIVE_SUPPLY_CORE.get())) {
                if (citizen.inventoryCapacityFor(new ItemStack(Items.OAK_SAPLING)) > 0) return Items.OAK_SAPLING;
            }
        }
        if (CitizenWorkService.schedulePhase(level.getDayTime(), citizen) == CitizenWorkService.SchedulePhase.REST) {
            for (Item tool : PERSONAL_TOOLS) {
                if (citizen.hasInventoryItem(stack -> interchangeableTool(tool, stack.getItem()))) continue;
                Item available = stock.items().stream().filter(item -> interchangeableTool(tool, item))
                        .filter(item -> citizen.inventoryCapacityFor(new ItemStack(item)) > 0)
                        .sorted(Comparator.<Item>comparingDouble(item -> CitizenToolSpeed.multiplier(new ItemStack(item))).thenComparing(item -> BuiltInRegistries.ITEM.getKey(item).toString())).findFirst().orElse(null);
                if (available != null) return available;
            }
        }
        boolean creative = settlement.creativeCoreInstalled() || stock.items().contains(ModItems.CREATIVE_SUPPLY_CORE.get());
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
        }) {
            if (hasArmorForSlot(citizen, slot)) continue;
            Item available = stock.items().stream()
                    .filter(item -> item instanceof ArmorItem armor && armor.getEquipmentSlot() == slot)
                    .distinct()
                    .min(java.util.Comparator.<Item>comparingDouble(item -> CitizenToolSpeed.multiplier(new ItemStack(item))).thenComparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()))
                    .orElse(null);
            if (available != null) return available;
            if (creative) return creativeArmor(slot);
        }
        if (citizen.equipmentItem(EquipmentSlot.OFFHAND).isEmpty()
                && !citizen.hasInventoryItem(stack -> stack.is(Items.SHIELD)) && stock.items().contains(Items.SHIELD)) {
            return Items.SHIELD;
        }
        return null;
    }

    /** Shared discovery cache; inventory extraction and selected-source checks remain authoritative. */
    @Nullable
    public static BuildingRecord supplyWarehouse(
            ServerLevel level, SettlementRecord settlement, BlockPos from, Item requested
    ) {
        EquipmentStock stock = equipmentStock(level, settlement);
        return SettlementInventoryService.warehouses(settlement).stream()
                .filter(warehouse -> {
                    Set<Item> items = stock.byWarehouse().getOrDefault(warehouse.id(), Set.of());
                    return items.contains(requested) || items.contains(ModItems.CREATIVE_SUPPLY_CORE.get());
                })
                .sorted(Comparator.comparingDouble(warehouse -> warehouse.position().distSqr(from)))
                .filter(warehouse -> WarehouseInventoryService.countItem(level, warehouse, requested) > 0
                        || WarehouseInventoryService.hasCreativeSupply(level, warehouse))
                .findFirst().orElse(null);
    }

    @Nullable public static BuildingRecord supplyWarehouse(ServerLevel level,SettlementRecord settlement,CitizenRecord citizen,BlockPos from,Item requested) {
        if(com.hkin.exiledsettlement.road.PlayerBridgeWork.assignment(settlement,citizen)!=null)
            return com.hkin.exiledsettlement.road.PlayerBridgeWork.supplier(level,settlement,citizen,from,requested);
        return supplyWarehouse(level,settlement,from,requested);
    }

    private static EquipmentStock equipmentStock(ServerLevel level, SettlementRecord settlement) {
        long now = level.getGameTime();
        Map<UUID, EquipmentStock> snapshots = STOCK_CACHE.computeIfAbsent(level, ignored -> new HashMap<>());
        EquipmentStock cached = snapshots.get(settlement.id());
        if (cached != null && cached.expiresAt() > now) return cached;
        snapshots.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        Set<Item> allItems = new HashSet<>();
        Map<UUID, Set<Item>> byWarehouse = new HashMap<>();
        for (BuildingRecord warehouse : SettlementInventoryService.warehouses(settlement)) {
            Set<Item> items = Set.copyOf(WarehouseInventoryService.summarize(level, warehouse).keySet());
            byWarehouse.put(warehouse.id(), items);
            allItems.addAll(items);
        }
        EquipmentStock snapshot = new EquipmentStock(now + STOCK_SNAPSHOT_TICKS,
                Set.copyOf(allItems), Map.copyOf(byWarehouse));
        snapshots.put(settlement.id(), snapshot);
        return snapshot;
    }

    private record EquipmentStock(long expiresAt, Set<Item> items, Map<UUID, Set<Item>> byWarehouse) { }

    public static boolean isForestrySeed(Item item) {
        return item instanceof net.minecraft.world.item.BlockItem block
                && block.getBlock().defaultBlockState().is(net.minecraft.tags.BlockTags.SAPLINGS);
    }

    public static int plantingSeedMinimum(Item item) { return item == Items.DARK_OAK_SAPLING ? 4 : 1; }

    public static boolean hasPlantingStock(CitizenRecord citizen) {
        return citizen.inventory().stream().filter(stack -> isForestrySeed(stack.getItem()))
                .anyMatch(stack -> citizen.inventory().stream().filter(other -> other.is(stack.getItem()))
                        .mapToInt(ItemStack::getCount).sum() >= plantingSeedMinimum(stack.getItem()));
    }

    private static boolean interchangeableTool(Item required, Item available) {
        if (required instanceof HoeItem) return available instanceof HoeItem;
        if (required instanceof AxeItem) return available instanceof AxeItem;
        if (required instanceof PickaxeItem) return available instanceof PickaxeItem;
        if (required instanceof net.minecraft.world.item.ShovelItem) return available instanceof net.minecraft.world.item.ShovelItem;
        if (required instanceof SwordItem) return available instanceof SwordItem || available instanceof BowItem;
        if (required instanceof FishingRodItem) return available instanceof FishingRodItem;
        return required == available;
    }

    public static boolean hasWeapon(CitizenRecord citizen) {
        return has(citizen, SwordItem.class) || has(citizen, BowItem.class);
    }

    public static ItemStack bestWeapon(CitizenRecord citizen) {
        int bow = citizen.findInventorySlot(stack -> stack.getItem() instanceof BowItem);
        if (bow >= 0 && has(citizen, Items.ARROW)) return citizen.inventoryItem(bow);
        int sword = citizen.findInventorySlot(stack -> stack.getItem() instanceof SwordItem);
        if (sword >= 0) return citizen.inventoryItem(sword);
        int fallback = citizen.findInventorySlot(stack -> stack.getItem() instanceof AxeItem
                || stack.getItem() instanceof PickaxeItem || stack.getItem() instanceof HoeItem);
        return fallback >= 0 ? citizen.inventoryItem(fallback) : ItemStack.EMPTY;
    }

    public static ItemStack visibleHand(CitizenRecord citizen) {
        ItemStack active = citizen.carriedItem();
        if (!active.isEmpty()) return active;
        ItemStack weapon = bestWeapon(citizen);
        if (!weapon.isEmpty()) return weapon;
        int tool = citizen.findInventorySlot(CitizenEquipmentService::isProfessionEquipment);
        return tool >= 0 ? citizen.inventoryItem(tool) : ItemStack.EMPTY;
    }

    /** Copies wearable inventory items onto the live player-shaped projection for rendering. */
    public static void syncVisibleEquipment(CitizenRecord citizen, SettlementCitizenEntity entity) {
        if (citizen.equipAvailableInventory() && entity.level() instanceof ServerLevel level) {
            com.hkin.exiledsettlement.settlement.SettlementSavedData.get(level).touch();
        }
        entity.setItemSlot(EquipmentSlot.MAINHAND, visibleHand(citizen).copy());
        for (EquipmentSlot slot : CitizenRecord.EQUIPMENT_SLOTS) {
            entity.setItemSlot(slot, slot.isArmor() && !entity.isArmorReady() ? ItemStack.EMPTY : citizen.equipmentItem(slot));
        }
    }

    public static boolean isProfessionEquipment(ItemStack stack) {
        Item item = stack.getItem();
        return item instanceof HoeItem || item instanceof AxeItem || item instanceof PickaxeItem
                || item instanceof net.minecraft.world.item.ShovelItem
                || item instanceof SwordItem || item instanceof BowItem || item instanceof FishingRodItem
                || stack.is(Items.SHEARS) || stack.is(Items.LEAD) || stack.is(Items.ARROW);
    }

    /** Keeps exactly the resident's working loadout while allowing duplicate crafted tools to be delivered. */
    public static boolean isReservedEquipment(CitizenRecord citizen, int slot) {
        ItemStack stack = citizen.inventoryItem(slot);
        if (stack.isEmpty()) return false;
        // Material availability and batch pickup use this same working-loadout
        // filter. Extraction already excludes the personal escape reserve.
        if (citizen.pitReserveSlot(slot)) return true;
        // Personal armor is stored separately; spare inventory armor belongs to logistics.
        if (CitizenRecord.wearableSlot(stack) != null) return false;
        for (Item required : PERSONAL_TOOLS) {
            if (interchangeableTool(required, stack.getItem())) return slot == citizen.findInventorySlot(
                    candidate -> interchangeableTool(required, candidate.getItem()));
        }
        return switch (citizen.profession()) {
            case FARMER, MELON_FARMER -> slot == citizen.findInventorySlot(candidate -> candidate.getItem() instanceof HoeItem);
            case WORKER -> slot == citizen.findInventorySlot(candidate -> candidate.getItem() instanceof net.minecraft.world.item.ShovelItem)
                    || slot == citizen.findInventorySlot(candidate -> candidate.getItem() instanceof PickaxeItem
                    || candidate.getItem() instanceof AxeItem);
            case WAREHOUSE_KEEPER, TRAVELING_MERCHANT, CHEF, KILN_WORKER, TEACHER, PRIEST -> false;
            default -> false;
            case HERDSMAN -> slot == citizen.findInventorySlot(candidate -> candidate.is(Items.LEAD));
            case HUNTER -> slot == citizen.findInventorySlot(candidate -> candidate.getItem() instanceof SwordItem)
                    || slot == citizen.findInventorySlot(candidate -> candidate.getItem() instanceof BowItem)
                    || slot == citizen.findInventorySlot(candidate -> candidate.is(Items.ARROW))
                    || slot == citizen.findInventorySlot(candidate -> candidate.is(Items.LEAD));
            case TOOLSMITH, MINER -> slot == citizen.findInventorySlot(candidate -> candidate.getItem() instanceof PickaxeItem);
            case LUMBERJACK -> slot == citizen.findInventorySlot(candidate -> candidate.getItem() instanceof AxeItem);
            case FISHER -> slot == citizen.findInventorySlot(candidate -> candidate.getItem() instanceof FishingRodItem);
            case SAWYER -> slot == citizen.findInventorySlot(candidate -> candidate.getItem() instanceof AxeItem);
        };
    }

    public static int deliverableCount(CitizenRecord citizen, int slot) {
        if (isReservedEquipment(citizen, slot)) return citizen.inventoryItem(slot).is(Items.LEAD)
                ? Math.max(0, citizen.inventoryItem(slot).getCount() - 1) : 0;
        return Math.max(0, citizen.inventoryItem(slot).getCount()
                - com.hkin.exiledsettlement.settlement.CommunityLifeService.reservedFood(citizen, slot)
                - com.hkin.exiledsettlement.settlement.PriestProductionService.reservedCount(citizen, slot)
                - CitizenClothingService.reserved(citizen,slot));
    }

    public static int toolSlotForWork(CitizenRecord citizen) {
        return switch (citizen.profession()) {
            case FARMER, MELON_FARMER -> bestTool(citizen,stack -> stack.getItem() instanceof HoeItem);
            case WORKER -> switch (citizen.workState()) {
                case TO_ROAD, TO_ROAD_TOOL -> bestTool(citizen, stack -> stack.getItem() instanceof net.minecraft.world.item.ShovelItem);
                case TO_BLUEPRINT, TO_REPAIR, TO_DEMOLITION -> bestTool(citizen,
                        stack -> stack.getItem() instanceof PickaxeItem || stack.getItem() instanceof AxeItem
                                || stack.getItem() instanceof net.minecraft.world.item.ShovelItem);
                default -> -1;
            };
            case HUNTER -> {
                int bow = bestTool(citizen,stack -> stack.getItem() instanceof BowItem);
                yield bow >= 0 && bestTool(citizen,stack -> stack.is(Items.ARROW)) >= 0
                        ? bow : bestTool(citizen,stack -> stack.getItem() instanceof SwordItem);
            }
            case TOOLSMITH, MINER -> bestTool(citizen,stack -> stack.getItem() instanceof PickaxeItem);
            case LUMBERJACK -> bestTool(citizen,stack -> stack.getItem() instanceof AxeItem);
            case FISHER -> bestTool(citizen,stack -> stack.getItem() instanceof FishingRodItem);
            case SAWYER -> bestTool(citizen,stack -> stack.getItem() instanceof AxeItem);
            case HERDSMAN -> bestTool(citizen, stack -> stack.is(Items.SHEARS) || stack.is(Items.LEAD));
            case WAREHOUSE_KEEPER, TRAVELING_MERCHANT, CHEF, KILN_WORKER, TEACHER, PRIEST -> -1;
            default -> -1;
        };
    }

    private static int bestTool(CitizenRecord citizen, java.util.function.Predicate<ItemStack> match) {
        int best=-1; double speed=0;
        for(int i=0;i<citizen.inventory().size();i++) {
            var stack=citizen.inventoryItem(i);
            if(!stack.isEmpty() && match.test(stack) && CitizenToolSpeed.multiplier(stack)>speed) {
                best=i; speed=CitizenToolSpeed.multiplier(stack);
            }
        }
        return best;
    }

    private static final Item[] PERSONAL_TOOLS = {Items.WOODEN_AXE, Items.WOODEN_PICKAXE, Items.WOODEN_SHOVEL,
            Items.WOODEN_HOE, Items.SHEARS, Items.FISHING_ROD, Items.LEAD};

    /** Nearby workers first reach a real source rather than leaving without a usable engineering kit. */
    public static boolean prepareConstructionDeparture(ServerLevel level,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e){
        if(c.profession()!=CitizenProfession.WORKER || !s.creativeCoreInstalled()
                || e.blockPosition().distSqr(s.corePosition())>144 || s.blueprints().isEmpty())return false;
        for(Item tool:new Item[]{Items.WOODEN_AXE,Items.WOODEN_PICKAXE,Items.WOODEN_SHOVEL}){
            if(c.hasInventoryItem(stack->interchangeableTool(tool,stack.getItem())) || c.equipmentCapacityFor(new ItemStack(tool))<1)continue;
            c.requestConstructionTool(tool,null);
            return c.startEquipmentFetch(null,s.corePosition());
        }
        if(c.reservePitBlocks(PitEscapeService::ordinary,PitEscapeService.RESERVE)>0)
            com.hkin.exiledsettlement.settlement.SettlementSavedData.get(level).touch();
        if(c.pitReserve().getCount()<PitEscapeService.RESERVE && c.inventoryCapacityFor(new ItemStack(Items.DIRT))>0)
            return c.startEquipmentFetch(null,s.corePosition());
        return false;
    }

    /** Prepare the ordinary engineering kit while physically at its supply source. */
    public static void topUpConstructionTools(ServerLevel level, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity) {
        if (citizen.profession() != CitizenProfession.WORKER) return;
        PitEscapeService.topUp(level,settlement,citizen,entity);
        boolean core = settlement.creativeCoreInstalled()
                && SettlementCoreInteractionService.canReceiveSupply(level, settlement, entity);
        if(core){
            com.hkin.exiledsettlement.settlement.CommunityLifeService.topUpRations(level,settlement,citizen,entity,null);
            CitizenClothingService.prepareDeparture(level,settlement,citizen,entity);
        }
        for (Item required : new Item[]{Items.WOODEN_AXE, Items.WOODEN_PICKAXE, Items.WOODEN_SHOVEL}) {
            if (citizen.hasInventoryItem(stack -> interchangeableTool(required, stack.getItem()))) continue;
            BuildingRecord warehouse = supplyWarehouse(level, settlement, citizen, entity.blockPosition(), required);
            boolean localWarehouse = warehouse != null && WarehouseInventoryService.canInteract(level, warehouse, entity);
            if (!localWarehouse && !core) continue;
            if (citizen.equipmentCapacityFor(new ItemStack(required)) < 1) continue;
            ItemStack stock = localWarehouse
                    ? WarehouseInventoryService.extractItemOrCreativeForResident(level, warehouse, entity, required, 1)
                    : new ItemStack(required);
            if (stock.isEmpty()) continue;
            ItemStack left = citizen.receiveEquipment(stock);
            int received = stock.getCount() - left.getCount();
            if (!left.isEmpty() && localWarehouse) WarehouseInventoryService.insertForResident(level, warehouse, entity, left);
            if (received > 0) {
                com.hkin.exiledsettlement.settlement.SettlementSavedData.get(level).touch();
                com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level, citizen, "construction_tool_pickup",
                        localWarehouse ? warehouse.position() : settlement.corePosition(), BuiltInRegistries.ITEM.getKey(required).toString(), received);
            }
        }
    }

    /** Optional kit only uses a physically reached warehouse. */
    public static void topUpPersonalTools(ServerLevel level, SettlementRecord settlement, CitizenRecord citizen,
                                         SettlementCitizenEntity entity, BuildingRecord warehouse) {
        if (warehouse == null || !WarehouseInventoryService.canInteract(level, warehouse, entity)) return;
        for (Item required : PERSONAL_TOOLS) {
            if (citizen.hasInventoryItem(stack -> interchangeableTool(required, stack.getItem()))) continue;
            Item item = equipmentStock(level, settlement).items().stream()
                    .filter(candidate -> interchangeableTool(required, candidate))
                    .filter(candidate -> WarehouseInventoryService.countItem(level,warehouse,candidate)>0)
                    .min(Comparator.comparingDouble(candidate -> CitizenToolSpeed.multiplier(new ItemStack(candidate)))).orElse(null);
            if (item == null || citizen.inventoryCapacityFor(new ItemStack(item)) < 1) continue;
            citizen.insertIntoInventory(WarehouseInventoryService.extractItemForResident(level, warehouse, entity, item, 1));
        }
    }

    private static boolean has(CitizenRecord citizen, Item item) {
        return citizen.hasInventoryItem(stack -> stack.is(item));
    }

    private static boolean has(CitizenRecord citizen, Class<? extends Item> itemClass) {
        return citizen.hasInventoryItem(stack -> itemClass.isInstance(stack.getItem()));
    }

    private static boolean hasArmorForSlot(CitizenRecord citizen, EquipmentSlot slot) {
        return !citizen.equipmentItem(slot).isEmpty()
                || citizen.hasInventoryItem(stack -> CitizenRecord.wearableSlot(stack) == slot);
    }

    private static Item creativeArmor(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> Items.IRON_HELMET;
            case CHEST -> Items.IRON_CHESTPLATE;
            case LEGS -> Items.IRON_LEGGINGS;
            case FEET -> Items.IRON_BOOTS;
            default -> Items.AIR;
        };
    }
}
