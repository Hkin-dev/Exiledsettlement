package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record CancelPlacementPayload() implements CustomPacketPayload {
    public static final CancelPlacementPayload INSTANCE = new CancelPlacementPayload();
    public static final Type<CancelPlacementPayload> TYPE = new Type<>(
            ExiledSettlementMod.id("cancel_building_placement")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, CancelPlacementPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
