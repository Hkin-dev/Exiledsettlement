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
/** Buried full support may consume ordinary blocks; visible caps keep their planned palette. */
public final class BuriedFillMaterials {
 private static final Set<String> VALUABLE=Set.of("iron_block","gold_block","diamond_block","emerald_block","netherite_block","copper_block","raw_iron_block","raw_gold_block","raw_copper_block","coal_block","lapis_block","redstone_block","amethyst_block");
 private record Stock(long tick,Set<Item> items){}
 private static final Map<SettlementRecord,Stock> STOCK=new WeakHashMap<>();
 public static boolean ordinary(ServerLevel l,BlockPos p,BlockState state){
  var b=state.getBlock();var id=BuiltInRegistries.BLOCK.getKey(b);
  if(VALUABLE.contains(id.getPath()) || state.hasBlockEntity() || !state.getFluidState().isEmpty() || state.getDestroySpeed(l,p)<0)return false;
  // Plain blocks, ordinary logs/pillars  have no functional or gravity behavior.
  boolean plain=b.getClass()==Block.class || b.getClass()==RotatedPillarBlock.class;
  return plain && Block.isShapeFullBlock(state.getCollisionShape(l,p)) && state.isFaceSturdy(l,p,Direction.UP);
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
  if(project.bridge || project.areaClearance || project.roadReclaim || project.escapeFor!=null || !step.fill() || !(step.target().is(Blocks.DIRT)||step.target().is(Blocks.COBBLESTONE)))return false;
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
  if(!hidden(l,s,project,step))return step.target();
  if(worker!=null)for(var stack:worker.inventory())if(!stack.isEmpty() && stack.getItem() instanceof BlockItem item && ordinary(l,step.position(),item.getBlock().defaultBlockState()))return item.getBlock().defaultBlockState();
  var items=stock(l,s);
  return items.stream().filter(i->i instanceof BlockItem && i!=step.target().getBlock().asItem()).map(i->((BlockItem)i).getBlock().defaultBlockState()).filter(state->ordinary(l,step.position(),state))
   .sorted(Comparator.comparing(state->BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())).findFirst().orElse(step.target());
 }
}
