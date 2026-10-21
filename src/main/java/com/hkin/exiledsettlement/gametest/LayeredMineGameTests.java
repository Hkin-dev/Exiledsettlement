package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import java.util.*;

@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_layered_mine")
@PrefixGameTestTemplate(false)
public final class LayeredMineGameTests {
    @net.neoforged.bus.api.SubscribeEvent public static void register(RegisterGameTestsEvent event) {
        if("layered_mine".equals(System.getProperty("exiledsettlement.testSuite")))event.register(LayeredMineGameTests.class);
    }
    private static int serial;
    private record Fixture(GameTestHelper h,SettlementRecord s,BuildingRecord mine,CitizenRecord c,
                           SettlementCitizenEntity actor,Set<ChunkPos> chunks) {
        BlockPos at(){return mine.position();}
        BlockPos p(int x,int y,int z){return MineBlueprint.position(at(),mine.facing(),x,y,z);}
        void clean(){actor.discard();s.deactivate();for(var p:chunks)h.getLevel().setChunkForced(p.x,p.z,false);}
    }
    private static Fixture fixture(GameTestHelper h,Direction facing) {
        var l=h.getLevel();var at=new BlockPos(16000+serial++*96,100,16000);
        var chunks=new HashSet<ChunkPos>();
        for(var p:BlockPos.betweenClosed(at.offset(-20,-26,-20),at.offset(20,8,20))) {
            var cp=new ChunkPos(p);if(chunks.add(cp))l.setChunkForced(cp.x,cp.z,true);
            l.setBlock(p,p.getY()<at.getY()?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),2);
        }
        var core=at.offset(-12,0,14);l.setBlock(core,ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),2);
        var s=SettlementSavedData.get(l).createOrRecover(core,UUID.randomUUID(),"Layered mine acceptance").value();
        s.upgradeLevel();s.upgradeLevel();s.terrain().enabled=false;s.autoRoads().enabled=false;s.decorations().enabled=false;
        var t=BlueprintTemplates.create(BuildingType.MINE,at,facing);
        for(var p:t.placements())l.setBlock(p.position(),p.state(),2|16);
        BlueprintBlockStateResolver.refreshCompleted(l,t);
        var mine=s.addBuilding(t.type(),at,facing,t.minCorner(),t.maxCorner(),0,2);
        BuildingFunctionService.validateSingle(l,SettlementSavedData.get(l),s,mine);
        var c=s.citizens().iterator().next();for(var other:s.citizens())if(other!=c)other.markDead(at);
        c.setManualAssignment(CitizenProfession.MINER,mine.id());c.assignProfession(CitizenProfession.MINER,mine.id());
        c.insertIntoInventory(new ItemStack(Items.DIAMOND_PICKAXE));c.insertIntoInventory(new ItemStack(Items.BREAD,32));
        var e=ModEntities.SETTLEMENT_CITIZEN.get().create(l);
        e.moveTo(at.getX(),at.getY(),at.getZ()+7,0,0);e.setNoAi(false);l.addFreshEntity(e);
        c.updateProjection(e.getUUID(),e.blockPosition());
        e.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());
        e.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
        e.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_PICKAXE));
        l.setDayTime(6000);
        return new Fixture(h,s,mine,c,e,chunks);
    }
    private static void pass(Fixture f,String name){com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("LAYERED_MINE_PASS {}",name);f.clean();f.h.succeed();}
    private static int count(Fixture f,Item item){return f.c.inventory().stream().filter(v->v.is(item)).mapToInt(ItemStack::getCount).sum();}
    private static void advance(Fixture f,boolean supply) {
        var l=f.h.getLevel();var p=MineExpansionService.frontStations(f.mine).getFirst();
        f.actor.setPos(p.getX()+.5,p.getY(),p.getZ()+.5);
        var material=MineExpansionService.needed(f.mine);
        if(supply && material!=null)f.c.insertIntoInventory(new ItemStack(material));
        f.mine.mineExpansion().work=1;f.mine.mineExpansion().lastAction=l.getGameTime()-200;
        MineExpansionService.completedCycle(l,SettlementSavedData.get(l),f.s,f.mine,f.c,f.actor);
    }
    private static void full(Fixture f) {
        int total=LayeredMinePlan.firstSize()+LayeredMinePlan.repeatSize();
        for(int i=0;i<total;i++) {
            var step=MineExpansionService.next(f.mine);var material=MineExpansionService.needed(f.mine);
            for(var station:MineExpansionService.frontStations(f.mine))
                f.h.assertTrue(f.h.getLevel().noCollision(new net.minecraft.world.phys.AABB(
                    station.getX()+.2,station.getY(),station.getZ()+.2,
                    station.getX()+.8,station.getY()+1.95,station.getZ()+.8)),"Work station is inside unfinished rock at "+i+" "+station);
            int before=material==null?0:count(f,material);
            advance(f,true);
            f.h.assertTrue(f.mine.mineExpansion().cursor==i+1,"Stopped at "+i+" "+step+" : "+f.mine.mineExpansion().reason);
            if(material!=null)f.h.assertTrue(count(f,material)==before,"Material not consumed at "+i);
            for(int slot=0;slot<CitizenRecord.INVENTORY_SIZE;slot++) {
                var item=f.c.inventoryItem(slot);
                if(MineProductionService.isOutput(item)){
                    var cargo=f.c.extractInventoryItem(v->ItemStack.isSameItemSameComponents(v,item),item.getCount());
                    WarehouseInventoryService.insert(f.h.getLevel(),f.mine,cargo);
                }
            }
            if(i==30 || i==LayeredMinePlan.firstSize())f.mine.mineExpansion().load(f.mine.mineExpansion().save());
        }
    }
    @GameTest(templateNamespace="exiledsettlement_layered_mine",template="empty",timeoutTicks=300)
    public static void approvedGeometryAndStorageAllRotations(GameTestHelper h) {
        Fixture last=null;
        for(var d:Direction.Plane.HORIZONTAL) {
            var f=fixture(h,d);last=f;var t=BlueprintTemplates.createForBuilding(f.mine);
            h.assertTrue(f.mine.operational() && f.mine.workerCapacity()==10,"Mine not operational");
            h.assertTrue(t.minCorner().getY()==93 && t.maxCorner().getY()==105,"Wrong initial depth");
            for(var cell:LayeredMinePlan.initial()){
                var p=cell.position();var entry=t.placementAt(f.p(p.getX(),p.getY(),p.getZ()));
                h.assertTrue(entry!=null && entry.state().equals(cell.state().rotate(MineBlueprint.rotation(d))),"Approved cell changed");
            }
            for(var p:MineBlueprint.workPositions(f.at(),d))h.assertTrue(t.placementAt(p).state().isAir(),"Blocked work station");
            h.assertTrue(WarehouseInventoryService.interiorPositions(h.getLevel(),f.mine).size()==16,"Store stations missing");
            f.actor.setPos(f.at().getX(),100,f.at().getZ());h.assertTrue(!WarehouseInventoryService.canInteract(h.getLevel(),f.mine,f.actor),"Remote mine storage");
            var p=MineBlueprint.storagePosition(f.at(),d);f.actor.setPos(p.getX()+.5,p.getY(),p.getZ()+.5);
            h.assertTrue(WarehouseInventoryService.canInteract(h.getLevel(),f.mine,f.actor),"Actual store room inaccessible");
            f.clean();
        }
        com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("LAYERED_MINE_PASS geometry-four-directions");h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_layered_mine",template="empty",timeoutTicks=200)
    public static void newPlacementProtectsShaftAndRejectsFlooding(GameTestHelper h) {
        var f=fixture(h,Direction.EAST);var l=h.getLevel();var t=BlueprintTemplates.createForBuilding(f.mine);
        f.mine.deactivate();
        for(var p:t.placements())l.setBlock(p.position(),p.position().getY()<f.at().getY()?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),2|16);
        var wet=f.p(-3,-6,5);l.setBlock(wet,Blocks.WATER.defaultBlockState(),2);
        var denied=BlueprintService.placeBlueprint(l,f.s.ownerId(),BuildingType.MINE,f.at(),f.mine.facing(),false,null);
        h.assertTrue(!denied.success(),"Flooded mine accepted");
        l.setBlock(wet,Blocks.STONE.defaultBlockState(),2);
        var result=BlueprintService.placeBlueprint(l,f.s.ownerId(),BuildingType.MINE,f.at(),f.mine.facing(),false,null);
        h.assertTrue(result.success(),"New template cannot be planned: "+result.reason());
        h.assertTrue(!com.hkin.exiledsettlement.terrain.TerrainService.needsFoundation(f.s,result.value()),"Generic foundation would fill mine opening");
        h.assertTrue(BlueprintTemplates.create(BlueprintRecord.load(result.value().save())).minCorner().getY()==93,"Planning save lost initial level");
        pass(f,"planning");
    }
    @GameTest(templateNamespace="exiledsettlement_layered_mine",template="empty",timeoutTicks=300)
    public static void twoLayersRealBlocksMaterialsAndSave(GameTestHelper h) {
        for(var d:Direction.Plane.HORIZONTAL){
            var f=fixture(h,d);full(f);var t=BlueprintTemplates.createForBuilding(f.mine);
            h.assertTrue(t.minCorner().getY()==f.at().getY()-18,"Template depth did not follow expansion");
            h.assertTrue(f.mine.minCorner().getY()<=f.at().getY()-19,"Navigation bounds did not descend");
            var copy=BuildingRecord.load(f.mine.save(h.getLevel().registryAccess()));
            h.assertTrue(copy.mineExpansion().cursor==416 && copy.minCorner().equals(f.mine.minCorner()),"Save lost depth or cursor");
            var finalCells=new HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
            for(var step:MineExpansionService.steps().subList(0,416))finalCells.put(MineExpansionService.world(f.mine,step.local()),MineExpansionService.state(f.mine,step));
            for(var e:finalCells.entrySet())h.assertTrue(h.getLevel().getBlockState(e.getKey()).equals(e.getValue()),"Final placed state mismatch "+e);
            h.assertTrue(LayeredMinePlan.completedLayers(copy.mineExpansion().cursor)==2,"Wrong layer count");
            f.clean();
        }
        com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("LAYERED_MINE_PASS two-layers-four-directions");h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_layered_mine",template="empty",timeoutTicks=150)
    public static void materialProtectionAndUnsafeTerrainPause(GameTestHelper h) {
        var f=fixture(h,Direction.SOUTH);var l=h.getLevel();var step=MineExpansionService.next(f.mine);
        var at=MineExpansionService.world(f.mine,step.local());var before=l.getBlockState(at);
        advance(f,false);h.assertTrue(f.mine.mineExpansion().cursor==0 && l.getBlockState(at).equals(before),"Missing material changed world");
        f.c.insertIntoInventory(new ItemStack(Items.COBBLESTONE_STAIRS));
        java.util.function.Consumer<net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent> deny=e->{if(e.getPos().equals(at))e.setCanceled(true);};
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(deny);
        try{advance(f,false);}finally{net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(deny);}
        h.assertTrue(f.mine.mineExpansion().cursor==0 && count(f,Items.COBBLESTONE_STAIRS)==1 && l.getBlockState(at).equals(before),"Cancelled placement consumed material");
        var floor=MineExpansionService.world(f.mine,step.floor());var oldFloor=l.getBlockState(floor);
        l.setBlock(floor,Blocks.AIR.defaultBlockState(),2);h.assertTrue(!MineExpansionService.guard(l,f.s,f.mine,step).isEmpty(),"Cavern floor accepted");l.setBlock(floor,oldFloor,2);
        l.setBlock(at,Blocks.BEDROCK.defaultBlockState(),2);h.assertTrue(!MineExpansionService.guard(l,f.s,f.mine,step).isEmpty(),"Bedrock accepted");l.setBlock(at,before,2);
        var side=at.west();var oldSide=l.getBlockState(side);l.setBlock(side,Blocks.WATER.defaultBlockState(),2);
        h.assertTrue(!MineExpansionService.guard(l,f.s,f.mine,step).isEmpty(),"Fluid accepted");l.setBlock(side,oldSide,2);
        MineExpansionService.playerEdit(l,at);h.assertTrue(!MineExpansionService.guard(l,f.s,f.mine,step).isEmpty(),"Player edit accepted");
        h.assertTrue(BuildingRecord.load(f.mine.save(l.registryAccess())).mineExpansion().playerEdits.contains(at.asLong()),"Player protection lost");
        pass(f,"guards");
    }
    @GameTest(templateNamespace="exiledsettlement_layered_mine",template="empty",timeoutTicks=5000)
    public static void physicalWalkDownTwoLayersAndReturnToStore(GameTestHelper h) {
        var f=fixture(h,Direction.NORTH);full(f);
        // Use the actual navigation/physics without the work scheduler changing this route.
        f.actor.getPersistentData().remove(CitizenProjectionService.CITIZEN_ID_TAG);
        f.c.updateProjection(null,f.at());f.s.deactivate();
        var start=f.p(-3,0,7);f.actor.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        var targets=List.of(MineBlueprint.storagePosition(f.at(),f.mine.facing()),f.p(-1,-18,9),
                MineBlueprint.storagePosition(f.at(),f.mine.facing()),f.p(-3,0,6));
        int[] cursor={0};double[] travelled={0};var previous=new net.minecraft.world.phys.Vec3[]{f.actor.position()};
        h.onEachTick(()->{
            travelled[0]+=f.actor.position().distanceTo(previous[0]);previous[0]=f.actor.position();
            if(cursor[0]>=targets.size())return;
            var target=targets.get(cursor[0]);
            if(f.actor.distanceToSqr(target.getX()+.5,target.getY(),target.getZ()+.5)<1 && Math.abs(f.actor.getY()-target.getY())<.6){
                cursor[0]++;f.actor.stopSettlementNavigation();
            } else if(h.getLevel().getGameTime()%10==0)f.actor.navigateTo(target,.65);
            if(h.getLevel().getGameTime()%400==0) {
                var path=f.actor.getNavigation().getPath();
                com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("LAYERED_WALK stage={} at={} target={} distance={} local={} next={} ground={} feet={} above={}",
                    cursor[0],f.actor.position(),target,travelled[0],MineBlueprint.local(f.at(),f.mine.facing(),f.actor.blockPosition()),
                    path==null || path.isDone()?"none":path.getNextNodePos(),h.getLevel().getBlockState(f.actor.blockPosition().below()),
                    h.getLevel().getBlockState(f.actor.blockPosition()),h.getLevel().getBlockState(f.actor.blockPosition().above()));
            }
        });
        h.startSequence().thenWaitUntil(()->h.assertTrue(cursor[0]==targets.size(),"Waiting for physical descent and return"))
            .thenExecute(()->{
                h.assertTrue(travelled[0]>50,"No meaningful physical travel");
                com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("LAYERED_MINE_PASS physical-route distance={}",travelled[0]);f.clean();
            }).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_layered_mine",template="empty",timeoutTicks=6500)
    public static void realMinerFetchesStairsWorksAndDeposits(GameTestHelper h) throws Exception {
        var f=fixture(h,Direction.SOUTH);var l=h.getLevel();f.s.deactivate();
        // Small paid-stock access fixture; the mine and its route use the full approved template.
        var store=f.at().offset(10,0,1);
        var supplier=f.s.addBuilding(BuildingType.WAREHOUSE,store,Direction.NORTH,store.offset(-1,0,-1),store.offset(1,3,1),0,1);
        supplier.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of());
        WarehouseInventoryService.insert(l,supplier,new ItemStack(Items.COBBLESTONE_STAIRS,16));
        var start=f.p(-3,0,7);f.actor.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        f.c.startTask(CitizenWorkState.TO_MINE,MineExpansionService.frontStations(f.mine).getFirst(),null,null);
        var signature=new Class<?>[]{net.minecraft.server.level.ServerLevel.class,SettlementSavedData.class,SettlementRecord.class,CitizenRecord.class,SettlementCitizenEntity.class};
        var mining=CitizenWorkService.class.getDeclaredMethod("mine",signature);mining.setAccessible(true);
        var storing=CitizenWorkService.class.getDeclaredMethod("storeMineCargo",signature);storing.setAccessible(true);
        double[] distance={0};var previous=new net.minecraft.world.phys.Vec3[]{f.actor.position()};
        boolean[] delivered={false};long begin=l.getGameTime();
        h.onEachTick(()->{
            l.setDayTime(6000);distance[0]+=f.actor.position().distanceTo(previous[0]);previous[0]=f.actor.position();
            if(l.getGameTime()%10!=0)return;
            try {
                if(f.mine.mineExpansion().cursor>=8) {
                    storing.invoke(null,l,SettlementSavedData.get(l),f.s,f.c,f.actor);
                    delivered[0]=WarehouseInventoryService.stock(l,f.mine).quantity()>0 && !f.c.hasInventoryItem(MineProductionService::isOutput);
                } else if(f.c.workState()==CitizenWorkState.TO_MINE_STORAGE) {
                    storing.invoke(null,l,SettlementSavedData.get(l),f.s,f.c,f.actor);
                    if(f.c.workState()==CitizenWorkState.IDLE)f.c.startTask(CitizenWorkState.TO_MINE,MineExpansionService.frontStations(f.mine).getFirst(),null,null);
                } else mining.invoke(null,l,SettlementSavedData.get(l),f.s,f.c,f.actor);
            }catch(ReflectiveOperationException e){throw new RuntimeException(e);}
            if(l.getGameTime()%400==0)com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("LAYERED_MINER cursor={} at={} state={} reason={} supply={} store={}",
                    f.mine.mineExpansion().cursor,f.actor.position(),f.c.workState(),f.mine.mineExpansion().reason,
                    WarehouseInventoryService.countItem(l,supplier,Items.COBBLESTONE_STAIRS),WarehouseInventoryService.stock(l,f.mine).quantity());
        });
        h.startSequence().thenWaitUntil(()->h.assertTrue(delivered[0],"Waiting for physical supply, excavation and delivery"))
            .thenExecute(()->{
                h.assertTrue(WarehouseInventoryService.countItem(l,supplier,Items.COBBLESTONE_STAIRS)==0,"No actual supply extraction");
                h.assertTrue(count(f,Items.COBBLESTONE_STAIRS)==12,"Stair consumption mismatch");
                h.assertTrue(distance[0]>25,"No physical supply trip");
                com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("LAYERED_MINE_PASS working-miner ticks={} distance={} stored={} stairsSpent=4",
                    l.getGameTime()-begin,distance[0],WarehouseInventoryService.stock(l,f.mine).quantity());f.clean();
            }).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_layered_mine",template="empty",timeoutTicks=300)
    public static void layeredMineSurvivesWorldRestart(GameTestHelper h) throws Exception {
        var file=java.nio.file.Path.of("layered-mine-restart.json");var l=h.getLevel();var data=SettlementSavedData.get(l);
        if(!java.nio.file.Files.exists(file)) {
            var f=fixture(h,Direction.WEST);full(f);
            f.mine.mineExpansion().work=1;f.mine.mineExpansion().reason="saved partial work";
            f.mine.mineExpansion().playerEdits.add(f.p(0,-18,8).asLong());
            var row=new com.google.gson.JsonObject();row.addProperty("settlement",f.s.id().toString());
            row.addProperty("mine",f.mine.id().toString());row.addProperty("stock",WarehouseInventoryService.stock(l,f.mine).quantity());
            java.nio.file.Files.writeString(file,row.toString());
            data.touch();f.clean();
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("LAYERED_RESTART prepared");
        } else {
            var row=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
            var s=data.findById(UUID.fromString(row.get("settlement").getAsString())).orElseThrow();
            var mine=s.building(UUID.fromString(row.get("mine").getAsString()));
            h.assertTrue(mine!=null && mine.mineExpansion().cursor==416 && mine.mineExpansion().work==1
                    && mine.mineExpansion().reason.equals("saved partial work"),"Restart lost cursor/work/reason");
            h.assertTrue(mine.mineExpansion().playerEdits.contains(MineBlueprint.position(mine.position(),mine.facing(),0,-18,8).asLong()),"Restart lost player edits");
            h.assertTrue(WarehouseInventoryService.stock(l,mine).quantity()==row.get("stock").getAsInt(),"Restart changed stock");
            var cells=new HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
            for(var step:MineExpansionService.steps().subList(0,416))cells.put(MineExpansionService.world(mine,step.local()),MineExpansionService.state(mine,step));
            for(var cell:cells.entrySet()){
                l.getChunkAt(cell.getKey());
                h.assertTrue(BlueprintService.matchesPlacedState(l.getBlockState(cell.getKey()),cell.getValue()),"Restart changed world cell "+cell);
            }
            h.assertTrue(mine.minCorner().getY()==mine.position().getY()-19,"Restart lost lower navigation bounds");
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("LAYERED_RESTART loaded cells={} stock={}",cells.size(),row.get("stock"));
        }
        com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("LAYERED_MINE_PASS restart");h.succeed();
    }
}
