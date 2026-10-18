package com.hkin.exiledsettlement.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/** Live completion of immutable node dependencies; no completion counters or world mutation. */
public final class ConstructionDependencies {
    private ConstructionDependencies() { }

    public record Resolution(@Nullable BlockPos target, boolean cycle) { }

    /** Bound transitive planning to 64 nodes per request. A cycle is diagnostic, never permission to place. */
    public static Resolution resolve(ServerLevel level, BuildingBlueprintTemplate template, BlueprintPlacement requested) {
        var visited = new java.util.HashSet<BlockPos>();
        BlueprintPlacement current = requested;
        for (int steps = 0; steps < 64; steps++) {
            if (!visited.add(current.position())) return new Resolution(null, true);
            BlockPos next = firstUnfinished(level, template, current);
            if (next == null) next = ConstructionClosureGuard.prerequisite(level, template, current);
            if (next == null) return new Resolution(current == requested ? null : current.position(), false);
            BlueprintPlacement dependency = template.placementAt(next);
            if (dependency == null) return new Resolution(next, false);
            current = dependency;
        }
        // A large acyclic chain continues next work cycle rather than expanding without a bound.
        return new Resolution(current.position(), false);
    }

    @Nullable
    public static BlockPos firstUnfinished(ServerLevel level, BuildingBlueprintTemplate template,
            BlueprintPlacement placement) {
        for (BlockPos position : template.constructionPrerequisites(placement)) {
            if (!level.hasChunkAt(position)) return position;
            BlueprintPlacement required = template.placementAt(position);
            var actual = level.getBlockState(position);
            if (required.mode() == BlueprintPlacementMode.OPTIONAL_SUPPORT && !actual.isAir()) continue;
            if (!BlueprintService.placementSatisfied(level,template,required)) return position;
        }
        return null;
    }
}
