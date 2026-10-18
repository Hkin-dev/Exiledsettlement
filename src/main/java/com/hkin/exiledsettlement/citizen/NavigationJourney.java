package com.hkin.exiledsettlement.citizen;

import net.minecraft.core.BlockPos;
import java.util.*;

/** Runtime journey history survives intermediate waypoints and repeated commands. */
public final class NavigationJourney {
    private final Map<BlockPos,Attempt> attempts=new LinkedHashMap<>(16,.75f,true);
    private Attempt active;
    private BlockPos target;
    private static final class Attempt {
        int failures,detourStage;
        long retryAt,progressAt,lastSeen,bestAt;
        double best=Double.POSITIVE_INFINITY;
        BlockPos last;
        boolean loop;
        final Set<BlockPos> visited=new LinkedHashSet<>();
        final Map<Edge,Integer> edges=new LinkedHashMap<>();
        final Map<BlockPos,Long> rejected=new LinkedHashMap<>();
    }
    private record Edge(BlockPos from,BlockPos to) { }
    public void begin(BlockPos destination,BlockPos feet,double distance,long now){
        attempts.entrySet().removeIf(e->now-e.getValue().lastSeen>2400);
        target=destination.immutable();active=attempts.computeIfAbsent(target,k->{var a=new Attempt();a.progressAt=now;a.bestAt=now;return a;});
        active.detourStage=Math.max(active.detourStage,(int)Math.min(3,Math.max(0,now-active.bestAt)/200));
        active.lastSeen=now;active.rejected.entrySet().removeIf(e->e.getValue()<=now);
        if(active.last==null){active.last=feet.immutable();active.visited.add(active.last);active.best=distance;}
        while(attempts.size()>16)attempts.remove(attempts.keySet().iterator().next());
    }
    /** New physical cells, not new Path objects or node indices, prove detour progress. */
    public boolean observe(BlockPos feet,double distance,long now){
        if(active==null)return false;
        active.lastSeen=now;boolean progress=false;
        if(!feet.equals(active.last)){
            var at=feet.immutable();var edge=new Edge(active.last,at);active.last=at;
            if(active.edges.merge(edge,1,Integer::sum)>=3)active.loop=true;
            progress=active.visited.add(at);
            if(active.edges.size()>2048)active.edges.remove(active.edges.keySet().iterator().next());
            if(active.visited.size()>2048)active.visited.remove(active.visited.iterator().next());
        }
        if(distance<active.best-.25){active.best=distance;active.bestAt=now;progress=true;}
        if(progress && !active.loop)active.progressAt=now;
        return progress && !active.loop;
    }
    public boolean waiting(long now){return active!=null && now<active.retryAt;}
    public long progressAt(){return active==null?0:active.progressAt;}
    public int failures(){return active==null?0:active.failures;}
    public boolean looping(){return active!=null && active.loop;}
    public int allowance(){return switch(Math.min(3,Math.max(failures(),active==null?0:active.detourStage))){case 0->0;case 1->12;case 2->32;default->64;};}
    public boolean accepts(BlockPos candidate,BlockPos origin,BlockPos destination,long now){
        if(active!=null && active.rejected.getOrDefault(candidate,0L)>now)return false;
        if(active!=null && !candidate.equals(destination) && active.visited.contains(candidate))return false;
        double limit=Math.sqrt(origin.distSqr(destination))+allowance();
        if(active!=null)limit=Math.min(limit,active.best+allowance()+2);
        return Math.sqrt(candidate.distSqr(destination))<=limit;
    }
    public void fail(long now,BlockPos waypoint){
        if(active==null)return;
        active.failures=Math.min(8,active.failures+1);
        active.retryAt=now+(20L<<Math.min(2,active.failures-1));active.progressAt=active.retryAt;
        if(waypoint!=null && !waypoint.equals(target))active.rejected.put(waypoint.immutable(),now+400);
        active.edges.clear();active.loop=false;
    }
    public void arrived(long now){
        if(active==null)return;
        active.failures=0;active.detourStage=0;active.bestAt=now;active.retryAt=0;active.progressAt=now;active.loop=false;active.edges.clear();active.rejected.clear();
    }
    public void geometryChanged(long now){if(active!=null)active.retryAt=Math.min(active.retryAt,now);}
    /** Vanilla ends within its arrival tolerance; remember the requested endpoint too. */
    public void completedWaypoint(BlockPos waypoint){if(active!=null && waypoint!=null && !waypoint.equals(target))active.visited.add(waypoint.immutable());}
    public String diagnostic(long now){return "failures="+failures()+",detour="+allowance()+",retry="+(active==null?0:Math.max(0,active.retryAt-now))+",loop="+looping();}
}
