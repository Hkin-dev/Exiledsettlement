package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record RenameBuildingPayload(BlockPos corePosition, UUID buildingId, String name) implements CustomPacketPayload {
    public static final Type<RenameBuildingPayload> TYPE = new Type<>(ExiledSettlementMod.id("rename_building"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RenameBuildingPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBlockPos(payload.corePosition());
                buffer.writeUUID(payload.buildingId());
                buffer.writeUtf(payload.name(), 32);
            },
            buffer -> new RenameBuildingPayload(buffer.readBlockPos(), buffer.readUUID(), buffer.readUtf(32)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
