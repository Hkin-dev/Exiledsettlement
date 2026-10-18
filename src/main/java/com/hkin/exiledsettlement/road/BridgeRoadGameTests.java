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
import java.util.*;

@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_bridgeroads") @PrefixGameTestTemplate(false)
public final class BridgeRoadGameTests {
 @net.neoforged.bus.api.SubscribeEvent public static void register(net.neoforged.neoforge.event.RegisterGameTestsEvent e){if("bridge_roads".equals(System.getProperty("exiledsettlement.testSuite")))e.register(BridgeRoadGameTests.class);}
 private static int serial;
 @GameTest(templateNamespace="exiledsettlement_bridgeroads",template="empty",timeoutTicks=100)
 public static void blockedOldBridgeRepairDoesNotStarveNewApproachPublication(GameTestHelper h){
  var f=scene(h,false,0,false);var s=f.s();var l=h.getLevel();s.autoRoads().enabled=true;s.autoRoads().workReady=true;
  var blocked=f.p().accessSurfaces.iterator().next();var repair=new AutoRoadState.Route(f.p().id,blocked,List.of(blocked));repair.repairs.set(0);
  var end=BridgeRoadConnections.entrances(s,f.p().id).getFirst();var road=new AutoRoadState.Route(f.p().id,end,List.of(f.at().offset(-10,-1,0),f.at().offset(-9,-1,0)));
  s.autoRoads().routes.put("old repair",repair);s.autoRoads().routes.put("new approach",road);
  for(int i=0;i<8;i++)RoadEarthworkService.tickLevel(l);
  h.assertTrue(road.earthworkCursor==road.cells.size(),"Blocked old bridge repair monopolized road publication");
  h.assertTrue(!repair.repairs.isEmpty()&&!repair.suppressed&&!f.p().completed,"Waiting repair or unpaid bridge was discarded");
  s.deactivate();h.succeed();
 }
 @GameTest(templateNamespace="exiledsettlement_bridgeroads",template="empty",timeoutTicks=100)
 public static void bridgeApproachFoundationUsesBankWorksiteAndHonoursCancellation(GameTestHelper h){
  var f=scene(h,false,1,true,2,8);var s=f.s();var l=h.getLevel();var end=BridgeRoadConnections.entrances(s,f.p().id).getFirst();
  var cells=new ArrayList<BlockPos>();for(int x=-15;x<=-1;x++)cells.add(f.at().offset(x,0,0));
  var route=new AutoRoadState.Route(f.p().id,end,List.copyOf(cells));s.autoRoads().routes.put("approach",route);
  var target=end.below(5);var work=new TerrainProject(UUID.randomUUID(),"Bridge approach",target,List.of(new TerrainProject.Step(target,Blocks.COBBLESTONE.defaultBlockState(),l.getBlockState(target))));
  work.road=true;work.dependencies.add(route.id);s.terrain().index(work);var site=s.resumeTerrain(work);var feet=f.at().offset(-15,0,0);
  h.assertTrue(TimedConstructionService.arrivedAt(l,site,feet,target),"Dry bank cannot build its distant submerged approach column");
  h.assertTrue(!TimedConstructionService.arrivedAt(l,site,f.at().offset(15,0,0),target),"Opposite bank counted as this approach worksite");
  route.suppressed=true;h.assertTrue(!TimedConstructionService.arrivedAt(l,site,feet,target),"Cancelled approach kept its shared worksite");
  route.suppressed=false;work.dependencies.clear();h.assertTrue(!TimedConstructionService.arrivedAt(l,site,feet,target),"Unrelated earthwork gained bridge worksite");
  s.deactivate();h.succeed();
 }
 private record Scene(SettlementRecord s,BlockPos at,TerrainProject p){}
 private static Scene scene(GameTestHelper h,boolean z,int rise,boolean built){
  return scene(h,z,rise,built,1);
 }
 private static Scene scene(GameTestHelper h,boolean z,int rise,boolean built,int halfWidth){
  return scene(h,z,rise,built,halfWidth,0);
 }
 private static Scene scene(GameTestHelper h,boolean z,int rise,boolean built,int halfWidth,int gap){
  var l=h.getLevel();var at=h.absolutePos(new BlockPos(1600000+serial++*256,65,0));
  for(int x=-30;x<=30;x++)for(int v=-25;v<=25;v++){
   var p=at.offset(x,0,v);l.getChunkAt(p);l.setChunkForced(p.getX()>>4,p.getZ()>>4,true);
   int row=z?v:x;for(int y=-6;y<=7;y++)l.setBlock(p.above(y),(y==-6?Blocks.STONE:y<0 && (row< -gap || row>7+gap)?Blocks.DIRT:gap>0&&y< -1?Blocks.WATER:Blocks.AIR).defaultBlockState(),2);
  }
  var core=at.offset(z?0:-15,0,z?-15:0);var s=SettlementSavedData.get(l).createOrRecover(core,UUID.randomUUID(),"Bridge road test").value();s.upgradeLevel();s.upgradeLevel();
  for(int pass=0;pass<10;pass++)for(int x=-2;x<=2;x++)for(int v=-2;v<=2;v++)s.claimChunk((at.getX()>>4)+x,(at.getZ()>>4)+v);
  s.terrain().enabled=false;s.autoRoads().enabled=false;s.decorations().enabled=false;s.community().stewardEnabled=false;
  for(var c:s.citizens())c.setManualAssignment(CitizenProfession.HERDSMAN,null);
  l.setDayTime(4000);l.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false,l.getServer());
  var b=new PlayerBridgePlan(at.offset(z?-halfWidth:0,-1+rise,z?0:-halfWidth),at.offset(z?halfWidth:7,-1+rise,z?7:halfWidth),!z,Integer.MIN_VALUE);
  for(int i=0;i<10000&&!b.ready&&b.problem.isEmpty();i++)b.survey(l,s,8);
  h.assertTrue(b.ready,"Bridge fixture rejected: "+b.problem);var site=PlayerBridgePlan.publish(l,s,b);var project=site.terrainProject();
  if(built){for(int i=0;i<project.steps.size();i++){var step=project.steps.get(i);l.setBlock(step.position(),step.target(),2);project.mark(i);}TerrainService.completed(l,s,site);}
  return new Scene(s,at,project);
 }
 private static void planning(GameTestHelper h,boolean z,int rise){
  var f=scene(h,z,rise,false);var s=f.s();var l=h.getLevel();var original=f.p().save();
  AutoRoadService.tick(l,s);h.assertTrue(s.autoRoads().routes.isEmpty()&&!AutoRoadPlanner.busy(s),"Disabled automatic roads created routes");
  s.autoRoads().enabled=true;
  h.onEachTick(()->{AutoRoadService.tick(l,s);AutoRoadPlanner.tickLevel(l);});
  h.startSequence().thenWaitUntil(()->h.assertTrue(s.autoRoads().routes.size()==2,"Both bridge exits not planned: "+s.autoRoads().status+" / "+AutoRoadPlanner.progress(l,s)))
   .thenExecute(()->{
    var exits=BridgeRoadConnections.entrances(s,f.p().id);
    for(var end:exits){var route=s.autoRoads().routes.values().stream().filter(r->r.entrance.equals(end)).findFirst().orElseThrow();
     h.assertTrue(AutoRoadService.parentValid(l,s,route)&&!route.manualBridge,"Invalid bridge route parent");
     h.assertTrue(end.getY()==f.at().getY()-1+rise,"Road endpoint lowered from deck");
     h.assertTrue(!route.accessConnected,"Unbuilt bridge marked connected");
     h.assertTrue(route.cells.stream().noneMatch(p->f.p().bridgeRails.contains(p.above())),"Road widens into bridge rails");
     if(rise>0){
      var stair=route.cells.stream().filter(p->{var target=RoadEarthworkService.surfaceTarget(l,s,route,p);return target!=null&&target.getBlock() instanceof StairBlock;}).findFirst().orElseThrow();
      h.assertTrue(PublicGroundService.publishRoadColumn(l,s,route,stair),"Approach stair not published");
      var owner=s.terrain().projects.get(s.terrain().cells.get(stair));
      h.assertTrue(owner!=null&&!owner.done(),"Missing unpaid stair work");
      PublicGroundService.publishRoadColumn(l,s,route,stair);
      h.assertTrue(owner.abandoned.isEmpty()&&!owner.done(),"Repeated road planning retired its own unpaid stair");
     }
    }
    h.assertTrue(f.p().dependencies.isEmpty()&&f.p().finished.isEmpty(),"Road planning changed bridge lifecycle");
    var restored=new AutoRoadState();restored.load(s.autoRoads().save());h.assertTrue(restored.routes.size()==2,"Saved routes lost");
    s.deactivate();AutoRoadPlanner.invalidate(s);
   }).thenSucceed();
 }
 @GameTest(templateNamespace="exiledsettlement_bridgeroads",template="empty",timeoutTicks=4000) public static void bothEndsOfUnbuiltNarrowBridgeBecomeRoadTargets(GameTestHelper h){planning(h,false,0);}
 @GameTest(templateNamespace="exiledsettlement_bridgeroads",template="empty",timeoutTicks=4000) public static void raisedZBridgeKeepsBothRoadEndpointHeights(GameTestHelper h){planning(h,true,2);}
 @GameTest(templateNamespace="exiledsettlement_bridgeroads",template="empty",timeoutTicks=100) public static void completedBridgeParentAndExplicitCancellationPersist(GameTestHelper h){
  var f=scene(h,false,0,true);var s=f.s();var ends=BridgeRoadConnections.entrances(s,f.p().id);
  h.assertTrue(s.blueprint(f.p().id)==null,"Fixture bridge still a blueprint");
  for(var end:ends){var r=new AutoRoadState.Route(f.p().id,end,List.of(end));r.suppressed=true;s.autoRoads().routes.put(f.p().id+":"+end.asLong(),r);h.assertTrue(AutoRoadService.parentValid(h.getLevel(),s,r),"Completed bridge lost road parent");}
  h.assertTrue(!BridgeRoadConnections.plan(h.getLevel(),s),"Cancelled endpoint road recreated");f.p().cancelled=true;
  h.assertTrue(s.autoRoads().routes.values().stream().noneMatch(r->AutoRoadService.parentValid(h.getLevel(),s,r)),"Cancelled bridge retains automatic targets");s.deactivate();h.succeed();
 }
 @GameTest(templateNamespace="exiledsettlement_bridgeroads",template="empty",timeoutTicks=100)
 public static void oldPublicGradeDoesNotSuppressBridgeApproachSurvey(GameTestHelper h){
  var f=scene(h,false,1,true);var s=f.s();var l=h.getLevel();var end=BridgeRoadConnections.entrances(s,f.p().id).getFirst();
  var old=new TerrainProject(UUID.randomUUID(),"Old public plane",end.below(),List.of(new TerrainProject.Step(end.below(),Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));
  old.publicGround=true;old.explicit=false;old.groundSurfaces.add(end.below());old.mark(0);old.completed=true;s.terrain().index(old);
  h.assertTrue(!RoadEarthworkService.plannable(l,s,end),"Old committed plane fixture missing");
  h.assertTrue(BridgeRoadConnections.plan(l,s)&&AutoRoadPlanner.busy(s),"Old public plane prevents bridge endpoint survey");
  h.assertTrue(old.abandoned.isEmpty()&&old.completed,"Mathematical survey changed paid public work");AutoRoadPlanner.invalidate(s);
  var route=new AutoRoadState.Route(f.p().id,end,List.of(end));s.autoRoads().routes.put(f.p().id+":"+end.asLong(),route);
  h.assertTrue(AdaptiveRoadService.reviewCommittedConflict(l,s)&&AutoRoadPlanner.busy(s),"Bridge route excluded from committed grade conflict review");
  h.assertTrue(old.abandoned.isEmpty(),"Grade review retires work before replacement publication");AutoRoadPlanner.invalidate(s);s.deactivate();h.succeed();
 }
 @GameTest(templateNamespace="exiledsettlement_bridgeroads",template="empty",timeoutTicks=20000,batch="bridge_road_entity")
 public static void realWorkerBuildsBothRaisedApproachesAndWalksAcrossAndBack(GameTestHelper h){
  var f=scene(h,false,1,true,4,4);var l=h.getLevel();var s=f.s();var at=f.at();var data=SettlementSavedData.get(l);
  var t=BlueprintTemplates.create(BuildingType.WAREHOUSE,at.offset(-24,0,18),Direction.NORTH);
  for(var v:t.placements())l.setBlock(v.position(),v.state(),2);
  for(var v:t.placements())BlueprintBlockStateResolver.refreshExisting(l,t,v);
  var warehouse=s.addBuilding(t.type(),t.registrationPosition(),t.facing(),t.minCorner(),t.maxCorner(),t.transitionMargin(),BlueprintTemplates.currentTemplateVersion(t.type()));
  BuildingFunctionService.validateSingle(l,data,s,warehouse);h.assertTrue(warehouse.operational(),"Warehouse fixture invalid");
  for(var item:List.of(Items.DIRT,Items.COBBLESTONE,Items.COBBLESTONE_STAIRS,Items.BREAD))WarehouseInventoryService.insert(l,warehouse,new ItemStack(item,512));
  for(var item:List.of(Items.IRON_SHOVEL,Items.IRON_PICKAXE,Items.IRON_AXE))for(int i=0;i<8;i++)WarehouseInventoryService.insert(l,warehouse,new ItemStack(item));
  var c=s.citizens().iterator().next();c.setManualAssignment(CitizenProfession.WORKER,null);c.assignProfession(CitizenProfession.WORKER,null);
  var actor=com.hkin.exiledsettlement.registry.ModEntities.SETTLEMENT_CITIZEN.get().create(l);
  actor.moveTo(at.getX()-15.5,at.getY(),at.getZ()+.5,0,0);actor.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());actor.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());l.addFreshEntity(actor);c.updateProjection(actor.getUUID(),actor.blockPosition());
  int[] trip={0};boolean[] fetched={false};long[] start={l.getGameTime()};s.autoRoads().enabled=true;
  h.onEachTick(()->{
   h.assertTrue(actor.isAlive()&&!actor.isInWater(),"Road worker died or entered water: "+actor.position()+" task="+c.workState()+" target="+c.targetPosition());
   h.assertTrue(f.p().dependencies.isEmpty(),"Approaches became bridge dependencies");
   if(c.inventory().stream().anyMatch(v->v.is(Items.COBBLESTONE_STAIRS)||v.is(Items.DIRT)))fetched[0]=true;
   var routes=s.autoRoads().routes.values().stream().filter(r->r.building.equals(f.p().id)).toList();
   if(l.getGameTime()%200==0)com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("BRIDGEROADS_PROGRESS routes={} state={} failure={} pos={} status={} blockers={}",routes.size(),c.workState(),c.workFailure(),actor.position(),s.autoRoads().status,routes.stream().map(r->r.progress+"/"+r.cells.size()+":"+RoadEarthworkService.accessBlocker(l,s,r)).toList());
   if(l.getGameTime()%1000==0)for(var r:routes){
    var end=r.entrance;var works=s.terrain().projectsAt(end).stream().limit(3).map(p->p.name+" done="+p.done()+" cancelled="+p.cancelled+" abandoned="+p.abandoned+" steps="+p.indices(end).stream().map(i->p.steps.get(i)+" finished="+p.finished.get(i)).toList()).toList();
    com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("BRIDGEROADS_DETAIL end={} actual={} goal={} status={} cursor={} projects={} task={} inventory={}",end,l.getBlockState(end),RoadEarthworkService.surfaceTarget(l,s,r,end),r.status,r.earthworkCursor,works,c.targetPosition(),c.inventory());
   }
   if(trip[0]==0 && routes.size()==2 && routes.stream().allMatch(r->r.accessConnected&&r.cells.stream().allMatch(RoadSpaceData.get(l)::contains))){
    trip[0]=1;c.clearWork();c.setManualAssignment(CitizenProfession.HERDSMAN,null);c.assignProfession(CitizenProfession.HERDSMAN,null);s.autoRoads().enabled=false;
   }
   if(trip[0]>0&&trip[0]<3){var goal=at.offset(trip[0]==1?12:-12,0,0);actor.navigateTo(goal,.65);if(actor.blockPosition().distSqr(goal)<3)trip[0]++;}
  });
  h.startSequence().thenWaitUntil(()->h.assertTrue(trip[0]==3,"Both approaches construction and round trip incomplete"))
   .thenExecute(()->{
    h.assertTrue(fetched[0],"Worker did not fetch real building supplies");
    h.assertTrue(f.p().accessSurfaces.stream().allMatch(RoadSpaceData.get(l)::contains),"Some wide bridge deck cells are missing from the road network");
    var connected=new HashSet<BlockPos>();var queue=new ArrayDeque<BlockPos>();var cells=new HashSet<>(f.p().accessSurfaces);
    for(var r:s.autoRoads().routes.values())if(r.building.equals(f.p().id))cells.addAll(r.cells);
    var first=BridgeRoadConnections.entrances(s,f.p().id).getFirst();queue.add(first);connected.add(first);
    while(!queue.isEmpty()){var p=queue.removeFirst();for(var d:Direction.Plane.HORIZONTAL)for(int dy=-1;dy<=1;dy++){
     var q=p.relative(d).above(dy);if(cells.contains(q)&&!connected.contains(q)&&RoadSpaceData.get(l).contains(q)&&RoadSurfaceProfile.usable(l,q)
       &&RoadSurfaceProfile.joins(l,p,l.getBlockState(p),q,l.getBlockState(q),d)){connected.add(q);queue.add(q);}
    }}
    h.assertTrue(connected.containsAll(cells),"Two approaches and full bridge deck are not one physically connected road network");
    h.assertTrue(l.getBlockState(at.offset(3,-3,0)).is(Blocks.WATER),"Approaches filled the under-bridge channel");
    var result=new com.google.gson.JsonObject();result.addProperty("passed",true);result.addProperty("bothEnds",true);result.addProperty("realSupply",true);result.addProperty("roundTrip",true);result.addProperty("ticks",l.getGameTime()-start[0]);result.addProperty("tickRate",l.getServer().tickRateManager().tickrate());result.addProperty("playerSavesAccessed",false);
    result.addProperty("entireDeck",true);result.addProperty("continuousRoadNetwork",true);result.addProperty("deckRoadCells",f.p().accessSurfaces.size());
    result.addProperty("waterGapEachEnd",4);result.addProperty("underBridgeWaterPreserved",true);
    try{java.nio.file.Files.createDirectories(com.hkin.exiledsettlement.debug.SaveAcceptanceService.directory());java.nio.file.Files.writeString(com.hkin.exiledsettlement.debug.SaveAcceptanceService.file("bridgeroads-result",".json"),result.toString());}catch(java.io.IOException e){throw new RuntimeException(e);}
    com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("BRIDGEROADS_COMPLETE {}",result);s.deactivate();actor.discard();
   }).thenSucceed();
 }
 @GameTest(templateNamespace="exiledsettlement_bridgeroads",template="empty",timeoutTicks=4000)
 public static void failedFirstEndDoesNotPreventSecondEndPlanning(GameTestHelper h){
  var f=scene(h,false,0,false);var s=f.s();var l=h.getLevel();var ends=BridgeRoadConnections.entrances(s,f.p().id);
  var near=ends.getFirst();var far=ends.getLast();var key=f.p().id+":"+near.asLong();
  s.autoRoads().enabled=true;
  AutoRoadPlanner.begin(l,s,f.p().id,AutoRoadService.coreSurface(l,s),near,key);
  s.terrain().suppressed.add(near.asLong());
  s.autoRoads().accessRetry.put(f.p().id,Long.MAX_VALUE); // Old shared saved cooldown must not block the far end.
  h.onEachTick(()->{AutoRoadService.tick(l,s);AutoRoadPlanner.tickLevel(l);});
  h.startSequence().thenWaitUntil(()->h.assertTrue(s.autoRoads().routes.values().stream().anyMatch(r->r.entrance.equals(far)),"Far bridge end starved by first-end failure"))
   .thenExecute(()->{
    var retry=BridgeRoadConnections.retryKey(s,f.p().id,near);
    h.assertTrue(s.autoRoads().accessRetry.containsKey(retry),"Planner failure did not record an independent port retry");
    h.assertTrue(!s.autoRoads().accessRetry.containsKey(BridgeRoadConnections.retryKey(s,f.p().id,far)),"Failure leaked to far port");
    var restored=new AutoRoadState();restored.load(s.autoRoads().save());h.assertTrue(restored.accessRetry.containsKey(retry),"Port retries lost on save");
    h.assertTrue(f.p().finished.isEmpty(),"Planning edited unfinished bridge");s.deactivate();AutoRoadPlanner.invalidate(s);
   }).thenSucceed();
 }
 @GameTest(templateNamespace="exiledsettlement_bridgeroads",template="empty",timeoutTicks=200)
 public static void wideBridgeEntireDeckIsRoadAndWholeRowIsConsumed(GameTestHelper h){
  var f=scene(h,true,0,true,4);var s=f.s();var l=h.getLevel();var b=f.p().playerBridge;
  var line=new ArrayList<BlockPos>();for(int row=b.minRow();row<=b.maxRow();row++)line.add(b.at(row,b.sideMin+b.width/2,b.surface-1));
  var corridor=AutoRoadService.corridor(l,s,line);
  h.assertTrue(new HashSet<>(corridor).equals(f.p().accessSurfaces),"Wide bridge road corridor omitted deck rows or included rails");
  h.startSequence().thenWaitUntil(()->h.assertTrue(f.p().accessSurfaces.stream().allMatch(RoadSpaceData.get(l)::contains),"Wide bridge not fully registered as road"))
   .thenExecute(()->{h.assertTrue(f.p().dependencies.isEmpty(),"Road registration changed bridge independence");s.deactivate();}).thenSucceed();
 }
 @GameTest(templateNamespace="exiledsettlement_bridgeroads",template="empty",timeoutTicks=4000)
 public static void bridgeEndWaterGapPlansDuringPublicSurveyWithoutOpeningOtherWater(GameTestHelper h){
  var f=scene(h,false,1,true,1,4);var s=f.s();var l=h.getLevel();var ends=BridgeRoadConnections.entrances(s,f.p().id);
  for(var end:ends)h.assertTrue(RoadEarthworkService.plannable(l,s,end),"Confirmed bridge water gap rejected");
  var water=ends.getFirst().north(5);
  h.assertTrue(!RoadEarthworkService.plannable(l,s,water),"Unrelated water allowed as an automatic road");
  s.autoRoads().enabled=true;s.terrain().enabled=true;s.autoRoads().gradingReviewVersion=1;s.terrain().publicGround.phase=10;
  h.onEachTick(()->{
   s.terrain().publicGround.phase=10;AutoRoadService.tick(l,s);AutoRoadPlanner.invalidateOptional(s);AutoRoadPlanner.tickLevel(l);
  });
  h.startSequence().thenWaitUntil(()->h.assertTrue(s.autoRoads().routes.values().stream().filter(r->r.building.equals(f.p().id)).count()==2,"Bridge ends waited for entire public survey"))
   .thenExecute(()->{
    h.assertTrue(s.terrain().projects.values().stream().noneMatch(p->p.road),"Survey published physical work before shared grade commit");
    var end=ends.getFirst();s.addBuilding(BuildingType.HOUSE,end,Direction.NORTH,end,end.above(3),0,1);
    h.assertTrue(!RoadEarthworkService.plannable(l,s,end),"Bridge approach bypassed a formal building");
    s.deactivate();AutoRoadPlanner.invalidate(s);
   }).thenSucceed();
 }
 @GameTest(templateNamespace="exiledsettlement_bridgeroads",template="empty",timeoutTicks=100)
 public static void lowerTurningLandingUsesItsOwnIrregularBank(GameTestHelper h){
  var f=scene(h,false,3,true,2,8);var s=f.s();var l=h.getLevel();var end=BridgeRoadConnections.entrances(s,f.p().id).getFirst();
  var slope=end.west(3).below();
  h.assertTrue(RoadEarthworkService.plannable(l,s,slope),"Water landing cannot descend toward lower bank");
  var turn=end.west(5).north(3).below();
  // A small dry centre-line tongue must not cancel the adjacent wet turn lane.
  l.setBlock(end.west(2).below(3),Blocks.DIRT.defaultBlockState(),2);
  h.assertTrue(BridgeRoadConnections.approachGap(l,s,turn)&&RoadEarthworkService.plannable(l,s,turn),"Irregular shore cancels wet lateral turn");
  h.assertTrue(!BridgeRoadConnections.approachGap(l,s,end.west(5).north(8)),"Landing expands beyond bridge and turn envelope");
  h.assertTrue(!BridgeRoadConnections.approachGap(l,s,end.east(2)),"Landing permission leaks beneath main span");
  s.addBuilding(BuildingType.HOUSE,turn,Direction.NORTH,turn,turn.above(3),0,1);
  h.assertTrue(!RoadEarthworkService.plannable(l,s,turn),"Landing damages bank building");s.deactivate();h.succeed();
 }
 @GameTest(templateNamespace="exiledsettlement_bridgeroads",template="empty",timeoutTicks=100)
 public static void bridgeRoadTreePrefetchSkipsRegisteredBeams(GameTestHelper h){
  var f=scene(h,false,1,true,4);var s=f.s();var l=h.getLevel();var center=f.at().offset(4,0,0);
  TreeSurvey.invalidate(l);
  h.assertTrue(TreeSurvey.prepareArea(l,center,p->BridgeService.owner(s,p)!=null),"Road tree prefetch waits on bridge timbers");
  // Real trees outside the registered bridge retain their original sliced survey.
  var tree=f.at().offset(-14,0,12);for(int y=0;y<8;y++)l.setBlock(tree.above(y),Blocks.OAK_LOG.defaultBlockState(),2);
  for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)l.setBlock(tree.offset(x,8,z),Blocks.OAK_LEAVES.defaultBlockState(),2);
  h.assertTrue(!TreeSurvey.prepareArea(l,tree,p->BridgeService.owner(s,p)!=null),"Road prefetch incorrectly skips ordinary trees");s.deactivate();h.succeed();
 }
 @GameTest(templateNamespace="exiledsettlement_bridgeroads",template="empty",timeoutTicks=100)
 public static void bridgeLandingPrefersNearbyBuiltBankRoadOverRemoteCore(GameTestHelper h){
  var f=scene(h,false,0,true);var s=f.s();var l=h.getLevel();var end=BridgeRoadConnections.entrances(s,f.p().id).getFirst();var near=end.west(4);
  l.setBlock(near,Blocks.DIRT_PATH.defaultBlockState(),2);RoadSpaceData.get(l).addAll(List.of(near));
  var route=new AutoRoadState.Route(UUID.randomUUID(),near,List.of(near));route.progress=1;s.autoRoads().routes.put("local-bank",route);
  h.assertTrue(near.equals(BridgeRoadConnections.root(l,s,f.p().id,end)),"Bridge ignores nearby bank road and searches remote core");
  route.suppressed=true;h.assertTrue(!near.equals(BridgeRoadConnections.root(l,s,f.p().id,end)),"Cancelled road used as bridge root");s.deactivate();h.succeed();
 }
 @GameTest(templateNamespace="exiledsettlement_bridgeroads",template="empty",timeoutTicks=100)
 public static void bridgeLookupSurvivesHistorySaveAndCancellation(GameTestHelper h){
  var f=scene(h,false,0,true);var s=f.s();
  for(int i=0;i<256;i++){var p=new TerrainProject(UUID.randomUUID(),"Historic soil",f.at(),List.of());p.completed=true;s.terrain().index(p);}
  h.assertTrue(s.terrain().playerBridges().size()==1,"Historical soil entered bridge lookup");
  var restored=new TerrainState();restored.load(s.terrain().save());
  h.assertTrue(restored.playerBridges().size()==1&&restored.playerBridges().getFirst().id.equals(f.p().id),"Bridge lookup lost across save");
  restored.projects.get(f.p().id).cancelled=true;h.assertTrue(restored.playerBridges().isEmpty(),"Cancelled bridge retained in active lookup");s.deactivate();h.succeed();
 }
}
