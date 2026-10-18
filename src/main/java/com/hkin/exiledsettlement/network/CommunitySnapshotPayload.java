package com.hkin.exiledsettlement.network;
import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.*;
public record CommunitySnapshotPayload(BlockPos core, String name, List<String> lines, Map<String, String> settings) implements CustomPacketPayload {
    public static final Type<CommunitySnapshotPayload> TYPE = new Type<>(ExiledSettlementMod.id("community_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CommunitySnapshotPayload> STREAM_CODEC = StreamCodec.of(
            (b, p) -> { b.writeBlockPos(p.core); b.writeUtf(p.name, 64); b.writeVarInt(p.lines.size()); for (String line : p.lines) b.writeUtf(line, 2048);
                b.writeVarInt(p.settings.size()); p.settings.forEach((key, value) -> { b.writeUtf(key, 32); b.writeUtf(value, 64); }); },
            b -> { BlockPos core = b.readBlockPos(); String name = b.readUtf(64); int count = b.readVarInt();
                if (count < 0 || count > 512) throw new IllegalArgumentException("Oversized community report");
                List<String> lines = new ArrayList<>(); for (int i = 0; i < count; i++) lines.add(b.readUtf(2048));
                int settingsCount = b.readVarInt();
                if (settingsCount < 0 || settingsCount > 32) throw new IllegalArgumentException("Oversized community settings");
                Map<String, String> settings = new LinkedHashMap<>();
                for (int i = 0; i < settingsCount; i++) settings.put(b.readUtf(32), b.readUtf(64));
                return new CommunitySnapshotPayload(core, name, List.copyOf(lines), Map.copyOf(settings)); });
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
