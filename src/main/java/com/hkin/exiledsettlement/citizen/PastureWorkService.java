package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.region.PastureResidentService;
import com.hkin.exiledsettlement.region.ProductionRegionService;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;

import java.util.*;

/** One pasture's production queue. All effects happen at a live animal, after real labor. */
public final class PastureWorkService {
    private static final Map<CitizenRecord, Action> ACTIONS = new WeakHashMap<>();
    private static final Map<CitizenRecord, Map<UUID, Long>> DEFERRED = new WeakHashMap<>();
    private static final Map<CitizenRecord, Entry> ENTRIES = new WeakHashMap<>();
    private record Entry(UUID pasture, net.minecraft.core.BlockPos target, long until) {}
    private static final int LABOR_TICKS = 100;
    private static final int TARGET_TIMEOUT = 1200;
    private PastureWorkService() {}

    private static final class Action {
        final UUID target;
        final long started;
        long lastTick;
        double labor;
        Action(UUID target, long now) { this.target = target; started = lastTick = now; }
    }

    public static boolean manageable(SettlementRecord settlement, BuildingRecord pasture, Animal animal) {
        return animal.isAlive() && !animal.hasCustomName() && !animal.isLeashed()
                && !animal.isPassenger() && !animal.isVehicle() && !animal.isNoAi()
                && (!(animal instanceof TamableAnimal tame) || !tame.isTame())
                && (!PastureResidentService.hasOwnership(animal)
                    || PastureResidentService.ownedBy(settlement, pasture, animal));
    }

    public static List<Animal> residents(ServerLevel level, BuildingRecord pasture) {
        return level.getEntitiesOfClass(Animal.class, CitizenWorkService.buildingArea(pasture, 0),
                a -> a.isAlive() && PastureResidentService.inside(pasture, a.blockPosition())
                        && ProductionRegionService.matchesAnimal(pasture, a));
    }

    /** Reach an interior standing position before following an animal against a fence. */
    public static void approach(ServerLevel level, SettlementSavedData data, CitizenRecord citizen,
                                SettlementCitizenEntity worker, BuildingRecord pasture, Animal animal) {
        if (!PastureResidentService.inside(pasture, worker.blockPosition())) {
            Entry entry = ENTRIES.get(citizen);
            if (entry == null || !entry.pasture.equals(pasture.id()) || level.getGameTime() >= entry.until) {
                var target = PastureCaptureService.destination(level, pasture, worker.blockPosition());
                entry = new Entry(pasture.id(), target, level.getGameTime() + 80);
                ENTRIES.put(citizen, entry);
            }
            if (entry.target == null) {
                worker.stopSettlementNavigation();
                CitizenWorkService.setActivity(data, citizen, worker, CitizenActivity.BLOCKED, "PASTURE_NO_SAFE_ENTRANCE");
                return;
            }
            CitizenWorkService.move(data, citizen, worker, entry.target, CitizenActivity.HERDING);
            return;
        }
        ENTRIES.remove(citizen);
        CitizenWorkService.move(data, citizen, worker, animal.blockPosition(), CitizenActivity.HERDING);
    }

    public static boolean canCull(SettlementRecord settlement, BuildingRecord pasture,
                                  List<Animal> animals, Animal target) {
        if (!manageable(settlement, pasture, target) || target.isBaby() || target.isInLove()
                || !ProductionRegionService.animalSurplus(pasture, animals, target)) return false;
        // A player's protected animal occupies space but cannot replace one of
        // this pasture's two usable breeding adults.
        return animals.stream().filter(animal -> animal.getType() == target.getType()
                && !animal.isBaby() && manageable(settlement, pasture, animal)).count() > 2;
    }

    public static boolean assign(ServerLevel level, SettlementRecord settlement,
                                 CitizenRecord citizen, Set<UUID> claimed) {
        String priorFailure = citizen.workFailure();
        if (citizen.workFailure().startsWith("PASTURE_")) citizen.updateWorkFailure("");
        var pasture = CitizenWorkService.workplace(settlement, citizen, BuildingType.PASTURE_AREA);
        if (pasture == null || pasture.demolitionQueued()) {
            citizen.updateWorkFailure("PASTURE_NOT_READY");
            return false;
        }
        var animals = residents(level, pasture);
        var available = animals.stream().filter(a -> manageable(settlement, pasture, a))
                .filter(a -> !claimed.contains(a.getUUID()) && !deferred(level, citizen, a))
                .sorted(Comparator.comparingDouble(a -> a.blockPosition().distSqr(citizen.lastPosition())))
                .toList();
        for (var animal : available) {
            if (canCull(settlement, pasture, animals, animal)) {
                return start(citizen, animal, claimed);
            }
        }
        boolean woolReady = available.stream().anyMatch(a -> a instanceof Sheep sheep && sheep.readyForShearing());
        if (citizen.hasInventoryItem(stack -> stack.is(Items.SHEARS))) {
            for (var animal : available) {
                if (animal instanceof Sheep sheep && sheep.readyForShearing()
                        && citizen.inventoryCapacityFor(wool(sheep)) >= 3) return start(citizen, sheep, claimed);
            }
        }
        // Product cargo must be physically delivered before a long feeding/capture trip.
        if (CitizenWorkService.deliverableSlot(settlement, citizen, false) >= 0) {
            citizen.updateWorkFailure("PASTURE_DELIVER_PRODUCTS");
            return false;
        }
        if (PastureBreedingService.assign(level, settlement, citizen, claimed)) return true;
        long incoming = settlement.citizens().stream().filter(c -> c.workState() == CitizenWorkState.TO_CAPTURE
                && pasture.id().equals(c.targetBuildingId())).count();
        {
            var bounds = new AABB(pasture.position()).inflate(32, 8, 32);
            for (var animal : level.getEntitiesOfClass(Animal.class, bounds,
                    a -> manageable(settlement, pasture, a) && a.canBeLeashed()
                            && (!a.isBaby() || PastureResidentService.ownedBy(settlement, pasture, a))
                            && ProductionRegionService.matchesAnimal(pasture, a)).stream()
                    .sorted(Comparator.comparingDouble(a -> a.blockPosition().distSqr(citizen.lastPosition()))).toList()) {
                if (claimed.contains(animal.getUUID()) || PastureResidentService.inside(pasture, animal.blockPosition())
                        || PastureCaptureService.deferred(level, citizen, animal.getUUID())) continue;
                if (!PastureResidentService.ownedBy(settlement, pasture, animal)) {
                    if (animals.size() + incoming >= BuildingProductionService.animalCapacity(pasture)
                            || CitizenWorkService.protectedFromHunting(level, settlement, animal)) continue;
                }
                var destination = PastureCaptureService.destination(level, pasture, animal.blockPosition());
                if (destination != null && citizen.startCapture(pasture.id(), destination, animal.getUUID())) {
                    claimed.add(animal.getUUID());
                    return true;
                }
            }
        }
        if (woolReady && !citizen.hasInventoryItem(stack -> stack.is(Items.SHEARS)))
            citizen.updateWorkFailure("PASTURE_NEEDS_SHEARS");
        else if (!priorFailure.isBlank() && (hasDeferred(level, citizen)
                || PastureCaptureService.hasDeferred(level, citizen)))
            citizen.updateWorkFailure(priorFailure);
        else if (citizen.workFailure().isBlank())
            citizen.updateWorkFailure(animals.size() < 2 ? "PASTURE_NEEDS_ANIMALS" : "PASTURE_WAITING_ANIMALS");
        return false;
    }

    private static boolean start(CitizenRecord citizen, Animal animal, Set<UUID> claimed) {
        if (!citizen.startTask(CitizenWorkState.TO_HERD, animal.blockPosition(), animal.getUUID(), null)) return false;
        claimed.add(animal.getUUID());
        return true;
    }

    private static ItemStack wool(Sheep sheep) {
        var id = net.minecraft.resources.ResourceLocation.withDefaultNamespace(sheep.getColor().getName() + "_wool");
        return new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id));
    }

    public static void tick(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
                            CitizenRecord citizen, SettlementCitizenEntity worker) {
        var pasture = CitizenWorkService.workplace(settlement, citizen, BuildingType.PASTURE_AREA);
        var target = citizen.targetItemEntityId() == null ? null : level.getEntity(citizen.targetItemEntityId());
        if (pasture == null || pasture.demolitionQueued() || citizen.profession() != CitizenProfession.HERDSMAN
                || !(target instanceof Animal animal) || !manageable(settlement, pasture, animal)
                || !PastureResidentService.inside(pasture, animal.blockPosition())
                || !ProductionRegionService.matchesAnimal(pasture, animal)) {
            stop(data, citizen, worker);
            return;
        }
        Action action = ACTIONS.get(citizen);
        if (action == null || !action.target.equals(animal.getUUID())) {
            action = new Action(animal.getUUID(), level.getGameTime());
            ACTIONS.put(citizen, action);
        }
        long elapsed = Math.min(10, Math.max(0, level.getGameTime() - action.lastTick));
        action.lastTick = level.getGameTime();
        if (level.getGameTime() - action.started >= TARGET_TIMEOUT) {
            fail(level, data, citizen, worker, animal, "PASTURE_ANIMAL_UNREACHABLE");
            return;
        }
        boolean cull = canCull(settlement, pasture, residents(level, pasture), animal);
        int shears = citizen.findInventorySlot(stack -> stack.is(Items.SHEARS));
        if (!cull && (!(animal instanceof Sheep sheep) || !sheep.readyForShearing() || shears < 0)) {
            stop(data, citizen, worker);
            return;
        }
        if (!cull) {
            if (citizen.selectActiveSlot(shears)) data.touch();
            CitizenWorkService.syncHeldItem(citizen, worker);
        }
        citizen.updateTargetPosition(animal.blockPosition());
        if (!PastureResidentService.inside(pasture, worker.blockPosition())
                || worker.distanceToSqr(animal) > 6.25D || !worker.hasLineOfSight(animal)) {
            approach(level, data, citizen, worker, pasture, animal);
            return;
        }
        worker.stopSettlementNavigation();
        CitizenWorkService.setActivity(data, citizen, worker, CitizenActivity.HERDING, "");
        action.labor += elapsed * CitizenToolSpeed.multiplier(citizen);
        if (action.labor < LABOR_TICKS) return;
        action.labor = 0;
        if (cull) {
            if (!animal.hurt(level.damageSources().mobAttack(worker), 20.0F)) {
                fail(level, data, citizen, worker, animal, "PASTURE_ANIMAL_PROTECTED");
                return;
            }
            worker.swing(InteractionHand.MAIN_HAND);
            if (animal.isAlive()) { data.touch(); return; }
            CitizenWorkService.collectVisibleHuntingDrops(level, citizen, worker);
        } else if (animal instanceof Sheep sheep) {
            var tool = citizen.inventoryItem(shears);
            boolean settlementWasActive = settlement.active();
            if (!ResidentAnimalInteraction.allowed(level, settlement, citizen, worker, sheep, tool)) {
                fail(level, data, citizen, worker, animal, "PASTURE_ANIMAL_PROTECTED");
                return;
            }
            if (!sheep.readyForShearing() || !manageable(settlement, pasture, sheep)
                    || !PastureResidentService.inside(pasture, sheep.blockPosition())
                    || !PastureResidentService.inside(pasture, worker.blockPosition())
                    || worker.distanceToSqr(sheep) > 6.25D || !worker.hasLineOfSight(sheep)
                    || !worker.isAlive() || !citizen.alive()
                    || settlementWasActive && !settlement.active() || pasture.demolitionQueued()
                    || citizen.workState() != CitizenWorkState.TO_HERD
                    || !sheep.getUUID().equals(citizen.targetItemEntityId())
                    || CitizenWorkService.workplace(settlement, citizen, BuildingType.PASTURE_AREA) != pasture
                    || !citizen.inventoryItem(shears).is(Items.SHEARS)) {
                stop(data, citizen, worker);
                return;
            }
            if (citizen.inventoryCapacityFor(wool(sheep)) < 3) {
                fail(level, data, citizen, worker, animal, "INVENTORY_FULL");
                return;
            }
            for (var drop : sheep.onSheared(null, tool, level, sheep.blockPosition())) {
                var remainder = citizen.insertIntoInventory(drop);
                if (!remainder.isEmpty()) Block.popResource(level, sheep.blockPosition(), remainder);
            }
            citizen.damageInventoryItem(shears, 1);
            worker.swing(InteractionHand.MAIN_HAND);
        }
        stop(data, citizen, worker);
        CitizenWorkService.assignPersonalDelivery(level, settlement, citizen);
        data.touch();
    }

    private static boolean deferred(ServerLevel level, CitizenRecord citizen, Animal animal) {
        var map = DEFERRED.get(citizen);
        if (map == null) return false;
        map.values().removeIf(until -> until <= level.getGameTime());
        return map.containsKey(animal.getUUID());
    }

    private static boolean hasDeferred(ServerLevel level, CitizenRecord citizen) {
        var map = DEFERRED.get(citizen);
        if (map == null) return false;
        map.values().removeIf(until -> until <= level.getGameTime());
        return !map.isEmpty();
    }

    private static void fail(ServerLevel level, SettlementSavedData data, CitizenRecord citizen,
                             SettlementCitizenEntity worker, Animal animal, String reason) {
        DEFERRED.computeIfAbsent(citizen, c -> new HashMap<>()).put(animal.getUUID(), level.getGameTime() + 200);
        stop(data, citizen, worker);
        CitizenWorkService.setActivity(data, citizen, worker, CitizenActivity.BLOCKED, reason);
    }

    private static void stop(SettlementSavedData data, CitizenRecord citizen, SettlementCitizenEntity worker) {
        ACTIONS.remove(citizen);
        CitizenWorkService.clear(data, citizen, worker);
    }
}
