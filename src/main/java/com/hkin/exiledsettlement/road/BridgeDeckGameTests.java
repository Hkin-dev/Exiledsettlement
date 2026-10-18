package com.hkin.exiledsettlement.road;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import java.util.*;

@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_bridgedeck")
@PrefixGameTestTemplate(false)
public final class BridgeDeckGameTests {
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(RegisterGameTestsEvent e){if("timed_construction".equals(System.getProperty("exiledsettlement.testSuite")))e.register(BridgeDeckGameTests.class);}
    private static int serial;
    private record Fixture(SettlementRecord settlement,BlockPos origin){}
    private static Fixture fixture(GameTestHelper h){
        var l=h.getLevel();var at=h.absolutePos(new BlockPos(1500000+serial++*512,64,0));
        for(int x=-24;x<=32;x++)for(int z=-15;z<=40;z++){
            var p=at.offset(x,0,z);l.getChunkAt(p);l.setChunkForced(p.getX()>>4,p.getZ()>>4,true);
            for(int y=-8;y<=12;y++)l.setBlock(p.offset(0,y,0),
                    (y<=-7?Blocks.STONE:z>=3 && y<=-1?Blocks.DIRT:Blocks.AIR).defaultBlockState(),2);
        }
        var s=SettlementSavedData.get(l).createOrRecover(at.offset(4,0,32),UUID.randomUUID(),"Deck building test").value();
        s.upgradeLevel();s.upgradeLevel();
        for(int pass=0;pass<12;pass++)for(int x=-2;x<=3;x++)for(int z=-2;z<=3;z++)s.claimChunk((at.getX()>>4)+x,(at.getZ()>>4)+z);
        s.terrain().enabled=false;s.autoRoads().enabled=false;s.decorations().enabled=false;s.community().stewardEnabled=false;
        for(var c:s.citizens())c.setManualAssignment(CitizenProfession.HERDSMAN,null);
        l.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false,l.getServer());
        l.setDayTime(1000);return new Fixture(s,at);
    }
    private static PlayerBridgePlan plan(GameTestHelper h,Fixture f,boolean x){
        var at=f.origin();var p=new PlayerBridgePlan(at.offset(0,-1,-1),at.offset(7,9,1),x,Integer.MIN_VALUE);
        for(int i=0;i<10000 && !p.ready && p.problem.isEmpty();i++)p.survey(h.getLevel(),f.settlement(),8);
        h.assertTrue(p.ready,"Deck rejected: "+p.problem);return p;
    }
    @GameTest(templateNamespace="exiledsettlement_bridgedeck",template="empty",timeoutTicks=500)
    public static void deckHasExactSelectionSparsePillarsAndNoShorePrerequisite(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var p=plan(h,f,true);var at=f.origin();
        h.assertTrue(p.surface==at.getY() && p.minRow()==p.start && p.maxRow()==p.end && p.deck.size()==24,"Selection or first-point height changed");
        h.assertTrue(p.anchors.size()==24 && p.anchors.stream().allMatch(v->v.getZ()==at.getZ()-1 || v.getZ()==at.getZ()+1),"Sparse pillars incorrect");
        h.assertTrue(p.steps.stream().allMatch(v->v.position().getX()>=p.start && v.position().getX()<=p.end),"Bridge grew an approach");
        h.assertTrue(p.steps.stream().noneMatch(v->v.position().equals(at.offset(3,-4,0))),"Pillars filled the central underpass");
        h.assertTrue(List.of(p.shore(false),p.shore(true)).stream().noneMatch(v->BridgeConstructionSequence.dryStand(l,v)),"Fixture has standing ground at the bridge ends");
        h.assertTrue(p.deck.stream().allMatch(v->l.getBlockState(v).isAir()),"Preview modified the world");
        f.settlement().deactivate();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_bridgedeck",template="empty",timeoutTicks=500)
    public static void sideArrivalAnyWarehouseAndSavedModelMatchBuildings(GameTestHelper h){
        var f=fixture(h);var p=plan(h,f,true);var l=h.getLevel();var s=f.settlement();var b=PlayerBridgePlan.publish(l,s,p);var project=b.terrainProject();
        h.assertTrue(PlayerBridgeWork.arrived(l,project,f.origin().offset(4,0,3),project.steps.getFirst().position()),"Side of site is not accepted");
        h.assertTrue(!PlayerBridgeWork.arrived(l,project,f.origin().offset(4,0,8),project.steps.getFirst().position()),"Remote worker accepted");
        h.assertTrue(p.supplyingBank(f.origin().offset(4,0,15)) && WorkTaskService.priority(s,b)==0 && BuildingWorkPolicy.construction(s,b),"Bridge retains bank-only supply or public permissions");
        h.assertTrue(!WorkTaskService.automaticSlotAvailable(s,b,project.steps.getFirst().position(),Map.of(b.id(),2)),"Bridge auto-dispatch exceeds two building workers");
        var loaded=TerrainProject.load(project.save());
        h.assertTrue(loaded.playerBridge.buildingModel && loaded.playerBridge.workBottom==p.workBottom && loaded.playerBridge.inWorkArea(f.origin().offset(4,0,3)),"Model or worksite lost on save");
        var legacy=project.save();legacy.getCompound("PlayerBridge").remove("BuildingModel");
        h.assertTrue(!TerrainProject.load(legacy).playerBridge.buildingModel,"Legacy bridge silently migrated");
        s.deactivate();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_bridgedeck",template="empty",timeoutTicks=500)
    public static void waterPillarsAndZDirectionRemainInsideSelection(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var at=f.origin();
        for(int x=0;x<8;x++)for(int z=-1;z<=1;z++)for(int y=-6;y<=-3;y++)l.setBlock(at.offset(x,y,z),Blocks.WATER.defaultBlockState(),2);
        var p=plan(h,f,false);
        h.assertTrue(p.surface==at.getY() && p.deck.size()==24,"Z selection changed");
        h.assertTrue(p.steps.stream().filter(v->!v.original().getFluidState().isEmpty()).allMatch(v->v.fill() && p.anchors.contains(v.position())),"Water was cleared or replaced outside the pillars");
        h.assertTrue(p.steps.stream().filter(v->v.fill() && !v.original().getFluidState().isEmpty()).count()==16,"Submerged pillar plan incomplete");
        f.settlement().deactivate();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_bridgedeck",template="empty",timeoutTicks=500)
    public static void oldAutomaticMarksDoNotVetoButFormalBuildingsDo(GameTestHelper h){
        var f=fixture(h);var s=f.settlement();var l=h.getLevel();var p=plan(h,f,true);
        for(var v:p.steps)s.terrain().suppressed.add(v.position().asLong());
        var site=PlayerBridgePlan.publish(l,s,p);var first=site.terrainProject().steps.getFirst();p.admitted.set(0);
        h.assertTrue(TerrainService.guard(l,s,site,first).isEmpty(),"Old automatic suppression vetoed accepted building");
        s.addBuilding(BuildingType.HOUSE,first.position(),Direction.NORTH,first.position(),first.position().above(4),0,1);
        h.assertTrue(TerrainService.guard(l,s,site,first).equals("PROTECTED_AREA"),"Late formal building was not protected");
        s.deactivate();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_bridgedeck",template="empty",timeoutTicks=500)
    public static void confirmationRechecksTerrainAndTransfersOnlyExactPublicCells(GameTestHelper h){
        var f=fixture(h);var s=f.settlement();var l=h.getLevel();var p=plan(h,f,true);var at=p.steps.getFirst().position();var outside=at.east(30);
        var old=new TerrainProject(UUID.randomUUID(),"Public overlap",at,List.of(
                new TerrainProject.Step(at,Blocks.STONE.defaultBlockState(),Blocks.AIR.defaultBlockState()),
                new TerrainProject.Step(outside,Blocks.STONE.defaultBlockState(),Blocks.AIR.defaultBlockState())));
        old.publicGround=true;old.mark(0);s.terrain().index(old);var evidence=old.save();
        l.setBlock(at,Blocks.GOLD_BLOCK.defaultBlockState(),2);boolean rejected=false;
        try{PlayerBridgePlan.publish(l,s,p);}catch(IllegalStateException expected){rejected=true;}
        h.assertTrue(rejected && old.save().equals(evidence) && s.blueprints().isEmpty(),"Failed confirmation mutated existing work");
        l.setBlock(at,Blocks.AIR.defaultBlockState(),2);PlayerBridgePlan.publish(l,s,p);
        h.assertTrue(old.abandoned.get(0) && old.retiredPaid.get(0) && !old.abandoned.get(1),"Exact transfer lost paid/outside evidence");
        s.deactivate();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_bridgedeck_entity",template="empty",timeoutTicks=20000,batch="deck_entity")
    public static void workerFetchesFromSideWarehouseBuildsPillarsDeckAndRails(GameTestHelper h){
        entityTrial(h,false);
    }
    @GameTest(templateNamespace="exiledsettlement_bridgedeck_entity",template="empty",timeoutTicks=20000,batch="deck_water_entity")
    public static void workerBuildsSubmergedPillarsAndBeamsWithoutShoreStations(GameTestHelper h){
        entityTrial(h,true);
    }
    @GameTest(templateNamespace="exiledsettlement_bridgecontinuity",template="empty",timeoutTicks=4000,batch="deck_continuity")
    public static void stockedWorkerKeepsBridgeAssignmentAcrossEveryBlockAndPhase(GameTestHelper h){
        entityTrial(h,false,true);
    }
    private static void entityTrial(GameTestHelper h,boolean wet){entityTrial(h,wet,false);}
    private static void entityTrial(GameTestHelper h,boolean wet,boolean continuous){
        var f=fixture(h);var l=h.getLevel();var s=f.settlement();var at=f.origin();var data=SettlementSavedData.get(l);
        if(wet)for(int x=-2;x<=9;x++)for(int z=-2;z<=2;z++)for(int y=-6;y<=-2;y++)l.setBlock(at.offset(x,y,z),Blocks.WATER.defaultBlockState(),2);
        var box=at.offset(2,-1,0);l.setBlock(box,Blocks.CHEST.defaultBlockState(),2);
        ((net.minecraft.world.Container)l.getBlockEntity(box)).setItem(0,new ItemStack(Items.DIAMOND,7));
        var plan=plan(h,f,true);
        var t=BlueprintTemplates.create(BuildingType.WAREHOUSE,at.offset(-4,0,35),Direction.NORTH);
        for(var v:t.placements())l.setBlock(v.position(),v.state(),2);
        for(var v:t.placements())BlueprintBlockStateResolver.refreshExisting(l,t,v);
        var warehouse=s.addBuilding(t.type(),t.registrationPosition(),t.facing(),t.minCorner(),t.maxCorner(),t.transitionMargin(),BlueprintTemplates.currentTemplateVersion(t.type()));
        BuildingFunctionService.validateSingle(l,data,s,warehouse);h.assertTrue(warehouse.operational(),"Warehouse fixture invalid");
        var initial=new LinkedHashMap<net.minecraft.world.item.Item,Integer>();
        for(var v:plan.requirements().entrySet()){WarehouseInventoryService.insert(l,warehouse,new ItemStack(v.getKey(),v.getValue()+64));initial.put(v.getKey(),WarehouseInventoryService.countItem(l,warehouse,v.getKey()));}
        for(var item:List.of(Items.IRON_AXE,Items.IRON_PICKAXE,Items.IRON_SHOVEL))WarehouseInventoryService.insert(l,warehouse,new ItemStack(item,12));
        WarehouseInventoryService.insert(l,warehouse,new ItemStack(Items.BREAD,256));
        var c=s.citizens().iterator().next();c.setManualAssignment(CitizenProfession.WORKER,null);c.assignProfession(CitizenProfession.WORKER,null);
        var actor=com.hkin.exiledsettlement.registry.ModEntities.SETTLEMENT_CITIZEN.get().create(l);
        actor.moveTo(at.getX()+4.5,at.getY(),at.getZ()+9.5,0,0);
        actor.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());actor.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());l.addFreshEntity(actor);c.updateProjection(actor.getUUID(),actor.blockPosition());
        h.assertTrue(c.inventory().stream().noneMatch(v->initial.containsKey(v.getItem())),"Worker starts with materials");
        var site=PlayerBridgePlan.publish(l,s,plan);var saved=new TerrainProject[]{site.terrainProject()};var resumed=new BitSet();var fetched=new boolean[]{false};var moved=new boolean[]{false};int[] max={0};
        long[] progressClock={0,0};int[] observedProgress={0};
        if(continuous){
            l.setDayTime(4000);
            for(var entry:plan.requirements().entrySet())c.insertIntoInventory(new ItemStack(entry.getKey(),entry.getValue()));
            for(var item:List.of(Items.DIAMOND_AXE,Items.DIAMOND_PICKAXE,Items.DIAMOND_SHOVEL))c.insertIntoInventory(new ItemStack(item));
            c.insertIntoInventory(new ItemStack(Items.BREAD,32));
            for(var entry:initial.entrySet())entry.setValue(entry.getValue()+plan.requirements().get(entry.getKey()));
            actor.moveTo(at.getX()+4.5,at.getY(),at.getZ()+3.5,0,0);
            for(int i:plan.activeIndices(site.terrainProject()))plan.admitted.set(i);
            h.assertTrue(c.startTask(CitizenWorkState.TO_BLUEPRINT,plan.steps.getFirst().position(),null,site.id()),"Cannot start stocked worker fixture");
        }
        h.onEachTick(()->{
            var p=saved[0];h.assertTrue(actor.isAlive() && !actor.isInWater(),"Worker died or entered water");
            if(continuous && !p.completed){
                h.assertTrue(site.id().equals(c.targetBlueprintId()) && c.workState()==CitizenWorkState.TO_BLUEPRINT,
                        "Bridge assignment dropped after "+p.finished.cardinality()+" blocks: "+c.workState()+" / "+c.workFailure());
                h.assertTrue(plan.inWorkArea(actor.navigationFeetPosition()),"Stocked worker left bridge worksite");
            }
            if(p.finished.cardinality()>observedProgress[0]){
                if(progressClock[0]>0)progressClock[1]=Math.max(progressClock[1],l.getGameTime()-progressClock[0]);
                progressClock[0]=l.getGameTime();observedProgress[0]=p.finished.cardinality();
            }
            max[0]=Math.max(max[0],OptionalWorkBudget.snapshot(s).pending());h.assertTrue(max[0]<=1000,"Quota exceeded");
            if(actor.getZ()<at.getZ()+7)moved[0]=true;
            if(c.inventory().stream().anyMatch(v->initial.containsKey(v.getItem())))fetched[0]=true;
            if(p.finished.cardinality()>0 && p.playerBridge.phase<3 && !resumed.get(p.playerBridge.phase)){
                resumed.set(p.playerBridge.phase);var restored=TerrainProject.load(p.save());
                h.assertTrue(restored.finished.equals(p.finished) && restored.playerBridge.admitted.equals(p.playerBridge.admitted),"Midphase progress lost");
                saved[0]=restored;site.withTerrainProject(restored);s.terrain().index(restored);
            }
            if(l.getGameTime()%200==0)com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("BRIDGEDECK_PROGRESS finished={}/{} state={} failure={} position={} target={}",p.finished.cardinality(),p.steps.size(),c.workState(),c.workFailure(),actor.position(),c.targetPosition());
        });
        h.startSequence().thenWaitUntil(()->h.assertTrue(saved[0].completed,"Bridge not completed"))
            .thenExecute(()->{
                var p=saved[0];h.assertTrue(fetched[0] && moved[0] && resumed.cardinality()==3,"Real fetching, approach or save/resume missing");
                h.assertTrue(TerrainService.constructionComplete(l,p) && s.autoRoads().routes.isEmpty(),"World incomplete or dependent on road");
                for(var entry:initial.entrySet()){
                    int left=WarehouseInventoryService.countItem(l,warehouse,entry.getKey())+s.citizens().stream().flatMap(v->v.inventory().stream()).filter(v->v.is(entry.getKey())).mapToInt(ItemStack::getCount).sum();
                    long placed=p.steps.stream().filter(v->v.fill() && v.target().getBlock().asItem()==entry.getKey()).count();
                    h.assertTrue(entry.getValue()-left==placed,"Material imbalance: "+entry.getKey()+" expected "+placed+" spent "+(entry.getValue()-left));
                }
                int diamonds=WarehouseInventoryService.countItem(l,warehouse,Items.DIAMOND)+s.citizens().stream().flatMap(v->v.inventory().stream()).filter(v->v.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum();
                h.assertTrue(diamonds==7,"Container contents lost or copied");
                for(var rail:p.bridgeRails){var state=l.getBlockState(rail);h.assertTrue(state.getValue(net.minecraft.world.level.block.FenceBlock.EAST) || state.getValue(net.minecraft.world.level.block.FenceBlock.WEST),"Rail connections stale");}
                h.assertTrue(wet?l.getBlockState(at.offset(3,-4,0)).is(Blocks.WATER):l.getBlockState(at.offset(3,-4,0)).isAir(),"Central underpass changed");
                com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("BRIDGEDECK_COMPLETE steps={} realFetched={} continuous={} maxProgressGap={} sideArrival=true savedPhases={} maxPending={} diamonds={}",p.steps.size(),!continuous,continuous,progressClock[1],resumed,max[0],diamonds);
                if(Boolean.getBoolean("exiledsettlement.bridgeDeckNative")){
                    var result=new com.google.gson.JsonObject();result.addProperty("passed",true);result.addProperty("settlement",s.id().toString());result.addProperty("project",p.id.toString());
                    result.addProperty("continuous",continuous);result.addProperty("maxProgressGapTicks",progressClock[1]);
                    if(continuous){result.addProperty("assignmentDrops",0);result.addProperty("worksiteExits",0);}
                    result.addProperty("steps",p.steps.size());result.addProperty("finished",p.finished.cardinality());result.addProperty("sideArrival",true);result.addProperty("fetched",true);result.addProperty("savedPhases",resumed.cardinality());result.addProperty("diamonds",diamonds);result.addProperty("wet",wet);result.addProperty("tickRate",l.getServer().tickRateManager().tickrate());result.addProperty("playerSavesAccessed",false);
                    result.addProperty("fetched",!continuous && fetched[0]);
                    try{java.nio.file.Files.createDirectories(com.hkin.exiledsettlement.debug.SaveAcceptanceService.directory());java.nio.file.Files.writeString(com.hkin.exiledsettlement.debug.SaveAcceptanceService.file("bridgedeck-result",".json"),new com.google.gson.Gson().toJson(result));}catch(java.io.IOException e){throw new IllegalStateException(e);}
                }
                s.deactivate();actor.discard();
            }).thenSucceed();
    }
}
