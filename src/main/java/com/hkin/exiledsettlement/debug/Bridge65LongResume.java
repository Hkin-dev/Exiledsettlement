package com.hkin.exiledsettlement.debug;
import com.google.gson.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.building.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;
import java.nio.file.*;
/** Resume only the owned long-bridge fixture. Finite warehouse resupply, no world edits or resident relocation. */
@net.neoforged.fml.common.EventBusSubscriber(modid="exiledsettlement")
public final class Bridge65LongResume {
 private static ServerLevel level;private static SettlementRecord s;private static TerrainProject p;private static BuildingRecord warehouse;private static CitizenRecord carrier;
 private static BlockPos origin;private static long start;private static int before,maxPending,trip;private static boolean startedWalking,stopped;private static String failure="";
 private static final BitSet out=new BitSet(),back=new BitSet();private static final List<UUID> workers=new ArrayList<>();
 private static void guard(ServerLevel l){if(!SaveAcceptanceService.enabled() || !l.getServer().isDedicatedServer() || l.getServer().getPlayerCount()!=0)throw new IllegalStateException("EMPTY_TEST_SERVER_REQUIRED");}
 public static String start(ServerLevel l){
  guard(l);if(level!=null)throw new IllegalStateException("ALREADY_STARTED");
  s=SettlementSavedData.get(l).settlements().stream().filter(v->v.active() && v.terrain().projects.values().stream().anyMatch(b->b.playerBridge!=null && b.playerBridge.width==9 && b.playerBridge.end-b.playerBridge.start==131)).findFirst().orElseThrow();
  p=s.terrain().projects.values().stream().filter(b->b.playerBridge!=null && b.playerBridge.width==9).findFirst().orElseThrow();
  if(p.finished.cardinality()<2000 || p.completed || p.cancelled)throw new IllegalStateException("INCOMPLETE_LONG_FIXTURE_REQUIRED");
  warehouse=s.buildings().stream().filter(b->b.type()==BuildingType.WAREHOUSE).findFirst().orElseThrow();
  if(WarehouseInventoryService.countItem(l,warehouse,Items.IRON_SHOVEL)!=0)throw new IllegalStateException("EXPECTED_EXHAUSTED_SHOVELS");
  level=l;origin=s.corePosition().offset(16,0,-12);carrier=s.citizens().iterator().next();
  for(var c:s.citizens())if(c.profession()==CitizenProfession.WORKER)workers.add(c.id());
  SaveAcceptanceService.load(l,s,11);for(var at:p.bridgeDeck)l.getChunkAt(at);checkMaterials();
  before=p.finished.cardinality();start=l.getGameTime();
  int accepted=24-WarehouseInventoryService.insert(l,warehouse,new ItemStack(Items.IRON_SHOVEL,24)).getCount();if(accepted!=24)throw new IllegalStateException("WAREHOUSE_RESUPPLY_FAILED");
  SettlementSavedData.get(l).touch();write("bridge65-long-resume-inputs");return inspect();
 }
 private static void checkMaterials(){
  var initial=p.playerBridge.requirements();for(var item:List.of(Items.OAK_LOG,Items.OAK_PLANKS,Items.OAK_FENCE,Items.OAK_STAIRS)){
   int left=WarehouseInventoryService.countItem(level,warehouse,item)+s.citizens().stream().flatMap(c->c.inventory().stream()).filter(v->v.is(item)).mapToInt(ItemStack::getCount).sum();
   long placed=p.steps.stream().filter(v->v.fill() && v.target().getBlock().asItem()==item && level.getBlockState(v.position()).getBlock().asItem()==item).count();
   if(initial.getOrDefault(item,0)+64-left!=placed)throw new IllegalStateException("MATERIAL_BALANCE_"+item+" initial="+(initial.getOrDefault(item,0)+64)+" left="+left+" placed="+placed);
  }
 }
 @net.neoforged.bus.api.SubscribeEvent public static void tick(ServerTickEvent.Post event){
  if(level==null || stopped || !event.getServer().tickRateManager().runsNormally())return;level.resetEmptyTime();
  try{
   if(p.cancelled || !p.completed && s.blueprint(p.id)==null)throw new IllegalStateException("BRIDGE_WORK_OR_ROUTE_LOST");
   maxPending=Math.max(maxPending,OptionalWorkBudget.snapshot(s).pending());if(maxPending>1000)throw new IllegalStateException("QUOTA");
   for(var id:workers){var c=s.citizens().stream().filter(v->v.id().equals(id)).findFirst().orElseThrow();var e=CitizenProjectionService.findProjection(level,c);if(!c.alive() || e!=null && (!e.isAlive() || e.isInWater()))throw new IllegalStateException("WORKER_UNSAFE_"+id);}
   if(p.completed){
    if(!startedWalking){carrier.clearWork();carrier.setManualAssignment(CitizenProfession.HERDSMAN,null);carrier.assignProfession(CitizenProfession.HERDSMAN,null);carrier.insertIntoInventory(new ItemStack(Items.APPLE,7));startedWalking=true;}
    var e=CitizenProjectionService.findProjection(level,carrier);if(e!=null){
     var goal=origin.offset(trip==1?135:-10,0,0);e.navigateTo(goal,.65);int column=e.getBlockX()-origin.getX();
     if(column>=0 && column<128 && Math.abs(e.getZ()-origin.getZ())<=3.5 && e.getY()>=p.playerBridge.surface-.01){if(trip==1)out.set(column);if(trip==2)back.set(column);}
     if(e.blockPosition().distSqr(goal)<4)trip++;
     if(trip>=3){
      if(out.cardinality()!=128 || back.cardinality()!=128)throw new IllegalStateException("ROUNDTRIP_MISSED_COLUMNS");
      if(!TerrainService.constructionComplete(level,p) || p.accessSurfaces.stream().anyMatch(at->!BridgeService.ready(level,s,at)))throw new IllegalStateException("WORLD_INCOMPLETE");
      if(carrier.inventory().stream().filter(v->v.is(Items.APPLE)).mapToInt(ItemStack::getCount).sum()!=7)throw new IllegalStateException("CARGO_CHANGED");checkMaterials();stopped=true;write("bridge65-long-result");
     }
    }
   }
   if(level.getGameTime()%200==0)write("bridge65-long-progress");
  }catch(Exception e){failure=e.toString();stopped=true;write("bridge65-long-result");}
 }
 private static JsonObject report(){var j=new JsonObject();j.addProperty("passed",stopped && failure.isEmpty());j.addProperty("stopped",stopped);j.addProperty("failure",failure);j.addProperty("settlement",s.id().toString());j.addProperty("before",before);j.addProperty("finished",p.finished.cardinality());j.addProperty("steps",p.steps.size());j.addProperty("completed",p.completed);j.addProperty("phase",p.playerBridge.phase);j.addProperty("resumedAt",start);j.addProperty("gameTime",level.getGameTime());j.addProperty("dayTime",level.getDayTime());j.addProperty("elapsedTicks",level.getGameTime()-start);j.addProperty("tickRate",level.getServer().tickRateManager().tickrate());j.addProperty("maxPending",maxPending);j.addProperty("addedWarehouseShovels",24);j.addProperty("remainingWarehouseShovels",WarehouseInventoryService.countItem(level,warehouse,Items.IRON_SHOVEL));j.addProperty("worldEdits",0);j.addProperty("teleports",0);j.addProperty("trip",trip);j.addProperty("forwardColumns",out.cardinality());j.addProperty("returnColumns",back.cardinality());return j;}
 private static void write(String name){try{Files.createDirectories(SaveAcceptanceService.directory());Files.writeString(SaveAcceptanceService.file(name,".json"),new Gson().toJson(report()));}catch(Exception e){throw new IllegalStateException(e);}}
 public static String inspect(){return level==null?"NOT_STARTED":new Gson().toJson(report());}
 private Bridge65LongResume(){}
}
