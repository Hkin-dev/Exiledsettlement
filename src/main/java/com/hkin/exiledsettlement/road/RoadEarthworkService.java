package com.hkin.exiledsettlement.road;

import com.hkin.exiledsettlement.blueprint.FarmEarthworkPlan;
import com.hkin.exiledsettlement.region.ProductionRegionService;
import com.hkin.exiledsettlement.citizen.ResidentTreeFelling;
import com.hkin.exiledsettlement.citizen.TreeSurvey;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import com.hkin.exiledsettlement.blueprint.BlueprintService;
import java.util.*;

/** Roads freeze paid preparation orders only after the route search chooses a corridor. */
public final class RoadEarthworkService {
    private RoadEarthworkService() { }
    public static final int ROAD_CLEARANCE_HEIGHT=4;
    private static final ThreadLocal<SettlementRecord> NECESSARY_SEARCH=new ThreadLocal<>();
    /** Only mathematical road selection can revise an adjustable public plane.
     * Publication and resident actions remain outside this scope and use committed shared goals. */
    public static final class PlanningScope implements AutoCloseable {
        private final SettlementRecord previous;
        private PlanningScope(SettlementRecord s,boolean necessary){previous=NECESSARY_SEARCH.get();if(necessary)NECESSARY_SEARCH.set(s);else NECESSARY_SEARCH.remove();}
        @Override public void close(){if(previous==null)NECESSARY_SEARCH.remove();else NECESSARY_SEARCH.set(previous);}
    }
    public static PlanningScope planningScope(SettlementRecord s,boolean necessary){return new PlanningScope(s,necessary);}
    /** Structural beams cannot be road-clearing trees. Match the actual exclusion used by road planning. */
    public static boolean reservedStructure(SettlementRecord s,BlockPos p) {
        return BridgeService.owner(s,p)!=null
                || s.buildings().stream().anyMatch(b->b.active() && b.containsColumn(p))
                || s.formalBlueprints().stream().anyMatch(b->!b.cancellationQueued() && b.containsColumn(p));
    }
    public static boolean permitted(ServerLevel level,SettlementRecord s,BlockPos p) {
        if(InstantTerrainService.active(s)){
            if(p.getY()<=level.getMinBuildHeight() || p.getY()+ROAD_CLEARANCE_HEIGHT>=level.getMaxBuildHeight()
                    || !level.hasChunkAt(p) || !s.contains(p) || InstantTerrainService.reserved(s,p))return false;
            // Fresh branches share one physical elevation. These are this invocation's new routes.
            for(var route:s.autoRoads().routes.values())if(!route.suppressed && !route.p0Invalidated){
                  var shared=route.cellInColumn(p);if(shared!=null && shared.getY()!=p.getY()
                          && !JointRoadGradePlanner.adjustable(s,p)
                          && (s.terrain().publicGround.phase!=14 || AdaptiveRoadService.sharedAt(s,shared)))return false;
            }
            return true;
        }
        if(PlayerRoadEdits.holding(level,p))return false;
        var playerSurface=RoadSpaceData.get(level).playerSurface(p);
        if(playerSurface!=null && PlayerRoadEdits.retained(level,playerSurface) && !playerSurface.equals(p))return false;
        var checkpoint=PublicGroundService.checkpointAt(s,p);
        if(checkpoint!=null && checkpoint!=p.getY() && !PlayerRoadEdits.retained(level,p))return false;
        var publicPlane=s.terrain().publicPlane(p);var sharedGoal=PublicGroundService.targetAt(s,p);
        if(NECESSARY_SEARCH.get()!=s && s.terrain().publicGround.phase!=14 && publicPlane!=null && publicPlane.getY()!=p.getY() && (sharedGoal==null || sharedGoal!=p.getY()) && !PlayerRoadEdits.retained(level,p))return false;
        var manualY=RoadSpaceData.get(level).manualHeight(p);if(manualY!=null && manualY!=p.getY())return false;
        var existingY=RoadSpaceData.get(level).columnHeight(p);
        if(existingY!=null && existingY!=p.getY()){
            var existing=p.atY(existingY);
            if(AdaptiveRoadService.walkSurface(level,existing) && (RoadSpaceData.get(level).levelAt(existing)>1
                    || AdaptiveRoadService.sharedAt(s,existing) && !JointRoadGradePlanner.adjustable(s,existing)
                    || AdaptiveRoadService.fixedPassage(level,s,existing)))return false;
        }
        if(!s.contains(p) || !s.contains(p.above(ROAD_CLEARANCE_HEIGHT)) || !level.hasChunkAt(p)
                || level.isOutsideBuildHeight(p) || level.isOutsideBuildHeight(p.above(ROAD_CLEARANCE_HEIGHT))
                || s.decorations().forbidden(p) || s.community().danger(p) || s.terrain().suppressed.contains(p.asLong()))return false;
        for(var b:s.buildings())if(b.active() && b.containsColumn(p))return false;
        for(var b:s.formalBlueprints())if(!b.cancellationQueued()) {
            if(b.containsColumn(p))return false;
        }
        return true;
    }
    public static boolean plannable(ServerLevel level,SettlementRecord s,BlockPos p) {
        if(!permitted(level,s,p))return false;
        if(InstantTerrainService.active(s))return true;
        var bridge=BridgeService.owner(s,p);
        if(bridge!=null)return bridge.accessSurfaces.contains(p);
        boolean bridgeApproach=BridgeRoadConnections.approachGap(level,s,p);
        if(BridgeService.waterSpan(level,p) && !bridgeApproach)return false;
        if(AdaptiveRoadService.paidPassage(level,s,p) && AdaptiveRoadService.walkSurface(level,p)){
            for(int h=1;h<=ROAD_CLEARANCE_HEIGHT;h++){var cover=level.getBlockState(p.above(h));if(!cover.isAir() && !TerrainService.soft(cover) && !ResidentTreeFelling.plannable(level,s,p.above(h)) && !ownedPublicObstacle(level,s,p.above(h)))return false;}
            return true;
        }
        for(int y=0;y<=clearanceHeight(s,p);y++) {
            var state=level.getBlockState(p.above(y));
            if(TerrainService.replaceableWater(state))return false;
            if(!state.getFluidState().isEmpty() || !TerrainService.natural(state) && !ResidentTreeFelling.plannable(level,s,p.above(y)) && !state.is(Blocks.DIRT_PATH)
                    && !AdaptiveRoadService.editableSurface(level,s,p.above(y))
                    && !s.terrain().supersededPaid(p.above(y),state) && !ownedPublicObstacle(level,s,p.above(y)))return false;
        }
        int bottom=p.getY()-1;
        while(bottom>=level.getMinBuildHeight() && (!com.hkin.exiledsettlement.terrain.TerrainSurface.ground(level,p.atY(bottom)) || TreeSurvey.candidate(level.getBlockState(p.atY(bottom))))) {
            var state=level.getBlockState(p.atY(bottom));
            if(TerrainService.replaceableWater(state) && !bridgeApproach)return false;
            if(!state.getFluidState().isEmpty() && !(bridgeApproach && TerrainService.replaceableWater(state)) || !TerrainService.natural(state) && !TerrainService.replaceableWater(state) && !ResidentTreeFelling.plannable(level,s,p.atY(bottom))
                    && !AdaptiveRoadService.editableSurface(level,s,p.atY(bottom)))return false;
            bottom--;
        }
        return bottom>=level.getMinBuildHeight();
    }
    public static double effort(ServerLevel level,SettlementRecord s,BlockPos p) {
        if(BridgeService.span(level,s,p))return BridgeService.ready(level,s,p)?0:6;
        var target=PublicGroundService.planningTargetAt(s,p);double cost=target==null?0:Math.abs(target-p.getY())*48D;
        if(AutoRoadService.safeCell(level,s,p))return cost;
        if(FarmEarthworkPlan.soil(level.getBlockState(p)) || level.getBlockState(p).is(Blocks.DIRT_PATH)){
            boolean open=true;
            for(int h=1;h<=ROAD_CLEARANCE_HEIGHT;h++)if(!level.getBlockState(p.above(h)).isAir() && !ResidentTreeFelling.plannable(level,s,p.above(h)))open=false;
            if(open)return cost; // Evaluate the same actual soil after the planned trees are removed.
        }
        for(int h=1;h<=ROAD_CLEARANCE_HEIGHT;h++){var state=level.getBlockState(p.above(h));if(!state.isAir() && !ResidentTreeFelling.plannable(level,s,p.above(h)))cost+=TerrainService.soft(state)?.25:1+Math.max(0,state.getDestroySpeed(level,p.above(h)));}
        var ground=level.getBlockState(p);if(!FarmEarthworkPlan.soil(ground) && !ground.is(Blocks.DIRT_PATH))cost+=ground.isAir()?1:2+Math.max(0,ground.getDestroySpeed(level,p));
        int bottom=p.getY()-1;while(bottom>=level.getMinBuildHeight() && (!com.hkin.exiledsettlement.terrain.TerrainSurface.ground(level,p.atY(bottom)) || TreeSurvey.candidate(level.getBlockState(p.atY(bottom))))){cost++;bottom--;}
        return cost;
    }
    /** A recorded paid public barrier may be removed for a road; unowned/player replacements may not. */
    public static boolean ownedPublicObstacle(ServerLevel level,SettlementRecord s,BlockPos at){
        if(!level.hasChunkAt(at) || s.terrain().suppressed.contains(at.asLong()) || PlayerRoadEdits.retained(level,at)
                || PlayerRoadEdits.holding(level,at) || BridgeService.column(s,at))return false;
        var state=level.getBlockState(at);
        return TerrainSurface.barrier(state) && state.getFluidState().isEmpty()
                && (s.terrain().registeredPublicPaid(at,state)||s.terrain().supersededPaid(at,state));
    }
    /** Recorded obsolete caps and their paid bases above a new road plane, including above headroom. */
    private static List<TerrainProject.Step> obsoleteClears(ServerLevel level,SettlementRecord s,BlockPos p){
        var steps=new ArrayList<TerrainProject.Step>();var roads=RoadSpaceData.get(level);
        for(var at:s.terrain().supersededColumn(p))if(at.getY()>p.getY()
                && level.hasChunkAt(at) && !roads.manual(at) && roads.levelAt(at)<=1
                && !PublicGroundService.fixedRoad(level,s,at)){
            var state=level.getBlockState(at);
            if(!state.isAir() && !state.hasBlockEntity() && state.getFluidState().isEmpty() && s.terrain().supersededPaid(at,state))
                steps.add(new TerrainProject.Step(at,Blocks.AIR.defaultBlockState(),state));
        }
        steps.sort(Comparator.comparingInt((TerrainProject.Step x)->x.position().getY()).reversed());return steps;
    }
    public static BlockState surfaceTarget(ServerLevel level,SettlementRecord s,AutoRoadState.Route route,BlockPos p){
        var bridge=BridgeService.target(s,p);if(bridge!=null)return bridge;
        if(PlayerRoadEdits.retained(level,p))return null;
        // Public workers own the fill and any stair orientation. Roads wait, then reuse it.
        if(AdaptiveRoadService.fixedPassage(level,s,p) || RoadSpaceData.get(level).manual(p) || RoadSpaceData.get(level).levelAt(p)>1)return null;
        // One physical shared surface cannot keep a different stair goal for
        // each claimant. Flat shoulders reuse the stair needed by a live
        // centreline at this same grade; conflicting directions stay blocked.
        BlockState sharedStair=null;
        for(var claimant:s.autoRoads().routes.values())if(!claimant.suppressed && claimant.containsCell(p)){
            var candidate=routeSurfaceTarget(level,claimant,p);
            if(candidate.getBlock() instanceof StairBlock){
                if(sharedStair!=null && sharedStair.getValue(StairBlock.FACING)!=candidate.getValue(StairBlock.FACING))return null;
                sharedStair=candidate;
            }
        }
        return sharedStair!=null?sharedStair:routeSurfaceTarget(level,route,p);
    }
    private static BlockState routeSurfaceTarget(ServerLevel level,AutoRoadState.Route route,BlockPos p){
        for(int i=1;i<route.centerline.size();i++){
            var a=route.centerline.get(i-1);var b=route.centerline.get(i);if(Math.abs(a.getY()-b.getY())!=1)continue;
            var low=a.getY()<b.getY()?a:b;var high=low==a?b:a;
            boolean alongX=low.getX()!=high.getX();
            if(p.getY()!=high.getY() || (alongX?p.getX()!=high.getX() || Math.abs(p.getZ()-high.getZ())>1:p.getZ()!=high.getZ() || Math.abs(p.getX()-high.getX())>1))continue;
            var facing=Direction.fromDelta(high.getX()-low.getX(),0,high.getZ()-low.getZ());
            if(facing!=null && route.containsCell(p.relative(facing.getOpposite()).below())){
                var actual=level.getBlockState(p);
                var stair=actual.getBlock() instanceof StairBlock?actual:Blocks.COBBLESTONE_STAIRS.defaultBlockState();
                return stair.setValue(StairBlock.FACING,facing).setValue(StairBlock.HALF,net.minecraft.world.level.block.state.properties.Half.BOTTOM);
            }
        }
        return Blocks.DIRT.defaultBlockState();
    }
    /** Registration alone is insufficient when a stair must be turned or removed. */
    public static boolean surfaceReady(ServerLevel level,SettlementRecord s,AutoRoadState.Route route,BlockPos p){
        if(BridgeService.owner(s,p)!=null)return BridgeService.ready(level,s,p) && RoadSurfaceProfile.connected(level,route,p);
        if(!AutoRoadService.safeCell(level,s,p))return false;
        if(!obsoleteClears(level,s,p).isEmpty())return false;
        var desired=surfaceTarget(level,s,route,p);
        if(desired==null)return RoadSurfaceProfile.connected(level,route,p);
        var actual=level.getBlockState(p);
        if(!RoadSurfaceProfile.connected(level,route,p))return false;
        return geometryMatches(level,route,p,actual,desired);
    }
    private static boolean geometryMatches(ServerLevel level,AutoRoadState.Route route,BlockPos p,BlockState actual,BlockState desired){
        if(desired.getBlock() instanceof StairBlock)return RoadSurfaceProfile.equivalent(level,p,actual,desired)
                || actual.getBlock() instanceof SlabBlock && RoadSurfaceProfile.connected(level,route,p);
        return !(actual.getBlock() instanceof StairBlock)
                && (!(actual.getBlock() instanceof SlabBlock) || RoadSurfaceProfile.connected(level,route,p));
    }
    /** A real work strip can open before cosmetic paving/side widening closes.
     * Bridges themselves still need their complete three-wide deck and guards. */
    public static String accessBlocker(ServerLevel level,SettlementRecord s,AutoRoadState.Route route) {
        for(var id:route.sourceBridges){var bridge=s.terrain().projects.get(id);
            if(bridge==null || !bridge.completed || bridge.accessSurfaces.stream().anyMatch(p->!BridgeService.ready(level,s,p)))return "共用主桥未连通 @ "+id;}
        BlockPos previous=null;
        for(var at:route.centerline) {
            if(!clearanceReady(level,s,at))return "道路四格净空未完成 @ "+at.toShortString();
            var bridge=BridgeService.owner(s,at);
            if(bridge!=null && !bridge.completed || !AutoRoadService.safeCell(level,s,at) || !RoadSurfaceProfile.usable(level,at))return "工作带地面或桥梁未完成 @ "+at.toShortString();
            var desired=surfaceTarget(level,s,route,at);
            if(desired!=null && desired.getBlock() instanceof StairBlock && !RoadSurfaceProfile.equivalent(level,at,level.getBlockState(at),desired))return "工作带台阶方向或形状不符 @ "+at.toShortString();
            if(previous!=null && !workStripJoins(level,s,route,previous,at))return "工作带落脚面不连续 @ "+at.toShortString();
            previous=at;
        }
        return previous==null?"工作带尚未规划":RoadEntranceService.blocker(level,s,route);
    }
    /** Four clear block cells over each road surface include ascent headroom. */
    public static int clearanceHeight(SettlementRecord s,BlockPos at){
        return ROAD_CLEARANCE_HEIGHT;
    }
    public static boolean clearanceReady(ServerLevel level,SettlementRecord s,BlockPos at){
        for(int h=1;h<=clearanceHeight(s,at);h++){var p=at.above(h);if(!level.hasChunkAt(p))return false;
            var cover=level.getBlockState(p);if(!cover.getFluidState().isEmpty() || !cover.getCollisionShape(level,p).isEmpty())return false;}
        return true;
    }
    /** Rounded centerlines may turn diagonally, but residents still need a real orthogonal landing. */
    public static boolean workStripJoins(ServerLevel level,SettlementRecord s,AutoRoadState.Route route,BlockPos a,BlockPos b){
        int dx=b.getX()-a.getX(),dz=b.getZ()-a.getZ();
        var direction=Direction.fromDelta(dx,0,dz);
        if(direction!=null)return RoadSurfaceProfile.joins(level,a,level.getBlockState(a),b,level.getBlockState(b),direction);
        if(Math.abs(dx)!=1 || Math.abs(dz)!=1 || Math.abs(a.getY()-b.getY())>1)return false;
        for(int y=Math.min(a.getY(),b.getY());y<=Math.max(a.getY(),b.getY());y++)
            for(var mid:List.of(new BlockPos(b.getX(),y,a.getZ()),new BlockPos(a.getX(),y,b.getZ()))){
                if(!route.containsCell(mid) || !surfaceReady(level,s,route,mid))continue;
                var first=Direction.fromDelta(mid.getX()-a.getX(),0,mid.getZ()-a.getZ());
                var second=Direction.fromDelta(b.getX()-mid.getX(),0,b.getZ()-mid.getZ());
                if(RoadSurfaceProfile.joins(level,a,level.getBlockState(a),mid,level.getBlockState(mid),first)
                        && RoadSurfaceProfile.joins(level,mid,level.getBlockState(mid),b,level.getBlockState(b),second))return true;
            }
        return false;
    }
    public static void tickLevel(ServerLevel level) {
        var data=SettlementSavedData.get(level);
        for(var s:data.settlements()) {
            // Old one-column road orders may retain only a former parent ID after a replan.
            // Drop their intent when every target now belongs to a formal footprint; keep all paid blocks.
            if(level.getGameTime()%100==0)for(var project:s.terrain().projects.values())
                if(project.road && !project.entranceAccess && !project.bridge && !project.roadReclaim && !project.cancelled && !project.done()
                        && project.steps.stream().allMatch(step->s.buildings().stream().anyMatch(b->b.active() && b.contains(step.position()))
                            || s.blueprints().stream().anyMatch(b->b.terrainProject()==null && !b.cancellationQueued() && b.containsColumn(step.position())))){
                    project.cancelled=true;project.heightSuperseded=true;s.terrain().index(project);
                    var site=s.blueprint(project.id);if(site!=null)site.queueCancellation();data.touch();
                }
            for(var route:s.autoRoads().routes.values())if(route.suppressed)for(var project:s.terrain().dependingOn(route.id))if(project.road && project.dependencies.remove(route.id) && project.dependencies.isEmpty()) {
                project.cancelled=true;var site=s.blueprint(project.id);if(site!=null)site.queueCancellation();
            }
            for(var route:s.autoRoads().routes.values())if(route.suppressed)for(var project:s.terrain().dependingOn(route.building))if(project.road && project.dependencies.contains(route.building)
                    && s.autoRoads().routes.values().stream().noneMatch(r->!r.suppressed && r.building.equals(route.building) && project.dependencies.contains(r.id))) {
                project.dependencies.remove(route.building);if(project.dependencies.isEmpty()){project.cancelled=true;var site=s.blueprint(project.id);if(site!=null)site.queueCancellation();}
            }
            if(!AutoRoadService.allowed(s))continue;
            for(var route:s.autoRoads().routes.values())if(!route.suppressed && com.hkin.exiledsettlement.util.TickCadence.due(level.getGameTime(),route.id,20,11)) {
                route.accessConnected=accessBlocker(level,s,route).isEmpty();
                if(!AutoRoadService.required(s,route))continue;
                // Natural ground may already carry a safe work strip, but it is
                // not paid paving. Mixing those states makes the 100-tick paving
                // review rewind long preparation scans forever.
                for(int i=0;i<route.cells.size();i++)if(AutoRoadService.laidCell(level,s,route,route.cells.get(i)))route.completed.set(i);else {
                    if(route.completed.get(i) && !RoadSurfaceProfile.usable(level,route.cells.get(i))){route.repairs.set(i);route.admitted.set(i);route.earthworkCursor=Math.min(route.earthworkCursor,i);}
                    route.completed.clear(i);
                }
                route.progress=0;route.advance();
            }
            for(var route:s.autoRoads().routes.values())if(!route.suppressed && com.hkin.exiledsettlement.util.TickCadence.due(level.getGameTime(),route.id,100,13)){
                for(int i=0;i<route.cells.size();i++)if(level.hasChunkAt(route.cells.get(i))){
                    var at=route.cells.get(i);boolean paid=AutoRoadService.laidCell(level,s,route,at);
                    if(paid){route.completed.set(i);
                        if(clearanceReady(level,s,at))route.repairs.clear(i);
                        else {route.repairs.set(i);route.earthworkCursor=Math.min(route.earthworkCursor,i);}
                    }else {
                        boolean existing=route.completed.get(i) || route.repairs.get(i);
                        if(existing && !RoadSurfaceProfile.usable(level,at)){route.repairs.set(i);route.admitted.set(i);}
                        route.completed.clear(i);
                        if(existing){route.earthworkCursor=Math.min(route.earthworkCursor,i);route.reviewAfter=0;}
                    }
                }
                route.advance();
            }
            int budget=8;
            OptionalWorkBudget capacity=null;
            if(s.autoRoads().routes.values().stream().anyMatch(r->!r.suppressed && !r.p0Invalidated && !r.repairs.isEmpty())){
                capacity=OptionalWorkBudget.snapshot(s);
                budget-=RoadMaintenanceService.publish(level,s,capacity,4);
            }
            for(var route:s.autoRoads().routes.values())
                if(!route.suppressed && com.hkin.exiledsettlement.util.TickCadence.due(level.getGameTime(),route.id,20,3) && AutoRoadService.parentValid(level,s,route)) {
                    if(capacity==null)capacity=OptionalWorkBudget.snapshot(s);
                    RoadEntranceService.prepare(level,s,route,capacity);
                }
            if(level.getGameTime()%100==0)for(var route:s.autoRoads().routes.values())if(!route.suppressed && route.progress<route.cells.size() && route.earthworkCursor>=route.cells.size())route.earthworkCursor=route.progress;
            var jobs=new ArrayList<>(s.autoRoads().routes.values());
            jobs.sort(Comparator.comparingInt(r->AutoRoadService.required(s,r)?0:1));
            jobs.removeIf(r->!AutoRoadService.earthworkPlanningReady(s,r));
            var required=jobs.stream().filter(r->AutoRoadService.required(s,r) && !r.suppressed && r.earthworkCursor<r.cells.size()).toList();
            // Maintenance already reserves up to four cells above. A damaged
            // route waiting for an unfinished bridge must not monopolize the
            // remaining publication budget and starve every new approach.
            if(!required.isEmpty())jobs=new ArrayList<>(required);
            if(!jobs.isEmpty())Collections.rotate(jobs,-Math.floorMod(s.autoRoads().earthworkRouteCursor++,jobs.size()));
            for(var route:jobs)if(!route.suppressed && route.earthworkCursor<route.cells.size()) {
                if(!AutoRoadService.routeAllowed(s,route) || !AutoRoadService.parentValid(level,s,route))continue;
                if(capacity==null)capacity=OptionalWorkBudget.snapshot(s);
                BridgeService.linkSources(s,route);
                while(budget-->0 && route.earthworkCursor<route.cells.size()) {
                    var p=route.cells.get(route.earthworkCursor);
                    var crossing=BridgeService.owner(s,p);
                    if(crossing!=null){if(!crossing.completed)break;route.earthworkCursor++;continue;}
                    // A local bridge conflict must not stall already reachable
                    // land preparation, nor let it turn water into a soil road.
                    if(BridgeService.waterSpan(level,p)&&!BridgeRoadConnections.approachGap(level,s,p)){route.status="跨河需要玩家在建筑菜单规划桥梁";route.earthworkCursor++;continue;}
                    if(!level.hasChunkAt(p)){route.status="等待道路地形正常加载："+p.toShortString();break;}
                    if(!plannable(level,s,p)){route.status="道路局部准备被阻挡："+p.toShortString();route.reviewAfter=Math.min(route.reviewAfter,level.getGameTime()+100);route.earthworkCursor++;continue;}
                    if(!PublicGroundService.publishRoadColumn(level,s,route,p,capacity)){
                        if(capacity.denied())break;
                        route.status="等待公共地面与通路联合工单或局部保护条件";
                    }
                    route.earthworkCursor++;
                }

                data.touch();if(budget<=0)break;
            }
        }
    }
}
