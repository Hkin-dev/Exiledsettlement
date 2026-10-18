package com.hkin.exiledsettlement.debug;
import com.google.gson.*;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
/** Read-only, test-harness diagnostics for saved bridge approaches. */
public final class BridgeEndpointInspection {
 public static JsonObject inspect(ServerLevel l,SettlementRecord s){
  var out=new JsonObject();out.add("planner",AutoRoadPlanner.progress(l,s));var list=new JsonArray();
  for(var b:s.terrain().projects.values())if(b.playerBridge!=null&&!b.cancelled){
   for(var end:BridgeRoadConnections.entrances(s,b.id))try(var scope=RoadEarthworkService.planningScope(s,true)){
    var j=new JsonObject();j.addProperty("bridge",b.id.toString());j.addProperty("completed",b.completed);j.addProperty("end",end.toShortString());
    j.addProperty("status",BridgeRoadConnections.portStatus(s,b.id,end));j.addProperty("root",String.valueOf(BridgeRoadConnections.root(l,s,b.id,end)));
    j.addProperty("plannable",RoadEarthworkService.plannable(l,s,end));
    var cells=new JsonArray();var axis=b.playerBridge.alongX?Direction.EAST:Direction.SOUTH;var lateral=axis.getClockWise();
    for(int d=-18;d<=18;d++)for(int side=-2;side<=2;side++)for(int dy=-5;dy<=1;dy++){
     var p=end.relative(axis,d).relative(lateral,side).above(dy);if(!BridgeRoadConnections.side(s,b.id,end,p)||!l.hasChunkAt(p))continue;
     var c=new JsonObject();c.addProperty("at",p.toShortString());c.addProperty("block",l.getBlockState(p).toString());
     c.addProperty("permitted",RoadEarthworkService.permitted(l,s,p));c.addProperty("plannable",RoadEarthworkService.plannable(l,s,p));
     c.addProperty("gap",BridgeRoadConnections.approachGap(l,s,p));c.addProperty("checkpoint",PublicGroundService.checkpointAt(s,p));c.addProperty("public",PublicGroundService.targetAt(s,p));
     c.addProperty("roadY",RoadSpaceData.get(l).columnHeight(p));c.addProperty("manualY",RoadSpaceData.get(l).manualHeight(p));c.addProperty("forbidden",s.decorations().forbidden(p));
     c.addProperty("suppressed",s.terrain().suppressed.contains(p.asLong()));cells.add(c);
    }j.add("cells",cells);list.add(j);
   }
  }out.add("ports",list);var plan=AutoRoadPlanner.saved(s.autoRoads());
  var flood=plan.getLongArray("Flood");if(flood.length>0){var at=BlockPos.of(flood[0]);out.addProperty("floodFirst",at.toShortString());var trees=new JsonArray();
   for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)for(int y=-1;y<=5;y++){var p=at.offset(x,y,z);if(!l.hasChunkAt(p)||!com.hkin.exiledsettlement.citizen.TreeSurvey.candidate(l.getBlockState(p)))continue;
    var j=new JsonObject();j.addProperty("at",p.toShortString());j.addProperty("block",l.getBlockState(p).toString());trees.add(j);
   }out.add("frontierWood",trees);
  }return out;
 }
}
