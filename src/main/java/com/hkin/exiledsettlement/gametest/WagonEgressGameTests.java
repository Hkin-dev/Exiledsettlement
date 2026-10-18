package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.*;
import java.util.UUID;

@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_wagonegress")
@PrefixGameTestTemplate(false)
public final class WagonEgressGameTests {
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if("wagon_egress".equals(System.getProperty("exiledsettlement.testSuite")))event.register(WagonEgressGameTests.class);
    }
    private static int serial;
    private static void check(GameTestHelper h,boolean protectedBounds,boolean reload) {
        var level=h.getLevel();var feet=new BlockPos(78000+serial++*512,100,78000);
        var chunk=new net.minecraft.world.level.ChunkPos(feet);
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.setChunkForced(chunk.x+x,chunk.z+z,true);
        for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++) {
            for(int y=0;y<=5;y++)level.setBlock(feet.offset(x,y,z),Blocks.AIR.defaultBlockState(),2);
            level.setBlock(feet.offset(x,-1,z),Blocks.STONE.defaultBlockState(),2);
        }
        for(var direction:Direction.Plane.HORIZONTAL)level.setBlock(feet.relative(direction).above(2),Blocks.STONE.defaultBlockState(),2);
        var data=SettlementSavedData.get(level);
        var settlement=data.createOrRecover(feet.west(12),UUID.randomUUID(),"Wagon egress regression").value();
        var wagon=settlement.addBuilding(BuildingType.WAGON,feet.offset(5,0,5),Direction.NORTH,
                protectedBounds?feet.offset(-7,0,-7):feet.offset(5,0,5),
                protectedBounds?feet.offset(7,4,7):feet.offset(5,0,5),0,2);
        if(reload) {
            settlement=SettlementRecord.load(settlement.save(level.registryAccess()),level.registryAccess());
            h.assertTrue(settlement.buildings().stream().anyMatch(b->b.type()==BuildingType.WAGON && b.templateVersion()==2),"Saved wagon identity changed");
        }
        var home=settlement.addBuilding(BuildingType.HOUSE,feet.west(20));
        var citizen=settlement.citizens().iterator().next();citizen.assignHome(home.id());citizen.updateWorkFailure("BED_UNREACHABLE");
        var entity=ModEntities.SETTLEMENT_CITIZEN.get().create(level);
        entity.moveTo(feet.getX()+.5,feet.getY(),feet.getZ()+.5,0,0);entity.setOnGround(true);entity.setNoAi(true);
        h.assertTrue(level.addFreshEntity(entity),"Resident fixture failed to spawn");settlement.deactivate();
        try {
            boolean planned=ResidentEgressService.plan(level,settlement,citizen,entity);
            h.assertTrue(planned!=protectedBounds,"Egress must avoid wagon bounds and still work beside a wagon");
            h.assertTrue(!planned || !settlement.terrain().projects.isEmpty(),"Successful egress must publish a real work order");
            for(var direction:Direction.Plane.HORIZONTAL)h.assertTrue(level.getBlockState(feet.relative(direction).above(2)).is(Blocks.STONE),"Planning must not break blocks for free");
        }finally {entity.discard();data.touch();}
        h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_bridge65_protection",template="empty",timeoutTicks=100)
    public static void starterWagonDoesNotCrashEgress(GameTestHelper h){check(h,false,false);}
    @GameTest(templateNamespace="exiledsettlement_bridge65_protection",template="empty",timeoutTicks=100)
    public static void wagonBoundsRemainProtected(GameTestHelper h){check(h,true,false);}
    @GameTest(templateNamespace="exiledsettlement_bridge65_protection",template="empty",timeoutTicks=100)
    public static void savedWagonStillAllowsNaturalGroundEgress(GameTestHelper h){check(h,false,true);}
}
