package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.region.ProductionRegionService;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.*;

/** Surveys developed public ground. Only resident construction performs world edits. */
public final class PublicGroundService {
    public static final int HEIGHT_VERSION=10;
    private PublicGroundService() { }
    public static void requestReview(SettlementRecord s){
        if(com.hkin.exiledsettlement.debug.SaveAcceptanceService.enabled()){
            var w=s.terrain().publicGround;
            String caller=StackWalker.getInstance().walk(frames->frames.skip(1).findFirst().map(f->f.getClassName()+"."+f.getMethodName()).orElse("unknown"));
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("Public ground review: settlement={}, phase={}, cursor={}, caller={}",s.id(),w.phase,w.cursor,caller);
        }
        AutoRoadPlanner.invalidateOptional(s);PublicRoadIntegration.reset(s);var w=s.terrain().publicGround;w.signature=Long.MIN_VALUE;w.nextReview=0;w.phase=0;w.roadsIntegrated=false;
        w.releasedConstraintReview=false;
    }
    /** Removing a temporary constraint does not invalidate its already safe solved plane. */
    public static void queueReleasedConstraintReview(SettlementRecord s){
        var w=s.terrain().publicGround;
        if(w.phase==0)w.signature=Long.MIN_VALUE;
        else w.releasedConstraintReview=true;
    }
    /** A local edit queues a fresh survey after this transaction has published.
     * Keep the saved cursor: repeated player edits must not starve real work. */
    public static void queueSurfaceReview(SettlementRecord s,BlockPos at,boolean road){
        var w=s.terrain().publicGround;
        if(!road && !bounds(w,at.getX(),at.getZ()) && s.terrain().publicPlane(at)==null)return;
        // Ordinary edits change actual blocks, not the approved target heights.
        // Recheck only this column and its immediate support/connection neighbors.
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            long k=key(at.getX()+dx,at.getZ()+dz);
            if(w.goals.containsKey(k))w.dirtyColumns.add(k);
        }
        if(w.phase==0 && !w.dirtyColumns.isEmpty())w.nextReview=0;
    }
    public static boolean reviewQueued(SettlementRecord s){var w=s.terrain().publicGround;return w.releasedConstraintReview || !w.dirtyColumns.isEmpty();}
    private record Rect(int x1,int z1,int x2,int z2,int margin) {
        boolean contains(int x,int z){return x>=x1-margin && x<=x2+margin && z>=z1-margin && z<=z2+margin;}
    }
    public static final class State {
        public int heightVersion=HEIGHT_VERSION;
        public long signature,nextReview,cursor;
        // The building constraints captured by begin(), independent of mutable road rows.
        private long constraintSignature=Long.MIN_VALUE;
        public int phase,minX,minZ,maxX,maxZ,representative,waterCursor,planCursor;
        // Runtime deadline only: saved cursors describe work, never wall-clock time.
        long deadline=Long.MAX_VALUE;
        boolean timeFor(int work){return work==0 || System.nanoTime()<deadline;}
        public boolean capacityPaused;
        public String status="等待勘察聚落公共空地";
        final List<Rect> footprints=new ArrayList<>();
        private Set<Long> footprintColumns;
        private boolean footprintAt(int x,int z){
            if(footprintColumns==null){
                footprintColumns=new HashSet<>();
                for(var r:footprints)for(int px=r.x1-r.margin;px<=r.x2+r.margin;px++)
                    for(int pz=r.z1-r.margin;pz<=r.z2+r.margin;pz++)footprintColumns.add(key(px,pz));
            }
            return footprintColumns.contains(key(x,z));
        }
        final Map<Long,Integer> anchors=new LinkedHashMap<>(),goals=new LinkedHashMap<>();
        final Map<Long,Integer> anchorRadii=new LinkedHashMap<>(),baselines=new LinkedHashMap<>();
        final List<Rect> buildings=new ArrayList<>();
        final Map<Long,Long> nearest=new LinkedHashMap<>();
        final Map<Long,Long> nearestRoad=new LinkedHashMap<>();
        final Map<Long,Integer> roadDistances=new LinkedHashMap<>();
        final Map<Long,Double> roadHeights=new LinkedHashMap<>();
        final Map<Long,Integer> distances=new LinkedHashMap<>(),barriers=new LinkedHashMap<>();
        final Map<Long,Double> heights=new LinkedHashMap<>(),nextHeights=new LinkedHashMap<>();
        final Map<Long,Double> lower=new LinkedHashMap<>(),upper=new LinkedHashMap<>();
        final Set<Long> queued=new HashSet<>();
        int stableSweeps;
        boolean sourcesFinal,roadsIntegrated,releasedConstraintReview;
        int roadCursor;
        final Set<Long> integratedRoads=new LinkedHashSet<>(),roadGeneratedAnchors=new LinkedHashSet<>();
        double sweepChange;
        private List<Long> iterationKeys=List.of();
        private List<Long> publicationKeys=List.of();
        final Set<Long> dirtyColumns=new LinkedHashSet<>();
        boolean localPublication;
        List<Long> publicationKeys(){
            if(localPublication)return publicationKeys;
            if(publicationKeys.size()!=goals.size()){
                // Preserve an in-progress old-order prefix; only unseen columns change order.
                var original=keys();int prefix=Math.clamp(planCursor,0,original.size());
                var ordered=new ArrayList<Long>(original.subList(0,prefix));
                ordered.addAll(PublicWorkAreas.interleave(original.subList(prefix,original.size())));
                publicationKeys=List.copyOf(ordered);
            }
            return publicationKeys;
        }
        List<Long> keys(){if(iterationKeys.size()!=goals.size())iterationKeys=List.copyOf(goals.keySet());return iterationKeys;}
        final Set<Long> mask=new LinkedHashSet<>(),outside=new LinkedHashSet<>(),wet=new LinkedHashSet<>(),openWater=new LinkedHashSet<>(),planned=new LinkedHashSet<>();
        final Set<Long> deferred=new LinkedHashSet<>();
        final ArrayDeque<Long> queue=new ArrayDeque<>();
        final Set<Long> seams=new LinkedHashSet<>(),seenSeams=new LinkedHashSet<>();
        final Map<Long,Direction> stairs=new LinkedHashMap<>();
        long bestSeam;
        int bestDistance=Integer.MAX_VALUE;
        final Map<String,Integer> skipped=new LinkedHashMap<>();
        public CompoundTag save(){
            var t=new CompoundTag();t.putInt("Version",3);t.putInt("HeightVersion",heightVersion);t.put("AnchorRadii",planes(anchorRadii));t.put("Baselines",planes(baselines));t.putLong("Signature",signature);t.putLong("NextReview",nextReview);t.putLong("Cursor",cursor);
            t.putLong("ConstraintSignature",constraintSignature);
            t.put("Barriers",planes(barriers));t.put("Distances",planes(distances));t.putInt("StableSweeps",stableSweeps);t.putDouble("SweepChange",sweepChange);
            var ns=new ListTag();nearest.forEach((k,v)->{var a=new CompoundTag();a.putLong("Column",k);a.putLong("Anchor",v);ns.add(a);});t.put("Nearest",ns);
            var roads=new ListTag();nearestRoad.forEach((k,v)->{var a=new CompoundTag();a.putLong("Column",k);a.putLong("Anchor",v);roads.add(a);});t.put("NearestRoad",roads);
            t.put("RoadDistances",planes(roadDistances));t.put("RoadHeights",heightTags(roadHeights));
            t.putBoolean("SourcesFinal",sourcesFinal);t.putBoolean("RoadsIntegrated",roadsIntegrated);t.putInt("RoadCursor",roadCursor);t.putLongArray("IntegratedRoads",array(integratedRoads));t.putLongArray("RoadGeneratedAnchors",array(roadGeneratedAnchors));
            t.putBoolean("ReleasedConstraintReview",releasedConstraintReview);
            t.putLongArray("PublicationKeys",array(publicationKeys));
            t.putLongArray("DirtyColumns",array(dirtyColumns));t.putBoolean("LocalPublication",localPublication);
            t.put("Heights",heightTags(heights));t.put("NextHeights",heightTags(nextHeights));
            t.put("Lower",heightTags(lower));t.put("Upper",heightTags(upper));
            var bs=new ListTag();for(var r:buildings){var a=new CompoundTag();a.putIntArray("Bounds",new int[]{r.x1,r.z1,r.x2,r.z2});bs.add(a);}t.put("Buildings",bs);
            t.putInt("Phase",phase);t.putInt("MinX",minX);t.putInt("MinZ",minZ);t.putInt("MaxX",maxX);t.putInt("MaxZ",maxZ);t.putInt("Representative",representative);t.putInt("WaterCursor",waterCursor);t.putInt("PlanCursor",planCursor);t.putString("Status",status);
            var rs=new ListTag();for(var r:footprints){var a=new CompoundTag();a.putIntArray("Bounds",new int[]{r.x1,r.z1,r.x2,r.z2,r.margin});rs.add(a);}t.put("Footprints",rs);
            t.put("Anchors",planes(anchors));t.put("Goals",planes(goals));t.putLongArray("Mask",array(mask));t.putLongArray("Outside",array(outside));t.putLongArray("Wet",array(wet));t.putLongArray("OpenWater",array(openWater));t.putLongArray("Planned",array(planned));t.putLongArray("Queue",array(queue));
            t.putLongArray("Seams",array(seams));t.putLongArray("SeenSeams",array(seenSeams));t.putLong("BestSeam",bestSeam);t.putInt("BestDistance",bestDistance);
            t.putLongArray("Deferred",array(deferred));
            var stairTags=new ListTag();stairs.forEach((k,d)->{var a=new CompoundTag();a.putLong("Column",k);a.putString("Facing",d.getName());stairTags.add(a);});t.put("Stairs",stairTags);
            var errors=new CompoundTag();skipped.forEach(errors::putInt);t.put("Skipped",errors);return t;
        }
        public void load(CompoundTag t){
            // Older snapshots may have acknowledged a building without capturing its anchors.
            constraintSignature=t.contains("ConstraintSignature")?t.getLong("ConstraintSignature"):Long.MIN_VALUE;
            heightVersion=t.contains("HeightVersion")?t.getInt("HeightVersion"):1;readPlanes(t.getList("AnchorRadii",10),anchorRadii);readPlanes(t.getList("Baselines",10),baselines);
            readPlanes(t.getList("Barriers",10),barriers);readPlanes(t.getList("Distances",10),distances);stableSweeps=t.getInt("StableSweeps");sweepChange=t.getDouble("SweepChange");
            var ns=t.getList("Nearest",10);for(int i=0;i<ns.size();i++){var a=ns.getCompound(i);nearest.put(a.getLong("Column"),a.getLong("Anchor"));}
            var roads=t.getList("NearestRoad",10);for(int i=0;i<roads.size();i++){var a=roads.getCompound(i);nearestRoad.put(a.getLong("Column"),a.getLong("Anchor"));}
            readPlanes(t.getList("RoadDistances",10),roadDistances);readHeights(t.getList("RoadHeights",10),roadHeights);
            sourcesFinal=t.getBoolean("SourcesFinal");roadsIntegrated=t.getBoolean("RoadsIntegrated");roadCursor=t.getInt("RoadCursor");readSet(t,"IntegratedRoads",integratedRoads);readSet(t,"RoadGeneratedAnchors",roadGeneratedAnchors);
            releasedConstraintReview=t.getBoolean("ReleasedConstraintReview");
            publicationKeys=java.util.Arrays.stream(t.getLongArray("PublicationKeys")).boxed().toList();
            readSet(t,"DirtyColumns",dirtyColumns);localPublication=t.getBoolean("LocalPublication");
            readHeights(t.getList("Heights",10),heights);readHeights(t.getList("NextHeights",10),nextHeights);
            readHeights(t.getList("Lower",10),lower);readHeights(t.getList("Upper",10),upper);
            var bs=t.getList("Buildings",10);for(int i=0;i<bs.size();i++){var a=bs.getCompound(i).getIntArray("Bounds");if(a.length==4)buildings.add(new Rect(a[0],a[1],a[2],a[3],0));}
            signature=t.getLong("Signature");nextReview=t.getLong("NextReview");cursor=t.getLong("Cursor");phase=t.getInt("Phase");minX=t.getInt("MinX");minZ=t.getInt("MinZ");maxX=t.getInt("MaxX");maxZ=t.getInt("MaxZ");representative=t.getInt("Representative");waterCursor=t.getInt("WaterCursor");planCursor=t.getInt("PlanCursor");if(t.contains("Status"))status=t.getString("Status");
            if(!t.contains("ConstraintSignature"))nextReview=0;
            var list=t.getList("Footprints",10);for(int i=0;i<list.size();i++){var a=list.getCompound(i).getIntArray("Bounds");if(a.length==5)footprints.add(new Rect(a[0],a[1],a[2],a[3],a[4]));}
            readPlanes(t.getList("Anchors",10),anchors);readPlanes(t.getList("Goals",10),goals);readSet(t,"Mask",mask);readSet(t,"Outside",outside);readSet(t,"Wet",wet);readSet(t,"OpenWater",openWater);readSet(t,"Planned",planned);for(long k:t.getLongArray("Queue"))queue.add(k);
            var errors=t.getCompound("Skipped");for(var k:errors.getAllKeys())skipped.put(k,errors.getInt(k));
            readSet(t,"Seams",seams);readSet(t,"SeenSeams",seenSeams);bestSeam=t.getLong("BestSeam");bestDistance=t.contains("BestDistance")?t.getInt("BestDistance"):Integer.MAX_VALUE;
            readSet(t,"Deferred",deferred);
            queued.addAll(queue);
            var stairTags=t.getList("Stairs",10);for(int i=0;i<stairTags.size();i++){var a=stairTags.getCompound(i);var d=Direction.byName(a.getString("Facing"));if(d!=null)stairs.put(a.getLong("Column"),d);}
        }
        private static ListTag planes(Map<Long,Integer> values){var list=new ListTag();values.forEach((k,y)->{var a=new CompoundTag();a.putLong("Column",k);a.putInt("Y",y);list.add(a);});return list;}
        private static ListTag heightTags(Map<Long,Double> values){var list=new ListTag();values.forEach((k,y)->{var a=new CompoundTag();a.putLong("Column",k);a.putDouble("Y",y);list.add(a);});return list;}
        private static void readHeights(ListTag list,Map<Long,Double> out){for(int i=0;i<list.size();i++){var a=list.getCompound(i);out.put(a.getLong("Column"),a.getDouble("Y"));}}
        private static void readPlanes(ListTag list,Map<Long,Integer> out){for(int i=0;i<list.size();i++){var a=list.getCompound(i);out.put(a.getLong("Column"),a.getInt("Y"));}}
        private static long[] array(Collection<Long> values){return values.stream().mapToLong(Long::longValue).toArray();}
        private static void readSet(CompoundTag t,String name,Set<Long> out){for(long k:t.getLongArray(name))out.add(k);}
    }
    private static long key(int x,int z){return new BlockPos(x,0,z).asLong();}
    private static boolean bounds(State w,int x,int z){return x>=w.minX && x<=w.maxX && z>=w.minZ && z<=w.maxZ;}
    private static long constraintSignature(SettlementRecord s){
        long h=Objects.hash(s.corePosition(),TerrainConfig.PUBLIC_CHECKPOINT_WEIGHT.get(),TerrainConfig.PUBLIC_ROAD_WEIGHT.get(),TerrainConfig.PUBLIC_GRADE_RUN.get(),TerrainConfig.PUBLIC_CLIFF_RISE.get());for(var b:s.buildings())if(b.active() && !b.demolitionQueued())h=31*h+Objects.hash(b.id(),b.minCorner(),b.maxCorner(),regionSignature(b.region()),siteSignature(b.productionSite()));
        for(var b:s.formalBlueprints())if(!b.cancellationQueued()){var t=BlueprintTemplates.create(b);h=31*h+Objects.hash(b.id(),t.minCorner(),t.maxCorner(),regionSignature(b.region()),siteSignature(b.productionSite()));}
        return h;
    }
    private static long signature(ServerLevel level,SettlementRecord s){
        long h=constraintSignature(s);
        for(var r:s.autoRoads().routes.values())if(!r.suppressed && !r.p0Invalidated)for(var p:r.cells)h=31*h+key(p.getX(),p.getZ());
        for(var p:RoadSpaceData.get(level).decorationPositions())if(s.contains(p) && level.hasChunkAt(p) && fixedRoad(level,s,p)){
            h=31*h+p.asLong();var actual=level.getBlockState(p);
            if(actual.getBlock() instanceof StairBlock || actual.getBlock() instanceof SlabBlock)
                h=31*h+actual.toString().hashCode();
        }
        return h;
    }
    /** Fixed road grades constrain their surroundings; exclusive automatic roads follow the shared plane. */
    public static boolean fixedRoad(ServerLevel level,SettlementRecord s,BlockPos p){
        var roads=RoadSpaceData.get(level);
        return com.hkin.exiledsettlement.road.AdaptiveRoadService.walkSurface(level,p)
                && (roads.manual(p) || roads.levelAt(p)>1 || com.hkin.exiledsettlement.road.PlayerRoadEdits.retained(level,p)
                    || com.hkin.exiledsettlement.road.AdaptiveRoadService.fixedPassage(level,s,p));
    }
    private static int regionSignature(com.hkin.exiledsettlement.region.ProductionRegion r) {
        return r==null?0:Objects.hash(r.surveyed,r.planeY(),r.cells.keySet());
    }
    private static int siteSignature(com.hkin.exiledsettlement.region.ProductionSiteSnapshot site) {
        return site==null?0:Objects.hash(site.planeY(),site.cells().stream().map(c->c.ground()).collect(java.util.stream.Collectors.toSet()));
    }
    private static void addAnchor(State w,long k,int y) {
        if(w.barriers.getOrDefault(k,0)==15)return;
        var previous=w.anchors.putIfAbsent(k,y);
        if(previous!=null && previous!=y){w.anchors.remove(k);w.barriers.put(k,15);}
    }
    /** Exact membership and its eight-neighbor ring; merged notches remain public ground. */
    static void regionCheckpoints(State w,com.hkin.exiledsettlement.region.ProductionRegion r) {
        if(r==null || !r.surveyed || r.cells.isEmpty())return;
        var footprint=new HashSet<Long>(r.cells.keySet());
        for(long k:r.cells.keySet()) {
            var p=BlockPos.of(k);
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
                long n=key(p.getX()+dx,p.getZ()+dz);
                if(!r.cells.containsKey(n)){footprint.add(n);addAnchor(w,n,r.planeY());}
            }
        }
        // Row runs keep the persisted mask compact without filling missing production cells.
        var rows=new TreeMap<Integer,SortedSet<Integer>>();
        for(long k:footprint){var p=BlockPos.of(k);rows.computeIfAbsent(p.getZ(),z->new TreeSet<>()).add(p.getX());}
        rows.forEach((z,xs)->{int start=xs.first(),last=start;for(int x:xs){if(x>last+1){w.footprints.add(new Rect(start,z,last,z,0));start=x;}last=x;}w.footprints.add(new Rect(start,z,last,z,0));});
        w.buildings.add(new Rect(r.min().getX(),r.min().getZ(),r.max().getX(),r.max().getZ(),0));
        w.footprintColumns=null;
    }
    /** Frozen workplaces constrain only their outside ring; productive columns remain protected. */
    private static void siteCheckpoints(State w,com.hkin.exiledsettlement.region.ProductionSiteSnapshot site) {
        if(site.cells().isEmpty())return;
        var membership=new HashSet<Long>();for(var c:site.cells())membership.add(key(c.ground().getX(),c.ground().getZ()));
        var footprint=new HashSet<Long>(membership);
        for(long k:membership){var p=BlockPos.of(k);for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            long n=key(p.getX()+dx,p.getZ()+dz);if(!membership.contains(n)){footprint.add(n);addAnchor(w,n,site.planeY());}
        }}
        var rows=new TreeMap<Integer,SortedSet<Integer>>();
        for(long k:footprint){var p=BlockPos.of(k);rows.computeIfAbsent(p.getZ(),z->new TreeSet<>()).add(p.getX());}
        rows.forEach((z,xs)->{int start=xs.first(),last=start;for(int x:xs){if(x>last+1){w.footprints.add(new Rect(start,z,last,z,0));start=x;}last=x;}w.footprints.add(new Rect(start,z,last,z,0));});
        var t=site.template();w.buildings.add(new Rect(t.minCorner().getX(),t.minCorner().getZ(),t.maxCorner().getX(),t.maxCorner().getZ(),0));
        w.footprintColumns=null;
    }
    private static void checkpoints(State w,BuildingBlueprintTemplate t){
        var a=t.minCorner();var b=t.maxCorner();int y=t.entrances().stream().mapToInt(e->e.position().getY()-1).min().orElse(t.anchor().getY()-1);
        for(var placement:t.placements())if(placement.state().getBlock() instanceof net.minecraft.world.level.block.StairBlock
                && placement.position().getY()<=y && t.entrances().stream().anyMatch(e->e.position().distManhattan(placement.position())<=8))
            w.anchors.put(key(placement.position().getX(),placement.position().getZ()),placement.position().getY());
        w.buildings.add(new Rect(a.getX(),a.getZ(),b.getX(),b.getZ(),0));
        w.footprints.add(new Rect(a.getX(),a.getZ(),b.getX(),b.getZ(),1));
        for(int x=a.getX()-1;x<=b.getX()+1;x++)for(int z=a.getZ()-1;z<=b.getZ()+1;z++){
            if(x>=a.getX() && x<=b.getX() && z>=a.getZ() && z<=b.getZ())continue;
            addAnchor(w,key(x,z),y);
        }
    }
    private static void begin(ServerLevel level,SettlementRecord s,State w,long signature){
        w.localPublication=false;w.dirtyColumns.clear();
        w.constraintSignature=constraintSignature(s);
        AutoRoadPlanner.invalidateOptional(s);PublicRoadIntegration.reset(s);w.roadsIntegrated=false;w.roadCursor=0;w.integratedRoads.clear();w.roadGeneratedAnchors.clear();w.signature=signature;w.phase=1;w.cursor=0;w.waterCursor=0;w.planCursor=0;w.footprints.clear();w.anchors.clear();w.goals.clear();w.mask.clear();w.outside.clear();w.wet.clear();w.openWater.clear();w.planned.clear();w.queue.clear();w.skipped.clear();
        w.seams.clear();w.seenSeams.clear();w.stairs.clear();w.bestDistance=Integer.MAX_VALUE;
        w.deferred.clear();w.footprintColumns=null;
        w.anchorRadii.clear();w.baselines.clear();
        w.nearestRoad.clear();w.roadDistances.clear();w.roadHeights.clear();
        w.buildings.clear();w.nearest.clear();w.distances.clear();w.barriers.clear();w.heights.clear();w.nextHeights.clear();w.lower.clear();w.upper.clear();w.queued.clear();w.iterationKeys=List.of();w.publicationKeys=List.of();w.stableSweeps=0;w.sweepChange=0;
        var core=s.corePosition();w.footprints.add(new Rect(core.getX(),core.getZ(),core.getX(),core.getZ(),6));w.anchors.put(key(core.getX(),core.getZ()),core.getY()-1);
        for(var b:s.buildings())if(b.active() && !b.demolitionQueued() && b.region()==null && b.productionSite()==null
                && (!b.type().isDynamicArea() || BlueprintTemplates.fixedProduction(b.type(),b.templateVersion())) && !com.hkin.exiledsettlement.decoration.DecorationService.owned(s,b.id())) {
            if(!BlueprintTemplates.supports(b.type())) {
                w.footprints.add(new Rect(b.minCorner().getX(),b.minCorner().getZ(),b.maxCorner().getX(),b.maxCorner().getZ(),1));
                continue;
            }
            checkpoints(w,BlueprintTemplates.createForBuilding(b));
        }
        for(var b:s.blueprints())if(b.terrainProject()==null && !b.cancellationQueued() && b.region()==null && b.productionSite()==null
                && (!b.type().isDynamicArea() || BlueprintTemplates.fixedProduction(b.type(),b.templateVersion())) && !com.hkin.exiledsettlement.decoration.DecorationService.owned(s,b.id()))checkpoints(w,BlueprintTemplates.create(b));
        var seenRegions=Collections.newSetFromMap(new IdentityHashMap<com.hkin.exiledsettlement.region.ProductionRegion,Boolean>());
        for(var b:s.buildings())if(b.active() && !b.demolitionQueued() && b.region()!=null && seenRegions.add(b.region()))regionCheckpoints(w,b.region());
        for(var b:s.formalBlueprints())if(!b.cancellationQueued() && b.region()!=null && seenRegions.add(b.region()))regionCheckpoints(w,b.region());
        var seenSites=Collections.newSetFromMap(new IdentityHashMap<com.hkin.exiledsettlement.region.ProductionSiteSnapshot,Boolean>());
        for(var b:s.buildings())if(b.active() && !b.demolitionQueued() && b.productionSite()!=null && seenSites.add(b.productionSite()))siteCheckpoints(w,b.productionSite());
        for(var b:s.formalBlueprints())if(!b.cancellationQueued() && b.productionSite()!=null && seenSites.add(b.productionSite()))siteCheckpoints(w,b.productionSite());
        for(var r:s.autoRoads().routes.values())if(!r.suppressed && !r.p0Invalidated)for(var p:r.cells)w.footprints.add(new Rect(p.getX(),p.getZ(),p.getX(),p.getZ(),1));
        w.minX=w.footprints.stream().mapToInt(r->r.x1-r.margin).min().orElse(core.getX())-1;w.maxX=w.footprints.stream().mapToInt(r->r.x2+r.margin).max().orElse(core.getX())+1;
        w.minZ=w.footprints.stream().mapToInt(r->r.z1-r.margin).min().orElse(core.getZ())-1;w.maxZ=w.footprints.stream().mapToInt(r->r.z2+r.margin).max().orElse(core.getZ())+1;
        for(var p:RoadSpaceData.get(level).decorationPositions())if(bounds(w,p.getX(),p.getZ()) && level.hasChunkAt(p)
                && !com.hkin.exiledsettlement.road.BridgeService.column(s,p)
                && fixedRoad(level,s,p)){
            double surface=0;for(var side:Direction.Plane.HORIZONTAL)surface+=com.hkin.exiledsettlement.road.RoadSurfaceProfile.edgeHeight(level,p,level.getBlockState(p),side)-1;
            w.roadHeights.put(key(p.getX(),p.getZ()),surface/4);
            if(!fixedRoad(level,s,p))continue;
            long k=key(p.getX(),p.getZ());var previous=w.anchors.putIfAbsent(k,p.getY());
            if(previous!=null && previous!=p.getY())skip(w,"固定道路与建筑外圈标高冲突");
            var state=level.getBlockState(p);
            if(state.getBlock() instanceof StairBlock || state.getBlock() instanceof SlabBlock)
                for(var d:Direction.Plane.HORIZONTAL){var n=p.relative(d);
                    if(!bounds(w,n.getX(),n.getZ()) || RoadSpaceData.get(level).columnHeight(n)!=null)continue;
                    int landing=com.hkin.exiledsettlement.road.RoadSurfaceProfile.landingGround(level,p,d);
                    var old=w.anchors.putIfAbsent(key(n.getX(),n.getZ()),landing);
                    if(old!=null && old!=landing)skip(w,"台阶方向落脚面与固定地坪冲突");
                }
        }
        pinConstructionGrades(s,w);
        for(var project:s.terrain().projects.values())if(project.escapeFor!=null && !project.cancelled)
            for(var p:project.accessSurfaces)w.anchors.put(key(p.getX(),p.getZ()),p.getY());
        w.representative=core.getY()-1;w.status="正在固定建筑外圈一格检查点并识别内部空地";
    }
    static void pinConstructionGrades(SettlementRecord s,State w){
        for(var r:s.autoRoads().routes.values())if(AutoRoadService.fixedConstructionGrade(s,r))for(var p:r.cells){
            long k=key(p.getX(),p.getZ());w.anchors.putIfAbsent(k,p.getY());w.roadHeights.put(k,(double)p.getY());
        }
    }
    public static boolean planningTickDue(long tick,int phase){
        return phase!=0 || tick%10==0;
    }
    private static boolean migrate(SettlementRecord s,State w){
        int migrated=0;
        for(var project:s.terrain().projects.values())if(!project.cancelled && (project.publicGround && project.publicGroundVersion<HEIGHT_VERSION
                || !project.road && !project.explicit && (project.layoutVersion>0 || project.name.startsWith("修整 · ")) && project.layoutVersion<TerrainService.LAYOUT_VERSION
                    && project.dependencies.stream().noneMatch(id->{var b=s.blueprint(id);return b!=null && !b.cancellationQueued() && b.terrainProject()==null;}))){
            project.heightSuperseded=true;project.cancelled=true;s.terrain().index(project);
            w.heightVersion=1;
            var site=s.blueprint(project.id);if(site!=null)site.queueCancellation();
            w.status="正在停止旧碎片高度目标，保留实际施工记录供居民修整";
            if(++migrated>=16)return true;
        }
        if(migrated>0)return true;
        if(w.heightVersion<HEIGHT_VERSION){w.heightVersion=HEIGHT_VERSION;w.phase=0;w.signature=Long.MIN_VALUE;w.nextReview=0;}
        return false;
    }
    public static void tick(ServerLevel level,SettlementRecord s){
        if(!planningTickDue(level.getGameTime(),s.terrain().publicGround.phase) || !s.active() || !s.terrain().enabled || s.terrain().publicPaused || s.community().evacuation || !level.hasChunkAt(s.corePosition()))return;
        int slices = Math.floorMod(level.getDayTime(),24000L) >= 13000 ? TerrainConfig.NIGHT_PLANNING_SLICES.get() : 4;
        long deadline = System.nanoTime() + 2_000_000L;
        var w=s.terrain().publicGround;w.deadline=deadline;
        try {
        if(migrate(s,w)){SettlementSavedData.get(level).touch();return;}
        for (int slice=0; slice<slices; slice++) {
            if(!planningTickDue(level.getGameTime(),s.terrain().publicGround.phase))break;
            int phase=w.phase;
            advancePlanning(level,s,deadline);
            // World phases run every tick, once, with both a time deadline and
            // per-tick column ceilings. Night does not multiply these ceilings.
            if (phase<6 || w.phase<6 || System.nanoTime()>=deadline) break;
        }
        }finally{w.deadline=Long.MAX_VALUE;}
        if(s.terrain().surveys.isEmpty())s.terrain().status=s.terrain().publicGround.status;
    }
    private static void advancePlanning(ServerLevel level,SettlementRecord s,long deadline){
        var w=s.terrain().publicGround;long now=level.getGameTime();
        if(w.phase==0){if(now<w.nextReview)return;long hash=signature(level,s);
            if(hash==w.signature && w.constraintSignature==constraintSignature(s) && w.roadsIntegrated && !w.goals.isEmpty()
                    && w.deferred.stream().noneMatch(k->level.hasChunkAt(BlockPos.of(k)))){
                // Geometry signatures do not describe current blocks. Recheck the committed
                // plane in bounded publication slices, without letting a new pothole lower it.
                w.phase=5;w.planCursor=0;w.localPublication=!w.dirtyColumns.isEmpty();
                w.publicationKeys=w.localPublication?List.copyOf(w.dirtyColumns):List.of();w.dirtyColumns.clear();
                if(!w.localPublication)w.planned.clear();w.skipped.clear();
                w.status=w.localPublication?"正在复查局部地形变化":"正在复查既定公共地坪的实际缺口";
            }else{
                // Existing orders keep their own frozen targets; new development can be appended independently.
                begin(level,s,w,hash);
            }
        }
        if(w.phase==1)scan(level,s,w);
        else if(w.phase==2)outside(w);
        else if(w.phase==3)water(w);
        else if(w.phase==4)grade(level,s,w);
        else if(w.phase==5){if(!w.roadsIntegrated){w.phase=14;w.roadCursor=0;}else build(level,s,w);}
        else if(w.phase==14)integrateRoads(level,s,w,deadline);
        else if(w.phase==6)seams(w);
        else if(w.phase==7)passages(s,w);
        else if(w.phase==8)spread(w);
        else if(w.phase==9)divide(w);
        else if(w.phase==10)relax(w);
        else if(w.phase==11)envelopes(w);
        else if(w.phase==12)spreadRoads(w);
        else if(w.phase==13)initializeRoadRegions(w);
        if(w.phase==1 || w.phase==4){
            long total=((long)w.maxX-w.minX+1)*((long)w.maxZ-w.minZ+1);
            w.status=(w.phase==1?"正在识别公共空地：":"正在采样公共空地：")+w.cursor+"/"+total+"列";
        }
        else if(w.phase==11)w.status="正在核对固定地坪之间的实际缓坡空间：待核对 "+w.queue.size()+"列";
        else if(w.phase==10)w.status="正在收敛联合地坪：本轮 "+w.planCursor+"/"+w.goals.size()+"列，稳定检查 "+w.stableSweeps+"/2";
        else if(w.phase==8 || w.phase==12)w.status="正在传播检查点与道路高度：待核对 "+w.queue.size()+"列";
        else if(w.phase==13)w.status="正在初始化独立通路区域："+w.planCursor+"/"+w.goals.size()+"列";
        else if(w.phase==5 && w.roadsIntegrated && !w.capacityPaused)w.status=(w.localPublication?"正在复查局部地形：":"正在发布居民挖填工程：")+w.planCursor+"/"+w.publicationKeys().size()+"列";
        SettlementSavedData.get(level).touch();
    }
    private static void scan(ServerLevel level,SettlementRecord s,State w){
        long width=(long)w.maxX-w.minX+1,total=width*((long)w.maxZ-w.minZ+1);
        for(int budget=0;budget<TerrainConfig.PUBLIC_SCAN_COLUMNS.get() && w.cursor<total && w.timeFor(budget);budget++){
            int x=(int)(w.minX+w.cursor%width),z=(int)(w.minZ+w.cursor/width);var p=new BlockPos(x,0,z);
            if(!level.hasChunkAt(p)){w.deferred.add(key(x,z));w.cursor++;continue;}
            long k=key(x,z);if(w.footprintAt(x,z) || surrounded(w,x,z))w.mask.add(k);
            int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1;var at=p.atY(y);
            while(y>level.getMinBuildHeight() && (level.getBlockState(at).getBlock() instanceof BushBlock || level.getBlockState(at).is(Blocks.SNOW)) && level.getBlockState(at).getFluidState().isEmpty())at=p.atY(--y);
            if(TerrainService.replaceableWater(level.getBlockState(at))){w.wet.add(k);
                for(var b:s.buildings())if(b.active() && (b.type()==BuildingType.FISHING_HUT && b.position().distManhattan(at.atY(b.position().getY()))<=16
                        || (b.type().isDynamicArea() || b.type()==BuildingType.WELL || b.type()==BuildingType.LEISURE_GARDEN) && x>=b.minCorner().getX() && x<=b.maxCorner().getX() && z>=b.minCorner().getZ() && z<=b.maxCorner().getZ())){w.openWater.add(k);break;}
            }
            w.cursor++;
        }
        if(w.cursor<total)return;
        for(int x=w.minX;x<=w.maxX;x++){enqueueOutside(w,key(x,w.minZ));enqueueOutside(w,key(x,w.maxZ));}
        for(int z=w.minZ;z<=w.maxZ;z++){enqueueOutside(w,key(w.minX,z));enqueueOutside(w,key(w.maxX,z));}
        for(long k:w.deferred)enqueueOutside(w,k);
        w.phase=2;w.status="正在识别被建筑和道路围合的公共空地";
    }
    /** Require surrounding buildings in at least three directions; do not fill an outer rectangular envelope. */
    private static boolean surrounded(State w,int x,int z){
        if(w.buildings.size()<3)return false;var angles=new ArrayList<Double>();
        for(var b:w.buildings)angles.add(Math.atan2((b.z1+b.z2)*.5-z,(b.x1+b.x2)*.5-x));
        angles.sort(Double::compare);double gap=angles.getFirst()+Math.PI*2-angles.getLast();
        for(int i=1;i<angles.size();i++)gap=Math.max(gap,angles.get(i)-angles.get(i-1));
        return gap<=Math.PI*2/3+1e-9;
    }
    private static void enqueueOutside(State w,long k){if(!w.mask.contains(k) && w.outside.add(k))w.queue.add(k);}
    private static void outside(State w){
        for(int budget=0;budget<512 && !w.queue.isEmpty() && w.timeFor(budget);budget++){var p=BlockPos.of(w.queue.removeFirst());for(var d:Direction.Plane.HORIZONTAL){var n=p.relative(d);if(bounds(w,n.getX(),n.getZ()))enqueueOutside(w,n.asLong());}}
        if(!w.queue.isEmpty())return;
        // A connected wet component crossing the developed boundary remains a river/pond.
        w.queue.addAll(w.openWater);
        for(long k:w.wet){var p=BlockPos.of(k);boolean unknownEdge=false;for(var d:Direction.Plane.HORIZONTAL)if(w.deferred.contains(p.relative(d).asLong())){unknownEdge=true;break;}
            if((w.outside.contains(k) || unknownEdge) && w.openWater.add(k))w.queue.add(k);
        }
        w.phase=3;w.status="正在区分内部水坑与贯穿水体";
    }
    private static void water(State w){
        for(int budget=0;budget<512 && !w.queue.isEmpty() && w.timeFor(budget);budget++){var p=BlockPos.of(w.queue.removeFirst());for(var d:Direction.Plane.HORIZONTAL){long k=p.relative(d).asLong();if(w.wet.contains(k) && w.openWater.add(k))w.queue.add(k);}}
        if(w.queue.isEmpty()){w.phase=4;w.cursor=0;w.status="正在统一公共空地标高";}
    }
    private static String excluded(ServerLevel level,SettlementRecord s,BlockPos p){
        if(com.hkin.exiledsettlement.road.BridgeService.column(s,p))return "桥梁保留河道与冻结高程";
        if(!s.contains(p) || s.community().danger(p) || s.decorations().forbidden(p))return "领地或禁放保护";
        if(p.getX()==s.corePosition().getX() && p.getZ()==s.corePosition().getZ())return "聚落核心";
        for(var b:s.buildings())if(b.active() && b.containsColumn(p))return "建筑、生产区域或水景";
        for(var b:s.formalBlueprints())if(!b.cancellationQueued() && b.containsColumn(p))return "建筑预留";
        return "";
    }
    private static void skip(State w,String reason){w.skipped.merge(reason,1,Integer::sum);}
    private record Surface(int y,boolean wet) { }
    /** Read terrain before superseded public fills; roofs and player facilities are not samples. */
    private static Surface reference(ServerLevel level,SettlementRecord s,int x,int z){
        var seed=new BlockPos(x,0,z);if(!level.hasChunkAt(seed))return null;
        var originals=s.terrain().supersededOriginals.get(key(x,z));
        int top=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1;
        if(originals!=null)for(var e:originals.entrySet())if(!e.getValue().isAir())top=Math.max(top,e.getKey());
        for(int y=top;y>=level.getMinBuildHeight();y--){var p=seed.atY(y);var actual=level.getBlockState(p);
            if((s.terrain().supersededChanged(p,actual) && !natural(actual)))return null;
            var state=originals==null?actual:originals.getOrDefault(y,actual);
            if(state.isAir() || TerrainService.soft(state) || com.hkin.exiledsettlement.citizen.ResidentTreeFelling.plannable(level,s,p))continue;
            if(TerrainService.replaceableWater(state))return new Surface(y,true);
            if(TerrainSurface.ground(level,p,state) && (natural(state) || state.getBlock() instanceof StairBlock
                    || state.getBlock() instanceof SlabBlock || state.is(Blocks.DIRT_PATH)))return new Surface(y,false);
            // A recorded obsolete cap is not an unknown facility and cannot hide the ground beneath it.
            if(s.terrain().supersededPaid(p,actual))continue;
            return null;
        }
        return null;
    }
    public record HeightSample(int height,double weight) {}
    public static double sampleWeight(int dx,int dz){return 1.0/(1.0+dx*dx+dz*dz);}
    public static int weightedHeight(List<HeightSample> samples){
        var sorted=samples.stream().sorted(Comparator.comparingInt(HeightSample::height)).toList();
        double half=sorted.stream().mapToDouble(HeightSample::weight).sum()/2,seen=0;
        for(var sample:sorted){seen+=sample.weight();if(seen>=half)return sample.height();}
        throw new IllegalArgumentException("Empty height samples");
    }
    private static Surface reference(ServerLevel level,SettlementRecord s,int x,int z,Map<Long,Surface> samples){
        long k=key(x,z);if(!samples.containsKey(k))samples.put(k,reference(level,s,x,z));return samples.get(k);
    }
    private static Integer localHeight(ServerLevel level,SettlementRecord s,int x,int z,Map<Long,Surface> samples){
        var center=reference(level,s,x,z,samples);var heights=new ArrayList<HeightSample>();
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
            var sample=reference(level,s,x+dx,z+dz,samples);if(sample==null || sample.wet())continue;
            heights.add(new HeightSample(sample.y(),sampleWeight(dx,dz)));
        }
        if(heights.isEmpty())return center!=null && !center.wet()?center.y():null;
        return weightedHeight(heights);
    }
    /** Cheap rejected columns do not spend the bounded world-sampling budget. */
    static List<Long> gradeBatch(State w){
        long width=(long)w.maxX-w.minX+1,total=width*((long)w.maxZ-w.minZ+1);
        int limit=TerrainConfig.PUBLIC_SAMPLE_COLUMNS.get();
        var batch=new ArrayList<Long>(limit);
        for(int visits=0;visits<Math.max(512,limit) && batch.size()<limit && w.cursor<total;visits++){
            int x=(int)(w.minX+w.cursor%width),z=(int)(w.minZ+w.cursor/width);w.cursor++;
            long k=key(x,z);if(!w.outside.contains(k))batch.add(k);
        }
        return batch;
    }
    private static void grade(ServerLevel level,SettlementRecord s,State w){
        long total=((long)w.maxX-w.minX+1)*((long)w.maxZ-w.minZ+1);
        // Neighboring 5x5 windows reuse reads only inside this synchronous batch.
        // Later ticks and restored surveys always read the current world again.
        var samples=new HashMap<Long,Surface>();
        var batch=gradeBatch(w);
        for(int i=0;i<batch.size();i++){
            long k=batch.get(i);
            if(!w.timeFor(i)){
                // gradeBatch reserves its cursor up front. Return the unprocessed
                // suffix so no column disappears when the time budget expires.
                var pending=BlockPos.of(k);w.cursor=((long)pending.getZ()-w.minZ)*((long)w.maxX-w.minX+1)+pending.getX()-w.minX;return;
            }
            var column=BlockPos.of(k);int x=column.getX(),z=column.getZ();
            if(!level.hasChunkAt(new BlockPos(x,0,z))){w.deferred.add(k);continue;}
            var p=new BlockPos(x,w.representative,z);String reason=excluded(level,s,p);if(!reason.isEmpty() && !(x==s.corePosition().getX() && z==s.corePosition().getZ())){skip(w,reason);continue;}
            if(w.openWater.contains(k)){skip(w,"保留贯穿水体");continue;}
            var local=localHeight(level,s,x,z,samples);if(local==null)local=w.anchors.get(k);if(local==null && w.roadHeights.containsKey(k))local=(int)Math.floor(w.roadHeights.get(k)+.5);
            if(local==null){skip(w,"附近没有可用干地参考");continue;}w.baselines.put(k,local);
            w.goals.put(k,w.anchors.getOrDefault(k,local));
        }
        if(w.cursor>=total){startSources(w);w.status="正在划分检查点与道路的平缓融合区域";}
    }
    static void startSources(State w){
        startSources(w,false);
    }
    private static void startSources(State w,boolean finalPass){
        w.sourcesFinal=finalPass;
        w.phase=8;w.planCursor=0;w.queue.clear();w.nearest.clear();w.distances.clear();w.nearestRoad.clear();w.roadDistances.clear();
        for(long k:w.anchors.keySet())if(w.goals.containsKey(k)){w.nearest.put(k,k);w.distances.put(k,0);w.queue.add(k);}
    }
    private static int bit(Direction d){return 1<<d.get2DDataValue();}
    private static void block(State w,long k,Direction d){long n=BlockPos.of(k).relative(d).asLong();w.barriers.merge(k,bit(d),(a,b)->a|b);w.barriers.merge(n,bit(d.getOpposite()),(a,b)->a|b);}
    private static boolean blocked(State w,long k,Direction d){return (w.barriers.getOrDefault(k,0)&bit(d))!=0;}
    static void spread(State w){
        for(int budget=0;budget<512 && !w.queue.isEmpty() && w.timeFor(budget);budget++){
            long k=w.queue.removeFirst();var p=BlockPos.of(k);
            for(var d:Direction.Plane.HORIZONTAL){long n=p.relative(d).asLong();if(!w.goals.containsKey(n) || blocked(w,k,d))continue;
                if(Math.abs(w.baselines.get(k)-w.baselines.get(n))>=TerrainConfig.PUBLIC_CLIFF_RISE.get() && !w.wet.contains(k) && !w.wet.contains(n)){block(w,k,d);continue;}
                if(w.nearest.containsKey(n))continue;w.nearest.put(n,w.nearest.get(k));w.distances.put(n,w.distances.get(k)+1);w.queue.add(n);
            }
        }
        if(w.queue.isEmpty()){w.phase=12;w.planCursor=0;
            for(long k:w.roadHeights.keySet())if(w.goals.containsKey(k)){w.nearestRoad.put(k,k);w.roadDistances.put(k,0);w.queue.add(k);}
        }
    }
    static void spreadRoads(State w){
        for(int budget=0;budget<512 && !w.queue.isEmpty() && w.timeFor(budget);budget++){
            long k=w.queue.removeFirst();var p=BlockPos.of(k);
            for(var d:Direction.Plane.HORIZONTAL){long n=p.relative(d).asLong();if(!w.goals.containsKey(n) || blocked(w,k,d))continue;
                if(Math.abs(w.baselines.get(k)-w.baselines.get(n))>=TerrainConfig.PUBLIC_CLIFF_RISE.get() && !w.wet.contains(k) && !w.wet.contains(n)){block(w,k,d);continue;}
                if(w.nearestRoad.containsKey(n))continue;w.nearestRoad.put(n,w.nearestRoad.get(k));w.roadDistances.put(n,w.roadDistances.get(k)+1);w.queue.add(n);
            }
        }
        if(w.queue.isEmpty()){if(w.sourcesFinal)initializeHeights(w);else {w.phase=9;w.planCursor=0;}}
    }
    static void divide(State w){
        var keys=w.keys();
        for(int budget=0;budget<256 && w.planCursor<keys.size() && w.timeFor(budget);budget++){
            long k=keys.get(w.planCursor++);var a=w.nearest.get(k);var p=BlockPos.of(k);
            for(var d:Direction.Plane.HORIZONTAL){long n=p.relative(d).asLong();var b=w.nearest.get(n);if(a==null || b==null || a.equals(b))continue;
                int dy=Math.abs(w.anchors.get(a)-w.anchors.get(b));
                if(dy>0 && w.distances.get(k)+w.distances.get(n)+1<dy*TerrainConfig.PUBLIC_GRADE_RUN.get())block(w,k,d);
            }
            w.heights.put(k,(double)(a==null?w.goals.get(k):w.anchors.get(a)));
        }
        if(w.planCursor>=keys.size()){startEnvelopes(w);w.status="正在核对固定地坪之间的实际缓坡空间";}
    }
    static void startEnvelopes(State w){
        w.phase=11;w.planCursor=0;w.lower.clear();w.upper.clear();w.queue.clear();w.queued.clear();
        w.anchors.forEach((k,y)->{if(w.goals.containsKey(k)){w.lower.put(k,(double)y);w.upper.put(k,(double)y);w.queue.add(k);w.queued.add(k);}});
    }
    /** Feasible slope envelopes. Conflicting fixed levels form a retaining boundary,
     * rather than silently forcing a cliff inside the supposedly smooth region. */
    static void envelopes(State w){
        double rise=1D/TerrainConfig.PUBLIC_GRADE_RUN.get();
        for(int budget=0;budget<512 && !w.queue.isEmpty() && w.timeFor(budget);budget++){
            long k=w.queue.removeFirst();w.queued.remove(k);var p=BlockPos.of(k);
            for(var d:Direction.Plane.HORIZONTAL){long n=p.relative(d).asLong();if(!w.goals.containsKey(n) || blocked(w,k,d))continue;
                double low=Math.max(w.lower.getOrDefault(n,Double.NEGATIVE_INFINITY),w.lower.get(k)-rise);
                double high=Math.min(w.upper.getOrDefault(n,Double.POSITIVE_INFINITY),w.upper.get(k)+rise);
                if(low>high+1e-7){block(w,k,d);startEnvelopes(w);return;}
                if(low>w.lower.getOrDefault(n,Double.NEGATIVE_INFINITY)+1e-7 || high<w.upper.getOrDefault(n,Double.POSITIVE_INFINITY)-1e-7){
                    w.lower.put(n,low);w.upper.put(n,high);if(w.queued.add(n))w.queue.add(n);
                }
            }
        }
        if(!w.queue.isEmpty())return;
        // Recompute source reachability after all retaining boundaries are final.
        startSources(w,true);
    }
    private static void initializeHeights(State w){
        // Removing terrain attraction must not silently turn unsourced islands into
        // original-terrain grading. Defer them until a real checkpoint/road exists.
        w.goals.keySet().removeIf(k->!w.lower.containsKey(k) && !w.nearestRoad.containsKey(k));
        w.heights.clear();w.nextHeights.clear();w.iterationKeys=List.of();
        for(long k:w.goals.keySet())if(w.lower.containsKey(k))w.heights.put(k,(w.lower.get(k)+w.upper.get(k))*.5);
        w.phase=13;w.planCursor=0;w.queue.clear();w.stableSweeps=0;w.sweepChange=0;
    }
    /** Each road-only component starts on its own feasible plane, never another region's mean. */
    static void initializeRoadRegions(State w){
        var keys=w.keys();
        for(int budget=0;budget<512 && w.timeFor(budget);budget++){
            if(w.queue.isEmpty()){
                if(w.planCursor>=keys.size()){w.phase=10;w.planCursor=0;return;}
                long k=keys.get(w.planCursor++);if(w.heights.containsKey(k))continue;
                w.heights.put(k,w.roadHeights.get(w.nearestRoad.get(k)));w.queue.add(k);
            }
            long k=w.queue.removeFirst();var p=BlockPos.of(k);
            for(var d:Direction.Plane.HORIZONTAL){long n=p.relative(d).asLong();
                if(w.goals.containsKey(n) && !blocked(w,k,d) && !w.heights.containsKey(n)){w.heights.put(n,w.heights.get(k));w.queue.add(n);}
            }
        }
    }
    static void relax(State w){
        var keys=w.keys();
        for(int budget=0;budget<512 && w.planCursor<keys.size() && w.timeFor(budget);budget++){
            long k=keys.get(w.planCursor++);double y=w.heights.get(k);
            if(w.anchors.containsKey(k))y=w.anchors.get(k);
            else {double sum=0,count=0;var p=BlockPos.of(k);
                var a=w.nearest.get(k);if(a!=null && !w.roadHeights.containsKey(a)){
                    double weight=sourceWeight(TerrainConfig.PUBLIC_CHECKPOINT_WEIGHT.get(),w.distances.get(k));sum+=w.anchors.get(a)*weight;count+=weight;
                }
                var road=w.nearestRoad.get(k);if(road!=null){double weight=sourceWeight(TerrainConfig.PUBLIC_ROAD_WEIGHT.get(),w.roadDistances.get(k));sum+=w.roadHeights.get(road)*weight;count+=weight;}
                double low=w.lower.getOrDefault(k,Double.NEGATIVE_INFINITY),high=w.upper.getOrDefault(k,Double.POSITIVE_INFINITY),rise=1D/TerrainConfig.PUBLIC_GRADE_RUN.get();
                for(var d:Direction.Plane.HORIZONTAL){long n=p.relative(d).asLong();var height=w.heights.get(n);if(height!=null && !blocked(w,k,d)){sum+=height;count++;low=Math.max(low,height-rise);high=Math.min(high,height+rise);}}
                // In-place constrained coordinate descent preserves the feasible slope
                // after every update. Terrain and retaining walls have zero attraction.
                if(count>0 && low<=high+1e-7)y=Math.clamp(sum/count,low,Math.max(low,high));
            }
            w.nextHeights.put(k,y);w.sweepChange=Math.max(w.sweepChange,Math.abs(y-w.heights.get(k)));
            w.heights.put(k,y);
        }
        if(w.planCursor<keys.size())return;
        w.heights.clear();w.heights.putAll(w.nextHeights);w.nextHeights.clear();w.planCursor=0;
        w.stableSweeps=w.sweepChange<.02?w.stableSweeps+1:0;w.sweepChange=0;
        if(w.stableSweeps<2)return;
        w.heights.forEach((k,y)->w.goals.put(k,w.anchors.getOrDefault(k,(int)Math.floor(y+.5))));
        w.phase=6;w.planCursor=0;w.seams.clear();w.seenSeams.clear();w.stairs.clear();w.status="融合高度已确定，正在连接连续台面与双格通路";
    }
    static double sourceWeight(double weight,int distance){double reach=distance+1D;return weight/(reach*reach);}
    /** The first solution is provisional. Road rows are fitted before any new soil is published. */
    private static void integrateRoads(ServerLevel level,SettlementRecord s,State w,long deadline){
        var keys=new ArrayList<>(s.autoRoads().routes.keySet());
        if(w.roadCursor<keys.size()){
            if(PublicRoadIntegration.advance(level,s,keys.get(w.roadCursor),deadline))w.roadCursor++;
            w.status="公共地面与道路联合求解：已核对 "+w.roadCursor+"/"+keys.size()+" 条路线";return;
        }
        for(var route:s.autoRoads().routes.values())if(!route.suppressed && !route.p0Invalidated)for(var p:route.cells){
            long k=key(p.getX(),p.getZ());if(!w.goals.containsKey(k) || BridgeService.column(s,p))continue;
            var fixed=w.anchors.get(k);if(fixed!=null && fixed!=p.getY()){route.reviewAfter=0;route.status="联合高度冲突，保留实际通路并优先寻找安全改线";skip(w,"道路与固定检查点冲突，保留检查点");continue;}
            if(fixed==null)w.roadGeneratedAnchors.add(k);
            w.anchors.put(k,p.getY());w.roadHeights.put(k,(double)p.getY());w.integratedRoads.add(k);
        }
        // Road integration can update its own rows, but must not acknowledge buildings
        // added/cancelled since begin(). Finish this bounded pass, then recapture them.
        if(w.constraintSignature!=constraintSignature(s))w.releasedConstraintReview=true;
        w.roadsIntegrated=true;w.signature=signature(level,s);startSources(w);
        w.status="通路横坡与台阶已核对，正在将周边地面接入同一高度方案";
    }
    public static boolean planning(SettlementRecord s){var w=s.terrain().publicGround;return s.terrain().enabled && !s.terrain().publicPaused && (w.heightVersion!=HEIGHT_VERSION || !w.roadsIntegrated || w.phase!=0 && w.phase!=5);}

    /** Solved publication and admission capacity do not block read-only route computation. */
    public static boolean routePlanningPaused(SettlementRecord s){return planning(s);}

    /** One resident work owner per position, used by both public-ground and road scheduling. */
    public static boolean publishRoadColumn(ServerLevel level,SettlementRecord s,AutoRoadState.Route route,BlockPos at){
        return publishRoadColumn(level,s,route,at,OptionalWorkBudget.snapshot(s));
    }
    public static boolean publishRoadColumn(ServerLevel level,SettlementRecord s,AutoRoadState.Route route,BlockPos at,OptionalWorkBudget capacity){
        var w=s.terrain().publicGround;
        if(route.p0Invalidated || !AutoRoadService.earthworkPlanningReady(s,route) || BridgeService.column(s,at))return false;
        var proposed=w.goals.get(key(at.getX(),at.getZ()));
        boolean publishing=w.roadsIntegrated && (w.phase==0 || w.phase==5) && proposed!=null && proposed==at.getY();
        try(var scope=RoadEarthworkService.planningScope(s,publishing)){if(!RoadEarthworkService.plannable(level,s,at))return false;}
        var y=targetAt(s,at);if(s.terrain().enabled && !s.terrain().publicPaused && y!=null && y!=at.getY() && !publishing){route.reviewAfter=0;route.status="等待公共通路高程联合复查";return false;}
        var desired=RoadEarthworkService.surfaceTarget(level,s,route,at);
        // Paid paving needs only its headroom repaired. Do not rebuild its
        // supports or retire an unrelated shared earthwork column.
        var clearance=roadClearanceSteps(level,s,route,at);
        if(clearance!=null && !clearance.isEmpty()){
            if(!ProductionRegionService.conflict(s,route.id,clearance).isEmpty())return false;
            if(!capacity.fits(clearance,null)){route.status=capacity.status();return false;}
            var project=ProductionRegionService.publish(level,s,route.id,"道路四格净空修复",at,clearance);
            if(project!=null){project.publicGround=true;project.road=true;project.explicit=false;project.groundSurfaces.add(at);s.terrain().index(project);}
            for(var step:clearance){var owner=s.terrain().projects.get(s.terrain().cells.get(step.position()));
                if(owner!=null && !owner.cancelled){owner.dependencies.add(route.id);owner.road=true;}}
            capacity.reserve(clearance,null);return true;
        }
        // Fixed/manual/player surfaces keep their actual material and geometry.
        if(desired==null)return RoadEarthworkService.surfaceReady(level,s,route,at) && capacity.admitPaving(route,at);
        var steps=column(level,s,w,at,desired,true);if(steps==null)return false;
        var paving=AutoRoadService.laidCell(level,s,route,at)?null:at;
        if(!route.manualBridge && !capacity.fits(steps,paving)){route.status=capacity.status();return false;}
        retireRoadColumn(s,at,desired);
        retireOldColumn(s,at);
        var project=ProductionRegionService.publish(level,s,route.id,"公共地面与通路挖填",at,steps);
        if(project!=null){project.publicGround=true;project.road=true;project.explicit=false;project.groundSurfaces.add(at);s.terrain().index(project);}
        // Matching work can have been reused without creating a second carrier.
        for(var step:steps){var owner=s.terrain().projects.get(s.terrain().cells.get(step.position()));
            if(owner!=null && !owner.cancelled){owner.dependencies.add(route.id);owner.road=true;
                if(AutoRoadService.required(s,route))owner.dependencies.add(route.building);}}
        if(!route.manualBridge){capacity.reserve(steps,paving);capacity.admitPaving(route,at);}
        return true;
    }
    public static Integer targetAt(SettlementRecord s,BlockPos p){
        var fixed=AutoRoadService.fixedConstructionSurface(s,p);if(fixed!=null)return fixed.getY();
        var w=s.terrain().publicGround;
        var committed=s.terrain().publicPlane(p);if(committed!=null)return committed.getY();
        if(w.heightVersion==HEIGHT_VERSION && (w.phase==5 || w.phase==0))return w.goals.get(key(p.getX(),p.getZ()));
        // Solving a future revision must not invalidate the currently published
        // physical columns and connected roads before replacement publication.
        return null;
    }
    /** Mathematical alternatives use the future grade without authorizing construction on it. */
    public static Integer planningTargetAt(SettlementRecord s,BlockPos p){
        var fixed=AutoRoadService.fixedConstructionSurface(s,p);if(fixed!=null)return fixed.getY();
        var w=s.terrain().publicGround;
        return w.heightVersion==HEIGHT_VERSION && (w.phase==0 || w.phase==5 || w.phase==14)
                ?w.goals.get(key(p.getX(),p.getZ())):targetAt(s,p);
    }
    /** Revalidate both excavation and placement, including work claimed before a revision. */
    static String workGuard(ServerLevel level,SettlementRecord s,TerrainProject project,TerrainProject.Step step){
        if(project==null || !project.publicGround || project.road || project.bridge || project.areaClearance)return "";
        var w=s.terrain().publicGround;
        boolean publishing=w.heightVersion==HEIGHT_VERSION && w.roadsIntegrated && (w.phase==0 || w.phase==5);
        if(!publishing && s.terrain().publicPlane(step.position())==null)return "CONSTRUCTION_TARGET_CHANGED";
        var at=step.position();var y=targetAt(s,at);
        if(y==null)return "CONSTRUCTION_TARGET_CHANGED";
        if(!excluded(level,s,at).isEmpty())return "PROTECTED_AREA";
        for(var surface:project.groundSurfaces)if(surface.getX()==at.getX() && surface.getZ()==at.getZ() && surface.getY()!=y)return "CONSTRUCTION_TARGET_CHANGED";
        // A completed mathematical plan can withdraw or turn a stair without changing
        // its elevation. Stop stale actions before replacement publication reaches this
        // column; retain the order and paid evidence for the normal bounded transfer.
        // During an unfinished solve the frozen physical column remains authoritative.
        if(publishing)for(var surface:project.groundSurfaces)if(surface.getX()==at.getX() && surface.getZ()==at.getZ()){
            long column=key(at.getX(),at.getZ());var revisedY=w.goals.get(column);
            if(revisedY==null || revisedY!=surface.getY())return "CONSTRUCTION_TARGET_CHANGED";
            var cap=project.template().placementAt(surface);
            var facing=w.anchors.containsKey(column)?null:w.stairs.get(column);
            if(facing!=null || cap!=null && cap.state().getBlock() instanceof StairBlock){
                var desired=facing==null?Blocks.DIRT.defaultBlockState():Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,facing);
                if(cap==null || !TerrainService.satisfied(cap.state(),desired))return "CONSTRUCTION_TARGET_CHANGED";
            }
        }
        // A material replacement may have a paired dig; a pure clearance may never remove support.
        var finalGoal=project.template().placementAt(at);
        if(!step.fill() && at.getY()<=y && (finalGoal==null || finalGoal.state().isAir()))return "CONSTRUCTION_TARGET_CHANGED";
        return "";
    }
    public static Integer checkpointAt(SettlementRecord s,BlockPos p){var fixed=AutoRoadService.fixedConstructionSurface(s,p);if(fixed!=null)return fixed.getY();var w=s.terrain().publicGround;long k=key(p.getX(),p.getZ());return w.heightVersion==HEIGHT_VERSION && !w.roadGeneratedAnchors.contains(k)?w.anchors.get(k):null;}
    private static Direction lowerSide(State w,long k){var y=w.goals.get(k);if(y==null)return null;var p=BlockPos.of(k);for(var d:Direction.Plane.HORIZONTAL){var low=w.goals.get(p.relative(d).asLong());if(low!=null && low==y-1)return d;}return null;}
    static boolean continuousSlope(State w,long key,Direction downhill) {
        var origin=BlockPos.of(key);Integer y=w.goals.get(key);if(y==null || downhill==null)return false;
        // A plateau splits a slope. Two separated rises are not a staircase.
        int low=y, high=y, last=y;
        for(int i=1;i<=3;i++){var h=w.goals.get(origin.relative(downhill,i).asLong());if(h==null || h!=last-1)break;low=h;last=h;}
        last=y;
        for(int i=1;i<=3;i++){var h=w.goals.get(origin.relative(downhill.getOpposite(),i).asLong());if(h==null || h!=last+1)break;high=h;last=h;}
        return high-low>=2;
    }
    /** The complete two-wide run is selected once, not independently at each elevation. */
    static List<Long> stairRun(State w,long key,Direction downhill,Direction lane){
        var p=BlockPos.of(key);
        while(stairEdge(w,p.relative(downhill.getOpposite()),downhill,lane))p=p.relative(downhill.getOpposite());
        var result=new ArrayList<Long>();
        while(stairEdge(w,p,downhill,lane)){
            result.add(p.asLong());result.add(p.relative(lane).asLong());p=p.relative(downhill);
        }
        return result.size()>=4?result:List.of();
    }
    private static boolean stairEdge(State w,BlockPos p,Direction downhill,Direction lane){
        var y=w.goals.get(p.asLong());if(y==null || w.anchors.containsKey(p.asLong()) || w.anchors.containsKey(p.relative(lane).asLong()))return false;
        return Objects.equals(w.goals.get(p.relative(lane).asLong()),y)
                && Objects.equals(w.goals.get(p.relative(downhill).asLong()),y-1)
                && Objects.equals(w.goals.get(p.relative(lane).relative(downhill).asLong()),y-1);
    }
    private static boolean eligibleRun(State w,long key,Direction downhill,Direction lane){
        var p=BlockPos.of(key);return stairEdge(w,p,downhill,lane)
                && (stairEdge(w,p.relative(downhill),downhill,lane) || stairEdge(w,p.relative(downhill.getOpposite()),downhill,lane));
    }
    static void seams(State w){
        var entries=w.keys();for(int budget=0;budget<256 && w.planCursor<entries.size() && w.timeFor(budget);budget++){long k=entries.get(w.planCursor++);if(lowerSide(w,k)!=null)w.seams.add(k);}
        if(w.planCursor>=entries.size()){w.phase=7;w.planCursor=0;}
    }
    static void passages(SettlementRecord s,State w){
        var entries=w.keys();
        for(int budget=0;budget<256 && w.timeFor(budget);budget++){
        if(w.queue.isEmpty()){
            if(w.planCursor>=entries.size()){w.phase=5;w.planCursor=0;w.publicationKeys=List.of();w.status="公共空地已勘察，正在发布居民挖填工程";return;}
            long next=entries.get(w.planCursor++);
            if(!w.seams.contains(next) || !w.seenSeams.add(next))continue;
            w.queue.add(next);w.bestDistance=Integer.MAX_VALUE;
        }
            long k=w.queue.removeFirst();var p=BlockPos.of(k);int y=w.goals.get(k);var d=lowerSide(w,k);
            var side=d.getClockWise();boolean wide=!w.anchors.containsKey(k) && List.of(side,side.getOpposite()).stream().anyMatch(dir->!w.anchors.containsKey(p.relative(dir).asLong()) && Objects.equals(w.goals.get(p.relative(dir).asLong()),y) && Objects.equals(w.goals.get(p.relative(dir).relative(d).asLong()),y-1));
            int distance=Math.abs(p.getX()-s.corePosition().getX())+Math.abs(p.getZ()-s.corePosition().getZ());
            if(w.stairs.containsKey(k))w.bestDistance=-1; // This contour already has the shared run.
            if(wide && continuousSlope(w,k,d) && distance<w.bestDistance
                    && List.of(side,side.getOpposite()).stream().anyMatch(lane->eligibleRun(w,k,d,lane))){w.bestDistance=distance;w.bestSeam=k;}
            for(var dir:Direction.Plane.HORIZONTAL){long n=p.relative(dir).asLong();if(w.seams.contains(n) && Objects.equals(w.goals.get(n),y) && w.seenSeams.add(n))w.queue.add(n);}
        if(w.queue.isEmpty() && w.bestDistance>=0 && w.bestDistance<Integer.MAX_VALUE){var direction=lowerSide(w,w.bestSeam);for(var lane:List.of(direction.getClockWise(),direction.getCounterClockWise())){
            var run=stairRun(w,w.bestSeam,direction,lane);if(!run.isEmpty()){run.forEach(k2->w.stairs.put(k2,direction.getOpposite()));break;}
        }}
        }
    }
    private static boolean natural(BlockState state){return TerrainService.natural(state) && !(state.getBlock() instanceof CropBlock) && !(state.getBlock() instanceof StemBlock) && !(state.getBlock() instanceof AttachedStemBlock) && !state.is(Blocks.FARMLAND);}
    private static void build(ServerLevel level,SettlementRecord s,State w){
        var entries=w.publicationKeys();var capacity=OptionalWorkBudget.snapshot(s);
        w.capacityPaused=false;
        // Let released quota accumulate for an actual paid-road obstruction.
        // Existing jobs continue; protected or already-published cuts do not
        // reserve admission or block optional publication.
        if(unpublishedRoadClearance(level,s)){w.capacityPaused=true;w.status="优先发布已铺道路四格净空修复；现有工程继续施工";return;}
        for(int budget=0;budget<TerrainConfig.PUBLIC_PUBLISH_COLUMNS.get() && w.planCursor<entries.size() && w.timeFor(budget);budget++){
            long key=entries.get(w.planCursor);var at=BlockPos.of(key).atY(w.goals.get(key));if(!level.hasChunkAt(at)){w.planCursor++;w.deferred.add(key);skip(w,"局部等待加载");continue;}
            String reason=excluded(level,s,at);if(!reason.isEmpty()){w.planCursor++;skip(w,reason);continue;}
            if(roadColumn(level,s,at)){
                var route=s.autoRoads().routes.values().stream().filter(r->!r.suppressed && !r.p0Invalidated && r.cellInColumn(at)!=null).findFirst().orElse(null);
                if(route!=null && AutoRoadService.allowed(s) && AutoRoadService.routeAllowed(s,route)
                        && !publishRoadColumn(level,s,route,route.cellInColumn(at),capacity)){
                    if(capacity.denied()){w.capacityPaused=true;w.status=capacity.status();return;}
                }
                w.planCursor++;
                continue;
            }
            var steps=column(level,s,w,at,null,false);if(steps==null){w.planCursor++;continue;}
            if(!capacity.fits(steps,null)){w.capacityPaused=true;w.status=capacity.status();return;}
            retireOldColumn(s,at);
            if(steps.stream().anyMatch(step->step.position().equals(at) && step.target().is(Blocks.COBBLESTONE)))retireSoilWallCap(s,at);
            if(!steps.isEmpty()){
                var project=ProductionRegionService.publish(level,s,s.id(),"公共地面与通路挖填",at,steps);
                if(project!=null){project.publicGround=true;project.explicit=false;project.layoutVersion=TerrainService.LAYOUT_VERSION;project.groundSurfaces.add(at);s.terrain().index(project);}
                capacity.reserve(steps,null);
            }
            w.planned.add(key);w.planCursor++;
        }
        if(w.planCursor>=entries.size()){
            if(w.constraintSignature!=constraintSignature(s))w.releasedConstraintReview=true;
            w.phase=0;w.nextReview=w.dirtyColumns.isEmpty() && !w.releasedConstraintReview?level.getGameTime()+1200:0;w.localPublication=false;
            if(w.releasedConstraintReview){w.signature=Long.MIN_VALUE;w.releasedConstraintReview=false;}
            w.status="公共空地勘察完成：目标 "+w.goals.size()+"列；保留外部水体 "+w.openWater.size()+"列";
        }
    }
    private static List<TerrainProject.Step> roadClearanceSteps(ServerLevel level,SettlementRecord s,AutoRoadState.Route route,BlockPos at){
        if(!AutoRoadService.laidCell(level,s,route,at) || !RoadEarthworkService.permitted(level,s,at))return null;
        var steps=new ArrayList<TerrainProject.Step>();
        for(int h=RoadEarthworkService.ROAD_CLEARANCE_HEIGHT;h>=1;h--){var p=at.above(h);if(!level.hasChunkAt(p))return null;
            var state=level.getBlockState(p);if(state.isAir())continue;
            if(!state.getFluidState().isEmpty() || (!natural(state) && !com.hkin.exiledsettlement.citizen.ResidentTreeFelling.plannable(level,s,p)) || state.hasBlockEntity()
                    || (s.terrain().supersededChanged(p,state) && !natural(state)) || s.terrain().suppressed.contains(p.asLong()))return null;
            steps.add(new TerrainProject.Step(p,Blocks.AIR.defaultBlockState(),state));}
        return steps;
    }
    private static boolean unpublishedRoadClearance(ServerLevel level,SettlementRecord s){
        if(!AutoRoadService.allowed(s))return false;
        for(var route:s.autoRoads().routes.values())if(!route.suppressed && !route.p0Invalidated && AutoRoadService.parentValid(level,s,route)){
            int budget=32;
            for(int i=route.repairs.nextSetBit(0);i>=0 && i<route.cells.size() && budget-->0;i=route.repairs.nextSetBit(i+1)){
                var steps=roadClearanceSteps(level,s,route,route.cells.get(i));
                if(steps==null || steps.isEmpty() || !ProductionRegionService.conflict(s,route.id,steps).isEmpty())continue;
                if(steps.stream().anyMatch(step->!TerrainService.pendingAt(s,step.position())))return true;
            }
        }
        return false;
    }
    static boolean roadColumn(ServerLevel level,SettlementRecord s,BlockPos at){
        if(RoadSpaceData.get(level).columnHeight(at)!=null)return true;
        return s.autoRoads().routes.values().stream().anyMatch(r->!r.suppressed && !r.p0Invalidated && r.cellInColumn(at)!=null);
    }
    private static void retireRoadColumn(SettlementRecord s,BlockPos at,BlockState desired){
        for(var project:s.terrain().projectsInColumn(at))if(!project.cancelled && !project.bridge && !project.roadReclaim && (project.road || project.publicGround)){
            var placements=project.template().placements().stream().filter(p->p.position().getX()==at.getX() && p.position().getZ()==at.getZ() && !p.state().isAir()).toList();
            if(placements.isEmpty())continue;
            int top=placements.stream().mapToInt(p->p.position().getY()).max().orElse(at.getY());
            var cap=project.template().placementAt(at);
            if(top==at.getY() && cap!=null && TerrainService.satisfied(cap.state(),desired))continue;
            // Do not release a pure support column below the requested road cap.
            if(top<at.getY() && cap==null)continue;
            project.retireColumn(at);s.terrain().index(project);
            for(var step:project.steps)if(step.position().getX()==at.getX() && step.position().getZ()==at.getZ())s.terrain().cells.remove(step.position(),project.id);
        }
    }
    static void retireOldColumn(SettlementRecord s,BlockPos at){
        for(var project:s.terrain().projectsInColumn(at))if(project.publicGround && !project.cancelled
                && (project.groundSurfaces.stream().anyMatch(p->p.getX()==at.getX() && p.getZ()==at.getZ() && p.getY()!=at.getY()) || changedStairTarget(s,project,at))){
            // Preserve paid block evidence before abandoning the former target.
            project.retireColumn(at);s.terrain().index(project);
            for(var step:project.steps)if(step.position().getX()==at.getX() && step.position().getZ()==at.getZ())s.terrain().cells.remove(step.position(),project.id);
        }
    }
    private static boolean soilWallCap(TerrainProject project,BlockPos at){
        if(!project.publicGround || project.road || project.bridge || project.roadReclaim || project.cancelled || !project.groundSurfaces.contains(at))return false;
        var cap=project.template().placementAt(at);
        return cap!=null && cap.state().is(Blocks.DIRT);
    }
    private static void retireSoilWallCap(SettlementRecord s,BlockPos at){
        for(var project:s.terrain().projectsInColumn(at))if(soilWallCap(project,at)){
            project.retireColumn(at);s.terrain().index(project);
            for(var step:project.steps)if(step.position().getX()==at.getX() && step.position().getZ()==at.getZ())s.terrain().cells.remove(step.position(),project.id);
        }
    }
    private static boolean changedStairTarget(SettlementRecord s,TerrainProject project,BlockPos at){
        // Road caps are compared against their route in retireRoadColumn, not the
        // independent public-ground staircase map (which may be empty).
        if(project.road)return false;
        var placement=project.template().placementAt(at);if(placement==null)return false;
        var facing=s.terrain().publicGround.stairs.get(key(at.getX(),at.getZ()));
        if(facing==null)return placement.state().getBlock() instanceof StairBlock;
        return !BlueprintService.matchesPlacedState(placement.state(),Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,facing));
    }
    private static List<TerrainProject.Step> column(ServerLevel level,SettlementRecord s,State w,BlockPos at,BlockState roadTarget,boolean road){
        var clears=new ArrayList<TerrainProject.Step>();var fills=new ArrayList<TerrainProject.Step>();int top=level.getHeight(Heightmap.Types.WORLD_SURFACE,at.getX(),at.getZ())-1;
        if(road)top=Math.max(at.getY()+RoadEarthworkService.clearanceHeight(s,at),s.terrain().supersededColumn(at).stream().mapToInt(BlockPos::getY).max().orElse(at.getY()+2));
        for(int y=top;y>at.getY();y--){var p=at.atY(y);var state=level.getBlockState(p);if(state.isAir())continue;if((s.terrain().supersededChanged(p,state) && !natural(state)) || !(natural(state) || road && com.hkin.exiledsettlement.citizen.ResidentTreeFelling.plannable(level,s,p) || s.terrain().supersededPaid(p,state) || s.terrain().registeredPublicPaid(p,state) || road && AdaptiveRoadService.editableSurface(level,s,p)) || !state.getFluidState().isEmpty()){skip(w,"保留设施或高于地坪的水");return null;}clears.add(new TerrainProject.Step(p,Blocks.AIR.defaultBlockState(),state));}
        int faceBottom=at.getY();boolean retaining=false;
        for(var d:Direction.Plane.HORIZONTAL){var n=at.relative(d);var desired=w.goals.get(key(n.getX(),n.getZ()));
            if(!level.hasChunkAt(n)){w.deferred.add(key(n.getX(),n.getZ()));skip(w,"相邻范围等待加载");return null;}
            var fixed=AutoRoadService.fixedConstructionSurface(s,n);
            int ground=fixed!=null?fixed.getY():desired!=null?desired:level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,n.getX(),n.getZ())-1;
            if(at.getY()-ground>=2 && level.hasChunkAt(n)){retaining=true;faceBottom=Math.min(faceBottom,ground+1);}
        }
        int bottom=Math.min(at.getY()-1,faceBottom-1);while(bottom>=level.getMinBuildHeight()){
            var p=at.atY(bottom);var state=level.getBlockState(p);if(bottom<faceBottom && TerrainSurface.ground(level,p) && !com.hkin.exiledsettlement.citizen.TreeSurvey.candidate(state))break;
            if((s.terrain().supersededChanged(p,state) && !natural(state)) || !natural(state) && !(road && com.hkin.exiledsettlement.citizen.ResidentTreeFelling.plannable(level,s,p)) && !TerrainService.replaceableWater(state) && !s.terrain().supersededPaid(p,state) && !s.terrain().registeredPublicPaid(p,state) && !(road && AdaptiveRoadService.editableSurface(level,s,p))){skip(w,"保留地下设施");return null;}bottom--;
        }
        if(bottom<level.getMinBuildHeight()){skip(w,"无连续实际支撑");return null;}
        for(int y=bottom+1;y<=at.getY();y++){
            var p=at.atY(y);var state=level.getBlockState(p);var target=retaining?Blocks.COBBLESTONE.defaultBlockState():Blocks.DIRT.defaultBlockState();
            var facing=w.stairs.get(key(at.getX(),at.getZ()));
            if(!road && y==at.getY() && facing!=null && !w.anchors.containsKey(key(at.getX(),at.getZ())))target=Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,facing);
            if(road && y==at.getY())target=roadTarget;
            // Reuse approved stone already present; a periodic review must not rebuild it as cobblestone.
            if(retaining && (!road || y<at.getY()) && target.is(Blocks.COBBLESTONE) && BuriedFillMaterials.existingWall(level,s,p,state))continue;
            if(TerrainService.satisfied(state,target))continue;
            if((s.terrain().supersededChanged(p,state) && !natural(state))){skip(w,"保留旧土石位置的非地形设施");return null;}
            // Preview precedes retirement. A still-registered paid cap is just as
            // recoverable as retired evidence; unknown/player-changed stairs are not.
            if(!natural(state) && !(road && com.hkin.exiledsettlement.citizen.ResidentTreeFelling.plannable(level,s,p)) && !TerrainService.replaceableWater(state) && !s.terrain().supersededPaid(p,state) && !s.terrain().registeredPublicPaid(p,state) && !(road && AdaptiveRoadService.editableSurface(level,s,p))){skip(w,"保留地面设施");return null;}
            if(!state.isAir() && !TerrainService.replaceableWater(state))clears.add(new TerrainProject.Step(p,Blocks.AIR.defaultBlockState(),state));
            if(TerrainService.replaceableWater(state) && target.getBlock() instanceof StairBlock){fills.add(new TerrainProject.Step(p,Blocks.DIRT.defaultBlockState(),state));fills.add(new TerrainProject.Step(p,Blocks.AIR.defaultBlockState(),Blocks.DIRT.defaultBlockState()));fills.add(new TerrainProject.Step(p,target,Blocks.AIR.defaultBlockState()));}
            else fills.add(new TerrainProject.Step(p,target,TerrainService.replaceableWater(state)?state:Blocks.AIR.defaultBlockState()));
        }
        clears.sort(Comparator.comparingInt((TerrainProject.Step a)->a.position().getY()).reversed());clears.addAll(fills);
        for(var step:clears)if(s.terrain().suppressed.contains(step.position().asLong())){skip(w,"玩家移除保留");return null;}
        AdaptiveRoadService.releaseCompletedRoadTargets(s,clears);
        boolean stoneCap=clears.stream().anyMatch(step->step.position().equals(at) && step.target().is(Blocks.COBBLESTONE));
        // Preview replacement capacity before retiring any current column.
        // Only the old target in this exact column can be transferred below.
        var checked=clears.stream().filter(step->{var p=s.terrain().projects.get(s.terrain().cells.get(step.position()));
            if(p==null || p.cancelled || p.bridge || p.roadReclaim)return true;
            if(!road && stoneCap && soilWallCap(p,at))return false;
            if(p.publicGround && (p.groundSurfaces.stream().anyMatch(g->g.getX()==at.getX() && g.getZ()==at.getZ() && g.getY()!=at.getY()) || changedStairTarget(s,p,at)))return false;
            if(road && (p.road || p.publicGround)){
                var oldTop=p.template().placements().stream().filter(g->g.position().getX()==at.getX() && g.position().getZ()==at.getZ() && !g.state().isAir()).mapToInt(g->g.position().getY()).max().orElse(Integer.MIN_VALUE);
                var cap=p.template().placementAt(at);return oldTop<at.getY() && cap==null || oldTop==at.getY() && cap!=null && TerrainService.satisfied(cap.state(),roadTarget);
            }
            return true;
        }).toList();
        String conflict=ProductionRegionService.conflict(s,checked);
        if(!conflict.isEmpty()){skip(w,conflict);w.deferred.add(key(at.getX(),at.getZ()));return null;}
        return clears;
    }
    public static int unpublishedColumns(SettlementRecord s) {
        var w=s.terrain().publicGround;
        return Math.max(0,w.goals.size()-w.planCursor);
    }
    public static List<String> report(SettlementRecord s){var w=s.terrain().publicGround;return List.of("公共空地："+w.status,"覆盖核心周边、建筑外缘与围合空地；内部水坑由居民填实，贯穿水体保留。", "局部等待加载 "+w.deferred.size()+"列，不阻挡其他空地工程；保留或等待："+w.skipped);}
    public static com.google.gson.JsonObject observation(ServerLevel level,SettlementRecord s){
        var w=s.terrain().publicGround;var out=new com.google.gson.JsonObject();out.addProperty("phase",w.phase);out.addProperty("cursor",w.cursor);out.addProperty("planCursor",w.planCursor);out.addProperty("signature",w.signature);out.addProperty("status",w.status);out.addProperty("initialWet",w.wet.size());out.addProperty("openWater",w.openWater.size());out.addProperty("candidateColumns",w.goals.size());out.addProperty("plannedColumns",w.planned.size());
        out.addProperty("roadsIntegrated",w.roadsIntegrated);out.addProperty("integratedRoadColumns",w.integratedRoads.size());out.addProperty("roadIntegrationCursor",w.roadCursor);
        out.addProperty("releasedConstraintReviewQueued",w.releasedConstraintReview);
        var capacity=OptionalWorkBudget.snapshot(s);out.addProperty("optionalPendingBlocks",capacity.pending());out.addProperty("optionalPendingLimit",capacity.limit());out.addProperty("optionalPlanningPaused",w.capacityPaused || capacity.full());
        out.addProperty("terrainWeight",0);out.addProperty("wallWeight",0);out.addProperty("checkpointWeight",TerrainConfig.PUBLIC_CHECKPOINT_WEIGHT.get());out.addProperty("roadWeight",TerrainConfig.PUBLIC_ROAD_WEIGHT.get());out.addProperty("roadHeightSources",w.roadHeights.size());
        var legacy=new com.google.gson.JsonArray();
        for(var p:s.terrain().supersededPositions().stream().sorted().toList()){
            var y=targetAt(s,p);if(y==null || p.getY()<=y || !level.hasChunkAt(p))continue;
            var actual=level.getBlockState(p);if(actual.isAir() || !s.terrain().supersededPaid(p,actual))continue;
            var j=new com.google.gson.JsonObject();j.addProperty("x",p.getX());j.addProperty("y",p.getY());j.addProperty("z",p.getZ());
            j.addProperty("goalY",y);j.addProperty("actual",actual.toString());j.addProperty("roadColumn",roadColumn(level,s,p));
            j.addProperty("protected",!excluded(level,s,p).isEmpty());j.addProperty("pending",TerrainService.pendingAt(s,p));legacy.add(j);
        }
        out.add("obsoleteAboveGround",legacy);
        out.addProperty("fixedRoadCheckpoints",RoadSpaceData.get(level).decorationPositions().stream()
                .filter(p->fixedRoad(level,s,p) && Objects.equals(w.anchors.get(key(p.getX(),p.getZ())),p.getY())).count());
        out.addProperty("heightVersion",w.heightVersion);out.addProperty("supersededOrders",s.terrain().projects.values().stream().filter(p->p.heightSuperseded).count());
        out.addProperty("checkpoints",w.anchors.size());out.addProperty("blockedEdges",w.barriers.values().stream().mapToInt(Integer::bitCount).sum()/2);out.addProperty("stableSweeps",w.stableSweeps);
        var checkpoints=new com.google.gson.JsonArray();w.anchors.forEach((k,y)->{var p=BlockPos.of(k);var a=new com.google.gson.JsonObject();a.addProperty("x",p.getX());a.addProperty("z",p.getZ());a.addProperty("y",y);a.addProperty("target",w.goals.getOrDefault(k,y));checkpoints.add(a);});out.add("checkpointHeights",checkpoints);
        var local=new com.google.gson.JsonArray();w.baselines.forEach((k,y)->{var p=BlockPos.of(k);var a=new com.google.gson.JsonObject();a.addProperty("x",p.getX());a.addProperty("z",p.getZ());a.addProperty("y",y);a.addProperty("target",w.goals.getOrDefault(k,y));local.add(a);});out.add("localHeights",local);
        out.addProperty("deferredColumns",w.deferred.size());
        var skipped=new com.google.gson.JsonObject();w.skipped.forEach(skipped::addProperty);out.add("skipped",skipped);
        var goals=new LinkedHashMap<>(w.goals);var planned=new HashSet<>(w.planned);var wet=new HashSet<>(w.wet);var expected=new HashMap<Long,BlockState>();
        for(var project:s.terrain().projects.values())if(project.publicGround && !project.cancelled){for(var p:project.groundSurfaces){long k=key(p.getX(),p.getZ());goals.put(k,p.getY());planned.add(k);var goal=project.template().placementAt(p);if(goal!=null)expected.put(k,goal.state());}for(var step:project.steps)if(project.groundSurfaces.contains(step.position()) && TerrainService.replaceableWater(step.original()))wet.add(key(step.position().getX(),step.position().getZ()));}
        out.addProperty("publishedSurfaces",planned.size());
        var actual=new com.google.gson.JsonArray();for(var e:goals.entrySet()){var p=BlockPos.of(e.getKey()).atY(e.getValue());var a=new com.google.gson.JsonObject();var xyz=new com.google.gson.JsonArray();xyz.add(p.getX());xyz.add(p.getY());xyz.add(p.getZ());a.add("position",xyz);a.addProperty("planned",planned.contains(e.getKey()));a.addProperty("initialWet",wet.contains(e.getKey()));a.addProperty("loaded",level.hasChunkAt(p));if(level.hasChunkAt(p)){a.addProperty("actual",level.getBlockState(p).toString());a.addProperty("above",level.getBlockState(p.above()).toString());var state=level.getBlockState(p);var facing=w.stairs.get(e.getKey());var desired=expected.get(e.getKey());boolean ground=desired!=null?TerrainService.satisfied(state,desired):facing!=null?BlueprintService.matchesPlacedState(state,Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,facing)):TerrainService.satisfied(state,Blocks.DIRT.defaultBlockState()) || state.is(Blocks.COBBLESTONE);a.addProperty("surfaceReady",ground && level.getBlockState(p.above()).getFluidState().isEmpty() && level.getBlockState(p.above(2)).getFluidState().isEmpty() && level.getBlockState(p.above()).getCollisionShape(level,p.above()).isEmpty() && level.getBlockState(p.above(2)).getCollisionShape(level,p.above(2)).isEmpty());}actual.add(a);}out.add("surfaces",actual);return out;
    }
}
