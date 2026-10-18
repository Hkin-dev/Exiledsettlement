package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_throughput") @PrefixGameTestTemplate(false)
public final class PlanningThroughputGameTests {
    @net.neoforged.bus.api.SubscribeEvent public static void register(net.neoforged.neoforge.event.RegisterGameTestsEvent e){
        if("timed_construction".equals(System.getProperty("exiledsettlement.testSuite")))e.register(PlanningThroughputGameTests.class);
    }
    private static int serial;
    @GameTest(templateNamespace="exiledsettlement_throughput",template="empty",batch="profile_day",timeoutTicks=2000)
    public static void daytimePlanningPublishesRealOrders(GameTestHelper h){
        com.hkin.exiledsettlement.gametest.TimedConstructionGameTests.planningPublishesRealOrders(h,6000);
    }
    private record Scene(SettlementRecord s,BlockPos at){}
    private static Scene scene(GameTestHelper h){
        var l=h.getLevel();var at=h.absolutePos(new BlockPos(2100000+serial++*512,80,0));
        for(int x=-20;x<=20;x++)for(int z=-20;z<=20;z++){
            var p=at.offset(x,0,z);l.getChunkAt(p);l.setChunkForced(p.getX()>>4,p.getZ()>>4,true);
            for(int y=-3;y<=4;y++)l.setBlock(p.above(y),(y==-3?Blocks.STONE:y<0?Blocks.DIRT:Blocks.AIR).defaultBlockState(),2);
        }
        var s=SettlementSavedData.get(l).createOrRecover(at,UUID.randomUUID(),"Fresh planning throughput").value();
        s.upgradeLevel();s.upgradeLevel();for(int pass=0;pass<10;pass++)for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)s.claimChunk((at.getX()>>4)+x,(at.getZ()>>4)+z);
        s.terrain().enabled=false;s.autoRoads().enabled=false;s.autoRoads().gradingReviewVersion=1;s.decorations().enabled=false;s.community().stewardEnabled=false;
        for(var c:s.citizens())c.setManualAssignment(com.hkin.exiledsettlement.citizen.CitizenProfession.HERDSMAN,null);
        return new Scene(s,at);
    }
    private static void clean(GameTestHelper h,Scene f){
        AutoRoadPlanner.invalidate(f.s());f.s().deactivate();
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){var p=f.at().offset(x*16,0,z*16);h.getLevel().setChunkForced(p.getX()>>4,p.getZ()>>4,false);}
    }
    @GameTest(templateNamespace="exiledsettlement_throughput",template="empty")
    public static void playerEditsKeepGeometryPaidHistoryAndExplicitZones(GameTestHelper h){
        var f=scene(h);var s=f.s();var l=h.getLevel();var at=f.at().offset(7,-1,0);
        var r=new AutoRoadState.Route(UUID.randomUUID(),at,List.of(at,at.east()));r.centerline=List.copyOf(r.cells);r.width=3;
        r.completed.set(0,2);r.admitted.set(0,2);r.progress=2;s.autoRoads().routes.put("paid",r);
        RoadSpaceData.get(l).addAll(r.cells);var w=s.terrain().publicGround;w.phase=4;w.cursor=17;
        w.goals.put(at.atY(0).asLong(),at.getY());w.minX=at.getX()-2;w.maxX=at.getX()+2;w.minZ=at.getZ()-2;w.maxZ=at.getZ()+2;
        l.setBlock(at.above(),Blocks.DIRT.defaultBlockState(),2);PlayerRoadEdits.changed(l,at.above());
        h.assertTrue(r.cells.equals(List.of(at,at.east())) && r.progress==2 && r.completed.cardinality()==2,"Player edit rewrote route or lost paid progress");
        h.assertTrue(!PlayerRoadEdits.holding(l,at) && !PlayerRoadEdits.retained(l,at) && RoadSpaceData.get(l).playerSurface(at)==null,"Implicit road adoption/cooldown remains");
        h.assertTrue(w.phase==4 && w.cursor==17 && !w.releasedConstraintReview && w.dirtyColumns.size()==1,"Local edit restarted full survey");
        h.assertTrue(r.repairs.cardinality()==1 && r.repairs.get(0),"Unrelated road columns were marked damaged");
        var restored=new PublicGroundService.State();restored.load(w.save());h.assertTrue(restored.dirtyColumns.equals(w.dirtyColumns),"Local repair request lost across save");
        w.dirtyColumns.clear();r.repairs.clear();s.decorations().forbiddenChunks.add(new net.minecraft.world.level.ChunkPos(at).toLong());
        PlayerRoadEdits.changed(l,at);
        h.assertTrue(w.dirtyColumns.isEmpty() && r.repairs.isEmpty() && !RoadEarthworkService.permitted(l,s,at),"Explicit player zone ignored");
        h.assertTrue(l.getBlockState(at.above()).is(Blocks.DIRT),"Change notification modified actual world");
        clean(h,f);h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_throughput",template="empty",timeoutTicks=800)
    public static void localHoleUsesSavedPlaneWithoutFullResurvey(GameTestHelper h){
        var f=scene(h);var s=f.s();var l=h.getLevel();s.terrain().enabled=true;
        var w=s.terrain().publicGround;var hole=f.at().offset(3,-1,2);boolean[] edited={false};long[] signature={0};
        h.onEachTick(()->{
            if(!edited[0] && w.phase==0 && w.roadsIntegrated && !w.goals.isEmpty()){
                signature[0]=w.signature;int y=w.goals.get(hole.atY(0).asLong());h.assertTrue(y==hole.getY(),"Fixture target is not flat");
                l.setBlock(hole,Blocks.AIR.defaultBlockState(),2);PlayerRoadEdits.changed(l,hole);edited[0]=true;
                h.assertTrue(w.signature==signature[0] && !w.dirtyColumns.isEmpty(),"Hole invalidated geometric solution");
            }else if(edited[0]){
                h.assertTrue(w.phase==0 || w.phase==5,"Local hole triggered global sampling or solving");
                h.assertTrue(w.signature==signature[0],"Local repair changed layout signature");
                if(TerrainService.pendingAt(s,hole)){
                    h.assertTrue(l.isEmptyBlock(hole),"Planner filled hole for free");
                    h.assertTrue(w.goals.get(hole.atY(0).asLong())==hole.getY(),"Hole became a lower target");
                    clean(h,f);h.succeed();
                }
            }
        });
    }
    @GameTest(templateNamespace="exiledsettlement_throughput",template="empty",timeoutTicks=100)
    public static void ordinaryReplacementOfPaidSoilCanBeReplanned(GameTestHelper h){
        var f=scene(h);var s=f.s();var l=h.getLevel();var at=f.at().offset(4,-1,0);
        var paid=new TerrainProject(UUID.randomUUID(),"Paid old public soil",at,List.of(new TerrainProject.Step(at,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));
        paid.publicGround=true;paid.explicit=false;paid.mark(0);paid.completed=true;paid.heightSuperseded=true;paid.groundSurfaces.add(at);s.terrain().index(paid);
        l.setBlock(at,Blocks.STONE.defaultBlockState(),2);
        h.assertTrue(s.terrain().supersededChanged(at,l.getBlockState(at)),"Fixture has no paid replacement evidence");
        var w=s.terrain().publicGround;w.phase=5;w.roadsIntegrated=true;w.goals.put(at.atY(0).asLong(),at.getY());s.terrain().enabled=true;
        h.startSequence().thenWaitUntil(()->h.assertTrue(TerrainService.pendingAt(s,at),"Ordinary replacement is still implicitly protected"))
                .thenExecute(()->{h.assertTrue(l.getBlockState(at).is(Blocks.STONE),"Planner rewrote paid replacement without workers");clean(h,f);}).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_throughput",template="empty")
    public static void fullAdmissionAndPublicPublicationDoNotBlockNewRoadSearch(GameTestHelper h){
        var f=scene(h);var s=f.s();var l=h.getLevel();
        var b=s.addBuilding(BuildingType.WAGON,f.at().east(12));b.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of(b.position()));
        s.terrain().enabled=true;s.autoRoads().enabled=true;var w=s.terrain().publicGround;w.phase=5;w.roadsIntegrated=true;w.planCursor=7;
        for(int x=-18;x<=18;x++)for(int z=-18;z<=18;z++)w.goals.put(f.at().offset(x,0,z).atY(0).asLong(),f.at().getY()-1);
        var cells=new ArrayList<BlockPos>();for(int i=0;i<TerrainConfig.OPTIONAL_PENDING_BLOCKS.get();i++)cells.add(f.at().offset(200+i,-1,200));
        var quota=new AutoRoadState.Route(UUID.randomUUID(),cells.getLast(),cells);quota.admitted.set(0,cells.size());s.autoRoads().routes.put("quota",quota);
        h.assertTrue(OptionalWorkBudget.snapshot(s).full(),"Fixture did not fill admission budget");
        AutoRoadService.tick(l,s);
        h.assertTrue(AutoRoadPlanner.busy(s) && AutoRoadPlanner.saved(s.autoRoads()).getUUID("Building").equals(b.id()),"New road still waits for public publication / admission");
        h.assertTrue(w.phase==5 && w.planCursor==7 && OptionalWorkBudget.snapshot(s).pending()==cells.size(),"Search changed public cursor or released extra work");
        clean(h,f);h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_throughput",template="empty")
    public static void sameGradeNewCorridorPreservesPublicPublication(GameTestHelper h){
        var f=scene(h);var s=f.s();var l=h.getLevel();s.terrain().enabled=true;s.autoRoads().enabled=true;
        var w=s.terrain().publicGround;w.phase=5;w.roadsIntegrated=true;w.planCursor=3;
        var line=List.of(f.at().offset(3,-1,4),f.at().offset(4,-1,4),f.at().offset(5,-1,4));
        for(var at:line)w.goals.put(at.atY(0).asLong(),at.getY());
        var candidate=new AutoRoadState.Route(UUID.randomUUID(),line.getLast(),line);candidate.centerline=line;candidate.width=3;
        h.assertTrue(AdaptiveRoadService.accept(l,s,"new",candidate),"Same-plane new route rejected");
        h.assertTrue(w.phase==5 && w.planCursor==3 && w.roadsIntegrated && w.releasedConstraintReview,"New corridor reset ongoing publication");
        h.assertTrue(s.terrain().projects.isEmpty() && l.getBlockState(line.getFirst()).is(Blocks.DIRT),"Route computation constructed world blocks");
        clean(h,f);h.succeed();
    }
}
