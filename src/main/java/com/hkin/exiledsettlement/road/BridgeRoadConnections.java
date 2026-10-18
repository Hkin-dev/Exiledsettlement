package com.hkin.exiledsettlement.road;

import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.terrain.TerrainProject;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Roads consume the two saved bridge exits; they never own or gate bridge construction. */
public final class BridgeRoadConnections {
    private BridgeRoadConnections() {}
    public static TerrainProject bridge(SettlementRecord s,UUID id){
        var p=s.terrain().projects.get(id);
        return p!=null && p.playerBridge!=null && !p.cancelled?p:null;
    }
    public static List<BlockPos> entrances(SettlementRecord s,UUID id){
        var p=bridge(s,id);if(p==null)return List.of();var b=p.playerBridge;int side=b.sideMin+b.width/2;
        return List.of(b.at(b.minRow()-1,side,(b.buildingModel?b.surface:b.heightAt(b.minRow()))-1),
                b.at(b.maxRow()+1,side,(b.buildingModel?b.surface:b.heightAt(b.maxRow()))-1));
    }
    public static BlockPos deck(SettlementRecord s,UUID id,BlockPos entrance){
        var p=bridge(s,id);if(p==null)return null;var exits=entrances(s,id);int i=exits.indexOf(entrance);if(i<0)return null;
        var direction=p.playerBridge.alongX?Direction.EAST:Direction.SOUTH;
        return entrance.relative(i==0?direction:direction.getOpposite());
    }
    /** Retry ownership is per port, so a blocked bank never stalls the other bank. */
    public static UUID retryKey(SettlementRecord s,UUID id,BlockPos end){
        return bridge(s,id)==null?id:UUID.nameUUIDFromBytes((id+":bridge-port:"+end.asLong()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    /** A road consumes the entire walking row, including wide bridges. */
    public static List<BlockPos> deckRow(SettlementRecord s,BlockPos at){
        var p=BridgeService.owner(s,at);if(p==null || p.playerBridge==null || !p.accessSurfaces.contains(at))return List.of();
        var b=p.playerBridge;var result=new ArrayList<BlockPos>();
        for(int side=b.sideMin;side<b.sideMin+b.width;side++){
            var cell=b.at(b.longitudinal(at),side,at.getY());if(p.accessSurfaces.contains(cell))result.add(cell);
        }
        return result;
    }
    /** Only the axial gap between a confirmed bridge end and its bank may cross water.
     * Existing route cells retain their authorization while residents build the approach. */
    public static boolean approachGap(ServerLevel level,SettlementRecord s,BlockPos at){
        for(var project:s.terrain().playerBridges()){
            var b=project.playerBridge;int lateral=b.alongX?at.getZ():at.getX();
            // Landing search includes the selected width and one three-wide turn.
            // This is permission to compare routes, never a rectangular fill target.
            int center=b.sideMin+b.width/2;
            if(lateral<Math.min(b.sideMin,center-1)-3||lateral>Math.max(b.sideMin+b.width-1,center+1)+3)continue;
            for(var end:entrances(s,project.id)){
                if(!side(s,project.id,end,at))continue;
                var port=deck(s,project.id,end);var outward=Direction.fromDelta(end.getX()-port.getX(),0,end.getZ()-port.getZ());
                int distance=Math.abs(b.longitudinal(at)-b.longitudinal(port));
                if(distance<1 || distance>64)continue;
                if(Math.abs(at.getY()-end.getY())>distance/2)continue;
                if(s.autoRoads().routes.values().stream().anyMatch(r->r.building.equals(project.id)&&r.entrance.equals(end)
                        &&!r.suppressed&&!r.p0Invalidated&&r.containsCell(at)))return true;
                boolean gap=true;
                for(int row=1;row<=distance;row++){
                    // Shorelines are irregular: each lateral lane ends at its own
                    // dry bank, not at an unrelated dry cell on the centre line.
                    var column=b.at(b.longitudinal(port)+(whichOutward(outward)*row),lateral,port.getY());
                    if(!level.hasChunkAt(column)||!s.contains(column)){gap=false;break;}
                    var top=column.atY(level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,column.getX(),column.getZ())-1);
                    if(!com.hkin.exiledsettlement.citizen.TreeSurvey.candidate(level.getBlockState(top))
                            && AdaptiveRoadService.walkSurface(level,top)){gap=false;break;}
                }
                if(gap)return true;
            }
        }
        return false;
    }
    private static int whichOutward(Direction direction){return direction==Direction.EAST||direction==Direction.SOUTH?1:-1;}
    public static boolean plan(ServerLevel level,SettlementRecord s){
        for(var p:s.terrain().playerBridges()){
            for(var end:entrances(s,p.id)){
                String key=p.id+":"+end.asLong();
                if(s.autoRoads().routes.containsKey(key))continue; // Explicit cancellation remains authoritative.
                var retry=retryKey(s,p.id,end);
                if(level.getGameTime()<s.autoRoads().accessRetry.getOrDefault(retry,0L))continue;
                try(var scope=RoadEarthworkService.planningScope(s,true)){
                    if(!level.hasChunkAt(end) || !RoadEarthworkService.plannable(level,s,end)){
                        s.autoRoads().accessFailure.put(retry,"桥端保护、地形或加载条件尚未满足");continue;
                    }
                    var root=root(level,s,p.id,end);if(root==null){s.autoRoads().accessFailure.put(retry,"桥外暂未找到可接通的陆地或路网");continue;}
                    AutoRoadPlanner.begin(level,s,p.id,root,end,key);
                    s.autoRoads().status="正在规划桥梁端部接路："+end.toShortString();return true;
                }
            }
        }
        return false;
    }
    public static String portStatus(SettlementRecord s,UUID id,BlockPos end){
        var route=s.autoRoads().routes.values().stream().filter(r->r.building.equals(id)&&r.entrance.equals(end)).findFirst().orElse(null);
        if(route!=null)return route.suppressed?"已撤销":route.accessConnected?"已接通":route.status;
        return s.autoRoads().accessFailure.getOrDefault(retryKey(s,id,end),"等待规划");
    }
    /** Each approach stays on its own exterior side; it cannot loop under or around its bridge. */
    public static boolean side(SettlementRecord s,UUID id,BlockPos end,BlockPos at){
        var p=bridge(s,id);if(p==null)return true;int which=entrances(s,id).indexOf(end);if(which<0)return true;
        return which==0?p.playerBridge.longitudinal(at)<=p.playerBridge.longitudinal(end)
                :p.playerBridge.longitudinal(at)>=p.playerBridge.longitudinal(end);
    }
    public static BlockPos root(ServerLevel level,SettlementRecord s,UUID id,BlockPos end){
        try(var scope=RoadEarthworkService.planningScope(s,true)){return findRoot(level,s,id,end);}
    }
    private static BlockPos findRoot(ServerLevel level,SettlementRecord s,UUID id,BlockPos end){
        // Join the local bank's existing network before asking for a new route
        // all the way back to the settlement core across unrelated obstacles.
        var nearby=s.autoRoads().routes.values().stream().filter(r->!r.suppressed && !r.p0Invalidated && !r.building.equals(id))
                .flatMap(r->r.cells.stream()).distinct()
                .filter(p->side(s,id,end,p)&&p.distManhattan(end)>=3&&level.hasChunkAt(p)
                        &&RoadSpaceData.get(level).contains(p)&&AutoRoadService.safeCell(level,s,p)&&RoadEarthworkService.plannable(level,s,p))
                .min(Comparator.comparingDouble(p->p.distSqr(end))).orElse(null);
        if(nearby!=null)return nearby;
        var local=new ArrayList<BlockPos>();
        for(var b:s.buildings())if(AutoRoadService.roadEligible(b))local.addAll(AutoRoadService.entrances(level,s,b));
        var known=local.stream().filter(p->side(s,id,end,p)&&p.distManhattan(end)>=3)
                .min(Comparator.comparingDouble(p->p.distSqr(end))).orElse(null);
        if(known!=null)return known;
        var core=AutoRoadService.coreSurface(level,s);if(core!=null && side(s,id,end,core))return core;
        var port=deck(s,id,end);if(port==null)return null;
        var outward=Direction.fromDelta(end.getX()-port.getX(),0,end.getZ()-port.getZ());
        for(int distance=4;distance<=64;distance++){
            var column=end.relative(outward,distance);if(!level.hasChunkAt(column)||!s.contains(column))break;
            var ground=column.atY(level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,column.getX(),column.getZ())-1);
            if(distance<2*Math.abs(ground.getY()-end.getY()))continue;
            if(AdaptiveRoadService.walkSurface(level,ground)&&RoadEarthworkService.plannable(level,s,ground))return ground;
        }
        return null;
    }
    public static String blocker(ServerLevel level,SettlementRecord s,AutoRoadState.Route route){
        var p=bridge(s,route.building);var port=deck(s,route.building,route.entrance);
        if(p==null || port==null)return "桥梁端点不存在";
        if(!p.completed || !BridgeService.ready(level,s,port))return "等待桥面真实完工";
        if(!route.containsCell(route.entrance) || !level.hasChunkAt(route.entrance)
                || !RoadSurfaceProfile.usable(level,route.entrance))return "桥端道路尚未完成";
        var direction=Direction.fromDelta(port.getX()-route.entrance.getX(),0,port.getZ()-route.entrance.getZ());
        return RoadSurfaceProfile.joins(level,route.entrance,level.getBlockState(route.entrance),port,level.getBlockState(port),direction)
                ?"":"桥端道路与桥面高差未接通";
    }
}
