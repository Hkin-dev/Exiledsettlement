package com.hkin.exiledsettlement.network;
import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.UUID;
public record ConfirmWorksPayload(UUID token,boolean confirm) implements CustomPacketPayload {
    public static final Type<ConfirmWorksPayload> TYPE=new Type<>(ExiledSettlementMod.id("confirm_works"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ConfirmWorksPayload> STREAM_CODEC=StreamCodec.of(
        (b,p)->{b.writeUUID(p.token);b.writeBoolean(p.confirm);},b->new ConfirmWorksPayload(b.readUUID(),b.readBoolean()));
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
