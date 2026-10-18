package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

public record OverviewSnapshotPayload(BlockPos corePosition, List<Long> claimedChunks, List<SettlementDetailsPayload.BuildingEntry> buildings)
        implements CustomPacketPayload {
    public static final Type<OverviewSnapshotPayload> TYPE = new Type<>(
            ExiledSettlementMod.id("overview_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OverviewSnapshotPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBlockPos(payload.corePosition());
                buffer.writeVarInt(payload.claimedChunks().size());
                payload.claimedChunks().forEach(buffer::writeLong);
                buffer.writeVarInt(payload.buildings().size());
                payload.buildings().forEach(entry -> SettlementDetailsPayload.writeEntry(buffer, entry));
            },
            buffer -> {
                BlockPos core = buffer.readBlockPos();
                int territoryCount = buffer.readVarInt();
                if (territoryCount < 0 || territoryCount > 65536) throw new IllegalArgumentException("Invalid territory snapshot");
                List<Long> territory = new ArrayList<>(territoryCount);
                for (int i = 0; i < territoryCount; i++) territory.add(buffer.readLong());
                int count = buffer.readVarInt();
                if (count < 0 || count > 1024) throw new IllegalArgumentException("Invalid building snapshot");
                List<SettlementDetailsPayload.BuildingEntry> buildings = new ArrayList<>(count);
                for (int i = 0; i < count; i++) buildings.add(SettlementDetailsPayload.readEntry(buffer));
                return new OverviewSnapshotPayload(core, List.copyOf(territory), List.copyOf(buildings));
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
