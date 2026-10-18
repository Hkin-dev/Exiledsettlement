package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record SetCitizenYsmModelPayload(
        UUID settlementId, UUID citizenId, String modelId, String textureName
) implements CustomPacketPayload {
    public static final Type<SetCitizenYsmModelPayload> TYPE =
            new Type<>(ExiledSettlementMod.id("set_citizen_ysm_model"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetCitizenYsmModelPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeUUID(payload.settlementId());
                        buffer.writeUUID(payload.citizenId());
                        buffer.writeUtf(payload.modelId(), 256);
                        buffer.writeUtf(payload.textureName(), 128);
                    },
                    buffer -> new SetCitizenYsmModelPayload(
                            buffer.readUUID(), buffer.readUUID(),
                            buffer.readUtf(256), buffer.readUtf(128)));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
