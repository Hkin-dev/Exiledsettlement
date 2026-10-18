package com.hkin.exiledsettlement.terrain;
import com.google.gson.*;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.debug.*;
import com.hkin.exiledsettlement.registry.*;
import com.hkin.exiledsettlement.region.ProductionRegionService;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import java.util.*;
public final class Layer84Checks {
 private static final BlockPos CORE=new BlockPos(45100,250,45100);
 private static void check(boolean ok,String name,List<String> checks){if(!ok)throw new IllegalStateException(name);checks.add(name);}
 public static String run(ServerLevel l){
  var checks=new ArrayList<String>();var p=new BlockPos(46600,250,46600);var data=SettlementSavedData.get(l);if(data.findByCore(p).isPresent())throw new IllegalStateException("TEST_ALREADY_EXISTS");
  var saved=new LinkedHashMap<BlockPos,BlockState>();for(var raw:BlockPos.betweenClosed(p.offset(-3,-4,-3),p.offset(8,4,3))){var at=raw.immutable();if(!l.getBlockState(at).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");saved.put(at,l.getBlockState(at));}
  var s=data.createOrRecover(p,UUID.randomUUID(),"Layer84 rule checks only").value();s.terrain().enabled=true;
  try{
   for(int x=-3;x<=8;x++)for(int z=-3;z<=3;z++)for(int y=-4;y<=-3;y++)l.setBlock(p.offset(x,y,z),Blocks.DIRT.defaultBlockState(),2);
   UUID fill=UUID.randomUUID();var a=ProductionRegionService.publish(l,s,fill,"Layer84 first column",p,List.of(new TerrainProject.Step(p.below(2),Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()),new TerrainProject.Step(p.below(),Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));
   var b=ProductionRegionService.publish(l,s,fill,"Layer84 second column",p.east(),List.of(new TerrainProject.Step(p.east().below(2),Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()),new TerrainProject.Step(p.east().below(),Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));
   var ba=s.blueprint(a.id);var bb=s.blueprint(b.id);
   check(TerrainService.pending(l,ba,Set.of()).getFirst().placement().position().getY()==248,"first column starts on low layer",checks);
   check(TerrainService.pending(l,bb,Set.of()).getFirst().placement().position().getY()==248,"second column starts on same layer",checks);
   l.setBlock(p.below(2),Blocks.DIRT.defaultBlockState(),2);a.mark(0);TerrainLayerService.invalidate(s);
   check(TerrainService.at(l,ba,p.below())==null,"continuation cannot rise while sibling low layer remains",checks);
   check(TerrainService.pending(l,ba,Set.of()).isEmpty(),"assignment also respects sibling layer",checks);
   check(TerrainService.pending(l,bb,Set.of(p.east().below(2))).isEmpty(),"claimed low cell cannot be bypassed",checks);
   TerrainLayerService.invalidate(s);check(TerrainService.at(l,ba,p.below())==null,"claimed sibling keeps frontier low",checks);
   l.setBlock(p.east().below(2),Blocks.DIRT.defaultBlockState(),2);b.mark(0);TerrainLayerService.invalidate(s);
   check(TerrainService.at(l,ba,p.below())!=null && TerrainService.at(l,bb,p.east().below())!=null,"both columns advance together",checks);
   var ordered=new ArrayList<TerrainProject.Step>();for(int x=0;x<40;x++){ordered.add(new TerrainProject.Step(p.offset(x,-1,0),Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()));ordered.add(new TerrainProject.Step(p.offset(x,-2,0),Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()));}var order=new TerrainProject(UUID.randomUUID(),"large order",p,ordered);
   check(order.layerOrder().subList(0,40).stream().allMatch(i->order.steps.get(i).position().getY()==248),"layer index spans more than one 32-cell window",checks);
   for(int i:order.layerOrder().subList(0,35))order.mark(i);check(order.steps.get(order.layerOrder().get(order.layerFrontier())).position().getY()==248,"finished scan window does not skip residual low layer",checks);
   UUID dig=UUID.randomUUID();var pos=p.east(5);l.setBlock(pos,Blocks.STONE.defaultBlockState(),2);l.setBlock(pos.above(),Blocks.STONE.defaultBlockState(),2);l.setBlock(pos.east(),Blocks.STONE.defaultBlockState(),2);l.setBlock(pos.east().above(),Blocks.STONE.defaultBlockState(),2);
   var da=ProductionRegionService.publish(l,s,dig,"Layer84 clear column",pos,List.of(new TerrainProject.Step(pos,Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState()),new TerrainProject.Step(pos.above(),Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState())));
   var db=ProductionRegionService.publish(l,s,dig,"Layer84 clear sibling",pos.east(),List.of(new TerrainProject.Step(pos.east(),Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState()),new TerrainProject.Step(pos.east().above(),Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState())));TerrainLayerService.invalidate(s);
   check(TerrainService.pending(l,s.blueprint(da.id),Set.of()).getFirst().placement().position().getY()==251,"dig starts high",checks);
   l.setBlock(pos.above(),Blocks.AIR.defaultBlockState(),2);da.mark(1);TerrainLayerService.invalidate(s);check(TerrainService.at(l,s.blueprint(da.id),pos)==null,"dig does not descend while sibling high layer remains",checks);
   check(TerrainService.pending(l,bb,Set.of()).getFirst().placement().position().getY()==249,"independent mother keeps its own layer",checks);
   var hi=p.offset(7,2,2);var low=hi.below();l.setBlock(hi,Blocks.STONE.defaultBlockState(),2);l.setBlock(low,Blocks.STONE.defaultBlockState(),2);
   var house=BlueprintRecord.create(BuildingType.HOUSE,p,p,Direction.NORTH,UUID.randomUUID(),false,Map.of(hi,Blocks.STONE.defaultBlockState(),low,Blocks.STONE.defaultBlockState()));
   check(BlueprintService.clearanceLayerAllowed(l,house,hi) && !BlueprintService.clearanceLayerAllowed(l,house,low),"house clearance filters lower layers",checks);
   l.setBlock(hi,Blocks.AIR.defaultBlockState(),2);check(BlueprintService.clearanceLayerAllowed(l,house,low) && !BlueprintService.clearanceLayerAllowed(l,house,p),"house clearance advances after high layer",checks);
   l.setBlock(low,Blocks.AIR.defaultBlockState(),2);check(BlueprintService.clearanceLayerAllowed(l,house,p),"house placement released after clearance",checks);
   for(var block:List.of(Blocks.DIRT,Blocks.COARSE_DIRT,Blocks.ROOTED_DIRT,Blocks.COBBLESTONE,Blocks.GRANITE,Blocks.ANDESITE,Blocks.DIORITE,Blocks.STONE_BRICKS,Blocks.TUFF,Blocks.CALCITE))check(BuriedFillMaterials.ordinary(l,p,block.defaultBlockState()),"ordinary fill "+block,checks);
   for(var block:List.of(Blocks.OAK_PLANKS,Blocks.SPRUCE_PLANKS,Blocks.OAK_LOG,Blocks.WHITE_WOOL,Blocks.TERRACOTTA,Blocks.WHITE_CONCRETE,Blocks.GRASS_BLOCK))check(!BuriedFillMaterials.ordinary(l,p,block.defaultBlockState()),"not whitelisted fill "+block,checks);
   for(var block:List.of(Blocks.DIAMOND_BLOCK,Blocks.GOLD_BLOCK,Blocks.IRON_BLOCK,Blocks.COPPER_BLOCK,Blocks.REDSTONE_BLOCK,Blocks.BARREL,Blocks.CHEST,Blocks.CRAFTING_TABLE,Blocks.SAND,Blocks.GRAVEL,Blocks.TNT,Blocks.MAGMA_BLOCK,Blocks.OAK_SLAB,Blocks.COBBLESTONE_STAIRS,Blocks.BEDROCK))check(!BuriedFillMaterials.ordinary(l,p,block.defaultBlockState()),"reserved unsafe fill "+block,checks);
   order.recordFill(0,Blocks.GRANITE.defaultBlockState());var loaded=TerrainProject.load(order.save());check(loaded.steps.get(0).target().is(Blocks.GRANITE),"actual alternative target survives save",checks);
   // Add the building only after the unrelated clearing tests: its real protection must not interfere with those fixtures.
   var parent=s.addBlueprint(BuildingType.HOUSE,p.east(8),p.east(8),Direction.NORTH,s.ownerId(),false);
   l.setBlock(parent.anchor().below(2),Blocks.DIRT.defaultBlockState(),2);
   var footing=ProductionRegionService.publish(l,s,parent.id(),"Layer89 building footing",parent.anchor(),List.of(new TerrainProject.Step(parent.anchor().below(),Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));
   var roadAt=p.east(7).below(2);var road=ProductionRegionService.publish(l,s,parent.id(),"Layer89 optional road",roadAt,List.of(new TerrainProject.Step(roadAt,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState())));
   if(footing==null || road==null)throw new IllegalStateException("LAYER89_FIXTURE_ORDER_FAILED: "+s.terrain().status);
   road.road=true;road.explicit=true;s.terrain().index(road);TerrainLayerService.invalidate(s);
   check(TerrainService.at(l,s.blueprint(road.id),roadAt)!=null,"lower optional road is physically ready",checks);
   check(TerrainService.at(l,s.blueprint(footing.id),parent.anchor().below())!=null,"lower road cannot lock higher building footing",checks);
   return "PASS "+checks.size()+" "+String.join("; ",checks)+"; fixture restored; no resident completion credited";
  }catch(RuntimeException failure){throw new IllegalStateException("Layer check "+failure.getStackTrace()[1]+": "+failure,failure);}
  finally{s.deactivate();saved.forEach((at,state)->l.setBlock(at,state,2));data.touch();TerrainLayerService.invalidate(s);}
 }
 public static String live(ServerLevel l){
  var data=SettlementSavedData.get(l);data.findByCore(new BlockPos(45000,250,45000)).filter(old->old.name().equals("Layer84 normal real work and mixed buried fill")).ifPresent(SettlementRecord::deactivate);if(data.findByCore(CORE).isPresent())throw new IllegalStateException("FIXTURE_EXISTS");
  for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)l.setChunkForced((CORE.getX()>>4)+x,(CORE.getZ()>>4)+z,true);
  for(var p:BlockPos.betweenClosed(CORE.offset(-12,-6,-10),CORE.offset(16,5,10)))if(!l.getBlockState(p).isAir())throw new IllegalStateException("UNUSED_AIR_REQUIRED");
  for(int x=-12;x<=16;x++)for(int z=-10;z<=10;z++)for(int y=-6;y<=-1;y++)l.setBlock(CORE.offset(x,y,z),Blocks.DIRT.defaultBlockState(),2);
  l.setBlock(CORE,ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(),2);var s=data.createOrRecover(CORE,UUID.randomUUID(),"Layer84 normal real work and mixed buried fill").value();s.upgradeLevel();s.upgradeLevel();s.terrain().enabled=false;s.autoRoads().enabled=false;s.decorations().enabled=false;
  var store=CORE.offset(-6,0,-6);l.setBlock(store,Blocks.BARREL.defaultBlockState(),2);var w=s.addBuilding(BuildingType.WAREHOUSE,store,Direction.NORTH,store.offset(-2,0,-2),store.offset(2,2,2),0,1);w.updateFunction(true,BuildingStatus.OPERATIONAL,1,List.of(store));s.terrain().surveyed.add(w.id());
  WarehouseInventoryService.insert(l,w,new ItemStack(Items.DIRT,9));for(var item:List.of(Items.GRANITE,Items.ANDESITE,Items.DIORITE))WarehouseInventoryService.insert(l,w,new ItemStack(item,6));WarehouseInventoryService.insert(l,w,new ItemStack(Items.DIAMOND_BLOCK,64));WarehouseInventoryService.insert(l,w,new ItemStack(Items.BREAD,128));for(var item:List.of(Items.DIAMOND_SHOVEL,Items.DIAMOND_PICKAXE,Items.DIAMOND_AXE))WarehouseInventoryService.insert(l,w,new ItemStack(item,8));
  var fill=UUID.randomUUID();var dig=UUID.randomUUID();
  for(int x=2;x<=4;x++)for(int z=0;z<=2;z++){
   var steps=new ArrayList<TerrainProject.Step>();for(int y=247;y<=249;y++){var p=CORE.offset(x,0,z).atY(y);l.setBlock(p,Blocks.AIR.defaultBlockState(),2);steps.add(new TerrainProject.Step(p,Blocks.DIRT.defaultBlockState(),Blocks.AIR.defaultBlockState()));}
   var order=ProductionRegionService.publish(l,s,fill,"Layer84 layered fill",steps.getFirst().position(),steps);order.explicit=true;order.groundSurfaces.add(steps.getLast().position());s.terrain().index(order);
  }
  for(int x=8;x<=10;x++)for(int z=0;z<=2;z++){
   var steps=new ArrayList<TerrainProject.Step>();for(int y=250;y<=252;y++){var p=CORE.offset(x,0,z).atY(y);l.setBlock(p,Blocks.STONE.defaultBlockState(),2);steps.add(new TerrainProject.Step(p,Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState()));}
   var order=ProductionRegionService.publish(l,s,dig,"Layer84 layered dig",steps.getFirst().position(),steps);order.explicit=true;s.terrain().index(order);
  }
  while(s.citizens().size()<8)s.addMigrant();int n=0;for(var c:s.citizens()){c.setManualAssignment(CitizenProfession.WORKER,null);c.updateProjection(null,CORE.offset(-2,0,1+n++%5));c.insertIntoInventory(new ItemStack(Items.BREAD,16));}
  ForceWorkDebugService.set(l,s,false);CitizenProjectionService.ensureSettlementCitizens(l,s);SaveAcceptanceService.load(l,s,2);data.touch();TerrainLayerService.invalidate(s);return "OK "+s.id()+"; 9 columns x 3 fill layers, 9 columns x 3 clear layers; finite 9 dirt + 18 ordinary alternatives; 64 diamonds reserved; normal work";
 }
 public static String inspect(ServerLevel l){var s=SettlementSavedData.get(l).findByCore(CORE).orElseThrow();var j=new JsonObject();j.addProperty("settlement",s.id().toString());j.addProperty("pending",s.blueprints().size());var counts=new JsonObject();int filled=0,cleared=0,caps=0;for(int x=2;x<=4;x++)for(int z=0;z<=2;z++)for(int y=247;y<=249;y++){var p=CORE.offset(x,0,z).atY(y);var b=l.getBlockState(p);if(Block.isShapeFullBlock(b.getCollisionShape(l,p)))filled++;if(y==249 && b.is(Blocks.DIRT))caps++;var name=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b.getBlock()).toString();counts.addProperty(name,counts.has(name)?counts.get(name).getAsInt()+1:1);}for(int x=8;x<=10;x++)for(int z=0;z<=2;z++)for(int y=250;y<=252;y++)if(l.getBlockState(CORE.offset(x,0,z).atY(y)).isAir())cleared++;j.addProperty("filled",filled);j.addProperty("cleared",cleared);j.addProperty("dirtCaps",caps);j.add("fillBlocks",counts);return j.toString();}
}
