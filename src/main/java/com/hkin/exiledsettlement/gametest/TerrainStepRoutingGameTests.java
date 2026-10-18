package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import java.util.*;

@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_worksteps")
@PrefixGameTestTemplate(false)
public final class TerrainStepRoutingGameTests {
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if("timed_construction".equals(System.getProperty("exiledsettlement.testSuite")))event.register(TerrainStepRoutingGameTests.class);
    }
    private static int serial;
    private record Fixture(GameTestHelper h,SettlementSavedData data,SettlementRecord s,CitizenRecord worker,
            SettlementCitizenEntity actor,BlockPos at,BlueprintRecord site,TerrainProject project) {
        void clean(){s.deactivate();actor.discard();for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)h.getLevel().setChunkForced((at.getX()>>4)+dx,(at.getZ()>>4)+dz,false);}
    }
    private static Fixture fixture(GameTestHelper h,BlockState target) {
        var l=h.getLevel();var at=h.absolutePos(new BlockPos(180000+serial++*512,4,0));
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)l.setChunkForced((at.getX()>>4)+dx,(at.getZ()>>4)+dz,true);
        for(var p:BlockPos.betweenClosed(at.offset(-5,-1,-5),at.offset(5,4,5))){l.getChunkAt(p);l.setBlock(p,p.getY()==at.getY()-1?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),2);}
        var data=SettlementSavedData.get(l);var s=data.createOrRecover(at.west(4),UUID.randomUUID(),"Current terrain step regression").value();
        s.terrain().enabled=false;s.autoRoads().enabled=false;s.decorations().enabled=false;
        var c=s.citizens().iterator().next();c.assignProfession(CitizenProfession.WORKER,null);c.insertIntoInventory(new ItemStack(Items.BREAD,16));
        c.receiveEquipment(new ItemStack(Items.IRON_PICKAXE));c.receiveEquipment(new ItemStack(Items.IRON_SHOVEL));
        c.insertIntoInventory(new ItemStack(target.getBlock().asItem(),1));
        var p=new TerrainProject(UUID.randomUUID(),"Dig then pave one real cell",at,List.of(
                new TerrainProject.Step(at,Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState()),
                new TerrainProject.Step(at,target,Blocks.AIR.defaultBlockState())));
        p.explicit=true;p.road=true;s.terrain().index(p);
        var site=s.addBlueprint(BuildingType.HOUSE,at,at.north(3),Direction.NORTH,s.ownerId(),false).withTerrainProject(p);
        l.setBlock(at,Blocks.STONE.defaultBlockState(),2);
        var actor=ModEntities.SETTLEMENT_CITIZEN.get().create(l);actor.setNoAi(true);actor.moveTo(at.getX()-1.5,at.getY(),at.getZ()+.5,0,0);actor.setOnGround(true);
        actor.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());
        actor.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
        l.addFreshEntity(actor);c.updateProjection(actor.getUUID(),actor.blockPosition());
        c.startTask(CitizenWorkState.TO_BLUEPRINT,at,null,site.id());l.setDayTime(6000);
        return new Fixture(h,data,s,c,actor,at,site,p);
    }
    private static void digThenPave(GameTestHelper h,BlockState target) {
        var f=fixture(h,target);var l=h.getLevel();long[] digAt={-1};long[] fillAt={-1};
        h.assertTrue(!TimedConstructionService.fill(l,f.site(),f.at()),"Future fill incorrectly routes the current excavation into timed placement");
        h.onEachTick(()->{
            CitizenWorkService.advanceSiteConstruction(l,f.data(),f.s(),f.worker(),f.actor());
            if(f.project().finished.get(0) && digAt[0]<0) {
                digAt[0]=l.getGameTime();
                h.assertTrue(TimedConstructionService.fill(l,f.site(),f.at()),"Completed excavation did not switch to the real paving step");
            }
            if(f.project().finished.get(1) && fillAt[0]<0)fillAt[0]=l.getGameTime();
        });
        h.startSequence().thenWaitUntil(()->h.assertTrue(f.project().finished.get(1),"Arrived worker still did not dig and pave: "+f.worker().workState()+"/"+f.worker().workFailure()))
                .thenExecute(()->{
                    h.assertTrue(digAt[0]>=0 && fillAt[0]-digAt[0]>=10,"Dig and fill bypassed separate work cadence");
                    h.assertTrue(l.getBlockState(f.at()).equals(target),"Final physical surface does not match");
                    h.assertTrue(!f.worker().hasInventoryItem(s->s.is(target.getBlock().asItem())),"Paving did not consume material");
                    h.assertTrue(f.worker().inventory().stream().filter(s->s.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum()==1,"Real excavation drop lost or duplicated");
                    h.assertTrue(f.worker().inventory().stream().filter(s->s.is(Items.IRON_PICKAXE)).anyMatch(s->s.getDamageValue()==1),"Excavation tool not charged");
                    h.assertTrue(f.worker().inventory().stream().filter(s->s.is(Items.IRON_SHOVEL)).anyMatch(s->s.getDamageValue()==1),"Paving tool not charged");
                    var loaded=TerrainProject.load(f.project().save());h.assertTrue(loaded.finished.get(0)&&loaded.finished.get(1),"Actual step progress lost on reload");
                    com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("WORK_STEPS verified target={} digTick={} fillTick={} material=1 drop=1",target,digAt[0],fillAt[0]);
                    f.clean();
                }).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_worksteps",template="empty",timeoutTicks=180)
    public static void excavationBeforeStairsAdvances(GameTestHelper h){digThenPave(h,Blocks.COBBLESTONE_STAIRS.defaultBlockState());}
    @GameTest(templateNamespace="exiledsettlement_worksteps",template="empty",timeoutTicks=180)
    public static void excavationBeforeSlabAdvances(GameTestHelper h){digThenPave(h,Blocks.OAK_SLAB.defaultBlockState());}
    @GameTest(templateNamespace="exiledsettlement_worksteps",template="empty")
    public static void directSoilReplacementStillUsesTimedFill(GameTestHelper h) {
        var f=fixture(h,Blocks.DIRT.defaultBlockState());var l=h.getLevel();
        h.assertTrue(TimedConstructionService.fill(l,f.site(),f.at()),"Allowed direct soil replacement was incorrectly routed through excavation");
        CitizenWorkService.advanceSiteConstruction(l,f.data(),f.s(),f.worker(),f.actor());
        h.assertTrue(l.getBlockState(f.at()).is(Blocks.DIRT) && f.project().finished.get(0) && f.project().finished.get(1),"Direct replacement no longer completes both real steps");
        h.assertTrue(!f.worker().hasInventoryItem(s->s.is(Items.DIRT)),"Direct fill did not charge material");
        h.assertTrue(f.worker().inventory().stream().filter(s->s.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum()==1,"Direct replacement drop changed");
        f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_worksteps",template="empty")
    public static void protectedStepReportsBlockerWithoutWorkOrMaterialLoss(GameTestHelper h) {
        var f=fixture(h,Blocks.COBBLESTONE_STAIRS.defaultBlockState());var l=h.getLevel();
        f.s().terrain().suppressed.add(f.at().asLong());
        CitizenWorkService.advanceSiteConstruction(l,f.data(),f.s(),f.worker(),f.actor());
        h.assertTrue(f.worker().activity()==CitizenActivity.BLOCKED && f.worker().workFailure().equals("PROTECTED_AREA"),"Protected worker still displays construction: "+f.worker().activity()+"/"+f.worker().workFailure());
        h.assertTrue(l.getBlockState(f.at()).is(Blocks.STONE) && f.project().finished.isEmpty()
                && f.s().blueprint(f.site().id())!=null && f.worker().hasInventoryItem(s->s.is(Items.COBBLESTONE_STAIRS)),"Protection changed world, order or material");
        f.clean();h.succeed();
    }
}
