package com.hkin.exiledsettlement.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Constant-size last-face dependency check; never edits the world to simulate a placement. */
public final class ConstructionClosureGuard {
    private ConstructionClosureGuard() { }

    @Nullable
    public static BlockPos prerequisite(ServerLevel level, BuildingBlueprintTemplate template,
            BlueprintPlacement proposed) {
        // Open agricultural surfaces use real soil support, not room sealing.
        if(FarmEarthworkPlan.applies(template.type()))return null;
        var changed = BlueprintPlacementGroups.parts(template, proposed);
        for (var part : changed) {
            if (!part.state().isCollisionShapeFullBlock(level, part.position())) continue;
            for (Direction direction : Direction.values()) {
                BlockPos hole = part.position().relative(direction);
                var missing = template.placementAt(hole);
                if (missing == null || !missing.mode().isFinalStructure() || missing.state().isAir()
                        || changed.stream().anyMatch(p -> p.position().equals(hole))
                        || BlueprintService.placementSatisfied(level,template,missing)) continue;
                boolean sealed = true;
                boolean wasOpen = false;
                for (Direction face : Direction.values()) {
                    BlockPos neighbor = hole.relative(face);
                    if (!level.hasChunkAt(neighbor)) { sealed = false; break; }
                    BlockState before = level.getBlockState(neighbor);
                    BlockState after = changed.stream().filter(p -> p.position().equals(neighbor))
                            .map(BlueprintPlacement::state).findFirst().orElse(before);
                    wasOpen |= !before.isCollisionShapeFullBlock(level, neighbor);
                    if (!after.isCollisionShapeFullBlock(level, neighbor)) { sealed = false; break; }
                }
                if (sealed && wasOpen) return hole.immutable();
            }
        }
        return null;
    }
}
