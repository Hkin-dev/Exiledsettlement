package com.hkin.exiledsettlement.debug;

import com.google.gson.*;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import java.nio.file.*;
import java.util.*;

/** A spawned test resident walks existing paid roads using ordinary navigation. No block edits. */
public final class Engineering91RoadWalk {
    private static Trial trial;
    private static JsonObject finished;
    private static final class Trial {
        ServerLevel level;SettlementRecord settlement;AutoRoadState.Route route;SettlementCitizenEntity resident;
        List<BlockPos> line,cells,waypoints;int waypoint,outside,falls;long start;
        final BitSet forward=new BitSet(),backward=new BitSet();final JsonArray positions=new JsonArray();
        final JsonArray outsidePositions=new JsonArray();
    }
    public static String start(ServerLevel l,String id){
        if(!SaveAcceptanceService.enabled() || !l.getServer().isDedicatedServer() || l.getServer().getPlayerCount()!=0)
            throw new IllegalStateException("EMPTY_TEST_SERVER_REQUIRED");
        if(trial!=null)throw new IllegalStateException("WALK_ALREADY_RUNNING");
        // Explicit route IDs also support fresh, isolated grading fixtures;
        // never require loading the historical engineering91 scenario.
        var s=SettlementSavedData.get(l).settlements().stream()
                .filter(candidate->candidate.autoRoads().routes.values().stream().anyMatch(r->r.id.toString().equals(id) && !r.suppressed))
                .findFirst().orElseThrow();
        var r=s.autoRoads().routes.values().stream().filter(p->p.id.toString().equals(id) && !p.suppressed).findFirst().orElseThrow();
        if(r.centerline.size()<2 || r.progress!=r.cells.size() || !RoadEarthworkService.accessBlocker(l,s,r).isEmpty()
                || r.cells.stream().anyMatch(p->!RoadEarthworkService.surfaceReady(l,s,r,p)))
            throw new IllegalStateException("PHYSICALLY_COMPLETED_ROAD_REQUIRED");
        var e=ModEntities.SETTLEMENT_CITIZEN.get().create(l);if(e==null)throw new IllegalStateException("NO_RESIDENT");
        var first=r.centerline.getFirst();double y=first.getY()+l.getBlockState(first).getCollisionShape(l,first).max(net.minecraft.core.Direction.Axis.Y);
        e.moveTo(first.getX()+.5,y,first.getZ()+.5,0,0);e.setOnGround(true);e.setPersistenceRequired();
        if(!l.noCollision(e,e.getBoundingBox()))throw new IllegalStateException("SPAWN_FOOTING_BLOCKED");
        l.addFreshEntity(e);
        var t=new Trial();t.level=l;t.settlement=s;t.route=r;t.resident=e;t.line=List.copyOf(r.centerline);t.cells=List.copyOf(r.cells);t.start=l.getGameTime();
        var points=new ArrayList<BlockPos>();
        for(int i=4;i<t.line.size()-1;i+=4)points.add(t.line.get(i).above());points.add(t.line.getLast().above());
        for(int i=t.line.size()-5;i>0;i-=4)points.add(t.line.get(i).above());points.add(first.above());
        t.waypoints=List.copyOf(points);trial=t;finished=null;
        return "STARTED real resident round trip; spawn is fixture input, no later teleport, block edit, inventory or work credit";
    }
    public static void tick(MinecraftServer server){
        var t=trial;if(t==null)return;var l=t.level;long now=l.getGameTime();l.resetEmptyTime();
        var feet=t.resident.navigationFeetPosition();
        var actual=t.cells.stream().filter(p->p.getX()==feet.getX() && p.getZ()==feet.getZ()).findFirst().orElse(null);
        if(actual==null)t.outside++;else if(Math.abs(t.resident.getY()-(actual.getY()+1))>1)t.falls++;
        if(actual==null && t.outsidePositions.size()<64){var observed=new JsonObject();
            observed.addProperty("tick",now-t.start);observed.addProperty("x",t.resident.getX());observed.addProperty("y",t.resident.getY());observed.addProperty("z",t.resident.getZ());
            observed.addProperty("waypoint",t.waypoint);var path=t.resident.getNavigation().getPath();
            if(path!=null && !path.isDone())observed.addProperty("nextNode",path.getNextNodePos().toShortString());
            observed.add("navigation",((com.hkin.exiledsettlement.citizen.CitizenGroundNavigation)t.resident.getNavigation()).roadProbeDiagnostic());
            t.outsidePositions.add(observed);}
        boolean returning=t.waypoint>0 && t.waypoints.subList(0,t.waypoint).contains(t.line.getLast().above());
        if(actual!=null && Math.abs(t.resident.getY()-(actual.getY()+1))<=1)for(int i=0;i<t.line.size();i++){
            var p=t.line.get(i);if(Math.max(Math.abs(p.getX()-feet.getX()),Math.abs(p.getZ()-feet.getZ()))<=1
                    && Math.abs(t.resident.getY()-(p.getY()+1))<=1)(returning?t.backward:t.forward).set(i);
        }
        if(now%10==0){var pos=new JsonArray();pos.add(now-t.start);pos.add(t.resident.getX());pos.add(t.resident.getY());pos.add(t.resident.getZ());t.positions.add(pos);}
        if(!t.resident.isAlive() || now-t.start>=4000){finish(false,"navigation timeout or death");return;}
        if(t.waypoint>=t.waypoints.size()){
            finish(t.forward.cardinality()==t.line.size() && t.backward.cardinality()==t.line.size() && t.outside==0 && t.falls==0,"round trip finished");return;
        }
        var target=t.waypoints.get(t.waypoint);
        if(t.resident.distanceToSqr(target.getX()+.5,target.getY(),target.getZ()+.5)<.81){t.waypoint++;return;}
        if(now%5==0)t.resident.navigateTo(target,.7);
    }
    private static JsonObject report(){
        var t=trial;var out=new JsonObject();out.addProperty("route",t.route.id.toString());out.addProperty("elapsed",t.level.getGameTime()-t.start);
        out.addProperty("centerlineSections",t.line.size());out.addProperty("forwardSections",t.forward.cardinality());out.addProperty("backwardSections",t.backward.cardinality());
        out.addProperty("outsideCorridorTicks",t.outside);out.addProperty("fallTicks",t.falls);out.addProperty("teleportsAfterSpawn",0);
        out.addProperty("blockEdits",0);out.addProperty("spawnedTestResident",true);out.addProperty("ordinaryNavigation",true);out.add("positions",t.positions);out.add("outsidePositions",t.outsidePositions);return out;
    }
    private static void finish(boolean pass,String reason){
        finished=report();finished.addProperty("finished",true);finished.addProperty("passed",pass);finished.addProperty("reason",reason);
        try{Files.writeString(SaveAcceptanceService.file("engineering91-road-walk",".jsonl"),new Gson().toJson(finished)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
        catch(java.io.IOException ex){finished.addProperty("evidenceError",ex.getClass().getSimpleName());}
        trial.resident.discard();trial=null;
    }
    public static String status(){return trial!=null?new Gson().toJson(report()):finished!=null?new Gson().toJson(finished):"NOT_STARTED";}
    public static void stop(){if(trial!=null)finish(false,"server stopped");}
    private Engineering91RoadWalk(){}
}
