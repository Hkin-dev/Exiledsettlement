package com.hkin.exiledsettlement.mixin;

import com.hkin.exiledsettlement.client.ClientSeasonController;
import com.hkin.exiledsettlement.settlement.SeasonClimate;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(LevelRenderer.class)
public abstract class ClientSeasonWeatherMixin {
    @Redirect(method={"renderSnowAndRain","tickRain"},at=@At(value="INVOKE",target="Lnet/minecraft/world/level/biome/Biome;getPrecipitationAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;"))
    private Biome.Precipitation exiledsettlement$weather(Biome biome, BlockPos pos) {
        return SeasonClimate.precipitation(biome,pos,ClientSeasonController.weatherSeason());
    }
}
