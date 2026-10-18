package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Explicit shared-grade regression inputs, never autonomous construction credit. */
public final class SharedRoad119Checks {
    private static void check(boolean value,String name,List<String> checks){if(!value)throw new IllegalStateException(name);checks.add(name);}
    public static String run(ServerLevel level){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(60600,250,60600);var data=SettlementSavedData.get(level);
        if(data.findByCore(core).isPresent())throw new IllegalStateException("FIXTURE_EXISTS");
        var s=data.createOrRecover(core,UUID.randomUUID(),"Shared automatic surface inputs").value();
        s.autoRoads().enabled=true;s.terrain().enabled=false;
        var saved=new LinkedHashMap<BlockPos,BlockState>();var checks=new ArrayList<String>();
        var roads=RoadSpaceData.get(level);var at=core.east(6).below();
        try{
            for(var raw:BlockPos.betweenClosed(core.offset(3,-3,-3),core.offset(9,3,3))){
                var p=raw.immutable();if(!level.getBlockState(p).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");saved.put(p,level.getBlockState(p));
            }
            for(int x=3;x<=9;x++)for(int z=-3;z<=3;z++)for(int y=-3;y<=-1;y++)level.setBlock(core.offset(x,y,z),Blocks.DIRT.defaultBlockState(),2);
            var slope=new AutoRoadState.Route(UUID.randomUUID(),at.south().below(),List.of(at.north(),at,at.south().below()));
            var flat=new AutoRoadState.Route(UUID.randomUUID(),at.east(),List.of(at.west(),at,at.east()));
            s.autoRoads().routes.put("slope",slope);s.autoRoads().routes.put("flat",flat);
            roads.addAll(List.of(at));
            var paid=new TerrainProject(UUID.randomUUID(),"paid common dirt",at,List.of(new TerrainProject.Step(at,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));
            paid.road=true;paid.publicGround=true;paid.cursor=1;paid.completed=true;paid.groundSurfaces.add(at);paid.accessSurfaces.add(at);s.terrain().index(paid);
            check(AdaptiveRoadService.paidPassage(level,s,at),"actual paid shared dirt is recognised",checks);
            check(AdaptiveRoadService.editableSurface(level,s,at),"same-grade common automatic surface permits real repair",checks);
            var desired=RoadEarthworkService.surfaceTarget(level,s,slope,at);
            check(desired!=null && desired.getBlock() instanceof StairBlock && desired.getValue(StairBlock.FACING)==Direction.NORTH,"shared downhill connection requests its actual high-side stair",checks);
            check(desired.equals(RoadEarthworkService.surfaceTarget(level,s,flat,at)),"flat claimant and slope claimant share one physical stair target",checks);
            check(RoadSurfaceProfile.joins(level,at,desired,at.north(),Blocks.DIRT.defaultBlockState(),Direction.NORTH)
                    && RoadSurfaceProfile.joins(level,at,desired,at.south().below(),Blocks.DIRT.defaultBlockState(),Direction.SOUTH),"common stair actually joins both landing heights",checks);
            check(level.getBlockState(at).is(Blocks.DIRT) && !RoadEarthworkService.surfaceReady(level,s,slope,at),"planning does not replace dirt or pretend the slope is already walkable",checks);
            check(PublicGroundService.publishRoadColumn(level,s,slope,at),"common surface publishes a real paid terrain order",checks);
            check(TerrainService.pendingAt(s,at) && level.getBlockState(at).is(Blocks.DIRT),"publication retains physical dirt and unfinished replacement",checks);
            var hole=core.east(9).below();var holeRoute=new AutoRoadState.Route(UUID.randomUUID(),hole,List.of(hole));s.autoRoads().routes.put("hole",holeRoute);
            var stale=new TerrainProject(UUID.randomUUID(),"completed obsolete clearance",hole,List.of(new TerrainProject.Step(hole,Blocks.AIR.defaultBlockState(),Blocks.DIRT.defaultBlockState())));
            stale.road=true;stale.cursor=1;stale.completed=true;stale.accessSurfaces.add(hole);s.terrain().index(stale);level.setBlock(hole,Blocks.AIR.defaultBlockState(),2);
            check(!AdaptiveRoadService.paidPassage(level,s,hole),"empty completed clearance is never a paid walking surface",checks);
            check(RoadEarthworkService.surfaceTarget(level,s,holeRoute,hole)!=null && RoadEarthworkService.plannable(level,s,hole),"missing automatic road remains eligible for actual fill rather than freezing an air goal",checks);
            return "PASS "+checks.size()+": "+String.join("; ",checks)+"; explicit inputs restored; real autonomous repair separate";
        }finally{for(var p:saved.keySet())roads.remove(p);saved.forEach((p,state)->level.setBlock(p,state,2));s.deactivate();data.touch();}
    }
    private SharedRoad119Checks(){}
}
