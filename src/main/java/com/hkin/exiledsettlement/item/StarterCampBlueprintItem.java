package com.hkin.exiledsettlement.item;

import com.hkin.exiledsettlement.settlement.FoundingService;
import com.hkin.exiledsettlement.settlement.SettlementMessages;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/** Starts the normal positioning preview before any settlement exists. */
public final class StarterCampBlueprintItem extends Item {
    public StarterCampBlueprintItem(Properties properties) { super(properties.stacksTo(1)); }
    @Override public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().isClientSide()) return InteractionResult.SUCCESS;
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.FAIL;
        var result=FoundingService.beginCamp(player);
        if (!result.success()) { SettlementMessages.sendFailure(player,result.reason());return InteractionResult.FAIL; }
        return InteractionResult.SUCCESS;
    }
}
