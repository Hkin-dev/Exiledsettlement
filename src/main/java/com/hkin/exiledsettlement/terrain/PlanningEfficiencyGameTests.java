package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.road.AutoRoadState;
import com.hkin.exiledsettlement.road.PlayerRoadEdits;
import com.hkin.exiledsettlement.road.RoadSpaceData;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** Pure route queries in detached records; no resident, block or inventory edits. */
@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_efficiency")
@PrefixGameTestTemplate(false)
public final class PlanningEfficiencyGameTests {
    private static volatile long benchmarkChecksum;

    private PlanningEfficiencyGameTests() { }

    @net.neoforged.bus.api.SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if("efficiency".equals(System.getProperty("exiledsettlement.testSuite")))event.register(PlanningEfficiencyGameTests.class);
    }

    private static SettlementRecord detached() {
        return new SettlementRecord(UUID.randomUUID(),"Detached road column checks",
                new BlockPos(2700000,80,2700000),UUID.randomUUID());
    }

    private static AutoRoadState.Route route(BlockPos... cells) {
        var route=new AutoRoadState.Route(UUID.randomUUID(),cells.length==0?BlockPos.ZERO:cells[0],List.of(cells));
        route.width=3;
        return route;
    }

    private static long column(BlockPos at) {return BlockPos.asLong(at.getX(),0,at.getZ());}

    /** Exact previous production implementation, retained only as a comparison oracle. */
    private static boolean scanned(ServerLevel level,SettlementRecord settlement,BlockPos at) {
        if(RoadSpaceData.get(level).columnHeight(at)!=null)return true;
        return settlement.autoRoads().routes.values().stream().filter(r->!r.suppressed && !r.p0Invalidated)
                .anyMatch(r->r.cells.stream().anyMatch(p->p.getX()==at.getX() && p.getZ()==at.getZ()));
    }

    private static void same(GameTestHelper helper,SettlementRecord settlement,BlockPos at,boolean expected) {
        helper.assertTrue(scanned(helper.getLevel(),settlement,at)==expected,"Legacy oracle mismatch at "+at);
        helper.assertTrue(PublicGroundService.roadColumn(helper.getLevel(),settlement,at)==expected,
                "Indexed road membership mismatch at "+at);
    }

    @GameTest(templateNamespace="exiledsettlement_efficiency",template="empty")
    public static void roadColumnsMatchForHeightsDuplicatesEmptyAndSuppressedRoutes(GameTestHelper helper) {
        var settlement=detached();var at=settlement.corePosition();
        same(helper,settlement,at,false);
        settlement.autoRoads().routes.put("empty",route());
        same(helper,settlement,at,false);
        var live=route(at,at.above(12),at.east(2));
        var suppressed=route(at.west(3));suppressed.suppressed=true;
        var invalid=route(at.north(4));invalid.p0Invalidated=true;
        settlement.autoRoads().routes.put("live",live);
        settlement.autoRoads().routes.put("suppressed",suppressed);
        settlement.autoRoads().routes.put("invalid",invalid);
        live.admitted.set(0,3);live.completed.set(0);live.repairs.set(2);live.advance();
        for(int y:new int[]{-64,0,80,96,319}) {
            same(helper,settlement,at.atY(y),true);
            same(helper,settlement,at.east(2).atY(y),true);
            same(helper,settlement,at.east().atY(y),false);
            same(helper,settlement,at.west(3).atY(y),false);
            same(helper,settlement,at.north(4).atY(y),false);
        }
        helper.assertTrue(live.cellInColumn(at.above(200)).equals(at),"Duplicate column no longer keeps first cell");
        live.suppressed=true;same(helper,settlement,at,false);
        live.suppressed=false;live.p0Invalidated=true;same(helper,settlement,at,false);
        live.p0Invalidated=false;same(helper,settlement,at,true);
        helper.assertTrue(live.progress==1 && live.completed.cardinality()==1
                && live.admitted.cardinality()==3 && live.repairs.get(2),"Query mutated paid work or repairs");
        // An actual registered road column must still win even without a live route.
        var registered=at.south(7);var roads=RoadSpaceData.get(helper.getLevel());
        helper.assertTrue(roads.columnHeight(registered)==null,"Detached fixture overlaps a registered road");
        try {
            roads.addAll(List.of(registered));
            same(helper,settlement,registered.above(30),true);
        } finally {roads.remove(registered);}
        same(helper,settlement,registered,false);
        helper.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_efficiency",template="empty")
    public static void routeReplacementReplanningAndReloadDiscardOldColumns(GameTestHelper helper) {
        var settlement=detached();var at=settlement.corePosition();
        var mutable=at.mutable();var input=new ArrayList<BlockPos>();input.add(mutable);input.add(at.east());
        var original=new AutoRoadState.Route(UUID.randomUUID(),at,input);original.width=3;
        settlement.autoRoads().routes.put("route",original);
        same(helper,settlement,at,true);
        mutable.move(40,0,0);input.clear();
        same(helper,settlement,at,true);same(helper,settlement,at.east(40),false);
        // Widening/replanning replaces the list, including equal-sized replacements.
        original.cells=List.of(at.north(10),at.north(10).east());original.centerline=original.cells;
        same(helper,settlement,at,false);same(helper,settlement,at.north(10),true);
        helper.assertTrue(original.cellIndex(at)==-1 && original.cellIndex(at.north(10))==0,"Cell index retained the old plan");
        var raised=at.north(10).above(5);PlayerRoadEdits.adopt(original,raised);
        same(helper,settlement,raised.below(100),true);
        helper.assertTrue(original.cellInColumn(at.north(10)).equals(raised)
                && original.cellIndex(at.north(10))==-1,"Same-column regrade retained old height");
        original.cells=List.of();original.centerline=List.of();
        same(helper,settlement,raised,false);
        var replacement=route(at.south(12),at.south(12).east());
        replacement.admitted.set(0,2);replacement.completed.set(0);replacement.repairs.set(1);replacement.advance();
        settlement.autoRoads().routes.put("route",replacement);
        same(helper,settlement,raised,false);same(helper,settlement,at.south(12),true);
        var saved=settlement.autoRoads().save();
        // A populated receiver proves load replaces existing records, not just adds new ones.
        var restored=detached();restored.autoRoads().routes.put("stale",route(at.west(50)));
        same(helper,restored,at.west(50),true);
        restored.autoRoads().load(saved);
        same(helper,restored,at.west(50),false);same(helper,restored,raised,false);
        same(helper,restored,at.south(12).below(100),true);
        var loaded=restored.autoRoads().routes.get("route");
        helper.assertTrue(loaded!=replacement && loaded.progress==1 && loaded.completed.get(0)
                && loaded.admitted.cardinality()==2 && loaded.repairs.get(1),"Reload changed paid progress or repairs");
        replacement.cells=List.of(at.east(60));replacement.centerline=replacement.cells;
        same(helper,restored,at.south(12),true);same(helper,restored,at.east(60),false);
        restored.autoRoads().routes.clear();same(helper,restored,at.south(12),false);
        helper.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_efficiency",template="empty",batch="planning_query_benchmark",timeoutTicks=200)
    public static void interleavedWarmColumnQueryBenchmark(GameTestHelper helper) {
        var settlement=detached();var base=settlement.corePosition();var expectedColumns=new HashSet<Long>();
        int routes=24,cellsPerRoute=256,queryCount=192,repeats=2,warmups=6,samples=9;
        for(int r=0;r<routes;r++) {
            var cells=new ArrayList<BlockPos>();
            for(int i=0;i<cellsPerRoute;i++)cells.add(base.offset(i,Math.floorMod(i,4),r*4));
            var route=new AutoRoadState.Route(UUID.randomUUID(),cells.getLast(),cells);route.width=3;
            route.suppressed=r%7==0;route.p0Invalidated=r%11==0;
            settlement.autoRoads().routes.put("route-"+r,route);
            if(!route.suppressed && !route.p0Invalidated)for(var p:cells)expectedColumns.add(column(p));
        }
        var random=new Random(10152026L);var queries=new ArrayList<BlockPos>();long expected=0;
        for(int i=0;i<queryCount;i++) {
            var p=base.offset(random.nextInt(cellsPerRoute+64),random.nextInt(200)-100,
                    random.nextInt(routes)*4+(i%3==0?1:0));queries.add(p);
            if(expectedColumns.contains(column(p)))expected++;
        }
        for(var query:queries)same(helper,settlement,query,expectedColumns.contains(column(query)));
        var scannedNanos=new long[samples];var indexedNanos=new long[samples];
        for(int round=-warmups;round<samples;round++)for(int pass=0;pass<2;pass++) {
            boolean indexed=Math.floorMod(round+pass,2)==0;long count=0;long start=System.nanoTime();
            for(int repeat=0;repeat<repeats;repeat++)for(var query:queries)
                if(indexed?PublicGroundService.roadColumn(helper.getLevel(),settlement,query):scanned(helper.getLevel(),settlement,query))count++;
            long elapsed=System.nanoTime()-start;benchmarkChecksum=count;
            helper.assertTrue(count==expected*repeats,"Benchmark variants returned different column membership");
            if(round>=0)(indexed?indexedNanos:scannedNanos)[round]=elapsed;
        }
        var oldSorted=scannedNanos.clone();var newSorted=indexedNanos.clone();Arrays.sort(oldSorted);Arrays.sort(newSorted);
        ExiledSettlementMod.LOGGER.info("[planning-column-benchmark] scope=pure-query-warm-cache routes={} cellsPerRoute={} queriesPerSample={} warmups={} samples={} checksum={} scannedNanos={} indexedNanos={} scannedMedianMs={} indexedMedianMs={} scannedMaxMs={} indexedMaxMs={}",
                routes,cellsPerRoute,queryCount*repeats,warmups,samples,benchmarkChecksum,Arrays.toString(scannedNanos),Arrays.toString(indexedNanos),
                oldSorted[samples/2]/1e6,newSorted[samples/2]/1e6,oldSorted[samples-1]/1e6,newSorted[samples-1]/1e6);
        helper.succeed();
    }
}
