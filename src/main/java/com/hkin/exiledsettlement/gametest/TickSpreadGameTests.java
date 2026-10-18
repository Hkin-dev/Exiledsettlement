package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import java.util.UUID;

@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_timed")
@PrefixGameTestTemplate(false)
public final class TickSpreadGameTests {
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if("timed_construction".equals(System.getProperty("exiledsettlement.testSuite")))event.register(TickSpreadGameTests.class);
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="tick_budget",timeoutTicks=100)
    public static void expensiveQueryYieldsWithoutRepeatingCompletedWork(GameTestHelper h) {
        var l=h.getLevel();var actor=ModEntities.SETTLEMENT_CITIZEN.get().create(l);
        h.assertTrue(actor!=null,"Missing resident");var task=UUID.randomUUID();var at=h.absolutePos(new BlockPos(0,2,0));
        actor.moveTo(at.getX()+.5,at.getY(),at.getZ()+.5,0,0);int[] first={0},second={0};
        h.runAtTickTime(1,()->{
            boolean yielded=false;
            try(var scope=ConstructionPlanningBudget.begin(actor,task)) {
                ConstructionPlanningBudget.probe(actor,at,()->{first[0]++;long until=System.nanoTime()+3_000_000L;while(System.nanoTime()<until)Thread.onSpinWait();return null;});
                ConstructionPlanningBudget.probe(actor,at,()->{first[0]++;return null;});
                ConstructionPlanningBudget.probe(actor,at.east(),()->{second[0]++;return null;});
            }catch(ConstructionPlanningBudget.Deferred expected){yielded=true;}
            h.assertTrue(yielded && first[0]==1 && second[0]==0,"Spent time budget repeated a cached result or started another expensive query");
        });
        h.runAtTickTime(2,()->{
            try(var scope=ConstructionPlanningBudget.begin(actor,task)) {
                ConstructionPlanningBudget.probe(actor,at,()->{first[0]++;return null;});
                ConstructionPlanningBudget.probe(actor,at.east(),()->{second[0]++;return null;});
            }
            h.assertTrue(first[0]==1 && second[0]==1,"Resuming lost the completed prefix or failed to progress");h.succeed();
        });
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="tick_resume",timeoutTicks=300)
    public static void realBlockedPathsResumeWithoutFalseReachability(GameTestHelper h) {
        ConstructionPlanningBudgetGameTests.failedCandidatePrefixIsMemoizedAndSearchCompletesAcrossTicks(h);
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="tick_fairness",timeoutTicks=300)
    public static void multipleWorkersRetainFairSearchSlices(GameTestHelper h) {
        ConstructionPlanningBudgetGameTests.constructionQueriesObeyPerResidentAndSharedLevelLimits(h);
    }
}
