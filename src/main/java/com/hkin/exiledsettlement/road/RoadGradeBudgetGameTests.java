package com.hkin.exiledsettlement.road;

import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import java.util.*;

@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_nightbudget")
@PrefixGameTestTemplate(false)
public final class RoadGradeBudgetGameTests {
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if("timed_construction".equals(System.getProperty("exiledsettlement.testSuite")))event.register(RoadGradeBudgetGameTests.class);
    }
    @GameTest(templateNamespace="exiledsettlement_nightbudget",template="empty",batch="budget_road")
    public static void yieldingWithinCandidateRowPreservesFlatRoad(GameTestHelper h) {
        var l=h.getLevel();var at=h.absolutePos(new BlockPos(400000,20,0));
        for(var p:BlockPos.betweenClosed(at.offset(-3,-8,-3),at.offset(11,5,3))){l.getChunkAt(p);l.setBlock(p,p.getY()<=at.getY()?Blocks.DIRT.defaultBlockState():Blocks.AIR.defaultBlockState(),2);}
        var s=new SettlementRecord(UUID.randomUUID(),"Detached road budget",at.west(10).above(),UUID.randomUUID());
        var line=new ArrayList<BlockPos>();for(int x=0;x<=8;x++)line.add(at.east(x).below(Math.min(x,8-x)/2));
        var whole=new RoadGradePlanner(line);int wholeCalls=0;while(!whole.done() && wholeCalls++<100)whole.advance(l,s,Long.MAX_VALUE);
        var sliced=new RoadGradePlanner(line);int slicedCalls=0;while(!sliced.done() && slicedCalls++<1000)sliced.advance(l,s,0);
        h.assertTrue(whole.done() && sliced.done() && slicedCalls>wholeCalls,"Expired deadline did not split candidate rows");
        h.assertTrue(whole.result().equals(sliced.result()) && sliced.result().stream().allMatch(p->p.getY()==at.getY()),"Yielding changed grading or restored a avoidable dip");
        h.assertTrue(l.getBlockState(at.east(4)).is(Blocks.DIRT),"Planner edited real terrain");
        com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("ROAD_BUDGET wholeCalls={} slicedCalls={} equalGeometry=true",wholeCalls,slicedCalls);
        h.succeed();
    }
}
