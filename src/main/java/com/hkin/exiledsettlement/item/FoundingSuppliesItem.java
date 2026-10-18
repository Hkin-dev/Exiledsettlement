package com.hkin.exiledsettlement.item;

import com.hkin.exiledsettlement.settlement.FoundingService;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

public final class FoundingSuppliesItem extends Item {
    public FoundingSuppliesItem(Properties properties) { super(properties); }
    @Override public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().isClientSide()) return InteractionResult.SUCCESS;
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.FAIL;
        if (!FoundingService.redeem(player, context.getClickedPos())) {
            player.displayClientMessage(Component.literal("需要未使用的开拓资格，以及自己聚落内尚未登记的空木桶；每名玩家仅能兑换一次。"), true);
            return InteractionResult.FAIL;
        }
        context.getItemInHand().shrink(1); return InteractionResult.CONSUME;
    }
}
