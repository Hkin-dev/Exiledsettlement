package com.hkin.exiledsettlement.debug;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
public final class WidePit127Checks{
 private static void check(boolean ok,String name,List<String> out){if(!ok)throw new IllegalStateException(name);out.add(name);}
 public static String run(ServerLevel l){
  if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");var core=new BlockPos(61200,250,61200);var data=SettlementSavedData.get(l);if(data.findByCore(core).isPresent())throw new IllegalStateException("FIXTURE_EXISTS");
  var s=data.createOrRecover(core,UUID.randomUUID(),"Wide pit failed ordinary journey").value();var saved=new LinkedHashMap<BlockPos,BlockState>();var out=new ArrayList<String>();SettlementCitizenEntity e=null;
  try{
   var feet=core.east(6);var bank=feet.east().above(2);
   for(var raw:BlockPos.betweenClosed(feet.offset(-2,-1,-2),feet.offset(4,4,2))){var p=raw.immutable();if(!l.getBlockState(p).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");saved.put(p,l.getBlockState(p));}
   for(var p:List.of(feet,feet.north(),feet.south(),feet.west()))l.setBlock(p.below(),Blocks.STONE.defaultBlockState(),2);
   for(int y=-1;y<=1;y++)l.setBlock(feet.east().above(y),Blocks.STONE.defaultBlockState(),2);
   for(int x=2;x<=4;x++)for(int y=-1;y<=1;y++)l.setBlock(feet.offset(x,y,0),Blocks.STONE.defaultBlockState(),2);
   var c=s.citizens().iterator().next();c.setManualAssignment(CitizenProfession.WORKER,null);c.insertIntoInventory(new ItemStack(Items.DIRT,2));c.startTask(CitizenWorkState.TO_EQUIPMENT,core,null,null);
   e=ModEntities.SETTLEMENT_CITIZEN.get().create(l);e.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());e.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());e.moveTo(feet.getX()+.5,feet.getY(),feet.getZ()+.5,0,0);e.setOnGround(true);l.addFreshEntity(e);data.updateProjection(s,c,e.getUUID(),feet);
   check(e.canStandAtPosition(feet.west()) && PitEscapeService.preview(l,s,c,e)==null,"ordinary preview preserves walkable flat neighbours without automatic filling",out);
   var path=e.getNavigation().createPath(bank,0);check(path==null || !path.canReach(),"ordinary navigator cannot climb the real two-block bank",out);
   check(PitEscapeService.request(l,s,c,e) && bank.equals(c.pitEscape.exit),"failed-journey fallback recognises a raised bank despite flat cells inside the pit",out);
   for(int tick=0;tick<40;tick++){c.pitEscape.lastTick=l.getGameTime()-10;PitEscapeService.tick(l,data,s,c,e);e.tick();}
   check(bank.equals(e.navigationFeetPosition()) && l.noCollision(e),"actual actor fills and steps out to the real bank",out);
   check(c.pitReserve().isEmpty() && c.inventory().stream().filter(stack->stack.is(Items.DIRT)).mapToInt(ItemStack::getCount).sum()==0 && l.getBlockState(feet).is(Blocks.DIRT) && l.getBlockState(feet.above()).is(Blocks.DIRT),"exactly two existing dirt blocks are paid and retained",out);
   check(s.terrain().escapeSupport(feet,l.getBlockState(feet)) && s.terrain().escapeSupport(feet.above(),l.getBlockState(feet.above())) && s.terrain().projects.isEmpty(),"real support evidence is preserved without a recovery order",out);
   check(c.workState()==CitizenWorkState.TO_EQUIPMENT && core.equals(c.targetPosition()),"wide-pit escape keeps the original supply task",out);
   return "PASS "+out.size()+": "+String.join("; ",out)+"; explicit actor input restored; autonomous scene escape separate";
  }finally{if(e!=null)e.discard();saved.forEach((p,state)->l.setBlock(p,state,2));s.deactivate();data.touch();}
 }
 private WidePit127Checks(){}
}
