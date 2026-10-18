package com.hkin.exiledsettlement.building;

import com.hkin.exiledsettlement.registry.ModItems;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.blueprint.BlueprintEntrance;
import com.hkin.exiledsettlement.citizen.CitizenNavigationReservationService;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.citizen.CitizenInteractionRange;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public final class WarehouseInventoryService {
    private static final double STATION_RESERVATION_RADIUS_SQUARED = 12.0D * 12.0D;

    private WarehouseInventoryService() {
    }

    public static List<ContainerAt> loadedContainers(ServerLevel level, BuildingRecord warehouse) {
        if (WarehouseStock.applies(warehouse)) return List.of(new ContainerAt(warehouse.position(), stock(level,warehouse)));
        return physicalContainers(level,warehouse);
    }

    public static WarehouseStock stock(ServerLevel level, BuildingRecord building) {
        var stock=building.warehouseStock().bind(level);stock.importBoxes(level,building);return stock;
    }

    public static List<ContainerAt> physicalContainers(ServerLevel level, BuildingRecord warehouse) {
        List<ContainerAt> containers = new ArrayList<>();
        if(warehouse.type().isHouse()) {
            for(int x=warehouse.minCorner().getX()>>4;x<=warehouse.maxCorner().getX()>>4;x++)
                for(int z=warehouse.minCorner().getZ()>>4;z<=warehouse.maxCorner().getZ()>>4;z++) {
                    var chunk=level.getChunkSource().getChunkNow(x,z);if(chunk==null)continue;
                    for(var entry:chunk.getBlockEntities().entrySet()) {
                        var p=entry.getKey();
                        if(insideStructure(warehouse,p) && p.getY()>=warehouse.minCorner().getY() && p.getY()<=warehouse.maxCorner().getY()
                                && entry.getValue() instanceof Container box
                                && !registeredPublicContainer(level,warehouse,p))containers.add(new ContainerAt(p,box));
                    }
                }
            return containers;
        }
        for (BlockPos position : new java.util.LinkedHashSet<>(warehouse.servicePositions())) {
            if (!level.hasChunkAt(position)) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(position);
            if (blockEntity instanceof Container container) {
                containers.add(new ContainerAt(position, container));
            }
        }
        return containers;
    }

    /** A barrel explicitly registered as public storage is not adopted by the surrounding home. */
    public static boolean registeredPublicContainer(ServerLevel level, BuildingRecord home, BlockPos position) {
        var settlement=com.hkin.exiledsettlement.settlement.SettlementSavedData.get(level).findContaining(home.position()).orElse(null);
        return settlement!=null && settlement.buildings().stream().anyMatch(b->b.active() && !b.id().equals(home.id())
                && b.type().isStorage() && b.servicePositions().contains(position));
    }

    public static int totalSlots(ServerLevel level, BuildingRecord warehouse) {
        if(WarehouseStock.applies(warehouse))return WarehouseStock.CAPACITY;
        return loadedContainers(level, warehouse).stream()
                .mapToInt(containerAt -> containerAt.container().getContainerSize())
                .sum();
    }

    public static int usedSlots(ServerLevel level, BuildingRecord warehouse) {
        if(WarehouseStock.applies(warehouse))return stock(level,warehouse).quantity();
        int used = 0;
        for (ContainerAt containerAt : loadedContainers(level, warehouse)) {
            Container container = containerAt.container();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (!container.getItem(slot).isEmpty()) {
                    used++;
                }
            }
        }
        return used;
    }

    private record PartialStack(Container container, int slot) {}

    public static boolean hasMergeableStacks(ServerLevel level, BuildingRecord warehouse) {
        return mergeStacks(level, warehouse, false) > 0;
    }

    public static boolean needsOrganization(ServerLevel level, BuildingRecord warehouse) {
        if(WarehouseStock.applies(warehouse))return false;
        return hasMergeableStacks(level, warehouse) || organize(level, warehouse, false) > 0;
    }

    public static int organizeForResident(ServerLevel level, BuildingRecord warehouse, LivingEntity entity) {
        if (!warehouse.operational() || !warehouse.type().isStorage() || !canInteract(level, warehouse, entity)) return 0;
        return mergeStacks(level, warehouse, true) + organize(level, warehouse, true);
    }

    private static int compareStacks(ItemStack a, ItemStack b) {
        if (a.isEmpty()) return b.isEmpty() ? 0 : 1;
        if (b.isEmpty()) return -1;
        int category = Integer.compare(WarehouseCategory.classify(a).ordinal(), WarehouseCategory.classify(b).ordinal());
        if (category != 0) return category;
        int item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(a.getItem()).compareTo(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(b.getItem()));
        if (item != 0) return item;
        return ItemStack.isSameItemSameComponents(a, b) ? Integer.compare(b.getCount(), a.getCount()) : 0;
    }

    /** Fill each early slot directly, avoiding hundreds of empty-slot shuffles in large warehouses. */
    private static int organize(ServerLevel level, BuildingRecord warehouse, boolean apply) {
        var slots=new ArrayList<PartialStack>();int moved=0;
        for (var at : loadedContainers(level, warehouse)) for (int slot = 0; slot < at.container().getContainerSize(); slot++) {
            if(!at.container().getItem(slot).is(ModItems.CREATIVE_SUPPLY_CORE.get()))slots.add(new PartialStack(at.container(),slot));
        }
        boolean ordered=true;
        for(int i=1;i<slots.size();i++)if(compareStacks(stackAt(slots.get(i-1)),stackAt(slots.get(i)))>0){ordered=false;break;}
        if(ordered)return 0;
        for(int i=0;i<slots.size();i++) {
            var destination=slots.get(i);var old=stackAt(destination);PartialStack best=destination;
            for(int j=i+1;j<slots.size();j++){
                var candidate=slots.get(j);var stack=stackAt(candidate);
                if(compareStacks(stack,stackAt(best))<0 && accepts(destination,stack) && accepts(candidate,old))best=candidate;
            }
            if(best!=destination){
                if(!apply)return 1;
                destination.container().setItem(destination.slot(),stackAt(best).copy());
                best.container().setItem(best.slot(),old.copy());
                destination.container().setChanged();best.container().setChanged();
                if(++moved>=32)return moved;
            }
        }
        return moved;
    }
    private static ItemStack stackAt(PartialStack slot){return slot.container().getItem(slot.slot());}
    private static boolean accepts(PartialStack slot,ItemStack stack){return stack.isEmpty() || slot.container().canPlaceItem(slot.slot(),stack)
            && stack.getCount()<=Math.min(stack.getMaxStackSize(),slot.container().getMaxStackSize(stack));}

    public static int mergeStacksForResident(ServerLevel level, BuildingRecord warehouse, LivingEntity entity) {
        if (!warehouse.operational() || !warehouse.type().isStorage()
                || !canInteract(level, warehouse, entity)) return 0;
        return mergeStacks(level, warehouse, true);
    }

    private static int mergeStacks(ServerLevel level, BuildingRecord warehouse, boolean apply) {
        Map<Item, List<PartialStack>> partial = new LinkedHashMap<>();
        int moved = 0;
        for (ContainerAt at : loadedContainers(level, warehouse)) {
            Container source = at.container();
            for (int slot = 0; slot < source.getContainerSize(); slot++) {
                ItemStack stack = source.getItem(slot);
                if (stack.isEmpty() || !stack.isStackable() || stack.is(ModItems.CREATIVE_SUPPLY_CORE.get())) continue;
                List<PartialStack> destinations = partial.computeIfAbsent(stack.getItem(), ignored -> new ArrayList<>());
                for (var iterator = destinations.iterator(); iterator.hasNext() && !stack.isEmpty();) {
                    PartialStack destination = iterator.next();
                    ItemStack existing = destination.container().getItem(destination.slot());
                    int room = Math.min(existing.getMaxStackSize(), destination.container().getMaxStackSize(existing))
                            - existing.getCount();
                    if (room <= 0) { iterator.remove(); continue; }
                    if (!ItemStack.isSameItemSameComponents(existing, stack)
                            || !destination.container().canPlaceItem(destination.slot(), stack)) continue;
                    if (!apply) return 1;
                    int amount = Math.min(room, stack.getCount());
                    existing.grow(amount);
                    stack.shrink(amount);
                    source.setChanged();
                    destination.container().setChanged();
                    moved += amount;
                    if (moved >= 512) return moved; // Bounded local housekeeping batch.
                }
                if (!stack.isEmpty() && stack.getCount() < Math.min(stack.getMaxStackSize(), source.getMaxStackSize(stack)))
                    destinations.add(new PartialStack(source, slot));
            }
        }
        return moved;
    }

    public static boolean canAccept(ServerLevel level, BuildingRecord warehouse, ItemStack stack) {
        if(WarehouseStock.applies(warehouse))return warehouse.operational() && !stack.isEmpty() && stock(level,warehouse).room()>0;
        if (!warehouse.operational() || stack.isEmpty() || !warehouse.accepts(stack)) {
            return false;
        }
        if(com.hkin.exiledsettlement.settlement.WarehouseStewardService.admission(level,warehouse,stack,loadedContainers(level,warehouse))<=0)return false;
        for (ContainerAt containerAt : loadedContainers(level, warehouse)) {
            Container container = containerAt.container();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (!container.canPlaceItem(slot, stack)) {
                    continue;
                }
                ItemStack existing = container.getItem(slot);
                if (existing.isEmpty()) {
                    return true;
                }
                if (ItemStack.isSameItemSameComponents(existing, stack)
                        && existing.getCount() < Math.min(existing.getMaxStackSize(), container.getMaxStackSize(existing))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String deliveryBlocker(ServerLevel level, com.hkin.exiledsettlement.settlement.SettlementRecord s, ItemStack stack) {
        boolean accepts=false, loaded=false, policy=false;
        for(var b:SettlementInventoryService.warehouses(s)){
            if(!b.accepts(stack))continue;accepts=true;
            var containers=loadedContainers(level,b);if(containers.isEmpty())continue;loaded=true;
            if(containers.stream().anyMatch(at->containerCanAccept(at.container(),stack))
                    && com.hkin.exiledsettlement.settlement.WarehouseStewardService.admission(level,b,stack,containers)<=0)policy=true;
        }
        return !accepts?"WAREHOUSE_FILTER_REJECTED":!loaded?"WAREHOUSE_CONTAINERS_UNAVAILABLE":policy?"WAREHOUSE_RESERVE_LIMIT":"OUTPUT_FULL";
    }

    public static ItemStack insert(ServerLevel level, BuildingRecord warehouse, ItemStack offered) {
        if(WarehouseStock.applies(warehouse))return offered.isEmpty() || !warehouse.operational()?offered.copy():stock(level,warehouse).insert(offered);
        if (offered.isEmpty() || !warehouse.operational() || !warehouse.accepts(offered)) return offered.copy();
        ItemStack remaining = offered.copy();
        List<ContainerAt> containers = loadedContainers(level, warehouse);

        for (ContainerAt containerAt : containers) {
            Container container = containerAt.container();
            for (int slot = 0; slot < container.getContainerSize() && !remaining.isEmpty(); slot++) {
                ItemStack existing = container.getItem(slot);
                if (existing.isEmpty()
                        || !container.canPlaceItem(slot, remaining)
                        || !ItemStack.isSameItemSameComponents(existing, remaining)) {
                    continue;
                }
                int limit = Math.min(existing.getMaxStackSize(), container.getMaxStackSize(existing));
                int moved = Math.min(limit - existing.getCount(), remaining.getCount());
                if (moved > 0) {
                    existing.grow(moved);
                    remaining.shrink(moved);
                    container.setChanged();
                }
            }
        }

        for (ContainerAt containerAt : containers) {
            Container container = containerAt.container();
            for (int slot = 0; slot < container.getContainerSize() && !remaining.isEmpty(); slot++) {
                if (!container.getItem(slot).isEmpty() || !container.canPlaceItem(slot, remaining)) {
                    continue;
                }
                int limit = Math.min(remaining.getMaxStackSize(), container.getMaxStackSize(remaining));
                int moved = Math.min(limit, remaining.getCount());
                container.setItem(slot, remaining.copyWithCount(moved));
                remaining.shrink(moved);
                container.setChanged();
            }
        }
        return remaining;
    }

    public static ItemStack extractAny(ServerLevel level, BuildingRecord building, int limit) {
        for (ContainerAt containerAt : loadedContainers(level, building)) {
            Container container = containerAt.container();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty() || stack.is(ModItems.CREATIVE_SUPPLY_CORE.get())) continue;
                ItemStack extracted = stack.split(Math.min(limit, stack.getCount()));
                container.setChanged();
                return extracted;
            }
        }
        return ItemStack.EMPTY;
    }

    public static ItemStack extractItem(ServerLevel level, BuildingRecord building, Item item, int limit) {
        if (limit <= 0) return ItemStack.EMPTY;
        ItemStack extracted = ItemStack.EMPTY;
        for (ContainerAt containerAt : loadedContainers(level, building)) {
            Container container = containerAt.container();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.is(item)) continue;
                if (!extracted.isEmpty() && !ItemStack.isSameItemSameComponents(extracted, stack)) continue;
                if (extracted.isEmpty()) extracted = stack.copyWithCount(0);
                int moved = Math.min(limit - extracted.getCount(), stack.getCount());
                if (moved <= 0) return extracted;
                stack.shrink(moved);
                extracted.grow(moved);
                container.setChanged();
                if (extracted.getCount() >= limit) return extracted;
            }
        }
        return extracted;
    }

    public static ItemStack extractItemOrCreative(ServerLevel level, BuildingRecord building, Item item, int limit) {
        ItemStack extracted = extractItem(level, building, item, limit);
        if (!extracted.isEmpty()) return extracted;
        return hasCreativeSupply(level, building) ? new ItemStack(item, Math.max(1, limit)) : ItemStack.EMPTY;
    }

    public static ItemStack extractAnyForResident(
            ServerLevel level, BuildingRecord building, LivingEntity entity, int limit
    ) {
        if (limit <= 0) return ItemStack.EMPTY;
        for (ContainerAt containerAt : interactableContainers(level, building, entity)) {
            Container container = containerAt.container();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty() || stack.is(ModItems.CREATIVE_SUPPLY_CORE.get())) continue;
                ItemStack extracted = stack.split(Math.min(limit, stack.getCount()));
                container.setChanged();
                return extracted;
            }
        }
        return ItemStack.EMPTY;
    }

    public static ItemStack extractItemForResident(
            ServerLevel level, BuildingRecord building, LivingEntity entity, Item item, int limit
    ) {
        return extractItemForResident(level, building, entity, item, limit, stack -> limit);
    }

    /** Capacity is checked against the actual stack components before anything leaves storage. */
    public static ItemStack extractItemForResident(
            ServerLevel level, BuildingRecord building, LivingEntity entity, Item item, int limit,
            java.util.function.ToIntFunction<ItemStack> capacity
    ) {
        if (limit <= 0) return ItemStack.EMPTY;
        ItemStack extracted = ItemStack.EMPTY;
        for (ContainerAt containerAt : interactableContainers(level, building, entity)) {
            Container container = containerAt.container();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.is(item)) continue;
                if (!extracted.isEmpty() && !ItemStack.isSameItemSameComponents(extracted, stack)) continue;
                int allowed = Math.min(limit, capacity.applyAsInt(stack));
                if (allowed <= 0) continue;
                if (extracted.isEmpty()) extracted = stack.copyWithCount(0);
                int moved = Math.min(allowed - extracted.getCount(), stack.getCount());
                if (moved <= 0) return extracted;
                stack.shrink(moved);
                extracted.grow(moved);
                container.setChanged();
                if (extracted.getCount() >= limit) return extracted;
            }
        }
        return extracted;
    }

    public static ItemStack extractItemOrCreativeForResident(
            ServerLevel level, BuildingRecord building, LivingEntity entity, Item item, int limit
    ) {
        if(WarehouseStock.applies(building) && !canInteract(level,building,entity))return ItemStack.EMPTY;
        ItemStack extracted = extractItemForResident(level, building, entity, item, limit);
        if (!extracted.isEmpty()) return extracted;
        return hasCreativeSupply(level, building) ? new ItemStack(item, Math.max(1, limit)) : ItemStack.EMPTY;
    }

    public static ItemStack insertForResident(
            ServerLevel level, BuildingRecord warehouse, LivingEntity entity, ItemStack offered
    ) {
        if(WarehouseStock.applies(warehouse))return canInteract(level,warehouse,entity)?insert(level,warehouse,offered):offered.copy();
        if (offered.isEmpty() || !warehouse.operational() || !warehouse.accepts(offered)) return offered.copy();
        var containers=interactableContainers(level,warehouse,entity);
        int allowed=com.hkin.exiledsettlement.settlement.WarehouseStewardService.admission(level,warehouse,offered,containers);
        if(allowed<=0)return offered.copy();
        var rem=insertIntoContainers(containers,offered.copyWithCount(allowed));
        return offered.copyWithCount(offered.getCount()-allowed+rem.getCount());
    }

    public static ItemStack insertIntoBuilding(ServerLevel level, BuildingRecord building, ItemStack offered) {
        if(WarehouseStock.applies(building))return insert(level,building,offered);
        if (offered.isEmpty() || !building.operational()) return offered.copy();
        return insertIntoContainers(loadedContainers(level, building), offered);
    }

    public static boolean hasCreativeSupply(ServerLevel level, BuildingRecord warehouse) {
        if (!warehouse.operational() || !warehouse.type().isStorage()) return false;
        if (WarehouseStock.applies(warehouse))
            return stock(level, warehouse).summary().getOrDefault(ModItems.CREATIVE_SUPPLY_CORE.get(), 0) > 0;
        for (ContainerAt containerAt : loadedContainers(level, warehouse)) {
            Container container = containerAt.container();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (container.getItem(slot).is(ModItems.CREATIVE_SUPPLY_CORE.get())) return true;
            }
        }
        return false;
    }

    public static int countItem(ServerLevel level, BuildingRecord building, Item item) {
        if(WarehouseStock.applies(building))return stock(level,building).summary().getOrDefault(item,0);
        int count = 0;
        for (ContainerAt containerAt : loadedContainers(level, building)) {
            Container container = containerAt.container();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.is(item)) count += stack.getCount();
            }
        }
        return count;
    }

    public static int countCategory(ServerLevel level, BuildingRecord building, WarehouseCategory category) {
        if(WarehouseStock.applies(building))return stock(level,building).summary().entrySet().stream()
                .filter(e->WarehouseCategory.classify(new ItemStack(e.getKey()))==category).mapToInt(Map.Entry::getValue).sum();
        int count = 0;
        for (ContainerAt containerAt : loadedContainers(level, building)) {
            Container container = containerAt.container();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty() && WarehouseCategory.classify(stack) == category) count += stack.getCount();
            }
        }
        return count;
    }

    public static Map<Item, Integer> summarize(ServerLevel level, BuildingRecord building) {
        if(WarehouseStock.applies(building))return stock(level,building).summary();
        Map<Item, Integer> summary = new LinkedHashMap<>();
        for (ContainerAt containerAt : loadedContainers(level, building)) {
            Container container = containerAt.container();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty()) summary.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return summary;
    }

    public static boolean hasAnyItem(ServerLevel level, BuildingRecord building) {
        if(WarehouseStock.applies(building))return stock(level,building).quantity()>0;
        return loadedContainers(level, building).stream().anyMatch(containerAt -> {
            Container container = containerAt.container();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (!container.getItem(slot).isEmpty()) return true;
            }
            return false;
        });
    }

    public static BlockPos nearestContainerPosition(ServerLevel level, BuildingRecord warehouse, BlockPos from) {
        if(requiresStorageInterior(warehouse))return interiorPositions(level,warehouse).stream()
                .min(java.util.Comparator.comparingDouble(p->p.distSqr(from))).orElse(warehouse.position());
        return loadedContainers(level, warehouse).stream()
                .flatMap(containerAt -> accessibleStandingPositions(level, warehouse, containerAt.position(), null).stream())
                .min((left, right) -> Double.compare(left.distSqr(from), right.distSqr(from)))
                .orElse(warehouse.position());
    }

    @Nullable
    public static BlockPos reserveInteractionPosition(
            ServerLevel level, BuildingRecord warehouse, SettlementCitizenEntity entity
    ) {
        return reserveInteractionPosition(level, warehouse, entity, ignored -> true);
    }

    @Nullable
    public static BlockPos reserveExtractionPosition(
            ServerLevel level, BuildingRecord warehouse, SettlementCitizenEntity entity, @Nullable Item item
    ) {
        return reserveInteractionPosition(level, warehouse, entity,
                containerAt -> containerHas(containerAt.container(), item));
    }

    @Nullable
    public static BlockPos reserveExtractionPosition(
            ServerLevel level, BuildingRecord warehouse, SettlementCitizenEntity entity, Set<Item> items
    ) {
        return reserveInteractionPosition(level, warehouse, entity,
                containerAt -> containerHasAny(containerAt.container(), items));
    }

    @Nullable
    public static BlockPos reserveInsertionPosition(
            ServerLevel level, BuildingRecord warehouse, SettlementCitizenEntity entity, ItemStack stack
    ) {
        return reserveInteractionPosition(level, warehouse, entity,
                containerAt -> containerCanAccept(containerAt.container(), stack));
    }

    public static void releaseInteractionPosition(ServerLevel level, LivingEntity entity) {
        CitizenNavigationReservationService.releaseStation(level, entity.getUUID());
    }

    @Nullable
    private static BlockPos reserveInteractionPosition(ServerLevel level, BuildingRecord warehouse,
            SettlementCitizenEntity entity, Predicate<ContainerAt> containerFilter) {
        if(requiresStorageInterior(warehouse)) {
            if(canInteract(level,warehouse,entity))return entity.navigationFeetPosition();
            return StorageAccessService.destination(level,warehouse,entity);
        }
        if (interactableContainers(level, warehouse, entity).stream().anyMatch(containerFilter)) return entity.navigationFeetPosition();
        List<BlockPos> candidates = new ArrayList<>();
        entrancesForApproach(level, warehouse).forEach(entrance -> candidates.add(entrance.position().relative(entrance.outward())));
        if (candidates.isEmpty() || !warehouse.type().isStorage()) loadedContainers(level, warehouse).stream()
                .filter(containerFilter).limit(8).forEach(container -> candidates.addAll(
                        accessibleStandingPositions(level, warehouse, container.position(), entity)));
        return candidates.stream().distinct().filter(position -> canStand(level, position))
                .sorted(java.util.Comparator.comparingDouble(position -> position.distSqr(entity.blockPosition())))
                .limit(3).filter(entity::hasReachablePathTo).findFirst().orElse(null);
    }

    @Nullable
    public static BlockPos approachPosition(
            ServerLevel level, BuildingRecord building, SettlementCitizenEntity entity
    ) {
        if (requiresStorageInterior(building) ? canInteract(level,building,entity)
                : distanceToBoundsSquared(entity.blockPosition(), building) <= STATION_RESERVATION_RADIUS_SQUARED) {
            return null;
        }
        // Approach first, reserve only within the local interaction window. Old
        // shell templates have real doors but no explicit entrance metadata.
        List<BlockPos> candidates = new ArrayList<>();
        for (BlueprintEntrance entrance : entrancesForApproach(level, building)) {
            candidates.add(entrance.position().relative(entrance.outward()));
        }
        if (candidates.isEmpty()) {
            loadedContainers(level, building).forEach(container -> candidates.addAll(
                    accessibleStandingPositions(level, building, container.position(), entity)));
        }
        if(requiresStorageInterior(building)) {
            var nearest=candidates.stream().filter(p->canStand(level,p)).min(java.util.Comparator.comparingDouble(p->p.distSqr(entity.blockPosition()))).orElse(null);
            return nearest!=null && nearest.distSqr(entity.blockPosition())>4 ? nearest:null;
        }
        return candidates.stream().filter(position -> canStand(level, position))
                .distinct().sorted(java.util.Comparator.comparingDouble(
                        position -> position.distSqr(entity.blockPosition())))
                .filter(entity::hasReachablePathTo).findFirst().orElse(null);
    }

    private static List<BlueprintEntrance> entrancesForApproach(ServerLevel level, BuildingRecord building) {
        if (!BlueprintTemplates.supports(building.type())) return List.of();
        var template = BlueprintTemplates.createForBuilding(building);
        if (!template.entrances().isEmpty()) return template.entrances();
        List<BlueprintEntrance> entrances = new ArrayList<>();
        for (var placement : template.placements()) {
            var state = level.getBlockState(placement.position());
            Direction facing;
            if (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                facing = state.getValue(DoorBlock.FACING);
            } else if (state.getBlock() instanceof FenceGateBlock) {
                facing = state.getValue(FenceGateBlock.FACING);
            } else continue;
            Direction opposite = facing.getOpposite();
            if (distanceToBoundary(placement.position(), building, opposite)
                    < distanceToBoundary(placement.position(), building, facing)) facing = opposite;
            entrances.add(new BlueprintEntrance(placement.position(), facing));
        }
        return entrances;
    }

    private static int distanceToBoundary(BlockPos position, BuildingRecord building, Direction direction) {
        return switch (direction) {
            case EAST -> building.maxCorner().getX() - position.getX();
            case WEST -> position.getX() - building.minCorner().getX();
            case SOUTH -> building.maxCorner().getZ() - position.getZ();
            case NORTH -> position.getZ() - building.minCorner().getZ();
            default -> Integer.MAX_VALUE;
        };
    }

    @Nullable
    public static BlockPos waitingPosition(
            ServerLevel level, BuildingRecord warehouse, SettlementCitizenEntity entity
    ) {
        long gameTime = level.getGameTime();
        List<BlockPos> candidates = entrancesForApproach(level, warehouse).stream()
                .flatMap(entrance -> java.util.stream.IntStream.rangeClosed(2, 6).boxed()
                        .flatMap(distance -> java.util.stream.IntStream.rangeClosed(-2, 2)
                                .mapToObj(side -> entrance.position()
                                        .relative(entrance.outward(), distance)
                                        .relative(entrance.outward().getClockWise(), side))))
                .filter(position -> canStand(level, position))
                .distinct()
                .sorted((left, right) -> Double.compare(left.distSqr(entity.blockPosition()),
                        right.distSqr(entity.blockPosition())))
                .toList();
        BlockPos existing = CitizenNavigationReservationService.stationFor(level, entity.getUUID(), gameTime);
        if (existing != null && candidates.contains(existing)
                && CitizenNavigationReservationService.claimStation(
                level, entity.getUUID(), existing, entity.blockPosition(), gameTime)) return existing;
        for (BlockPos candidate : candidates) {
            if (!entity.hasReachablePathTo(candidate)) continue;
            if (CitizenNavigationReservationService.claimStation(
                    level, entity.getUUID(), candidate, entity.blockPosition(), gameTime)) return candidate;
        }
        return null;
    }

    public static void abandonInteractionPosition(ServerLevel level, LivingEntity entity) {
        CitizenNavigationReservationService.abandonStation(level, entity.getUUID(), level.getGameTime());
    }

    public static boolean canInteract(
            ServerLevel level, BuildingRecord warehouse, LivingEntity entity
    ) {
        return !interactableContainers(level, warehouse, entity).isEmpty();
    }

    /** A camp's bulk inventory is accessed at its barrel, not inside a nonexistent warehouse room. */
    private static boolean requiresStorageInterior(BuildingRecord building) {
        return WarehouseStock.applies(building) && building.type()!=BuildingType.WAGON;
    }

    private static List<ContainerAt> interactableContainers(ServerLevel level, BuildingRecord warehouse, LivingEntity entity) {
        if (!warehouse.active() || !warehouse.operational() && !(WarehouseStock.applies(warehouse) && warehouse.demolitionQueued())) return List.of();
        if(requiresStorageInterior(warehouse)) {
            var p=entity.blockPosition();
            boolean inside=(warehouse.type()!=BuildingType.MINE || entity.getY()<warehouse.position().getY()-13)
                    && p.getX()>warehouse.minCorner().getX() && p.getX()<warehouse.maxCorner().getX()
                    && p.getZ()>warehouse.minCorner().getZ() && p.getZ()<warehouse.maxCorner().getZ()
                    && entity.getY()>=warehouse.minCorner().getY() && entity.getY()<warehouse.maxCorner().getY()
                    && level.hasChunkAt(p) && !(level.getBlockState(p).getBlock() instanceof DoorBlock)
                    && !(level.getBlockState(p).getBlock() instanceof FenceGateBlock) && level.noCollision(entity);
            return inside?loadedContainers(level,warehouse):List.of();
        }
        List<ContainerAt> containers = loadedContainers(level, warehouse);
        if (warehouse.type().isStorage()) {
            if (!warehouse.minCorner().equals(warehouse.maxCorner()) && insideStructure(warehouse, entity.blockPosition())
                    && entity.getY()>=warehouse.minCorner().getY()-1 && entity.getY()<=warehouse.maxCorner().getY()+1) return containers;
        }
        if (!warehouse.minCorner().equals(warehouse.maxCorner()) && entrancesForApproach(level, warehouse).stream().anyMatch(entrance ->
                CitizenInteractionRange.visible(entity, entrance.position()))) return containers;
        var visible = containers.stream().filter(container -> CitizenInteractionRange.visible(entity, container.position())).toList();
        // One real warehouse loading point serves all of its registered containers.
        return warehouse.type().isStorage() && !visible.isEmpty() ? containers : visible;
    }

    private static List<BlockPos> accessibleStandingPositions(
            ServerLevel level, BuildingRecord building, BlockPos container,
            @Nullable LivingEntity entity
    ) {
        List<BlockPos> positions = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int down = 0; down <= (building.type().isStorage() ? 3 : 0); down++) {
            BlockPos candidate = container.relative(direction).below(down);
            if (canStand(level, candidate)
                    && insideStructure(building, candidate)
                    && visibleFromStation(level, candidate, container, entity)) {
                positions.add(candidate.immutable());
            }
            }
        }
        return positions;
    }

    private static boolean isLegalStation(
            ServerLevel level, BuildingRecord building, LivingEntity entity, BlockPos station
    ) {
        return isLegalStation(level, building, entity, station, ignored -> true);
    }

    private static boolean isLegalStation(
            ServerLevel level, BuildingRecord building, LivingEntity entity, BlockPos station,
            Predicate<ContainerAt> containerFilter
    ) {
        if (!canStand(level, station) || !insideStructure(building, station)) return false;
        if (building.type().isStorage()) {
            List<ContainerAt> containers = loadedContainers(level, building);
            return containers.stream().anyMatch(containerFilter)
                    && containers.stream().anyMatch(container ->
                    accessibleStandingPositions(level, building, container.position(), entity).contains(station));
        }
        return loadedContainers(level, building).stream().filter(containerFilter).anyMatch(container ->
                station.distManhattan(container.position()) == 1
                        && visibleFromStation(level, station, container.position(), entity));
    }

    private static boolean containerHas(Container container, @Nullable Item item) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(ModItems.CREATIVE_SUPPLY_CORE.get()) && item != null) return true;
            if (!stack.isEmpty() && (item == null || stack.is(item))) return true;
        }
        return false;
    }

    private static boolean containerHasAny(Container container, Set<Item> items) {
        if (items.isEmpty()) return containerHas(container, null);
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(ModItems.CREATIVE_SUPPLY_CORE.get())) return true;
            if (!stack.isEmpty() && items.contains(stack.getItem())) return true;
        }
        return false;
    }

    private static boolean containerCanAccept(Container container, ItemStack stack) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.canPlaceItem(slot, stack)) continue;
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(existing, stack)
                    && existing.getCount() < Math.min(existing.getMaxStackSize(), container.getMaxStackSize(existing))) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack insertIntoContainers(List<ContainerAt> containers, ItemStack offered) {
        ItemStack remaining = offered.copy();
        for (ContainerAt containerAt : containers) {
            Container container = containerAt.container();
            for (int slot = 0; slot < container.getContainerSize() && !remaining.isEmpty(); slot++) {
                ItemStack existing = container.getItem(slot);
                if (existing.isEmpty() || !container.canPlaceItem(slot, remaining)
                        || !ItemStack.isSameItemSameComponents(existing, remaining)) continue;
                int limit = Math.min(existing.getMaxStackSize(), container.getMaxStackSize(existing));
                int moved = Math.min(limit - existing.getCount(), remaining.getCount());
                if (moved > 0) {
                    existing.grow(moved);
                    remaining.shrink(moved);
                    container.setChanged();
                }
            }
        }
        for (ContainerAt containerAt : containers) {
            Container container = containerAt.container();
            for (int slot = 0; slot < container.getContainerSize() && !remaining.isEmpty(); slot++) {
                if (!container.getItem(slot).isEmpty() || !container.canPlaceItem(slot, remaining)) continue;
                int limit = Math.min(remaining.getMaxStackSize(), container.getMaxStackSize(remaining));
                int moved = Math.min(limit, remaining.getCount());
                container.setItem(slot, remaining.copyWithCount(moved));
                remaining.shrink(moved);
                container.setChanged();
            }
        }
        return remaining;
    }

    private static boolean insideStructure(BuildingRecord building, BlockPos position) {
        if (building.minCorner().equals(building.maxCorner())) return true;
        if (building.type() == BuildingType.WAGON && building.templateVersion() >= 3) {
            // A two-barrel camp store has no indoor room: use its adjacent apron.
            return position.getX() >= building.minCorner().getX()-1 && position.getX() <= building.maxCorner().getX()+1
                    && position.getZ() >= building.minCorner().getZ()-1 && position.getZ() <= building.maxCorner().getZ()+1;
        }
        return position.getX() >= building.minCorner().getX()
                && position.getX() <= building.maxCorner().getX()
                && position.getZ() >= building.minCorner().getZ()
                && position.getZ() <= building.maxCorner().getZ();
    }

    public static List<BlockPos> interiorPositions(ServerLevel level,BuildingRecord b) {
        var result=new ArrayList<BlockPos>();
        for(int y=b.minCorner().getY();y<(b.type()==BuildingType.MINE?b.position().getY()-13:b.maxCorner().getY());y++)
            for(int x=b.minCorner().getX()+1;x<b.maxCorner().getX();x++)
                for(int z=b.minCorner().getZ()+1;z<b.maxCorner().getZ();z++) {
                    var p=new BlockPos(x,y,z);if(level.hasChunkAt(p) && canStand(level,p))result.add(p);
                }
        return result;
    }

    private static boolean visibleFromStation(
            ServerLevel level, BlockPos station, BlockPos container, @Nullable LivingEntity entity
    ) {
        Vec3 eye = new Vec3(station.getX() + 0.5D, station.getY() + 1.62D, station.getZ() + 0.5D);
        Vec3 center = Vec3.atCenterOf(container);
        // Preliminary station selection has no resident yet. Minecraft 1.21.1
        // does not accept null in the entity-based ClipContext constructor.
        BlockHitResult hit = level.clip(new ClipContext(
                eye, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                entity == null ? net.minecraft.world.phys.shapes.CollisionContext.empty()
                        : net.minecraft.world.phys.shapes.CollisionContext.of(entity)));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(container);
    }

    public static boolean canStand(ServerLevel level, BlockPos position) {
        return level.hasChunkAt(position)
                && level.getBlockState(position).getCollisionShape(level, position).isEmpty()
                && level.getBlockState(position.above()).getCollisionShape(level, position.above()).isEmpty()
                && level.getFluidState(position).isEmpty()
                && level.getBlockState(position.below()).isFaceSturdy(level, position.below(), Direction.UP);
    }

    private static double distanceToBoundsSquared(BlockPos position, BuildingRecord building) {
        int dx = position.getX() < building.minCorner().getX()
                ? building.minCorner().getX() - position.getX()
                : Math.max(0, position.getX() - building.maxCorner().getX());
        int dz = position.getZ() < building.minCorner().getZ()
                ? building.minCorner().getZ() - position.getZ()
                : Math.max(0, position.getZ() - building.maxCorner().getZ());
        return (double) dx * dx + (double) dz * dz;
    }

    public record ContainerAt(BlockPos position, Container container) {
    }
}
