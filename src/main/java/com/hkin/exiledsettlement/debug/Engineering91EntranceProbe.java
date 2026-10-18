package com.hkin.exiledsettlement.debug;
import com.google.gson.*;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import java.nio.file.*;
import java.util.*;
/** Diagnostic native doorway round trip. Spawn is explicit input; no later reposition or edits. */
public final class Engineering91EntranceProbe {
 private static Trial trial;private static JsonObject finished;
 private static final class Trial {ServerLevel level;SettlementCitizenEntity actor;String route,blocker;BlockPos start,inside,door,side;Direction outward;long began;int stage;boolean guided,enteredDoor,exitedDoor;double previousX,previousY,previousZ;final JsonArray positions=new JsonArray();}
 public static String start(ServerLevel l,String id) {
  return start(l,id,false);
 }
 public static String start(ServerLevel l,String id,boolean guided) {
  if(!SaveAcceptanceService.enabled() || !l.getServer().isDedicatedServer() || l.getServer().getPlayerCount()!=0)throw new IllegalStateException("EMPTY_TEST_SERVER_REQUIRED");
  if(trial!=null)throw new IllegalStateException("PROBE_RUNNING");
  var s=SettlementSavedData.get(l).findByCore(Engineering91Acceptance.CORE).orElseThrow();
  var route=s.autoRoads().routes.values().stream().filter(r->r.id.toString().equals(id) && !r.suppressed).findFirst().orElseThrow();
  var b=s.building(route.building);if(b==null)throw new IllegalStateException("REAL_FORMAL_BUILDING_REQUIRED");
  var template=BlueprintTemplates.createForBuilding(b);var door=template.entrances().stream().filter(e->RoadEntranceService.outside(template,e).equals(route.entrance)).findFirst().orElseThrow();
  if(!RoadSurfaceProfile.usable(l,route.entrance))throw new IllegalStateException("REAL_OUTSIDE_FOOTING_REQUIRED");
  var e=ModEntities.SETTLEMENT_CITIZEN.get().create(l);if(e==null)throw new IllegalStateException("NO_ACTOR");
  var start=route.entrance.above();double y=route.entrance.getY()+l.getBlockState(route.entrance).getCollisionShape(l,route.entrance).max(Direction.Axis.Y);
  e.moveTo(start.getX()+.5,y,start.getZ()+.5,0,0);e.setOnGround(true);e.setPersistenceRequired();
  if(!l.noCollision(e,e.getBoundingBox()))throw new IllegalStateException("SPAWN_BLOCKED");
  BlockPos inside=null;var inward=door.outward().getOpposite();var lateral=inward.getClockWise();
  for(int distance=2;distance<=4 && inside==null;distance++)for(int offset:new int[]{0,1,-1,2,-2}){
   var at=door.position().relative(inward,distance).relative(lateral,offset);
   if(b.contains(at) && e.canStandAtPosition(at)){inside=at;break;}
  }
  if(inside==null)throw new IllegalStateException("ACTUAL_INDOOR_WORKFACE_REQUIRED");
  if(!l.addFreshEntity(e))throw new IllegalStateException("SPAWN_FAILED");
  var t=new Trial();t.level=l;t.actor=e;t.route=id;t.start=start;t.inside=inside;t.door=door.position();t.outward=door.outward();t.guided=guided;
  if(guided){for(int sign:new int[]{-1,1}){var side=t.door.relative(t.outward).relative(t.outward.getClockWise(),sign);if(e.canStandAtPosition(side)){t.side=side;break;}}if(t.side==null){e.discard();throw new IllegalStateException("NO_ACTUAL_SIDE_LANDING");}}
  t.previousX=e.getX();t.previousY=e.getY();t.previousZ=e.getZ();t.blocker=RoadEntranceService.blocker(l,s,route);t.began=l.getGameTime();trial=t;finished=null;
  return "STARTED native entrance round trip; indoor="+inside+"; blocker="+t.blocker+"; diagnostic input, no edits or construction credit";
 }
 public static void tick(MinecraftServer server) {
  var t=trial;if(t==null)return;var now=t.level.getGameTime();
  trackDoor(t);
  if(now%5==0){var p=new JsonArray();p.add(now-t.began);p.add(t.actor.getX());p.add(t.actor.getY());p.add(t.actor.getZ());t.positions.add(p);}
  if(!t.actor.isAlive() || now-t.began>=2000){finish(false,"native navigation timeout or death");return;}
  if(t.guided){
   var target=switch(t.stage){case 0,4->t.side;case 1,3->t.door;case 2->t.inside;default->t.start;};
   double distance=t.actor.distanceToSqr(target.getX()+.5,target.getY(),target.getZ()+.5);
   if(distance<.04 && t.actor.onGround()){
    if(t.stage++==5){finish(t.enteredDoor && t.exitedDoor,"guided native MoveControl doorway round trip; navigation fallback still requires implementation");return;}
   }else if(t.stage>=1 && t.stage<=4 || distance<1){
    t.actor.getNavigation().stop();t.actor.getMoveControl().setWantedPosition(target.getX()+.5,target.getY(),target.getZ()+.5,.7);
   }else if(now%5==0)t.actor.navigateTo(target,.7);
   return;
  }
  var target=t.stage==0?t.inside:t.start;
  if(t.actor.distanceToSqr(target.getX()+.5,target.getY(),target.getZ()+.5)<.81 && t.actor.onGround()){
   if(t.stage++==1){finish(t.enteredDoor && t.exitedDoor,t.enteredDoor && t.exitedDoor?"native actor crossed the declared entrance in both directions":"interior round trip used another opening; declared entrance not verified");return;}
  } else if(now%5==0)t.actor.navigateTo(target,.7);
 }
 private static void trackDoor(Trial t){
  double ox=t.outward.getStepX(),oz=t.outward.getStepZ(),cx=t.door.getX()+.5,cz=t.door.getZ()+.5;
  double previous=(t.previousX-cx)*ox+(t.previousZ-cz)*oz,current=(t.actor.getX()-cx)*ox+(t.actor.getZ()-cz)*oz;
  if(previous*current<=0 && Math.abs(previous-current)>1e-6){
   double fraction=previous/(previous-current),x=t.previousX+(t.actor.getX()-t.previousX)*fraction,z=t.previousZ+(t.actor.getZ()-t.previousZ)*fraction,y=t.previousY+(t.actor.getY()-t.previousY)*fraction;
   if(Math.abs((x-cx)*oz-(z-cz)*ox)<.5 && Math.abs(y-t.door.getY())<.25){
    if(previous>0 && current<=0 && (t.guided?t.stage<3:t.stage==0))t.enteredDoor=true;
    if(previous<0 && current>=0 && (t.guided?t.stage>=3:t.stage==1))t.exitedDoor=true;
   }
  }
  t.previousX=t.actor.getX();t.previousY=t.actor.getY();t.previousZ=t.actor.getZ();
 }
 private static JsonObject report(){var t=trial;var out=new JsonObject();out.addProperty("route",t.route);out.addProperty("elapsed",t.level.getGameTime()-t.began);out.addProperty("stage",t.stage);out.addProperty("inside",t.inside.toShortString());out.addProperty("declaredDoor",t.door.toShortString());out.addProperty("enteredDeclaredDoor",t.enteredDoor);out.addProperty("exitedDeclaredDoor",t.exitedDoor);out.addProperty("geometryBlocker",t.blocker);out.addProperty("teleportsAfterSpawn",0);out.addProperty("blockEdits",0);out.addProperty("ordinaryNavigation",!t.guided);out.addProperty("guidedNativeMoveControl",t.guided);out.addProperty("spawnedDiagnosticActor",true);out.add("positions",t.positions);return out;}
 private static void finish(boolean pass,String reason){finished=report();finished.addProperty("finished",true);finished.addProperty("passed",pass);finished.addProperty("reason",reason);
  try{Files.writeString(SaveAcceptanceService.file("engineering91-entrance-probe",".jsonl"),new Gson().toJson(finished)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(java.io.IOException e){finished.addProperty("evidenceError",e.getClass().getSimpleName());}
  trial.actor.discard();trial=null;
 }
 public static String status(){return trial!=null?new Gson().toJson(report()):finished!=null?new Gson().toJson(finished):"NOT_STARTED";}
 public static void stop(){if(trial!=null)finish(false,"server stopped");}
 private Engineering91EntranceProbe(){}
}
