package com.hkin.exiledsettlement.client;
import com.hkin.exiledsettlement.network.*;
import com.hkin.exiledsettlement.region.ProductionRegion;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;
/** Requested pages never put a whole region into one custom payload. */
public final class ClientRegionCache {
    private record Entry(ProductionRegion region,int revision,long requested,boolean complete) { }
    private static final Map<UUID,Entry> CACHE=new HashMap<>();
    public static void clear(){CACHE.clear();}
    public static ProductionRegion get(UUID id) {
        var level=Minecraft.getInstance().level;if(level==null)return null;long now=level.getGameTime();var old=CACHE.get(id);
        if(old==null || now-old.requested>=100) {
            CACHE.put(id,new Entry(old==null?null:old.region,old==null?-1:old.revision,now,old!=null && old.complete));
            PacketDistributor.sendToServer(new RequestRegionPagePayload(id,0,old!=null && old.complete?old.revision:-1));
        }
        return old!=null && old.complete?old.region:null;
    }
    public static void receive(RegionPagePayload p) {
        var level=Minecraft.getInstance().level;if(level==null)return;var old=CACHE.get(p.id());ProductionRegion r;
        if(p.offset()==-1) {
            if(old!=null && old.complete && old.revision==p.revision()){old.region.surveyed=p.surveyed();old.region.prepared=p.prepared();old.region.status=p.status();CACHE.put(p.id(),new Entry(old.region,p.revision(),level.getGameTime(),true));}return;
        }
        if(p.offset()==0)r=new ProductionRegion(p.buildingType(),p.first(),p.second(),p.facing());
        else {if(old==null || old.revision!=p.revision() || old.region==null || old.region.cells.size()!=p.offset())return;r=old.region;}
        for(var c:p.cells())r.cells.put(ProductionRegion.key(c.ground().getX(),c.ground().getZ()),c);
        r.stairs.putAll(p.stairs());r.legacyWater.addAll(p.legacyWater());r.waterBarriers.addAll(p.waterBarriers());
        r.surveyed=p.surveyed();r.prepared=p.prepared();r.status=p.status();r.invalidate();int next=p.offset()+p.cells().size();boolean done=next>=p.total();
        CACHE.put(p.id(),new Entry(r,p.revision(),level.getGameTime(),done));
        if(!done)PacketDistributor.sendToServer(new RequestRegionPagePayload(p.id(),next,p.revision()));
    }
}
