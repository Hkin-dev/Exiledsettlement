package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.command.*;
import com.hkin.exiledsettlement.debug.SaveAcceptanceService;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.registry.ModBlocks;
import com.hkin.exiledsettlement.building.WarehouseInventoryService;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import java.util.*;

/** Explicit isolated fixtures. Setup is never credited as resident work. */
public final class Dispatch88Checks {
    public static final BlockPos CORE=new BlockPos(52000,250,52000);
    private static void check(boolean ok,String name,List<String> out){if(!ok)throw new IllegalStateException(name);out.add(name);}
    private static long key(BlockPos p){return BlockPos.asLong(p.getX(),0,p.getZ());}
    private static void solve(SettlementRecord s,PublicGroundService.State w){
        for(int i=0;i<20000 && w.phase!=5;i++)switch(w.phase){
            case 8 -> PublicGroundService.spread(w);case 9 -> PublicGroundService.divide(w);
            case 12 -> PublicGroundService.spreadRoads(w);case 13 -> PublicGroundService.initializeRoadRegions(w);
            case 11 -> PublicGroundService.envelopes(w);case 10 -> PublicGroundService.relax(w);
            case 6 -> PublicGroundService.seams(w);case 7 -> PublicGroundService.passages(s,w);
            default -> throw new IllegalStateException("unexpected solver phase "+w.phase);
        }
        if(w.phase!=5)throw new IllegalStateException("solver did not converge");
    }
    public static String run(ServerLevel l){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(51900,250,51900);var s=new SettlementRecord(UUID.randomUUID(),"Dispatch88 detached",core,UUID.randomUUID());
        var b=s.addBlueprint(BuildingType.HOUSE,core.east(14),core.east(14),Direction.NORTH,s.ownerId(),false);
        var at=core.offset(3,-1,0);var optional=new AutoRoadState.Route(UUID.randomUUID(),at,List.of(at));
        var r=new AutoRoadState.Route(b.id(),at,List.of(at,at.east(),at.east(2)));r.width=3;r.centerline=r.cells;r.constructionGradeFixed=true;
        s.autoRoads().routes.put("required",r);s.autoRoads().routes.put("optional",optional);
        var w=s.terrain().publicGround;w.phase=11;w.roadsIntegrated=false;
        var out=new ArrayList<String>();
        check(PublicGroundService.planning(s),"public solution still pending",out);
        check(AutoRoadService.earthworkPlanningReady(s,r),"P0 access can publish during phase11",out);
        check(!AutoRoadService.earthworkPlanningReady(s,optional),"optional road keeps joint transaction gate",out);
        check(PublicGroundService.targetAt(s,at)==249,"required road owns stable shared column height",out);
        w.goals.put(key(at),246);w.phase=14;
        check(PublicGroundService.targetAt(s,at)==249,"provisional public goal cannot override P0 road",out);
        for(int phase:List.of(1,4,8,10,11,13,14)){
            w.phase=phase;check(AutoRoadService.earthworkPlanningReady(s,r),"P0 continues in public phase "+phase,out);
        }
        var saved=s.autoRoads().save();var loaded=new AutoRoadState();loaded.load(saved);
        check(loaded.routes.get("required").constructionGradeFixed,"fixed construction grade persists",out);
        r.suppressed=true;check(!AutoRoadService.earthworkPlanningReady(s,r),"explicit cancelled route cannot bypass joint gate",out);r.suppressed=false;
        PublicGroundService.pinConstructionGrades(s,w);check(w.anchors.get(key(at))==249,"required plane feeds public fixed checkpoints",out);
        w.goals.clear();w.baselines.clear();w.anchors.clear();w.roadHeights.clear();w.barriers.clear();
        for(int x=-2;x<10;x++)for(int z=-4;z<=4;z++){var p=at.offset(x,0,z);w.goals.put(key(p),249);w.baselines.put(key(p),249);}
        PublicGroundService.pinConstructionGrades(s,w);PublicGroundService.startSources(w);solve(s,w);
        check(r.cells.stream().allMatch(p->w.goals.get(key(p))==p.getY()),"solved public plane retains P0 corridor",out);
        check(w.goals.values().stream().allMatch(y->y==249),"flat surroundings remain flat",out);
        var end=at.east(8);AutoRoadPlanner.begin(l,s,b.id(),at,end,"required-new");PublicGroundService.requestReview(s);
        check(AutoRoadPlanner.busy(s),"public review preserves necessary route search",out);AutoRoadPlanner.invalidate(s);
        AutoRoadPlanner.begin(l,s,UUID.randomUUID(),at,end,"optional-new");PublicGroundService.requestReview(s);
        check(!AutoRoadPlanner.busy(s),"public review still invalidates optional search",out);
        check(PublicGroundService.planningTickDue(1,11) && PublicGroundService.planningTickDue(1,10),"pure solver advances between world scan ticks",out);
        check(PublicGroundService.planningTickDue(1,4) && PublicGroundService.planningTickDue(10,4),"world survey advances every tick under time and column budgets",out);
        var low=at.below();var beforeLow=l.getBlockState(low);
        try{
            var support=new TerrainProject(UUID.randomUUID(),"required existing support",at,List.of(
                new TerrainProject.Step(low,Blocks.AIR.defaultBlockState(),Blocks.DIRT.defaultBlockState()),
                new TerrainProject.Step(low,Blocks.COBBLESTONE.defaultBlockState(),Blocks.AIR.defaultBlockState()),
                new TerrainProject.Step(at,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));
            support.road=true;support.groundSurfaces.add(at);
            l.setBlock(low,Blocks.DIRT_PATH.defaultBlockState(),2);
            check(!TerrainService.retainConstructionSupport(l,s,support,support.steps.get(1)),"recessed path still requires real full support",out);
            l.setBlock(low,Blocks.DIRT.defaultBlockState(),2);support.groundSurfaces.add(low);
            check(!TerrainService.retainConstructionSupport(l,s,support,support.steps.get(1)),"exposed support keeps specified material",out);support.groundSurfaces.remove(low);
            check(TerrainService.retainConstructionSupport(l,s,support,support.steps.get(1)),"buried unchanged stable soil releases redundant replacement",out);
            check(support.abandoned.get(0) && support.abandoned.get(1) && !support.finished.get(2)
                && l.getBlockState(low).is(Blocks.DIRT),"release preserves world and unfinished real road cap",out);
        }finally{l.setBlock(low,beforeLow,2);}
        var start=core.offset(3,-1,-3);var finish=start.east().south();var east=start.east();var south=start.south();
        var corner=new AutoRoadState.Route(b.id(),finish,List.of(start,east,south,finish));corner.centerline=List.of(start,finish);
        var before=new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        for(var p:corner.cells){before.put(p,l.getBlockState(p));l.setBlock(p,Blocks.GRASS_BLOCK.defaultBlockState(),2);}
        try{
            check(RoadEarthworkService.workStripJoins(l,s,corner,start,finish),"rounded diagonal has real two-step landing",out);
            l.setBlock(east,Blocks.AIR.defaultBlockState(),2);
            check(RoadEarthworkService.workStripJoins(l,s,corner,start,finish),"other real corner landing still connects",out);
            l.setBlock(south,Blocks.AIR.defaultBlockState(),2);
            check(!RoadEarthworkService.workStripJoins(l,s,corner,start,finish),"planned diagonal cannot fake missing corner ground",out);
        }finally{before.forEach((p,state)->l.setBlock(p,state,2));}
        w.phase=5;w.planCursor=17;w.roadsIntegrated=true;r.constructionGradeFixed=true;
        b.queueCancellation();check(AutoRoadService.fixedConstructionSurface(s,at)==null,"cancelled parent releases P0 height constraint",out);
        AutoRoadService.commitNecessaryGrades(l,s);
        check(!r.constructionGradeFixed && w.phase==5 && w.planCursor==17 && w.releasedConstraintReview,"released parent queues review without restarting publication",out);
        return "PASS "+out.size()+" dispatch planning checks: "+String.join("; ",out)+"; corner fixture edits restored; no completion credited";
    }
    public static String live(ServerLevel l){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var data=SettlementSavedData.get(l);if(data.findByCore(CORE).isPresent())throw new IllegalStateException("FIXTURE_EXISTS");
        // This copy is dedicated to this comparison; no player settlement is opened or modified.
        for(var old:data.settlements())old.deactivate();
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)l.setChunkForced((CORE.getX()>>4)+x,(CORE.getZ()>>4)+z,true);
        for(var at:BlockPos.betweenClosed(CORE.offset(-12,-3,-27),CORE.offset(40,10,27)))if(!l.getBlockState(at).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");
        for(int x=-12;x<=40;x++)for(int z=-27;z<=27;z++){
            l.setBlock(CORE.offset(x,-3,z),Blocks.STONE.defaultBlockState(),2);
            l.setBlock(CORE.offset(x,-2,z),Blocks.DIRT.defaultBlockState(),2);
            l.setBlock(CORE.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState(),2);
        }
        // A shallow obstacle across the short corridor needs real tools or fill, not fake paving.
        for(int z=-26;z<=26;z++){l.setBlock(CORE.offset(6,-1,z),Blocks.AIR.defaultBlockState(),2);l.setBlock(CORE.offset(6,-2,z),Blocks.AIR.defaultBlockState(),2);}
        l.setBlock(CORE,ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),2);
        var s=data.createOrRecover(CORE,UUID.randomUUID(),"Dispatch88 house warehouse and terrain").value();s.upgradeLevel();s.upgradeLevel();
        s.terrain().enabled=true;s.autoRoads().enabled=true;s.decorations().enabled=false;
        var store=CORE.offset(-7,0,0);l.setBlock(store,Blocks.BARREL.defaultBlockState(),2);
        var warehouse=s.addBuilding(BuildingType.WAREHOUSE,store,Direction.NORTH,store.offset(-2,0,-2),store.offset(2,2,2),0,1);
        warehouse.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of(store));s.terrain().surveyed.add(warehouse.id());
        var house=s.addBlueprint(BuildingType.HOUSE,CORE.offset(17,0,-12),CORE.offset(17,0,-12),Direction.NORTH,s.ownerId(),false);
        var storage=s.addBlueprint(BuildingType.WAREHOUSE,CORE.offset(17,0,10),CORE.offset(17,0,10),Direction.NORTH,s.ownerId(),false);
        var materials=new LinkedHashMap<Item,Integer>();
        for(var site:List.of(house,storage))for(var placement:BlueprintTemplates.create(site).placements())
            if(!placement.state().isAir() && placement.material()!=null)materials.merge(placement.material(),1,Integer::sum);
        materials.forEach((item,n)->WarehouseInventoryService.insert(l,warehouse,new ItemStack(item,n+64)));
        for(var item:List.of(Items.DIRT,Items.COBBLESTONE,Items.SCAFFOLDING,Items.OAK_LOG))WarehouseInventoryService.insert(l,warehouse,new ItemStack(item,256));
        WarehouseInventoryService.insert(l,warehouse,new ItemStack(Items.BREAD,64));
        int n=0;while(s.citizens().size()<6)s.addMigrant();
        for(var c:s.citizens()){
            c.clearWork();c.setManualAssignment(n<4?CitizenProfession.WORKER:CitizenProfession.HERDSMAN,null);
            c.updateProjection(null,CORE.offset(-3+n,0,4));c.insertIntoInventory(new ItemStack(Items.BREAD,16));
            if(n<4)for(var item:List.of(Items.DIAMOND_SHOVEL,Items.DIAMOND_PICKAXE,Items.DIAMOND_AXE))c.insertIntoInventory(new ItemStack(item));n++;
        }
        // Detached pending public solve: a legitimate larger math grid, with no precompleted work.
        var w=s.terrain().publicGround;
        for(int x=-55;x<=55;x++)for(int z=-35;z<=35;z++){long k=key(CORE.offset(x,0,z));w.goals.put(k,249);w.baselines.put(k,249);}
        w.anchors.put(key(CORE),249);PublicGroundService.startSources(w);w.signature=Long.MIN_VALUE;
        CitizenProjectionService.ensureSettlementCitizens(l,s);SaveAcceptanceService.load(l,s,3);data.touch();
        return "OK "+s.id()+"; house="+house.id()+" warehouse="+storage.id()+"; 4 workers, 2 specialists; finite real materials and tools; no construction force; setup excluded";
    }
    public static String inspect(ServerLevel l){
        var s=SettlementSavedData.get(l).findByCore(CORE).orElseThrow();var j=new com.google.gson.JsonObject();
        j.addProperty("publicPhase",s.terrain().publicGround.phase);j.addProperty("publicStatus",s.terrain().publicGround.status);
        var sites=new com.google.gson.JsonArray();for(var b:s.blueprints())if(b.terrainProject()==null){var x=new com.google.gson.JsonObject();x.addProperty("id",b.id().toString());x.addProperty("type",b.type().name());x.addProperty("allowed",TerrainService.allowed(s,b));x.addProperty("accessReady",AutoRoadService.accessReady(s,b.id()));sites.add(x);}j.add("parents",sites);
        var routes=new com.google.gson.JsonArray();for(var r:s.autoRoads().routes.values()){var x=new com.google.gson.JsonObject();x.addProperty("parent",r.building.toString());x.addProperty("cells",r.cells.size());x.addProperty("connected",r.accessConnected);x.addProperty("fixed",r.constructionGradeFixed);x.addProperty("status",r.status);routes.add(x);}j.add("routes",routes);
        var pending=new com.google.gson.JsonArray();var lower=new com.google.gson.JsonArray();
        for(var b:s.blueprints())if(b.terrainProject()!=null){var p=b.terrainProject();
            var raw=TerrainService.pendingRaw(l,b,Set.of());
            for(var work:raw){var at=work.placement().position();var mother=com.hkin.exiledsettlement.citizen.WorkTaskService.mother(s,b,at);
                if(at.getY()>=249 || s.blueprint(mother)==null || s.blueprint(mother).terrainProject()!=null)continue;
                lower.add(b.id()+" "+at.toShortString()+" mother="+mother+" road="+p.road+" deps="+p.dependencies);
            }
            for(int i=p.cursor;i<p.steps.size();i++){var step=p.steps.get(i);var at=step.position();
                if(p.finished.get(i) || at.getX()!=CORE.getX()+6 || at.getY()!=249)continue;
                var x=new com.google.gson.JsonObject();x.addProperty("site",b.id().toString());x.addProperty("at",at.toShortString());
                x.addProperty("target",step.target().toString());x.addProperty("guard",TerrainService.guard(l,s,b,step));
                x.addProperty("supported",com.hkin.exiledsettlement.road.BridgeService.supported(l,p,step));
                final int index=i;x.addProperty("layer",TerrainLayerService.allowed(l,s,b,step));x.addProperty("raw",raw.stream().anyMatch(v->v.index()==-index-1));
                x.addProperty("mother",com.hkin.exiledsettlement.citizen.WorkTaskService.mother(s,b,at).toString());
                x.addProperty("rawFirst",raw.isEmpty()?"none":raw.getFirst().placement().position().toShortString());pending.add(x);
            }
        }j.add("pitPending",pending);j.add("lowerP0",lower);
        j.add("routePlanner",AutoRoadPlanner.progress(s));return j.toString();
    }
    private Dispatch88Checks(){}
}
