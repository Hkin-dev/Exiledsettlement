package com.hkin.exiledsettlement.mixin;

import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerSeasonWeatherMixin {
    @Inject(method="tickPrecipitation",at=@At("HEAD"))
    private void exiledsettlement$seasonalAccumulation(BlockPos pos, CallbackInfo callback) {
        SeasonWeatherService.precipitationColumn((ServerLevel)(Object)this, pos);
    }
    @Redirect(method="tickPrecipitation",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/biome/Biome;getPrecipitationAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;"))
    private Biome.Precipitation exiledsettlement$seasonalPrecipitation(Biome biome, BlockPos pos) {
        var level=(ServerLevel)(Object)this;
        return level.dimension().equals(Level.OVERWORLD) ? SeasonClimate.precipitation(biome,pos,WorldSeasonData.get(level).season(level.getDayTime())) : biome.getPrecipitationAt(pos);
    }
}
