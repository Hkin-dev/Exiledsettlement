package com.hkin.exiledsettlement.blueprint;

import com.hkin.exiledsettlement.building.BuildingType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic, template-bound transition-zone details for the newly promoted
 * expansion snapshots. The anchor is the seed: preview, construction, repair
 * and reload always compile the same modules without another save-only roll.
 */
final class ExpansionTemplateEnhancements {
    private ExpansionTemplateEnhancements() { }

    static BuildingBlueprintTemplate apply(BuildingBlueprintTemplate template, int templateVersion) {
        // Freeze the historical expansion geometry when a newer snapshot is promoted.
        int enhancedVersion = switch (template.type()) {
            case HOUSE_3, ADVANCED_HOUSE_1, ADVANCED_HOUSE_3 -> 3;
            case ADVANCED_HOUSE_2 -> 4;
            default -> 1;
        };
        if (templateVersion != enhancedVersion
                || template.transitionMargin() < 2 || template.entrances().isEmpty()
                || !isNewExpansionSnapshot(template.type())) return template;

        List<BlueprintPlacement> placements = new ArrayList<>(template.placements());
        Set<BlockPos> occupied = new HashSet<>();
        placements.forEach(placement -> occupied.add(placement.position()));
        BlueprintEntrance entrance = template.entrances().getFirst();
        Direction outward = entrance.outward();

        // Homes and the starter camp receive a two-block material-paid gravel
        // connection. It is part of the preview and construction snapshot, not
        // a free post-completion world edit.
        if (template.type().isHouse() || template.type() == BuildingType.STARTER_CAMP) {
            for (int distance = 1; distance <= 2; distance++) {
                replace(placements, occupied, entrance.position().below().relative(outward, distance),
                        Blocks.GRAVEL.defaultBlockState());
            }
        }

        long seed = template.anchor().asLong() ^ ((long) template.type().ordinal() << 48)
                ^ ((long) template.facing().ordinal() << 56);
        addDecoration(placements, occupied,
                exteriorPosition(template, entrance, 2),
                (int) Math.floorMod(seed, 4), outward);
        addDecoration(placements, occupied,
                exteriorPosition(template, entrance, -2),
                (int) Math.floorMod(seed >>> 7, 4), outward);

        return new BuildingBlueprintTemplate(template.type(), template.anchor(), template.facing(),
                template.width(), template.depth(), template.height(), template.minCorner(),
                template.maxCorner(), template.transitionMargin(), placements, template.entrances());
    }

    private static boolean isNewExpansionSnapshot(BuildingType type) {
        return switch (type) {
            case HOUSE_3, ADVANCED_HOUSE_1, ADVANCED_HOUSE_2, ADVANCED_HOUSE_3,
                    STARTER_CAMP, MARKET, TAVERN, RESTAURANT -> true;
            default -> false;
        };
    }

    private static BlockPos exteriorPosition(BuildingBlueprintTemplate template,
            BlueprintEntrance entrance, int lateral) {
        BlockPos min = template.minCorner(), max = template.maxCorner();
        int x = entrance.position().getX(), z = entrance.position().getZ();
        return switch (entrance.outward()) {
            case NORTH -> new BlockPos(Math.max(min.getX(), Math.min(max.getX(), x + lateral)),
                    min.getY() + 1, min.getZ() - 1);
            case SOUTH -> new BlockPos(Math.max(min.getX(), Math.min(max.getX(), x - lateral)),
                    min.getY() + 1, max.getZ() + 1);
            case EAST -> new BlockPos(max.getX() + 1, min.getY() + 1,
                    Math.max(min.getZ(), Math.min(max.getZ(), z + lateral)));
            case WEST -> new BlockPos(min.getX() - 1, min.getY() + 1,
                    Math.max(min.getZ(), Math.min(max.getZ(), z - lateral)));
            default -> throw new IllegalStateException("Non-horizontal entrance");
        };
    }

    private static void addDecoration(List<BlueprintPlacement> placements, Set<BlockPos> occupied,
            BlockPos position, int module, Direction outward) {
        BlockState state = switch (module) {
            case 0 -> Blocks.COMPOSTER.defaultBlockState();
            case 1 -> Blocks.HAY_BLOCK.defaultBlockState()
                    .setValue(RotatedPillarBlock.AXIS, outward.getAxis());
            case 2 -> Blocks.BARREL.defaultBlockState()
                    .setValue(BarrelBlock.FACING, outward.getOpposite());
            default -> Blocks.OAK_LEAVES.defaultBlockState();
        };
        add(placements, occupied, position, state);
    }

    private static void add(List<BlueprintPlacement> placements, Set<BlockPos> occupied,
            BlockPos position, BlockState state) {
        if (!occupied.add(position)) return;
        Block block = state.getBlock();
        placements.add(new BlueprintPlacement(position.immutable(), state, block.asItem()));
    }

    private static void replace(List<BlueprintPlacement> placements, Set<BlockPos> occupied,
            BlockPos position, BlockState state) {
        placements.removeIf(placement -> placement.position().equals(position));
        occupied.remove(position);
        add(placements, occupied, position, state);
    }
}
