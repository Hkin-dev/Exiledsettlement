package com.hkin.exiledsettlement.blueprint;

import com.hkin.exiledsettlement.building.BuildingType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import java.util.*;

/** Normalized local coordinates; all previews and the final upload use this same transform. */
public final class SchematicDocument {
    private final String name;
    private final List<BlueprintPlacement> placements;
    private final int width, height, depth;
    public SchematicDocument(String name, Map<BlockPos, BlockState> cells) {
        if (cells.isEmpty()) throw new IllegalArgumentException("投影没有可施工方块");
        this.name = name.strip().substring(0, Math.min(32, name.strip().length()));
        int minX = cells.keySet().stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int minY = cells.keySet().stream().mapToInt(BlockPos::getY).min().orElseThrow();
        int minZ = cells.keySet().stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        width = cells.keySet().stream().mapToInt(BlockPos::getX).max().orElseThrow() - minX + 1;
        height = cells.keySet().stream().mapToInt(BlockPos::getY).max().orElseThrow() - minY + 1;
        depth = cells.keySet().stream().mapToInt(BlockPos::getZ).max().orElseThrow() - minZ + 1;
        if (width > 128 || height > 128 || depth > 128) throw new IllegalArgumentException("投影整体跨度超过128格");
        placements = cells.entrySet().stream().map(e -> new BlueprintPlacement(
                e.getKey().offset(-minX, -minY, -minZ), e.getValue(), e.getValue().getBlock().asItem()))
                .sorted(Comparator.comparingInt((BlueprintPlacement p) -> p.position().getY())
                        .thenComparingLong(p -> p.position().asLong())).toList();
        // Reuse server rules now, so unsupported blocks cannot disappear silently at submission.
        draft(this.name, BuildingType.BENCH, defaultEntrance()).snapshot(BlockPos.ZERO, Direction.NORTH);
    }
    public String name() { return name; }
    public int width() { return width; }
    public int height() { return height; }
    public int depth() { return depth; }
    public int count() { return placements.size(); }
    public BlueprintEntrance defaultEntrance() {
        for (BlueprintPlacement p : placements) if (p.state().getBlock() instanceof DoorBlock
                && p.state().getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER)
            return new BlueprintEntrance(p.position(), p.state().getValue(DoorBlock.FACING));
        return new BlueprintEntrance(new BlockPos(width / 2, Math.min(1, height), -1), Direction.NORTH);
    }
    public Draft draft(String name, BuildingType type, BlueprintEntrance entrance) { return new Draft(name, type, entrance); }

    public final class Draft {
        private final String name;
        private final BuildingType type;
        private final BlueprintEntrance entrance;
        private BlockPos cachedAnchor;
        private Direction cachedFacing;
        private BuildingBlueprintTemplate cachedTemplate;
        private Draft(String name, BuildingType type, BlueprintEntrance entrance) {
            this.name = name; this.type = type; this.entrance = entrance;
        }
        public String name() { return name; }
        public BuildingType type() { return type; }
        private Rotation rotation(Direction facing) {
            return switch (facing) {
                case EAST -> Rotation.CLOCKWISE_90; case SOUTH -> Rotation.CLOCKWISE_180;
                case WEST -> Rotation.COUNTERCLOCKWISE_90; default -> Rotation.NONE;
            };
        }
        private BlockPos transform(BlockPos local, BlockPos anchor, Rotation rotation) {
            BlockPos rotated = local.rotate(rotation);
            return switch (rotation) {
                case CLOCKWISE_90 -> rotated.offset(depth - 1, 0, 0).offset(anchor);
                case CLOCKWISE_180 -> rotated.offset(width - 1, 0, depth - 1).offset(anchor);
                case COUNTERCLOCKWISE_90 -> rotated.offset(0, 0, width - 1).offset(anchor);
                default -> rotated.offset(anchor);
            };
        }
        public CustomTemplateSnapshot snapshot(BlockPos anchor, Direction facing) {
            Rotation rotation = rotation(facing);
            return CustomTemplateSnapshot.create(name, type, anchor, facing, transformed(anchor, rotation), List.of(
                    new BlueprintEntrance(transform(entrance.position(), anchor, rotation), rotation.rotate(entrance.outward()))));
        }
        private List<BlueprintPlacement> transformed(BlockPos anchor, Rotation rotation) {
            return placements.stream().map(p -> new BlueprintPlacement(
                    transform(p.position(), anchor, rotation), p.state().rotate(rotation), p.material())).toList();
        }
        public BuildingBlueprintTemplate template(BlockPos anchor, Direction facing) {
            if (!anchor.equals(cachedAnchor) || facing != cachedFacing) {
                Rotation rotation = rotation(facing);
                boolean swap = facing.getAxis() == Direction.Axis.X;
                int w = swap ? depth : width, d = swap ? width : depth;
                cachedTemplate = new BuildingBlueprintTemplate(type, anchor, facing, w, d, height,
                        anchor, anchor.offset(w - 1, height - 1, d - 1), 2, transformed(anchor, rotation), List.of(
                        new BlueprintEntrance(transform(entrance.position(), anchor, rotation), rotation.rotate(entrance.outward()))));
                cachedAnchor = anchor.immutable(); cachedFacing = facing;
            }
            return cachedTemplate;
        }
    }
}
