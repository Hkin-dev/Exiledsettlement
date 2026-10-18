package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import java.util.*;
import java.util.function.Consumer;

@GameTestHolder("exiledsettlement_clearance_phase")
@PrefixGameTestTemplate(false)
public final class ClearancePhaseGameTests {
    private static int serial;
    private record Fixture(GameTestHelper h,SettlementSavedData data,SettlementRecord s,BlueprintRecord b,
            BuildingBlueprintTemplate t,CitizenRecord c,SettlementCitizenEntity actor,List<BlockPos> obstacles,BlueprintPlacement floor) {
        void clean(){s.deactivate();actor.discard();for(var p:t.placements()) {var q=new ChunkPos(p.position());h.getLevel().setChunkForced(q.x,q.z,false);}}
    }
    private static Fixture fixture(GameTestHelper h) {
        var l=h.getLevel();var core=h.absolutePos(new BlockPos(130000+serial++*128,40,0));
        var data=SettlementSavedData.get(l);var s=data.createOrRecover(core,UUID.randomUUID(),"Clearance phase").value();
        s.terrain().enabled=false;s.autoRoads().enabled=false;s.decorations().enabled=false;
        var t=BlueprintTemplates.create(BuildingType.TAVERN,core.south(5),Direction.SOUTH);
        for(var p:BlockPos.betweenClosed(t.minCorner().offset(-5,-1,-5),t.maxCorner().offset(5,0,5).atY(t.minCorner().getY()-1))) {
            var q=new ChunkPos(p);l.setChunkForced(q.x,q.z,true);l.setBlock(p,Blocks.STONE.defaultBlockState(),2);
        }
        for(var p:t.placements())l.getChunkAt(p.position());
        var b=s.addBlueprint(BuildingType.TAVERN,t.anchor(),t.anchor(),Direction.SOUTH,s.ownerId(),false,2,Map.of());
        var air=t.placements().stream().filter(p->p.mode()==BlueprintPlacementMode.CLEARANCE
                && BuildingPlacementRules.requiresClearance(t,p.position()) && p.position().getY()>t.minCorner().getY()+1).limit(3).map(BlueprintPlacement::position).toList();
        var roof=t.placements().stream().filter(p->p.mode().isFinalStructure()).max(Comparator.comparingInt(p->p.position().getY())).orElseThrow();
        var obstacles=new ArrayList<>(air);obstacles.add(roof.position());
        obstacles.forEach(p->l.setBlock(p,Blocks.DIRT.defaultBlockState(),2));
        var floor=t.placements().stream().filter(p->p.mode().isFinalStructure() && p.material()!=null
                && p.position().getY()==t.minCorner().getY()).findFirst().orElseThrow();
        var c=s.citizens().iterator().next();for(var other:s.citizens())if(other!=c)other.markDead(core);
        c.assignProfession(CitizenProfession.WORKER,null);
        var actor=ModEntities.SETTLEMENT_CITIZEN.get().create(l);actor.setNoAi(true);
        actor.moveTo(t.minCorner().getX()-1.5,t.minCorner().getY(),t.minCorner().getZ()+.5,0,0);actor.setOnGround(true);
        actor.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());
        actor.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
        c.updateProjection(actor.getUUID(),actor.blockPosition());l.setDayTime(6000);
        return new Fixture(h,data,s,b,t,c,actor,obstacles,floor);
    }
    @GameTest(templateNamespace="exiledsettlement_clearance_phase",template="empty",timeoutTicks=400)
    public static void idleWorkerAutomaticallyAcceptsClearanceWithoutBuildingMaterials(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();
        for(var item:List.of(Items.IRON_SHOVEL,Items.IRON_PICKAXE,Items.IRON_AXE))f.c.receiveEquipment(new ItemStack(item));
        f.c.insertIntoInventory(new ItemStack(Items.BREAD,16));
        h.startSequence().thenWaitUntil(()->h.assertTrue(l.areEntitiesLoaded(new ChunkPos(f.actor.blockPosition()).toLong()),"Wait for worker section"))
        .thenExecute(()->h.assertTrue(l.addFreshEntity(f.actor),"Worker spawn failed"))
        .thenWaitUntil(()->{
            l.setDayTime(6000);CitizenWorkService.tickSettlement(l,f.data,f.s);
            h.assertTrue(f.b.id().equals(f.c.targetBlueprintId()),"Idle dispatch failed: "+f.c.workState()+" "+f.c.workFailure());
            var p=BlueprintService.pendingAt(l,f.b,f.c.targetPosition());
            h.assertTrue(p!=null && p.placement().mode()==BlueprintPlacementMode.CLEARANCE,"Dispatched structure instead of clearance");
        }).thenExecute(()->{System.out.println("IDLE_CLEARANCE_DISPATCH_OK");f.clean();}).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_clearance_phase",template="empty")
    public static void newTreeFarmKeepsRectangleAndOldMembershipSurvivesReload(GameTestHelper h) {
        var l=h.getLevel();var at=h.absolutePos(new BlockPos(150000,40,0));
        for(var p:BlockPos.betweenClosed(at.offset(-4,-1,-4),at.offset(24,-1,24)))l.setBlock(p,Blocks.DIRT.defaultBlockState(),2);
        l.setBlock(at.offset(4,-1,4),Blocks.DIRT_PATH.defaultBlockState(),2);
        var r=new com.hkin.exiledsettlement.region.ProductionRegion(BuildingType.TREE_FARM,at,at.offset(8,0,8),Direction.NORTH);
        r.survey(l,p->true,1000);
        long internal=r.cells.values().stream().filter(c->(c.use()==com.hkin.exiledsettlement.region.ProductionRegion.Use.BOUNDARY || c.use()==com.hkin.exiledsettlement.region.ProductionRegion.Use.GATE)
                && c.ground().getX()>at.getX() && c.ground().getX()<at.getX()+8 && c.ground().getZ()>at.getZ() && c.ground().getZ()<at.getZ()+8).count();
        var legacy=com.hkin.exiledsettlement.region.ProductionRegion.load(r.save());
        h.assertTrue(!legacy.rectangularSelection && legacy.cells.equals(r.cells) && legacy.cells.size()==80 && internal==4,"Existing plot was reshaped");
        var fresh=new com.hkin.exiledsettlement.region.ProductionRegion(BuildingType.TREE_FARM,at,at.offset(8,0,8),Direction.NORTH);
        fresh.rectangularSelection=true;fresh.survey(l,p->true,1000);
        h.assertTrue(fresh.cells.size()==81 && fresh.surveyed,"New tree farm omitted selected ground");
        for(var c:fresh.cells.values())if(c.use()==com.hkin.exiledsettlement.region.ProductionRegion.Use.BOUNDARY || c.use()==com.hkin.exiledsettlement.region.ProductionRegion.Use.GATE)
            h.assertTrue(c.ground().getX()==at.getX() || c.ground().getX()==at.getX()+8 || c.ground().getZ()==at.getZ() || c.ground().getZ()==at.getZ()+8,"Interior fence in rectangle");
        var loaded=com.hkin.exiledsettlement.region.ProductionRegion.load(fresh.save());
        h.assertTrue(loaded.rectangularSelection && loaded.cells.equals(fresh.cells),"New rectangle lost on reload");
        var protectedPlot=new com.hkin.exiledsettlement.region.ProductionRegion(BuildingType.TREE_FARM,at,at.offset(8,0,8),Direction.NORTH);
        protectedPlot.rectangularSelection=true;protectedPlot.survey(l,p->p.getX()!=at.getX()+4 || p.getZ()!=at.getZ()+4,1000);
        h.assertTrue(!protectedPlot.surveyed,"Protected cell silently excluded");
        protectedPlot.survey(l,p->true,1000);h.assertTrue(protectedPlot.surveyed && protectedPlot.cells.size()==81,"Unblocked survey did not resume exact rectangle");
        l.setBlock(at.offset(0,-1,4),Blocks.WATER.defaultBlockState(),2);l.setBlock(at.offset(-1,-1,4),Blocks.WATER.defaultBlockState(),2);
        var wet=new com.hkin.exiledsettlement.region.ProductionRegion(BuildingType.TREE_FARM,at,at.offset(8,0,8),Direction.NORTH);
        wet.rectangularSelection=true;wet.survey(l,p->true,1000);
        h.assertTrue(!wet.surveyed && wet.cells.size()==81,"Connected water removed cells or bypassed protection");
        System.out.println("TREE_RECTANGLE_OK cells=81 internalFences=0 legacyCells=80 protectedAndWaterWait=true");
        h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_clearance_phase",template="empty",timeoutTicks=2400)
    public static void restaurantAndTavernPlanRoadWhileTemporarilyNonOperational(GameTestHelper h) {
        var l=h.getLevel();var data=SettlementSavedData.get(l);var settlements=new ArrayList<SettlementRecord>();var buildings=new ArrayList<BuildingRecord>();
        int i=0;
        for(var type:List.of(BuildingType.RESTAURANT,BuildingType.TAVERN))for(var facing:Direction.Plane.HORIZONTAL) {
            var core=h.absolutePos(new BlockPos(160000+i++*160,1,0));
            var s=data.createOrRecover(core,UUID.randomUUID(),"Restaurant road").value();settlements.add(s);
            s.terrain().enabled=false;s.decorations().enabled=false;s.autoRoads().enabled=true;s.autoRoads().gradingReviewVersion=1;
            for(var p:BlockPos.betweenClosed(core.offset(-40,-1,-40),core.offset(40,-1,40))){var chunk=new ChunkPos(p);l.setChunkForced(chunk.x,chunk.z,true);l.setBlock(p,Blocks.DIRT.defaultBlockState(),2);}
            var t=BlueprintTemplates.create(type,core.south(20),facing);
            for(var p:t.placements())l.setBlock(p.position(),p.state(),2);
            var b=s.addBuilding(type,t.registrationPosition(),facing,t.minCorner(),t.maxCorner(),t.transitionMargin(),BlueprintTemplates.currentTemplateVersion(type));buildings.add(b);
            b.updateFunction(false,BuildingStatus.MISSING_CONTAINER,0,List.of());
            h.assertTrue(com.hkin.exiledsettlement.road.AutoRoadService.roadEligible(b),"Completed restaurant omitted while non-operational");
            h.assertTrue(!com.hkin.exiledsettlement.road.AutoRoadService.entrances(l,s,b).isEmpty(),"No valid entry: "+type+" "+facing);
        }
        h.startSequence().thenWaitUntil(()->{
            for(var s:settlements)com.hkin.exiledsettlement.road.AutoRoadService.tick(l,s);
            com.hkin.exiledsettlement.road.AutoRoadPlanner.tickLevel(l);
            if(h.getTick()%400==0)for(var s:settlements)System.out.println("RESTAURANT_PLAN "+com.hkin.exiledsettlement.road.AutoRoadPlanner.progress(l,s));
            for(int n=0;n<settlements.size();n++) {
                var s=settlements.get(n);var b=buildings.get(n);
                h.assertTrue(s.autoRoads().routes.values().stream().anyMatch(r->r.building.equals(b.id()) && !r.cells.isEmpty()),"No restaurant road: "+b.type()+" "+b.facing()+" "+s.autoRoads().status);
            }
        }).thenExecute(()->{settlements.forEach(s->{com.hkin.exiledsettlement.road.AutoRoadPlanner.invalidate(s);s.deactivate();
            var core=s.corePosition();for(int x=(core.getX()-40)>>4;x<=(core.getX()+40)>>4;x++)for(int z=(core.getZ()-40)>>4;z<=(core.getZ()+40)>>4;z++)l.setChunkForced(x,z,false);
        });System.out.println("RESTAURANT_ROADS_OK cases=8");}).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_clearance_phase",template="empty",timeoutTicks=100)
    public static void obsoleteSupplyRequestReturnsToClearanceWithoutAStockSource(GameTestHelper h) {
        obsoleteSupply(h,false);
    }
    @GameTest(templateNamespace="exiledsettlement_clearance_phase",template="empty",timeoutTicks=100)
    public static void obsoleteScaffoldRequestReturnsToClearanceWithoutHeldMaterials(GameTestHelper h) {
        obsoleteSupply(h,true);
    }
    private static void obsoleteSupply(GameTestHelper h,boolean footing) {
        var f=fixture(h);var l=h.getLevel();
        for(var item:List.of(Items.IRON_SHOVEL,Items.IRON_PICKAXE,Items.IRON_AXE))f.c.receiveEquipment(new ItemStack(item));
        f.c.insertIntoInventory(new ItemStack(Items.BREAD,16));
        f.c.insertIntoInventory(new ItemStack(Items.COBBLESTONE,7));
        f.c.startTask(CitizenWorkState.TO_BLUEPRINT,f.floor.position(),null,f.b.id());
        if(footing)f.c.fetchFootingMaterial(null);else f.c.fetchConstructionMaterial(null);
        h.startSequence().thenWaitUntil(()->h.assertTrue(l.areEntitiesLoaded(new ChunkPos(f.actor.blockPosition()).toLong()),"Wait for worker section"))
        .thenExecute(()->h.assertTrue(l.addFreshEntity(f.actor),"Worker spawn failed"))
        .thenWaitUntil(()->{
            l.setDayTime(6000);CitizenWorkService.tickSettlement(l,f.data,f.s);
            h.assertTrue(f.c.workState()==CitizenWorkState.TO_BLUEPRINT,"Obsolete supply occupies construction slot: "+f.c.workState()+" "+f.c.workFailure());
            h.assertTrue(f.c.inventory().stream().filter(s->s.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum()==7,"Resuming clearance lost carried materials");
        }).thenExecute(()->{System.out.println("STALE_SUPPLY_CLEARANCE_OK footing="+footing);f.clean();}).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_clearance_phase",template="empty",timeoutTicks=200)
    public static void newTreeFarmPublishesRealPreparationBeforeFences(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();f.s.removeBlueprint(f.b.id());var at=f.s.corePosition().south(5);
        for(var p:BlockPos.betweenClosed(at.offset(-2,-2,-2),at.offset(10,-1,10)))l.setBlock(p,Blocks.DIRT.defaultBlockState(),2);
        var soil=at.offset(4,-1,4);var high=at.offset(4,5,4);
        l.setBlock(soil,Blocks.CLAY.defaultBlockState(),2);l.setBlock(high,Blocks.OAK_PLANKS.defaultBlockState(),2);
        var placed=com.hkin.exiledsettlement.region.ProductionRegionService.place(l,BuildingType.TREE_FARM,at,at.offset(8,0,8),Direction.NORTH,f.s.ownerId(),null);
        h.assertTrue(placed.success(),"Tree farm rejected: "+placed.reason());var site=placed.value();
        h.assertTrue(site.region().rectangularSelection,"Placement failed to opt in new tree farm");
        h.startSequence().thenWaitUntil(()->{
            com.hkin.exiledsettlement.region.ProductionRegionService.tickLevel(l);
            var plans=f.s.terrain().dependingOn(site.id());
            h.assertTrue(plans.stream().flatMap(p->p.steps.stream()).anyMatch(s->s.position().equals(soil) && !s.fill()),"Clay missing real excavation order: "+site.region().status);
            h.assertTrue(plans.stream().flatMap(p->p.steps.stream()).anyMatch(s->s.position().equals(high) && !s.fill()),"Sixth clearance block missing excavation order");
            h.assertTrue(BlueprintService.pendingPlacements(l,site,Set.of()).isEmpty(),"Fences dispatched before preparation completed");
            h.assertTrue(l.getBlockState(soil).is(Blocks.CLAY) && l.getBlockState(high).is(Blocks.OAK_PLANKS),"Planning changed world for free");
        }).thenExecute(()->{System.out.println("TREE_PREPARATION_OK clayAndSixthBlock=true fencesBlocked=true");f.clean();}).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_clearance_phase",template="empty",timeoutTicks=800)
    public static void idleWorkerWalksFromOutsideAndClearsTheWholeSite(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();var min=f.t.minCorner();
        for(var p:BlockPos.betweenClosed(min.offset(-15,-1,-3),min.offset(-1,-1,3))){var chunk=new ChunkPos(p);l.setChunkForced(chunk.x,chunk.z,true);l.setBlock(p,Blocks.STONE.defaultBlockState(),2);}
        f.actor.setNoAi(false);f.actor.moveTo(min.getX()-12.5,min.getY(),min.getZ()+.5,0,0);f.actor.setOnGround(true);
        f.c.updateProjection(f.actor.getUUID(),f.actor.blockPosition());var start=f.actor.position();long wall=System.nanoTime(),tick=l.getGameTime();
        for(var item:List.of(Items.IRON_SHOVEL,Items.IRON_PICKAXE,Items.IRON_AXE))f.c.receiveEquipment(new ItemStack(item));
        f.c.insertIntoInventory(new ItemStack(Items.BREAD,16));
        h.startSequence().thenWaitUntil(()->h.assertTrue(l.areEntitiesLoaded(new ChunkPos(f.actor.blockPosition()).toLong()),"Wait for worker section"))
        .thenExecute(()->h.assertTrue(l.addFreshEntity(f.actor),"Worker spawn failed"))
        .thenWaitUntil(()->{
            l.setDayTime(6000);f.c.updateProjection(f.actor.getUUID(),f.actor.blockPosition());
            if(h.getTick()%8==0)CitizenWorkService.tickSettlement(l,f.data,f.s);
            CitizenWorkService.advanceSiteConstruction(l,f.data,f.s,f.c,f.actor);
            h.assertTrue(f.obstacles.stream().allMatch(p->l.getBlockState(p).isAir()),"Automatic worker has not cleared: "+f.c.workState()+" "+f.c.workFailure()+" @ "+f.actor.blockPosition());
            h.assertTrue(f.actor.position().distanceToSqr(start)>36,"Worker never walked into worksite");
            h.assertTrue(f.c.inventory().stream().filter(s->s.is(Items.DIRT)).mapToInt(ItemStack::getCount).sum()==4,"Real excavation drops missing");
            h.assertTrue(f.c.inventory().stream().filter(s->s.is(Items.IRON_SHOVEL)).anyMatch(s->s.getDamageValue()==4),"Excavation durability missing");
        }).thenExecute(()->{System.out.println("IDLE_CLEARANCE_WALK_OK ticks="+(l.getGameTime()-tick)+" seconds="+((System.nanoTime()-wall)/1e9)+" walked="+Math.sqrt(f.actor.position().distanceToSqr(start))+" drops=4 damage=4");f.clean();}).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_clearance_phase",template="empty")
    public static void allEightRotationsDispatchOnlyClearance(GameTestHelper h) {
        int cases=0;
        for(var type:List.of(BuildingType.HUNTING_LODGE,BuildingType.TOOL_WORKSHOP,BuildingType.TOWN_HALL,BuildingType.FISHING_HUT,
                BuildingType.SAWMILL,BuildingType.SCHOOL,BuildingType.MARKET,BuildingType.TAVERN))for(var facing:Direction.Plane.HORIZONTAL) {
            var at=h.absolutePos(new BlockPos(140000+cases*64,50,0));var t=BlueprintTemplates.create(type,at,facing);
            for(var p:t.placements())h.getLevel().getChunkAt(p.position());
            var s=new SettlementRecord(UUID.randomUUID(),"Rotation",at,UUID.randomUUID());
            var b=s.addBlueprint(type,at,at,facing,s.ownerId(),false,BlueprintTemplates.currentTemplateVersion(type),Map.of());
            var air=t.placements().stream().filter(p->p.mode()==BlueprintPlacementMode.CLEARANCE && BuildingPlacementRules.requiresClearance(t,p.position())).findFirst().orElseThrow();
            h.getLevel().setBlock(air.position(),Blocks.DIRT.defaultBlockState(),2);
            var pending=BlueprintService.pendingPlacements(h.getLevel(),b,Set.of());
            h.assertTrue(!pending.isEmpty() && pending.stream().allMatch(p->p.placement().mode()==BlueprintPlacementMode.CLEARANCE),"Construction bypassed clearance: "+type+" "+facing);
            h.getLevel().setBlock(air.position(),Blocks.AIR.defaultBlockState(),2);cases++;
        }
        System.out.println("CLEARANCE_ROTATIONS_OK cases="+cases);h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_clearance_phase",template="empty")
    public static void claimedObstaclesAndStaleConstructionCannotBypassGate(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();var claimed=new HashSet<>(f.obstacles);
        h.assertTrue(TimedConstructionService.first(l,f.b,claimed)==null && TimedConstructionService.pending(l,f.b,claimed).isEmpty(),"Another worker escaped the claimed clearance phase");
        h.assertTrue(BlueprintService.pendingAt(l,f.b,f.floor.position())==null,"Saved structural target escaped gate");
        var material=new ItemStack(f.floor.material(),2);
        var result=BlueprintService.placeTimedAt(l,f.data,f.s,f.b,f.floor.position(),material,f.actor);
        h.assertTrue(!result.success() && material.getCount()==2 && l.getBlockState(f.floor.position()).isAir(),"Stale execution placed/charged floor before clearance");
        var roof=BlueprintService.pendingAt(l,f.b,f.obstacles.getLast());
        h.assertTrue(roof!=null && roof.placement().mode()==BlueprintPlacementMode.CLEARANCE && roof.placement().material()==null,"Structural obstruction was not a separate excavation");
        f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_clearance_phase",template="empty")
    public static void partialBuildingReloadRetainsFinishedFloorAndLateClearance(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();l.setBlock(f.floor.position(),f.floor.state(),2);
        var loaded=BlueprintRecord.load(f.b.save());
        h.assertTrue(TimedConstructionService.pending(l,loaded,Set.of()).stream().allMatch(p->p.placement().mode()==BlueprintPlacementMode.CLEARANCE
                && !p.placement().position().equals(f.floor.position())),"Reload destroys finished floor or ignores obstacles");
        f.obstacles.forEach(p->l.setBlock(p,Blocks.AIR.defaultBlockState(),2));
        h.assertTrue(TimedConstructionService.first(l,loaded,Set.of())!=null,"No construction after clearance");
        var late=f.obstacles.getFirst();l.setBlock(late,Blocks.DIRT.defaultBlockState(),2);
        h.assertTrue(TimedConstructionService.pending(l,loaded,Set.of()).stream().allMatch(p->p.placement().mode()==BlueprintPlacementMode.CLEARANCE),"Late obstacle failed to restore phase gate");
        h.assertTrue(l.getBlockState(f.floor.position()).equals(f.floor.state()),"Finished structure changed");f.clean();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_clearance_phase",template="empty")
    public static void protectedObstacleStaysAndChargesNothing(GameTestHelper h) {
        var f=fixture(h);var p=f.obstacles.getFirst();var l=h.getLevel();
        Consumer<LivingDestroyBlockEvent> deny=e->{if(e.getEntity()==f.actor && e.getPos().equals(p))e.setCanceled(true);};
        NeoForge.EVENT_BUS.addListener(deny);
        try {
            var result=BlueprintService.placeTimedAt(l,f.data,f.s,f.b,p,ItemStack.EMPTY,f.actor);
            h.assertTrue(!result.success() && l.getBlockState(p).is(Blocks.DIRT) && f.c.inventory().stream().allMatch(ItemStack::isEmpty),"Protection lost obstacle or created drops");
            h.assertTrue(BlueprintService.pendingAt(l,f.b,f.floor.position())==null,"Protected obstacle allowed later construction");
        } finally {NeoForge.EVENT_BUS.unregister(deny);f.clean();}h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_clearance_phase",template="empty",timeoutTicks=500)
    public static void realWorkerClearsBeforeBuildingWithDropsAndDurability(GameTestHelper h) {
        var f=fixture(h);var l=h.getLevel();f.c.receiveEquipment(new ItemStack(Items.IRON_SHOVEL));
        f.c.insertIntoInventory(new ItemStack(f.floor.material(),16));
        f.c.startTask(CitizenWorkState.TO_BLUEPRINT,f.floor.position(),null,f.b.id());
        long start=l.getGameTime(),wall=System.nanoTime();boolean[] cleared={false};
        h.startSequence().thenWaitUntil(()->h.assertTrue(l.areEntitiesLoaded(new ChunkPos(f.actor.blockPosition()).toLong()),"Wait for entity section"))
        .thenExecute(()->h.assertTrue(l.addFreshEntity(f.actor),"Worker spawn failed"))
        .thenWaitUntil(()-> {
            boolean obstacles=f.obstacles.stream().anyMatch(p->l.getBlockState(p).is(Blocks.DIRT));
            CitizenWorkService.advanceSiteConstruction(l,f.data,f.s,f.c,f.actor);
            if(obstacles)h.assertTrue(l.getBlockState(f.floor.position()).isAir(),"Floor built before the last excavation");
            if(f.obstacles.stream().allMatch(p->l.getBlockState(p).isAir()))cleared[0]=true;
            h.assertTrue(cleared[0],"Worker has not cleared all obstructions: "+f.c.workFailure());
            h.assertTrue(f.c.inventory().stream().filter(s->s.is(Items.DIRT)).mapToInt(ItemStack::getCount).sum()==4,"Excavation did not return four real dirt drops");
            h.assertTrue(f.c.inventory().stream().filter(s->s.is(Items.IRON_SHOVEL)).anyMatch(s->s.getDamageValue()==4),"Shovel durability not charged per real excavation");
            var supplied=new ItemStack(f.floor.material());
            var result=BlueprintService.placeTimedAt(l,f.data,f.s,f.b,f.floor.position(),supplied,f.actor);
            h.assertTrue(result.success() && supplied.isEmpty() && l.getBlockState(f.floor.position()).equals(f.floor.state()),"Cleared site did not unlock paid construction");
        }).thenExecute(()->{System.out.println("CLEARANCE_WORKER_OK ticks="+(l.getGameTime()-start)+" wallSeconds="+((System.nanoTime()-wall)/1.0e9)+" dirt=4 toolDamage=4 builtAfterClearance=true");f.clean();}).thenSucceed();
    }
}
