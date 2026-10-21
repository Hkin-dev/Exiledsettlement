package com.hkin.exiledsettlement.companion;

import com.hkin.exiledsettlement.citizen.CitizenProjectionService;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import java.util.*;

/** A reversible controller for registered guardians; vanilla anger never selects a player. */
public final class GuardianAi {
    private GuardianAi() {}
    private static final Map<IronGolem, Control> CONTROLS = new IdentityHashMap<>();
    private record SavedGoal(int priority, Goal goal) {}
    private record Control(List<SavedGoal> movement, List<SavedGoal> targets, GuardGoal goal) {}
    public static long pathRequests;

    public static boolean hostile(SettlementRecord s, LivingEntity target) {
        return target != null && target.isAlive() && !target.isRemoved() && target instanceof Enemy
                && target instanceof Mob && !(target instanceof Creeper) && !(target instanceof Player)
                && !(target instanceof SettlementCitizenEntity)
                && !(target instanceof TamableAnimal tame && tame.isTame())
                && !target.getPersistentData().hasUUID("exiledsettlement.petId")
                && !target.getPersistentData().hasUUID("exiledsettlement.guardianId")
                && s.contains(target.blockPosition())
                && (!(target instanceof NeutralMob) || threateningResident((ServerLevel) target.level(), s, (Mob) target));
    }
    public static boolean threateningResident(ServerLevel level, SettlementRecord s, Mob mob) {
        LivingEntity target = mob.getTarget();
        if (target instanceof SettlementCitizenEntity
                && s.id().equals(CitizenProjectionService.settlementId(target))) return true;
        for (SettlementCitizenEntity resident : level.getEntitiesOfClass(SettlementCitizenEntity.class,
                mob.getBoundingBox().inflate(12), e -> e.isAlive()
                        && s.id().equals(CitizenProjectionService.settlementId(e)))) {
            if (resident.getLastHurtByMob() == mob && resident.tickCount - resident.getLastHurtByMobTimestamp() <= 100)
                return true;
        }
        return false;
    }
    public static void attach(ServerLevel level, SettlementRecord s, GuardianState.Entry entry, IronGolem golem) {
        Control old = CONTROLS.get(golem);
        if (old != null && old.goal.settlement == s && old.goal.entry == entry) return;
        release(golem);
        List<SavedGoal> moves = golem.goalSelector.getAvailableGoals().stream()
                .map(g -> new SavedGoal(g.getPriority(), g.getGoal())).toList();
        List<SavedGoal> targets = golem.targetSelector.getAvailableGoals().stream()
                .map(g -> new SavedGoal(g.getPriority(), g.getGoal())).toList();
        moves.forEach(g -> golem.goalSelector.removeGoal(g.goal));
        targets.forEach(g -> golem.targetSelector.removeGoal(g.goal));
        golem.setTarget(null); golem.setPersistentAngerTarget(null); golem.setRemainingPersistentAngerTime(0);
        var goal = new GuardGoal(level, s, entry, golem);
        CONTROLS.put(golem, new Control(moves, targets, goal));
        golem.goalSelector.addGoal(0, goal);
    }
    public static void release(Entity entity) {
        if (!(entity instanceof IronGolem golem)) return;
        Control c = CONTROLS.remove(golem);
        if (c == null) return;
        golem.goalSelector.removeGoal(c.goal); c.goal.stop();
        c.movement.forEach(g -> golem.goalSelector.addGoal(g.priority, g.goal));
        c.targets.forEach(g -> golem.targetSelector.addGoal(g.priority, g.goal));
    }
    public static void releaseAll() { for (IronGolem golem : List.copyOf(CONTROLS.keySet())) release(golem); }
    public static void tickEntity(LivingEntity entity) {
        if (!(entity instanceof IronGolem golem) || !CONTROLS.containsKey(golem)) return;
        golem.setPersistentAngerTarget(null); golem.setRemainingPersistentAngerTime(0);
        Control c = CONTROLS.get(golem);
        if (golem.getTarget() != null && !hostile(c.goal.settlement, golem.getTarget())) golem.setTarget(null);
    }
    public static void holdForRepair(IronGolem golem, long until) {
        Control c = CONTROLS.get(golem);
        if (c != null) c.goal.repairUntil = until;
    }
    public static String activity(IronGolem golem) {
        Control c = CONTROLS.get(golem);
        return c == null ? "尚未加载行为" : c.goal.status;
    }
    private static final class GuardGoal extends Goal {
        final ServerLevel level; final SettlementRecord settlement; final GuardianState.Entry entry; final IronGolem golem;
        BlockPos fightOrigin, destination;
        net.minecraft.world.phys.Vec3 chaseProgressPosition;
        LivingEntity enemy;
        long nextScan, nextPath, attackAt, repairUntil, failedSince, chaseProgressTick;
        boolean returning;
        int patrolIndex;
        String status = "巡逻待命";
        GuardGoal(ServerLevel l, SettlementRecord s, GuardianState.Entry e, IronGolem g) {
            level = l; settlement = s; entry = e; golem = g;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.TARGET));
        }
        @Override public boolean canUse() { return golem.isAlive() && !golem.isRemoved(); }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void stop() {
            golem.getNavigation().stop(); golem.setTarget(null);
            if (enemy != null) returning = true;
            enemy = null;
        }
        @Override public void tick() {
            if (!CompanionMetrics.ENABLED) { tickMeasured(); return; }
            long started = CompanionMetrics.start();
            try { tickMeasured(); }
            finally { CompanionMetrics.finish(level, CompanionMetrics.Bucket.GUARDIAN_AI, started); }
        }
        private void tickMeasured() {
            long now = level.getGameTime();
            if (!settlement.active() || golem.isLeashed() || golem.isPassenger()) { stop(); status = "暂停巡逻"; return; }
            if (enemy != null && chaseProgressPosition != null
                    && golem.position().distanceToSqr(chaseProgressPosition) >= .25) {
                chaseProgressPosition = golem.position(); chaseProgressTick = now;
            }
            if (enemy != null && (!hostile(settlement, enemy) || fightOrigin == null
                    || enemy.blockPosition().distSqr(fightOrigin) > 24 * 24
                    || golem.blockPosition().distSqr(fightOrigin) > 24 * 24
                    || !settlement.contains(golem.blockPosition()) || now - failedSince > 200 && failedSince > 0
                    || now - chaseProgressTick > 200)) {
                enemy = null; golem.setTarget(null); destination = entry.anchor; nextPath = 0;
                returning = true;
                status = "返回岗位"; nextScan = now + 80;
            }
            if (returning && golem.blockPosition().distSqr(entry.anchor) < 9) {
                returning = false; destination = null;
            }
            if (now >= nextScan && enemy == null && !returning && settlement.contains(golem.blockPosition())) {
                nextScan = now + 20;
                enemy = level.getEntitiesOfClass(Mob.class, golem.getBoundingBox().inflate(12),
                                m -> hostile(settlement, m) && golem.hasLineOfSight(m)).stream()
                        .min(Comparator.<Mob>comparingInt(m -> threateningResident(level, settlement, m) ? 0 : 1)
                                .thenComparingDouble(golem::distanceToSqr)).orElse(null);
                if (enemy != null) {
                    fightOrigin = golem.blockPosition(); failedSince = 0; nextPath = 0;
                    chaseProgressPosition = golem.position(); chaseProgressTick = now;
                }
            }
            if (enemy != null) {
                status = "保护居民"; golem.setTarget(enemy); repairUntil = 0;
                golem.getLookControl().setLookAt(enemy, 30, 30);
                if (golem.distanceToSqr(enemy) <= 9 && golem.hasLineOfSight(enemy)) {
                    golem.getNavigation().stop(); failedSince = 0; chaseProgressTick = now;
                    if (now >= attackAt) { golem.doHurtTarget(enemy); attackAt = now + 20; }
                } else if (now >= nextPath) {
                    nextPath = now + 20; pathRequests++;
                    var path = CompanionMetrics.createPath(level, false, golem.getNavigation(), enemy, 0);
                    boolean moved = path != null && path.canReach()
                            && CompanionMetrics.submitPath(level, false, golem.getNavigation(), path, 0.9);
                    if (!moved && failedSince == 0) failedSince = now;
                    if (moved) failedSince = 0;
                }
                return;
            }
            golem.setTarget(null);
            if (now < repairUntil) { golem.getNavigation().stop(); status = "接受工匠维修"; return; }
            if (now < nextPath) return;
            nextPath = now + 100;
            // Explosive threats are avoided; a guardian never deliberately closes to strike a creeper.
            var creeper = level.getEntitiesOfClass(Creeper.class, golem.getBoundingBox().inflate(7), Entity::isAlive)
                    .stream().findFirst().orElse(null);
            if (creeper != null) {
                double dx = golem.getX() - creeper.getX(), dz = golem.getZ() - creeper.getZ();
                double n = Math.max(1, Math.sqrt(dx * dx + dz * dz));
                destination = BlockPos.containing(golem.getX() + dx / n * 7, golem.getY(), golem.getZ() + dz / n * 7);
                status = "避开爆炸危险";
            } else if (returning) {
                destination = entry.anchor; status = "返回岗位";
            } else if (destination == null || golem.blockPosition().distSqr(destination) < 9
                    || golem.getNavigation().isDone()) {
                destination = patrolPoint(); status = "巡逻";
            }
            if (destination == null || !level.hasChunkAt(destination) || !settlement.contains(destination)) {
                destination = entry.anchor; returning = true; status = "返回岗位";
            }
            if (level.hasChunkAt(destination) && settlement.contains(destination)) {
                pathRequests++;
                var path = CompanionMetrics.createPath(level, false, golem.getNavigation(), destination, 1);
                if (path != null && path.canReach()) CompanionMetrics.submitPath(level, false, golem.getNavigation(), path, 0.65);
                else {
                    golem.getNavigation().stop(); destination = entry.anchor; returning = true;
                    status = "道路受阻，等待通行"; nextPath = now + 200;
                }
            }
        }
        private BlockPos patrolPoint() {
            List<BlockPos> points = new ArrayList<>(); points.add(entry.anchor);
            settlement.buildings().stream().filter(b -> b.active() && b.operational()
                    && (b.type().isHouse() || b.type().isStorage())).limit(24)
                    .forEach(b -> b.servicePositions().stream().limit(2).forEach(points::add));
            for (int i = 0; i < Math.min(12, points.size() * 2); i++) {
                BlockPos base = points.get(Math.floorMod(patrolIndex++, points.size()));
                BlockPos p = base.offset(((patrolIndex & 1) == 0 ? 3 : -3), 0, ((patrolIndex & 2) == 0 ? 3 : -3));
                if (GuardianService.safeSite(level, settlement, golem, p, false)) return p;
            }
            return entry.anchor;
        }
    }
}
