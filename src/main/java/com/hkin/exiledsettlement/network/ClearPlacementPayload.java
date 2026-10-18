package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ClearPlacementPayload() implements CustomPacketPayload {
    public static final ClearPlacementPayload INSTANCE = new ClearPlacementPayload();
    public static final Type<ClearPlacementPayload> TYPE = new Type<>(
            ExiledSettlementMod.id("clear_building_placement")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ClearPlacementPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
