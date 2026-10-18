package com.hkin.exiledsettlement.mixin;

import com.hkin.exiledsettlement.client.ysm.YsmPlayerStateRegistry;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Associates each client player, including resident render proxies, with YSM's live state. */
@Mixin(targets = "com.hkin.exiledsettlement.compat.ysm.YsmPlayerStateCapabilityAlias", remap = false)
public abstract class YsmPlayerStateCapabilityMixin {
    @Inject(
            method = "<init>(Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("RETURN"),
            remap = false
    )
    private void exiledSettlement$capturePlayer(Player player, CallbackInfo callback) {
        YsmPlayerStateRegistry.register(player, this);
    }
}
