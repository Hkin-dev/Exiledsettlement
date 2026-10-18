package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.blueprint.BlueprintRecord;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.List;

/** Live, narrowly scoped preparation of temporary access, independent of final structure clearance. */
public final class ScaffoldSitePreparation {
    private ScaffoldSitePreparation() { }

    public record Result(boolean changed, String failure) { }

    public static boolean softObstacle(BlockState state) {
        if (!state.getFluidState().isEmpty() || state.hasBlockEntity()) return false;
        if (state.is(Blocks.SNOW)) return true;
        return !(state.getBlock() instanceof CropBlock) && !(state.getBlock() instanceof StemBlock)
                && !(state.getBlock() instanceof AttachedStemBlock) && !(state.getBlock() instanceof SaplingBlock)
                && (state.is(BlockTags.FLOWERS)
                || state.getBlock() instanceof BushBlock && state.canBeReplaced());
    }

    public static boolean space(ServerLevel level, BlockPos position) {
        return level.hasChunkAt(position) && !level.isOutsideBuildHeight(position)
                && (level.getBlockState(position).isAir() || softObstacle(level.getBlockState(position)));
    }

    public static boolean headroom(ServerLevel level, BlockPos position) {
        if (!level.hasChunkAt(position) || level.isOutsideBuildHeight(position)) return false;
        BlockState state = level.getBlockState(position);
        return state.getFluidState().isEmpty() && (softObstacle(state)
                || state.is(Blocks.SCAFFOLDING) || state.getCollisionShape(level, position).isEmpty());
    }

    public static boolean allowed(ServerLevel level, SettlementRecord settlement, BlueprintRecord site, BlockPos p) {
        if (site.cancellationQueued() || !settlement.contains(p) || !level.hasChunkAt(p)
                || level.isOutsideBuildHeight(p)) return false;
        if (settlement.buildings().stream().anyMatch(b -> b.active() && b.contains(p))) return false;
        var own = BlueprintTemplates.create(site).placementAt(p);
        if (own != null && own.mode().isFinalStructure() && !own.state().isAir()) return false;
        for (BlueprintRecord other : settlement.blueprints()) {
            if (other.id().equals(site.id()) || other.cancellationQueued()) continue;
            var template = BlueprintTemplates.create(other);
            if (p.getX() >= template.minCorner().getX() && p.getX() <= template.maxCorner().getX()
                    && p.getY() >= template.minCorner().getY() && p.getY() <= template.maxCorner().getY()
                    && p.getZ() >= template.minCorner().getZ() && p.getZ() <= template.maxCorner().getZ()) return false;
            if (other.hasTemporaryFooting(p) || template.placementAt(p) != null) return false;
        }
        return true;
    }

    public static String failure(ServerLevel level, BlockPos p, String reason) {
        return "SCAFFOLD_ACCESS|" + reason + "|" + p.getX() + "|" + p.getY() + "|" + p.getZ()
                + "|" + BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock());
    }

    /** One paid work action clears at most one plant (including its paired half) or snow layer block. */
    public static Result prepare(ServerLevel level, SettlementSavedData data, SettlementRecord settlement,
            BlueprintRecord site, CitizenRecord citizen, SettlementCitizenEntity entity, BlockPos cell) {
        for (BlockPos p : List.of(cell, cell.above(), cell.above(2))) {
            if (!level.hasChunkAt(p)) return new Result(false, failure(level, p, "BLUEPRINT_AREA_NOT_LOADED"));
            BlockState state = level.getBlockState(p);
            if (!softObstacle(state)) continue;
            int toolSlot = citizen.findInventorySlot(s -> s.is(net.minecraft.tags.ItemTags.SHOVELS));
            ItemStack tool = state.is(Blocks.SNOW) && toolSlot >= 0 ? citizen.inventoryItem(toolSlot) : ItemStack.EMPTY;
            var cleared = com.hkin.exiledsettlement.blueprint.SiteClearance.clear(level, p, entity, tool,
                    part -> allowed(level, settlement, site, part) && softObstacle(level.getBlockState(part)));
            if (!cleared.failure().isEmpty()) return new Result(false, failure(level, p, cleared.failure()));
            for (ItemStack drop : cleared.drops()) {
                ItemStack excess = citizen.insertIntoInventory(drop);
                if (!excess.isEmpty()) Block.popResource(level, p, excess);
            }
            if (!tool.isEmpty()) citizen.damageInventoryItem(toolSlot, 1);
            entity.traceConstructionDecision("scaffold-clear:" + p + ":state=" + state);
            data.touch();
            return new Result(true, "");
        }
        return new Result(false, "");
    }
}
