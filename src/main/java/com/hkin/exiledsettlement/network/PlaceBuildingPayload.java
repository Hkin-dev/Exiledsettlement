package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record PlaceBuildingPayload(BlockPos anchor, Direction facing) implements CustomPacketPayload {
    public static final Type<PlaceBuildingPayload> TYPE = new Type<>(
            ExiledSettlementMod.id("place_selected_building")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, PlaceBuildingPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBlockPos(payload.anchor());
                buffer.writeByte(payload.facing().get3DDataValue());
            },
            buffer -> new PlaceBuildingPayload(
                    buffer.readBlockPos(),
                    Direction.from3DDataValue(buffer.readUnsignedByte())
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
