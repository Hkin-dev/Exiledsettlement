package com.hkin.exiledsettlement.settlement;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** A paid fuel unit heats one home for a bounded time, shared by its occupants. */
public final class HomeHeatData extends SavedData {
    private static final Factory<HomeHeatData> FACTORY=new Factory<>(HomeHeatData::new,HomeHeatData::load);
    private final Map<UUID,Long> heated=new HashMap<>();
    public static HomeHeatData get(ServerLevel level) {return level.getDataStorage().computeIfAbsent(FACTORY,"exiledsettlement_home_heat");}
    public boolean warm(UUID id,long now) {return id!=null && heated.getOrDefault(id,-1L)>now;}
    public void light(UUID id,long now) {if(id!=null){heated.put(id,now+ThermalSettings.fuelHeatTicks);setDirty();}}
    public void clean(Set<UUID> active,long now) {if(heated.entrySet().removeIf(e->!active.contains(e.getKey()) || e.getValue()<=now))setDirty();}
    private static HomeHeatData load(CompoundTag t,HolderLookup.Provider r) {
        var d=new HomeHeatData();var list=t.getList("Homes",Tag.TAG_COMPOUND);
        for(int i=0;i<list.size();i++){var row=list.getCompound(i);if(row.hasUUID("Id"))d.heated.put(row.getUUID("Id"),row.getLong("Until"));}return d;
    }
    @Override public CompoundTag save(CompoundTag t,HolderLookup.Provider r) {
        var list=new ListTag();heated.forEach((id,until)->{var row=new CompoundTag();row.putUUID("Id",id);row.putLong("Until",until);list.add(row);});
        t.putInt("Version",1);t.put("Homes",list);return t;
    }
}
