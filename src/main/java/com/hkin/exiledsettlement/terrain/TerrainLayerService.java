package com.hkin.exiledsettlement.terrain;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.citizen.WorkTaskService;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.*;
/** One real, physically ready layer across the children of a mother task. */
public final class TerrainLayerService {
 private record Group(UUID mother,boolean reclaim,long area) {}
 private record Layer(int phase,int y) implements Comparable<Layer>{public int compareTo(Layer b){int p=Integer.compare(phase,b.phase);return p!=0?p:Integer.compare(y,b.y);}}
 private static final Map<SettlementRecord,Snapshot> CACHE=new WeakHashMap<>();
 private record Ready(BlueprintRecord site,int index,Layer layer) {
  BlockPos at(){return site.terrainProject().steps.get(index).position();}
  boolean finished(){var p=site.terrainProject();return site.cancellationQueued() || p.cancelled || p.completed || index<p.cursor || p.finished.get(index);}
 }
 private record Snapshot(long tick,Map<Group,Layer> layers,Map<Group,BlockPos> targets,Map<Group,PriorityQueue<Ready>> ready){}
 private static Layer current(Snapshot view,Group group){
  var queue=view.ready.get(group);if(queue==null)return null;
  while(!queue.isEmpty() && queue.peek().finished())queue.remove();
  if(queue.isEmpty()){view.layers.remove(group);view.targets.remove(group);return null;}
  var first=queue.peek();view.layers.put(group,first.layer());view.targets.put(group,first.at());return first.layer();
 }
 private static boolean applies(TerrainProject p){return p!=null && !p.bridge && p.escapeFor==null;}
 private static Group group(SettlementRecord s,BlueprintRecord b,BlockPos at){
  var p=b.terrainProject();UUID mother=WorkTaskService.mother(s,b,at);
  if(mother.equals(b.id())){
   if(p.road)mother=s.autoRoads().routes.values().stream().filter(r->p.dependencies.contains(r.id))
           .map(r->r.id).sorted().findFirst().orElse(b.id());
   else if(p.publicGround)mother=s.id();
   else if(!p.dependencies.isEmpty())mother=p.dependencies.stream().sorted().findFirst().orElse(b.id());
  }
  // Bridge approaches must finish reachable columns up to the walking surface.
  // A shared bottom layer across the whole water gap strands the builder before
  // any column can become a dry extension of the current work front.
  boolean bridgeApproach=p.road && !p.roadReclaim && s.autoRoads().routes.values().stream()
          .anyMatch(r->p.dependencies.contains(r.id) && com.hkin.exiledsettlement.road.BridgeRoadConnections.bridge(s,r.building)!=null);
  return new Group(mother,p.roadReclaim,bridgeApproach?BlockPos.asLong(at.getX(),0,at.getZ()):PublicWorkAreas.applies(s,b)?PublicWorkAreas.area(at):Long.MIN_VALUE);
 }
 private static Layer layer(TerrainProject.Step step){return new Layer(step.fill()?1:0,step.fill()?step.position().getY():-step.position().getY());}
 private static Snapshot snapshot(ServerLevel l,SettlementRecord s){
  var old=CACHE.get(s);if(old!=null && l.getGameTime()-old.tick>=0 && l.getGameTime()-old.tick<20)return old;
  var layers=new HashMap<Group,Layer>();var targets=new HashMap<Group,BlockPos>();
  var ready=new HashMap<Group,PriorityQueue<Ready>>();
  for(var b:s.blueprints())if(!b.cancellationQueued() && applies(b.terrainProject()) && TerrainService.allowed(s,b))
   for(var work:TerrainService.pendingRaw(l,b,Set.of())){
    var step=b.terrainProject().steps.get(-work.index()-1);var g=group(s,b,step.position());var key=layer(step);
    ready.computeIfAbsent(g,ignored->new PriorityQueue<>(Comparator.comparing(Ready::layer)
            .thenComparingDouble(v->v.at().distSqr(s.corePosition())))).add(new Ready(b,-work.index()-1,key));
    var oldLayer=layers.get(g);var oldTarget=targets.get(g);
    if(oldLayer==null || key.compareTo(oldLayer)<0 || key.equals(oldLayer)
            && step.position().distSqr(s.corePosition())<oldTarget.distSqr(s.corePosition())){
      layers.put(g,key);targets.put(g,step.position());
    }
   }
  var next=new Snapshot(l.getGameTime(),layers,targets,ready);CACHE.put(s,next);return next;
 }
 public static boolean allowed(ServerLevel l,SettlementRecord s,BlueprintRecord b,TerrainProject.Step step){
  if(!applies(b.terrainProject()))return true;
  if(com.hkin.exiledsettlement.citizen.WorkerAccessService.accessFill(s,b,step))return true;
  var current=current(snapshot(l,s),group(s,b,step.position()));return current==null || layer(step).equals(current);
 }
 public static void invalidate(SettlementRecord s){CACHE.remove(s);}
 public static com.google.gson.JsonArray observation(ServerLevel l,SettlementRecord s){
  var view=snapshot(l,s);var out=new com.google.gson.JsonArray();
  for(var group:view.ready.keySet())current(view,group);
  for(var entry:view.layers.entrySet()){
   var j=new com.google.gson.JsonObject();j.addProperty("mother",entry.getKey().mother().toString());j.addProperty("area",entry.getKey().area());
   j.addProperty("fill",entry.getValue().phase()==1);j.addProperty("y",entry.getValue().phase()==1?entry.getValue().y():-entry.getValue().y());
   j.addProperty("target",view.targets.get(entry.getKey()).toShortString());out.add(j);
  }
  return out;
 }
}
