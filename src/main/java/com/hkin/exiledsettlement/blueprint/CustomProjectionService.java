package com.hkin.exiledsettlement.blueprint;
import com.hkin.exiledsettlement.network.ProjectionUploadPayload;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Bounded upload, off-thread immutable decoding, then 256 world checks per server tick. */
public final class CustomProjectionService {
 private static final Map<UUID,Upload> uploads=new LinkedHashMap<>();
 private CustomProjectionService() { }
 private static final class Upload {
  final UUID id; final ServerLevel level; final long deadline; final byte[][] parts;
  int received,bytes,index; CompletableFuture<CustomTemplateSnapshot> decoding; CustomTemplateSnapshot snapshot;
  SettlementRecord settlement; final Map<BlockPos,BlockState> clearances=new LinkedHashMap<>();
  Upload(UUID id,ServerLevel level,int count) { this.id=id; this.level=level; parts=new byte[count][]; deadline=level.getGameTime()+1200; }
 }
 public static void receive(ServerPlayer player,ProjectionUploadPayload p) {
  if (p.parts()<1 || p.parts()>342 || p.part()<0 || p.part()>=p.parts() || p.bytes().length==0 || p.bytes().length>ProjectionUploadPayload.CHUNK_BYTES) { fail(player,"投影上传分片无效"); return; }
  Upload upload=uploads.get(player.getUUID());
  if (upload==null) {
   if (uploads.size()>=4 || SettlementSavedData.get(player.serverLevel()).settlements().stream().noneMatch(s->s.active()&&s.isMember(player.getUUID()))) { fail(player,"没有可管理聚落，或已有四份投影正在导入"); return; }
   upload=new Upload(p.upload(),player.serverLevel(),p.parts()); uploads.put(player.getUUID(),upload);
  }
  if (!upload.id.equals(p.upload()) || upload.parts.length!=p.parts() || upload.decoding!=null || upload.parts[p.part()]!=null) { fail(player,"已有导入任务或重复分片，请稍后重试"); return; }
  upload.parts[p.part()]=p.bytes().clone(); upload.received++; upload.bytes+=p.bytes().length;
  if (upload.bytes>CustomTemplateSnapshot.MAX_BYTES) { uploads.remove(player.getUUID()); fail(player,"投影上传超过8MiB"); return; }
  if (upload.received==upload.parts.length) {
   byte[][] parts=upload.parts;
   upload.decoding=CompletableFuture.supplyAsync(()-> {
    try { ByteArrayOutputStream out=new ByteArrayOutputStream(); for (byte[] part:parts) out.write(part);
     return CustomTemplateSnapshot.load(NbtIo.read(new DataInputStream(new ByteArrayInputStream(out.toByteArray())),NbtAccounter.create(CustomTemplateSnapshot.MAX_BYTES)));
    } catch (IOException error) { throw new IllegalArgumentException("投影数据无法读取",error); }
   });
   player.displayClientMessage(Component.literal("投影上传完成，正在分批校验材料、领土和冲突"),false);
  }
 }
 public static void tick(ServerLevel level) {
  var iterator=uploads.entrySet().iterator(); int budget=256;
  while (iterator.hasNext()) {
   var entry=iterator.next(); Upload u=entry.getValue(); if (u.level!=level) continue;
   ServerPlayer player=level.getServer().getPlayerList().getPlayer(entry.getKey());
   if (player==null || player.serverLevel()!=level || level.getGameTime()>u.deadline) { if (u.decoding!=null) u.decoding.cancel(false); iterator.remove(); if (player!=null) fail(player,"投影导入已超时或位置环境改变"); continue; }
   if (u.decoding==null || !u.decoding.isDone()) continue;
   try {
    if (u.snapshot==null) {
     u.snapshot=u.decoding.join(); var data=SettlementSavedData.get(level); var t=u.snapshot.template();
     u.settlement=data.findContaining(t.anchor()).orElse(null);
     if (u.settlement==null || !u.settlement.isMember(player.getUUID()) || u.settlement.blueprintAt(t.anchor())!=null || u.settlement.buildingAt(t.anchor())!=null) throw new IllegalArgumentException("没有建筑权限，或该登记位置已占用");
     for (var e:t.entrances()) if (!u.settlement.contains(e.position()) || !level.hasChunkAt(e.position())) throw new IllegalArgumentException("入口不在已加载领土内");
     String conflict=BlueprintService.validateProjectionReservations(u.settlement,t);
     if(!conflict.isEmpty())throw new IllegalArgumentException(conflict);
    }
    if (!u.settlement.active() || !u.settlement.isMember(player.getUUID())) throw new IllegalArgumentException("导入期间聚落权限已改变");
    var t=u.snapshot.template();
    while (u.index<t.placements().size() && budget-->0) {
     String reason=BlueprintService.validateCustomCell(level,u.settlement,t,t.placements().get(u.index++),u.clearances);
     if (!reason.isEmpty()) throw new IllegalArgumentException(reason);
    }
    if (u.index==t.placements().size()) {
     var result=BlueprintService.placeCustomValidated(level,player.getUUID(),u.snapshot,player,u.clearances);
     if (!result.success()) throw new IllegalArgumentException(result.reason());
     player.displayClientMessage(Component.literal("已创建自定义建筑工地："+u.snapshot.name()+"；已建方块自动计入，居民从公共库存取料施工"),false); iterator.remove();
    }
   } catch (RuntimeException error) { iterator.remove(); Throwable cause=error.getCause()==null?error:error.getCause(); fail(player,"投影导入失败："+cause.getMessage()); }
   if (budget<=0) break;
  }
 }
 private static boolean overlap(BlockPos a,BlockPos b,BlockPos c,BlockPos d) { return a.getX()<=d.getX() && b.getX()>=c.getX() && a.getY()<=d.getY() && b.getY()>=c.getY() && a.getZ()<=d.getZ() && b.getZ()>=c.getZ(); }
 private static void fail(ServerPlayer player,String reason) { player.displayClientMessage(Component.literal(reason),false); }
}
