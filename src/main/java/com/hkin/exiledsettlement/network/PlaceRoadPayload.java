package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record PlaceRoadPayload(BlockPos first, BlockPos second) implements CustomPacketPayload {
    public static final Type<PlaceRoadPayload> TYPE = new Type<>(ExiledSettlementMod.id("place_selected_road"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PlaceRoadPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBlockPos(payload.first());
                buffer.writeBlockPos(payload.second());
            },
            buffer -> new PlaceRoadPayload(buffer.readBlockPos(), buffer.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
