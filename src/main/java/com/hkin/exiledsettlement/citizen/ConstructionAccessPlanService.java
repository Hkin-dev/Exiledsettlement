package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.blueprint.BlueprintPlacement;
import com.hkin.exiledsettlement.blueprint.BlueprintRecord;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.blueprint.BuildingBlueprintTemplate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Runtime construction safety checks for entering, working and leaving a site. */
public final class ConstructionAccessPlanService {
    private static final int MAX_EGRESS_NODES = 4096;

    private ConstructionAccessPlanService() {
    }

    /**
     * Rejects only a placement that removes an existing escape route. A resident
     * already isolated by an older site is allowed to build a step that may restore
     * access instead of freezing the whole blueprint forever.
     */
    public static boolean preservesResidentEgress(
            ServerLevel level, BlueprintRecord blueprint, BlueprintPlacement placement
    ) {
        return preservesResidentEgress(level, blueprint, placement, null);
    }

    public static boolean preservesResidentEgress(
            ServerLevel level, BlueprintRecord blueprint, BlueprintPlacement placement,
            @Nullable SettlementCitizenEntity currentWorker
    ) {
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        AABB site = bounds(template, 1, 4);
        Set<SettlementCitizenEntity> residents = new HashSet<>(level.getEntitiesOfClass(
                SettlementCitizenEntity.class, site, entity -> entity.isAlive()));
        if (currentWorker != null && currentWorker.isAlive()) residents.add(currentWorker);
        if (placement.state().is(Blocks.SCAFFOLDING)) {
            // Scaffolding is an occupiable, climbable access cell. Treating its
            // collision rim as a full wall made every roof-to-column connection
            // fail the ordinary solid-block flood fill. It cannot remove an
            // existing route unless it is placed through a resident's body;
            // vertical and horizontal scaffold traversal is validated by the
            // construction movement controller itself.
            return residents.stream().noneMatch(resident ->
                    placementIntersectsResident(level, placement, resident));
        }
        for (SettlementCitizenEntity resident : residents) {
            BlockPos start = resident.blockPosition();
            // Outside the footprint is safe only at the ordinary ground/door
            // height. Eaves and roof catwalks commonly sit one cell beyond the
            // footprint; skipping every such resident let the next wall or roof
            // block silently seal their only descent route. Use the same real
            // ground-egress definition as the flood fill instead of treating an
            // elevated exterior cell as automatic escape.
            if (isSafeGroundEgress(template, start)) continue;
            if (placementIntersectsResident(level, placement, resident)) return false;
            boolean before = hasEgress(level, template, start, null, null);
            if (before && !hasEgress(level, template, start,
                    placement.position(), placement.state())) return false;
        }
        return true;
    }

    private static boolean placementIntersectsResident(
            ServerLevel level, BlueprintPlacement placement, SettlementCitizenEntity resident
    ) {
        // Do not infer that a resident can stand on top of the block which is
        // about to occupy its head. The egress flood fill may legitimately find
        // that future upper surface, but the worker is physically below it and
        // must first move to another station. A tiny deflation keeps a block
        // merely touching the soles from being treated as a body collision.
        AABB body = resident.getBoundingBox().deflate(1.0E-4D);
        BlockPos position = placement.position();
        return placement.state().getCollisionShape(level, position).toAabbs().stream()
                .map(shape -> shape.move(position.getX(), position.getY(), position.getZ()))
                .anyMatch(shape -> shape.intersects(body));
    }

    /** A cleanup step must not remove the only usable descent/support route. */
    public static boolean preservesResidentEgressAfterRemoval(
            ServerLevel level, BlueprintRecord blueprint, BlockPos removedPosition
    ) {
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        AABB accessArea = bounds(template, 0, 6).inflate(
                Math.max(2, template.transitionMargin()) + 1.0D, 3.0D,
                Math.max(2, template.transitionMargin()) + 1.0D);
        for (SettlementCitizenEntity resident : level.getEntitiesOfClass(
                SettlementCitizenEntity.class, accessArea, entity -> entity.isAlive())) {
            BlockPos start = resident.blockPosition();
            boolean before = hasEgress(level, template, start, null, null);
            if (before && !hasEgress(level, template, start,
                    removedPosition, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())) return false;
        }
        return true;
    }

    public static boolean hasEgressAfterChange(
            ServerLevel level, BlueprintRecord blueprint, BlockPos start,
            @Nullable BlockPos changedPosition, @Nullable BlockState changedState
    ) {
        return hasEgress(level, BlueprintTemplates.create(blueprint), start, changedPosition, changedState);
    }

    private static boolean hasEgress(
            ServerLevel level, BuildingBlueprintTemplate template, BlockPos start,
            @Nullable BlockPos changedPosition, @Nullable BlockState changedState
    ) {
        return ConstructionPlanningBudget.egress(level,
                new EgressQuery(template.minCorner(), template.maxCorner(), start.immutable(),
                        changedPosition == null ? null : changedPosition.immutable(), changedState), start,
                template.minCorner(), template.maxCorner(),
                () -> computeEgress(level, template, start, changedPosition, changedState));
    }

    private record EgressQuery(BlockPos minimum, BlockPos maximum, BlockPos start,
                               @Nullable BlockPos changedPosition, @Nullable BlockState changedState) { }

    private static boolean computeEgress(
            ServerLevel level, BuildingBlueprintTemplate template, BlockPos start,
            @Nullable BlockPos changedPosition, @Nullable BlockState changedState
    ) {
        BlockPos normalizedStart = normalizeStandingPosition(
                level, start, changedPosition, changedState);
        if (normalizedStart == null) return false;
        if (isSafeGroundEgress(template, normalizedStart)) return true;

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(normalizedStart);
        visited.add(normalizedStart);
        int examined = 0;
        while (!queue.isEmpty() && examined++ < MAX_EGRESS_NODES) {
            BlockPos current = queue.removeFirst();
            if (isSafeGroundEgress(template, current)) return true;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos next = current.relative(direction).offset(0, dy, 0);
                    if (!withinSearchBounds(template, next) || visited.contains(next)
                            || !canStandAt(level, next, changedPosition, changedState)
                            || dy > 0 && !bodyPassable(stateAt(level, current.above(2),
                            changedPosition, changedState))) continue;
                    visited.add(next.immutable());
                    queue.addLast(next.immutable());
                }
                BlockPos landing = current.relative(direction, 2);
                BlockPos middle = current.relative(direction);
                if (withinSearchBounds(template, landing) && !visited.contains(landing)
                        && canStandAt(level, landing, changedPosition, changedState)
                        && bodyPassable(stateAt(level, middle, changedPosition, changedState))
                        && bodyPassable(stateAt(level, middle.above(), changedPosition, changedState))) {
                    visited.add(landing.immutable());
                    queue.addLast(landing.immutable());
                }
            }
            // Scaffolding is traversed vertically in its own block column. The
            // ordinary horizontal +/-1 standing graph cannot represent that,
            // so it previously declared every roof worker isolated before the
            // last roof block was placed and therefore failed to protect the
            // real descent route from being sealed.
            if (usesScaffolding(level, current, changedPosition, changedState)) {
                for (int dy : new int[]{-1, 1}) {
                    BlockPos next = current.offset(0, dy, 0);
                    if (!withinSearchBounds(template, next) || visited.contains(next)
                            || !usesScaffolding(level, next, changedPosition, changedState)
                            || !canStandAt(level, next, changedPosition, changedState)) continue;
                    visited.add(next.immutable());
                    queue.addLast(next.immutable());
                }
            }
        }
        return false;
    }

    @Nullable
    private static BlockPos normalizeStandingPosition(
            ServerLevel level, BlockPos start,
            @Nullable BlockPos changedPosition, @Nullable BlockState changedState
    ) {
        for (int dy = 1; dy >= -2; dy--) {
            BlockPos candidate = start.offset(0, dy, 0);
            if (canStandAt(level, candidate, changedPosition, changedState)) return candidate.immutable();
        }
        return null;
    }

    private static boolean canStandAt(
            ServerLevel level, BlockPos position,
            @Nullable BlockPos changedPosition, @Nullable BlockState changedState
    ) {
        BlockState feet = stateAt(level, position, changedPosition, changedState);
        BlockState head = stateAt(level, position.above(), changedPosition, changedState);
        BlockPos supportPosition = position.below();
        BlockState support = stateAt(level, supportPosition, changedPosition, changedState);
        boolean stableTop = support.getCollisionShape(level, supportPosition).toAabbs().stream()
                .anyMatch(box -> box.maxY >= 0.90D
                        && box.minX <= 0.25D && box.maxX >= 0.75D
                        && box.minZ <= 0.25D && box.maxZ >= 0.75D);
        boolean ordinaryOrPartialSurface = bodyPassable(feet) && bodyPassable(head)
                && stableTop;
        boolean insideScaffolding = feet.getBlock() instanceof ScaffoldingBlock
                && bodyPassable(head);
        boolean aboveScaffolding = support.getBlock() instanceof ScaffoldingBlock
                && bodyPassable(feet) && bodyPassable(head);
        return feet.getFluidState().isEmpty() && head.getFluidState().isEmpty()
                && (ordinaryOrPartialSurface || insideScaffolding || aboveScaffolding);
    }

    private static boolean usesScaffolding(
            ServerLevel level, BlockPos position,
            @Nullable BlockPos changedPosition, @Nullable BlockState changedState
    ) {
        return stateAt(level, position, changedPosition, changedState)
                .getBlock() instanceof ScaffoldingBlock
                || stateAt(level, position.below(), changedPosition, changedState)
                .getBlock() instanceof ScaffoldingBlock;
    }

    private static boolean bodyPassable(BlockState state) {
        return state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof FenceGateBlock
                || state.getBlock() instanceof ScaffoldingBlock
                || state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
                BlockPos.ZERO).isEmpty();
    }

    private static BlockState stateAt(
            ServerLevel level, BlockPos position,
            @Nullable BlockPos changedPosition, @Nullable BlockState changedState
    ) {
        return changedPosition != null && changedPosition.equals(position) && changedState != null
                ? changedState : level.getBlockState(position);
    }

    private static boolean outsideHorizontalBounds(BuildingBlueprintTemplate template, BlockPos position) {
        return position.getX() < template.minCorner().getX()
                || position.getX() > template.maxCorner().getX()
                || position.getZ() < template.minCorner().getZ()
                || position.getZ() > template.maxCorner().getZ();
    }

    /**
     * Crossing the footprint boundary on a roof or eave is not an escape. The
     * previous flood fill stopped there and declared the worker safe even when
     * the cell was six blocks above the ground with no stair or scaffold column
     * down. Continue searching until the route reaches an outside standing cell
     * at the building's ordinary entrance/floor height (or lower). This remains
     * template-agnostic: a real stair, door route or scaffold descent naturally
     * connects the elevated worker to such a cell.
     */
    private static boolean isSafeGroundEgress(
            BuildingBlueprintTemplate template, BlockPos position
    ) {
        return outsideHorizontalBounds(template, position)
                && position.getY() <= template.anchor().getY();
    }

    private static boolean withinSearchBounds(BuildingBlueprintTemplate template, BlockPos position) {
        int margin = Math.max(2, template.transitionMargin()) + 2;
        return position.getX() >= template.minCorner().getX() - margin
                && position.getX() <= template.maxCorner().getX() + margin
                && position.getY() >= template.minCorner().getY() - 4
                && position.getY() <= template.maxCorner().getY() + 7
                && position.getZ() >= template.minCorner().getZ() - margin
                && position.getZ() <= template.maxCorner().getZ() + margin;
    }

    private static AABB bounds(BuildingBlueprintTemplate template, int horizontalMargin, int heightAbove) {
        BlockPos min = template.minCorner();
        BlockPos max = template.maxCorner();
        return new AABB(min.getX() - horizontalMargin, min.getY(), min.getZ() - horizontalMargin,
                max.getX() + 1.0D + horizontalMargin, max.getY() + 1.0D + heightAbove,
                max.getZ() + 1.0D + horizontalMargin);
    }
}
