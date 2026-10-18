package com.hkin.exiledsettlement.region;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.*;
import java.util.function.Predicate;

/** Persisted surface membership. Bounds are only a broad phase, never production membership. */
public final class ProductionRegion {
    public enum Use { CROP, FRUIT, WATER, PATH, BOUNDARY, GATE, PASTURE, FOREST }
    public record Cell(BlockPos ground, Use use, ResourceLocation production, BuildingType type) { }
    public final BuildingType type;
    public final Direction facing;
    public Direction gateFacing;
    public final Map<Long,Direction> gateFacings=new LinkedHashMap<>();
    public Direction gateFacing(BlockPos p){return gateFacings.getOrDefault(key(p.getX(),p.getZ()),gateFacing);}
    public final BlockPos first,second;
    public final Map<Long,Cell> cells=new LinkedHashMap<>();
    public final Set<Long> preserved=new HashSet<>();
    public UUID workingBuilding;
    public final Set<Long> readyCrops=new HashSet<>(),readyPasture=new HashSet<>();
    /** Real tilling already completed; weather and trampling become farmer maintenance. */
    public final Set<Long> cultivated=new HashSet<>();
    public long surveyCursor;
    public boolean surveyed,prepared,layoutReady;
    public int preparationCursor,revision,workCursor;
    public int layoutVersion,selectionMargin;
    public Integer flatY;
    public int groundVersion;
    public boolean groundPaused,groundMaintenance;
    /** Opt-in at placement only. Existing saved plots retain their exact membership and facilities. */
    public boolean rectangularSelection;
    public int gridX,gridZ;
    public final CompoundTag animalSafety=new CompoundTag();
    public final Set<Long> readyForest=new HashSet<>();
    public final Map<Long,String> groundBlockers=new LinkedHashMap<>();
    public boolean levelled(){return agricultural() || type==BuildingType.TREE_FARM || type==BuildingType.PASTURE_AREA;}
    public boolean plainSoil(){return type==BuildingType.TREE_FARM || type==BuildingType.PASTURE_AREA;}
    public boolean groundReady(Level level,Cell c){
        return level.hasChunkAt(c.ground()) && FarmEarthworkPlan.soil(level.getBlockState(c.ground()))
            && FarmEarthworkPlan.support(level,c.ground().below());
    }
    public final Set<Long> oldIrrigation=new LinkedHashSet<>();
    public boolean waterSurveyDone;
    public final Set<Long> waterFill=new LinkedHashSet<>();
    public final List<Long> farmPath=new ArrayList<>();
    public final Map<Long,Integer> gradeOrigins=new LinkedHashMap<>();
    public final Set<Integer> preparationRetries=new LinkedHashSet<>();
    public String status="正在勘察选区，保留已确认地坪";
    public long nextReview;
    public int reviewCursor;
    public String reviewStatus="按确认地坪施工，区域独立";
    public final Set<Long> reviewFixed=new LinkedHashSet<>();
    private Level layoutLevel;
    private BuildingBlueprintTemplate template;
    private List<Cell> ordered;
    private Set<ResourceLocation> productions;
    private BlockPos cachedMin,cachedMax;
    public final Map<ResourceLocation,Integer> animalFloors=new LinkedHashMap<>();
    public final Set<Long> waterBarriers=new LinkedHashSet<>();
    public final Set<Long> oldFences=new LinkedHashSet<>();
    public final Set<Long> naturalTrees=new HashSet<>();
    public final Set<Long> legacyWater=new HashSet<>();
    public final Map<Long,Direction> stairs=new LinkedHashMap<>();
    public Set<ResourceLocation> productions(){if(productions==null){productions=new LinkedHashSet<>();cells.values().forEach(c->productions.add(c.production()));}return Collections.unmodifiableSet(productions);}
    public ProductionRegion(BuildingType type,BlockPos first,BlockPos second,Direction facing) {
        this.type=type;this.first=first.immutable();this.second=second.immutable();this.facing=facing;this.gateFacing=facing;gridX=Math.min(first.getX(),second.getX())+2;gridZ=Math.min(first.getZ(),second.getZ())+2;
        flatY=first.getY()-1;
        selectionMargin=0;
    }
    public static long key(int x,int z){return new BlockPos(x,0,z).asLong();}
    public Cell cell(BlockPos p){return cells.get(key(p.getX(),p.getZ()));}
    public boolean contains(BlockPos p){var c=cell(p);return c!=null && p.getY()>=c.ground().getY()-1 && p.getY()<=c.ground().getY()+4;}
    public List<Cell> ordered(){if(ordered==null)ordered=List.copyOf(cells.values());return ordered;}
    public void invalidate(){revision++;template=null;ordered=null;productions=null;cachedMin=null;cachedMax=null;}
    public ResourceLocation production(BlockPos p,ResourceLocation fallback){var c=cell(p);return c==null?fallback:c.production();}
    public boolean agricultural(){return FarmEarthworkPlan.applies(type);}
    public static boolean acceptsWorkplace(BuildingType a,BuildingType b){return a==b || FarmEarthworkPlan.applies(a)&&FarmEarthworkPlan.applies(b);}
    public int planeY(){return flatY==null?first.getY()-1:flatY;}
    public int usableArea(){return (int)cells.values().stream().filter(c->c.use()==Use.FOREST || c.use()==Use.PASTURE || c.use()==Use.CROP || c.use()==Use.FRUIT).count();}
    public BlockPos min(){if(cachedMin==null)cachedMin=bound(false);return cachedMin;}
    public BlockPos max(){if(cachedMax==null)cachedMax=bound(true);return cachedMax;}
    private BlockPos bound(boolean maximum) {
        int x=first.getX(),y=first.getY(),z=first.getZ();
        for(var c:cells.values()){var p=c.ground();x=maximum?Math.max(x,p.getX()):Math.min(x,p.getX());y=maximum?Math.max(y,p.getY()+3):Math.min(y,p.getY());z=maximum?Math.max(z,p.getZ()):Math.min(z,p.getZ());}
        return new BlockPos(x,y,z);
    }
    public boolean survey(Level level,Predicate<BlockPos> allowed,int budget) {
        if(surveyed)return true;
        int minX=Math.min(first.getX(),second.getX())-selectionMargin,minZ=Math.min(first.getZ(),second.getZ())-selectionMargin;
        long width=(long)Math.abs(first.getX()-second.getX())+1+2*selectionMargin,depth=(long)Math.abs(first.getZ()-second.getZ())+1+2*selectionMargin;
        while(surveyCursor<width*depth && budget-->0) {
            int x=(int)(minX+surveyCursor%width),z=(int)(minZ+surveyCursor/width);
            var seed=new BlockPos(x,first.getY(),z);
            if(!level.hasChunkAt(seed)){status="等待正常加载地形："+seed.toShortString();return false;}
            var ground=new BlockPos(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1,z);
            ground=FarmVegetation.ground(level,ground,this);
            var state=level.getBlockState(ground);
            while(ground.getY()>level.getMinBuildHeight() && (state.getBlock() instanceof BushBlock || state.is(Blocks.SNOW) || state.getBlock() instanceof FenceBlock || state.getBlock() instanceof FenceGateBlock)){ground=ground.below();state=level.getBlockState(ground);}
            surveyCursor++;
            if(rectangularSelection) {
                if(!allowed.test(seed.below()) || !allowed.test(ground) || !allowed.test(ground.above(2))) {
                    surveyCursor--;status="完整选区等待清除保护冲突："+seed.toShortString();return false;
                }
            } else {
                if(!allowed.test(ground) || !allowed.test(ground.above(2)) || !(com.hkin.exiledsettlement.terrain.TerrainService.replaceableWater(state) || state.isFaceSturdy(level,ground,Direction.UP) || FarmEarthworkPlan.soil(state) || FarmVegetation.paidGround(level,ground,state,this)))continue;
                if(!com.hkin.exiledsettlement.terrain.TerrainService.natural(state) && !FarmEarthworkPlan.soil(state) && !com.hkin.exiledsettlement.terrain.TerrainService.replaceableWater(state) && !FarmVegetation.paidGround(level,ground,state,this))continue;
            }
            waterFill.remove(key(x,z));
            if(com.hkin.exiledsettlement.terrain.TerrainService.replaceableWater(state))waterFill.add(key(x,z));
            invalidate();cells.put(key(x,z),new Cell(ground,agricultural()?Use.CROP:type==BuildingType.TREE_FARM?Use.FOREST:Use.PASTURE,BuildingProductionService.defaultFor(type),type));
        }
        if(surveyCursor<width*depth)return false;
        if(plainSoil() && !RegionGroundService.excludeConnectedWater(level,this)) {
            surveyCursor=0;status="完整选区涉及外界连通水体，等待调整选区或水体";return false;
        }
        surveyed=true;waterSurveyDone=true;rebuildUses(level);status=cells.isEmpty()?"没有可用陆地":"地形区域已勘察";return true;
    }
    public static boolean hydrated(BlockPos soil,BlockPos water){return Math.abs(soil.getX()-water.getX())<=4 && Math.abs(soil.getZ()-water.getZ())<=4 && water.getY()>=soil.getY() && water.getY()<=soil.getY()+1;}
    public void rebuildUses(Level level){layoutLevel=level;try{rebuildUses();}finally{layoutLevel=null;}}
    public void rebuildUses() {
        if(agricultural() && layoutVersion<FarmLayout.VERSION)FarmLayout.grade(this);
        if(plainSoil() && groundVersion<RegionGroundLayout.VERSION){RegionGroundLayout.grade(this);groundVersion=RegionGroundLayout.VERSION;}
        stairs.clear();
        // Small/narrow agricultural selections must retain crop space instead of becoming all fence.
        boolean fenced=!agricultural() || Math.abs(first.getX()-second.getX())>=2 && Math.abs(first.getZ()-second.getZ())>=2;
        for(var e:List.copyOf(cells.entrySet())) {
            var c=e.getValue();var p=c.ground();boolean edge=false;
            for(var d:Direction.Plane.HORIZONTAL)if(!cells.containsKey(key(p.getX()+d.getStepX(),p.getZ()+d.getStepZ())))edge=true;
            edge=edge && fenced;

            Use use=edge?Use.BOUNDARY:c.type()==BuildingType.PASTURE_AREA?Use.PASTURE:c.type()==BuildingType.TREE_FARM?Use.FOREST:Use.CROP;
            if(!edge && c.use()==Use.WATER)use=Use.WATER;
            if(!edge && c.type()==BuildingType.MELON_FIELD && Math.floorMod(p.getX(),3)==0)use=Use.FRUIT;
            if(!plainSoil() && !edge && preserved.contains(e.getKey()) && c.use()!=Use.BOUNDARY && c.use()!=Use.GATE && (c.use()!=Use.PATH || layoutVersion>=2))use=c.use();
            if(!edge && (stairs.containsKey(e.getKey()) || waterBarriers.contains(e.getKey())))use=Use.PATH;
            cells.put(e.getKey(),new Cell(p,use,c.production(),c.type()));
        }
        Cell gate=null;
        for(var side:List.of(facing,facing.getClockWise(),facing.getCounterClockWise(),facing.getOpposite())) {
            gate=cells.values().stream().filter(c->c.use()==Use.BOUNDARY && !cells.containsKey(key(c.ground().getX()+side.getStepX(),c.ground().getZ()+side.getStepZ())) && gateReachable(c,side))
                    .min(Comparator.comparingDouble(c->c.ground().distSqr(farmPath.isEmpty() || !cells.containsKey(farmPath.getFirst())?first:cells.get(farmPath.getFirst()).ground()))).orElse(null);
            if(gate!=null){gateFacing=side;break;}
        }
        gateFacings.clear();
        if(gate!=null){long k=key(gate.ground().getX(),gate.ground().getZ());cells.put(k,new Cell(gate.ground(),Use.GATE,gate.production(),gate.type()));gateFacings.put(k,gateFacing);}
        if(levelled() && usableArea()>=49)for(var side:Direction.Plane.HORIZONTAL){
            var groups=new TreeMap<Integer,List<Cell>>();
            for(var c:cells.values())if(c.use()==Use.BOUNDARY && !cells.containsKey(key(c.ground().getX()+side.getStepX(),c.ground().getZ()+side.getStepZ())) && gateReachable(c,side)){
                int lateral=side.getAxis()==Direction.Axis.X?c.ground().getZ():c.ground().getX();
                groups.computeIfAbsent(Math.floorDiv(lateral,24),k->new ArrayList<>()).add(c);
            }
            for(var candidates:groups.values()){
                candidates.sort(Comparator.comparingInt(c->side.getAxis()==Direction.Axis.X?c.ground().getZ():c.ground().getX()));
                var c=candidates.get(candidates.size()/2);
                if(gateFacings.keySet().stream().map(cells::get).anyMatch(g->g.ground().distManhattan(c.ground())<8))continue;
                long k=key(c.ground().getX(),c.ground().getZ());cells.put(k,new Cell(c.ground(),Use.GATE,c.production(),c.type()));gateFacings.put(k,side);
            }
        }
        if(agricultural()) {
            for(var source:List.copyOf(cells.values()))if(source.use()==Use.WATER)for(var side:Direction.Plane.HORIZONTAL) {
                var n=cell(source.ground().relative(side));if(n==null || n.ground().getY()>=source.ground().getY())continue;
                long at=key(n.ground().getX(),n.ground().getZ());gradeOrigins.putIfAbsent(at,n.ground().getY());
                var use=n.use()==Use.BOUNDARY || n.use()==Use.GATE?n.use():Use.PATH;
                cells.put(at,new Cell(n.ground().atY(source.ground().getY()),use,n.production(),n.type()));waterBarriers.add(at);
            }
            stairs.clear();FarmLayout.plan(this);layoutVersion=FarmLayout.VERSION;
            var coverage=new HashSet<Long>();
            for(var c:cells.values())if(c.use()==Use.WATER)cover(coverage,c.ground());
            for(var seed:List.copyOf(cells.values())) {
                var c=cell(seed.ground());if(c==null || c.use()!=Use.CROP || preserved.contains(key(c.ground().getX(),c.ground().getZ())) && !gradeOrigins.containsKey(key(c.ground().getX(),c.ground().getZ())) && !waterFill.contains(key(c.ground().getX(),c.ground().getZ())) || coverage.contains(c.ground().asLong()))continue;
                var p=c.ground();Cell chosen=canContainWater(c)?c:null;
                if(chosen==null)for(var d:Direction.Plane.HORIZONTAL){var n=cell(p.relative(d));if(n!=null && n.use()==Use.CROP && !preserved.contains(key(n.ground().getX(),n.ground().getZ())) && hydrated(p,n.ground()) && canContainWater(n)){chosen=n;break;}}
                // Dry farmland remains usable under vanilla growth rules. Do not consume the last crop
                // cell for water or reject a narrow plot merely because an internal source cannot fit.
                if(chosen==null || !watersAnotherCrop(chosen))continue;
                var wet=new Cell(chosen.ground(),Use.WATER,chosen.production(),chosen.type());cells.put(key(chosen.ground().getX(),chosen.ground().getZ()),wet);cover(coverage,wet.ground());
                // A full paid side barrier at the source height seals every downhill outlet.
                for(var d:Direction.Plane.HORIZONTAL) {
                    var n=cell(wet.ground().relative(d));if(n==null || n.ground().getY()>=wet.ground().getY())continue;
                    long at=key(n.ground().getX(),n.ground().getZ());var use=n.use()==Use.BOUNDARY || n.use()==Use.GATE?n.use():Use.PATH;
                    cells.put(at,new Cell(n.ground().atY(wet.ground().getY()),use,n.production(),n.type()));waterBarriers.add(at);
                }
            }
        }
        invalidate();
    }
    private boolean gateReachable(Cell c,Direction side){
        var n=cell(c.ground().relative(side.getOpposite()));
        if(!(agricultural()?FarmLayout.usable(this,n):n!=null && n.use()!=Use.BOUNDARY))return false;
        if(Math.abs(n.ground().getY()-c.ground().getY())>1)return false;
        if(layoutLevel==null)return true;
        var outside=c.ground().relative(side);if(!layoutLevel.hasChunkAt(outside))return false;
        int y=layoutLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,outside.getX(),outside.getZ())-1;
        var at=FarmVegetation.ground(layoutLevel,outside.atY(y),this);y=at.getY();var state=layoutLevel.getBlockState(at);
        while(y>layoutLevel.getMinBuildHeight() && (state.getBlock() instanceof BushBlock || state.is(Blocks.SNOW) || state.getBlock() instanceof FenceBlock || state.getBlock() instanceof FenceGateBlock)){at=outside.atY(--y);state=layoutLevel.getBlockState(at);}
        return state.getFluidState().isEmpty() && (Math.abs(y-c.ground().getY())<=1 || plainSoil() && (FarmEarthworkPlan.soil(state) || com.hkin.exiledsettlement.terrain.TerrainService.natural(state)));
    }
    private boolean canContainWater(Cell c){for(var d:Direction.Plane.HORIZONTAL){var n=cell(c.ground().relative(d));if(n==null || n.ground().getY()<c.ground().getY() && preserved.contains(key(n.ground().getX(),n.ground().getZ())))return false;}return true;}
    private boolean watersAnotherCrop(Cell source){for(var d:Direction.Plane.HORIZONTAL){var n=cell(source.ground().relative(d));if(n!=null && n.use()==Use.CROP && hydrated(n.ground(),source.ground()))return true;}return false;}
    private static void cover(Set<Long> cells,BlockPos water){for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++)for(int dy=0;dy>=-1;dy--)cells.add(water.offset(dx,dy,dz).asLong());}
    public BuildingBlueprintTemplate template() {
        if(template!=null)return template;
        var placements=new ArrayList<BlueprintPlacement>();var entrances=new ArrayList<BlueprintEntrance>();
        for(var c:cells.values()) {
            var p=c.ground();
            switch(c.use()) {
                case CROP -> placements.add(new BlueprintPlacement(p,Blocks.DIRT.defaultBlockState(),Items.DIRT));
                case FRUIT -> placements.add(new BlueprintPlacement(p,Blocks.DIRT.defaultBlockState(),Items.DIRT));
                case WATER -> placements.add(legacyWater.contains(key(p.getX(),p.getZ()))?new BlueprintPlacement(p,Blocks.WATER.defaultBlockState(),Items.WATER_BUCKET,BlueprintPlacementMode.FARM_WATER):new BlueprintPlacement(p,Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.TOP).setValue(SlabBlock.WATERLOGGED,true),Items.WATER_BUCKET));
                case BOUNDARY -> {
                    placements.add(new BlueprintPlacement(p,Blocks.DIRT.defaultBlockState(),Items.DIRT));
                    int top=p.getY()+1;
                    for(var d:Direction.Plane.HORIZONTAL){var n=cells.get(key(p.getX()+d.getStepX(),p.getZ()+d.getStepZ()));if(n!=null)top=Math.max(top,n.ground().getY()+1);}
                    for(int y=p.getY()+1;y<top;y++)placements.add(new BlueprintPlacement(p.atY(y),Blocks.COBBLESTONE.defaultBlockState(),Items.COBBLESTONE));
                    placements.add(new BlueprintPlacement(p.atY(top),Blocks.OAK_FENCE.defaultBlockState(),Items.OAK_FENCE));
                }
                case GATE -> {placements.add(new BlueprintPlacement(p,Blocks.DIRT.defaultBlockState(),Items.DIRT));var at=p.above();var direction=gateFacing(p);placements.add(new BlueprintPlacement(at,Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING,direction),Items.OAK_FENCE_GATE));entrances.add(new BlueprintEntrance(at,direction));}
                case PATH -> {var direction=stairs.get(key(p.getX(),p.getZ()));if(direction!=null)placements.add(new BlueprintPlacement(p,Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,direction),Items.COBBLESTONE_STAIRS));else if(waterBarriers.contains(key(p.getX(),p.getZ())))placements.add(new BlueprintPlacement(p,Blocks.DIRT.defaultBlockState(),Items.DIRT));else placements.add(new BlueprintPlacement(p,Blocks.DIRT_PATH.defaultBlockState(),null));}
                case PASTURE, FOREST -> placements.add(new BlueprintPlacement(p,Blocks.DIRT.defaultBlockState(),Items.DIRT));
            }
        }
        var min=min();var max=max();var anchor=entrances.isEmpty()?first:entrances.getFirst().position();
        placements.sort(Comparator.comparingInt((BlueprintPlacement p)->p.position().getY()).thenComparingLong(p->p.position().asLong()));
        return template=new BuildingBlueprintTemplate(type,anchor,facing,max.getX()-min.getX()+1,max.getZ()-min.getZ()+1,max.getY()-min.getY()+1,min,max,0,placements,entrances);
    }
    public static ProductionRegion legacy(BuildingRecord b) {
        if(b.region()!=null)return b.region();
        var r=new ProductionRegion(b.type(),b.minCorner().atY(b.position().getY()),b.maxCorner().atY(b.position().getY()),b.facing());var t=BlueprintTemplates.createForBuilding(b);
        for(int x=b.minCorner().getX();x<=b.maxCorner().getX();x++)for(int z=b.minCorner().getZ();z<=b.maxCorner().getZ();z++) {
            var p=new BlockPos(x,b.position().getY()-1,z);var soil=t.placementAt(p);var above=t.placementAt(p.above());Use use;
            if(above!=null && above.state().getBlock() instanceof FenceGateBlock)use=Use.GATE;
            else if(above!=null && above.state().getBlock() instanceof FenceBlock)use=Use.BOUNDARY;
            else if(soil!=null && !soil.state().getFluidState().isEmpty()){use=Use.WATER;if(soil.state().is(Blocks.WATER))r.legacyWater.add(key(x,z));}
            else if(soil!=null && soil.state().is(Blocks.FARMLAND))use=Use.CROP;
            else if(b.type()==BuildingType.PASTURE_AREA)use=Use.PASTURE;
            else if(b.type()==BuildingType.TREE_FARM)use=Use.FOREST;
            else use=soil!=null && soil.state().is(Blocks.DIRT)?Use.FRUIT:Use.PATH;
            r.cells.put(key(x,z),new Cell(p,use,b.productionId(),b.type()));
        }
        r.surveyed=true;r.prepared=true;return r;
    }
    public CompoundTag save() {
        var t=new CompoundTag();t.putBoolean("RectangularSelection",rectangularSelection);t.putInt("GroundVersion",groundVersion);t.putBoolean("GroundPaused",groundPaused);t.putBoolean("GroundMaintenance",groundMaintenance);t.putInt("GridX",gridX);t.putInt("GridZ",gridZ);t.put("AnimalSafety",animalSafety.copy());
        var blockers=new ListTag();groundBlockers.forEach((at,reason)->{var a=new CompoundTag();a.putLong("At",at);a.putString("Reason",reason);blockers.add(a);});t.put("GroundBlockers",blockers);
        if(flatY!=null)t.putInt("FlatY",flatY);t.putLongArray("OldIrrigation",oldIrrigation.stream().mapToLong(Long::longValue).toArray());t.putLongArray("NaturalTrees",naturalTrees.stream().mapToLong(Long::longValue).toArray());t.putInt("Version",1);t.putInt("LayoutVersion",layoutVersion);t.putInt("SelectionMargin",selectionMargin);t.putBoolean("WaterSurveyDone",waterSurveyDone);t.putLongArray("WaterFill",waterFill.stream().mapToLong(Long::longValue).toArray());t.putLongArray("FarmPath",farmPath.stream().mapToLong(Long::longValue).toArray());t.putInt("Revision",revision);t.putIntArray("PreparationRetries",preparationRetries.stream().mapToInt(Integer::intValue).toArray());t.putString("Type",type.serializedName());t.putString("Facing",facing.getName());t.putString("GateFacing",gateFacing.getName());t.putLong("First",first.asLong());t.putLong("Second",second.asLong());t.putLong("SurveyCursor",surveyCursor);t.putBoolean("Surveyed",surveyed);t.putBoolean("Prepared",prepared);t.putBoolean("LayoutReady",layoutReady);t.putInt("PreparationCursor",preparationCursor);t.putString("Status",status);if(workingBuilding!=null)t.putUUID("WorkingBuilding",workingBuilding);
        t.putLong("NextReview",nextReview);t.putInt("ReviewCursor",reviewCursor);t.putString("ReviewStatus",reviewStatus);
        t.putLongArray("ReviewFixed",reviewFixed.stream().mapToLong(Long::longValue).toArray());
        var grading=new ListTag();gradeOrigins.forEach((at,y)->{var g=new CompoundTag();g.putLong("At",at);g.putInt("Y",y);grading.add(g);});t.put("GradeOrigins",grading);
        var pages=new ListTag();var list=new ListTag();
        for(var c:cells.values()){var a=new CompoundTag();a.putLong("At",c.ground().asLong());a.putString("Use",c.use().name());a.putString("Production",c.production().toString());a.putString("Type",c.type().serializedName());list.add(a);if(list.size()==1024){var page=new CompoundTag();page.put("Cells",list);pages.add(page);list=new ListTag();}}
        if(!list.isEmpty()){var page=new CompoundTag();page.put("Cells",list);pages.add(page);}t.put("Pages",pages);
        t.putLongArray("Cultivated",cultivated.stream().mapToLong(Long::longValue).toArray());t.putLongArray("Preserved",preserved.stream().mapToLong(Long::longValue).toArray());t.putLongArray("LegacyWater",legacyWater.stream().mapToLong(Long::longValue).toArray());t.putLongArray("OldFences",oldFences.stream().mapToLong(Long::longValue).toArray());t.putLongArray("WaterBarriers",waterBarriers.stream().mapToLong(Long::longValue).toArray());
        var gates=new ListTag();gateFacings.forEach((at,d)->{var a=new CompoundTag();a.putLong("At",at);a.putString("Facing",d.getName());gates.add(a);});t.put("GateFacings",gates);
        var stairList=new ListTag();stairs.forEach((at,d)->{var a=new CompoundTag();a.putLong("At",at);a.putString("Facing",d.getName());stairList.add(a);});t.put("Stairs",stairList);
        var floors=new ListTag();animalFloors.forEach((id,n)->{var a=new CompoundTag();a.putString("Type",id.toString());a.putInt("Count",n);floors.add(a);});t.put("AnimalFloors",floors);return t;
    }
    public static ProductionRegion load(CompoundTag t) {
        if(t.getInt("Version")!=1)throw new IllegalArgumentException("Unknown production region version");
        var r=new ProductionRegion(BuildingType.fromSerializedName(t.getString("Type")),BlockPos.of(t.getLong("First")),BlockPos.of(t.getLong("Second")),Direction.byName(t.getString("Facing")));
        r.rectangularSelection=t.getBoolean("RectangularSelection");
        r.groundVersion=t.getInt("GroundVersion");r.groundPaused=t.getBoolean("GroundPaused");r.groundMaintenance=t.getBoolean("GroundMaintenance");if(t.contains("GridX")){r.gridX=t.getInt("GridX");r.gridZ=t.getInt("GridZ");}r.animalSafety.merge(t.getCompound("AnimalSafety"));
        var blockers=t.getList("GroundBlockers",Tag.TAG_COMPOUND);for(int i=0;i<blockers.size();i++){var a=blockers.getCompound(i);r.groundBlockers.put(a.getLong("At"),a.getString("Reason"));}
        r.nextReview=t.getLong("NextReview");r.reviewCursor=t.getInt("ReviewCursor");if(t.contains("ReviewStatus"))r.reviewStatus=t.getString("ReviewStatus");
        for(long at:t.getLongArray("ReviewFixed"))r.reviewFixed.add(at);
        r.waterSurveyDone=t.getBoolean("WaterSurveyDone");for(long at:t.getLongArray("WaterFill"))r.waterFill.add(at);
        var grading=t.getList("GradeOrigins",Tag.TAG_COMPOUND);for(int i=0;i<grading.size();i++){var g=grading.getCompound(i);r.gradeOrigins.put(g.getLong("At"),g.getInt("Y"));}
        if(t.contains("GateFacing")){var direction=Direction.byName(t.getString("GateFacing"));if(direction!=null && direction.getAxis().isHorizontal())r.gateFacing=direction;}r.flatY=t.contains("FlatY")?t.getInt("FlatY"):null;for(long at:t.getLongArray("OldIrrigation"))r.oldIrrigation.add(at);r.layoutVersion=t.getInt("LayoutVersion");r.selectionMargin=t.getInt("SelectionMargin");for(long at:t.getLongArray("FarmPath"))r.farmPath.add(at);
        r.revision=t.getInt("Revision");for(int index:t.getIntArray("PreparationRetries"))r.preparationRetries.add(index);r.surveyCursor=t.getLong("SurveyCursor");r.surveyed=t.getBoolean("Surveyed");r.prepared=t.getBoolean("Prepared");r.layoutReady=t.contains("LayoutReady")?t.getBoolean("LayoutReady"):t.getBoolean("Merged");r.preparationCursor=t.getInt("PreparationCursor");r.status=t.getString("Status");if(t.hasUUID("WorkingBuilding"))r.workingBuilding=t.getUUID("WorkingBuilding");
        var pages=t.getList("Pages",Tag.TAG_COMPOUND);for(int i=0;i<pages.size();i++){var list=pages.getCompound(i).getList("Cells",Tag.TAG_COMPOUND);for(int j=0;j<list.size();j++){var a=list.getCompound(j);var at=BlockPos.of(a.getLong("At"));r.cells.put(key(at.getX(),at.getZ()),new Cell(at,Use.valueOf(a.getString("Use")),ResourceLocation.parse(a.getString("Production")),BuildingType.fromSerializedName(a.getString("Type"))));}}
        for(long at:t.getLongArray("Cultivated"))r.cultivated.add(at);for(long at:t.getLongArray("Preserved"))r.preserved.add(at);for(long at:t.getLongArray("NaturalTrees"))r.naturalTrees.add(at);for(long at:t.getLongArray("LegacyWater"))r.legacyWater.add(at);for(long at:t.getLongArray("OldFences"))r.oldFences.add(at);for(long at:t.getLongArray("WaterBarriers"))r.waterBarriers.add(at);
        var gates=t.getList("GateFacings",Tag.TAG_COMPOUND);for(int i=0;i<gates.size();i++){var a=gates.getCompound(i);var d=Direction.byName(a.getString("Facing"));if(d!=null && d.getAxis().isHorizontal())r.gateFacings.put(a.getLong("At"),d);}
        var stairList=t.getList("Stairs",Tag.TAG_COMPOUND);for(int i=0;i<stairList.size();i++){var a=stairList.getCompound(i);r.stairs.put(a.getLong("At"),Direction.byName(a.getString("Facing")));}
        var floors=t.getList("AnimalFloors",Tag.TAG_COMPOUND);for(int i=0;i<floors.size();i++){var a=floors.getCompound(i);r.animalFloors.put(ResourceLocation.parse(a.getString("Type")),a.getInt("Count"));}return r;
    }
}
