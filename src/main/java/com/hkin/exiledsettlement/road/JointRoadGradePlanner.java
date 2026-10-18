package com.hkin.exiledsettlement.road;

import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Stages a grade-only revision and all of its shared claimants before committing any route.
 * Uncommitted scratch work can be discarded/restarted after load; world and paid orders stay live. */
final class JointRoadGradePlanner {
    private record Scope(SettlementRecord settlement,Set<Long> columns) {}
    private static final ThreadLocal<Scope> ACTIVE=new ThreadLocal<>();
    static boolean adjustable(SettlementRecord s,BlockPos p){
        var scope=ACTIVE.get();return scope!=null && scope.settlement()==s && scope.columns().contains(column(p));
    }
    private static long column(BlockPos p){return BlockPos.asLong(p.getX(),0,p.getZ());}
    private record Source(AutoRoadState.Route route,List<BlockPos> cells,List<BlockPos> line) {}
    private final String seedKey;
    private final Map<String,Source> sources=new LinkedHashMap<>();
    private final Set<Long> allowed=new HashSet<>();
    private final Map<Long,Integer> proposed=new HashMap<>();
    private final Map<String,AutoRoadState.Route> candidates=new LinkedHashMap<>();
    private final Set<BlockPos> expanded=new LinkedHashSet<>();
    private RoadGradePlanner planner;
    private List<BlockPos> line;
    private List<String> members;
    private int phase,row,member;
    private boolean done,committed;
    JointRoadGradePlanner(SettlementRecord s,String key){
        seedKey=key;var seed=s.autoRoads().routes.get(key);
        if(seed==null || !eligible(s,seed) || seed.centerline.size()<3
                || AdaptiveRoadService.verticalTravel(seed.centerline)<=Math.abs(seed.centerline.getFirst().getY()-seed.centerline.getLast().getY())
                || seed.cells.stream().noneMatch(p->AdaptiveRoadService.sharedAt(s,p))){done=true;return;}
        for(var p:seed.cells)allowed.add(column(p));
        for(var e:s.autoRoads().routes.entrySet())if(!e.getValue().suppressed){
            var r=e.getValue();sources.put(e.getKey(),new Source(r,List.copyOf(r.cells),List.copyOf(r.centerline)));
            if(!eligible(s,r))for(var p:r.cells)allowed.remove(column(p));
            // A shared road's junction/entrance is an endpoint of another consumer.
            // It stays fixed; changing it would require a network topology revision.
            if(!r.centerline.isEmpty()){
                allowed.remove(column(r.centerline.getFirst()));allowed.remove(column(r.centerline.getLast()));
            }
        }
        planner=new RoadGradePlanner(seed.centerline);
    }
    private static boolean eligible(SettlementRecord s,AutoRoadState.Route r){
        return !r.suppressed && !r.p0Invalidated && !r.manualBridge && r.width==3
                && !AutoRoadService.fixedConstructionGrade(s,r) && AutoRoadService.routeAllowed(s,r);
    }
    boolean committed(){return committed;}
    boolean advance(ServerLevel level,SettlementRecord s,long deadline){
        if(done)return true;
        var previous=ACTIVE.get();ACTIVE.set(new Scope(s,allowed));
        try(var scope=RoadEarthworkService.planningScope(s,true)){
            for(int slice=0;slice<8 && !done;slice++){
                step(level,s,deadline);
                if(System.nanoTime()>=deadline)break;
            }
        }finally{if(previous==null)ACTIVE.remove();else ACTIVE.set(previous);}
        return done;
    }
    private void fail(){done=true;}
    private void step(ServerLevel level,SettlementRecord s,long deadline){
        var seed=sources.get(seedKey);
        if(phase==0){
            planner.advance(level,s,deadline);if(!planner.done())return;
            line=planner.result();
            if(AdaptiveRoadService.verticalTravel(line)>=AdaptiveRoadService.verticalTravel(seed.line())){fail();return;}
            phase=1;
        }
        if(phase==1){
            var cells=AutoRoadService.corridorCell(level,s,line,row++);
            if(cells==null){fail();return;}
            for(var p:cells){
                var old=seed.route().cellInColumn(p);
                if(old==null || old.getY()!=p.getY() && !allowed.contains(column(p))){fail();return;}
                var y=proposed.putIfAbsent(column(p),p.getY());if(y!=null && y!=p.getY()){fail();return;}
                expanded.add(p);
            }
            if(row<line.size())return;
            if(expanded.size()!=seed.cells().size()){fail();return;}
            proposed.entrySet().removeIf(e->seed.route().cellInColumn(BlockPos.of(e.getKey())).getY()==e.getValue());
            if(proposed.isEmpty()){fail();return;}
            for(var e:sources.entrySet()){
                var source=e.getValue();if(source.cells().stream().noneMatch(p->proposed.containsKey(column(p))))continue;
                var candidate=new AutoRoadState.Route(source.route().building,source.route().entrance,
                        source.cells().stream().map(this::remap).toList());
                candidate.centerline=source.line().stream().map(this::remap).toList();
                candidate.width=3;candidate.areaOptimized=true;candidate.sourceBridges.addAll(source.route().sourceBridges);
                candidates.put(e.getKey(),candidate);
            }
            members=List.copyOf(candidates.keySet());phase=2;row=0;expanded.clear();return;
        }
        if(phase==2){
            var key=members.get(member);var candidate=candidates.get(key);var source=sources.get(key);
            if(!geometry(source,candidate) || !eligible(s,source.route())){fail();return;}
            var cells=AutoRoadService.corridorCell(level,s,candidate.centerline,row++);
            if(cells==null || cells.stream().anyMatch(p->!candidate.containsCell(p))){fail();return;}
            expanded.addAll(cells);
            if(row<candidate.centerline.size())return;
            if(expanded.size()!=candidate.cells.size()){fail();return;}
            row=0;expanded.clear();if(++member<members.size())return;
            phase=3;return;
        }
        // Atomic final guard: no tick, save or resident can observe half a group.
        // Check current blocks/protection again, since earlier rows were sliced.
        for(var e:sources.entrySet()){
            var current=s.autoRoads().routes.get(e.getKey());var source=e.getValue();
            if(current!=source.route() || !current.cells.equals(source.cells()) || !current.centerline.equals(source.line())){fail();return;}
        }
        if(s.autoRoads().routes.values().stream().filter(r->!r.suppressed).count()!=sources.size()){fail();return;}
        var agreed=new HashMap<Long,Integer>();
        for(var e:sources.entrySet())for(var p:candidates.getOrDefault(e.getKey(),e.getValue().route()).cells){
            var y=agreed.putIfAbsent(column(p),p.getY());if(y!=null && y!=p.getY()){fail();return;}
        }
        for(var key:members){var candidate=candidates.get(key);var source=sources.get(key);
            if(!eligible(s,source.route()) || candidate.cells.stream().anyMatch(p->!level.hasChunkAt(p) || !RoadEarthworkService.plannable(level,s,p))
                    || s.terrain().projects.values().stream().anyMatch(p->!p.cancelled && !p.done() && p.roadReclaim && p.dependencies.contains(source.route().id))){fail();return;}
        }
        AdaptiveRoadService.installCandidates(level,s,candidates);
        committed=true;done=true;
    }
    private BlockPos remap(BlockPos p){return p.atY(proposed.getOrDefault(column(p),p.getY()));}
    private static boolean geometry(Source source,AutoRoadState.Route candidate){
        var line=candidate.centerline;
        if(line.isEmpty() || !line.getFirst().equals(source.line().getFirst()) || !line.getLast().equals(source.line().getLast())
                || AdaptiveRoadService.verticalTravel(line)>AdaptiveRoadService.verticalTravel(source.line()))return false;
        for(int i=1;i<line.size();i++){
            var a=line.get(i-1);var b=line.get(i);
            if(Math.abs(a.getY()-b.getY())>1)return false;
            if(i<line.size()-1){var c=line.get(i+1);
                boolean corner=(a.getX()!=b.getX() || c.getX()!=b.getX()) && (a.getZ()!=b.getZ() || c.getZ()!=b.getZ());
                if(corner && (a.getY()!=b.getY() || c.getY()!=b.getY()))return false;
            }
        }
        return true;
    }
}
