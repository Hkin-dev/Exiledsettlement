package com.hkin.exiledsettlement.road;

import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.decoration.DecorationService;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;

import java.util.*;

/** Bounded planning never changes terrain. Only an on-site resident may lay a cell. */
public final class AutoRoadService {
    public static final int SEARCH_NODES = 2048, CELL_WORK_TICKS = 24;
    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final Map<CitizenRecord, Integer> WORK = new WeakHashMap<>();
    private static final Map<CitizenRecord, Long> TIMED_WORK = new WeakHashMap<>();
    private static final Map<ServerLevel,Map<BlockPos,Long>> DEFERRED=new WeakHashMap<>();
    private static final Map<ServerLevel, Map<BlockPos, BlockState>> REMOVALS = new WeakHashMap<>();
    private AutoRoadService() { }
    public static boolean reviewDue(long tick,long dayTime){
        return tick%100==0 || Math.floorMod(dayTime,24000L)>=13000 && tick%25==0;
    }

    public static void tickLevel(ServerLevel level) {
        var data = SettlementSavedData.get(level);
        for (var s : data.settlements()) {
            if (!s.active() || !level.hasChunkAt(s.corePosition())) continue;
            if (!s.autoRoads().enabled && !hasPending(s) && s.autoRoads().budgetDay == level.getDayTime() / 24000L) continue;
            tick(level, s); data.touch();
        }
    }

    public static boolean allowed(SettlementRecord s) {
        return s.active() && (s.autoRoads().enabled && !s.terrain().publicPaused || hasPending(s)) && s.autoRoads().workReady
                && !s.community().evacuation;
    }

    private static boolean pending(com.hkin.exiledsettlement.blueprint.BlueprintRecord b){return b.terrainProject()==null && b.region()==null && !b.cancellationQueued()
            && (!b.type().isDynamicArea() || b.productionSite()!=null || BlueprintTemplates.fixedProduction(b.type(),b.templateVersion()));}
    public static boolean needsAccess(SettlementRecord s,com.hkin.exiledsettlement.blueprint.BlueprintRecord b){return pending(b) && !DecorationService.owned(s,b.id());}
    public static boolean hasPending(SettlementRecord s){return s.blueprints().stream().anyMatch(b->needsAccess(s,b)) || s.autoRoads().routes.values().stream().anyMatch(r->r.manualBridge && !r.suppressed && r.progress<r.cells.size());}
    public static boolean required(SettlementRecord s,AutoRoadState.Route r){var b=s.blueprint(r.building);return r.manualBridge || b!=null && pending(b);}
    /** Necessary access is a P0 mother dependency, not an optional village-wide grading prerequisite. */
    public static boolean necessaryPlan(SettlementRecord s,UUID parent){
        var site=s.blueprint(parent);return site!=null && needsAccess(s,site);
    }
    public static boolean fixedConstructionGrade(SettlementRecord s,AutoRoadState.Route r){
        return r.constructionGradeFixed && !r.suppressed && necessaryPlan(s,r.building);
    }
    public static BlockPos fixedConstructionSurface(SettlementRecord s,BlockPos p){
        for(var r:s.autoRoads().routes.values())if(fixedConstructionGrade(s,r)){
            var cell=r.cellInColumn(p);if(cell!=null)return cell;
        }
        return null;
    }
    public static boolean earthworkPlanningReady(SettlementRecord s,AutoRoadState.Route r){
        return !com.hkin.exiledsettlement.terrain.PublicGroundService.planning(s) || r.manualBridge || fixedConstructionGrade(s,r);
    }
    /** Commit only an already validated, consistent corridor; no blocks, tasks or paid progress are changed. */
    public static boolean commitNecessaryGrades(ServerLevel level,SettlementRecord s){
        boolean changed=false,added=false,invalidated=false;
        for(var r:s.autoRoads().routes.values())if(r.constructionGradeFixed && (r.suppressed || !necessaryPlan(s,r.building) || !parentValid(level,s,r))){
            invalidated|=r.suppressed || necessaryPlan(s,r.building);
            r.constructionGradeFixed=false;changed=true;
        }
        for(var r:s.autoRoads().routes.values())if(!r.constructionGradeFixed && !r.suppressed && necessaryPlan(s,r.building)
                && r.width==3 && !r.centerline.isEmpty() && parentValid(level,s,r)){
            var heights=new HashMap<Long,Integer>();boolean valid=true;
            for(var p:r.cells){var old=heights.putIfAbsent(BlockPos.asLong(p.getX(),0,p.getZ()),p.getY());
                if(old!=null && old!=p.getY() || !RoadEarthworkService.permitted(level,s,p)){valid=false;break;}}
            for(int i=1;valid && i<r.centerline.size();i++){
                var a=r.centerline.get(i-1);var b=r.centerline.get(i);
                if(Math.max(Math.abs(a.getX()-b.getX()),Math.abs(a.getZ()-b.getZ()))!=1 || Math.abs(a.getY()-b.getY())>1)valid=false;
            }
            if(valid){r.constructionGradeFixed=true;r.earthworkCursor=0;changed=true;added=true;}
        }
        if(added || invalidated)com.hkin.exiledsettlement.terrain.PublicGroundService.requestReview(s);
        else if(changed)com.hkin.exiledsettlement.terrain.PublicGroundService.queueReleasedConstraintReview(s);
        return changed;
    }
    public static boolean routeAllowed(SettlementRecord s,AutoRoadState.Route r){return !r.p0Invalidated && (r.manualBridge || required(s,r) || s.autoRoads().enabled && !s.terrain().publicPaused);}
    public static List<BlockPos> entrances(ServerLevel level,SettlementRecord s,com.hkin.exiledsettlement.blueprint.BlueprintRecord b){
        if(!pending(b))return List.of();var t=BlueprintTemplates.create(b);var result=new ArrayList<BlockPos>();
        for(var e:t.entrances()) {var p=RoadEntranceService.outside(t,e);
            if(RoadEarthworkService.plannable(level,s,p) && !result.contains(p))result.add(p);}
        return List.copyOf(result);
    }
    public static boolean parentMatches(ServerLevel level,SettlementRecord s,UUID parent,BlockPos end){
        if(BridgeRoadConnections.bridge(s,parent)!=null)return BridgeRoadConnections.entrances(s,parent).contains(end);
        var b=s.building(parent);if(b!=null)return entranceMatches(level,s,b,end);
        var site=s.blueprint(parent);return site!=null && entrances(level,s,site).contains(end);
    }
    public static boolean parentValid(ServerLevel level,SettlementRecord s,AutoRoadState.Route r){return r.manualBridge || parentMatches(level,s,r.building,r.entrance);}
    public static boolean accessReady(SettlementRecord s,UUID parent){return s.autoRoads().accessSurveyed.contains(parent)
            && s.autoRoads().routes.values().stream().filter(r->r.building.equals(parent)).anyMatch(r->!r.suppressed && r.accessConnected);}
    public static void transfer(SettlementRecord s,UUID from,UUID to){for(var r:s.autoRoads().routes.values())if(r.building.equals(from))r.building=to;s.autoRoads().accessSurveyed.remove(from);}
    private static boolean planPending(ServerLevel level,SettlementRecord s){
        var sites=s.blueprints().stream().filter(b->needsAccess(s,b)).sorted(Comparator.comparing(b->b.id().toString())).toList();
        for(var b:sites) {
            var existing=s.autoRoads().routes.entrySet().stream().filter(e->e.getValue().building.equals(b.id())).findFirst().orElse(null);
            if(existing!=null) {
                s.autoRoads().accessSurveyed.add(b.id());var route=existing.getValue();
                if(!route.suppressed && level.getGameTime()>=route.reviewAfter
                        && (!parentValid(level,s,route) || route.progress<route.cells.size() && (route.status.startsWith("桥梁局部结构") || route.cells.stream().anyMatch(p->level.hasChunkAt(p) && !RoadEarthworkService.plannable(level,s,p))))) {
                    var root=coreSurface(level,s);if(root!=null){route.reviewAfter=level.getGameTime()+1200;
                        var endpoints=entrances(level,s,b);
                        var end=endpoints.stream().min(Comparator.comparingDouble(p->p.distSqr(route.entrance))).orElse(null);
                        if(end!=null){AutoRoadPlanner.begin(level,s,b.id(),root,end,existing.getKey());return true;}}
                }
                continue;
            }
            if(level.getGameTime()<s.autoRoads().accessRetry.getOrDefault(b.id(),0L))continue;
            var endpoints=entrances(level,s,b);var root=coreSurface(level,s);
            if(root==null || endpoints.isEmpty()){s.autoRoads().status="待建建筑施工入口等待已加载可用地形："+b.anchor().toShortString();s.autoRoads().accessRetry.put(b.id(),level.getGameTime()+200);continue;}
            var end=endpoints.getFirst();AutoRoadPlanner.begin(level,s,b.id(),root,end,b.id()+":"+end.asLong());
            s.autoRoads().status="正在比较建筑道路与已有桥梁";return true;
        }
        return false;
    }

    public static void tick(ServerLevel level, SettlementRecord s) {
        var state = s.autoRoads(); state.newDay(level.getDayTime() / 24000L);
        state.workReady = false;
        if (!state.enabled && !hasPending(s)) { state.status = "尚未开启居民自动修路"; return; }
        if (s.community().evacuation) { state.status = "集合避险期间暂停公共工程"; return; }
        state.workReady = true;
        if(state.gradingReviewVersion<1){
            state.gradingReviewVersion=1;
            if(s.terrain().enabled)com.hkin.exiledsettlement.terrain.PublicGroundService.requestReview(s);
            SettlementSavedData.get(level).touch();
        }
        commitNecessaryGrades(level,s);
        if (AutoRoadPlanner.busy(s)) return;
        if(planPending(level,s))return;
        if(state.enabled && !s.terrain().publicPaused && BridgeRoadConnections.plan(level,s))return;
        if(state.enabled && !s.terrain().publicPaused && AdaptiveRoadService.reviewCommittedConflict(level,s))return;
        if(com.hkin.exiledsettlement.terrain.PublicGroundService.routePlanningPaused(s)){
            var capacity=com.hkin.exiledsettlement.terrain.OptionalWorkBudget.snapshot(s);
            state.status=capacity.full()?capacity.status():"等待公共地面："+s.terrain().publicGround.status;
            return;
        }
        if(!state.enabled || s.terrain().publicPaused)return;
        if(planUnconnected(level,s))return;
        // Existing-route optimization can wait for publication; new connections cannot.
        if(s.terrain().enabled && s.terrain().publicGround.phase!=0
                || com.hkin.exiledsettlement.terrain.OptionalWorkBudget.snapshot(s).full())return;
        if(AdaptiveRoadService.review(level,s))return;
        state.status = state.pending() > 0 ? "等待空闲工人持铲施工" : "检查建筑入口与道路连接";
        // At most one existing route is widened per planner pass; suppression and already laid cells survive.
        for (var old : state.routes.values()) if (!old.suppressed && old.width == 1) {
            var wider = corridor(level, s, old.centerline);
            if (wider.isEmpty()) { old.status = "旧路线侧边无法安全补宽，等待三格通行带"; continue; }
            var released=new HashSet<BlockPos>();for(int i=old.admitted.nextSetBit(0);i>=0 && i<old.cells.size();i=old.admitted.nextSetBit(i+1))released.add(old.cells.get(i));
            old.cells = wider; old.width = 3; old.progress = 0; old.completed.clear();
            old.admitted.clear();for(int i=0;i<wider.size();i++)if(released.contains(wider.get(i)))old.admitted.set(i);
            old.repairs.clear();
            old.status = "旧路线已生成三格补宽任务"; return;
        }
    }

    /** Unconnected buildings get a bounded turn before optional optimization. */
    private static boolean planUnconnected(ServerLevel level,SettlementRecord s){
        var state=s.autoRoads();
        var buildings = s.buildings().stream().filter(AutoRoadService::roadEligible)
                .filter(b -> DecorationService.forBuilding(s, b.id()) == null)
                .sorted(Comparator.comparingInt((BuildingRecord b) -> b.type().isStorage() ? 0 : b.type().isHouse() ? 1 : 2)
                        .thenComparing(b -> b.id().toString())).toList();
        if (buildings.isEmpty())return false;
        for(int inspected=0;inspected<Math.min(32,buildings.size());inspected++){
        var building = buildings.get(Math.floorMod(state.cursor++, buildings.size()));
        if(level.getGameTime()<state.accessRetry.getOrDefault(building.id(),0L))continue;
        List<BlockPos> endpoints = entrances(level, s, building);
        if (endpoints.isEmpty())continue;
        // One entrance per pass; a completed/suppressed stable slot is never replanned.
        for (BlockPos end : endpoints) {
            if(state.routes.values().stream().anyMatch(r->r.building.equals(building.id()) && Math.abs(r.entrance.getX()-end.getX())+Math.abs(r.entrance.getZ()-end.getZ())<=1))continue;
            String key = building.id() + ":" + end.asLong();
            var old = state.routes.get(key);
            if (old != null && (old.suppressed || old.progress > 0 || old.earthworkCursor>0 || old.areaOptimized)) continue;
            BlockPos root = coreSurface(level, s);
            if (root == null) { state.status = "核心附近没有可安全铺路的地面"; return false; }
            // Reuse the old identity and paid history when this is a moved entry.
            var moved=state.routes.entrySet().stream().filter(e->e.getValue().building.equals(building.id())
                    && !e.getValue().suppressed && !parentValid(level,s,e.getValue())
                    && end.equals(AdaptiveRoadService.reviewEndpoint(s,e.getValue(),endpoints))).findFirst().orElse(null);
            AutoRoadPlanner.begin(level, s, building, root, end, moved==null?key:moved.getKey());
            return true;
        }
        }
        return false;
    }

    /** Template entrances are authoritative; extend outwards past the complete building footprint. */
    public static List<BlockPos> entrances(ServerLevel level, SettlementRecord s, BuildingRecord b) {
        if (b.active() && b.operational() && !b.demolitionQueued() && b.type() == BuildingType.WAGON) {
            // The starter wagon is a registered physical container, without a blueprint template.
            return Arrays.stream(SIDES).map(side -> b.position().relative(side))
                    .map(pos -> surface(level, s, pos.getX(), pos.getZ(), b.position().getY() - 1, 3))
                    .filter(Objects::nonNull).min(Comparator.comparingDouble(p -> p.distSqr(s.corePosition())))
                    .map(List::of).orElse(List.of());
        }
        // School is intentionally appearance-only and never operational.
        // Its physically completed entrance must still receive a real road.
        if (!roadEligible(b) || !BlueprintTemplates.supports(b.type())) return List.of();
        var endpoints = new ArrayList<BlockPos>();
        var template=BlueprintTemplates.createForBuilding(b);
        for (var entrance : template.entrances()) {
            var cell=RoadEntranceService.outside(template,entrance);
            if (RoadEarthworkService.plannable(level,s,cell) && !endpoints.contains(cell)) endpoints.add(cell);
        }
        return List.copyOf(endpoints);
    }

    /** Access belongs to the completed structure, including a temporarily unusable workplace. */
    public static boolean roadEligible(BuildingRecord building) {
        return building!=null && building.active() && !building.demolitionQueued()
                && (building.operational() || BlueprintTemplates.supports(building.type())
                    && (!building.type().isDynamicArea() || building.region()==null
                        && (building.productionSite()!=null || BlueprintTemplates.fixedProduction(building.type(),building.templateVersion()))));
    }

    public static boolean entranceMatches(ServerLevel level,SettlementRecord s,BuildingRecord b,BlockPos entrance) {
        return entrances(level,s,b).contains(entrance);
    }

    public static BlockPos coreSurface(ServerLevel level, SettlementRecord s) {
        for (int distance = 1; distance <= 3; distance++) for (Direction side : SIDES) {
            var pos = s.corePosition().relative(side, distance);
            var found = surface(level, s, pos.getX(), pos.getZ(), pos.getY() - 1, 3);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean inside(BlockPos p, BlockPos min, BlockPos max) {
        return p.getX() >= min.getX() && p.getX() <= max.getX()
                && p.getZ() >= min.getZ() && p.getZ() <= max.getZ();
    }

    public static boolean safeCell(ServerLevel level, SettlementRecord s, BlockPos p) {
        if(com.hkin.exiledsettlement.building.MobRecoveryService.owns(s,p))return false;
        var bridge=BridgeService.owner(s,p);if(bridge!=null && bridge.accessSurfaces.contains(p))return BridgeService.ready(level,s,p);
        if(com.hkin.exiledsettlement.terrain.TerrainService.pendingAt(level,s,p))return false;
        if (!s.contains(p) || !s.contains(p.above(2)) || !level.hasChunkAt(p)
                || level.isOutsideBuildHeight(p) || level.isOutsideBuildHeight(p.above(2))
                || s.decorations().forbidden(p) || s.community().danger(p)) return false;
        var ground = level.getBlockState(p);
        var cover = level.getBlockState(p.above());
        if (!ground.getFluidState().isEmpty() || !cover.isAir()
                || !level.getBlockState(p.above(2)).isAir()) return false;
        if (s.buildings().stream().anyMatch(b -> b.active() && b.containsColumn(p))
                || s.blueprints().stream().anyMatch(b -> b.terrainProject()==null && !b.cancellationQueued() && b.containsColumn(p))) return false;
        if (RoadSpaceData.get(level).contains(p) || plannedAt(s,p)) {
            if(RoadSurfaceProfile.usable(level,p))return true;
        }
        var owner=s.terrain().cells.get(p);var publicProject=owner==null?null:s.terrain().projects.get(owner);var goal=publicProject==null?null:publicProject.template().placementAt(p);
        return natural(ground) || ground.is(Blocks.DIRT_PATH) || goal!=null && !goal.state().isAir() && com.hkin.exiledsettlement.terrain.TerrainService.satisfied(ground,goal.state()) && AdaptiveRoadService.walkSurface(level,p);
    }
    public static boolean laidCell(ServerLevel level,SettlementRecord s,AutoRoadState.Route route,BlockPos p){
        if(!RoadSpaceData.get(level).contains(p) || !RoadEarthworkService.surfaceReady(level,s,route,p))return false;
        var bridge=BridgeService.owner(s,p);
        if(bridge!=null && bridge.accessSurfaces.contains(p))return BridgeService.ready(level,s,p);
        return PlayerRoadEdits.retained(level,p) || !natural(level.getBlockState(p));
    }

    private static boolean natural(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM) || state.is(Blocks.ROOTED_DIRT);
    }

    private static BlockPos surface(ServerLevel level, SettlementRecord s, int x, int z, int y, int range) {
        BlockPos selected=null;double effort=Double.MAX_VALUE;
        for (int delta = 0; delta <= range; delta++) for (int sign : delta == 0 ? new int[]{1} : new int[]{1, -1}) {
            var p=new BlockPos(x,y+delta*sign,z);
            if(RoadEarthworkService.plannable(level,s,p)){double candidate=RoadEarthworkService.effort(level,s,p);if(candidate<effort){effort=candidate;selected=p;}}
        }
        return selected;
    }

    /** Standalone path query. Production planning uses resumable AutoRoadPlanner slices. */
    public static List<BlockPos> route(ServerLevel level, SettlementRecord s, BlockPos start, BlockPos end) {
        if (!safeCell(level, s, start) || !safeCell(level, s, end)) return List.of();
        record Node(BlockPos pos, double cost) { }
        var queue = new PriorityQueue<Node>(Comparator.comparingDouble((Node n) -> n.cost() + n.pos().distManhattan(end) * .35D).thenComparingLong(n -> n.pos().asLong()));
        var costs = new HashMap<BlockPos, Double>(); var parents = new HashMap<BlockPos, BlockPos>();
        queue.add(new Node(start, 0)); costs.put(start, 0D);
        var roads = RoadSpaceData.get(level);
        while (!queue.isEmpty()) {
            var current = queue.remove();
            if (current.cost() > costs.getOrDefault(current.pos(), Double.MAX_VALUE)) continue;
            if (current.pos().equals(end)) {
                var cells = new ArrayList<BlockPos>();
                for (BlockPos p = end; p != null; p = parents.get(p)) cells.add(p);
                Collections.reverse(cells); return List.copyOf(cells);
            }
            for (var side : SIDES) {
                var flat = current.pos().relative(side);
                var next = surface(level, s, flat.getX(), flat.getZ(), current.pos().getY(), 1);
                if (next == null || !wideNode(level, s, next, start, end)) continue;
                double cost = current.cost() + (roads.contains(next) ? 0.35D : 1D)
                        + Math.abs(current.pos().getY() - next.getY()) * 2D;
                if (cost >= costs.getOrDefault(next, Double.MAX_VALUE)) continue;
                costs.put(next, cost); parents.put(next, current.pos()); queue.add(new Node(next, cost));
            }
        }
        return List.of();
    }

    private static boolean wideNode(ServerLevel level, SettlementRecord s, BlockPos center, BlockPos start, BlockPos end) {
        if (AdaptiveRoadService.paidPassage(level,s,center)) return true;
        if (center.distManhattan(start) <= 2 || center.distManhattan(end) <= 2) return true;
        for (int dx=-1; dx<=1; dx++) for (int dz=-1; dz<=1; dz++) {
            BlockPos side=surface(level,s,center.getX()+dx,center.getZ()+dz,center.getY(),1);
            if (side==null) return false;
        }
        return true;
    }

    /** Ordered paid cells, with full corner joins; short endpoint throats preserve actual door widths. */
    public static List<BlockPos> corridor(ServerLevel level, SettlementRecord s, List<BlockPos> centerline) {
        if (centerline.isEmpty()) return List.of();
        LinkedHashSet<BlockPos> cells=new LinkedHashSet<>();
        var heights=new HashMap<Long,Integer>();
        for (int i=0;i<centerline.size();i++) {
            var row = corridorCell(level, s, centerline, i);
            if (row == null) return List.of();
            for(var p:row){var old=heights.putIfAbsent(BlockPos.asLong(p.getX(),0,p.getZ()),p.getY());
                if(old!=null && old!=p.getY())return List.of();}
            cells.addAll(row);
        }
        return List.copyOf(cells);
    }

    /** A planner expands one row per step, so a long corridor also remains resumable. */
    static List<BlockPos> corridorCell(ServerLevel level, SettlementRecord s, List<BlockPos> centerline, int i) {
        return corridorCell(level,s,centerline,i,true);
    }
    static List<BlockPos> corridorCell(ServerLevel level, SettlementRecord s, List<BlockPos> centerline, int i,boolean endpointThroats) {
            var cells = new ArrayList<BlockPos>();
            BlockPos center=centerline.get(i);
            if (!RoadEarthworkService.plannable(level,s,center)) return null;
            cells.add(center);
            if(BridgeService.span(level,s,center)){
                // Only the registered walking deck participates; never widen into rails or pillars.
                var fullRow=BridgeRoadConnections.deckRow(s,center);
                if(!fullRow.isEmpty())return fullRow;
                for(var d:SIDES){var side=center.relative(d);if(BridgeService.span(level,s,side))cells.add(side);}
                return cells;
            }
            boolean passage=AdaptiveRoadService.fixedPassage(level,s,center);
            boolean interfaceRow=passage || endpointThroats && (center.distManhattan(centerline.getFirst())<=2
                    || center.distManhattan(centerline.getLast())<=2);
            BlockPos previous=centerline.get(Math.max(0,i-1)), next=centerline.get(Math.min(centerline.size()-1,i+1));
            boolean alongX=previous.getX()!=center.getX() || next.getX()!=center.getX();
            boolean alongZ=previous.getZ()!=center.getZ() || next.getZ()!=center.getZ();
            for (int dx=-1;dx<=1;dx++) for (int dz=-1;dz<=1;dz++) {
                if (dx==0 && dz==0) continue;
                if (!(alongX && alongZ) && (alongX ? dx!=0 : dz!=0)) continue;
                var aligned=center.offset(dx,0,dz);
                // Ordinary rows have one grade across the whole width. Only an existing
                // fixed passage or entrance throat may retain its original narrow seam.
                var edited=RoadSpaceData.get(level).playerSurface(aligned);
                BlockPos side=edited!=null && PlayerRoadEdits.retained(level,edited)?edited:RoadEarthworkService.plannable(level,s,aligned)?aligned:interfaceRow?surface(level,s,aligned.getX(),aligned.getZ(),center.getY(),1):null;
                if (side==null) {
                    if(passage)continue;
                    if (endpointThroats && (center.distManhattan(centerline.getFirst())<=2 || center.distManhattan(centerline.getLast())<=2)) continue;
                    return null;
                }
                if(passage && !AdaptiveRoadService.paidPassage(level,s,side) && !RoadSpaceData.get(level).contains(side))continue;
                cells.add(side);
            }
            return cells;
    }

    public static int shovelSlot(CitizenRecord citizen) {
        return citizen.findInventorySlot(stack -> stack.getItem() instanceof ShovelItem);
    }

    private record ToolSource(BuildingRecord warehouse, Item item) { }
    private static ToolSource toolSource(ServerLevel level, SettlementRecord s, BlockPos from) {
        for (var b : SettlementInventoryService.warehouses(s).stream()
                .sorted(Comparator.comparingDouble(b -> b.position().distSqr(from))).toList()) {
            Item item = CommunityStock.items(level, s, b).keySet().stream()
                    .filter(i -> i instanceof ShovelItem)
                    .min(Comparator.comparing(i -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(i).toString())).orElse(null);
            if (item != null) return new ToolSource(b, item);
            if (WarehouseInventoryService.hasCreativeSupply(level, b)) return new ToolSource(b, Items.WOODEN_SHOVEL);
        }
        return s.creativeCoreInstalled() ? new ToolSource(null, Items.WOODEN_SHOVEL) : null;
    }

    /** Called only after normal construction, repair and logistics had the opportunity to claim this worker. */
    public static boolean assign(ServerLevel level, SettlementRecord s, CitizenRecord c, Set<BlockPos> claimed) {
        return assign(level,s,c,claimed,false);
    }
    public static boolean assign(ServerLevel level, SettlementRecord s, CitizenRecord c, Set<BlockPos> claimed,boolean priorityOnly) {
        // Building slots belong to construction and its local preparation, never to full-route paving.
        if(priorityOnly)return false;
        var state = s.autoRoads(); state.newDay(level.getDayTime() / 24000L);
        if (!com.hkin.exiledsettlement.citizen.CitizenWorkService.constructionCapable(c.profession()) || !allowed(s) || !c.canWork()
                || c.preferredWorksiteId() != null) return false;
        var capacity=com.hkin.exiledsettlement.terrain.OptionalWorkBudget.snapshot(s);
        var motherCounts=com.hkin.exiledsettlement.citizen.WorkTaskService.counts(s);
        var jobs=state.routes.values().stream().filter(r->{var parent=s.blueprint(r.building);return !priorityOnly || com.hkin.exiledsettlement.citizen.WorkTaskService.building(s,parent)
                && motherCounts.getOrDefault(parent.id(),0)<com.hkin.exiledsettlement.citizen.WorkTaskService.defaultWorkers(parent);})
                .sorted(Comparator.comparingInt((AutoRoadState.Route r)->r.repairs.isEmpty()?1:0).thenComparingInt((AutoRoadState.Route r)->{var parent=s.blueprint(r.building);return com.hkin.exiledsettlement.citizen.WorkTaskService.building(s,parent)
                    && motherCounts.getOrDefault(parent.id(),0)<com.hkin.exiledsettlement.citizen.WorkTaskService.defaultWorkers(parent)?0:1;}).thenComparingDouble((AutoRoadState.Route r)->
                s.citizens().stream().filter(other->other.workState()==CitizenWorkState.TO_ROAD && r.containsCell(other.targetPosition())).count()
                        /Math.max(1D,Math.ceil((r.cells.size()-r.completed.cardinality())/32D)))
                .thenComparingDouble(r->r.entrance.distSqr(c.lastPosition()))).toList();
        for (var job : jobs) {
            if (job.suppressed || job.progress >= job.cells.size() || !routeAllowed(s,job)) continue;
            if(BridgeRoadConnections.bridge(s,job.building)!=null && !RoadEarthworkService.accessBlocker(level,s,job).isEmpty()){
                job.status="先接通桥端落脚面，再铺设道路";continue;
            }
            var b = s.building(job.building);
            boolean validParent=parentValid(level,s,job);
            if (!validParent && job.repairs.isEmpty()) { job.status = "建筑或入口已改变，等待重新接路；保留已建道路"; continue; }
            while (job.progress < job.cells.size() && laidCell(level,s,job,job.cells.get(job.progress))) job.progress++;
            if (job.progress == job.cells.size()) { job.status = "连接完成"; continue; }
            BlockPos target = null;
            for(int i=job.repairs.nextSetBit(job.progress);i>=0 && i<job.cells.size();i=job.repairs.nextSetBit(i+1)){
                var candidate=job.cells.get(i);if(job.completed.get(i) || claimed.contains(candidate)
                        || DEFERRED.getOrDefault(level,Map.of()).getOrDefault(candidate,0L)>level.getGameTime())continue;
                if(RoadEarthworkService.surfaceReady(level,s,job,candidate) && capacity.admitPaving(job,candidate)){target=candidate;break;}
            }
            for (int scanned=0;validParent && target==null && scanned<Math.min(1024,job.cells.size()-job.progress);scanned++) {
                if(job.workCursor<job.progress || job.workCursor>=job.cells.size())job.workCursor=job.progress;
                int i=job.workCursor++;
                BlockPos candidate=job.cells.get(i);
                if (job.completed.get(i) || claimed.contains(candidate) || DEFERRED.getOrDefault(level,Map.of()).getOrDefault(candidate,0L)>level.getGameTime()) continue;
                if (laidCell(level,s,job,candidate)) { job.completed.set(i); continue; }
                if (RoadEarthworkService.surfaceReady(level,s,job,candidate)
                        && capacity.admitPaving(job,candidate)) { target=candidate; break; }
            }
            job.advance();
            if (target==null) { job.status="等待局部整地、通路或保护条件；其他路线继续"; continue; }

            if (c.startTask(CitizenWorkState.TO_ROAD, target, null, null)) {
                c.setRoadTaskId(job.id);
                claimed.add(target); job.status = "工人前往路面施工"; return true;
            }
        }
        return false;
    }

    public static void process(ServerLevel level, SettlementSavedData data, SettlementRecord s,
                               CitizenRecord c, SettlementCitizenEntity entity) {
        var state = s.autoRoads(); state.newDay(level.getDayTime() / 24000L);
        if (!com.hkin.exiledsettlement.citizen.CitizenWorkService.constructionCapable(c.profession()) || !allowed(s)) { finish(level, data, c, entity); return; }
        if (c.workState() == CitizenWorkState.TO_ROAD_TOOL) { finish(level,data,c,entity); return; }
        // Shared physical cells do not change the assigned route's identity.
        // Another route at this same cell may have an invalid entrance; choosing
        // that route repeatedly released every worker before navigation began.
        var job = c.roadTaskId()==null ? state.routes.values().stream()
                .filter(r->!r.suppressed && r.containsCell(c.targetPosition()) && RoadMaintenanceService.workTargetAllowed(level,s,r,c.targetPosition()) && routeAllowed(s,r))
                .findFirst().orElse(null) : state.routes.values().stream()
                .filter(r->r.id.equals(c.roadTaskId()) && !r.suppressed && r.containsCell(c.targetPosition()))
                .findFirst().orElse(null);
        if (job == null) { finish(level, data, c, entity); return; }
        var b = s.building(job.building);
        if (!RoadMaintenanceService.workTargetAllowed(level,s,job,c.targetPosition()) || !routeAllowed(s,job)) {
            job.status = "建筑或入口已改变，暂停此路线"; finish(level, data, c, entity); return;
        }
        if (!safeCell(level, s, c.targetPosition())) {
            job.status = "地形、禁放区或通道已改变，暂停此路线"; finish(level, data, c, entity); return;
        }

        BlockPos standing = c.targetPosition().above();
        entity.setItemSlot(EquipmentSlot.MAINHAND, c.inventoryItem(shovelSlot(c)).copy());
        // A dirt path is 1/16 block lower than dirt. Keep the existing, collision-checked
        // one-cell movement controller active across polls when vanilla cannot cross that seam.
        if (entity.hasActiveConstructionMovement() && !com.hkin.exiledsettlement.blueprint.TimedConstructionService.columnArrived(level,entity,c.targetPosition())) {
            WORK.remove(c); entity.continueConstructionStep(.65D);
            job.status = "工人正在跨过路面接缝"; return;
        }
        // Snow hides the ground from the reach check. Shovel it on site before working the dirt.
        if (level.getBlockState(standing).is(Blocks.SNOW)
                && entity.onGround() && CitizenInteractionRange.contains(entity, standing)) {
            if (!net.neoforged.neoforge.common.CommonHooks.canEntityDestroy(level, standing, entity)) {
                job.status = "领地保护拒绝清雪";
                activity(data, c, entity, CitizenActivity.BLOCKED, "ROAD_BLOCKED"); return;
            }
            int slot = shovelSlot(c);
            var snow = level.getBlockState(standing);
            var drops = Block.getDrops(snow, level, standing, null, entity, c.inventoryItem(slot));
            if (level.destroyBlock(standing, false, entity)) {
                for (var drop : drops) {
                    var excess = c.insertIntoInventory(drop);
                    if (!excess.isEmpty()) Block.popResource(level, standing, excess);
                }
                c.damageInventoryItem(slot, 1);
                entity.swing(InteractionHand.MAIN_HAND);
                data.touch(); job.status = "工人已清除路面雪层";
            }
            WORK.remove(c); return;
        }
        if (!com.hkin.exiledsettlement.blueprint.TimedConstructionService.columnArrived(level,entity,c.targetPosition())) {
            TIMED_WORK.remove(c);
            WORK.remove(c);
            String failure = entity.navigateTo(standing, .65D);
            if (("NO_PATH".equals(failure) || "STUCK".equals(failure))
                    && entity.beginAdjacentConstructionMovement(standing, .65D)) failure = "";
            job.status = failure == null || failure.isEmpty() ? "工人正在前往路面" : "暂时无法到达路面（" + failure + "）";
            if("NO_PATH".equals(failure) || "STUCK".equals(failure)) {
                DEFERRED.computeIfAbsent(level,l->new HashMap<>()).put(c.targetPosition(),level.getGameTime()+1200);
                job.status="暂缓不可达路面，工人尝试其他道路："+c.targetPosition().toShortString();finish(level,data,c,entity);return;
            }
            activity(data, c, entity, failure == null || failure.isEmpty() ? CitizenActivity.ROAD_BUILDING : CitizenActivity.BLOCKED,
                    failure == null ? "" : failure); return;
        }
        entity.stopSettlementNavigation();
        long now=level.getGameTime();TIMED_WORK.putIfAbsent(c,now);
        entity.swing(InteractionHand.MAIN_HAND);
        activity(data, c, entity, CitizenActivity.ROAD_BUILDING, ""); job.status = "正在铺设土径";
        if (now-TIMED_WORK.get(c)<com.hkin.exiledsettlement.citizen.CitizenToolSpeed.interval(c,com.hkin.exiledsettlement.blueprint.TimedConstructionService.INTERVAL)) return;
        var result = layCell(level, s, c, entity, c.targetPosition());
        if (!result.success()) { job.status = result.reason(); activity(data, c, entity, CitizenActivity.BLOCKED, "ROAD_BLOCKED"); WORK.remove(c); return; }
        job.mark(c.targetPosition()); job.status = job.progress == job.cells.size() ? "连接完成" : "继续铺路";
        data.touch(); finish(level, data, c, entity);
    }

    /** On-site authoritative transaction. Protection rejection must restore terrain before any charge or road registration. */
    public static OperationResult<Integer> layCell(ServerLevel level, SettlementRecord s, CitizenRecord c,
                                                 SettlementCitizenEntity worker, BlockPos p) {
        if(com.hkin.exiledsettlement.building.MobRecoveryService.owns(s,p))return OperationResult.failure("等待P1生物破坏恢复");
        var state = s.autoRoads(); state.newDay(level.getDayTime() / 24000L);
        if (!allowed(s) || !c.canWork()
                || state.routeAt(p) == null || !safeCell(level, s, p))
            return OperationResult.failure("道路任务暂停或地形已改变");
        int slot = shovelSlot(c);
        var standing = p.above();
        if (!com.hkin.exiledsettlement.blueprint.TimedConstructionService.columnArrived(level,worker,p))
            return OperationResult.failure("工人尚未到达施工位置");
        if (!natural(level.getBlockState(p)) && RoadSurfaceProfile.usable(level,p)) {
            RoadSpaceData.get(level).addAll(List.of(p), RoadTier.LEVEL_1);
            com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,c,"road_reuse",p,level.getBlockState(p).getBlock().asItem().toString(),0);
            return OperationResult.success(0);
        }
        var snapshot = BlockSnapshot.create(level.dimension(), level, p, Block.UPDATE_CLIENTS);
        if (!PlayerRoadEdits.retained(level,p) && natural(level.getBlockState(p)) && !level.setBlock(p, Blocks.DIRT_PATH.defaultBlockState(), Block.UPDATE_ALL))
            return OperationResult.failure("路面放置失败");
        if (EventHooks.onBlockPlace(worker, snapshot, Direction.UP)) {
            snapshot.restore(Block.UPDATE_ALL); return OperationResult.failure("领地保护拒绝修路");
        }
        RoadSpaceData.get(level).addAll(List.of(p), RoadTier.LEVEL_1);
        c.damageInventoryItem(slot, 1); state.spent++;
        com.hkin.exiledsettlement.debug.SaveAcceptanceService.action(level,c,"road_lay",p,level.getBlockState(p).getBlock().asItem().toString(),1);
        return OperationResult.success(1);
    }

    private static void fetchTool(ServerLevel level, SettlementSavedData data, SettlementRecord s,
                                  CitizenRecord c, SettlementCitizenEntity e) {
        if (shovelSlot(c) >= 0) { finish(level, data, c, e); return; }
        var source = toolSource(level, s, e.blockPosition());
        if (source == null) { s.autoRoads().status = "公共库存缺少铲子"; finish(level, data, c, e); return; }
        var warehouse = source.warehouse();
        BlockPos target = warehouse == null ? SettlementCoreInteractionService.reserveSupplyPosition(level, s, e)
                : WarehouseInventoryService.reserveExtractionPosition(level, warehouse, e, source.item());
        if (target == null) { activity(data, c, e, CitizenActivity.BLOCKED, "WAREHOUSE_STATION_BUSY"); return; }
        if (e.distanceToSqr(target.getX() + .5D, target.getY(), target.getZ() + .5D) > .81D) {
            String failure = e.navigateTo(target, .65D);
            activity(data, c, e, CitizenActivity.FETCHING_EQUIPMENT, failure == null ? "" : failure); return;
        }
        if (warehouse == null ? !SettlementCoreInteractionService.canReceiveSupply(level, s, e)
                : !WarehouseInventoryService.canInteract(level, warehouse, e)) {
            activity(data, c, e, CitizenActivity.BLOCKED, "WAREHOUSE_INACCESSIBLE"); return;
        }
        if (c.equipmentCapacityFor(new ItemStack(source.item())) < 1) {
            activity(data, c, e, CitizenActivity.BLOCKED, "INVENTORY_FULL"); return;
        }
        ItemStack extracted = warehouse == null ? new ItemStack(source.item())
                : WarehouseInventoryService.extractItemForResident(level, warehouse, e, source.item(), 1);
        if (extracted.isEmpty() && warehouse != null && WarehouseInventoryService.hasCreativeSupply(level, warehouse))
            extracted = new ItemStack(source.item());
        if (extracted.isEmpty()) { finish(level, data, c, e); return; }
        var remaining = c.receiveEquipment(extracted);
        if (!remaining.isEmpty() && warehouse != null) WarehouseInventoryService.insertForResident(level, warehouse, e, remaining);
        finish(level, data, c, e); data.touch();
    }

    private static void activity(SettlementSavedData data, CitizenRecord c, SettlementCitizenEntity e, CitizenActivity activity, String failure) {
        if (c.updateActivity(activity) | c.updateWorkFailure(failure)) data.touch(); e.setActivity(activity);
    }

    private static void finish(ServerLevel level, SettlementSavedData data, CitizenRecord c, SettlementCitizenEntity e) {
        TIMED_WORK.remove(c);
        WORK.remove(c); WarehouseInventoryService.releaseInteractionPosition(level, e);
        SettlementCoreInteractionService.release(level, e);
        if (c.clearWork()) data.touch(); e.stopSettlementNavigation();
        CitizenEquipmentService.syncVisibleEquipment(c, e); e.setActivity(CitizenActivity.IDLE);
    }

    public static String cancelNear(SettlementRecord s, BlockPos p) {
        var route = s.autoRoads().routes.values().stream().filter(r -> !r.suppressed)
                .min(Comparator.comparingDouble(r -> r.cells.stream().mapToDouble(at -> at.distSqr(p)).min().orElse(Double.MAX_VALUE))).orElse(null);
        if (route == null || route.cells.stream().noneMatch(at -> at.distSqr(p) <= 256)) return "附近16格内没有自动路线。";
        route.suppressed = true; AutoRoadPlanner.invalidate(s);
        route.status = "玩家已撤销，保持现有路面且不再重建"; return route.status;
    }

    public static SettlementRecord protectedSettlementAt(ServerLevel level, BlockPos p) {
        return SettlementSavedData.get(level).settlements().stream().filter(s -> s.active()
                && s.autoRoads().routes.values().stream().anyMatch(r -> r.containsCell(p))).findFirst().orElse(null);
    }

    public static boolean plannedAt(SettlementRecord s, BlockPos p) {
        return s.autoRoads().routes.values().stream().anyMatch(r -> !r.suppressed && r.containsCell(p));
    }

    public static void notePlayerRemoval(ServerLevel level, BlockPos p) {
        if (protectedSettlementAt(level, p) != null)
            REMOVALS.computeIfAbsent(level, ignored -> new LinkedHashMap<>()).put(p.immutable(), level.getBlockState(p));
    }

    public static void flushPlayerRemovals(ServerLevel level) {
        var pending = REMOVALS.remove(level); if (pending == null) return;
        pending.forEach((p, before) -> { if (!level.getBlockState(p).equals(before)) playerRemoved(level, p); });
    }

    public static void playerRemoved(ServerLevel level, BlockPos p) {
        var data = SettlementSavedData.get(level);
        for (var s : data.settlements()) for (var r : s.autoRoads().routes.values()) {
            if (!reconsiderSurface(r,p)) continue;
            AutoRoadPlanner.invalidate(s);
            var roads=RoadSpaceData.get(level);
            if(!roads.manual(p) && !RoadSurfaceProfile.usable(level,p))roads.remove(p);
            data.touch();
        }
    }

    /** A block edit invalidates its completion, while explicit route cancellation remains authoritative. */
    public static boolean reconsiderSurface(AutoRoadState.Route route,BlockPos p){
        if(route.suppressed || !route.containsCell(p))return false;
        int i=route.cellIndex(p);route.completed.clear(i);route.progress=Math.min(route.progress,i);
        route.earthworkCursor=Math.min(route.earthworkCursor,i);route.reviewAfter=0;
        route.status="玩家调整路面：复查实际通行、材质与标高，继续自动维护";return true;
    }

    private static String name(BuildingRecord b) {
        if (!b.customName().isEmpty()) return b.customName();
        if (b.type().isHouse()) return "住宅";
        return switch (b.type()) {
            case WAGON -> "货车"; case WAREHOUSE -> "仓库"; case MARKET -> "市场";
            case FARM_FIELD -> "农田"; case MELON_FIELD -> "瓜田"; case PASTURE_AREA -> "牧场";
            case TREE_FARM -> "林场"; case HUNTING_LODGE -> "猎屋"; case FORESTER_LODGE -> "护林站";
            case FISHING_HUT -> "渔屋"; case SAWMILL -> "锯木场"; case TOOL_WORKSHOP -> "工具工坊";
            case TOWN_HALL -> "市政厅"; case SCHOOL -> "学校"; case RESTAURANT -> "餐厅";
            case TAVERN -> "酒馆"; case LEISURE_GARDEN -> "休闲花园"; default -> "建筑";
        };
    }

    /** Read-only diagnostics use current policy and stock, not a saved planner status. */
    public static List<String> blockers(ServerLevel level, SettlementRecord s) {
        var reasons = new ArrayList<String>(); var state = s.autoRoads();
        if (!state.enabled && !hasPending(s)) reasons.add("公共工程未开启：选择右侧“公共工程”，再选“开启”。");
        if (s.community().evacuation) reasons.add("集合避险已开启：解除避险后才允许修路。");
        boolean forced = com.hkin.exiledsettlement.debug.ForceWorkDebugService.enabled(level, s);
        if (!forced && s.citizens().stream().filter(CitizenRecord::canWork)
                .filter(c -> c.preferredWorksiteId() == null)
                .noneMatch(c -> CitizenWorkService.schedulePhase(level.getDayTime(), c) == CitizenWorkService.SchedulePhase.WORK)) {
            reasons.add("当前是居民休息/睡眠时段，等到白天工作时段（游戏内07:00—18:00）才施工。");
            if (!level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DAYLIGHT))
                reasons.add("日夜循环已关闭：时间不会自然进入工作时段。");
        }
        var workers = s.citizens().stream().filter(CitizenRecord::canWork)
                .filter(c -> CitizenWorkService.constructionCapable(c.profession()))
                .filter(c -> c.preferredWorksiteId() == null).toList();
        if (workers.isEmpty()) reasons.add("没有未锁定工地的成年工人：在职业/工地分配中留出工人，其他职业不参与公共施工。");
        else {
            if (workers.stream().allMatch(c -> c.workState() != CitizenWorkState.IDLE
                    && c.workState() != CitizenWorkState.TO_ROAD && c.workState() != CitizenWorkState.TO_ROAD_TOOL
                    || CommunityLifeService.handles(c))) reasons.add("工人正处理生活、建设、物流或其他任务，空闲后才会修路。");

        }
        return List.copyOf(reasons);
    }

    public static List<String> report(ServerLevel level, SettlementRecord s) {
        var state = s.autoRoads(); var lines = new ArrayList<String>();
        lines.add("—— 居民自动修路 ——");
        int spent = state.budgetDay == level.getDayTime() / 24000L ? state.spent : 0;
        lines.add("自动修路：" + (state.enabled ? "开启" : "关闭") + "；连续施工，今日已铺 " + spent + "格；无数量、长度或每日额度上限");
        if (com.hkin.exiledsettlement.debug.ForceWorkDebugService.enabled(level, s))
            lines.add("强制工作调试已开启：忽略休息、睡眠及生存需求中断；真实工具、材料、通行与保护条件继续生效。");
        lines.add("共享路网：复用已铺及待建干线，优先减少三格路面的新增面积并兼顾步行距离。");
        if(hasPending(s))lines.add("已确认建筑的必要施工通路优先安排；跨河由居民从可达岸真实架桥，桥面三格净宽，保留河道。关闭可选自动优化不取消已确认建筑的必要通路。");
        lines.add(com.hkin.exiledsettlement.terrain.TerrainConfig.ROAD_CURVE_RADIUS.get()<3
                ?"新路形：保留直段，缓弯已关闭；既有路线保持。":"新路形：直段与缓弯组合，空地不足或土方明显增加时保留原转角；既有路线保持。");
        int longest = state.routes.values().stream().mapToInt(r -> r.centerline.size()).max().orElse(0);
        long optimized = state.routes.values().stream().filter(r -> r.areaOptimized && !r.suppressed).count();
        long built = RoadSpaceData.get(level).decorationPositions().stream().filter(s::contains).count();
        lines.add("实际道路 " + built + "格；最长中心线 " + longest + "格；已优化支路 " + optimized + "条。");
        var blockers = blockers(level, s);
        if (blockers.isEmpty()) lines.add("基本开工条件已满足；等待工人领取任务，路线状态见下方。");
        else { lines.add("目前未开工/暂停的原因："); blockers.forEach(reason -> lines.add("• " + reason)); }
        lines.add("最近一次规划检查：" + state.status);
        lines.addAll(com.hkin.exiledsettlement.terrain.PublicGroundService.report(s));
        long repairs=state.routes.values().stream().filter(r->!r.suppressed && !r.p0Invalidated).mapToLong(r->r.repairs.cardinality()).sum();
        if(repairs>0)lines.add("已建道路待维修："+repairs+"格；按原标高独立修补，仍需材料、工具与实际到场。");
        if(com.hkin.exiledsettlement.terrain.PublicGroundService.reviewQueued(s))lines.add("变化已排入复查；普通地形仅核对附近既定地坪，当前进度保留。");
        var capacity=com.hkin.exiledsettlement.terrain.OptionalWorkBudget.snapshot(s);
        lines.add("可选道路与整地待办："+capacity.pending()+"/"+capacity.limit()+"格"+(capacity.full()?"；等待已有工程施工后继续发布":""));
        long revised=state.routes.values().stream().filter(r->r.p0Invalidated && !r.suppressed).count();
        if(revised>0)lines.add("新P0影响的旧路线："+revised+"条等待重新选线；已建道路保留。");
        for(var e:state.accessFailure.entrySet())if(s.blueprint(e.getKey())!=null)lines.add("待建施工通路："+s.blueprint(e.getKey()).anchor().toShortString()+"；"+e.getValue());
        lines.add("路线 " + state.routes.size() + " / 待施工 " + state.pending() + "；公共库存需提供铲子。生存、必要施工和物流优先。");
        for(var p:s.terrain().projects.values())if(BridgeRoadConnections.bridge(s,p.id)!=null){
            var ends=BridgeRoadConnections.entrances(s,p.id);
            long registered=p.accessSurfaces.stream().filter(RoadSpaceData.get(level)::contains).count();
            lines.add("桥面路网 "+registered+"/"+p.accessSurfaces.size()+"格；端一 "+ends.getFirst().toShortString()+"："+BridgeRoadConnections.portStatus(s,p.id,ends.getFirst())
                    +"；端二 "+ends.getLast().toShortString()+"："+BridgeRoadConnections.portStatus(s,p.id,ends.getLast()));
        }
        lines.add("装饰性模块禁放区同时禁止自动修路；明确撤销的路线不重建。普通挖除、换料会复查并继续维护；需要固定路线请使用手动画路功能。");
        for (var r : state.routes.values().stream().filter(r -> r.suppressed || r.progress < r.cells.size()).limit(8).toList()) {
            var b = s.building(r.building);
            lines.add((r.manualBridge?"玩家规划桥梁":BridgeRoadConnections.bridge(s,r.building)!=null?"桥梁端部接路":b == null ? s.blueprint(r.building)!=null?"待建建筑施工通路":"已移除建筑" : name(b)) + " @ " + r.entrance.toShortString()
                    + "：" + (r.progress+r.completed.get(r.progress,r.cells.size()).cardinality()) + "/" + r.cells.size() + "格；"
                    + (required(s,r)?r.accessConnected?"道路工作带已接通；路肩与路面继续完善；":"道路尚未接通；不阻挡可到场的建筑施工；":"") + r.status);
        }
        return lines;
    }
}
