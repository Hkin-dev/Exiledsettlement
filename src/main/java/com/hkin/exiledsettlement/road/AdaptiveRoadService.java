package com.hkin.exiledsettlement.road;

import com.hkin.exiledsettlement.citizen.CitizenWorkState;
import com.hkin.exiledsettlement.region.ProductionRegionService;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import java.util.*;

/** Reuse paid access, review old automatic routes, and restore redundant paths through residents. */
public final class AdaptiveRoadService {
    public static final int VERSION=9;
    private record Scan(int size,List<BlockPos> cells,int cursor){ }
    private static final Map<SettlementRecord,Scan> SCANS=new WeakHashMap<>();
    private static final Map<SettlementRecord,Integer> IDENTITY_CURSORS=new WeakHashMap<>();
    private AdaptiveRoadService(){ }
    public static boolean paidPassage(ServerLevel level,SettlementRecord s,BlockPos at){
        var project=s.terrain().projects.get(s.terrain().cells.get(at));
        if(project==null || project.cancelled || project.roadReclaim || TerrainService.pendingAt(s,at))return false;
        var goal=project.template().placementAt(at);
        if(goal==null || goal.state().isAir() || !RoadSurfaceProfile.usable(level,at)
                || !TerrainService.satisfied(level.getBlockState(at),goal.state()))return false;
        return project.accessSurfaces.contains(at) || project.road && automaticAt(s,at);
    }
    public static boolean automaticAt(SettlementRecord s,BlockPos at){
        return s.autoRoads().routes.values().stream().anyMatch(r->!r.suppressed && (r.containsCell(at) || r.retired.contains(at)));
    }
    /** Recorded automatic surfaces may be revised; a shared column retains its common grade. */
    public static boolean editableSurface(ServerLevel level,SettlementRecord s,BlockPos at){
        if(PlayerRoadEdits.retained(level,at) || PlayerRoadEdits.holding(level,at))return false;
        if(BridgeService.owner(s,at)!=null)return false;
        var roads=RoadSpaceData.get(level);
        boolean liveColumn=s.autoRoads().routes.values().stream().filter(r->!r.suppressed)
                .anyMatch(r->r.cellInColumn(at)!=null);
        if(roads.manual(at) || roads.levelAt(at)>1 || !automaticAt(s,at) && !liveColumn)return false;
        if(sharedAt(s,at) && !JointRoadGradePlanner.adjustable(s,at) && s.autoRoads().routes.values().stream().filter(r->!r.suppressed)
                .map(r->r.cellInColumn(at)).filter(Objects::nonNull).anyMatch(p->p.getY()!=at.getY()))return false;
        if(roads.contains(at) && RoadSurfaceProfile.usable(level,at))return true;
        for(var project:s.terrain().projectsAt(at))if((project.road || project.heightSuperseded || completedStairRepair(project)) && !project.roadReclaim){
            var goal=project.template().placementAt(at);if(goal==null || !TerrainService.satisfied(level.getBlockState(at),goal.state()))continue;
            for(int i:project.indices(at))if(project.steps.get(i).fill() && !project.abandoned.get(i) && (i<project.cursor || project.finished.get(i)))return true;
        }
        return false;
    }
    public static boolean fixedPassage(ServerLevel level,SettlementRecord s,BlockPos at){
        return paidPassage(level,s,at) && !editableSurface(level,s,at);
    }
    public static boolean sharedAt(SettlementRecord s,BlockPos at){return s.autoRoads().routes.values().stream().filter(r->!r.suppressed && r.containsCell(at)).limit(2).count()>1;}
    public static boolean walkSurface(ServerLevel level,BlockPos at){
        var state=level.getBlockState(at);
        if(!state.getFluidState().isEmpty() || !(state.is(Blocks.DIRT_PATH) || state.isFaceSturdy(level,at,net.minecraft.core.Direction.UP)
                || state.getBlock() instanceof StairBlock || state.getBlock() instanceof SlabBlock))return false;
        for(int h=1;h<=2;h++){var p=at.above(h);var cover=level.getBlockState(p);
            if(!cover.getFluidState().isEmpty() || !cover.getCollisionShape(level,p).isEmpty())return false;
        }return true;
    }
    private static void importAccess(ServerLevel level,SettlementRecord s){
        if(level.getGameTime()%100==0)reconcileIdentity(level,s);
        var scan=SCANS.get(s);
        if(scan==null || scan.size()!=s.terrain().cells.size()){
            var access=new LinkedHashSet<BlockPos>();
            for(var project:s.terrain().projects.values())if(!project.cancelled){
                access.addAll(project.accessSurfaces);
                if(project.road)for(var step:project.steps)if(!step.target().isAir() && automaticAt(s,step.position()))access.add(step.position());
            }
            scan=new Scan(s.terrain().cells.size(),List.copyOf(access),0);
        }
        int cursor=scan.cursor();var roads=RoadSpaceData.get(level);
        for(int i=0;i<64 && !scan.cells().isEmpty();i++){
            var at=scan.cells().get(cursor++%scan.cells().size());
            if(!level.hasChunkAt(at) || !RoadEarthworkService.permitted(level,s,at))continue;
            if(paidPassage(level,s,at) && walkSurface(level,at) && level.getBlockState(at.above()).isAir()
                    && level.getBlockState(at.above(2)).isAir())roads.addAll(List.of(at));
        }
        SCANS.put(s,new Scan(scan.size(),scan.cells(),scan.cells().isEmpty()?0:cursor%scan.cells().size()));
    }
    /** Remove only stale registrations or known incidental public stairs; never infer a new road from a block type. */
    private static void reconcileIdentity(ServerLevel level,SettlementRecord s){
        var roads=RoadSpaceData.get(level);var positions=roads.decorationPositions();if(positions.isEmpty())return;
        int cursor=IDENTITY_CURSORS.getOrDefault(s,0);
        for(int i=0;i<Math.min(128,positions.size());i++){
            var at=positions.get(cursor++%positions.size());if(!s.contains(at) || !level.hasChunkAt(at) || roads.manual(at) || roads.levelAt(at)>1)continue;
            var state=level.getBlockState(at);boolean incidental=false;
            if(state.getBlock() instanceof StairBlock || state.getBlock() instanceof SlabBlock){
                for(var p:s.terrain().projects.values())if(!p.road && !p.accessSurfaces.contains(at)){
                    var goal=p.template().placementAt(at);if(goal!=null && TerrainService.satisfied(state,goal.state())){incidental=true;break;}
                }
                if(s.terrain().supersededPaid(at,state) && !automaticAt(s,at))incidental=true;
            }
            if(!walkSurface(level,at) || incidental && !automaticAt(s,at))roads.remove(at);
        }
        IDENTITY_CURSORS.put(s,cursor%positions.size());
    }
    public static boolean review(ServerLevel level,SettlementRecord s){
        if(migratePlayerEdits(s))SettlementSavedData.get(level).touch();
        if(coalesceIdenticalRoutes(s)>0)SettlementSavedData.get(level).touch();
        importAccess(level,s);restoreRetired(level,s);
        if(AutoRoadPlanner.busy(s))return false;
        var ground=s.terrain().publicGround;
        if(s.terrain().enabled && (ground.heightVersion!=PublicGroundService.HEIGHT_VERSION
                || PublicGroundService.routePlanningPaused(s)))return false;
        var reviews=new ArrayList<>(s.autoRoads().routes.entrySet());
        // A troublesome route must not starve the rest of the completed network.
        for(int inspected=0;inspected<Math.min(32,reviews.size());inspected++){
            int index=Math.floorMod(s.autoRoads().reviewCursor++,reviews.size());var entry=reviews.get(index);
            var old=entry.getValue();
            if(old.adaptiveVersion<VERSION){old.adaptiveVersion=VERSION;old.reviewAfter=0;old.earthworkCursor=0;old.accessConnected=false;}
            if(old.suppressed || level.getGameTime()<old.reviewAfter)continue;
            if(old.cells.stream().anyMatch(p->!level.hasChunkAt(p)))continue;
            var building=s.building(old.building);
            boolean bridge=BridgeRoadConnections.bridge(s,old.building)!=null;
            if(!bridge && !AutoRoadService.roadEligible(building))continue;
            if(s.terrain().projects.values().stream().anyMatch(p->!p.cancelled && !p.completed && !p.done() && p.dependencies.contains(old.id) && p.roadReclaim))continue;
            var endpoints=bridge?BridgeRoadConnections.entrances(s,old.building):AutoRoadService.entrances(level,s,building);
            var end=reviewEndpoint(s,old,endpoints);
            var root=bridge && end!=null?BridgeRoadConnections.root(level,s,old.building,end):AutoRoadService.coreSurface(level,s);
            if(end==null || root==null)continue;
            old.reviewAfter=level.getGameTime()+1200;
            AutoRoadPlanner.begin(level,s,old.building,root,end,entry.getKey());return true;
        }
        return false;
    }
    /** Follow a moved entrance, without taking another live or cancelled slot. */
    static BlockPos reviewEndpoint(SettlementRecord s,AutoRoadState.Route old,List<BlockPos> endpoints){
        return endpoints.stream().filter(p->s.autoRoads().routes.values().stream().noneMatch(other->
                other!=old && other.building.equals(old.building)
                        && Math.abs(other.entrance.getX()-p.getX())+Math.abs(other.entrance.getZ()-p.getZ())<=1))
                .min(Comparator.comparingDouble(p->p.distSqr(old.entrance))).orElse(null);
    }
    /** Repair impossible old goals against committed ground without admitting
     * new survey or construction positions. Publication keeps its own quota. */
    public static boolean reviewCommittedConflict(ServerLevel level,SettlementRecord s){
        if(AutoRoadPlanner.busy(s) || PublicGroundService.planning(s))return false;
        var entries=new ArrayList<>(s.autoRoads().routes.entrySet());
        for(int i=0;i<Math.min(32,entries.size());i++){
            var entry=entries.get(Math.floorMod(s.autoRoads().reviewCursor++,entries.size()));
            var route=entry.getValue();
            if(route.suppressed || route.manualBridge || AutoRoadService.fixedConstructionGrade(s,route)
                    || level.getGameTime()<route.reviewAfter || !committedConflict(s,route))continue;
            var building=s.building(route.building);
            boolean bridge=BridgeRoadConnections.bridge(s,route.building)!=null;
            if(!bridge && !AutoRoadService.roadEligible(building) || !AutoRoadService.parentValid(level,s,route))continue;
            var root=bridge?BridgeRoadConnections.root(level,s,route.building,route.entrance):AutoRoadService.coreSurface(level,s);if(root==null)continue;
            route.reviewAfter=level.getGameTime()+1200;
            AutoRoadPlanner.beginCommittedRepair(level,s,route.building,root,route.entrance,entry.getKey());
            return true;
        }
        return false;
    }
    public static boolean committedConflict(SettlementRecord s,AutoRoadState.Route route){
        return route.cells.stream().anyMatch(p->{var plane=s.terrain().publicPlane(p);
            return plane!=null && plane.getY()!=p.getY();});
    }
    public static boolean migratePlayerEdits(SettlementRecord s){
        var state=s.autoRoads();if(state.playerEditVersion>=1)return false;
        for(var route:state.routes.values())if(route.suppressed && route.mergedInto==null
                && route.status.equals("玩家移除路面，路线不再重建")){
            route.suppressed=false;route.earthworkCursor=0;route.reviewAfter=0;
            route.status="旧版普通路面改动不再撤销路线，正在复查通行与玩家改良";
            for(var p:route.cells){var owner=s.terrain().projects.get(s.terrain().cells.get(p));
                if(owner!=null && owner.road)s.terrain().suppressed.remove(p.asLong());}
        }
        state.playerEditVersion=1;
        return true;
    }
    /** Deduplicate the same work geometry; different corridors remain real replacement jobs. */
    public static int coalesceIdenticalRoutes(SettlementRecord s){
        var primary=new HashMap<String,List<AutoRoadState.Route>>();int merged=0;
        for(var route:s.autoRoads().routes.values())if(!route.suppressed){
            String key=route.building+":"+route.entrance.asLong();var choices=primary.computeIfAbsent(key,ignored->new ArrayList<>());
            var first=choices.stream().filter(r->r.cells.equals(route.cells) && r.centerline.equals(route.centerline)
                    && r.width==route.width && r.manualBridge==route.manualBridge && r.sourceBridges.equals(route.sourceBridges)).findFirst().orElse(null);
            if(first==null){choices.add(route);continue;}
            // A raw union can contain two grades of the same column and can
            // invalidate bitset indices. Here indices and geometry are identical.
            first.completed.or(route.completed);first.advance();first.accessConnected=false;
            first.constructionGradeFixed|=route.constructionGradeFixed;
            first.retired.addAll(route.retired);first.retired.removeAll(first.cells);
            route.suppressed=true;route.mergedInto=first.id;route.status="已合并相同道路工单，保留真实施工进度";merged++;
            for(var project:s.terrain().projects.values())if(project.dependencies.remove(route.id))project.dependencies.add(first.id);
        }
        return merged;
    }
    public static int roughness(List<BlockPos> line){
        int total=0,last=0;
        for(int i=1;i<line.size();i++){int dy=line.get(i).getY()-line.get(i-1).getY();total+=Math.abs(dy)*2+Math.abs(dy-last);last=dy;}
        return total;
    }
    public static int verticalTravel(List<BlockPos> line){
        int total=0;for(int i=1;i<line.size();i++)total+=Math.abs(line.get(i).getY()-line.get(i-1).getY());return total;
    }
    private static double price(ServerLevel level,SettlementRecord s,List<BlockPos> line,List<BlockPos> cells){
        var roads=RoadSpaceData.get(level);
        return line.size()*.25D+roughness(line)*16D+crossfall(line,cells)*24D+cells.stream().mapToDouble(p->
                (roads.contains(p)?0D:1D)+RoadEarthworkService.effort(level,s,p)*1.5D).sum();
    }
    private static int turns(List<BlockPos> line){
        int total=0,px=0,pz=0;
        for(int i=1;i<line.size();i++){var a=line.get(i-1);var b=line.get(i);int x=Integer.signum(b.getX()-a.getX()),z=Integer.signum(b.getZ()-a.getZ());
            if(i>1&&(x!=px||z!=pz))total++;px=x;pz=z;
        }return total;
    }
    /** Count ordinary transverse offsets separately from longitudinal rise. */
    public static int crossfall(List<BlockPos> line,List<BlockPos> cells){
        var columns=new HashMap<Long,Integer>();
        for(var p:cells)columns.put(BlockPos.asLong(p.getX(),0,p.getZ()),p.getY());
        int total=0;
        for(int i=3;i<line.size()-3;i++){
            var p=line.get(i);var before=line.get(i-1);var after=line.get(i+1);
            boolean x=before.getX()!=p.getX() || after.getX()!=p.getX();
            boolean z=before.getZ()!=p.getZ() || after.getZ()!=p.getZ();
            if(x==z)continue;
            for(int sign:new int[]{-1,1}){
                var side=p.offset(x?0:sign,0,x?sign:0);
                var y=columns.get(BlockPos.asLong(side.getX(),0,side.getZ()));
                if(y!=null)total+=Math.abs(y-p.getY());
            }
        }
        return total;
    }
    public static boolean accept(ServerLevel level,SettlementRecord s,String key,AutoRoadState.Route candidate){
        return accept(level,s,key,candidate,false);
    }
    static boolean accept(ServerLevel level,SettlementRecord s,String key,AutoRoadState.Route candidate,boolean committedRepair){
        if(candidate.cells.stream().anyMatch(p->!RoadEarthworkService.permitted(level,s,p)))return false;
        var old=s.autoRoads().routes.get(key);candidate.adaptiveVersion=VERSION;candidate.reviewAfter=level.getGameTime()+2400;
        if(old!=null){
            if(old.suppressed)return false;
            // Damage and unfinished earthwork do not change the chosen corridor.
            // Keep its paid orders and publication progress; the ordinary road
            // repair poll will reopen physically damaged cells in this route.
            if(!old.p0Invalidated && old.building.equals(candidate.building) && old.entrance.equals(candidate.entrance)
                    && old.cells.size()==candidate.cells.size()
                    && old.cells.stream().allMatch(candidate::containsCell) && old.centerline.equals(candidate.centerline)
                    && old.width==candidate.width && old.manualBridge==candidate.manualBridge
                    && old.sourceBridges.equals(candidate.sourceBridges)){
                old.adaptiveVersion=VERSION;old.reviewAfter=candidate.reviewAfter;
                old.earthworkCursor=Math.min(old.earthworkCursor,old.progress);
                old.status="已复查相同通路，保留施工工单并继续检查真实路面";
                return false;
            }
            if(s.terrain().projects.values().stream().anyMatch(p->!p.cancelled && !p.done() && p.dependencies.contains(old.id) && p.roadReclaim)){old.status="旧路恢复进行中，完成后继续优化";return false;}
            double before=price(level,s,old.centerline,old.cells),after=price(level,s,candidate.centerline,candidate.cells);
            long retired=old.cells.stream().filter(p->!candidate.containsCell(p) && level.hasChunkAt(p) && level.getBlockState(p).is(Blocks.DIRT_PATH)).count();
            after+=retired*.5D;
            boolean changed=!candidate.cells.equals(old.cells);
            boolean entranceCorrection=!candidate.entrance.equals(old.entrance) && !AutoRoadService.parentValid(level,s,old);
            boolean surfaceCorrection=candidate.cells.stream().anyMatch(p->RoadSpaceData.get(level).contains(p)
                    && !RoadEarthworkService.surfaceReady(level,s,candidate,p));
            boolean obstructed=old.cells.stream().anyMatch(p->!RoadEarthworkService.plannable(level,s,p));
            if(!old.p0Invalidated && !entranceCorrection && !obstructed
                    && verticalTravel(candidate.centerline)>verticalTravel(old.centerline)){
                old.status="保留平顺通路：公共高度拟合不能增加非必要升降";return false;
            }
            boolean straighter=candidate.centerline.size()<old.centerline.size() && turns(candidate.centerline)<turns(old.centerline)
                    && verticalTravel(candidate.centerline)<=verticalTravel(old.centerline)
                    && old.cells.stream().noneMatch(p->RoadSpaceData.get(level).manual(p)||PlayerRoadEdits.retained(level,p));
            boolean bridgeCoverage=candidate.cells.stream().filter(p->BridgeService.span(level,s,p)).count()
                    >old.cells.stream().filter(p->BridgeService.span(level,s,p)).count();
            boolean quality=bridgeCoverage || straighter || gradeMismatch(s,old.cells)>gradeMismatch(s,candidate.cells)
                    || crossfall(old.centerline,old.cells)>crossfall(candidate.centerline,candidate.cells)
                    || roughness(old.centerline)>roughness(candidate.centerline)
                    || old.cells.stream().anyMatch(p->level.hasChunkAt(p) && RoadSpaceData.get(level).contains(p) && !RoadEarthworkService.surfaceReady(level,s,old,p));
            if(!old.p0Invalidated && (!changed && !surfaceCorrection && !entranceCorrection || changed && !entranceCorrection && !quality && !obstructed && after+3D>=before*.9D)){old.status="已复查：现有道路无足够安全收益，保留通路";return false;}
        }
        installCandidate(level,s,key,candidate);
        if(s.terrain().enabled && s.terrain().publicGround.phase!=14){
            // A new corridor on the solved plane may publish independently.
            // Keep the current publication cursor; geometry changes still restart safely.
            boolean fitsSolved=old==null && !PublicGroundService.planning(s)
                    && candidate.cells.stream().allMatch(p->Objects.equals(PublicGroundService.planningTargetAt(s,p),p.getY()));
            if(committedRepair || fitsSolved)PublicGroundService.queueReleasedConstraintReview(s);
            else PublicGroundService.requestReview(s);
        }
        return true;
    }
    /** Used only after every member of a joint revision has passed validation. */
    static void installCandidate(ServerLevel level,SettlementRecord s,String key,AutoRoadState.Route candidate){
        installCandidate(level,s,key,candidate,true);
    }
    static void installCandidates(ServerLevel level,SettlementRecord s,Map<String,AutoRoadState.Route> candidates){
        candidates.forEach((key,candidate)->installCandidate(level,s,key,candidate,false));
        // Shared stair targets must see the complete new group, never an old
        // claimant left over halfway through the atomic replacement.
        candidates.values().forEach(candidate->countActualCompletion(level,s,candidate));
    }
    private static void installCandidate(ServerLevel level,SettlementRecord s,String key,AutoRoadState.Route candidate,boolean countReady){
        var old=s.autoRoads().routes.get(key);
        candidate.adaptiveVersion=VERSION;candidate.reviewAfter=level.getGameTime()+2400;
        if(old!=null){
            candidate.id=old.id;candidate.retired.addAll(old.retired);
            var liveColumns=new HashSet<Long>();for(var p:candidate.cells)liveColumns.add(BlockPos.asLong(p.getX(),0,p.getZ()));
            // A grade change in the same column belongs to its road earthwork, not later underground reclamation.
            for(var p:List.copyOf(candidate.retired))if(liveColumns.contains(BlockPos.asLong(p.getX(),0,p.getZ())))retireReusedRecovery(s,p);
            candidate.retired.removeIf(p->liveColumns.contains(BlockPos.asLong(p.getX(),0,p.getZ())));
            for(var p:old.cells)if(!candidate.containsCell(p) && !liveColumns.contains(BlockPos.asLong(p.getX(),0,p.getZ())))candidate.retired.add(p);
            candidate.retired.removeAll(candidate.cells);
            // Unclaimed stale orders no longer freeze an obsolete corridor.
            // Actual blocks survive cancellation and enter the later paid restoration pass.
            for(var project:s.terrain().projects.values())if(project.road && !project.bridge && !project.roadReclaim && !project.done() && project.dependencies.remove(old.id)){
                boolean sharedParent=s.autoRoads().routes.values().stream().anyMatch(r->!r.id.equals(old.id)
                        && r.building.equals(old.building) && project.dependencies.contains(r.id));
                if(!sharedParent)project.dependencies.remove(old.building);
                if(project.dependencies.isEmpty()){
                    project.cancelled=true;project.heightSuperseded=true;s.terrain().index(project);var site=s.blueprint(project.id);if(site!=null)site.queueCancellation();
                }
            }
            candidate.status="已优化路线与标高；先接通新路，再由居民恢复多余旧路";
        }
        s.autoRoads().routes.put(key,candidate);
        if(countReady)countActualCompletion(level,s,candidate);
    }
    private static void countActualCompletion(ServerLevel level,SettlementRecord s,AutoRoadState.Route candidate){
        candidate.completed.clear();
        for(int i=0;i<candidate.cells.size();i++)if(AutoRoadService.laidCell(level,s,candidate,candidate.cells.get(i)))candidate.completed.set(i);
        candidate.advance();
    }
    public static int gradeMismatch(SettlementRecord s,List<BlockPos> cells){
        int total=0;for(var p:cells){var target=PublicGroundService.planningTargetAt(s,p);if(target!=null)total+=Math.abs(target-p.getY());}return total;
    }
    private static boolean completedStairRepair(TerrainProject p){
        return !p.explicit && p.name.equals("低位台阶通行修正") && (p.completed || p.done());
    }
    /** Finished or cancelled public reservations may be replaced; active work remains authoritative. */
    public static void releaseCompletedRoadTargets(SettlementRecord s,List<TerrainProject.Step> steps){
        for(var step:steps){var owner=s.terrain().projects.get(s.terrain().cells.get(step.position()));
            // A completed restoration is paid soil, not a permanent reservation against a later road.
            // In-progress reclamation still owns its target and must finish before it can be reused.
            if(owner!=null && (owner.road || owner.publicGround || completedStairRepair(owner)) && (owner.done() || owner.cancelled)){
                if(completedStairRepair(owner) && !owner.heightSuperseded){owner.heightSuperseded=true;s.terrain().index(owner);}
                s.terrain().cells.remove(step.position(),owner.id);
            }
        }
    }
    private static void restoreRetired(ServerLevel level,SettlementRecord s){
        var roads=RoadSpaceData.get(level);
        var capacity=OptionalWorkBudget.snapshot(s);
        for(var route:s.autoRoads().routes.values()){
            // A later revision may reuse previously retired cells. Never reclaim its own live corridor.
            if(route.suppressed || route.retired.isEmpty())continue;
            boolean connected=route.accessConnected && route.progress>=route.cells.size()
                    && route.cells.stream().allMatch(p->level.hasChunkAt(p) && RoadEarthworkService.surfaceReady(level,s,route,p) && roads.contains(p));
            int budget=8;
            for(var at:List.copyOf(route.retired)){
                if(budget--<=0)break;
                // Rotate attempted cells, including blocked ones, so the first eight
                // protected/unloaded columns cannot starve every later old road.
                route.retired.remove(at);route.retired.add(at);
                SettlementSavedData.get(level).touch();
                boolean shared=s.autoRoads().routes.values().stream().anyMatch(r->!r.suppressed && r.cellInColumn(at)!=null);
                if(shared){retireReusedRecovery(s,at);route.retired.remove(at);continue;}
                if(!connected || !level.hasChunkAt(at) || TerrainService.pendingAt(s,at))continue;
                if(roads.manual(at) || roads.levelAt(at)>1 || PlayerRoadEdits.retained(level,at) || fixedPassage(level,s,at)) {route.retired.remove(at);continue;}
                var state=level.getBlockState(at);
                if(!state.is(Blocks.DIRT_PATH) && (state.is(net.minecraft.tags.BlockTags.DIRT)
                        || com.hkin.exiledsettlement.blueprint.FarmEarthworkPlan.soil(state)
                        || !(RoadSurfaceProfile.usable(level,at) && editableSurface(level,s,at)))){roads.remove(at);route.retired.remove(at);continue;}
                if(!RoadEarthworkService.permitted(level,s,at))continue;
                var steps=List.of(new TerrainProject.Step(at,Blocks.AIR.defaultBlockState(),state),
                        new TerrainProject.Step(at,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()));
                if(!capacity.fits(steps,null)){route.status=capacity.status();return;}
                releaseCompletedRoadTargets(s,steps);
                var project=ProductionRegionService.publish(level,s,route.id,"旧自动路恢复地面",at,steps,true);
                if(project!=null){project.road=true;project.roadReclaim=true;project.explicit=false;s.terrain().index(project);capacity.reserve(steps,null);}
            }
        }
    }
    private static void retireReusedRecovery(SettlementRecord s,BlockPos at){
        for(var p:List.copyOf(s.terrain().projectsAt(at)))if(p.roadReclaim && !p.cancelled && !p.done()
                && p.steps.stream().allMatch(step->step.position().getX()==at.getX() && step.position().getZ()==at.getZ())){
            // Release the obsolete order only. Paid blocks, items and progress survive.
            p.cancelled=true;p.heightSuperseded=true;s.terrain().index(p);
            var site=s.blueprint(p.id);if(site!=null)site.queueCancellation();
        }
    }
    /** Revalidate after publication: a newer route may already reuse this column. */
    public static String reclaimGuard(ServerLevel level,SettlementRecord s,TerrainProject project,BlockPos at){
        if(!project.roadReclaim)return "";
        boolean owner=s.autoRoads().routes.values().stream().anyMatch(r->!r.suppressed
                && project.dependencies.contains(r.id) && r.retired.contains(at));
        if(!owner || s.autoRoads().routes.values().stream().anyMatch(r->!r.suppressed && r.cellInColumn(at)!=null))return "CONSTRUCTION_TARGET_CHANGED";
        var roads=RoadSpaceData.get(level);
        if(roads.manualHeight(at)!=null || roads.levelAt(at)>1 || PlayerRoadEdits.retained(level,at)
                || PlayerRoadEdits.holding(level,at) || BridgeService.column(s,at))return "PROTECTED_AREA";
        return "";
    }
}
