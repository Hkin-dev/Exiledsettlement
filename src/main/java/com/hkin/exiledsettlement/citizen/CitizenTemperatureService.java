package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import java.util.*;

public final class CitizenTemperatureService {
    private CitizenTemperatureService() { }
    public static boolean sheltered(ServerLevel level,BlockPos feet) {
        return !level.canSeeSky(feet.above()) && level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,feet.getX(),feet.getZ())>feet.getY()+1;
    }
    public static float ambient(ServerLevel level,BlockPos at) {
        int season=WorldSeasonData.get(level).season(level.getDayTime());
        float base=level.getBiome(at).value().getBaseTemperature();
        float temp=ThermalSettings.ambient[season]+(base-.8F)*18;
        if(Math.floorMod(level.getDayTime(),24000)>12000)temp-=4;
        temp-=Math.max(0,at.getY()-64)*.04F;
        return temp;
    }
    public static float storm(ServerLevel level,BlockPos at) {
        if(!level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD) || !level.isRaining())return 0;
        int season=WorldSeasonData.get(level).season(level.getDayTime());
        return SeasonClimate.precipitation(level.getBiome(at).value(),at,season)==Biome.Precipitation.SNOW?BlizzardData.get(level).intensity(level):0;
    }
    public static void tick(ServerLevel level,SettlementSavedData data,SettlementRecord s) {
        var heat=HomeHeatData.get(level);long now=level.getGameTime();
        for(var c:s.citizens()) {
            if(!c.alive())continue;var e=CitizenProjectionService.findProjection(level,c);if(e==null || !e.isAlive())continue;
            if(com.hkin.exiledsettlement.debug.ForceWorkDebugService.forced(c)){c.thermal().lastTick=now;c.thermal().lastDayTime=level.getDayTime();continue;}
            var t=c.thermal();var at=e.blockPosition();long elapsed=t.lastTick<0?100:Math.clamp(now-t.lastTick,0,200);
            long dayDelta=t.lastDayTime<0?0:level.getDayTime()-t.lastDayTime;
            if(dayDelta>elapsed+200)elapsed+=Math.min(600,dayDelta-elapsed);
            t.lastTick=now;t.lastDayTime=level.getDayTime();t.shelter=sheltered(level,at);t.ambient=ambient(level,at);
            var home=s.building(c.homeBuildingId());
            boolean insideHome=home!=null && home.active() && home.operational() && home.contains(at) && t.shelter;
            if(insideHome && t.ambient<12 && !heat.warm(home.id(),now) && c.hasInventoryItem(st->st.is(net.minecraft.world.item.Items.COAL)||st.is(net.minecraft.world.item.Items.CHARCOAL))) {
                c.extractInventoryItem(st->st.is(net.minecraft.world.item.Items.COAL)||st.is(net.minecraft.world.item.Items.CHARCOAL),1);
                heat.light(home.id(),now);s.community().coldFuelBurned++;
            }
            float wind=t.shelter?0:storm(level,at);
            var precip=SeasonClimate.precipitation(level.getBiome(at).value(),at,WorldSeasonData.get(level).season(level.getDayTime()));
            boolean exposed=!t.shelter && level.isRaining() && precip!=Biome.Precipitation.NONE;
            t.wet=Math.clamp(t.wet+(exposed?.025F:-.015F)*(elapsed/100F),0,1);
            float environment=t.ambient;
            if(t.shelter)environment=Math.max(5,environment+6);
            if(insideHome && heat.warm(home.id(),now))environment=Math.max(20,environment);
            float insulation=CitizenClothingService.insulation(t.garment,t.wet,wind);
            float felt=environment-wind*12-t.wet*8+insulation;
            float target=37-Math.max(0,12-felt)*.11F+Math.max(0,felt-32)*.03F;
            target=Math.clamp(target,30,40);
            float rate=target>=t.body?.07F:ThermalSettings.coolingStep;
            t.change=Math.clamp((target-t.body)*.025F,-rate,rate)*(elapsed/100F);
            t.body=Math.clamp(t.body+t.change,30,42);
            t.warming=t.body<ThermalSettings.retreat || t.warming && t.body<ThermalSettings.resume;
            t.reason=(t.shelter?"有遮蔽":"室外暴露")+(insideHome && heat.warm(home.id(),now)?" · 住宅供热":"")
                    +(wind>.1F?" · 暴风雪":"")+(t.wet>.3F?" · 衣物潮湿":"");
            if(!t.garment.isEmpty()) {
                t.wear+=elapsed/100.0*(1+Math.min(1,wind+t.wet*.5));int wear=(int)t.wear;t.wear-=wear;
                if(wear>0){t.garment.setDamageValue(t.garment.getDamageValue()+wear);if(t.garment.getDamageValue()>=t.garment.getMaxDamage()) {
                    t.garment=net.minecraft.world.item.ItemStack.EMPTY;t.reason+=" · 衣物已损坏";
                }}
            }
            e.setVisibleGarment(CitizenClothingService.kind(t.garment));
            if(t.body<ThermalSettings.injury)e.hurt(level.damageSources().freeze(),.5F);
            CitizenInteractionService.updateThermal(e,c);
            data.touch();
        }
    }
}
