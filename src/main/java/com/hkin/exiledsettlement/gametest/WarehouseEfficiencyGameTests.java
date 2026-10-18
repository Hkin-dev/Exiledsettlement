package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.blueprint.BlueprintPlacement;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.blueprint.BuildingBlueprintTemplate;
import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingStatus;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.building.WarehouseInventoryService;
import com.hkin.exiledsettlement.building.WarehouseStock;
import com.hkin.exiledsettlement.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.FenceGateBlock;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** In-memory algorithm comparisons in a new test world; no player-world access. */
@net.neoforged.fml.common.EventBusSubscriber(modid = ExiledSettlementMod.MOD_ID,
        bus = net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_efficiency")
@PrefixGameTestTemplate(false)
public final class WarehouseEfficiencyGameTests {
    private static volatile long benchmarkSink;
    private static final int WARMUP_ROUNDS = 12;
    private static final int MEASURED_ROUNDS = 10;
    private static final int QUERIES_PER_ROUND = 256;

    @net.neoforged.bus.api.SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if ("efficiency".equals(System.getProperty("exiledsettlement.testSuite")))
            event.register(WarehouseEfficiencyGameTests.class);
    }

    @GameTest(templateNamespace = "exiledsettlement_efficiency", template = "empty", batch = "warehouse_efficiency")
    public static void creativeSupplyCacheTracksRealStockAndReload(GameTestHelper helper) {
        var level = helper.getLevel();
        var warehouse = warehouse(helper);
        var stock = WarehouseInventoryService.stock(level, warehouse);
        helper.assertTrue(!WarehouseInventoryService.hasCreativeSupply(level, warehouse), "Empty warehouse has creative supply");
        var namedDirt = new ItemStack(Items.DIRT, 64);
        namedDirt.set(DataComponents.CUSTOM_NAME, Component.literal("named material"));
        var namedCore = new ItemStack(ModItems.CREATIVE_SUPPLY_CORE.get());
        namedCore.set(DataComponents.CUSTOM_NAME, Component.literal("named core"));
        stock.insert(namedDirt);
        stock.insert(new ItemStack(Items.DIRT, 32));
        stock.insert(namedCore);
        helper.assertTrue(WarehouseInventoryService.hasCreativeSupply(level, warehouse), "Core insertion left a stale negative summary");
        helper.assertTrue(WarehouseInventoryService.countItem(level, warehouse, Items.DIRT) == 96,
                "Summary did not aggregate component variants by item");
        var saved = warehouse.save(level.registryAccess());
        var removed = WarehouseInventoryService.extractItem(level, warehouse, ModItems.CREATIVE_SUPPLY_CORE.get(), 1);
        helper.assertTrue(ItemStack.isSameItemSameComponents(removed, namedCore)
                        && !WarehouseInventoryService.hasCreativeSupply(level, warehouse),
                "Core removal lost components or left a stale positive summary");
        stock.insert(namedCore);
        helper.assertTrue(WarehouseInventoryService.hasCreativeSupply(level, warehouse), "Core reinsertion was not detected");
        stock.clearContent();
        helper.assertTrue(!WarehouseInventoryService.hasCreativeSupply(level, warehouse), "Clearing stock left creative supply enabled");

        var loaded = BuildingRecord.load(saved);
        helper.assertTrue(WarehouseInventoryService.hasCreativeSupply(level, loaded), "Saved core was missed on first bind");
        var loadedNamed = WarehouseInventoryService.extractItem(level, loaded, Items.DIRT, 64);
        helper.assertTrue(loadedNamed.getCount() == 64 && ItemStack.isSameItemSameComponents(loadedNamed, namedDirt),
                "Save/reload or summarized lookup changed named material components/order");
        helper.assertTrue(WarehouseInventoryService.countItem(level, loaded, Items.DIRT) == 32,
                "Extraction failed to invalidate item counts");
        WarehouseInventoryService.extractItem(level, loaded, ModItems.CREATIVE_SUPPLY_CORE.get(), 1);
        helper.assertTrue(!WarehouseInventoryService.hasCreativeSupply(level, loaded), "Reloaded core removal left stale supply");
        var noCoreReload = BuildingRecord.load(loaded.save(level.registryAccess()));
        helper.assertTrue(!WarehouseInventoryService.hasCreativeSupply(level, noCoreReload), "Reload invented a removed core");

        stock.insert(namedCore);
        warehouse.updateFunction(false, BuildingStatus.PENDING_VALIDATION, 0, List.of());
        helper.assertTrue(!WarehouseInventoryService.hasCreativeSupply(level, warehouse), "Non-operational warehouse bypassed eligibility");
        warehouse.updateFunction(true, BuildingStatus.OPERATIONAL, WarehouseStock.CAPACITY, List.of());
        helper.assertTrue(WarehouseInventoryService.hasCreativeSupply(level, warehouse), "Operational warehouse lost its unchanged real core");
        helper.succeed();
    }

    @GameTest(templateNamespace = "exiledsettlement_efficiency", template = "empty", batch = "warehouse_efficiency", timeoutTicks = 200)
    public static void fullWarehouseWithoutCoreReusesSummary(GameTestHelper helper) {
        var warehouse = warehouse(helper);
        var stock = WarehouseInventoryService.stock(helper.getLevel(), warehouse);
        int stacks = WarehouseStock.CAPACITY / 64;
        for (int slot = 0; slot < stacks; slot++) stock.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        long coldStart = System.nanoTime();
        helper.assertTrue(!WarehouseInventoryService.hasCreativeSupply(helper.getLevel(), warehouse), "Full ordinary stock became creative supply");
        long coldNanos = System.nanoTime() - coldStart;
        long revision = stock.revision();
        var snapshot = stock.summary();
        var timings = compare(
                () -> scanForCore(stock) ? 1 : 0,
                () -> stock.summary().getOrDefault(ModItems.CREATIVE_SUPPLY_CORE.get(), 0) > 0 ? 1 : 0,
                QUERIES_PER_ROUND);
        helper.assertTrue(stock.quantity() == WarehouseStock.CAPACITY && stock.revision() == revision
                        && stock.summary() == snapshot, "Read-only queries changed inventory or rebuilt the summary");
        helper.assertTrue(benchmarkSink == 0, "Core query algorithms disagree for an ordinary warehouse");
        System.out.println("WAREHOUSE_EFFICIENCY stacks=" + stacks + " queriesPerAlgorithm=" + (MEASURED_ROUNDS * QUERIES_PER_ROUND)
                + " warmupRounds=" + WARMUP_ROUNDS + " alternatingOrder=true coldSummaryNanos=" + coldNanos
                + " legacyScanNanos=" + timings[0] + " summaryLookupNanos=" + timings[1]
                + " legacyStackVisits=" + ((long) stacks * MEASURED_ROUNDS * QUERIES_PER_ROUND)
                + " cachedStackVisits=0 quantity=" + stock.quantity());
        helper.succeed();
    }

    @GameTest(templateNamespace = "exiledsettlement_efficiency", template = "empty", batch = "warehouse_efficiency", timeoutTicks = 200)
    public static void gateIndexPreservesExactTemplateOrder(GameTestHelper helper) {
        List<BuildingBlueprintTemplate> templates = new ArrayList<>();
        var types = List.of(BuildingType.FARM_FIELD, BuildingType.MELON_FIELD, BuildingType.TREE_FARM, BuildingType.PASTURE_AREA);
        for (var type : types) for (var facing : Direction.Plane.HORIZONTAL) {
            var template = BlueprintTemplates.createArea(type, new BlockPos(0, 70, 0), new BlockPos(31, 70, 31), facing);
            var expected = template.placements().stream()
                    .filter(p -> p.state().getBlock() instanceof FenceGateBlock).toList();
            helper.assertTrue(!expected.isEmpty() && expected.equals(template.gatePlacements()),
                    "Gate subset/order changed for " + type + " facing " + facing);
            boolean immutable = false;
            try { template.gatePlacements().clear(); } catch (UnsupportedOperationException expectedFailure) { immutable = true; }
            helper.assertTrue(immutable, "Gate index can be mutated");
            templates.add(template);
        }
        long allPlacements = templates.stream().mapToLong(t -> t.placements().size()).sum();
        long gates = templates.stream().mapToLong(t -> t.gatePlacements().size()).sum();
        long oldFingerprint = gateFingerprint(templates, false), indexedFingerprint = gateFingerprint(templates, true);
        helper.assertTrue(oldFingerprint == indexedFingerprint && gates < allPlacements, "Index changed gate traversal or did not reduce scanning");
        int gateQueriesPerRound = 32;
        var timings = compare(() -> gateFingerprint(templates, false), () -> gateFingerprint(templates, true), gateQueriesPerRound);
        System.out.println("GATE_INDEX_EFFICIENCY templates=" + templates.size() + " oldVisitsPerPass=" + allPlacements
                + " indexedVisitsPerPass=" + gates + " warmupRounds=" + WARMUP_ROUNDS + " alternatingOrder=true"
                + " queriesPerAlgorithm=" + (MEASURED_ROUNDS * gateQueriesPerRound)
                + " legacyScanNanos=" + timings[0] + " indexedScanNanos=" + timings[1]
                + " orderedFingerprint=" + oldFingerprint);
        helper.succeed();
    }

    private static BuildingRecord warehouse(GameTestHelper helper) {
        var warehouse = BuildingRecord.create(BuildingType.WAREHOUSE, helper.absolutePos(new BlockPos(1, 1, 1)));
        warehouse.updateFunction(true, BuildingStatus.OPERATIONAL, WarehouseStock.CAPACITY, List.of());
        return warehouse;
    }

    private static boolean scanForCore(WarehouseStock stock) {
        for (int slot = 0; slot < stock.getContainerSize(); slot++)
            if (stock.getItem(slot).is(ModItems.CREATIVE_SUPPLY_CORE.get())) return true;
        return false;
    }

    private static long gateFingerprint(List<BuildingBlueprintTemplate> templates, boolean indexed) {
        long result = 1;
        for (var template : templates) for (BlueprintPlacement p : indexed ? template.gatePlacements() : template.placements()) {
            if (!indexed && !(p.state().getBlock() instanceof FenceGateBlock)) continue;
            result = 31 * result + p.position().asLong();
        }
        return result;
    }

    /** Warm both algorithms, alternate execution order, report observations without timing assertions. */
    private static long[] compare(LongSupplier legacy, LongSupplier indexed, int queriesPerRound) {
        long[] elapsed = new long[2];
        for (int round = -WARMUP_ROUNDS; round < MEASURED_ROUNDS; round++) {
            int first = Math.floorMod(round, 2);
            for (int turn = 0; turn < 2; turn++) {
                int algorithm = (first + turn) % 2;
                LongSupplier query = algorithm == 0 ? legacy : indexed;
                long checksum = 0, start = System.nanoTime();
                for (int i = 0; i < queriesPerRound; i++) checksum += query.getAsLong();
                long nanos = System.nanoTime() - start;
                benchmarkSink = checksum;
                if (round >= 0) elapsed[algorithm] += nanos;
            }
        }
        return elapsed;
    }
}
