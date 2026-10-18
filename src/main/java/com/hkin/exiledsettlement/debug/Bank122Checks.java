package com.hkin.exiledsettlement.debug;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
public final class Bank122Checks{
 private static void check(boolean ok,String name,List<String> checks){if(!ok)throw new IllegalStateException(name);checks.add(name);}
 public static String run(ServerLevel l){
  if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
  var core=new BlockPos(61000,250,61000);var data=SettlementSavedData.get(l);if(data.findByCore(core).isPresent())throw new IllegalStateException("FIXTURE_EXISTS");
  var s=data.createOrRecover(core,UUID.randomUUID(),"Protected one-block bank input").value();var saved=new LinkedHashMap<BlockPos,BlockState>();var checks=new ArrayList<String>();SettlementCitizenEntity e=null;
  try{
   var feet=core.east(6);var bank=feet.west().above();
   for(var raw:BlockPos.betweenClosed(feet.offset(-2,-1,-2),feet.offset(2,4,2))){var p=raw.immutable();if(!l.getBlockState(p).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");saved.put(p,l.getBlockState(p));}
   l.setBlock(feet.below(),Blocks.STONE_BRICKS.defaultBlockState(),2);l.setBlock(bank.below(),Blocks.OAK_LEAVES.defaultBlockState(),2);
   for(var d:List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST))for(int h=0;h<=1;h++)l.setBlock(feet.relative(d).above(h),Blocks.STONE.defaultBlockState(),2);
   var b=s.addBuilding(BuildingType.HOUSE,feet,Direction.NORTH,feet.offset(-1,-1,0),feet,0,1);
   b.withCustomTemplate(CustomTemplateSnapshot.create("existing protected footing",BuildingType.HOUSE,feet,Direction.NORTH,
      List.of(new BlueprintPlacement(feet.below(),Blocks.STONE_BRICKS.defaultBlockState(),Items.STONE_BRICKS),new BlueprintPlacement(bank.below(),Blocks.OAK_LEAVES.defaultBlockState(),Items.OAK_LEAVES)),List.of(new BlueprintEntrance(feet,Direction.NORTH))));b.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of(feet));
   var c=s.citizens().iterator().next();c.setManualAssignment(CitizenProfession.WORKER,null);c.startTask(CitizenWorkState.TO_EQUIPMENT,core,null,null);
   e=ModEntities.SETTLEMENT_CITIZEN.get().create(l);e.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());e.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
   e.moveTo(feet.getX()+.5,feet.getY(),feet.getZ()+.5,0,0);e.setOnGround(true);l.addFreshEntity(e);data.updateProjection(s,c,e.getUUID(),feet);
   check(PitEscapeService.observation(l,s,c,e).contains("footFillAllowed=false"),"formal interior correctly forbids a pillar at the actor's feet",checks);
   check(bank.equals(PitEscapeService.preview(l,s,c,e)),"existing one-block bank is selected without any filler or tool",checks);
   check(PitEscapeService.request(l,s,c,e) && e.hasActiveConstructionMovement(),"bank escape starts the original collision-checked bounded controller",checks);
   double largest=0;for(int tick=0;tick<24;tick++){double x=e.getX(),y=e.getY(),z=e.getZ();e.tick();largest=Math.max(largest,Math.sqrt(Math.pow(e.getX()-x,2)+Math.pow(e.getY()-y,2)+Math.pow(e.getZ()-z,2)));PitEscapeService.tick(l,data,s,c,e);}
   check(bank.equals(e.navigationFeetPosition()) && l.noCollision(e),"actual entity steps onto the existing bank with clear body geometry: feet="+e.navigationFeetPosition()+",xyz="+e.getX()+"/"+e.getY()+"/"+e.getZ()+",collisionFree="+l.noCollision(e)+",movement="+e.constructionMovementDiagnostics()+",largest="+largest,checks);
   check(largest<.5,"actual escape consists of bounded physical substeps",checks);
   check(c.pitReserve().isEmpty() && l.getBlockState(feet).isAir() && l.getBlockState(bank.below()).is(Blocks.OAK_LEAVES),"bank movement consumes no material and preserves the formal floor and leaves",checks);
   check(s.terrain().projects.isEmpty() && c.workState()==CitizenWorkState.TO_EQUIPMENT && core.equals(c.targetPosition()),"physical escape retains the real supply task without a construction or recovery order",checks);
   return "PASS "+checks.size()+": "+String.join("; ",checks)+"; explicit actor input restored; autonomous trapped resident separate";
  }finally{if(e!=null)e.discard();saved.forEach((p,state)->l.setBlock(p,state,2));s.deactivate();data.touch();}
 }
 private Bank122Checks(){}
}
