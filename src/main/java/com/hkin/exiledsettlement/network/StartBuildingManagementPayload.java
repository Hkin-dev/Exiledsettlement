package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

public record StartBuildingManagementPayload(
        BlockPos corePosition, int boundaryRadiusChunks,
        List<SettlementDetailsPayload.BuildingEntry> buildings
) implements CustomPacketPayload {
    public static final Type<StartBuildingManagementPayload> TYPE = new Type<>(
            ExiledSettlementMod.id("start_building_management"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StartBuildingManagementPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBlockPos(payload.corePosition());
                buffer.writeVarInt(payload.boundaryRadiusChunks());
                buffer.writeVarInt(payload.buildings().size());
                payload.buildings().forEach(entry -> SettlementDetailsPayload.writeEntry(buffer, entry));
            },
            buffer -> {
                BlockPos core = buffer.readBlockPos();
                int radius = buffer.readVarInt();
                int count = Math.min(1024, buffer.readVarInt());
                List<SettlementDetailsPayload.BuildingEntry> buildings = new ArrayList<>(count);
                for (int i = 0; i < count; i++) buildings.add(SettlementDetailsPayload.readEntry(buffer));
                return new StartBuildingManagementPayload(core, radius, List.copyOf(buildings));
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
