package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.blueprint.BlueprintEntrance;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.blueprint.BuildingBlueprintTemplate;
import com.hkin.exiledsettlement.blueprint.FarmEarthworkPlan;
import com.hkin.exiledsettlement.building.BuildingProductionService;
import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.building.WarehouseInventoryService;
import com.hkin.exiledsettlement.debug.SaveAcceptanceService;
import com.hkin.exiledsettlement.region.PastureResidentService;
import com.hkin.exiledsettlement.region.ProductionRegion;
import com.hkin.exiledsettlement.region.ProductionRegionService;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Real lead escrow, physical escort through a single gate, and recoverable capture attempts. */
public final class PastureCaptureService {
    public static final String LEAD_TAG = "exiledsettlement.pastureCaptureLead";
    private static final int NO_PROGRESS_TICKS = 400;
    private static final int ATTEMPT_TICKS = 2400;
    private static final int RETRY_TICKS = 200;
    private static final int LEASH_RESTORE_TICKS = 120;
    private static final Map<CitizenRecord, Attempt> ATTEMPTS = new WeakHashMap<>();
    private static final Map<CitizenRecord, Map<UUID, Long>> DEFERRED = new WeakHashMap<>();
    private static final Map<Animal, Long> RESTORE_WAIT = new WeakHashMap<>();

    private PastureCaptureService() {}

    private static final class Attempt {
        final UUID animal;
        final long started;
        long progress;
        Vec3 workerPosition, animalPosition;
        BlueprintEntrance gate;
        BlockPos destination;
        long checkDestinationAt;

        Attempt(CitizenRecord citizen, SettlementCitizenEntity worker, Animal target, long now) {
            animal = citizen.targetItemEntityId();
            started = progress = now;
            workerPosition = worker.position();
            animalPosition = target == null ? null : target.position();
        }

        boolean expired(SettlementCitizenEntity worker, Animal target, long now) {
            if (workerPosition.distanceToSqr(worker.position()) > 0.25D
                    || target != null && (animalPosition == null
                    || animalPosition.distanceToSqr(target.position()) > 0.25D)) {
                progress = now;
                workerPosition = worker.position();
                animalPosition = target == null ? null : target.position();
            }
            return now - progress >= NO_PROGRESS_TICKS || now - started >= ATTEMPT_TICKS;
        }
    }

    public static boolean deferred(ServerLevel level, CitizenRecord citizen, UUID animalId) {
        var entries = DEFERRED.get(citizen);
        if (entries == null) return false;
        entries.entrySet().removeIf(entry -> entry.getValue() <= level.getGameTime());
        return entries.containsKey(animalId);
    }

    public static boolean hasDeferred(ServerLevel level, CitizenRecord citizen) {
        var entries = DEFERRED.get(citizen);
        if (entries == null) return false;
        entries.entrySet().removeIf(entry -> entry.getValue() <= level.getGameTime());
        return !entries.isEmpty();
    }

    public static void tick(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
                            CitizenRecord citizen, SettlementCitizenEntity worker) {
        BuildingRecord pasture = settlement.building(citizen.targetBuildingId());
        Entity target = citizen.targetItemEntityId() == null ? null : level.getEntity(citizen.targetItemEntityId());
        Animal animal = target instanceof Animal value ? value : null;
        if (!settlement.active() || !CitizenWorkService.usable(pasture, BuildingType.PASTURE_AREA)
                || pasture.demolitionQueued()) {
            fail(level, data, citizen, worker, "PASTURE_UNAVAILABLE");
            return;
        }
        Attempt attempt = ATTEMPTS.get(citizen);
        if (attempt == null || !java.util.Objects.equals(attempt.animal, citizen.targetItemEntityId())) {
            attempt = new Attempt(citizen, worker, animal, level.getGameTime());
            ATTEMPTS.put(citizen, attempt);
        }
        if (attempt.expired(worker, animal, level.getGameTime())) {
            fail(level, data, citizen, worker, "PASTURE_CAPTURE_NO_PROGRESS");
            return;
        }
        if (animal == null) {
            worker.stopSettlementNavigation();
            status(data, citizen, worker, CitizenActivity.BLOCKED, "PASTURE_ANIMAL_UNLOADED");
            return;
        }
        if (!eligible(settlement, pasture, animal)
                || animal.isLeashed() && animal.getLeashHolder() != worker) {
            fail(level, data, citizen, worker, "PASTURE_ANIMAL_PROTECTED");
            return;
        }
        if (!insideBody(pasture, animal) && !PastureResidentService.ownedBy(settlement, pasture, animal)
                && population(level, pasture) >= BuildingProductionService.animalCapacity(pasture)) {
            fail(level, data, citizen, worker, "PASTURE_FULL");
            return;
        }
        CompoundTag paid = animal.getPersistentData().getCompound(LEAD_TAG);
        if (!paid.isEmpty() && !paidFor(paid, settlement, citizen, pasture)) {
            fail(level, data, citizen, worker, "PASTURE_ANIMAL_PROTECTED");
            return;
        }
        if (!animal.isLeashed() && !paid.isEmpty()) {
            // Vanilla resolves saved leash holders lazily. A real broken leash has already
            // produced its own item; its escrow must never create a second lead.
            long since = RESTORE_WAIT.computeIfAbsent(animal, unused -> level.getGameTime());
            worker.stopSettlementNavigation();
            if (level.getGameTime() - since < LEASH_RESTORE_TICKS) {
                status(data, citizen, worker, CitizenActivity.CAPTURING, "PASTURE_RESTORING_LEASH");
                return;
            }
            animal.getPersistentData().remove(LEAD_TAG);
            RESTORE_WAIT.remove(animal);
            fail(level, data, citizen, worker, "PASTURE_LEAD_BROKEN");
            return;
        }
        if (animal.isLeashed()) RESTORE_WAIT.remove(animal);
        if (animal.isLeashed() && insideBody(pasture, worker) && insideBody(pasture, animal)
                && PastureResidentService.inside(pasture, animal.blockPosition())) {
            complete(level, data, settlement, citizen, worker, pasture, animal);
            return;
        }
        if (attempt.gate == null || !usableGate(level, attempt.gate)) {
            long savedGate = paid.getLong("Gate");
            attempt.gate = paid.contains("Gate")
                    ? BlueprintTemplates.createForBuilding(pasture).entrances().stream()
                        .filter(gate -> gate.position().asLong() == savedGate && usableGate(level, gate))
                        .findFirst().orElse(null)
                    : null;
            if (attempt.gate == null) attempt.gate = entrance(level, pasture, animal.blockPosition());
            attempt.destination = null;
        }
        if (level.getGameTime() >= attempt.checkDestinationAt) {
            if (attempt.destination == null || !standing(level, attempt.destination)) {
                attempt.destination = destination(level, pasture, animal.blockPosition(), attempt.gate);
            }
            attempt.checkDestinationAt = level.getGameTime() + 80;
        }
        BlockPos goal = attempt.destination;
        if (attempt.gate == null || goal == null) {
            worker.stopSettlementNavigation();
            status(data, citizen, worker, CitizenActivity.BLOCKED, "PASTURE_NO_SAFE_ENTRANCE");
            return;
        }
        if (citizen.updateTargetPosition(goal)) data.touch();
        if (!animal.isLeashed()) {
            if (!citizen.hasInventoryItem(stack -> stack.is(Items.LEAD))) {
                fetchLead(level, data, settlement, citizen, worker);
                return;
            }
            if (worker.distanceToSqr(animal) > 6.25D || !worker.hasLineOfSight(animal)) {
                CitizenWorkService.move(data, citizen, worker, animal.blockPosition(), CitizenActivity.CAPTURING);
                return;
            }
            ItemStack availableLead = citizen.inventory().stream().filter(stack -> stack.is(Items.LEAD))
                    .findFirst().orElse(ItemStack.EMPTY);
            if (!ResidentAnimalInteraction.allowed(level, settlement, citizen, worker, animal, availableLead)) {
                fail(level, data, citizen, worker, "PROTECTED_AREA");
                return;
            }
            if (!eligible(settlement, pasture, animal) || animal.isLeashed()
                    || !worker.isAlive() || !citizen.alive() || !settlement.active()
                    || citizen.workState() != CitizenWorkState.TO_CAPTURE
                    || !animal.getUUID().equals(citizen.targetItemEntityId())
                    || !pasture.id().equals(citizen.targetBuildingId())
                    || !CitizenWorkService.usable(pasture, BuildingType.PASTURE_AREA)
                    || pasture.demolitionQueued() || worker.distanceToSqr(animal) > 6.25D
                    || !worker.hasLineOfSight(animal)) {
                fail(level, data, citizen, worker, "PASTURE_ANIMAL_PROTECTED");
                return;
            }
            ItemStack lead = citizen.extractInventoryItem(stack -> stack.is(Items.LEAD), 1);
            if (lead.isEmpty()) return;
            paid = new CompoundTag();
            paid.putUUID("Settlement", settlement.id());
            paid.putUUID("Citizen", citizen.id());
            paid.putUUID("Pasture", pasture.id());
            paid.put("Stack", lead.save(level.registryAccess()));
            paid.putLong("Gate", attempt.gate.position().asLong());
            animal.getPersistentData().put(LEAD_TAG, paid);
            animal.setLeashedTo(worker, true);
            if (!animal.isLeashed() || animal.getLeashHolder() != worker) {
                animal.getPersistentData().remove(LEAD_TAG);
                putBack(level, citizen, worker.blockPosition(), lead);
                fail(level, data, citizen, worker, "PASTURE_ANIMAL_PROTECTED");
                return;
            }
            SaveAcceptanceService.action(level, citizen, "pasture_lead_attach",
                    animal.blockPosition(), "minecraft:lead", 1);
            data.touch();
        }
        worker.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.LEAD));
        PastureEscortGoal.attach(level, settlement, citizen, worker, pasture, animal, goal);
        if (insideBody(pasture, worker) && insideBody(pasture, animal)
                && PastureResidentService.inside(pasture, animal.blockPosition())) {
            complete(level, data, settlement, citizen, worker, pasture, animal);
            return;
        }
        if (worker.distanceToSqr(animal) > 36.0D) {
            worker.stopSettlementNavigation();
            status(data, citizen, worker, CitizenActivity.CAPTURING, "PASTURE_WAITING_FOR_ANIMAL");
            return;
        }
        BlockPos approach = attempt.gate.position().relative(attempt.gate.outward(), 1);
        boolean gateOpen = gateOpen(level, attempt.gate.position());
        if (!insideBody(pasture, worker) && (!gateOpen || worker.distanceToSqr(
                approach.getX() + .5D, approach.getY(), approach.getZ() + .5D) > 4.0D)) {
            CitizenWorkService.move(data, citizen, worker, approach, CitizenActivity.CAPTURING);
        } else {
            CitizenWorkService.move(data, citizen, worker, goal, CitizenActivity.CAPTURING);
        }
    }

    private static void complete(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
                                  CitizenRecord citizen, SettlementCitizenEntity worker,
                                  BuildingRecord pasture, Animal animal) {
        if (paidFor(animal.getPersistentData().getCompound(LEAD_TAG), settlement, citizen, pasture)) {
            returnPaidLead(level, animal, citizen, worker);
        } else {
            // Old saved captures had no real escrow. Finish their physical handoff
            // without inventing a lead item.
            animal.dropLeash(true, false);
        }
        PastureResidentService.capture(level, settlement, pasture, animal);
        SaveAcceptanceService.action(level, citizen, "pasture_capture",
                animal.blockPosition(), "minecraft:lead", 0);
        CitizenWorkService.clear(data, citizen, worker);
    }

    private static boolean eligible(SettlementRecord settlement, BuildingRecord pasture, Animal animal) {
        return animal.isAlive() && !animal.isRemoved() && !animal.hasCustomName()
                && (!animal.isBaby() || PastureResidentService.ownedBy(settlement, pasture, animal))
                && !animal.isPassenger() && !animal.isVehicle() && !animal.isNoAi()
                && (!(animal instanceof TamableAnimal tame) || !tame.isTame())
                && ProductionRegionService.matchesAnimal(pasture, animal)
                && (!PastureResidentService.hasOwnership(animal)
                    || PastureResidentService.ownedBy(settlement, pasture, animal));
    }

    private static int population(ServerLevel level, BuildingRecord pasture) {
        AABB bounds = new AABB(pasture.minCorner()).minmax(new AABB(pasture.maxCorner())).inflate(0, 3, 0);
        return level.getEntitiesOfClass(Animal.class, bounds,
                animal -> animal.isAlive() && insideBody(pasture, animal)
                        && ProductionRegionService.matchesAnimal(pasture, animal)).size();
    }

    private static void fetchLead(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
                                  CitizenRecord citizen, SettlementCitizenEntity worker) {
        if (citizen.inventoryCapacityFor(new ItemStack(Items.LEAD)) < 1) {
            fail(level, data, citizen, worker, "INVENTORY_FULL");
            return;
        }
        BuildingRecord warehouse = settlement.buildings().stream()
                .filter(building -> building.type().isStorage() && building.active() && building.operational()
                        && level.hasChunkAt(building.position())
                        && WarehouseInventoryService.countItem(level, building, Items.LEAD) > 0)
                .min(Comparator.comparingDouble(building -> building.position().distSqr(worker.blockPosition())))
                .orElse(null);
        if (warehouse == null) {
            fail(level, data, citizen, worker, "PASTURE_NEEDS_LEAD");
            return;
        }
        BlockPos station = WarehouseInventoryService.reserveExtractionPosition(level, warehouse, worker, Items.LEAD);
        if (station == null) {
            CitizenWorkService.waitForWarehouseStation(level, data, citizen, worker, warehouse,
                    CitizenActivity.FETCHING_EQUIPMENT);
            return;
        }
        if (!WarehouseInventoryService.canInteract(level, warehouse, worker)) {
            CitizenWorkService.move(data, citizen, worker, station, CitizenActivity.FETCHING_EQUIPMENT);
            return;
        }
        ItemStack taken = WarehouseInventoryService.extractItemForResident(level, warehouse, worker, Items.LEAD, 1);
        if (!taken.isEmpty()) {
            ItemStack excess = citizen.insertIntoInventory(taken);
            if (!excess.isEmpty()) {
                excess = WarehouseInventoryService.insertForResident(level, warehouse, worker, excess);
                if (!excess.isEmpty()) Block.popResource(level, worker.blockPosition(), excess);
            }
            data.touch();
        }
        WarehouseInventoryService.releaseInteractionPosition(level, worker);
    }

    /** Called before clearWork removes the saved target identity. */
    public static void release(ServerLevel level, CitizenRecord citizen, SettlementCitizenEntity worker) {
        PastureEscortGoal.release(citizen);
        ATTEMPTS.remove(citizen);
        Entity target = citizen.targetItemEntityId() == null ? null : level.getEntity(citizen.targetItemEntityId());
        if (!(target instanceof Animal animal)) return;
        if (citizen.workState() == CitizenWorkState.TO_CAPTURE
                && !animal.getPersistentData().contains(LEAD_TAG)
                && animal.isLeashed() && animal.getLeashHolder() == worker) {
            // Pre-escrow saves attached a display-only lead. Cancelling that
            // exact old capture must not later mint an item through leash breakage.
            animal.dropLeash(true, false);
            return;
        }
        CompoundTag paid = animal.getPersistentData().getCompound(LEAD_TAG);
        if (!paid.hasUUID("Citizen") || !citizen.id().equals(paid.getUUID("Citizen"))) return;
        if (animal.isLeashed() && animal.getLeashHolder() == worker) {
            returnPaidLead(level, animal, citizen, worker);
        } else if (animal.isLeashed()) {
            // Another owner has supplied their own leash. Never detach it or refund our old one.
            animal.getPersistentData().remove(LEAD_TAG);
            RESTORE_WAIT.remove(animal);
        }
    }

    /** Reconciles an escrow after either side was unloaded or the saved job was cancelled. */
    public static void reconcile(Animal animal) {
        if (!(animal.level() instanceof ServerLevel level) || (animal.tickCount + animal.getId()) % 40 != 0) return;
        CompoundTag paid = animal.getPersistentData().getCompound(LEAD_TAG);
        if (!paid.hasUUID("Citizen") || !paid.hasUUID("Settlement")) return;
        if (!animal.isLeashed()) {
            long since = RESTORE_WAIT.computeIfAbsent(animal, unused -> level.getGameTime());
            if (level.getGameTime() - since >= LEASH_RESTORE_TICKS) {
                animal.getPersistentData().remove(LEAD_TAG);
                RESTORE_WAIT.remove(animal);
            }
            return;
        }
        RESTORE_WAIT.remove(animal);
        Entity holder = animal.getLeashHolder();
        if (!(holder instanceof SettlementCitizenEntity worker)
                || !paid.getUUID("Citizen").equals(CitizenProjectionService.citizenId(worker))) {
            animal.getPersistentData().remove(LEAD_TAG);
            return;
        }
        SettlementRecord settlement = SettlementSavedData.get(level).findById(paid.getUUID("Settlement")).orElse(null);
        CitizenRecord citizen = settlement == null ? null : settlement.citizen(paid.getUUID("Citizen"));
        BuildingRecord pasture = settlement == null || !paid.hasUUID("Pasture")
                ? null : settlement.building(paid.getUUID("Pasture"));
        if (citizen != null && citizen.alive() && settlement.active()
                && CitizenWorkService.usable(pasture, BuildingType.PASTURE_AREA) && !pasture.demolitionQueued()
                && citizen.workState() == CitizenWorkState.TO_CAPTURE
                && animal.getUUID().equals(citizen.targetItemEntityId())
                && pasture.id().equals(citizen.targetBuildingId())
                && CitizenWorkService.schedulePhase(level.getDayTime(), citizen) == CitizenWorkService.SchedulePhase.WORK) {
            return;
        }
        returnPaidLead(level, animal, citizen, worker);
        SettlementSavedData.get(level).touch();
    }

    private static void returnPaidLead(ServerLevel level, Animal animal, @Nullable CitizenRecord citizen,
                                      SettlementCitizenEntity worker) {
        CompoundTag paid = animal.getPersistentData().getCompound(LEAD_TAG);
        if (!animal.isLeashed() || animal.getLeashHolder() != worker || !paid.hasUUID("Citizen")
                || !(citizen != null && paid.getUUID("Citizen").equals(citizen.id())
                    || paid.getUUID("Citizen").equals(CitizenProjectionService.citizenId(worker)))) return;
        ItemStack lead = ItemStack.parseOptional(level.registryAccess(), paid.getCompound("Stack"));
        animal.getPersistentData().remove(LEAD_TAG);
        RESTORE_WAIT.remove(animal);
        animal.dropLeash(true, false);
        if (lead.is(Items.LEAD) && lead.getCount() == 1) {
            putBack(level, citizen, worker.blockPosition(), lead);
        }
    }

    private static void putBack(ServerLevel level, @Nullable CitizenRecord citizen, BlockPos at, ItemStack stack) {
        ItemStack remaining = citizen != null && citizen.alive() ? citizen.insertIntoInventory(stack) : stack;
        if (!remaining.isEmpty()) Block.popResource(level, at, remaining);
    }

    static boolean paidFor(CompoundTag paid, SettlementRecord settlement,
                                   CitizenRecord citizen, BuildingRecord pasture) {
        return paid.hasUUID("Citizen") && paid.hasUUID("Settlement") && paid.hasUUID("Pasture")
                && citizen.id().equals(paid.getUUID("Citizen"))
                && settlement.id().equals(paid.getUUID("Settlement"))
                && pasture.id().equals(paid.getUUID("Pasture"));
    }

    private static void fail(ServerLevel level, SettlementSavedData data, CitizenRecord citizen,
                             SettlementCitizenEntity worker, String reason) {
        UUID id = citizen.targetItemEntityId();
        if (id != null) DEFERRED.computeIfAbsent(citizen, unused -> new java.util.HashMap<>())
                .put(id, level.getGameTime() + RETRY_TICKS);
        CitizenWorkService.clear(data, citizen, worker);
        status(data, citizen, worker, CitizenActivity.BLOCKED, reason);
    }

    private static void status(SettlementSavedData data, CitizenRecord citizen,
                               SettlementCitizenEntity worker, CitizenActivity activity, String reason) {
        CitizenWorkService.setActivity(data, citizen, worker, activity, reason);
    }

    @Nullable
    public static BlockPos destination(ServerLevel level, BuildingRecord pasture, BlockPos animalPosition) {
        return destination(level, pasture, animalPosition, entrance(level, pasture, animalPosition));
    }

    @Nullable
    private static BlockPos destination(ServerLevel level, BuildingRecord pasture, BlockPos animalPosition,
                                        @Nullable BlueprintEntrance entrance) {
        if (entrance == null) return null;
        List<BlockPos> choices = new ArrayList<>();
        if (pasture.productionSite() != null) {
            pasture.productionSite().workCells().stream().filter(cell -> cell.use() == ProductionRegion.Use.PASTURE)
                    .map(cell -> cell.ground().above()).filter(at -> standing(level, at)).forEach(choices::add);
        } else if (pasture.region() != null) {
            pasture.region().cells.values().stream().filter(cell -> cell.use() == ProductionRegion.Use.PASTURE
                            && pasture.region().readyPasture.contains(cell.ground().asLong()))
                    .map(cell -> cell.ground().above()).filter(at -> standing(level, at)).forEach(choices::add);
        } else {
            for (BlockPos at : BlockPos.betweenClosed(
                    new BlockPos(pasture.minCorner().getX() + 1, pasture.position().getY(), pasture.minCorner().getZ() + 1),
                    new BlockPos(pasture.maxCorner().getX() - 1, pasture.position().getY(), pasture.maxCorner().getZ() - 1))) {
                if (standing(level, at)) choices.add(at.immutable());
            }
        }
        Direction inward = entrance.outward().getOpposite();
        return choices.stream().filter(at -> inwardDistance(at, entrance, inward) >= 2)
                .max(Comparator.comparingDouble(at -> Math.min(6, inwardDistance(at, entrance, inward)) * 1000.0D
                        - at.distSqr(entrance.position()) - at.distSqr(animalPosition) * .01D))
                .orElse(null);
    }

    private static int inwardDistance(BlockPos at, BlueprintEntrance entrance, Direction inward) {
        return (at.getX() - entrance.position().getX()) * inward.getStepX()
                + (at.getZ() - entrance.position().getZ()) * inward.getStepZ();
    }

    private static boolean standing(ServerLevel level, BlockPos feet) {
        return level.hasChunkAt(feet) && FarmEarthworkPlan.support(level, feet.below())
                && level.getFluidState(feet).isEmpty() && level.getFluidState(feet.above()).isEmpty()
                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
    }

    @Nullable
    private static BlueprintEntrance entrance(ServerLevel level, BuildingRecord pasture, BlockPos from) {
        return BlueprintTemplates.createForBuilding(pasture).entrances().stream()
                .filter(gate -> usableGate(level, gate))
                .min(Comparator.comparingDouble(gate -> gate.position().distSqr(from))).orElse(null);
    }

    private static boolean usableGate(ServerLevel level, BlueprintEntrance entrance) {
        return level.hasChunkAt(entrance.position())
                && level.getBlockState(entrance.position()).getBlock() instanceof FenceGateBlock
                && standing(level, entrance.position().relative(entrance.outward()))
                && standing(level, entrance.position().relative(entrance.outward().getOpposite()));
    }

    private static boolean gateOpen(ServerLevel level, BlockPos at) {
        if (!level.hasChunkAt(at)) return false;
        var state = level.getBlockState(at);
        return state.getBlock() instanceof FenceGateBlock && state.getValue(FenceGateBlock.OPEN);
    }

    static boolean insideBody(BuildingRecord pasture, Entity entity) {
        AABB box = entity.getBoundingBox().deflate(.02D, 0, .02D);
        return interior(pasture, BlockPos.containing(box.minX, entity.getY(), box.minZ))
                && interior(pasture, BlockPos.containing(box.minX, entity.getY(), box.maxZ))
                && interior(pasture, BlockPos.containing(box.maxX, entity.getY(), box.minZ))
                && interior(pasture, BlockPos.containing(box.maxX, entity.getY(), box.maxZ));
    }

    private static boolean interior(BuildingRecord pasture, BlockPos at) {
        if (pasture.productionSite() != null) {
            var cell = pasture.productionSite().cell(at);
            return cell != null && pasture.productionSite().contains(at)
                    && cell.use() != ProductionRegion.Use.BOUNDARY && cell.use() != ProductionRegion.Use.GATE;
        }
        if (pasture.region() != null) {
            var cell = pasture.region().cell(at);
            return cell != null && pasture.region().contains(at)
                    && cell.use() != ProductionRegion.Use.BOUNDARY && cell.use() != ProductionRegion.Use.GATE;
        }
        return at.getX() > pasture.minCorner().getX() && at.getX() < pasture.maxCorner().getX()
                && at.getZ() > pasture.minCorner().getZ() && at.getZ() < pasture.maxCorner().getZ()
                && at.getY() >= pasture.minCorner().getY() && at.getY() <= pasture.maxCorner().getY() + 2;
    }

    /** One gate group stays open until both the worker and the entire animal have crossed. */
    public static void gates(ServerLevel level, SettlementRecord settlement) {
        for (BuildingRecord pasture : settlement.buildings()) {
            if (pasture.type() != BuildingType.PASTURE_AREA || !pasture.active()) continue;
            PastureResidentService.reconcile(level, settlement, pasture);
            BuildingBlueprintTemplate template = BlueprintTemplates.createForBuilding(pasture);
            BlueprintEntrance active = null;
            SettlementCitizenEntity actor = null;
            for (CitizenRecord citizen : settlement.citizens()) {
                if (!citizen.alive() || citizen.workState() != CitizenWorkState.TO_CAPTURE
                        || !pasture.id().equals(citizen.targetBuildingId())) continue;
                Entity target = citizen.targetItemEntityId() == null ? null : level.getEntity(citizen.targetItemEntityId());
                SettlementCitizenEntity worker = CitizenProjectionService.findProjection(level, citizen);
                if (!(target instanceof Animal animal) || worker == null
                        || !animal.isLeashed() || animal.getLeashHolder() != worker
                        || insideBody(pasture, worker) && insideBody(pasture, animal)) continue;
                Attempt attempt = ATTEMPTS.get(citizen);
                active = attempt != null && attempt.gate != null ? attempt.gate
                        : entrance(level, pasture, animal.blockPosition());
                actor = worker;
                if (active != null) break;
            }
            for (var placement : template.gatePlacements()) {
                BlockPos at = placement.position();
                if (!level.hasChunkAt(at)) continue;
                var passing=FarmAccessService.requestingActor(level,pasture,at);
                boolean occupied = gateOccupied(level, at);
                // Occupancy can hold an already-open gate; an animal merely
                // touching a closed gate must never open it and escape.
                boolean open = passing != null || active != null && at.distManhattan(active.position()) <= 1
                        || gateOpen(level, at) && occupied;
                changeGate(level, settlement, at, open, passing!=null?passing:actor);
            }
        }
    }

    /** Only a body crossing the actual closing panel must hold the gate open. */
    private static boolean gateOccupied(ServerLevel level, BlockPos at) {
        return FenceGateAccessService.occupied(level,at);
    }

    private static void changeGate(ServerLevel level, SettlementRecord settlement, BlockPos at, boolean open,
                                   @Nullable SettlementCitizenEntity actor) {
        FenceGateAccessService.change(level,settlement,at,open,actor);
    }
}
