package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import com.hkin.exiledsettlement.road.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import java.util.function.Consumer;

@GameTestHolder("exiledsettlement_timed")
@PrefixGameTestTemplate(false)
public final class TimedConstructionGameTests {
    private record FarmFillFixture(Fixture f,BlueprintRecord farm,BlueprintRecord child,List<BlockPos> targets) {}
    private static FarmFillFixture farmFillFixture(GameTestHelper h,BuildingType type) {
        var f=fixture(h,true);var l=h.getLevel();var first=f.at().above(24);
        f.s().terrain().enabled=false;f.s().autoRoads().enabled=false;f.s().decorations().enabled=false;
        for(var other:f.s().citizens())if(other!=f.c())other.setManualAssignment(CitizenProfession.HERDSMAN,null);
        f.c().insertIntoInventory(new ItemStack(Items.DIRT,PitEscapeService.RESERVE));
        f.c().reservePitBlocks(PitEscapeService::ordinary,PitEscapeService.RESERVE);
        for(var p:BlockPos.betweenClosed(first.offset(-8,-1,-4),first.offset(15,-1,8))) {
            l.getChunkAt(p);l.setBlock(p,Blocks.DIRT.defaultBlockState(),18);
            for(int depth=1;depth<=12;depth++)l.setBlock(p.below(depth),Blocks.STONE.defaultBlockState(),18);
            for(int y=1;y<=5;y++)l.setBlock(p.above(y),Blocks.AIR.defaultBlockState(),18);
        }
        var result=com.hkin.exiledsettlement.region.ProductionRegionService.place(l,type,first,first.offset(14,0,6),Direction.NORTH,f.s().ownerId(),null);
        h.assertTrue(result.success(),"Farm fill fixture rejected: "+result.reason());var farm=result.value();var r=farm.region();
        while(!r.surveyed)r.survey(l,f.s()::contains,128);
        r.layoutReady=true;r.prepared=true;r.preparationCursor=r.cells.size();r.preparationRetries.clear();
        var steps=new ArrayList<TerrainProject.Step>();var targets=new ArrayList<BlockPos>();
        // Two real 12-block water columns, both more than four blocks from the worker's bank.
        for(int x:new int[]{10,12})for(int depth=12;depth>=1;depth--) {
            var p=first.offset(x,-depth,3);l.setBlock(p,Blocks.WATER.defaultBlockState(),18);targets.add(p);
            steps.add(new TerrainProject.Step(p,Blocks.DIRT.defaultBlockState(),Blocks.WATER.defaultBlockState()));
        }
        for(int x:new int[]{10,12})l.setBlock(first.offset(x,-13,3),Blocks.STONE.defaultBlockState(),18);
        var project=com.hkin.exiledsettlement.region.ProductionRegionService.publish(l,f.s(),farm.id(),"Farm fill area test",targets.getFirst(),steps);
        h.assertTrue(project!=null,"Farm fill child not published");
        f.c().setPreferredWorksite(farm.id());
        f.actor().moveTo(first.getX()-2.5,first.getY(),first.getZ()+3.5,0,0);f.actor().setOnGround(true);
        f.c().updateProjection(f.actor().getUUID(),f.actor().blockPosition());
        return new FarmFillFixture(f,farm,f.s().blueprint(project.id),targets);
    }
    private static int farmFillDirt(Fixture f){return carried(f,Items.DIRT)-f.c().pitReserve().getCount();}
    private static void startFarmFill(FarmFillFixture t) {
        t.f().c().startTask(CitizenWorkState.TO_BLUEPRINT,t.targets().getFirst(),null,t.child().id());
    }
    private static void advanceFarmFill(FarmFillFixture t) {
        var f=t.f();var l=f.h().getLevel();
        if(f.c().workState()==CitizenWorkState.IDLE && !t.child().terrainProject().completed) {
            var next=BlueprintService.nextUnclaimedPending(l,f.data(),t.child(),Set.of(),f.actor().blockPosition());
            if(next!=null)f.c().startTask(CitizenWorkState.TO_BLUEPRINT,next.placement().position(),null,t.child().id());
        }
        CitizenWorkService.advanceSiteConstruction(l,f.data(),f.s(),f.c(),f.actor());
    }
    private static void fillWholeFarmFromBank(GameTestHelper h,BuildingType type) {
        var t=farmFillFixture(h,type);var f=t.f();var l=h.getLevel();var project=t.child().terrainProject();
        f.c().receiveEquipment(new ItemStack(Items.IRON_SHOVEL));f.c().insertIntoInventory(new ItemStack(Items.DIRT,24));startFarmFill(t);
        h.assertTrue(!TimedConstructionService.columnArrived(l,f.actor(),t.targets().getFirst()),"Fixture still satisfies old per-column reach");
        h.assertTrue(TimedConstructionService.arrived(l,t.child(),f.actor(),t.targets().getFirst()),"Parent farm work area not used");
        var start=f.actor().position();boolean[] reloaded={false};
        h.onEachTick(()->{
            advanceFarmFill(t);
            if(!reloaded[0] && project.finished.cardinality()>=6) {
                var loaded=TerrainProject.load(project.save());t.child().withTerrainProject(loaded);f.s().terrain().index(loaded);
                t.farm().withRegion(com.hkin.exiledsettlement.region.ProductionRegion.load(t.farm().region().save()));reloaded[0]=true;
            }
        });
        h.startSequence().thenWaitUntil(()->h.assertTrue(t.child().terrainProject().completed,"Deep farm fill stalled: "+f.c().workFailure()+"/"+f.c().workState()+" finished="+t.child().terrainProject().finished.cardinality()+"/"+t.child().terrainProject().steps.size()+" dirt="+farmFillDirt(f)))
        .thenExecute(()->{
            h.assertTrue(reloaded[0] && t.targets().stream().allMatch(p->l.getBlockState(p).is(Blocks.DIRT)),"Deep columns not fully filled after state reload");
            h.assertTrue(farmFillDirt(f)==0 && f.c().inventory().stream().anyMatch(i->i.is(Items.IRON_SHOVEL)&&i.getDamageValue()==24),"Fill skipped exact material or tool wear");
            h.assertTrue(f.actor().position().equals(start) && f.s().blueprint(t.farm().id())!=null,"Worker chased columns or farm activated early");
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("FARM_FILL_AREA type={} depth=12 blocks=24 dirt=24 wear=24 moved={} stateReload={}",type,f.actor().position().distanceTo(start),reloaded[0]);f.clean();
        }).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="farm_fill_area",timeoutTicks=1000)
    public static void farmDeepFillUsesWholeWorksiteAndPaidReload(GameTestHelper h){fillWholeFarmFromBank(h,BuildingType.FARM_FIELD);}
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="farm_fill_area",timeoutTicks=1000)
    public static void melonDeepFillUsesWholeWorksiteAndPaidReload(GameTestHelper h){fillWholeFarmFromBank(h,BuildingType.MELON_FIELD);}
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="farm_fill_area")
    public static void farmFillKeepsMaterialToolAndProtectionGates(GameTestHelper h) {
        for(int mode=0;mode<3;mode++) {
            var t=farmFillFixture(h,BuildingType.FARM_FIELD);var f=t.f();var l=h.getLevel();
            if(mode!=0)f.c().receiveEquipment(new ItemStack(Items.IRON_SHOVEL));
            if(mode!=1)f.c().insertIntoInventory(new ItemStack(Items.DIRT,24));startFarmFill(t);
            boolean[] called={false};Consumer<BlockEvent.EntityPlaceEvent> deny=e->{if(t.targets().contains(e.getPos())){called[0]=true;e.setCanceled(true);}};
            if(mode==2)NeoForge.EVENT_BUS.addListener(deny);
            try {
                CitizenWorkService.advanceSiteConstruction(l,f.data(),f.s(),f.c(),f.actor());
                h.assertTrue(t.targets().stream().allMatch(p->l.getBlockState(p).is(Blocks.WATER)) && t.child().terrainProject().finished.isEmpty(),"Missing supply/protection changed the water or completed work: "+mode);
                h.assertTrue(farmFillDirt(f)==(mode==1?0:24) && f.c().inventory().stream().filter(i->i.is(Items.IRON_SHOVEL)).allMatch(i->i.getDamageValue()==0),"Failed fill charged materials/tool: "+mode);
                if(mode==2)h.assertTrue(called[0] && "PROTECTED_AREA".equals(f.c().workFailure()),"Protection event was not reached");
            }finally{if(mode==2)NeoForge.EVENT_BUS.unregister(deny);f.clean();}
        }h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="farm_fill_area")
    public static void farmFillDoesNotExpandUnrelatedWorkOrOutsideArrival(GameTestHelper h) {
        var t=farmFillFixture(h,BuildingType.FARM_FIELD);var f=t.f();var l=h.getLevel();var project=t.child().terrainProject();var target=t.targets().getFirst();
        h.assertTrue(TimedConstructionService.arrived(l,t.child(),f.actor(),target),"Own field did not expand fill range");
        project.publicGround=true;h.assertTrue(!TimedConstructionService.arrived(l,t.child(),f.actor(),target),"Public earthwork borrowed farm work area");project.publicGround=false;
        project.road=true;h.assertTrue(!TimedConstructionService.arrived(l,t.child(),f.actor(),target),"Road borrowed farm work area");project.road=false;
        project.regionOwner=null;h.assertTrue(TimedConstructionService.arrived(l,t.child(),f.actor(),target),"Saved dependency-only child lost parent work area");
        project.dependencies.clear();h.assertTrue(!TimedConstructionService.arrived(l,t.child(),f.actor(),target),"Unowned fill borrowed nearby farm");project.dependencies.add(t.farm().id());
        f.actor().moveTo(t.farm().region().first.getX()-7.5,t.farm().region().first.getY(),f.actor().getZ(),0,0);
        h.assertTrue(!TimedConstructionService.arrived(l,t.child(),f.actor(),target),"Outside worker counted as arrived");
        var face=TimedConstructionService.approach(l,t.child(),f.actor(),target);
        h.assertTrue(face!=null && TimedConstructionService.arrivedAt(l,t.child(),face,target),"Approach still searches around the distant water column");
        f.c().receiveEquipment(new ItemStack(Items.IRON_SHOVEL));f.c().insertIntoInventory(new ItemStack(Items.DIRT,24));startFarmFill(t);
        CitizenWorkService.advanceSiteConstruction(l,f.data(),f.s(),f.c(),f.actor());
        h.assertTrue(project.finished.isEmpty() && farmFillDirt(f)==24,"Outside worker built remotely");f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="farm_fill_walk",timeoutTicks=1200)
    public static void farmFillWorkerWalksIntoParentWorksiteBeforeFilling(GameTestHelper h) {
        var t=farmFillFixture(h,BuildingType.FARM_FIELD);var f=t.f();var l=h.getLevel();var first=t.farm().region().first;
        f.c().receiveEquipment(new ItemStack(Items.IRON_SHOVEL));f.c().insertIntoInventory(new ItemStack(Items.DIRT,24));
        f.actor().moveTo(first.getX()-7.5,first.getY(),first.getZ()+3.5,0,0);f.actor().setNoAi(false);
        h.assertTrue(l.addFreshEntity(f.actor()),"Fill worker not spawned");startFarmFill(t);var start=f.actor().position();
        h.onEachTick(()->advanceFarmFill(t));
        h.startSequence().thenWaitUntil(()->h.assertTrue(t.child().terrainProject().completed,"Worker did not walk to bank and complete fill: "+f.c().workFailure()+" pos="+f.actor().position()+" state="+f.c().workState()+" finished="+t.child().terrainProject().finished.cardinality()+"/"+t.child().terrainProject().steps.size()+" dirt="+farmFillDirt(f)))
        .thenExecute(()->{h.assertTrue(f.actor().position().distanceTo(start)>2 && !f.actor().isInWater() && farmFillDirt(f)==0,"Real arrival/material payment missing");
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("FARM_FILL_WALK moved={} blocks=24 water={}",f.actor().position().distanceTo(start),f.actor().isInWater());f.clean();}).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void completedBuildingRefreshesFencesAndGlassConnections(GameTestHelper h) {
        var f=fixture(h,true);var l=h.getLevel();var at=f.at();
        var goals=List.of(new BlueprintPlacement(at,Blocks.OAK_FENCE.defaultBlockState(),Items.OAK_FENCE),
                new BlueprintPlacement(at.east(),Blocks.OAK_FENCE.defaultBlockState(),Items.OAK_FENCE),
                new BlueprintPlacement(at.south(2),Blocks.GLASS_PANE.defaultBlockState(),Items.GLASS_PANE),
                new BlueprintPlacement(at.south(2).east(),Blocks.GLASS_PANE.defaultBlockState(),Items.GLASS_PANE));
        var site=f.building(goals);
        for(var p:goals)l.setBlock(p.position(),p.state(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        // Also refresh the adjoining existing fence outside this template.
        l.setBlock(at.west(),Blocks.OAK_FENCE.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        h.assertTrue(!l.getBlockState(at).getValue(FenceBlock.EAST),"Fixture already connected");
        var result=BlueprintService.finishTimedIfReady(l,f.data(),f.s(),site);
        h.assertTrue(result.success() && result.value()!=null && f.s().blueprint(site.id())==null,"Completion failed: "+result.reason());
        h.assertTrue(l.getBlockState(at).getValue(FenceBlock.EAST) && l.getBlockState(at.east()).getValue(FenceBlock.WEST)
                && l.getBlockState(at.west()).getValue(FenceBlock.EAST),"Fence connections not refreshed at completion");
        h.assertTrue(l.getBlockState(at.south(2)).getValue(IronBarsBlock.EAST) && l.getBlockState(at.south(2).east()).getValue(IronBarsBlock.WEST),"Glass panes not connected");
        f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void existingBuildingRefreshPreservesLiveStateAndMissingBlocks(GameTestHelper h) {
        var f=fixture(h,false);var l=h.getLevel();var at=f.at();
        var goal=new BlueprintPlacement(at,Blocks.OAK_FENCE.defaultBlockState(),Items.OAK_FENCE);
        var missing=new BlueprintPlacement(at.east(),Blocks.OAK_FENCE.defaultBlockState(),Items.OAK_FENCE);
        var template=CustomTemplateSnapshot.create("Connection repair",BuildingType.HOUSE,at,Direction.NORTH,List.of(goal,missing),List.of(new BlueprintEntrance(at,Direction.NORTH)));
        var b=f.s().addBuilding(BuildingType.HOUSE,at,Direction.NORTH,at,at.east(),0,1).withCustomTemplate(template);
        l.setBlock(at,Blocks.OAK_FENCE.defaultBlockState().setValue(FenceBlock.WATERLOGGED,true),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        l.setBlock(at.west(),Blocks.OAK_FENCE.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        com.hkin.exiledsettlement.building.BuildingFunctionService.validateSingle(l,f.data(),f.s(),b);
        h.assertTrue(l.getBlockState(at).getValue(FenceBlock.WEST) && l.getBlockState(at).getValue(FenceBlock.WATERLOGGED),"Refresh lost waterlogging or failed to connect existing fence");
        h.assertTrue(l.getBlockState(at.east()).isAir(),"Refresh rebuilt a removed block");
        var state=l.getBlockState(at);BlueprintBlockStateResolver.refreshCompleted(l,template.template());
        h.assertTrue(l.getBlockState(at).equals(state),"Repeated refresh changes an already correct state");f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void bridgeCompletionRefreshesRailings(GameTestHelper h) {
        var f=fixture(h,false);var l=h.getLevel();var at=f.at();
        var site=f.s().addBlueprint(BuildingType.HOUSE,at,at.north(3),Direction.NORTH,f.s().ownerId(),false);
        var p=new TerrainProject(site.id(),"Bridge finish",at,List.of(new TerrainProject.Step(at,Blocks.OAK_FENCE.defaultBlockState(),Blocks.AIR.defaultBlockState()),new TerrainProject.Step(at.east(),Blocks.OAK_FENCE.defaultBlockState(),Blocks.AIR.defaultBlockState())));
        p.bridge=true;site.withTerrainProject(p);
        for(int i=0;i<p.steps.size();i++){var step=p.steps.get(i);l.setBlock(step.position(),step.target(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);p.mark(i);}
        TerrainService.completed(l,f.s(),site);
        h.assertTrue(p.completed && l.getBlockState(at).getValue(FenceBlock.EAST) && l.getBlockState(at.east()).getValue(FenceBlock.WEST),"Completed bridge railings remain disconnected");f.clean();h.succeed();
    }
    private static com.hkin.exiledsettlement.building.BuildingRecord efficiencyFarm(Fixture f,boolean melon,List<BlockPos> cells) {
        var type=melon?BuildingType.MELON_FIELD:BuildingType.FARM_FIELD;var ground=f.at().below();
        var goals=new ArrayList<>(cells.stream().map(p->new BlueprintPlacement(p,Blocks.FARMLAND.defaultBlockState(),Items.DIRT)).toList());
        goals.add(new BlueprintPlacement(ground.offset(-5,0,-4),Blocks.STONE.defaultBlockState(),Items.STONE));
        goals.add(new BlueprintPlacement(ground.offset(16,3,5),Blocks.STONE.defaultBlockState(),Items.STONE));
        var farm=f.s().addBuilding(type,ground,Direction.NORTH,ground.offset(-5,0,-4),ground.offset(16,3,5),0,1);
        farm.withCustomTemplate(CustomTemplateSnapshot.create("Farm work area",type,ground,Direction.NORTH,goals,List.of(new BlueprintEntrance(ground.west(5).above(),Direction.WEST))));
        farm.updateFunction(true,com.hkin.exiledsettlement.building.BuildingStatus.OPERATIONAL,1,List.of());
        f.c().assignProfession(melon?CitizenProfession.MELON_FARMER:CitizenProfession.FARMER,farm.id());
        f.c().receiveEquipment(new ItemStack(Items.IRON_HOE));
        for(var cell:cells)f.h().getLevel().setBlock(cell,Blocks.DIRT.defaultBlockState(),18);
        return farm;
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="farm_work_area",timeoutTicks=260)
    public static void farmerWorksWholeAreaWithoutPerCropReachOrSight(GameTestHelper h) {
        var f=fixture(h,false);var l=h.getLevel();var first=f.at().below().east(8);var cells=new ArrayList<BlockPos>();
        for(int x=0;x<6;x++)for(int z=0;z<2;z++)cells.add(first.offset(x,0,z));
        efficiencyFarm(f,false,cells);f.c().insertIntoInventory(new ItemStack(Items.WHEAT_SEEDS,12));
        // A wall in the already entered work area must not become a per-crop interaction gate.
        for(int z=-3;z<=3;z++)for(int y=0;y<3;y++)l.setBlock(f.at().offset(3,y,z),Blocks.STONE.defaultBlockState(),18);
        f.c().startTask(CitizenWorkState.TO_FARM,first,null,null);var start=f.actor().position();long begun=l.getGameTime();
        h.onEachTick(()->{if(com.hkin.exiledsettlement.util.TickCadence.due(l.getGameTime(),f.c().id(),CitizenWorkService.WORK_INTERVAL_TICKS,0))invokeWork("farm",f);});
        h.startSequence().thenIdle(240).thenExecute(()->{
            long planted=cells.stream().filter(p->l.getBlockState(p.above()).is(Blocks.WHEAT)).count();
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("FARM_AREA_SAMPLE ticks={} planted={} seeds={} moved={}",l.getGameTime()-begun,planted,carried(f,Items.WHEAT_SEEDS),f.actor().position().distanceTo(start));
            h.assertTrue(planted==12 && carried(f,Items.WHEAT_SEEDS)==0,"Farm area did not finish its 12 real till/plant targets: "+planted);
            h.assertTrue(f.actor().position().equals(start),"Farmer still walked to individual crops");
            h.assertTrue(f.c().inventory().stream().anyMatch(i->i.is(Items.IRON_HOE)&&i.getDamageValue()==12),"Tilling skipped real hoe wear");f.clean();
        }).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="farm_work_area",timeoutTicks=120)
    public static void melonFarmerWorksFarCellAndHarvestsRealFruit(GameTestHelper h) {
        var f=fixture(h,false);var l=h.getLevel();var ground=f.at().below().east(12);
        efficiencyFarm(f,true,List.of(ground));f.c().insertIntoInventory(new ItemStack(Items.MELON_SEEDS));f.c().startTask(CitizenWorkState.TO_FARM,ground,null,null);
        h.startSequence().thenWaitUntil(()->{invokeWork("farm",f);h.assertTrue(l.getBlockState(ground.above()).is(Blocks.MELON_STEM),"Far melon stem not planted");})
        .thenExecute(()->{l.setBlock(ground.above(),Blocks.MELON.defaultBlockState(),18);f.c().clearWork();f.c().startTask(CitizenWorkState.TO_FARM,ground.above(),null,null);})
        .thenWaitUntil(()->{invokeWork("farm",f);h.assertTrue(l.getBlockState(ground.above()).isAir(),"Far fruit not harvested");})
        .thenExecute(()->{h.assertTrue(carried(f,Items.MELON_SEEDS)==0 && carried(f,Items.MELON_SLICE)>0,"Seed consumption or real fruit drops missing");f.clean();}).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="farm_work_area",timeoutTicks=120)
    public static void farmAreaStillRequiresArrivalAndHonorsProtection(GameTestHelper h) {
        var f=fixture(h,false);var l=h.getLevel();var ground=f.at().below();var farm=efficiencyFarm(f,false,List.of(ground));
        f.actor().moveTo(farm.minCorner().getX()-.5,f.at().getY(),f.at().getZ()+.5,0,0);f.c().startTask(CitizenWorkState.TO_FARM,ground,null,null);
        invokeWork("farm",f);h.assertTrue(l.getBlockState(ground).is(Blocks.DIRT),"Farmed remotely before arrival");
        var station=CitizenWorkService.findFarmWorkPosition(l,f.actor(),farm,ground);
        h.assertTrue(station!=null && station.getX()>farm.minCorner().getX() && station.getX()<farm.maxCorner().getX(),"Approach chose a station outside the farm");
        f.actor().moveTo(f.at().getX()+.5,f.at().getY(),f.at().getZ()+.5,0,0);f.c().clearWork();f.c().startTask(CitizenWorkState.TO_FARM,ground,null,null);
        Consumer<BlockEvent.EntityPlaceEvent> deny=e->{if(e.getPos().equals(ground))e.setCanceled(true);};NeoForge.EVENT_BUS.addListener(deny);
        h.startSequence().thenWaitUntil(()->{invokeWork("farm",f);h.assertTrue("PROTECTED_AREA".equals(f.c().workFailure()),"Protection not exercised on action cadence");})
        .thenExecute(()->{NeoForge.EVENT_BUS.unregister(deny);h.assertTrue(l.getBlockState(ground).is(Blocks.DIRT) && f.c().inventory().stream().anyMatch(i->i.is(Items.IRON_HOE)&&i.getDamageValue()==0),"Rejected till changed soil or tool");f.clean();}).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="farm_area_walk",timeoutTicks=360)
    public static void farmerActuallyEntersAreaBeforeWorking(GameTestHelper h) {
        var f=fixture(h,false);var l=h.getLevel();var ground=f.at().below().east(2);var farm=efficiencyFarm(f,false,List.of(ground));
        f.c().insertIntoInventory(new ItemStack(Items.WHEAT_SEEDS));
        f.actor().moveTo(f.at().getX()-6.5,f.at().getY(),f.at().getZ()+.5,0,0);f.actor().setNoAi(false);
        h.assertTrue(l.addFreshEntity(f.actor()),"Farmer not spawned");var start=f.actor().position();
        f.c().startTask(CitizenWorkState.TO_FARM,ground,null,null);
        h.onEachTick(()->{
            if(com.hkin.exiledsettlement.util.TickCadence.due(l.getGameTime(),f.c().id(),CitizenWorkService.WORK_INTERVAL_TICKS,0))invokeWork("farm",f);
        });
        h.startSequence().thenWaitUntil(()->h.assertTrue(l.getBlockState(ground.above()).is(Blocks.WHEAT),"Farmer did not enter field and plant: "+f.c().workFailure()+" pos="+f.actor().position()+" bounds="+farm.minCorner()+" state="+f.c().workState()+" ground="+l.getBlockState(ground)+" crop="+l.getBlockState(ground.above())+" target="+f.c().targetPosition()))
        .thenExecute(()->{h.assertTrue(f.actor().position().distanceTo(start)>1 && f.actor().getX()>farm.minCorner().getX()+1 && carried(f,Items.WHEAT_SEEDS)==0,"Arrival or seed payment missing");f.clean();}).thenSucceed();
    }
    private static boolean dispatch(Fixture f,CitizenRecord c,Set<BlockPos> claimed,boolean priority,boolean publicOnly) {
        if(c.workState()!=CitizenWorkState.IDLE)return false;
        try {
            var m=CitizenWorkService.class.getDeclaredMethod("assignBlueprint",net.minecraft.server.level.ServerLevel.class,
                    SettlementSavedData.class,SettlementRecord.class,CitizenRecord.class,Set.class,boolean.class,boolean.class,boolean.class);
            m.setAccessible(true);boolean result=(boolean)m.invoke(null,f.h().getLevel(),f.data(),f.s(),c,claimed,false,publicOnly,priority);
            if(result)WorkTaskService.reserve(f.s(),c);return result;
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    private static BlueprintRecord staffingSite(Fixture f,BlockPos at) {
        var goals=new ArrayList<BlueprintPlacement>();
        for(int x=0;x<4;x++)for(int z=0;z<4;z++)goals.add(new BlueprintPlacement(at.offset(x,0,z),Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS));
        return f.s().addBlueprint(BuildingType.WAREHOUSE,at,at.north(2),Direction.NORTH,f.s().ownerId(),false)
                .withCustomTemplate(CustomTemplateSnapshot.create("Staffing",BuildingType.WAREHOUSE,at,Direction.NORTH,goals,List.of(new BlueprintEntrance(at,Direction.NORTH))));
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void fallbackDispatchCannotFloodOneBuildingAndSpreadsToNext(GameTestHelper h) {
        var f=fixture(h,false);var first=staffingSite(f,f.at());
        while(f.s().citizens().size()<12)f.s().addMigrant();
        var claimed=new HashSet<BlockPos>();
        for(var c:f.s().citizens()) {
            c.assignProfession(CitizenProfession.WORKER,null);c.insertIntoInventory(new ItemStack(Items.OAK_PLANKS,32));
            dispatch(f,c,claimed,true,false);dispatch(f,c,claimed,false,false);dispatch(f,c,claimed,false,true);
        }
        h.assertTrue(WorkTaskService.assigned(f.s(),first.id())==2,"Fallback flooded sole building");
        var second=staffingSite(f,f.at().west(7));
        for(var c:f.s().citizens())if(c.workState()==CitizenWorkState.IDLE){dispatch(f,c,claimed,true,false);dispatch(f,c,claimed,false,false);}
        h.assertTrue(WorkTaskService.assigned(f.s(),first.id())==2 && WorkTaskService.assigned(f.s(),second.id())==2,"Second site did not receive its own workers");
        var extra=f.s().citizens().stream().filter(c->c.workState()==CitizenWorkState.IDLE).findFirst().orElseThrow();
        extra.setPreferredWorksite(first.id());extra.startTask(CitizenWorkState.TO_BLUEPRINT,f.at().east(3),null,first.id());WorkTaskService.reserve(f.s(),extra);
        h.assertTrue(WorkTaskService.withinBuildingCapacity(f.s(),extra),"Manual worker was rejected");
        f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void preparationAndSupplyShareParentSeats(GameTestHelper h) {
        var f=fixture(h,false);var site=staffingSite(f,f.at());var at=f.at().below();
        var child=f.s().addBlueprint(BuildingType.HOUSE,at,at.north(2),Direction.NORTH,f.s().ownerId(),false);
        var project=new TerrainProject(child.id(),"Foundation",at,List.of(new TerrainProject.Step(at,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));
        project.dependencies.add(site.id());child.withTerrainProject(project);f.s().terrain().projects.put(project.id,project);
        var people=f.s().citizens().stream().limit(3).toList();
        people.get(0).startTask(CitizenWorkState.TO_BLUEPRINT,f.at(),null,site.id());people.get(0).fetchConstructionMaterial(null);WorkTaskService.reserve(f.s(),people.get(0));
        people.get(1).startTask(CitizenWorkState.TO_EQUIPMENT,f.at().north(4),null,null);people.get(1).setConstructionResume(child.id(),at);WorkTaskService.reserve(f.s(),people.get(1));
        h.assertTrue(WorkTaskService.assigned(f.s(),site.id())==2 && !WorkTaskService.automaticSlotAvailable(f.s(),child,at),"Preparation/tool travel escaped parent cap");
        h.assertTrue(!WorkTaskService.automaticSlotAvailable(f.s(),site,f.at()),"Main structure ignored child reservation");
        var restored=CitizenRecord.load(people.get(1).save(h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(site.id().equals(WorkTaskService.task(f.s(),restored).mother()),"Saved tool trip lost its parent");
        h.assertTrue(WorkTaskService.automaticSlotAvailable(f.s(),child,f.at().east(10)),"Unrelated public work inherited building cap");
        f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void oldOverstaffingYieldsWithoutLosingCargoOrWork(GameTestHelper h) {
        var f=dropsFixture(h);var site=staffingSite(f,f.at());
        var ordered=f.s().citizens().stream().sorted(Comparator.comparing(CitizenRecord::id)).toList();
        for(var c:ordered){c.assignProfession(CitizenProfession.WORKER,null);c.insertIntoInventory(new ItemStack(Items.OAK_PLANKS,8));c.startTask(CitizenWorkState.TO_BLUEPRINT,f.at(),null,site.id());WorkTaskService.reserve(f.s(),c);}
        h.assertTrue(ordered.size()>2 && ordered.stream().filter(c->WorkTaskService.withinBuildingCapacity(f.s(),c)).count()==2,"Existing overstaffing was not reduced");
        var extra=ordered.getLast();var before=extra.inventory().stream().filter(i->i.is(Items.OAK_PLANKS)).mapToInt(ItemStack::getCount).sum();
        CitizenWorkService.advanceSiteConstruction(h.getLevel(),f.data(),f.s(),extra,f.actor());
        h.assertTrue(extra.workState()==CitizenWorkState.IDLE && extra.inventory().stream().filter(i->i.is(Items.OAK_PLANKS)).mapToInt(ItemStack::getCount).sum()==before,"Yield lost cargo or retained excess assignment");
        h.assertTrue(f.s().blueprint(site.id())==site && h.getLevel().getBlockState(f.at()).isAir(),"Yield cancelled order or forged construction");
        var active=ordered.getFirst();
        h.startSequence().thenIdle(10).thenExecute(()->{
            f.actor().getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,active.id().toString());
            CitizenWorkService.advanceSiteConstruction(h.getLevel(),f.data(),f.s(),active,f.actor());
            h.assertTrue(h.getLevel().getBlockState(f.at()).is(Blocks.OAK_PLANKS) && active.inventory().stream().filter(i->i.is(Items.OAK_PLANKS)).mapToInt(ItemStack::getCount).sum()==7,"Admitted worker did not perform paid construction: "+active.workFailure());
            f.clean();
        }).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void ordinaryBuildingAlsoIgnoresAutomaticDecorationBan(GameTestHelper h) {
        var f=dropsFixture(h);var l=h.getLevel();var at=f.at();
        var site=f.building(List.of(new BlueprintPlacement(at,Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS)));
        f.s().decorations().forbiddenChunks.add(new net.minecraft.world.level.ChunkPos(at).toLong());f.s().terrain().suppressed.add(at.asLong());
        f.c().insertIntoInventory(new ItemStack(Items.OAK_PLANKS));f.c().startTask(CitizenWorkState.TO_BLUEPRINT,at,null,site.id());
        CitizenWorkService.advanceSiteConstruction(l,f.data(),f.s(),f.c(),f.actor());
        h.assertTrue(l.getBlockState(at).is(Blocks.OAK_PLANKS) && carried(f,Items.OAK_PLANKS)==0,"Ordinary player building still obeys automatic no-build settings");
        h.assertTrue(f.s().decorations().forbidden(at) && f.s().terrain().suppressed.contains(at.asLong()),"Automatic settings were globally erased");
        f.clean();h.succeed();
    }
    private static void tallTree(net.minecraft.server.level.ServerLevel level,BlockPos root,int height) {
        level.setBlock(root.below(),Blocks.DIRT.defaultBlockState(),18);
        for(int y=0;y<height;y++)level.setBlock(root.above(y),Blocks.OAK_LOG.defaultBlockState(),18);
        level.setBlock(root.above(height),Blocks.OAK_LEAVES.defaultBlockState(),18);
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="whole_tree")
    public static void everyProfessionFellsOneWholeTallTree(GameTestHelper h) {
        var f=dropsFixture(h);var l=h.getLevel();var root=f.at();int count=0;
        for(var profession:CitizenProfession.values()) {
            f.c().assignProfession(profession,null);tallTree(l,root,16);
            h.assertTrue(ResidentBlockDrops.destroy(l,root,f.actor()),"Tree rejected for "+profession);
            count+=16;
            for(int y=0;y<=16;y++)h.assertTrue(l.getBlockState(root.above(y)).isAir(),"High tree remains for "+profession);
            h.assertTrue(carried(f,Items.OAK_LOG)==count,"Whole-tree cargo lost/duplicated for "+profession);
        }
        h.assertTrue(groundLoot(f,Items.OAK_LOG)==0,"Logs left on ground");f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="whole_tree")
    public static void wholeTreeProtectionRollsBackBeforeLoot(GameTestHelper h) {
        var f=dropsFixture(h);var l=h.getLevel();var root=f.at();tallTree(l,root,12);
        Consumer<BlockEvent.EntityPlaceEvent> deny=e->{if(e.getPos().equals(root.above(6)))e.setCanceled(true);};NeoForge.EVENT_BUS.addListener(deny);
        try {h.assertTrue(!ResidentBlockDrops.destroy(l,root,f.actor()),"Protection ignored");}
        finally{NeoForge.EVENT_BUS.unregister(deny);}
        for(int y=0;y<12;y++)h.assertTrue(l.getBlockState(root.above(y)).is(Blocks.OAK_LOG),"Partial protected tree cut");
        h.assertTrue(l.getBlockState(root.above(12)).is(Blocks.OAK_LEAVES) && carried(f,Items.OAK_LOG)==0 && groundLoot(f,Items.OAK_LOG)==0,"Failed felling created drops");
        f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="whole_tree")
    public static void wholeTreeKeepsNeighbourAndFormalBuilding(GameTestHelper h) {
        var f=dropsFixture(h);var l=h.getLevel();var root=f.at();var other=root.east(6);
        tallTree(l,root,8);tallTree(l,other,8);
        for(int x=1;x<6;x++)l.setBlock(root.offset(x,8,0),Blocks.OAK_LEAVES.defaultBlockState(),18);
        var persistent=root.offset(0,8,1);l.setBlock(persistent,Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true),18);
        h.assertTrue(ResidentBlockDrops.destroy(l,root,f.actor()),"Own tree failed");
        for(int y=0;y<8;y++)h.assertTrue(l.getBlockState(other.above(y)).is(Blocks.OAK_LOG),"Neighbour trunk removed");
        h.assertTrue(l.getBlockState(other.above(8)).is(Blocks.OAK_LEAVES) && l.getBlockState(persistent).is(Blocks.OAK_LEAVES),"Neighbour/persistent foliage removed");
        var building=f.s().addBuilding(BuildingType.HOUSE,other,Direction.NORTH,other,other.above(10),0,0);
        h.assertTrue(!ResidentBlockDrops.destroy(l,other,f.actor()) && l.getBlockState(other).is(Blocks.OAK_LOG),"Formal building timber felled");
        f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="whole_tree",timeoutTicks=160)
    public static void workerClearsHighTreeFromGroundAndSavesProgress(GameTestHelper h) {
        var f=dropsFixture(h);var l=h.getLevel();var root=f.at();tallTree(l,root,20);var high=root.above(19);
        var site=f.s().addBlueprint(BuildingType.HOUSE,root,root.north(4),Direction.NORTH,f.s().ownerId(),false);
        var plan=new TerrainProject(site.id(),"High tree clearing",root,List.of(new TerrainProject.Step(high,Blocks.AIR.defaultBlockState(),Blocks.OAK_LOG.defaultBlockState())));
        site.withTerrainProject(plan);f.s().terrain().projects.put(plan.id,plan);f.s().terrain().index(plan);
        f.c().insertIntoInventory(new ItemStack(Items.IRON_AXE));f.c().startTask(CitizenWorkState.TO_BLUEPRINT,high,null,site.id());
        var start=f.actor().position();
        h.startSequence().thenWaitUntil(()->{
            CitizenWorkService.advanceSiteConstruction(l,f.data(),f.s(),f.c(),f.actor());
            h.assertTrue(l.getBlockState(root).isAir(),"Tree clearance has not advanced: "+f.c().workFailure());
        }).thenExecute(()->{
            for(int y=0;y<=20;y++)h.assertTrue(l.getBlockState(root.above(y)).isAir(),"High crown left behind");
            h.assertTrue(carried(f,Items.OAK_LOG)==20 && f.actor().position().equals(start) && f.c().workerAccess.block==null,"Ground worker climbed or cargo differed");
            h.assertTrue(plan.finished.get(0) && TerrainProject.load(plan.save()).finished.get(0),"Real tree work not saved");
            h.assertTrue(f.c().inventory().stream().anyMatch(s->s.is(Items.IRON_AXE)&&s.getDamageValue()==1),"Tree action tool wear missing");
            f.clean();
        }).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="whole_tree")
    public static void timedReplacementClearsWholeTreeAndPreservesNewBlock(GameTestHelper h) {
        var f=dropsFixture(h);var l=h.getLevel();var root=f.at();tallTree(l,root,10);
        h.assertTrue(TimedConstructionService.put(l,root,Blocks.OAK_PLANKS.defaultBlockState(),f.actor()),"Timed replacement failed");
        h.assertTrue(l.getBlockState(root).is(Blocks.OAK_PLANKS) && carried(f,Items.OAK_LOG)==10,"Replacement or drops lost");
        for(int y=1;y<=10;y++)h.assertTrue(l.getBlockState(root.above(y)).isAir(),"Replacement left upper tree");
        f.clean();h.succeed();
    }
    private static Fixture dropsFixture(GameTestHelper h) {
        var f=fixture(h,true);
        f.actor().getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,f.s().id().toString());
        f.actor().getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,f.c().id().toString());
        return f;
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="whole_tree_walk",timeoutTicks=400)
    public static void workerWalksOnGroundBeforeWholeTreeClearance(GameTestHelper h) {
        var f=dropsFixture(h);var l=h.getLevel();var root=f.at();tallTree(l,root,24);var high=root.above(23);
        var site=f.s().addBlueprint(BuildingType.HOUSE,root,root.north(4),Direction.NORTH,f.s().ownerId(),false);
        var plan=new TerrainProject(site.id(),"Walking high tree clearing",root,List.of(new TerrainProject.Step(high,Blocks.AIR.defaultBlockState(),Blocks.OAK_LOG.defaultBlockState())));
        site.withTerrainProject(plan);f.s().terrain().projects.put(plan.id,plan);f.s().terrain().index(plan);
        f.c().insertIntoInventory(new ItemStack(Items.IRON_AXE));f.c().startTask(CitizenWorkState.TO_BLUEPRINT,high,null,site.id());
        f.actor().moveTo(root.getX()-7.5,root.getY(),root.getZ()+.5,0,0);var start=f.actor().position();
        f.actor().setNoAi(false);l.addFreshEntity(f.actor());f.c().updateProjection(f.actor().getUUID(),f.actor().blockPosition());
        CitizenWorkService.advanceSiteConstruction(l,f.data(),f.s(),f.c(),f.actor());
        h.assertTrue(l.getBlockState(root).is(Blocks.OAK_LOG),"Remote clearance before ground arrival");
        h.startSequence().thenWaitUntil(()->{
            CitizenWorkService.advanceSiteConstruction(l,f.data(),f.s(),f.c(),f.actor());
            var destination=f.actor().getNavigation().getTargetPos();
            h.assertTrue(destination==null || destination.getY()<=root.getY()+1,"Worker navigates toward canopy: "+destination);
            h.assertTrue(f.actor().getY()<=root.getY()+1,"Worker climbed toward canopy");
            h.assertTrue(l.getBlockState(root).isAir(),"Waiting for physical ground arrival: "+f.c().workFailure());
        }).thenExecute(()->{
            h.assertTrue(f.actor().position().distanceTo(start)>1 && carried(f,Items.OAK_LOG)==24 && plan.finished.get(0),"No actual walk, complete tree or cargo");
            f.clean();
        }).thenSucceed();
    }
    private static int carried(Fixture f,net.minecraft.world.item.Item item) {
        return f.c().inventory().stream().filter(s->s.is(item)).mapToInt(ItemStack::getCount).sum();
    }
    private static int groundLoot(Fixture f,net.minecraft.world.item.Item item) {
        return f.h().getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(f.at()).inflate(8)).stream().filter(e->e.getItem().is(item)).mapToInt(e->e.getItem().getCount()).sum();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="direct_drops")
    public static void residentDropsEnterBackpackWithoutCollectingExistingItems(GameTestHelper h) {
        var f=dropsFixture(h);var l=h.getLevel();var p=f.at();
        var old=new net.minecraft.world.entity.item.ItemEntity(l,p.getX()+.5,p.getY()+.5,p.getZ()+.5,new ItemStack(Items.DIAMOND,3));l.addFreshEntity(old);
        l.setBlock(p,Blocks.DIRT.defaultBlockState(),2);
        h.assertTrue(TimedConstructionService.put(l,p,Blocks.AIR.defaultBlockState(),f.actor()),"Timed break failed");
        h.assertTrue(carried(f,Items.DIRT)==1 && groundLoot(f,Items.DIRT)==0,"Timed replacement did not directly insert one real drop");
        h.assertTrue(carried(f,Items.DIAMOND)==0 && old.isAlive(),"Existing ground loot was vacuumed up");
        l.setBlock(p,Blocks.OAK_LOG.defaultBlockState(),2);
        h.assertTrue(ResidentBlockDrops.destroy(l,p,f.actor()) && carried(f,Items.OAK_LOG)==1,"Cleanup destruction did not insert log");
        var loaded=CitizenRecord.load(f.c().save(l.registryAccess()),l.registryAccess());
        h.assertTrue(loaded.inventory().stream().filter(s->s.is(Items.DIRT)||s.is(Items.OAK_LOG)).mapToInt(ItemStack::getCount).sum()==2,"Saved cargo changed");
        old.discard();f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="direct_drops")
    public static void containerClearanceRoutesContentsAndDoesNotDuplicateShell(GameTestHelper h) {
        var f=dropsFixture(h);var l=h.getLevel();var p=f.at();l.setBlock(p,Blocks.CHEST.defaultBlockState(),2);
        var box=(net.minecraft.world.Container)l.getBlockEntity(p);var named=new ItemStack(Items.DIAMOND,9);
        named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Preserved cargo"));box.setItem(0,named);
        Consumer<BlockEvent.EntityPlaceEvent> deny=e->{if(e.getPos().equals(p))e.setCanceled(true);};NeoForge.EVENT_BUS.addListener(deny);
        try {h.assertTrue(!TimedConstructionService.put(l,p,Blocks.AIR.defaultBlockState(),f.actor()),"Protected container was removed");}
        finally {NeoForge.EVENT_BUS.unregister(deny);}
        h.assertTrue(l.getBlockState(p).is(Blocks.CHEST) && ((net.minecraft.world.Container)l.getBlockEntity(p)).getItem(0).getCount()==9
                && carried(f,Items.DIAMOND)==0 && groundLoot(f,Items.DIAMOND)==0,"Container rollback duplicated or lost contents");
        var result=SiteClearance.clear(l,p,f.actor(),ItemStack.EMPTY,at->true);
        h.assertTrue(result.failure().isEmpty(),"Clearance failed");
        for(var drop:result.drops())h.assertTrue(f.c().insertIntoInventory(drop).isEmpty(),"Unexpected overflow");
        h.assertTrue(carried(f,Items.CHEST)==1 && carried(f,Items.DIAMOND)==9 && groundLoot(f,Items.DIAMOND)==0,"Chest shell/contents duplicated or left on ground");
        h.assertTrue(f.c().inventory().stream().anyMatch(s->ItemStack.isSameItemSameComponents(s,named)),"Item components changed");
        f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="direct_drops")
    public static void protectedBreakKeepsWorldAndOverflowRemainsReal(GameTestHelper h) {
        var f=dropsFixture(h);var l=h.getLevel();var p=f.at();
        h.startSequence().thenIdle(5).thenExecute(()->{
        l.setBlock(p,Blocks.DIRT.defaultBlockState(),2);
        Consumer<BlockEvent.EntityPlaceEvent> deny=e->{if(e.getPos().equals(p))e.setCanceled(true);};NeoForge.EVENT_BUS.addListener(deny);
        try {h.assertTrue(!TimedConstructionService.put(l,p,Blocks.AIR.defaultBlockState(),f.actor()),"Protection ignored");}
        finally {NeoForge.EVENT_BUS.unregister(deny);}
        h.assertTrue(l.getBlockState(p).is(Blocks.DIRT) && carried(f,Items.DIRT)==0 && groundLoot(f,Items.DIRT)==0,"Rejected destruction produced loot");
        // The independent resident tick may already have reserved the empty egress slot.
        // Fill all ordinary cargo capacity, retaining that real reservation.
        int capacity=f.c().inventoryCapacityFor(new ItemStack(Items.COBBLESTONE));
        h.assertTrue(capacity>=35*64 && capacity<=36*64,"Unexpected occupied fixture inventory");
        h.assertTrue(f.c().insertIntoInventory(new ItemStack(Items.COBBLESTONE,capacity)).isEmpty() && !f.c().hasInventoryCapacity(),"Overflow fixture has ordinary cargo space");
        h.assertTrue(TimedConstructionService.put(l,p,Blocks.AIR.defaultBlockState(),f.actor()),"Overflow fallback break failed");
        h.assertTrue(carried(f,Items.DIRT)==0 && groundLoot(f,Items.DIRT)==1 && carried(f,Items.COBBLESTONE)==capacity,"Overflow was lost, duplicated or expanded inventory: dirt="+carried(f,Items.DIRT)+" ground="+groundLoot(f,Items.DIRT)+" cobble="+carried(f,Items.COBBLESTONE));
        f.clean();}).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="direct_drops")
    public static void nearlyFullWorkerStopsBreakingWithoutWarehouse(GameTestHelper h) {
        var f=dropsFixture(h);var l=h.getLevel();var p=f.at();
        var site=f.building(List.of(new BlueprintPlacement(p,Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS)));
        f.c().insertIntoInventory(new ItemStack(Items.COBBLESTONE,30*64));
        f.c().startTask(CitizenWorkState.TO_BLUEPRINT,p,null,site.id());l.setBlock(p,Blocks.DIRT.defaultBlockState(),2);
        h.assertTrue(CitizenWorkService.pauseForCargoDelivery(l,f.data(),f.s(),f.c(),f.actor()),"Near-full bag continued without storage");
        CitizenWorkService.advanceSiteConstruction(l,f.data(),f.s(),f.c(),f.actor());
        h.assertTrue(l.getBlockState(p).is(Blocks.DIRT) && f.s().blueprint(site.id())!=null && carried(f,Items.COBBLESTONE)==30*64 && !f.c().workFailure().isEmpty(),"Storage blocker changed block, cargo or order");
        f.c().assignProfession(CitizenProfession.LUMBERJACK,null);
        h.assertTrue(CitizenWorkService.farmerDeliveryReady(l,f.s(),f.c()),"Nonworker waited for a completely full bag");
        f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="direct_delivery",timeoutTicks=800)
    public static void nearFullResidentWalksToWarehouseAndUnloadsWholeCargo(GameTestHelper h) {
        var f=dropsFixture(h);var l=h.getLevel();var p=f.at();
        var warehouse=f.s().addBuilding(BuildingType.WAREHOUSE,p.east(4),Direction.WEST,p.offset(1,0,-2),p.offset(7,3,2),0,1);
        warehouse.updateFunction(true,com.hkin.exiledsettlement.building.BuildingStatus.OPERATIONAL,1,List.of());
        var stock=com.hkin.exiledsettlement.building.WarehouseInventoryService.stock(l,warehouse);
        f.c().insertIntoInventory(new ItemStack(Items.RAW_IRON,30*64));
        f.actor().moveTo(p.getX()-5.5,p.getY(),p.getZ()+.5,0,0);var start=f.actor().position();
        boolean[] started={false};boolean[] arrived={false};
        h.startSequence().thenIdle(5).thenExecute(()->{
            f.actor().setNoAi(false);l.addFreshEntity(f.actor());f.c().updateProjection(f.actor().getUUID(),f.actor().blockPosition());
            f.c().clearWork();l.setDayTime(6000);
            h.assertTrue(CitizenWorkService.pauseForCargoDelivery(l,f.data(),f.s(),f.c(),f.actor()),"No early delivery state="+f.c().workState()+" empty="+f.c().inventory().stream().filter(ItemStack::isEmpty).count()+" cargo="+carried(f,Items.RAW_IRON));started[0]=true;
            h.assertTrue(com.hkin.exiledsettlement.building.WarehouseInventoryService.countItem(l,warehouse,Items.RAW_IRON)==0,"Remote deposit before arrival");
        });
        h.onEachTick(()->{
            if(!started[0])return;
            if(l.getGameTime()%100==0)com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("DIRECT_DELIVERY_PROGRESS state={} failure={} pos={} alive={} tick={} warehouse={} cargo={}",f.c().workState(),f.c().workFailure(),f.actor().position(),f.actor().isAlive(),f.actor().tickCount,warehouse.status(),carried(f,Items.RAW_IRON));
            if(f.c().workState()==CitizenWorkState.TO_WAREHOUSE && l.getGameTime()%8==0)invokeWork("deliverWarehouse",f);
            int stored=com.hkin.exiledsettlement.building.WarehouseInventoryService.countItem(l,warehouse,Items.RAW_IRON);
            if(stored>0)arrived[0]=true;
            if(stored>0 && stored<30*64) {
                var loaded=CitizenRecord.load(f.c().save(l.registryAccess()),l.registryAccess());
                h.assertTrue(loaded.workState()==CitizenWorkState.TO_WAREHOUSE && warehouse.id().equals(loaded.targetBuildingId())
                        && loaded.inventory().stream().filter(s->s.is(Items.RAW_IRON)).mapToInt(ItemStack::getCount).sum()==carried(f,Items.RAW_IRON),"Saved partial delivery lost its destination or cargo");
            }
            h.assertTrue(stored+carried(f,Items.RAW_IRON)==30*64,"Transport lost or duplicated cargo");
            if(stored==30*64) {
                h.assertTrue(arrived[0] && f.actor().position().distanceToSqr(start)>9 && f.c().workState()!=CitizenWorkState.TO_WAREHOUSE,"Delivery did not physically finish");
                com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("DIRECT_DELIVERY stored={} moved={} state={}",stored,f.actor().position().distanceTo(start),f.c().workState());
                f.clean();h.succeed();
            }
        });
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="direct_drops")
    public static void pairedDoorCleanupAwardsOnlyOneDoor(GameTestHelper h) {
        var f=dropsFixture(h);var l=h.getLevel();var p=f.at();
        var lower=Blocks.OAK_DOOR.defaultBlockState();var upper=lower.setValue(net.minecraft.world.level.block.DoorBlock.HALF,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
        var site=f.building(List.of(new BlueprintPlacement(p,lower,Items.OAK_DOOR),new BlueprintPlacement(p.above(),upper,Items.OAK_DOOR)));
        l.setBlock(p,lower,18);l.setBlock(p.above(),upper,18);
        h.assertTrue(BlueprintPlacementGroups.remove(l,BlueprintTemplates.create(site),BlueprintTemplates.create(site).placementAt(p),f.actor()),"Paired removal failed");
        h.assertTrue(l.getBlockState(p).isAir() && l.getBlockState(p.above()).isAir() && carried(f,Items.OAK_DOOR)==1 && groundLoot(f,Items.OAK_DOOR)==0,"Door halves duplicated loot or stayed on ground");
        f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="direct_drops")
    public static void fullAndInaccessibleWarehousePreserveCargoAndPendingWork(GameTestHelper h) {
        var f=dropsFixture(h);var l=h.getLevel();var p=f.at();
        var warehouse=f.s().addBuilding(BuildingType.WAREHOUSE,p.east(4),Direction.WEST,p.offset(1,0,-2),p.offset(7,3,2),0,1);
        warehouse.updateFunction(true,com.hkin.exiledsettlement.building.BuildingStatus.OPERATIONAL,1,List.of());
        var stock=com.hkin.exiledsettlement.building.WarehouseInventoryService.stock(l,warehouse);stock.insert(new ItemStack(Items.DIRT,200000));
        f.c().insertIntoInventory(new ItemStack(Items.RAW_IRON,30*64));
        var site=f.building(List.of(new BlueprintPlacement(p,Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS)));
        f.c().startTask(CitizenWorkState.TO_BLUEPRINT,p,null,site.id());
        h.assertTrue(CitizenWorkService.pauseForCargoDelivery(l,f.data(),f.s(),f.c(),f.actor()) && f.c().workState()==CitizenWorkState.TO_BLUEPRINT,"Full warehouse did not pause pending work");
        h.assertTrue(carried(f,Items.RAW_IRON)==1920 && f.s().blueprint(site.id())!=null,"Full warehouse discarded cargo/order");
        stock.clearContent();
        for(var at:BlockPos.betweenClosed(p.offset(1,0,-2),p.offset(7,3,2)))l.setBlock(at,Blocks.STONE.defaultBlockState(),2);
        CitizenWorkService.pauseForCargoDelivery(l,f.data(),f.s(),f.c(),f.actor());invokeWork("deliverWarehouse",f);
        h.assertTrue(f.c().workState()==CitizenWorkState.TO_WAREHOUSE && carried(f,Items.RAW_IRON)==1920
                && com.hkin.exiledsettlement.building.WarehouseInventoryService.countItem(l,warehouse,Items.RAW_IRON)==0 && f.s().blueprint(site.id())!=null,"Inaccessible warehouse remotely transferred goods or removed work");
        f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void targetedQueriesAndDispatchCountsStayFresh(GameTestHelper h) {
        var f=fixture(h,false);var l=h.getLevel();var at=f.at();
        var site=f.building(List.of(new BlueprintPlacement(at,Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS),
                new BlueprintPlacement(at.east(),Blocks.STONE.defaultBlockState(),Items.STONE)));
        for(var p:TimedConstructionService.pending(l,site,Set.of()))h.assertTrue(TimedConstructionService.at(l,site,p.placement().position()).equals(p),"Targeted query differs from full current-world query");
        l.setBlock(at,Blocks.OAK_PLANKS.defaultBlockState(),2);h.assertTrue(TimedConstructionService.at(l,site,at)==null,"Placed goal stayed pending");
        l.setBlock(at,Blocks.AIR.defaultBlockState(),2);h.assertTrue(TimedConstructionService.at(l,site,at)!=null,"Broken goal was hidden by cache");
        f.c().startTask(CitizenWorkState.TO_BLUEPRINT,at,null,site.id());WorkTaskService.reserve(f.s(),f.c());
        h.assertTrue(WorkTaskService.assigned(f.s(),site.id())==1 && WorkTaskService.counts(f.s()).get(site.id())==1,"Fast dispatch ownership disagrees with full task");
        f.c().clearWork();h.assertTrue(WorkTaskService.assigned(f.s(),site.id())==0,"Released worker still counted in same tick");
        f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void fillApproachCannotSelectDistantHighLedge(GameTestHelper h) {
        var f=fixture(h,false);var l=h.getLevel();var target=f.at();
        var site=f.s().addBlueprint(BuildingType.HOUSE,target,f.at().north(6),Direction.NORTH,f.s().ownerId(),false);
        site.withTerrainProject(new TerrainProject(site.id(),"Reach regression",target,List.of(new TerrainProject.Step(target,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()))));
        // The near ledge is standable but eleven blocks above the target column's surface.
        for(var p:BlockPos.betweenClosed(target.offset(-3,10,-3),target.offset(3,10,3)))if(p.getX()!=target.getX() || p.getZ()!=target.getZ())l.setBlock(p,Blocks.STONE.defaultBlockState(),2);
        var ledge=target.offset(2,11,0);f.actor().moveTo(ledge.getX()+.5,ledge.getY(),ledge.getZ()+.5,0,0);
        h.assertTrue(!TimedConstructionService.arrived(l,site,f.actor(),target),"High ledge counted as actual arrival");
        var face=TimedConstructionService.approach(l,site,f.actor(),target);
        h.assertTrue(face==null || TimedConstructionService.arrivedAt(l,site,face,target),"Navigation selected a standable but unusable workface: "+face);
        h.assertTrue(l.getBlockState(target).isAir() && !site.terrainProject().finished.get(0),"Out-of-range query changed world or work progress");
        f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="planning_speed",timeoutTicks=4000)
    public static void planningPublishesRealOrdersWithinNight(GameTestHelper h) {
        planningPublishesRealOrders(h,13000);
    }
    public static void planningPublishesRealOrders(GameTestHelper h,long dayTime) {
        var f=fixture(h,false);var level=h.getLevel();var at=f.at();
        // A new isolated scene; no resident work, debug completion or accelerated world clock.
        for(var p:BlockPos.betweenClosed(at.offset(-26,-2,-26),at.offset(26,-2,26))) {
            var chunk=new ChunkPos(p);level.setChunkForced(chunk.x,chunk.z,true);
            level.getChunkAt(p);level.setBlock(p,Blocks.STONE.defaultBlockState(),2);
            level.setBlock(p.above(),Blocks.DIRT.defaultBlockState(),2);
            for(int y=2;y<=7;y++)level.setBlock(p.above(y),Blocks.AIR.defaultBlockState(),2);
            if(Math.floorMod(p.getX()+p.getZ(),7)==0)level.setBlock(p.above(2),Blocks.DIRT.defaultBlockState(),2);
        }
        f.s().terrain().enabled=true;f.s().autoRoads().enabled=true;f.s().autoRoads().workReady=true;
        for(int i=0;i<17;i++) {
            var line=new ArrayList<BlockPos>();int z=-24+i*3;
            for(int x=-24;x<=24;x++)line.add(at.offset(x,-1,z));
            var route=new AutoRoadState.Route(UUID.randomUUID(),line.getLast(),line);
            route.centerline=List.copyOf(line);route.width=3;f.s().autoRoads().routes.put("speed-"+i,route);
        }
        level.setDayTime(dayTime);long started=level.getGameTime();var phaseTicks=new TreeMap<Integer,Integer>();long[] firstOrder={-1};
        var phaseNanos=new TreeMap<Integer,ArrayList<Long>>();
        h.onEachTick(()->{
            var w=f.s().terrain().publicGround;phaseTicks.merge(w.phase,1,Integer::sum);
            int measuredPhase=w.phase;long before=System.nanoTime();PublicGroundService.tick(level,f.s());
            if(PublicGroundService.planningTickDue(level.getGameTime(),measuredPhase))phaseNanos.computeIfAbsent(measuredPhase,k->new ArrayList<>()).add(System.nanoTime()-before);
            int pending=OptionalWorkBudget.snapshot(f.s()).pending();
            if(pending>0 && firstOrder[0]<0)firstOrder[0]=level.getGameTime()-started;
            if(pending>=256 || w.phase==0 && !f.s().terrain().projects.isEmpty()) {
                com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("PLANNING_SPEED ticks={} firstOrder={} pending={} phaseTicks={} status={}",level.getGameTime()-started,firstOrder[0],pending,phaseTicks,w.status);
                phaseNanos.forEach((phase,times)->{times.sort(Long::compare);com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("PLANNING_COST phase={} count={} totalMs={} p95Ms={} maxMs={}",phase,times.size(),times.stream().mapToLong(Long::longValue).sum()/1e6,times.get((int)Math.floor((times.size()-1)*.95))/1e6,times.getLast()/1e6);});
                // Every-tick bounded sampling must reduce actual release latency,
                // not merely show a faster UI cursor. Cold initialization is included.
                h.assertTrue(firstOrder[0]<600 && level.getGameTime()-started<900,"Bounded planning failed to publish first work within 30s / 256 targets within 45s");
                h.assertTrue(pending<=TerrainConfig.OPTIONAL_PENDING_BLOCKS.get(),"Fast publication exceeded pending quota");
                h.assertTrue(level.getBlockState(at.east(24).below()).is(Blocks.DIRT),"Planner edited the world rather than publishing resident orders");
                f.clean();for(var p:BlockPos.betweenClosed(at.offset(-26,0,-26),at.offset(26,0,26))){var chunk=new ChunkPos(p);level.setChunkForced(chunk.x,chunk.z,false);}h.succeed();
            }
            if(level.getGameTime()-started==3999)com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("PLANNING_SPEED stalled pending={} phaseTicks={} status={}",pending,phaseTicks,w.status);
        });
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",batch="planning_math")
    public static void fastPlanningPreservesSlopeAndSavedProgress(GameTestHelper h) {
        var checks=com.google.gson.JsonParser.parseString(PublicGradeChecks.run()).getAsJsonObject();
        h.assertTrue(checks.get("passed").getAsBoolean(),"Production grade geometry or save/reload regression: "+checks);
        h.assertTrue(PublicGroundService.planningTickDue(1,14) && PublicGroundService.planningTickDue(1,6)
                && PublicGroundService.planningTickDue(1,4) && PublicGroundService.planningTickDue(1,5),"Active planning still waits for a ten-tick cadence");
        com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("PLANNING_MATH {}",checks);
        h.succeed();
    }
    private static int serial;
    private record Fixture(GameTestHelper h, SettlementSavedData data, SettlementRecord s,
            BlockPos at, CitizenRecord c, SettlementCitizenEntity actor) {
        void clean(){s.deactivate();actor.discard();for(var p:BlockPos.betweenClosed(at.offset(-8,0,-8),at.offset(8,0,8))){var chunk=new ChunkPos(p);h.getLevel().setChunkForced(chunk.x,chunk.z,false);}}
        BlueprintRecord building(List<BlueprintPlacement> goals) {
            var site=s.addBlueprint(BuildingType.HOUSE,at,at.north(3),Direction.NORTH,s.ownerId(),false);
            site.withCustomTemplate(CustomTemplateSnapshot.create("Timed fixture",BuildingType.HOUSE,at,Direction.NORTH,
                    goals,List.of(new BlueprintEntrance(goals.getFirst().position(),Direction.NORTH))));return site;
        }
    }
    private static Fixture fixture(GameTestHelper h, boolean persisted) {
        var level=h.getLevel();var at=h.absolutePos(new BlockPos(80000+(serial++)*512,3,0));
        level.getChunkAt(at);
        for(var p:BlockPos.betweenClosed(at.offset(-8,-1,-8),at.offset(8,-1,8))) {
            var chunk=new ChunkPos(p);level.setChunkForced(chunk.x,chunk.z,true);
            level.getChunkAt(p);level.setBlock(p,Blocks.STONE.defaultBlockState(),2);
            for(int y=1;y<=5;y++)level.setBlock(p.above(y),Blocks.AIR.defaultBlockState(),2);
        }
        var data=persisted?SettlementSavedData.get(level):new SettlementSavedData();
        var s=data.createOrRecover(at,UUID.randomUUID(),"Timed test").value();
        h.assertTrue(s!=null,"Fixture settlement overlap");
        var c=s.citizens().iterator().next();c.assignProfession(CitizenProfession.WORKER,null);
        var actor=ModEntities.SETTLEMENT_CITIZEN.get().create(level);h.assertTrue(actor!=null,"Missing resident entity");
        actor.setNoAi(true);actor.moveTo(at.getX()-2.5,at.getY(),at.getZ()+.5,0,0);actor.setOnGround(true);
        level.setDayTime(6000);return new Fixture(h,data,s,at,c,actor);
    }

    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void mutuallySealedGapsAreBuiltWithExactMaterialCost(GameTestHelper h) {
        var f=fixture(h,false);var level=h.getLevel();var a=f.at();var b=a.east();
        for(var p:BlockPos.betweenClosed(a.offset(-1,-1,-1),b.offset(1,1,1)))level.setBlock(p,Blocks.STONE.defaultBlockState(),2);
        level.setBlock(a,Blocks.AIR.defaultBlockState(),2);level.setBlock(b,Blocks.AIR.defaultBlockState(),2);
        var site=f.building(List.of(new BlueprintPlacement(a,Blocks.STONE.defaultBlockState(),Items.STONE),new BlueprintPlacement(b,Blocks.STONE.defaultBlockState(),Items.STONE)));
        h.assertTrue(ConstructionDependencies.resolve(level,BlueprintTemplates.create(site),BlueprintTemplates.create(site).placementAt(a)).cycle(),"Fixture did not exercise the reported old cycle");
        var stock=new ItemStack(Items.STONE,2);
        h.assertTrue(!BlueprintService.placeTimedAt(level,f.data(),f.s(),site,a,stock,f.actor()).success()
                && stock.getCount()==2,"Construction bypassed entrance clearance");
        f.actor().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(Items.IRON_PICKAXE));
        for(var pending:List.copyOf(TimedConstructionService.pending(level,site,Set.of()))) {
            h.assertTrue(pending.placement().mode()==BlueprintPlacementMode.CLEARANCE,"Structure dispatched before clearance");
            h.assertTrue(BlueprintService.placeTimedAt(level,f.data(),f.s(),site,pending.placement().position(),ItemStack.EMPTY,f.actor()).success(),"Preparation clearance failed");
        }
        h.assertTrue(BlueprintService.placeTimedAt(level,f.data(),f.s(),site,a,stock,f.actor()).success(),"Old cycle still blocks first timed block");
        h.assertTrue(stock.getCount()==1 && level.getBlockState(a).is(Blocks.STONE),"First block was not charged once");
        h.assertTrue(BlueprintService.placeTimedAt(level,f.data(),f.s(),site,b,stock,f.actor()).success() && stock.isEmpty(),"Second block failed or cost was wrong");
        f.actor().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(Items.IRON_PICKAXE));
        for(var pending:List.copyOf(TimedConstructionService.pending(level,site,Set.of()))) {
            h.assertTrue(pending.placement().mode()==BlueprintPlacementMode.CLEARANCE,"Unexpected remaining structure");
            h.assertTrue(BlueprintService.placeTimedAt(level,f.data(),f.s(),site,pending.placement().position(),ItemStack.EMPTY,f.actor()).success(),"Entrance clearance failed");
        }
        h.assertTrue(f.s().blueprint(site.id())==null && f.s().buildingAt(a)!=null,"Finished real geometry did not register: pending="+TimedConstructionService.pending(level,site,Set.of())+", finish="+BlueprintService.finishTimedIfReady(level,f.data(),f.s(),site).reason());
        f.clean();h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void canceledProtectionRestoresBlockAndKeepsMaterial(GameTestHelper h) {
        var f=fixture(h,false);var at=f.at();var site=f.building(List.of(new BlueprintPlacement(at,Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS)));
        var stock=new ItemStack(Items.OAK_PLANKS,1);boolean[] called={false};
        Consumer<BlockEvent.EntityPlaceEvent> listener=event->{if(event.getPos().equals(at)){called[0]=true;event.setCanceled(true);}};
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            h.assertTrue(!BlueprintService.placeTimedAt(h.getLevel(),f.data(),f.s(),site,at,stock,f.actor()).success(),"Canceled event was ignored");
            h.assertTrue(called[0] && stock.getCount()==1 && h.getLevel().getBlockState(at).isAir() && f.s().blueprint(site.id())!=null,"Protection did not roll back world/material/task");
        } finally {NeoForge.EVENT_BUS.unregister(listener);f.clean();}
        h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void missingMaterialAndRemoteWorkerCannotPlace(GameTestHelper h) {
        var f=fixture(h,false);var at=f.at();var site=f.building(List.of(new BlueprintPlacement(at,Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS)));
        h.assertTrue(!BlueprintService.placeTimedAt(h.getLevel(),f.data(),f.s(),site,at,ItemStack.EMPTY,f.actor()).success() && h.getLevel().getBlockState(at).isAir(),"Missing material was ignored");
        var stock=new ItemStack(Items.OAK_PLANKS,1);f.actor().moveTo(at.getX()+30,at.getY(),at.getZ(),0,0);
        h.assertTrue(!BlueprintService.placeTimedAt(h.getLevel(),f.data(),f.s(),site,at,stock,f.actor()).success() && stock.getCount()==1,"Remote worker contributed construction");
        f.clean();h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",timeoutTicks=100)
    public static void arrivalCadenceRestAndResumeUseRealElapsedTicks(GameTestHelper h) {
        var f=fixture(h,false);var at=f.at();var site=f.building(List.of(
                new BlueprintPlacement(at,Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS),
                new BlueprintPlacement(at.east(),Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS),
                new BlueprintPlacement(at.east(2),Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS)));
        f.c().insertIntoInventory(new ItemStack(Items.OAK_PLANKS,3));f.c().startTask(CitizenWorkState.TO_BLUEPRINT,at,null,site.id());
        f.actor().setOnGround(false); // Arrival does not require the old stable-standing action gate.
        Runnable advance=()->CitizenWorkService.advanceSiteConstruction(h.getLevel(),f.data(),f.s(),f.c(),f.actor());
        advance.run();advance.run();
        h.assertTrue(f.c().inventory().stream().filter(s->s.is(Items.OAK_PLANKS)).mapToInt(ItemStack::getCount).sum()==2,"Same-tick calls changed construction speed");
        h.startSequence().thenIdle(11).thenExecute(()->{
            h.getLevel().setDayTime(15000);advance.run();
            h.assertTrue(h.getLevel().getBlockState(at.east()).isAir(),"Rest advanced construction");
            h.getLevel().setDayTime(6000);f.actor().moveTo(at.getX()+30,at.getY(),at.getZ(),0,0);advance.run();
            h.assertTrue(h.getLevel().getBlockState(at.east()).isAir(),"Absent worker advanced construction");
            f.actor().moveTo(at.getX()-2.5,at.getY(),at.getZ()+.5,0,0);
            if(f.c().workState()!=CitizenWorkState.TO_BLUEPRINT)f.c().startTask(CitizenWorkState.TO_BLUEPRINT,at.east(),null,site.id());
        }).thenIdle(11).thenExecute(()->{
            var before=CitizenWorkService.constructionWorkObservation(h.getLevel(),f.s(),f.c(),f.actor());
            advance.run();advance.run();h.assertTrue(h.getLevel().getBlockState(at.east()).is(Blocks.OAK_PLANKS),"Returned worker did not resume: "+f.c().workState()+"/"+f.c().workFailure()+" before="+before+" after="+CitizenWorkService.constructionWorkObservation(h.getLevel(),f.s(),f.c(),f.actor()));
            h.assertTrue(h.getLevel().getBlockState(at.east(2)).isAir(),"One time window advanced two blocks");
        }).thenIdle(11).thenExecute(()->{
            advance.run();h.assertTrue(h.getLevel().getBlockState(at.east(2)).is(Blocks.OAK_PLANKS),"Continuous construction stopped");f.clean();
        }).thenSucceed();
    }

    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void pairedDoorIgnoresSupportAndOccupancyButRollsBackProtection(GameTestHelper h) {
        var f=fixture(h,false);var level=h.getLevel();var at=f.at().above(2);
        var lower=Blocks.OAK_DOOR.defaultBlockState();
        var upper=lower.setValue(DoorBlock.HALF,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
        var site=f.building(List.of(new BlueprintPlacement(f.at(),Blocks.STONE.defaultBlockState(),Items.STONE),
                new BlueprintPlacement(at,lower,Items.OAK_DOOR),new BlueprintPlacement(at.above(),upper,null)));
        level.setBlock(f.at(),Blocks.STONE.defaultBlockState(),2);
        f.actor().moveTo(at.getX()+.5,at.getY(),at.getZ()+.5,0,0);
        var stock=new ItemStack(Items.OAK_DOOR,1);boolean[] called={false};
        Consumer<BlockEvent.EntityMultiPlaceEvent> listener=event->{if(event.getReplacedBlockSnapshots().stream().anyMatch(s->s.getPos().equals(at))){called[0]=true;event.setCanceled(true);}};
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            h.assertTrue(!BlueprintService.placeTimedAt(level,f.data(),f.s(),site,at,stock,f.actor()).success(),"Pair protection ignored");
            h.assertTrue(called[0] && stock.getCount()==1 && level.getBlockState(at).isAir() && level.getBlockState(at.above()).isAir(),"Pair rollback lost material or half");
        } finally {NeoForge.EVENT_BUS.unregister(listener);}
        h.assertTrue(level.getBlockState(at.below()).isAir(),"Pair fixture unexpectedly supported");
        h.assertTrue(BlueprintService.placeTimedAt(level,f.data(),f.s(),site,at,stock,f.actor()).success()
                && stock.isEmpty() && level.getBlockState(at).is(Blocks.OAK_DOOR) && level.getBlockState(at.above()).is(Blocks.OAK_DOOR),"Pair support/occupancy still blocked or cost wrong");
        f.clean();h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void unsupportedRoadFillUsesToolsMaterialsAndRealProgress(GameTestHelper h) {
        var f=fixture(h,true);var level=h.getLevel();var at=f.at().east(3).above(2);
        var project=new TerrainProject(UUID.randomUUID(),"Timed road fill",at,List.of(new TerrainProject.Step(at,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));
        project.road=true;project.explicit=true;f.s().terrain().projects.put(project.id,project);
        f.actor().moveTo(f.at().getX()+2.5,f.at().getY(),f.at().getZ()+.5,0,0);
        var site=f.s().addBlueprint(BuildingType.HOUSE,at,f.at().north(4),Direction.NORTH,f.s().ownerId(),false).withTerrainProject(project);
        f.s().terrain().index(project);f.c().receiveEquipment(new ItemStack(Items.IRON_SHOVEL));f.c().insertIntoInventory(new ItemStack(Items.DIRT,1));
        f.c().startTask(CitizenWorkState.TO_BLUEPRINT,at,null,site.id());
        h.assertTrue(!FarmEarthworkPlan.support(level,at.below()),"Fixture accidentally had target support");
        CitizenWorkService.advanceSiteConstruction(level,f.data(),f.s(),f.c(),f.actor());
        h.assertTrue(level.getBlockState(at).is(Blocks.DIRT) && project.finished.get(0),"Unsupported fill still waited for physical support");
        h.assertTrue(!f.c().hasInventoryItem(s->s.is(Items.DIRT)) && f.c().inventory().stream().filter(s->s.is(Items.IRON_SHOVEL)).anyMatch(s->s.getDamageValue()==1),"Fill skipped material or tool charge");
        var loaded=TerrainProject.load(project.save());h.assertTrue(loaded.finished.get(0),"Real timed progress was not persisted");
        f.clean();h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void roadPavingFromNearbyKeepsShovelAndProtection(GameTestHelper h) {
        var f=fixture(h,true);var level=h.getLevel();var at=f.at().east(2).below();f.s().autoRoads().enabled=true;f.s().autoRoads().workReady=true;f.s().setCreativeCoreInstalled(true);
        var b=f.s().addBuilding(BuildingType.HOUSE,f.at().east(6),Direction.WEST,f.at().east(6),f.at().east(7).above(2),0,BlueprintTemplates.currentTemplateVersion(BuildingType.HOUSE));
        b.updateFunction(true,com.hkin.exiledsettlement.building.BuildingStatus.OPERATIONAL,2,List.of());
        var route=new AutoRoadState.Route(b.id(),at,List.of(at));f.s().autoRoads().routes.put("timed",route);
        level.setBlock(at,Blocks.DIRT.defaultBlockState(),2);f.actor().moveTo(at.getX()-3.5,at.getY()+1,at.getZ()+.5,0,0);
        h.assertTrue(!AutoRoadService.layCell(level,f.s(),f.c(),f.actor(),at).success(),"Missing shovel was ignored");
        f.c().receiveEquipment(new ItemStack(Items.IRON_SHOVEL));
        Consumer<BlockEvent.EntityPlaceEvent> listener=event->{if(event.getPos().equals(at))event.setCanceled(true);};NeoForge.EVENT_BUS.addListener(listener);
        try {h.assertTrue(!AutoRoadService.layCell(level,f.s(),f.c(),f.actor(),at).success() && level.getBlockState(at).is(Blocks.DIRT),"Road protection was ignored");}
        finally {NeoForge.EVENT_BUS.unregister(listener);}
        var result=AutoRoadService.layCell(level,f.s(),f.c(),f.actor(),at);
        h.assertTrue(result.success() && level.getBlockState(at).is(Blocks.DIRT_PATH),"Nearby road failed: "+result.reason()+", safe="+AutoRoadService.safeCell(level,f.s(),at));
        h.assertTrue(f.c().inventoryItem(AutoRoadService.shovelSlot(f.c())).getDamageValue()==1,"Road shovel was not charged exactly once");
        f.clean();h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void stoneReplacementUsesCarriedPickaxeWithoutAnotherShovel(GameTestHelper h) {
        var f=fixture(h,true);var level=h.getLevel();var at=f.at().east(3).above();
        level.setBlock(at,Blocks.STONE.defaultBlockState(),2);
        var project=new TerrainProject(UUID.randomUUID(),"Stone replacement",at,List.of(new TerrainProject.Step(at,Blocks.DIRT.defaultBlockState(),Blocks.STONE.defaultBlockState())));
        project.road=true;project.explicit=true;f.s().terrain().projects.put(project.id,project);f.s().terrain().index(project);
        var site=f.s().addBlueprint(BuildingType.HOUSE,at,f.at().north(4),Direction.NORTH,f.s().ownerId(),false).withTerrainProject(project);
        f.actor().moveTo(f.at().getX()+2.5,f.at().getY(),f.at().getZ()+.5,0,0);
        f.c().receiveEquipment(new ItemStack(Items.IRON_SHOVEL));f.c().receiveEquipment(new ItemStack(Items.IRON_PICKAXE));
        f.c().insertIntoInventory(new ItemStack(Items.DIRT));f.c().startTask(CitizenWorkState.TO_BLUEPRINT,at,null,site.id());
        CitizenWorkService.advanceSiteConstruction(level,f.data(),f.s(),f.c(),f.actor());
        h.assertTrue(level.getBlockState(at).is(Blocks.DIRT) && project.finished.get(0),"Stone fill requested another shovel despite carrying shovel and pickaxe: "+f.c().constructionTool());
        h.assertTrue(f.c().inventory().stream().filter(s->s.is(Items.IRON_SHOVEL)).mapToInt(ItemStack::getCount).sum()==1
                && f.c().inventory().stream().filter(s->s.is(Items.IRON_SHOVEL)).allMatch(s->s.getDamageValue()==0)
                && f.c().inventory().stream().filter(s->s.is(Items.IRON_PICKAXE)).anyMatch(s->s.getDamageValue()==1),"Replacement charged the wrong tool");
        f.clean();h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void existingWrongShovelRequestUsesCarriedCorrectTool(GameTestHelper h) {
        var f=fixture(h,false);f.c().receiveEquipment(new ItemStack(Items.IRON_SHOVEL));f.c().receiveEquipment(new ItemStack(Items.IRON_PICKAXE));
        f.c().requestConstructionTool(Items.IRON_SHOVEL,Blocks.STONE.defaultBlockState());
        for(int i=0;i<20;i++) {
            var required=CitizenEquipmentService.nextEquipment(h.getLevel(),f.s(),f.c());
            h.assertTrue(required!=Items.IRON_SHOVEL && f.c().constructionTool()==null,"Existing incompatible request keeps issuing shovels");
        }
        h.assertTrue(f.c().inventory().stream().filter(s->s.is(Items.IRON_SHOVEL)).mapToInt(ItemStack::getCount).sum()==1,"Existing shovel count changed");
        f.clean();h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void establishedFarmMaintenanceStopsWithoutLosingOrdersOrNewConstruction(GameTestHelper h) {
        var f=fixture(h,true);var level=h.getLevel();var at=f.at().east(2);
        var built=f.s().addBuilding(BuildingType.FARM_FIELD,at,Direction.NORTH,at,at.offset(4,2,4),0,1);
        var region=new com.hkin.exiledsettlement.region.ProductionRegion(BuildingType.FARM_FIELD,at,at.offset(4,0,4),Direction.NORTH);
        region.surveyed=true;region.layoutReady=true;region.workingBuilding=built.id();region.groundMaintenance=true;
        built.withRegion(region);
        var old=f.s().addAreaBlueprint(BuildingType.FARM_FIELD,at,f.at().north(5),Direction.NORTH,f.s().ownerId(),false,at,at.offset(4,0,4)).withRegion(region);
        var project=new TerrainProject(UUID.randomUUID(),"Existing farm maintenance",at,List.of(new TerrainProject.Step(at,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));
        project.explicit=true;project.dependencies.add(old.id());f.s().terrain().projects.put(project.id,project);
        var child=f.s().addBlueprint(BuildingType.HOUSE,at,f.at().north(6),Direction.NORTH,f.s().ownerId(),false).withTerrainProject(project);
        h.assertTrue(!CitizenWorkService.blueprintWorkAllowed(f.s(),f.c(),old,at) && !TerrainService.allowed(f.s(),child),"Worker may still maintain established farmland");
        f.c().startTask(CitizenWorkState.TO_BLUEPRINT,at,null,old.id());
        CitizenWorkService.advanceSiteConstruction(level,f.data(),f.s(),f.c(),f.actor());
        h.assertTrue(f.c().workState()==CitizenWorkState.IDLE && level.getBlockState(at).isAir(),"Previously assigned farm maintenance was executed");
        com.hkin.exiledsettlement.region.FarmConstructionPolicy.stopAutomaticMaintenance(f.s());
        h.assertTrue(f.s().blueprint(old.id())==old && f.s().blueprint(child.id())==child && project.finished.isEmpty() && !project.cancelled && region.groundPaused,"Stopping maintenance deleted order/progress evidence");
        h.assertTrue(com.hkin.exiledsettlement.region.ProductionRegion.load(region.save()).groundPaused,"Stopped maintenance state was not saved");
        var fresh=f.s().addAreaBlueprint(BuildingType.FARM_FIELD,at,f.at().south(5),Direction.NORTH,f.s().ownerId(),false,at,at.offset(5,0,4));
        var extension=com.hkin.exiledsettlement.region.ProductionRegion.load(region.save());extension.groundPaused=false;extension.groundMaintenance=false;extension.workingBuilding=null;fresh.withRegion(extension);
        h.assertTrue(!TerrainService.allowed(f.s(),fresh),"Fresh farm skipped unfinished preparation");
        extension.prepared=true;
        h.assertTrue(CitizenWorkService.blueprintWorkAllowed(f.s(),f.c(),fresh,at) && TerrainService.allowed(f.s(),fresh),"Explicit independent new farm construction was blocked");
        f.clean();h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void forcedPublicAndRoadWorkCannotCutOrReplaceBuiltHouse(GameTestHelper h) {
        for(boolean road:new boolean[]{false,true})for(boolean fill:new boolean[]{false,true}) {
            var f=fixture(h,false);var level=h.getLevel();var at=f.at().east(2);
            var building=f.s().addBuilding(BuildingType.HOUSE,at,Direction.NORTH,at,at.offset(2,3,2),0,1);
            level.setBlock(at,Blocks.OAK_PLANKS.defaultBlockState(),2);
            var site=f.s().addBlueprint(BuildingType.HOUSE,at,f.at().north(5),Direction.NORTH,f.s().ownerId(),false);
            var project=new TerrainProject(site.id(),"Old public order",at,List.of(new TerrainProject.Step(at,
                    fill?Blocks.DIRT.defaultBlockState():Blocks.AIR.defaultBlockState(),Blocks.OAK_PLANKS.defaultBlockState())));
            project.road=road;project.publicGround=!road;project.dependencies.add(building.id());
            site.withTerrainProject(project);f.s().terrain().projects.put(project.id,project);f.s().terrain().index(project);
            var result=BlueprintService.debugAdvanceOne(level,f.data(),f.s(),site);
            h.assertTrue(level.getBlockState(at).is(Blocks.OAK_PLANKS) && !result.advanced(),"Forced "+(road?"road":"public")+" "+(fill?"fill":"cut")+" destroyed a house: "+result);
            h.assertTrue(f.s().blueprint(site.id())==site && !project.cancelled && !project.completed && project.finished.isEmpty(),"Protected order or paid progress was discarded");
            f.clean();
        }
        h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void oldEarthworkCannotUndermineHouseOrPendingHouseColumn(GameTestHelper h) {
        var f=fixture(h,false);var level=h.getLevel();var at=f.at().east(2);
        var building=f.s().addBuilding(BuildingType.HOUSE,at,Direction.NORTH,at,at.offset(2,3,2),0,1);
        var below=at.below(5);level.setBlock(below,Blocks.STONE.defaultBlockState(),2);var site=f.s().addBlueprint(BuildingType.HOUSE,below,f.at().north(5),Direction.NORTH,f.s().ownerId(),false);
        var step=new TerrainProject.Step(below,Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState());
        var project=new TerrainProject(site.id(),"Old road beneath floor",below,List.of(step));project.road=true;project.dependencies.add(building.id());
        site.withTerrainProject(project);f.s().terrain().projects.put(project.id,project);f.s().terrain().index(project);
        h.assertTrue(TerrainService.guard(level,f.s(),site,step).equals("PROTECTED_AREA"),"Ordinary road work can undermine a building from below its recorded bounds");
        var pending=f.building(List.of(new BlueprintPlacement(f.at(),Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS),new BlueprintPlacement(f.at().west(2),Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS)));
        var otherStep=new TerrainProject.Step(f.at().west(2).below(),Blocks.DIRT.defaultBlockState(),Blocks.STONE.defaultBlockState());
        project.dependencies.add(pending.id());
        h.assertTrue(TerrainService.guard(level,f.s(),site,otherStep).equals("PROTECTED_AREA"),"A parent dependency exempted a house reservation from road protection");
        f.clean();h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void forceSkipsProtectedTargetButStillWorksOutsideBuilding(GameTestHelper h) {
        var f=fixture(h,false);var level=h.getLevel();var at=f.at().east(2);var safe=f.at().west(2);
        f.s().addBuilding(BuildingType.HOUSE,at,Direction.NORTH,at,at.offset(2,3,2),0,1)
                .withCustomTemplate(CustomTemplateSnapshot.create("Protected house",BuildingType.HOUSE,at,Direction.NORTH,List.of(new BlueprintPlacement(at,Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS)),List.of(new BlueprintEntrance(at,Direction.NORTH))));
        level.setBlock(at,Blocks.OAK_PLANKS.defaultBlockState(),2);level.setBlock(safe,Blocks.DIRT.defaultBlockState(),2);
        var site=f.s().addBlueprint(BuildingType.HOUSE,at,f.at().north(5),Direction.NORTH,f.s().ownerId(),false);
        var project=new TerrainProject(site.id(),"Mixed old public order",at,List.of(new TerrainProject.Step(at,Blocks.AIR.defaultBlockState(),Blocks.OAK_PLANKS.defaultBlockState()),new TerrainProject.Step(safe,Blocks.AIR.defaultBlockState(),Blocks.DIRT.defaultBlockState())));
        project.publicGround=true;site.withTerrainProject(project);f.s().terrain().projects.put(project.id,project);f.s().terrain().index(project);
        var result=BlueprintService.debugAdvanceOne(level,f.data(),f.s(),site);
        h.assertTrue(result.advanced() && level.getBlockState(safe).isAir() && level.getBlockState(at).is(Blocks.OAK_PLANKS),"A protected target starved safe work or the house was destroyed");
        h.assertTrue(f.s().blueprint(site.id())==site && !project.completed && !project.cancelled,"Partial debug work falsely closed the protected order");
        f.clean();h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void placingP0RevokesNearbyOptionalPlansAndPreservesPaidWorld(GameTestHelper h) {
        var f=fixture(h,true);var level=h.getLevel();var at=f.at().east(2);var paid=at.east();var far=f.at().west(5);
        level.setBlock(paid,Blocks.DIRT.defaultBlockState(),2);
        var carrier=f.s().addBlueprint(BuildingType.HOUSE,at,f.at().north(5),Direction.NORTH,f.s().ownerId(),false);
        var project=new TerrainProject(carrier.id(),"Old public grading",at,List.of(new TerrainProject.Step(at,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()),
                new TerrainProject.Step(paid,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()),new TerrainProject.Step(far,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));
        project.publicGround=true;project.mark(1);project.groundSurfaces.addAll(List.of(at,paid,far));carrier.withTerrainProject(project);f.s().terrain().index(project);
        var route=new AutoRoadState.Route(UUID.randomUUID(),at,List.of(at,paid));route.admitted.set(0,2);route.completed.set(1);route.advance();f.s().autoRoads().routes.put("old",route);
        var otherAt=f.at().east(7);
        var other=f.s().addBlueprint(BuildingType.HOUSE,otherAt,f.at().north(6),Direction.NORTH,f.s().ownerId(),false)
                .withCustomTemplate(CustomTemplateSnapshot.create("Other P0",BuildingType.HOUSE,otherAt,Direction.NORTH,List.of(new BlueprintPlacement(otherAt,Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS)),List.of(new BlueprintEntrance(otherAt,Direction.NORTH))));
        var snapshot=CustomTemplateSnapshot.create("New P0",BuildingType.HOUSE,at,Direction.NORTH,List.of(new BlueprintPlacement(at,Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS)),List.of(new BlueprintEntrance(at,Direction.NORTH)));
        var result=BlueprintService.placeCustom(level,f.s().ownerId(),snapshot,null);
        h.assertTrue(result.success(),"P2 reservations prevented successful P0 placement: "+result.reason());
        h.assertTrue(project.abandoned.get(0) && project.abandoned.get(1) && !project.abandoned.get(2) && project.retiredPaid.get(1),"Local revocation lost paid evidence or touched distant planning");
        h.assertTrue(level.getBlockState(paid).is(Blocks.DIRT) && level.getBlockState(at).isAir() && f.s().blueprint(other.id())==other,"Placing a P0 edited world blocks or cancelled another P0");
        h.assertTrue(route.p0Invalidated && !AutoRoadService.routeAllowed(f.s(),route) && route.completed.get(1) && f.s().terrain().publicGround.signature==Long.MIN_VALUE,"Old route was not revoked or shared ground did not restart");
        var saved=new AutoRoadState();saved.load(f.s().autoRoads().save());h.assertTrue(saved.routes.get("old").p0Invalidated && saved.routes.get("old").completed.get(1),"Revoked route or paid road state did not survive saving");
        var oldAbandoned=(BitSet)project.abandoned.clone();
        h.assertTrue(!BlueprintService.placeCustom(level,f.s().ownerId(),snapshot,null).success() && project.abandoned.equals(oldAbandoned),"Failed placement revoked additional work");
        f.clean();h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void newRoadPublicationAvoidsExistingAndPendingBuildingColumns(GameTestHelper h) {
        var f=fixture(h,true);var level=h.getLevel();var at=f.at().east(2);
        f.s().addBuilding(BuildingType.HOUSE,at,Direction.NORTH,at,at.offset(2,3,2),0,1);
        level.setBlock(at,Blocks.OAK_PLANKS.defaultBlockState(),2);f.s().terrain().enabled=false;
        var route=new AutoRoadState.Route(UUID.randomUUID(),at,List.of(at));f.s().autoRoads().routes.put("new",route);
        int orders=f.s().blueprints().size();
        h.assertTrue(!RoadEarthworkService.permitted(level,f.s(),at) && !PublicGroundService.publishRoadColumn(level,f.s(),route,at)
                && f.s().blueprints().size()==orders && level.getBlockState(at).is(Blocks.OAK_PLANKS),"New road publication entered an existing building");
        var pendingAt=f.at().west(2);
        var pending=f.s().addBlueprint(BuildingType.HOUSE,pendingAt,f.at().north(5),Direction.NORTH,f.s().ownerId(),false)
                .withCustomTemplate(CustomTemplateSnapshot.create("Pending P0",BuildingType.HOUSE,pendingAt,Direction.NORTH,List.of(new BlueprintPlacement(pendingAt,Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS)),List.of(new BlueprintEntrance(pendingAt,Direction.NORTH))));
        var pendingRoute=new AutoRoadState.Route(pending.id(),pendingAt,List.of(pendingAt));
        h.assertTrue(!RoadEarthworkService.permitted(level,f.s(),pendingAt) && !PublicGroundService.publishRoadColumn(level,f.s(),pendingRoute,pendingAt),"New road publication treated its P0 parent as permission to enter the building");
        f.clean();h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void farmP0PlacementAlsoRevokesOptionalPlanning(GameTestHelper h) {
        var f=fixture(h,true);var level=h.getLevel();var at=f.at().east(2);
        var carrier=f.s().addBlueprint(BuildingType.HOUSE,at,f.at().north(7),Direction.NORTH,f.s().ownerId(),false);
        var project=new TerrainProject(carrier.id(),"Old public grade",at,List.of(new TerrainProject.Step(at,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));
        project.publicGround=true;carrier.withTerrainProject(project);f.s().terrain().index(project);
        var result=com.hkin.exiledsettlement.region.ProductionRegionService.place(level,BuildingType.FARM_FIELD,f.at().offset(-6,0,-6),f.at().offset(6,0,6),Direction.NORTH,f.s().ownerId(),null);
        h.assertTrue(result.success() && project.cancelled && project.abandoned.get(0) && !project.completed && level.getBlockState(at).isAir(),"New farm P0 left the optional plan active or edited world blocks: "+result.reason()+" cancelled="+project.cancelled+" abandoned="+project.abandoned);
        h.assertTrue(result.value().containsColumn(at) && !RoadEarthworkService.permitted(level,f.s(),at),"An unsurveyed farm did not protect its entire accepted selection");
        f.clean();h.succeed();
    }

    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void revokedRoadAcceptsSafeReplacementAndRetainsActualPaidRoad(GameTestHelper h) {
        var f=fixture(h,true);var level=h.getLevel();var at=f.at().east(2);var paid=f.at().west(2).below();
        f.s().terrain().enabled=false;
        level.setBlock(paid,Blocks.DIRT_PATH.defaultBlockState(),2);RoadSpaceData.get(level).addAll(List.of(paid));
        var old=new AutoRoadState.Route(UUID.randomUUID(),at,List.of(at,paid));old.width=3;old.p0Invalidated=true;old.completed.set(1);old.admitted.set(0,2);f.s().autoRoads().routes.put("revoked",old);
        var safe=new AutoRoadState.Route(old.building,f.at().west().below(),List.of(paid,f.at().west().below()));safe.width=3;
        h.assertTrue(AdaptiveRoadService.accept(level,f.s(),"revoked",safe),"Revoked route refused a safe replacement as an ordinary no-benefit optimization");
        var actual=f.s().autoRoads().routes.get("revoked");
        h.assertTrue(!actual.p0Invalidated && actual.id.equals(old.id) && AutoRoadService.routeAllowed(f.s(),actual)
                && actual.completed.get(actual.cellIndex(paid)) && level.getBlockState(paid).is(Blocks.DIRT_PATH),"Road replacement lost identity, actual paid paving or dispatch permission");
        f.clean();h.succeed();
    }

    private static void invokeWork(String method,Fixture f) {
        try {var work=CitizenWorkService.class.getDeclaredMethod(method,net.minecraft.server.level.ServerLevel.class,SettlementSavedData.class,SettlementRecord.class,CitizenRecord.class,SettlementCitizenEntity.class);
            work.setAccessible(true);work.invoke(null,f.h().getLevel(),f.data(),f.s(),f.c(),f.actor());}
        catch(ReflectiveOperationException e){throw new RuntimeException(e);}
    }
    @GameTest(templateNamespace="exiledsettlement_timed",template="empty")
    public static void oldWorkerFarmActionsStopWhileFarmerStillTills(GameTestHelper h) {
        var f=fixture(h,false);var level=h.getLevel();var at=f.at().east(2);
        var farm=f.s().addBuilding(BuildingType.FARM_FIELD,at,Direction.NORTH,f.at().offset(-6,-1,-6),f.at().offset(6,2,6),0,1);
        farm.updateFunction(true,com.hkin.exiledsettlement.building.BuildingStatus.OPERATIONAL,1,List.of());
        level.setBlock(at,Blocks.DIRT.defaultBlockState(),2);f.actor().moveTo(at.getX()+.5,at.getY()+1,at.getZ()+.5,0,0);
        f.c().receiveEquipment(new ItemStack(Items.IRON_HOE));f.c().startTask(CitizenWorkState.TO_FARM,at,null,null);
        invokeWork("farm",f);h.assertTrue(f.c().workState()==CitizenWorkState.IDLE && level.getBlockState(at).is(Blocks.DIRT),"Worker legacy farming action still changed soil");
        f.c().startRepair(farm.id(),at);invokeWork("repair",f);
        h.assertTrue(f.c().workState()==CitizenWorkState.IDLE && level.getBlockState(at).is(Blocks.DIRT),"Worker legacy repair still changed farmland");
        f.c().assignProfession(CitizenProfession.FARMER,farm.id());f.c().startTask(CitizenWorkState.TO_FARM,at,null,null);
        // Farming has a UUID-staggered production cadence. Wait for its real slot.
        h.startSequence().thenWaitUntil(()->{invokeWork("farm",f);h.assertTrue(level.getBlockState(at).is(Blocks.FARMLAND),"Farmer's own tilling did not run on its cadence");})
                .thenExecute(f::clean).thenSucceed();
    }

    @GameTest(templateNamespace="exiledsettlement_timed",template="empty",timeoutTicks=200)
    public static void workTargetSnapshotFollowsTargetsFiltersWorkersAndClearsIdle(GameTestHelper h) {
        var f=fixture(h,true);var level=h.getLevel();
        // Wait for actual entity tracking: a fixed tick delay races asynchronous chunk loading.
        h.startSequence().thenExecute(()->{
        f.actor().getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,f.s().id().toString());
        f.actor().getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,f.c().id().toString());
        h.assertTrue(level.addFreshEntity(f.actor()),"Fixture resident was not added");
        f.c().updateProjection(f.actor().getUUID(),f.at());
        }).thenWaitUntil(()->h.assertTrue(level.getEntity(f.actor().getUUID())==f.actor(),"Fixture resident is not tracked yet"))
        .thenExecute(()->{
        f.c().startTask(CitizenWorkState.TO_BLUEPRINT,f.at().east(),null,UUID.randomUUID());
        var first=com.hkin.exiledsettlement.debug.WorkTargetDebugService.snapshot(level,f.s(),f.actor().position(),true);
        h.assertTrue(first.targets().size()==1 && first.targets().getFirst().position().equals(net.minecraft.world.phys.Vec3.atCenterOf(f.at().east())),"Initial target not linked: "+first+" actor="+level.getEntity(f.actor().getUUID())+" state="+f.c().workState()+" projection="+CitizenProjectionService.findProjection(level,f.c()));
        f.c().updateTargetPosition(f.at().west());f.c().updateWorkFailure("NO_PATH");
        var second=com.hkin.exiledsettlement.debug.WorkTargetDebugService.snapshot(level,f.s(),f.actor().position(),false);
        h.assertTrue(second.targets().getFirst().position().equals(net.minecraft.world.phys.Vec3.atCenterOf(f.at().west())) && second.targets().getFirst().blocked(),"Changed/blocked target did not update");
        var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),level.registryAccess());
        try {com.hkin.exiledsettlement.network.WorkTargetsPayload.STREAM_CODEC.encode(buffer,second);
            h.assertTrue(com.hkin.exiledsettlement.network.WorkTargetsPayload.STREAM_CODEC.decode(buffer).equals(second),"Target packet coordinates or colors changed in transit");}finally{buffer.release();}
        f.c().assignProfession(CitizenProfession.FARMER,null);
        h.assertTrue(com.hkin.exiledsettlement.debug.WorkTargetDebugService.snapshot(level,f.s(),f.actor().position(),true).targets().isEmpty(),"Workers filter still includes farmers");
        f.c().clearWork();f.actor().stopSettlementNavigation();
        h.assertTrue(com.hkin.exiledsettlement.debug.WorkTargetDebugService.snapshot(level,f.s(),f.actor().position(),false).targets().isEmpty(),"Idle resident kept a stale work line");
        var dispatcher=new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
        com.hkin.exiledsettlement.command.SettlementCommands.register(dispatcher);
        var command=dispatcher.parse("exiledsettlement debug work_targets on workers",level.getServer().createCommandSourceStack().withPermission(2));
        h.assertTrue(!command.getReader().canRead() && command.getExceptions().isEmpty(),"Work target command branch not registered");
        f.clean();
        }).thenSucceed();
    }
}
