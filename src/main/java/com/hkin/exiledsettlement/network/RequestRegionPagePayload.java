package com.hkin.exiledsettlement.network;
import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.network.*;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.UUID;
public record RequestRegionPagePayload(UUID id,int offset,int revision) implements CustomPacketPayload {
    public static final Type<RequestRegionPagePayload> TYPE=new Type<>(ExiledSettlementMod.id("request_region_page"));
    public static final StreamCodec<RegistryFriendlyByteBuf,RequestRegionPagePayload> STREAM_CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.id);b.writeVarInt(p.offset);b.writeVarInt(p.revision);},b->new RequestRegionPagePayload(b.readUUID(),b.readVarInt(),b.readVarInt()));
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
