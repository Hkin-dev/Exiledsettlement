package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.region.PastureResidentService;
import com.hkin.exiledsettlement.region.ProductionRegionService;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finishes a paid, physically leashed escort after the resident has crossed the
 * gate. A zero-radius native path gets the animal fully inside a small pasture;
 * vanilla's short leash following distance is not treated as a completed entry.
 */
@EventBusSubscriber(modid = "exiledsettlement")
public final class PastureEscortGoal extends Goal {
    private static final Map<Animal, PastureEscortGoal> ACTIVE = new IdentityHashMap<>();
    private final ServerLevel level;
    private final SettlementRecord settlement;
    private final CitizenRecord citizen;
    private final SettlementCitizenEntity worker;
    private final BuildingRecord pasture;
    private final Animal animal;
    private final BlockPos destination;
    private final boolean paidLead;
    private long nextPath;
    private boolean running;

    private PastureEscortGoal(ServerLevel level, SettlementRecord settlement, CitizenRecord citizen,
            SettlementCitizenEntity worker, BuildingRecord pasture, Animal animal, BlockPos destination) {
        this.level = level;
        this.settlement = settlement;
        this.citizen = citizen;
        this.worker = worker;
        this.pasture = pasture;
        this.animal = animal;
        this.destination = destination.immutable();
        ItemStack escrow = ItemStack.parseOptional(level.registryAccess(),
                animal.getPersistentData().getCompound(PastureCaptureService.LEAD_TAG).getCompound("Stack"));
        this.paidLead = escrow.is(Items.LEAD) && escrow.getCount() == 1;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static void attach(ServerLevel level, SettlementRecord settlement, CitizenRecord citizen,
            SettlementCitizenEntity worker, BuildingRecord pasture, Animal animal, BlockPos destination) {
        var previous = ACTIVE.get(animal);
        if (previous != null && previous.citizen == citizen && previous.destination.equals(destination)
                && previous.valid()) return;
        release(citizen);
        var goal = new PastureEscortGoal(level, settlement, citizen, worker, pasture, animal, destination);
        if (!goal.valid() || PastureCaptureService.insideBody(pasture, animal)) return;
        remove(animal);
        ACTIVE.put(animal, goal);
        // Panic (1) and native breeding (2) retain priority over the escort.
        animal.goalSelector.addGoal(3, goal);
    }

    public static void release(CitizenRecord citizen) {
        for (var goal : List.copyOf(ACTIVE.values())) {
            if (goal.citizen == citizen) remove(goal.animal);
        }
    }

    /** The leash mixin may preserve only this goal's currently running path. */
    public static boolean controls(Animal animal, Entity holder) {
        var goal = ACTIVE.get(animal);
        return goal != null && goal.worker == holder && goal.running && goal.canUse();
    }

    private static void remove(Animal animal) {
        var goal = ACTIVE.remove(animal);
        if (goal != null) animal.goalSelector.removeGoal(goal);
    }

    private boolean valid() {
        return paidLead && settlement.active() && citizen.canWork() && worker.isAlive()
                && citizen.profession() == CitizenProfession.HERDSMAN
                && citizen.workState() == CitizenWorkState.TO_CAPTURE
                && CitizenWorkService.schedulePhase(level.getDayTime(), citizen) == CitizenWorkService.SchedulePhase.WORK
                && pasture.id().equals(citizen.workBuildingId()) && pasture.id().equals(citizen.targetBuildingId())
                && animal.getUUID().equals(citizen.targetItemEntityId())
                && settlement.building(pasture.id()) == pasture
                && CitizenWorkService.usable(pasture, BuildingType.PASTURE_AREA) && !pasture.demolitionQueued()
                && level.getEntity(worker.getUUID()) == worker && level.getEntity(animal.getUUID()) == animal
                && animal.isAlive() && !animal.isRemoved() && !animal.hasCustomName()
                && !animal.isPassenger() && !animal.isVehicle() && !animal.isNoAi()
                && (!(animal instanceof TamableAnimal tame) || !tame.isTame())
                && ProductionRegionService.matchesAnimal(pasture, animal)
                && (!PastureResidentService.hasOwnership(animal) || PastureResidentService.ownedBy(settlement, pasture, animal))
                && animal.isLeashed() && animal.getLeashHolder() == worker
                && PastureCaptureService.paidFor(animal.getPersistentData().getCompound(PastureCaptureService.LEAD_TAG),
                        settlement, citizen, pasture)
                && PastureCaptureService.insideBody(pasture, worker)
                && level.hasChunkAt(destination) && PastureResidentService.inside(pasture, destination);
    }

    @Override public boolean canUse() {
        return valid() && animal.hurtTime == 0 && !animal.isOnFire() && !animal.isInWaterOrBubble();
    }

    @Override public boolean canContinueToUse() { return canUse(); }

    @Override public void start() { running = true; nextPath = 0; }

    @Override public void tick() {
        if (PastureCaptureService.insideBody(pasture, animal)) {
            // Keep the completed physical entry until the capture scheduler
            // performs its real handoff; vanilla's short follow point must not
            // pull the animal back across the gate between scheduler updates.
            animal.getNavigation().stop();
            return;
        }
        animal.getLookControl().setLookAt(worker, 10.0F, animal.getMaxHeadXRot());
        long now = level.getGameTime();
        if (now < nextPath) return;
        var path = animal.getNavigation().createPath(destination, 0);
        if (path != null) animal.getNavigation().moveTo(path, 1.0D);
        nextPath = now + 20;
    }

    @Override public void stop() {
        running = false;
        animal.getNavigation().stop();
    }

    @SubscribeEvent public static void cleanup(ServerTickEvent.Post event) {
        if (ACTIVE.isEmpty()) return;
        for (var goal : List.copyOf(ACTIVE.values())) {
            if (goal.level.getServer() == event.getServer() && !goal.valid()) remove(goal.animal);
        }
    }

    @SubscribeEvent public static void leave(EntityLeaveLevelEvent event) {
        if (ACTIVE.isEmpty()) return;
        for (var goal : List.copyOf(ACTIVE.values())) {
            if (event.getEntity() == goal.animal || event.getEntity() == goal.worker) remove(goal.animal);
        }
    }

    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        for (var goal : List.copyOf(ACTIVE.values())) {
            if (goal.level.getServer() == event.getServer()) remove(goal.animal);
        }
    }
}
