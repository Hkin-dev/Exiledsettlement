package com.hkin.exiledsettlement.road;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;

public final class RoadPathPreference {
    private static final float NON_ROAD_WALKABLE_MALUS = 0.75F;

    private RoadPathPreference() {
    }

    /**
     * PathFinder writes a candidate's walkedDistance before deciding whether its
     * new parent is better. With road costs, repeated rejected parents can inflate
     * a 19-block route past the unchanged 32-block search window. Restore only the
     * accepted parent chain before expanding a node; weighted g/h/costMalus and all
     * vanilla range/visited-node limits remain untouched.
     */
    public static void restoreAcceptedRouteDistance(Mob mob, Node node) {
        if (mob == null || node == null || !RoadEntityTags.prefersRoadPaths(mob)) return;
        // Match vanilla's follow-range * 16 node budget and cap defensive traversal
        // at the ordinary citizen budget (512), without increasing search capacity.
        int linkBudget = Math.max(1, Math.min(512,
                (int) Math.floor(mob.getAttributeValue(Attributes.FOLLOW_RANGE) * 16.0D)));
        float distance = 0.0F;
        int links = 0;
        for (Node current = node; current.cameFrom != null; current = current.cameFrom) {
            if (++links > linkBudget) return;
            float step = current.distanceTo(current.cameFrom);
            if (!Float.isFinite(step) || step <= 0.0F) return;
            distance += step;
            if (!Float.isFinite(distance)) return;
        }
        node.walkedDistance = distance;
    }

    public static void apply(Mob mob, Node node) {
        if (!(mob.level() instanceof ServerLevel level) || !RoadEntityTags.prefersRoadPaths(mob)
                || node == null || node.costMalus < 0.0F || !biasable(node.type)) {
            return;
        }
        BlockPos roadPosition = new BlockPos(node.x, node.y - 1, node.z);
        int roadLevel = RoadSpaceData.get(level).levelAt(roadPosition);
        if (roadLevel <= 0) {
            node.costMalus = Math.max(node.costMalus, NON_ROAD_WALKABLE_MALUS);
            return;
        }
        // Vanilla adds geometric step length plus costMalus. For a flat one-block
        // step, scale the ordinary 1.75 cost by the actual road speed multiplier.
        // Road detour tolerance therefore tracks the 10/15/20 percent speed gain,
        // instead of rewarding a 46/59/75 percent detour. Costs stay nonnegative.
        float roadMalus = (float) ((1.0D + NON_ROAD_WALKABLE_MALUS)
                / (1.0D + RoadTier.fromLevel(roadLevel).speedBonus()) - 1.0D);
        node.costMalus = roadMalus;
    }

    private static boolean biasable(PathType type) {
        return type == PathType.WALKABLE || type == PathType.OPEN
                || type == PathType.WALKABLE_DOOR || type == PathType.DOOR_OPEN;
    }
}
