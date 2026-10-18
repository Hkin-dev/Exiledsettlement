package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.blueprint.BlueprintRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Per-resident movement executor owned by the site. Never creates blocks or replans an in-flight leg. */
public final class ScaffoldMovementController {
    private UUID worker;
    private BlockPos station;
    private BlockPos workTarget;
    private long retryAfter;
    private long lastMotion;
    private long routeDeadline;
    private long unavailableSince = -1;
    private boolean recovering;
    private boolean recoveryCompleted;
    private final Set<BlockPos> recoveryAttempted = new HashSet<>();
    private List<BlockPos> surfaceExit = List.of();
    private net.minecraft.world.phys.Vec3 lastPosition;
    private final Map<List<BlockPos>, Integer> failures = new HashMap<>();
    private List<BlockPos> route = List.of();
    private long calls, totalNanos, maxNanos;

    public void completedTarget() { station = null; route = List.of(); }
    public boolean unavailableFor(long tick, long duration) { return unavailableSince >= 0 && tick - unavailableSince >= duration; }
    public boolean isRecovering() { return recovering; }
    public boolean consumeRecoveryCompleted() {
        boolean result = recoveryCompleted; recoveryCompleted = false; return result;
    }
    public String diagnostics() {
        return "target=" + workTarget + ",recovering=" + recovering + ",station=" + station + ",route=" + route + ",failures=" + failures
                + ",calls=" + calls + ",totalMs=" + totalNanos / 1_000_000.0 + ",maxMs=" + maxNanos / 1_000_000.0;
    }

    public boolean approach(ServerLevel level, BlueprintRecord site, SettlementCitizenEntity entity,
            BlockPos target, double speed) {
        long started = System.nanoTime();
        try { return advance(level, site, entity, target, speed); }
        finally { long cost = System.nanoTime() - started; calls++; totalNanos += cost; maxNanos = Math.max(maxNanos, cost); }
    }

    private boolean advance(ServerLevel level, BlueprintRecord site, SettlementCitizenEntity entity,
            BlockPos target, double speed) {
        if (!entity.getUUID().equals(worker)) {
            worker = entity.getUUID(); station = null; route = List.of();
            lastPosition = entity.position(); lastMotion = level.getGameTime();
        }
        if (!target.equals(workTarget) && !entity.hasActiveConstructionMovement() && !recovering) {
            CitizenNavigationReservationService.releaseStation(level, entity.getUUID());
            station = null; route = List.of(); workTarget = target.immutable();
            // Three failures blacklist a route for this task, not every future target.
            failures.clear();
            retryAfter = 0;
            unavailableSince = -1;
        }
        if (lastPosition == null || entity.position().distanceToSqr(lastPosition) >= 0.0625) {
            lastPosition = entity.position(); lastMotion = level.getGameTime();
        }
        if (station != null && (level.getGameTime() - lastMotion >= 200
                || routeDeadline > 0 && level.getGameTime() >= routeDeadline)) {
            entity.finishConstructionMovement(); entity.getNavigation().stop();
            failures.merge(route, 1, Integer::sum);
            CitizenNavigationReservationService.releaseStation(level, entity.getUUID());
            if (unavailableSince < 0) unavailableSince = level.getGameTime();
            station = null; retryAfter = level.getGameTime() + 20;
        }
        if (station != null) CitizenNavigationReservationService.claimStation(
                level, entity.getUUID(), station, entity.blockPosition(), level.getGameTime());
        if (entity.hasActiveConstructionMovement()) return false;
        if (!surfaceExit.isEmpty()) {
            if (entity.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(
                    surfaceExit.getLast())) >= 0.09) {
                failures.merge(surfaceExit, 1, Integer::sum);
                retryAfter = level.getGameTime() + 20;
            }
            surfaceExit = List.of();
        }
        if (recovering) {
            recovering = false;
            recoveryCompleted = station != null && entity.position().distanceToSqr(
                    net.minecraft.world.phys.Vec3.atBottomCenterOf(station)) < 0.09;
            if (recoveryCompleted) unavailableSince = -1;
            if (!recoveryCompleted) {
                failures.merge(route, 1, Integer::sum);
                entity.traceConstructionDecision("site-route-recovery-failed:" + station);
            }
            CitizenNavigationReservationService.releaseStation(level, entity.getUUID());
            station = null;
            route = List.of();
            return false;
        }
        BlockPos currentFeet = entity.blockPosition();
        // Scaffold bodies are climbing cells, not horizontal work platforms.
        // Finish the climb onto the exposed top before claiming a work station.
        if (level.getBlockState(currentFeet).is(Blocks.SCAFFOLDING)
                && !level.getBlockState(currentFeet.above()).is(Blocks.SCAFFOLDING)
                && entity.canTraverseRecordedConstructionEdge(currentFeet, currentFeet.above())) {
            if (level.getGameTime() < retryAfter) return false;
            List<BlockPos> exit = List.of(currentFeet.immutable(), currentFeet.above());
            if (failures.getOrDefault(exit, 0) < 3) {
                if (entity.beginRecordedConstructionRoute(List.of(currentFeet.above()), speed)) {
                    surfaceExit = exit;
                    entity.traceConstructionDecision("scaffold-surface-exit:" + currentFeet);
                    return false;
                }
                failures.merge(exit, 1, Integer::sum);
                retryAfter = level.getGameTime() + 20;
            }
        }
        if (!level.getBlockState(currentFeet).is(Blocks.SCAFFOLDING)
                && CitizenWorkService.canConstructFromCurrentPosition(entity, target)
                && clearsPlacementGroup(site, target, entity.getBoundingBox())) {
            unavailableSince = -1;
            return CitizenNavigationReservationService.claimStation(level, entity.getUUID(), entity.blockPosition(),
                    entity.blockPosition(), level.getGameTime());
        }
        if (level.getGameTime() < retryAfter) return false;
        if (station != null) {
            if (!entity.getNavigation().isDone()) return false;
            failures.merge(route, 1, Integer::sum);
            CitizenNavigationReservationService.releaseStation(level, entity.getUUID());
            if (unavailableSince < 0) unavailableSince = level.getGameTime();
            station = null; retryAfter = level.getGameTime() + 20;
            return false;
        }
        BlockPos start = entity.normalizeConstructionStandingPosition();
        // Only traverse actual scaffold cells (and their tops), never speculative future blocks.
        Set<BlockPos> cells = new HashSet<>();
        for (BlockPos p : site.temporaryFootings().keySet()) if (level.getBlockState(p).is(Blocks.SCAFFOLDING)) {
            if (entity.canOccupyConstructionPosition(p)) cells.add(p);
            if (entity.canOccupyConstructionPosition(p.above())) cells.add(p.above());
        }
        // The unfinished arms need not connect yet. Include the real exterior ground
        // walkway so a worker can descend a tower, walk around, and enter the next one.
        var template = com.hkin.exiledsettlement.blueprint.BlueprintTemplates.create(site);
        for (var placement : template.placements()) {
            BlockPos floor = placement.position();
            if (level.hasChunkAt(floor) && entity.canOccupyConstructionPosition(floor)) cells.add(floor);
            BlockPos surface = placement.position().above();
            if (level.hasChunkAt(surface) && entity.canOccupyConstructionPosition(surface)) cells.add(surface);
        }
        for (BlockPos edge : ExteriorScaffoldFrame.ring(template, template.minCorner().getY() + 1)) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -3; dy <= 1; dy++) {
                    BlockPos p = edge.offset(dx, dy, dz);
                    if (p.getX() >= template.minCorner().getX() && p.getX() <= template.maxCorner().getX()
                            && p.getZ() >= template.minCorner().getZ() && p.getZ() <= template.maxCorner().getZ()) continue;
                    if (level.hasChunkAt(p) && entity.canStandAtPosition(p)) cells.add(p);
                }
            }
        }
        cells.add(start);
        var queue = new ArrayDeque<BlockPos>();
        var parent = new HashMap<BlockPos, BlockPos>();
        queue.add(start); parent.put(start, start);
        while (!queue.isEmpty()) {
            BlockPos p = queue.removeFirst();
            if (!level.getBlockState(p).is(Blocks.SCAFFOLDING)
                    && !p.equals(start) && !p.equals(target) && !p.above().equals(target)
                    && clearsPlacementGroup(site, target, entity.getBoundingBox().move(
                    p.getX() + 0.5 - entity.getX(), p.getY() - entity.getY(), p.getZ() + 0.5 - entity.getZ()))
                    && CitizenWorkService.constructionWorkStationGeometry(p, target)
                    && CitizenWorkService.constructionStationInRange(level, p, target, entity)) {
                var steps = new ArrayList<BlockPos>();
                for (BlockPos n = p; !n.equals(start); n = parent.get(n)) steps.add(n);
                Collections.reverse(steps);
                List<BlockPos> key = new ArrayList<>(); key.add(start); key.addAll(steps); key = List.copyOf(key);
                if (failures.getOrDefault(key, 0) < 3) {
                    if (CitizenNavigationReservationService.claimStation(level, entity.getUUID(), p,
                            entity.blockPosition(), level.getGameTime())) {
                    if (entity.beginRecordedConstructionRoute(steps, speed)) {
                        station = p; route = key; lastMotion = level.getGameTime();
                        routeDeadline = lastMotion + Math.max(200, steps.size() * 30L); return false;
                    }
                    failures.merge(key, 1, Integer::sum);
                    CitizenNavigationReservationService.releaseStation(level, entity.getUUID());
                    }
                    // An occupied endpoint must not disconnect the graph behind it.
                }
            }
            for (Direction d : Direction.values()) for (int rise : new int[]{0, -1, 1}) {
                if (d.getAxis().isVertical() && rise != 0) continue;
                BlockPos next = p.relative(d).above(rise);
                if (!cells.contains(next) || parent.containsKey(next)
                        || !entity.canTraverseRecordedConstructionEdge(p, next)) continue;
                parent.put(next, p); queue.add(next);
            }
        }
        // Ground approach is delegated once. Do not send a high resident to a ground target.
        if (!level.getBlockState(start).is(Blocks.SCAFFOLDING)
                && !level.getBlockState(start.below()).is(Blocks.SCAFFOLDING)) {
            List<BlockPos> approaches = new ArrayList<>();
            for (BlockPos p : BlockPos.betweenClosed(target.offset(-3, -3, -3), target.offset(3, 1, 3))) {
                if (!p.equals(target) && !p.above().equals(target)
                        && clearsPlacementGroup(site, target, entity.getBoundingBox().move(
                        p.getX() + 0.5 - entity.getX(), p.getY() - entity.getY(), p.getZ() + 0.5 - entity.getZ()))
                        && entity.canStandAtPosition(p) && CitizenWorkService.constructionWorkStationGeometry(p, target)
                        && CitizenWorkService.constructionStationInRange(level, p, target, entity)) approaches.add(p.immutable());
            }
            for (BlockPos p : cells) {
                // This connected component has already been searched. Walking to
                // another entrance of it cannot expose a new work station and
                // would reset the unavailable timer forever.
                if (parent.containsKey(p)) continue;
                if (level.getBlockState(p).is(Blocks.SCAFFOLDING) && entity.canStandAtPosition(p.below())) approaches.add(p);
                if (level.getBlockState(p).is(Blocks.SCAFFOLDING)
                        && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) approaches.add(p);
            }
            approaches.sort(Comparator.comparingDouble(p -> p.distSqr(start)));
            int attempts = 0;
            for (BlockPos p : approaches) {
                if (p.equals(start)) continue;
                List<BlockPos> key = List.of(start, p.immutable());
                if (failures.getOrDefault(key, 0) >= 3) continue;
                if (!CitizenNavigationReservationService.claimStation(level, entity.getUUID(), p,
                        entity.blockPosition(), level.getGameTime())) continue;
                if (attempts++ >= 4) {
                    CitizenNavigationReservationService.releaseStation(level, entity.getUUID());
                    break;
                }
                if (!entity.hasReachablePathTo(p)) {
                    failures.merge(key, 1, Integer::sum);
                    CitizenNavigationReservationService.releaseStation(level, entity.getUUID());
                    continue;
                }
                String error = entity.navigateTo(p, speed);
                if (error.isEmpty()) {
                    station = p.immutable(); route = key; lastMotion = level.getGameTime();
                    routeDeadline = lastMotion + Math.max(200, start.distManhattan(p) * 30L); return false;
                }
                failures.merge(key, 1, Integer::sum);
                CitizenNavigationReservationService.releaseStation(level, entity.getUUID());
            }
        }
        // A failed work-station search is not a movement command. Leave the
        // elevated component through an actually proven route before retargeting.
        // Reuse this bounded graph rather than starting another world scan.
        if (unavailableFor(level.getGameTime(), 40) && !recoveryAttempted.contains(target)
                && (start.getY() > template.minCorner().getY() + 1
                || level.getBlockState(start).is(Blocks.SCAFFOLDING)
                || level.getBlockState(start.below()).is(Blocks.SCAFFOLDING))) {
            var exits = parent.keySet().stream()
                    .filter(p -> !p.equals(start) && p.getY() <= template.minCorner().getY() + 1)
                    .filter(p -> entity.canStandAtPosition(p)
                            && !level.getBlockState(p).is(Blocks.SCAFFOLDING)
                            && !level.getBlockState(p.below()).is(Blocks.SCAFFOLDING))
                    .sorted(Comparator.comparingDouble(p -> p.distSqr(start))).toList();
            int attempts = 0;
            for (BlockPos exit : exits) {
                if (attempts++ >= 4) break;
                List<BlockPos> steps = new ArrayList<>();
                for (BlockPos n = exit; !n.equals(start); n = parent.get(n)) steps.add(n);
                Collections.reverse(steps);
                List<BlockPos> key = new ArrayList<>(); key.add(start); key.addAll(steps); key = List.copyOf(key);
                if (failures.getOrDefault(key, 0) >= 3) continue;
                if (!CitizenNavigationReservationService.claimStation(level, entity.getUUID(), exit,
                        entity.blockPosition(), level.getGameTime())) continue;
                if (entity.beginRecordedConstructionRoute(steps, speed)) {
                    recoveryAttempted.add(target.immutable());
                    recovering = true; station = exit; route = key; lastMotion = level.getGameTime();
                    routeDeadline = lastMotion + Math.max(200, steps.size() * 30L);
                    entity.traceConstructionDecision("site-route-recovery:" + start + "->" + exit);
                    return false;
                }
                failures.merge(key, 1, Integer::sum);
                CitizenNavigationReservationService.releaseStation(level, entity.getUUID());
            }
            // A roof cell can be isolated from the one-block graph while a
            // clear, short descent beside it still exists. Use the existing
            // swept-column drop executor, not a teleport or a speculative edge.
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                for (int drop = 2; drop <= 6; drop++) {
                    BlockPos exit = start.relative(direction).below(drop);
                    if (exit.getY() > template.minCorner().getY() + 1
                            || !entity.canBeginConstructionDropFrom(start, exit)) continue;
                    List<BlockPos> key = List.of(start, exit);
                    if (failures.getOrDefault(key, 0) >= 3
                            || !CitizenNavigationReservationService.claimStation(level, entity.getUUID(), exit,
                            entity.blockPosition(), level.getGameTime())) continue;
                    if (entity.beginConstructionDropMovement(exit, speed)) {
                        recoveryAttempted.add(target.immutable());
                        recovering = true; station = exit; route = key; lastMotion = level.getGameTime();
                        routeDeadline = lastMotion + 200;
                        entity.traceConstructionDecision("site-route-recovery-drop:" + start + "->" + exit);
                        return false;
                    }
                    failures.merge(key, 1, Integer::sum);
                    CitizenNavigationReservationService.releaseStation(level, entity.getUUID());
                }
            }
        }
        CitizenNavigationReservationService.releaseStation(level, entity.getUUID());
        retryAfter = level.getGameTime() + 20;
        if (unavailableSince < 0) unavailableSince = level.getGameTime();
        entity.traceConstructionDecision("site-route-unavailable:target=" + target + ":feet=" + start
                + ":nodes=" + cells.size() + ":visited=" + parent.size() + ":" + diagnostics());
        return false;
    }

    // Beds and doors are atomic placements. Reaching the requested half is not
    // sufficient when the worker is standing in the other half's volume.
    private static boolean clearsPlacementGroup(BlueprintRecord site, BlockPos target,
            net.minecraft.world.phys.AABB bounds) {
        var template = com.hkin.exiledsettlement.blueprint.BlueprintTemplates.create(site);
        var placement = template.placementAt(target);
        if (placement == null) return true; // Temporary scaffold, not blueprint geometry.
        return com.hkin.exiledsettlement.blueprint.BlueprintPlacementGroups.parts(template, placement).stream()
                .noneMatch(part -> bounds.intersects(new net.minecraft.world.phys.AABB(part.position()).deflate(0.01)));
    }
}
