package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.region.PastureResidentService;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.ai.goal.Goal;
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
 * A temporary herding instruction, issued only after a real nearby protected
 * interaction. Native panic/breeding goals keep higher priority. No coordinates,
 * food, love time or age are changed by this goal.
 */
@EventBusSubscriber(modid = "exiledsettlement")
public final class PasturePairApproachGoal extends Goal {
    private static final Map<PastureBreedingService.State, PasturePairApproachGoal> ACTIVE = new IdentityHashMap<>();
    private final ServerLevel level;
    private final SettlementRecord settlement;
    private final CitizenRecord citizen;
    private final SettlementCitizenEntity worker;
    private final BuildingRecord pasture;
    private final Animal first, second;
    private final PastureBreedingService.State job;
    private long nextPath;

    private PasturePairApproachGoal(ServerLevel level, SettlementRecord settlement, CitizenRecord citizen,
            SettlementCitizenEntity worker, BuildingRecord pasture, Animal first, Animal second) {
        this.level = level;
        this.settlement = settlement;
        this.citizen = citizen;
        this.worker = worker;
        this.pasture = pasture;
        this.first = first;
        this.second = second;
        this.job = citizen.pastureJob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    static boolean attached(PastureBreedingService.State job) {
        return ACTIVE.containsKey(job);
    }

    /** Caller has just passed ResidentAnimalInteraction for this real animal. */
    static boolean attach(ServerLevel level, SettlementRecord settlement, CitizenRecord citizen,
            SettlementCitizenEntity worker, BuildingRecord pasture, Animal first, Animal second) {
        var goal = new PasturePairApproachGoal(level, settlement, citizen, worker, pasture, first, second);
        if (!goal.valid() || !PastureResidentService.inside(pasture, worker.blockPosition())
                || worker.distanceToSqr(second) > 6.25D || !worker.hasLineOfSight(second)) return false;
        var food = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(citizen.pastureJob.foodId);
        var offered = new net.minecraft.world.item.ItemStack(food);
        if (PastureBreedingService.countFood(citizen, food) < 1 || !first.isFood(offered) || !second.isFood(offered)) return false;
        release(citizen.pastureJob);
        ACTIVE.put(citizen.pastureJob, goal);
        second.goalSelector.addGoal(3, goal);
        return true;
    }

    static void release(PastureBreedingService.State job) {
        var goal = ACTIVE.remove(job);
        if (goal != null) goal.second.goalSelector.removeGoal(goal);
    }

    private boolean valid() {
        return job.active() && job.firstFed && job.stage == 2 && citizen.alive() && worker.isAlive()
                && settlement.active() && citizen.profession() == CitizenProfession.HERDSMAN
                && citizen.workState() == CitizenWorkState.TO_PASTURE_FEED
                && pasture.id().equals(citizen.workBuildingId()) && pasture.id().equals(job.pastureId)
                && CitizenWorkService.workplace(settlement, citizen, BuildingType.PASTURE_AREA) == pasture
                && !pasture.demolitionQueued()
                && first.getUUID().equals(job.firstAnimal) && second.getUUID().equals(job.secondAnimal)
                && level.getEntity(first.getUUID()) == first && level.getEntity(second.getUUID()) == second
                && level.getEntity(worker.getUUID()) == worker
                && first.getType() == second.getType() && !first.isBaby() && !second.isBaby()
                && PastureBreedingService.eligible(settlement, pasture, first)
                && PastureBreedingService.eligible(settlement, pasture, second)
                && first.isInLove() && second.canFallInLove();
    }

    @Override
    public boolean canUse() {
        return valid() && first.distanceToSqr(second) > 16.0D
                && second.hurtTime == 0 && !second.isOnFire() && !second.isInWaterOrBubble();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        nextPath = 0;
    }

    @Override
    public void tick() {
        second.getLookControl().setLookAt(first, 10.0F, second.getMaxHeadXRot());
        long now = level.getGameTime();
        if (now >= nextPath) {
            second.getNavigation().moveTo(first, 1.0D);
            nextPath = now + 20;
        }
    }

    @Override
    public void stop() {
        // GoalSelector stops this goal before starting a competing goal, so this
        // cannot replace a running panic/breeding navigation request.
        second.getNavigation().stop();
    }

    /** Remove outside GoalSelector iteration; never mutate its active set in canUse. */
    @SubscribeEvent
    public static void cleanup(ServerTickEvent.Post event) {
        if (ACTIVE.isEmpty()) return;
        for (var goal : List.copyOf(ACTIVE.values())) {
            if (goal.level.getServer() == event.getServer() && !goal.valid()) release(goal.job);
        }
    }

    @SubscribeEvent
    public static void leave(EntityLeaveLevelEvent event) {
        if (ACTIVE.isEmpty()) return;
        for (var goal : List.copyOf(ACTIVE.values())) {
            var entity = event.getEntity();
            if (entity == goal.first || entity == goal.second || entity == goal.worker) release(goal.job);
        }
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        for (var goal : List.copyOf(ACTIVE.values())) {
            if (goal.level.getServer() == event.getServer()) release(goal.job);
        }
    }
}
