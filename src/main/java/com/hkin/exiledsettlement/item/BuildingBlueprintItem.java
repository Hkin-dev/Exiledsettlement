package com.hkin.exiledsettlement.item;

import com.hkin.exiledsettlement.blueprint.BlueprintRecord;
import com.hkin.exiledsettlement.blueprint.BlueprintService;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.settlement.OperationResult;
import com.hkin.exiledsettlement.settlement.SettlementMessages;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

import java.util.List;

public final class BuildingBlueprintItem extends Item {
    private final BuildingType buildingType;

    public BuildingBlueprintItem(BuildingType buildingType, Properties properties) {
        super(properties.stacksTo(16));
        this.buildingType = buildingType;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)
                || !(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
        }
        BlockPos anchor = context.getClickedPos().relative(context.getClickedFace());
        OperationResult<BlueprintRecord> result = BlueprintService.placeBlueprint(
                level,
                player.getUUID(),
                buildingType,
                anchor,
                player.getDirection(),
                !player.isCreative(),
                player
        );
        if (!result.success()) {
            SettlementMessages.sendFailure(player, result.reason());
            return InteractionResult.FAIL;
        }
        if (!player.isCreative()) {
            context.getItemInHand().shrink(1);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.exiledsettlement.blueprint.place").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.exiledsettlement.blueprint.confirm").withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.exiledsettlement.blueprint.cancel").withStyle(ChatFormatting.DARK_GRAY));
    }
}
