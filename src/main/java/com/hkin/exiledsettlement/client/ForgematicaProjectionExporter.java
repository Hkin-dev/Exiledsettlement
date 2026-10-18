package com.hkin.exiledsettlement.client;

import com.hkin.exiledsettlement.blueprint.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import java.lang.reflect.Method;
import java.util.*;

/** Optional adapter to the public Forgematica placement/container APIs. Never touches a real world. */
public final class ForgematicaProjectionExporter {
 private record Region(Object container,Method get,Vec3i size,Vec3i signedSize,BlockPos position,
        Rotation rotation,Mirror mirror,Object placement) { }
 private final Object placement; private final BlockPos origin; private final Rotation rotation; private final Mirror mirror;
 private final Method transform,transformPlacement; private final List<Region> regions=new ArrayList<>();
 private final Map<BlockPos,BlueprintPlacement> blocks=new LinkedHashMap<>();
 private int regionIndex,x,y,z; private long volume; public String error="";
 public ForgematicaProjectionExporter() throws ReflectiveOperationException {
  if (!ModList.get().isLoaded("forgematica")) throw new IllegalArgumentException("请先安装兼容的Forgematica，并选中一个已启用投影");
  Class<?> data=Class.forName("fi.dy.masa.litematica.data.DataManager");
  Object manager=data.getMethod("getSchematicPlacementManager").invoke(null);
  placement=call(manager,"getSelectedSchematicPlacement");
  if (placement==null || !(Boolean)call(placement,"isEnabled")) throw new IllegalArgumentException("请在Forgematica中选中并启用一个投影");
  origin=(BlockPos)call(placement,"getOrigin"); rotation=(Rotation)call(placement,"getRotation"); mirror=(Mirror)call(placement,"getMirror");
  Object schematic=call(placement,"getSchematic");
  Class<?> positions=Class.forName("fi.dy.masa.litematica.util.PositionUtils");
  transform=positions.getMethod("getTransformedBlockPos",BlockPos.class,Mirror.class,Rotation.class);
  Class<?> subClass=Class.forName("fi.dy.masa.litematica.schematic.placement.SubRegionPlacement");
  transformPlacement=positions.getMethod("getTransformedPlacementPosition",BlockPos.class,placement.getClass(),subClass);
  Map<?,?> enabled=(Map<?,?>)call(placement,"getEnabledRelativeSubRegionPlacements");
  for (var entry:enabled.entrySet()) {
   String name=(String)entry.getKey(); Object sub=entry.getValue();
   Object container=schematic.getClass().getMethod("getSubRegionContainer",String.class).invoke(schematic,name);
   if (container==null) throw new IllegalArgumentException("投影子区域尚未加载："+name);
   Vec3i size=(Vec3i)call(container,"getSize"); Vec3i signed=(Vec3i)schematic.getClass().getMethod("getAreaSize",String.class).invoke(schematic,name);
   long cells=(long)size.getX()*size.getY()*size.getZ(); volume+=cells;
   if (size.getX()<1 || size.getY()<1 || size.getZ()<1 || size.getX()>128 || size.getY()>128 || size.getZ()>128 || volume>128L*128*128) throw new IllegalArgumentException("投影体积超过128格各轴/2097152个扫描格");
   regions.add(new Region(container,container.getClass().getMethod("get",int.class,int.class,int.class),size,signed,
    (BlockPos)call(sub,"getPos"),(Rotation)call(sub,"getRotation"),(Mirror)call(sub,"getMirror"),sub));
  }
  if (regions.isEmpty()) throw new IllegalArgumentException("投影没有启用的子区域");
 }
 private static Object call(Object object,String method) throws ReflectiveOperationException { return object.getClass().getMethod(method).invoke(object); }
 public boolean done() { return regionIndex>=regions.size() || !error.isEmpty(); }
 public int count() { return blocks.size(); }
 public List<BlueprintPlacement> placements() { return List.copyOf(blocks.values()); }
 public void tick() {
  if (done()) return;
  try {
   for (int budget=2048;budget>0 && regionIndex<regions.size();budget--) {
    Region region=regions.get(regionIndex); BlockState state=(BlockState)region.get.invoke(region.container,x,y,z);
    if (!state.isAir() && !state.is(Blocks.STRUCTURE_VOID)) {
     int sx=region.signedSize.getX(),sy=region.signedSize.getY(),sz=region.signedSize.getZ();
     BlockPos local=new BlockPos(x+Math.min(0,sx>0?sx-1:sx+1),y+Math.min(0,sy>0?sy-1:sy+1),z+Math.min(0,sz>0?sz-1:sz+1));
     BlockPos regionOrigin=(BlockPos)transform.invoke(null,region.position,mirror,rotation);
     BlockPos transformed=(BlockPos)transformPlacement.invoke(null,local,placement,region.placement);
     BlockPos at=transformed.offset(regionOrigin).offset(origin);
     Mirror subMirror=region.mirror;
     if (subMirror!=Mirror.NONE && (rotation==Rotation.CLOCKWISE_90 || rotation==Rotation.COUNTERCLOCKWISE_90))
      subMirror=subMirror==Mirror.FRONT_BACK?Mirror.LEFT_RIGHT:Mirror.FRONT_BACK;
     state=state.mirror(mirror).mirror(subMirror).rotate(rotation.getRotated(region.rotation));
     BlueprintPlacement previous=blocks.putIfAbsent(at,new BlueprintPlacement(at,state,state.getBlock().asItem()));
     if (previous!=null && !previous.state().equals(state)) throw new IllegalArgumentException("投影子区域在同一位置有冲突方块："+at.toShortString());
     if (blocks.size()>CustomTemplateSnapshot.MAX_BLOCKS) throw new IllegalArgumentException("非空气方块超过32768格");
    }
    if (++x>=region.size.getX()) { x=0; if (++z>=region.size.getZ()) { z=0; if (++y>=region.size.getY()) { y=0;regionIndex++; } } }
   }
  } catch (ReflectiveOperationException | RuntimeException failure) { error="无法读取投影："+(failure.getCause()==null?failure.getMessage():failure.getCause().getMessage()); }
 }
}
