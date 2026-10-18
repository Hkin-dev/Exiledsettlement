package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record PlaceAreaPayload(BlockPos first, BlockPos second, Direction facing)
        implements CustomPacketPayload {
    public static final Type<PlaceAreaPayload> TYPE = new Type<>(ExiledSettlementMod.id("place_area"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PlaceAreaPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBlockPos(payload.first());
                buffer.writeBlockPos(payload.second());
                buffer.writeVarInt(payload.facing().get2DDataValue());
            },
            buffer -> new PlaceAreaPayload(buffer.readBlockPos(), buffer.readBlockPos(),
                    Direction.from2DDataValue(buffer.readVarInt())));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
