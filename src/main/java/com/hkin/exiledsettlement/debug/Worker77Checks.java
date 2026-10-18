package com.hkin.exiledsettlement.debug;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.road.RoadSpaceData;
import com.hkin.exiledsettlement.road.AutoRoadState;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;
import java.util.*;

/** Detached checks and explicit temporary blocks, separate from real native resident acceptance. */
public final class Worker77Checks {
    private static void check(boolean pass,String name,List<String> results){if(!pass)throw new IllegalStateException(name);results.add(name);}
    public static String run(ServerLevel level){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var out=new ArrayList<String>();var origin=new BlockPos(30500,250,30500);
        var s=new SettlementRecord(UUID.randomUUID(),"Worker77 detached",origin,UUID.randomUUID());
        var h2=s.addBlueprint(BuildingType.HOUSE,origin.east(4),origin.above(40),Direction.NORTH,s.ownerId(),false);
        var h3=s.addBlueprint(BuildingType.HOUSE_2,origin.east(20),origin.above(41),Direction.NORTH,s.ownerId(),false);
        var h4=s.addBlueprint(BuildingType.HOUSE_3,origin.west(20),origin.above(42),Direction.NORTH,s.ownerId(),false);
        var other=s.addBlueprint(BuildingType.WAREHOUSE,origin.south(20),origin.above(43),Direction.NORTH,s.ownerId(),false);
        check(WorkTaskService.defaultWorkers(h2)==2,"house_2_default",out);check(WorkTaskService.defaultWorkers(h3)==3,"house_3_default",out);
        check(WorkTaskService.defaultWorkers(h4)==4,"house_4_default",out);check(WorkTaskService.defaultWorkers(other)==2,"other_p0_default",out);
        check(WorkTaskService.priority(s,h2)==0,"building_p0",out);
        var at=h2.anchor().below(2);var prep=s.addBlueprint(BuildingType.HOUSE,at,origin.above(44),Direction.NORTH,s.ownerId(),false);
        var project=new TerrainProject(prep.id(),"foundation",at,List.of(new TerrainProject.Step(at,Blocks.COBBLESTONE.defaultBlockState(),Blocks.AIR.defaultBlockState())));
        project.dependencies.add(h2.id());prep.withTerrainProject(project);s.terrain().index(project);
        check(WorkTaskService.priority(s,prep)==0,"foundation_inherits_p0",out);check(WorkTaskService.mother(s,prep,at).equals(h2.id()),"foundation_mother",out);
        check(!TerrainService.dependenciesReady(s,h2.id()),"real_foundation_is_required",out);
        project.mark(0);check(TerrainService.dependenciesReady(s,h2.id()),"finished_foundation_releases_parent",out);
        var optional=s.addBlueprint(BuildingType.HOUSE,origin.south(35),origin.above(45),Direction.NORTH,s.ownerId(),false);
        var pub=new TerrainProject(optional.id(),"public",optional.anchor(),List.of(new TerrainProject.Step(optional.anchor(),Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));
        pub.publicGround=true;pub.dependencies.add(h2.id());optional.withTerrainProject(pub);s.terrain().index(pub);
        check(WorkTaskService.priority(s,optional)==2,"independent_terrain_p2",out);check(TerrainService.dependenciesReady(s,h2.id()),"p0_does_not_wait_for_surroundings",out);
        var cs=new ArrayList<>(s.citizens());var c=cs.getFirst();c.startTask(CitizenWorkState.TO_BLUEPRINT,at,null,prep.id());WorkTaskService.bind(s,c);
        check(WorkTaskService.assigned(s,h2.id())==1,"child_counts_once_under_parent",out);check(c.motherTaskId().equals(h2.id()),"mother_bound",out);
        c.fetchConstructionMaterial(null);check(WorkTaskService.task(s,c).priority()==0 && WorkTaskService.assigned(s,h2.id())==1,"material_child_keeps_parent_slot",out);
        var saved=CitizenRecord.load(c.save(level.registryAccess()),level.registryAccess());check(h2.id().equals(saved.motherTaskId()),"mother_saved",out);
        c.clearWork();check(c.motherTaskId()==null && WorkTaskService.assigned(s,h2.id())==0,"clear_releases_parent",out);
        c.startEquipmentFetch(null,origin);c.setConstructionResume(h2.id(),h2.anchor());WorkTaskService.bind(s,c);
        check(WorkTaskService.task(s,c).priority()==0 && WorkTaskService.assigned(s,h2.id())==1,"tool_child_keeps_parent",out);
        saved=CitizenRecord.load(c.save(level.registryAccess()),level.registryAccess());check(h2.id().equals(saved.constructionResumeId()) && saved.constructionResumePosition().equals(h2.anchor()),"tool_resume_saved",out);c.clearWork();
        c.startTask(CitizenWorkState.TO_PUBLIC_ITEM,origin,null,null);check(WorkTaskService.task(s,c).mother()!=null && WorkTaskService.task(s,c).child()!=null,"generic_work_has_mother_child",out);c.clearWork();
        var shared=s.addBlueprint(BuildingType.HOUSE,origin,origin.above(48),Direction.NORTH,s.ownerId(),false);
        var road=new TerrainProject(shared.id(),"shared access",origin,List.of(new TerrainProject.Step(origin,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));road.road=true;road.dependencies.add(h2.id());road.dependencies.add(h3.id());shared.withTerrainProject(road);
        c.startTask(CitizenWorkState.TO_BLUEPRINT,origin,null,shared.id());WorkTaskService.reserve(s,c);
        var second=cs.get(1);second.startTask(CitizenWorkState.TO_BLUEPRINT,origin.east(),null,shared.id());WorkTaskService.reserve(s,second);
        check(shared.id().equals(c.motherTaskId()) && shared.id().equals(second.motherTaskId()),"shared_road_has_independent_work_goal",out);
        check(WorkTaskService.assigned(s,h2.id())==0 && WorkTaskService.assigned(s,h3.id())==0,"shared_road_does_not_take_building_slots",out);
        var mother=c.motherTaskId();c.fetchConstructionMaterial(null);WorkTaskService.bind(s,c);check(mother.equals(c.motherTaskId()) && mother.equals(WorkTaskService.task(s,c).mother()),"shared_slot_stable_during_supply",out);
        check(WorkTaskService.counts(s).get(shared.id())==2,"dispatch_snapshot_counts",out);c.clearWork();second.clearWork();
        var access=new AutoRoadState.Route(h3.id(),origin,List.of(origin));s.autoRoads().routes.put("worker77",access);
        c.startTask(CitizenWorkState.TO_ROAD,origin,null,null);c.setRoadTaskId(access.id);WorkTaskService.bind(s,c);
        check(WorkTaskService.task(s,c).priority()==2 && access.id.equals(c.motherTaskId()),"road_is_p2_with_independent_slot",out);
        saved=CitizenRecord.load(c.save(level.registryAccess()),level.registryAccess());check(access.id.equals(saved.roadTaskId()),"road_child_id_saved",out);c.clearWork();
        c.startTask(CitizenWorkState.TO_ROAD_TOOL,origin,null,null);c.setRoadTaskId(access.id);WorkTaskService.bind(s,c);
        check(WorkTaskService.task(s,c).priority()==2 && access.id.equals(c.motherTaskId()),"road_tool_keeps_road_slot",out);c.clearWork();
        var samples=new ArrayList<PublicGroundService.HeightSample>();for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)samples.add(new PublicGroundService.HeightSample(dx==0&&dz==0?80:100,PublicGroundService.sampleWeight(dx,dz)));
        check(PublicGroundService.weightedHeight(samples)==100,"deep_single_cell_not_height_filtered",out);
        check(PublicGroundService.sampleWeight(0,0)>PublicGroundService.sampleWeight(1,0) && PublicGroundService.sampleWeight(1,0)>PublicGroundService.sampleWeight(2,0),"near_samples_have_more_weight",out);
        check(PublicGroundService.weightedHeight(List.of(new PublicGroundService.HeightSample(10,.5),new PublicGroundService.HeightSample(20,.2)))==10,"near_height_wins",out);
        Collections.reverse(samples);check(PublicGroundService.weightedHeight(samples)==100,"weighted_result_order_independent",out);
        var before=new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();var entity=ModEntities.SETTLEMENT_CITIZEN.get().create(level);
        try {
            for(var raw:BlockPos.betweenClosed(origin.north(20).offset(-1,-1,-1),origin.north(20).offset(4,4,1))){var p=raw.immutable();if(!level.getBlockState(p).isAir())throw new IllegalStateException("Unused air required");before.put(p,level.getBlockState(p));}
            var cell=origin.north(20);level.setBlock(cell.below(),Blocks.STONE.defaultBlockState(),2);
            var own=s.addBlueprint(BuildingType.HOUSE,cell,origin.above(46),Direction.NORTH,s.ownerId(),false);
            own.withCustomTemplate(CustomTemplateSnapshot.create("Grass clearance fixture",BuildingType.HOUSE,cell,Direction.NORTH,List.of(new BlueprintPlacement(cell,Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS)),List.of(new BlueprintEntrance(cell,Direction.NORTH))));
            var reserve=s.addBlueprint(BuildingType.HOUSE,cell,origin.above(47),Direction.NORTH,s.ownerId(),false);
            var p=new TerrainProject(reserve.id(),"unrelated public reservation",cell,List.of(new TerrainProject.Step(cell,Blocks.AIR.defaultBlockState(),Blocks.SHORT_GRASS.defaultBlockState())));p.publicGround=true;reserve.withTerrainProject(p);
            level.setBlock(cell,Blocks.SHORT_GRASS.defaultBlockState(),2);own.addClearanceTarget(cell,level.getBlockState(cell));entity.setPos(cell.getX()-1.5,cell.getY(),cell.getZ()+.5);
            var clear=BlueprintService.clearAt(level,SettlementSavedData.get(level),s,own,cell,entity,ItemStack.EMPTY);
            check(clear.success() && level.getBlockState(cell).isAir(),"public_reservation_does_not_protect_site_grass:"+clear.reason()+":inside="+s.contains(cell),out);
            var lower=Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF,DoubleBlockHalf.LOWER);var upper=lower.setValue(DoublePlantBlock.HALF,DoubleBlockHalf.UPPER);
            level.setBlock(cell,lower,2);level.setBlock(cell.above(),upper,2);own.addClearanceTarget(cell,lower);
            clear=BlueprintService.clearAt(level,SettlementSavedData.get(level),s,own,cell,entity,ItemStack.EMPTY);
            check(clear.success() && level.getBlockState(cell).isAir() && level.getBlockState(cell.above()).isAir(),"double_grass_cleared_together",out);
            var cut=cell.east(3);level.setBlock(cut,Blocks.STONE.defaultBlockState(),2);
            check(WorkerAccessService.allowed(level,s,cut),"ordinary_stone_cut_allowed",out);
            level.setBlock(cut,Blocks.BEDROCK.defaultBlockState(),2);check(!WorkerAccessService.allowed(level,s,cut),"bedrock_cut_rejected",out);
            level.setBlock(cut,Blocks.BARREL.defaultBlockState(),2);check(!WorkerAccessService.allowed(level,s,cut),"container_cut_rejected",out);
            level.setBlock(cut,Blocks.WATER.defaultBlockState(),2);check(!WorkerAccessService.allowed(level,s,cut),"river_cut_rejected",out);
            level.setBlock(cut,Blocks.STONE.defaultBlockState(),2);RoadSpaceData.get(level).addAll(List.of(cut));check(!WorkerAccessService.allowed(level,s,cut),"registered_road_cut_rejected",out);RoadSpaceData.get(level).remove(cut);
            c.workerAccess.block=cut;c.workerAccess.goal=cut.east();c.workerAccess.original="minecraft:stone";c.workerAccess.ticks=12;
            saved=CitizenRecord.load(c.save(level.registryAccess()),level.registryAccess());check(cut.equals(saved.workerAccess.block) && saved.workerAccess.ticks==12,"cut_progress_saved",out);c.clearWork();check(c.workerAccess.block==null,"cancel_clears_cut",out);
        }finally{if(entity!=null)entity.discard();before.forEach((p,state)->level.setBlock(p,state,2));}
        dispatch(level,s,List.of(h2,h3,h4,other),project,road,out);
        return "PASS "+out.size()+" detached checks: "+String.join(", ",out);
    }
    /** Exercise the production dispatcher itself; detached records never become native residents. */
    private static void dispatch(ServerLevel level,SettlementRecord s,List<BlueprintRecord> parents,TerrainProject foundation,TerrainProject shared,List<String> out){
        foundation.completed=true;shared.mark(0);shared.completed=true;
        for(var b:parents){
            var placements=new ArrayList<BlueprintPlacement>();
            for(int i=0;i<8;i++)placements.add(new BlueprintPlacement(b.anchor().offset(i%4,0,i/4),Blocks.OAK_PLANKS.defaultBlockState(),Items.OAK_PLANKS));
            b.withCustomTemplate(CustomTemplateSnapshot.create("Detached dispatcher",b.type(),b.anchor(),Direction.NORTH,placements,List.of(new BlueprintEntrance(b.anchor(),Direction.NORTH))));
            s.terrain().surveyed.add(b.id());var route=new AutoRoadState.Route(b.id(),b.anchor(),List.of(b.anchor()));route.accessConnected=true;s.autoRoads().routes.put("ready-"+b.id(),route);s.autoRoads().accessSurveyed.add(b.id());
        }
        while(s.citizens().size()<12)s.addMigrant();
        var residents=new ArrayList<>(s.citizens());for(var c:residents){c.clearWork();c.setManualAssignment(CitizenProfession.WORKER,null);c.insertIntoInventory(new ItemStack(Items.OAK_PLANKS,16));}
        var claimed=new HashSet<BlockPos>();
        try{
            var assign=CitizenWorkService.class.getDeclaredMethod("assignBlueprint",ServerLevel.class,SettlementSavedData.class,SettlementRecord.class,CitizenRecord.class,Set.class,boolean.class,boolean.class,boolean.class);assign.setAccessible(true);
            for(int i=0;i<11;i++){
                var c=residents.get(i);boolean assigned=(boolean)assign.invoke(null,level,SettlementSavedData.get(level),s,c,claimed,false,false,true);
                if(!assigned)throw new IllegalStateException("P0 dispatch missing worker "+i);WorkTaskService.reserve(s,c);
                if(i==3)check(parents.stream().allMatch(b->WorkTaskService.assigned(s,b.id())==1),"dispatcher_spreads_first_four_workers",out);
            }
            check(WorkTaskService.assigned(s,parents.get(0).id())==2 && WorkTaskService.assigned(s,parents.get(1).id())==3
                    && WorkTaskService.assigned(s,parents.get(2).id())==4 && WorkTaskService.assigned(s,parents.get(3).id())==2,"dispatcher_fills_2_3_4_2_slots",out);
            check(!(boolean)assign.invoke(null,level,SettlementSavedData.get(level),s,residents.get(11),claimed,false,false,true),"priority_pass_stops_at_default_slots",out);
            check(residents.subList(0,11).stream().allMatch(c->WorkTaskService.task(s,c).priority()==0),"public_p2_does_not_take_priority_slots",out);
            for(var c:residents)c.clearWork();check(WorkTaskService.counts(s).isEmpty(),"dispatch_cancel_releases_all_slots",out);
        }catch(ReflectiveOperationException e){throw new IllegalStateException("Production dispatcher check",e);}
    }
}
