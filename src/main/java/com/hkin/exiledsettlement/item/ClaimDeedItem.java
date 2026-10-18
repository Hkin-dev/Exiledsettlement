package com.hkin.exiledsettlement.item;

import com.hkin.exiledsettlement.settlement.OperationResult;
import com.hkin.exiledsettlement.settlement.SettlementMessages;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;

/** Expands an owned settlement by exactly one orthogonally adjacent neutral chunk. */
public final class ClaimDeedItem extends Item {
    public ClaimDeedItem(Properties properties) {
        super(properties.stacksTo(16));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)
                || !(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
        }
        OperationResult<SettlementRecord> result = SettlementSavedData.get(level)
                .claimChunk(context.getClickedPos(), player.getUUID());
        if (!result.success()) {
            SettlementMessages.sendFailure(player, result.reason());
            return InteractionResult.FAIL;
        }
        if (!player.isCreative()) context.getItemInHand().shrink(1);
        ChunkPos chunk = new ChunkPos(context.getClickedPos());
        player.displayClientMessage(Component.translatable(
                "message.exiledsettlement.land_claimed", chunk.x, chunk.z, result.value().name()), false);
        return InteractionResult.SUCCESS;
    }
}
