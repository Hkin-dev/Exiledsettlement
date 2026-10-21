package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.building.BuildingProductionService;
import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.building.SettlementInventoryService;
import com.hkin.exiledsettlement.building.WarehouseInventoryService;
import com.hkin.exiledsettlement.region.PastureResidentService;
import com.hkin.exiledsettlement.region.ProductionRegionService;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Real warehouse food, two protected feeding actions, then vanilla mating and birth. */
public final class PastureBreedingService {
    private PastureBreedingService() {}

    public static final int FEED_WORK_TICKS = 40;
    private static final int RETRY_TICKS = 200;
    private static final int TASK_TIMEOUT_TICKS = 2400;

    /** Only task facts are stored here; every physical food stack stays in the resident inventory. */
    public static final class State {
        public UUID pastureId;
        public UUID firstAnimal;
        public UUID secondAnimal;
        public UUID sourceId;
        public ResourceLocation foodId;
        public boolean firstFed;
        public int stage; // 0 none, 1 supply, 2 feed
        public double workTicks;
        public long retryAt;
        public long startedAt;
        public long lastWorkTick = -1; // transient: never count unloaded time as labour

        public boolean active() {
            return stage > 0 && pastureId != null && firstAnimal != null && secondAnimal != null
                    && !firstAnimal.equals(secondAnimal) && foodId != null;
        }

        public void clear() {
            PasturePairApproachGoal.release(this);
            pastureId = null;
            firstAnimal = null;
            secondAnimal = null;
            sourceId = null;
            foodId = null;
            firstFed = false;
            stage = 0;
            workTicks = 0;
            startedAt = 0;
            lastWorkTick = -1;
            // Retrying a failed task must not immediately repeat the same lookup.
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("RetryAt", retryAt);
            if (!active()) return tag;
            tag.putUUID("Pasture", pastureId);
            tag.putUUID("FirstAnimal", firstAnimal);
            tag.putUUID("SecondAnimal", secondAnimal);
            if (sourceId != null) tag.putUUID("Source", sourceId);
            tag.putString("Food", foodId.toString());
            tag.putBoolean("FirstFed", firstFed);
            tag.putInt("Stage", stage);
            tag.putDouble("WorkTicks", workTicks);
            tag.putLong("StartedAt", startedAt);
            return tag;
        }

        public void load(CompoundTag tag) {
            clear();
            retryAt = Math.max(0, tag.getLong("RetryAt"));
            if (!tag.hasUUID("Pasture") || !tag.hasUUID("FirstAnimal") || !tag.hasUUID("SecondAnimal")) return;
            pastureId = tag.getUUID("Pasture");
            firstAnimal = tag.getUUID("FirstAnimal");
            secondAnimal = tag.getUUID("SecondAnimal");
            sourceId = tag.hasUUID("Source") ? tag.getUUID("Source") : null;
            foodId = ResourceLocation.tryParse(tag.getString("Food"));
            firstFed = tag.getBoolean("FirstFed");
            stage = tag.getInt("Stage");
            double savedWork = tag.getDouble("WorkTicks");
            workTicks = Double.isFinite(savedWork) ? Math.max(0, Math.min(FEED_WORK_TICKS, savedWork)) : 0;
            startedAt = Math.max(0, tag.getLong("StartedAt"));
            if (!active() || stage > 2 || !BuiltInRegistries.ITEM.containsKey(foodId)) clear();
        }
    }

    public static boolean assign(
            ServerLevel level, SettlementRecord settlement, CitizenRecord citizen, Set<UUID> claimed
    ) {
        State job = citizen.pastureJob;
        if (citizen.workState() != CitizenWorkState.IDLE || level.getGameTime() < job.retryAt) return false;
        BuildingRecord pasture = CitizenWorkService.workplace(settlement, citizen, BuildingType.PASTURE_AREA);
        if (pasture == null || pasture.demolitionQueued()) return false;
        List<Animal> residents = residents(level, pasture);
        if (!hasBirthRoom(settlement, pasture, residents, citizen)) {
            waitFor(citizen, level, "PASTURE_BREEDING_CAPACITY");
            return false;
        }

        Set<UUID> reserved = reservedAnimals(settlement, citizen);
        List<Animal> adults = residents.stream()
                .filter(a -> eligible(settlement, pasture, a) && a.canFallInLove()
                        && !a.isBaby() && !claimed.contains(a.getUUID()) && !reserved.contains(a.getUUID()))
                .sorted(Comparator.comparingDouble(a -> a.blockPosition().distSqr(citizen.lastPosition())))
                .toList();
        boolean pairFound = false;
        Set<EntityType<?>> checkedSpecies = new HashSet<>();
        for (int i = 0; i < adults.size(); i++) {
            Animal first = adults.get(i);
            if (checkedSpecies.contains(first.getType())) continue;
            for (int j = i + 1; j < adults.size(); j++) {
                Animal second = adults.get(j);
                if (first.getType() != second.getType()) continue;
                pairFound = true;
                checkedSpecies.add(first.getType());
                FoodSource supply = findFood(level, settlement, citizen, first, second);
                // The four supported species share food rules within a species.
                // Do not rescan every warehouse for every possible animal pair.
                if (supply == null) break;
                boolean needSupply = countFood(citizen, supply.food) < 2;
                CitizenWorkState state = needSupply ? CitizenWorkState.TO_PASTURE_SUPPLY
                        : CitizenWorkState.TO_PASTURE_FEED;
                BlockPos destination = needSupply ? supply.warehouse.position() : first.blockPosition();
                if (!citizen.startTask(state, destination, first.getUUID(), null)) return false;
                job.pastureId = pasture.id();
                job.firstAnimal = first.getUUID();
                job.secondAnimal = second.getUUID();
                job.sourceId = supply.warehouse == null ? null : supply.warehouse.id();
                job.foodId = BuiltInRegistries.ITEM.getKey(supply.food);
                job.firstFed = false;
                job.stage = needSupply ? 1 : 2;
                job.workTicks = 0;
                job.startedAt = level.getGameTime();
                job.lastWorkTick = -1;
                claimed.add(first.getUUID());
                claimed.add(second.getUUID());
                return true;
            }
        }
        waitFor(citizen, level, pairFound ? "PASTURE_NO_FEED" : "PASTURE_WAITING_BREEDING_PAIR");
        return false;
    }

    public static void tick(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity
    ) {
        State job = citizen.pastureJob;
        BuildingRecord pasture = CitizenWorkService.workplace(settlement, citizen, BuildingType.PASTURE_AREA);
        if (!job.active() || pasture == null || pasture.demolitionQueued()
                || !pasture.id().equals(job.pastureId)) {
            fail(level, data, citizen, entity, "PASTURE_TASK_CHANGED");
            return;
        }
        if (level.getGameTime() - job.startedAt > TASK_TIMEOUT_TICKS) {
            fail(level, data, citizen, entity, "PASTURE_FEED_ROUTE_TIMEOUT");
            return;
        }
        Animal first = level.getEntity(job.firstAnimal) instanceof Animal animal ? animal : null;
        Animal second = level.getEntity(job.secondAnimal) instanceof Animal animal ? animal : null;
        // An unloaded target is not permission to load a chunk or replace an animal.
        if (first == null || second == null) {
            job.lastWorkTick = -1;
            CitizenWorkService.setActivity(data, citizen, entity, CitizenActivity.BLOCKED,
                    "PASTURE_ANIMAL_UNAVAILABLE");
            return;
        }
        if (!eligible(settlement, pasture, first) || !eligible(settlement, pasture, second)
                || first.isBaby() || second.isBaby() || first.getType() != second.getType()
                || !second.canFallInLove() || (!job.firstFed && !first.canFallInLove())) {
            fail(level, data, citizen, entity, "PASTURE_TASK_CHANGED");
            return;
        }
        if (job.firstFed && !first.isInLove()) {
            // The first meal was actually eaten. Never refund it or feed that parent twice.
            fail(level, data, citizen, entity, "PASTURE_MATE_WINDOW_ENDED");
            return;
        }
        if (!hasBirthRoom(settlement, pasture, residents(level, pasture), citizen)) {
            fail(level, data, citizen, entity, "PASTURE_BREEDING_CAPACITY");
            return;
        }
        Item food = BuiltInRegistries.ITEM.get(job.foodId);
        if (food == Items.AIR || !first.isFood(new ItemStack(food)) || !second.isFood(new ItemStack(food))) {
            fail(level, data, citizen, entity, "PASTURE_FEED_CHANGED");
            return;
        }
        int required = job.firstFed ? 1 : 2;
        if (job.stage == 1) {
            fetch(level, data, settlement, citizen, entity, pasture, food, required);
            return;
        }
        if (countFood(citizen, food) < required) {
            // Do not replace food consumed or removed by another system with a virtual meal.
            fail(level, data, citizen, entity, "PASTURE_NO_FEED");
            return;
        }
        feed(level, data, settlement, citizen, entity, pasture, first, second, food);
    }

    private static void fetch(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity, BuildingRecord pasture, Item food, int required
    ) {
        State job = citizen.pastureJob;
        int missing = required - countFood(citizen, food);
        if (missing <= 0) {
            startFeeding(level, data, citizen, entity, pasture);
            return;
        }
        BuildingRecord warehouse = settlement.building(job.sourceId);
        if (warehouse == null || !SettlementInventoryService.warehouses(settlement).contains(warehouse)
                || WarehouseInventoryService.countItem(level, warehouse, food) < missing) {
            warehouse = warehouseFor(level, settlement, citizen, food, missing);
            if (warehouse == null) {
                fail(level, data, citizen, entity, "PASTURE_NO_FEED");
                return;
            }
            job.sourceId = warehouse.id();
            data.touch();
        }
        if (citizen.inventoryCapacityFor(new ItemStack(food)) < missing) {
            fail(level, data, citizen, entity, "PASTURE_BACKPACK_FULL");
            return;
        }
        BlockPos station = WarehouseInventoryService.reserveExtractionPosition(level, warehouse, entity, food);
        if (station == null) {
            CitizenWorkService.waitForWarehouseStation(level, data, citizen, entity, warehouse,
                    CitizenActivity.FETCHING_FARM_INPUT);
            return;
        }
        if (!WarehouseInventoryService.canInteract(level, warehouse, entity)) {
            CitizenWorkService.moveToWarehouseStation(level, data, citizen, entity, station,
                    CitizenActivity.FETCHING_FARM_INPUT);
            return;
        }
        ItemStack extracted = WarehouseInventoryService.extractItemForResident(
                level, warehouse, entity, food, missing, citizen::inventoryCapacityFor);
        if (extracted.isEmpty()) {
            fail(level, data, citizen, entity, "PASTURE_NO_FEED");
            return;
        }
        ItemStack remaining = citizen.insertIntoInventory(extracted);
        if (!remaining.isEmpty()) {
            ItemStack rejected = WarehouseInventoryService.insertForResident(level, warehouse, entity, remaining);
            if (!rejected.isEmpty()) net.minecraft.world.level.block.Block.popResource(level, entity.blockPosition(), rejected);
        }
        data.touch();
        if (countFood(citizen, food) >= required) startFeeding(level, data, citizen, entity, pasture);
    }

    private static void startFeeding(
            ServerLevel level, SettlementSavedData data, CitizenRecord citizen,
            SettlementCitizenEntity entity, BuildingRecord pasture
    ) {
        State job = citizen.pastureJob;
        WarehouseInventoryService.releaseInteractionPosition(level, entity);
        entity.stopSettlementNavigation();
        job.stage = 2;
        job.lastWorkTick = -1;
        UUID next = job.firstFed ? job.secondAnimal : job.firstAnimal;
        Animal target = level.getEntity(next) instanceof Animal animal ? animal : null;
        citizen.advancePastureTask(CitizenWorkState.TO_PASTURE_FEED,
                target == null ? pasture.position() : target.blockPosition(), next);
        CitizenWorkService.syncHeldItem(citizen, entity);
        data.touch();
    }

    private static void feed(
            ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            CitizenRecord citizen, SettlementCitizenEntity entity, BuildingRecord pasture,
            Animal first, Animal second, Item food
    ) {
        State job = citizen.pastureJob;
        Animal target = job.firstFed ? second : first;
        if (!PastureResidentService.inside(pasture, entity.blockPosition())
                || entity.distanceToSqr(target) > 6.25D || !entity.hasLineOfSight(target)) {
            job.lastWorkTick = -1;
            PastureWorkService.approach(level, data, citizen, entity, pasture, target);
            return;
        }
        entity.stopSettlementNavigation();
        int foodSlot = foodSlot(citizen, food);
        citizen.selectActiveSlot(foodSlot);
        CitizenWorkService.syncHeldItem(citizen, entity);
        CitizenWorkService.setActivity(data, citizen, entity, CitizenActivity.HERDING, "");
        if (job.firstFed && first.distanceToSqr(second) > 36.0D) {
            job.lastWorkTick = -1;
            job.workTicks = 0;
            if (!PasturePairApproachGoal.attached(job)) {
                ItemStack offered = citizen.inventoryItem(foodSlot).copyWithCount(1);
                if (!ResidentAnimalInteraction.allowed(level, settlement, citizen, entity, second, offered)) {
                    fail(level, data, citizen, entity, "PASTURE_FEED_PROTECTED");
                    return;
                }
                if (!PasturePairApproachGoal.attach(level, settlement, citizen, entity, pasture, first, second)) {
                    fail(level, data, citizen, entity, "PASTURE_TASK_CHANGED");
                    return;
                }
            }
            CitizenWorkService.setActivity(data, citizen, entity, CitizenActivity.HERDING, "PASTURE_PAIR_APPROACH");
            data.touch();
            return;
        }
        long now = level.getGameTime();
        if (job.lastWorkTick >= 0 && now > job.lastWorkTick && now - job.lastWorkTick <= 20) {
            job.workTicks += (now - job.lastWorkTick) * citizen.professionExperience().multiplier(citizen.profession());
        }
        job.lastWorkTick = now;
        data.touch();
        if (job.workTicks < FEED_WORK_TICKS) return;

        ItemStack meal = citizen.inventoryItem(foodSlot).copyWithCount(1);
        boolean firstWasFed = job.firstFed;
        boolean settlementWasActive = settlement.active();
        if (!ResidentAnimalInteraction.allowed(level, settlement, citizen, entity, target, meal)) {
            fail(level, data, citizen, entity, "PASTURE_FEED_PROTECTED");
            return;
        }
        // A protection listener may change entity, inventory or workplace state.
        BuildingRecord currentPasture = CitizenWorkService.workplace(settlement, citizen, BuildingType.PASTURE_AREA);
        if (!job.active() || job.stage != 2 || job.firstFed != firstWasFed
                || citizen.workState() != CitizenWorkState.TO_PASTURE_FEED
                || citizen.profession() != CitizenProfession.HERDSMAN
                || !citizen.alive() || !entity.isAlive() || (settlementWasActive && !settlement.active())
                || currentPasture == null || !currentPasture.id().equals(pasture.id())
                || currentPasture.demolitionQueued() || !pasture.id().equals(job.pastureId)
                || !PastureResidentService.inside(currentPasture, entity.blockPosition())
                || entity.distanceToSqr(target) > 6.25D || !entity.hasLineOfSight(target)
                || !first.getUUID().equals(job.firstAnimal) || !second.getUUID().equals(job.secondAnimal)
                || !BuiltInRegistries.ITEM.getKey(food).equals(job.foodId)
                || !eligible(settlement, pasture, target) || !target.canFallInLove()
                || !target.isFood(meal) || countFood(citizen, food) < (job.firstFed ? 1 : 2)
                || (job.firstFed && first.distanceToSqr(second) > 36.0D)
                || !hasBirthRoom(settlement, pasture, residents(level, pasture), citizen)
                || (job.firstFed && !first.isInLove())
                || !eligible(settlement, pasture, job.firstFed ? first : second)
                || (!job.firstFed && !second.canFallInLove())) {
            fail(level, data, citizen, entity, "PASTURE_TASK_CHANGED");
            return;
        }
        foodSlot = foodSlot(citizen, food);
        if (foodSlot < 0) {
            fail(level, data, citizen, entity, "PASTURE_NO_FEED");
            return;
        }
        ItemStack eaten = citizen.extractInventoryItem(stack -> stack.is(food), 1);
        if (eaten.isEmpty()) return;
        target.setInLove(null);
        entity.swing(InteractionHand.MAIN_HAND);
        data.touch();
        if (!job.firstFed) {
            job.firstFed = true;
            job.workTicks = 0;
            job.lastWorkTick = -1;
            citizen.advancePastureTask(CitizenWorkState.TO_PASTURE_FEED,
                    second.blockPosition(), second.getUUID());
            CitizenWorkService.syncHeldItem(citizen, entity);
        } else {
            job.retryAt = now + RETRY_TICKS;
            CitizenWorkService.clear(data, citizen, entity);
        }
    }

    /** Number of items in this slot reserved for this actual unfinished pair, never an entire stack. */
    public static int keepSupply(CitizenRecord citizen, int slot) {
        State job = citizen.pastureJob;
        if (!job.active() || citizen.profession() != CitizenProfession.HERDSMAN
                || !job.pastureId.equals(citizen.workBuildingId())) return 0;
        Item food = BuiltInRegistries.ITEM.get(job.foodId);
        int remaining = job.firstFed ? 1 : 2;
        for (int i = 0; i <= slot; i++) {
            ItemStack stack = citizen.inventoryItem(i);
            if (!stack.is(food)) continue;
            int keep = Math.min(remaining, stack.getCount());
            if (i == slot) return keep;
            remaining -= keep;
            if (remaining <= 0) return 0;
        }
        return 0;
    }

    public static void cancel(CitizenRecord citizen) {
        citizen.pastureJob.clear();
    }

    private static void fail(
            ServerLevel level, SettlementSavedData data, CitizenRecord citizen,
            SettlementCitizenEntity entity, String reason
    ) {
        citizen.pastureJob.retryAt = level.getGameTime() + RETRY_TICKS;
        CitizenWorkService.clear(data, citizen, entity);
        CitizenWorkService.setActivity(data, citizen, entity, CitizenActivity.BLOCKED, reason);
        data.touch();
    }

    private static void waitFor(CitizenRecord citizen, ServerLevel level, String reason) {
        citizen.pastureJob.retryAt = level.getGameTime() + RETRY_TICKS;
        citizen.updateWorkFailure(reason);
    }

    private static List<Animal> residents(ServerLevel level, BuildingRecord pasture) {
        return level.getEntitiesOfClass(Animal.class, CitizenWorkService.buildingArea(pasture, 0),
                a -> a.isAlive() && PastureResidentService.inside(pasture, a.blockPosition())
                        && ProductionRegionService.matchesAnimal(pasture, a));
    }

    static boolean eligible(SettlementRecord settlement, BuildingRecord pasture, Animal animal) {
        return animal.isAlive() && !animal.isRemoved() && !animal.hasCustomName() && !animal.isLeashed()
                && !animal.isPassenger() && !animal.isVehicle() && !animal.isNoAi()
                && (!(animal instanceof TamableAnimal tame) || !tame.isTame())
                && PastureResidentService.inside(pasture, animal.blockPosition())
                && ProductionRegionService.matchesAnimal(pasture, animal)
                && (PastureResidentService.ownedBy(settlement, pasture, animal)
                        || PastureResidentService.eligibleForClaim(animal));
    }

    private static Set<UUID> reservedAnimals(SettlementRecord settlement, CitizenRecord current) {
        Set<UUID> ids = new HashSet<>();
        for (CitizenRecord other : settlement.citizens()) {
            if (other == current || !other.alive() || !other.pastureJob.active()) continue;
            ids.add(other.pastureJob.firstAnimal);
            ids.add(other.pastureJob.secondAnimal);
        }
        return ids;
    }

    private static boolean hasBirthRoom(
            SettlementRecord settlement, BuildingRecord pasture, List<Animal> residents, CitizenRecord current
    ) {
        Set<UUID> taskAnimals = new HashSet<>();
        int pending = 0;
        for (CitizenRecord worker : settlement.citizens()) {
            State job = worker.pastureJob;
            if (!worker.alive() || !job.active() || !pasture.id().equals(job.pastureId)) continue;
            taskAnimals.add(job.firstAnimal);
            taskAnimals.add(job.secondAnimal);
            if (worker != current) pending++;
        }
        Map<EntityType<?>, Integer> inLove = new HashMap<>();
        for (Animal animal : residents) {
            if (animal.isInLove() && !taskAnimals.contains(animal.getUUID())) {
                inLove.merge(animal.getType(), 1, Integer::sum);
            }
        }
        int courting = inLove.values().stream().mapToInt(count -> (count + 1) / 2).sum();
        return residents.size() + pending + courting < BuildingProductionService.animalCapacity(pasture) + 1;
    }

    private record FoodSource(Item food, BuildingRecord warehouse) {}

    private static FoodSource findFood(
            ServerLevel level, SettlementRecord settlement, CitizenRecord citizen, Animal first, Animal second
    ) {
        Set<Item> candidates = new java.util.LinkedHashSet<>();
        for (ItemStack stack : citizen.inventory()) if (!stack.isEmpty()) candidates.add(stack.getItem());
        for (BuildingRecord warehouse : SettlementInventoryService.warehouses(settlement)) {
            candidates.addAll(WarehouseInventoryService.summarize(level, warehouse).keySet());
        }
        for (Item food : candidates) {
            ItemStack sample = new ItemStack(food);
            if (!first.isFood(sample) || !second.isFood(sample)) continue;
            int missing = 2 - countFood(citizen, food);
            if (missing <= 0) return new FoodSource(food, null);
            if (citizen.inventoryCapacityFor(sample) < missing) continue;
            BuildingRecord source = warehouseFor(level, settlement, citizen, food, missing);
            if (source != null) return new FoodSource(food, source);
        }
        return null;
    }

    private static BuildingRecord warehouseFor(
            ServerLevel level, SettlementRecord settlement, CitizenRecord citizen, Item food, int missing
    ) {
        return SettlementInventoryService.warehouses(settlement).stream()
                .filter(w -> WarehouseInventoryService.countItem(level, w, food) >= missing)
                .min(Comparator.comparingDouble(w -> w.position().distSqr(citizen.lastPosition())))
                .orElse(null);
    }

    static int countFood(CitizenRecord citizen, Item food) {
        int count = 0;
        for (int slot = 0; slot < CitizenRecord.INVENTORY_SIZE; slot++) {
            if (citizen.pitReserveSlot(slot)) continue;
            ItemStack stack = citizen.inventoryItem(slot);
            if (stack.is(food)) count += stack.getCount();
        }
        return count;
    }

    private static int foodSlot(CitizenRecord citizen, Item food) {
        for (int slot = 0; slot < CitizenRecord.INVENTORY_SIZE; slot++) {
            if (!citizen.pitReserveSlot(slot) && citizen.inventoryItem(slot).is(food)) return slot;
        }
        return -1;
    }
}
