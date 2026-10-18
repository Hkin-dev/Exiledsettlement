package com.hkin.exiledsettlement.blueprint;

import com.hkin.exiledsettlement.building.BuildingType;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.Property;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Immutable, world-owned structure. No entities, block-entity contents or executable NBT are accepted. */
public final class CustomTemplateSnapshot {
 public static final int MAX_BLOCKS=32768, MAX_BYTES=8*1024*1024;
 private final CompoundTag saved;
 private final BuildingBlueprintTemplate template;
 private CustomTemplateSnapshot(CompoundTag saved,BuildingBlueprintTemplate template) { this.saved=saved; this.template=template; }
 public BuildingBlueprintTemplate template() { return template; }
 public CompoundTag save() { return saved.copy(); }
 public String name() { return saved.getString("Name"); }
 public static CustomTemplateSnapshot create(String name,BuildingType type,BlockPos anchor,Direction facing,
       List<BlueprintPlacement> placements,List<BlueprintEntrance> entrances) {
  if (placements.isEmpty() || placements.size()>MAX_BLOCKS || entrances.isEmpty()) throw new IllegalArgumentException("投影为空、超限或尚未选择入口");
  CompoundTag tag=new CompoundTag(); tag.putInt("Version",1); tag.putUUID("Id",UUID.randomUUID());
  tag.putString("Name",name.substring(0,Math.min(name.length(),32))); tag.putString("Type",type.serializedName());
  tag.putLong("Anchor",anchor.asLong()); tag.putString("Facing",facing.getName());
  LinkedHashMap<BlockState,Integer> palette=new LinkedHashMap<>(); long[] cells=new long[placements.size()]; int[] indices=new int[cells.length];
  for (int i=0;i<placements.size();i++) {
   var p=placements.get(i); cells[i]=p.position().asLong(); indices[i]=palette.computeIfAbsent(p.state(),ignored->palette.size());
  }
  ListTag states=new ListTag(); palette.keySet().forEach(state->states.add(NbtUtils.writeBlockState(state)));
  tag.put("Palette",states); tag.putLongArray("Cells",cells); tag.putIntArray("States",indices);
  ListTag entries=new ListTag(); for (var e:entrances) { CompoundTag t=new CompoundTag(); t.putLong("At",e.position().asLong()); t.putString("Out",e.outward().getName()); entries.add(t); }
  tag.put("Entrances",entries); tag.putString("Fingerprint",fingerprint(tag)); return load(tag);
 }
 public static CustomTemplateSnapshot load(CompoundTag source) {
  CompoundTag tag=source.copy();
  if (tag.getInt("Version")!=1 || !tag.hasUUID("Id") || !tag.getString("Fingerprint").equals(fingerprint(tag))) throw new IllegalArgumentException("自定义建筑快照版本或指纹错误");
  if (tag.getString("Name").isBlank() || tag.getString("Name").length()>32) throw new IllegalArgumentException("建筑名称应为1—32个字符");
  BuildingType type=BuildingType.fromSerializedName(tag.getString("Type"));
  if (type==BuildingType.WAGON) throw new IllegalArgumentException("货车不能作为自定义建筑用途");
  BlockPos anchor=BlockPos.of(tag.getLong("Anchor")); Direction facing=Direction.byName(tag.getString("Facing"));
  if (facing==null || !facing.getAxis().isHorizontal()) throw new IllegalArgumentException("建筑朝向错误");
  long[] cells=tag.getLongArray("Cells"); int[] ids=tag.getIntArray("States"); ListTag palette=tag.getList("Palette",Tag.TAG_COMPOUND);
  if (cells.length==0 || cells.length>MAX_BLOCKS || ids.length!=cells.length || palette.isEmpty() || palette.size()>MAX_BLOCKS) throw new IllegalArgumentException("投影方块数量或调色板错误");
  List<BlockState> states=new ArrayList<>(); for (int i=0;i<palette.size();i++) states.add(readState(palette.getCompound(i)));
  List<BlueprintPlacement> placements=new ArrayList<>(); Set<BlockPos> seen=new HashSet<>();
  int minX=Integer.MAX_VALUE,minY=minX,minZ=minX,maxX=Integer.MIN_VALUE,maxY=maxX,maxZ=maxX;
  for (int i=0;i<cells.length;i++) {
   if (ids[i]<0 || ids[i]>=states.size()) throw new IllegalArgumentException("投影状态索引错误");
   BlockPos p=BlockPos.of(cells[i]); BlockState state=states.get(ids[i]);
   if (!seen.add(p)) throw new IllegalArgumentException("子区域方块重叠");
   if (state.isAir() || state.getBlock().asItem()==Items.AIR || state.getBlock() instanceof CommandBlock
       || state.is(Blocks.BARRIER) || state.is(Blocks.LIGHT) || state.is(Blocks.STRUCTURE_BLOCK) || state.is(Blocks.JIGSAW) || !state.getFluidState().isEmpty()) throw new IllegalArgumentException("不支持的施工方块："+BuiltInRegistries.BLOCK.getKey(state.getBlock()));
   placements.add(new BlueprintPlacement(p,state,state.getBlock().asItem()));
   minX=Math.min(minX,p.getX()); minY=Math.min(minY,p.getY()); minZ=Math.min(minZ,p.getZ()); maxX=Math.max(maxX,p.getX()); maxY=Math.max(maxY,p.getY()); maxZ=Math.max(maxZ,p.getZ());
  }
  if (maxX-minX>=128 || maxY-minY>=128 || maxZ-minZ>=128) throw new IllegalArgumentException("投影每轴最多128格");
  BlockPos min=new BlockPos(minX,minY,minZ),max=new BlockPos(maxX,maxY,maxZ);
  if (anchor.getX()<minX || anchor.getX()>maxX || anchor.getY()<minY || anchor.getY()>maxY || anchor.getZ()<minZ || anchor.getZ()>maxZ) throw new IllegalArgumentException("登记位置必须位于建筑内部");
  List<BlueprintEntrance> entrances=new ArrayList<>(); ListTag entries=tag.getList("Entrances",Tag.TAG_COMPOUND);
  if (entries.isEmpty() || entries.size()>8) throw new IllegalArgumentException("请选择1—8个入口");
  for (int i=0;i<entries.size();i++) { var e=entries.getCompound(i); Direction out=Direction.byName(e.getString("Out")); BlockPos at=BlockPos.of(e.getLong("At"));
   if (out==null || !out.getAxis().isHorizontal() || at.getX()<minX-1 || at.getX()>maxX+1 || at.getY()<minY || at.getY()>maxY+1 || at.getZ()<minZ-1 || at.getZ()>maxZ+1) throw new IllegalArgumentException("入口位置或朝向无效");
   entrances.add(new BlueprintEntrance(at,out));
  }
  placements.sort(Comparator.comparingInt((BlueprintPlacement p)->p.position().getY()).thenComparingLong(p->p.position().asLong()));
  var template=new BuildingBlueprintTemplate(type,anchor,facing,maxX-minX+1,maxZ-minZ+1,maxY-minY+1,min,max,2,placements,entrances);
  for (var p:template.placements()) if (BlueprintPlacementGroups.isPaired(p.state()) && BlueprintPlacementGroups.parts(template,p).isEmpty())
   throw new IllegalArgumentException("投影中的床或门缺少匹配的另一半："+p.position().toShortString());
  return new CustomTemplateSnapshot(tag,template);
 }
 private static BlockState readState(CompoundTag tag) {
  ResourceLocation id=ResourceLocation.tryParse(tag.getString("Name"));
  if (id==null || !BuiltInRegistries.BLOCK.containsKey(id)) throw new IllegalArgumentException("未知方块："+tag.getString("Name"));
  BlockState state=BuiltInRegistries.BLOCK.get(id).defaultBlockState(); CompoundTag properties=tag.getCompound("Properties");
  for (String key:properties.getAllKeys()) { Property<?> property=state.getBlock().getStateDefinition().getProperty(key);
   if (property==null) throw new IllegalArgumentException("未知方块属性："+key);
   state=set(state,property,properties.getString(key));
  }
  return state;
 }
 private static <T extends Comparable<T>> BlockState set(BlockState state,Property<T> property,String value) {
  T parsed=property.getValue(value).orElseThrow(()->new IllegalArgumentException("无效属性值："+property.getName()+"="+value)); return state.setValue(property,parsed);
 }
 private static String fingerprint(CompoundTag tag) {
  CompoundTag copy=tag.copy(); copy.remove("Fingerprint");
  try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(copy.toString().getBytes(StandardCharsets.UTF_8))); }
  catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
 }
}
