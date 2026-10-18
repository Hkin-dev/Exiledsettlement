package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import java.util.*;

/** Fresh isolated fixtures; only resident actions after publication count as construction. */
@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_stairrevision")
@PrefixGameTestTemplate(false)
public final class PublicStairRevisionGameTests {
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if("timed_construction".equals(System.getProperty("exiledsettlement.testSuite")))event.register(PublicStairRevisionGameTests.class);
    }
    private static int serial;
    private record Fixture(SettlementRecord s,BlockPos at) {}
    private static Fixture fixture(GameTestHelper h) {
        var l=h.getLevel();var at=h.absolutePos(new BlockPos(240000+serial++*512,4,0));
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)l.setChunkForced((at.getX()>>4)+dx,(at.getZ()>>4)+dz,true);
        for(var p:BlockPos.betweenClosed(at.offset(-7,-1,-5),at.offset(5,5,5))){l.getChunkAt(p);l.setBlock(p,p.getY()<at.getY()?Blocks.STONE.defaultBlockState():p.getY()==at.getY()?Blocks.DIRT.defaultBlockState():Blocks.AIR.defaultBlockState(),2);}
        var s=SettlementSavedData.get(l).createOrRecover(at.west(5).above(),UUID.randomUUID(),"Public stair revision regression").value();
        s.terrain().enabled=true;s.autoRoads().enabled=false;s.decorations().enabled=false;
        for(var c:s.citizens())c.setManualAssignment(CitizenProfession.HERDSMAN,null);
        var w=s.terrain().publicGround;w.phase=5;w.roadsIntegrated=true;w.nextReview=Long.MAX_VALUE;
        w.goals.put(at.atY(0).asLong(),at.getY());l.setDayTime(6000);
        return new Fixture(s,at);
    }
    private static TerrainProject oldStair(Fixture f,BlockState state,boolean paid) {
        var p=new TerrainProject(UUID.randomUUID(),"Recorded former public stair",f.at(),List.of(new TerrainProject.Step(f.at(),state,Blocks.AIR.defaultBlockState())));
        p.publicGround=true;p.explicit=false;p.layoutVersion=TerrainService.LAYOUT_VERSION;p.groundSurfaces.add(f.at());
        if(paid){p.mark(0);p.completed=true;}
        f.s().terrain().index(p);return p;
    }
    private static BlueprintRecord site(Fixture f,TerrainProject p) {
        return f.s().addBlueprint(BuildingType.HOUSE,f.at(),f.at().above(96),Direction.NORTH,f.s().ownerId(),false).withTerrainProject(p);
    }
    @GameTest(templateNamespace="exiledsettlement_stairrevision",template="empty")
    public static void staleStairFillStopsBeforeReplacementPublication(GameTestHelper h) {
        var f=fixture(h);var stair=Blocks.COBBLESTONE_STAIRS.defaultBlockState();var p=oldStair(f,stair,false);var site=site(f,p);
        h.getLevel().setBlock(f.at(),Blocks.AIR.defaultBlockState(),2);
        h.assertTrue(TerrainService.guard(h.getLevel(),f.s(),site,p.steps.getFirst()).equals("CONSTRUCTION_TARGET_CHANGED"),"Obsolete same-height stair fill still executable");
        h.assertTrue(!p.cancelled && p.finished.isEmpty(),"Rejecting stale action discarded unfinished work");
        f.s().deactivate();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_stairrevision",template="empty")
    public static void changedFacingStopsButCommittedRunRemainsExecutable(GameTestHelper h) {
        var f=fixture(h);var stair=Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.NORTH);
        var p=oldStair(f,stair,false);var site=site(f,p);h.getLevel().setBlock(f.at(),Blocks.AIR.defaultBlockState(),2);
        var w=f.s().terrain().publicGround;w.stairs.put(f.at().atY(0).asLong(),Direction.SOUTH);
        h.assertTrue(TerrainService.guard(h.getLevel(),f.s(),site,p.steps.getFirst()).equals("CONSTRUCTION_TARGET_CHANGED"),"Obsolete stair orientation still executable");
        w.stairs.put(f.at().atY(0).asLong(),Direction.NORTH);
        h.assertTrue(TerrainService.guard(h.getLevel(),f.s(),site,p.steps.getFirst()).isEmpty(),"Current stair corridor blocked");
        w.phase=10;w.stairs.clear();
        h.assertTrue(TerrainService.guard(h.getLevel(),f.s(),site,p.steps.getFirst()).isEmpty(),"Unfinished mathematical revision invalidates frozen work");
        f.s().deactivate();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_stairrevision",template="empty",timeoutTicks=300)
    public static void paidSameHeightStairPublishesAndResidentRecoversIt(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();var s=f.s();var at=f.at();var stair=Blocks.COBBLESTONE_STAIRS.defaultBlockState();
        l.setBlock(at,stair,2);var old=oldStair(f,stair,true);
        // Reload payment and planning evidence before any revision is published.
        s.terrain().load(s.terrain().save());
        var c=s.citizens().iterator().next();c.setManualAssignment(CitizenProfession.WORKER,null);c.assignProfession(CitizenProfession.WORKER,null);
        c.insertIntoInventory(new ItemStack(Items.DIRT,17));c.insertIntoInventory(new ItemStack(Items.BREAD,16));c.receiveEquipment(new ItemStack(Items.IRON_PICKAXE));c.receiveEquipment(new ItemStack(Items.IRON_SHOVEL));
        var actor=ModEntities.SETTLEMENT_CITIZEN.get().create(l);actor.setNoAi(true);actor.moveTo(at.getX()-6.5,at.getY()+1,at.getZ()+.5,0,0);
        actor.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());actor.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
        l.addFreshEntity(actor);c.updateProjection(actor.getUUID(),actor.blockPosition());var start=actor.position();boolean[] published={false};
        h.onEachTick(()->{
            if(!published[0]){
                PublicGroundService.tick(l,s);
                var pending=s.blueprints().stream().filter(b->b.terrainProject()!=null && b.terrainProject().remaining()>0).findFirst().orElse(null);
                if(pending==null)return;
                h.assertTrue(l.getBlockState(at).equals(stair),"Planner removed stair without work");
                h.assertTrue(s.terrain().supersededPaid(at,stair),"Paid stair evidence lost at transfer");
                published[0]=true;actor.setNoAi(false);c.startTask(CitizenWorkState.TO_BLUEPRINT,at,null,pending.id());
            }
            if(l.getGameTime()%10==0){
                actor.navigateTo(at.west().above(),.65);
                if(actor.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(at))<=9)
                    CitizenWorkService.advanceSiteConstruction(l,SettlementSavedData.get(l),s,c,actor);
            }
        });
        h.startSequence().thenWaitUntil(()->h.assertTrue(published[0],"Same-height paid stair never gets replacement work"))
                .thenWaitUntil(()->h.assertTrue(l.getBlockState(at).is(Blocks.DIRT),"Resident did not recover obsolete stair: "+c.workState()+"/"+c.workFailure()))
                .thenExecute(()->{
                    h.assertTrue(actor.position().distanceTo(start)>.5,"No actual approach to old stair");
                    h.assertTrue(c.inventory().stream().filter(i->i.is(Items.DIRT)).mapToInt(ItemStack::getCount).sum()==16,"Replacement did not consume exactly one dirt");
                    h.assertTrue(c.inventory().stream().filter(i->i.is(Items.COBBLESTONE_STAIRS)).mapToInt(ItemStack::getCount).sum()==1,"Stair drop lost or duplicated");
                    h.assertTrue(s.terrain().projects.values().stream().allMatch(p->p.remaining()==0),"Unfinished work remains after physical replacement");
                    var restored=new TerrainState();restored.load(s.terrain().save());
                    h.assertTrue(restored.projects.values().stream().allMatch(p->p.remaining()==0),"Save lost completed replacement");
                    com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("STAIR_REVISION recovered=1 dirtConsumed=1 stairDrop=1 moved={}",actor.position().distanceTo(start));
                    actor.discard();s.deactivate();
                }).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_stairrevision",template="empty",timeoutTicks=60)
    public static void unrecordedAndPlayerChangedStairsStayProtected(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();var old=Blocks.COBBLESTONE_STAIRS.defaultBlockState();oldStair(f,old,true);
        var changed=old.setValue(StairBlock.FACING,Direction.SOUTH);l.setBlock(f.at(),changed,2);
        var unknown=f.at().south(2);l.setBlock(unknown,old,2);f.s().terrain().publicGround.goals.put(unknown.atY(0).asLong(),unknown.getY());
        h.onEachTick(()->PublicGroundService.tick(l,f.s()));
        h.startSequence().thenIdle(20).thenExecute(()->{
            h.assertTrue(f.s().blueprints().isEmpty(),"Unknown or player-modified stair acquired a demolition order");
            h.assertTrue(l.getBlockState(f.at()).equals(changed) && l.getBlockState(unknown).equals(old),"Protected stair changed");
            f.s().deactivate();
        }).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_stairrevision",template="empty")
    public static void slopesKeepOneAlignedRunAndTerracesHaveNoScatteredStairs(GameTestHelper h) {
        var s=new SettlementRecord(UUID.randomUUID(),"Mathematical slope fixture",BlockPos.ZERO,UUID.randomUUID());
        for(var down:Direction.Plane.HORIZONTAL)for(var heights:List.of(new int[]{10,10,10,10},new int[]{10,9,9,8},new int[]{10,9,10,9},new int[]{10,9,8,7})){
            var w=new PublicGroundService.State();var side=down.getClockWise();
            for(int i=0;i<heights.length;i++)for(int lane=-6;lane<=6;lane++)w.goals.put(BlockPos.ZERO.relative(down,i).relative(side,lane).asLong(),heights[i]);
            w.phase=6;while(w.phase==6)PublicGroundService.seams(w);while(w.phase==7)PublicGroundService.passages(s,w);
            if(heights[3]==7){
                h.assertTrue(w.stairs.size()==6 && w.stairs.values().stream().allMatch(d->d==down.getOpposite()),"Broad continuous slope got scattered or misoriented stairs");
            }else h.assertTrue(w.stairs.isEmpty(),"Flat, terraced or reversing slope got stairs: "+Arrays.toString(heights));
            var restored=new PublicGroundService.State();restored.load(w.save());h.assertTrue(restored.stairs.equals(w.stairs),"Stair run changed after save");
        }
        h.succeed();
    }
}
