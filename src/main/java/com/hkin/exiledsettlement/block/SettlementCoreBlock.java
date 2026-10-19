package com.hkin.exiledsettlement.block;

import com.hkin.exiledsettlement.citizen.CitizenProjectionService;
import com.hkin.exiledsettlement.building.BuildingFunctionService;
import com.hkin.exiledsettlement.menu.SettlementCoreMenu;
import com.hkin.exiledsettlement.settlement.OperationResult;
import com.hkin.exiledsettlement.settlement.SettlementMessages;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public final class SettlementCoreBlock extends Block {
    public static final net.minecraft.world.level.block.state.properties.DirectionProperty FACING =
            net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
    public SettlementCoreBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, net.minecraft.world.level.block.Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, net.minecraft.world.level.block.Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected VoxelShape getShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context
    ) {
        return Blocks.LECTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LecternBlock.FACING,
                state.getValue(FACING)).getShape(level, pos, context);
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

        OperationResult<SettlementRecord> result = SettlementSavedData.get(serverLevel).createOrRecover(pos, player);
        if (!result.success() || result.value() == null) {
            SettlementMessages.sendFailure(player, result.reason());
            serverLevel.destroyBlock(pos, true);
            return;
        }

        CitizenProjectionService.ensureSettlementCitizens(serverLevel, result.value());
        player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable(
                        "message.exiledsettlement.created",
                        result.value().name(),
                        SettlementMessages.shortId(result.value().id())
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
            SettlementRecord settlement = SettlementSavedData.get(serverLevel).findByCore(pos).orElse(null);
            if (settlement == null) {
                SettlementMessages.sendFailure(serverPlayer, "CORE_INACTIVE");
            } else if (!settlement.isMember(serverPlayer.getUUID())) {
                SettlementMessages.sendFailure(serverPlayer, "NO_PERMISSION");
            } else {
                BuildingFunctionService.validateSettlement(serverLevel, SettlementSavedData.get(serverLevel), settlement);
                serverPlayer.openMenu(
                        new SimpleMenuProvider(
                                (containerId, inventory, menuPlayer) -> new SettlementCoreMenu(
                                        containerId,
                                        inventory,
                                        pos,
                                        settlement
                                ),
                                net.minecraft.network.chat.Component.translatable(
                                        "gui.exiledsettlement.core.title",
                                        settlement.name()
                                )
                        ),
                        buffer -> {
                            buffer.writeBlockPos(pos);
                            buffer.writeBlockPos(pos);
                        }
                );
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            SettlementSavedData.get(serverLevel).deactivateByCore(pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
