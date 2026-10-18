package com.hkin.exiledsettlement.road;

import com.hkin.exiledsettlement.blueprint.FarmEarthworkPlan;
import com.hkin.exiledsettlement.region.ProductionRegionService;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Restore known damaged road columns without solving a new village-wide grade.
 * This publishes normal resident work, never changes blocks or inventory. */
public final class RoadMaintenanceService {
    private static final Map<AutoRoadState.Route,Integer> CURSORS=new WeakHashMap<>();
    private RoadMaintenanceService(){}
    public static boolean repairAt(AutoRoadState.Route route,BlockPos at){
        int i=route.cellIndex(at);
        return !route.suppressed && !route.p0Invalidated && i>=0 && route.repairs.get(i);
    }
    public static boolean workTargetAllowed(ServerLevel level,SettlementRecord s,AutoRoadState.Route route,BlockPos at){
        return AutoRoadService.parentValid(level,s,route) || repairAt(route,at);
    }
    /** At most eight damaged columns per call; blocked columns never own the cursor. */
    public static int publish(ServerLevel level,SettlementRecord s,OptionalWorkBudget capacity,int budget){
        var routes=new ArrayList<>(s.autoRoads().routes.values());
        if(!routes.isEmpty())Collections.rotate(routes,-Math.floorMod(s.autoRoads().earthworkRouteCursor,routes.size()));
        int spent=0;
        for(var route:routes){
            if(!AutoRoadService.routeAllowed(s,route) || route.repairs.isEmpty())continue;
            int from=CURSORS.getOrDefault(route,0),i=route.repairs.nextSetBit(from);
            if(i<0 || i>=route.cells.size())i=route.repairs.nextSetBit(0);
            if(i<0 || i>=route.cells.size())continue;
            CURSORS.put(route,i+1);publishColumn(level,s,route,route.cells.get(i),capacity);
            if(++spent>=budget)break;
        }
        return spent;
    }
    public static boolean publishColumn(ServerLevel level,SettlementRecord s,AutoRoadState.Route route,BlockPos at,OptionalWorkBudget capacity){
        if(!AutoRoadService.allowed(s) || !AutoRoadService.routeAllowed(s,route) || !repairAt(route,at)
                || BridgeService.column(s,at) || !RoadEarthworkService.permitted(level,s,at))return false;
        var committed=PublicGroundService.targetAt(s,at);
        if(s.terrain().enabled && !s.terrain().publicPaused && committed!=null && committed!=at.getY())return false;
        // No new cut, reshaping, retaining wall, or adoption of a player's surface.
        var desired=RoadEarthworkService.surfaceTarget(level,s,route,at);
        if(desired==null)return false;
        var steps=new ArrayList<TerrainProject.Step>();
        for(int h=RoadEarthworkService.ROAD_CLEARANCE_HEIGHT;h>=1;h--){
            var p=at.above(h);if(!level.hasChunkAt(p))return false;
            var actual=level.getBlockState(p);if(actual.isAir())continue;
            if(!TerrainService.soft(actual) || !actual.getFluidState().isEmpty() || actual.hasBlockEntity())return false;
            steps.add(new TerrainProject.Step(p,Blocks.AIR.defaultBlockState(),actual));
        }
        int bottom=at.getY()-1;
        while(bottom>=level.getMinBuildHeight() && !FarmEarthworkPlan.support(level,at.atY(bottom))){
            var p=at.atY(bottom);
            if(!RoadEarthworkService.permitted(level,s,p) || !level.getBlockState(p).isAir())return false;
            bottom--;
        }
        if(bottom<level.getMinBuildHeight())return false;
        for(int y=bottom+1;y<=at.getY();y++){
            var p=at.atY(y);var actual=level.getBlockState(p);
            if(!actual.isAir()){
                if(y<at.getY() || RoadSurfaceProfile.usable(level,p))continue;
                return false;
            }
            BlockState target=paidTarget(s,p,y==at.getY()?desired:Blocks.DIRT.defaultBlockState());
            steps.add(new TerrainProject.Step(p,target,actual));
        }
        if(!ProductionRegionService.conflict(s,route.id,steps).isEmpty())return false;
        if(!capacity.fits(steps,at)){route.status=capacity.status();return false;}
        var project=ProductionRegionService.publish(level,s,route.id,"已建道路缺口维修",at,steps);
        if(project!=null){project.publicGround=true;project.road=true;project.groundSurfaces.add(at);s.terrain().index(project);}
        for(var step:steps){var owner=s.terrain().projects.get(s.terrain().cells.get(step.position()));
            if(owner!=null && !owner.cancelled){owner.dependencies.add(route.id);owner.road=true;}}
        capacity.reserve(steps,at);capacity.admitPaving(route,at);
        route.status="按原道路标高维修缺口；公共地面勘察继续";
        return true;
    }
    private static BlockState paidTarget(SettlementRecord s,BlockPos at,BlockState fallback){
        for(var project:s.terrain().projectsInColumn(at))if(!project.cancelled && !project.heightSuperseded && (project.road || project.publicGround))
            for(int i:project.indices(at))if(project.finished.get(i) && !project.abandoned.get(i) && project.steps.get(i).fill())return project.steps.get(i).target();
        return fallback;
    }
}
