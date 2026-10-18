package com.hkin.exiledsettlement.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import java.util.*;
import java.util.function.Predicate;

/** Real clearance shared by buildings, ground works and temporary access. */
public final class SiteClearance {
    private SiteClearance() { }
    public record Result(List<ItemStack> drops, List<BlockPos> removed, String failure) { }
    private static Result failed(String reason) { return new Result(List.of(), List.of(), reason); }

    public static Result clear(ServerLevel level, BlockPos at, LivingEntity worker, ItemStack tool,
                               Predicate<BlockPos> allowed) {
        return clear(level,at,worker,tool,allowed,state -> false);
    }
    public static Result clear(ServerLevel level, BlockPos at, LivingEntity worker, ItemStack tool,
                               Predicate<BlockPos> allowed, Predicate<BlockState> wetNaturalAllowed) {
        return com.hkin.exiledsettlement.citizen.ResidentBlockDrops.run(level, worker, () -> clearDirect(level, at, worker, tool, allowed, wetNaturalAllowed), result -> result.failure().isEmpty());
    }

    private static Result clearDirect(ServerLevel level, BlockPos at, LivingEntity worker, ItemStack tool,
                               Predicate<BlockPos> allowed, Predicate<BlockState> wetNaturalAllowed) {
        if (!level.hasChunkAt(at) || level.isOutsideBuildHeight(at)) return failed("BLUEPRINT_AREA_NOT_LOADED");
        BlockState state = level.getBlockState(at);
        if (state.isAir()) return new Result(List.of(), List.of(), "");
        var tree=com.hkin.exiledsettlement.citizen.ResidentTreeFelling.clear(level,at,worker,tool,allowed,state.getFluidState().createLegacyBlock());
        if(tree!=null)return tree;
        var parts = new ArrayList<BlockPos>(List.of(at));
        BlockPos loot = at;
        if (state.getBlock() instanceof DoublePlantBlock && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            boolean lower = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
            BlockPos otherPos = lower ? at.above() : at.below();
            if (!level.hasChunkAt(otherPos)) return failed("BLUEPRINT_AREA_NOT_LOADED");
            var other = level.getBlockState(otherPos);
            if (other.getBlock() == state.getBlock() && other.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    && other.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
                parts.add(otherPos); loot = lower ? at : otherPos;
            }
        }
        var before = parts.stream().map(level::getBlockState).toList();
        for (int i = 0; i < parts.size(); i++) {
            var p = parts.get(i); var current = before.get(i);
            if (level.isOutsideBuildHeight(p) || !allowed.test(p)) return failed("PROTECTED_AREA");
            if (!current.getFluidState().isEmpty() && !wetNaturalAllowed.test(current)) return failed("FARM_EARTHWORK_FLUID");
            if (current.getDestroySpeed(level, p) < 0 || !CommonHooks.canEntityDestroy(level, p, worker)) return failed("PROTECTED_AREA");
        }
        for (int i = 0; i < parts.size(); i++) if (!level.getBlockState(parts.get(i)).equals(before.get(i)))
            return failed("CONSTRUCTION_TARGET_CHANGED");
        var drops = Block.getDrops(level.getBlockState(loot), level, loot, level.getBlockEntity(loot), worker, tool);
        if (parts.size() == 1) {
            if (!level.destroyBlock(at, false, worker)) return failed("PROTECTED_AREA");
        } else {
            int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
            var snapshots = parts.stream().map(p -> BlockSnapshot.create(level.dimension(), level, p, flags)).toList();
            for (var p : parts) if (!level.setBlock(p, Blocks.AIR.defaultBlockState(), flags)) {
                for (int i = snapshots.size() - 1; i >= 0; i--) snapshots.get(i).restore(flags);
                return failed("CONSTRUCTION_TARGET_CHANGED");
            }
            // Remove both halves before survival updates, then award the lower-half loot once.
            for (int i = 0; i < parts.size(); i++) level.updateNeighborsAt(parts.get(i), before.get(i).getBlock());
        }
        return new Result(List.copyOf(drops), List.copyOf(parts), "");
    }
}
