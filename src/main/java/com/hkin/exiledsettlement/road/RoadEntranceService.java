package com.hkin.exiledsettlement.road;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Blocks;
import com.hkin.exiledsettlement.terrain.*;
import com.hkin.exiledsettlement.region.ProductionRegionService;
import java.util.*;

/** Planned doorway elevation and actual final landing; never changes blocks. */
public final class RoadEntranceService {
    private RoadEntranceService() { }
    public static BlockPos outside(BuildingBlueprintTemplate t,BlueprintEntrance e) {
        var p=e.position();
        while(p.getX()>=t.minCorner().getX() && p.getX()<=t.maxCorner().getX()
                && p.getZ()>=t.minCorner().getZ() && p.getZ()<=t.maxCorner().getZ())p=p.relative(e.outward());
        return p.below();
    }
    /** The template's porch air may still contain original terrain after the shell is built. */
    public static TerrainProject prepare(ServerLevel level,SettlementRecord s,AutoRoadState.Route route,OptionalWorkBudget capacity){
        var building=s.building(route.building);
        if(building==null || !BlueprintTemplates.supports(building.type()))return null;
        var t=BlueprintTemplates.createForBuilding(building);
        var e=t.entrances().stream().filter(a->outside(t,a).equals(route.entrance)).findFirst().orElse(null);
        if(e==null)return null;
        var steps=new ArrayList<TerrainProject.Step>();
        for(var feet=route.entrance.above();!feet.equals(e.position());feet=feet.relative(e.outward().getOpposite())){
            for(int h=1;h>=0;h--){
                var at=feet.above(h);if(!level.hasChunkAt(at))return null;
                var state=level.getBlockState(at);var goal=t.placementAt(at);
                if(state.isAir() || goal!=null && !goal.state().isAir() && goal.mode().isFinalStructure())continue;
                if(state.getBlock() instanceof DoorBlock || state.hasBlockEntity() || !state.getFluidState().isEmpty()
                        || !TerrainService.natural(state) || state.getDestroySpeed(level,at)<0
                        || s.community().danger(at) || s.decorations().forbidden(at) || s.terrain().suppressed.contains(at.asLong()))continue;
                steps.add(new TerrainProject.Step(at,Blocks.AIR.defaultBlockState(),state));
            }
        }
        if(steps.isEmpty() || !capacity.fits(steps,null))return null;
        var project=ProductionRegionService.publish(level,s,building.id(),"建筑登记入口净空",e.position(),steps);
        if(project!=null){project.road=true;project.entranceAccess=true;project.explicit=false;project.dependencies.add(route.id);s.terrain().index(project);capacity.reserve(steps,null);}
        return project;
    }
    public static String blocker(ServerLevel level,SettlementRecord s,AutoRoadState.Route route) {
        if(route.manualBridge)return "";
        if(BridgeRoadConnections.bridge(s,route.building)!=null)return BridgeRoadConnections.blocker(level,s,route);
        var building=s.building(route.building);var site=s.blueprint(route.building);
        if(building!=null && !BlueprintTemplates.supports(building.type()))return "";
        if(building==null && site==null)return "入口母建筑不存在";
        var t=building!=null?BlueprintTemplates.createForBuilding(building):BlueprintTemplates.create(site);
        var e=t.entrances().stream().filter(a->outside(t,a).equals(route.entrance)).findFirst().orElse(null);
        if(e==null)return "道路终点未对齐门槛标高";
        if(!route.containsCell(route.entrance))return "道路尚未延伸到门前";
        var previous=route.entrance;
        var stop=e.position().below();var inward=e.outward().getOpposite();
        while(!previous.equals(stop)) {
            var next=previous.relative(inward);
            if(!level.hasChunkAt(previous) || !level.hasChunkAt(next))return "门前落脚面尚未加载";
            var a=level.getBlockState(previous);var b=level.getBlockState(next);
            // A pending building may still lack its indoor floor: compare its
            // planned floor, while requiring actual ground outside its shell.
            if(building==null){var from=t.placementAt(previous);
                if(!previous.equals(route.entrance) && from!=null && !from.state().isAir())a=from.state();
                var goal=t.placementAt(next);
                if(goal!=null && !goal.state().isAir())b=goal.state();}
            if((previous.equals(route.entrance) || building!=null) && !RoadSurfaceProfile.usable(level,previous) || a.isAir() || b.isAir()
                    || !RoadSurfaceProfile.joins(level,previous,a,next,b,inward))return "门前落脚面不连续 @ "+next.toShortString();
            if(building!=null)for(int h=1;h<=2;h++){
                var at=next.above(h);var cover=level.getBlockState(at);
                if(!cover.getFluidState().isEmpty() || !cover.getCollisionShape(level,at).isEmpty() && !(cover.getBlock() instanceof DoorBlock))return "门口净空受阻 @ "+at.toShortString();
            }
            previous=next;
        }
        return "";
    }
}
