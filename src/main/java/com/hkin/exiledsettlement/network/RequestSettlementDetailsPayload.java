package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record RequestSettlementDetailsPayload(BlockPos corePosition) implements CustomPacketPayload {
    public static final Type<RequestSettlementDetailsPayload> TYPE = new Type<>(ExiledSettlementMod.id("request_settlement_details"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestSettlementDetailsPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeBlockPos(payload.corePosition()),
            buffer -> new RequestSettlementDetailsPayload(buffer.readBlockPos()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
