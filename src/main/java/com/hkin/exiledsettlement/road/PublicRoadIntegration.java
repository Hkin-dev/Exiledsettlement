package com.hkin.exiledsettlement.road;

import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** A bounded road-grade pass inside the public-ground transaction. No world edits. */
public final class PublicRoadIntegration {
    private static final Map<SettlementRecord,Work> WORK=new WeakHashMap<>();
    private static final class Work {
        final AutoRoadState.Route source;
        final RoadGradePlanner planner;
        final BlockPos entrance;
        final Set<BlockPos> cells=new LinkedHashSet<>();
        final Map<Long,Integer> heights=new HashMap<>();
        int row;
        JointRoadGradePlanner joint;
        boolean jointDone;
        Work(AutoRoadState.Route route,BlockPos end){
            source=route;entrance=end;var line=new ArrayList<>(route.centerline);
            var last=line.getLast();
            if(last.getX()==end.getX() && last.getZ()==end.getZ())line.set(line.size()-1,end);
            else if(Math.abs(last.getX()-end.getX())+Math.abs(last.getZ()-end.getZ())==1)line.add(end);
            planner=new RoadGradePlanner(line);
        }
    }
    public static void reset(SettlementRecord s){WORK.remove(s);}
    /** Returns true when this route has been checked; a failed revision retains its safe old route. */
    public static boolean advance(ServerLevel level,SettlementRecord s,String key){
        return advance(level,s,key,System.nanoTime()+2_000_000L);
    }
    public static boolean advance(ServerLevel level,SettlementRecord s,String key,long deadline){
        var route=s.autoRoads().routes.get(key);
        if(route==null || route.p0Invalidated || AutoRoadService.fixedConstructionGrade(s,route) || route.manualBridge || route.suppressed || route.centerline.isEmpty() || !AutoRoadService.routeAllowed(s,route))return true;
        var work=WORK.get(s);
        if(work==null || work.source!=route){
            var end=route.entrance;var building=s.building(route.building);var site=s.blueprint(route.building);
            var endpoints=building!=null?AutoRoadService.entrances(level,s,building):site!=null?AutoRoadService.entrances(level,s,site):List.<BlockPos>of();
            if(!AutoRoadService.parentValid(level,s,route))end=endpoints.stream()
                    .filter(p->Math.abs(p.getX()-route.entrance.getX())+Math.abs(p.getZ()-route.entrance.getZ())<=1)
                    .min(Comparator.comparingDouble(p->p.distSqr(route.entrance))).orElse(end);
            work=new Work(route,end);WORK.put(s,work);
        }
        if(!work.jointDone){
            if(work.joint==null)work.joint=new JointRoadGradePlanner(s,key);
            if(!work.joint.advance(level,s,deadline))return false;
            work.jointDone=true;
            if(work.joint.committed()){WORK.remove(s);return true;}
        }
        if(!work.planner.done()){
            for(int i=0;i<4 && !work.planner.done();i++){
                work.planner.advance(level,s,deadline);if(System.nanoTime()>=deadline)break;
            }
            return false;
        }
        var line=work.planner.result();
        for(int i=1;i<line.size();i++)if(Math.abs(line.get(i).getY()-line.get(i-1).getY())>1){
            route.reviewAfter=0;route.status="门前高差需要安全改线，保留原路等待复查";WORK.remove(s);return true;}
        for(int i=0;i<8 && work.row<line.size();i++){
            var cells=AutoRoadService.corridorCell(level,s,line,work.row++);
            if(cells==null || cells.stream().anyMatch(p->!level.hasChunkAt(p))){route.status="公共通路联合规划：局部条件未满足，保留实际通路";WORK.remove(s);return true;}
            for(var p:cells){var old=work.heights.putIfAbsent(BlockPos.asLong(p.getX(),0,p.getZ()),p.getY());
                if(old!=null && old!=p.getY()){route.status="公共通路联合规划：转角高程冲突，等待改线";WORK.remove(s);return true;}}
            work.cells.addAll(cells);
            if(System.nanoTime()>=deadline)break;
        }
        if(work.row<line.size())return false;
        var candidate=new AutoRoadState.Route(route.building,work.entrance,List.copyOf(work.cells));
        candidate.centerline=line;candidate.width=3;candidate.areaOptimized=true;candidate.sourceBridges.addAll(route.sourceBridges);
        AdaptiveRoadService.accept(level,s,key,candidate);WORK.remove(s);return true;
    }
    private PublicRoadIntegration(){ }
}
