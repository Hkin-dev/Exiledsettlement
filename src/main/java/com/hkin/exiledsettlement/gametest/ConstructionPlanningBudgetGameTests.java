package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.blueprint.BlueprintRecord;
import com.hkin.exiledsettlement.blueprint.BlueprintPlacement;
import com.hkin.exiledsettlement.blueprint.BlueprintPlacementMode;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.blueprint.BlueprintBlockStateResolver;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.ConstructionAccessPlanService;
import com.hkin.exiledsettlement.citizen.ConstructionPlanningBudget;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import com.hkin.exiledsettlement.citizen.CitizenProfession;
import com.hkin.exiledsettlement.citizen.CitizenProjectionService;
import com.hkin.exiledsettlement.citizen.CitizenWorkService;
import com.hkin.exiledsettlement.citizen.ExteriorScaffoldFrame;
import com.hkin.exiledsettlement.citizen.CitizenWorkState;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Real vanilla path queries, bounded across residents; no construction bypass or enlarged reach. */
@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ConstructionPlanningBudgetGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private ConstructionPlanningBudgetGameTests() { }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "construction_performance")
    public static void fixedEnclosurePathBenchmark(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 8, 100, 1);
        try {
            var resident = fixture.residents.getFirst();
            var candidates = fixture.candidates();
            long[] single = new long[5], batch = new long[5];
            for (int run = -2; run < 5; run++) {
                // Identical entity, geometry and candidate order; no cached scope
                // and no movement. Alternate order to reduce warmup bias.
                for (int pass = 0; pass < 2; pass++) {
                    boolean useBatch = Math.floorMod(run + pass, 2) == 0;
                    long start = System.nanoTime();
                    if (useBatch) {
                        helper.assertTrue(resident.findReachableConstructionStation(candidates) == null,
                                "Batch benchmark crossed enclosing walls");
                    } else {
                        for (BlockPos candidate : candidates) helper.assertTrue(
                                !resident.hasReachableConstructionPathTo(candidate), "Individual query crossed walls");
                    }
                    if (run >= 0) (useBatch ? batch : single)[run] = System.nanoTime() - start;
                }
            }
            java.util.Arrays.sort(single);
            java.util.Arrays.sort(batch);
            LOGGER.info("[fixed-path-benchmark] candidates={} samples=5 individualMedianMs={} batchMedianMs={} individualMaxMs={} batchMaxMs={}",
                    candidates.size(), single[2] / 1e6, batch[2] / 1e6, single[4] / 1e6, batch[4] / 1e6);
        } finally { fixture.finish(); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "frame_candidates")
    public static void supportedColumnCandidatesMatchBoundedRingScan(GameTestHelper helper) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            var template = BlueprintTemplates.create(BuildingType.HOUSE, new BlockPos(-15, -60, -17), direction);
            for (BlockPos target : List.of(template.minCorner().above(4), template.maxCorner(), new BlockPos(60, -55, 60))) {
                for (int limit : new int[]{0, 5, 16}) {
                    List<BlockPos> expected = new ArrayList<>();
                    for (int radius = 0; radius <= limit; radius++) {
                        for (int dx = -radius; dx <= radius; dx++) {
                            for (int dz = -radius; dz <= radius; dz++) {
                                if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                                int x = target.getX() + dx, z = target.getZ() + dz;
                                if (ExteriorScaffoldFrame.column(template, new BlockPos(x, target.getY(), z))) {
                                    for (int dy = -10; dy <= 0; dy++) expected.add(target.offset(dx, dy, dz));
                                }
                            }
                        }
                    }
                    var actual = CitizenWorkService.exteriorFrameBaseCandidates(template, target, limit);
                    helper.assertTrue(actual.equals(expected)
                                    && actual.size() <= ExteriorScaffoldFrame.columns(template, target.getY()).size() * 11,
                            "Direct support enumeration changed candidate positions or ordering");
                }
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "frame_physics", timeoutTicks = 100)
    public static void thirdLevelRingRemainsConnectedUnderVanillaPhysics(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 9, 100, 0);
        BlockPos min = fixture.origin.offset(4, -1, 3), max = fixture.origin.offset(24, 8, 12);
        var template = new com.hkin.exiledsettlement.blueprint.BuildingBlueprintTemplate(
                BuildingType.WAREHOUSE, min, Direction.NORTH, 21, 10, 10, min, max, 2, List.of());
        int y = min.getY() + 3;
        var columns = ExteriorScaffoldFrame.columns(template, y);
        var ring = ExteriorScaffoldFrame.ring(template, y);
        helper.assertTrue(columns.size() > 4, "Wide frame did not get intermediate supports");
        var supported = new java.util.HashSet<BlockPos>();
        for (BlockPos column : columns) for (int cy = min.getY() + 1; cy <= y; cy++) {
            BlockPos p = new BlockPos(column.getX(), cy, column.getZ());
            fixture.level.setBlock(p, Blocks.SCAFFOLDING.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE, 0), 3);
            supported.add(p);
        }
        for (BlockPos p : ring) {
            helper.assertTrue(ExteriorScaffoldFrame.cell(template, p), "Ring cell rejected by access planner");
            int distance = columns.stream().mapToInt(p::distManhattan).min().orElseThrow();
            helper.assertTrue(distance <= 6, "Unsupported long span");
            helper.assertTrue(distance == 0 || java.util.Arrays.stream(Direction.values())
                    .anyMatch(d -> supported.contains(p.relative(d))), "Ring order leaves an unsupported gap");
            if (!ExteriorScaffoldFrame.column(template, p)) helper.assertTrue(
                    !ExteriorScaffoldFrame.cell(template, p.below()), "Old second-level arm still permitted");
            fixture.level.setBlock(p, Blocks.SCAFFOLDING.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE, distance), 3);
            supported.add(p);
        }
        var connected = new java.util.HashSet<BlockPos>();
        var queue = new java.util.ArrayDeque<BlockPos>();
        queue.add(ring.getFirst());
        while (!queue.isEmpty()) {
            BlockPos p = queue.remove();
            if (!connected.add(p)) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) if (ring.contains(p.relative(d))) queue.add(p.relative(d));
        }
        helper.assertTrue(connected.size() == ring.size(), "Ring is not one connected loop");
        helper.runAtTickTime(60, () -> {
            for (BlockPos p : ring) helper.assertTrue(fixture.level.getBlockState(p).is(Blocks.SCAFFOLDING),
                    "Vanilla physics collapsed the complete ring at " + p);
            fixture.finish();
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "construction_batch_paths")
    public static void multipleStationsShareOneSearchAndWorldChangesInvalidate(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 7, 100, 1);
        try {
            var resident = fixture.residents.getFirst();
            var candidates = fixture.candidates();
            try (var slice = ConstructionPlanningBudget.begin(resident, fixture.task)) {
                helper.assertTrue(resident.findReachableConstructionStation(candidates) == null,
                        "Batch query let enclosed resident cross a wall");
                helper.assertTrue(resident.findReachableConstructionStation(candidates) == null,
                        "Repeated negative batch changed without geometry change");
                helper.assertTrue(ConstructionPlanningBudget.snapshot(fixture.level, resident.getUUID()).residentPaths() == 1,
                        "Candidate set was searched individually or negative batch was not memoized");
                fixture.openDoor();
                BlockPos selected = resident.findReachableConstructionStation(candidates);
                helper.assertTrue(selected != null && candidates.contains(selected),
                        "Opening the door did not invalidate the negative batch");
                helper.assertTrue(ConstructionPlanningBudget.snapshot(fixture.level, resident.getUUID()).residentPaths() == 2,
                        "Door change should cause exactly one new batch search");
            }
        } finally { fixture.finish(); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "template_cache")
    public static void immutableTemplateCachePreservesVersionFacingAndBounds(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(2, 4, 2));
        var current = BlueprintTemplates.create(BuildingType.TOWN_HALL, anchor, Direction.NORTH, 3);
        helper.assertTrue(current == BlueprintTemplates.create(BuildingType.TOWN_HALL, anchor, Direction.NORTH, 3),
                "Repeated geometry was rebuilt");
        var old = BlueprintTemplates.create(BuildingType.TOWN_HALL, anchor, Direction.NORTH, 2);
        var rotated = BlueprintTemplates.create(BuildingType.TOWN_HALL, anchor, Direction.EAST, 3);
        helper.assertTrue(current != old && current != rotated && rotated.facing() == Direction.EAST,
                "Cache merged historical versions or rotations");
        var small = BlueprintTemplates.createArea(BuildingType.FARM_FIELD, anchor, anchor.offset(8, 0, 8), Direction.NORTH);
        var large = BlueprintTemplates.createArea(BuildingType.FARM_FIELD, anchor, anchor.offset(12, 0, 12), Direction.NORTH);
        helper.assertTrue(small != large && !small.maxCorner().equals(large.maxCorner()),
                "Cache lost dynamic production area bounds");
        for (int index = 0; index < 129; index++) {
            BlueprintTemplates.create(BuildingType.HOUSE, anchor.offset(index * 32 + 64, 0, 0), Direction.NORTH, 4);
        }
        var rebuilt = BlueprintTemplates.create(BuildingType.TOWN_HALL, anchor, Direction.NORTH, 3);
        helper.assertTrue(rebuilt != current && rebuilt.placements().equals(current.placements())
                        && rebuilt.requirements().equals(current.requirements()),
                "Bounded eviction lost geometry or materials");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "construction_repeated_step")
    public static void repeatedLocalStepDoesNotRestartPhysicalMovement(GameTestHelper helper) {
        BlockPos feet = helper.absolutePos(new BlockPos(3, 3, 3));
        helper.getLevel().setBlock(feet.below(), Blocks.STONE.defaultBlockState(), 2);
        helper.getLevel().setBlock(feet.east(), Blocks.STONE.defaultBlockState(), 2);
        var resident = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(resident != null, "Could not create local step resident");
        resident.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0, 0);
        BlockPos landing = feet.east().above();
        helper.assertTrue(resident.beginAdjacentConstructionMovement(landing, 0.7), "Could not begin rising step");
        Vec3 before = resident.position();
        for (int index = 0; index < 10; index++) {
            helper.assertTrue(resident.beginAdjacentConstructionMovement(landing, 0.7), "Repeated command rejected active step");
        }
        helper.assertTrue(resident.hasActiveConstructionMovement() && resident.position().equals(before),
                "Scheduler polls drove or restarted the physical step instead of preserving it");
        resident.finishConstructionMovement();
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "construction_diagnostics")
    public static void constructionDecisionHistoryIsBoundedAndDeduplicated(GameTestHelper helper) {
        var resident = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(resident != null, "Could not create diagnostic resident");
        for (int index = 0; index < 25; index++) resident.traceConstructionDecision("step-" + index);
        resident.traceConstructionDecision("step-24");
        var history = resident.constructionDecisionHistory();
        helper.assertTrue(history.size() == 20 && history.getFirst().endsWith(":step-5")
                        && history.getLast().endsWith(":step-24"), "History lost its bound or retained repeated decisions");
        resident.traceConstructionDecision("next");
        helper.assertTrue(history.getLast().endsWith(":step-24"), "Diagnostic snapshot changed after publication");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "construction_budget_idle_capacity", timeoutTicks = 100)
    public static void yieldedWorkerDoesNotBlockUnusedLevelCapacity(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 6, 100, 2);
        try {
            SettlementCitizenEntity first = fixture.residents.getFirst();
            try (var slice = ConstructionPlanningBudget.begin(first, fixture.task)) {
                for (BlockPos candidate : fixture.candidates()) first.hasReachableConstructionPathTo(candidate);
                helper.fail("First worker did not exhaust its slice");
            } catch (ConstructionPlanningBudget.Deferred expected) { }
            SettlementCitizenEntity second = fixture.residents.get(1);
            try (var slice = ConstructionPlanningBudget.begin(second, fixture.task)) {
                second.hasReachableConstructionPathTo(fixture.candidates().getFirst());
            } catch (ConstructionPlanningBudget.Deferred unexpected) {
                helper.fail("An exhausted queue head blocked unused level capacity");
            }
            helper.assertTrue(ConstructionPlanningBudget.snapshot(fixture.level, second.getUUID()).residentPaths() == 1,
                    "Second worker did not perform its real search in the same tick");
            helper.succeed();
        } finally {
            fixture.finish();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "construction_budget_limits", timeoutTicks = 160)
    public static void constructionQueriesObeyPerResidentAndSharedLevelLimits(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 0, 160, 6);
        List<BlockPos> candidates = fixture.candidates();
        boolean[] everServed = new boolean[fixture.residents.size()];
        int[] completedTicks = {0};
        helper.startSequence().thenWaitUntil(() -> {
            for (int index = 0; index < fixture.residents.size(); index++) {
                SettlementCitizenEntity resident = fixture.residents.get(index);
                try (var slice = ConstructionPlanningBudget.begin(resident, fixture.task)) {
                    for (BlockPos candidate : candidates) {
                        helper.assertTrue(!resident.hasReachableConstructionPathTo(candidate),
                                "Resident unexpectedly escaped the sealed fixture");
                    }
                } catch (ConstructionPlanningBudget.Deferred waiting) {
                    // Waiting is intentional and must not be converted to unreachable.
                }
                var snapshot = ConstructionPlanningBudget.snapshot(fixture.level, resident.getUUID());
                helper.assertTrue(snapshot.residentPaths() <= ConstructionPlanningBudget.PATHS_PER_RESIDENT_TICK,
                        "One worker exceeded its real path-search slice");
                helper.assertTrue(snapshot.levelPaths() <= ConstructionPlanningBudget.PATHS_PER_LEVEL_TICK,
                        "Multiple workers exceeded the shared level path-search budget");
                everServed[index] |= snapshot.residentPaths() > 0;
            }
            completedTicks[0]++;
            for (boolean served : everServed) helper.assertTrue(served, "A later worker is still waiting for its first fair slice");
            helper.assertTrue(completedTicks[0] >= 3, "Observe several real ticks before completing the budget check");
        }).thenExecute(fixture::finish).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "construction_budget_resume", timeoutTicks = 160)
    public static void failedCandidatePrefixIsMemoizedAndSearchCompletesAcrossTicks(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 1, 160, 1);
        SettlementCitizenEntity resident = fixture.residents.getFirst();
        List<BlockPos> candidates = fixture.candidates();
        long baseline = ConstructionPlanningBudget.snapshot(fixture.level, resident.getUUID()).residentTotalPaths();
        boolean[] yielded = {false};
        helper.startSequence().thenWaitUntil(() -> {
            boolean complete = false;
            try (var slice = ConstructionPlanningBudget.begin(resident, fixture.task)) {
                // Replay the real station-selector pattern from its first entry.
                // Only new candidates may consume another native search.
                for (BlockPos candidate : candidates) {
                    helper.assertTrue(!resident.hasReachableConstructionPathTo(candidate),
                            "Enclosed resident was reported as reachable");
                }
                complete = true;
            } catch (ConstructionPlanningBudget.Deferred waiting) {
                yielded[0] = true;
            }
            var snapshot = ConstructionPlanningBudget.snapshot(fixture.level, resident.getUUID());
            helper.assertTrue(snapshot.residentPaths() <= ConstructionPlanningBudget.PATHS_PER_RESIDENT_TICK,
                    "A resumed slice exceeded its native search allowance");
            helper.assertTrue(complete, "Candidate search is still continuing in later slices");
            helper.assertTrue(yielded[0], "Fixture did not exercise a cross-tick yield");
            helper.assertTrue(snapshot.residentTotalPaths() - baseline == candidates.size(),
                    "Completed negative candidates were recalculated instead of resumed");
            helper.assertTrue(snapshot.residentCacheHits() > 0, "No failed-prefix memo was reused");
        }).thenExecute(fixture::finish).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "construction_budget_invalidation", timeoutTicks = 160)
    public static void localGeometryChangesInvalidateButDistantChangesKeepPlanningProgress(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 2, 160, 1);
        SettlementCitizenEntity resident = fixture.residents.getFirst();
        BlockPos target = fixture.origin.offset(16, 0, 5);
        try (var slice = ConstructionPlanningBudget.begin(resident, fixture.task)) {
            helper.assertTrue(!resident.hasReachableConstructionPathTo(target), "Closed fixture had an exit");
        }
        long afterClosed = ConstructionPlanningBudget.snapshot(fixture.level, resident.getUUID()).residentTotalPaths();
        fixture.level.setBlock(fixture.origin.offset(96, 0, 0), Blocks.STONE.defaultBlockState(), 2);
        try (var slice = ConstructionPlanningBudget.begin(resident, fixture.task)) {
            helper.assertTrue(!resident.hasReachableConstructionPathTo(target), "Distant edit changed local reachability");
        }
        helper.assertTrue(ConstructionPlanningBudget.snapshot(fixture.level, resident.getUUID()).residentTotalPaths() == afterClosed,
                "Unrelated distant block edit discarded the local search memo");
        // A real successful setBlock call must invalidate both head and feet geometry.
        fixture.openDoor();
        try (var slice = ConstructionPlanningBudget.begin(resident, fixture.task)) {
            helper.assertTrue(resident.hasReachableConstructionPathTo(target),
                    "Opening the actual exit did not invalidate a cached unreachable result");
        }
        helper.assertTrue(ConstructionPlanningBudget.snapshot(fixture.level, resident.getUUID()).residentTotalPaths() == afterClosed + 1,
                "Changed nearby geometry was not queried exactly once");
        try (var slice = ConstructionPlanningBudget.begin(resident, UUID.randomUUID())) {
            helper.assertTrue(resident.hasReachableConstructionPathTo(target), "New task could not query the live exit");
        }
        helper.assertTrue(ConstructionPlanningBudget.snapshot(fixture.level, resident.getUUID()).residentTotalPaths() == afterClosed + 2,
                "A new task silently reused the previous task's memo");
        ConstructionPlanningBudget.forget(fixture.level, resident.getUUID());
        helper.assertTrue(ConstructionPlanningBudget.snapshot(fixture.level, resident.getUUID()).memoEntries() == 0,
                "Released resident retained planning memory");
        fixture.finish();
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "construction_budget_movement", timeoutTicks = 200)
    public static void planningYieldPreservesActualActiveMovement(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 3, 200, 1);
        SettlementCitizenEntity resident = fixture.residents.getFirst();
        fixture.openDoor();
        BlockPos destination = fixture.origin.offset(23, 0, 5);
        helper.assertTrue(resident.navigateTo(destination, 0.70D).isEmpty(),
                "Initial movement route is not real-reachable");
        Path original = resident.getNavigation().getPath();
        helper.assertTrue(original != null && original.canReach(), "Initial movement route was not committed");
        Vec3 start = resident.position();
        boolean deferred = false;
        try (var slice = ConstructionPlanningBudget.begin(resident, fixture.task)) {
            for (BlockPos candidate : fixture.candidates()) resident.hasReachableConstructionPathTo(candidate);
        } catch (ConstructionPlanningBudget.Deferred waiting) {
            deferred = true;
        }
        helper.assertTrue(deferred, "Movement fixture did not exhaust a planning slice");
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(resident.getNavigation().getPath() == original
                            && !resident.getNavigation().isDone(),
                    "A planning yield reset or stopped the running path");
            helper.assertTrue(resident.position().distanceToSqr(start) > 0.25D,
                    "Resident has not continued physical movement after the yield");
        }).thenExecute(fixture::finish).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "construction_budget_egress", timeoutTicks = 100)
    public static void repeatedEgressProofsShareMemoAndRespectTheirOwnSlice(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 4, 100, 1);
        SettlementCitizenEntity resident = fixture.residents.getFirst();
        BlueprintRecord blueprint = BlueprintRecord.create(BuildingType.HOUSE,
                fixture.origin.offset(2, 0, 5), fixture.origin.offset(2, 0, 5),
                Direction.SOUTH, UUID.randomUUID(), false);
        boolean deferred = false;
        try (var slice = ConstructionPlanningBudget.begin(resident, blueprint.id())) {
            for (int index = 0; index < 12; index++) {
                BlockPos changed = fixture.origin.offset(10 + index, 0, 5);
                boolean result = ConstructionAccessPlanService.hasEgressAfterChange(fixture.level,
                        blueprint, resident.blockPosition(), changed, Blocks.STONE.defaultBlockState());
                helper.assertTrue(result == ConstructionAccessPlanService.hasEgressAfterChange(fixture.level,
                                blueprint, resident.blockPosition(), changed, Blocks.STONE.defaultBlockState()),
                        "Repeated egress proof changed within identical geometry");
            }
        } catch (ConstructionPlanningBudget.Deferred waiting) {
            deferred = true;
        }
        var snapshot = ConstructionPlanningBudget.snapshot(fixture.level, resident.getUUID());
        helper.assertTrue(deferred && snapshot.residentEgress() == ConstructionPlanningBudget.EGRESS_PER_RESIDENT_TICK,
                "Ground-egress flood fills escaped their independent hard slice");
        helper.assertTrue(snapshot.residentCacheHits() == ConstructionPlanningBudget.EGRESS_PER_RESIDENT_TICK,
                "Repeated identical egress proofs were not memoized");
        fixture.finish();
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "construction_budget_local_batch", timeoutTicks = 1000)
    public static void groundSitePlacesOnePaidCellWithoutLocalEgressSearch(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 5, 1000, 1);
        SettlementCitizenEntity resident = fixture.residents.getFirst();
        UUID owner = UUID.randomUUID();
        SettlementRecord settlement = new SettlementRecord(UUID.randomUUID(), "Local budget batch",
                fixture.origin.offset(9, 0, 8), owner);
        BlueprintRecord blueprint = settlement.addBlueprint(BuildingType.HOUSE,
                fixture.origin.offset(9, 0, 8), fixture.origin.offset(9, 0, 8), Direction.NORTH, owner, false);
        var template = BlueprintTemplates.create(blueprint);
        for (BlueprintPlacement placement : template.placements()) {
            if (placement.mode() == BlueprintPlacementMode.OPTIONAL_SUPPORT
                    && !fixture.level.getBlockState(placement.position()).isAir()) continue;
            fixture.level.setBlock(placement.position(), placement.mode() == BlueprintPlacementMode.CLEARANCE
                    ? Blocks.AIR.defaultBlockState() : placement.state(), 2);
        }
        for (BlueprintPlacement placement : template.placements()) {
            BlueprintBlockStateResolver.refreshExisting(fixture.level, placement);
        }
        // Locate a ground-floor standing cell and a same-layer set of visible
        // wall blocks in the unchanged formal house. Roof targets intentionally
        // require scaffold entry in production, so they cannot exercise the
        // already-at-a-legal-station continuation branch in one work cycle.
        // Only fixture setup removes these cells; the real scheduler restores them.
        BlockPos station = null;
        List<BlueprintPlacement> unfinished = List.of();
        for (BlockPos candidate : BlockPos.betweenClosed(template.minCorner(), template.maxCorner())) {
            if (candidate.getX() <= template.minCorner().getX() || candidate.getX() >= template.maxCorner().getX()
                    || candidate.getZ() <= template.minCorner().getZ() || candidate.getZ() >= template.maxCorner().getZ()
                    || candidate.getY() != template.minCorner().getY() + 1
                    || !resident.canStandAtPosition(candidate)) continue;
            Vec3 eye = Vec3.atBottomCenterOf(candidate).add(0.0D, 1.62D, 0.0D);
            var byLayer = template.placements().stream()
                    .filter(placement -> placement.mode() == BlueprintPlacementMode.STRUCTURE && placement.material() != null)
                    .filter(placement -> placement.position().getY() == candidate.getY() + 1)
                    .filter(placement -> eye.distanceToSqr(Vec3.atCenterOf(placement.position())) <= 15.0D)
                    .filter(placement -> placement.state().isCollisionShapeFullBlock(fixture.level, placement.position()))
                    .filter(placement -> fixture.level.getBlockEntity(placement.position()) == null)
                    .filter(placement -> {
                        var hit = fixture.level.clip(new ClipContext(eye,
                                Vec3.atCenterOf(placement.position()), ClipContext.Block.COLLIDER,
                                ClipContext.Fluid.NONE, resident));
                        return hit.getType() == HitResult.Type.MISS
                                || hit.getBlockPos().equals(placement.position());
                    })
                    .collect(java.util.stream.Collectors.groupingBy(placement -> placement.position().getY()));
            for (List<BlueprintPlacement> layer : byLayer.values()) {
                if (layer.size() > unfinished.size()) {
                    station = candidate.immutable();
                    unfinished = List.copyOf(layer);
                }
            }
        }
        LOGGER.info("[construction-local-batch-fixture] templateVersion={} bounds={}..{} station={} candidates={}",
                blueprint.templateVersion(), template.minCorner(), template.maxCorner(), station, unfinished.size());
        helper.assertTrue(station != null && unfinished.size() >= 6,
                "Formal house exposed only " + unfinished.size()
                        + " close, visible ground-floor wall cells for the local-batch gate");
        resident.moveTo(station.getX() + 0.5D, station.getY(), station.getZ() + 0.5D, 0.0F, 0.0F);
        resident.setOnGround(true);
        CitizenRecord worker = settlement.citizens().iterator().next();
        for (CitizenRecord other : settlement.citizens()) if (other != worker) other.markDead(station);
        worker.assignProfession(CitizenProfession.WORKER, null);
        worker.updateProjection(resident.getUUID(), station);
        resident.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG, worker.id().toString());
        resident.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG, settlement.id().toString());
        for (BlueprintPlacement placement : unfinished) {
            fixture.level.setBlock(placement.position(), Blocks.AIR.defaultBlockState(), 2);
            worker.insertIntoInventory(new ItemStack(placement.material()));
        }
        helper.assertTrue(worker.startTask(CitizenWorkState.TO_BLUEPRINT,
                unfinished.getFirst().position(), null, blueprint.id()), "Could not assign the local construction task");
        fixture.level.setDayTime(6000L);
        final var readyCells = unfinished;
        final var readyStation = station;
        // This test isolates local structure batching after the newly required
        // startup frame is complete; frame construction has its own live gate.
        for (int y = template.minCorner().getY() + 3; y < template.maxCorner().getY(); y += 4) {
            var supports = ExteriorScaffoldFrame.columns(template, y);
            for (BlockPos c : supports) for (int cy = template.minCorner().getY() + 1; cy <= y; cy++) {
                BlockPos p = new BlockPos(c.getX(), cy, c.getZ());
                fixture.level.setBlock(p, Blocks.SCAFFOLDING.defaultBlockState().setValue(
                        net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE, 0), 3);
                if (!blueprint.hasTemporaryFooting(p)) blueprint.addTemporaryFooting(p, net.minecraft.world.item.Items.SCAFFOLDING, null);
            }
            for (BlockPos p : ExteriorScaffoldFrame.ring(template, y)) {
                int distance = supports.stream().mapToInt(p::distManhattan).min().orElseThrow();
                fixture.level.setBlock(p, Blocks.SCAFFOLDING.defaultBlockState().setValue(
                        net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE, distance), 3);
                if (!blueprint.hasTemporaryFooting(p)) blueprint.addTemporaryFooting(p, net.minecraft.world.item.Items.SCAFFOLDING, null);
            }
        }
        BlockPos groundStation = template.minCorner().offset(-2, 1, 2);
        resident.moveTo(groundStation.getX() + .5, groundStation.getY(), groundStation.getZ() + .5, 0, 0);
        resident.setOnGround(true);
        resident.setNoAi(true);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                fixture.level.getEntity(resident.getUUID()) == resident,
                "Remote fixture has not entered real entity tracking")).thenExecute(() -> {
        CitizenWorkService.advanceSiteConstruction(fixture.level, new SettlementSavedData(), settlement, worker, resident);
        long placed = readyCells.stream().filter(placement ->
                fixture.level.getBlockState(placement.position()).is(placement.state().getBlock())).count();
        var snapshot = ConstructionPlanningBudget.snapshot(fixture.level, resident.getUUID());
        LOGGER.info("[construction-local-batch] station={} candidates={} first={} placed={} state={} activity={} failure={} target={} position={} decision={} budget={}",
                readyStation, readyCells.size(), readyCells.getFirst().position(), placed, worker.workState(),
                worker.activity(), worker.workFailure(), worker.targetPosition(), resident.position(),
                resident.constructionDecisionTrace(), snapshot);
        helper.assertTrue(placed == 1,
                "Local batch placed " + placed + " cells; state=" + worker.workState()
                        + "; failure=" + worker.workFailure() + "; egress=" + snapshot.residentEgress()
                        + "; details in construction-local-batch log");
        helper.assertTrue(snapshot.residentEgress() <= ConstructionPlanningBudget.EGRESS_PER_RESIDENT_TICK,
                "Local batch bypassed the existing egress safety budget");
        fixture.finish();
        }).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "frame_worker", timeoutTicks = 6000)
    public static void residentCompletesWholeFrameBeforeGroundConstruction(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 10, 6000, 1);
        var resident = fixture.residents.getFirst();
        // Fixture's default enclosure belongs to path benchmarks, not this open
        // worksite; otherwise its unrelated wall intersects the exterior ring.
        for (BlockPos p : BlockPos.betweenClosed(fixture.origin.offset(1, 0, 4), fixture.origin.offset(3, 3, 6))) {
            fixture.level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
        }
        UUID owner = UUID.randomUUID();
        var data = new SettlementSavedData();
        var settlement = data.createOrRecover(fixture.origin.offset(9, 0, 8), owner, "Frame worker").value();
        settlement.upgradeLevel(); settlement.upgradeLevel();
        var blueprint = settlement.addBlueprint(BuildingType.HOUSE,
                fixture.origin.offset(9, 0, 8), fixture.origin.offset(9, 0, 8), Direction.NORTH, owner, false);
        var template = BlueprintTemplates.create(blueprint);
        for (BlueprintPlacement p : template.placements()) {
            if (p.mode() == BlueprintPlacementMode.OPTIONAL_SUPPORT && !fixture.level.getBlockState(p.position()).isAir()) continue;
            fixture.level.setBlock(p.position(), p.mode() == BlueprintPlacementMode.CLEARANCE
                    ? Blocks.AIR.defaultBlockState() : p.state(), 2);
        }
        var missing = template.placements().stream().filter(p -> p.mode() == BlueprintPlacementMode.STRUCTURE
                && p.material() != null && p.position().getY() == template.minCorner().getY() + 4).findFirst().orElseThrow();
        fixture.level.setBlock(missing.position(), Blocks.AIR.defaultBlockState(), 2);
        BlockPos start = template.minCorner().offset(-1, 1, -1);
        resident.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0, 0);
        resident.setOnGround(true);
        var worker = settlement.citizens().iterator().next();
        for (var other : settlement.citizens()) if (other != worker) other.markDead(start);
        worker.assignProfession(CitizenProfession.WORKER, null);
        worker.updateProjection(resident.getUUID(), start);
        resident.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG, worker.id().toString());
        resident.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG, settlement.id().toString());
        worker.insertIntoInventory(new ItemStack(net.minecraft.world.item.Items.SCAFFOLDING, 256));
        worker.insertIntoInventory(new ItemStack(missing.material(), 16));
        worker.startTask(CitizenWorkState.TO_BLUEPRINT, missing.position(), null, blueprint.id());
        var ring = new ArrayList<BlockPos>();
        for (int y = template.minCorner().getY() + 3; y < template.maxCorner().getY(); y += 4) {
            ring.addAll(ExteriorScaffoldFrame.ring(template, y));
        }
        class Run {
            void tick(long tick) {
                if (fixture.finished) return;
                fixture.level.setDayTime(6000);
                CitizenWorkService.tickSettlement(fixture.level, data, settlement);
                CitizenWorkService.advanceSiteConstruction(fixture.level, data, settlement, worker, resident);
                if (fixture.level.getBlockState(missing.position()).is(missing.state().getBlock())) {
                    helper.assertTrue(ring.stream().allMatch(p -> fixture.level.getBlockState(p).is(Blocks.SCAFFOLDING)),
                            "Structure was placed before completing the frame");
                    LOGGER.info("[frame-worker] complete tick={} cells={} footings={}", tick, ring.size(), blueprint.temporaryFootings().size());
                    fixture.finish(); helper.succeed(); return;
                }
                if (tick % 1000 == 0) LOGGER.info("[frame-worker] tick={} footings={} pos={} failure={} decision={}",
                        tick, blueprint.temporaryFootings().size(), resident.position(), worker.workFailure(), resident.constructionDecisionTrace());
                if (tick >= 5800) {
                    fixture.finish();
                    helper.fail("Frame incomplete: " + blueprint.temporaryFootings().size() + " footings; "
                            + worker.workFailure() + "; " + resident.constructionDecisionTrace());
                    return;
                }
                helper.runAtTickTime(tick + 1, () -> tick(tick + 1));
            }
        }
        helper.runAtTickTime(20, () -> new Run().tick(20));
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "scaffold_grip", timeoutTicks = 800)
    public static void scaffoldArrivalKeepsGripUntilNextTaskOrSupportRemoval(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 11, 800, 1);
        var resident = fixture.residents.getFirst();
        resident.setNoAi(true); // Isolate the explicit climb controller from idle wandering goals.
        BlockPos base = fixture.origin.offset(10, 0, 4), top = base.above(4);
        for (int dy = 0; dy <= 4; dy++) fixture.level.setBlock(base.above(dy),
                Blocks.SCAFFOLDING.defaultBlockState().setValue(
                        net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE, 0), 3);
        BlockPos arm = top.east();
        fixture.level.setBlock(arm, Blocks.SCAFFOLDING.defaultBlockState().setValue(
                net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE, 1), 3);
        resident.moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 0, 0);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(resident.tickCount > 0,
                "Scaffold fixture has not entered actual entity ticking"))
                .thenExecute(() -> helper.assertTrue(resident.beginScaffoldingWorkHeight(top, 0.65),
                "Could not begin actual scaffold ascent"))
                .thenExecuteAfter(80, () -> helper.assertTrue(
                Math.abs(resident.getY() - top.getY()) < 0.1 && !resident.hasActiveConstructionMovement(),
                "Scaffold arrival did not hold its work height: " + resident.position()
                        + "; " + resident.constructionMovementDiagnostics()))
                .thenExecuteAfter(10, () -> helper.assertTrue(resident.beginAdjacentConstructionMovement(arm, 0.65),
                "Could not transfer from tower to the horizontal scaffold"))
                .thenExecuteAfter(70, () -> {
            helper.assertTrue(Math.abs(resident.getY() - top.getY()) < 0.1
                            && resident.blockPosition().getX() == arm.getX(),
                    "Resident fell during the idle work handoff: " + resident.position());
            fixture.level.setBlock(arm, Blocks.AIR.defaultBlockState(), 3);
        }).thenExecuteAfter(20, () -> {
            helper.assertTrue(!resident.isNoGravity(), "Missing scaffold left resident suspended in air");
            fixture.finish();
        }).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "scaffold_supply_egress", timeoutTicks = 1200)
    public static void highWorkerDescendsBeforeSeekingMaterials(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 12, 1200, 1);
        var resident = fixture.residents.getFirst();
        UUID owner = UUID.randomUUID();
        var settlement = new SettlementRecord(UUID.randomUUID(), "Supply descent", fixture.origin.offset(9, 0, 8), owner);
        var blueprint = settlement.addBlueprint(BuildingType.HOUSE, fixture.origin.offset(9, 0, 8),
                fixture.origin.offset(9, 0, 8), Direction.NORTH, owner, false);
        var template = BlueprintTemplates.create(blueprint);
        BlockPos base = new BlockPos(template.maxCorner().getX() + 1, template.minCorner().getY() + 1,
                template.minCorner().getZ() - 1);
        for (int dy = 0; dy <= 4; dy++) {
            BlockPos p = base.above(dy);
            fixture.level.setBlock(p, Blocks.SCAFFOLDING.defaultBlockState().setValue(
                    net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE, 0), 3);
            blueprint.addTemporaryFooting(p, net.minecraft.world.item.Items.SCAFFOLDING, dy == 0 ? null : p.below());
        }
        var worker = settlement.citizens().iterator().next();
        for (var other : settlement.citizens()) if (other != worker) other.markDead(base);
        worker.assignProfession(CitizenProfession.WORKER, null);
        worker.updateProjection(resident.getUUID(), base.above(4));
        resident.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG, worker.id().toString());
        resident.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG, settlement.id().toString());
        resident.moveTo(base.getX() + .5, base.getY() + 4, base.getZ() + .5, 0, 0);
        resident.setNoAi(true);
        BlockPos target = template.placements().stream().filter(p -> p.material() != null).findFirst().orElseThrow().position();
        worker.startTask(CitizenWorkState.TO_CONSTRUCTION_SUPPLY, target, null, blueprint.id());
        var data = new SettlementSavedData();
        boolean[] reachedGround = {false};
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(resident.tickCount > 0
                        && fixture.level.getEntity(resident.getUUID()) == resident,
                "Supply fixture has not entered actual entity ticking"))
                .thenExecuteFor(300, () -> {
                if (reachedGround[0]) return;
                CitizenWorkService.tickSettlement(fixture.level, data, settlement);
                if (resident.getY() <= base.getY() + .1 && !resident.hasActiveConstructionMovement()
                        && !fixture.level.getBlockState(resident.blockPosition()).is(Blocks.SCAFFOLDING)) {
                    reachedGround[0] = true;
                }
            }).thenExecute(() -> {
                fixture.finish();
                helper.assertTrue(reachedGround[0], "Supply worker did not leave scaffold: " + resident.position()
                        + "; " + worker.workFailure() + "; " + resident.constructionDecisionTrace());
            }).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "scaffold_owner", timeoutTicks = 1000)
    public static void scaffoldWorkersCanShareFrameWork(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 13, 1000, 2);
        UUID owner = UUID.randomUUID();
        var settlement = new SettlementRecord(UUID.randomUUID(), "Exclusive scaffolding", f.origin, owner);
        var site = settlement.addBlueprint(BuildingType.HOUSE, f.origin, f.origin, Direction.NORTH, owner, false);
        var workers = new ArrayList<>(settlement.citizens());
        for (int i = 0; i < 2; i++) {
            workers.get(i).updateProjection(f.residents.get(i).getUUID(), f.origin);
            workers.get(i).startTask(CitizenWorkState.TO_BLUEPRINT, f.origin, null, site.id());
        }
        helper.startSequence().thenExecuteAfter(5, () -> { }).thenWaitUntil(() -> helper.assertTrue(
                f.residents.stream().allMatch(e -> f.level.getEntity(e.getUUID()) != null), "Entities not loaded"))
                .thenExecute(() -> {
                    var a = workers.get(0); var b = workers.get(1);
                    helper.assertTrue(CitizenWorkService.claimScaffoldWork(f.level, settlement, site, a), "First worker denied");
                    helper.assertTrue(CitizenWorkService.claimScaffoldWork(f.level, settlement, site, b), "Second scaffold worker denied");
                    a.fetchFootingMaterial(null);
                    helper.assertTrue(CitizenWorkService.claimScaffoldWork(f.level, settlement, site, b), "Material trip blocked another worker");
                    a.clearWork();
                    helper.assertTrue(CitizenWorkService.claimScaffoldWork(f.level, settlement, site, b), "Task change did not release owner");
                    a.startTask(CitizenWorkState.TO_BLUEPRINT, f.origin, null, site.id());
                    f.residents.get(1).discard();
                    helper.assertTrue(CitizenWorkService.claimScaffoldWork(f.level, settlement, site, a), "Removed entity retained ownership");
                    a.markDead(f.origin);
                    helper.assertTrue(!CitizenWorkService.claimScaffoldWork(f.level, settlement, site, a), "Dead worker admitted");
                    f.finish();
                }).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "frame_acceptance", timeoutTicks = 12000)
    public static void frameFromStockHOUSENORTH(GameTestHelper helper) {
        frameFromStock(helper, BuildingType.HOUSE, Direction.NORTH, 0);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "frame_acceptance", timeoutTicks = 12000)
    public static void frameFromStockHOUSEEAST(GameTestHelper helper) {
        frameFromStock(helper, BuildingType.HOUSE, Direction.EAST, 1);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "frame_acceptance", timeoutTicks = 12000)
    public static void frameFromStockHOUSESOUTH(GameTestHelper helper) {
        frameFromStock(helper, BuildingType.HOUSE, Direction.SOUTH, 2);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "frame_acceptance", timeoutTicks = 12000)
    public static void frameFromStockHOUSEWEST(GameTestHelper helper) {
        frameFromStock(helper, BuildingType.HOUSE, Direction.WEST, 3);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "frame_acceptance", timeoutTicks = 12000)
    public static void frameFromStockHOUSE2NORTH(GameTestHelper helper) {
        frameFromStock(helper, BuildingType.HOUSE_2, Direction.NORTH, 4);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "frame_acceptance", timeoutTicks = 12000)
    public static void frameFromStockHOUSE2EAST(GameTestHelper helper) {
        frameFromStock(helper, BuildingType.HOUSE_2, Direction.EAST, 5);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "frame_acceptance", timeoutTicks = 12000)
    public static void frameFromStockHOUSE2SOUTH(GameTestHelper helper) {
        frameFromStock(helper, BuildingType.HOUSE_2, Direction.SOUTH, 6);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "frame_acceptance", timeoutTicks = 12000)
    public static void frameFromStockHOUSE2WEST(GameTestHelper helper) {
        frameFromStock(helper, BuildingType.HOUSE_2, Direction.WEST, 7);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "frame_acceptance", timeoutTicks = 12000)
    public static void frameFromStockWAREHOUSENORTH(GameTestHelper helper) {
        frameFromStock(helper, BuildingType.WAREHOUSE, Direction.NORTH, 8);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "frame_acceptance", timeoutTicks = 12000)
    public static void frameFromStockWAREHOUSEEAST(GameTestHelper helper) {
        frameFromStock(helper, BuildingType.WAREHOUSE, Direction.EAST, 9);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "frame_acceptance", timeoutTicks = 12000)
    public static void frameFromStockWAREHOUSESOUTH(GameTestHelper helper) {
        frameFromStock(helper, BuildingType.WAREHOUSE, Direction.SOUTH, 10);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "frame_acceptance", timeoutTicks = 12000)
    public static void frameFromStockWAREHOUSEWEST(GameTestHelper helper) {
        frameFromStock(helper, BuildingType.WAREHOUSE, Direction.WEST, 11);
    }

    private static void frameFromStock(GameTestHelper helper, BuildingType type, Direction facing, int scenario) {
        frameFromStock(helper, type, facing, scenario, 0);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "frame_missing_stock", timeoutTicks = 12000)
    public static void scaffoldResumesAfterStockReplenishment(GameTestHelper helper) {
        frameFromStock(helper, BuildingType.HOUSE, Direction.NORTH, 12, 1);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "frame_night_pause", timeoutTicks = 12000)
    public static void scaffoldResumesAfterNightPause(GameTestHelper helper) {
        frameFromStock(helper, BuildingType.HOUSE, Direction.NORTH, 13, 2);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "construction_pair_station")
    public static void bedPartnerVolumeCannotBeAWorkStation(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 50, 100, 1);
        try {
            var resident = fixture.residents.getFirst();
            var site = BlueprintRecord.create(BuildingType.HOUSE, fixture.origin.offset(9, 0, 8),
                    fixture.origin.offset(9, 0, 8), Direction.NORTH, UUID.randomUUID(), false);
            var template = BlueprintTemplates.create(site);
            var bed = template.placements().stream().filter(p -> p.state().getBlock()
                    instanceof net.minecraft.world.level.block.BedBlock)
                    .filter(p -> com.hkin.exiledsettlement.blueprint.BlueprintPlacementGroups.isPrimary(p.state()))
                    .findFirst().orElseThrow();
            var partner = com.hkin.exiledsettlement.blueprint.BlueprintPlacementGroups.partnerPosition(bed);
            for (BlockPos p : BlockPos.betweenClosed(partner.offset(-3, -1, -3), partner.offset(3, 2, 3))) {
                fixture.level.setBlock(p, p.getY() < partner.getY()
                        ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
            }
            resident.moveTo(partner.getX() + 0.5, partner.getY(), partner.getZ() + 0.5, 0, 0);
            resident.setOnGround(true);
            helper.assertTrue(!resident.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(bed.position())),
                    "Fixture must occupy only the second half of the bed");
            helper.assertTrue(!site.constructionController().movement(resident).approach(
                    fixture.level, site, resident, bed.position(), 0.7),
                    "Controller accepted a worker standing inside the bed's partner volume");
            helper.succeed();
        } finally { fixture.finish(); }
    }

    private static void frameFromStock(GameTestHelper helper, BuildingType type, Direction facing, int scenario, int interruption) {
        Fixture f = new Fixture(helper, 30 + scenario, 12000, 1);
        UUID owner = UUID.randomUUID();
        var settlement = new SettlementRecord(UUID.randomUUID(), "Frame acceptance", f.origin.offset(9, 0, 8), owner);
        settlement.upgradeLevel(); settlement.upgradeLevel();
        var site = settlement.addBlueprint(type, f.origin.offset(9, 0, 8), f.origin.offset(9, 0, 8), facing, owner, false);
        var template = BlueprintTemplates.create(site);
        BlockPos min = template.minCorner().offset(-6, 0, -6), max = template.maxCorner().offset(6, 3, 6);
        for (int x = new ChunkPos(min).x; x <= new ChunkPos(max).x; x++) {
            for (int z = new ChunkPos(min).z; z <= new ChunkPos(max).z; z++) {
                var chunk = new ChunkPos(x, z);
                if (!f.chunks.contains(chunk)) { f.chunks.add(chunk); f.level.setChunkForced(x, z, true); }
            }
        }
        for (BlockPos p : BlockPos.betweenClosed(min.below(2), max)) {
            f.level.setBlock(p, p.getY() <= template.minCorner().getY()
                    ? Blocks.DIRT.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
        }
        BlockPos stock = template.minCorner().offset(-4, 1, 1);
        var warehouse = settlement.addBuilding(BuildingType.WAREHOUSE, stock);
        List<BlockPos> stores = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            BlockPos p = stock.south(i * 2);
            stores.add(p); f.level.setBlock(p, Blocks.BARREL.defaultBlockState(), 3);
        }
        warehouse.updateFunction(true, com.hkin.exiledsettlement.building.BuildingStatus.OPERATIONAL, 81, stores);
        var materials = new java.util.LinkedHashMap<net.minecraft.world.item.Item, Integer>();
        materials.put(net.minecraft.world.item.Items.SCAFFOLDING, 512);
        materials.put(net.minecraft.world.item.Items.IRON_AXE, 1);
        materials.put(net.minecraft.world.item.Items.IRON_PICKAXE, 1);
        for (var p : template.placements()) if (p.material() != null) materials.merge(p.material(), 1, Integer::sum);
        int slot = 0;
        for (var entry : materials.entrySet()) {
            int left = entry.getKey().getDefaultMaxStackSize() == 1 ? entry.getValue() : Math.max(64, entry.getValue());
            while (left > 0) {
                int n = Math.min(left, entry.getKey().getDefaultMaxStackSize());
                ((net.minecraft.world.Container) f.level.getBlockEntity(stores.get(slot / 27)))
                        .setItem(slot++ % 27, new ItemStack(entry.getKey(), n));
                left -= n;
            }
        }
        if (interruption == 1) {
            for (BlockPos p : stores) {
                var c = (net.minecraft.world.Container) f.level.getBlockEntity(p);
                for (int i = 0; i < c.getContainerSize(); i++) {
                    if (c.getItem(i).is(net.minecraft.world.item.Items.SCAFFOLDING)) c.setItem(i, ItemStack.EMPTY);
                }
            }
        }
        var resident = f.residents.getFirst();
        BlockPos start = stock.north(2);
        resident.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0, 0);
        resident.setOnGround(true);
        var worker = settlement.citizens().iterator().next();
        for (var other : settlement.citizens()) if (other != worker) other.markDead(start);
        worker.assignProfession(CitizenProfession.WORKER, null);
        worker.updateProjection(resident.getUUID(), start);
        resident.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG, worker.id().toString());
        resident.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG, settlement.id().toString());
        var pending = template.placements().stream().filter(p -> p.mode() == BlueprintPlacementMode.STRUCTURE
                && p.material() != null).findFirst().orElseThrow();
        worker.startTask(CitizenWorkState.TO_BLUEPRINT, pending.position(), null, site.id());
        helper.assertTrue(worker.inventory().stream().allMatch(ItemStack::isEmpty), "Resident must start with an empty backpack");
        var data = new SettlementSavedData();
        class Run {
            int previousCount, trips, stalls, falls;
            double previousY = Double.NaN;
            long lastProgress, begun = -1;
            long nightStarted = -1;
            int beforeNight;
            CitizenWorkState previous = CitizenWorkState.IDLE;
            void tick(long tick) {
                if (f.finished) return;
                if (resident.tickCount == 0) { helper.runAtTickTime(tick + 1, () -> tick(tick + 1)); return; }
                if (begun < 0) { begun = tick; lastProgress = tick; }
                if (!Double.isNaN(previousY) && previousY - resident.getY() > 0.75) {
                    falls++;
                    LOGGER.warn("[frame-fall] type={} facing={} tick={} previousY={} pos={} move={} site={} route={}",
                            type, facing, tick, previousY, resident.position(), resident.constructionMovementDiagnostics(),
                            site.constructionController().diagnostics(), site.constructionController().movementDiagnostics());
                }
                previousY = resident.getY();
                // Standalone frame test uses working hours. Full-day gate separately includes rest.
                boolean night = interruption == 2 && nightStarted >= 0 && tick - nightStarted < 200;
                if (interruption == 2 && nightStarted < 0 && previousCount >= 1 && !resident.hasActiveConstructionMovement()) {
                    nightStarted = tick; beforeNight = previousCount; night = true;
                }
                if (interruption == 1 && tick - begun == 400) {
                    var c = (net.minecraft.world.Container) f.level.getBlockEntity(stores.getFirst());
                    for (int i = 0; i < 8; i++) c.setItem(i, new ItemStack(net.minecraft.world.item.Items.SCAFFOLDING, 64));
                }
                f.level.setDayTime(night ? 15000 : 6000);
                if (tick % CitizenWorkService.WORK_INTERVAL_TICKS == 0) CitizenWorkService.tickSettlement(f.level, data, settlement);
                if (tick % 10 == 0) CitizenWorkService.advanceSiteConstruction(f.level, data, settlement, worker, resident);
                int count = site.temporaryFootings().size();
                if (interruption == 1 && tick - begun < 400) helper.assertTrue(count == 0, "Frame grew without scaffold stock");
                if (night) helper.assertTrue(count == beforeNight, "Frame progressed during night rest");
                if (count > previousCount) { lastProgress = tick; previousCount = count; }
                if ((worker.workState() == CitizenWorkState.TO_FOOTING_SUPPLY
                        || worker.workState() == CitizenWorkState.TO_CONSTRUCTION_SUPPLY) && previous != worker.workState()) trips++;
                previous = worker.workState();
                if (tick - lastProgress == 200) {
                    stalls++;
                    LOGGER.warn("[frame-acceptance-stall] type={} facing={} pos={} status={} route={} failure={}",
                            type, facing, resident.position(), site.constructionController().diagnostics(),
                            site.constructionController().movementDiagnostics(), worker.workFailure());
                }
                var frame = site.constructionController().frame(f.level, site);
                long built = template.placements().stream().filter(p -> p.material() != null
                        && f.level.getBlockState(p.position()).equals(p.state())).count();
                // Access is now demand-driven: assert real frame AND structural work,
                // not the retired requirement to finish every ring before building.
                if (!frame.isEmpty() && count > 0 && built >= 8
                        && (interruption != 2 || nightStarted >= 0 && !night)) {
                    helper.assertTrue(trips > 0, "Frame was not built using real stock pickup");
                    helper.assertTrue(interruption != 2 || nightStarted >= 0, "Night pause was not exercised");
                    helper.assertTrue(falls == 0, "Resident fell more than 0.75 blocks in a tick");
                    helper.assertTrue(site.temporaryFootings().keySet().stream().allMatch(p ->
                            p.getX() < template.minCorner().getX() || p.getX() > template.maxCorner().getX()
                                    || p.getZ() < template.minCorner().getZ() || p.getZ() > template.maxCorner().getZ()),
                            "Scaffold entered building footprint");
                    LOGGER.info("[frame-acceptance] type={} facing={} elapsed={} cells={} trips={} stalls={}",
                            type, facing, tick - begun, count, trips, stalls);
                    var builtFrame = frame.stream().filter(p -> f.level.getBlockState(p).is(Blocks.SCAFFOLDING)).toList();
                    helper.runAtTickTime(tick + 60, () -> {
                        helper.assertTrue(builtFrame.stream().allMatch(p -> f.level.getBlockState(p).is(Blocks.SCAFFOLDING)),
                                "Built scaffold cells collapsed during stability observation");
                        int stored = 0;
                        for (BlockPos p : stores) {
                            var c = (net.minecraft.world.Container) f.level.getBlockEntity(p);
                            for (int i = 0; i < c.getContainerSize(); i++) {
                                if (c.getItem(i).is(net.minecraft.world.item.Items.SCAFFOLDING)) stored += c.getItem(i).getCount();
                            }
                        }
                        int carried = worker.inventory().stream().filter(s -> s.is(net.minecraft.world.item.Items.SCAFFOLDING))
                                .mapToInt(ItemStack::getCount).sum();
                        helper.assertTrue(stored + carried + count == 512, "Scaffold material conservation failed");
                        f.finish(); helper.succeed();
                    });
                    return;
                }
                if (tick >= 11800) {
                    String reason = type + "/" + facing + " frame incomplete: " + count + " feet=" + resident.position()
                            + " task=" + worker.workState() + " failure=" + worker.workFailure()
                            + " site=" + site.constructionController().diagnostics()
                            + " route=" + site.constructionController().movementDiagnostics();
                    f.finish(); helper.fail(reason); return;
                }
                helper.runAtTickTime(tick + 1, () -> tick(tick + 1));
            }
        }
        Run run = new Run(); helper.runAtTickTime(1, () -> run.tick(1));
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "scaffold_round_trip", timeoutTicks = 700)
    public static void scaffoldRecordedRouteCompletesRoundTrip(GameTestHelper helper) {
        scaffoldRoundTrip(helper, Direction.EAST, 30);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "scaffold_round_trip", timeoutTicks = 700)
    public static void scaffoldRoundTripWest(GameTestHelper helper) {
        scaffoldRoundTrip(helper, Direction.WEST, 31);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "scaffold_round_trip", timeoutTicks = 700)
    public static void scaffoldRoundTripNorth(GameTestHelper helper) {
        scaffoldRoundTrip(helper, Direction.NORTH, 32);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "scaffold_round_trip", timeoutTicks = 700)
    public static void scaffoldRoundTripSouth(GameTestHelper helper) {
        scaffoldRoundTrip(helper, Direction.SOUTH, 33);
    }

    private static void scaffoldRoundTrip(GameTestHelper helper, Direction side, int scenario) {
        Fixture f = new Fixture(helper, scenario, 700, 1);
        var resident = f.residents.getFirst();
        resident.setNoAi(true);
        BlockPos base = f.origin.offset(10, 0, 4);
        for (int y = 0; y <= 3; y++) f.level.setBlock(base.above(y),
                Blocks.SCAFFOLDING.defaultBlockState()
                        .setValue(ScaffoldingBlock.DISTANCE, 0)
                        .setValue(ScaffoldingBlock.BOTTOM, y == 0), 2);
        f.level.setBlock(base.above(3).relative(side), Blocks.SCAFFOLDING.defaultBlockState()
                .setValue(ScaffoldingBlock.DISTANCE, 1).setValue(ScaffoldingBlock.BOTTOM, false), 2);
        f.level.setBlock(base.above(3).relative(side).relative(side.getClockWise()),
                Blocks.SCAFFOLDING.defaultBlockState().setValue(ScaffoldingBlock.DISTANCE, 2)
                        .setValue(ScaffoldingBlock.BOTTOM, false), 2);
        BlockPos ground = base.relative(side.getOpposite());
        resident.moveTo(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5, 0, 0);
        List<BlockPos> outward = List.of(base, base.above(), base.above(2), base.above(3),
                base.above(4), base.above(4).relative(side), base.above(4).relative(side).relative(side.getClockWise()));
        List<BlockPos> back = new ArrayList<>(outward);
        java.util.Collections.reverse(back);
        back.removeFirst();
        back.add(ground);
        helper.startSequence().thenExecuteAfter(10, () -> {
            helper.assertTrue(resident.canTraverseRecordedConstructionEdge(base.above(3), base.above(4)),
                    "Scaffold column cannot reach its own top surface");
            helper.assertTrue(resident.beginRecordedConstructionRoute(outward, 0.65),
                    "Could not begin outward route: " + resident.constructionMovementDiagnostics());
        }).thenWaitUntil(() -> helper.assertTrue(!resident.hasActiveConstructionMovement()
                        && resident.position().distanceToSqr(Vec3.atBottomCenterOf(outward.getLast())) < 0.04,
                "Outward route failed: " + resident.constructionMovementDiagnostics()))
                .thenExecute(() -> helper.assertTrue(resident.beginRecordedConstructionRoute(back, 0.65),
                        "Could not start return route"))
                .thenWaitUntil(() -> helper.assertTrue(!resident.hasActiveConstructionMovement()
                                && resident.position().distanceToSqr(Vec3.atBottomCenterOf(ground)) < 0.04,
                        "Return route failed: " + resident.constructionMovementDiagnostics()))
                .thenExecute(f::finish).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "scaffold_round_trip", timeoutTicks = 100)
    public static void scaffoldRouteRejectsLowEaveBeforeMovement(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 34, 100, 1);
        var resident = f.residents.getFirst();
        BlockPos start = f.origin.offset(10, 2, 4), destination = start.east().below();
        f.level.setBlock(start.below(), Blocks.SCAFFOLDING.defaultBlockState(), 2);
        f.level.setBlock(destination.below(), Blocks.STONE.defaultBlockState(), 2);
        // Destination has two clear cells, but the horizontal crossing at the
        // original height clips the eave before the resident can descend.
        f.level.setBlock(destination.above(2), Blocks.STONE.defaultBlockState(), 2);
        resident.moveTo(Vec3.atBottomCenterOf(start));
        helper.assertTrue(resident.canOccupyConstructionPosition(destination), "Landing must be legal");
        helper.assertTrue(!resident.canTraverseRecordedConstructionEdge(start, destination),
                "Planner accepted a route the horizontal executor cannot follow");
        f.finish();
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "site_recovery", timeoutTicks = 1000)
    public static void unreachableWorkTargetRetreatsAndResumesPlacement(GameTestHelper helper) {
        workTargetRecovery(helper, 35, 4);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "site_recovery", timeoutTicks = 1000)
    public static void scaffoldRootExitsAndResumesPlacement(GameTestHelper helper) {
        workTargetRecovery(helper, 36, 0);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "site_recovery", timeoutTicks = 1000)
    public static void isolatedRoofExitsAndResumesPlacement(GameTestHelper helper) {
        workTargetRecovery(helper, 37, -4);
    }

    private static void workTargetRecovery(GameTestHelper helper, int scenario, int startHeight) {
        Fixture f = new Fixture(helper, scenario, 1000, 1);
        var resident = f.residents.getFirst();
        resident.setNoAi(true);
        var settlement = new SettlementRecord(UUID.randomUUID(), "Recovery", f.origin, UUID.randomUUID());
        var site = settlement.addBlueprint(BuildingType.HOUSE, f.origin.offset(9, 0, 8),
                f.origin.offset(9, 0, 8), Direction.NORTH, UUID.randomUUID(), false);
        var template = BlueprintTemplates.create(site);
        BlockPos base = new BlockPos(template.maxCorner().getX() + 1,
                template.minCorner().getY() + 1, template.minCorner().getZ() - 1);
        f.level.setBlock(base.below(), Blocks.STONE.defaultBlockState(), 3);
        for (int y = 0; startHeight >= 0 && y <= 4; y++) {
            BlockPos p = base.above(y);
            f.level.setBlock(p, Blocks.SCAFFOLDING.defaultBlockState(), 3);
            site.addTemporaryFooting(p, net.minecraft.world.item.Items.SCAFFOLDING);
        }
        if (startHeight < 0) f.level.setBlock(base.above(3), Blocks.STONE.defaultBlockState(), 3);
        resident.moveTo(Vec3.atBottomCenterOf(base.above(Math.abs(startHeight))));
        var movement = site.constructionController().movement(resident);
        BlockPos impossible = base.above(30);
        final boolean[] recovered = {false};
        final BlueprintPlacement[] next = {null};
        helper.onEachTick(() -> {
            if (!recovered[0]) {
                movement.approach(f.level, site, resident, impossible, 0.65);
                if (!movement.consumeRecoveryCompleted()) return;
                helper.assertTrue(resident.getY() <= base.getY() + 0.1, "Recovery did not reach ground");
                recovered[0] = true;
                next[0] = template.placements().stream().filter(p -> p.material() != null
                        && p.position().getY() == template.minCorner().getY()
                        && p.mode() != BlueprintPlacementMode.CLEARANCE)
                        .min(java.util.Comparator.comparingDouble(p -> p.position().distSqr(base))).orElseThrow();
                // This is a movement/recovery fixture, not a foundation-order
                // test. Supply real completed lower support for the work node.
                for (var support : template.placements()) {
                    if (support.position().getY() <= next[0].position().getY()
                            && support.mode() != BlueprintPlacementMode.CLEARANCE) {
                        f.level.setBlock(support.position(), support.state(), 3);
                    }
                }
                f.level.setBlock(next[0].position(), Blocks.AIR.defaultBlockState(), 3);
            }
            if (!movement.approach(f.level, site, resident, next[0].position(), 0.65)) return;
            var placed = com.hkin.exiledsettlement.blueprint.BlueprintService.placeAt(f.level,
                    new SettlementSavedData(), settlement, site, next[0].position(),
                    new ItemStack(next[0].material()), resident);
            helper.assertTrue(placed.success(), "Recovered resident could not place: " + placed.reason());
            helper.assertTrue(f.level.getBlockState(next[0].position()).is(next[0].state().getBlock()),
                    "No real block placed after recovery");
            f.finish(); helper.succeed();
        });
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "site_recovery", timeoutTicks = 1000)
    public static void workerInsideCatwalkClimbsOntoSurfaceBeforeWorking(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 38, 1000, 1);
        var resident = f.residents.getFirst();
        resident.setNoAi(true);
        var site = BlueprintRecord.create(BuildingType.HOUSE, f.origin.offset(9, 0, 8),
                f.origin.offset(9, 0, 8), Direction.NORTH, UUID.randomUUID(), false);
        BlockPos base = f.origin.offset(10, 0, 4), body = base.above(3).east();
        for (int y = 0; y <= 3; y++) f.level.setBlock(base.above(y), Blocks.SCAFFOLDING.defaultBlockState(), 3);
        f.level.setBlock(body, Blocks.SCAFFOLDING.defaultBlockState(), 3);
        site.addTemporaryFooting(body, net.minecraft.world.item.Items.SCAFFOLDING);
        resident.moveTo(Vec3.atBottomCenterOf(body));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                f.level.getEntity(resident.getUUID()) == resident && resident.tickCount > 0,
                "Catwalk fixture has not entered real entity ticking")).thenExecuteAfter(5, () -> {
            // Restore the reported pose after fixture settling, then invoke the
            // production site controller rather than manually ordering ascent.
            resident.moveTo(Vec3.atBottomCenterOf(body));
            helper.assertTrue(!site.constructionController().movement(resident).approach(
                    f.level, site, resident, body.east(2), 0.65), "Scaffold interior accepted as work station");
        }).thenExecuteAfter(30, () -> {
            helper.assertTrue(resident.position().distanceToSqr(Vec3.atBottomCenterOf(body.above())) < 0.04,
                    "Worker remained inside catwalk: " + resident.position() + "; " + resident.constructionMovementDiagnostics());
            helper.assertTrue(!resident.hasActiveConstructionMovement(), "Surface exit never completed");
            f.finish();
        }).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "scaffold_ground_handoff", timeoutTicks = 1600)
    public static void scaffoldWorkerDescendsOpensDoorAndPlacesSameLantern(GameTestHelper helper) {
        groundLanternHandoff(helper, 39, false);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "scaffold_ground_handoff", timeoutTicks = 1600)
    public static void workServiceKeepsLanternTaskAcrossScaffoldDescent(GameTestHelper helper) {
        groundLanternHandoff(helper, 40, true);
    }

    private static void groundLanternHandoff(GameTestHelper helper, int scenario, boolean useWorkService) {
        Fixture f = new Fixture(helper, scenario, 1600, 1);
        var resident = f.residents.getFirst();
        var settlement = new SettlementRecord(UUID.randomUUID(), "Ground handoff", f.origin, UUID.randomUUID());
        var site = settlement.addBlueprint(BuildingType.HOUSE, f.origin.offset(9, 0, 8),
                f.origin.offset(9, 0, 8), Direction.NORTH, UUID.randomUUID(), false);
        var template = BlueprintTemplates.create(site);
        for (BlockPos p : BlockPos.betweenClosed(template.minCorner().offset(-3, 0, -3),
                new BlockPos(template.maxCorner().getX() + 3, template.minCorner().getY(), template.maxCorner().getZ() + 3))) {
            f.level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
        }
        for (var p : template.placements()) f.level.setBlock(p.position(), p.state(), 2);
        var lantern = template.placements().stream().filter(p -> p.state().is(Blocks.LANTERN)).findFirst().orElseThrow();
        f.level.setBlock(lantern.position(), Blocks.AIR.defaultBlockState(), 3);
        var doors = template.placements().stream().filter(p -> p.state().getBlock() instanceof net.minecraft.world.level.block.DoorBlock)
                .map(BlueprintPlacement::position).toList();
        helper.assertTrue(!doors.isEmpty(), "Fixture needs a door");
        BlockPos base = new BlockPos(template.maxCorner().getX() + 1, template.minCorner().getY() + 1,
                template.maxCorner().getZ() + 1);
        for (int y = 0; y <= 4; y++) {
            BlockPos p = base.above(y);
            f.level.setBlock(p, Blocks.SCAFFOLDING.defaultBlockState(), 3);
            site.addTemporaryFooting(p, net.minecraft.world.item.Items.SCAFFOLDING);
        }
        resident.moveTo(Vec3.atBottomCenterOf(base.above(5)));
        var movement = site.constructionController().movement(resident);
        var data = new SettlementSavedData();
        var worker = settlement.citizens().iterator().next();
        if (useWorkService) {
            for (BlockPos p : site.constructionController().frame(f.level, site)) {
                f.level.setBlock(p, Blocks.SCAFFOLDING.defaultBlockState(), 2);
                site.addTemporaryFooting(p, net.minecraft.world.item.Items.SCAFFOLDING);
            }
            for (var other : settlement.citizens()) if (other != worker) other.markDead(base);
            worker.assignProfession(CitizenProfession.WORKER, null);
            worker.updateProjection(resident.getUUID(), resident.blockPosition());
            resident.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG, worker.id().toString());
            resident.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG, settlement.id().toString());
            worker.insertIntoInventory(new ItemStack(lantern.material()));
            // The legacy frame can overlap completed cells depending on world
            // alignment. Supply their actual replacement material as well.
            java.util.Map<net.minecraft.world.item.Item, Integer> remaining = new java.util.HashMap<>();
            for (var missing : com.hkin.exiledsettlement.blueprint.BlueprintService.pendingPlacements(f.level, site, java.util.Set.of())) {
                var material = missing.placement().material();
                if (material != null && material != lantern.material()) remaining.merge(material, 1, Integer::sum);
            }
            remaining.forEach((material, count) -> helper.assertTrue(
                    worker.insertIntoInventory(new ItemStack(material, count)).isEmpty(),
                    "Ground handoff fixture could not carry its real repair materials"));
            worker.startTask(CitizenWorkState.TO_BLUEPRINT, lantern.position(), null, site.id());
        }
        boolean[] opened = {false}, grounded = {false};
        helper.runAtTickTime(1590, () -> {
            BlockPos feet = resident.navigationFeetPosition();
            LOGGER.error("[ground-handoff] position={} bounds={}..{} floor={} feet={} head={} standable={} work={}/{} trace={}",
                    resident.position(),template.minCorner(),template.maxCorner(),f.level.getBlockState(feet.below()),
                    f.level.getBlockState(feet),f.level.getBlockState(feet.above()),resident.canStandAtPosition(feet),
                    worker.workState(),worker.workFailure(),resident.constructionDecisionTrace());
            helper.fail("Ground handoff failed to place lantern: " + resident.position() + ":" + worker.workState() + ":" + worker.workFailure());
        });
        helper.onEachTick(() -> {
            for (BlockPos door : doors) {
                var state = f.level.getBlockState(door);
                if (state.hasProperty(net.minecraft.world.level.block.DoorBlock.OPEN)
                        && state.getValue(net.minecraft.world.level.block.DoorBlock.OPEN)) opened[0] = true;
            }
            if (resident.getY() <= base.getY() + 0.2) grounded[0] = true;
            if (useWorkService) {
                f.level.setDayTime(6000);
                CitizenWorkService.tickSettlement(f.level, data, settlement);
                CitizenWorkService.advanceSiteConstruction(f.level, data, settlement, worker, resident);
                if (!f.level.getBlockState(lantern.position()).is(Blocks.LANTERN)) return;
                helper.assertTrue(grounded[0], "Site worker did not descend before remote placement");
                f.finish(); helper.succeed(); return;
            }
            boolean ready = movement.approach(f.level, site, resident, lantern.position(), 0.65);
            movement.consumeRecoveryCompleted(); // Same target, as in the work-service handoff.
            if (!ready) return;
            helper.assertTrue(grounded[0] && opened[0], "Worker did not descend and open a door");
            var result = com.hkin.exiledsettlement.blueprint.BlueprintService.placeAt(f.level,
                    new SettlementSavedData(), settlement, site, lantern.position(),
                    new ItemStack(lantern.material()), resident);
            helper.assertTrue(result.success(), "Lantern placement failed: " + result.reason());
            helper.assertTrue(f.level.getBlockState(lantern.position()).is(Blocks.LANTERN), "Lantern not placed");
            f.finish(); helper.succeed();
        });
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "logistics_scaffold_exit", timeoutTicks = 600)
    public static void supplyExitFromFractionalScaffoldBase(GameTestHelper helper) {
        logisticsExit(helper, 41, false);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "logistics_scaffold_exit", timeoutTicks = 600)
    public static void supplyExitFromScaffoldTop(GameTestHelper helper) {
        logisticsExit(helper, 42, true);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "logistics_supply_handoff", timeoutTicks = 1200)
    public static void scaffoldExitActuallyReceivesConstructionSupply(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 43, 1200, 1);
        var resident = f.residents.getFirst();
        BlockPos core = f.origin.offset(20, 0, 5);
        var settlement = new SettlementRecord(UUID.randomUUID(), "Supply handoff", core, UUID.randomUUID());
        settlement.setCreativeCoreInstalled(true);
        f.level.setBlock(core, com.hkin.exiledsettlement.registry.ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(), 3);
        var site = settlement.addBlueprint(BuildingType.HOUSE, f.origin.offset(10, 0, 10),
                f.origin.offset(10, 0, 10), Direction.NORTH, UUID.randomUUID(), false);
        var template = BlueprintTemplates.create(site);
        BlockPos base = f.origin.offset(10, 0, 5);
        for (int y = 0; y < 5; y++) {
            f.level.setBlock(base.above(y), Blocks.SCAFFOLDING.defaultBlockState(), 3);
            site.addTemporaryFooting(base.above(y), net.minecraft.world.item.Items.SCAFFOLDING);
        }
        var worker = settlement.citizens().iterator().next();
        for (var other : settlement.citizens()) if (other != worker) other.markDead(base);
        worker.assignProfession(CitizenProfession.WORKER, null);
        worker.updateProjection(resident.getUUID(), base);
        resident.getPersistentData().putString(CitizenProjectionService.CITIZEN_ID_TAG, worker.id().toString());
        resident.getPersistentData().putString(CitizenProjectionService.SETTLEMENT_ID_TAG, settlement.id().toString());
        resident.moveTo(base.getX() + .5, base.getY() + .53, base.getZ() + .98);
        var pending = template.placements().stream().filter(p -> p.material() != null).findFirst().orElseThrow();
        worker.startTask(CitizenWorkState.TO_CONSTRUCTION_SUPPLY, pending.position(), null, site.id());
        var data = new SettlementSavedData();
        helper.runAtTickTime(1100, () -> helper.fail("Supply handoff stuck: " + worker.workState()
                + ":" + worker.workFailure() + ":" + resident.position() + ":" + resident.constructionDecisionTrace()));
        helper.onEachTick(() -> {
            CitizenWorkService.tickSettlement(f.level, data, settlement);
            if (worker.findInventorySlot(stack -> stack.is(pending.material())) < 0) return;
            helper.assertTrue(worker.workState() == CitizenWorkState.TO_BLUEPRINT, "Did not hand supply back to construction");
            helper.assertTrue(resident.getY() <= base.getY() + .1, "Received supplies while suspended");
            f.finish(); helper.succeed();
        });
    }

    private static void logisticsExit(GameTestHelper helper, int scenario, boolean elevated) {
        Fixture f = new Fixture(helper, scenario, 600, 1);
        var resident = f.residents.getFirst();
        var settlement = new SettlementRecord(UUID.randomUUID(), "Supply exit", f.origin, UUID.randomUUID());
        var site = settlement.addBlueprint(BuildingType.HOUSE, f.origin.below(), f.origin.below(),
                Direction.NORTH, UUID.randomUUID(), false);
        BlockPos base = new BlockPos(f.origin.getX() + 10, BlueprintTemplates.create(site).minCorner().getY() + 1, f.origin.getZ() + 5);
        for (BlockPos p : BlockPos.betweenClosed(base.offset(-3, -1, -3), base.offset(3, 6, 3))) {
            f.level.setBlock(p, p.getY() < base.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
        }
        for (int y = 0; y < 5; y++) {
            BlockPos p = base.above(y);
            f.level.setBlock(p, Blocks.SCAFFOLDING.defaultBlockState(), 3);
            site.addTemporaryFooting(p, net.minecraft.world.item.Items.SCAFFOLDING);
        }
        resident.moveTo(base.getX() + 0.5, base.getY() + (elevated ? 5 : 0.53), base.getZ() + 0.98);
        var exit = new com.hkin.exiledsettlement.citizen.ScaffoldLogisticsExit(site.id());
        helper.runAtTickTime(550, () -> helper.fail("Exit stuck: " + resident.position() + ":" + resident.constructionDecisionTrace()));
        helper.onEachTick(() -> {
            if (!exit.advance(f.level, site, resident, 0.65)) return;
            helper.assertTrue(resident.getY() <= base.getY() + 0.1, "Exit did not reach ground");
            helper.assertTrue(!f.level.getBlockState(resident.blockPosition()).is(Blocks.SCAFFOLDING), "Still inside scaffold");
            f.finish(); helper.succeed();
        });
    }

    private static final class Fixture {
        final ServerLevel level;
        final BlockPos origin;
        final UUID task = UUID.randomUUID();
        final List<SettlementCitizenEntity> residents = new ArrayList<>();
        final List<ChunkPos> chunks = new ArrayList<>();
        boolean finished;

        Fixture(GameTestHelper helper, int scenario, int timeout, int count) {
            level = helper.getLevel();
            origin = helper.absolutePos(new BlockPos(4, 10, 4)).offset(32768 + scenario * 192, 0, 0);
            // Entity sections need their ticking neighborhood, including stations
            // at the edge of the remote fixture, before the real work assertion.
            ChunkPos minimum = new ChunkPos(origin.offset(-18, 0, -18));
            ChunkPos maximum = new ChunkPos(origin.offset(43, 0, 32));
            for (int x = minimum.x; x <= maximum.x; x++) {
                for (int z = minimum.z; z <= maximum.z; z++) {
                    chunks.add(new ChunkPos(x, z));
                    level.setChunkForced(x, z, true);
                }
            }
            for (BlockPos cell : BlockPos.betweenClosed(origin.offset(-1, -1, -1), origin.offset(26, 5, 15))) {
                level.setBlock(cell, cell.getY() < origin.getY()
                        ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
            }
            BlockPos start = origin.offset(2, 0, 5);
            for (BlockPos cell : BlockPos.betweenClosed(start.offset(-1, 0, -1), start.offset(1, 3, 1))) {
                if (cell.getX() != start.getX() || cell.getZ() != start.getZ()) {
                    level.setBlock(cell, Blocks.STONE_BRICKS.defaultBlockState(), 2);
                }
            }
            for (int index = 0; index < count; index++) {
                SettlementCitizenEntity resident = ModEntities.SETTLEMENT_CITIZEN.get().create(level);
                helper.assertTrue(resident != null, "Could not create planning resident");
                resident.moveTo(start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D, 0.0F, 0.0F);
                resident.setOnGround(true);
                helper.assertTrue(level.addFreshEntity(resident), "Could not spawn planning resident");
                residents.add(resident);
            }
            helper.runAtTickTime(timeout - 1L, this::finish);
        }

        List<BlockPos> candidates() {
            List<BlockPos> candidates = new ArrayList<>();
            for (int x = 12; x < 20; x++) {
                for (int z = 2; z < 8; z++) candidates.add(origin.offset(x, 0, z));
            }
            return candidates;
        }

        void openDoor() {
            level.setBlock(origin.offset(3, 0, 5), Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(origin.offset(3, 1, 5), Blocks.AIR.defaultBlockState(), 2);
        }

        void finish() {
            if (finished) return;
            finished = true;
            for (SettlementCitizenEntity resident : residents) resident.discard();
            for (ChunkPos chunk : chunks) level.setChunkForced(chunk.x, chunk.z, false);
        }
    }
}
