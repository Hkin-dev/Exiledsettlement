package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import java.util.*;
import java.util.function.Consumer;
import static com.hkin.exiledsettlement.building.MobRecoveryState.Status.*;

@GameTestHolder("exiledsettlement_recovery")
@PrefixGameTestTemplate(false)
public final class MobRecoveryGameTests {
    private static int serial;
    private record Fixture(GameTestHelper h,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity actor,BlockPos at,Creeper mob){
        void clean(){s.deactivate();actor.discard();mob.discard();}
    }
    private static Fixture fixture(GameTestHelper h){
        var l=h.getLevel();var at=h.absolutePos(new BlockPos(390000+(serial++)*160,40,0));
        var s=SettlementSavedData.get(l).createOrRecover(at,UUID.randomUUID(),"Recovery acceptance").value();
        s.terrain().enabled=false;s.autoRoads().enabled=false;s.decorations().enabled=false;
        for(var p:BlockPos.betweenClosed(at.offset(-10,-4,-10),at.offset(24,-1,10))){
            var ch=new ChunkPos(p);l.setChunkForced(ch.x,ch.z,true);l.setBlock(p,Blocks.STONE.defaultBlockState(),2);
        }
        var c=s.citizens().iterator().next();for(var other:s.citizens())if(other!=c)other.markDead(at);
        c.setManualAssignment(CitizenProfession.WORKER,null);c.receiveEquipment(new ItemStack(Items.IRON_PICKAXE));
        c.receiveEquipment(new ItemStack(Items.IRON_SHOVEL));c.receiveEquipment(new ItemStack(Items.IRON_AXE));
        c.insertIntoInventory(new ItemStack(Items.COBBLESTONE,16));c.reservePitBlocks(PitEscapeService::ordinary,16);c.insertIntoInventory(new ItemStack(Items.BREAD,16));
        var actor=ModEntities.SETTLEMENT_CITIZEN.get().create(l);actor.moveTo(at.getX()+.5,at.getY(),at.getZ()+.5,0,0);actor.setOnGround(true);
        actor.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());
        actor.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
        c.updateProjection(actor.getUUID(),actor.blockPosition());
        var mob=EntityType.CREEPER.create(l);mob.moveTo(at.getX()+10.5,at.getY(),at.getZ()+.5,0,0);mob.setNoAi(true);
        l.setDayTime(6000);return new Fixture(h,s,c,actor,at,mob);
    }
    private static void observe(Fixture f,BlockPos p){MobRecoveryService.capture(f.h.getLevel(),f.mob,List.of(p),"rule_fixture");}
    private static void step(Fixture f) {
        try {
            var m=CitizenWorkService.class.getDeclaredMethod("repair",net.minecraft.server.level.ServerLevel.class,SettlementSavedData.class,SettlementRecord.class,CitizenRecord.class,SettlementCitizenEntity.class);
            m.setAccessible(true);m.invoke(null,f.h.getLevel(),SettlementSavedData.get(f.h.getLevel()),f.s,f.c,f.actor);
        }catch(Exception ex){throw new RuntimeException(ex);}
    }
    @GameTest(templateNamespace="exiledsettlement_recovery",template="empty",timeoutTicks=100)
    public static void actualExplosionRecordsTerrainAndReloadsWithoutInventingOldLoss(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var p=f.at.east(10);l.setBlock(p,Blocks.OAK_PLANKS.defaultBlockState(),3);
        l.explode(f.mob,p.getX()+.5,p.getY()+.5,p.getZ()+.5,2,Level.ExplosionInteraction.MOB);
        h.runAfterDelay(8,()->{
            MobRecoveryService.tick(l);var e=f.s.mobRecovery().owner(p);
            h.assertTrue(e!=null && e.cells.get(p).original.is(Blocks.OAK_PLANKS) && e.cells.get(p).status==PENDING,"Terrain outside templates was not captured");
            var copy=SettlementRecord.load(f.s.save(l.registryAccess()),l.registryAccess());
            h.assertTrue(copy.mobRecovery().owner(p)!=null && copy.mobRecovery().owner(p).cells.get(p).original.equals(e.cells.get(p).original),"Evidence lost on save");
            var old=f.s.save(l.registryAccess());old.remove("MobRecovery");
            h.assertTrue(SettlementRecord.load(old,l.registryAccess()).mobRecovery().incidents.isEmpty(),"Old save invented evidence");
            h.assertTrue(l.getBlockState(p).isAir(),"Event handler rebuilt world for free");f.clean();h.succeed();
        });
    }
    @GameTest(templateNamespace="exiledsettlement_recovery",template="empty",timeoutTicks=100)
    public static void canceledAndUnchangedProbesProduceNoRepair(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var p=f.at.east(3);l.setBlock(p,Blocks.OAK_PLANKS.defaultBlockState(),3);
        Consumer<LivingDestroyBlockEvent> cancel=e->e.setCanceled(true);NeoForge.EVENT_BUS.addListener(cancel);
        try{NeoForge.EVENT_BUS.post(new LivingDestroyBlockEvent(f.mob,p,l.getBlockState(p)));}finally{NeoForge.EVENT_BUS.unregister(cancel);}
        observe(f,p);
        h.runAfterDelay(8,()->{MobRecoveryService.tick(l);h.assertTrue(f.s.mobRecovery().owner(p)==null && f.s.mobRecovery().incidents.isEmpty(),"Undamaged/canceled target became work");f.clean();h.succeed();});
    }
    @GameTest(templateNamespace="exiledsettlement_recovery",template="empty",timeoutTicks=100)
    public static void playerEditAndRepeatedDamagePreserveOriginalAndCargo(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var p=f.at.east(3);l.setBlock(p,Blocks.OAK_PLANKS.defaultBlockState(),3);observe(f,p);l.removeBlock(p,false);
        h.runAfterDelay(8,()->{
            MobRecoveryService.tick(l);var e=f.s.mobRecovery().owner(p);f.c.insertIntoInventory(new ItemStack(Items.OAK_PLANKS,2));
            f.c.clearWork();h.assertTrue(MobRecoveryService.assign(l,f.s,f.c,new HashSet<>()),"P1 did not assign");
            MobRecoveryService.capture(l,f.mob,List.of(p),"repeat");h.assertTrue(f.s.mobRecovery().owner(p)==e,"Repeated damage replaced original evidence");
            l.setBlock(p,Blocks.GOLD_BLOCK.defaultBlockState(),3);MobRecoveryService.playerEdit(l,p);step(f);
            h.assertTrue(l.getBlockState(p).is(Blocks.GOLD_BLOCK) && e.cells.get(p).status==CONFLICT,"Player block overwritten");
            h.assertTrue(f.c.inventory().stream().filter(s->s.is(Items.OAK_PLANKS)).mapToInt(ItemStack::getCount).sum()==2,"Conflict consumed cargo");f.clean();h.succeed();
        });
    }
    @GameTest(templateNamespace="exiledsettlement_recovery",template="empty",timeoutTicks=150)
    public static void pairedDoorConsumesOneItemAndDoubleSlabConsumesTwo(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var p=f.at.east(3);var door=Blocks.OAK_DOOR.defaultBlockState();
        l.setBlock(p,door,18);l.setBlock(p.above(),door.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER),18);
        observe(f,p);l.removeBlock(p,false);l.removeBlock(p.above(),false);f.c.insertIntoInventory(new ItemStack(Items.OAK_DOOR));
        h.runAfterDelay(8,()->{
            MobRecoveryService.tick(l);f.c.clearWork();h.assertTrue(MobRecoveryService.assign(l,f.s,f.c,new HashSet<>()),"Door assignment failed: "+f.s.mobRecovery().incidents.values().stream().flatMap(e->e.cells.values().stream()).filter(v->v.status!=UNCHANGED).map(v->v.pos+"="+v.status+":"+v.reason).toList());
            var task=WorkTaskService.task(f.s,f.c);h.assertTrue(task.priority()==1,"Recovery not P1");
            step(f);
        });
        h.runAfterDelay(30,()->{
            if(f.c.workState()!=CitizenWorkState.IDLE)step(f);
            h.assertTrue(l.getBlockState(p).is(Blocks.OAK_DOOR) && l.getBlockState(p.above()).is(Blocks.OAK_DOOR),"Door halves not restored");
            h.assertTrue(!f.c.hasInventoryItem(s->s.is(Items.OAK_DOOR)),"Door material not consumed");
            h.assertTrue(MobRecoveryService.cost(Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.DOUBLE))==2,"Double slab cost wrong");f.clean();h.succeed();
        });
    }
    @GameTest(templateNamespace="exiledsettlement_recovery",template="empty",timeoutTicks=100)
    public static void placementProtectionRestoresWorldAndRetainsMaterial(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var p=f.at.east(3);l.setBlock(p,Blocks.OAK_PLANKS.defaultBlockState(),3);observe(f,p);l.removeBlock(p,false);
        f.c.insertIntoInventory(new ItemStack(Items.OAK_PLANKS));
        h.runAfterDelay(8,()->{
            MobRecoveryService.tick(l);f.c.clearWork();MobRecoveryService.assign(l,f.s,f.c,new HashSet<>());
            Consumer<BlockEvent.EntityPlaceEvent> cancel=e->{if(e.getPos().equals(p))e.setCanceled(true);};NeoForge.EVENT_BUS.addListener(cancel);
            step(f);h.runAfterDelay(12,()->{try{step(f);}finally{NeoForge.EVENT_BUS.unregister(cancel);}
            h.assertTrue(l.getBlockState(p).isAir() && f.c.hasInventoryItem(s->s.is(Items.OAK_PLANKS)),"Protection did not roll back atomically");
            h.assertTrue(f.s.mobRecovery().owner(p)!=null,"Protection deleted pending repair");f.clean();h.succeed();});
        });
    }
    @GameTest(templateNamespace="exiledsettlement_recovery",template="empty",timeoutTicks=100)
    public static void containerContentsAreNotCopiedAndMissingMaterialDoesNotOccupyWorkers(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var p=f.at.east(3);l.setBlock(p,Blocks.BARREL.defaultBlockState(),3);
        ((Container)l.getBlockEntity(p)).setItem(0,new ItemStack(Items.DIAMOND,7));observe(f,p);l.removeBlock(p,false);
        h.runAfterDelay(8,()->{
            MobRecoveryService.tick(l);h.assertTrue(!MobRecoveryService.assign(l,f.s,f.c,new HashSet<>()),"Missing material occupied worker");
            f.c.insertIntoInventory(new ItemStack(Items.BARREL));f.c.clearWork();h.assertTrue(MobRecoveryService.assign(l,f.s,f.c,new HashSet<>()),"Funded barrel did not assign");step(f);h.runAfterDelay(12,()->{step(f);
            h.assertTrue(l.getBlockEntity(p) instanceof Container && ((Container)l.getBlockEntity(p)).isEmpty(),"Container inventory duplicated");
            var e=f.s.mobRecovery().incidents.values().stream().filter(v->v.cells.containsKey(p)).findFirst().orElseThrow();
            h.assertTrue(e.cells.get(p).reason.equals("RECOVERY_BLOCK_ENTITY_DATA"),"Missing metadata falsely counted complete");f.clean();h.succeed();});
        });
    }
    @GameTest(templateNamespace="exiledsettlement_recovery",template="empty",timeoutTicks=2400)
    public static void sharedTwoWorkerLimitPersistsDuringSupplyAndSave(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var cells=List.of(f.at.east(3),f.at.east(4),f.at.east(5));
        for(var p:cells)l.setBlock(p,Blocks.OAK_PLANKS.defaultBlockState(),3);
        MobRecoveryService.capture(l,f.mob,cells,"capacity_fixture");for(var p:cells)l.removeBlock(p,false);
        h.runAfterDelay(8,()->{
            MobRecoveryService.tick(l);var e=f.s.mobRecovery().owner(cells.getFirst());
            var second=f.s.addMigrant();var third=f.s.addMigrant();
            var workers=List.of(f.c,second,third);for(var c:workers){c.clearWork();c.setManualAssignment(CitizenProfession.WORKER,null);c.insertIntoInventory(new ItemStack(Items.OAK_PLANKS,8));}
            var claimed=new HashSet<BlockPos>();
            h.assertTrue(MobRecoveryService.assign(l,f.s,f.c,claimed) && MobRecoveryService.assign(l,f.s,second,claimed),"Two slots not available");
            f.c.fetchRepairMaterial(UUID.randomUUID());
            h.assertTrue(!MobRecoveryService.assign(l,f.s,third,claimed) && WorkTaskService.assigned(f.s,e.id)==2,"Supply lost mother quota");
            var loaded=SettlementRecord.load(f.s.save(l.registryAccess()),l.registryAccess());
            var task=WorkTaskService.task(loaded,loaded.citizen(f.c.id()));
            h.assertTrue(task.priority()==1 && task.mother().equals(e.id) && WorkTaskService.assigned(loaded,e.id)==2,"Saved P1/supply ownership changed");
            h.assertTrue(!com.hkin.exiledsettlement.road.AutoRoadService.safeCell(l,f.s,cells.getFirst()),"Road bypasses P1 ownership");
            f.clean();h.succeed();
        });
    }
    @GameTest(templateNamespace="exiledsettlement_recovery",template="empty",timeoutTicks=2400)
    public static void ignitedCreeperAndAutonomousWorkerRestorePaidTerrain(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var center=f.at.east(12);
        for(var p:BlockPos.betweenClosed(center.offset(-2,-3,-2),center.offset(2,-1,2)))l.setBlock(p,Blocks.DIRT.defaultBlockState(),3);
        var warehouse=f.s.addBuilding(BuildingType.WAGON,f.at.west(4));l.setBlock(warehouse.position(),Blocks.BARREL.defaultBlockState(),3);
        warehouse.updateFunction(true,BuildingStatus.OPERATIONAL,27,List.of(warehouse.position()));
        ((Container)l.getBlockEntity(warehouse.position())).setItem(0,new ItemStack(Items.DIRT,64));
        ((Container)l.getBlockEntity(warehouse.position())).setItem(1,new ItemStack(Items.STONE,64));
        f.mob.moveTo(center.getX()+.5,center.getY(),center.getZ()+.5,0,0);
        h.startSequence().thenWaitUntil(()->h.assertTrue(l.areEntitiesLoaded(new ChunkPos(center).toLong()),"Wait for sections"))
            .thenExecute(()->{h.assertTrue(l.addFreshEntity(f.mob),"Creeper failed to spawn");f.mob.ignite();})
            .thenWaitUntil(()->h.assertTrue(!f.mob.isAlive(),"Waiting for real creeper fuse"))
            .thenIdle(8).thenExecute(()->{
                MobRecoveryService.tick(l);h.assertTrue(f.s.mobRecovery().incidents.values().stream().anyMatch(e->e.cells.values().stream().anyMatch(c->c.status==PENDING)),"Real creeper produced no recovery targets");
                h.assertTrue(l.addFreshEntity(f.actor),"Worker spawn failed");
            }).thenWaitUntil(()->{
                l.setDayTime(6000);CitizenWorkService.tickSettlement(l,SettlementSavedData.get(l),f.s);
                h.assertTrue(f.s.mobRecovery().incidents.values().stream().noneMatch(MobRecoveryState.Incident::unresolved),"Recovery unfinished: "+f.c.workState()+" "+f.c.workFailure()+" "+f.c.lastPosition());
            }).thenExecute(()->{
                long done=f.s.mobRecovery().incidents.values().stream().flatMap(e->e.cells.values().stream()).filter(c->c.status==DONE).count();
                h.assertTrue(done>0,"No actual repairs");
                h.assertTrue(f.actor.position().distanceTo(f.at.getCenter())>3,"Worker never walked into recovery area");
                System.out.println("MOB_RECOVERY_NATIVE_DONE blocks="+done+" worker="+f.actor.position()+" tick="+l.getGameTime());f.clean();
            }).thenSucceed();
    }
}
