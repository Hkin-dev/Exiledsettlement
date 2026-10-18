package com.hkin.exiledsettlement.network;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Small server-authoritative calendar snapshot; never opens a screen. */
public record SeasonSnapshotPayload(String dimension, String name, int days, long offsetDays, boolean cycling,
                                    long stormStart, long stormEnd, float wind, float stormStrength) implements CustomPacketPayload {
    public SeasonSnapshotPayload(String dimension,String name,int days,long offsetDays,boolean cycling) {
        this(dimension,name,days,offsetDays,cycling,-1,-1,0,0);
    }
    public static final Type<SeasonSnapshotPayload> TYPE = new Type<>(ExiledSettlementMod.id("season_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SeasonSnapshotPayload> STREAM_CODEC = StreamCodec.of(
            (b, p) -> { b.writeUtf(p.dimension, 256); b.writeUtf(p.name, 64); b.writeVarInt(p.days); b.writeLong(p.offsetDays); b.writeBoolean(p.cycling);
                b.writeLong(p.stormStart);b.writeLong(p.stormEnd);b.writeFloat(p.wind);b.writeFloat(p.stormStrength); },
            b -> { String dimension = b.readUtf(256), name = b.readUtf(64); int days = b.readVarInt();
                if (days < 1 || days > 64) throw new IllegalArgumentException("Invalid season length");
                return new SeasonSnapshotPayload(dimension, name, days, b.readLong(), b.readBoolean(),b.readLong(),b.readLong(),b.readFloat(),b.readFloat()); });
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
