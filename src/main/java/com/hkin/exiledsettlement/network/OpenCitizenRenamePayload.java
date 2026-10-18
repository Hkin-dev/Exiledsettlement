package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.UUID;

public record OpenCitizenRenamePayload(UUID token, String currentName, String suggestedName, String professionKey)
        implements CustomPacketPayload {
    public static final Type<OpenCitizenRenamePayload> TYPE = new Type<>(ExiledSettlementMod.id("open_citizen_rename"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenCitizenRenamePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeUUID(payload.token()); buffer.writeUtf(payload.currentName(), 64);
                buffer.writeUtf(payload.suggestedName(), 256); buffer.writeUtf(payload.professionKey(), 128);
            }, buffer -> new OpenCitizenRenamePayload(buffer.readUUID(), buffer.readUtf(64), buffer.readUtf(256), buffer.readUtf(128)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
