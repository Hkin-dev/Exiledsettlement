package com.hkin.exiledsettlement.settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;

/** Shared by the server precipitation hook and the vanilla client weather renderer. */
public final class SeasonClimate {
    private SeasonClimate() { }
    public static Biome.Precipitation precipitation(Biome biome, BlockPos pos, int season) {
        var original = biome.getPrecipitationAt(pos);
        return seasonalSnow(biome, season) && original == Biome.Precipitation.RAIN ? Biome.Precipitation.SNOW : original;
    }
    public static boolean seasonalSnow(Biome biome, int season) {
        return season == 3 && biome.hasPrecipitation() && SeasonVisualPalette.temperate(biome.getBaseTemperature());
    }
}
