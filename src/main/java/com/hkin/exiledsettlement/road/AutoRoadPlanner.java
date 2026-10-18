package com.hkin.exiledsettlement.road;

import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.*;

import java.util.*;

/** Resumable network search. A time slice pauses computation; it never rejects a long road. */
public final class AutoRoadPlanner {
    private static final Map<SettlementRecord, Plan> PLANS = new WeakHashMap<>();
    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final int NODES_PER_TICK = 128;
    private static final long SLICE_NANOS = 2_000_000L;
    private AutoRoadPlanner() { }

    public static boolean busy(SettlementRecord s) { return PLANS.containsKey(s); }
    public static com.google.gson.JsonObject progress(SettlementRecord s){var j=new com.google.gson.JsonObject();var p=PLANS.get(s);if(p==null)return j;
        j.addProperty("parent",p.building.toString());j.addProperty("committedRepair",p.committedRepair);j.addProperty("phase",p.phase);j.addProperty("checked",p.checked);j.addProperty("open",p.open.size());j.addProperty("costs",p.costs.size());return j;}
    public static com.google.gson.JsonObject progress(ServerLevel level,SettlementRecord s){
        var j=progress(s);var p=PLANS.get(s);if(p==null)return j;
        j.addProperty("root",p.root.toShortString());j.addProperty("end",p.end.toShortString());
        j.addProperty("rootCachedSafe",p.safe.get(p.root));j.addProperty("endCachedSafe",p.safe.get(p.end));
        j.addProperty("rootPermitted",RoadEarthworkService.permitted(level,s,p.root));
        j.addProperty("rootPlannable",RoadEarthworkService.plannable(level,s,p.root));
        j.addProperty("endPermitted",RoadEarthworkService.permitted(level,s,p.end));
        var closest=p.costs.keySet().stream().min(Comparator.comparingInt(k->k.pos.distManhattan(p.root))).orElse(null);
        if(closest!=null){j.addProperty("closest",closest.pos.toShortString());j.addProperty("closestDistance",closest.pos.distManhattan(p.root));}
        var first=p.open.peek();if(first!=null){j.addProperty("frontier",first.key.pos.toShortString());j.addProperty("frontierCost",first.cost);}
        var exits=new com.google.gson.JsonArray();
        for(var direction:SIDES){var at=p.root.relative(direction);var cell=new com.google.gson.JsonObject();cell.addProperty("at",at.toShortString());
            cell.addProperty("cached",p.safe.get(at));cell.addProperty("plannable",RoadEarthworkService.plannable(level,s,at));
            cell.addProperty("goal",com.hkin.exiledsettlement.terrain.PublicGroundService.targetAt(s,at));exits.add(cell);}
        j.add("rootExits",exits);return j;
    }
    public static void invalidate(SettlementRecord s) { PLANS.remove(s);s.autoRoads().planning=new CompoundTag(); }
    /** A public review may discard optional optimization, but never restart a necessary access search. */
    public static void invalidateOptional(SettlementRecord s){
        var plan=PLANS.get(s);
        if(plan!=null && (plan.committedRepair || AutoRoadService.necessaryPlan(s,plan.building) || BridgeRoadConnections.bridge(s,plan.building)!=null))return;
        if(plan==null && s.autoRoads().planning.hasUUID("Building") && (s.autoRoads().planning.getBoolean("CommittedRepair") || AutoRoadService.necessaryPlan(s,s.autoRoads().planning.getUUID("Building")) || BridgeRoadConnections.bridge(s,s.autoRoads().planning.getUUID("Building"))!=null))return;
        invalidate(s);
    }
    public static void invalidateChangedRoutes(SettlementRecord s,Set<String> keys){
        var plan=PLANS.get(s);
        if(plan!=null && keys.contains(plan.key) || plan==null && keys.contains(s.autoRoads().planning.getString("Key")))invalidate(s);
    }
    public static CompoundTag saved(AutoRoadState state){
        for(var e:PLANS.entrySet())if(e.getKey().autoRoads()==state)return e.getValue().save();
        return state.planning.copy();
    }
    /** Read-only harness inspection of the production grading algorithm. */
    public static List<BlockPos> previewGrade(ServerLevel level,SettlementRecord s,List<BlockPos> line){
        var planner=new RoadGradePlanner(line);
        while(!planner.done())planner.advance(level,s);
        return planner.result();
    }

    public static void begin(ServerLevel level, SettlementRecord s, BuildingRecord building,
                             BlockPos root, BlockPos end, String key) {
        PLANS.put(s, new Plan(level, s, building.id(), root, end, key));
        s.autoRoads().status = "正在比较共享路网与新增路面：" + end.toShortString();
    }
    public static void begin(ServerLevel level,SettlementRecord s,UUID parent,BlockPos root,BlockPos end,String key){PLANS.put(s,new Plan(level,s,parent,root,end,key));}
    public static void beginCommittedRepair(ServerLevel level,SettlementRecord s,BuildingRecord building,BlockPos root,BlockPos end,String key){
        beginCommittedRepair(level,s,building.id(),root,end,key);
    }
    public static void beginCommittedRepair(ServerLevel level,SettlementRecord s,UUID parent,BlockPos root,BlockPos end,String key){
        var plan=new Plan(level,s,parent,root,end,key);plan.committedRepair=true;PLANS.put(s,plan);
        s.autoRoads().status="正在核对旧路与已发布共同地面冲突，保留实际施工与发布额度";
    }

    public static void tickLevel(ServerLevel level) {
        long deadline = System.nanoTime() + SLICE_NANOS;
        var data = SettlementSavedData.get(level);
        for (var s : data.settlements()) {
            var plan = PLANS.get(s);
            if(plan==null && !s.autoRoads().planning.isEmpty()) {
                var tag=s.autoRoads().planning;s.autoRoads().planning=new CompoundTag();
                try {plan=Plan.restore(level,s,tag);PLANS.put(s,plan);}catch(RuntimeException e){s.autoRoads().status="施工通路勘察记录失效，按当前地形重勘察";}
            }
            if (plan == null || !AutoRoadService.allowed(s)) continue;
            if(s.blueprint(plan.building)==null && s.blueprints().stream().anyMatch(b->AutoRoadService.needsAccess(s,b)
                    && s.autoRoads().routes.values().stream().noneMatch(r->r.building.equals(b.id())) && level.getGameTime()>=s.autoRoads().accessRetry.getOrDefault(b.id(),0L))) {
                invalidate(s);s.autoRoads().status="必要施工通路优先，暂缓可选旧路复查";continue;
            }
            if(com.hkin.exiledsettlement.terrain.PublicGroundService.routePlanningPaused(s)
                    && BridgeRoadConnections.bridge(s,plan.building)==null
                    && !AutoRoadService.necessaryPlan(s,plan.building) && !(plan.committedRepair
                    && !com.hkin.exiledsettlement.terrain.PublicGroundService.planning(s)))continue;
            var building = s.building(plan.building);
            if (!AutoRoadService.parentMatches(level,s,plan.building,plan.end)) {
                PLANS.remove(s); s.autoRoads().status = "入口已改变，重新选择连接"; continue;
            }
            // Optional grading must also compare alternatives to its own old
            // committed soil. Only this mathematical/acceptance scope is relaxed;
            // resident publication still waits for the new shared transaction.
            try(var scope=RoadEarthworkService.planningScope(s,true)){
            int slices=Math.floorMod(level.getDayTime(),24000L)>=13000?com.hkin.exiledsettlement.terrain.TerrainConfig.NIGHT_PLANNING_SLICES.get():1;
            for(int slice=0;slice<slices && !plan.finished && System.nanoTime()<deadline;slice++)plan.advance(level,s,deadline);
            if (plan.finished) {
                PLANS.remove(s);
                if (plan.expanded != null) {
                    var old = s.autoRoads().routes.get(plan.key);
                    if (old == null || !old.suppressed) {
                        var job = new AutoRoadState.Route(plan.building, plan.end, List.copyOf(plan.expanded));
                        job.sourceBridges.addAll(plan.sourceBridges(s));
                        job.centerline = List.copyOf(plan.line); job.width = 3; job.areaOptimized = true;
                        job.status="直段与缓弯已规划：缓弯"+(plan.curveFallback?0:plan.curves.rounded())+"处，等待居民真实施工";
                        if(!AdaptiveRoadService.accept(level,s,plan.key,job,plan.committedRepair)){data.touch();continue;}
                        var retry=BridgeRoadConnections.retryKey(s,plan.building,plan.end);
                        s.autoRoads().accessFailure.remove(retry);s.autoRoads().accessRetry.remove(retry);
                        var queuedCells=plan.queued;
                        long added = job.cells.stream().filter(p -> !RoadSpaceData.get(level).contains(p)
                                && !queuedCells.contains(p)).count();
                        s.autoRoads().status = "已规划共享支路：中心线" + job.centerline.size()
                                + "格，新增路面" + added + "格，缓弯"+(plan.curveFallback?0:plan.curves.rounded())+"处；等待居民施工";
                    }
                } else {s.autoRoads().status = "入口 " + plan.end.toShortString()
                        + " 暂无安全的已加载通路；"+plan.failure+"；地形或区块就绪后重新规划";var retry=BridgeRoadConnections.retryKey(s,plan.building,plan.end);s.autoRoads().accessRetry.put(retry,level.getGameTime()+400);s.autoRoads().accessFailure.put(retry,plan.failure);}
                data.touch();
            }
            if (System.nanoTime() >= deadline) break;
            }
        }
    }

    private record Key(BlockPos pos, Direction heading) { }
    private record Node(Key key, double cost, double estimate) { }

    static final class Plan {
        final UUID building;
        final BlockPos root, end;
        final String key;
        final Set<BlockPos> queued = new HashSet<>(), network = new HashSet<>();
        final Set<BlockPos> suppressed = new HashSet<>();
        final Set<BlockPos> replacing=new HashSet<>();
        final Deque<BlockPos> flood = new ArrayDeque<>();
        final Map<BlockPos,BlockPos> networkParents=new HashMap<>();
        Set<UUID> sourceBridges(SettlementRecord s){
            var ids=new LinkedHashSet<UUID>();var visited=new HashSet<BlockPos>();
            for(BlockPos at=line.getFirst();at!=null && visited.add(at);at=networkParents.get(at)) {
                var bridge=BridgeService.owner(s,at);if(bridge!=null && bridge.accessSurfaces.contains(at))ids.add(bridge.id);
            }
            return ids;
        }
        final Map<BlockPos, Boolean> safe = new HashMap<>();
        final Map<BlockPos, BlockPos> surfaces = new HashMap<>();
        final Map<Key, Double> costs = new HashMap<>();
        final Map<Key, Key> parents = new HashMap<>();
        final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::estimate)
                .thenComparingDouble(Node::cost).thenComparingLong(n -> n.key.pos.asLong())
                .thenComparingInt(n -> n.key.heading == null ? -1 : n.key.heading.ordinal()));
        List<BlockPos> line;
        List<BlockPos> unsmoothed;
        boolean curveFallback;
        RoadCurvePlanner curves;
        RoadGradePlanner grade;
        LinkedHashSet<BlockPos> expanded;
        final Map<Long,Integer> expandedHeights=new HashMap<>();
        int phase, expandedIndex, checked, minX, maxX, minY, maxY, minZ, maxZ;
        boolean finished;
        boolean committedRepair;
        String failure="没有可连通的已加载三格走廊";

        static CompoundTag key(Key k){var t=new CompoundTag();t.putLong("At",k.pos.asLong());t.putInt("Heading",k.heading==null?-1:k.heading.ordinal());return t;}
        static Key key(CompoundTag t){int d=t.getInt("Heading");return new Key(BlockPos.of(t.getLong("At")),d<0?null:Direction.values()[d]);}
        CompoundTag save(){
            var t=new CompoundTag();t.putInt("Version",3);t.putInt("WidthPolicy",3);t.putBoolean("CommittedRepair",committedRepair);t.putUUID("Building",building);t.putLong("Root",root.asLong());t.putLong("End",end.asLong());t.putString("Key",key);t.putInt("Phase",phase);t.putInt("Checked",checked);
            var links=new long[networkParents.size()*2];int offset=0;for(var e:networkParents.entrySet()){links[offset++]=e.getKey().asLong();links[offset++]=e.getValue().asLong();}t.putLongArray("NetworkParents",links);
            t.putLongArray("Network",network.stream().mapToLong(BlockPos::asLong).toArray());t.putLongArray("Flood",flood.stream().mapToLong(BlockPos::asLong).toArray());
            var entries=new ListTag();costs.forEach((k,c)->{var a=key(k);a.putDouble("Cost",c);if(parents.containsKey(k))a.put("Parent",key(parents.get(k)));entries.add(a);});t.put("Costs",entries);
            var nodes=new ListTag();for(var n:open){var a=key(n.key);a.putDouble("Cost",n.cost);a.putDouble("Estimate",n.estimate);nodes.add(a);}t.put("Open",nodes);
            if(line!=null)t.putLongArray("Line",line.stream().mapToLong(BlockPos::asLong).toArray());
            if(unsmoothed!=null)t.putLongArray("Unsmoothed",unsmoothed.stream().mapToLong(BlockPos::asLong).toArray());return t;
        }
        static Plan restore(ServerLevel level,SettlementRecord s,CompoundTag t){
            if(t.getInt("Version")!=3)throw new IllegalArgumentException("Route planning algorithm changed");
            var p=new Plan(level,s,t.getUUID("Building"),BlockPos.of(t.getLong("Root")),BlockPos.of(t.getLong("End")),t.getString("Key"));
            p.committedRepair=t.getBoolean("CommittedRepair");
            if(BridgeRoadConnections.bridge(s,p.building)!=null){
                var root=BridgeRoadConnections.root(level,s,p.building,p.end);
                if(root!=null&&!root.equals(p.root)){
                    var resumed=new Plan(level,s,p.building,root,p.end,p.key);resumed.committedRepair=p.committedRepair;
                    return resumed; // Only restart the mathematical search; paid routes and orders stay intact.
                }
            }
            p.network.clear();p.flood.clear();for(long at:t.getLongArray("Network"))p.network.add(BlockPos.of(at));for(long at:t.getLongArray("Flood"))p.flood.add(BlockPos.of(at));
            var links=t.getLongArray("NetworkParents");for(int i=0;i+1<links.length;i+=2)p.networkParents.put(BlockPos.of(links[i]),BlockPos.of(links[i+1]));
            p.minX=p.network.stream().mapToInt(BlockPos::getX).min().orElse(p.root.getX());p.maxX=p.network.stream().mapToInt(BlockPos::getX).max().orElse(p.root.getX());
            p.minZ=p.network.stream().mapToInt(BlockPos::getZ).min().orElse(p.root.getZ());p.maxZ=p.network.stream().mapToInt(BlockPos::getZ).max().orElse(p.root.getZ());
            p.minY=p.network.stream().mapToInt(BlockPos::getY).min().orElse(p.root.getY());p.maxY=p.network.stream().mapToInt(BlockPos::getY).max().orElse(p.root.getY());
            var entries=t.getList("Costs",Tag.TAG_COMPOUND);for(int i=0;i<entries.size();i++){var a=entries.getCompound(i);var k=key(a);p.costs.put(k,a.getDouble("Cost"));if(a.contains("Parent"))p.parents.put(k,key(a.getCompound("Parent")));}
            var nodes=t.getList("Open",Tag.TAG_COMPOUND);for(int i=0;i<nodes.size();i++){var a=nodes.getCompound(i);var k=key(a);double cost=a.getDouble("Cost");p.open.add(new Node(k,cost,cost+p.heuristic(k.pos)));}
            p.phase=t.getInt("Phase");p.checked=t.getInt("Checked");
            // Reopen previously visited states once when loading the corrected width policy.
            // Keep costs, parents and the paid world; newly admitted slope edges must be tried.
            if(p.phase==1 && t.getInt("WidthPolicy")<3){
                p.open.clear();p.costs.forEach((k,c)->p.open.add(new Node(k,c,c+p.heuristic(k.pos))));
            }
            if(p.phase>=2){p.line=Arrays.stream(t.getLongArray("Line")).mapToObj(BlockPos::of).toList();p.unsmoothed=Arrays.stream(t.getLongArray("Unsmoothed")).mapToObj(BlockPos::of).toList();
                var paid=new HashSet<BlockPos>(p.queued);paid.addAll(p.network);p.curves=new RoadCurvePlanner(p.line,paid,p.suppressed,p.replacing);
                if(p.phase==2)p.phase=2;else {p.grade=new RoadGradePlanner(p.line);p.phase=3;}}
            return p;
        }

        Plan(ServerLevel level, SettlementRecord s, UUID building, BlockPos root, BlockPos end, String key) {
            this.building = building; this.root = root.immutable(); this.end = end.immutable(); this.key = key;
            var old=s.autoRoads().routes.get(key);if(old!=null && !old.suppressed)replacing.addAll(old.cells);
            for (var entry : s.autoRoads().routes.entrySet()) {
                var road = entry.getValue(); var b = s.building(road.building);
                if (road.suppressed && road.mergedInto==null) suppressed.addAll(road.cells);
                if (!entry.getKey().equals(key) && !road.suppressed
                        && (road.progress >= road.cells.size() || AutoRoadService.parentValid(level,s,road))) queued.addAll(road.cells);
            }
            network.add(this.root); flood.add(this.root);
            minX = maxX = root.getX(); minZ = maxZ = root.getZ();
            minY = maxY = root.getY();
        }

        boolean safe(ServerLevel level, SettlementRecord s, BlockPos p) {
            if(!BridgeRoadConnections.side(s,building,end,p))return false;
            if (suppressed.contains(p) && !RoadSpaceData.get(level).contains(p)) return false;
            return safe.computeIfAbsent(p.immutable(), at -> RoadEarthworkService.plannable(level, s, at));
        }

        BlockPos surface(ServerLevel level, SettlementRecord s, BlockPos p) {
            if (surfaces.containsKey(p)) return surfaces.get(p);
            BlockPos result = null;
            double effort=Double.MAX_VALUE;
            for (int dy : new int[]{0, 1, -1}) if (safe(level, s, p.offset(0, dy, 0))) {
                var at=p.offset(0,dy,0);double next=RoadEarthworkService.effort(level,s,at);
                if(next<effort){result=at.immutable();effort=next;}
            }
            surfaces.put(p.immutable(), result); return result;
        }

        boolean nearNetwork(BlockPos p) {
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
                if (Math.abs(dx) + Math.abs(dz) <= 2)
                    for (int dy = -1; dy <= 1; dy++) if (network.contains(p.offset(dx, dy, dz))) return true;
            return false;
        }

        boolean wide(ServerLevel level, SettlementRecord s, BlockPos p,Direction heading,boolean corner) {
            if(BridgeService.span(level,s,p))return true;
            if (AdaptiveRoadService.paidPassage(level,s,p)) return true;
            if (p.distManhattan(end) <= 2 || nearNetwork(p)) return true;
            // Straight rows need a same-height cross-section, not a flat 3x3 square.
            // The latter incorrectly disallows the adjacent higher/lower row of a slope.
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if(!corner && (heading.getAxis()==Direction.Axis.X?dx!=0:dz!=0))continue;
                if (!safe(level, s, p.offset(dx, 0, dz))) return false;
            }
            return true;
        }

        Set<BlockPos> cross(ServerLevel level, SettlementRecord s, BlockPos p, Direction heading, boolean corner) {
            var cells = new HashSet<BlockPos>(); cells.add(p);
            if(BridgeService.span(level,s,p)){cells.addAll(BridgeRoadConnections.deckRow(s,p));return cells;}
            if (heading == null) return cells;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (!corner && (heading.getAxis() == Direction.Axis.X ? dx != 0 : dz != 0)) continue;
                var at = surface(level, s, p.offset(dx, 0, dz)); if (at != null) cells.add(at);
            }
            return cells;
        }

        double heuristic(BlockPos p) {
            // Guide the first feasible corridor toward the network at the usual three-wide
            // construction cost. This is an estimate, not a proof of global optimality.
            // Acceptance still compares actual area/earthwork and validates every landing.
            // Weighted guidance finds a usable first corridor promptly. Periodic adaptive
            // review still compares real costs; neither a time slice nor this weight rejects a route.
            return 8D * (3.08D * (Math.max(0, Math.max(minX - p.getX(), p.getX() - maxX))
                    + Math.max(0, Math.max(minZ - p.getZ(), p.getZ() - maxZ)))
                    + 12D * Math.max(0,Math.max(minY-p.getY(),p.getY()-maxY)));
        }

        void advance(ServerLevel level, SettlementRecord s, long deadline) {
            safe.clear();surfaces.clear(); // Never preserve tree/world classifications across slices or restart.
            var roads = RoadSpaceData.get(level);
            for (int work = 0; work < NODES_PER_TICK && !finished
                    && (work == 0 || System.nanoTime() < deadline); work++) {
                checked++;
                if (phase == 0) {
                    if (flood.isEmpty()) {
                        if(!com.hkin.exiledsettlement.citizen.TreeSurvey.prepareArea(level,end,p->RoadEarthworkService.reservedStructure(s,p))){s.autoRoads().status="勘察挡路树，分片继续";return;}
                        if (!safe(level, s, end)) { finished = true; break; }
                        var first = new Key(end, null); costs.put(first, 0D);
                        open.add(new Node(first, 0D, heuristic(end))); phase = 1; continue;
                    }
                    if(!com.hkin.exiledsettlement.citizen.TreeSurvey.prepareArea(level,flood.getFirst(),p->RoadEarthworkService.reservedStructure(s,p))){s.autoRoads().status="勘察挡路树，分片继续";return;}
                    var at = flood.removeFirst();
                    for (var side : SIDES) for (int dy : new int[]{0, 1, -1}) {
                        var next = at.relative(side).offset(0, dy, 0);
                        if (network.contains(next) || !(roads.contains(next) && (!replacing.contains(next) || roads.manual(next)) || queued.contains(next))
                                || !safe(level, s, next)) continue;
                        // Queued corridors reduce the new construction cost,
                        // but only actual supported surfaces extend the source
                        // network. A new consumer then includes unfinished
                        // shared crossings and acquires their real dependencies.
                        if(!AutoRoadService.safeCell(level,s,next))continue;
                        if(queued.contains(next) && s.autoRoads().routes.values().stream()
                                .noneMatch(r->!r.suppressed && r.containsCell(next) && RoadEarthworkService.surfaceReady(level,s,r,next)))continue;
                        network.add(next);networkParents.put(next,at); flood.addLast(next);
                        minX = Math.min(minX, next.getX()); maxX = Math.max(maxX, next.getX());
                        minZ = Math.min(minZ, next.getZ()); maxZ = Math.max(maxZ, next.getZ());
                        minY = Math.min(minY,next.getY()); maxY = Math.max(maxY,next.getY());
                    }
                } else if (phase == 1) {
                    if (open.isEmpty()) { finished = true; break; }
                    if(!com.hkin.exiledsettlement.citizen.TreeSurvey.prepareArea(level,open.peek().key.pos,p->RoadEarthworkService.reservedStructure(s,p))){s.autoRoads().status="勘察挡路树，分片继续";return;}
                    var current = open.remove(); var at = current.key.pos;
                    if (current.cost > costs.getOrDefault(current.key, Double.MAX_VALUE)) continue;
                    if (network.contains(at)) {
                        line = new ArrayList<>();
                        for (Key k = current.key; k != null; k = parents.get(k)) line.add(k.pos);
                        unsmoothed=List.copyOf(line);
                        var paid=new HashSet<BlockPos>(queued);paid.addAll(network);
                        curves=new RoadCurvePlanner(line,paid,suppressed,replacing);phase=2;continue;
                    }
                    for (var side : SIDES) for(int dy:new int[]{0,1,-1}) {
                        if (current.key.heading != null && side == current.key.heading.getOpposite()) continue;
                        var next = at.relative(side).offset(0,dy,0);
                        var fromBridge=BridgeService.owner(s,at);var toBridge=BridgeService.owner(s,next);
                        if(fromBridge!=null && fromBridge.playerBridge!=null && side.getAxis()!=(fromBridge.playerBridge.alongX?Direction.Axis.X:Direction.Axis.Z)
                                || toBridge!=null && toBridge.playerBridge!=null && side.getAxis()!=(toBridge.playerBridge.alongX?Direction.Axis.X:Direction.Axis.Z))continue;
                        boolean corner = current.key.heading != null && current.key.heading.getAxis() != side.getAxis();
                        if (!safe(level,s,next) || !wide(level, s, next,side,false)
                                || corner && !wide(level,s,at,side,true)) continue;
                        boolean bridge=BridgeService.span(level,s,at) || BridgeService.span(level,s,next);
                        if(bridge && BridgeService.span(level,s,at) && BridgeService.span(level,s,next) && dy!=0)continue;
                        if(bridge && current.key.heading!=null && current.key.heading.getAxis()!=side.getAxis())continue;
                        if(corner && (dy!=0 || parents.containsKey(current.key) && parents.get(current.key).pos.getY()!=at.getY()))continue;
                        var extra = cross(level, s, at, side, corner);
                        extra.addAll(cross(level, s, next, side, false));
                        extra.removeAll(cross(level, s, at, current.key.heading, false));
                        long newArea = extra.stream().filter(p -> (!roads.contains(p) || replacing.contains(p) && !roads.manual(p) && !AdaptiveRoadService.sharedAt(s,p)) && !queued.contains(p)).count();
                        double bridgeMaterials=extra.stream().filter(p->!roads.contains(p) && !queued.contains(p) && BridgeService.span(level,s,p)).count()*3D;
                        double cost = current.cost + newArea + bridgeMaterials + .08D + Math.abs(at.getY() - next.getY()) * 12D+RoadEarthworkService.effort(level,s,next)*.5D;
                        var k = new Key(next, side);
                        if (cost >= costs.getOrDefault(k, Double.MAX_VALUE)) continue;
                        costs.put(k, cost); parents.put(k, current.key);
                        open.add(new Node(k, cost, cost + heuristic(next)));
                    }
                } else if(phase==2) {
                    if(line.stream().anyMatch(p->BridgeService.span(level,s,p))){grade=new RoadGradePlanner(line);phase=3;continue;}
                    curves.advance(level,s);
                    if(curves.done()){line=curves.result();grade=new RoadGradePlanner(line);phase=3;}
                } else if(phase==3) {
                    grade.advance(level,s,deadline);
                    if(grade.done()){line=grade.result();expanded=new LinkedHashSet<>();phase=4;}
                } else {
                    if (expandedIndex == line.size()) { finished = true; break; }
                    var cells = AutoRoadService.corridorCell(level, s, line, expandedIndex++);
                    boolean conflictingHeight=cells!=null && cells.stream().anyMatch(p->{var y=expandedHeights.get(BlockPos.asLong(p.getX(),0,p.getZ()));return y!=null && y!=p.getY();});
                    if (cells == null || conflictingHeight || cells.stream().anyMatch(p -> suppressed.contains(p)
                            && !RoadSpaceData.get(level).contains(p))) {
                        if(!curveFallback && curves.rounded()>0) {
                            line=unsmoothed;grade=new RoadGradePlanner(line);expanded.clear();expandedHeights.clear();expandedIndex=0;curveFallback=true;phase=3;continue;
                        }
                        failure=(conflictingHeight?"转折走廊发生重叠高程冲突":"三格走廊被地形或保护边界阻挡")+" @ "+line.get(expandedIndex-1).toShortString();
                        expanded = null; finished = true; break;
                    }
                    expanded.addAll(cells);
                    for(var p:cells)expandedHeights.put(BlockPos.asLong(p.getX(),0,p.getZ()),p.getY());
                }
            }
            if (!finished) s.autoRoads().status = (phase == 0 ? "整理共享路网" : phase == 1 ? "比较新增路面面积" : phase==2?"组合直段与地形缓弯":phase==3?"平缓道路纵向标高":"核对三格通行带")
                    + "：已处理" + checked + "个节点，分批继续";
        }
    }
}

