package com.hkin.exiledsettlement.network;
import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
public record PlanBridgePayload(BlockPos first,BlockPos second,boolean alongX,int surface) implements CustomPacketPayload {
 public static final Type<PlanBridgePayload> TYPE=new Type<>(ExiledSettlementMod.id("plan_bridge"));
 public static final StreamCodec<RegistryFriendlyByteBuf,PlanBridgePayload> STREAM_CODEC=StreamCodec.of(
  (b,p)->{b.writeBlockPos(p.first);b.writeBlockPos(p.second);b.writeBoolean(p.alongX);b.writeInt(p.surface);},
  b->new PlanBridgePayload(b.readBlockPos(),b.readBlockPos(),b.readBoolean(),b.readInt()));
 public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
