package com.hkin.exiledsettlement.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Groups real double chests by horizontal column; height only advances the category's page. */
public record WarehouseDisplayLayout(int group, int groups, int offset) {
    public static WarehouseDisplayLayout forBox(ServerLevel level, BuildingRecord building, BlockPos box) {
        var boxes = building.servicePositions().stream()
                .filter(p -> level.getBlockEntity(p) instanceof net.minecraft.world.Container)
                .map(p -> WarehouseDisplayService.representative(level, p)).distinct().toList();
        var target = WarehouseDisplayService.representative(level, box);
        if (boxes.isEmpty()) return new WarehouseDisplayLayout(0, 1, 0);
        if (building.type() != BuildingType.WAREHOUSE) {
            var sorted = boxes.stream().sorted(Comparator.comparingLong(BlockPos::asLong)).toList();
            int index = Math.max(0, sorted.indexOf(target)), groups = Math.min(sorted.size(), WarehouseDisplayCategory.columns().size());
            int offset = 0;
            for (int i = index % groups; i < index; i += groups) offset += WarehouseDisplayService.displayRows(level, sorted.get(i)) * 9;
            return new WarehouseDisplayLayout(index % groups, groups, offset);
        }
        // Use both halves' local minimum so a world rotation cannot swap the anchor half.
        var columns = new TreeMap<BlockPos, List<BlockPos>>(Comparator.<BlockPos>comparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        for (var pos : boxes) columns.computeIfAbsent(column(level, building, pos), ignored -> new ArrayList<>()).add(pos);
        int groups = Math.min(columns.size(), WarehouseDisplayCategory.columns().size());
        var keys = new ArrayList<>(columns.keySet());
        int index = Math.max(0, keys.indexOf(column(level, building, target))), group = index % groups, offset = 0;
        for (int i = group; i <= index; i += groups) {
            for (var pos : columns.get(keys.get(i)).stream().sorted(Comparator.comparingInt(BlockPos::getY)).toList()) {
                if (pos.equals(target)) return new WarehouseDisplayLayout(group, groups, offset);
                offset += WarehouseDisplayService.displayRows(level, pos) * 9;
            }
        }
        return new WarehouseDisplayLayout(group, groups, 0);
    }
    private static BlockPos column(ServerLevel level, BuildingRecord building, BlockPos pos) {
        var first = local(building, pos);
        var state = level.getBlockState(pos);
        if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                && state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
            var other = local(building, pos.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state)));
            return new BlockPos(Math.min(first.getX(), other.getX()), 0, Math.min(first.getZ(), other.getZ()));
        }
        return new BlockPos(first.getX(), 0, first.getZ());
    }
    private static BlockPos local(BuildingRecord building, BlockPos pos) {
        int x = pos.getX() - building.position().getX(), z = pos.getZ() - building.position().getZ();
        int turns = (building.facing().get2DDataValue() - Direction.SOUTH.get2DDataValue() + 4) % 4;
        for (int i = 0; i < turns; i++) { int previous = x; x = z; z = -previous; }
        return new BlockPos(x, pos.getY(), z);
    }
}
