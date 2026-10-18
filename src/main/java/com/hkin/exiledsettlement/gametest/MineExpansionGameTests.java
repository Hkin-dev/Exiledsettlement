package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.ModBlocks;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import java.util.*;

@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_mine_expansion")
@PrefixGameTestTemplate(false)
public final class MineExpansionGameTests {
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if("mine_expansion".equals(System.getProperty("exiledsettlement.testSuite")))event.register(MineExpansionGameTests.class);
    }
    private static int serial;
    private record Fixture(GameTestHelper h,SettlementRecord s,BlockPos at,BuildingRecord mine,CitizenRecord c) {
        void clean(){s.deactivate();for(var c:s.citizens()){var e=CitizenProjectionService.findProjection(h.getLevel(),c);if(e!=null)e.discard();}}
    }
    private static Fixture fixture(GameTestHelper h,Direction facing) {
        var l=h.getLevel();var at=new BlockPos(8000+(serial++)*192,100,8000);
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)l.setChunkForced((at.getX()>>4)+x,(at.getZ()>>4)+z,true);
        for(var p:BlockPos.betweenClosed(at.offset(-30,-19,-30),at.offset(30,10,30)))
            l.setBlock(p,p.getY()<at.getY()?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),2);
        var core=at.offset(-16,0,16);l.setBlock(core,ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),3);
        var s=SettlementSavedData.get(l).createOrRecover(core,UUID.randomUUID(),"Independent expanding mine test").value();
        s.upgradeLevel();s.upgradeLevel();s.terrain().enabled=false;s.autoRoads().enabled=false;s.decorations().enabled=false;
        var mine=built(h,s,BlueprintTemplates.create(BuildingType.MINE,at,facing));
        var c=s.citizens().iterator().next();for(var other:s.citizens())if(other!=c)other.markDead(at);
        c.setManualAssignment(CitizenProfession.MINER,mine.id());c.assignProfession(CitizenProfession.MINER,mine.id());
        c.insertIntoInventory(new ItemStack(Items.BREAD,32));c.insertIntoInventory(new ItemStack(Items.DIAMOND_PICKAXE));
        c.updateProjection(null,at.offset(-8,0,7));l.setDayTime(2000);
        return new Fixture(h,s,at,mine,c);
    }
    private static BuildingRecord built(GameTestHelper h,SettlementRecord s,BuildingBlueprintTemplate t) {
        var l=h.getLevel();for(var p:t.placements())l.setBlock(p.position(),p.state(),2|16);
        BlueprintBlockStateResolver.refreshCompleted(l,t);
        var b=s.addBuilding(t.type(),t.anchor(),t.facing(),t.minCorner(),t.maxCorner(),t.transitionMargin(),BlueprintTemplates.currentTemplateVersion(t.type()));
        BuildingFunctionService.validateSingle(l,SettlementSavedData.get(l),s,b);return b;
    }
    private static SettlementCitizenEntity actor(Fixture f) {
        var l=f.h.getLevel();CitizenProjectionService.ensureSettlementCitizens(l,f.s);
        var e=CitizenProjectionService.findProjection(l,f.c);
        f.h.assertTrue(e!=null,"Waiting for independent test entity");return e;
    }
    private static void advance(Fixture f,SettlementCitizenEntity e) {
        var mine=f.mine;var l=f.h.getLevel();var at=MineExpansionService.frontStations(mine).getFirst();
        e.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);
        var material=MineExpansionService.needed(mine);if(material!=null)f.c.insertIntoInventory(new ItemStack(material));
        mine.mineExpansion().work=1;mine.mineExpansion().lastAction=l.getGameTime()-200;
        MineExpansionService.completedCycle(l,SettlementSavedData.get(l),f.s,mine,f.c,e);
    }
    @GameTest(templateNamespace="exiledsettlement_mine_expansion",template="empty",timeoutTicks=150)
    public static void boundedGeometryRotationAndSavedTemplate(GameTestHelper h) {
        for(var direction:Direction.Plane.HORIZONTAL) {
            var f=fixture(h,direction);var mine=f.mine;var l=h.getLevel();
            var base=BlueprintTemplates.createForBuilding(mine);
            var positions=new HashSet<BlockPos>();
            for(var step:MineExpansionService.steps()) {
                var at=MineExpansionService.world(mine,step.local());positions.add(at);
                h.assertTrue(Math.abs(at.getX()-f.at.getX())<=24 && Math.abs(at.getZ()-f.at.getZ())<=24,"Expansion is unbounded");
            }
            h.assertTrue(positions.size()>200 && positions.size()<500,"Unexpected visual excavation extent");
            h.assertTrue(mine.workerCapacity()==10 && MineExpansionService.workPositions(mine).size()==10,"Expansion changes worker cap");
            mine.mineExpansion().cursor=6;
            var first=MineExpansionService.world(mine,MineExpansionService.steps().getFirst().local());
            var copy=BuildingRecord.load(mine.save(l.registryAccess()));
            h.assertTrue(copy.mineExpansion().cursor==6 && BlueprintTemplates.createForBuilding(copy).placementAt(first).state().isAir(),"Open portal was lost or would be repaired closed");
            h.assertTrue(base.placementAt(first).state().is(Blocks.COBBLESTONE),"Historical mine template changed");
            var oldTag=mine.save();oldTag.remove("MineExpansion");
            h.assertTrue(BuildingRecord.load(oldTag).mineExpansion().cursor==0,"Legacy mine is not migrated lazily");
            f.clean();
        }
        h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_mine_expansion",template="empty",timeoutTicks=150)
    public static void ownPortalExceptionPreservesOtherProtections(GameTestHelper h) {
        var f=fixture(h,Direction.NORTH);var l=h.getLevel();var step=MineExpansionService.next(f.mine);
        var at=MineExpansionService.world(f.mine,step.local());
        h.assertTrue(com.hkin.exiledsettlement.terrain.PlayerWorksService.protectedAt(f.s,at),"Original generic protection was weakened");
        h.assertTrue(MineExpansionService.guard(l,f.s,f.mine,step).isEmpty(),"Approved own portal is blocked: "+MineExpansionService.guard(l,f.s,f.mine,step));
        h.assertTrue(com.hkin.exiledsettlement.terrain.PlayerWorksService.protectedForMineAdvance(f.s,f.mine,at.above()),"Own-mine exemption escaped its exact next cell");
        var other=f.s.addBuilding(BuildingType.HOUSE,at,Direction.NORTH,at,at,0,1);
        h.assertTrue(!MineExpansionService.guard(l,f.s,f.mine,step).isEmpty(),"Other overlapping building not protected");other.deactivate();
        var before=l.getBlockState(at);
        l.setBlock(at,Blocks.CHEST.defaultBlockState(),2);h.assertTrue(!MineExpansionService.guard(l,f.s,f.mine,step).isEmpty(),"Container is not protected");
        l.setBlock(at,before,2);
        var adjacent=at.relative(f.mine.facing());var saved=l.getBlockState(adjacent);
        l.setBlock(adjacent,Blocks.WATER.defaultBlockState(),2);h.assertTrue(!MineExpansionService.guard(l,f.s,f.mine,step).isEmpty(),"Adjacent fluid not protected");
        l.setBlock(adjacent,saved,2);
        MineExpansionService.playerEdit(l,at);
        var loaded=BuildingRecord.load(f.mine.save());h.assertTrue(loaded.mineExpansion().playerEdits.contains(at.asLong()),"Player edit lost on save");
        h.assertTrue(!MineExpansionService.guard(l,f.s,f.mine,step).isEmpty(),"Player edit was overwritten");
        h.assertTrue(l.getBlockState(at).equals(before) && f.mine.mineExpansion().cursor==0,"Read-only denial mutated mine");
        f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_mine_expansion",template="empty",timeoutTicks=250)
    public static void realDropsCancelledEventsAndNoOfflineOrRemoteWork(GameTestHelper h) {
        var f=fixture(h,Direction.SOUTH);var l=h.getLevel();
        h.startSequence().thenWaitUntil(()->actor(f)).thenExecute(()->{
            var e=actor(f);var state=f.mine.mineExpansion();var at=MineExpansionService.world(f.mine,MineExpansionService.next(f.mine).local());
            state.work=1;state.lastAction=l.getGameTime()-200;
            MineExpansionService.completedCycle(l,SettlementSavedData.get(l),f.s,f.mine,f.c,e);
            h.assertTrue(state.cursor==0,"Surface miner excavated remotely");
            var station=MineExpansionService.frontStations(f.mine).getFirst();e.setPos(station.getX()+.5,station.getY(),station.getZ()+.5);
            java.util.function.Consumer<net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent> deny=event->{if(event.getPos().equals(at))event.setCanceled(true);};
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(deny);
            int before=f.c.inventory().stream().filter(s->s.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum();
            try{advance(f,e);}finally{net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(deny);}
            h.assertTrue(state.cursor==0 && l.getBlockState(at).is(Blocks.COBBLESTONE),"Denied event did not roll back");
            h.assertTrue(before==f.c.inventory().stream().filter(s->s.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum(),"Cancelled excavation generated loot");
            advance(f,e);
            h.assertTrue(state.cursor==1 && l.getBlockState(at).isAir(),"Real excavation failed: "+state.reason);
            h.assertTrue(before+1==f.c.inventory().stream().filter(s->s.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum(),"Actual block drop was lost or duplicated");
            int cursor=state.cursor;long rest=12500L+CitizenWorkService.scheduleOffset(f.c);l.setDayTime(rest);advance(f,e);
            h.assertTrue(state.cursor==cursor,"Rest-time miner excavated");l.setDayTime(2000);
            f.mine.markDamaged(f.mine.position());advance(f,e);h.assertTrue(state.cursor==cursor,"Damaged mine continued excavation");f.mine.clearRepairPosition(f.mine.position());
            f.clean();
        }).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_mine_expansion",template="empty",timeoutTicks=300)
    public static void fullFinitePlanSupportCostWalkingSpaceAndReload(GameTestHelper h) {
        var f=fixture(h,Direction.EAST);var l=h.getLevel();
        h.startSequence().thenWaitUntil(()->actor(f)).thenExecute(()->{
            var e=actor(f);int logs=0,torches=0;
            for(int i=0;i<MineExpansionService.steps().size();i++) {
                var item=MineExpansionService.needed(f.mine);if(item==Items.SPRUCE_LOG)logs++;if(item==Items.TORCH)torches++;
                advance(f,e);h.assertTrue(f.mine.mineExpansion().cursor==i+1,"Plan stopped at "+i+": "+f.mine.mineExpansion().reason);
                // Rule fixture stores actual mined cargo between actions; entity chain is tested separately.
                for(int slot=0;slot<CitizenRecord.INVENTORY_SIZE;slot++) {
                    var stack=f.c.inventoryItem(slot);if(MineProductionService.isOutput(stack)) {
                        var cargo=f.c.extractInventoryItem(v->ItemStack.isSameItemSameComponents(v,stack),stack.getCount());
                        WarehouseInventoryService.insert(l,f.mine,cargo);
                    }
                }
                if(i==17 || i==100)f.mine.mineExpansion().load(f.mine.mineExpansion().save());
            }
            h.assertTrue(!f.c.hasInventoryItem(v->v.is(Items.SPRUCE_LOG)||v.is(Items.TORCH)),"Support materials were free or duplicated");
            var template=BlueprintTemplates.createForBuilding(f.mine);
            for(var step:MineExpansionService.steps()) {
                var at=MineExpansionService.world(f.mine,step.local());var expected=template.placementAt(at).state();
                h.assertTrue(BlueprintService.matchesPlacedState(l.getBlockState(at),expected),"Final structure mismatch at "+at);
            }
            // Actual collision geometry, including beams and wall torches, leaves 1.95-block clearance.
            for(int z=3;z<=23;z++)for(int x=0;x<=1;x++)checkStand(h,f,MineExpansionService.world(f.mine,new BlockPos(x,-10,z)));
            for(int x=-8;x<=0;x++)for(int z=10;z<=11;z++)checkStand(h,f,MineExpansionService.world(f.mine,new BlockPos(x,-10,z)));
            int cursor=f.mine.mineExpansion().cursor;advance(f,e);h.assertTrue(f.mine.mineExpansion().cursor==cursor,"Expansion exceeded finite plan");
            export(f);
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("MINE_EXPANSION_PLAN_OK steps={} logs={} torches={} stored={}",cursor,logs,torches,WarehouseInventoryService.stock(l,f.mine).quantity());
            f.clean();
        }).thenSucceed();
    }
    private static void checkStand(GameTestHelper h,Fixture f,BlockPos at) {
        h.assertTrue(h.getLevel().noCollision(new net.minecraft.world.phys.AABB(at.getX()+.2,at.getY(),at.getZ()+.2,at.getX()+.8,at.getY()+1.95,at.getZ()+.8)),"Blocked final passage: "+at);
        h.assertTrue(h.getLevel().getBlockState(at.below()).isFaceSturdy(h.getLevel(),at.below(),Direction.UP),"Missing floor: "+at);
    }
    private static void export(Fixture f) {
        var rows=new com.google.gson.JsonArray();var l=f.h.getLevel();
        var template=BlueprintTemplates.createForBuilding(f.mine);
        for(var at:BlockPos.betweenClosed(template.minCorner(),template.maxCorner())) {
            if(at.getY()<f.at.getY()-11 || at.getY()>f.at.getY()-6)continue;
            var row=new com.google.gson.JsonObject();var pos=new com.google.gson.JsonArray();
            pos.add(at.getX()-f.at.getX());pos.add(at.getY()-f.at.getY());pos.add(at.getZ()-f.at.getZ());
            row.add("position",pos);row.addProperty("state",l.getBlockState(at).toString().replace("Block{","").replace("}",""));rows.add(row);
        }
        try{java.nio.file.Files.writeString(java.nio.file.Path.of("mine-expansion-blocks.json"),rows.toString());}
        catch(java.io.IOException error){throw new IllegalStateException(error);}
    }
    @GameTest(templateNamespace="exiledsettlement_mine_expansion",template="empty",timeoutTicks=14000)
    public static void realMinerWalksFetchesSupportsAdvancesAndStores(GameTestHelper h) {
        var f=fixture(h,Direction.NORTH);var l=h.getLevel();var data=SettlementSavedData.get(l);
        var warehouse=built(h,f.s,BlueprintTemplates.create(BuildingType.WAREHOUSE,f.at.offset(17,0,12),Direction.WEST));
        WarehouseInventoryService.insert(l,warehouse,new ItemStack(Items.SPRUCE_LOG,64));
        WarehouseInventoryService.insert(l,warehouse,new ItemStack(Items.TORCH,32));
        var mine=f.mine;var c=f.c;long start=l.getGameTime(),wall=System.nanoTime();
        boolean[] supply={false},advanced={false};int[] max={0};
        h.onEachTick(()->{
            l.setDayTime(2000);var e=CitizenProjectionService.findProjection(l,c);if(e==null)return;
            supply[0]|=WarehouseInventoryService.countItem(l,warehouse,Items.SPRUCE_LOG)<64;
            var first=MineExpansionService.world(mine,new BlockPos(0,-10,4));
            advanced[0]|=mine.mineExpansion().cursor>=17 && e.distanceToSqr(first.getX()+.5,first.getY(),first.getZ()+.5)<2.25;
            max[0]=Math.max(max[0],mine.mineExpansion().cursor);
            if(l.getGameTime()%400==0)com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("[mine-expand-live] cursor={} supply={} advanced={} state={} failure={} expansion={} pos={} target={} stored={}",mine.mineExpansion().cursor,supply[0],advanced[0],c.workState(),c.workFailure(),mine.mineExpansion().reason,e.blockPosition(),c.targetPosition(),WarehouseInventoryService.stock(l,mine).quantity());
        });
        h.startSequence().thenWaitUntil(()->h.assertTrue(mine.mineExpansion().cursor>=24 && supply[0] && advanced[0]
                && WarehouseInventoryService.stock(l,mine).quantity()>0,"Waiting for real mining, support pickup, forward movement and cellar deposit"))
                .thenExecute(()->{
                    var copy=SettlementRecord.load(f.s.save(l.registryAccess()),l.registryAccess());
                    h.assertTrue(copy.building(mine.id()).mineExpansion().cursor==mine.mineExpansion().cursor,"Save lost expansion progress");
                    com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("MINE_EXPANSION_ENTITY_OK steps={} ticks={} wallSeconds={} supply={} advanced={} stored={}",max[0],l.getGameTime()-start,(System.nanoTime()-wall)/1e9,supply[0],advanced[0],WarehouseInventoryService.stock(l,mine).quantity());
                    f.clean();
                }).thenSucceed();
        data.touch();
    }
}
