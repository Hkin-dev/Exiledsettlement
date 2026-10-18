package com.hkin.exiledsettlement.network;
import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
public record CommunityActionPayload(BlockPos core, String action) implements CustomPacketPayload {
    public static final Type<CommunityActionPayload> TYPE = new Type<>(ExiledSettlementMod.id("community_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CommunityActionPayload> STREAM_CODEC = StreamCodec.of(
            (b, p) -> { b.writeBlockPos(p.core); b.writeUtf(p.action, 32); },
            b -> new CommunityActionPayload(b.readBlockPos(), b.readUtf(32)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
