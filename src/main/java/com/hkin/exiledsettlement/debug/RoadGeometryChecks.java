package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.road.RoadSurfaceProfile;
import com.google.gson.JsonObject;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;

/** Read-only geometry checks; no fixtures, materials, entities or world edits. */
public final class RoadGeometryChecks {
    private RoadGeometryChecks(){ }
    public static String run(ServerLevel level){
        var checks=new JsonObject();var p=new BlockPos(0,64,0);
        for(var d:Direction.Plane.HORIZONTAL){
            var stair=Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,d);
            var alternate=Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,d);
            checks.addProperty(d+"_low_half",RoadSurfaceProfile.edgeHeight(level,p,stair,d.getOpposite())==64.5);
            checks.addProperty(d+"_high_full",RoadSurfaceProfile.edgeHeight(level,p,stair,d)==65);
            checks.addProperty(d+"_material_reuse",RoadSurfaceProfile.equivalent(level,p,alternate,stair));
            checks.addProperty(d+"_wrong_facing_rejected",!RoadSurfaceProfile.equivalent(level,p,stair.setValue(StairBlock.FACING,d.getOpposite()),stair));
            checks.addProperty(d+"_lower_landing",RoadSurfaceProfile.joins(level,p,stair,p.relative(d.getOpposite()).below(),Blocks.STONE.defaultBlockState(),d.getOpposite()));
            checks.addProperty(d+"_upper_landing",RoadSurfaceProfile.joins(level,p,stair,p.relative(d),Blocks.STONE.defaultBlockState(),d));
            checks.addProperty(d+"_wrong_lower_landing_rejected",!RoadSurfaceProfile.joins(level,p,stair.setValue(StairBlock.FACING,d.getOpposite()),p.relative(d.getOpposite()).below(),Blocks.STONE.defaultBlockState(),d.getOpposite()));
            var slab=Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.BOTTOM);
            checks.addProperty(d+"_half_slab",RoadSurfaceProfile.edgeHeight(level,p,slab,d)==64.5);
            var upside=stair.setValue(StairBlock.HALF,Half.TOP);
            checks.addProperty(d+"_upside_full",RoadSurfaceProfile.edgeHeight(level,p,upside,d.getOpposite())==65);
            var inner=stair.setValue(StairBlock.SHAPE,StairsShape.INNER_LEFT);
            var outer=stair.setValue(StairBlock.SHAPE,StairsShape.OUTER_LEFT);
            checks.addProperty(d+"_inner_shape_distinguished",!RoadSurfaceProfile.equivalent(level,p,inner,stair));
            checks.addProperty(d+"_outer_shape_distinguished",!RoadSurfaceProfile.equivalent(level,p,outer,stair));
            checks.addProperty(d+"_inner_material_reuse",RoadSurfaceProfile.equivalent(level,p,inner,alternate.setValue(StairBlock.SHAPE,StairsShape.INNER_LEFT)));
            checks.addProperty(d+"_top_slab",RoadSurfaceProfile.edgeHeight(level,p,slab.setValue(SlabBlock.TYPE,SlabType.TOP),d)==65);
            checks.addProperty(d+"_double_slab",RoadSurfaceProfile.edgeHeight(level,p,slab.setValue(SlabBlock.TYPE,SlabType.DOUBLE),d)==65);
        }
        var out=new JsonObject();out.add("checks",checks);out.addProperty("worldEdits",0);
        var registry=new com.hkin.exiledsettlement.road.RoadSpaceData();registry.addAll(java.util.List.of(p));registry.pinManual(java.util.List.of(p));
        registry.adoptPlayerSurface(p,p.above());
        checks.addProperty("player_height_moves_registration",registry.contains(p.above()) && !registry.contains(p));
        checks.addProperty("manual_intent_moves_with_surface",registry.manual(p.above()) && registry.manualHeight(p)==p.getY()+1);
        var reloaded=com.hkin.exiledsettlement.road.RoadSpaceData.load(registry.save(new net.minecraft.nbt.CompoundTag(),level.registryAccess()),level.registryAccess());
        checks.addProperty("player_surface_survives_reload",reloaded.playerSurfaceAt(p.above()) && reloaded.manual(p.above()));
        var route=new com.hkin.exiledsettlement.road.AutoRoadState.Route(java.util.UUID.randomUUID(),p,java.util.List.of(p,p.east(),p.east(2)));
        route.centerline=route.cells;route.completed.set(0,3);route.progress=3;
        com.hkin.exiledsettlement.road.PlayerRoadEdits.adopt(route,p.east().above());
        checks.addProperty("route_uses_actual_player_height",route.cells.get(1).equals(p.east().above()) && route.centerline.get(1).equals(p.east().above()));
        checks.addProperty("adoption_does_not_fake_completion",route.completed.isEmpty() && route.progress==0);
        out.addProperty("passed",checks.entrySet().stream().allMatch(e->e.getValue().getAsBoolean()));return out.toString();
    }
}
