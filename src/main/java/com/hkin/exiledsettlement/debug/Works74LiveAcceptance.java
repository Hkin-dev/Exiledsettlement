package com.hkin.exiledsettlement.debug;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.ModBlocks;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.terrain.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Isolated scene setup only; after creation all work is the ordinary citizen work controller. */
public final class Works74LiveAcceptance {
    public static String start(ServerLevel level)throws Exception{
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(27000,250,27000);var data=SettlementSavedData.get(level);
        if(data.findByCore(core).isPresent())throw new IllegalStateException("Fixture exists; inspect it instead of overwriting");
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)level.setChunkForced((core.getX()>>4)+x,(core.getZ()>>4)+z,true);
        for(var pos:BlockPos.betweenClosed(core.offset(-35,-3,-25),core.offset(35,15,25))){
            if(pos.getY()<core.getY())level.setBlock(pos,Blocks.DIRT.defaultBlockState(),2);
            else if(!level.getBlockState(pos).isAir())throw new IllegalStateException("Fixture airspace already occupied");
        }
        for(int x=8;x<=13;x++)for(int z=-25;z<=25;z++){
            level.setBlock(core.offset(x,-1,z),Blocks.AIR.defaultBlockState(),2);
            level.setBlock(core.offset(x,-2,z),Blocks.WATER.defaultBlockState(),2);
        }
        level.setBlock(core,ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),2);
        var result=data.createOrRecover(core,UUID.randomUUID(),"Works74 real resident chain");
        if(!result.success())throw new IllegalStateException(result.reason());var s=result.value();s.upgradeLevel();s.upgradeLevel();
        s.terrain().enabled=false;s.autoRoads().enabled=false;s.decorations().enabled=false;s.community().stewardEnabled=false;
        var t=BlueprintTemplates.create(BuildingType.WAREHOUSE,core.offset(-24,0,-12),Direction.EAST);
        for(var p:t.placements())level.setBlock(p.position(),p.state(),2);
        for(var p:t.placements())BlueprintBlockStateResolver.refreshExisting(level,t,p);
        var warehouse=s.addBuilding(t.type(),t.registrationPosition(),t.facing(),t.minCorner(),t.maxCorner(),t.transitionMargin(),BlueprintTemplates.currentTemplateVersion(t.type()));
        BuildingFunctionService.validateSingle(level,data,s,warehouse);
        if(!warehouse.operational())throw new IllegalStateException("Fixture warehouse invalid");
        for(var at:WarehouseInventoryService.loadedContainers(level,warehouse))at.container().clearContent();
        for(var item:List.of(Items.OAK_LOG,Items.OAK_PLANKS,Items.OAK_FENCE,Items.COBBLESTONE,Items.OAK_STAIRS,Items.DIRT))
            WarehouseInventoryService.insert(level,warehouse,new ItemStack(item,256));
        for(var item:List.of(Items.IRON_AXE,Items.DIAMOND_PICKAXE,Items.IRON_SHOVEL,Items.IRON_HOE,Items.IRON_SWORD,Items.SHEARS,Items.FISHING_ROD))
            WarehouseInventoryService.insert(level,warehouse,new ItemStack(item,12));
        WarehouseInventoryService.insert(level,warehouse,new ItemStack(Items.BREAD,256));
        var containers=WarehouseInventoryService.loadedContainers(level,warehouse);
        containers.getLast().container().setItem(20,new ItemStack(Items.APPLE,7));containers.getLast().container().setItem(21,new ItemStack(Items.APPLE,11));
        int index=0;for(var c:s.citizens()){
            c.setManualAssignment(index==0?CitizenProfession.WAREHOUSE_KEEPER:CitizenProfession.WORKER,index==0?warehouse.id():null);
            c.updateProjection(null,core.offset(-3,0,8+index++));
        }
        var preview=PlayerWorksService.previewBridge(level,s,core.offset(7,-1,0),core.offset(14,-1,0));
        if(!preview.success())throw new IllegalStateException(preview.reason());var route=preview.value();s.autoRoads().routes.put("manual_bridge",route);
        if(!BridgeService.publish(level,s,route))throw new IllegalStateException(route.status);
        s.terrain().dependingOn(route.id).forEach(p->p.explicit=true);
        var clears=new ArrayList<TerrainProject.Step>();
        for(int x=0;x<4;x++){var p=core.offset(x,0,12);level.setBlock(p,Blocks.OAK_PLANKS.defaultBlockState(),2);clears.add(new TerrainProject.Step(p,Blocks.AIR.defaultBlockState(),Blocks.OAK_PLANKS.defaultBlockState()));}
        var site=s.addBlueprint(BuildingType.HOUSE,core.offset(0,0,12),core.above(40),Direction.NORTH,s.ownerId(),false,1,Map.of());
        var clear=new TerrainProject(site.id(),"Live player clearance",core.offset(0,0,12),clears);clear.explicit=true;clear.areaClearance=true;site.withTerrainProject(clear);s.terrain().index(clear);
        ForceWorkDebugService.set(level,s,true);data.touch();CitizenProjectionService.ensureSettlementCitizens(level,s);
        SaveAcceptanceService.load(level,s,3);SaveAcceptanceService.watch(level,s,"works74-live",12000,400);
        return "OK fixture="+s.id()+"; finite stocked warehouse; resident inventories initially empty; no resident work simulated; bridge="+route.id+"; clearance="+clear.id;
    }
    public static String supplyHouse(ServerLevel level){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var data=SettlementSavedData.get(level);var s=data.findByCore(new BlockPos(27000,250,27000)).orElseThrow();
        var house=s.blueprints().stream().filter(b->b.terrainProject()==null && b.type()==BuildingType.HOUSE).findFirst().orElseThrow();
        var warehouse=s.buildings().stream().filter(b->b.type()==BuildingType.WAREHOUSE).findFirst().orElseThrow();
        int inserted=0;var materials=new LinkedHashMap<Item,Integer>(BlueprintTemplates.create(house).requirements());
        materials.merge(Items.SCAFFOLDING,128,Integer::sum);materials.merge(Items.COBBLESTONE,256,Integer::sum);
        materials.put(Items.DIAMOND_SHOVEL,12);materials.put(Items.DIAMOND_AXE,12);materials.put(Items.DIAMOND_PICKAXE,12);
        for(var item:materials.entrySet()){
            var remainder=WarehouseInventoryService.insert(level,warehouse,new ItemStack(item.getKey(),item.getValue()));
            inserted+=item.getValue()-remainder.getCount();if(!remainder.isEmpty())throw new IllegalStateException("Test warehouse full: "+remainder);
        }
        data.touch();return "TEST SETUP ONLY: finite house materials added to warehouse="+inserted+"; residents must fetch, transport and construct normally";
    }
    private Works74LiveAcceptance(){}
}
