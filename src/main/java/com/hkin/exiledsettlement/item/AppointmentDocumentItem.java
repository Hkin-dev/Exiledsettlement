package com.hkin.exiledsettlement.item;

import com.hkin.exiledsettlement.settlement.SettlementMessages;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Owner-signed document used directly on another player to grant settlement membership. */
public final class AppointmentDocumentItem extends Item {
    public AppointmentDocumentItem(Properties properties) {
        super(properties.stacksTo(16));
    }

    @Override
    public InteractionResult interactLivingEntity(
            ItemStack stack, Player user, LivingEntity target, InteractionHand hand
    ) {
        if (!(user.level() instanceof ServerLevel level)
                || !(user instanceof ServerPlayer owner)
                || !(target instanceof ServerPlayer appointed)) {
            return InteractionResult.PASS;
        }
        SettlementSavedData data = SettlementSavedData.get(level);
        SettlementRecord settlement = data.findContaining(owner.blockPosition())
                .filter(candidate -> candidate.isOwner(owner.getUUID()))
                .orElse(null);
        if (settlement == null) {
            SettlementMessages.sendFailure(owner, "APPOINTMENT_REQUIRES_OWNED_SETTLEMENT");
            return InteractionResult.FAIL;
        }
        if (!data.addMember(settlement, owner.getUUID(), appointed.getUUID())) {
            SettlementMessages.sendFailure(owner, "MEMBER_CHANGE_FAILED");
            return InteractionResult.FAIL;
        }
        if (!owner.isCreative()) stack.shrink(1);
        owner.displayClientMessage(Component.translatable("message.exiledsettlement.member.added",
                appointed.getGameProfile().getName(), settlement.name()), false);
        appointed.displayClientMessage(Component.translatable("message.exiledsettlement.member.joined",
                settlement.name()), false);
        return InteractionResult.SUCCESS;
    }
}
