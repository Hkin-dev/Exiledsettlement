package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jetbrains.annotations.Nullable;

public record ContinuePlacementPayload(
        boolean road, @Nullable SettlementDetailsPayload.BuildingEntry blueprint
) implements CustomPacketPayload {
    public static final Type<ContinuePlacementPayload> TYPE = new Type<>(ExiledSettlementMod.id("continue_placement"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ContinuePlacementPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBoolean(payload.road());
                buffer.writeBoolean(payload.blueprint() != null);
                if (payload.blueprint() != null) SettlementDetailsPayload.writeEntry(buffer, payload.blueprint());
            },
            buffer -> {
                boolean road = buffer.readBoolean();
                SettlementDetailsPayload.BuildingEntry blueprint = buffer.readBoolean()
                        ? SettlementDetailsPayload.readEntry(buffer) : null;
                return new ContinuePlacementPayload(road, blueprint);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
