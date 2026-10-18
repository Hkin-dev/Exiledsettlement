package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("exiledsettlement_cleanup")
@PrefixGameTestTemplate(false)
public final class CleanupRouteGameTests {
    private static int serial;
    private static void check(GameTestHelper h, BuildingType type, boolean blocked) {
        var level=h.getLevel();var origin=new BlockPos(92000+serial++*512,100,92000);
        var chunk=new net.minecraft.world.level.ChunkPos(origin);
        for(int x=-1;x<=3;x++)for(int z=-1;z<=1;z++)level.setChunkForced(chunk.x+x,chunk.z+z,true);
        // A real three-block downhill route, with broad landings and no teleport during the test.
        for(int x=-6;x<=34;x++)for(int z=-7;z<=7;z++) {
            int floor=x<8?2:x<12?1:x<16?0:-1;
            for(int y=-3;y<=7;y++)level.setBlock(origin.offset(x,y,z),y<=floor?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),2);
        }
        var target=origin.east(28);
        level.setBlock(target,Blocks.COBBLESTONE.defaultBlockState(),2);
        if(blocked)for(var p:BlockPos.betweenClosed(target.offset(-6,0,-6),target.offset(6,6,6)))
            if(!p.equals(target))level.setBlock(p,Blocks.BEDROCK.defaultBlockState(),2);
        var data=SettlementSavedData.get(level);
        var settlement=data.createOrRecover(origin.east(12),UUID.randomUUID(),"Cleanup route regression").value();
        h.assertTrue(settlement!=null,"Fixture settlement failed");
        var citizen=settlement.citizens().iterator().next();citizen.assignProfession(CitizenProfession.WORKER,null);
        var site=settlement.addBlueprint(type,target,target.north(),Direction.NORTH,settlement.ownerId(),false);
        site.withCustomTemplate(CustomTemplateSnapshot.create("Cancelled low block",type,target,Direction.NORTH,
                List.of(new BlueprintPlacement(target,Blocks.COBBLESTONE.defaultBlockState(),Items.COBBLESTONE)),
                List.of(new BlueprintEntrance(target.north(),Direction.NORTH))));
        site.queueCancellation();
        var actor=ModEntities.SETTLEMENT_CITIZEN.get().create(level);
        actor.moveTo(origin.getX()+.5,origin.getY()+3,origin.getZ()+.5,0,0);actor.setOnGround(true);
        actor.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,settlement.id().toString());
        actor.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,citizen.id().toString());
        citizen.updateProjection(actor.getUUID(),actor.blockPosition());
        citizen.insertIntoInventory(new ItemStack(Items.IRON_PICKAXE));citizen.selectActiveSlot(0);
        citizen.insertIntoInventory(new ItemStack(Items.SCAFFOLDING,64));
        citizen.insertIntoInventory(new ItemStack(Items.DIRT,64));
        actor.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(Items.IRON_PICKAXE));
        citizen.startTask(CitizenWorkState.TO_DEMOLITION,target,null,site.id());
        h.assertTrue(level.addFreshEntity(actor),"Resident fixture failed");
        settlement.deactivate();level.setDayTime(6000);
        var start=actor.position();final long started=System.nanoTime();final int[] ticks={0};final double[] largestStep={0};final net.minecraft.world.phys.Vec3[] previous={start};
        h.onEachTick(()->{
            ticks[0]++;largestStep[0]=Math.max(largestStep[0],actor.position().distanceTo(previous[0]));previous[0]=actor.position();
            if(ticks[0]%5==0 && level.getBlockState(target).is(Blocks.COBBLESTONE)) {
                try(var budget=ConstructionPlanningBudget.begin(actor,site.id())) {
                    var method=CitizenWorkService.class.getDeclaredMethod("cleanupBlueprintSite",ServerLevel.class,
                            SettlementSavedData.class,SettlementRecord.class,CitizenRecord.class,SettlementCitizenEntity.class);
                    method.setAccessible(true);method.invoke(null,level,data,settlement,citizen,actor);
                } catch(java.lang.reflect.InvocationTargetException ex) {
                    if(!(ex.getCause() instanceof ConstructionPlanningBudget.Deferred))throw new RuntimeException(ex.getCause());
                } catch(ReflectiveOperationException ex) {throw new RuntimeException(ex);}
            }
        });
        h.startSequence().thenWaitUntil(()->{
            if(blocked)h.assertTrue(ticks[0]>=120,"Waiting for blocked-route observation");
            else h.assertTrue(level.getBlockState(target).isAir(),"Cleanup stalled: "+citizen.workFailure()+" at="+actor.blockPosition()+" trace="+actor.constructionDecisionTrace());
        }).thenExecute(()->{
            int loot=citizen.inventory().stream().filter(s->s.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum();
            h.assertTrue(site.temporaryFootings().isEmpty(),"Cleanup created an unnecessary access structure");
            h.assertTrue(citizen.inventory().stream().filter(s->s.is(Items.SCAFFOLDING)||s.is(Items.DIRT))
                    .mapToInt(ItemStack::getCount).sum()==128,"Walkable cleanup consumed access materials");
            h.assertTrue(largestStep[0]<1.5,"Resident teleported during cleanup");
            if(blocked) {
                h.assertTrue(level.getBlockState(target).is(Blocks.COBBLESTONE) && loot==0,"Unreachable target removed remotely");
                h.assertTrue(settlement.blueprint(site.id())!=null,"Blocked work order was discarded");
            } else {
                h.assertTrue(actor.position().distanceTo(start)>20 && loot==1,"Missing real travel or exact block drop");
                h.assertTrue(citizen.inventory().stream().anyMatch(s->s.is(Items.IRON_PICKAXE)&&s.getDamageValue()==1),"Cleanup tool durability changed incorrectly");
            }
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("CLEANUP_ROUTE type={} blocked={} ticks={} distance={} maxStep={} loot={} wallSeconds={}",type,blocked,ticks[0],actor.position().distanceTo(start),largestStep[0],loot,(System.nanoTime()-started)/1e9);
            actor.discard();settlement.deactivate();data.touch();
        }).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_cleanup",template="empty",timeoutTicks=1200)
    public static void cancelledFieldWalksDownhillBeforeRemovingBlock(GameTestHelper h){check(h,BuildingType.FARM_FIELD,false);}
    @GameTest(templateNamespace="exiledsettlement_cleanup",template="empty",timeoutTicks=1200)
    public static void cancelledBuildingWalksDownhillBeforeRemovingBlock(GameTestHelper h){check(h,BuildingType.HOUSE,false);}
    @GameTest(templateNamespace="exiledsettlement_cleanup",template="empty",timeoutTicks=1200)
    public static void cancelledTreeFarmWalksDownhillBeforeRemovingBlock(GameTestHelper h){check(h,BuildingType.TREE_FARM,false);}
    @GameTest(templateNamespace="exiledsettlement_cleanup",template="empty",timeoutTicks=300)
    public static void unreachableCancelledFieldKeepsBlockCargoAndOrder(GameTestHelper h){check(h,BuildingType.FARM_FIELD,true);}
}
