package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.region.ProductionRegionService;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import java.util.*;

@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_groundrepair")
@PrefixGameTestTemplate(false)
public final class PublicWorkAreasGameTests {
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(RegisterGameTestsEvent e){if("timed_construction".equals(System.getProperty("exiledsettlement.testSuite")))e.register(PublicWorkAreasGameTests.class);}
    @GameTest(templateNamespace="exiledsettlement_groundrepair",template="empty")
    public static void publicationInterleavesAreasAndResumesWithoutLostColumns(GameTestHelper h){
        var w=new PublicGroundService.State();
        for(int x=-32;x<32;x++)for(int z=-16;z<16;z++)w.goals.put(BlockPos.asLong(x,0,z),64);
        var original=w.keys();w.planCursor=7;w.phase=5;var ordered=w.publicationKeys();
        h.assertTrue(ordered.subList(0,7).equals(original.subList(0,7)),"Already visited prefix was changed");
        h.assertTrue(new HashSet<>(ordered).equals(w.goals.keySet()) && ordered.size()==w.goals.size(),"Columns lost or repeated");
        h.assertTrue(ordered.subList(7,15).stream().map(BlockPos::of).map(PublicWorkAreas::area).distinct().count()==8,"First round concentrated in one area");
        w.planCursor=19;var loaded=new PublicGroundService.State();loaded.load(w.save());
        h.assertTrue(loaded.planCursor==19 && loaded.publicationKeys().equals(ordered),"Publication order changed after save");h.succeed();
    }
    private static int serial;
    private record Fixture(GameTestHelper h,SettlementRecord s,BlockPos at){void clean(){s.deactivate();}}
    private static Fixture fixture(GameTestHelper h){
        var l=h.getLevel();var raw=h.absolutePos(new BlockPos(840000+serial++*256,5,0));
        var at=new BlockPos(Math.floorDiv(raw.getX(),16)*16+8,raw.getY(),Math.floorDiv(raw.getZ(),16)*16+8);
        for(var p:BlockPos.betweenClosed(at.offset(-27,-2,-8),at.offset(27,4,8))){l.getChunkAt(p);l.setChunkForced(p.getX()>>4,p.getZ()>>4,true);
            l.setBlock(p,p.getY()==at.getY()-2?Blocks.STONE.defaultBlockState():p.getY()==at.getY()-1?Blocks.GRASS_BLOCK.defaultBlockState():Blocks.AIR.defaultBlockState(),2);}
        var s=SettlementSavedData.get(l).createOrRecover(at,UUID.randomUUID(),"Distributed public work").value();s.terrain().enabled=true;s.autoRoads().enabled=false;s.decorations().enabled=false;
        for(var c:s.citizens())c.setManualAssignment(CitizenProfession.HERDSMAN,null);
        s.terrain().publicGround.phase=5;s.terrain().publicGround.roadsIntegrated=true;l.setDayTime(6000);return new Fixture(h,s,at);
    }
    private static TerrainProject job(Fixture f,BlockPos at,boolean fill){
        var l=f.h().getLevel();var target=fill?Blocks.DIRT.defaultBlockState():Blocks.AIR.defaultBlockState();
        l.setBlock(at,fill?Blocks.AIR.defaultBlockState():Blocks.STONE.defaultBlockState(),2);
        var p=ProductionRegionService.publish(l,f.s(),f.s().id(),"Public work fixture",at,List.of(new TerrainProject.Step(at,target,l.getBlockState(at))));
        p.publicGround=true;p.explicit=false;p.layoutVersion=TerrainService.LAYOUT_VERSION;
        var surface=fill?at:at.below(3);p.groundSurfaces.add(surface);f.s().terrain().publicGround.goals.put(surface.atY(0).asLong(),surface.getY());f.s().terrain().index(p);return p;
    }
    @GameTest(templateNamespace="exiledsettlement_groundrepair",template="empty")
    public static void distantPublicFillDoesNotWaitForAnotherAreasDig(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var cut=job(f,f.at().offset(0,2,3),false);
        var near=job(f,f.at().offset(2,-1,3),true);var far=job(f,f.at().offset(20,-1,3),true);
        TerrainLayerService.invalidate(f.s());
        h.assertTrue(!TerrainService.pending(l,f.s().blueprint(cut.id),Set.of()).isEmpty(),"Cut fixture blocked");
        h.assertTrue(TerrainService.pending(l,f.s().blueprint(near.id),Set.of()).isEmpty(),"Same-area fill bypassed digging");
        h.assertTrue(!TerrainService.pending(l,f.s().blueprint(far.id),Set.of()).isEmpty(),"Remote fill still blocked by another area");
        f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_groundrepair",template="empty",timeoutTicks=500)
    public static void automaticWorkersChooseDifferentAreasAndPhysicallyFillThem(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var s=f.s();var data=SettlementSavedData.get(l);
        var positions=List.of(f.at().offset(-20,-1,3),f.at().offset(1,-1,3),f.at().offset(20,-1,3));
        for(var p:positions){job(f,p,true);job(f,p.south(),true);}
        var citizens=s.citizens().stream().limit(3).toList();var actors=new ArrayList<SettlementCitizenEntity>();var starts=new ArrayList<net.minecraft.world.phys.Vec3>();var stock=new ArrayList<Integer>();var chosen=new HashSet<Long>();var claimed=new HashSet<BlockPos>();
        for(var c:citizens){c.setManualAssignment(CitizenProfession.WORKER,null);c.assignProfession(CitizenProfession.WORKER,null);c.insertIntoInventory(new ItemStack(Items.DIRT,20));c.insertIntoInventory(new ItemStack(Items.BREAD,16));c.receiveEquipment(new ItemStack(Items.IRON_SHOVEL));
            var actor=ModEntities.SETTLEMENT_CITIZEN.get().create(l);actor.moveTo(f.at().getX()-.5,f.at().getY(),f.at().getZ()-3.5,0,0);
            actor.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());actor.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());l.addFreshEntity(actor);c.updateProjection(actor.getUUID(),actor.blockPosition());actors.add(actor);starts.add(actor.position());stock.add(c.inventory().stream().filter(v->v.is(Items.DIRT)).mapToInt(ItemStack::getCount).sum());
            try{var method=CitizenWorkService.class.getDeclaredMethod("assignBlueprint",net.minecraft.server.level.ServerLevel.class,SettlementSavedData.class,SettlementRecord.class,CitizenRecord.class,Set.class);method.setAccessible(true);
                h.assertTrue((Boolean)method.invoke(null,l,data,s,c,claimed),"No automatic public assignment");}catch(ReflectiveOperationException ex){throw new RuntimeException(ex);}
            h.assertTrue(chosen.add(PublicWorkAreas.area(c.targetPosition())),"Workers concentrated in an occupied area");
        }
        var targets=citizens.stream().map(CitizenRecord::targetPosition).toList();
        h.onEachTick(()->{for(int i=0;i<citizens.size();i++){var c=citizens.get(i);var a=actors.get(i);var target=targets.get(i);
            if(!l.getBlockState(target).is(Blocks.DIRT)){a.navigateTo(target.above().west(),.65);CitizenWorkService.advanceSiteConstruction(l,data,s,c,a);}}});
        h.startSequence().thenWaitUntil(()->h.assertTrue(targets.stream().allMatch(p->l.getBlockState(p).is(Blocks.DIRT)),"Different-area workers did not complete real fills: "+citizens.stream().map(c->c.workFailure()+"/"+c.workState()).toList()))
            .thenExecute(()->{for(int i=0;i<actors.size();i++){h.assertTrue(actors.get(i).position().distanceTo(starts.get(i))>2,"Worker never approached its area");h.assertTrue(citizens.get(i).inventory().stream().filter(v->v.is(Items.DIRT)).mapToInt(ItemStack::getCount).sum()<stock.get(i),"Worker did not spend real dirt");actors.get(i).discard();}
                int spent=stock.stream().mapToInt(Integer::intValue).sum()-citizens.stream().flatMap(c->c.inventory().stream()).filter(v->v.is(Items.DIRT)).mapToInt(ItemStack::getCount).sum();
                long actual=s.terrain().projects.values().stream().flatMap(p->p.steps.stream()).filter(v->v.fill() && l.getBlockState(v.position()).is(Blocks.DIRT)).count();
                h.assertTrue(spent==actual,"Materials do not match actual fills: spent="+spent+" filled="+actual);f.clean();}).thenSucceed();
    }
}
