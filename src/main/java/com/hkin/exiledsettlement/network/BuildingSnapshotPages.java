package com.hkin.exiledsettlement.network;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;
/** One latest snapshot per player, sent in successive small packets, never truncated. */
public final class BuildingSnapshotPages {
    private static final Map<UUID,Job> JOBS=new HashMap<>();
    private static final class Job {
        final UUID id=UUID.randomUUID();final BlockPos core;final List<SettlementDetailsPayload.BuildingEntry> entries;int cursor;
        Job(BlockPos core,List<SettlementDetailsPayload.BuildingEntry> entries){this.core=core;this.entries=List.copyOf(entries);}
    }
    public static void send(ServerPlayer player,BlockPos core,CustomPacketPayload header,List<SettlementDetailsPayload.BuildingEntry> entries){PacketDistributor.sendToPlayer(player,header);JOBS.put(player.getUUID(),new Job(core,entries));}
    public static void tick(MinecraftServer server) {
        var iterator=JOBS.entrySet().iterator();while(iterator.hasNext()) {
            var entry=iterator.next();var player=server.getPlayerList().getPlayer(entry.getKey());if(player==null){iterator.remove();continue;}
            var job=entry.getValue();int end=Math.min(job.entries.size(),job.cursor+BuildingSummaryPagePayload.PAGE_SIZE);
            PacketDistributor.sendToPlayer(player,new BuildingSummaryPagePayload(job.core,job.id,job.cursor,job.entries.size(),job.entries.subList(job.cursor,end)));job.cursor=end;
            if(end>=job.entries.size())iterator.remove();
        }
    }
}
