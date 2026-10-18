package com.hkin.exiledsettlement.citizen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
/** Physical reach replaces exclusive work stations; transactions and safety remain authoritative. */
public final class CitizenInteractionRange {
 private CitizenInteractionRange() { }
 public static boolean contains(LivingEntity e, BlockPos p) {
  double dx=Math.max(0,Math.abs(e.getX()-(p.getX()+.5))-.5), dz=Math.max(0,Math.abs(e.getZ()-(p.getZ()+.5))-.5);
  return dx*dx+dz*dz<=20.25 && Math.abs(e.getY()-p.getY())<=2;
 }
 /** Planned construction stations use the existing conservative approach radius,
  * without making ray visibility a condition of real on-site construction. */
 public static boolean containsAt(BlockPos station, BlockPos p) {
  double dx=Math.max(0,Math.abs(station.getX()-p.getX())-.5), dz=Math.max(0,Math.abs(station.getZ()-p.getZ())-.5);
  return dx*dx+dz*dz<=16 && Math.abs(station.getY()-p.getY())<=2;
 }
 public static boolean visible(LivingEntity e, BlockPos p) {
  if (!contains(e,p)) return false;
  var hit=e.level().clip(new ClipContext(e.getEyePosition(),Vec3.atCenterOf(p),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,e));
  return hit.getType()==HitResult.Type.MISS || hit.getBlockPos().equals(p);
 }
 /** The exposed upper face of an empty beam slot can be visible while its
  * center is hidden by the adjacent completed beam under the worker's feet. */
 public static boolean visiblePlacement(LivingEntity e,BlockPos p) {
  return visible(e,p) || contains(e,p) && e.level().getBlockState(p).isAir()
          && upperFaceVisible(e.level(),e.getEyePosition(),p,e);
 }
 public static boolean visiblePlacementAt(net.minecraft.server.level.ServerLevel level,BlockPos station,BlockPos p,LivingEntity e) {
  double dx=Math.max(0,Math.abs(station.getX()-p.getX())-.5),dz=Math.max(0,Math.abs(station.getZ()-p.getZ())-.5);
  return dx*dx+dz*dz<=16 && Math.abs(station.getY()-p.getY())<=2 && level.getBlockState(p).isAir()
          && upperFaceVisible(level,new Vec3(station.getX()+.5,station.getY()+e.getEyeHeight(),station.getZ()+.5),p,e);
 }
 private static boolean upperFaceVisible(net.minecraft.world.level.Level level,Vec3 eye,BlockPos p,LivingEntity e) {
  var hit=level.clip(new ClipContext(eye,new Vec3(p.getX()+.5,p.getY()+.999,p.getZ()+.5),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,e));
  return hit.getType()==HitResult.Type.MISS || hit.getBlockPos().equals(p);
 }
}
