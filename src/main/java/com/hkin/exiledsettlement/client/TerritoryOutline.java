package com.hkin.exiledsettlement.client;

import net.minecraft.world.level.ChunkPos;
import java.util.*;

/** Chunk union perimeter: adjacent owned chunks never create an internal wall. */
public final class TerritoryOutline {
    public record Edge(int x1, int z1, int x2, int z2) {}
    public record Bounds(int minX, int minZ, int maxX, int maxZ) {}

    public static Set<Long> chunks(ChunkPos core, int radius, Collection<Long> claims) {
        Set<Long> result = new HashSet<>(claims);
        for (int x = core.x - radius; x <= core.x + radius; x++) {
            for (int z = core.z - radius; z <= core.z + radius; z++) result.add(ChunkPos.asLong(x, z));
        }
        return Set.copyOf(result);
    }

    public static List<Edge> edges(Set<Long> chunks) {
        List<Edge> result = new ArrayList<>();
        for (long key : chunks) {
            ChunkPos c = new ChunkPos(key);
            int x = c.getMinBlockX(), z = c.getMinBlockZ();
            if (!chunks.contains(ChunkPos.asLong(c.x - 1, c.z))) result.add(new Edge(x, z, x, z + 16));
            if (!chunks.contains(ChunkPos.asLong(c.x + 1, c.z))) result.add(new Edge(x + 16, z, x + 16, z + 16));
            if (!chunks.contains(ChunkPos.asLong(c.x, c.z - 1))) result.add(new Edge(x, z, x + 16, z));
            if (!chunks.contains(ChunkPos.asLong(c.x, c.z + 1))) result.add(new Edge(x, z + 16, x + 16, z + 16));
        }
        return List.copyOf(result);
    }

    public static Bounds bounds(Set<Long> chunks) {
        int minX = Integer.MAX_VALUE, minZ = minX, maxX = Integer.MIN_VALUE, maxZ = maxX;
        for (long key : chunks) {
            ChunkPos c = new ChunkPos(key);
            minX = Math.min(minX, c.getMinBlockX()); minZ = Math.min(minZ, c.getMinBlockZ());
            maxX = Math.max(maxX, c.getMaxBlockX() + 1); maxZ = Math.max(maxZ, c.getMaxBlockZ() + 1);
        }
        return new Bounds(minX, minZ, maxX, maxZ);
    }
}
