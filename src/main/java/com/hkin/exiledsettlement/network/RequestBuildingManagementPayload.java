package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record RequestBuildingManagementPayload(BlockPos corePosition, UUID buildingId, boolean refreshOnly)
        implements CustomPacketPayload {
    public RequestBuildingManagementPayload(BlockPos corePosition, UUID buildingId) {
        this(corePosition, buildingId, false);
    }
    public static final Type<RequestBuildingManagementPayload> TYPE = new Type<>(
            ExiledSettlementMod.id("request_building_management"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestBuildingManagementPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBlockPos(payload.corePosition());
                buffer.writeUUID(payload.buildingId());
                buffer.writeBoolean(payload.refreshOnly());
            },
            buffer -> new RequestBuildingManagementPayload(buffer.readBlockPos(), buffer.readUUID(), buffer.readBoolean()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
