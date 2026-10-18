package com.hkin.exiledsettlement.gametest;
import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.*;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.Container;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProjectionAndRangeGameTests {
 private static int fixtureIndex;
 @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",batch="range_roads")
 public static void calendarEditsPreserveDailyConsumptionClocksAndSave(GameTestHelper h) {
  CommunityState state=new CommunityState();state.seasonLength=30;state.lastDay=200;state.lastNeedTick=999;state.mealsEaten=7;state.yearConsumed.put("bread",12);
  SeasonControlService.set(state,200,3,12);
  h.assertTrue(SeasonCalendar.season(state.calendarTime(200*24000L+6000),30)==3 && SeasonCalendar.dayWithin(state.calendarTime(200*24000L),30)==12,"Season/date setting disagreed with HUD calendar");
  h.assertTrue(state.lastDay==200 && state.lastNeedTick==999 && state.mealsEaten==7 && state.yearConsumed.get("bread")==12,"Season command altered daily consumption or historical ledger");
  SeasonControlService.length(state,200,60);
  h.assertTrue(state.calendarDay(200)%60==22 && state.calendarDay(200)/60%4==3,"Season-length change lost season fraction");
  CommunityState loaded=new CommunityState();loaded.load(state.save());h.assertTrue(loaded.seasonLength==60 && loaded.calendarOffsetDays==state.calendarOffsetDays,"Manual calendar lost on reload");
  SeasonControlService.set(loaded,200,0,1);SeasonControlService.set(loaded,200,3,1);
  h.assertTrue(loaded.lastOfferYear==loaded.lastProcessedYear,"Repeated season changes can replay yearly rewards");
  SeasonControlService.reset(loaded,200);h.assertTrue(loaded.calendarOffsetDays==0 && loaded.seasonLength==SeasonCalendar.length(CommunityContent.seasonDays),"Calendar reset failed");h.succeed();
 }
 @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",batch="range_roads")
 public static void customSnapshotSurvivesBlueprintAndCompletedBuildingReload(GameTestHelper h) {
  BlockPos at=h.absolutePos(new BlockPos(3,2,3)); var snapshot=snapshot(at);
  var blueprint=BlueprintRecord.create(BuildingType.WAREHOUSE,at,at,Direction.NORTH,UUID.randomUUID(),false).withCustomTemplate(snapshot);
  var loaded=BlueprintRecord.load(blueprint.save());
  h.assertTrue(BlueprintTemplates.create(loaded).placements().equals(snapshot.template().placements()),"Custom blueprint reverted to default geometry");
  var building=BuildingRecord.create(BuildingType.WAREHOUSE,at,Direction.NORTH,snapshot.template().minCorner(),snapshot.template().maxCorner(),2,1).withCustomTemplate(snapshot);
  var restored=BuildingRecord.load(building.save());
  h.assertTrue(BlueprintTemplates.createForBuilding(restored).placements().equals(snapshot.template().placements())
   && BlueprintTemplates.createForBuilding(restored).entrances().equals(snapshot.template().entrances()),"Repair/demolition/entry geometry lost on completed-building reload");h.succeed();
 }
 @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",batch="range_roads")
 public static void customImportRejectsExecutableAndMissingMaterialBlocks(GameTestHelper h) {
  BlockPos at=h.absolutePos(new BlockPos(3,2,3));
  for (var state:List.of(Blocks.COMMAND_BLOCK.defaultBlockState(),Blocks.STRUCTURE_BLOCK.defaultBlockState(),Blocks.BARRIER.defaultBlockState(),Blocks.WATER.defaultBlockState(),Blocks.OAK_DOOR.defaultBlockState(),Blocks.RED_BED.defaultBlockState())) {
   boolean refused=false;
   try { CustomTemplateSnapshot.create("invalid",BuildingType.BENCH,at,Direction.NORTH,List.of(new BlueprintPlacement(at,state,state.getBlock().asItem())),List.of(new BlueprintEntrance(at,Direction.NORTH))); }
   catch (IllegalArgumentException expected) { refused=true; }
   h.assertTrue(refused,"An executable or unpayable block entered custom construction: "+state);
  }
  var valid=snapshot(at);var changed=valid.save();changed.putString("Name","changed");boolean rejected=false;
  try { CustomTemplateSnapshot.load(changed); } catch (IllegalArgumentException expected) { rejected=true; }
  h.assertTrue(rejected,"Modified frozen snapshot fingerprint was accepted");h.succeed();
 }
 @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",batch="range_roads")
 public static void oneHundredResidentsShareRangeWithoutDuplicatingFiniteStock(GameTestHelper h) {
  BlockPos at=h.absolutePos(new BlockPos(3,2,3));h.getLevel().setBlock(at,Blocks.BARREL.defaultBlockState(),3);
  var building=BuildingRecord.create(BuildingType.WAGON,at);building.updateFunction(true,BuildingStatus.OPERATIONAL,27,List.of(at));
  var container=(Container)h.getLevel().getBlockEntity(at);container.setItem(0,new ItemStack(Items.BREAD,64));
  for (int count: new int[]{20,50,100}) {
   var residents=new ArrayList<SettlementCitizenEntity>();
   for(int i=0;i<count;i++){var resident=ModEntities.SETTLEMENT_CITIZEN.get().create(h.getLevel());resident.setPos(at.getX()+3.5,at.getY(),at.getZ()+.5);residents.add(resident);}
   long begin=System.nanoTime();
   for(int round=0;round<10;round++){container.setItem(0,new ItemStack(Items.BREAD,64));int actual=0;for(var resident:residents)actual+=WarehouseInventoryService.extractItemForResident(h.getLevel(),building,resident,Items.BREAD,1).getCount();h.assertTrue(actual==Math.min(count,64),"Population range benchmark duplicated stock");}
   ExiledSettlementMod.LOGGER.info("[range-population] residents={} rounds=10 meanMs={} checkpoints=0",count,(System.nanoTime()-begin)/10000000.0);
  }
  container.setItem(0,new ItemStack(Items.BREAD,64));
  int taken=0;
  for (int i=0;i<100;i++) {
   var e=ModEntities.SETTLEMENT_CITIZEN.get().create(h.getLevel());e.setPos(at.getX()+3.5,at.getY(),at.getZ()+.5);
   h.assertTrue(CitizenNavigationReservationService.stationFor(h.getLevel(),e.getUUID(),h.getLevel().getGameTime())==null,"Range interaction allocated a checkpoint");
   taken+=WarehouseInventoryService.extractItemForResident(h.getLevel(),building,e,Items.BREAD,1).getCount();
  }
  h.assertTrue(taken==64 && container.getItem(0).isEmpty(),"Concurrent range access duplicated finite stock: "+taken);
  var remote=ModEntities.SETTLEMENT_CITIZEN.get().create(h.getLevel());remote.setPos(at.getX()+8.5,at.getY(),at.getZ()+.5);container.setItem(0,new ItemStack(Items.BREAD));
  h.assertTrue(WarehouseInventoryService.extractItemForResident(h.getLevel(),building,remote,Items.BREAD,1).isEmpty() && container.getItem(0).getCount()==1,"Out-of-range resident withdrew stock");
  remote.setPos(at.getX()+3.5,at.getY(),at.getZ()+.5);h.getLevel().setBlock(at.east(),Blocks.STONE.defaultBlockState(),3);h.getLevel().setBlock(at.east().above(),Blocks.STONE.defaultBlockState(),3);
  h.assertTrue(WarehouseInventoryService.extractItemForResident(h.getLevel(),building,remote,Items.BREAD,1).isEmpty(),"Resident took stock through a wall");h.succeed();
 }
 @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",batch="range_roads",timeoutTicks=1800)
 public static void customBuildingTakesRealMaterialsCompletesReloadsAndDemolishes(GameTestHelper h) {
  var level=h.getLevel();BlockPos core=h.absolutePos(new BlockPos(360000+fixtureIndex++*256,4,32000));List<ChunkPos> chunks=new ArrayList<>();
  for (int x=(core.getX()-32)>>4;x<=(core.getX()+32)>>4;x++)for(int z=(core.getZ()-32)>>4;z<=(core.getZ()+32)>>4;z++){level.setChunkForced(x,z,true);chunks.add(new ChunkPos(x,z));}
  for(BlockPos p:BlockPos.betweenClosed(core.offset(-25,-1,-25),core.offset(25,-1,25))){level.setBlock(p,Blocks.DIRT.defaultBlockState(),2);for(int y=1;y<5;y++)level.setBlock(p.above(y),Blocks.AIR.defaultBlockState(),2);}
  level.setBlock(core,ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),3);UUID owner=UUID.randomUUID();var data=SettlementSavedData.get(level);var settlement=data.createOrRecover(core,owner,"Custom lifecycle").value();
  var citizens=new ArrayList<>(settlement.citizens());var citizen=citizens.getFirst();for(int i=1;i<citizens.size();i++)citizens.get(i).markDead(core);
  citizen.assignProfession(CitizenProfession.WORKER,null);citizen.insertIntoInventory(new ItemStack(Items.BREAD,4));
  BlockPos stock=core.west(10);level.setBlock(stock,Blocks.BARREL.defaultBlockState(),3);var source=settlement.addBuilding(BuildingType.WAGON,stock);source.updateFunction(true,BuildingStatus.OPERATIONAL,27,List.of(stock));
  var inventory=(Container)level.getBlockEntity(stock);inventory.setItem(0,new ItemStack(Items.OAK_PLANKS,2));inventory.setItem(1,new ItemStack(Items.BARREL));
  BlockPos at=core.east(12);var snapshot=snapshot(at);var result=BlueprintService.placeCustom(level,owner,snapshot,null);h.assertTrue(result.success(),"Custom site rejected: "+result.reason());
  level.setDayTime(6000);citizen.updateProjection(null,core.south(4));CitizenProjectionService.ensureSettlementCitizens(level,settlement);
  boolean[] visited={false};BuildingRecord[] completed={null};
  h.onEachTick(()->{ if(!settlement.active())return;level.setDayTime(6000);var e=CitizenProjectionService.findProjection(level,citizen);if(e!=null){citizen.updateProjection(e.getUUID(),e.blockPosition());visited[0]|=WarehouseInventoryService.canInteract(level,source,e);if(h.getTick()%8==0)CitizenWorkService.tickSettlement(level,data,settlement);} });
  h.startSequence().thenWaitUntil(()->{completed[0]=settlement.buildingAt(at);h.assertTrue(completed[0]!=null,"Custom construction pending: "+citizen.workState()+"/"+citizen.workFailure());})
   .thenExecute(()->{
    h.assertTrue(visited[0]&&inventory.getItem(0).isEmpty()&&inventory.getItem(1).isEmpty(),"Custom structure bypassed real material fetching");
    h.assertTrue(completed[0].customTemplate()!=null && completed[0].operational(),"Custom warehouse lost snapshot or functional container");
    var restored=BuildingRecord.load(completed[0].save());h.assertTrue(BlueprintTemplates.createForBuilding(restored).placements().equals(snapshot.template().placements()),"Reload changed custom demolition geometry");
    completed[0].queueDemolition();data.touch();
   }).thenWaitUntil(()->h.assertTrue(!completed[0].active(),"Custom demolition pending: "+citizen.workFailure()))
   .thenExecute(()->{for(var p:snapshot.template().placements())h.assertTrue(level.getBlockState(p.position()).isAir(),"Custom demolition left a snapshot block");settlement.deactivate();var e=CitizenProjectionService.findProjection(level,citizen);if(e!=null)e.discard();chunks.forEach(c->level.setChunkForced(c.x,c.z,false));}).thenSucceed();
 }
 @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",batch="range_roads")
 public static void customBuildingMetadataKeepsActualBoundsAcrossNetwork(GameTestHelper h) {
  BlockPos at=h.absolutePos(new BlockPos(3,2,3));var s=new SettlementRecord(UUID.randomUUID(),"Snapshot",at,UUID.randomUUID());var custom=snapshot(at);
  var blueprint=s.addBlueprint(BuildingType.WAREHOUSE,at,at,Direction.NORTH,s.ownerId(),false).withCustomTemplate(custom);
  var entry=com.hkin.exiledsettlement.network.PlacementNetworking.blueprintEntry(h.getLevel(),s,blueprint);
  var payload=new com.hkin.exiledsettlement.network.SettlementDetailsPayload(at,1,List.of(entry));
  var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),h.getLevel().registryAccess());
  try {com.hkin.exiledsettlement.network.SettlementDetailsPayload.STREAM_CODEC.encode(buffer,payload);
   var restored=com.hkin.exiledsettlement.network.SettlementDetailsPayload.STREAM_CODEC.decode(buffer).buildings().getFirst();
   h.assertTrue(restored.customTemplate()&&restored.minCorner().equals(custom.template().minCorner())&&restored.maxCorner().equals(custom.template().maxCorner())&&restored.name().equals(custom.name()),"Custom overview fell back to built-in template bounds");
  } finally {buffer.release();}h.succeed();
 }
 @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",batch="range_roads")
 public static void seasonCommandRequiresAdministratorAndLeavesWorldClockUnchanged(GameTestHelper h) {
  var level=h.getLevel();var owner=h.makeMockServerPlayerInLevel();var member=h.makeMockServerPlayerInLevel();
  BlockPos core=h.absolutePos(new BlockPos(390000,4,32000));var data=SettlementSavedData.get(level);var s=data.createOrRecover(core,owner.getUUID(),"Calendar command").value();s.addMember(member.getUUID());
  owner.moveTo(core.getCenter());member.moveTo(core.getCenter());long worldTime=level.getDayTime();var commands=level.getServer().getCommands();
  CommunityLifeService.migrateCalendar(level,data,s);
  var before=s.community().save();
  var seasonNode=commands.getDispatcher().getRoot().getChild("exiledsettlement").getChild("season");
  for(var source:List.of(owner.createCommandSourceStack().withPermission(0),owner.createCommandSourceStack().withPermission(1),member.createCommandSourceStack().withPermission(0))) {
   for(String action:List.of("set winter 3","length 10","reset","worldday 91","for "+s.id()+" set winter")) {
    h.assertTrue(executeCommand(commands,source,"exiledsettlement season "+action)==0 && s.community().save().equals(before),"Non-administrator changed calendar with: "+action);
   }
   for(String action:List.of("set","length","reset","worldday","for")) h.assertTrue(!seasonNode.getChild(action).canUse(source),"Non-administrator can discover testing command: "+action);
  }
  h.assertTrue(executeCommand(commands,owner.createCommandSourceStack().withPermission(0),"exiledsettlement season status")==1,"Owner cannot read calendar");
  h.assertTrue(executeCommand(commands,member.createCommandSourceStack().withPermission(0),"exiledsettlement season status")==1,"Member cannot read calendar");
  var administrator=owner.createCommandSourceStack().withPermission(2);
  h.assertTrue(executeCommand(commands,administrator,"exiledsettlement season length 10")==1,"Administrator could not adjust test season length");
  h.assertTrue(executeCommand(commands,administrator,"exiledsettlement season set winter 3")==1,"Administrator could not select test winter day");
  h.assertTrue(executeCommand(commands,administrator,"exiledsettlement season set summer 11")==0,"Out-of-season day accepted");
  h.assertTrue(level.getDayTime()==worldTime&&s.community().seasonLength==10,"Calendar command changed global time or accepted invalid value");
  h.assertTrue(executeCommand(commands,level.getServer().createCommandSourceStack(),"exiledsettlement season for "+s.id()+" reset")==1,"Console could not reset test calendar");
  h.assertTrue(level.getDayTime()==worldTime&&s.community().calendarOffsetDays==0&&s.community().seasonLength==SeasonCalendar.length(CommunityContent.seasonDays),"Console reset changed world clock or failed to restore calendar");s.deactivate();data.touch();h.succeed();
 }
 private static int executeCommand(net.minecraft.commands.Commands commands,net.minecraft.commands.CommandSourceStack source,String command) {
  try {return commands.getDispatcher().execute(command,source);}catch(com.mojang.brigadier.exceptions.CommandSyntaxException error){return 0;}
 }
 private static CustomTemplateSnapshot snapshot(BlockPos at) {
  return CustomTemplateSnapshot.create("My warehouse",BuildingType.WAREHOUSE,at,Direction.NORTH,List.of(
   new BlueprintPlacement(at,Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS),new BlueprintPlacement(at.east(),Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS),
   new BlueprintPlacement(at.south(),Blocks.BARREL.defaultBlockState(),Items.BARREL)),List.of(new BlueprintEntrance(at.north(),Direction.NORTH)));
 }
}
