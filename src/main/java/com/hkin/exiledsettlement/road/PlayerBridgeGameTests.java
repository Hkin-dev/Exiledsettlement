package com.hkin.exiledsettlement.road;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import java.util.*;

@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement",bus=net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_bridge65")
@PrefixGameTestTemplate(false)
public final class PlayerBridgeGameTests {
 @net.neoforged.bus.api.SubscribeEvent
 public static void register(RegisterGameTestsEvent e){if("timed_construction".equals(System.getProperty("exiledsettlement.testSuite")))e.register(PlayerBridgeGameTests.class);}
 private static int serial;
 @GameTest(templateNamespace="exiledsettlement_bridge65_protection",template="empty",timeoutTicks=500)
 public static void landingUsesSelectedBankBeforeNearbyFormalBuilding(GameTestHelper h){
  var f=fixture(h,5,8);var l=h.getLevel();var at=f.at();
  var wall=at.offset(-6,0,2);l.setBlock(wall,Blocks.BRICKS.defaultBlockState(),2);
  f.s().addBuilding(BuildingType.HOUSE,wall,Direction.NORTH,wall.offset(-3,0,-4),wall.offset(0,5,0),0,1);
  var plan=new PlayerBridgePlan(f.plan().first,f.plan().second,true,f.plan().surface,false);
  for(int i=0;i<10000 && !plan.ready && plan.problem.isEmpty();i++)plan.survey(l,f.s(),32);
  h.assertTrue(plan.ready,"Unnecessary approach entered nearby house: "+plan.problem);
  h.assertTrue(plan.minRow()==at.getX()-4 && plan.heightAt(plan.minRow())==at.getY(),"Ramp did not use the shortest actual grade");
  var site=PlayerBridgePlan.publish(l,f.s(),plan);var restored=TerrainProject.load(site.terrainProject().save());
  h.assertTrue(restored.playerBridge.minRow()==plan.minRow() && l.getBlockState(wall).is(Blocks.BRICKS),"Saved grade or formal building changed");
  f.s().deactivate();h.succeed();
 }
 @GameTest(templateNamespace="exiledsettlement_bridge65_protection",template="empty",timeoutTicks=500)
 public static void levelSelectedBanksNeedNoMandatoryExtension(GameTestHelper h){
  var f=fixture(h,5,8);var l=h.getLevel();var at=f.at();
  // Dry canyon permits a deck flush with both existing banks.
  for(int x=0;x<8;x++)for(int z=-3;z<=3;z++)for(int y=-12;y<=-2;y++)l.setBlock(at.offset(x,y,z),(y==-12?Blocks.STONE:Blocks.AIR).defaultBlockState(),2);
  var plan=new PlayerBridgePlan(f.plan().first,f.plan().second,true,at.getY(),false);
  for(int i=0;i<10000 && !plan.ready && plan.problem.isEmpty();i++)plan.survey(l,f.s(),32);
  h.assertTrue(plan.ready && plan.minRow()==plan.start && plan.maxRow()==plan.end,"Flat bank forced an extra approach: "+plan.problem);
  f.s().deactivate();h.succeed();
 }
 @GameTest(templateNamespace="exiledsettlement_bridge65_protection",template="empty",timeoutTicks=500)
 public static void exactPublicTransferPreservesPaidOutsideUnderpassAndSave(GameTestHelper h){
  var f=fixture(h,5,8);var l=h.getLevel();var s=f.s();var plan=f.plan();
  var at=plan.steps.stream().filter(TerrainProject.Step::fill).findFirst().orElseThrow().position();
  var outside=at.offset(0,0,30);var below=at.below(12);var before=l.getBlockState(at);
  var old=new TerrainProject(UUID.randomUUID(),"Transfer old public",at,List.of(
   new TerrainProject.Step(at,before,Blocks.AIR.defaultBlockState()),
   new TerrainProject.Step(at,Blocks.STONE_BRICKS.defaultBlockState(),before),
   new TerrainProject.Step(outside,Blocks.COBBLESTONE.defaultBlockState(),Blocks.AIR.defaultBlockState()),
   new TerrainProject.Step(below,Blocks.COBBLESTONE.defaultBlockState(),Blocks.AIR.defaultBlockState())));
  old.publicGround=true;old.mark(0);old.groundSurfaces.add(at);old.groundSurfaces.add(outside);old.groundSurfaces.add(below);s.terrain().index(old);
  var road=new TerrainProject(UUID.randomUUID(),"Transfer old road",at,List.of(new TerrainProject.Step(at,Blocks.DIRT_PATH.defaultBlockState(),before)));
  road.road=true;s.terrain().index(road);
  var site=PlayerBridgePlan.publish(l,s,plan);var p=site.terrainProject();
  h.assertTrue(l.getBlockState(at).equals(before),"Confirmation edited the world");
  h.assertTrue(old.abandoned.get(0) && old.abandoned.get(1) && road.abandoned.get(0),"Not all overlapping owners transferred");
  h.assertTrue(old.retiredPaid.get(0) && old.retiredPaid.cardinality()==1 && road.retiredPaid.isEmpty(),"Payment history fabricated or lost");
  h.assertTrue(!old.abandoned.get(2) && !old.abandoned.get(3) && old.remaining()==2 && !old.cancelled,"Outside or lower road work removed");
  h.assertTrue(s.terrain().cells.get(at).equals(p.id) && !old.groundSurfaces.contains(at) && old.groundSurfaces.contains(below),"Ownership or lower surface lost");
  var restored=new TerrainState();restored.load(s.terrain().save());var saved=restored.projects.get(old.id);
  h.assertTrue(saved.abandoned.equals(old.abandoned) && saved.retiredPaid.equals(old.retiredPaid) && saved.remaining()==2
   && restored.cells.get(at).equals(p.id) && restored.cells.get(below).equals(old.id),"Saved ownership/evidence changed");
  s.deactivate();h.succeed();
 }
 @GameTest(templateNamespace="exiledsettlement_bridge65_protection",template="empty",timeoutTicks=500)
 public static void failedConfirmationAndHiddenFormalOrderNeverTransfer(GameTestHelper h){
  var f=fixture(h,5,8);var l=h.getLevel();var s=f.s();var at=f.plan().steps.getFirst().position();
  var old=new TerrainProject(UUID.randomUUID(),"Transfer candidate",at,List.of(new TerrainProject.Step(at,Blocks.STONE.defaultBlockState(),l.getBlockState(at))));
  old.road=true;s.terrain().index(old);var before=old.save();var actual=l.getBlockState(at);
  l.setBlock(at,Blocks.GOLD_BLOCK.defaultBlockState(),2);boolean rejected=false;
  try{PlayerBridgePlan.publish(l,s,f.plan());}catch(IllegalStateException expected){rejected=true;}
  h.assertTrue(rejected && old.save().equals(before) && s.terrain().projects.size()==1,"Failed confirmation mutated old work");
  l.setBlock(at,actual,2);
  var formal=new TerrainProject(UUID.randomUUID(),"Formal region foundation",at,old.steps);formal.regionOwner=UUID.randomUUID();formal.publicGround=true;s.terrain().index(formal);
  h.assertTrue(s.terrain().cells.get(at).equals(old.id),"Test did not hide formal order behind primary owner");rejected=false;
  try{PlayerBridgePlan.publish(l,s,f.plan());}catch(IllegalStateException expected){rejected=true;}
  h.assertTrue(rejected && old.save().equals(before) && formal.abandoned.isEmpty(),"Formal work protection bypassed");
  s.deactivate();h.succeed();
 }
 @GameTest(templateNamespace="exiledsettlement_bridge65_protection",template="empty",timeoutTicks=500)
 public static void realContainerClearancePreservesContentsAndContainerOnce(GameTestHelper h){
  var f=fixture(h,5,8);var l=h.getLevel();var s=f.s();var at=f.at().offset(-4,0,0);
  l.setBlock(at,Blocks.CHEST.defaultBlockState(),2);
  var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)l.getBlockEntity(at);
  chest.setItem(0,new ItemStack(Items.DIAMOND,13));
  var c=s.citizens().iterator().next();
  var actor=com.hkin.exiledsettlement.registry.ModEntities.SETTLEMENT_CITIZEN.get().create(l);
  actor.moveTo(at.getX()-1.5,at.getY(),at.getZ()+.5,0,0);
  actor.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());
  actor.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
  l.addFreshEntity(actor);c.updateProjection(actor.getUUID(),actor.blockPosition());
  var result=SiteClearance.clear(l,at,actor,new ItemStack(Items.IRON_AXE),p->p.equals(at));
  h.assertTrue(result.failure().isEmpty() && l.getBlockState(at).isAir(),"Real container clearance failed");
  for(var drop:result.drops())h.assertTrue(c.insertIntoInventory(drop).isEmpty(),"Unexpected cargo overflow");
  h.assertTrue(c.inventory().stream().filter(v->v.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum()==13,"Container contents lost or duplicated");
  h.assertTrue(c.inventory().stream().filter(v->v.is(Items.CHEST)).mapToInt(ItemStack::getCount).sum()==1,"Container item lost or duplicated");
  var saved=SettlementRecord.load(s.save(l.registryAccess()),l.registryAccess());
  h.assertTrue(saved.citizen(c.id()).inventory().stream().filter(v->v.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum()==13,"Recovered contents lost in save");
  h.assertTrue(l.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new net.minecraft.world.phys.AABB(at).inflate(3)).isEmpty(),"Clearance left duplicate ground drops");
  s.deactivate();actor.discard();h.succeed();
 }
 @GameTest(templateNamespace="exiledsettlement_bridge65_nearbank",template="empty",timeoutTicks=500)
 public static void unevenBankFitsNearbyInsteadOfReachingDistantContainer(GameTestHelper h){
  var f=unevenFixture(h);var l=h.getLevel();var plan=f.plan();
  h.assertTrue(plan.ready,"Nearby uneven bank rejected: "+plan.problem);
  h.assertTrue(plan.minRow()>=f.at().getX()-10,"Bridge skipped the nearby bank and extended toward distant facilities");
  h.assertTrue(l.getBlockState(f.at().offset(-30,2,0)).is(Blocks.CHEST),"Survey changed protected content");
  var site=PlayerBridgePlan.publish(l,f.s(),plan);var loaded=TerrainProject.load(site.terrainProject().save());
  for(int row=plan.minRow();row<=plan.maxRow();row++)h.assertTrue(plan.heightAt(row)==loaded.playerBridge.heightAt(row),"Saved nearby landing changed profile");
  f.s().deactivate();h.succeed();
 }
 private static Fixture unevenFixture(GameTestHelper h){
  var f=fixture(h,5,8);var l=h.getLevel();
  for(int x=-24;x<=-3;x++)for(int z=-8;z<=8;z++)for(int y=-1;y<=3;y++){
   int floor=Math.floorMod(x,2)==0?-1:1;
   l.setBlock(f.at().offset(x,y,z),(y<floor?Blocks.DIRT:y==floor?Blocks.GRASS_BLOCK:Blocks.AIR).defaultBlockState(),2);
  }
  l.setBlock(f.at().offset(-30,2,0),Blocks.CHEST.defaultBlockState(),2);
  var chestAt=f.at().offset(-4,0,0);l.setBlock(chestAt,Blocks.CHEST.defaultBlockState(),2);
  ((net.minecraft.world.level.block.entity.ChestBlockEntity)l.getBlockEntity(chestAt)).setItem(0,new ItemStack(Items.DIAMOND,13));
  for(int y=0;y<3;y++)l.setBlock(f.at().offset(-4,y,1),Blocks.OAK_LOG.defaultBlockState(),2);
  l.setBlock(f.at().offset(-4,3,1),Blocks.OAK_LEAVES.defaultBlockState(),2);
  var oldRoad=f.at().offset(-5,0,-1);l.setBlock(oldRoad,Blocks.STONE_BRICKS.defaultBlockState(),2);
  RoadSpaceData.get(l).addAll(List.of(oldRoad),RoadTier.LEVEL_3);RoadSpaceData.get(l).pinManual(List.of(oldRoad));
  var plan=new PlayerBridgePlan(f.plan().first,f.plan().second,true,f.plan().surface,false);
  for(int i=0;i<10000 && !plan.ready && plan.problem.isEmpty();i++)plan.survey(l,f.s(),7);
  return new Fixture(f.s(),f.at(),plan);
 }
 @GameTest(templateNamespace="exiledsettlement_bridge65_nearbank",template="empty",timeoutTicks=180000,batch="bridge65_uneven_entity")
 public static void nearbyUnevenLandingBuildsFromOneBankAndCarriesResidentBothWays(GameTestHelper h){entityTrial(h,5,8,false,0,1,false,false,true);}
 @GameTest(templateNamespace="exiledsettlement_bridge65_rules",template="empty")
 public static void roadsCannotCreateBridgesButCanConsumePlayerProjects(GameTestHelper h){
  var f=fixture(h,5,8);var l=h.getLevel();var s=f.s();var water=f.at().east(3);
  var route=new AutoRoadState.Route(UUID.randomUUID(),water,List.of(water));
  h.assertTrue(BridgeService.candidate(l,s,water),"Fixture is not a geometrically viable water crossing");
  h.assertTrue(!BridgeService.span(l,s,water) && !RoadEarthworkService.plannable(l,s,water),"Road search still proposes a new water crossing");
  h.assertTrue(!BridgeService.publish(l,s,route) && s.terrain().projects.isEmpty() && s.formalBlueprints().isEmpty(),"Road publication created an unsolicited bridge");
  h.assertTrue(l.getBlockState(water).isAir() && l.getFluidState(water.below(2)).is(net.minecraft.tags.FluidTags.WATER),"Rejected road publication changed the river");
  var site=PlayerBridgePlan.publish(l,s,f.plan());h.assertTrue(site!=null,"Player-confirmed bridge did not publish");var project=site.terrainProject();
  var surface=project.accessSurfaces.stream().filter(p->p.getX()==water.getX()).findFirst().orElseThrow();
  var consumer=new AutoRoadState.Route(UUID.randomUUID(),surface,List.of(surface));consumer.sourceBridges.add(project.id);
  h.assertTrue(BridgeService.span(l,s,surface) && RoadEarthworkService.plannable(l,s,surface),"Roads cannot consume a registered bridge");
  h.assertTrue(BridgeService.publish(l,s,consumer) && s.terrain().projects.size()==1,"Road reuse duplicated the player bridge");
  h.assertTrue(project.dependencies.isEmpty() && !project.road && project.playerBridge!=null,"Road reuse took ownership of the player bridge");
  s.deactivate();h.succeed();
 }
 private record Fixture(SettlementRecord s,BlockPos at,PlayerBridgePlan plan){}
 private static Fixture fixture(GameTestHelper h,int width,int length){return fixture(h,width,length,false,0);}
 private static Fixture fixture(GameTestHelper h,int width,int length,boolean reverse,int high){
  var l=h.getLevel();l.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false,l.getServer());var at=h.absolutePos(new BlockPos(930000+serial++*1024,64,0));
  for(int x=-55;x<length+55;x++)for(int z=-35;z<=35;z++){
   var base=at.offset(x,0,z);l.getChunkAt(base);l.setChunkForced(base.getX()>>4,base.getZ()>>4,true);
   l.setBlock(base.below(21),Blocks.STONE.defaultBlockState(),2);
   for(int y=-20;y<7;y++){boolean river=x>=0 && x<length;int floor=x>=length?high-1:-1;
    l.setBlock(base.above(y),river?(y<-2?Blocks.WATER:y==-2?Blocks.WATER:Blocks.AIR).defaultBlockState(): (y<floor?Blocks.DIRT:y==floor?Blocks.GRASS_BLOCK:Blocks.AIR).defaultBlockState(),2);
   }
  }
  var s=SettlementSavedData.get(l).createOrRecover(at.offset(reverse?length+16:-16,reverse?high:0,12),UUID.randomUUID(),"Single bank bridge").value();s.upgradeLevel();s.upgradeLevel();
  for(int pass=0;pass<16;pass++)for(int x=-4;x<length/16+3;x++)for(int z=-3;z<=3;z++)s.claimChunk((at.getX()>>4)+x,(at.getZ()>>4)+z);
  s.terrain().enabled=false;s.autoRoads().enabled=false;s.decorations().enabled=false;s.community().stewardEnabled=false;
  for(var c:s.citizens())c.setManualAssignment(CitizenProfession.HERDSMAN,null);
  var low=at.offset(-2,-1,-width/2);var upper=at.offset(length+1,-1,width-width/2-1);
  var plan=new PlayerBridgePlan(reverse?upper:low,reverse?low:upper,true,at.getY()+1,false);
  for(int i=0;i<10000 && !plan.ready && plan.problem.isEmpty();i++)plan.survey(l,s,32);
  h.assertTrue(plan.ready,"Survey failed: "+plan.problem);l.setDayTime(1000);return new Fixture(s,at,plan);
 }
 @GameTest(templateNamespace="exiledsettlement_bridge65",template="empty",timeoutTicks=120000,batch="bridge65_entity")
 public static void singleBankWarehouseCompletesWholeBridgeAndResidentRoundTrip(GameTestHelper h){entityTrial(h,5,8,false,0,1);}
 @GameTest(templateNamespace="exiledsettlement_bridge65_reverse",template="empty",timeoutTicks=180000,batch="bridge65_reverse")
 public static void reverseSingleBankEvenWidthHighShoreAndSaveResume(GameTestHelper h){entityTrial(h,6,8,true,3,1);}
 @GameTest(templateNamespace="exiledsettlement_bridge65_long",template="empty",timeoutTicks=600000,batch="bridge65_long")
 public static void longWideBridgeOverQuotaCompletesRealConstruction(GameTestHelper h){entityTrial(h,9,128,false,0,4);}
 @GameTest(templateNamespace="exiledsettlement_bridge65_autobanks",template="empty",timeoutTicks=180000,batch="bridge65_auto_entity")
 public static void bodyOnlySelectionBuildsAcrossIrregularBanksAndIsland(GameTestHelper h){entityTrial(h,5,8,false,0,1,true);}
 @GameTest(templateNamespace="exiledsettlement_bridge65_autobanks",template="empty",timeoutTicks=180000,batch="bridge65_auto_reverse")
 public static void bodyOnlyReverseHighBankBuildsAndSavesAdaptedLandings(GameTestHelper h){entityTrial(h,6,8,true,3,1,true);}
 private static Fixture adaptedFixture(GameTestHelper h,int width,int length,boolean reverse,int high){
  var f=fixture(h,width,length,reverse,high);var l=h.getLevel();var at=f.at();
  for(int x=-5;x<length+5;x++)for(int z=-width;z<=width;z++){
   int left=-Math.floorMod(z,3),right=length+Math.floorMod(z,3);
   boolean river=x>=left && x<right && !(x==length/2 && z==0);
   int floor=x>=right?high-1:-1;
   for(int y=-20;y<8;y++)l.setBlock(at.offset(x,y,z),
    (river?(y<=-2?Blocks.WATER:Blocks.AIR):(y<floor?Blocks.DIRT:y==floor?Blocks.GRASS_BLOCK:Blocks.AIR)).defaultBlockState(),2);
  }
  var low=at.offset(2,-3,-width/2);var upper=at.offset(length-3,-3,width-width/2-1);
  var plan=new PlayerBridgePlan(reverse?upper:low,reverse?low:upper,true,Integer.MIN_VALUE,false);
  for(int i=0;i<10000 && !plan.ready && plan.problem.isEmpty();i++)plan.survey(l,f.s(),32);
  h.assertTrue(plan.ready,"Automatic bank fit failed: "+plan.problem);
  h.assertTrue(plan.minRow()<at.getX()-2 && plan.maxRow()>at.getX()+length+1,"Body-only selection did not extend to both actual shores");
  h.assertTrue(plan.surface==at.getY()+1 && plan.heightAt(plan.start)==plan.surface && plan.heightAt(plan.end)==plan.surface,"Body elevation was not safely resolved and preserved");
  h.assertTrue(l.getBlockState(at.offset(length/2,-1,0)).is(Blocks.GRASS_BLOCK),"Survey edited the island");
  return new Fixture(f.s(),at,plan);
 }
 @GameTest(templateNamespace="exiledsettlement_bridge65_autobanks",template="empty")
 public static void automaticLandingsProtectFormalBuildingsInsteadOfLooseContainers(GameTestHelper h){
  var f=adaptedFixture(h,5,8,false,0);var l=h.getLevel();
  var obstruction=f.plan().at(f.plan().minRow(),f.plan().sideMin+2,f.plan().heightAt(f.plan().minRow()));
  l.setBlock(obstruction,Blocks.CHEST.defaultBlockState(),2);
  var retry=new PlayerBridgePlan(f.plan().first,f.plan().second,true,Integer.MIN_VALUE,false);
  for(int i=0;i<10000 && !retry.ready && retry.problem.isEmpty();i++)retry.survey(l,f.s(),32);
  h.assertTrue(retry.ready && retry.steps.stream().anyMatch(step->!step.fill() && step.position().equals(obstruction)),"Loose container still blocked an explicitly selected bridge");
  f.s().addBuilding(BuildingType.HOUSE,obstruction,Direction.NORTH,obstruction,obstruction,0,1);
  var protectedPlan=new PlayerBridgePlan(f.plan().first,f.plan().second,true,Integer.MIN_VALUE,false);
  for(int i=0;i<10000 && !protectedPlan.ready && protectedPlan.problem.isEmpty();i++)protectedPlan.survey(l,f.s(),32);
  h.assertTrue(!protectedPlan.ready && !protectedPlan.problem.isEmpty() && l.getBlockState(obstruction).is(Blocks.CHEST) && f.s().terrain().projects.isEmpty(),"Formal building contents lost protection");
  f.s().deactivate();h.succeed();
 }
 @GameTest(templateNamespace="exiledsettlement_bridge65_autobanks",template="empty")
 public static void bodyContainingLandDoesNotRequireRectangularWaterBanks(GameTestHelper h){
  var f=fixture(h,5,8);var l=h.getLevel();
  var plan=new PlayerBridgePlan(f.at().offset(-4,-1,-2),f.at().offset(12,-1,2),true,Integer.MIN_VALUE,false);
  for(int i=0;i<10000 && !plan.ready && plan.problem.isEmpty();i++)plan.survey(l,f.s(),7);
  h.assertTrue(plan.ready,"Including dry bank edges still rejected: "+plan.problem);
  h.assertTrue(plan.steps.stream().noneMatch(step->!l.getFluidState(step.position()).isEmpty()),"Automatic fit planned to fill the river");
  var site=PlayerBridgePlan.publish(l,f.s(),plan);var loaded=TerrainProject.load(site.terrainProject().save());
  for(int row=plan.minRow();row<=plan.maxRow();row++)h.assertTrue(plan.heightAt(row)==loaded.playerBridge.heightAt(row),"Adapted landing profile changed on reload");
  h.assertTrue(plan.stagingArea().equals(loaded.playerBridge.stagingArea()),"Adapted worksite changed on reload");
  f.s().deactivate();h.succeed();
 }
 @GameTest(templateNamespace="exiledsettlement_bridge65_autobanks",template="empty",timeoutTicks=180000,batch="bridge65_dry_entity")
 public static void suspendedDryValleyBridgeKeepsRoadBelowWalkable(GameTestHelper h){entityTrial(h,5,8,false,5,1,false,true);}
 private static Fixture dryValleyFixture(GameTestHelper h,int width,int length,boolean reverse,int high){
  var f=fixture(h,width,length,reverse,high);var l=h.getLevel();
  for(int x=0;x<length;x++)for(int z=-30;z<=30;z++)for(int y=-20;y<high+8;y++)
   l.setBlock(f.at().offset(x,y,z),(y<=-9?Blocks.STONE:Blocks.AIR).defaultBlockState(),2);
  var road=new ArrayList<BlockPos>();for(int z=-12;z<=12;z++){
   var p=f.at().offset(length/2,-9,z);l.setBlock(p,Blocks.STONE_BRICKS.defaultBlockState(),2);road.add(p);
  }
  RoadSpaceData.get(l).addAll(road,RoadTier.LEVEL_1);RoadSpaceData.get(l).pinManual(road);
  var a=f.at().offset(-2,-1,-width/2);var b=f.at().offset(length+1,high-1,width-width/2-1);
  var plan=new PlayerBridgePlan(reverse?b:a,reverse?a:b,true,Integer.MIN_VALUE,false);
  for(int i=0;i<10000 && !plan.ready && plan.problem.isEmpty();i++)plan.survey(l,f.s(),32);
  h.assertTrue(plan.ready,"Dry canyon bridge rejected: "+plan.problem);
  h.assertTrue(plan.steps.stream().filter(v->v.position().getX()>=f.at().getX() && v.position().getX()<f.at().getX()+length).allMatch(v->v.position().getY()>=plan.surface-2),"Suspended body planned pillars into the underpass");
  return new Fixture(f.s(),f.at(),plan);
 }
 private static void entityTrial(GameTestHelper h,int width,int length,boolean reverse,int high,int workerCount){entityTrial(h,width,length,reverse,high,workerCount,false);}
 private static void entityTrial(GameTestHelper h,int width,int length,boolean reverse,int high,int workerCount,boolean adapted){entityTrial(h,width,length,reverse,high,workerCount,adapted,false);}
 private static void entityTrial(GameTestHelper h,int width,int length,boolean reverse,int high,int workerCount,boolean adapted,boolean dry){
  entityTrial(h,width,length,reverse,high,workerCount,adapted,dry,false);
 }
 private static void entityTrial(GameTestHelper h,int width,int length,boolean reverse,int high,int workerCount,boolean adapted,boolean dry,boolean uneven){
  var f=uneven?unevenFixture(h):dry?dryValleyFixture(h,width,length,reverse,high):adapted?adaptedFixture(h,width,length,reverse,high):fixture(h,width,length,reverse,high);var l=h.getLevel();var s=f.s();var data=SettlementSavedData.get(l);
  h.assertTrue(f.plan().ready,"Entity trial needs a valid preview: "+f.plan().problem);
  var under=dry?net.minecraft.world.entity.EntityType.VILLAGER.create(l):null;int[] underTrips={0};var underColumns=new BitSet[]{new BitSet(),new BitSet(),new BitSet()};
  if(under!=null){under.moveTo(f.at().getX()+length/2+.5,f.at().getY()-8,f.at().getZ()-8+.5,0,0);under.setNoAi(true);l.addFreshEntity(under);}
  var t=BlueprintTemplates.create(BuildingType.WAREHOUSE,f.at().offset(reverse?length+23:-43,reverse?high:0,-10),Direction.EAST);
  for(var p:t.placements())l.setBlock(p.position(),p.state(),2);
  for(var p:t.placements())BlueprintBlockStateResolver.refreshExisting(l,t,p);
  var warehouse=s.addBuilding(t.type(),t.registrationPosition(),t.facing(),t.minCorner(),t.maxCorner(),t.transitionMargin(),BlueprintTemplates.currentTemplateVersion(t.type()));
  BuildingFunctionService.validateSingle(l,data,s,warehouse);h.assertTrue(warehouse.operational(),"Warehouse fixture invalid");
  for(var e:f.plan().requirements().entrySet())WarehouseInventoryService.insert(l,warehouse,new ItemStack(e.getKey(),e.getValue()+64));
  for(var item:List.of(Items.IRON_AXE,Items.IRON_PICKAXE,Items.IRON_SHOVEL))WarehouseInventoryService.insert(l,warehouse,new ItemStack(item,length>64?32:12));
  WarehouseInventoryService.insert(l,warehouse,new ItemStack(Items.BREAD,256));
  var c=s.citizens().iterator().next();c.setManualAssignment(CitizenProfession.WORKER,null);c.assignProfession(CitizenProfession.WORKER,null);
  var actor=com.hkin.exiledsettlement.registry.ModEntities.SETTLEMENT_CITIZEN.get().create(l);
  actor.moveTo(f.at().getX()+(reverse?length+15.5:-15.5),f.at().getY()+(reverse?high:0),f.at().getZ()+5.5,0,0);
  actor.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());actor.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
  net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event)->{
   if(event.getEntity()==actor && s.active())com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.error("BRIDGE65_UNEXPECTED_REMOVAL citizen={} currentProjection={} removed={}",c.id(),c.entityId(),actor.getRemovalReason(),new Exception("Removal call stack"));
  });
  l.addFreshEntity(actor);c.updateProjection(actor.getUUID(),actor.blockPosition());
  h.assertTrue(actor!=null,"Worker projection missing");h.assertTrue(c.inventory().stream().noneMatch(v->v.is(Items.OAK_LOG)||v.is(Items.OAK_PLANKS)),"Worker must start without bridge material");
  for(var other:s.citizens().stream().filter(v->v!=c).limit(workerCount-1).toList()){other.setManualAssignment(CitizenProfession.WORKER,null);other.assignProfession(CitizenProfession.WORKER,null);}
  var initial=new LinkedHashMap<net.minecraft.world.item.Item,Integer>();for(var item:f.plan().requirements().keySet())initial.put(item,WarehouseInventoryService.countItem(l,warehouse,item));
  var site=PlayerBridgePlan.publish(l,s,f.plan());var savedProject=new TerrainProject[]{site.terrainProject()};var restoredPhases=new BitSet();int[] max={0};boolean[] fetched={false};long[] done={-1};int[] trip={0};boolean[] crossed={false,false,false};var start=actor.position();var crossedColumns=new BitSet[]{new BitSet(),new BitSet(),new BitSet()};
  h.onEachTick(()->{
   var project=savedProject[0];var plan=project.playerBridge;
   if(reverse && plan.phase<3 && project.finished.cardinality()>0 && !restoredPhases.get(plan.phase)){
    var restored=TerrainProject.load(project.save());h.assertTrue(restored.finished.equals(project.finished) && restored.playerBridge.admitted.equals(plan.admitted),"Midphase persistence lost real work");
    site.withTerrainProject(restored);s.terrain().index(restored);savedProject[0]=restored;restoredPhases.set(plan.phase);return;
   }
   int count=OptionalWorkBudget.snapshot(s).pending();max[0]=Math.max(max[0],count);h.assertTrue(count<=1000,"Bridge exceeded shared quota");
   for(var resident:s.citizens())if(resident.profession()==CitizenProfession.WORKER){var e=CitizenProjectionService.findProjection(l,resident);if(e!=null){h.assertTrue(e.isAlive() && !e.isInWater(),"Another bridge worker entered water or died");if(!project.completed)h.assertTrue(plan.supplyingBank(e.blockPosition()),"Worker had to leave the supplying bank to build");}}
   h.assertTrue(s.autoRoads().routes.isEmpty() && project.dependencies.isEmpty() && !project.road,"Independent bridge acquired a road owner");
   if(c.inventory().stream().anyMatch(v->v.is(Items.OAK_LOG)||v.is(Items.OAK_PLANKS)))fetched[0]=true;
   h.assertTrue(actor.isAlive(),"Worker unavailable: health="+actor.getHealth()+" removed="+actor.getRemovalReason()+" damage="+actor.getLastDamageSource()+" citizenAlive="+c.alive()+" progress="+project.finished.cardinality()+" pos="+actor.position());if(actor.isInWater()){com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.error("BRIDGE65_WATER progress={} state={} pos={} goal={} phase={}",project.finished.cardinality(),c.workState(),actor.position(),c.targetPosition(),plan.phase);s.deactivate();}
   h.assertTrue(!actor.isInWater(),"Bridge construction required swimming");
   if(l.getGameTime()%1000==0)com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("BRIDGE65 progress={}/{} phase={} frontier={} state={} failure={} pos={} target={} completed={} trip={} shore={}/{}",project.finished.cardinality(),project.steps.size(),plan.phase,plan.frontier,c.workState(),c.workFailure(),actor.position(),c.targetPosition(),project.completed,trip[0],plan.shore(false),plan.shore(true));
   if(l.getGameTime()%1000==0 && plan.frontier<project.steps.size()){
    var step=project.steps.get(plan.frontier);
    com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("BRIDGE65_FRONT goal={} actual={} guard={} admitted={} cursor={} fence={} stockFence={} activity={} faces={}",step,l.getBlockState(step.position()),TerrainService.guard(l,s,site,step),plan.admitted.get(plan.frontier),project.cursor,c.inventory().stream().filter(v->v.is(Items.OAK_FENCE)).mapToInt(ItemStack::getCount).sum(),WarehouseInventoryService.countItem(l,warehouse,Items.OAK_FENCE),c.activity(),CitizenWorkService.terrainWorkFaces(l,s,c,actor));
   }
   if(project.completed){
    if(done[0]<0){done[0]=l.getGameTime();c.clearWork();c.setManualAssignment(CitizenProfession.HERDSMAN,null);c.assignProfession(CitizenProfession.HERDSMAN,null);c.insertIntoInventory(new ItemStack(Items.APPLE,7));}
    boolean far=(trip[0]==1)^reverse;var goal=plan.shore(far).offset(far?2:-2,0,0);actor.navigateTo(goal,.65);
    if(trip[0]<3 && actor.getX()>f.at().getX()+1 && actor.getX()<f.at().getX()+7 && Math.abs(actor.getZ()-f.at().getZ())<2 && actor.getY()>=f.plan().surface)crossed[trip[0]]=true;
    int riverColumn=actor.blockPosition().getX()-f.at().getX();
    if(trip[0]<3 && riverColumn>=0 && riverColumn<length && Math.abs(actor.getZ()-f.at().getZ())<width/2.0 && actor.getY()>=plan.surface)
        crossedColumns[trip[0]].set(riverColumn);
    if(actor.blockPosition().distSqr(goal)<4)trip[0]++;
    if(under!=null && underTrips[0]<3){
     under.setNoAi(false);var underGoal=f.at().offset(length/2,-8,underTrips[0]==1?8:-8);
     under.getNavigation().moveTo(underGoal.getX()+.5,underGoal.getY(),underGoal.getZ()+.5,.8);
     h.assertTrue(under.isAlive() && !under.isInWater(),"Underpass resident died or entered water");
     int side=under.blockPosition().getZ()-(f.at().getZ()-width/2);
     if(side>=0 && side<width && under.getY()<plan.surface-2 && Math.abs(under.getX()-(f.at().getX()+length/2+.5))<1.5)underColumns[underTrips[0]].set(side);
     if(under.blockPosition().distSqr(underGoal)<2)underTrips[0]++;
    }
   }
  });
  h.startSequence().thenWaitUntil(()->h.assertTrue(savedProject[0].completed && trip[0]>=3 && (under==null || underTrips[0]>=3),"Bridge not completed with actual upper and lower round trips"))
   .thenExecute(()->{var project=savedProject[0];if(reverse)h.assertTrue(restoredPhases.cardinality()==3,"Missing phase save/resume coverage");h.assertTrue(fetched[0],"No real warehouse material collection");h.assertTrue(crossed[1] && crossed[2] && crossedColumns[1].cardinality()==length && crossedColumns[2].cardinality()==length,"Round trip did not traverse every bridge column in both directions");h.assertTrue(actor.position().distanceTo(start)>2,"Worker never moved");
    h.assertTrue(TerrainService.constructionComplete(l,project),"Bridge world incomplete");
    for(var item:List.of(Items.OAK_LOG,Items.OAK_PLANKS,Items.OAK_FENCE,Items.OAK_STAIRS)){
     int left=WarehouseInventoryService.countItem(l,warehouse,item)+s.citizens().stream().flatMap(v->v.inventory().stream()).filter(v->v.is(item)).mapToInt(ItemStack::getCount).sum();
     long placed=project.steps.stream().filter(v->v.fill() && v.target().getBlock().asItem()==item && l.getBlockState(v.position()).getBlock().asItem()==item).count();
     int recovered=uneven && item==Items.OAK_LOG?3:0;
     h.assertTrue(initial.getOrDefault(item,0)+recovered-left==placed,"Material balance differs for "+item+": "+initial.getOrDefault(item,0)+" + "+recovered+" - "+left+" != "+placed);
    }h.assertTrue(project.accessSurfaces.stream().allMatch(p->BridgeService.ready(l,s,p)),"Bridge not open");
    h.assertTrue(c.inventory().stream().filter(v->v.is(Items.APPLE)).mapToInt(ItemStack::getCount).sum()==7,"Roundtrip cargo changed");
    if(uneven){
     int diamonds=WarehouseInventoryService.countItem(l,warehouse,Items.DIAMOND)+s.citizens().stream().flatMap(v->v.inventory().stream()).filter(v->v.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum();
     h.assertTrue(diamonds==13,"Real bridge excavation lost or duplicated chest contents");
     h.assertTrue(!l.getBlockState(f.at().offset(-4,0,0)).is(Blocks.CHEST) && !l.getBlockState(f.at().offset(-4,0,1)).is(Blocks.OAK_LOG),"Bridge did not perform its actual obstacle clearance");
    }
    var restored=TerrainProject.load(project.save());h.assertTrue(restored.playerBridge!=null && restored.finished.equals(project.finished),"Bridge save lost progress");
    com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("BRIDGE65_COMPLETE steps={} warehouse={} maxPending={} roundTrips=1 cargo=7 width={} span={} reverse={} savedPhases={}",project.steps.size(),warehouse.id(),max[0],width-2,length,reverse,restoredPhases);
    if(under!=null){h.assertTrue(underColumns[1].cardinality()==width && underColumns[2].cardinality()==width,"Resident did not traverse every bridge-underpass column both ways");
     for(int z=-12;z<=12;z++)h.assertTrue(l.getBlockState(f.at().offset(length/2,-9,z)).is(Blocks.STONE_BRICKS),"Existing road under bridge was changed");
     com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("BRIDGE65_DRY_UNDERPASS_COMPLETE upperTrips={} lowerTrips={} columns={} highDifference={}",trip[0],underTrips[0],width,high);under.discard();}
    s.deactivate();actor.discard();}).thenSucceed();
 }
 @GameTest(templateNamespace="exiledsettlement_bridge65_rules",template="empty",timeoutTicks=500,batch="bridge65_rules")
 public static void longWidePlanSavesAndAdmitsOnlyLocalFrontier(GameTestHelper h){
  var f=fixture(h,9,128);var l=h.getLevel();var b=PlayerBridgePlan.publish(l,f.s(),f.plan());var p=b.terrainProject();
  h.assertTrue(p.steps.size()>1000,"Long bridge did not exceed quota in total geometry");
  var earthDeck=p.bridgeDeck.stream().filter(at->l.getBlockState(at).is(Blocks.GRASS_BLOCK)).findFirst().orElseThrow();
  h.assertTrue(!BridgeService.matches(l,p,earthDeck),"Unbuilt grass was accepted as a paid timber deck");
  h.startSequence().thenExecuteAfter(30,()->{
   h.assertTrue(OptionalWorkBudget.snapshot(f.s()).pending()<=1000,"Quota exceeded");
   h.assertTrue(p.playerBridge.admitted.cardinality()>0 && p.playerBridge.admitted.cardinality()<p.steps.size(),"No bounded frontier publication");
   var restored=TerrainProject.load(p.save());h.assertTrue(restored.playerBridge.sequence.batches().equals(p.playerBridge.sequence.batches()) && restored.playerBridge.direction()==p.playerBridge.direction() && restored.playerBridge.admitted.equals(p.playerBridge.admitted) && restored.playerBridge.rows.equals(p.playerBridge.rows),"Saved frontier changed");
   for(int width:new int[]{3,4,7}){
    var state=new AutoRoadState();var route=new AutoRoadState.Route(UUID.randomUUID(),p.anchor,List.copyOf(p.accessSurfaces));route.manualBridge=true;route.width=width;state.routes.put("player_bridge",route);
    var loaded=new AutoRoadState();loaded.load(state.save());var kept=loaded.routes.get("player_bridge");
    h.assertTrue(kept!=null && kept.manualBridge && kept.width==width && kept.id.equals(route.id),"Saved manual bridge route lost width or identity: "+width);
    route.manualBridge=false;route.width=4;loaded.load(state.save());h.assertTrue(loaded.routes.isEmpty(),"Ordinary road accepted unsupported width");
   }
   var full=SettlementRecord.load(f.s().save(l.registryAccess()),l.registryAccess());
   h.assertTrue(full.blueprint(p.id)!=null && full.autoRoads().routes.isEmpty() && full.terrain().projects.get(p.id).playerBridge.width==9 && full.terrain().projects.get(p.id).dependencies.isEmpty(),"Full settlement save lost wide bridge work or route");
   var indexed=full.terrain().projects.get(p.id);for(int n=0;n<indexed.steps.size();n++)indexed.mark(n);indexed.completed=true;
   h.assertTrue(full.terrain().unfinishedProjects().stream().noneMatch(v->v.id.equals(p.id)),"Finished bridge stayed in pending index");indexed.reopen(0);
   h.assertTrue(full.terrain().unfinishedProjects().stream().anyMatch(v->v.id.equals(p.id)),"Damaged bridge did not re-enter pending index");
   h.assertTrue(p.bridgeDeck.stream().noneMatch(at->l.getBlockState(at).is(BridgePalette.deck)),"Planning changed world");f.s().deactivate();
  }).thenSucceed();
 }
 @GameTest(templateNamespace="exiledsettlement_bridge65_rules",template="empty",timeoutTicks=200,batch="bridge65_safety")
 public static void pauseResumeAndConflictDoNotMutateBridge(GameTestHelper h){
  var f=fixture(h,5,4);var l=h.getLevel();int before=f.s().blueprints().size();var blocked=f.plan().steps.getFirst().position();var old=l.getBlockState(blocked);
  l.setBlock(blocked,Blocks.CHEST.defaultBlockState(),2);boolean rejected=false;try{PlayerBridgePlan.publish(l,f.s(),f.plan());}catch(IllegalStateException expected){rejected=true;}
  h.assertTrue(rejected && f.s().blueprints().size()==before,"Changed preview partly published a bridge");l.setBlock(blocked,old,2);
  var site=PlayerBridgePlan.publish(l,f.s(),f.plan());var p=site.terrainProject();p.playerBridge.paused=true;
  h.startSequence().thenExecuteAfter(30,()->{
   h.assertTrue(p.playerBridge.admitted.isEmpty() && p.finished.isEmpty(),"Paused bridge published or constructed work");
   h.assertTrue(TerrainProject.load(p.save()).playerBridge.paused,"Pause lost on save");p.playerBridge.paused=false;
  }).thenExecuteAfter(30,()->{
   h.assertTrue(!p.playerBridge.admitted.isEmpty(),"Resume did not publish frontier");
   h.assertTrue(BlueprintService.constructionMarkers(l,site).stream().allMatch(at->p.indices(at).stream().anyMatch(i->p.playerBridge.admitted.get(i))),"Unpublished ghost work marker");
   AutoRoadService.cancelNear(f.s(),p.accessSurfaces.iterator().next());
   h.assertTrue(!p.cancelled && f.s().autoRoads().routes.isEmpty(),"Road cancellation owns the bridge");
   PlayerBridgeWork.cancelNear(l,f.s(),p.accessSurfaces.iterator().next());
  }).thenExecuteAfter(30,()->{
   h.assertTrue(p.cancelled && OptionalWorkBudget.snapshot(f.s()).pending()==0,"Cancellation did not release quota");
   h.assertTrue(p.finished.isEmpty(),"Cancellation fabricated work completion");f.s().deactivate();
  }).thenSucceed();
 }

}
