package com.hkin.exiledsettlement.debug;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import com.hkin.exiledsettlement.registry.ModBlocks;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Explicit finite setup of small custom work sites; all subsequent work is native resident work. */
public final class Worker77LiveAcceptance {
    public static String start(ServerLevel level)throws Exception{
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(31300,250,31300);var data=SettlementSavedData.get(level);
        if(data.findByCore(core).isPresent())throw new IllegalStateException("ALREADY_EXISTS");
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)level.setChunkForced((core.getX()>>4)+x,(core.getZ()>>4)+z,true);
        for(var p:BlockPos.betweenClosed(core.offset(-20,-4,-20),core.offset(24,5,20)))if(!level.getBlockState(p).isAir())throw new IllegalStateException("Unused air required");
        for(int x=-20;x<=24;x++)for(int z=-20;z<=20;z++){
            for(int y=-4;y<=-1;y++)level.setBlock(core.offset(x,y,z),Blocks.STONE.defaultBlockState(),2);
            level.setBlock(core.offset(x,-1,z),Blocks.STONE.defaultBlockState(),2);
        }
        level.setBlock(core,ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),2);
        var result=data.createOrRecover(core,UUID.randomUUID(),"Worker77 real construction");if(!result.success())throw new IllegalStateException(result.reason());var s=result.value();s.upgradeLevel();s.upgradeLevel();
        s.terrain().enabled=false;s.autoRoads().enabled=false;while(s.citizens().size()<12)s.addMigrant();
        var barrel=core.offset(-10,0,0);level.setBlock(barrel,Blocks.BARREL.defaultBlockState(),2);
        var warehouse=s.addBuilding(BuildingType.WAREHOUSE,barrel,Direction.NORTH,core.offset(-14,0,-4),core.offset(-6,2,4),0,1);
        warehouse.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of(barrel));
        for(var item:List.of(Items.COBBLESTONE,Items.DIRT,Items.OAK_PLANKS,Items.SCAFFOLDING,Items.BREAD))WarehouseInventoryService.insert(level,warehouse,new ItemStack(item,512));
        for(var item:List.of(Items.DIAMOND_AXE,Items.DIAMOND_PICKAXE,Items.DIAMOND_SHOVEL,Items.IRON_HOE,Items.IRON_SWORD,Items.SHEARS,Items.FISHING_ROD))WarehouseInventoryService.insert(level,warehouse,new ItemStack(item,16));
        var ids=new ArrayList<UUID>();var types=List.of(BuildingType.HOUSE,BuildingType.HOUSE_2,BuildingType.HOUSE_3,BuildingType.TOOL_WORKSHOP);
        for(int i=0;i<types.size();i++){
            var a=core.offset(3+(i%2)*10,0,-10+(i/2)*17);var placements=new ArrayList<BlueprintPlacement>();
            for(int x=0;x<3;x++)for(int z=0;z<3;z++)placements.add(new BlueprintPlacement(a.offset(x,-1,z),Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS));
            placements.add(new BlueprintPlacement(a.offset(2,0,2),Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS));
            var b=s.addBlueprint(types.get(i),a,a.above(40),Direction.NORTH,s.ownerId(),false);
            b.withCustomTemplate(CustomTemplateSnapshot.create("Worker77 site "+i,types.get(i),a,Direction.NORTH,placements,List.of(new BlueprintEntrance(a,Direction.NORTH))));ids.add(b.id());
            // A single-cell three-block hole below the real floor must be filled by residents.
            for(int y=-3;y<=-1;y++)level.setBlock(a.offset(1,y,1),Blocks.AIR.defaultBlockState(),2);
            level.setBlock(a.offset(1,-1,1),Blocks.SHORT_GRASS.defaultBlockState(),2);
        }
        var a=core.offset(3,0,-10);var optional=s.addBlueprint(BuildingType.HOUSE,a,core.above(50),Direction.NORTH,s.ownerId(),false);
        var publicOrder=new TerrainProject(optional.id(),"Worker77 optional public work",a,List.of(new TerrainProject.Step(a.offset(1,-1,1),Blocks.AIR.defaultBlockState(),Blocks.SHORT_GRASS.defaultBlockState()),new TerrainProject.Step(core.offset(20,0,16),Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState())));
        publicOrder.publicGround=true;publicOrder.dependencies.add(ids.getFirst());optional.withTerrainProject(publicOrder);s.terrain().index(publicOrder);
        int index=0;for(var c:s.citizens()){c.setManualAssignment(CitizenProfession.WORKER,null);c.updateProjection(null,core.offset(-3,0,-5+index++));}
        ForceWorkDebugService.set(level,s,true);data.touch();CitizenProjectionService.ensureSettlementCitizens(level,s);
        SaveAcceptanceService.load(level,s,3);SaveAcceptanceService.watch(level,s,"worker77-live-v2",16000,100);
        return "OK settlement="+s.id()+"; custom sites="+ids+"; finite real stock; fixture blocks/population are setup only; force-work debug skips life/schedule only";
    }
    public static String status(ServerLevel level){
        var s=SettlementSavedData.get(level).findByCore(new BlockPos(31300,250,31300)).orElseThrow();
        return String.join("\n",PublicWorkService.report(level,s));
    }
    public static String retireFailedFixture(ServerLevel level){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var data=SettlementSavedData.get(level);
        var old=data.findByCore(new BlockPos(31000,250,31000)).orElseThrow();
        if(!old.name().startsWith("Worker77"))throw new IllegalStateException("NOT_WORKER77_FIXTURE");
        old.deactivate();ForceWorkDebugService.set(level,old,false);data.touch();
        return "SETUP ONLY: failed floating-pad fixture inactive; records, real blocks and inventory retained; no construction credited";
    }
    public static String supply(ServerLevel level){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var s=SettlementSavedData.get(level).findByCore(new BlockPos(31300,250,31300)).orElseThrow();
        var w=s.buildings().stream().filter(b->b.type()==BuildingType.WAREHOUSE).findFirst().orElseThrow();
        int n=Math.max(0,512-WarehouseInventoryService.countItem(level,w,Items.DIRT));
        WarehouseInventoryService.insert(level,w,new ItemStack(Items.DIRT,n));SettlementSavedData.get(level).touch();
        return "TEST SETUP ONLY dirt added="+n+"; residents must fetch and place it normally";
    }
}
