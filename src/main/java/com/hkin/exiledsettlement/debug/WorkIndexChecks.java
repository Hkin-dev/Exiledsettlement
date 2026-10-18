package com.hkin.exiledsettlement.debug;

import com.google.gson.JsonObject;
import com.hkin.exiledsettlement.terrain.*;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Detached-record tests for candidate selection, not cached world/work safety decisions. */
public final class WorkIndexChecks {
    private WorkIndexChecks(){ }
    public static String audit(SettlementRecord settlement){
        var state=settlement.terrain();var positions=new LinkedHashSet<BlockPos>();var dependencies=new LinkedHashSet<UUID>();
        for(var p:state.projects.values()){if(!p.steps.isEmpty())positions.add(p.steps.getFirst().position());dependencies.addAll(p.dependencies);}
        var sampled=new ArrayList<>(positions);Collections.shuffle(sampled,new Random(70));var failures=new com.google.gson.JsonArray();
        int count=Math.min(128,sampled.size()),depCount=0;
        for(var at:sampled.subList(0,count)){
            var expected=state.projects.values().stream().filter(p->!p.indices(at).isEmpty()).map(p->p.id).collect(java.util.stream.Collectors.toSet());
            var actual=state.projectsAt(at).stream().map(p->p.id).collect(java.util.stream.Collectors.toSet());
            if(!expected.equals(actual))failures.add("position "+at.toShortString());
            var columnExpected=state.projects.values().stream().filter(p->p.steps.stream().anyMatch(step->step.position().getX()==at.getX() && step.position().getZ()==at.getZ())).map(p->p.id).collect(java.util.stream.Collectors.toSet());
            var columnActual=state.projectsInColumn(at).stream().map(p->p.id).collect(java.util.stream.Collectors.toSet());
            if(!columnExpected.equals(columnActual))failures.add("column "+at.toShortString());
        }
        for(var id:dependencies){if(depCount++>=128)break;
            var expected=state.projects.values().stream().filter(p->p.dependencies.contains(id)).map(p->p.id).collect(java.util.stream.Collectors.toSet());
            var actual=state.dependingOn(id).stream().map(p->p.id).collect(java.util.stream.Collectors.toSet());
            if(!expected.equals(actual))failures.add("dependency "+id);
        }
        int routeCells=0;for(var r:settlement.autoRoads().routes.values())for(int i=0;i<r.cells.size();i++){
            var at=r.cells.get(i);routeCells++;
            if(r.cellIndex(at)!=r.cells.indexOf(at) || !r.containsCell(at)
                    || !r.cellInColumn(at).equals(r.cells.stream().filter(p->p.getX()==at.getX() && p.getZ()==at.getZ()).findFirst().orElseThrow()))failures.add("route "+r.id+" "+at.toShortString());
        }
        var out=new JsonObject();out.addProperty("projects",state.projects.size());out.addProperty("sampledPositions",count);out.addProperty("sampledDependencies",Math.min(depCount,128));out.addProperty("routeCells",routeCells);out.add("failures",failures);out.addProperty("passed",failures.isEmpty());out.addProperty("worldEdits",0);return out.toString();
    }
    private static TerrainProject project(UUID id,BlockPos p){return new TerrainProject(id,"index check",p,List.of(
            new TerrainProject.Step(p,Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState()),
            new TerrainProject.Step(p,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));}
    public static String run(){
        var checks=new JsonObject();var state=new TerrainState();var at=new BlockPos(0,64,0);var id=UUID.randomUUID();var dependency=UUID.randomUUID();
        for(int i=1;i<=5000;i++)state.index(project(UUID.randomUUID(),at.offset(i,0,0)));
        var p=project(id,at);p.dependencies.add(dependency);state.index(p);
        checks.addProperty("position_lookup_ignores_5000_unrelated_jobs",state.projectsAt(at).equals(List.of(p)));
        checks.addProperty("dependency_lookup_ignores_unrelated_jobs",state.dependingOn(dependency).equals(List.of(p)));
        checks.addProperty("two_steps_do_not_duplicate_project",state.projectsAt(at).size()==1 && p.indices(at).size()==2);
        var overlap=project(UUID.randomUUID(),at);state.index(overlap);
        checks.addProperty("overlapping_projects_both_preserved",new HashSet<>(state.projectsAt(at)).equals(Set.of(p,overlap)));
        p.abandon(0);p.cancelled=true;
        checks.addProperty("abandon_and_cancel_do_not_erase_paid_candidates",state.projectsAt(at).contains(p) && p.abandoned.get(0) && p.cancelled);
        state.index(p);checks.addProperty("reindex_does_not_duplicate",state.projectsAt(at).size()==2);
        var d2=UUID.randomUUID();p.dependencies.add(d2);
        checks.addProperty("late_dependency_add_is_immediate",state.dependingOn(d2).equals(List.of(p)));
        p.dependencies.remove(dependency);checks.addProperty("dependency_remove_is_immediate",state.dependingOn(dependency).isEmpty());
        p.dependencies.add(dependency);p.dependencies.removeIf(d->d.equals(d2));
        checks.addProperty("iterator_remove_updates_index",state.dependingOn(d2).isEmpty());
        p.dependencies.add(d2);p.dependencies.retainAll(Set.of(d2));
        checks.addProperty("retain_all_updates_index",state.dependingOn(dependency).isEmpty());
        p.dependencies.add(dependency);p.dependencies.removeAll(Set.of(d2,dependency));
        checks.addProperty("remove_all_updates_index",state.dependingOn(dependency).isEmpty() && state.dependingOn(d2).isEmpty());
        p.dependencies.addAll(Set.of(d2,dependency));p.dependencies.clear();
        checks.addProperty("clear_updates_index",state.dependingOn(dependency).isEmpty() && state.dependingOn(d2).isEmpty());
        p.dependencies.add(dependency);var replacement=project(id,at.above());replacement.dependencies.add(d2);state.index(replacement);
        checks.addProperty("same_id_replacement_releases_old_geometry",state.projectsAt(at).equals(List.of(overlap)) && state.projectsAt(at.above()).equals(List.of(replacement)));
        checks.addProperty("same_id_replacement_releases_old_dependency",state.dependingOn(dependency).isEmpty() && state.dependingOn(d2).equals(List.of(replacement)));
        p.dependencies.add(UUID.randomUUID());checks.addProperty("retired_instance_cannot_corrupt_index",state.dependingOn(d2).equals(List.of(replacement)));
        replacement.bridge=true;replacement.appendBridgeRepair(List.of(new TerrainProject.Step(at.above(2),Blocks.STONE.defaultBlockState(),Blocks.AIR.defaultBlockState())));
        checks.addProperty("appended_geometry_is_immediate",state.projectsAt(at.above(2)).equals(List.of(replacement)));
        var loaded=new TerrainState();loaded.load(state.save());
        checks.addProperty("load_rebuilds_column_index",loaded.projectsInColumn(at.above(20)).size()==2);
        checks.addProperty("load_rebuilds_position_index",loaded.projectsAt(at.above(2)).size()==1 && loaded.projectsAt(at.above(2)).getFirst().id.equals(id));
        checks.addProperty("load_rebuilds_dependency_index",loaded.dependingOn(d2).size()==1 && loaded.dependingOn(d2).getFirst().id.equals(id));
        loaded.projects.get(id).dependencies.clear();checks.addProperty("loaded_mutations_update_index",loaded.dependingOn(d2).isEmpty());
        var live=state.dependingOn(d2);replacement.completed=true;
        checks.addProperty("dependency_snapshot_keeps_live_completion",live.getFirst().completed);
        replacement.completed=false;replacement.cancelled=true;
        checks.addProperty("dependency_snapshot_keeps_live_cancellation",live.getFirst().cancelled);
        replacement.cancelled=false;replacement.bridge=false;replacement.layoutVersion=TerrainService.LAYOUT_VERSION-1;state.index(replacement);
        checks.addProperty("legacy_foundation_index_finds_old_layout",state.legacyFoundations().equals(List.of(replacement)));
        replacement.layoutVersion=TerrainService.LAYOUT_VERSION;state.index(replacement);
        checks.addProperty("legacy_foundation_index_releases_updated_layout",state.legacyFoundations().isEmpty());
        replacement.layoutVersion=TerrainService.LAYOUT_VERSION-1;state.index(replacement);
        var history=new TerrainState();history.load(state.save());
        checks.addProperty("legacy_foundation_index_rebuilt_on_load",history.legacyFoundations().size()==1);
        replacement.cancelled=true;
        checks.addProperty("legacy_foundation_live_cancellation_observed",state.legacyFoundations().isEmpty());replacement.cancelled=false;
        var s=new SettlementRecord(UUID.randomUUID(),"dependency check",at,UUID.randomUUID());
        var parent=s.addBlueprint(com.hkin.exiledsettlement.building.BuildingType.HOUSE,at,net.minecraft.core.Direction.NORTH,s.ownerId(),false);
        var required=project(UUID.randomUUID(),at.below());required.dependencies.add(parent.id());s.terrain().index(required);
        checks.addProperty("unfinished_dependency_still_blocks",!TerrainService.dependenciesReady(s,parent.id()));
        required.completed=true;checks.addProperty("completion_observed_without_stale_cache",TerrainService.dependenciesReady(s,parent.id()));
        required.completed=false;required.cancelled=true;checks.addProperty("cancellation_observed_without_stale_cache",TerrainService.dependenciesReady(s,parent.id()));
        var route=new AutoRoadState.Route(UUID.randomUUID(),at,List.of(at,at.east(),at.east()));
        checks.addProperty("route_index_preserves_first_occurrence",route.cellIndex(at.east())==1 && route.cellInColumn(at.east().above()).equals(at.east()));
        checks.addProperty("route_missing_and_null_safe",route.cellIndex(null)==-1 && !route.containsCell(at.west()));
        route.cells=List.of(at.above(),at.east().above());
        checks.addProperty("route_replacement_rebuilds_index",!route.containsCell(at) && route.cellIndex(at.above())==0 && route.cellInColumn(at).equals(at.above()));
        var out=new JsonObject();out.add("checks",checks);out.addProperty("worldEdits",0);out.addProperty("unrelatedProjects",5000);out.addProperty("passed",checks.entrySet().stream().allMatch(e->e.getValue().getAsBoolean()));return out.toString();
    }
}
