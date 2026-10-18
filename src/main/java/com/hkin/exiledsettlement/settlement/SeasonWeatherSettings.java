package com.hkin.exiledsettlement.settlement;

import com.google.gson.JsonObject;

/** Durations are Minecraft ticks. Validated atomically when the community data pack reloads. */
public final class SeasonWeatherSettings {
    private SeasonWeatherSettings() { }
    public static int[] clearMin = {12000, 24000, 18000, 6000}, clearMax = {24000, 48000, 36000, 12000};
    public static int[] rainMin = {6000, 3000, 6000, 6000}, rainMax = {12000, 9000, 12000, 12000};
    public static int writeBudget = 64, meltBudget = 128;
    public static void reload(JsonObject json) {
        int[][] values = new int[4][4]; String[] names = {"spring", "summer", "autumn", "winter"};
        for (int i=0;i<4;i++) {
            var season = json.getAsJsonObject(names[i]);
            values[0][i] = range(season,"clear_min_ticks",200,240000);
            values[1][i] = range(season,"clear_max_ticks",values[0][i],240000);
            values[2][i] = range(season,"rain_min_ticks",200,240000);
            values[3][i] = range(season,"rain_max_ticks",values[2][i],240000);
        }
        int writes = range(json,"block_write_budget_per_tick",1,256), melts = range(json,"melt_checks_per_second",1,1024);
        clearMin=values[0]; clearMax=values[1]; rainMin=values[2]; rainMax=values[3]; writeBudget=writes; meltBudget=melts;
    }
    private static int range(JsonObject json,String key,int minimum,int maximum) {
        int value=json.get(key).getAsInt(); if(value<minimum||value>maximum) throw new IllegalArgumentException(key+" out of range"); return value;
    }
}
