package com.hkin.exiledsettlement.terrain;

import com.google.gson.JsonObject;
import com.hkin.exiledsettlement.debug.SaveAcceptanceService;
import com.hkin.exiledsettlement.road.AutoRoadState;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Explicit isolated fixtures exercise the real shared publisher; no simulated worker completion. */
public final class PublicWorksChecks {
    public static String run(ServerLevel level){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var checks=new JsonObject();var core=new BlockPos(25000,240,25000);var at=core.offset(4,-1,0);
        var previous=new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        try{
            for(var p:BlockPos.betweenClosed(at.offset(-2,-2,-2),at.offset(2,3,2))){var pos=p.immutable();previous.put(pos,level.getBlockState(pos));level.setBlock(pos,pos.getY()<239?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),2);}
            var s=new SettlementRecord(UUID.randomUUID(),"public works fixture",core,UUID.randomUUID());
            var route=new AutoRoadState.Route(UUID.randomUUID(),at,List.of(at));route.centerline=route.cells;s.autoRoads().routes.put("first",route);
            var w=s.terrain().publicGround;long k=BlockPos.asLong(at.getX(),0,at.getZ());w.goals.put(k,at.getY());w.baselines.put(k,at.getY());w.phase=5;
            checks.addProperty("provisional_height_does_not_publish_soil",!PublicGroundService.publishRoadColumn(level,s,route,at) && s.terrain().projects.isEmpty());
            w.roadsIntegrated=true;w.integratedRoads.add(k);w.roadCursor=1;
            checks.addProperty("joint_target_publishes_real_material_order",PublicGroundService.publishRoadColumn(level,s,route,at) && !s.terrain().projects.isEmpty());
            int count=s.terrain().projects.size();var owner=s.terrain().cells.get(at);
            checks.addProperty("publisher_never_places_world_block",level.getBlockState(at).isAir());
            checks.addProperty("repeated_schedule_reuses_same_order",PublicGroundService.publishRoadColumn(level,s,route,at) && s.terrain().projects.size()==count && Objects.equals(owner,s.terrain().cells.get(at)));
            var other=new AutoRoadState.Route(UUID.randomUUID(),at,List.of(at));other.centerline=other.cells;s.autoRoads().routes.put("second",other);
            PublicGroundService.publishRoadColumn(level,s,other,at);
            var project=s.terrain().projects.get(owner);
            checks.addProperty("two_roads_share_one_position_and_dependencies",project!=null && project.dependencies.containsAll(List.of(route.id,other.id)) && s.terrain().projects.size()==count);
            checks.addProperty("work_is_not_marked_finished",project!=null && !project.completed && project.cursor==0 && project.finished.isEmpty());
            s.terrain().enabled=false;s.autoRoads().enabled=true;
            checks.addProperty("road_work_survives_ground_switch_off",TerrainService.allowed(s,s.blueprint(owner)));
            s.terrain().enabled=true;s.autoRoads().enabled=false;
            checks.addProperty("ground_switch_cannot_override_road_switch_off",!TerrainService.allowed(s,s.blueprint(owner)));
            s.autoRoads().enabled=true;
            var restored=new TerrainState();restored.load(s.terrain().save());
            checks.addProperty("reload_preserves_shared_target_and_dependencies",Objects.equals(restored.cells.get(at),owner) && restored.projects.get(owner).dependencies.containsAll(List.of(route.id,other.id)));
            checks.addProperty("reload_preserves_joint_phase_and_road_cursor",restored.publicGround.roadsIntegrated && restored.publicGround.roadCursor==1 && restored.publicGround.integratedRoads.contains(k));
            w.phase=14;w.roadCursor=0;w.roadsIntegrated=false;var partial=new PublicGroundService.State();partial.load(w.save());
            checks.addProperty("mid_road_solve_survives_restart",partial.phase==14 && !partial.roadsIntegrated && partial.goals.equals(w.goals));
            w.anchors.put(k,at.getY());w.roadGeneratedAnchors.add(k);
            checks.addProperty("automatic_road_solution_is_not_permanent_player_checkpoint",PublicGroundService.checkpointAt(s,at)==null);
            w.roadGeneratedAnchors.remove(k);
            checks.addProperty("manual_checkpoint_keeps_height_authority",Objects.equals(PublicGroundService.checkpointAt(s,at),at.getY()));
            w.anchors.clear();
            w.phase=5;w.roadsIntegrated=true;w.goals.put(k,at.getY()+1);
            checks.addProperty("stale_road_height_cannot_publish",!PublicGroundService.publishRoadColumn(level,s,route,at) && s.terrain().projects.size()==count);
            checks.addProperty("old_road_worker_cannot_restore_stale_height","CONSTRUCTION_TARGET_CHANGED".equals(TerrainService.guard(level,s,s.blueprint(owner),project.steps.getFirst())));
            w.goals.put(k,at.getY());s.terrain().suppressed.add(at.asLong());
            checks.addProperty("explicit_suppression_still_blocks",!PublicGroundService.publishRoadColumn(level,s,route,at));
            checks.addProperty("column_index_preserves_shared_order",s.terrain().projectsInColumn(at.above(9)).size()==1);
        }finally{previous.forEach((p,state)->level.setBlock(p,state,2));}
        var out=new JsonObject();out.add("checks",checks);out.addProperty("fixtureWorldEdits",true);out.addProperty("residentCompletionSimulated",false);out.addProperty("passed",checks.entrySet().stream().allMatch(e->e.getValue().getAsBoolean()));return out.toString();
    }
    private PublicWorksChecks(){ }
}
