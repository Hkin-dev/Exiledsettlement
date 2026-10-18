package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record BuildingStaffPayload(
        BlockPos corePosition, UUID buildingId, UUID citizenId,
        boolean unlock, int expectedRevision
) implements CustomPacketPayload {
    public static final Type<BuildingStaffPayload> TYPE = new Type<>(ExiledSettlementMod.id("building_staff"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingStaffPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBlockPos(payload.corePosition());
                buffer.writeUUID(payload.buildingId());
                buffer.writeUUID(payload.citizenId());
                buffer.writeBoolean(payload.unlock());
                buffer.writeVarInt(payload.expectedRevision());
            },
            buffer -> new BuildingStaffPayload(buffer.readBlockPos(), buffer.readUUID(), buffer.readUUID(),
                    buffer.readBoolean(), buffer.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
