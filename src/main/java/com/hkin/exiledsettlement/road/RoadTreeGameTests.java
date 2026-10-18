package com.hkin.exiledsettlement.road;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import java.util.*;

@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_roadtrees")
@PrefixGameTestTemplate(false)
public final class RoadTreeGameTests {
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(RegisterGameTestsEvent e){if("timed_construction".equals(System.getProperty("exiledsettlement.testSuite")))e.register(RoadTreeGameTests.class);}
    private static int serial;
    private record Fixture(SettlementRecord s,BlockPos at){}
    private static Fixture fixture(GameTestHelper h){
        var l=h.getLevel();var at=h.absolutePos(new BlockPos(2100000+serial++*512,12,0));
        for(int x=-2;x<=3;x++)for(int z=-2;z<=3;z++){var p=at.offset(x*16,0,z*16);l.getChunkAt(p);l.setChunkForced(p.getX()>>4,p.getZ()>>4,true);}
        for(var p:BlockPos.betweenClosed(at.offset(-12,-1,-10),at.offset(35,-1,40))){for(int y=p.getY();y>=l.getMinBuildHeight();y--)l.setBlock(p.atY(y),Blocks.DIRT.defaultBlockState(),18);for(int y=1;y<=18;y++)l.setBlock(p.above(y),Blocks.AIR.defaultBlockState(),18);}
        var s=SettlementSavedData.get(l).createOrRecover(at.west(8),UUID.randomUUID(),"Tree road test").value();
        s.upgradeLevel();s.upgradeLevel();for(int i=0;i<12;i++)for(int x=-2;x<=3;x++)for(int z=-2;z<=3;z++)s.claimChunk((at.getX()>>4)+x,(at.getZ()>>4)+z);
        s.terrain().enabled=false;s.autoRoads().enabled=false;s.decorations().enabled=false;s.community().stewardEnabled=false;
        for(var c:s.citizens())c.setManualAssignment(CitizenProfession.HERDSMAN,null);
        l.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_RANDOMTICKING).set(0,l.getServer());
        l.setDayTime(1000);TreeSurvey.invalidate(l);return new Fixture(s,at);
    }
    private static void tree(GameTestHelper h,BlockPos root,int height,Block log){
        var l=h.getLevel();l.setBlock(root.below(),Blocks.DIRT.defaultBlockState(),18);
        for(int y=0;y<height;y++)l.setBlock(root.above(y),log.defaultBlockState(),18);
        l.setBlock(root.above(height),Blocks.OAK_LEAVES.defaultBlockState(),18);TreeSurvey.invalidate(l);
    }
    @GameTest(templateNamespace="exiledsettlement_roadtrees",template="empty")
    public static void roadAcceptsTreeButNotTimberOrProtectedLeaves(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var p=f.at().east(6);tree(h,p,10,Blocks.OAK_LOG);
        h.assertTrue(RoadEarthworkService.plannable(l,f.s(),p.below()),"Ordinary tree rejected by road planning");
        h.assertTrue(RoadEarthworkService.effort(l,f.s(),p.below())==RoadEarthworkService.effort(l,f.s(),p.west(3).below()),"Trees distort road cost");
        var pole=p.east(5);l.setBlock(pole,Blocks.OAK_LOG.defaultBlockState(),18);l.setBlock(pole.above(),Blocks.OAK_LOG.defaultBlockState(),18);
        h.assertTrue(!TreeSurvey.fresh(l,pole).tree()&&!RoadEarthworkService.plannable(l,f.s(),pole.below()),"Timber pole recognised as clearable tree");
        var hedge=p.west(4);l.setBlock(hedge,Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true),18);
        h.assertTrue(!RoadEarthworkService.plannable(l,f.s(),hedge.below()),"Persistent hedge removed for road");f.s().deactivate();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_roadtrees",template="empty")
    public static void touchingTreesAreSeparateButGiantTrunkIsOne(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var a=f.at().east(4);var b=a.east();tree(h,a,8,Blocks.OAK_LOG);tree(h,b,9,Blocks.OAK_LOG);
        var own=TreeSurvey.fresh(l,a);h.assertTrue(own.tree()&&own.parts().containsKey(a.above(7))&&!own.parts().containsKey(b),"Adjacent independent trunks merged");
        var root=a.east(8);for(int x=0;x<2;x++)for(int z=0;z<2;z++)tree(h,root.offset(x,0,z),10,Blocks.DARK_OAK_LOG);
        var giant=TreeSurvey.fresh(l,root);h.assertTrue(giant.tree()&&giant.parts().values().stream().filter(s->s.is(Blocks.DARK_OAK_LOG)).count()==40,"Giant trunk split");
        var claimed=TreeSurvey.claimed(l,Set.of(root));h.assertTrue(claimed.contains(root.offset(1,9,1)),"Same-tree work is not reserved");f.s().deactivate();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_roadtrees",template="empty")
    public static void formalBuildingAndProductionTreeRejectWholeTreeExpansion(GameTestHelper h){
        var f=fixture(h);var p=f.at().east(4);tree(h,p,8,Blocks.OAK_LOG);
        f.s().addBuilding(BuildingType.HOUSE,p.above(4),Direction.NORTH,p.above(4),p.above(5),0,1);
        h.assertTrue(!ResidentTreeFelling.plannable(h.getLevel(),f.s(),p),"Tree expansion enters formal building");
        var b=p.east(8);tree(h,b,8,Blocks.OAK_LOG);f.s().addBuilding(BuildingType.TREE_FARM,b,Direction.NORTH,b,b.above(1),0,1);
        h.assertTrue(!ResidentTreeFelling.plannable(h.getLevel(),f.s(),b.above(5)),"Road consumes production tree");f.s().deactivate();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_roadtrees",template="empty",timeoutTicks=600)
    public static void productionPlannerKeepsSameStraightGeometryAndResumes(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var start=f.at().below();var end=start.east(18);
        var plans=new AutoRoadPlanner.Plan[]{new AutoRoadPlanner.Plan(l,f.s(),UUID.randomUUID(),start,end,"proof"),null};
        var restored=new boolean[]{false};
        h.startSequence().thenWaitUntil(()->{plans[0].advance(l,f.s(),System.nanoTime()+2_000_000);h.assertTrue(plans[0].finished,"Bare ground planning");})
        .thenExecute(()->{
            h.assertTrue(plans[0].expanded!=null,"Bare route failed");
            for(int x:new int[]{4,8,12})for(int z=-1;z<=1;z++)tree(h,f.at().offset(x,0,z),8+(z+1),Blocks.OAK_LOG);
            plans[1]=new AutoRoadPlanner.Plan(l,f.s(),UUID.randomUUID(),start,end,"proof");
        }).thenWaitUntil(()->{
            plans[1].advance(l,f.s(),System.nanoTime()+2_000_000);
            if(plans[1].phase==1&&!restored[0]){plans[1]=AutoRoadPlanner.Plan.restore(l,f.s(),plans[1].save());TreeSurvey.invalidate(l);restored[0]=true;}
            h.assertTrue(plans[1].finished,"Tree survey/planning: "+f.s().autoRoads().status);
        }).thenExecute(()->{
            h.assertTrue(restored[0]&&plans[1].expanded!=null&&plans[0].line.equals(plans[1].line)&&plans[0].expanded.equals(plans[1].expanded),"Trees changed centerline, width or height");
            for(int x:new int[]{4,8,12})h.assertTrue(l.getBlockState(f.at().east(x)).is(Blocks.OAK_LOG),"Planning chopped trees");
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("ROADTREE_GEOMETRY sameLine=true sameCorridor=true trees=9 resumed=true nodes={}",plans[1].checked);
            f.s().deactivate();
        }).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_roadtrees",template="empty")
    public static void publicationContainsActualTreeClearanceAndKeepsPaidProgress(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var at=f.at().east(6);tree(h,at,10,Blocks.OAK_LOG);
        var route=new AutoRoadState.Route(UUID.randomUUID(),at.below(),List.of(at.below()));route.width=3;route.centerline=route.cells;
        f.s().autoRoads().enabled=true;f.s().autoRoads().routes.put("test",route);
        h.assertTrue(PublicGroundService.publishRoadColumn(l,f.s(),route,at.below()),"Road publication still rejects planned tree");
        var projects=f.s().terrain().projects.values().stream().filter(p->p.dependencies.contains(route.id)).toList();
        h.assertTrue(!projects.isEmpty()&&projects.stream().flatMap(p->p.steps.stream()).filter(p->!p.fill()&&p.original().is(Blocks.OAK_LOG)).count()==4,"Four-block corridor lacks real clearing targets");
        for(var p:projects){var restored=TerrainProject.load(p.save());h.assertTrue(restored.steps.equals(p.steps)&&restored.dependencies.equals(p.dependencies)&&restored.finished.isEmpty(),"Saved clearing changed ownership or fabricated progress");}
        h.assertTrue(l.getBlockState(at).is(Blocks.OAK_LOG),"Publishing modified tree");f.s().deactivate();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_roadtrees_entity",template="empty",timeoutTicks=16000,batch="tree_road_entity")
    public static void workerFetchesClearsTreesPavesAndWalksBothDirections(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var s=f.s();var at=f.at();var data=SettlementSavedData.get(l);
        var wagonPos=at.east(18);l.setBlock(wagonPos,Blocks.CHEST.defaultBlockState(),2);
        var wagon=s.addBuilding(BuildingType.WAGON,wagonPos,Direction.WEST,wagonPos,wagonPos,0,1);
        wagon.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of());
        var t=BlueprintTemplates.create(BuildingType.WAREHOUSE,at.offset(4,0,25),Direction.NORTH);
        for(var v:t.placements())l.setBlock(v.position(),v.state(),2);
        for(var v:t.placements())BlueprintBlockStateResolver.refreshExisting(l,t,v);
        var warehouse=s.addBuilding(t.type(),t.registrationPosition(),t.facing(),t.minCorner(),t.maxCorner(),t.transitionMargin(),BlueprintTemplates.currentTemplateVersion(t.type()));
        BuildingFunctionService.validateSingle(l,data,s,warehouse);h.assertTrue(warehouse.operational(),"Real warehouse fixture invalid");
        for(var item:List.of(Items.IRON_AXE,Items.IRON_PICKAXE,Items.IRON_SHOVEL))WarehouseInventoryService.insert(l,warehouse,new ItemStack(item,4));
        for(var item:List.of(Items.DIRT,Items.COBBLESTONE,Items.BREAD))WarehouseInventoryService.insert(l,warehouse,new ItemStack(item,256));
        var root=at.below();var end=AutoRoadService.entrances(l,s,wagon).getFirst();
        var planning=new AutoRoadPlanner.Plan(l,s,wagon.id(),root,end,"tree-chain");
        for(int x:new int[]{5,9,13})tree(h,at.east(x),10,Blocks.OAK_LOG);
        var c=s.citizens().iterator().next();c.assignProfession(CitizenProfession.WORKER,null);c.setManualAssignment(CitizenProfession.WORKER,null);
        var actor=com.hkin.exiledsettlement.registry.ModEntities.SETTLEMENT_CITIZEN.get().create(l);
        actor.moveTo(at.getX()+.5,at.getY(),at.getZ()+10.5,0,0);
        actor.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());actor.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
        l.addFreshEntity(actor);c.updateProjection(actor.getUUID(),actor.blockPosition());
        var route=new AutoRoadState.Route[1];var fetched=new boolean[]{false};var cut=new boolean[]{false};var walk=new int[]{0};
        var since=new long[]{l.getGameTime()};
        h.onEachTick(()->{
            h.assertTrue(actor.isAlive(),"Worker died");
            if(c.inventory().stream().anyMatch(i->i.is(Items.IRON_AXE)))fetched[0]=true;
            if(l.getBlockState(at.east(5)).isAir())cut[0]=true;
            if(l.getGameTime()%200==0)com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("ROADTREE_CHAIN elapsed={} road={}/{} state={} failure={} position={} target={}",l.getGameTime()-since[0],route[0]==null?0:route[0].completed.cardinality(),route[0]==null?0:route[0].cells.size(),c.workState(),c.workFailure(),actor.position(),c.targetPosition());
        });
        h.startSequence().thenWaitUntil(()->{planning.advance(l,s,System.nanoTime()+2_000_000);h.assertTrue(planning.finished,"Tree route planning");})
        .thenExecute(()->{
            h.assertTrue(planning.expanded!=null&&planning.line.stream().allMatch(p->p.getY()==root.getY()&&p.getZ()==root.getZ()),"Tree route bends or rises");
            route[0]=new AutoRoadState.Route(wagon.id(),end,List.copyOf(planning.expanded));route[0].centerline=List.copyOf(planning.line);route[0].width=3;route[0].areaOptimized=true;route[0].adaptiveVersion=AdaptiveRoadService.VERSION;route[0].reviewAfter=Long.MAX_VALUE;
            s.autoRoads().routes.put("tree-chain",route[0]);s.autoRoads().enabled=true;
        }).thenWaitUntil(()->h.assertTrue(route[0].progress>=route[0].cells.size(),"Waiting real tree clearing and paving"))
        .thenExecute(()->{
            h.assertTrue(fetched[0]&&cut[0],"Missing real tool fetching/tree clearance");
            int logs=WarehouseInventoryService.countItem(l,warehouse,Items.OAK_LOG)+s.citizens().stream().flatMap(v->v.inventory().stream()).filter(i->i.is(Items.OAK_LOG)).mapToInt(ItemStack::getCount).sum();
            var box=(net.minecraft.world.Container)l.getBlockEntity(wagonPos);
            for(int i=0;i<box.getContainerSize();i++)if(box.getItem(i).is(Items.OAK_LOG))logs+=box.getItem(i).getCount();
            h.assertTrue(logs==30,"Tree logs lost or duplicated: "+logs);
            for(int x:new int[]{5,9,13})for(int y=0;y<=10;y++)h.assertTrue(l.getBlockState(at.east(x).above(y)).isAir(),"Tree left above road");
            h.assertTrue(route[0].cells.stream().allMatch(p->l.getBlockState(p).is(Blocks.DIRT_PATH)),"Registered road differs from physical road");
            c.clearWork();c.setManualAssignment(CitizenProfession.HERDSMAN,null);c.assignProfession(CitizenProfession.HERDSMAN,null);s.autoRoads().enabled=false;
            // Construction above uses the live scheduler. Isolate the subsequent navigation
            // proof from idle/meal/bed destinations, retaining the real entity and physics.
            s.deactivate();actor.stopSettlementNavigation();
            actor.navigateTo(root.above(),.65D);
        }).thenWaitUntil(()->{actor.navigateTo(root.above(),.65D);h.assertTrue(actor.blockPosition().distManhattan(root.above())<=1,"Walking to near road end");})
        .thenExecute(()->{walk[0]++;actor.navigateTo(end.above(),.65D);})
        .thenWaitUntil(()->{actor.navigateTo(end.above(),.65D);h.assertTrue(actor.blockPosition().distManhattan(end.above())<=1,"Walking through cleared road");})
        .thenExecute(()->{walk[0]++;actor.navigateTo(root.above(),.65D);})
        .thenWaitUntil(()->{actor.navigateTo(root.above(),.65D);h.assertTrue(actor.blockPosition().distManhattan(root.above())<=1,"Walking back through cleared road");})
        .thenExecute(()->{
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("ROADTREE_CHAIN_COMPLETE trees=3 logs=30 fetched=true paved={} twoWay=true ticks={}",route[0].cells.size(),l.getGameTime()-since[0]);
            h.assertTrue(walk[0]==2,"Missing full traversal");s.deactivate();actor.discard();
            if(Boolean.getBoolean("exiledsettlement.roadTreeNative")){
                var p=at.east(25);tree(h,p,10,Blocks.OAK_LOG);
                var pending=new AutoRoadState.Route(UUID.randomUUID(),p.below(),List.of(p.below()));pending.centerline=pending.cells;pending.width=3;
                s.autoRoads().routes.put("restart-pending",pending);
                h.assertTrue(PublicGroundService.publishRoadColumn(l,s,pending,p.below()),"Pending restart fixture cannot publish");data.touch();
            }
        }).thenSucceed();
    }
    @GameTest(templateNamespace="exiledsettlement_roadtrees",template="empty")
    public static void replacingPaidTreeDetourKeepsOldRoadUntilNewRouteIsBuilt(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var start=f.at().below();var line=new ArrayList<BlockPos>();
        for(int x=0;x<=4;x++)line.add(start.east(x));
        for(int z=1;z<=4;z++)line.add(start.offset(4,0,z));
        for(int x=5;x<=12;x++)line.add(start.offset(x,0,4));
        for(int z=3;z>=0;z--)line.add(start.offset(12,0,z));
        for(int x=13;x<=16;x++)line.add(start.east(x));
        var cells=AutoRoadService.corridor(l,f.s(),line);h.assertTrue(!cells.isEmpty(),"Old detour fixture invalid");
        for(var p:cells)l.setBlock(p,Blocks.DIRT_PATH.defaultBlockState(),18);RoadSpaceData.get(l).addAll(cells);
        var old=new AutoRoadState.Route(UUID.randomUUID(),start.east(16),cells);old.centerline=List.copyOf(line);old.width=3;old.completed.set(0,cells.size());old.progress=cells.size();old.accessConnected=true;
        f.s().autoRoads().routes.put("detour",old);tree(h,f.at().east(8),10,Blocks.OAK_LOG);
        var straight=new ArrayList<BlockPos>();for(int x=0;x<=16;x++)straight.add(start.east(x));
        var proposed=new AutoRoadState.Route(old.building,old.entrance,AutoRoadService.corridor(l,f.s(),straight));proposed.centerline=List.copyOf(straight);proposed.width=3;
        h.assertTrue(AdaptiveRoadService.accept(l,f.s(),"detour",proposed),"Paid detour permanently blocks straighter replacement");
        h.assertTrue(proposed.id.equals(old.id)&&!proposed.retired.isEmpty()&&proposed.progress<proposed.cells.size(),"Replacement lost identity or pretended completion");
        for(var p:cells)h.assertTrue(l.getBlockState(p).is(Blocks.DIRT_PATH),"Old paid road removed before new road exists");
        h.assertTrue(l.getBlockState(f.at().east(8)).is(Blocks.OAK_LOG),"Road replacement freely cut a tree");f.s().deactivate();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_roadtrees",template="empty")
    public static void unloadedTreeBoundaryIsNotPartialTreePermission(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var far=f.at().east(4096);var p=new BlockPos((far.getX()>>4)*16+15,far.getY(),(far.getZ()>>4)*16+8);
        l.getChunkAt(p);tree(h,p,8,Blocks.OAK_LOG);
        h.assertTrue(!l.hasChunkAt(p.east()),"Fixture boundary already loaded");
        var result=TreeSurvey.fresh(l,p);
        h.assertTrue(result.kind()==TreeSurvey.Kind.BLOCKED&&result.reason().equals("BLUEPRINT_AREA_NOT_LOADED"),"Unloaded tree accepted as complete");
        h.assertTrue(l.getBlockState(p).is(Blocks.OAK_LOG)&&!l.hasChunkAt(p.east()),"Survey modified tree or loaded neighboring chunk");f.s().deactivate();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_roadtrees",template="empty")
    public static void touchingMangroveRootsDoNotJoinIndependentStems(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();var a=f.at().east(5);var b=a.east(4);
        for(int x=0;x<=4;x++)l.setBlock(a.east(x),Blocks.MANGROVE_ROOTS.defaultBlockState(),18);
        for(var p:List.of(a,b)){
            for(int y=1;y<=8;y++)l.setBlock(p.above(y),Blocks.MANGROVE_LOG.defaultBlockState(),18);
            l.setBlock(p.above(9),Blocks.MANGROVE_LEAVES.defaultBlockState(),18);
        }
        var own=TreeSurvey.fresh(l,a);
        h.assertTrue(own.tree()&&own.parts().containsKey(a.above(8))&&!own.parts().containsKey(b)&&!own.parts().containsKey(b.above()),"Touching mangrove roots merged two stems");
        f.s().deactivate();h.succeed();
    }
    @GameTest(templateNamespace="exiledsettlement_roadtrees_entity",template="empty",timeoutTicks=2400,batch="tree_road_profile")
    public static void tallForestPlanningDayAndNight(GameTestHelper h){
        var f=fixture(h);var l=h.getLevel();f.s().deactivate();
        for(int x:new int[]{4,8,12})for(int z=-1;z<=1;z++)tree(h,f.at().offset(x,0,z),32,Blocks.OAK_LOG);
        var plans=new AutoRoadPlanner.Plan[]{new AutoRoadPlanner.Plan(l,f.s(),UUID.randomUUID(),f.at().below(),f.at().east(18).below(),"day"),null};
        h.startSequence().thenWaitUntil(()->{
            long before=System.nanoTime();plans[0].advance(l,f.s(),before+2_000_000);TreeWorkMetrics.record("plan_day",before);
            h.assertTrue(plans[0].finished,"Daytime tall forest planning");
        }).thenExecute(()->{l.setDayTime(14000);TreeSurvey.invalidate(l);plans[1]=new AutoRoadPlanner.Plan(l,f.s(),UUID.randomUUID(),f.at().below(),f.at().east(18).below(),"night");})
        .thenWaitUntil(()->{
            long before=System.nanoTime();plans[1].advance(l,f.s(),before+2_000_000);TreeWorkMetrics.record("plan_night",before);
            h.assertTrue(plans[1].finished,"Nighttime tall forest planning");
        }).thenExecute(()->{
            h.assertTrue(plans[0].expanded!=null&&plans[0].expanded.equals(plans[1].expanded),"Day/night tall forest geometry differs");
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("ROADTREE_DAY_NIGHT trees=9 height=32 sameGeometry=true dayNodes={} nightNodes={}",plans[0].checked,plans[1].checked);
        }).thenSucceed();
    }
}
