package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record BuildingActionPayload(
        BlockPos corePosition, UUID buildingId, Action action, int value,
        int expectedRevision, ResourceLocation itemId
) implements CustomPacketPayload {
    public static final Type<BuildingActionPayload> TYPE = new Type<>(ExiledSettlementMod.id("building_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingActionPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBlockPos(payload.corePosition());
                buffer.writeUUID(payload.buildingId());
                buffer.writeVarInt(payload.action().ordinal());
                buffer.writeVarInt(payload.value());
                buffer.writeVarInt(payload.expectedRevision());
                buffer.writeResourceLocation(payload.itemId());
            },
            buffer -> new BuildingActionPayload(buffer.readBlockPos(), buffer.readUUID(),
                    Action.values()[Math.max(0, Math.min(Action.values().length - 1, buffer.readVarInt()))],
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readResourceLocation()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public enum Action {
        QUEUE_DEMOLITION,
        DEPOSIT_ALL,
        WITHDRAW_STACK,
        SET_TOOL_LIMIT,
        SET_EQUIPMENT_LIMIT,
        TOGGLE_CATEGORY,
        SET_PRODUCTION,
        CANCEL_CONSTRUCTION,
        SET_WORKER_TARGET,
        SET_WORKSITE_MODE,
        TOGGLE_GROUND,
        SET_BREW_STOCK_TARGET
    }
}
