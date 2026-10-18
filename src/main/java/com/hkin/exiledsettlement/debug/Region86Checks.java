package com.hkin.exiledsettlement.debug;
import com.google.gson.*;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.region.*;
import com.hkin.exiledsettlement.registry.ModBlocks;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import java.util.*;

/** Only explicit local-test commands. All fixtures are inputs, never resident completion. */
public final class Region86Checks {
    private static final BlockPos CORE=new BlockPos(49000,250,49000);
    private static void check(boolean ok,String name,List<String> list){if(!ok)throw new IllegalStateException(name);list.add(name);}
    private static ProductionRegion region(BuildingType type,BlockPos first,BlockPos last){
        var r=new ProductionRegion(type,first,last,Direction.NORTH);r.flatY=first.getY();r.surveyed=true;r.waterSurveyDone=true;r.layoutReady=true;r.groundVersion=RegionGroundLayout.VERSION;r.nextReview=Long.MAX_VALUE;
        for(int x=first.getX();x<=last.getX();x++)for(int z=first.getZ();z<=last.getZ();z++){
            var at=new BlockPos(x,first.getY(),z);r.cells.put(ProductionRegion.key(x,z),new ProductionRegion.Cell(at,type==BuildingType.TREE_FARM?ProductionRegion.Use.FOREST:ProductionRegion.Use.PASTURE,BuildingProductionService.defaultFor(type),type));r.gradeOrigins.put(ProductionRegion.key(x,z),at.getY());
        }r.animalSafety.putInt("Phase",7);r.rebuildUses();return r;
    }
    public static String run(ServerLevel l){
        var data=SettlementSavedData.get(l);var core=new BlockPos(49300,250,49300);if(data.findByCore(core).isPresent())throw new IllegalStateException("FIXTURE_EXISTS");
        var s=data.createOrRecover(core,UUID.randomUUID(),"Region86 rules").value();s.terrain().enabled=false;s.terrain().publicPaused=true;
        var checks=new ArrayList<String>();var before=new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        try{
            for(var type:List.of(BuildingType.TREE_FARM,BuildingType.PASTURE_AREA)){
                var at=core.offset(type==BuildingType.TREE_FARM?2:8,-1,0);var r=region(type,at,at.east(2).south(2));
                var b=s.addAreaBlueprint(type,at,at.above(96),Direction.NORTH,s.ownerId(),false,r.min(),r.max()).withRegion(r);
                var middle=at.east().south();var c=r.cell(middle);
                for(var p:List.of(middle,middle.below(),middle.above(),middle.above(2),middle.above(6),middle.above(7))){before.putIfAbsent(p,l.getBlockState(p));l.setBlock(p,p.getY()<=middle.getY()?Blocks.DIRT.defaultBlockState():Blocks.AIR.defaultBlockState(),2);}
                check(RegionGroundService.clearanceTop(r,249,270)==255,type+" clearance stops at sixth block",checks);
                l.setBlock(middle.above(7),Blocks.STONE.defaultBlockState(),2);check(!RegionGroundService.clearanceNeeded(l,r,c),type+" seventh block excluded",checks);
                l.setBlock(middle.above(6),Blocks.STONE.defaultBlockState(),2);check(RegionGroundService.clearanceNeeded(l,r,c),type+" sixth block included",checks);l.setBlock(middle.above(6),Blocks.AIR.defaultBlockState(),2);
                if(type==BuildingType.TREE_FARM){l.setBlock(middle.above(),Blocks.OAK_LOG.defaultBlockState(),2);l.setBlock(middle.above(2),Blocks.OAK_LEAVES.defaultBlockState(),2);check(!RegionGroundService.clearanceNeeded(l,r,c),"healthy existing tree preserved",checks);r.gradeOrigins.put(ProductionRegion.key(middle.getX(),middle.getZ()),248);check(RegionGroundService.clearanceNeeded(l,r,c),"tree on changed ground not silently exempt",checks);r.gradeOrigins.put(ProductionRegion.key(middle.getX(),middle.getZ()),249);l.setBlock(middle.above(),Blocks.AIR.defaultBlockState(),2);l.setBlock(middle.above(2),Blocks.AIR.defaultBlockState(),2);}
                var target=middle.above(2);l.setBlock(target,Blocks.STONE.defaultBlockState(),2);
                var paid=middle;var outside=at.west().above(3);before.putIfAbsent(outside,l.getBlockState(outside));
                var oldSite=s.addBlueprint(BuildingType.HOUSE,target,target.above(96),Direction.NORTH,s.ownerId(),false);
                var old=new TerrainProject(oldSite.id(),"Old public overlap",target,List.of(new TerrainProject.Step(paid,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()),new TerrainProject.Step(target,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()),new TerrainProject.Step(outside,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));old.publicGround=true;old.mark(0);old.dependencies.add(s.id());oldSite.withTerrainProject(old);s.terrain().index(old);
                var newWork=ProductionRegionService.publish(l,s,b.id(),"Region86 clearance",target,List.of(new TerrainProject.Step(target,Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState())));
                check(newWork!=null && b.id().equals(newWork.regionOwner),type+" owns replaced public step",checks);
                check(newWork.explicit && !newWork.publicGround && !newWork.road,type+" has independent scope",checks);
                check(old.abandoned.get(1) && old.finished.get(0) && !old.abandoned.get(0) && !old.finished.get(2),type+" keeps paid and unrelated public work",checks);
                check(l.getBlockState(target).is(Blocks.STONE) && l.getBlockState(paid).is(Blocks.DIRT),type+" publishing does not edit world",checks);
                check(TerrainService.allowed(s,s.blueprint(newWork.id)),type+" works while public grading paused",checks);
                r.groundPaused=true;check(!TerrainService.allowed(s,s.blueprint(newWork.id)),type+" own pause blocks child",checks);r.groundPaused=false;
                check(WorkTaskService.priority(s,b)==0 && WorkTaskService.mother(s,s.blueprint(newWork.id),target).equals(b.id()),type+" new construction parent P0",checks);
                r.groundMaintenance=true;check(WorkTaskService.priority(s,s.blueprint(newWork.id))==2 && TerrainService.allowed(s,s.blueprint(newWork.id)),type+" own maintenance P2 independent",checks);
                check(TerrainService.guard(l,s,s.blueprint(newWork.id),newWork.steps.getFirst()).isEmpty(),type+" actual guard ignores public pause",checks);
                check(b.id().equals(TerrainProject.load(newWork.save()).regionOwner),type+" owner persists",checks);
                int depCount=newWork.dependencies.size();check(ProductionRegionService.publish(l,s,s.id(),"Public must not borrow",target,List.of(new TerrainProject.Step(target,Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState())))==null && newWork.dependencies.size()==depCount,type+" public cannot borrow region child",checks);
                var saved=ProductionRegion.load(r.save());check(saved.flatY==249 && saved.groundMaintenance,type+" saved regional state",checks);
            }
            return "PASS "+checks.size()+" "+String.join("; ",checks)+"; restored fixture inputs, no resident completion";
        }finally{for(var e:before.entrySet())l.setBlock(e.getKey(),e.getValue(),2);s.deactivate();data.touch();TerrainLayerService.invalidate(s);}
    }
    public static String live(ServerLevel l){
        var data=SettlementSavedData.get(l);if(data.findByCore(CORE).isPresent())throw new IllegalStateException("FIXTURE_EXISTS");
        for(var at:BlockPos.betweenClosed(CORE.offset(-16,-3,-9),CORE.offset(16,8,10)))if(!l.getBlockState(at).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");
        for(int x=-16;x<=16;x++)for(int z=-9;z<=10;z++)for(int y=-3;y<=-1;y++)l.setBlock(CORE.offset(x,y,z),Blocks.DIRT.defaultBlockState(),2);
        l.setBlock(CORE,ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),2);var s=data.createOrRecover(CORE,UUID.randomUUID(),"Region86 independent pasture and forest").value();s.upgradeLevel();s.upgradeLevel();s.terrain().enabled=false;s.terrain().publicPaused=true;s.autoRoads().enabled=false;s.decorations().enabled=false;
        var store=CORE.offset(0,0,-6);l.setBlock(store,Blocks.BARREL.defaultBlockState(),2);var warehouse=s.addBuilding(BuildingType.WAREHOUSE,store,Direction.NORTH,store.offset(-2,0,-2),store.offset(2,2,2),0,1);warehouse.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of(store));s.terrain().surveyed.add(warehouse.id());
        for(var item:List.of(new ItemStack(Items.DIRT,16),new ItemStack(Items.OAK_FENCE,64),new ItemStack(Items.OAK_FENCE_GATE,8),new ItemStack(Items.DIAMOND_PICKAXE,4),new ItemStack(Items.DIAMOND_SHOVEL,4),new ItemStack(Items.BREAD,64)))WarehouseInventoryService.insert(l,warehouse,item);
        for(var type:List.of(BuildingType.TREE_FARM,BuildingType.PASTURE_AREA)){
            var first=CORE.offset(type==BuildingType.TREE_FARM?-12:4,-1,2);var r=region(type,first,first.east(4).south(4));r.groundPaused=type==BuildingType.TREE_FARM;
            var b=s.addAreaBlueprint(type,first,first.above(96),Direction.NORTH,s.ownerId(),false,r.min(),r.max()).withRegion(r);var middle=first.east(2).south(2);
            for(int h=1;h<=7;h++)l.setBlock(middle.above(h),Blocks.STONE.defaultBlockState(),2);
            if(type==BuildingType.TREE_FARM){var tree=first.east().south();for(int h=1;h<=4;h++)l.setBlock(tree.above(h),Blocks.OAK_LOG.defaultBlockState(),2);l.setBlock(tree.above(5),Blocks.OAK_LEAVES.defaultBlockState(),2);r.naturalTrees.add(ProductionRegion.key(tree.getX(),tree.getZ()));}
            else{
                var low=first.east().south();l.setBlock(low,Blocks.AIR.defaultBlockState(),2);l.setBlock(low.below(),Blocks.AIR.defaultBlockState(),2);
                var extra=CORE.offset(-1,-1,0);l.setBlock(extra,Blocks.AIR.defaultBlockState(),2);var old=s.addBlueprint(BuildingType.HOUSE,low,low.above(96),Direction.NORTH,s.ownerId(),false);
                var p=new TerrainProject(old.id(),"Region86 old public pending",low,List.of(new TerrainProject.Step(low.below(),Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()),new TerrainProject.Step(low,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()),new TerrainProject.Step(extra,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));p.publicGround=true;p.dependencies.add(s.id());old.withTerrainProject(p);s.terrain().index(p);
            }
        }
        while(s.citizens().size()<6)s.addMigrant();int n=0;for(var c:s.citizens()){c.setManualAssignment(n<4?CitizenProfession.WORKER:n==4?CitizenProfession.FARMER:CitizenProfession.HERDSMAN,null);c.updateProjection(null,CORE.offset(n-3,0,-1));c.insertIntoInventory(new ItemStack(Items.BREAD,16));n++;}
        CitizenProjectionService.ensureSettlementCitizens(l,s);SaveAcceptanceService.load(l,s,2);data.touch();return "OK "+s.id()+"; forest paused, pasture P0, public disabled and paused; finite stock; six-block clearance, seventh remains; fixture only";
    }
    public static String resume(ServerLevel l){var data=SettlementSavedData.get(l);var s=data.findByCore(CORE).orElseThrow();var b=s.blueprints().stream().filter(p->p.region()!=null && p.type()==BuildingType.TREE_FARM).findFirst().orElseThrow();b.region().groundPaused=false;data.touch();return "OK forest only resumed; public remains paused";}
    public static String inspect(ServerLevel l){var s=SettlementSavedData.get(l).findByCore(CORE).orElseThrow();var j=new JsonObject();j.addProperty("settlement",s.id().toString());j.addProperty("publicEnabled",s.terrain().enabled);j.addProperty("publicPaused",s.terrain().publicPaused);var blocks=new JsonObject();for(int x:List.of(-10,6)){var c=CORE.offset(x,-1,4);for(int h=1;h<=7;h++)blocks.addProperty((x<0?"forest":"pasture")+h,l.getBlockState(c.above(h)).isAir()?"air":net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(l.getBlockState(c.above(h)).getBlock()).toString());}j.add("clearance",blocks);var jobs=new JsonArray();for(var b:s.blueprints())if(b.terrainProject()!=null){var p=b.terrainProject();var a=new JsonObject();a.addProperty("name",p.name);a.addProperty("owner",String.valueOf(p.regionOwner));a.addProperty("allowed",TerrainService.allowed(s,b));a.addProperty("pending",TerrainService.pending(l,b,Set.of()).size());var guards=new JsonArray();for(int i=p.cursor;i<p.steps.size();i++)if(!p.finished.get(i)){var step=p.steps.get(i);var g=new JsonObject();g.addProperty("at",step.position().toShortString());g.addProperty("guard",TerrainService.guard(l,s,b,step));guards.add(g);}a.add("guards",guards);jobs.add(a);}j.add("jobs",jobs);j.addProperty("retainedTree",l.getBlockState(CORE.offset(-11,0,3)).is(Blocks.OAK_LOG));j.addProperty("unrelatedPublicStillAir",l.getBlockState(CORE.offset(-1,-1,0)).isAir());return j.toString();}
}
