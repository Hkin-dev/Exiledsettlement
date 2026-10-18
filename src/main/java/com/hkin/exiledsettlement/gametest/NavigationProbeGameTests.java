package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.blueprint.BlueprintBlockStateResolver;
import com.hkin.exiledsettlement.blueprint.BlueprintPlacementMode;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.CitizenNavigationReservationService;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.road.RoadPathPreference;
import com.hkin.exiledsettlement.road.RoadSpaceData;
import com.hkin.exiledsettlement.road.RoadTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/** Real navigation regressions; no work bypass, expanded range or teleport after spawn. */
@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NavigationProbeGameTests {
    private NavigationProbeGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "navigation_probe_live", timeoutTicks = 240)
    public static void pathPreflightPreservesActiveMovementAndCommitsExactTarget(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 0, 240);
        SettlementCitizenEntity entity = fixture.entity;
        BlockPos runningTarget = fixture.origin.offset(21, 0, 5);
        BlockPos candidate = fixture.origin.offset(16, 0, 2);
        Path original = fixture.startPath(runningTarget);
        Vec3 initialPosition = entity.position();

        helper.startSequence().thenExecute(() -> {
            int originalIndex = original.getNextNodeIndex();
            for (int probe = 0; probe < 6; probe++) {
                helper.assertTrue(entity.hasReachablePathTo(candidate), "Clear candidate was not reachable");
                fixture.assertActivePath(original, runningTarget);
                helper.assertTrue(original.getNextNodeIndex() == originalIndex,
                        "A synchronous preflight advanced or reset the active path");
            }
        }).thenWaitUntil(() -> {
            fixture.assertActivePath(original, runningTarget);
            helper.assertTrue(entity.position().distanceToSqr(initialPosition) > 0.25D,
                    "Resident did not continue real movement while candidate queries were made");
        }).thenExecute(() -> {
            helper.assertTrue(entity.hasReachablePathTo(candidate)
                            && entity.hasReachablePathTo(candidate),
                    "Repeated candidate query became unreachable");
            helper.assertTrue(entity.navigateTo(candidate, 0.70D).isEmpty(),
                    "Selected candidate could not be committed to navigation");
            Path committed = entity.getNavigation().getPath();
            helper.assertTrue(committed != null && committed.canReach()
                            && candidate.equals(committed.getTarget())
                            && candidate.equals(entity.getNavigation().getTargetPos())
                            && committed.getEndNode() != null
                            && candidate.equals(committed.getEndNode().asBlockPos()),
                    "Committed route or future recomputation still targets the old destination");
        }).thenWaitUntil(() -> helper.assertTrue(
                entity.position().distanceToSqr(Vec3.atBottomCenterOf(candidate)) < 0.81D,
                "Resident has not physically reached the selected candidate"))
                .thenExecute(fixture::finish).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "navigation_probe_live", timeoutTicks = 60)
    public static void failedPreflightDoesNotReuseRunningPathOrLeavePreparedRoute(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 1, 60);
        SettlementCitizenEntity entity = fixture.entity;
        BlockPos runningTarget = fixture.origin.offset(21, 0, 5);
        BlockPos reachable = fixture.origin.offset(16, 0, 2);
        BlockPos enclosed = fixture.origin.offset(16, 0, 10);
        for (BlockPos cell : BlockPos.betweenClosed(enclosed.offset(-1, 0, -1), enclosed.offset(1, 3, 1))) {
            if (cell.getX() != enclosed.getX() || cell.getZ() != enclosed.getZ()) {
                fixture.level.setBlock(cell, Blocks.STONE_BRICKS.defaultBlockState(), 2);
            }
        }
        Path original = fixture.startPath(runningTarget);
        helper.assertTrue(entity.hasReachablePathTo(reachable), "Preparation fixture is not reachable");
        helper.assertTrue(!reachable.equals(entity.preparedConstructionRouteIdentity(reachable)),
                "Fixture did not prepare a nontrivial approach route");
        for (int probe = 0; probe < 6; probe++) {
            helper.assertTrue(!entity.hasReachablePathTo(enclosed),
                    "Enclosed candidate borrowed the active route's canReach result");
            fixture.assertActivePath(original, runningTarget);
            helper.assertTrue(reachable.equals(entity.preparedConstructionRouteIdentity(reachable)),
                    "Failed candidate left a stale prepared construction approach");
        }
        Vec3 before = entity.position();
        helper.startSequence().thenWaitUntil(() -> {
            fixture.assertActivePath(original, runningTarget);
            helper.assertTrue(entity.position().distanceToSqr(before) > 0.25D,
                    "Failed candidate queries stopped real movement");
        }).thenExecute(fixture::finish).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "navigation_probe_live", timeoutTicks = 80)
    public static void formalWarehouseCornerStationsRemainReachableWithRoadCosts(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 2, 80);
        var template = BlueprintTemplates.create(BuildingType.WAREHOUSE,
                fixture.origin.offset(0, 0, 5), Direction.EAST);
        for (var placement : template.placements()) {
            if (placement.mode() == BlueprintPlacementMode.OPTIONAL_SUPPORT
                    && !fixture.level.getBlockState(placement.position()).isAir()) continue;
            fixture.level.setBlock(placement.position(), placement.mode() == BlueprintPlacementMode.CLEARANCE
                    ? Blocks.AIR.defaultBlockState() : placement.state(), 2);
        }
        for (var placement : template.placements()) {
            BlueprintBlockStateResolver.refreshExisting(fixture.level, placement);
        }
        // Same east waiting point and four west-side targets as the observed
        // permanent warehouse stall. This is initial setup, before any movement.
        BlockPos start = fixture.origin.offset(19, 0, 8);
        fixture.entity.moveTo(start.getX() + 0.516D, start.getY(), start.getZ() + 0.710D, 0.0F, 0.0F);
        fixture.entity.setOnGround(true);
        List<BlockPos> targets = List.of(fixture.origin.offset(2, 0, 3), fixture.origin.offset(3, 0, 3),
                fixture.origin.offset(1, 0, 5), fixture.origin.offset(1, 0, 6));
        for (BlockPos target : targets) {
            helper.assertTrue(fixture.entity.canStandAtPosition(target), "Formal warehouse target is not standable");
            Path path = fixture.entity.getNavigation().createPath(target, 0);
            helper.assertTrue(path != null && path.canReach() && target.equals(path.getTarget()),
                    "Weighted navigation still rejects the reachable formal warehouse target " + target);
            helper.assertTrue(geometricLength(path) < 32.0D, "Regression silently exceeded the original path range");
            long biasedNodes = java.util.stream.IntStream.range(0, path.getNodeCount())
                    .filter(index -> path.getNode(index).costMalus >= 0.75F).count();
            helper.assertTrue(biasedNodes >= 8, "Warehouse path passed only because road preference was disabled");
        }
        fixture.finish();
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "navigation_probe_live", timeoutTicks = 80)
    public static void acceptedDistanceRepairPreservesRealRoadPreference(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 3, 80);
        RoadSpaceData roads = RoadSpaceData.get(fixture.level);
        List<BlockPos> road = new ArrayList<>();
        for (int x = 2; x <= 22; x++) road.add(fixture.origin.offset(x, -1, 4));
        road.add(fixture.origin.offset(2, -1, 5));
        road.add(fixture.origin.offset(22, -1, 5));
        roads.addAll(road, RoadTier.LEVEL_3);
        BlockPos destination = fixture.origin.offset(22, 0, 5);
        Path path = fixture.entity.getNavigation().createPath(destination, 0);
        helper.assertTrue(path != null && path.canReach(), "Short road detour is not reachable");
        long roadNodes = java.util.stream.IntStream.range(0, path.getNodeCount())
                .filter(index -> roads.contains(path.getNode(index).asBlockPos().below())).count();
        helper.assertTrue(roadNodes >= 12, "Distance repair discarded the existing preference for nearby roads");
        helper.assertTrue(geometricLength(path) < 32.0D, "Road preference exceeded the unchanged search range");
        fixture.finish();
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "navigation_probe_live", timeoutTicks = 40)
    public static void rejectedParentDistanceIsRepairedWithoutChangingWeightedCosts(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 4, 40);
        Node root = new Node(0, 0, 0);
        Node middle = new Node(1, 0, 0);
        Node current = new Node(2, 0, 1);
        middle.cameFrom = root;
        current.cameFrom = middle;
        middle.walkedDistance = 27.0F;
        current.walkedDistance = 31.9F;
        current.g = 4.0F;
        current.h = 5.0F;
        current.costMalus = 0.75F;
        RoadPathPreference.restoreAcceptedRouteDistance(fixture.entity, current);
        helper.assertTrue(Math.abs(current.walkedDistance - (1.0F + (float) Math.sqrt(2.0D))) < 0.0001F,
                "Accepted parent chain did not replace the rejected parent's polluted distance");
        helper.assertTrue(current.g == 4.0F && current.h == 5.0F && current.costMalus == 0.75F,
                "Geometric distance repair altered weighted search costs");
        middle.cameFrom = current;
        current.walkedDistance = 31.9F;
        RoadPathPreference.restoreAcceptedRouteDistance(fixture.entity, current);
        helper.assertTrue(current.walkedDistance == 31.9F,
                "Malformed cyclic parent chain was not rejected within the bounded traversal");
        fixture.finish();
        helper.succeed();
    }

    private static double geometricLength(Path path) {
        double distance = 0.0D;
        for (int index = 1; index < path.getNodeCount(); index++) {
            distance += path.getNode(index - 1).distanceTo(path.getNode(index));
        }
        return distance;
    }

    private static final class Fixture {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final BlockPos origin;
        private final SettlementCitizenEntity entity;
        private final ChunkPos firstChunk;
        private final ChunkPos lastChunk;
        private boolean finished;

        private Fixture(GameTestHelper helper, int scenario, int timeoutTicks) {
            this.helper = helper;
            level = helper.getLevel();
            origin = helper.absolutePos(new BlockPos(4, 10, 4)).offset(24576 + scenario * 96, 0, 0);
            firstChunk = new ChunkPos(origin.offset(-2, 0, -2));
            lastChunk = new ChunkPos(origin.offset(26, 0, 16));
            for (int x = firstChunk.x; x <= lastChunk.x; x++) {
                for (int z = firstChunk.z; z <= lastChunk.z; z++) level.setChunkForced(x, z, true);
            }
            for (BlockPos position : BlockPos.betweenClosed(origin.offset(-1, -1, -1), origin.offset(25, 5, 15))) {
                level.setBlock(position, position.getY() < origin.getY()
                        ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
            }
            entity = ModEntities.SETTLEMENT_CITIZEN.get().create(level);
            helper.assertTrue(entity != null, "Could not create navigation resident");
            BlockPos start = origin.offset(2, 0, 5);
            entity.moveTo(start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D, 0.0F, 0.0F);
            entity.setOnGround(true);
            helper.assertTrue(level.addFreshEntity(entity), "Could not add navigation resident");
            helper.runAtTickTime(timeoutTicks - 1L, this::finish);
        }

        private Path startPath(BlockPos target) {
            Path path = entity.getNavigation().createPath(target, 0);
            helper.assertTrue(path != null && path.canReach() && target.equals(path.getTarget()),
                    "Initial navigation fixture is not reachable");
            helper.assertTrue(entity.getNavigation().moveTo(path, 0.70D), "Initial route was not started");
            return path;
        }

        private void assertActivePath(Path original, BlockPos target) {
            helper.assertTrue(entity.getNavigation().getPath() == original
                            && !entity.getNavigation().isDone()
                            && target.equals(entity.getNavigation().getTargetPos())
                            && target.equals(original.getTarget()),
                    "A candidate preflight replaced, stopped or redirected the active route");
        }

        private void finish() {
            if (finished) return;
            finished = true;
            CitizenNavigationReservationService.releaseAll(level, entity.getUUID());
            entity.discard();
            for (int x = firstChunk.x; x <= lastChunk.x; x++) {
                for (int z = firstChunk.z; z <= lastChunk.z; z++) level.setChunkForced(x, z, false);
            }
        }
    }
}
