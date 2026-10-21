package com.hkin.exiledsettlement.companion;

import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.citizen.CitizenProjectionService;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Scoped, reversible AI; no player owner, teleport, replacement entity or permanent NoAI flag. */
public final class PetAi {
    private static final Map<Animal, Control> CONTROLS = new IdentityHashMap<>();
    public static long pathRequests;
    private PetAi() {}

    private record SavedGoal(int priority, Goal goal) {}
    private record Control(PetState.Pet pet, PetGoal goal, FloatGoal floating,
                           List<SavedGoal> movement, List<SavedGoal> targeting) {}

    public static void attach(ServerLevel level, SettlementRecord settlement, PetState.Pet pet, Animal animal) {
        if (!(animal instanceof Cat || animal instanceof Wolf) || !pet.occupiesSlot()) return;
        Control old = CONTROLS.get(animal);
        if (old != null && old.pet() == pet) return;
        release(animal);
        List<SavedGoal> movement = snapshot(animal.goalSelector.getAvailableGoals());
        List<SavedGoal> targeting = snapshot(animal.targetSelector.getAvailableGoals());
        movement.forEach(saved -> animal.goalSelector.removeGoal(saved.goal()));
        targeting.forEach(saved -> animal.targetSelector.removeGoal(saved.goal()));
        animal.setTarget(null);
        animal.getNavigation().stop();
        PetGoal goal = new PetGoal(level, settlement, pet, animal);
        FloatGoal floating = new FloatGoal(animal);
        animal.goalSelector.addGoal(0, floating);
        animal.goalSelector.addGoal(1, goal);
        CONTROLS.put(animal, new Control(pet, goal, floating, movement, targeting));
    }

    private static List<SavedGoal> snapshot(java.util.Set<WrappedGoal> goals) {
        return goals.stream().map(goal -> new SavedGoal(goal.getPriority(), goal.getGoal())).toList();
    }

    public static boolean controls(Animal animal) { return CONTROLS.containsKey(animal); }

    public static void suspend(Animal animal) {
        if (!controls(animal)) return;
        animal.setTarget(null);
        animal.getNavigation().stop();
        pose(animal, false);
    }

    public static void release(Animal animal) {
        Control control = CONTROLS.remove(animal);
        if (control == null) return;
        animal.goalSelector.removeGoal(control.goal());
        animal.goalSelector.removeGoal(control.floating());
        control.movement().forEach(saved -> animal.goalSelector.addGoal(saved.priority(), saved.goal()));
        control.targeting().forEach(saved -> animal.targetSelector.addGoal(saved.priority(), saved.goal()));
        animal.setTarget(null);
        animal.getNavigation().stop();
        pose(animal, false);
    }

    public static void releaseAll() { for (Animal animal : List.copyOf(CONTROLS.keySet())) release(animal); }

    private static void pose(Animal animal, boolean resting) {
        if (animal instanceof Cat cat) cat.setLying(resting);
        if (animal instanceof TamableAnimal tame) {
            tame.setOrderedToSit(false);
            tame.setInSittingPose(resting && animal instanceof Wolf);
        }
    }

    private static final class PetGoal extends Goal {
        final ServerLevel level;
        final SettlementRecord settlement;
        final PetState.Pet pet;
        final Animal animal;
        long nextDecision;
        long nextPath;
        long attackAt;
        long retreatUntil;
        long pauseUntil;
        int pathFailures;
        BlockPos commanded;
        BlockPos routeProgress;
        long routeProgressAt;
        BlockPos combatOrigin;
        Mob enemy;
        boolean returning;

        PetGoal(ServerLevel level, SettlementRecord settlement, PetState.Pet pet, Animal animal) {
            this.level = level;
            this.settlement = settlement;
            this.pet = pet;
            this.animal = animal;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP, Flag.TARGET));
        }
        @Override public boolean canUse() {
            return pet.occupiesSlot() && settlement.active() && animal.isAlive() && !animal.isRemoved()
                    && !animal.isNoAi() && !animal.isPassenger() && !animal.isLeashed();
        }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() { nextDecision = 0; animal.setTarget(null); }
        @Override public void stop() { animal.setTarget(null); animal.getNavigation().stop(); pose(animal, false); }

        @Override public void tick() {
            if (!CompanionMetrics.ENABLED) { tickMeasured(); return; }
            long started = CompanionMetrics.start();
            try { tickMeasured(); }
            finally { CompanionMetrics.finish(level, CompanionMetrics.Bucket.PET_AI, started); }
        }

        private void tickMeasured() {
            long now = level.getGameTime();
            if (now < nextDecision) return;
            nextDecision = now + 10;
            animal.resetLove();
            pose(animal, false);
            // A path object is not proof of movement: collisions can stall an otherwise valid route.
            if (commanded != null && !animal.getNavigation().isDone()) {
                if (routeProgress == null || routeProgress.distSqr(animal.blockPosition()) >= 1) {
                    routeProgress = animal.blockPosition();
                    routeProgressAt = now;
                } else if (now - routeProgressAt >= 160) {
                    animal.getNavigation().stop();
                    animal.setTarget(null);
                    enemy = null;
                    returning = !pet.cat;
                    commanded = null;
                    routeProgress = null;
                    nextPath = now + 200;
                    pet.blocker = "宠物实际移动受阻，暂缓后重新寻路";
                    pet.activity = "等待通路";
                    return;
                }
            } else routeProgress = null;
            List<Mob> threats = level.getEntitiesOfClass(Mob.class, animal.getBoundingBox().inflate(10, 5, 10),
                    mob -> mob != animal && mob instanceof Enemy && mob.isAlive()
                            && !(mob instanceof TamableAnimal tame && tame.isTame())
                            && !mob.getPersistentData().hasUUID(PetCareService.PET_ID)
                            && !mob.getPersistentData().hasUUID("exiledsettlement.guardianId"));
            Mob nearest = threats.stream().min(Comparator.comparingDouble(animal::distanceToSqr)).orElse(null);
            boolean explosion = threats.stream().anyMatch(mob -> mob instanceof Creeper && animal.distanceToSqr(mob) < 64);
            boolean weak = animal.getHealth() <= animal.getMaxHealth() * .5F;
            boolean overwhelmed = threats.size() >= 3
                    || threats.stream().anyMatch(mob -> mob.getMaxHealth() >= animal.getMaxHealth() * 4);
            boolean danger = settlement.community().evacuation || settlement.community().danger(animal.blockPosition())
                    || animal.isOnFire() || explosion || nearest != null && (pet.cat || weak || overwhelmed);
            if (danger || now < retreatUntil) {
                if (danger) retreatUntil = now + 100;
                enemy = null;
                animal.setTarget(null);
                returning = false;
                pet.activity = "避险";
                if (now < nextPath) return;
                BlockPos refuge = refuge(nearest);
                if (refuge != null) move(refuge, 1.25);
                else { animal.getNavigation().stop(); pet.blocker = "附近没有安全可达避险位置"; }
                return;
            }
            if (pet.status == PetState.Status.ADOPTING) {
                animal.setTarget(null);
                pet.activity = pet.worker == null ? "等待再次亲近" : "等待照料者接近";
                // Reservation suppresses hunting, but does not drag the wild animal toward its future home.
                if (now >= pauseUntil) {
                    pauseUntil = now + 100;
                    BlockPos nearby = chooseNear(animal.blockPosition(), 3, false);
                    if (nearby != null) move(nearby, .7);
                }
                return;
            }
            if (!pet.cat && !weak && !returning) {
                if (enemy == null || !enemy.isAlive()) {
                    enemy = threats.stream().filter(this::attackingFamily)
                            .filter(mob -> !(mob instanceof Creeper))
                            .min(Comparator.comparingDouble(animal::distanceToSqr)).orElse(null);
                    if (enemy != null) combatOrigin = animal.blockPosition();
                }
                if (enemy != null) {
                    if (!settlement.contains(animal.blockPosition()) || !settlement.contains(enemy.blockPosition()) || combatOrigin == null
                            || combatOrigin.distSqr(enemy.blockPosition()) > 256
                            || combatOrigin.distSqr(animal.blockPosition()) > 256 || !attackingFamily(enemy)) {
                        enemy = null;
                        animal.setTarget(null);
                        returning = true;
                    } else {
                        animal.setTarget(enemy);
                        pet.activity = "保护受袭居民";
                        if (animal.distanceToSqr(enemy) <= 4 && animal.hasLineOfSight(enemy)) {
                            animal.getNavigation().stop();
                            if (now >= attackAt) { animal.doHurtTarget(enemy); attackAt = now + 20; }
                        } else move(enemy.blockPosition(), 1.15);
                        return;
                    }
                }
            }
            animal.setTarget(null);
            BuildingRecord home = pet.home == null ? null : settlement.building(pet.home);
            CitizenRecord owner = pet.caretaker == null ? null : settlement.citizen(pet.caretaker);
            BlockPos center = owner != null && owner.bedPosition() != null ? owner.bedPosition()
                    : home != null ? home.position() : pet.lastPosition;
            if (home == null || !home.active() || !home.operational()) {
                pet.activity = "待安置，附近活动";
                if (now >= pauseUntil) {
                    pauseUntil = now + 160;
                    BlockPos nearby = chooseNear(animal.blockPosition(), 3, true);
                    if (nearby != null) move(nearby, .7);
                }
                return;
            }
            boolean night = Math.floorMod(level.getDayTime(), 24000) >= 12000;
            boolean closeHome = animal.blockPosition().distSqr(center) <= 36;
            if (returning && closeHome) returning = false;
            if (night || returning || !settlement.contains(animal.blockPosition())
                    || animal.blockPosition().distSqr(center) > (pet.cat ? 144 : 1024)) {
                pet.activity = closeHome ? "在家休息" : "步行归家";
                if (closeHome && !obstructing()) {
                    animal.getNavigation().stop();
                    pose(animal, true);
                } else {
                    BlockPos destination = chooseNear(center, 4, true);
                    if (destination != null) move(destination, .85);
                    else pet.blocker = "住宅附近没有可达休息位置";
                }
                return;
            }
            if (pet.worker != null && pet.workerUntil >= now) {
                pet.activity = "等待照料者喂食";
                animal.getNavigation().stop();
                return;
            }
            SettlementCitizenEntity companion = owner == null ? null : CitizenProjectionService.findProjection(level, owner);
            boolean canAccompany = companion != null && companion.isAlive() && !companion.isDefending()
                    && !companion.hasActiveConstructionMovement() && !settlement.community().danger(companion.blockPosition())
                    && Math.abs(companion.getY() - center.getY()) <= 4
                    && companion.blockPosition().distSqr(center) <= (pet.cat ? 144 : 1024);
            if (canAccompany && !companion.isSleeping() && animal.distanceToSqr(companion) >= 16
                    && (!pet.cat || Math.floorMod(now + pet.id.hashCode(), 600) < 140)) {
                pet.activity = pet.cat ? "短时亲近家人" : "陪同照料者";
                BlockPos beside = chooseNear(companion.blockPosition(), 3, true);
                if (beside != null) move(beside, .9);
                return;
            }
            if (obstructing() || now >= pauseUntil) {
                pauseUntil = now + 100 + animal.getRandom().nextInt(140);
                BlockPos destination = chooseNear(center, pet.cat ? 10 : 8, true);
                pet.activity = pet.cat ? "住宅附近闲逛" : "住宅附近看守";
                if (destination != null) move(destination, .75);
                return;
            }
            if (animal.getNavigation().isDone()) {
                pet.activity = pet.cat ? "在家附近休息" : "在门前附近休息";
                if (!obstructing()) pose(animal, true);
                if (canAccompany) animal.getLookControl().setLookAt(companion, 20, 20);
            }
        }

        private boolean attackingFamily(Mob mob) {
            if (!(mob instanceof Enemy) || mob instanceof Creeper || !mob.isAlive()
                    || mob instanceof TamableAnimal tame && tame.isTame()
                    || mob.getPersistentData().hasUUID(PetCareService.PET_ID)
                    || mob.getPersistentData().hasUUID("exiledsettlement.guardianId")) return false;
            if (mob.getTarget() instanceof SettlementCitizenEntity citizen
                    && settlement.id().equals(CitizenProjectionService.settlementId(citizen))) return true;
            for (SettlementCitizenEntity citizen : level.getEntitiesOfClass(SettlementCitizenEntity.class,
                    animal.getBoundingBox().inflate(12), candidate -> candidate.isAlive()
                            && settlement.id().equals(CitizenProjectionService.settlementId(candidate)))) {
                if (citizen.getLastHurtByMob() == mob && citizen.tickCount - citizen.getLastHurtByMobTimestamp() <= 100) return true;
            }
            return false;
        }

        private BlockPos refuge(Mob threat) {
            List<BlockPos> candidates = new ArrayList<>();
            BuildingRecord home = pet.home == null ? null : settlement.building(pet.home);
            if (home != null && home.active() && home.operational()) {
                BlockPos atHome = chooseNear(home.position(), 4, true);
                if (atHome != null) candidates.add(atHome);
            }
            for (int i = 0; i < 10; i++) {
                int dx = animal.getRandom().nextInt(19) - 9;
                int dz = animal.getRandom().nextInt(19) - 9;
                BlockPos candidate = floorAt(animal.blockPosition().offset(dx, 0, dz));
                if (candidate != null && safe(candidate, false)
                        && (threat == null || threat.blockPosition().distSqr(candidate) > animal.distanceToSqr(threat) + 9))
                    candidates.add(candidate);
            }
            candidates.sort(Comparator.comparingDouble(candidate -> threat == null ? animal.blockPosition().distSqr(candidate)
                    : -threat.blockPosition().distSqr(candidate)));
            return candidates.stream().filter(candidate -> threat == null
                    || threat.blockPosition().distSqr(candidate) >= 64).findFirst().orElse(null);
        }

        private BlockPos chooseNear(BlockPos center, int radius, boolean withinSettlement) {
            for (int i = 0; i < 8; i++) {
                int dx = animal.getRandom().nextInt(radius * 2 + 1) - radius;
                int dz = animal.getRandom().nextInt(radius * 2 + 1) - radius;
                if (dx * dx + dz * dz > radius * radius) continue;
                BlockPos candidate = floorAt(center.offset(dx, 0, dz));
                if (candidate != null && withinDailyRange(candidate) && safe(candidate, withinSettlement)) return candidate;
            }
            return null;
        }

        private boolean withinDailyRange(BlockPos candidate) {
            if (pet.status != PetState.Status.ACTIVE || level.getGameTime() < retreatUntil) return true;
            BuildingRecord home = pet.home == null ? null : settlement.building(pet.home);
            if (home == null || !home.active() || !home.operational()) return true;
            CitizenRecord owner = pet.caretaker == null ? null : settlement.citizen(pet.caretaker);
            BlockPos center = owner != null && owner.bedPosition() != null ? owner.bedPosition() : home.position();
            return center.distSqr(candidate) <= (pet.cat ? 144 : 1024);
        }

        private BlockPos floorAt(BlockPos position) {
            if (!level.hasChunkAt(position)) return null;
            for (int dy : new int[]{0, 1, -1, 2, -2}) {
                BlockPos candidate = position.offset(0, dy, 0);
                if (level.getBlockState(candidate.below()).isFaceSturdy(level, candidate.below(), Direction.UP)
                        && level.getBlockState(candidate).getCollisionShape(level, candidate).isEmpty()
                        && level.getBlockState(candidate.above()).getCollisionShape(level, candidate.above()).isEmpty())
                    return candidate;
            }
            return null;
        }

        private boolean safe(BlockPos position, boolean withinSettlement) {
            if (!level.hasChunkAt(position) || withinSettlement && !settlement.contains(position)
                    || settlement.community().danger(position) || !level.getFluidState(position).isEmpty()
                    || level.getBlockState(position.below()).is(BlockTags.BEDS)
                    || level.getBlockState(position).getBlock() instanceof DoorBlock
                    || level.getBlockState(position).getBlock() instanceof FenceGateBlock) return false;
            AABB standing = animal.getBoundingBox().move(position.getX() + .5 - animal.getX(),
                    position.getY() - animal.getY(), position.getZ() + .5 - animal.getZ());
            if (!level.noCollision(animal, standing) || !level.getEntitiesOfClass(LivingEntity.class,
                    standing.inflate(.65, .1, .65), living -> living != animal && living.isAlive()).isEmpty()) return false;
            for (BuildingRecord building : settlement.buildings()) {
                if (!building.active()) continue;
                if (building.servicePositions().stream().anyMatch(service -> service.distSqr(position) <= 2)) return false;
            }
            return true;
        }

        private boolean obstructing() {
            if (!safe(animal.blockPosition(), false)) return true;
            return !level.getEntitiesOfClass(SettlementCitizenEntity.class, animal.getBoundingBox().inflate(1.2, .2, 1.2),
                    LivingEntity::isAlive).isEmpty();
        }

        private void move(BlockPos destination, double speed) {
            long now = level.getGameTime();
            if (now < nextPath) return;
            if (destination.equals(commanded) && !animal.getNavigation().isDone()) return;
            var path = CompanionMetrics.createPath(level, true, animal.getNavigation(), destination, 0);
            pathRequests++;
            boolean canMove = path != null && path.canReach()
                    && CompanionMetrics.submitPath(level, true, animal.getNavigation(), path, speed);
            commanded = destination;
            if (canMove) {
                pathFailures = 0;
                nextPath = now + 40;
                if (pet.blocker.contains("路线") || pet.blocker.contains("可达")) pet.blocker = "";
            } else {
                pathFailures = Math.min(4, pathFailures + 1);
                nextPath = now + (40L << pathFailures);
                pet.blocker = "宠物路线受阻，等待通路";
                if (enemy != null) { enemy = null; animal.setTarget(null); returning = true; }
            }
        }
    }
}
