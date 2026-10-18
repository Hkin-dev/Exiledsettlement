package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import java.util.*;

/** All terrain edits before repair publication are isolated test setup. */
@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_groundrepair")
@PrefixGameTestTemplate(false)
public final class PublicGroundRepairGameTests {
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if("timed_construction".equals(System.getProperty("exiledsettlement.testSuite")))event.register(PublicGroundRepairGameTests.class);
    }
    @GameTest(templateNamespace="exiledsettlement_groundrepair",template="empty",batch="public_repair",timeoutTicks=3600)
    public static void unchangedLayoutRediscoversHolesAfterSaveAndResidentFillsThem(GameTestHelper h) {
        var l=h.getLevel();var at=h.absolutePos(new BlockPos(220000,260,0));
        for(var p:BlockPos.betweenClosed(at.offset(-8,-2,-8),at.offset(8,-2,8))){
            var chunk=new ChunkPos(p);l.setChunkForced(chunk.x,chunk.z,true);l.getChunkAt(p);
            l.setBlock(p,Blocks.STONE.defaultBlockState(),2);l.setBlock(p.above(),Blocks.GRASS_BLOCK.defaultBlockState(),2);
            for(int y=2;y<=5;y++)l.setBlock(p.above(y),Blocks.AIR.defaultBlockState(),2);
        }
        var data=SettlementSavedData.get(l);var s=data.createOrRecover(at,UUID.randomUUID(),"Pothole repair isolated fixture").value();
        s.terrain().enabled=true;s.autoRoads().enabled=false;s.decorations().enabled=false;
        var w=s.terrain().publicGround;var holes=List.of(at.offset(3,-1,0),at.offset(4,-1,1));
        for(var resident:s.citizens())resident.setManualAssignment(CitizenProfession.HERDSMAN,null);
        var c=s.citizens().iterator().next();c.setManualAssignment(CitizenProfession.WORKER,null);c.assignProfession(CitizenProfession.WORKER,null);
        c.insertIntoInventory(new ItemStack(Items.DIRT,18));c.insertIntoInventory(new ItemStack(Items.BREAD,16));c.receiveEquipment(new ItemStack(Items.IRON_SHOVEL));c.receiveEquipment(new ItemStack(Items.IRON_PICKAXE));c.receiveEquipment(new ItemStack(Items.IRON_AXE));
        var actor=ModEntities.SETTLEMENT_CITIZEN.get().create(l);h.assertTrue(actor!=null,"No worker");
        actor.setNoAi(true);actor.moveTo(at.getX()-5.5,at.getY(),at.getZ()+.5,0,0);actor.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());
        actor.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
        c.updateProjection(actor.getUUID(),actor.blockPosition());l.addFreshEntity(actor);var start=actor.position();
        // Sixteen ordinary blocks remain reserved for personal pit escape; two are repair stock.
        l.setDayTime(6000);long[] damaged={-1};long[] signature={0};boolean[] published={false};int[] projects={0};
        h.onEachTick(()->{
            PublicGroundService.tick(l,s);
            if(damaged[0]<0 && w.phase==0 && w.roadsIntegrated && !w.goals.isEmpty()){
                h.assertTrue(s.terrain().projects.isEmpty(),"Flat fixture unexpectedly needed earthwork");
                signature[0]=w.signature;
                var saved=w.save();var restored=new PublicGroundService.State();restored.load(saved);
                h.assertTrue(restored.goals.equals(w.goals) && restored.nextReview==w.nextReview,"Review plane/timer did not survive save");
                w.load(saved);
                for(var hole:holes){h.assertTrue(w.goals.get(hole.atY(0).asLong())==hole.getY(),"Hole not in the established public plane");l.setBlock(hole,Blocks.AIR.defaultBlockState(),2);}
                damaged[0]=l.getGameTime();return;
            }
            if(damaged[0]<0)return;
            h.assertTrue(w.signature==signature[0],"Layout changed: this would hide the original bug");
            if(!published[0] && holes.stream().allMatch(p->TerrainService.pendingAt(s,p))){
                published[0]=true;actor.setNoAi(false);projects[0]=s.terrain().projects.size();
                h.assertTrue(holes.stream().allMatch(l::isEmptyBlock),"Planner filled holes without resident work");
                var markers=s.blueprints().stream().flatMap(b->com.hkin.exiledsettlement.blueprint.BlueprintService.constructionMarkers(l,b).stream()).toList();
                h.assertTrue(markers.containsAll(holes),"Published holes have no construction glow markers");
                h.assertTrue(l.getGameTime()-damaged[0]>=1200,"Review ignored its normal interval");
                com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("PUBLIC_REPAIR published={} delay={} signatureUnchanged=true",projects[0],l.getGameTime()-damaged[0]);
            }
            if(!published[0])return;
            if(l.getGameTime()%10==0){
                var site=s.blueprints().stream().filter(b->b.terrainProject()!=null && b.terrainProject().remaining()>0).findFirst().orElse(null);
                if(site!=null){
                    var target=site.terrainProject().steps.getFirst().position();
                    if(!site.id().equals(c.targetBlueprintId()))c.startTask(CitizenWorkState.TO_BLUEPRINT,target,null,site.id());
                    if(actor.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(target))>9)actor.navigateTo(target.above().west(),.65);
                    CitizenWorkService.advanceSiteConstruction(l,data,s,c,actor);
                }
            }
            if(holes.stream().allMatch(p->l.getBlockState(p).is(Blocks.DIRT))){
                h.assertTrue(actor.position().distanceTo(start)>2,"Resident did not physically approach");
                h.assertTrue(c.inventory().stream().filter(i->i.is(Items.DIRT)).mapToInt(ItemStack::getCount).sum()==16,"Repair did not consume exactly two dirt while preserving the escape reserve");
                h.assertTrue(s.terrain().projects.size()==projects[0],"Review duplicated pending repair orders");
                h.assertTrue(s.terrain().projects.values().stream().allMatch(p->p.finished.cardinality()==p.steps.size()),"Real repair progress not recorded");
                com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("PUBLIC_REPAIR complete holes=2 dirtConsumed=2 moved={} elapsed={}",actor.position().distanceTo(start),l.getGameTime()-damaged[0]);
                actor.discard();s.deactivate();h.succeed();
            }
        });
    }
    private PublicGroundRepairGameTests(){}
}
