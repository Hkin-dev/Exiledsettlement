package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.citizen.CitizenWorkService;
import com.hkin.exiledsettlement.blueprint.BlueprintBlockStateResolver;
import com.hkin.exiledsettlement.blueprint.BlueprintPlacementMode;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.blueprint.BuildingBlueprintTemplate;
import com.hkin.exiledsettlement.building.BuildingFunctionService;
import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.building.WarehouseInventoryService;
import com.hkin.exiledsettlement.citizen.CitizenNavigationReservationService;
import com.hkin.exiledsettlement.citizen.CitizenProfession;
import com.hkin.exiledsettlement.citizen.CitizenProjectionService;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import com.hkin.exiledsettlement.citizen.CitizenWorkState;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.registry.ModBlocks;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Live profession smoke gates, not throughput or whole-day acceptance.
 * Each gate runs at most 6000 ordinary server ticks (five minutes at 20 TPS).
 * Completed, versioned production templates and finite warehouse stock are the
 * only fixtures. After the initial spawn, the production scheduler and vanilla
 * entity ticks perform every movement, extraction, work action and delivery.
 * Run these world-writing tests only in a disposable GameTest world.
 */
@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProfessionWorkGameTests {
    private static final int TIMEOUT_TICKS = 6000;
    private static final String BATCH = "profession_work_live";

    private ProfessionWorkGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void builderCollectsFiniteBatchThenPlacesSeveralBlocks(GameTestHelper helper) {
        builderCollectsFiniteBatch(helper, false);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void builderCraftsScaffoldFromWarehouseSticks(GameTestHelper helper) {
        builderCollectsFiniteBatch(helper, true);
    }

    private static void builderCollectsFiniteBatch(GameTestHelper helper, boolean sticksOnly) {
        Fixture fixture = Fixture.create(helper, sticksOnly ? 5 : 4, CitizenProfession.SAWYER);
        fixture.citizen.setManualAssignment(CitizenProfession.WORKER, null);
        fixture.citizen.assignProfession(CitizenProfession.WORKER, null);
        BlockPos anchor = fixture.core.offset(7, 0, 8);
        var site = fixture.settlement.addBlueprint(BuildingType.HOUSE, anchor, anchor, Direction.NORTH,
                fixture.settlement.ownerId(), false);
        var template = BlueprintTemplates.create(site);
        Item material = template.placements().stream().filter(p -> p.material() != null)
                .findFirst().orElseThrow().material();
        fixture.stock(0, 0, new ItemStack(material, 17));
        fixture.stock(1, 0, new ItemStack(material, 47));
        if (sticksOnly) {
            for (int slot = 0; slot < 24; slot++) fixture.stock(2, slot, new ItemStack(Items.STICK, 64));
        } else {
            for (int slot = 0; slot < 4; slot++) fixture.stock(2, slot, new ItemStack(Items.SCAFFOLDING, 64));
        }
        fixture.stock(3, 0, new ItemStack(Items.COBBLESTONE, 32));
        fixture.start();
        int[] peak = {0};
        boolean[] departed = {false};
        helper.startSequence().thenWaitUntil(fixture::awaitProjection).thenWaitUntil(() -> {
            fixture.observe();
            int bag = fixture.inventoryCount(material);
            peak[0] = Math.max(peak[0], bag);
            long placed = template.placements().stream().filter(p -> p.material() == material
                    && fixture.level.getBlockState(p.position()).is(p.state().getBlock())).count();
            if (peak[0] > 0 && fixture.citizen.workState() == CitizenWorkState.TO_BLUEPRINT) departed[0] = true;
            helper.assertTrue(!departed[0] || peak[0] >= 32,
                    "Builder departed after a tiny pickup instead of collecting split finite stock: " + peak[0]);
            helper.assertTrue(peak[0] >= 32 && placed >= 4,
                    "Finite warehouse -> backpack -> multiple placements not complete: peak=" + peak[0]
                            + " placed=" + placed + " " + fixture.diagnostics());
        }).thenExecute(fixture::finish).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void keeperPhysicallyTransfersRejectedStockWithoutPingPong(GameTestHelper helper) {
        Fixture fixture = Fixture.create(helper, 3, CitizenProfession.WAREHOUSE_KEEPER);
        fixture.workplace.toggleWarehouseCategory(com.hkin.exiledsettlement.building.WarehouseCategory.FOOD);
        Container source = WarehouseInventoryService.loadedContainers(fixture.level, fixture.workplace).getFirst().container();
        source.setItem(0, new ItemStack(Items.APPLE, 31));
        source.setItem(1, new ItemStack(Items.STONE_BRICKS, 7));
        source.setItem(2, new ItemStack(Items.STONE_BRICKS, 11));
        fixture.start();
        boolean[] carried = {false};
        helper.startSequence().thenWaitUntil(fixture::awaitProjection)
                .thenExecute(() -> fixture.citizen.insertIntoInventory(new ItemStack(Items.BREAD, 16)))
                .thenWaitUntil(() -> {
            fixture.observe();
            int bag = fixture.inventoryCount(Items.APPLE);
            int from = WarehouseInventoryService.countItem(fixture.level, fixture.workplace, Items.APPLE);
            int to = fixture.stored(Items.APPLE);
            carried[0] |= bag == 31;
            fixture.requireInvariant(from + to + bag == 31, "Keeper duplicated or lost apples");
            helper.assertTrue(fixture.invariantFailure.isEmpty(), fixture.diagnostics());
            helper.assertTrue(carried[0] && to == 31 && from == 0 && bag == 0,
                    "Keeper has not completed its real classification trip: " + fixture.diagnostics());
        }).thenIdle(240).thenExecute(() -> {
            helper.assertTrue(fixture.stored(Items.APPLE) == 31
                            && WarehouseInventoryService.countItem(fixture.level, fixture.workplace, Items.APPLE) == 0,
                    "Keeper moved classified stock back to its rejecting source");
            helper.assertTrue(WarehouseInventoryService.countItem(fixture.level, fixture.workplace, Items.STONE_BRICKS) == 18,
                    "Keeper moved stock already accepted by its source warehouse");
            var loaded = SettlementRecord.load(fixture.settlement.save(fixture.level.registryAccess()), fixture.level.registryAccess());
            helper.assertTrue(loaded.citizen(fixture.citizen.id()).profession() == CitizenProfession.WAREHOUSE_KEEPER,
                    "Keeper profession did not survive save/load");
            fixture.finish();
        }).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void emptyBackpackFarmerFetchesToolsSeedsAndPlantsInsideFarm(GameTestHelper helper) {
        Fixture fixture = Fixture.create(helper, 0, CitizenProfession.FARMER);
        fixture.stock(0, 0, new ItemStack(Items.IRON_HOE));
        fixture.stock(1, 0, new ItemStack(Items.WHEAT_SEEDS, 64));
        fixture.start();
        boolean[] sawEquipmentTrip = {false};
        boolean[] sawSeedTrip = {false};
        boolean[] enteredFarm = {false};
        int[] previousPlanted = {0};

        helper.startSequence().thenWaitUntil(fixture::awaitProjection).thenWaitUntil(() -> {
            fixture.observe();
            sawEquipmentTrip[0] |= fixture.citizen.workState() == CitizenWorkState.TO_EQUIPMENT;
            sawSeedTrip[0] |= fixture.citizen.workState() == CitizenWorkState.TO_FARM_SUPPLY;
            enteredFarm[0] |= insideHorizontal(fixture.workplace, fixture.entity.blockPosition());
            int planted = (int) fixture.workplace.servicePositions().stream()
                    .filter(position -> fixture.level.getBlockState(position.above()).is(Blocks.WHEAT))
                    .count();
            if (planted > previousPlanted[0]) {
                  fixture.requireInvariant(insideHorizontal(fixture.workplace, fixture.entity.blockPosition()),
                        "Wheat was planted while the resident was outside the fenced field: planted="
                                + previousPlanted[0] + " -> " + planted);
            }
            previousPlanted[0] = planted;
            int seeds = fixture.inventoryCount(Items.WHEAT_SEEDS) + fixture.stored(Items.WHEAT_SEEDS);
            fixture.requireInvariant(seeds + planted == 64,
                    "Seed conservation failed: seed stacks=" + seeds + ", planted=" + planted);
            fixture.requireInvariant(fixture.stored(Items.IRON_HOE)
                            + fixture.inventoryCount(Items.IRON_HOE) == 1,
                    "The single warehouse hoe was lost or duplicated");
            helper.assertTrue(fixture.invariantFailure.isEmpty(), fixture.diagnostics());
            helper.assertTrue(sawEquipmentTrip[0] && sawSeedTrip[0] && enteredFarm[0]
                            && fixture.inventoryCount(Items.IRON_HOE) == 1
                            && fixture.stored(Items.IRON_HOE) == 0 && planted >= 8,
                    "Farmer did not fetch a hoe and seeds, enter the fenced farm and plant eight cells: "
                            + fixture.diagnostics() + ", planted=" + planted
                            + ", equipmentTrip=" + sawEquipmentTrip[0] + ", seedTrip=" + sawSeedTrip[0]);
        }).thenExecute(fixture::finish).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void melonFarmerEntersClosedFieldPlantsAndHarvests(GameTestHelper helper) {
        Fixture fixture = Fixture.create(helper, 6, CitizenProfession.MELON_FARMER);
        fixture.stock(0, 0, new ItemStack(Items.IRON_HOE));
        fixture.stock(1, 0, new ItemStack(Items.MELON_SEEDS, 64));
        var template = BlueprintTemplates.createForBuilding(fixture.workplace);
        BlockPos fruit = template.placements().stream()
                .filter(p -> p.state().is(Blocks.DIRT))
                .map(p -> p.position().above())
                .filter(p -> insideHorizontal(fixture.workplace, p))
                .findFirst().orElseThrow();
        fixture.level.setBlock(fruit, Blocks.MELON.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(template.placements().stream()
                        .filter(p -> p.state().getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock)
                        .allMatch(p -> !fixture.level.getBlockState(p.position()).getValue(
                                net.minecraft.world.level.block.FenceGateBlock.OPEN)),
                "Melon-field fixture must start with closed gates");
        fixture.start();
        boolean[] entered = {false};
        helper.startSequence().thenWaitUntil(fixture::awaitProjection).thenWaitUntil(() -> {
            fixture.observe();
            entered[0] |= insideHorizontal(fixture.workplace, fixture.entity.blockPosition());
            long planted = fixture.workplace.servicePositions().stream()
                    .filter(p -> fixture.level.getBlockState(p.above()).is(Blocks.MELON_STEM)
                            || fixture.level.getBlockState(p.above()).is(Blocks.ATTACHED_MELON_STEM)).count();
            helper.assertTrue(fixture.invariantFailure.isEmpty(), fixture.diagnostics());
            helper.assertTrue(entered[0] && planted >= 4
                            && !fixture.level.getBlockState(fruit).is(Blocks.MELON)
                            && fixture.inventoryCount(Items.MELON_SLICE) + fixture.stored(Items.MELON_SLICE) >= 3,
                    "Melon farmer has not entered, planted and collected the harvest: " + fixture.diagnostics()
                            + ", planted=" + planted + ", slices="
                            + (fixture.inventoryCount(Items.MELON_SLICE) + fixture.stored(Items.MELON_SLICE)));
        }).thenExecute(fixture::finish).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "resident_melon_updates", timeoutTicks = TIMEOUT_TICKS)
    public static void pumpkinFarmerRepairsTrampledSoilPlantsAndDelivers(GameTestHelper helper) {
        Fixture f = Fixture.create(helper, 7, CitizenProfession.MELON_FARMER);
        helper.assertTrue(f.workplace.setProductionId(com.hkin.exiledsettlement.building.BuildingProductionService.PUMPKIN), "Pumpkin setting rejected");
        f.stock(0, 0, new ItemStack(Items.IRON_HOE));
        f.stock(1, 0, new ItemStack(Items.PUMPKIN_SEEDS, 64));
        var template = BlueprintTemplates.createForBuilding(f.workplace);
        var damaged = template.placements().stream().filter(p -> p.state().is(Blocks.FARMLAND))
                .map(p -> p.position()).limit(2).toList();
        damaged.forEach(p -> f.level.setBlock(p, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL));
        BlockPos fruit = template.placements().stream().filter(p -> p.state().is(Blocks.DIRT))
                .map(p -> p.position().above()).findFirst().orElseThrow();
        f.level.setBlock(fruit, Blocks.PUMPKIN.defaultBlockState(), Block.UPDATE_ALL);
        f.start();
        helper.onEachTick(() -> { if (!f.finished) f.level.setDayTime(12000); });
        helper.startSequence().thenWaitUntil(f::awaitProjection).thenWaitUntil(() -> {
            f.observe();
            long stems = f.workplace.servicePositions().stream().filter(p -> f.level.getBlockState(p.above()).is(Blocks.PUMPKIN_STEM)
                    || f.level.getBlockState(p.above()).is(Blocks.ATTACHED_PUMPKIN_STEM)).count();
            helper.assertTrue(f.invariantFailure.isEmpty(), f.diagnostics());
            helper.assertTrue(damaged.stream().allMatch(p -> f.level.getBlockState(p).is(Blocks.FARMLAND))
                            && stems >= 4 && f.stored(Items.PUMPKIN) >= 1
                            && f.stored(Items.PUMPKIN_SEEDS) + f.inventoryCount(Items.PUMPKIN_SEEDS) < 64
                            && f.citizen.inventory().stream().anyMatch(stack -> stack.is(Items.IRON_HOE) && stack.getDamageValue() >= 2),
                    "Pumpkin repair, real planting inputs and warehouse delivery incomplete: " + f.diagnostics());
        }).thenExecute(f::finish).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "resident_melon_updates", timeoutTicks = TIMEOUT_TICKS)
    public static void mixedMelonsPlantBothRealSeedTypesWithoutReturningThem(GameTestHelper helper) {
        Fixture f = Fixture.create(helper, 8, CitizenProfession.MELON_FARMER);
        helper.assertTrue(f.workplace.setProductionId(com.hkin.exiledsettlement.building.BuildingProductionService.MIXED_MELONS), "Mixed setting rejected");
        f.stock(0, 0, new ItemStack(Items.IRON_HOE));
        f.stock(1, 0, new ItemStack(Items.PUMPKIN_SEEDS, 32));
        f.stock(2, 0, new ItemStack(Items.MELON_SEEDS, 32));
        f.start();
        helper.onEachTick(() -> { if (!f.finished) f.level.setDayTime(12000); });
        helper.startSequence().thenWaitUntil(f::awaitProjection).thenWaitUntil(() -> {
            f.observe();
            int pumpkins = 0, melons = 0;
            for (BlockPos ground : f.workplace.servicePositions()) {
                var plant = f.level.getBlockState(ground.above());
                if (plant.is(Blocks.PUMPKIN_STEM)) pumpkins++;
                if (plant.is(Blocks.MELON_STEM)) melons++;
                if (plant.is(Blocks.PUMPKIN_STEM) || plant.is(Blocks.MELON_STEM)) {
                    var expected = com.hkin.exiledsettlement.building.BuildingProductionService.plantingSeed(f.workplace, ground);
                    helper.assertTrue(plant.is(expected == Items.PUMPKIN_SEEDS ? Blocks.PUMPKIN_STEM : Blocks.MELON_STEM), "Mixed cell changed its deterministic crop");
                }
            }
            helper.assertTrue(f.invariantFailure.isEmpty(), f.diagnostics());
            helper.assertTrue(pumpkins >= 2 && melons >= 2
                            && f.stored(Items.PUMPKIN_SEEDS) + f.inventoryCount(Items.PUMPKIN_SEEDS) == 32 - pumpkins
                            && f.stored(Items.MELON_SEEDS) + f.inventoryCount(Items.MELON_SEEDS) == 32 - melons,
                    "Mixed planting did not conserve both real seed types: " + f.diagnostics());
        }).thenExecute(f::finish).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void fisherFetchesReplacementRodDeliversAndStopsWithoutTools(GameTestHelper helper) {
        Fixture fixture = Fixture.create(helper, 1, CitizenProfession.FISHER);
        // Two finite, one-use rods force real replacement without filling the
        // resident's backpack artificially or waiting an entire fishing season.
        ItemStack firstRod = new ItemStack(Items.FISHING_ROD);
        firstRod.setDamageValue(firstRod.getMaxDamage() - 1);
        fixture.stock(0, 0, firstRod);
        fixture.stock(1, 0, firstRod.copy());
        fixture.start();
        boolean[] acquiredFirst = {false};
        boolean[] acquiredReplacement = {false};
        long[] toolsExhaustedAt = {-1L};
        int[] fishAtExhaustion = {-1};
        int[] lastStoredRods = {2};
        int[] acquisitions = {0};
        int[] previousFish = {0};
        BlockPos[] fishingStation = {null};
        Vec3[] previousPosition = {null};

        helper.startSequence().thenWaitUntil(fixture::awaitProjection)
                .thenExecute(() -> fixture.citizen.insertIntoInventory(new ItemStack(Items.BREAD, 16)))
                .thenWaitUntil(() -> {
            fixture.observe();
            if (fixture.citizen.workState() == CitizenWorkState.TO_FISH
                    && fixture.citizen.targetPosition() != null) {
                fishingStation[0] = fixture.citizen.targetPosition().immutable();
            }
            int storedRods = fixture.stored(Items.FISHING_ROD);
            if (storedRods < lastStoredRods[0]) acquisitions[0] += lastStoredRods[0] - storedRods;
            lastStoredRods[0] = storedRods;
            acquiredFirst[0] |= acquisitions[0] >= 1 && fixture.inventoryCount(Items.FISHING_ROD) == 1;
            acquiredReplacement[0] |= acquisitions[0] == 2 && fixture.inventoryCount(Items.FISHING_ROD) == 1;
            int fish = fixture.inventoryCount(Items.COD) + fixture.inventoryCount(Items.SALMON)
                    + fixture.stored(Items.COD) + fixture.stored(Items.SALMON);
            if (fish > previousFish[0]) {
                // The work event and entity movement may run on opposite sides
                // of this observation. Check the two adjacent real tick samples,
                // never an earlier visit to the shore or an expanded work reach.
                fixture.requireInvariant(legalFishingStation(fixture, fishingStation[0])
                                && (withinFishingReach(fixture.entity.position(), fishingStation[0])
                                || withinFishingReach(previousPosition[0], fishingStation[0])),
                        "Fish appeared without a reachable, supported shore work position: station="
                                + fishingStation[0] + ", fish=" + previousFish[0] + " -> " + fish);
            }
            previousFish[0] = fish;
            previousPosition[0] = fixture.entity.position();
            fixture.requireInvariant(storedRods + fixture.inventoryCount(Items.FISHING_ROD) <= 2,
                    "The two finite fishing rods were duplicated");
            if (acquiredReplacement[0] && storedRods == 0
                    && fixture.inventoryCount(Items.FISHING_ROD) == 0 && toolsExhaustedAt[0] < 0L) {
                toolsExhaustedAt[0] = fixture.level.getGameTime();
                fishAtExhaustion[0] = fish;
            }
            if (toolsExhaustedAt[0] >= 0L) {
                fixture.requireInvariant(fish == fishAtExhaustion[0],
                        "Fish production continued after both rods broke: " + fishAtExhaustion[0] + " -> " + fish);
            }
            helper.assertTrue(fixture.invariantFailure.isEmpty(), fixture.diagnostics());
            helper.assertTrue(acquiredFirst[0] && acquiredReplacement[0]
                            && fish >= 2 && fish <= 4
                            && fixture.stored(Items.COD) + fixture.stored(Items.SALMON) == fish
                            && toolsExhaustedAt[0] >= 0L
                            && fixture.level.getGameTime() - toolsExhaustedAt[0] >= 400L,
                    "Fisher did not fetch both finite rods, deliver the catches and stop for 400 ticks: "
                            + fixture.diagnostics() + ", acquisitions=" + acquisitions[0] + ", fish=" + fish);
        }).thenExecute(fixture::finish).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = BATCH, timeoutTicks = TIMEOUT_TICKS)
    public static void sawyerFetchesSplitLogsProcessesAndDeliversConservatively(GameTestHelper helper) {
        Fixture fixture = Fixture.create(helper, 2, CitizenProfession.SAWYER);
        fixture.stock(0, 0, new ItemStack(Items.IRON_AXE));
        fixture.stock(0, 1, new ItemStack(Items.OAK_LOG, 3));
        fixture.stock(1, 0, new ItemStack(Items.OAK_LOG, 5));
        fixture.start();
        boolean[] sawEquipmentTrip = {false};
        boolean[] sawLogTrip = {false};
        boolean[] workedInside = {false};
        int[] previousOutputValue = {0};
        int[] peakCarriedLogs = {0};
        boolean[] collectedFullBatchBeforeWork = {false};

        helper.startSequence().thenWaitUntil(fixture::awaitProjection).thenWaitUntil(() -> {
            fixture.observe();
            sawEquipmentTrip[0] |= fixture.citizen.workState() == CitizenWorkState.TO_EQUIPMENT;
            sawLogTrip[0] |= fixture.citizen.workState() == CitizenWorkState.TO_SAWMILL_SUPPLY;
            int carriedLogs = fixture.inventoryCount(Items.OAK_LOG);
            int storedLogs = fixture.stored(Items.OAK_LOG);
            int logs = storedLogs + carriedLogs;
            int planks = fixture.stored(Items.OAK_PLANKS) + fixture.inventoryCount(Items.OAK_PLANKS);
            int outputValue = carpenterOutputPlankSixths(fixture);
            peakCarriedLogs[0] = Math.max(peakCarriedLogs[0], carriedLogs);
            fixture.requireInvariant(carriedLogs <= 8,
                    "Sawyer carried more than the eight finite input logs: carried=" + carriedLogs);
            if (planks == 0) {
                collectedFullBatchBeforeWork[0] |= carriedLogs == 8 && storedLogs == 0;
                if (fixture.citizen.workState() == CitizenWorkState.TO_SAWMILL) {
                    fixture.requireInvariant(carriedLogs == 8 && storedLogs == 0,
                            "Sawyer left the supply phase before collecting both input stacks: carried="
                                    + carriedLogs + ", warehouse=" + storedLogs);
                }
            }
            fixture.requireInvariant(logs * 30 + planks * 6 + outputValue == 240,
                    "Carpenter material conservation failed: logs=" + logs + ", planks=" + planks
                            + ", productSixths=" + outputValue);
            if (planks * 6 + outputValue > previousOutputValue[0]) {
                fixture.requireInvariant(collectedFullBatchBeforeWork[0] && peakCarriedLogs[0] == 8,
                        "Sawyer started production without first carrying all eight available logs: peak="
                                + peakCarriedLogs[0]);
                workedInside[0] |= WarehouseInventoryService.canInteract(fixture.level, fixture.workplace, fixture.entity);
                fixture.requireInvariant(WarehouseInventoryService.canInteract(fixture.level, fixture.workplace, fixture.entity),
                        "Sawmill output appeared while the resident was outside the workshop");
            }
            previousOutputValue[0] = planks * 6 + outputValue;
            helper.assertTrue(fixture.invariantFailure.isEmpty(), fixture.diagnostics());
            helper.assertTrue(sawEquipmentTrip[0] && sawLogTrip[0] && workedInside[0]
                            && collectedFullBatchBeforeWork[0] && peakCarriedLogs[0] == 8
                            && fixture.inventoryCount(Items.IRON_AXE) == 1 && logs == 0
                            && planks * 6 + outputValue == 240
                            && fixture.citizen.inventory().stream().noneMatch(stack -> carpenterProduct(stack.getItem())),
                    "Carpenter did not naturally fetch two log stacks, process and deliver their full value: "
                            + fixture.diagnostics() + ", logs=" + logs + ", planks=" + planks
                            + ", peakCarriedLogs=" + peakCarriedLogs[0]);
        }).thenExecute(fixture::finish).thenSucceed();
    }

    private static int carpenterOutputPlankSixths(Fixture fixture) {
        int total = 0;
        for (Item item : List.of(Items.CHARCOAL, Items.OAK_SLAB, Items.OAK_STAIRS, Items.OAK_DOOR,
                Items.OAK_TRAPDOOR, Items.OAK_PRESSURE_PLATE, Items.OAK_BUTTON, Items.CHEST, Items.OAK_BOAT)) {
            int count = fixture.stored(item) + fixture.inventoryCount(item);
            int value = item == Items.CHARCOAL ? 12 : item == Items.OAK_SLAB ? 3
                    : item == Items.OAK_STAIRS ? 9 : item == Items.OAK_DOOR ? 12
                    : item == Items.OAK_TRAPDOOR ? 18 : item == Items.OAK_PRESSURE_PLATE ? 12
                    : item == Items.OAK_BUTTON ? 6 : item == Items.CHEST ? 48 : 30;
            total += count * value;
        }
        return total;
    }

    private static boolean carpenterProduct(Item item) {
        return item == Items.CHARCOAL || item == Items.OAK_SLAB || item == Items.OAK_STAIRS
                || item == Items.OAK_DOOR || item == Items.OAK_TRAPDOOR
                || item == Items.OAK_PRESSURE_PLATE || item == Items.OAK_BUTTON
                || item == Items.CHEST || item == Items.OAK_BOAT;
    }

    private static boolean insideHorizontal(BuildingRecord building, BlockPos position) {
        return position.getX() > building.minCorner().getX() && position.getX() < building.maxCorner().getX()
                && position.getZ() > building.minCorner().getZ() && position.getZ() < building.maxCorner().getZ();
    }

    private static boolean legalFishingStation(Fixture fixture, BlockPos station) {
        if (station == null || !fixture.entity.canStandAtPosition(station)) return false;
        BlockPos support = station.below();
        if (!fixture.level.getBlockState(support).isFaceSturdy(fixture.level, support, Direction.UP)
                || !fixture.level.getBlockState(station).getCollisionShape(fixture.level, station).isEmpty()
                || !fixture.level.getBlockState(station.above())
                .getCollisionShape(fixture.level, station.above()).isEmpty()) return false;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos water = support.relative(direction);
            BlockPos hut = fixture.workplace.position();
            if (Math.abs(water.getX() - hut.getX()) <= 12
                    && Math.abs(water.getY() - hut.getY()) <= 4
                    && Math.abs(water.getZ() - hut.getZ()) <= 12
                    && fixture.level.getFluidState(water).is(FluidTags.WATER)) return true;
        }
        return false;
    }

    private static boolean withinFishingReach(Vec3 position, BlockPos station) {
        // Normal profession interaction distance: 2.5 blocks, not the removed
        // construction reach workaround. No navigation or inventory mutations.
        return position != null && station != null
                && position.distanceToSqr(Vec3.atBottomCenterOf(station)) <= 6.25D;
    }

    private static final class Fixture {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final SettlementSavedData data;
        private final SettlementRecord settlement;
        private final BlockPos core;
        private final CitizenRecord citizen;
        private final BuildingRecord warehouse;
        private final BuildingRecord workplace;
        private final ChunkPos firstChunk;
        private final ChunkPos lastChunk;
        private final List<BlockPos> stockedContainers = new ArrayList<>();
        private SettlementCitizenEntity entity;
        private long startedAt;
        private String invariantFailure = "";
        private boolean projectionObserved;
        private boolean finished;

        private Fixture(GameTestHelper helper, SettlementRecord settlement, BlockPos core,
                        CitizenRecord citizen, BuildingRecord warehouse, BuildingRecord workplace,
                        ChunkPos firstChunk, ChunkPos lastChunk) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.data = SettlementSavedData.get(level);
            this.settlement = settlement;
            this.core = core;
            this.citizen = citizen;
            this.warehouse = warehouse;
            this.workplace = workplace;
            this.firstChunk = firstChunk;
            this.lastChunk = lastChunk;
        }

        private static Fixture create(GameTestHelper helper, int scenario, CitizenProfession profession) {
            ServerLevel level = helper.getLevel();
            SettlementSavedData data = SettlementSavedData.get(level);
            BlockPos core = unusedFixtureCore(data,
                    helper.absolutePos(new BlockPos(8, 10, 8)).offset(3072 + scenario * 192, 0, 0));
            ChunkPos firstChunk = new ChunkPos(core.offset(-40, 0, -32));
            ChunkPos lastChunk = new ChunkPos(core.offset(40, 0, 32));
            forceChunks(level, firstChunk, lastChunk, true);
            // Isolated, disposable fixture terrain; formal building shapes are
            // copied unchanged and their actual function is validated below.
            for (BlockPos position : BlockPos.betweenClosed(core.offset(-36, -4, -28), core.offset(36, 15, 28))) {
                if (position.getY() < core.getY()) {
                    level.setBlock(position, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
                } else if (!level.getBlockState(position).isAir()) {
                    level.setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            level.setBlock(core, ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(), Block.UPDATE_ALL);
            level.setDayTime(6000L);
            UUID owner = level.players().size() == 1 ? level.players().getFirst().getUUID() : UUID.randomUUID();
            var result = data.createOrRecover(core, owner, "Live " + profession + " Work Gate");
            helper.assertTrue(result.success() && result.value() != null,
                    "Could not create isolated profession fixture: " + result.reason());
            SettlementRecord settlement = result.value();
            settlement.upgradeLevel();
            settlement.upgradeLevel();
            settlement.setCreativeCoreInstalled(false);
            CitizenRecord citizen = settlement.citizens().iterator().next();
            for (CitizenRecord other : settlement.citizens()) {
                if (other != citizen) other.markDead(core);
            }
            BuildingRecord warehouse = placeAndRegister(helper, settlement,
                    BlueprintTemplates.create(BuildingType.WAREHOUSE, core.offset(-24, 0, -12), Direction.EAST));
            BuildingBlueprintTemplate workplaceTemplate;
            if (profession == CitizenProfession.FARMER || profession == CitizenProfession.MELON_FARMER) {
                workplaceTemplate = BlueprintTemplates.createArea(profession.workplaceType(),
                        core.offset(6, 0, -14), core.offset(14, 0, -6), Direction.SOUTH);
            } else {
                workplaceTemplate = BlueprintTemplates.create(profession.workplaceType(),
                        core.offset(12, 0, -8), Direction.NORTH);
                if (profession == CitizenProfession.FISHER) {
                    for (BlockPos water : BlockPos.betweenClosed(core.offset(18, -1, -17), core.offset(20, -1, -15))) {
                        level.setBlock(water, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
            BuildingRecord workplace = placeAndRegister(helper, settlement, workplaceTemplate);
            citizen.setManualAssignment(profession, workplace.id());
            data.touch();
            Fixture fixture = new Fixture(helper, settlement, core, citizen, warehouse, workplace, firstChunk, lastChunk);
            // Release persistent registrations/chunks on timeout as well as success.
            helper.runAtTickTime(TIMEOUT_TICKS - 1L, () -> {
                if (!fixture.finished) {
                    fixture.requireInvariant(false, "The live work gate reached its 6000-tick limit");
                    ExiledSettlementMod.LOGGER.error("[profession-gate] timeout {}", fixture.fullDiagnostics());
                    fixture.dumpFailedRoutes();
                    fixture.finish();
                }
            });
            return fixture;
        }

        private static BlockPos unusedFixtureCore(SettlementSavedData data, BlockPos first) {
            // A repeated /test run must not reactivate an old gate with a full
            // backpack or overwrite a previously observed fixture. Retain both
            // successful and failed layouts and use a separate local district.
            for (int attempt = 0; attempt < 32; attempt++) {
                BlockPos candidate = first.offset(0, 0, attempt * 192);
                if (data.settlements().stream().noneMatch(existing -> existing.overlapsBoundary(candidate, 4))) {
                    return candidate;
                }
            }
            throw new IllegalStateException("No unused local profession-test district; use a fresh test world");
        }

        private static BuildingRecord placeAndRegister(GameTestHelper helper, SettlementRecord settlement,
                                                       BuildingBlueprintTemplate template) {
            ServerLevel level = helper.getLevel();
            for (var placement : template.placements()) {
                level.setBlock(placement.position(), placement.mode() == BlueprintPlacementMode.CLEARANCE
                        ? Blocks.AIR.defaultBlockState() : placement.state(), Block.UPDATE_ALL);
            }
            for (var placement : template.placements()) BlueprintBlockStateResolver.refreshExisting(level, placement);
            BuildingRecord building = settlement.addBuilding(template.type(), template.registrationPosition(),
                    template.facing(), template.minCorner(), template.maxCorner(), template.transitionMargin(),
                    BlueprintTemplates.currentTemplateVersion(template.type()));
            BuildingFunctionService.validateSingle(level, SettlementSavedData.get(level), settlement, building);
            helper.assertTrue(building.operational(), "Formal " + template.type()
                    + " fixture did not pass actual function validation: " + building.status());
            return building;
        }

        private void stock(int containerIndex, int slot, ItemStack stack) {
            List<WarehouseInventoryService.ContainerAt> containers = WarehouseInventoryService.loadedContainers(level, warehouse);
            helper.assertTrue(containers.size() > containerIndex,
                    "Formal warehouse does not provide the distinct containers required by this fixture");
            Container container = containers.get(containerIndex).container();
            BlockPos position = containers.get(containerIndex).position();
            if (!stockedContainers.contains(position)) stockedContainers.add(position);
            helper.assertTrue(container.getItem(slot).isEmpty(), "Fixture stock would overwrite another input");
            container.setItem(slot, stack.copy());
            container.setChanged();
        }

        private void start() {
            helper.assertTrue(citizen.inventory().stream().allMatch(ItemStack::isEmpty),
                    "The resident must begin with an empty backpack");
            BlockPos start = core.offset(0, 0, 12);
            helper.assertTrue(WarehouseInventoryService.loadedContainers(level, warehouse).stream()
                            .allMatch(container -> container.position().distSqr(start) >= 144.0D),
                    "Initial resident position is less than 12 blocks from a supply container");
            // Seed the record's initial spawn point before requesting a
            // projection. Chunk readiness/bounded projection work may defer
            // spawning; never require every fixture to spawn on the same tick,
            // and never move an already-working entity back to the start.
            citizen.updateProjection(null, start);
            startedAt = level.getGameTime();
            data.touch();
            CitizenProjectionService.ensureSettlementCitizens(level, settlement);
            ExiledSettlementMod.LOGGER.info("[profession-gate] requested profession={} core={} citizen={} workplace={} warehouse={}",
                    citizen.profession(), core, citizen.id(), workplace.position(), warehouse.position());
        }

        private void awaitProjection() {
            helper.assertTrue(!finished, diagnostics());
            if (entity == null) {
                CitizenProjectionService.ensureSettlementCitizens(level, settlement);
                entity = CitizenProjectionService.findProjection(level, citizen);
            }
            helper.assertTrue(entity != null, "Waiting for actual resident projection; elapsed="
                    + (level.getGameTime() - startedAt) + ", citizenPosition=" + citizen.lastPosition());
            if (!projectionObserved) {
                projectionObserved = true;
                requireInvariant(citizen.inventory().stream().allMatch(ItemStack::isEmpty),
                        "The resident did not have an empty backpack at first projection observation");
                // Record geometric proof for the existing stocked containers.
                // Do not move stock to easier containers or replace the formal
                // warehouse when navigation to a valid station fails.
                for (BlockPos container : stockedContainers) {
                    List<BlockPos> stations = suppliedContainerStations(container);
                    requireInvariant(!stations.isEmpty(),
                            "Fixture supplied a container without a safe visible standing cell: " + container);
                    ExiledSettlementMod.LOGGER.info("[profession-gate] supply profession={} container={} legalStations={}",
                            citizen.profession(), container, stations);
                }
                ExiledSettlementMod.LOGGER.info("[profession-gate] ready spawnWaitTicks={} {}",
                        level.getGameTime() - startedAt, fullDiagnostics());
            }
            helper.assertTrue(invariantFailure.isEmpty(), diagnostics());
        }

        private List<BlockPos> suppliedContainerStations(BlockPos container) {
            List<BlockPos> stations = new ArrayList<>();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos station = container.relative(direction);
                if (station.getX() < warehouse.minCorner().getX()
                        || station.getX() > warehouse.maxCorner().getX()
                        || station.getZ() < warehouse.minCorner().getZ()
                        || station.getZ() > warehouse.maxCorner().getZ()
                        || !entity.canStandAtPosition(station)) continue;
                Vec3 eye = new Vec3(station.getX() + 0.5D, station.getY() + 1.62D, station.getZ() + 0.5D);
                var hit = level.clip(new ClipContext(eye, Vec3.atCenterOf(container),
                        ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, entity));
                if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(container)) stations.add(station);
            }
            return List.copyOf(stations);
        }

        private void observe() {
            boolean rest = switch (citizen.profession()) {
                case MELON_FARMER -> inventoryCount(Items.PUMPKIN) > 0 && workplace.servicePositions().stream()
                        .filter(p -> level.getBlockState(p.above()).is(Blocks.PUMPKIN_STEM)
                                || level.getBlockState(p.above()).is(Blocks.ATTACHED_PUMPKIN_STEM)).count() >= 4;
                case FISHER -> stored(Items.FISHING_ROD) == 0 && inventoryCount(Items.FISHING_ROD) == 0
                        && inventoryCount(Items.COD) + inventoryCount(Items.SALMON) > 0;
                case SAWYER -> inventoryCount(Items.OAK_PLANKS) > 0 && inventoryCount(Items.OAK_LOG) == 0;
                default -> false;
            };
            level.setDayTime((rest ? 23000L : 6000L) + CitizenWorkService.scheduleOffset(citizen));
            helper.assertTrue(!finished, diagnostics());
            requireInvariant(entity != null && entity.isAlive(), "The resident died or unloaded");
            requireInvariant(workplace.operational() && warehouse.operational(), "A validated work building stopped operating");
            requireInvariant(!settlement.creativeCoreInstalled()
                    && !WarehouseInventoryService.hasCreativeSupply(level, warehouse), "Creative supply contaminated the gate");
            if ((level.getGameTime() - startedAt) % 200L == 0L) {
                ExiledSettlementMod.LOGGER.info("[profession-gate] {}", fullDiagnostics());
            }
        }

        private void requireInvariant(boolean condition, String message) {
            if (!condition && invariantFailure.isEmpty()) invariantFailure = message;
        }

        private int stored(Item item) {
            return WarehouseInventoryService.countItem(level, warehouse, item);
        }

        private int inventoryCount(Item item) {
            return citizen.inventory().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
        }

        private String diagnostics() {
            // GameTest writes failures into a writable-book page (1024-char
            // limit). Keep assertions short; full inventory/path evidence is
            // logged separately, never embedded in the failure book.
            String summary = "profession=" + citizen.profession() + ", elapsed=" + (level.getGameTime() - startedAt)
                    + ", state=" + citizen.workState() + ", failure=" + citizen.workFailure()
                    + ", at=" + (entity == null ? "not_spawned" : entity.blockPosition())
                    + ", target=" + citizen.targetPosition() + ", invariant=" + invariantFailure;
            return summary.length() <= 480 ? summary : summary.substring(0, 477) + "...";
        }

        private String fullDiagnostics() {
            return "profession=" + citizen.profession() + ", elapsed=" + (level.getGameTime() - startedAt)
                    + ", state=" + citizen.workState() + ", failure=" + citizen.workFailure()
                    + ", position=" + (entity == null ? "not_spawned" : entity.position())
                    + ", entityTicks=" + (entity == null ? -1 : entity.tickCount)
                    + ", activity=" + (entity == null ? "not_spawned" : entity.activity())
                    + ", navigation=" + (entity == null ? "not_spawned" : entity.settlementNavigationDiagnostics())
                    + ", target=" + citizen.targetPosition() + ", inventory="
                    + citizen.inventory().stream().filter(stack -> !stack.isEmpty()).toList()
                    + ", stock=" + WarehouseInventoryService.summarize(level, warehouse)
                    + ", invariant=" + invariantFailure;
        }

        private void finish() {
            if (finished) return;
            finished = true;
            ExiledSettlementMod.LOGGER.info("[profession-gate] finished {}", fullDiagnostics());
            settlement.deactivate();
            if (entity != null) {
                CitizenNavigationReservationService.releaseAll(level, entity.getUUID());
                entity.stopSettlementNavigation();
                entity.discard();
            }
            forceChunks(level, firstChunk, lastChunk, false);
            data.touch();
        }

        private void dumpFailedRoutes() {
            if (entity == null || !entity.isAlive()) return;
            // The measured gate has already failed. Probing the native path
            // finder only here avoids changing its target/path cache while the
            // production scheduler is being measured.
            List<BlockPos> candidates = stockedContainers.stream()
                    .flatMap(container -> suppliedContainerStations(container).stream()).distinct().toList();
            BlockPos reservation = CitizenNavigationReservationService.stationFor(level,
                    entity.getUUID(), level.getGameTime());
            ExiledSettlementMod.LOGGER.error("[profession-gate-route] profession={} from={} reservation={} navigation={}",
                    citizen.profession(), entity.position(), reservation, entity.settlementNavigationDiagnostics());
            for (BlockPos candidate : candidates) dumpNativePath("supply", candidate);
            for (var entrance : BlueprintTemplates.createForBuilding(warehouse).entrances()) {
                dumpNativePath("entrance", entrance.position());
            }
            int minX = warehouse.minCorner().getX() - 2;
            int maxX = warehouse.maxCorner().getX() + 8;
            int minZ = warehouse.minCorner().getZ() - 2;
            int maxZ = warehouse.maxCorner().getZ() + 2;
            for (int y = core.getY(); y <= core.getY() + 1; y++) {
                StringBuilder map = new StringBuilder();
                for (int z = minZ; z <= maxZ; z++) {
                    map.append(z - core.getZ()).append(':');
                    for (int x = minX; x <= maxX; x++) {
                        BlockPos position = new BlockPos(x, y, z);
                        char symbol;
                        if (position.equals(entity.blockPosition())) symbol = 'R';
                        else if (candidates.contains(position)) symbol = 'S';
                        else if (stockedContainers.contains(position)) symbol = 'C';
                        else if (level.getBlockEntity(position) instanceof Container) symbol = 'c';
                        else if (!level.getBlockState(position).getCollisionShape(level, position).isEmpty()) symbol = '#';
                        else symbol = '.';
                        map.append(symbol);
                    }
                    map.append('\n');
                }
                ExiledSettlementMod.LOGGER.error("[profession-gate-map] profession={} origin={} y={} xOffset={}..{} R=resident S=legalSupply C=stocked c=container #=collision\n{}",
                        citizen.profession(), core, y, minX - core.getX(), maxX - core.getX(), map);
            }
        }

        private void dumpNativePath(String kind, BlockPos candidate) {
            ExiledSettlementMod.LOGGER.error("[profession-gate-cell] profession={} kind={} candidate={} floor={} feet={} head={} footType={} headType={}",
                    citizen.profession(), kind, candidate, level.getBlockState(candidate.below()),
                    level.getBlockState(candidate), level.getBlockState(candidate.above()),
                    net.minecraft.world.level.pathfinder.WalkNodeEvaluator.getPathTypeStatic(entity, candidate),
                    net.minecraft.world.level.pathfinder.WalkNodeEvaluator.getPathTypeStatic(entity, candidate.above()));
            var path = entity.getNavigation().createPath(candidate, 0);
            List<String> nodes = new ArrayList<>();
            if (path != null) {
                for (int index = 0; index < Math.min(64, path.getNodeCount()); index++) {
                    var node = path.getNode(index);
                    nodes.add(node.x + "/" + node.y + "/" + node.z + ":" + node.type
                            + ":walk=" + node.walkedDistance + ":cost=" + node.costMalus);
                }
            }
            ExiledSettlementMod.LOGGER.error("[profession-gate-path] profession={} kind={} candidate={} standable={} heldByOther={} pathCanReach={} pathTarget={} end={} nodeCount={} nodes={}",
                    citizen.profession(), kind, candidate, entity.canStandAtPosition(candidate),
                    CitizenNavigationReservationService.stationHeldByOther(level, entity.getUUID(), candidate, level.getGameTime()),
                    path != null && path.canReach(), path == null ? null : path.getTarget(),
                    path == null ? null : path.getEndNode(), path == null ? 0 : path.getNodeCount(), nodes);
        }

        private static void forceChunks(ServerLevel level, ChunkPos first, ChunkPos last, boolean force) {
            for (int x = first.x; x <= last.x; x++) {
                for (int z = first.z; z <= last.z; z++) level.setChunkForced(x, z, force);
            }
        }
    }
}
