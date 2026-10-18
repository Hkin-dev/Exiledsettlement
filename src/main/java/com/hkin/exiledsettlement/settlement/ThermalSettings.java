package com.hkin.exiledsettlement.settlement;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Game balance, not a medical model. Bundled values may be overridden through community thermal.json. */
public final class ThermalSettings {
    public static float[] ambient={14,26,10,-8};
    public static float warning=36, retreat=35, injury=33, resume=36.5F;
    public static float wool=14, leather=7, blizzardChance=.2F, coolingStep=.03F;
    public static int fuelHeatTicks=6000, tailorTicks=400, garmentDurability=7200;
    private ThermalSettings() { }
    public static void reload(JsonObject t) {
        float[] a=new float[4];var values=t.getAsJsonArray("season_ambient_celsius");
        if(values.size()!=4)throw new IllegalArgumentException("Four seasonal temperatures required");
        for(int i=0;i<4;i++)a[i]=number(values.get(i).getAsFloat(),-50,50);
        float warn=number(t.get("warning_celsius").getAsFloat(),30,38),ret=number(t.get("retreat_celsius").getAsFloat(),30,warn);
        float hurt=number(t.get("injury_celsius").getAsFloat(),25,ret),back=number(t.get("resume_celsius").getAsFloat(),warn,38);
        float w=number(t.get("wool_insulation").getAsFloat(),0,40),l=number(t.get("leather_insulation").getAsFloat(),0,40);
        float chance=number(t.get("blizzard_chance").getAsFloat(),0,1),step=number(t.get("maximum_cooling_per_100_ticks").getAsFloat(),.001F,.1F);
        int heat=Math.clamp(t.get("fuel_heat_ticks").getAsInt(),100,24000),craft=Math.clamp(t.get("tailor_ticks").getAsInt(),100,2400);
        ambient=a;warning=warn;retreat=ret;injury=hurt;resume=back;wool=w;leather=l;blizzardChance=chance;coolingStep=step;
        fuelHeatTicks=heat;tailorTicks=craft;
    }
    private static float number(float n,float low,float high) {
        if(!Float.isFinite(n)||n<low||n>high)throw new IllegalArgumentException("Invalid thermal balance value");return n;
    }
}
