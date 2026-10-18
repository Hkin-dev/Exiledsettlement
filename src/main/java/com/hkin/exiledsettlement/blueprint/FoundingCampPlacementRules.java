package com.hkin.exiledsettlement.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Shared read-only terrain plan, executed instantly only by the one-time founding kit. */
public final class FoundingCampPlacementRules {
    private FoundingCampPlacementRules() { }
    public record Plan(List<BlueprintPlacement> placements, Set<BlockPos> fills,
                       Set<BlockPos> clears, BlockPos failurePosition, String reason) {
        public boolean valid() { return reason.isEmpty(); }
    }
    private static Plan failure(BlockPos pos, String reason) {
        return new Plan(List.of(), Set.of(), Set.of(), pos, reason);
    }
    private static String bounds(LevelReader level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return "CHUNK_NOT_LOADED";
        return level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)
                ? "FOUNDING_CAMP_SITE" : "";
    }
    private static boolean natural(LevelReader level, BlockPos pos, BlockState state) {
        if (state.hasBlockEntity() || state.getDestroySpeed(level,pos)<0) return false;
        if (!state.getFluidState().isEmpty()) return state.is(Blocks.WATER)
                || state.getFluidState().is(FluidTags.WATER) && state.canBeReplaced();
        return state.isAir() || state.canBeReplaced() || state.is(BlockTags.DIRT)
                || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.LOGS)
                || state.is(BlockTags.LEAVES) || state.is(BlockTags.SAND)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE)
                || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE);
    }
    public static Plan plan(LevelReader level, BuildingBlueprintTemplate template) {
        var targets=new LinkedHashMap<BlockPos,BlueprintPlacement>();
        var columns=new LinkedHashSet<BlockPos>();
        var fills=new LinkedHashSet<BlockPos>();
        var clears=new LinkedHashSet<BlockPos>();
        int ground=template.minCorner().getY();
        for(var p:template.placements()) {
            targets.put(p.position(),p);
            columns.add(new BlockPos(p.position().getX(),ground,p.position().getZ()));
        }
        // Include courtyard and entrance floors, not just the sparse saved structural floor.
        for(var top:columns) {
            BlockPos pos=top;
            boolean supported=false;
            while(pos.getY()>=level.getMinBuildHeight()) {
                String reason=bounds(level,pos);
                if(!reason.isEmpty()) return failure(pos,reason);
                var current=level.getBlockState(pos);
                boolean sturdy=current.isFaceSturdy(level,pos,Direction.UP)
                        && !current.is(BlockTags.LOGS) && !current.is(BlockTags.LEAVES)
                        && current.getFluidState().isEmpty();
                // Unchanged deeper support needs no excavation.
                if(pos.getY()<ground && sturdy) {supported=true;break;}
                if(!targets.containsKey(pos)) {
                    if(!natural(level,pos,current)) return failure(pos,"FOUNDING_CAMP_SITE");
                    var state=pos.getY()==ground?Blocks.COARSE_DIRT.defaultBlockState():Blocks.COBBLESTONE.defaultBlockState();
                    targets.put(pos,new BlueprintPlacement(pos,state,null));
                    fills.add(pos);
                }
                if(sturdy) {supported=true;break;}
                pos=pos.below();
            }
            if(!supported) return failure(top,"FOUNDING_CAMP_GROUND");
        }
        for(var placement:targets.values()) {
            var pos=placement.position();
            String reason=bounds(level,pos);
            if(!reason.isEmpty()) return failure(pos,reason);
            var current=level.getBlockState(pos);
            if(current.equals(placement.state())) continue;
            if(!natural(level,pos,current)) return failure(pos,"FOUNDING_CAMP_SITE");
            if(!current.isAir()) clears.add(pos);
        }
        // Install support bottom-up before furnishings and paired blocks.
        var ordered=new ArrayList<>(targets.values());
        ordered.sort(Comparator.comparingInt(p->p.position().getY()));
        return new Plan(List.copyOf(ordered),Set.copyOf(fills),Set.copyOf(clears),template.anchor(),"");
    }
}
