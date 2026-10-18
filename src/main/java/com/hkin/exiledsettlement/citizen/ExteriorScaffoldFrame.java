package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.blueprint.BuildingBlueprintTemplate;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable access geometry only: never caches world blocks or path results. */
public final class ExteriorScaffoldFrame {
    private ExteriorScaffoldFrame() { }
    // A span of twelve has no cell more than six horizontal links from a tower.
    public static final int MAX_TOWER_SPAN = 12;
    public static final int FIRST_RING_OFFSET = 3;
    public static final int RING_INTERVAL = 4;
    private record Key(BlockPos min, BlockPos max, int y) { }
    private static final Map<Key, List<BlockPos>> RINGS = new LinkedHashMap<>();

    public static boolean column(BuildingBlueprintTemplate template, BlockPos p) {
        int x0 = template.minCorner().getX() - 1, x1 = template.maxCorner().getX() + 1;
        int z0 = template.minCorner().getZ() - 1, z1 = template.maxCorner().getZ() + 1;
        return ((p.getZ() == z0 || p.getZ() == z1) && supportCoordinate(p.getX(), x0, x1))
                || ((p.getX() == x0 || p.getX() == x1) && supportCoordinate(p.getZ(), z0, z1));
    }

    private static boolean supportCoordinate(int value, int min, int max) {
        return value >= min && value <= max && (value == max || (value - min) % MAX_TOWER_SPAN == 0);
    }

    public static List<BlockPos> columns(BuildingBlueprintTemplate template, int y) {
        int x0 = template.minCorner().getX() - 1, x1 = template.maxCorner().getX() + 1;
        int z0 = template.minCorner().getZ() - 1, z1 = template.maxCorner().getZ() + 1;
        var result = new java.util.LinkedHashSet<BlockPos>();
        for (int x = x0; x < x1; x += MAX_TOWER_SPAN) {
            result.add(new BlockPos(x, y, z0)); result.add(new BlockPos(x, y, z1));
        }
        for (int z = z0; z < z1; z += MAX_TOWER_SPAN) {
            result.add(new BlockPos(x0, y, z)); result.add(new BlockPos(x1, y, z));
        }
        result.add(new BlockPos(x1, y, z1));
        return List.copyOf(result);
    }

    public static boolean cell(BuildingBlueprintTemplate template, BlockPos p) {
        if (column(template, p)) return true;
        int x0 = template.minCorner().getX() - 1, x1 = template.maxCorner().getX() + 1;
        int z0 = template.minCorner().getZ() - 1, z1 = template.maxCorner().getZ() + 1;
        return p.getX() >= x0 && p.getX() <= x1 && p.getZ() >= z0 && p.getZ() <= z1
                && (p.getX() == x0 || p.getX() == x1 || p.getZ() == z0 || p.getZ() == z1)
                && Math.floorMod(p.getY() - template.minCorner().getY(), RING_INTERVAL) == FIRST_RING_OFFSET;
    }

    /** Nearest-to-support ordering builds both ends before the middle of a long span. */
    public static synchronized List<BlockPos> ring(BuildingBlueprintTemplate template, int y) {
        Key key = new Key(template.minCorner(), template.maxCorner(), y);
        List<BlockPos> cached = RINGS.get(key);
        if (cached != null) return cached;
        int x0 = key.min.getX() - 1, x1 = key.max.getX() + 1;
        int z0 = key.min.getZ() - 1, z1 = key.max.getZ() + 1;
        List<BlockPos> ring = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            ring.add(new BlockPos(x, y, z0)); ring.add(new BlockPos(x, y, z1));
        }
        for (int z = z0 + 1; z < z1; z++) {
            ring.add(new BlockPos(x0, y, z)); ring.add(new BlockPos(x1, y, z));
        }
        var supports = columns(template, y);
        ring.sort(Comparator.comparingInt((BlockPos p) -> supports.stream()
                .mapToInt(p::distManhattan).min().orElseThrow())
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        cached = List.copyOf(ring);
        if (RINGS.size() >= 128) RINGS.remove(RINGS.keySet().iterator().next());
        RINGS.put(key, cached);
        return cached;
    }
}
