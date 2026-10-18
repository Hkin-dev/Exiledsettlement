package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record CitizenInteractionPayload(boolean active) implements CustomPacketPayload {
    public static final Type<CitizenInteractionPayload> TYPE = new Type<>(ExiledSettlementMod.id("citizen_interaction"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CitizenInteractionPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeBoolean(payload.active()), buffer -> new CitizenInteractionPayload(buffer.readBoolean()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
