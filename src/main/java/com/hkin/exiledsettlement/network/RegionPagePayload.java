package com.hkin.exiledsettlement.network;
import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.region.ProductionRegion;
import com.hkin.exiledsettlement.building.BuildingType;
import net.minecraft.core.*;
import net.minecraft.network.*;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.*;
public record RegionPagePayload(UUID id,int revision,int offset,int total,BuildingType buildingType,BlockPos first,BlockPos second,Direction facing,boolean surveyed,boolean prepared,String status,List<ProductionRegion.Cell> cells,Map<Long,Direction> stairs,Set<Long> legacyWater,Set<Long> waterBarriers) implements CustomPacketPayload {
    public static final Type<RegionPagePayload> TYPE=new Type<>(ExiledSettlementMod.id("region_page"));
    public static final int PAGE_SIZE=256;
    public static final StreamCodec<RegistryFriendlyByteBuf,RegionPagePayload> STREAM_CODEC=StreamCodec.of((b,p)->{
        b.writeUUID(p.id);b.writeVarInt(p.revision);b.writeVarInt(p.offset);b.writeVarInt(p.total);b.writeEnum(p.buildingType);b.writeBlockPos(p.first);b.writeBlockPos(p.second);b.writeEnum(p.facing);b.writeBoolean(p.surveyed);b.writeBoolean(p.prepared);b.writeUtf(p.status,512);
        if(p.cells.size()>PAGE_SIZE)throw new IllegalArgumentException("Region page overflow");b.writeVarInt(p.cells.size());
        for(var c:p.cells){b.writeBlockPos(c.ground());b.writeEnum(c.use());b.writeResourceLocation(c.production());b.writeEnum(c.type());long key=ProductionRegion.key(c.ground().getX(),c.ground().getZ());b.writeBoolean(p.stairs.containsKey(key));if(p.stairs.containsKey(key))b.writeEnum(p.stairs.get(key));b.writeBoolean(p.legacyWater.contains(key));b.writeBoolean(p.waterBarriers.contains(key));}
    },b->{
        var id=b.readUUID();int rev=b.readVarInt(),offset=b.readVarInt(),total=b.readVarInt();var type=b.readEnum(BuildingType.class);var first=b.readBlockPos();var second=b.readBlockPos();var facing=b.readEnum(Direction.class);boolean surveyed=b.readBoolean(),prepared=b.readBoolean();var status=b.readUtf(512);
        int count=b.readVarInt();if(count<0 || count>PAGE_SIZE)throw new IllegalArgumentException("Invalid region page");var cells=new ArrayList<ProductionRegion.Cell>(count);var stairs=new HashMap<Long,Direction>();var water=new HashSet<Long>();var barriers=new HashSet<Long>();
        for(int i=0;i<count;i++){var c=new ProductionRegion.Cell(b.readBlockPos(),b.readEnum(ProductionRegion.Use.class),b.readResourceLocation(),b.readEnum(BuildingType.class));cells.add(c);long key=ProductionRegion.key(c.ground().getX(),c.ground().getZ());if(b.readBoolean())stairs.put(key,b.readEnum(Direction.class));if(b.readBoolean())water.add(key);if(b.readBoolean())barriers.add(key);}
        return new RegionPagePayload(id,rev,offset,total,type,first,second,facing,surveyed,prepared,status,List.copyOf(cells),Map.copyOf(stairs),Set.copyOf(water),Set.copyOf(barriers));
    });
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
