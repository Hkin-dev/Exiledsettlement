package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Explicit inputs exercise admission against real publication; no resident work credit. */
public final class OptionalWork91Checks {
    private static void check(boolean ok,String name,List<String> checks){if(!ok)throw new IllegalStateException(name);checks.add(name);}
    private static TerrainProject publicOrder(SettlementRecord s,List<TerrainProject.Step> steps){
        var p=new TerrainProject(UUID.randomUUID(),"explicit pending input",steps.getFirst().position(),steps);
        p.publicGround=true;p.dependencies.add(s.id());p.groundSurfaces.add(steps.getLast().position());s.terrain().index(p);return p;
    }
    public static String run(ServerLevel l){
        if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
        var core=new BlockPos(54000,250,54000);var data=SettlementSavedData.get(l);
        if(data.findByCore(core).isPresent())throw new IllegalStateException("FIXTURE_EXISTS");
        var s=data.createOrRecover(core,UUID.randomUUID(),"1000 block admission isolated inputs").value();
        s.terrain().enabled=true;s.autoRoads().enabled=true;s.decorations().enabled=false;
        var checks=new ArrayList<String>();var saved=new LinkedHashMap<BlockPos,BlockState>();
        var roadAt=core.east(6);for(int y=-2;y<=3;y++){var at=roadAt.above(y);saved.put(at,l.getBlockState(at));if(!l.getBlockState(at).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");}
        try{
            var steps=new ArrayList<TerrainProject.Step>();
            for(int i=0;i<1000;i++)steps.add(new TerrainProject.Step(core.offset(i%32-16,10,30+i/32),Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()));
            var old=publicOrder(s,steps);int oldCount=s.terrain().projects.size();
            var budget=OptionalWorkBudget.snapshot(s,1000);
            check(budget.pending()==1000 && budget.full(),"1000 distinct unfinished terrain blocks reach the ceiling",checks);
            var duplicate=List.of(new TerrainProject.Step(steps.getFirst().position(),Blocks.AIR.defaultBlockState(),Blocks.DIRT.defaultBlockState()),steps.getFirst());
            check(budget.fits(duplicate,null) && budget.pending()==1000,"dig then fill and shared position require only one existing block slot",checks);
            var at=core.west(6);var extra=List.of(new TerrainProject.Step(at,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()));
            check(!budget.fits(extra,null) && old.remaining()==1000 && !old.cancelled && old.abandoned.isEmpty(),"ceiling refuses new work without cancelling or marking old work",checks);
            var parent=s.addBlueprint(BuildingType.HOUSE,core,core.above(20),Direction.NORTH,s.ownerId(),false);
            var base=core.below();var childSite=s.addBlueprint(BuildingType.HOUSE,base,core.above(40),Direction.NORTH,s.ownerId(),false);
            var child=new TerrainProject(childSite.id(),"essential building base",base,List.of(new TerrainProject.Step(base,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));
            child.publicGround=true;child.dependencies.add(parent.id());childSite.withTerrainProject(child);s.terrain().index(child);
            check(OptionalWorkBudget.snapshot(s,1000).pending()==1000 && child.remaining()==1,"actual P0 building foundation remains independent of the optional quota",checks);
            l.setBlock(roadAt.below(2),Blocks.STONE.defaultBlockState(),2);
            var w=s.terrain().publicGround;var joint=w.save();joint.putInt("Phase",5);joint.putInt("HeightVersion",PublicGroundService.HEIGHT_VERSION);joint.putBoolean("RoadsIntegrated",true);joint.putInt("PlanCursor",17);
            var goals=new net.minecraft.nbt.ListTag();for(var pos:List.of(roadAt,roadAt.north(),roadAt.south(),roadAt.east(),roadAt.west())){
                var goal=new net.minecraft.nbt.CompoundTag();goal.putLong("Column",BlockPos.asLong(pos.getX(),0,pos.getZ()));goal.putInt("Y",roadAt.getY());goals.add(goal);
            }joint.put("Goals",goals);w.load(joint);
            var road=new AutoRoadState.Route(UUID.randomUUID(),roadAt,List.of(roadAt));s.autoRoads().routes.put("quota",road);
            check(!PublicGroundService.publishRoadColumn(l,s,road,roadAt) && s.terrain().projects.size()==oldCount+1
                    && w.planCursor==17 && road.earthworkCursor==0 && road.admitted.isEmpty() && l.getBlockState(roadAt).isAir(),
                    "real road publication pauses before releasing any work or moving publication cursors",checks);
            old.mark(0);old.mark(1);old.mark(2);
            check(PublicGroundService.publishRoadColumn(l,s,road,roadAt) && road.admitted.get(0)
                    && OptionalWorkBudget.snapshot(s,1000).pending()==999 && l.getBlockState(roadAt).isAir() && l.getBlockState(roadAt.below()).isAir(),
                    "draining three input slots resumes real pending support and paving publication below 1000, without placing blocks",checks);
            var loaded=SettlementRecord.load(s.save(l.registryAccess()),l.registryAccess());
            check(OptionalWorkBudget.snapshot(loaded,1000).pending()==999 && loaded.terrain().publicGround.planCursor==17
                    && loaded.autoRoads().routes.get("quota").admitted.get(0),"save reload preserves pending block count, road admission and paused cursor",checks);
            var legacy=new SettlementRecord(UUID.randomUUID(),"over ceiling existing input",core,s.ownerId());
            var excess=new ArrayList<>(steps);for(int i=0;i<30;i++)excess.add(new TerrainProject.Step(core.offset(i,20,70),Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()));
            var historic=publicOrder(legacy,excess);var over=OptionalWorkBudget.snapshot(legacy,1000);
            check(over.pending()==1030 && !over.fits(extra,null) && historic.remaining()==1030,"pre-existing excess drains intact instead of being truncated to the ceiling",checks);
            var isolated=new SettlementRecord(UUID.randomUUID(),"column retirement",core,s.ownerId());
            var a=core.west(10);var b=core.east(10);
            var multi=publicOrder(isolated,List.of(new TerrainProject.Step(a,Blocks.COBBLESTONE.defaultBlockState(),Blocks.AIR.defaultBlockState()),new TerrainProject.Step(b,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));
            multi.groundSurfaces.add(a);multi.mark(0);multi.retireColumn(a);isolated.terrain().index(multi);
            check(!multi.heightSuperseded && multi.remaining()==1 && b.equals(isolated.terrain().publicPlane(b)) && !multi.finished.get(1),
                    "replacing one column retains the other committed unfinished column and its work",checks);
            var restored=new TerrainState();restored.load(isolated.terrain().save());
            check(restored.supersededPaid(a,Blocks.COBBLESTONE.defaultBlockState()) && restored.projects.get(multi.id).remaining()==1,
                    "retired actual paid material and other pending work survive reload",checks);
            multi.heightSuperseded=true;var before=multi.save();isolated.terrain().index(multi);
            check(!multi.heightSuperseded && multi.cursor==before.getInt("Cursor") && multi.finished.equals(BitSet.valueOf(before.getLongArray("Finished")))
                    && multi.abandoned.equals(BitSet.valueOf(before.getLongArray("Abandoned"))),"earlier whole-carrier retirement is repaired without changing any pending or completed step",checks);
            multi.retireColumn(b);isolated.terrain().index(multi);
            check(multi.heightSuperseded && isolated.terrain().unfinishedProjects().isEmpty(),"only retiring the final column obsoletes the whole carrier",checks);
            return "PASS "+checks.size()+": "+String.join("; ",checks)+"; explicit fixture inputs restored; no resident construction credited";
        }finally{s.deactivate();saved.forEach((at,state)->l.setBlock(at,state,2));data.touch();}
    }
    private OptionalWork91Checks(){}
}
