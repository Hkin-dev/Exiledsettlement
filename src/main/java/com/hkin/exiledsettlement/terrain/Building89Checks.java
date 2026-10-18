package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.debug.SaveAcceptanceService;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.region.ProductionRegionService;
import com.hkin.exiledsettlement.registry.ModBlocks;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import java.util.*;

/** Explicit isolated comparison: real reachable buildings with roads declined and unfinished. */
public final class Building89Checks {
    public static final BlockPos CORE=new BlockPos(53000,250,53000);
    private static void check(boolean ok,String name,List<String> out){if(!ok)throw new IllegalStateException(name);out.add(name);}
    public static String run(ServerLevel l){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var s=new SettlementRecord(UUID.randomUUID(),"Building89 detached",CORE,UUID.randomUUID());var out=new ArrayList<String>();
        var house=s.addBlueprint(BuildingType.HOUSE,CORE.east(17),CORE.east(17),Direction.NORTH,s.ownerId(),false);
        var warehouse=s.addBlueprint(BuildingType.WAREHOUSE,CORE.south(17),CORE.south(17),Direction.NORTH,s.ownerId(),false);
        for(var b:List.of(house,warehouse))s.terrain().surveyed.add(b.id());
        check(TerrainService.allowed(s,house) && TerrainService.allowed(s,warehouse),"reachable buildings do not need a registered road",out);
        var route=new AutoRoadState.Route(house.id(),CORE.east(15).below(),List.of(CORE.east(4).below()));route.suppressed=true;s.autoRoads().routes.put("declined",route);
        check(TerrainService.allowed(s,house) && !AutoRoadService.accessReady(s,house.id()),"declining road cannot lock construction",out);
        var child=s.addBlueprint(BuildingType.HOUSE,CORE.east(4),CORE.east(4),Direction.NORTH,s.ownerId(),false);
        var p=new TerrainProject(child.id(),"road still unfinished",child.anchor().below(),List.of(new TerrainProject.Step(child.anchor().below(),Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));
        p.road=true;p.dependencies.add(house.id());child.withTerrainProject(p);s.terrain().projects.put(p.id,p);s.terrain().index(p);
        check(TerrainService.dependenciesReady(s,house.id()) && TerrainService.allowed(s,house),"unfinished road earthwork cannot gate building",out);
        check(WorkTaskService.priority(s,child)==2 && !WorkTaskService.essential(s,p,house,p.anchor),"road earthwork remains P2",out);
        p.bridge=true;check(TerrainService.dependenciesReady(s,house.id()) && WorkTaskService.priority(s,child)==2,"planned bridge cannot gate reachable building",out);p.bridge=false;
        var c=s.addMigrant();c.setManualAssignment(CitizenProfession.WORKER,null);c.startTask(CitizenWorkState.TO_ROAD,route.cells.getFirst(),null,null);c.setRoadTaskId(route.id);route.suppressed=false;c.setMotherTask(house.id());WorkTaskService.bind(s,c);
        check(WorkTaskService.assigned(s,house.id())==0 && WorkTaskService.task(s,c).priority()==2,"existing road claim releases building quota",out);
        check(!AutoRoadService.assign(l,s,c,new HashSet<>(),true),"priority construction pass never claims paving",out);c.clearWork();
        p.road=false;p.steps=List.of(new TerrainProject.Step(house.anchor().below(),Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()));s.terrain().index(p);
        check(!TerrainService.dependenciesReady(s,house.id()),"actual unfinished building footing remains prerequisite",out);
        p.completed=true;check(TerrainService.allowed(s,house),"completed footing releases building without road",out);
        s.terrain().surveyed.remove(warehouse.id());check(!TerrainService.allowed(s,warehouse),"necessary foundation survey is still required",out);
        return "PASS "+out.size()+" building priority checks: "+String.join("; ",out)+"; worldEdits=0";
    }
    public static String live(ServerLevel l){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var data=SettlementSavedData.get(l);if(data.findByCore(CORE).isPresent())throw new IllegalStateException("FIXTURE_EXISTS");
        for(var old:data.settlements())old.deactivate();
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)l.setChunkForced((CORE.getX()>>4)+x,(CORE.getZ()>>4)+z,true);
        for(var at:BlockPos.betweenClosed(CORE.offset(-12,-3,-27),CORE.offset(40,10,27)))if(!l.getBlockState(at).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");
        for(int x=-12;x<=40;x++)for(int z=-27;z<=27;z++){
            l.setBlock(CORE.offset(x,-3,z),Blocks.STONE.defaultBlockState(),2);l.setBlock(CORE.offset(x,-2,z),Blocks.DIRT.defaultBlockState(),2);l.setBlock(CORE.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState(),2);
        }
        l.setBlock(CORE,ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),2);
        var s=data.createOrRecover(CORE,UUID.randomUUID(),"Building89 reachable house and warehouse without roads").value();s.upgradeLevel();s.upgradeLevel();
        s.terrain().enabled=false;s.autoRoads().enabled=false;s.decorations().enabled=false;
        var store=CORE.offset(-7,0,0);l.setBlock(store,Blocks.BARREL.defaultBlockState(),2);
        var w=s.addBuilding(BuildingType.WAREHOUSE,store,Direction.NORTH,store.offset(-2,0,-2),store.offset(2,2,2),0,1);w.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of(store));s.terrain().surveyed.add(w.id());
        var house=s.addBlueprint(BuildingType.HOUSE,CORE.offset(17,0,-12),CORE.offset(17,0,-12),Direction.NORTH,s.ownerId(),false);
        var warehouse=s.addBlueprint(BuildingType.WAREHOUSE,CORE.offset(17,0,10),CORE.offset(17,0,10),Direction.NORTH,s.ownerId(),false);
        for(var b:List.of(house,warehouse)){
            var end=AutoRoadService.entrances(l,s,b).getFirst();var r=new AutoRoadState.Route(b.id(),end,List.of(end));r.suppressed=true;r.status="Fixture explicit declined road";s.autoRoads().routes.put(b.id().toString(),r);s.autoRoads().accessSurveyed.add(b.id());
        }
        // Independent unfinished paid road order, linked to a building but outside its construction footprint.
        var roadAt=CORE.offset(3,-1,20);l.setBlock(roadAt,Blocks.AIR.defaultBlockState(),2);
        var road=ProductionRegionService.publish(l,s,house.id(),"Building89 independent unfinished road",roadAt,List.of(new TerrainProject.Step(roadAt,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));
        if(road==null)throw new IllegalStateException("ROAD_ORDER_FAILED");road.road=true;road.explicit=true;road.groundSurfaces.add(roadAt);s.terrain().index(road);
        var materials=new LinkedHashMap<Item,Integer>();for(var b:List.of(house,warehouse))for(var p:BlueprintTemplates.create(b).placements())if(!p.state().isAir() && p.material()!=null)materials.merge(p.material(),1,Integer::sum);
        materials.forEach((item,n)->WarehouseInventoryService.insert(l,w,new ItemStack(item,n+64)));
        for(var item:List.of(Items.DIRT,Items.COBBLESTONE,Items.SCAFFOLDING))WarehouseInventoryService.insert(l,w,new ItemStack(item,256));WarehouseInventoryService.insert(l,w,new ItemStack(Items.BREAD,64));
        while(s.citizens().size()<6)s.addMigrant();int n=0;for(var c:s.citizens()){
            c.clearWork();c.setManualAssignment(n<4?CitizenProfession.WORKER:CitizenProfession.HERDSMAN,null);c.updateProjection(null,CORE.offset(-3+n,0,4));c.insertIntoInventory(new ItemStack(Items.BREAD,16));
            if(n<4)for(var item:List.of(Items.DIAMOND_SHOVEL,Items.DIAMOND_PICKAXE,Items.DIAMOND_AXE))c.insertIntoInventory(new ItemStack(item));n++;
        }
        CitizenProjectionService.ensureSettlementCitizens(l,s);SaveAcceptanceService.load(l,s,3);data.touch();
        return "OK "+s.id()+"; house="+house.id()+" warehouse="+warehouse.id()+"; 4 workers, 2 specialists, roads declined, unrelated road earthwork unfinished, finite supply, no forced work; setup excluded";
    }
    private Building89Checks(){}
}
