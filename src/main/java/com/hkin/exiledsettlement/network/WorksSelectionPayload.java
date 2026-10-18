package com.hkin.exiledsettlement.network;
import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
public record WorksSelectionPayload(BlockPos core,String mode,int radius) implements CustomPacketPayload {
    public static final Type<WorksSelectionPayload> TYPE=new Type<>(ExiledSettlementMod.id("works_selection"));
    public static final StreamCodec<RegistryFriendlyByteBuf,WorksSelectionPayload> STREAM_CODEC=StreamCodec.of(
        (b,p)->{b.writeBlockPos(p.core);b.writeUtf(p.mode);b.writeVarInt(p.radius);},
        b->new WorksSelectionPayload(b.readBlockPos(),b.readUtf(64),b.readVarInt()));
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
