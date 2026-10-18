package com.hkin.exiledsettlement.debug;

import com.google.gson.*;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.region.*;
import com.hkin.exiledsettlement.registry.ModBlocks;
import com.hkin.exiledsettlement.road.AutoRoadService;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import java.util.*;

/** Explicit isolated fixtures. Rule setup is not resident completion. */
public final class Worker85Checks {
    private static final BlockPos CORE = new BlockPos(47800,250,47800);
    private static void check(boolean ok,String name,List<String> out) {
        if(!ok)throw new IllegalStateException(name);out.add(name);
    }
    public static String run(ServerLevel level) throws Exception {
        var core=new BlockPos(48200,250,48200);var data=SettlementSavedData.get(level);
        if(data.findByCore(core).isPresent())throw new IllegalStateException("TEST_ALREADY_EXISTS");
        var s=data.createOrRecover(core,UUID.randomUUID(),"Worker85 rule fixture").value();
        s.terrain().enabled=true;s.autoRoads().enabled=true;
        var at=core.east(2).below();var before=level.getBlockState(at.below());
        if(!level.getBlockState(at).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");
        var out=new ArrayList<String>();
        try {
            level.setBlock(at.below(),Blocks.DIRT.defaultBlockState(),2);
            var site=s.addBlueprint(BuildingType.HOUSE,at,at,Direction.NORTH,s.ownerId(),false);
            var p=new TerrainProject(site.id(),"Worker85 public soil",at,List.of(new TerrainProject.Step(at,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));
            p.explicit=true;p.publicGround=true;p.groundSurfaces.add(at);site.withTerrainProject(p);s.terrain().index(p);
            while(s.citizens().size()<CitizenProfession.values().length)s.addMigrant();
            var citizens=new ArrayList<>(s.citizens());int index=0;
            var assign=CitizenWorkService.class.getDeclaredMethod("assignBlueprint",ServerLevel.class,SettlementSavedData.class,SettlementRecord.class,CitizenRecord.class,Set.class,boolean.class,boolean.class,boolean.class);
            assign.setAccessible(true);
            for(var profession:CitizenProfession.values()) {
                var c=citizens.get(index++);c.setManualAssignment(profession,null);
                c.insertIntoInventory(new ItemStack(Items.DIRT,8));c.insertIntoInventory(new ItemStack(Items.DIAMOND_SHOVEL));
                boolean worker=profession==CitizenProfession.WORKER;
                check((boolean)assign.invoke(null,level,data,s,c,new HashSet<BlockPos>(),false,true,false)==worker,"real public assignment "+profession,out);
                check(CitizenWorkService.blueprintWorkAllowed(s,c,site,at)==worker,"existing public claim "+profession,out);
                if(!worker)check(!AutoRoadService.assign(level,s,c,new HashSet<BlockPos>()),"road assignment denied "+profession,out);
                c.clearWork();
            }
            check(PublicWorkService.builderLimit(level,s,site)==1,"public labor pool counts only one worker",out);
            var house=s.addBlueprint(BuildingType.HOUSE,core.south(8),core.south(8),Direction.NORTH,s.ownerId(),false);
            p.dependencies.add(house.id());
            for(var c:citizens) {
                boolean worker=c.profession()==CitizenProfession.WORKER;
                check(CitizenWorkService.blueprintWorkAllowed(s,c,house,house.anchor())==worker,"building role "+c.profession(),out);
                p.bridge=true;check(CitizenWorkService.blueprintWorkAllowed(s,c,site,at)==worker,"bridge role "+c.profession(),out);p.bridge=false;
            }
            var farmer=citizens.stream().filter(c->c.profession()==CitizenProfession.FARMER).findFirst().orElseThrow();
            p.escapeFor=core;p.escapingCitizen=farmer.id();
            check(CitizenWorkService.blueprintWorkAllowed(s,farmer,site,at),"personal emergency exit preserved",out);
            var other=citizens.stream().filter(c->c.profession()==CitizenProfession.HERDSMAN).findFirst().orElseThrow();
            check(!CitizenWorkService.blueprintWorkAllowed(s,other,site,at),"personal exit cannot recruit another specialist",out);
            p.escapeFor=null;p.escapingCitizen=null;
            var water=core.offset(5,-1,2);var crop=water.east();var r=new ProductionRegion(BuildingType.FARM_FIELD,water,crop,Direction.NORTH);
            r.flatY=249;r.surveyed=true;r.prepared=true;r.layoutVersion=FarmLayout.VERSION;r.waterSurveyDone=true;
            r.cells.put(ProductionRegion.key(water.getX(),water.getZ()),new ProductionRegion.Cell(water,ProductionRegion.Use.WATER,ResourceLocation.parse("minecraft:wheat"),BuildingType.FARM_FIELD));
            r.cells.put(ProductionRegion.key(crop.getX(),crop.getZ()),new ProductionRegion.Cell(crop,ProductionRegion.Use.CROP,ResourceLocation.parse("minecraft:wheat"),BuildingType.FARM_FIELD));
            var farm=s.addBuilding(BuildingType.FARM_FIELD,water,Direction.NORTH,water,crop.above(),0,1).withRegion(r);farm.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of(water));r.workingBuilding=farm.id();farmer.setManualAssignment(CitizenProfession.FARMER,farm.id());
            var maintenance=s.addBlueprint(BuildingType.FARM_FIELD,water,water.above(40),Direction.NORTH,s.ownerId(),false).withRegion(r);
            check(CitizenWorkService.blueprintWorkAllowed(s,farmer,maintenance,water),"own farm irrigation preserved",out);
            check(!CitizenWorkService.blueprintWorkAllowed(s,other,maintenance,water),"other specialist cannot take farm infrastructure",out);
            check(!CitizenWorkService.blueprintWorkAllowed(s,farmer,maintenance,crop),"farm helper cannot take general region construction",out);
            p.dependencies.add(farm.id());check(!CitizenWorkService.blueprintWorkAllowed(s,farmer,site,at),"farm dependency cannot authorize public soil",out);
            var oldWater=water.above();r.oldIrrigation.add(oldWater.asLong());var retired=s.addBlueprint(BuildingType.HOUSE,oldWater,oldWater,Direction.NORTH,s.ownerId(),false);
            var retire=new TerrainProject(retired.id(),"Own old irrigation",oldWater,List.of(new TerrainProject.Step(oldWater,Blocks.AIR.defaultBlockState(),Blocks.WATER.defaultBlockState())));retire.dependencies.add(farm.id());retired.withTerrainProject(retire);
            check(CitizenWorkService.blueprintWorkAllowed(s,farmer,retired,oldWater),"own irrigation retirement preserved",out);
            farmer.startTask(CitizenWorkState.TO_BLUEPRINT,at,null,site.id());
            check(!CitizenWorkService.blueprintWorkAllowed(s,CitizenRecord.load(farmer.save(level.registryAccess()),level.registryAccess()),site,at),"loaded farmer public claim remains unauthorized",out);
            return "PASS "+out.size()+" "+String.join("; ",out)+"; fixture restored; no resident completion credited";
        } finally {s.deactivate();level.setBlock(at.below(),before,2);data.touch();TerrainLayerService.invalidate(s);}
    }

    public static String live(ServerLevel level) {
        var data=SettlementSavedData.get(level);data.findByCore(new BlockPos(47700,250,47700)).filter(old->old.name().equals("Worker85 public engineering and specialist production")).ifPresent(SettlementRecord::deactivate);if(data.findByCore(CORE).isPresent())throw new IllegalStateException("FIXTURE_EXISTS");
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)level.setChunkForced((CORE.getX()>>4)+x,(CORE.getZ()>>4)+z,true);
        for(var at:BlockPos.betweenClosed(CORE.offset(-12,-4,-10),CORE.offset(12,5,10)))if(!level.getBlockState(at).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");
        for(int x=-12;x<=12;x++)for(int z=-10;z<=10;z++)for(int y=-4;y<=-1;y++)level.setBlock(CORE.offset(x,y,z),Blocks.DIRT.defaultBlockState(),2);
        level.setBlock(CORE,ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),2);
        var s=data.createOrRecover(CORE,UUID.randomUUID(),"Worker85 public engineering and specialist production").value();s.upgradeLevel();s.upgradeLevel();
        s.terrain().enabled=false;s.autoRoads().enabled=false;s.decorations().enabled=false;
        var store=CORE.offset(-6,0,-6);level.setBlock(store,Blocks.BARREL.defaultBlockState(),2);
        var warehouse=s.addBuilding(BuildingType.WAREHOUSE,store,Direction.NORTH,store.offset(-2,0,-2),store.offset(2,2,2),0,1);
        warehouse.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of(store));s.terrain().surveyed.add(warehouse.id());
        WarehouseInventoryService.insert(level,warehouse,new ItemStack(Items.DIRT,8));WarehouseInventoryService.insert(level,warehouse,new ItemStack(Items.GRANITE,4));
        WarehouseInventoryService.insert(level,warehouse,new ItemStack(Items.BREAD,64));WarehouseInventoryService.insert(level,warehouse,new ItemStack(Items.DIAMOND_SHOVEL,2));
        UUID mother=UUID.randomUUID();
        for(int x=2;x<=3;x++)for(int z=0;z<=1;z++) {
            var steps=new ArrayList<TerrainProject.Step>();for(int y=248;y<=249;y++) {var at=CORE.offset(x,0,z).atY(y);level.setBlock(at,Blocks.AIR.defaultBlockState(),2);steps.add(new TerrainProject.Step(at,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()));}
            var p=ProductionRegionService.publish(level,s,mother,"Worker85 public fill",steps.getFirst().position(),steps);p.explicit=true;p.publicGround=true;p.groundSurfaces.add(steps.getLast().position());s.terrain().index(p);
        }
        var first=CORE.offset(-5,-1,4);var last=first.east(2);var region=new ProductionRegion(BuildingType.FARM_FIELD,first,last,Direction.NORTH);
        region.flatY=249;region.surveyed=true;region.prepared=true;region.layoutVersion=FarmLayout.VERSION;region.waterSurveyDone=true;region.nextReview=level.getGameTime()+1000000;
        for(int x=0;x<3;x++) {var at=first.east(x);region.cells.put(ProductionRegion.key(at.getX(),at.getZ()),new ProductionRegion.Cell(at,ProductionRegion.Use.CROP,ResourceLocation.parse("minecraft:wheat"),BuildingType.FARM_FIELD));region.readyCrops.add(at.asLong());region.cultivated.add(at.asLong());level.setBlock(at,Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE,7),2);level.setBlock(at.above(),Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7),2);}
        level.setBlock(first.west(),Blocks.WATER.defaultBlockState(),2);
        var gate=first.north();region.cells.put(ProductionRegion.key(gate.getX(),gate.getZ()),new ProductionRegion.Cell(gate,ProductionRegion.Use.GATE,ResourceLocation.parse("minecraft:wheat"),BuildingType.FARM_FIELD));level.setBlock(gate.above(),Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.NORTH),2);
        var farm=s.addBuilding(BuildingType.FARM_FIELD,first,Direction.NORTH,first,last.above(),0,1).withRegion(region);region.workingBuilding=farm.id();farm.updateFunction(true,BuildingStatus.OPERATIONAL,3,List.of(first));s.terrain().surveyed.add(farm.id());
        while(s.citizens().size()<12)s.addMigrant();int n=0;
        for(var profession:CitizenProfession.values()) {
            var c=new ArrayList<>(s.citizens()).get(n++);c.setManualAssignment(profession,profession==CitizenProfession.FARMER?farm.id():null);c.updateProjection(null,CORE.offset(-2,0,n%5));c.insertIntoInventory(new ItemStack(Items.BREAD,16));
            if(profession==CitizenProfession.FARMER){c.insertIntoInventory(new ItemStack(Items.DIAMOND_HOE));c.insertIntoInventory(new ItemStack(Items.WHEAT_SEEDS,16));var site=s.blueprints().stream().filter(b->b.terrainProject()!=null).findFirst().orElseThrow();c.startTask(CitizenWorkState.TO_BLUEPRINT,site.terrainProject().steps.getFirst().position(),null,site.id());}
        }
        var extra=new ArrayList<>(s.citizens()).get(11);extra.setManualAssignment(CitizenProfession.WORKER,null);extra.updateProjection(null,CORE.west(2));extra.insertIntoInventory(new ItemStack(Items.BREAD,16));
        ForceWorkDebugService.set(level,s,false);CitizenProjectionService.ensureSettlementCitizens(level,s);SaveAcceptanceService.load(level,s,2);data.touch();TerrainLayerService.invalidate(s);
        return "OK "+s.id()+"; 2 workers, all 10 specialist professions; 8 real fill cells; farmer begins with an obsolete public claim and three mature crops; finite stock; normal work";
    }

    public static String accessFixture(ServerLevel level) {
        var data=SettlementSavedData.get(level);var s=data.findByCore(CORE).orElseThrow();
        if(!s.name().equals("Worker85 public engineering and specialist production"))throw new IllegalStateException("ISOLATED_FIXTURE_ONLY");
        var farm=s.buildings().stream().filter(b->b.type()==BuildingType.FARM_FIELD).findFirst().orElseThrow();var r=farm.region();var gate=CORE.offset(-5,-1,3);
        r.cells.put(ProductionRegion.key(gate.getX(),gate.getZ()),new ProductionRegion.Cell(gate,ProductionRegion.Use.GATE,ResourceLocation.parse("minecraft:wheat"),BuildingType.FARM_FIELD));
        level.setBlock(gate.above(),Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.NORTH),2);
        ProductionRegionService.validate(level,farm);data.touch();return "TEST_INPUT_GATE_ADDED readyCrops="+r.readyCrops.size()+"; setup only; no resident work credited";
    }

    public static String inspect(ServerLevel level) {
        var s=SettlementSavedData.get(level).findByCore(CORE).orElseThrow();var j=new JsonObject();j.addProperty("settlement",s.id().toString());j.addProperty("pending",s.blueprints().size());int filled=0;var blocks=new JsonObject();
        for(int x=2;x<=3;x++)for(int z=0;z<=1;z++)for(int y=248;y<=249;y++){var at=CORE.offset(x,0,z).atY(y);var state=level.getBlockState(at);if(Block.isShapeFullBlock(state.getCollisionShape(level,at)))filled++;var name=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();blocks.addProperty(name,blocks.has(name)?blocks.get(name).getAsInt()+1:1);}
        j.addProperty("filled",filled);j.add("fillBlocks",blocks);j.addProperty("unauthorizedClaims",s.citizens().stream().filter(c->c.targetBlueprintId()!=null && !CitizenWorkService.blueprintWorkAllowed(s,c,s.blueprint(c.targetBlueprintId()),c.targetPosition())).count());return j.toString();
    }
}
