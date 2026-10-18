package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.debug.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import java.util.UUID;

@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_pitescape")
@PrefixGameTestTemplate(false)
public final class PitEscapeGameTests {
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(RegisterGameTestsEvent event){
        if("pit_escape".equals(System.getProperty("exiledsettlement.testSuite")))event.register(PitEscapeGameTests.class);
    }
    private static int serial;
    private record Fixture(SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e,BlockPos feet){}
    private static Fixture fixture(GameTestHelper h,boolean open,boolean roof,boolean formal){
        var l=h.getLevel();var feet=new BlockPos(68000+serial++*64,250,68000);
        forceFooting(h,feet);
        var data=SettlementSavedData.get(l);var s=data.createOrRecover(feet.west(12),UUID.randomUUID(),"Independent pit/roof regression").value();
        for(int x=-6;x<=6;x++)for(int z=-6;z<=6;z++){
            for(int y=-1;y<=6;y++)l.setBlock(feet.offset(x,y,z),Blocks.AIR.defaultBlockState(),2);
            if(open || x==0 && z==0)l.setBlock(feet.offset(x,-1,z),Blocks.STONE.defaultBlockState(),2);
        }
        // The old algorithm accepts this raised bank even when it is a warehouse roof.
        for(int x=1;x<=4;x++)for(int z=-2;z<=2;z++)for(int y=-1;y<=3;y++)
            l.setBlock(feet.offset(x,y,z),Blocks.STONE.defaultBlockState(),2);
        if(roof && formal)s.addBuilding(BuildingType.WAREHOUSE,feet.east(),Direction.WEST,
                feet.offset(1,-1,-2),feet.offset(4,3,2),0,1);
        if(!roof)for(int x=1;x<=4;x++)for(int z=-2;z<=2;z++)for(int y=2;y<=3;y++)
            l.setBlock(feet.offset(x,y,z),Blocks.AIR.defaultBlockState(),2);
        var c=s.citizens().iterator().next();c.setManualAssignment(CitizenProfession.WORKER,null);
        c.insertIntoInventory(new ItemStack(Items.DIRT,16));c.startTask(CitizenWorkState.TO_EQUIPMENT,s.corePosition(),null,null);
        var e=ModEntities.SETTLEMENT_CITIZEN.get().create(l);
        e.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());
        e.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
        e.moveTo(feet.getX()+.5,feet.getY(),feet.getZ()+.5,0,0);e.setOnGround(true);e.setNoAi(true);
        h.assertTrue(l.addFreshEntity(e),"Actor must enter the world's physical entity queries");data.updateProjection(s,c,e.getUUID(),feet);
        s.deactivate(); // Only the explicitly observed escape advances in this isolated fixture.
        return new Fixture(s,c,e,feet);
    }
    private static void forceFooting(GameTestHelper h,BlockPos at){
        var chunk=new net.minecraft.world.level.ChunkPos(at);
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)h.getLevel().setChunkForced(chunk.x+x,chunk.z+z,true);
    }
    private static void noPillar(GameTestHelper h,Fixture f){
        var l=h.getLevel();var stock=f.c().save(l.registryAccess());
        for(int i=0;i<5;i++)h.assertTrue(!PitEscapeService.request(l,f.s(),f.c(),f.e()),"Unproven pit must not request a pillar");
        h.assertTrue(stock.equals(f.c().save(l.registryAccess())),"Refusal changed task, stock or escape state");
        for(int y=0;y<4;y++)h.assertTrue(l.getBlockState(f.feet().above(y)).isAir(),"Refusal placed a dirt pillar");
        h.assertTrue(f.s().terrain().projects.isEmpty(),"Refusal created work orders");f.e().discard();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_bridge65_protection",template="empty",timeoutTicks=200)
    public static void flatWarehouseFailureDoesNotBuildToRoof(GameTestHelper h){
        var f=fixture(h,true,true,true);var roof=f.feet().east().above(4);
        h.assertTrue(f.e().canStandAtPosition(roof) && f.e().canTraverseRecordedConstructionEdge(f.feet().above(4),roof),
                "Fixture must reproduce the old locally standable roof exit");noPillar(h,f);
    }
    @GameTest(templateNamespace="exiledsettlement_bridge65_protection",template="empty",timeoutTicks=200)
    public static void enclosedFootingNeverUsesWarehouseRoof(GameTestHelper h){noPillar(h,fixture(h,false,true,true));}
    @GameTest(templateNamespace="exiledsettlement_bridge65_protection",template="empty",timeoutTicks=200)
    public static void isolatedWallIsNotAnEscapeBank(GameTestHelper h){
        var f=fixture(h,false,true,false);var l=h.getLevel();
        for(int x=1;x<=4;x++)for(int z=-2;z<=2;z++)if(x!=1 || z!=0)
            for(int y=-1;y<=3;y++)l.setBlock(f.feet().offset(x,y,z),Blocks.AIR.defaultBlockState(),2);
        noPillar(h,f);
    }
    @GameTest(templateNamespace="exiledsettlement_bridge65_protection",template="empty",timeoutTicks=200)
    public static void savedRoofEscapeStopsWithoutFurtherPayment(GameTestHelper h){
        var f=fixture(h,false,true,true);var l=h.getLevel();var tag=new CompoundTag();
        tag.putLong("Column",f.feet().asLong());tag.putLong("Exit",f.feet().east().above(4).asLong());
        f.c().pitEscape.load(tag);f.c().reservePitBlocks(PitEscapeService::ordinary,16);
        h.assertTrue(!PitEscapeService.tick(l,SettlementSavedData.get(l),f.s(),f.c(),f.e()),"Saved roof escape continued");
        h.assertTrue(f.c().pitEscape.exit==null && f.c().pitReserve().getCount()==16 && l.getBlockState(f.feet()).isAir(),"Stopping spent or placed a block");
        h.assertTrue(f.c().workState()==CitizenWorkState.TO_EQUIPMENT && f.s().corePosition().equals(f.c().targetPosition()),"Stopping erased supply task");
        f.e().discard();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_bridge65_protection",template="empty",timeoutTicks=200)
    public static void removedExitRouteStopsBeforePayment(GameTestHelper h){
        var f=fixture(h,false,false,false);var l=h.getLevel();
        h.assertTrue(PitEscapeService.request(l,f.s(),f.c(),f.e()),"Valid real bank was refused");
        for(int x=1;x<=4;x++)for(int z=-2;z<=2;z++)if(x!=1 || z!=0)
            for(int y=-1;y<=1;y++)l.setBlock(f.feet().offset(x,y,z),Blocks.AIR.defaultBlockState(),2);
        f.c().pitEscape.lastTick=l.getGameTime()-10;
        h.assertTrue(!PitEscapeService.tick(l,SettlementSavedData.get(l),f.s(),f.c(),f.e()),"Changed exit route kept placing blocks");
        h.assertTrue(f.c().pitReserve().getCount()==16 && l.getBlockState(f.feet()).isAir(),"Changed exit spent material");
        f.e().discard();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_bridge65_protection",template="empty",timeoutTicks=200)
    public static void removedExitDoesNotStartOverheadCutting(GameTestHelper h){
        var f=fixture(h,false,false,false);var l=h.getLevel();f.c().insertIntoInventory(new ItemStack(Items.IRON_PICKAXE));
        h.assertTrue(PitEscapeService.request(l,f.s(),f.c(),f.e()),"Valid bank was refused");
        l.setBlock(f.feet().above(2),Blocks.STONE.defaultBlockState(),2);
        for(int x=1;x<=4;x++)for(int z=-2;z<=2;z++)if(x!=1 || z!=0)
            for(int y=-1;y<=1;y++)l.setBlock(f.feet().offset(x,y,z),Blocks.AIR.defaultBlockState(),2);
        h.assertTrue(!PitEscapeService.tick(l,SettlementSavedData.get(l),f.s(),f.c(),f.e()),"Broken route started unnecessary head cutting");
        var pick=f.c().inventoryItem(f.c().findInventorySlot(stack->stack.is(Items.IRON_PICKAXE)));
        h.assertTrue(pick.getDamageValue()==0 && f.c().pitReserve().getCount()==16 && l.getBlockState(f.feet().above(2)).is(Blocks.STONE),"Invalid escape broke terrain or spent material/tool durability");
        f.e().discard();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_bridge65_protection",template="empty",timeoutTicks=200)
    public static void realPitStillClearsHeadPaysAndSaves(GameTestHelper h){
        forceFooting(h,new BlockPos(56000,250,56000));
        h.startSequence().thenExecuteAfter(30,()->h.assertTrue(PitEscape91Checks.run(h.getLevel()).startsWith("PASS"),"Deep pit regression failed")).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_bridge65_protection",template="empty",timeoutTicks=200)
    public static void widePitRetainsItsFlatBottomAndRealBank(GameTestHelper h){
        forceFooting(h,new BlockPos(61200,250,61200));
        h.startSequence().thenExecuteAfter(30,()->h.assertTrue(WidePit127Checks.run(h.getLevel()).startsWith("PASS"),"Wide pit regression failed")).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_bridge65_protection",template="empty",timeoutTicks=200)
    public static void oneBlockBankNeedsNoDirt(GameTestHelper h){
        forceFooting(h,new BlockPos(61000,250,61000));
        h.startSequence().thenExecuteAfter(30,()->h.assertTrue(Bank122Checks.run(h.getLevel()).startsWith("PASS"),"Existing step regression failed")).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_bridge65_protection",template="empty",timeoutTicks=300)
    public static void physicalEscapeReachesConnectedLand(GameTestHelper h){
        var f=fixture(h,false,false,false);var l=h.getLevel();
        h.assertTrue(PitEscapeService.request(l,f.s(),f.c(),f.e()),"Real enclosed two-block bank must remain escapable");
        f.c().pitEscape.load(f.c().pitEscape.save()); // A resumed job must rediscover its actual route.
        h.onEachTick(()->{
            PitEscapeService.tick(l,SettlementSavedData.get(l),f.s(),f.c(),f.e());
            h.assertTrue(f.e().isAlive() && !f.e().isInWater(),"Physical escape damaged or submerged worker");
            if(f.e().navigationFeetPosition().equals(f.feet().east().above(2))){
                h.assertTrue(f.c().pitReserve().getCount()==14 && l.getBlockState(f.feet()).is(Blocks.DIRT)
                        && l.getBlockState(f.feet().above()).is(Blocks.DIRT),"Escape did not pay exactly two retained blocks");
                h.assertTrue(f.c().targetPosition().equals(f.s().corePosition()),"Escape changed the original task");
                f.e().discard();h.succeed();
            }
        });
    }
}
