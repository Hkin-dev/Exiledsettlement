package com.hkin.exiledsettlement.block;

import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingFunctionService;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.menu.SettlementCoreMenu;
import com.hkin.exiledsettlement.settlement.OperationResult;
import com.hkin.exiledsettlement.settlement.SettlementMessages;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class SettlementBuildingBlock extends Block {
    private final BuildingType buildingType;

    public SettlementBuildingBlock(BuildingType buildingType, BlockBehaviour.Properties properties) {
        super(properties);
        this.buildingType = buildingType;
    }

    public BuildingType buildingType() {
        return buildingType;
    }

    @Override
    protected VoxelShape getShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context
    ) {
        return buildingType == BuildingType.TOWN_HALL
                ? Blocks.LECTERN.defaultBlockState().getShape(level, pos, context)
                : super.getShape(state, level, pos, context);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(placer instanceof ServerPlayer player)) {
            serverLevel.destroyBlock(pos, false);
            return;
        }

        OperationResult<BuildingRecord> result = SettlementSavedData.get(serverLevel)
                .registerBuilding(pos, buildingType, player.getUUID());
        if (!result.success()) {
            SettlementMessages.sendFailure(player, result.reason());
            serverLevel.destroyBlock(pos, true);
            return;
        }
        SettlementRecord settlement = SettlementSavedData.get(serverLevel)
                .findByBuildingPosition(pos)
                .orElse(null);
        if (settlement != null && result.value() != null) {
            BuildingFunctionService.validateSingle(
                    serverLevel,
                    SettlementSavedData.get(serverLevel),
                    settlement,
                    result.value()
            );
        }
        player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable(
                        "message.exiledsettlement.building.registered",
                        net.minecraft.network.chat.Component.translatable(buildingType.translationKey())
                ),
                false
        );
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hitResult
    ) {
        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            SettlementSavedData data = SettlementSavedData.get(serverLevel);
            SettlementRecord settlement = data.findByBuildingPosition(pos).orElse(null);
            if (settlement == null) {
                SettlementMessages.sendFailure(serverPlayer, "UNREGISTERED_BUILDING");
            } else if (!settlement.isMember(serverPlayer.getUUID())) {
                SettlementMessages.sendFailure(serverPlayer, "NO_PERMISSION");
            } else {
                BuildingRecord building = settlement.buildingAt(pos);
                if (building != null) {
                    BuildingFunctionService.validateSingle(serverLevel, data, settlement, building);
                    if (buildingType == BuildingType.TOWN_HALL && building.operational()) {
                        serverPlayer.openMenu(new SimpleMenuProvider(
                                (containerId, inventory, menuPlayer) -> new SettlementCoreMenu(
                                        containerId, inventory, settlement.corePosition(), pos, settlement),
                                net.minecraft.network.chat.Component.translatable(
                                        "gui.exiledsettlement.town_hall.title", settlement.name())
                        ), buffer -> {
                            buffer.writeBlockPos(settlement.corePosition());
                            buffer.writeBlockPos(pos);
                        });
                    } else {
                        SettlementMessages.sendBuildingSummary(serverPlayer, settlement, building);
                    }
                }
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            SettlementSavedData data = SettlementSavedData.get(serverLevel);
            SettlementRecord settlement = data.findByBuildingPosition(pos).orElse(null);
            if (settlement == null || !settlement.retiredBlockPositions().contains(pos)) {
                data.unregisterBuilding(pos);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
