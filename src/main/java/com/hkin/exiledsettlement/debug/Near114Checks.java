package com.hkin.exiledsettlement.debug;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.registry.*;
import com.hkin.exiledsettlement.road.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
/** Explicit occluded workface inputs; paid transactions, never ten-building credit. */
public final class Near114Checks {
 private static void check(boolean ok,String label,List<String> checks){if(!ok)throw new IllegalStateException(label);checks.add(label);}
 public static String run(ServerLevel l)throws Exception {
  if(!SaveAcceptanceService.enabled())throw new IllegalStateException("TEST_ONLY");
  var core=new BlockPos(60200,250,60200);var data=SettlementSavedData.get(l);
  if(data.findByCore(core).isPresent())throw new IllegalStateException("FIXTURE_EXISTS");
  var s=data.createOrRecover(core,UUID.randomUUID(),"Near construction occluded inputs").value();
  s.upgradeLevel();s.upgradeLevel();s.autoRoads().enabled=true;s.autoRoads().workReady=true;s.terrain().enabled=true;s.decorations().enabled=false;
  var saved=new LinkedHashMap<BlockPos,BlockState>();var checks=new ArrayList<String>();SettlementCitizenEntity e=null;
  try {
   for(var raw:BlockPos.betweenClosed(core.offset(-2,-2,-3),core.offset(12,4,3))){var at=raw.immutable();if(!l.getBlockState(at).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");saved.put(at,l.getBlockState(at));}
   for(int x=-2;x<=12;x++)for(int z=-3;z<=3;z++)l.setBlock(core.offset(x,-1,z),Blocks.DIRT.defaultBlockState(),2);
   for(int y=0;y<=3;y++)for(int z=-3;z<=3;z++)l.setBlock(core.offset(1,y,z),Blocks.STONE.defaultBlockState(),2);
   var c=s.citizens().iterator().next();c.setManualAssignment(CitizenProfession.WORKER,null);c.insertIntoInventory(new ItemStack(Items.IRON_SHOVEL));
   e=ModEntities.SETTLEMENT_CITIZEN.get().create(l);e.setPos(core.getX()+.5,250,core.getZ()+.5);e.setOnGround(true);
   e.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG,s.id().toString());e.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG,c.id().toString());
   if(!l.addFreshEntity(e))throw new IllegalStateException("ENTITY_SPAWN_FAILED");data.updateProjection(s,c,e.getUUID(),e.blockPosition());
   var target=core.east(3);var can=CitizenWorkService.class.getDeclaredMethod("canConstructFromCurrentPosition",SettlementCitizenEntity.class,BlockPos.class);can.setAccessible(true);
   check(!CitizenInteractionRange.visible(e,target),"actual solid wall blocks the old ray",checks);
   check((boolean)can.invoke(null,e,target),"near ordinary construction proceeds through an occluding wall",checks);
   check(!(boolean)can.invoke(null,e,target.east(7)),"far ordinary construction still requires real arrival",checks);
   check(!(boolean)can.invoke(null,e,core),"near worker body overlap is still refused",checks);
   check(CitizenWorkService.constructionStationInRange(l,core,target,e) && !CitizenWorkService.constructionStationInRange(l,core,target.east(7),e),"scaffold planning uses original reach without visibility",checks);
   var site=s.addBlueprint(BuildingType.HOUSE,target,Direction.NORTH,s.ownerId(),false);
   site.withCustomTemplate(CustomTemplateSnapshot.create("occluded paid wall",BuildingType.HOUSE,target,Direction.NORTH,List.of(new BlueprintPlacement(target,Blocks.SPRUCE_PLANKS.defaultBlockState(),Items.SPRUCE_PLANKS,BlueprintPlacementMode.STRUCTURE)),List.of(new BlueprintEntrance(target,Direction.NORTH))));s.terrain().surveyed.add(site.id());
   var stack=new ItemStack(Items.SPRUCE_PLANKS,2);var result=BlueprintService.placeAt(l,data,s,site,target,stack,e);
   check(result.success() && l.getBlockState(target).is(Blocks.SPRUCE_PLANKS) && stack.getCount()==1,"actual occluded building transaction consumes one real material",checks);
   var roadAt=core.east(3).south(2).below();var road=new AutoRoadState.Route(UUID.randomUUID(),roadAt,List.of(roadAt));s.autoRoads().routes.put("near-input",road);
   check(!RoadSurfaceProfile.visibleSurface(l,e,roadAt) && CitizenInteractionRange.contains(e,roadAt),"occluded road is physically in the original interaction range",checks);
   int slot=c.findInventorySlot(st->st.is(Items.IRON_SHOVEL));int damage=c.inventoryItem(slot).getDamageValue();
   var paved=AutoRoadService.layCell(l,s,c,e,roadAt);
   check(paved.success() && l.getBlockState(roadAt).is(Blocks.DIRT_PATH) && c.inventoryItem(slot).getDamageValue()==damage+1,"actual occluded paving uses the real shovel and pays durability",checks);
   var before=e.position();e.setPos(core.getX()-8.5,250,core.getZ()+.5);e.setOnGround(true);
   var denied=AutoRoadService.layCell(l,s,c,e,roadAt);check(!denied.success() && c.inventoryItem(slot).getDamageValue()==damage+1,"far paving is refused without extra tool damage",checks);
   e.setPos(before);e.setOnGround(true);c.extractInventoryItem(st->st.is(Items.IRON_SHOVEL),1);
   check(!AutoRoadService.layCell(l,s,c,e,roadAt).success(),"near paving still refuses a missing actual tool",checks);
   return "PASS "+checks.size()+": "+String.join("; ",checks)+"; explicit inputs restored; autonomous navigation acceptance separate";
  }finally{if(e!=null)e.discard();s.deactivate();saved.forEach((at,state)->l.setBlock(at,state,2));data.touch();}
 }
 private Near114Checks(){}
}
