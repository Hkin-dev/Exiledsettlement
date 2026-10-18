package com.hkin.exiledsettlement.network;
import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.UUID;
public record ProjectionUploadPayload(UUID upload,int part,int parts,byte[] bytes) implements CustomPacketPayload {
 public static final int CHUNK_BYTES=24576;
 public static final Type<ProjectionUploadPayload> TYPE=new Type<>(ExiledSettlementMod.id("projection_upload"));
 public static final StreamCodec<RegistryFriendlyByteBuf,ProjectionUploadPayload> STREAM_CODEC=StreamCodec.of(
  (b,p)->{ b.writeUUID(p.upload); b.writeVarInt(p.part); b.writeVarInt(p.parts); b.writeByteArray(p.bytes); },
  b->new ProjectionUploadPayload(b.readUUID(),b.readVarInt(),b.readVarInt(),b.readByteArray(CHUNK_BYTES)));
 @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
