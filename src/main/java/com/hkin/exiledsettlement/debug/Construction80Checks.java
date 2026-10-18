package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import java.util.*;

/** Detached regression scenarios; fixture edits are restored and never count as resident work. */
public final class Construction80Checks {
    private static void check(boolean ok,String name,List<String> out){if(!ok)throw new IllegalStateException(name);out.add(name);}
    public static String run(ServerLevel level){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(33504,250,33504);var s=new SettlementRecord(UUID.randomUUID(),"Construction80 detached",core,UUID.randomUUID());
        var out=new ArrayList<String>();var before=new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        java.util.function.BiConsumer<BlockPos,net.minecraft.world.level.block.state.BlockState> put=(p,state)->{before.putIfAbsent(p,level.getBlockState(p));level.setBlock(p,state,2);};
        try {
            var at=core.east(3);put.accept(at,Blocks.DIRT.defaultBlockState());
            var p=new TerrainProject(UUID.randomUUID(),"Required fill",at,List.of(new TerrainProject.Step(at,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));
            s.terrain().index(p);p.mark(0);p.completed=true;
            check(!WorkerAccessService.allowed(level,s,at),"completed_required_fill_not_cut",out);
            p.completed=false;var site=s.addBlueprint(BuildingType.HOUSE,at,core.above(40),Direction.NORTH,s.ownerId(),false);site.withTerrainProject(p);
            var c=s.citizens().iterator().next();p.dependencies.add(site.id());c.startTask(CitizenWorkState.TO_BLUEPRINT,at,null,site.id());WorkTaskService.bind(s,c);
            check(!WorkerAccessService.allowed(level,s,at,c),"same_mother_cannot_undo_fill",out);
            var reloaded=new SettlementRecord(UUID.randomUUID(),"Reload geometry",core,UUID.randomUUID());reloaded.terrain().load(s.terrain().save());
            check(!WorkerAccessService.allowed(level,reloaded,at),"saved_fill_still_protected",out);
            p.heightSuperseded=true;p.completed=true;
            check(WorkerAccessService.allowed(level,s,at),"superseded_fill_can_be_cut",out);
            s.terrain().index(p);sharedTarget(s,at,at.getY(),0);
            check(!WorkerAccessService.allowed(level,s,at),"superseded_paid_support_below_shared_plane_kept",out);
            put.accept(at,Blocks.STONE.defaultBlockState());
            check(!WorkerAccessService.allowed(level,s,at),"material_revision_keeps_needed_support",out);
            put.accept(at,Blocks.DIRT.defaultBlockState());
            sharedTarget(s,at,at.getY()-1,1);
            check(!WorkerAccessService.allowed(level,s,at),"paid_support_kept_during_resurvey",out);
            sharedTarget(s,at,at.getY()-1,0);
            check(WorkerAccessService.allowed(level,s,at),"obsolete_above_lower_shared_plane_released",out);
            p.heightSuperseded=false;p.cancelled=true;
            check(WorkerAccessService.allowed(level,s,at),"cancelled_fill_releases_ground",out);
            var ordinary=at.east(2);put.accept(ordinary,Blocks.STONE.defaultBlockState());
            check(WorkerAccessService.allowed(level,s,ordinary),"ordinary_local_cut_kept",out);
            sharedTarget(s,ordinary,ordinary.getY(),0);
            check(!WorkerAccessService.allowed(level,s,ordinary),"natural_support_under_shared_plane_kept",out);
            put.accept(ordinary.above(),Blocks.STONE.defaultBlockState());
            check(WorkerAccessService.allowed(level,s,ordinary.above()),"ordinary_obstacle_above_shared_plane_can_be_cut",out);
            var grass=at.south();put.accept(grass,Blocks.SHORT_GRASS.defaultBlockState());
            check(WorkerAccessService.allowed(level,s,grass),"grass_not_locked_by_fill",out);

            var anchor=core.south(20);var t=BlueprintTemplates.create(BuildingType.WAREHOUSE,anchor,Direction.EAST,7);
            var b=s.addBuilding(BuildingType.WAREHOUSE,anchor,Direction.EAST,t.minCorner(),t.maxCorner(),t.transitionMargin(),7);
            b.updateFunction(true,BuildingStatus.OPERATIONAL,200000,List.of());
            var e=t.entrances().getFirst();var end=RoadEntranceService.outside(t,e);
            for(var floor=end;!floor.equals(e.position().below().relative(e.outward().getOpposite()));floor=floor.relative(e.outward().getOpposite())){
                put.accept(floor,floor.equals(end)?Blocks.DIRT.defaultBlockState():Blocks.STONE_BRICKS.defaultBlockState());put.accept(floor.above(),Blocks.AIR.defaultBlockState());put.accept(floor.above(2),Blocks.AIR.defaultBlockState());
            }
            var canonical=AutoRoadService.entrances(level,s,b);
            check(canonical.contains(end),"endpoint_reaches_first_outside_cell",out);
            check(canonical.stream().allMatch(a->a.getY()==e.position().getY()-1),"endpoint_keeps_doorway_elevation",out);
            put.accept(end,Blocks.AIR.defaultBlockState());put.accept(end.below(),Blocks.AIR.defaultBlockState());
            put.accept(end.below(2),Blocks.DIRT_PATH.defaultBlockState());put.accept(end.below(3),Blocks.STONE.defaultBlockState());
            var lower=new AutoRoadState.Route(b.id(),end.below(2),List.of(end.below(2)));s.autoRoads().routes.put("lower",lower);
            RoadSpaceData.get(level).addAll(List.of(end.below(2)));
            try{check(RoadEarthworkService.plannable(level,s,end),"lower_automatic_path_can_receive_paid_support",out);
                RoadSpaceData.get(level).pinManual(List.of(end.below(2)));
                check(!RoadEarthworkService.plannable(level,s,end),"manual_road_height_stays_protected",out);
            }finally{RoadSpaceData.get(level).remove(end.below(2));s.autoRoads().routes.remove("lower");}
            put.accept(end,Blocks.DIRT.defaultBlockState());
            var bad=end.relative(e.outward()).below(2);put.accept(bad,Blocks.DIRT_PATH.defaultBlockState());
            var old=new AutoRoadState.Route(b.id(),bad,List.of(bad));old.centerline=old.cells;old.progress=1;old.completed.set(0);
            check(!AutoRoadService.parentValid(level,s,old) && !RoadEarthworkService.accessBlocker(level,s,old).isEmpty(),"two_block_gap_not_connected",out);
            s.autoRoads().routes.put("old",old);s.autoRoads().accessSurveyed.add(b.id());
            check(!AutoRoadService.accessReady(s,b.id()),"paving_progress_does_not_fake_access",out);
            var route=new AutoRoadState.Route(b.id(),end,List.of(end));route.centerline=route.cells;
            check(RoadEarthworkService.accessBlocker(level,s,route).isEmpty(),"real_threshold_connection_passes",out);
            var inner=end.relative(e.outward().getOpposite());put.accept(inner,Blocks.AIR.defaultBlockState());
            check(!RoadEntranceService.blocker(level,s,route).isEmpty(),"missing_threshold_floor_rejected",out);
            put.accept(inner,Blocks.STONE_BRICKS.defaultBlockState());put.accept(inner.above(),Blocks.STONE.defaultBlockState());
            check(!RoadEntranceService.blocker(level,s,route).isEmpty(),"blocked_doorway_rejected",out);
            put.accept(inner.above(),Blocks.AIR.defaultBlockState());
            // Replanning may cost more than the old isolated fragment. It must
            // still correct the doorstep, while preserving the old world block.
            check(AdaptiveRoadService.accept(level,s,"old",route),"required_endpoint_replan_accepted",out);
            check(level.getBlockState(bad).is(Blocks.DIRT_PATH),"replacement_keeps_old_surface_until_connected",out);
            var observer=com.hkin.exiledsettlement.registry.ModEntities.SETTLEMENT_CITIZEN.get().create(level);
            try{
                put.accept(ordinary,Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.EAST));
                observer.setPos(ordinary.getX()-2,ordinary.getY(),ordinary.getZ()+.5);
                check(RoadSurfaceProfile.visibleSurface(level,observer,ordinary),"stair_solid_face_contact",out);
                observer.setPos(ordinary.getX()-8,ordinary.getY(),ordinary.getZ()+.5);
                check(!RoadSurfaceProfile.visibleSurface(level,observer,ordinary),"stair_contact_does_not_extend_reach",out);
                observer.setPos(ordinary.getX()-2,ordinary.getY(),ordinary.getZ()+.5);
                for(int z=-1;z<=1;z++)for(int y=0;y<=3;y++)put.accept(ordinary.offset(-1,y,z),Blocks.STONE.defaultBlockState());
                check(!RoadSurfaceProfile.visibleSurface(level,observer,ordinary),"stair_contact_respects_opaque_obstacle",out);
            }finally{observer.discard();}
            return "PASS "+out.size()+" construction checks: "+String.join(", ",out);
        }finally{before.forEach((p,state)->level.setBlock(p,state,2));}
    }
    private static void sharedTarget(SettlementRecord s,BlockPos p,int y,int phase){
        var tag=s.terrain().publicGround.save();tag.putInt("HeightVersion",PublicGroundService.HEIGHT_VERSION);tag.putInt("Phase",phase);tag.putBoolean("RoadsIntegrated",phase==0);
        var goals=new net.minecraft.nbt.ListTag();var entry=new net.minecraft.nbt.CompoundTag();entry.putLong("Column",p.atY(0).asLong());entry.putInt("Y",y);goals.add(entry);tag.put("Goals",goals);
        s.terrain().publicGround.load(tag);
    }
    public static String inspect(ServerLevel level,SettlementRecord s){
        var out=new com.google.gson.JsonArray();
        for(var b:s.buildings())if(b.active() && b.type()==BuildingType.WAREHOUSE){
            var t=BlueprintTemplates.createForBuilding(b);var item=new com.google.gson.JsonObject();item.addProperty("building",b.id().toString());
            var ends=new com.google.gson.JsonArray();
            for(var e:t.entrances()) {var p=RoadEntranceService.outside(t,e);var j=new com.google.gson.JsonObject();j.addProperty("position",p.toShortString());
                j.addProperty("plannable",RoadEarthworkService.plannable(level,s,p));j.addProperty("permitted",RoadEarthworkService.permitted(level,s,p));
                j.addProperty("state",level.getBlockState(p).toString());j.addProperty("above",level.getBlockState(p.above()).toString());
                j.addProperty("checkpoint",String.valueOf(PublicGroundService.checkpointAt(s,p)));j.addProperty("goal",String.valueOf(PublicGroundService.targetAt(s,p)));
                j.addProperty("publicPlane",String.valueOf(s.terrain().publicPlane(p)));ends.add(j);}
            item.add("endpoints",ends);out.add(item);
        }
        return out.toString();
    }
    /** Explicit finite-stock setup; all later fills, stair placement and paving are native work. */
    public static String live(ServerLevel level){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(33840,249,33840);var data=SettlementSavedData.get(level);
        data.findByCore(new BlockPos(33808,249,33808)).filter(old->old.name().equals("Construction80 doorway native work")).ifPresent(SettlementRecord::deactivate);
        if(data.findByCore(core).isPresent())throw new IllegalStateException("FIXTURE_ALREADY_EXISTS");
        for(var p:BlockPos.betweenClosed(core.offset(-5,-2,-4),core.offset(7,4,4)))if(!level.getBlockState(p).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");
        for(int x=-5;x<=7;x++)for(int z=-4;z<=4;z++){
            level.setBlock(core.offset(x,-2,z),Blocks.STONE.defaultBlockState(),2);level.setBlock(core.offset(x,-1,z),Blocks.DIRT.defaultBlockState(),2);
        }
        level.setBlock(core,com.hkin.exiledsettlement.registry.ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),2);
        var s=data.createOrRecover(core,UUID.randomUUID(),"Construction80 doorway native work").value();s.terrain().enabled=false;s.autoRoads().enabled=true;
        var floor=core.east(4);level.setBlock(floor,Blocks.STONE_BRICKS.defaultBlockState(),2);
        var entrance=new BlueprintEntrance(floor.above(),Direction.WEST);
        var placements=List.of(new BlueprintPlacement(floor,Blocks.STONE_BRICKS.defaultBlockState(),net.minecraft.world.item.Items.STONE_BRICKS));
        var b=s.addBuilding(BuildingType.WAREHOUSE,floor.above(),Direction.EAST,floor,floor.above(3),0,7);
        b.withCustomTemplate(CustomTemplateSnapshot.create("Native doorstep fixture",BuildingType.WAREHOUSE,floor,Direction.EAST,placements,List.of(entrance)));
        b.updateFunction(true,BuildingStatus.OPERATIONAL,200000,List.of());
        var end=core.east(3);var line=List.of(core.east().below(),core.east(2),end);
        var route=new AutoRoadState.Route(b.id(),end,line);route.centerline=line;route.width=3;route.areaOptimized=true;route.adaptiveVersion=AdaptiveRoadService.VERSION;route.reviewAfter=level.getGameTime()+24000;
        s.autoRoads().routes.put("fixture-door",route);s.autoRoads().accessSurveyed.add(b.id());
        int i=0;
        for(var c:s.citizens()){
            c.clearWork();c.setManualAssignment(i==0?CitizenProfession.WORKER:CitizenProfession.TRAVELING_MERCHANT,null);c.updateProjection(null,core.offset(-2,0,-2+i));
            if(i==0){c.insertIntoInventory(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT,16));
                c.insertIntoInventory(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE_STAIRS,8));
                c.insertIntoInventory(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SHOVEL));
                c.insertIntoInventory(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));}
            i++;
        }
        ForceWorkDebugService.set(level,s,true);data.touch();CitizenProjectionService.ensureSettlementCitizens(level,s);SaveAcceptanceService.load(level,s,2);
        return "OK settlement="+s.id()+"; three-cell route rises one block to real doorway; finite 16 dirt + 8 stairs; setup blocks excluded from native actions";
    }
}
