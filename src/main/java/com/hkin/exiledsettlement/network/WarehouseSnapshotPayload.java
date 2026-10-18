package com.hkin.exiledsettlement.network;
import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import java.util.*;
public record WarehouseSnapshotPayload(BlockPos core,int warehouse,int warehouses,String name,long used,long capacity,int page,int pages,String query,String feedback,boolean home,List<Row> rows) implements CustomPacketPayload {
    public record Row(String item,ItemStack sample,long count,int keep,boolean discard,boolean mixed) {}
    public static final Type<WarehouseSnapshotPayload> TYPE=new Type<>(ExiledSettlementMod.id("warehouse_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf,WarehouseSnapshotPayload> STREAM_CODEC=StreamCodec.of(
        (b,p)->{b.writeBlockPos(p.core);b.writeInt(p.warehouse);b.writeInt(p.warehouses);b.writeUtf(p.name,256);b.writeLong(p.used);b.writeLong(p.capacity);b.writeInt(p.page);b.writeInt(p.pages);b.writeUtf(p.query,128);b.writeUtf(p.feedback,256);b.writeBoolean(p.home);b.writeVarInt(p.rows.size());
            for(var r:p.rows){b.writeUtf(r.item,256);ItemStack.STREAM_CODEC.encode(b,r.sample);b.writeLong(r.count);b.writeInt(r.keep);b.writeBoolean(r.discard);b.writeBoolean(r.mixed);}},
        b->{var core=b.readBlockPos();int warehouse=b.readInt(),warehouses=b.readInt();var name=b.readUtf(256);long used=b.readLong(),capacity=b.readLong();int page=b.readInt(),pages=b.readInt();var query=b.readUtf(128);var feedback=b.readUtf(256);boolean home=b.readBoolean();int n=b.readVarInt();if(n<0 || n>10)throw new IllegalArgumentException("Warehouse page too large");var rows=new ArrayList<Row>();
            for(int i=0;i<n;i++)rows.add(new Row(b.readUtf(256),ItemStack.STREAM_CODEC.decode(b),b.readLong(),b.readInt(),b.readBoolean(),b.readBoolean()));return new WarehouseSnapshotPayload(core,warehouse,warehouses,name,used,capacity,page,pages,query,feedback,home,List.copyOf(rows));});
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
