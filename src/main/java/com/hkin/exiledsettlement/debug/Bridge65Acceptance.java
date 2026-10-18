package com.hkin.exiledsettlement.debug;
import com.google.gson.*;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import com.hkin.exiledsettlement.road.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;
/** Explicit empty-server fixture. Once started, only observes construction and requests real walks. */
@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement")
public final class Bridge65Acceptance {
 private static ServerLevel level;private static SettlementRecord settlement;private static TerrainProject project;private static BuildingRecord warehouse;
 private static final BlockPos ORIGIN=new BlockPos(4096,90,4096);private static long start;private static int maximum,waterTicks;private static boolean stopped;
 private static final List<UUID> workers=new ArrayList<>();private static final int[] trips={0,0};private static final BitSet crossings=new BitSet();private static final Set<UUID> fetched=new HashSet<>();
 private static final Map<net.minecraft.world.item.Item,Integer> initial=new LinkedHashMap<>();private static boolean walking;private static String failure="";
 private static void guard(ServerLevel l){if(!SaveAcceptanceService.enabled() || !l.getServer().isDedicatedServer() || l.getServer().getPlayerCount()!=0)throw new IllegalStateException("EMPTY_TEST_SERVER_REQUIRED");}
 public static String start(ServerLevel l){
  guard(l);if(!SettlementSavedData.get(l).settlements().isEmpty())throw new IllegalStateException("FRESH_WORLD_REQUIRED");
  level=l;l.setDayTime(1000);l.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false,l.getServer());
  for(int x=-55;x<24;x++)for(int z=-35;z<=35;z++){
   var base=ORIGIN.offset(x,0,z);l.getChunkAt(base);l.setChunkForced(base.getX()>>4,base.getZ()>>4,true);
   for(int y=-9;y<7;y++)l.setBlock(base.above(y),(x>=0 && x<4 ? y<=-7?Blocks.STONE:Blocks.AIR : y<-1?Blocks.DIRT:y==-1?Blocks.GRASS_BLOCK:Blocks.AIR).defaultBlockState(),2);
  }
  // A naturally uneven approach must be graded near the chosen body instead
  // of extending until a pre-existing flat strip happens to appear.
  for(int x=-24;x<=-3;x++)for(int z=-8;z<=8;z++)for(int y=-1;y<=3;y++){
   int floor=Math.floorMod(x,2)==0?-1:1;
   l.setBlock(ORIGIN.offset(x,y,z),(y<floor?Blocks.DIRT:y==floor?Blocks.GRASS_BLOCK:Blocks.AIR).defaultBlockState(),2);
  }
  l.setBlock(ORIGIN.offset(-30,2,0),Blocks.CHEST.defaultBlockState(),2);
  var chestAt=ORIGIN.offset(-4,0,0);l.setBlock(chestAt,Blocks.CHEST.defaultBlockState(),2);
  ((net.minecraft.world.level.block.entity.ChestBlockEntity)l.getBlockEntity(chestAt)).setItem(0,new ItemStack(Items.DIAMOND,13));
  for(int y=0;y<3;y++)l.setBlock(ORIGIN.offset(-4,y,1),Blocks.OAK_LOG.defaultBlockState(),2);
  var roadAt=ORIGIN.offset(-4,0,-1);l.setBlock(roadAt,Blocks.STONE_BRICKS.defaultBlockState(),2);
  var data=SettlementSavedData.get(l);var s=data.createOrRecover(ORIGIN.offset(-16,0,12),UUID.randomUUID(),"Bridge65 native acceptance").value();settlement=s;s.upgradeLevel();s.upgradeLevel();
  for(int pass=0;pass<8;pass++)for(int x=-4;x<=2;x++)for(int z=-3;z<=3;z++)s.claimChunk((ORIGIN.getX()>>4)+x,(ORIGIN.getZ()>>4)+z);
  s.terrain().enabled=false;s.autoRoads().enabled=false;s.decorations().enabled=false;s.community().stewardEnabled=false;
  for(var c:s.citizens())c.setManualAssignment(CitizenProfession.HERDSMAN,null);
  var old=new TerrainProject(UUID.randomUUID(),"Bridge65 transferred public",roadAt,List.of(
   new TerrainProject.Step(roadAt,Blocks.STONE_BRICKS.defaultBlockState(),Blocks.AIR.defaultBlockState()),
   new TerrainProject.Step(roadAt,Blocks.DIRT_PATH.defaultBlockState(),Blocks.STONE_BRICKS.defaultBlockState())));
  old.publicGround=true;old.mark(0);s.terrain().index(old);
  var plan=new PlayerBridgePlan(ORIGIN.offset(-2,-1,-2),ORIGIN.offset(5,-1,2),true,Integer.MIN_VALUE);
  for(int i=0;i<10000 && !plan.ready && plan.problem.isEmpty();i++)plan.survey(l,s,32);if(!plan.ready)throw new IllegalStateException(plan.problem);
  if(plan.minRow()<ORIGIN.getX()-10)throw new IllegalStateException("NEARBY_BANK_SKIPPED");
  var t=BlueprintTemplates.create(BuildingType.WAREHOUSE,ORIGIN.offset(-43,0,-10),Direction.EAST);
  for(var p:t.placements())l.setBlock(p.position(),p.state(),2);for(var p:t.placements())BlueprintBlockStateResolver.refreshExisting(l,t,p);
  warehouse=s.addBuilding(t.type(),t.registrationPosition(),t.facing(),t.minCorner(),t.maxCorner(),t.transitionMargin(),BlueprintTemplates.currentTemplateVersion(t.type()));BuildingFunctionService.validateSingle(l,data,s,warehouse);
  if(!warehouse.operational())throw new IllegalStateException("WAREHOUSE_INVALID");
  for(var e:plan.requirements().entrySet()){WarehouseInventoryService.insert(l,warehouse,new ItemStack(e.getKey(),e.getValue()+32));initial.put(e.getKey(),e.getValue()+32);}
  for(var item:List.of(Items.IRON_AXE,Items.IRON_PICKAXE,Items.IRON_SHOVEL))WarehouseInventoryService.insert(l,warehouse,new ItemStack(item,12));WarehouseInventoryService.insert(l,warehouse,new ItemStack(Items.BREAD,512));
  for(var c:s.citizens().stream().limit(2).toList()){
   var job=workers.isEmpty()?CitizenProfession.WORKER:CitizenProfession.HERDSMAN;c.setManualAssignment(job,null);c.assignProfession(job,null);workers.add(c.id());
   var e=com.hkin.exiledsettlement.registry.ModEntities.SETTLEMENT_CITIZEN.get().create(l);e.moveTo(ORIGIN.getX()-15.5,ORIGIN.getY(),ORIGIN.getZ()+4.5+workers.size()*2,0,0);
   e.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());e.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());l.addFreshEntity(e);c.updateProjection(e.getUUID(),e.blockPosition());
  }
  project=PlayerBridgePlan.publish(l,s,plan).terrainProject();start=l.getGameTime();data.touch();write("bridge65-inputs",report());return inspect();
 }
 @net.neoforged.bus.api.SubscribeEvent public static void tick(ServerTickEvent.Post event){
  if(level==null || stopped || !event.getServer().tickRateManager().runsNormally())return;
  level.resetEmptyTime();try{
   maximum=Math.max(maximum,OptionalWorkBudget.snapshot(settlement).pending());if(maximum>1000)throw new IllegalStateException("QUOTA_EXCEEDED");
   int index=0;for(var id:workers){var c=settlement.citizens().stream().filter(v->v.id().equals(id)).findFirst().orElseThrow();var e=CitizenProjectionService.findProjection(level,c);if(e==null)continue;
    if(!e.isAlive() || !c.alive())throw new IllegalStateException("WORKER_DIED");if(e.isInWater()){waterTicks++;throw new IllegalStateException("WORKER_ENTERED_WATER");}
    if(c.inventory().stream().anyMatch(v->v.is(Items.OAK_LOG)||v.is(Items.OAK_PLANKS)))fetched.add(id);
    if(project.completed){
     if(!walking){for(var wid:workers){var wc=settlement.citizens().stream().filter(v->v.id().equals(wid)).findFirst().orElseThrow();wc.clearWork();wc.setManualAssignment(CitizenProfession.HERDSMAN,null);wc.assignProfession(CitizenProfession.HERDSMAN,null);wc.insertIntoInventory(new ItemStack(Items.APPLE,7));}walking=true;}
     if(trips[index]<3){boolean far=(index==0?trips[index]==1:trips[index]!=1);var goal=project.playerBridge.shore(far).offset(0,0,index==0?-1:1);e.navigateTo(goal,.65);
      if(e.getX()>ORIGIN.getX()+.5 && e.getX()<ORIGIN.getX()+3.5 && Math.abs(e.getZ()-ORIGIN.getZ())<2 && e.getY()>=project.playerBridge.surface)crossings.set(index*3+trips[index]);
      if(e.blockPosition().distSqr(goal)<4)trips[index]++;
     }
    }index++;
   }
   if(project.completed && trips[0]>=3 && trips[1]>=3){
    if(!crossings.get(1)||!crossings.get(2)||!crossings.get(4)||!crossings.get(5))throw new IllegalStateException("NO_COMPLETE_BIDIRECTIONAL_PATH");
    if(fetched.size()!=1 || !TerrainService.constructionComplete(level,project))throw new IllegalStateException("INCOMPLETE_REAL_CHAIN");
    for(var item:List.of(Items.OAK_LOG,Items.OAK_PLANKS,Items.OAK_FENCE,Items.OAK_STAIRS)){
     int left=WarehouseInventoryService.countItem(level,warehouse,item)+settlement.citizens().stream().flatMap(c->c.inventory().stream()).filter(v->v.is(item)).mapToInt(ItemStack::getCount).sum();
     long placed=project.steps.stream().filter(v->v.fill() && v.target().getBlock().asItem()==item && level.getBlockState(v.position()).getBlock().asItem()==item).count();
     if(initial.getOrDefault(item,0)+(item==Items.OAK_LOG?3:0)-left!=placed)throw new IllegalStateException("MATERIAL_BALANCE_"+item);
    }
    for(var id:workers)if(settlement.citizens().stream().filter(c->c.id().equals(id)).flatMap(c->c.inventory().stream()).filter(v->v.is(Items.APPLE)).mapToInt(ItemStack::getCount).sum()!=7)throw new IllegalStateException("CARGO_CHANGED");
    verifyTransferAndCargo(level,settlement);stopped=true;write("bridge65-result",report());
   }
   if(level.getGameTime()%200==0)write("bridge65-progress",report());
  }catch(Exception ex){failure=ex.toString();stopped=true;write("bridge65-result",report());}
 }
 private static JsonObject report(){var j=new JsonObject();j.addProperty("freshWorld",true);j.addProperty("playerWorldReadOrCopied",false);j.addProperty("settlement",settlement.id().toString());j.addProperty("project",project.id.toString());j.addProperty("start",start);j.addProperty("gameTime",level.getGameTime());j.addProperty("dayTime",level.getDayTime());j.addProperty("ticks",level.getGameTime()-start);j.addProperty("tickRate",level.getServer().tickRateManager().tickrate());j.addProperty("steps",project.steps.size());j.addProperty("finished",project.finished.cardinality());j.addProperty("phase",project.playerBridge.phase);j.addProperty("completed",project.completed);j.addProperty("maxPending",maximum);j.addProperty("waterTicks",waterTicks);j.addProperty("fetchedWorkers",fetched.size());j.addProperty("constructionWorkers",1);j.addProperty("roadRecords",settlement.autoRoads().routes.size());j.addProperty("selectedBodyOnly",true);j.addProperty("dryValley",true);j.addProperty("autoNearExtension",project.playerBridge.start-project.playerBridge.minRow());j.addProperty("autoFarExtension",project.playerBridge.maxRow()-project.playerBridge.end);j.addProperty("stopped",stopped);j.addProperty("passed",stopped && failure.isEmpty());j.addProperty("failure",failure);j.add("trips",new Gson().toJsonTree(trips));j.addProperty("crossings",crossings.toString());j.add("workers",new Gson().toJsonTree(workers));return j;}
 private static void write(String label,JsonObject j){try{Files.createDirectories(SaveAcceptanceService.directory());Files.writeString(SaveAcceptanceService.file(label,".json"),new Gson().toJson(j));}catch(Exception e){throw new IllegalStateException(e);}}
 public static String verifyReload(ServerLevel l){
  guard(l);var s=SettlementSavedData.get(l).settlements().stream().filter(v->v.name().equals("Bridge65 native acceptance")).findFirst().orElseThrow();
  var p=s.terrain().projects.values().stream().filter(v->v.playerBridge!=null).findFirst().orElseThrow();
  var diagnostic=new JsonObject();diagnostic.addProperty("completed",p.completed);diagnostic.addProperty("finished",p.finished.cardinality());diagnostic.addProperty("steps",p.steps.size());
  diagnostic.add("mismatch",new Gson().toJsonTree(p.steps.stream().filter(v->v.fill() && !BridgeService.matches(l,p,v.position())).limit(12).map(v->v.position()+"="+l.getBlockState(v.position())+" expected="+v.target()+" loaded="+l.hasChunkAt(v.position())).toList()));
  diagnostic.add("blockedWalk",new Gson().toJsonTree(p.accessSurfaces.stream().filter(at->!BridgeService.ready(l,s,at)).limit(12).map(BlockPos::toShortString).toList()));write("bridge65-reload-diagnostic",diagnostic);
  if(!p.completed || !TerrainService.constructionComplete(l,p) || p.accessSurfaces.stream().anyMatch(at->!BridgeService.ready(l,s,at)))throw new IllegalStateException("SAVED_BRIDGE_NOT_COMPLETE "+diagnostic);
  verifyTransferAndCargo(l,s);
  var j=new JsonObject();j.addProperty("passed",true);j.addProperty("finished",p.finished.cardinality());j.addProperty("steps",p.steps.size());j.addProperty("realServerRestart",true);j.addProperty("worldEdits",0);write("bridge65-reload",j);return new Gson().toJson(j);
 }
 public static String inspect(){return level==null?"NOT_STARTED":new Gson().toJson(report());}
 private static void verifyTransferAndCargo(ServerLevel l,SettlementRecord s){
  var old=s.terrain().projects.values().stream().filter(v->v.name.equals("Bridge65 transferred public")).findFirst().orElseThrow();
  if(old.abandoned.cardinality()!=2 || old.retiredPaid.cardinality()!=1 || !old.retiredPaid.get(0))throw new IllegalStateException("TRANSFER_EVIDENCE_CHANGED");
  int diamonds=s.citizens().stream().flatMap(c->c.inventory().stream()).filter(v->v.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum();
  for(var b:s.buildings())if(b.type()==BuildingType.WAREHOUSE)diamonds+=WarehouseInventoryService.countItem(l,b,Items.DIAMOND);
  if(diamonds!=13)throw new IllegalStateException("CONTAINER_CONTENTS_"+diamonds);
 }
 private Bridge65Acceptance(){}
}
