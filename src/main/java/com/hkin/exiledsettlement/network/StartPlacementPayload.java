package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.building.BuildingType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

public record StartPlacementPayload(
        BuildingType buildingType, int templateVersion, String displayTranslationKey,
        BlockPos corePosition, int boundaryRadiusChunks,
        List<SettlementDetailsPayload.BuildingEntry> blueprints
) implements CustomPacketPayload {
    public static final Type<StartPlacementPayload> TYPE = new Type<>(
            ExiledSettlementMod.id("start_building_placement")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, StartPlacementPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeUtf(payload.buildingType().serializedName());
                buffer.writeVarInt(payload.templateVersion());
                buffer.writeUtf(payload.displayTranslationKey());
                buffer.writeBlockPos(payload.corePosition());
                buffer.writeVarInt(payload.boundaryRadiusChunks());
                buffer.writeVarInt(payload.blueprints().size());
                payload.blueprints().forEach(entry -> SettlementDetailsPayload.writeEntry(buffer, entry));
            },
            buffer -> {
                BuildingType type = BuildingType.fromSerializedName(buffer.readUtf());
                int templateVersion = buffer.readVarInt();
                String displayTranslationKey = buffer.readUtf();
                BlockPos core = buffer.readBlockPos();
                int radius = buffer.readVarInt();
                int count = Math.min(1024, buffer.readVarInt());
                List<SettlementDetailsPayload.BuildingEntry> blueprints = new ArrayList<>(count);
                for (int i = 0; i < count; i++) blueprints.add(SettlementDetailsPayload.readEntry(buffer));
                return new StartPlacementPayload(type, templateVersion, displayTranslationKey,
                        core, radius, List.copyOf(blueprints));
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
