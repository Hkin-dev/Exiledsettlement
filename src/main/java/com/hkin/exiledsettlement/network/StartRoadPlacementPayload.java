package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import com.hkin.exiledsettlement.road.RoadTier;

public record StartRoadPlacementPayload(BlockPos corePosition, RoadTier tier, int boundaryRadiusChunks) implements CustomPacketPayload {
    public static final Type<StartRoadPlacementPayload> TYPE = new Type<>(ExiledSettlementMod.id("start_road_placement"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StartRoadPlacementPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBlockPos(payload.corePosition());
                buffer.writeVarInt(payload.tier().ordinal());
                buffer.writeVarInt(payload.boundaryRadiusChunks());
            },
            buffer -> new StartRoadPlacementPayload(buffer.readBlockPos(),
                    RoadTier.values()[Math.max(0, Math.min(RoadTier.values().length - 1, buffer.readVarInt()))],
                    buffer.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
