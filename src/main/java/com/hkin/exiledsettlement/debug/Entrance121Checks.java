package com.hkin.exiledsettlement.debug;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import java.util.*;
public final class Entrance121Checks{
 private static void check(boolean ok,String name,List<String> out){if(!ok)throw new IllegalStateException(name);out.add(name);}
 public static String run(ServerLevel l){
  if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
  var core=new BlockPos(60800,250,60800);var data=SettlementSavedData.get(l);if(data.findByCore(core).isPresent())throw new IllegalStateException("FIXTURE_EXISTS");
  var s=data.createOrRecover(core,UUID.randomUUID(),"Original terrain in completed porch").value();s.autoRoads().enabled=true;
  var saved=new LinkedHashMap<BlockPos,BlockState>();var checks=new ArrayList<String>();var door=core.east(6);
  try{
   for(var raw:BlockPos.betweenClosed(core.offset(4,-1,-5),core.offset(8,3,1))){var p=raw.immutable();if(!l.getBlockState(p).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");saved.put(p,l.getBlockState(p));}
   var placements=new ArrayList<BlueprintPlacement>();
   for(int x=-1;x<=1;x++)for(int z=-3;z<=0;z++){var p=door.offset(x,-1,z);l.setBlock(p,Blocks.STONE_BRICKS.defaultBlockState(),2);placements.add(new BlueprintPlacement(p,Blocks.STONE_BRICKS.defaultBlockState(),Items.STONE_BRICKS));}
   for(int h=0;h<=1;h++){var p=door.above(h);var state=Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.HALF,h==0?DoubleBlockHalf.LOWER:DoubleBlockHalf.UPPER);l.setBlock(p,state,2);placements.add(new BlueprintPlacement(p,state,Items.SPRUCE_DOOR));}
   var lamp=door.north().above();l.setBlock(lamp,Blocks.LANTERN.defaultBlockState(),2);placements.add(new BlueprintPlacement(lamp,Blocks.LANTERN.defaultBlockState(),Items.LANTERN));
   var b=s.addBuilding(BuildingType.HOUSE,door,Direction.NORTH,door.offset(-1,-1,-3),door.offset(1,2,0),0,1);
   b.withCustomTemplate(CustomTemplateSnapshot.create("porch protection",BuildingType.HOUSE,door,Direction.NORTH,placements,List.of(new BlueprintEntrance(door,Direction.NORTH))));b.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of(door));
   var t=BlueprintTemplates.createForBuilding(b);var outside=RoadEntranceService.outside(t,t.entrances().getFirst());
   var road=new AutoRoadState.Route(b.id(),outside,List.of(outside.north(),outside));s.autoRoads().routes.put("porch",road);
   var obstacle=door.north(2);l.setBlock(obstacle,Blocks.GRASS_BLOCK.defaultBlockState(),2);l.setBlock(obstacle.above(),Blocks.DIRT.defaultBlockState(),2);
   var container=door.north(3);l.setBlock(container,Blocks.BARREL.defaultBlockState(),2);
   check(RoadEntranceService.prepare(l,s,road,OptionalWorkBudget.snapshot(s,0))==null && s.terrain().unfinishedProjects().isEmpty(),"full optional quota refuses new porch work without changing terrain",checks);
   var p=RoadEntranceService.prepare(l,s,road,OptionalWorkBudget.snapshot(s));
   check(p!=null && p.steps.size()==2 && p.steps.stream().allMatch(step->step.target().isAir()),"only original ordinary obstruction receives two real clearance steps",checks);
   check(p.road && p.entranceAccess && p.dependencies.contains(b.id()) && p.dependencies.contains(road.id),"porch clearance retains formal building and actual road ownership",checks);
   check(p.steps.getFirst().position().equals(obstacle.above()) && p.steps.getLast().position().equals(obstacle),"original porch obstruction clears from top to bottom",checks);
   check(l.getBlockState(obstacle).is(Blocks.GRASS_BLOCK) && !p.done() && !p.cancelled,"publication neither digs nor cancels or completes the real obstacle",checks);
   check(p.steps.stream().noneMatch(step->step.position().equals(lamp) || step.position().equals(container)) && l.getBlockState(lamp).is(Blocks.LANTERN) && l.getBlockState(container).is(Blocks.BARREL),"approved lantern and unrelated container remain untouched and unplanned",checks);
   check(p.steps.stream().allMatch(step->TerrainService.guard(l,s,s.blueprint(p.id),step).isEmpty()),"registered building-owned natural clearance passes the real terrain protection guard",checks);
   var loaded=TerrainProject.load(p.save());check(loaded.entranceAccess && loaded.road && loaded.dependencies.equals(p.dependencies) && loaded.remaining()==2,"saved porch order preserves ownership and actual unfinished work",checks);
   return "PASS "+checks.size()+": "+String.join("; ",checks)+"; explicit inputs restored; real resident clearance separate";
  }finally{saved.forEach((p,state)->l.setBlock(p,state,2));s.deactivate();data.touch();}
 }
 private Entrance121Checks(){}
}
