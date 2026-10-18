package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.blueprint.BlueprintRecord;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingStatus;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.CitizenProfession;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import com.hkin.exiledsettlement.citizen.CitizenWorkState;
import com.hkin.exiledsettlement.citizen.ProfessionAssignmentService;
import com.hkin.exiledsettlement.citizen.WorkTaskService;
import com.hkin.exiledsettlement.region.ProductionRegion;
import com.hkin.exiledsettlement.road.AutoRoadState;
import com.hkin.exiledsettlement.road.PlayerBridgePlan;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import com.hkin.exiledsettlement.terrain.TerrainProject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Detached records only: equivalence and local allocation timing, not entity/TPS acceptance. */
@net.neoforged.fml.common.EventBusSubscriber(modid = ExiledSettlementMod.MOD_ID,
        bus = net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_efficiency")
@PrefixGameTestTemplate(false)
public final class WorkEfficiencyGameTests {
    private WorkEfficiencyGameTests() { }
    private static volatile int benchmarkSink;

    @net.neoforged.bus.api.SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if ("efficiency".equals(System.getProperty("exiledsettlement.testSuite")))
            event.register(WorkEfficiencyGameTests.class);
    }

    @GameTest(templateNamespace = "exiledsettlement_efficiency", template = "empty")
    public static void ownershipCountsMatchCompleteTasksAcrossAllWorkStates(GameTestHelper helper) throws Exception {
        var settlement = settlement();
        var at = settlement.corePosition();
        var site = settlement.addBlueprint(BuildingType.HOUSE, at, at, Direction.NORTH,
                settlement.ownerId(), false, Map.of(at.above(), Blocks.STONE.defaultBlockState()));
        for (var state : CitizenWorkState.values()) {
            var citizen = settlement.addMigrant();
            if (state != CitizenWorkState.IDLE) citizen.startTask(state, at.above(), null, site.id());
        }
        // Also cover every non-construction fallback, including generated profession/child UUIDs.
        for (var state : CitizenWorkState.values()) {
            var citizen = settlement.addMigrant();
            if (state != CitizenWorkState.IDLE) citizen.startTask(state, at.east(40), null, null);
        }
        var resuming = settlement.addMigrant();
        resuming.startEquipmentFetch(UUID.randomUUID(), at.east(30));
        resuming.setConstructionResume(site.id(), at.above());
        resuming.setMotherTask(site.id());
        var invalidReservation = settlement.addMigrant();
        invalidReservation.startTask(CitizenWorkState.TO_BLUEPRINT, at, null, site.id());
        invalidReservation.setMotherTask(UUID.randomUUID());

        var child = settlement.addMigrant();
        child.life().ageDays = 0;
        child.life().adultReached = false;
        child.startTask(CitizenWorkState.TO_BLUEPRINT, at, null, site.id());
        helper.assertTrue(!child.canWork(), "Child fixture unexpectedly became an adult");
        var dead = settlement.addMigrant();
        dead.markDead(at);
        dead.startTask(CitizenWorkState.TO_BLUEPRINT, at, null, site.id());
        assertEquivalent(helper, settlement);
        helper.assertTrue(WorkTaskService.counts(settlement).get(site.id())
                        == settlement.citizens().stream().filter(CitizenRecord::alive)
                        .filter(c -> WorkTaskService.task(settlement, c) != null)
                        .filter(c -> site.id().equals(WorkTaskService.task(settlement, c).mother())).count(),
                "Counts changed alive-only semantics for idle, dead or child records");
        helper.succeed();
    }

    @GameTest(templateNamespace = "exiledsettlement_efficiency", template = "empty")
    public static void ownershipCountsPreserveDependenciesRoadsBridgesAndProductionRegions(GameTestHelper helper) throws Exception {
        var settlement = settlement();
        var at = settlement.corePosition();
        var parent = blueprint(settlement, at);
        var foundationAt = BlueprintTemplates.create(parent).minCorner().atY(at.getY() - 1);
        var childSite = blueprint(settlement, at.east(30));
        var foundation = terrain(childSite, foundationAt);
        foundation.dependencies.add(parent.id());
        var builder = settlement.addMigrant();
        builder.startTask(CitizenWorkState.TO_FOOTING_SUPPLY, foundationAt, null, childSite.id());
        builder.setMotherTask(parent.id());
        helper.assertTrue(parent.id().equals(WorkTaskService.task(settlement, builder).mother()),
                "Foundation fixture is not owned by its actual parent");
        assertEquivalent(helper, settlement);
        foundation.areaClearance = true;
        assertEquivalent(helper, settlement);
        helper.assertTrue(childSite.id().equals(WorkTaskService.task(settlement, builder).mother()),
                "Public clearance incorrectly became a parent building seat");
        foundation.areaClearance = false;

        var route = new AutoRoadState.Route(UUID.randomUUID(), at.east(60), List.of(at.east(60), at.east(61)));
        settlement.autoRoads().routes.put("work-efficiency", route);
        var road = settlement.addMigrant();
        road.startTask(CitizenWorkState.TO_ROAD_TOOL, at.east(80), null, null);
        road.setRoadTaskId(route.id);
        var legacyRoad = settlement.addMigrant();
        legacyRoad.startTask(CitizenWorkState.TO_ROAD, at.east(60), null, null);
        helper.assertTrue(route.id.equals(WorkTaskService.task(settlement, road).mother())
                        && route.id.equals(WorkTaskService.task(settlement, legacyRoad).mother()),
                "Explicit and legacy road fixtures did not resolve to the route");
        assertEquivalent(helper, settlement);
        route.suppressed = true;
        assertEquivalent(helper, settlement);
        route.suppressed = false;

        var bridgeSite = blueprint(settlement, at.south(30));
        var bridge = terrain(bridgeSite, foundationAt);
        bridge.playerBridge = new PlayerBridgePlan(at.south(30), at.offset(8, 0, 34), true, at.getY(), true);
        bridge.bridge = true;
        bridge.dependencies.add(parent.id());
        var bridgeWorker = settlement.addMigrant();
        bridgeWorker.startTask(CitizenWorkState.TO_BLUEPRINT, foundationAt, null, bridgeSite.id());
        bridgeWorker.setMotherTask(parent.id());
        assertEquivalent(helper, settlement);
        // Even an incomplete legacy marker must obey task()'s bridge-parent rule.
        var secondParent = blueprint(settlement, at.north(30));
        secondParent.withRegion(new ProductionRegion(BuildingType.FARM_FIELD,
                foundationAt.below(), foundationAt.above(), Direction.NORTH));
        secondParent.region().cells.put(ProductionRegion.key(foundationAt.getX(), foundationAt.getZ()),
                new ProductionRegion.Cell(foundationAt, ProductionRegion.Use.CROP,
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace("wheat"), BuildingType.FARM_FIELD));
        bridge.dependencies.add(secondParent.id());
        bridge.bridge = false;
        UUID higherParent = parent.id().compareTo(secondParent.id()) > 0 ? parent.id() : secondParent.id();
        bridgeWorker.setMotherTask(higherParent);
        assertEquivalent(helper, settlement);

        var forestSite = blueprint(settlement, at.west(30));
        var region = new ProductionRegion(BuildingType.TREE_FARM, at.west(30), at.offset(-24, 0, 6), Direction.NORTH);
        region.workingBuilding = UUID.randomUUID();
        forestSite.withRegion(region);
        var forester = settlement.addMigrant();
        forester.startForestry(region.workingBuilding, at.west(28));
        var capture = settlement.addMigrant();
        capture.startCapture(region.workingBuilding, at.west(28), UUID.randomUUID());
        helper.assertTrue(forestSite.id().equals(WorkTaskService.task(settlement, forester).mother())
                        && forestSite.id().equals(WorkTaskService.task(settlement, capture).mother()),
                "Production-region fixture does not resolve to its active blueprint");
        assertEquivalent(helper, settlement);
        forestSite.queueCancellation();
        assertEquivalent(helper, settlement);
        helper.succeed();
    }

    @GameTest(templateNamespace = "exiledsettlement_efficiency", template = "empty")
    public static void ownershipCountsSeeSameTickReassignmentWithoutCachingOrCargoChanges(GameTestHelper helper) throws Exception {
        var settlement = settlement();
        var first = blueprint(settlement, settlement.corePosition());
        var second = blueprint(settlement, settlement.corePosition().east(30));
        var citizen = settlement.addMigrant();
        citizen.insertIntoInventory(new ItemStack(Items.OAK_PLANKS, 37));
        citizen.insertIntoInventory(new ItemStack(Items.IRON_AXE));
        var inventoryBefore = citizen.inventory().stream().map(ItemStack::copy).toList();
        citizen.startTask(CitizenWorkState.TO_BLUEPRINT, first.anchor(), null, first.id());
        assertEquivalent(helper, settlement);
        helper.assertTrue(WorkTaskService.counts(settlement).equals(Map.of(first.id(), 1)), "Initial seat missing");
        citizen.clearWork();
        helper.assertTrue(WorkTaskService.counts(settlement).isEmpty(), "Released seat remained cached");
        citizen.startTask(CitizenWorkState.TO_BLUEPRINT, second.anchor(), null, second.id());
        citizen.setMotherTask(first.id());
        assertEquivalent(helper, settlement);
        helper.assertTrue(WorkTaskService.counts(settlement).equals(Map.of(second.id(), 1)), "Same-tick transfer kept the former owner");
        for (int i = 0; i < inventoryBefore.size(); i++) {
            helper.assertTrue(ItemStack.matches(inventoryBefore.get(i), citizen.inventory().get(i)),
                    "Counting or reassignment changed real cargo/tool components");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "exiledsettlement_efficiency", template = "empty")
    public static void fullProfessionSlotsKeepLocksExistingWorkersAndFundedChef(GameTestHelper helper) {
        var settlement = settlement();
        var residents = List.copyOf(settlement.citizens());
        var farm = workplace(settlement, BuildingType.FARM_FIELD, settlement.corePosition().east(30), 2, 2);
        var locked = residents.get(0);
        var automatic = residents.get(1);
        locked.setManualAssignment(CitizenProfession.FARMER, farm.id());
        automatic.assignProfession(CitizenProfession.FARMER, farm.id());
        var kitchen = workplace(settlement, BuildingType.RESTAURANT, settlement.corePosition().west(30), 1, 1);
        var chef = residents.get(2);
        chef.assignProfession(CitizenProfession.CHEF, kitchen.id());
        chef.life().task = "cook:prepare";
        chef.life().destination = kitchen.id();
        chef.life().cookingStage = 2;
        chef.life().workTicks = 160;
        chef.insertIntoInventory(new ItemStack(Items.WHEAT, 3));
        chef.insertIntoInventory(new ItemStack(Items.CHARCOAL));
        kitchen.updateFunction(false, BuildingStatus.MISSING_CONTAINER, 0, List.of());
        for (int i = 0; i < 3; i++) ProfessionAssignmentService.refresh(new SettlementSavedData(), settlement);
        helper.assertTrue(locked.hasManualAssignment() && locked.profession() == CitizenProfession.FARMER
                        && automatic.profession() == CitizenProfession.FARMER
                        && farm.id().equals(automatic.workBuildingId()) && settlement.professionActual(CitizenProfession.FARMER) == 2,
                "Full seats lost a lock, replaced an existing worker, or exceeded the target");
        helper.assertTrue(chef.profession() == CitizenProfession.CHEF && kitchen.id().equals(chef.workBuildingId())
                        && chef.life().workTicks == 160 && chef.life().cookingStage == 2
                        && chef.hasInventoryItem(stack -> stack.is(Items.WHEAT) && stack.getCount() == 3)
                        && chef.hasInventoryItem(stack -> stack.is(Items.CHARCOAL)),
                "No-vacancy refresh released a funded chef or altered cargo/progress");
        farm.setWorkerTarget(1, farm.configurationRevision());
        ProfessionAssignmentService.refresh(new SettlementSavedData(), settlement);
        helper.assertTrue(locked.profession() == CitizenProfession.FARMER && locked.hasManualAssignment()
                        && automatic.profession() == CitizenProfession.WORKER
                        && settlement.professionActual(CitizenProfession.FARMER) == 1,
                "Target reduction no longer removes only the surplus automatic assignment");
        farm.setWorkerTarget(0, farm.configurationRevision());
        ProfessionAssignmentService.refresh(new SettlementSavedData(), settlement);
        helper.assertTrue(locked.profession() == CitizenProfession.FARMER && locked.hasManualAssignment(),
                "A zero automatic target erased an explicit profession lock");
        helper.succeed();
    }

    @GameTest(templateNamespace = "exiledsettlement_efficiency", template = "empty")
    public static void fullRegularFarmSlotsStillAllowSeasonalHelpers(GameTestHelper helper) {
        var settlement = settlement();
        var farm = workplace(settlement, BuildingType.FARM_FIELD, settlement.corePosition().east(30), 4, 1);
        var farmer = settlement.citizens().iterator().next();
        farmer.assignProfession(CitizenProfession.FARMER, farm.id());
        settlement.community().policy = "harvest";
        settlement.community().lastDay = 0;
        ProfessionAssignmentService.refresh(new SettlementSavedData(), settlement);
        helper.assertTrue(settlement.professionActual(CitizenProfession.FARMER) == 3
                        && farmer.profession() == CitizenProfession.FARMER && farm.id().equals(farmer.workBuildingId()),
                "No-vacancy shortcut skipped the separate seasonal-helper pass");
        helper.succeed();
    }

    @GameTest(templateNamespace = "exiledsettlement_efficiency", template = "empty", timeoutTicks = 200)
    public static void ownershipCountBatchReportsTimeAndAllocationAgainstOriginal(GameTestHelper helper) throws Exception {
        var settlement = settlement();
        var site = blueprint(settlement, settlement.corePosition());
        while (settlement.citizens().size() < 192) settlement.addMigrant();
        int index = 0;
        for (var citizen : settlement.citizens()) {
            if (index++ % 3 == 0) citizen.startTask(CitizenWorkState.TO_BLUEPRINT, site.anchor(), null, site.id());
            else citizen.startTask(CitizenWorkState.TO_FISH, site.anchor(), null, null);
        }
        assertEquivalent(helper, settlement);
        for (int i = 0; i < 80; i++) {
            consume(referenceCounts(settlement));
            consume(WorkTaskService.counts(settlement));
        }
        var bean = ManagementFactory.getThreadMXBean();
        var allocation = bean instanceof com.sun.management.ThreadMXBean extended
                && extended.isThreadAllocatedMemorySupported() && extended.isThreadAllocatedMemoryEnabled() ? extended : null;
        long[] referenceNs = new long[5], optimizedNs = new long[5];
        long[] referenceBytes = new long[5], optimizedBytes = new long[5];
        int repetitions = 64;
        for (int sample = 0; sample < 5; sample++) {
            for (int pass = 0; pass < 2; pass++) {
                boolean optimized = ((sample + pass) & 1) == 1;
                long bytesBefore = allocated(allocation);
                long started = System.nanoTime();
                for (int i = 0; i < repetitions; i++) consume(optimized ? WorkTaskService.counts(settlement) : referenceCounts(settlement));
                long elapsed = System.nanoTime() - started;
                long bytesAfter = allocated(allocation);
                (optimized ? optimizedNs : referenceNs)[sample] = elapsed / repetitions;
                (optimized ? optimizedBytes : referenceBytes)[sample] = bytesBefore < 0 ? -1 : (bytesAfter - bytesBefore) / repetitions;
            }
        }
        ExiledSettlementMod.LOGGER.info("WORK_EFFICIENCY_COUNTS residents={} samples=5 callsPerSample={} referenceNsPerCall={} optimizedNsPerCall={} referenceBytesPerCall={} optimizedBytesPerCall={} worldEdits=0 scope=detached_record_microbenchmark",
                settlement.citizens().size(), repetitions, median(referenceNs), median(optimizedNs), median(referenceBytes), median(optimizedBytes));
        helper.assertTrue(referenceCounts(settlement).equals(WorkTaskService.counts(settlement)), "Batch changed complete ownership mapping");
        if (allocation != null) helper.assertTrue(median(optimizedBytes) < median(referenceBytes),
                "Ownership-only counting did not reduce allocation versus complete task construction");
        helper.succeed();
    }

    private static SettlementRecord settlement() {
        return new SettlementRecord(UUID.randomUUID(), "Detached work efficiency", new BlockPos(0, 64, 0), UUID.randomUUID());
    }

    private static BlueprintRecord blueprint(SettlementRecord settlement, BlockPos at) {
        return settlement.addBlueprint(BuildingType.HOUSE, at, Direction.NORTH, settlement.ownerId(), false);
    }

    private static TerrainProject terrain(BlueprintRecord site, BlockPos target) {
        var project = new TerrainProject(site.id(), "Detached ownership fixture", target,
                List.of(new TerrainProject.Step(target, Blocks.DIRT.defaultBlockState(), Blocks.AIR.defaultBlockState())));
        site.withTerrainProject(project);
        return project;
    }

    private static BuildingRecord workplace(SettlementRecord settlement, BuildingType type, BlockPos at, int capacity, int target) {
        var building = settlement.addBuilding(type, at);
        building.updateFunction(true, BuildingStatus.OPERATIONAL, capacity, List.of(at));
        building.setWorkerTarget(target, building.configurationRevision());
        return building;
    }

    /** Frozen pre-optimization algorithm, intentionally retaining full task construction. */
    private static Map<UUID, Integer> referenceCounts(SettlementRecord settlement) {
        Map<UUID, Integer> counts = new HashMap<>();
        for (var citizen : settlement.citizens()) if (citizen.alive()) {
            var task = WorkTaskService.task(settlement, citizen);
            if (task != null) counts.merge(task.mother(), 1, Integer::sum);
        }
        return counts;
    }

    private static void assertEquivalent(GameTestHelper helper, SettlementRecord settlement) throws Exception {
        Method ownership = WorkTaskService.class.getDeclaredMethod("assignedMother", SettlementRecord.class, CitizenRecord.class);
        ownership.setAccessible(true);
        for (var citizen : settlement.citizens()) {
            var task = WorkTaskService.task(settlement, citizen);
            helper.assertTrue(Objects.equals(task == null ? null : task.mother(), ownership.invoke(null, settlement, citizen)),
                    "Ownership shortcut differs from task() for " + citizen.workState() + " / " + citizen.id());
        }
        helper.assertTrue(referenceCounts(settlement).equals(WorkTaskService.counts(settlement)),
                "Complete counts map differs from the pre-optimization implementation");
    }

    private static void consume(Map<UUID, Integer> counts) { benchmarkSink = counts.hashCode(); }
    private static long allocated(com.sun.management.ThreadMXBean bean) {
        return bean == null ? -1 : bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }
    private static long median(long[] values) { var sorted = values.clone(); Arrays.sort(sorted); return sorted[sorted.length / 2]; }
}
