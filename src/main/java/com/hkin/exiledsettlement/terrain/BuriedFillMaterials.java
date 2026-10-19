package com.hkin.exiledsettlement.terrain;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
/** Explicit material palettes for buried fill and exposed retaining stonework. */
public final class BuriedFillMaterials {
 public static final net.minecraft.tags.TagKey<Block> FOUNDATION=net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,com.hkin.exiledsettlement.ExiledSettlementMod.id("foundation_fill"));
 public static final net.minecraft.tags.TagKey<Block> WALL=net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,com.hkin.exiledsettlement.ExiledSettlementMod.id("retaining_wall_fill"));
 private record Stock(long tick,Set<Item> items){}
 private static final Map<SettlementRecord,Stock> STOCK=new WeakHashMap<>();
 public static boolean ordinary(ServerLevel l,BlockPos p,BlockState state){
  return state.is(FOUNDATION) && stable(l,p,state);
 }
 public static boolean wall(ServerLevel l,BlockPos p,BlockState state){return state.is(WALL) && stable(l,p,state);}
 private static boolean stoneTarget(BlockState state){return state.is(WALL) || state.is(FOUNDATION) && !FarmEarthworkPlan.soil(state)
   || java.util.Set.of(Blocks.GRANITE,Blocks.DIORITE,Blocks.COBBLED_DEEPSLATE,Blocks.DEEPSLATE,Blocks.TUFF,Blocks.CALCITE,Blocks.BLACKSTONE,Blocks.BASALT,Blocks.SMOOTH_BASALT,Blocks.SANDSTONE,Blocks.RED_SANDSTONE,Blocks.DEEPSLATE_BRICKS,Blocks.DEEPSLATE_TILES,Blocks.POLISHED_BLACKSTONE_BRICKS,Blocks.BRICKS).contains(state.getBlock());}
 /** Compatibility for saved targets and already-paid support, never the supply palette. */
 public static boolean historicalFoundation(ServerLevel l,BlockPos at,BlockState state){
  return stable(l,at,state) && (stoneTarget(state) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT));
 }
 /** Completed paid stone is retained; a palette update is not an order to demolish an existing wall. */
 public static boolean existingWall(ServerLevel l,SettlementRecord s,BlockPos at,BlockState state){
  return wall(l,at,state) || stoneTarget(state) && stable(l,at,state)
   && (s.terrain().registeredPublicPaid(at,state) || s.terrain().supersededPaid(at,state));
 }
 private static boolean stable(ServerLevel l,BlockPos p,BlockState state){
  return !state.hasBlockEntity() && state.getFluidState().isEmpty() && state.getDestroySpeed(l,p)>=0
   && !(state.getBlock() instanceof FallingBlock) && state.getBlock().asItem()!=Items.AIR
   && Block.isShapeFullBlock(state.getCollisionShape(l,p)) && state.isFaceSturdy(l,p,Direction.UP);
 }
 private static boolean eligible(TerrainProject p,TerrainProject.Step step){return !p.bridge && !p.areaClearance && !p.roadReclaim && p.escapeFor==null && step.fill();}
 /** Legacy public wall columns used stone below a soil cap. Pending caps adopt the stone palette. */
 public static boolean wallStep(TerrainProject p,TerrainProject.Step step){
  if(!eligible(p,step))return false;
  if(stoneTarget(step.target()))return !(p.road && p.groundSurfaces.contains(step.position()));
  if(!step.target().is(Blocks.DIRT) || !p.publicGround || p.road || !p.groundSurfaces.contains(step.position()))return false;
  var below=p.template().placementAt(step.position().below());
  return below!=null && stoneTarget(below.state());
 }
 public static boolean flexible(ServerLevel l,SettlementRecord s,TerrainProject p,TerrainProject.Step step){return hidden(l,s,p,step) || wallStep(p,step);}
 private static net.minecraft.tags.TagKey<Block> palette(ServerLevel l,SettlementRecord s,TerrainProject p,TerrainProject.Step step){
  return hidden(l,s,p,step)?FOUNDATION:wallStep(p,step)?WALL:null;
 }
 private static List<BlockState> approved(ServerLevel l,BlockPos at,net.minecraft.tags.TagKey<Block> tag){
  var result=new ArrayList<BlockState>();BuiltInRegistries.BLOCK.getTag(tag).ifPresent(values->values.forEach(value->{var state=value.value().defaultBlockState();if(stable(l,at,state))result.add(state);}));
  result.sort(Comparator.comparing(state->BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()));return result;
 }
 public static boolean configured(ServerLevel l,SettlementRecord s,TerrainProject p,TerrainProject.Step step){
  var tag=palette(l,s,p,step);return tag==null || !approved(l,step.position(),tag).isEmpty();
 }
 private static boolean plannedSolid(ServerLevel l,SettlementRecord s,TerrainProject own,BlockPos p){
  var local=own.template().placementAt(p);if(local!=null)return !local.state().isAir() && Block.isShapeFullBlock(local.state().getCollisionShape(l,p));
  for(var project:s.terrain().projectsAt(p))if(!project.cancelled){var goal=project.template().placementAt(p);if(goal!=null)return !goal.state().isAir() && Block.isShapeFullBlock(goal.state().getCollisionShape(l,p));}
  var plane=PublicGroundService.targetAt(s,p);if(plane!=null && p.getY()<plane)return true;
  for(var b:s.formalBlueprints())if(!b.cancellationQueued()){var goal=BlueprintTemplates.create(b).placementAt(p);if(goal!=null && !goal.state().isAir())return Block.isShapeFullBlock(goal.state().getCollisionShape(l,p));}
  for(var b:s.buildings())if(b.active() && b.type()!=BuildingType.WAGON && b.contains(p)){var goal=BlueprintTemplates.createForBuilding(b).placementAt(p);if(goal!=null && !goal.state().isAir())return Block.isShapeFullBlock(goal.state().getCollisionShape(l,p));}
  return l.hasChunkAt(p) && Block.isShapeFullBlock(l.getBlockState(p).getCollisionShape(l,p));
 }
 public static boolean hidden(ServerLevel l,SettlementRecord s,TerrainProject project,TerrainProject.Step step){
  if(!eligible(project,step) || !(step.target().is(Blocks.DIRT)||stoneTarget(step.target())))return false;
  var p=step.position();if(project.groundSurfaces.contains(p) || project.accessSurfaces.contains(p))return false;
  // Enclosure in final real geometry, including sides, distinguishes buried volume from retaining faces.
  for(var side:Direction.values())if(side!=Direction.DOWN && !plannedSolid(l,s,project,p.relative(side)))return false;
  return true;
 }
 private static Set<Item> stock(ServerLevel l,SettlementRecord s){
  var cached=STOCK.get(s);if(cached!=null && l.getGameTime()-cached.tick>=0 && l.getGameTime()-cached.tick<20)return cached.items;
  var items=new HashSet<Item>();for(var b:s.buildings())if(b.active() && b.operational() && b.type().isStorage())items.addAll(WarehouseInventoryService.summarize(l,b).keySet());

  STOCK.put(s,new Stock(l.getGameTime(),items));return items;
 }
 public static BlockState choose(ServerLevel l,SettlementRecord s,TerrainProject project,TerrainProject.Step step,CitizenRecord worker){
  var tag=palette(l,s,project,step);if(tag==null)return step.target();
  var allowed=approved(l,step.position(),tag);
  // Rank each cell/material pair, including Y. Stable ranking keeps inspection,
  // batch pickup and actual placement consistent without saving a random queue.
  var items=new HashSet<>(stock(l,s));
  if(worker!=null)for(int slot=0;slot<worker.inventory().size();slot++){var stack=worker.inventoryItem(slot);
   if(!worker.pitReserveSlot(slot) && !stack.isEmpty())items.add(stack.getItem());
  }
  long seed=mix(l.getSeed() ^ project.id.getMostSignificantBits() ^ Long.rotateLeft(project.id.getLeastSignificantBits(),23)
   ^ mix(step.position().asLong()));
  BlockState selected=null;long best=0;
  for(var state:allowed)if(items.contains(state.getBlock().asItem())){
   long score=mix(seed ^ BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().hashCode());
   if(selected==null || Long.compareUnsigned(score,best)<0){selected=state;best=score;}
  }
  return selected!=null?selected:allowed.contains(step.target())?step.target():allowed.isEmpty()?step.target():allowed.getFirst();
  // Empty palettes retain a diagnostic target only; TerrainService.guard rejects all work.
 }
 private static long mix(long value){
  value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;
  value=(value^(value>>>27))*0x94d049bb133111ebL;
  return value^(value>>>31);
 }
 /** Batch pickup uses the same palette as placement, so it does not also craft obsolete targets. */
 public static Map<Item,Integer> supplyNeeds(ServerLevel l,SettlementRecord s,TerrainProject p,CitizenRecord worker){
  if(p.cancelled || p.completed)return Map.of();
  var remaining=new LinkedHashMap<BlockPos,Item>();
  for(int i=p.cursor;i<p.steps.size();i++)if(!p.finished.get(i) && !p.abandoned.get(i) && p.steps.get(i).fill()){
   var step=p.steps.get(i);remaining.put(step.position(),choose(l,s,p,step,worker).getBlock().asItem());
  }
  var result=new LinkedHashMap<Item,Integer>();remaining.values().forEach(item->{if(item!=Items.AIR)result.compute(item,(k,n)->Math.min(item.getDefaultMaxStackSize(),n==null?1:n+1));});return result;
 }
}
