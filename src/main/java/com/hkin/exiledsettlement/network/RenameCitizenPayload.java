package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.UUID;

public record RenameCitizenPayload(UUID token, String name, boolean cancel) implements CustomPacketPayload {
    public static final Type<RenameCitizenPayload> TYPE = new Type<>(ExiledSettlementMod.id("rename_citizen"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RenameCitizenPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> { buffer.writeUUID(payload.token()); buffer.writeUtf(payload.name(), 64); buffer.writeBoolean(payload.cancel()); },
            buffer -> new RenameCitizenPayload(buffer.readUUID(), buffer.readUtf(64), buffer.readBoolean()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
