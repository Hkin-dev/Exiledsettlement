package com.hkin.exiledsettlement.network;
import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.*;
public record BridgePreviewPayload(UUID token,boolean reset,boolean alongX,int width,List<BlockPos> rows,List<BlockPos> cuts,List<BlockPos> supports,List<BlockPos> conflicts,int suggestedSurface) implements CustomPacketPayload {
 public static final Type<BridgePreviewPayload> TYPE=new Type<>(ExiledSettlementMod.id("bridge_preview"));
 public static final StreamCodec<RegistryFriendlyByteBuf,BridgePreviewPayload> STREAM_CODEC=StreamCodec.of(
  (b,p)->{b.writeUUID(p.token);b.writeBoolean(p.reset);b.writeBoolean(p.alongX);b.writeInt(p.width);b.writeCollection(p.rows,(v,at)->v.writeBlockPos(at));b.writeCollection(p.cuts,(v,at)->v.writeBlockPos(at));b.writeCollection(p.supports,(v,at)->v.writeBlockPos(at));b.writeCollection(p.conflicts,(v,at)->v.writeBlockPos(at));b.writeInt(p.suggestedSurface);},
  b->new BridgePreviewPayload(b.readUUID(),b.readBoolean(),b.readBoolean(),b.readInt(),b.readList(v->v.readBlockPos()),b.readList(v->v.readBlockPos()),b.readList(v->v.readBlockPos()),b.readList(v->v.readBlockPos()),b.readInt()));
 public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
