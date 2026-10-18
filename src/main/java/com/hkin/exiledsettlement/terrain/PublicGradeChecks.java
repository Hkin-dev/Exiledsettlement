package com.hkin.exiledsettlement.terrain;

import com.google.gson.JsonObject;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.*;
import java.util.*;

/** Runs the production solver on detached records. No world changes. */
public final class PublicGradeChecks {
    private PublicGradeChecks(){ }
    private static long key(int x,int z){return BlockPos.asLong(x,0,z);}
    private static PublicGroundService.State fixture(int length,int width,int base,int left,int right){
        var w=new PublicGroundService.State();
        for(int x=0;x<=length;x++)for(int z=0;z<width;z++){
            long k=key(x,z);w.baselines.put(k,base);w.goals.put(k,base);
            if(x==0)w.anchors.put(k,left);else if(x==length)w.anchors.put(k,right);
        }
        PublicGroundService.startSources(w);return w;
    }
    private static void finish(PublicGroundService.State w){
        var s=new SettlementRecord(UUID.randomUUID(),"grade fixture",new BlockPos(0,65,0),UUID.randomUUID());
        for(int i=0;i<20000 && w.phase!=5;i++)switch(w.phase){
            case 8 -> PublicGroundService.spread(w);
            case 9 -> PublicGroundService.divide(w);
            case 12 -> PublicGroundService.spreadRoads(w);
            case 13 -> PublicGroundService.initializeRoadRegions(w);
            case 11 -> PublicGroundService.envelopes(w);
            case 10 -> PublicGroundService.relax(w);
            case 6 -> PublicGroundService.seams(w);
            case 7 -> PublicGroundService.passages(s,w);
            default -> throw new IllegalStateException("unexpected phase "+w.phase);
        }
        if(w.phase!=5)throw new IllegalStateException("grade fixture did not converge");
    }
    private static boolean slopes(PublicGroundService.State w){
        double rise=1D/TerrainConfig.PUBLIC_GRADE_RUN.get();
        for(var e:w.heights.entrySet())for(var d:Direction.Plane.HORIZONTAL){long n=BlockPos.of(e.getKey()).relative(d).asLong();
            if(w.heights.containsKey(n) && (w.barriers.getOrDefault(e.getKey(),0)&(1<<d.get2DDataValue()))==0
                    && Math.abs(e.getValue()-w.heights.get(n))>rise+1e-6)return false;
        }return true;
    }
    public static String run(){
        var checks=new JsonObject();var flat=fixture(24,7,64,64,64);finish(flat);
        checks.addProperty("flat_ground_not_raised_or_pitted",flat.goals.values().stream().allMatch(y->y==64));
        checks.addProperty("flat_ground_has_no_stairs",flat.stairs.isEmpty());
        var ramp=fixture(40,7,64,64,68);finish(ramp);
        checks.addProperty("anchors_unchanged",ramp.anchors.entrySet().stream().allMatch(e->Objects.equals(ramp.goals.get(e.getKey()),e.getValue())));
        checks.addProperty("continuous_slope_is_bounded",slopes(ramp));
        var differentTerrain=fixture(40,7,94,64,68);finish(differentTerrain);
        checks.addProperty("original_terrain_has_zero_height_attraction",ramp.goals.equals(differentTerrain.goals) && ramp.heights.equals(differentTerrain.heights));
        checks.addProperty("gentle_ramp_does_not_scatter_stairs",ramp.stairs.isEmpty());
        checks.addProperty("stairs_leave_fixed_checkpoints",ramp.stairs.keySet().stream().noneMatch(ramp.anchors::containsKey));
        checks.addProperty("stairs_have_real_lower_landing",ramp.stairs.entrySet().stream().allMatch(e->{long n=BlockPos.of(e.getKey()).relative(e.getValue().getOpposite()).asLong();return Objects.equals(ramp.goals.get(n),ramp.goals.get(e.getKey())-1);}));
        var conflict=fixture(1,4,64,64,68);finish(conflict);
        checks.addProperty("impossible_fixed_levels_separate",!conflict.barriers.isEmpty() && slopes(conflict));
        var original=fixture(80,17,64,64,68);PublicGroundService.spread(original);
        var resumed=new PublicGroundService.State();resumed.load(original.save());finish(original);finish(resumed);
        checks.addProperty("restart_mid_envelopes_preserves_result",original.goals.equals(resumed.goals) && original.stairs.equals(resumed.stairs));
        var mid=fixture(50,17,64,64,67);while(mid.phase!=10)switch(mid.phase){case 8 -> PublicGroundService.spread(mid);case 9 -> PublicGroundService.divide(mid);case 12 -> PublicGroundService.spreadRoads(mid);case 13 -> PublicGroundService.initializeRoadRegions(mid);case 11 -> PublicGroundService.envelopes(mid);default -> throw new IllegalStateException();}PublicGroundService.relax(mid);
        var loaded=new PublicGroundService.State();loaded.load(mid.save());finish(mid);finish(loaded);
        checks.addProperty("restart_mid_relaxation_preserves_result",mid.goals.equals(loaded.goals) && mid.stairs.equals(loaded.stairs));
        var road=fixture(40,7,90,64,68);road.roadHeights.put(key(16,3),66.5);finish(road);
        checks.addProperty("actual_road_source_changes_solution",!road.heights.equals(ramp.heights));
        checks.addProperty("road_source_does_not_break_slope",slopes(road));
        checks.addProperty("road_source_does_not_move_fixed_checkpoint",road.anchors.entrySet().stream().allMatch(e->Objects.equals(road.goals.get(e.getKey()),e.getValue())));
        var roadOnly=fixture(20,5,91,64,68);roadOnly.anchors.clear();roadOnly.roadHeights.put(key(10,2),65.5);PublicGroundService.startSources(roadOnly);finish(roadOnly);
        checks.addProperty("road_only_component_uses_half_height",roadOnly.heights.values().stream().allMatch(y->Math.abs(y-65.5)<1e-8));
        var noSource=fixture(20,5,64,64,64);noSource.anchors.clear();PublicGroundService.startSources(noSource);finish(noSource);
        checks.addProperty("no_source_component_is_deferred",noSource.goals.isEmpty() && noSource.stairs.isEmpty());
        checks.addProperty("weight_decays_with_path_distance",PublicGroundService.sourceWeight(8,3)==.5 && PublicGroundService.sourceWeight(6,1)==1.5);
        var separated=fixture(20,5,64,64,68);
        for(int z=0;z<5;z++){separated.barriers.put(key(9,z),1<<Direction.EAST.get2DDataValue());separated.barriers.put(key(10,z),1<<Direction.WEST.get2DDataValue());}
        separated.roadHeights.put(key(2,2),64.5);finish(separated);
        checks.addProperty("road_influence_does_not_cross_retaining_boundary",!separated.nearestRoad.containsKey(key(12,2)));
        checks.addProperty("checkpoint_influence_stays_on_own_side",separated.nearest.get(key(12,2)).equals(key(20,2)));
        checks.addProperty("wall_without_source_does_not_raise_ground",separated.goals.get(key(12,2))==68);
        var partial=fixture(60,17,64,64,67);partial.roadHeights.put(key(30,8),65.5);
        while(partial.phase!=12)PublicGroundService.spread(partial);PublicGroundService.spreadRoads(partial);
        var restored=new PublicGroundService.State();restored.load(partial.save());finish(partial);finish(restored);
        checks.addProperty("road_sources_survive_mid_spread_restart",partial.goals.equals(restored.goals) && partial.roadHeights.equals(restored.roadHeights));
        double oldRoad=TerrainConfig.PUBLIC_ROAD_WEIGHT.get(),oldCheckpoint=TerrainConfig.PUBLIC_CHECKPOINT_WEIGHT.get(),oldTerrain=TerrainConfig.PUBLIC_TERRAIN_WEIGHT.get();
        try{
            TerrainConfig.PUBLIC_TERRAIN_WEIGHT.set(4D);var legacy=fixture(40,7,94,64,68);finish(legacy);
            checks.addProperty("legacy_terrain_config_cannot_restore_attraction",legacy.heights.equals(ramp.heights));
            TerrainConfig.PUBLIC_ROAD_WEIGHT.set(100D);var strongerRoad=fixture(40,7,90,64,68);strongerRoad.roadHeights.put(key(16,3),66.5);finish(strongerRoad);
            checks.addProperty("road_weight_changes_height_towards_road",strongerRoad.heights.get(key(16,3))>road.heights.get(key(16,3)));
            TerrainConfig.PUBLIC_ROAD_WEIGHT.set(oldRoad);TerrainConfig.PUBLIC_CHECKPOINT_WEIGHT.set(100D);
            var strongerCheckpoint=fixture(40,7,90,64,68);strongerCheckpoint.roadHeights.put(key(16,3),66.5);finish(strongerCheckpoint);
            checks.addProperty("checkpoint_weight_changes_height_towards_checkpoint",strongerCheckpoint.heights.get(key(16,3))<road.heights.get(key(16,3)));
        }finally{TerrainConfig.PUBLIC_ROAD_WEIGHT.set(oldRoad);TerrainConfig.PUBLIC_CHECKPOINT_WEIGHT.set(oldCheckpoint);TerrainConfig.PUBLIC_TERRAIN_WEIGHT.set(oldTerrain);}
        var islands=fixture(20,5,90,64,68);islands.anchors.clear();islands.roadHeights.put(key(2,2),63.5);islands.roadHeights.put(key(17,2),70.5);
        for(int z=0;z<5;z++){islands.barriers.put(key(9,z),1<<Direction.EAST.get2DDataValue());islands.barriers.put(key(10,z),1<<Direction.WEST.get2DDataValue());}
        PublicGroundService.startSources(islands);finish(islands);
        checks.addProperty("road_only_regions_do_not_share_initial_height",Math.abs(islands.heights.get(key(5,2))-63.5)<1e-7 && Math.abs(islands.heights.get(key(15,2))-70.5)<1e-7);
        var sparse=new PublicGroundService.State();sparse.minX=0;sparse.maxX=999;sparse.minZ=sparse.maxZ=0;
        for(int x=0;x<900;x++)sparse.outside.add(key(x,0));
        var first=PublicGroundService.gradeBatch(sparse);
        checks.addProperty("empty_perimeter_visits_are_bounded",first.isEmpty() && sparse.cursor==512);
        var second=PublicGroundService.gradeBatch(sparse);
        int sampled=Math.min(100,TerrainConfig.PUBLIC_SAMPLE_COLUMNS.get());
        checks.addProperty("sampling_budget_counts_candidate_columns",second.size()==sampled && sparse.cursor==900+sampled && second.getFirst()==key(900,0));
        var sparseLoaded=new PublicGroundService.State();sparseLoaded.load(sparse.save());
        var tail=new ArrayList<Long>();while(sparse.cursor<1000)tail.addAll(PublicGroundService.gradeBatch(sparse));
        var resumedTail=new ArrayList<Long>();while(sparseLoaded.cursor<1000)resumedTail.addAll(PublicGroundService.gradeBatch(sparseLoaded));
        checks.addProperty("sampling_cursor_restart_preserves_remaining_columns",tail.equals(resumedTail) && tail.size()==100-sampled);
        var all=new ArrayList<Long>(second);all.addAll(tail);
        checks.addProperty("sparse_sampling_has_no_skips_or_duplicates",all.size()==100 && new HashSet<>(all).size()==100 && all.getLast()==key(999,0));
        var dense=new PublicGroundService.State();dense.minX=0;dense.maxX=999;dense.minZ=dense.maxZ=0;
        int denseLimit=Math.min(1000,TerrainConfig.PUBLIC_SAMPLE_COLUMNS.get());
        checks.addProperty("dense_world_read_budget_is_configured",PublicGroundService.gradeBatch(dense).size()==denseLimit && dense.cursor==denseLimit);
        var transaction=new SettlementRecord(UUID.randomUUID(),"release review",new BlockPos(0,65,0),UUID.randomUUID());
        var review=transaction.terrain().publicGround;review.phase=5;review.planCursor=123;review.roadsIntegrated=true;review.signature=1234;
        PublicGroundService.queueReleasedConstraintReview(transaction);
        checks.addProperty("released_constraint_keeps_publication_cursor",review.phase==5 && review.planCursor==123 && review.roadsIntegrated && review.signature==1234 && review.releasedConstraintReview);
        var savedReview=new PublicGroundService.State();savedReview.load(review.save());
        checks.addProperty("released_constraint_review_survives_restart",savedReview.releasedConstraintReview && savedReview.phase==5 && savedReview.planCursor==123);
        review.phase=0;review.nextReview=1200;PublicGroundService.queueReleasedConstraintReview(transaction);
        checks.addProperty("completed_release_keeps_review_cooldown",review.signature==Long.MIN_VALUE && review.nextReview==1200);
        review.phase=5;PublicGroundService.requestReview(transaction);
        checks.addProperty("new_constraint_review_still_invalidates_plan",review.phase==0 && !review.roadsIntegrated && !review.releasedConstraintReview && review.nextReview==0);
        var out=new JsonObject();out.add("checks",checks);out.addProperty("worldEdits",0);out.addProperty("passed",checks.entrySet().stream().allMatch(e->e.getValue().getAsBoolean()));return out.toString();
    }
}
