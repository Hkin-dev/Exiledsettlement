package com.hkin.exiledsettlement.network;
import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.UUID;
public record WorksPreviewPayload(UUID token,String description,boolean valid) implements CustomPacketPayload {
    public WorksPreviewPayload(UUID token,String description){this(token,description,true);}
    public static final Type<WorksPreviewPayload> TYPE=new Type<>(ExiledSettlementMod.id("works_preview"));
    public static final StreamCodec<RegistryFriendlyByteBuf,WorksPreviewPayload> STREAM_CODEC=StreamCodec.of(
        (b,p)->{b.writeUUID(p.token);b.writeUtf(p.description);b.writeBoolean(p.valid);},b->new WorksPreviewPayload(b.readUUID(),b.readUtf(16384),b.readBoolean()));
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
