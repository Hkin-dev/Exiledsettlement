package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.citizen.CitizenProfession;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record AssignCitizenPayload(
        UUID settlementId, UUID citizenId, boolean clear,
        CitizenProfession profession, UUID workBuildingId, int expectedRevision
) implements CustomPacketPayload {
    public static final Type<AssignCitizenPayload> TYPE = new Type<>(ExiledSettlementMod.id("assign_citizen"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AssignCitizenPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeUUID(payload.settlementId());
                buffer.writeUUID(payload.citizenId());
                buffer.writeBoolean(payload.clear());
                buffer.writeVarInt(payload.profession().ordinal());
                buffer.writeBoolean(payload.workBuildingId() != null);
                if (payload.workBuildingId() != null) buffer.writeUUID(payload.workBuildingId());
                buffer.writeVarInt(payload.expectedRevision());
            },
            buffer -> new AssignCitizenPayload(buffer.readUUID(), buffer.readUUID(), buffer.readBoolean(),
                    CitizenProfession.values()[Math.max(0,
                            Math.min(CitizenProfession.values().length - 1, buffer.readVarInt()))],
                    buffer.readBoolean() ? buffer.readUUID() : null, buffer.readVarInt()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
