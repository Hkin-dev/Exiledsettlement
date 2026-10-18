package com.hkin.exiledsettlement.settlement;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Per-dimension, saved weather event. All durations are game ticks, never real wall-clock time. */
public final class BlizzardData extends SavedData {
    private static final Factory<BlizzardData> FACTORY=new Factory<>(BlizzardData::new,BlizzardData::load);
    public long start=-1, end=-1;
    public float direction, strength;
    private boolean rainSeen;
    public static BlizzardData get(ServerLevel level) { return level.getDataStorage().computeIfAbsent(FACTORY,"exiledsettlement_blizzards"); }
    public static float intensity(long now,long start,long end,float strength) {
        if(start<0 || now<start || now>=end) return 0;
        return strength * (float)Math.min(1,Math.min((now-start)/600.0,(end-now)/600.0));
    }
    public float intensity(ServerLevel level) { return intensity(level.getGameTime(),start,end,strength); }
    public void begin(ServerLevel level,int ticks,float power) {
        start=level.getGameTime()+1200; end=start+ticks;
        direction=level.random.nextFloat()*(float)(Math.PI*2); strength=Math.clamp(power,0.1F,1F);setDirty();
        for(var p:level.players())p.displayClientMessage(Component.literal("暴风雪将至：检查衣物、燃料和回家路线"),false);
        SeasonSyncService.tickLevel(level);
    }
    public static void tick(ServerLevel level) {
        if(!level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;
        // The weather flag changes before the visual rain level fades in. Using
        // Level.isRaining() here cancels a newly requested storm during that fade.
        var data=get(level);boolean raining=level.getLevelData().isRaining();long now=level.getGameTime();
        if(data.start>=0 && (!raining || now>=data.end || WorldSeasonData.get(level).season(level.getDayTime())!=3)) {
            data.start=-1;data.end=-1;data.strength=0;data.setDirty();SeasonSyncService.tickLevel(level);
        }
        if(raining!=data.rainSeen) {
            data.rainSeen=raining;data.setDirty();
            if(raining && data.start<0 && WorldSeasonData.get(level).season(level.getDayTime())==3
                    && level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE)
                    && level.random.nextFloat()<ThermalSettings.blizzardChance)
                data.begin(level,3600+level.random.nextInt(2401),1);
        }
    }
    public static BlizzardData load(CompoundTag t,HolderLookup.Provider registries) {
        var d=new BlizzardData();d.start=t.contains("Start")?t.getLong("Start"):-1;d.end=t.getLong("End");
        d.direction=Float.isFinite(t.getFloat("Direction"))?t.getFloat("Direction"):0;
        d.strength=Math.clamp(t.getFloat("Strength"),0,1);d.rainSeen=t.getBoolean("RainSeen");return d;
    }
    @Override public CompoundTag save(CompoundTag t,HolderLookup.Provider registries) {
        t.putInt("Version",1);t.putLong("Start",start);t.putLong("End",end);t.putFloat("Direction",direction);
        t.putFloat("Strength",strength);t.putBoolean("RainSeen",rainSeen);return t;
    }
}
