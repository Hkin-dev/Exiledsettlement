package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_roadcontinuity") @PrefixGameTestTemplate(false)
public final class RoadContinuityGameTests {
    @net.neoforged.bus.api.SubscribeEvent public static void register(net.neoforged.neoforge.event.RegisterGameTestsEvent e){
        if("road_continuity".equals(System.getProperty("exiledsettlement.testSuite")))e.register(RoadContinuityGameTests.class);
    }
    private static int serial;
    private record Scene(SettlementRecord s,BlockPos at){}
    private static Scene scene(GameTestHelper h){
        var l=h.getLevel();var at=h.absolutePos(new BlockPos(1800000+serial++*512,80,0));
        for(int x=-18;x<=18;x++)for(int z=-18;z<=18;z++){
            var p=at.offset(x,0,z);l.getChunkAt(p);l.setChunkForced(p.getX()>>4,p.getZ()>>4,true);
            for(int y=-3;y<=5;y++)l.setBlock(p.above(y),(y==-3?Blocks.STONE:y<0?Blocks.DIRT:Blocks.AIR).defaultBlockState(),2);
        }
        var s=SettlementSavedData.get(l).createOrRecover(at,UUID.randomUUID(),"Road continuity isolated").value();
        s.upgradeLevel();s.upgradeLevel();for(int pass=0;pass<10;pass++)for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)s.claimChunk((at.getX()>>4)+x,(at.getZ()>>4)+z);
        s.terrain().enabled=false;s.autoRoads().enabled=false;s.autoRoads().gradingReviewVersion=1;s.decorations().enabled=false;s.community().stewardEnabled=false;
        for(var c:s.citizens())c.setManualAssignment(CitizenProfession.HERDSMAN,null);
        l.setDayTime(6000);l.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false,l.getServer());
        return new Scene(s,at);
    }
    private static void survey(Scene f,int width,int length){
        var w=f.s().terrain().publicGround;w.phase=4;w.cursor=0;w.roadsIntegrated=false;
        w.minX=f.at().getX()-width/2;w.maxX=w.minX+width-1;w.minZ=f.at().getZ()-length/2;w.maxZ=w.minZ+length-1;
        w.representative=f.at().getY()-1;w.anchors.put(f.at().below().atY(0).asLong(),f.at().getY()-1);
    }
    private static AutoRoadState.Route damaged(GameTestHelper h,Scene f){
        var at=f.at().offset(5,-1,0);var r=new AutoRoadState.Route(UUID.randomUUID(),at,List.of(at));r.width=3;
        r.completed.set(0);r.admitted.set(0);r.advance();f.s().autoRoads().routes.put("orphan",r);
        RoadSpaceData.get(h.getLevel()).addAll(List.of(at));h.getLevel().setBlock(at,Blocks.DIRT_PATH.defaultBlockState(),2);
        h.getLevel().setBlock(at,Blocks.AIR.defaultBlockState(),2);return r;
    }
    @GameTest(templateNamespace="exiledsettlement_roadcontinuity",template="empty",timeoutTicks=3000)
    public static void repeatedOrdinaryEditsPreserveSurveyAndPublishWork(GameTestHelper h){
        var f=scene(h);var s=f.s();var l=h.getLevel();survey(f,23,23);s.terrain().enabled=true;
        var w=s.terrain().publicGround;var hole=f.at().offset(3,-1,0);l.setBlock(hole,Blocks.AIR.defaultBlockState(),2);
        long[] cursor={0};boolean[] leftSampling={false};int[] edits={0};
        h.onEachTick(()->{
            if(w.phase==4){
                h.assertTrue(w.cursor>=cursor[0],"Survey cursor restarted by ordinary edits");cursor[0]=w.cursor;
                var p=f.at().offset(10,0,10);l.setBlock(p,(edits[0]++%2==0?Blocks.DIRT:Blocks.AIR).defaultBlockState(),2);
                PlayerRoadEdits.changed(l,p);
                h.assertTrue(w.phase==4,"Ordinary block edit reset whole survey");
                if(edits[0]==1){var restored=new PublicGroundService.State();restored.load(w.save());h.assertTrue(restored.cursor==w.cursor && restored.phase==w.phase,"Edit changed saved survey progress");}
            }else leftSampling[0]=true;
            if(TerrainService.pendingAt(s,hole)){
                h.assertTrue(leftSampling[0]&&cursor[0]>0&&edits[0]>=1,"Sampling never progressed");
                h.assertTrue(l.isEmptyBlock(hole),"Planner edited world");
                com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("ROAD_CONTINUITY_SURVEY edits={} cursor={} phase={} published=true",edits[0],cursor[0],w.phase);
                s.deactivate();h.succeed();
            }
        });
    }
    @GameTest(templateNamespace="exiledsettlement_roadcontinuity",template="empty",timeoutTicks=3000)
    public static void movedEntranceKeepsRoadIdentityAndPaidHistory(GameTestHelper h){
        var f=scene(h);var s=f.s();var l=h.getLevel();var pos=f.at().offset(12,0,0);
        var b=s.addBuilding(BuildingType.WAGON,pos);b.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of(pos));
        var end=AutoRoadService.entrances(l,s,b).getFirst();var old=new AutoRoadState.Route(b.id(),end.north(8),List.of(end.north(8)));
        old.width=3;old.completed.set(0);old.advance();s.autoRoads().routes.put("old-entry",old);s.autoRoads().enabled=true;s.autoRoads().workReady=true;
        h.assertTrue(AdaptiveRoadService.review(l,s),"Moved entrance was skipped");
        var saved=AutoRoadPlanner.saved(s.autoRoads());
        h.assertTrue(saved.getString("Key").equals("old-entry")&&saved.getLong("End")==end.asLong(),"Moved route did not reuse identity");
        h.assertTrue(old.completed.get(0),"Paid route history changed before replacement");
        l.setBlock(old.entrance,Blocks.DIRT_PATH.defaultBlockState(),2);RoadSpaceData.get(l).addAll(List.of(old.entrance));
        h.onEachTick(()->{
            AutoRoadPlanner.tickLevel(l);var replacement=s.autoRoads().routes.get("old-entry");
            if(replacement!=old){
                h.assertTrue(replacement.entrance.equals(end)&&replacement.id.equals(old.id),"Replacement lost entrance or identity");
                h.assertTrue(l.getBlockState(old.entrance).is(Blocks.DIRT_PATH),"Old road removed before replacement connected");
                replacement.suppressed=true;replacement.reviewAfter=0;
                h.assertTrue(!AdaptiveRoadService.review(l,s),"Explicitly cancelled route restarted");s.deactivate();h.succeed();
            }
        });
    }
    @GameTest(templateNamespace="exiledsettlement_roadcontinuity",template="empty",timeoutTicks=100)
    public static void newBuildingGetsTurnBeforeOldRoadReview(GameTestHelper h){
        var f=scene(h);var s=f.s();var l=h.getLevel();
        var a=s.addBuilding(BuildingType.WAGON,f.at().offset(-12,0,0));a.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of(a.position()));
        var b=s.addBuilding(BuildingType.WAGON,f.at().offset(12,0,0));b.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of(b.position()));
        var end=AutoRoadService.entrances(l,s,a).getFirst();var old=new AutoRoadState.Route(a.id(),end,List.of(end));old.width=3;old.adaptiveVersion=AdaptiveRoadService.VERSION;
        s.autoRoads().routes.put("old",old);s.autoRoads().enabled=true;AutoRoadService.tick(l,s);
        h.assertTrue(AutoRoadPlanner.saved(s.autoRoads()).getUUID("Building").equals(b.id()),"Old optimization starved new connection");
        AutoRoadPlanner.invalidate(s);s.deactivate();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_roadcontinuity",template="empty",timeoutTicks=100)
    public static void independentRepairKeepsBudgetCancellationAndPlayerProtection(GameTestHelper h){
        var f=scene(h);var s=f.s();var l=h.getLevel();var r=damaged(h,f);var at=r.entrance;
        r.completed.clear(0);r.repairs.set(0);r.advance();s.terrain().enabled=true;s.autoRoads().enabled=true;s.autoRoads().workReady=true;survey(f,100,100);
        // The admitted cap may finish even at capacity; an extra support may not.
        l.setBlock(at.below(),Blocks.AIR.defaultBlockState(),2);
        h.assertTrue(!RoadMaintenanceService.publishColumn(l,s,r,at,OptionalWorkBudget.snapshot(s,1)),"Repair exceeded quota for extra support");
        l.setBlock(at.below(),Blocks.DIRT.defaultBlockState(),2);
        r.suppressed=true;h.assertTrue(!RoadMaintenanceService.publishColumn(l,s,r,at,OptionalWorkBudget.snapshot(s)),"Cancelled route repaired");r.suppressed=false;
        l.setBlock(at,Blocks.CHEST.defaultBlockState(),2);
        h.assertTrue(!RoadMaintenanceService.publishColumn(l,s,r,at,OptionalWorkBudget.snapshot(s)),"Repair replaced player container");l.setBlock(at,Blocks.AIR.defaultBlockState(),2);
        h.assertTrue(RoadMaintenanceService.publishColumn(l,s,r,at,OptionalWorkBudget.snapshot(s)),"Known damage blocked by global survey");
        h.assertTrue(TerrainService.pendingAt(s,at)&&l.isEmptyBlock(at),"Repair did not publish real order");
        int count=s.terrain().projects.size();RoadMaintenanceService.publishColumn(l,s,r,at,OptionalWorkBudget.snapshot(s));
        h.assertTrue(s.terrain().projects.size()==count,"Repair duplicated order");
        var saved=s.autoRoads().save();var restored=new AutoRoadState();restored.load(saved);
        h.assertTrue(restored.routes.get("orphan").repairs.get(0),"Damage lost across save");s.deactivate();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_roadcontinuity",template="empty",batch="road_continuity_entity",timeoutTicks=4000)
    public static void workerRepairsOrphanRoadDuringLargeSurvey(GameTestHelper h){
        var f=scene(h);var s=f.s();var l=h.getLevel();var r=damaged(h,f);var hole=r.entrance;
        survey(f,160,160);s.terrain().enabled=true;s.autoRoads().enabled=true;s.autoRoads().workReady=true;
        var c=s.citizens().iterator().next();c.setManualAssignment(CitizenProfession.WORKER,null);c.assignProfession(CitizenProfession.WORKER,null);
        c.insertIntoInventory(new ItemStack(Items.DIRT,17));c.insertIntoInventory(new ItemStack(Items.BREAD,16));
        for(var tool:List.of(Items.IRON_SHOVEL,Items.IRON_PICKAXE,Items.IRON_AXE))c.receiveEquipment(new ItemStack(tool));
        var actor=ModEntities.SETTLEMENT_CITIZEN.get().create(l);actor.moveTo(f.at().getX()-6.5,f.at().getY(),f.at().getZ()+.5,0,0);
        actor.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());actor.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
        l.addFreshEntity(actor);c.updateProjection(actor.getUUID(),actor.blockPosition());var start=actor.position();long begin=l.getGameTime();
        boolean[] publishedDuringSurvey={false};int[] edits={0};
        h.onEachTick(()->{
            if(s.terrain().publicGround.phase==4){var p=f.at().offset(14,0,14);l.setBlock(p,(edits[0]++%2==0?Blocks.DIRT:Blocks.AIR).defaultBlockState(),2);PlayerRoadEdits.changed(l,p);}
            if(TerrainService.pendingAt(s,hole) && PublicGroundService.planning(s))publishedDuringSurvey[0]=true;
            if(l.getGameTime()%200==0)com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("ROAD_CONTINUITY_WORK state={} failure={} pos={} phase={} cursor={} repairs={} block={}",c.workState(),c.workFailure(),actor.position(),s.terrain().publicGround.phase,s.terrain().publicGround.cursor,r.repairs,l.getBlockState(hole));
            if(l.getBlockState(hole).is(Blocks.DIRT_PATH)){
                h.assertTrue(publishedDuringSurvey[0],"Repair waited for the full survey");
                h.assertTrue(actor.position().distanceTo(start)>3,"Worker never walked to repair");
                int dirt=c.inventory().stream().filter(i->i.is(Items.DIRT)).mapToInt(ItemStack::getCount).sum();
                h.assertTrue(dirt==16,"Repair did not consume one real dirt while preserving escape reserve: "+dirt);
                var out=new com.google.gson.JsonObject();out.addProperty("passed",true);out.addProperty("publishedDuringSurvey",true);out.addProperty("dirtConsumed",1);out.addProperty("moved",actor.position().distanceTo(start));out.addProperty("ticks",l.getGameTime()-begin);out.addProperty("tickRate",l.getServer().tickRateManager().tickrate());out.addProperty("playerSavesAccessed",false);
                try{java.nio.file.Files.createDirectories(com.hkin.exiledsettlement.debug.SaveAcceptanceService.directory());java.nio.file.Files.writeString(com.hkin.exiledsettlement.debug.SaveAcceptanceService.file("roadcontinuity-result",".json"),out.toString());}catch(java.io.IOException e){throw new RuntimeException(e);}
                com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("ROAD_CONTINUITY_COMPLETE {}",out);actor.discard();s.deactivate();h.succeed();
            }
        });
    }
}
