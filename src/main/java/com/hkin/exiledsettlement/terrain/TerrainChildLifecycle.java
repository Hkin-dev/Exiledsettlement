package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.blueprint.BlueprintRecord;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import java.util.*;

/** Cancels planning intent only; paid blocks, material records and safe cleanup remain intact. */
public final class TerrainChildLifecycle {
    private static final Map<SettlementRecord,Integer> CURSORS=new WeakHashMap<>();
    private TerrainChildLifecycle() {}

    public static boolean cancel(SettlementRecord s,BlueprintRecord parent) {
        if(!parent.cancellationQueued())return false;
        boolean changed=TerrainService.forgetSurvey(s,parent.id());
        if(parent.terrainProject()!=null && !safetyExit(parent.terrainProject()))
            changed|=stop(s,parent.terrainProject());
        var queue=new ArrayDeque<UUID>();queue.add(parent.id());
        for(var route:s.autoRoads().routes.values())if(!route.manualBridge && parent.id().equals(route.building))queue.add(route.id);
        var seen=new HashSet<UUID>();
        while(!queue.isEmpty()) {
            var id=queue.removeFirst();if(!seen.add(id))continue;
            changed|=TerrainService.forgetSurvey(s,id);
            var source=s.blueprint(id);
            var children=new LinkedHashMap<UUID,TerrainProject>();
            for(var p:s.terrain().dependingOn(id))children.put(p.id,p);
            // Older saves may retain only RegionOwner, without the redundant dependency entry.
            if(source!=null && source.region()!=null)
                for(var p:s.terrain().projects.values())if(id.equals(p.regionOwner))children.put(p.id,p);
            for(var p:List.copyOf(children.values())) {
                // A cancelled job cannot strand a resident in its hole or on a temporary route.
                // ResidentEgressService owns the final release of these short-lived safety jobs.
                if(safetyExit(p))continue;
                if(paidRoadMaintenance(s,p)) {
                    if(s.autoRoads().routes.values().stream().noneMatch(route->route.id.equals(id)))changed|=p.dependencies.remove(id);
                    continue;
                }
                changed|=p.dependencies.remove(id);
                boolean withdrawRegionOwner=Objects.equals(p.regionOwner,id);
                if(source!=null && source.region()!=null && source.region().workingBuilding!=null
                        && (Objects.equals(p.regionOwner,source.region().workingBuilding) || Objects.equals(p.regionOwner,id))
                        && s.formalBlueprints().stream().noneMatch(other->other!=source && !other.cancellationQueued()
                            && other.region()!=null && Objects.equals(other.region().workingBuilding,source.region().workingBuilding))) {
                    changed|=p.dependencies.remove(source.region().workingBuilding);
                    withdrawRegionOwner=true;
                }
                // The authoritative old-save owner is also revoked by an explicit cancellation.
                // Do not let the surviving building revive a withdrawn maintenance request.
                if(withdrawRegionOwner && p.regionOwner!=null){p.regionOwner=null;changed=true;}
                if(!p.areaClearance && p.playerBridge==null && !hasOwner(s,p,new HashSet<>())) {
                    if(!p.completed)changed|=stop(s,p);
                    queue.add(p.id);
                }
            }
        }
        if(changed)PublicGroundService.requestReview(s);
        return changed;
    }

    /** Existing callers may still queue cancellation directly; catch them before planning or workers run. */
    public static boolean tick(SettlementRecord s,long tick) {
        boolean changed=false;
        for(var parent:s.formalBlueprints())if(parent.cancellationQueued())changed|=cancel(s,parent);
        if(Math.floorMod(tick,20)==0)changed|=reconcile(s);
        return changed;
    }

    /** Withdraw only identifiable, exclusively owned post-build reviews; never infer damage history. */
    public static boolean retireFoundationReviews(SettlementRecord s,long tick) {
        if(Math.floorMod(tick,20L)!=0)return false;
        boolean changed=false;long cursor=Math.floorDiv(tick,20L)*16L;
        var buildings=List.copyOf(s.buildings());
        for(int n=0;n<Math.min(16,buildings.size());n++) {
            var building=buildings.get((int)Math.floorMod(cursor+n,(long)buildings.size()));
            // A completed-building survey was automatic. Do not clear an extant pending request.
            if(s.blueprint(building.id())==null)changed|=TerrainService.forgetSurvey(s,building.id());
        }
        var pending=s.terrain().unfinishedProjects();
        for(int n=0;n<Math.min(16,pending.size());n++) {
            var project=pending.get((int)Math.floorMod(cursor+n,(long)pending.size()));
            if(!exclusiveFoundationReview(s,project))continue;
            changed|=TerrainService.forgetSurvey(s,project.id);
            // stop() retains noncontiguous paid fills, records and cleanup supports.
            // Existing worker cancellation and egress own the physical departure.
            changed|=stop(s,project);
        }
        return changed;
    }

    private static boolean exclusiveFoundationReview(SettlementRecord s,TerrainProject p) {
        if(p.completed || p.cancelled || !p.name.startsWith("修整地基") || p.layoutVersion<=0
                || p.explicit || p.publicGround || p.road || p.bridge || p.areaClearance || p.roadReclaim
                || p.entranceAccess || p.playerBridge!=null || safetyExit(p) || p.regionOwner!=null
                || p.dependencies.size()!=1)return false;
        var owner=p.dependencies.iterator().next();
        // A shared or previously transferred construction order is deliberately left alone.
        // Ordinary "地基与台地" orders never enter this name-and-owner migration.
        return s.building(owner)!=null && s.blueprint(owner)==null;
    }

    /** Bounded repair of old saves whose cancelled parent has already been removed. */
    public static boolean reconcile(SettlementRecord s) {
        var candidates=new LinkedHashMap<UUID,TerrainProject>();
        for(var p:s.terrain().unfinishedProjects())candidates.put(p.id,p);
        // Region cancellation already marks its child cancelled before this service runs.
        // Its still-live cleanup carrier must also release unpaid reservations and descendants.
        for(var b:s.blueprints())if(b.cancellationQueued() && b.terrainProject()!=null)candidates.put(b.id(),b.terrainProject());
        var pending=List.copyOf(candidates.values());if(pending.isEmpty())return false;
        int cursor=CURSORS.getOrDefault(s,0),count=Math.min(16,pending.size());boolean changed=false;
        for(int n=0;n<count;n++) {
            var p=pending.get(Math.floorMod(cursor+n,pending.size()));
            if(p.completed || safetyExit(p))continue;
            var carrier=s.blueprint(p.id);
            boolean withdrawn=p.cancelled || carrier!=null && carrier.cancellationQueued();
            if(!withdrawn && paidRoadMaintenance(s,p))continue;
            if(!withdrawn && independent(p))continue;
            // Nonempty ownership, a region owner or a foundation layout is evidence this was a child.
            // An unowned explicit manual work order must never be inferred to be an orphan.
            boolean tracked=!p.dependencies.isEmpty() || p.regionOwner!=null || p.layoutVersion>0 && !p.publicGround;
            if(!withdrawn && (!tracked || hasOwner(s,p,new HashSet<>())))continue;
            changed|=stop(s,p);
            if(carrier!=null)changed|=cancel(s,carrier);
        }
        CURSORS.put(s,cursor+count);
        if(changed)PublicGroundService.requestReview(s);
        return changed;
    }

    private static boolean safetyExit(TerrainProject p){return p.escapeFor!=null || p.escapingCitizen!=null;}

    /** Historical roads may still repair paid cells or return retired paving to soil after losing a parent. */
    private static boolean paidRoadMaintenance(SettlementRecord s,TerrainProject p) {
        if(p.roadReclaim)return true;
        if(!p.road || p.steps.isEmpty())return false;
        for(var route:s.autoRoads().routes.values())if(p.dependencies.contains(route.id)
                && p.steps.stream().allMatch(step->{
                    var at=route.cellInColumn(step.position());if(at==null)return false;
                    int index=route.cellIndex(at);return index>=0 && (index<route.progress || route.completed.get(index) || route.repairs.get(index));
                }))return true;
        return false;
    }

    private static boolean independent(TerrainProject p) {
        return p.areaClearance || p.playerBridge!=null
                || p.dependencies.isEmpty() && p.regionOwner==null && p.explicit && p.layoutVersion==0;
    }

    private static boolean hasOwner(SettlementRecord s,TerrainProject p,Set<UUID> visiting) {
        if(!visiting.add(p.id))return false;
        try {
            for(var id:p.dependencies)if(owner(s,id,visiting))return true;
            return p.regionOwner!=null
                    && com.hkin.exiledsettlement.region.RegionWorkScope.region(s,p.regionOwner)!=null
                    && owner(s,p.regionOwner,visiting);
        } finally {visiting.remove(p.id);}
    }

    private static boolean owner(SettlementRecord s,UUID id,Set<UUID> visiting) {
        if(id==null)return false;
        if(id.equals(s.id()))return true;
        var building=s.building(id);
        if(building!=null)return building.active() && !building.demolitionQueued();
        var site=s.blueprint(id);
        if(site!=null && site.cancellationQueued())return false;
        var project=s.terrain().projects.get(id);
        if(project!=null)return !project.cancelled && (safetyExit(project) || paidRoadMaintenance(s,project)
                || independent(project) || hasOwner(s,project,visiting));
        if(site!=null)return true;
        for(var route:s.autoRoads().routes.values())if(route.id.equals(id)) {
            if(route.suppressed || route.p0Invalidated)return false;
            if(route.manualBridge)return true;
            // An abandoned road may still repair its already paid surface, but cannot authorize
            // the unfinished excavation/fill associated with a cancelled construction request.
            return !Objects.equals(route.building,id) && owner(s,route.building,visiting);
        }
        return false;
    }

    private static boolean stop(SettlementRecord s,TerrainProject p) {
        if(p.completed || safetyExit(p))return false;
        boolean changed=!p.cancelled;p.cancelled=true;
        var carrier=s.blueprint(p.id);if(carrier!=null)changed|=carrier.queueCancellation();
        // Completion can be noncontiguous; preserve every genuine paid fill, not just cursor prefixes.
        var paid=new HashSet<net.minecraft.core.BlockPos>();
        for(int i=0;i<p.steps.size();i++)if(p.steps.get(i).fill()
                && (p.retiredPaid.get(i) || !p.abandoned.get(i) && (i<p.cursor || p.finished.get(i))))
            paid.add(p.steps.get(i).position());
        for(var step:p.steps)if(!paid.contains(step.position()))changed|=s.terrain().cells.remove(step.position(),p.id);
        if(changed)s.terrain().index(p);
        return changed;
    }
}
