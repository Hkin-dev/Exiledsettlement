package com.hkin.exiledsettlement.network;
import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.*;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.*;
public record BuildingSummaryPagePayload(BlockPos core,UUID snapshot,int offset,int total,List<SettlementDetailsPayload.BuildingEntry> entries) implements CustomPacketPayload {
    public static final int PAGE_SIZE=128;
    public static final Type<BuildingSummaryPagePayload> TYPE=new Type<>(ExiledSettlementMod.id("building_summary_page"));
    public static final StreamCodec<RegistryFriendlyByteBuf,BuildingSummaryPagePayload> STREAM_CODEC=StreamCodec.of((b,p)->{
        b.writeBlockPos(p.core);b.writeUUID(p.snapshot);b.writeVarInt(p.offset);b.writeVarInt(p.total);b.writeVarInt(p.entries.size());
        if(p.entries.size()>PAGE_SIZE)throw new IllegalArgumentException("Summary page overflow");p.entries.forEach(e->SettlementDetailsPayload.writeEntry(b,e));
    },b->{var core=b.readBlockPos();var id=b.readUUID();int offset=b.readVarInt(),total=b.readVarInt(),count=b.readVarInt();if(count<0 || count>PAGE_SIZE)throw new IllegalArgumentException("Invalid summary page");var entries=new ArrayList<SettlementDetailsPayload.BuildingEntry>();for(int i=0;i<count;i++)entries.add(SettlementDetailsPayload.readEntry(b));return new BuildingSummaryPagePayload(core,id,offset,total,List.copyOf(entries));});
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
