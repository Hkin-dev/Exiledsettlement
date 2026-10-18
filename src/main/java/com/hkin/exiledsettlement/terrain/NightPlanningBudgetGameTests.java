package com.hkin.exiledsettlement.terrain;

import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import java.util.UUID;

@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_nightbudget")
@PrefixGameTestTemplate(false)
public final class NightPlanningBudgetGameTests {
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if("timed_construction".equals(System.getProperty("exiledsettlement.testSuite")))event.register(NightPlanningBudgetGameTests.class);
    }
    @GameTest(templateNamespace="exiledsettlement_nightbudget",template="empty",batch="night_budget",timeoutTicks=100)
    public static void nightSurveyKeepsOneWorldSlicePerEligibleTick(GameTestHelper h) {
        var l=h.getLevel();var core=h.absolutePos(new BlockPos(320000,4,0));l.setChunkForced(core.getX()>>4,core.getZ()>>4,true);l.getChunkAt(core);
        var s=new SettlementRecord(UUID.randomUUID(),"Detached night quota fixture",core,UUID.randomUUID());s.terrain().enabled=true;
        var w=s.terrain().publicGround;w.phase=1;w.minX=core.getX()+512;w.maxX=w.minX+100000;w.minZ=core.getZ();w.maxZ=w.minZ;
        l.setDayTime(13000);int[] calls={0};
        h.onEachTick(()->{
            long before=w.cursor;PublicGroundService.tick(l,s);long visited=w.cursor-before;
            h.assertTrue(visited>0 && visited<=TerrainConfig.PUBLIC_SCAN_COLUMNS.get(),"Night survey exceeds configured per-tick ceiling: "+visited);
            if(++calls[0]==5){l.setChunkForced(core.getX()>>4,core.getZ()>>4,false);h.succeed();}
        });
    }
    @GameTest(templateNamespace="exiledsettlement_nightbudget",template="empty",batch="night_profile",timeoutTicks=4000)
    public static void profileFreshNightRoadAndGroundPlanning(GameTestHelper h) {
        com.hkin.exiledsettlement.gametest.TimedConstructionGameTests.planningPublishesRealOrdersWithinNight(h);
    }
    private static PublicGroundService.State solve(boolean sliced,boolean reload) {
        var w=new PublicGroundService.State();
        for(int x=0;x<32;x++)for(int z=0;z<32;z++){long k=BlockPos.asLong(x,0,z);w.goals.put(k,64);w.baselines.put(k,64);}
        w.anchors.put(BlockPos.asLong(0,0,0),64);w.anchors.put(BlockPos.asLong(31,0,31),66);
        PublicGroundService.startSources(w);
        var s=new SettlementRecord(UUID.randomUUID(),"Detached sliced math",BlockPos.ZERO,UUID.randomUUID());
        boolean savedRelax=false,savedPassage=false;
        for(int i=0;i<100000;i++){
            w.deadline=sliced?0:Long.MAX_VALUE;
            switch(w.phase){
                case 8 -> PublicGroundService.spread(w);
                case 12 -> PublicGroundService.spreadRoads(w);
                case 9 -> PublicGroundService.divide(w);
                case 11 -> PublicGroundService.envelopes(w);
                case 13 -> PublicGroundService.initializeRoadRegions(w);
                case 10 -> PublicGroundService.relax(w);
                case 6 -> PublicGroundService.seams(w);
                case 7 -> PublicGroundService.passages(s,w);
                case 5 -> {return w;}
                default -> throw new IllegalStateException("Unexpected math phase "+w.phase);
            }
            if(reload && (w.phase==10 && w.planCursor>0 && !savedRelax || w.phase==7 && w.planCursor>0 && !savedPassage)){
                if(w.phase==10)savedRelax=true;else savedPassage=true;
                var restored=new PublicGroundService.State();restored.load(w.save());w=restored;
            }
        }
        throw new IllegalStateException("Time-sliced solver lost progress");
    }
    @GameTest(templateNamespace="exiledsettlement_nightbudget",template="empty",batch="budget_math")
    public static void expiredBudgetAndMidSliceReloadPreserveGeometry(GameTestHelper h) {
        var reference=solve(false,false);var sliced=solve(true,true);
        h.assertTrue(reference.goals.equals(sliced.goals) && reference.stairs.equals(sliced.stairs) && reference.barriers.equals(sliced.barriers),"Yield/save/reload changed terrain or stair geometry");
        h.succeed();
    }
}
