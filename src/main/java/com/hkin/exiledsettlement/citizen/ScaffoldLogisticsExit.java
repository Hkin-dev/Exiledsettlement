package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.blueprint.BlueprintRecord;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** A bounded, continuous exit. Horizontal ground transit is not a repeated descent. */
public final class ScaffoldLogisticsExit {
    private List<BlockPos> route = List.of();
    private final Map<List<BlockPos>, Integer> failures = new HashMap<>();
    private long deadline, retryAfter;
    private final UUID siteId;

    public ScaffoldLogisticsExit(UUID siteId) { this.siteId = siteId; }
    public UUID siteId() { return siteId; }

    private static boolean groundHeight(ServerLevel level,BlueprintRecord site,BlockPos feet,int groundY,boolean groundWork){
        if(groundWork || feet.getY()<=groundY && feet.getY()>=groundY-2)return true;
        // On a hillside an ordinary landing outside the building can be higher
        // than its floor. Do not require another descent after leaving the frame.
        var support=level.getBlockState(feet.below());
        return !site.containsColumn(feet) && support.getFluidState().isEmpty()
                && support.isFaceSturdy(level,feet.below(),Direction.UP)
                && com.hkin.exiledsettlement.terrain.TerrainService.natural(support);
    }

    public boolean advance(ServerLevel level, BlueprintRecord site, SettlementCitizenEntity entity, double speed) {
        long now = level.getGameTime();
        int groundY = BlueprintTemplates.create(site).minCorner().getY() + 1;
        BlockPos feet = entity.navigationFeetPosition();
        boolean groundWork=ConstructionSiteController.groundWork(site);
        // A collapsed scaffold can already leave the resident standing safely
        // on ordinary ground before the recorded sideways landing. Hand off
        // there instead of demanding one exact exit cell and replaying descent.
        // The local scaffold controller suspends gravity, so vanilla onGround
        // may remain false after reaching a supported landing. Validate its
        // actual feet height and support, then restore ordinary gravity.
        if (Math.abs(entity.getY() - feet.getY()) <= 0.10D
                && groundHeight(level,site,feet,groundY,groundWork)
                && !ConstructionSiteController.recordedSupport(level,site,feet)
                && !ConstructionSiteController.recordedSupport(level,site,feet.below())
                && level.hasChunkAt(feet) && !level.getBlockState(feet).is(Blocks.SCAFFOLDING)
                && !level.getBlockState(feet.below()).is(Blocks.SCAFFOLDING)
                && entity.canStandAtPosition(feet)) {
            entity.finishConstructionMovement();
            CitizenNavigationReservationService.releaseStation(level, entity.getUUID());
            route = List.of();
            return true;
        }
        if (!route.isEmpty()) {
            // Vanilla scaffold descent can settle about 0.30 blocks off the
            // exact center while still occupying the selected safe ground
            // cell. Requiring strictly less than 0.30 caused a completed exit
            // to be retried forever. This remains a landing-cell check, not an
            // enlarged block interaction range.
            boolean nearExit = entity.position().distanceToSqr(Vec3.atBottomCenterOf(route.getLast())) <= 0.16;
            if (nearExit && Math.abs(entity.getY() - route.getLast().getY()) <= 0.10) {
                entity.finishConstructionMovement();
                CitizenNavigationReservationService.releaseStation(level, entity.getUUID());
                route = List.of();
                return true;
            }
            if (entity.hasActiveConstructionMovement() && now < deadline) {
                CitizenNavigationReservationService.claimStation(level, entity.getUUID(), route.getLast(), entity.blockPosition(), now);
                return false;
            }
            // Navigation's arrival tolerance is wider than an actual ground landing.
            if (nearExit && now < deadline) return false;
            entity.finishConstructionMovement();
            CitizenNavigationReservationService.releaseStation(level, entity.getUUID());
            failures.merge(route, 1, Integer::sum);
            entity.traceConstructionDecision("supply-exit-failed:" + route);
            route = List.of(); retryAfter = now + 20;
        }
        if (now < retryAfter) return false;
        BlockPos start = entity.normalizeConstructionStandingPosition();
        var parents = new LinkedHashMap<BlockPos, BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        parents.put(start, start); queue.add(start);
        int attempted = 0;
        while (!queue.isEmpty() && parents.size() <= 1024) {
            BlockPos cell = queue.removeFirst();
            if (!cell.equals(start) && (groundWork || cell.getY()<=groundY
                    || groundHeight(level,site,cell,groundY,false))
                    && !ConstructionSiteController.recordedSupport(level,site,cell)
                    && !ConstructionSiteController.recordedSupport(level,site,cell.below())
                    && !level.getBlockState(cell).is(Blocks.SCAFFOLDING)
                    && !level.getBlockState(cell.below()).is(Blocks.SCAFFOLDING)
                    && entity.canStandAtPosition(cell)) {
                var steps = new ArrayList<BlockPos>();
                for (BlockPos p = cell; !p.equals(start); p = parents.get(p)) steps.add(p);
                Collections.reverse(steps);
                var key = new ArrayList<BlockPos>(); key.add(start); key.addAll(steps);
                if (failures.getOrDefault(key, 0) < 3 && failures.size() < 256 && attempted++ < 4
                        && CitizenNavigationReservationService.claimStation(level, entity.getUUID(), cell, entity.blockPosition(), now)) {
                    if (entity.beginRecordedConstructionRoute(steps, speed)) {
                        route = List.copyOf(key);
                        deadline = now + Math.max(200, steps.size() * 30L);
                        entity.traceConstructionDecision("supply-exit-route:" + route);
                        return false;
                    }
                    failures.merge(List.copyOf(key), 1, Integer::sum);
                    CitizenNavigationReservationService.releaseStation(level, entity.getUUID());
                }
            }
            var neighbors=new ArrayList<BlockPos>();neighbors.add(cell.above());neighbors.add(cell.below());
            for(var d:Direction.Plane.HORIZONTAL)for(int dy:new int[]{0,-1,1})neighbors.add(cell.relative(d).offset(0,dy,0));
            for (BlockPos next : neighbors) {
                if (parents.containsKey(next) || next.distManhattan(start) > 32 || !level.hasChunkAt(next)) continue;
                // On real ground, never climb a tower merely to leave its base.
                if (!groundWork && start.getY() <= groundY && next.getY() > start.getY()
                        && next.getX()==cell.getX() && next.getZ()==cell.getZ()) continue;
                if (!entity.canTraverseRecordedConstructionEdge(cell, next)) continue;
                parents.put(next, cell); queue.addLast(next);
            }
        }
        retryAfter = now + 40;
        entity.traceConstructionDecision("supply-exit-unavailable:feet=" + start + ":visited=" + parents.size());
        return false;
    }
}
