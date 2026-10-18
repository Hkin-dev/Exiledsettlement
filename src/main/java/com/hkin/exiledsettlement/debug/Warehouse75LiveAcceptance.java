package com.hkin.exiledsettlement.debug;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.registry.ModBlocks;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Finite test setup. All later transport, resident consumption and disposal use normal entity AI. */
public final class Warehouse75LiveAcceptance {
    private Warehouse75LiveAcceptance() {}
    public static String policy(ServerLevel level,int keep) {
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var s=SettlementSavedData.get(level).findByCore(new BlockPos(29200,250,29200)).orElseThrow();
        var b=s.buildings().stream().filter(v->v.type()==BuildingType.WAREHOUSE).findFirst().orElseThrow();
        WarehouseInventoryService.stock(level,b).rule("minecraft:snowball",keep,true);
        return "TEST POLICY CHANGE through shared stock rule setter: keep="+keep+"; no items moved or removed";
    }
    public static String start(ServerLevel level)throws Exception {
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(29200,250,29200);var data=SettlementSavedData.get(level);
        if(data.findByCore(core).isPresent())throw new IllegalStateException("Fixture already exists");
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)level.setChunkForced((core.getX()>>4)+x,(core.getZ()>>4)+z,true);
        for(var p:BlockPos.betweenClosed(core.offset(-24,-1,-15),core.offset(24,12,15))) {
            if(!level.getBlockState(p).isAir())throw new IllegalStateException("Requires unused airspace");
        }
        for(int x=-24;x<=24;x++)for(int z=-15;z<=15;z++)level.setBlock(core.offset(x,-1,z),Blocks.STONE.defaultBlockState(),2);
        level.setBlock(core,ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),2);
        var result=data.createOrRecover(core,UUID.randomUUID(),"Warehouse75 real transport and disposal");
        if(!result.success())throw new IllegalStateException(result.reason());var s=result.value();s.upgradeLevel();s.upgradeLevel();
        s.terrain().enabled=false;s.autoRoads().enabled=false;s.decorations().enabled=false;s.community().stewardEnabled=true;
        var template=BlueprintTemplates.create(BuildingType.WAREHOUSE,core.offset(-18,0,-8),Direction.EAST);
        for(var p:template.placements())level.setBlock(p.position(),p.state(),2);
        for(var p:template.placements())BlueprintBlockStateResolver.refreshExisting(level,template,p);
        var warehouse=s.addBuilding(template.type(),template.registrationPosition(),template.facing(),template.minCorner(),template.maxCorner(),template.transitionMargin(),BlueprintTemplates.currentTemplateVersion(template.type()));
        BuildingFunctionService.validateSingle(level,data,s,warehouse);
        // Legacy physical inputs explicitly seeded before first ledger import.
        var boxes=WarehouseInventoryService.physicalContainers(level,warehouse);
        if(boxes.isEmpty())throw new IllegalStateException("No legacy boxes");var box=boxes.get(0).container();box.setItem(0,new ItemStack(Items.BREAD,32));box.setItem(1,new ItemStack(Items.WHEAT,12));
        for(int slot=2;slot<7;slot++)box.setItem(slot,new ItemStack(Items.SNOWBALL,16));box.setChanged();
        for(var item:List.of(Items.IRON_AXE,Items.IRON_PICKAXE,Items.IRON_SHOVEL,Items.IRON_HOE,Items.IRON_SWORD,Items.SHEARS,Items.FISHING_ROD))WarehouseInventoryService.insert(level,warehouse,new ItemStack(item,12));
        var sourcePosition=core.offset(12,0,0);level.setBlock(sourcePosition,Blocks.BARREL.defaultBlockState(),2);
        var source=s.addBuilding(BuildingType.TOOL_WORKSHOP,sourcePosition,Direction.NORTH,sourcePosition,sourcePosition,0,1);
        source.updateFunction(true,BuildingStatus.OPERATIONAL,2,List.of(sourcePosition));
        var output=(Container)level.getBlockEntity(sourcePosition);output.setItem(0,new ItemStack(Items.SNOWBALL,16));output.setItem(1,new ItemStack(Items.APPLE,32));output.setChanged();
        int i=0;for(var c:s.citizens()) {
            c.setManualAssignment(i==0?CitizenProfession.WAREHOUSE_KEEPER:CitizenProfession.WORKER,i==0?warehouse.id():null);
            c.updateProjection(null,core.offset(1,0,5+i));
            if(i==1)c.startContainerHaul(source.id(),warehouse.id());
            if(i==2)c.adjustNeeds(-50,0,0);
            i++;
        }
        data.touch();CitizenProjectionService.ensureSettlementCitizens(level,s);SaveAcceptanceService.load(level,s,2);
        SaveAcceptanceService.watch(level,s,"warehouse75-live-v2",4800,200);
        return "TEST SETUP ONLY: settlement="+s.id()+"; finite legacy stock and physical producer output; snow total=96; apples=32; ordinary resident pathing/haul/eating/disposal follow";
    }
}
