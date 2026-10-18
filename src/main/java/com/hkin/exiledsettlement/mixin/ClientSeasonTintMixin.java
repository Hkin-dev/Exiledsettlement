package com.hkin.exiledsettlement.mixin;

import com.hkin.exiledsettlement.client.ClientSeasonController;
import com.hkin.exiledsettlement.settlement.SeasonVisualPalette;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLevel.class)
public abstract class ClientSeasonTintMixin {
    @Inject(method = "calculateBlockTint", at = @At("RETURN"), cancellable = true)
    private void exiledsettlement$seasonTint(BlockPos position, ColorResolver resolver, CallbackInfoReturnable<Integer> result) {
        int season = ClientSeasonController.visualSeason();
        if (season < 0 || (resolver != BiomeColors.GRASS_COLOR_RESOLVER && resolver != BiomeColors.FOLIAGE_COLOR_RESOLVER)) return;
        var level = (ClientLevel) (Object) this;
        result.setReturnValue(SeasonVisualPalette.tintTransition(result.getReturnValue(), season, ClientSeasonController.blendStep(),
                resolver == BiomeColors.FOLIAGE_COLOR_RESOLVER, level.getBiome(position).value().getBaseTemperature()));
    }
}
