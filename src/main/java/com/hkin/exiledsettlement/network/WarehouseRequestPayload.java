package com.hkin.exiledsettlement.network;
import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
public record WarehouseRequestPayload(BlockPos core,int warehouse,int page,String query,String item,int keep,boolean discard,boolean apply) implements CustomPacketPayload {
    public static final Type<WarehouseRequestPayload> TYPE=new Type<>(ExiledSettlementMod.id("warehouse_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf,WarehouseRequestPayload> STREAM_CODEC=StreamCodec.of(
        (b,p)->{b.writeBlockPos(p.core);b.writeInt(p.warehouse);b.writeInt(p.page);b.writeUtf(p.query,128);b.writeUtf(p.item,256);b.writeInt(p.keep);b.writeBoolean(p.discard);b.writeBoolean(p.apply);},
        b->new WarehouseRequestPayload(b.readBlockPos(),b.readInt(),b.readInt(),b.readUtf(128),b.readUtf(256),b.readInt(),b.readBoolean(),b.readBoolean()));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
