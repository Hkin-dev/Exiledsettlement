package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingStatus;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.building.WarehouseInventoryService;
import com.hkin.exiledsettlement.citizen.CitizenEquipmentService;
import com.hkin.exiledsettlement.citizen.CitizenProfession;
import com.hkin.exiledsettlement.citizen.CitizenProjectionService;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import com.hkin.exiledsettlement.citizen.CitizenWorkService;
import com.hkin.exiledsettlement.citizen.CitizenWorkState;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LogisticsWorkGameTests {
    private LogisticsWorkGameTests() { }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "resident_logistics", timeoutTicks = 100)
    public static void professionNameplateTracksAssignmentWithoutRenamingCitizen(GameTestHelper helper) {
        Fixture fixture = fixture(helper, 10);
        String originalName = fixture.citizen.name();
        for (CitizenProfession profession : CitizenProfession.values()) {
            fixture.citizen.assignProfession(profession, null);
            CitizenProjectionService.ensureSettlementCitizens(helper.getLevel(), fixture.settlement);
            var expected = net.minecraft.network.chat.Component.literal("[")
                    .append(net.minecraft.network.chat.Component.translatable(profession.translationKey()))
                    .append("] ").append(net.minecraft.network.chat.Component.literal(originalName));
            helper.assertTrue(expected.equals(fixture.entity.getCustomName()), "Nameplate did not follow profession");
            helper.assertTrue(originalName.equals(fixture.citizen.name()), "Nameplate modified persistent citizen name");
        }
        cleanup(helper, fixture);
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "resident_logistics", timeoutTicks = 640)
    public static void missingEquipmentRecoversAfterOtherWarehouseReceivesStoneTool(GameTestHelper helper) {
        Fixture fixture = fixture(helper, 0);
        BuildingRecord emptyWarehouse = warehouse(helper, fixture, fixture.core.east(4));
        BuildingRecord suppliedWarehouse = warehouse(helper, fixture, fixture.core.east(10));
        fixture.citizen.assignProfession(CitizenProfession.FARMER, null);
        fixture.citizen.startEquipmentFetch(emptyWarehouse.id(), emptyWarehouse.position());
        long started = helper.getLevel().getGameTime();
        boolean[] stocked = {false};
        helper.startSequence().thenWaitUntil(() -> {
            long elapsed = helper.getLevel().getGameTime() - started;
            if (elapsed >= 40 && !stocked[0]) {
                container(helper, suppliedWarehouse).setItem(0, new ItemStack(Items.STONE_HOE));
                stocked[0] = true;
            }
            tick(helper, fixture, elapsed);
            helper.assertTrue(fixture.citizen.hasInventoryItem(stack -> stack.is(Items.STONE_HOE)),
                    "Resident did not walk to the newly stocked warehouse and receive its usable stone hoe: "
                            + fixture.citizen.workFailure() + " @ " + fixture.entity.blockPosition());
            helper.assertTrue(fixture.entity.distanceToSqr(fixture.start.getX() + 0.5D,
                            fixture.start.getY(), fixture.start.getZ() + 0.5D) > 16.0D,
                    "Equipment test bypassed real resident movement");
            helper.assertTrue(WarehouseInventoryService.countItem(helper.getLevel(), suppliedWarehouse, Items.STONE_HOE) == 0
                            && CitizenEquipmentService.missingEquipment(fixture.citizen) == null,
                    "Stone tool was duplicated or did not fulfill the profession requirement");
        }).thenExecute(() -> cleanup(helper, fixture)).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "resident_rest_logistics", timeoutTicks = 640)
    public static void fullWarehouseReportsCapacityAndResidentDeliversToFreedAlternative(GameTestHelper helper) {
        Fixture fixture = fixture(helper, 1);
        helper.getLevel().setDayTime(23000L + CitizenWorkService.scheduleOffset(fixture.citizen));
        BuildingRecord near = warehouse(helper, fixture, fixture.core.east(4));
        BuildingRecord far = warehouse(helper, fixture, fixture.core.east(10));
        for (BuildingRecord warehouse : List.of(near, far)) {
            Container container = container(helper, warehouse);
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                container.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            }
        }
        fixture.citizen.insertIntoInventory(new ItemStack(Items.OAK_PLANKS, 32));
        fixture.citizen.startInventoryDelivery(near.id(), fixture.citizen.findInventorySlot(stack -> stack.is(Items.OAK_PLANKS)));
        CitizenWorkService.tickSettlement(helper.getLevel(), fixture.data, fixture.settlement);
        helper.assertTrue("OUTPUT_FULL".equals(fixture.citizen.workFailure()),
                "A full warehouse was incorrectly reported as a busy interaction station");
        long started = helper.getLevel().getGameTime();
        boolean[] freed = {false};
        helper.startSequence().thenWaitUntil(() -> {
            helper.getLevel().setDayTime(23000L + CitizenWorkService.scheduleOffset(fixture.citizen));
            long elapsed = helper.getLevel().getGameTime() - started;
            if (elapsed >= 40 && !freed[0]) {
                container(helper, far).setItem(0, ItemStack.EMPTY);
                freed[0] = true;
            }
            tick(helper, fixture, elapsed);
            helper.assertTrue(WarehouseInventoryService.countItem(helper.getLevel(), far, Items.OAK_PLANKS) == 32,
                    "Resident did not deliver to the alternative warehouse after capacity became available: "
                            + fixture.citizen.workFailure() + " @ " + fixture.entity.blockPosition());
            helper.assertTrue(!fixture.citizen.hasInventoryItem(stack -> stack.is(Items.OAK_PLANKS)),
                    "Delivered planks remained duplicated in the resident backpack");
        }).thenExecute(() -> cleanup(helper, fixture)).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "resident_logistics")
    public static void unrelatedPartialStackDoesNotTrapOutputCollector(GameTestHelper helper) {
        Fixture fixture = fixture(helper, 2);
        BuildingRecord sink = warehouse(helper, fixture, fixture.core.east(10));
        BuildingRecord source = warehouse(helper, fixture, fixture.core.east(4));
        container(helper, source).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        fixture.citizen.insertIntoInventory(new ItemStack(Items.DIRT, 35 * 64 + 1));
        helper.assertTrue(fixture.citizen.hasInventoryCapacity()
                        && fixture.citizen.inventoryCapacityFor(new ItemStack(Items.COBBLESTONE)) == 0,
                "The regression did not create the unrelated partial-stack condition");
        fixture.citizen.startContainerHaul(source.id(), sink.id());
        CitizenWorkService.tickSettlement(helper.getLevel(), fixture.data, fixture.settlement);
        helper.assertTrue(fixture.citizen.workState() == CitizenWorkState.TO_WAREHOUSE,
                "Output collection kept retrying a material that cannot fit in the backpack");
        helper.assertTrue(WarehouseInventoryService.countItem(helper.getLevel(), source, Items.COBBLESTONE) == 64,
                "A failed output collection altered or lost the source material");
        cleanup(helper, fixture);
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "resident_bulk_rest_delivery", timeoutTicks = 640)
    public static void restArrivalDepositsAllHarvestStacksAndKeepsRationsAndTools(GameTestHelper helper) {
        Fixture f = fixture(helper, 14);
        BuildingRecord sink = warehouse(helper, f, f.core.east(10));
        f.citizen.insertIntoInventory(new ItemStack(Items.BREAD, 16));
        f.citizen.insertIntoInventory(new ItemStack(Items.CARROT, 32));
        f.citizen.insertIntoInventory(new ItemStack(Items.OAK_LOG, 24));
        f.citizen.insertIntoInventory(new ItemStack(Items.COBBLESTONE, 17));
        f.citizen.insertIntoInventory(new ItemStack(Items.STONE_AXE));
        long started = helper.getLevel().getGameTime();
        helper.startSequence().thenWaitUntil(() -> {
            helper.getLevel().setDayTime(23000L + CitizenWorkService.scheduleOffset(f.citizen));
            tick(helper, f, helper.getLevel().getGameTime() - started);
            int logs = WarehouseInventoryService.countItem(helper.getLevel(), sink, Items.OAK_LOG);
            // At the first successful deposit, all reachable cargo must have arrived in that cycle.
            if (logs > 0) {
                helper.assertTrue(logs == 24
                                && WarehouseInventoryService.countItem(helper.getLevel(), sink, Items.CARROT) == 32
                                && WarehouseInventoryService.countItem(helper.getLevel(), sink, Items.COBBLESTONE) == 17,
                        "Rest arrival deposited only one backpack stack");
            }
            helper.assertTrue(logs == 24, "Waiting for physical rest delivery: " + f.citizen.workFailure());
            helper.assertTrue(f.entity.distanceToSqr(f.start.getX() + 0.5D, f.start.getY(), f.start.getZ() + 0.5D) > 16,
                    "Rest delivery skipped real walking");
            helper.assertTrue(f.citizen.hasInventoryItem(stack -> stack.is(Items.STONE_AXE))
                            && f.citizen.inventory().stream().filter(stack -> stack.is(Items.BREAD)).mapToInt(ItemStack::getCount).sum() == 16
                            && !f.citizen.hasInventoryItem(stack -> stack.is(Items.OAK_LOG) || stack.is(Items.CARROT) || stack.is(Items.COBBLESTONE)),
                    "Bulk delivery lost personal supplies or duplicated cargo");
        }).thenExecute(() -> cleanup(helper, f)).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "resident_bulk_rest_capacity")
    public static void bulkRestDeliveryPreservesCargoWhenWarehouseOnlyAcceptsPart(GameTestHelper helper) {
        Fixture f = fixture(helper, 15);
        helper.getLevel().setDayTime(23000L + CitizenWorkService.scheduleOffset(f.citizen));
        BuildingRecord sink = warehouse(helper, f, f.core.east(2));
        Container storage = container(helper, sink);
        for (int i = 0; i < storage.getContainerSize(); i++) storage.setItem(i, new ItemStack(Items.DIRT, 64));
        storage.setItem(0, new ItemStack(Items.OAK_LOG, 48));
        f.citizen.insertIntoInventory(new ItemStack(Items.OAK_LOG, 32));
        f.citizen.insertIntoInventory(new ItemStack(Items.COBBLESTONE, 20));
        f.citizen.startInventoryDelivery(sink.id(), f.citizen.findInventorySlot(stack -> stack.is(Items.OAK_LOG)));
        CitizenWorkService.tickSettlement(helper.getLevel(), f.data, f.settlement);
        helper.assertTrue(WarehouseInventoryService.countItem(helper.getLevel(), sink, Items.OAK_LOG) == 64
                        && f.citizen.inventory().stream().filter(stack -> stack.is(Items.OAK_LOG)).mapToInt(ItemStack::getCount).sum() == 16
                        && f.citizen.inventory().stream().filter(stack -> stack.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum() == 20,
                "Partial bulk insertion lost or duplicated items");
        CitizenWorkService.tickSettlement(helper.getLevel(), f.data, f.settlement);
        helper.assertTrue("OUTPUT_FULL".equals(f.citizen.workFailure()), "Remaining cargo lacks a full-warehouse explanation");
        cleanup(helper, f);
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "resident_logistics")
    public static void farmerRequestsHoeBeforeTakingFarmWork(GameTestHelper helper) {
        Fixture fixture = fixture(helper, 3);
        BlockPos ground = fixture.core.east(5).below();
        helper.getLevel().setBlock(ground, Blocks.FARMLAND.defaultBlockState(), 3);
        BuildingRecord farm = fixture.settlement.addBuilding(BuildingType.FARM_FIELD, ground);
        farm.updateFunction(true, BuildingStatus.OPERATIONAL, 2, List.of(ground));
        fixture.citizen.assignProfession(CitizenProfession.FARMER, farm.id());
        CitizenWorkService.tickSettlement(helper.getLevel(), fixture.data, fixture.settlement);
        helper.assertTrue(fixture.citizen.workState() == CitizenWorkState.TO_EQUIPMENT,
                "Farmer reserved farm work before obtaining a hoe");
        cleanup(helper, fixture);
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "resident_logistics")
    public static void fisherWithoutRodReturnsToEquipmentDispatchBeforeProducing(GameTestHelper helper) {
        Fixture fixture = fixture(helper, 4);
        BuildingRecord hut = fixture.settlement.addBuilding(BuildingType.FISHING_HUT, fixture.core.east(5));
        hut.updateFunction(true, BuildingStatus.OPERATIONAL, 1, List.of(hut.position()));
        fixture.citizen.assignProfession(CitizenProfession.FISHER, hut.id());
        fixture.citizen.startTask(CitizenWorkState.TO_FISH, fixture.entity.blockPosition(), null, null);
        CitizenWorkService.tickSettlement(helper.getLevel(), fixture.data, fixture.settlement);
        helper.assertTrue(fixture.citizen.workState() == CitizenWorkState.TO_EQUIPMENT
                        && !fixture.citizen.hasInventoryItem(stack -> stack.is(Items.COD) || stack.is(Items.SALMON)),
                "Fisher without a rod remained in the production chain or created a free catch");
        cleanup(helper, fixture);
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "resident_logistics")
    public static void forestryAssignmentsAndLegacySaveUseTreeFarmBuildingSlot(GameTestHelper helper) {
        Fixture fixture = fixture(helper, 5);
        BlockPos tree = fixture.core.east(5);
        BuildingRecord treeFarm = fixture.settlement.addBuilding(BuildingType.TREE_FARM, tree, Direction.NORTH,
                tree.offset(-2, 0, -2), tree.offset(2, 8, 2), 0);
        treeFarm.updateFunction(true, BuildingStatus.OPERATIONAL, 1, List.of(tree));
        helper.getLevel().setBlock(tree.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
        helper.getLevel().setBlock(tree, Blocks.OAK_LOG.defaultBlockState(), 3);
        fixture.citizen.assignProfession(CitizenProfession.LUMBERJACK, treeFarm.id());
        fixture.citizen.insertIntoInventory(new ItemStack(Items.STONE_AXE));
        CitizenWorkService.tickSettlement(helper.getLevel(), fixture.data, fixture.settlement);
        helper.assertTrue(fixture.citizen.workState() == CitizenWorkState.TO_FOREST
                        && treeFarm.id().equals(fixture.citizen.targetBuildingId())
                        && fixture.citizen.targetBlueprintId() == null,
                "Logging assignment stored the tree-farm identity as a construction blueprint");

        fixture.citizen.clearWork();
        fixture.entity.stopSettlementNavigation();
        helper.getLevel().setBlock(tree, Blocks.AIR.defaultBlockState(), 3);
        CitizenWorkService.tickSettlement(helper.getLevel(), fixture.data, fixture.settlement);
        helper.assertTrue(fixture.citizen.workState() == CitizenWorkState.TO_FOREST
                        && treeFarm.id().equals(fixture.citizen.targetBuildingId())
                        && fixture.citizen.targetBlueprintId() == null,
                "Planting assignment stored the tree-farm identity as a construction blueprint");

        fixture.citizen.clearWork();
        fixture.citizen.startTask(CitizenWorkState.TO_FOREST, tree, null, treeFarm.id());
        CitizenRecord restored = CitizenRecord.load(fixture.citizen.save(helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        helper.assertTrue(restored.workState() == CitizenWorkState.TO_FOREST
                        && treeFarm.id().equals(restored.targetBuildingId()) && restored.targetBlueprintId() == null
                        && restored.hasInventoryItem(stack -> stack.is(Items.STONE_AXE)),
                "Legacy forestry work did not restore its tree-farm link without losing the backpack");
        cleanup(helper, fixture);
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "resident_logistics")
    public static void hunterCannotCollectDistantDeadPreyLootWithoutWalkingThere(GameTestHelper helper) {
        Fixture fixture = fixture(helper, 6);
        BuildingRecord lodge = fixture.settlement.addBuilding(BuildingType.HUNTING_LODGE, fixture.core.west(4));
        lodge.updateFunction(true, BuildingStatus.OPERATIONAL, 1, List.of());
        fixture.citizen.assignProfession(CitizenProfession.HUNTER, lodge.id());
        fixture.citizen.insertIntoInventory(new ItemStack(Items.IRON_SWORD));
        fixture.citizen.insertIntoInventory(new ItemStack(Items.LEAD));
        BlockPos deathPosition = fixture.core.east(12);
        ItemEntity meat = new ItemEntity(helper.getLevel(), deathPosition.getX() + 0.5D,
                deathPosition.getY(), deathPosition.getZ() + 0.5D, new ItemStack(Items.BEEF, 3));
        helper.getLevel().addFreshEntity(meat);
        fixture.citizen.startTask(CitizenWorkState.TO_HUNT, deathPosition, UUID.randomUUID(), null);
        CitizenWorkService.tickSettlement(helper.getLevel(), fixture.data, fixture.settlement);
        helper.assertTrue(meat.isAlive() && meat.getItem().getCount() == 3
                        && !fixture.citizen.hasInventoryItem(stack -> stack.is(Items.BEEF))
                        && fixture.citizen.workState() == CitizenWorkState.TO_HUNT,
                "Hunter remotely collected dead-prey loot instead of walking to the recorded location");
        meat.discard();
        cleanup(helper, fixture);
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "resident_logistics")
    public static void foresterDoesNotReserveUnplantableStonePlot(GameTestHelper helper) {
        Fixture fixture = fixture(helper, 11);
        BlockPos planting = fixture.core.east(5);
        BuildingRecord treeFarm = fixture.settlement.addBuilding(BuildingType.TREE_FARM, planting, Direction.NORTH,
                planting.offset(-2, 0, -2), planting.offset(2, 8, 2), 0);
        treeFarm.updateFunction(true, BuildingStatus.OPERATIONAL, 1, List.of(planting));
        fixture.citizen.assignProfession(CitizenProfession.LUMBERJACK, treeFarm.id());
        fixture.citizen.insertIntoInventory(new ItemStack(Items.STONE_AXE));
        CitizenWorkService.tickSettlement(helper.getLevel(), fixture.data, fixture.settlement);
        helper.assertTrue(fixture.citizen.workState() != CitizenWorkState.TO_FOREST,
                "Forester reserved a stone plot where no supported sapling can survive");
        helper.assertTrue(helper.getLevel().getBlockState(planting).isAir()
                        && helper.getLevel().getBlockState(planting.below()).is(Blocks.STONE_BRICKS),
                "Forestry replaced non-plantable ground to force a job");
        cleanup(helper, fixture);
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "resident_logistics")
    public static void foresterDoesNotOverwriteChangedPlantingTarget(GameTestHelper helper) {
        Fixture fixture = fixture(helper, 12);
        BlockPos planting = fixture.core.east(3);
        BuildingRecord treeFarm = fixture.settlement.addBuilding(BuildingType.TREE_FARM, planting, Direction.NORTH,
                planting.offset(-2, 0, -2), planting.offset(2, 8, 2), 0);
        treeFarm.updateFunction(true, BuildingStatus.OPERATIONAL, 1, List.of(planting));
        helper.getLevel().setBlock(planting.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
        fixture.citizen.assignProfession(CitizenProfession.LUMBERJACK, treeFarm.id());
        fixture.citizen.insertIntoInventory(new ItemStack(Items.STONE_AXE));
        fixture.citizen.startForestry(treeFarm.id(), planting);
        helper.getLevel().setBlock(planting, Blocks.TORCH.defaultBlockState(), 3);
        CitizenWorkService.tickSettlement(helper.getLevel(), fixture.data, fixture.settlement);
        helper.assertTrue(helper.getLevel().getBlockState(planting).is(Blocks.TORCH)
                        && fixture.citizen.workState() != CitizenWorkState.TO_FOREST,
                "A planting task replaced a player's changed block or remained stuck on it");
        cleanup(helper, fixture);
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "resident_logistics", timeoutTicks = 500)
    public static void foresterRollsBackDarkOakReplantWhenProtectionDeniesSecondSapling(GameTestHelper helper) {
        Fixture fixture = fixture(helper, 13);
        BlockPos tree = fixture.core.east(3);
        BuildingRecord treeFarm = fixture.settlement.addBuilding(BuildingType.TREE_FARM, tree, Direction.NORTH,
                tree.offset(-2, 0, -2), tree.offset(4, 8, 4), 0);
        treeFarm.updateFunction(true, BuildingStatus.OPERATIONAL, 1, List.of(tree));
        List<BlockPos> plot = List.of(tree, tree.east(), tree.south(), tree.south().east());
        plot.forEach(pos -> helper.getLevel().setBlock(pos.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3));
        helper.getLevel().setBlock(tree, Blocks.DARK_OAK_LOG.defaultBlockState(), 3);
        fixture.citizen.assignProfession(CitizenProfession.LUMBERJACK, treeFarm.id());
        fixture.citizen.insertIntoInventory(new ItemStack(Items.STONE_AXE));
        fixture.citizen.startForestry(treeFarm.id(), tree);
        AtomicInteger denials = new AtomicInteger();
        Consumer<BlockEvent.EntityPlaceEvent> listener = event -> {
            if (event.getLevel() == helper.getLevel() && event.getPos().equals(tree.east())
                    // NeoForge reports the replaced snapshot for non-player
                    // actors. Inspect the attempted world state at this event.
                    && event.getLevel().getBlockState(event.getPos()).is(Blocks.DARK_OAK_SAPLING)) {
                denials.incrementAndGet();
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        // Release the temporary listener even if an asynchronous assertion fails.
        helper.runAtTickTime(480, () -> {
            NeoForge.EVENT_BUS.unregister(listener);
            cleanup(helper, fixture);
        });
        long started = helper.getLevel().getGameTime();
        helper.startSequence().thenWaitUntil(() -> {
            tick(helper, fixture, helper.getLevel().getGameTime() - started);
            helper.assertTrue(denials.get() > 0, "Waiting for forestry protection cancellation: "
                    + fixture.citizen.workState() + " / " + fixture.citizen.workFailure());
            helper.assertTrue(plot.stream().allMatch(pos -> helper.getLevel().getBlockState(pos).isAir()),
                    "Denied dark-oak replant left a partial two-by-two plot");
            helper.assertTrue(fixture.citizen.inventory().stream().filter(stack -> stack.is(Items.DARK_OAK_LOG))
                            .mapToInt(ItemStack::getCount).sum() == 1,
                    "Replant rollback lost or duplicated the already harvested log");
        }).thenExecute(() -> {
            NeoForge.EVENT_BUS.unregister(listener);
            cleanup(helper, fixture);
        }).thenSucceed();
    }

    private static Fixture fixture(GameTestHelper helper, int scenario) {
        BlockPos core = helper.absolutePos(new BlockPos(4096 + scenario * 96, 10, 8));
        for (BlockPos ground : BlockPos.betweenClosed(core.offset(-18, -1, -18), core.offset(18, -1, 18))) {
            helper.getLevel().setBlock(ground, Blocks.STONE_BRICKS.defaultBlockState(), 2);
        }
        ChunkPos min = new ChunkPos(core.offset(-20, 0, -20));
        ChunkPos max = new ChunkPos(core.offset(20, 0, 20));
        for (int x = min.x; x <= max.x; x++) for (int z = min.z; z <= max.z; z++) {
            helper.getLevel().setChunkForced(x, z, true);
        }
        helper.getLevel().setDayTime(6000L);
        SettlementRecord settlement = new SettlementRecord(UUID.randomUUID(), "Resident logistics regression", core, UUID.randomUUID());
        CitizenRecord citizen = settlement.citizens().iterator().next();
        for (CitizenRecord other : settlement.citizens()) if (other != citizen) other.markDead(core);
        CitizenProjectionService.ensureSettlementCitizens(helper.getLevel(), settlement);
        SettlementCitizenEntity entity = CitizenProjectionService.findProjection(helper.getLevel(), citizen);
        helper.assertTrue(entity != null, "Resident projection was not created");
        // These are single-container logistics fixtures, not complete warehouse
        // templates with distant entrance waiting areas. Begin inside the normal
        // 12-block station-search window (10 blocks from the destination barrel),
        // then let vanilla entity ticks cover the remaining physical approach.
        // Long-distance entry through a real warehouse belongs to building tests.
        BlockPos start = core;
        entity.moveTo(start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D, 0.0F, 0.0F);
        entity.setOnGround(true);
        citizen.updateProjection(entity.getUUID(), start);
        return new Fixture(new SettlementSavedData(), settlement, citizen, entity, core, start, min, max);
    }

    private static BuildingRecord warehouse(GameTestHelper helper, Fixture fixture, BlockPos position) {
        helper.getLevel().setBlock(position, Blocks.BARREL.defaultBlockState(), 3);
        BuildingRecord warehouse = fixture.settlement.addBuilding(BuildingType.WAREHOUSE, position);
        warehouse.updateFunction(true, BuildingStatus.OPERATIONAL, 27, List.of(position));
        return warehouse;
    }

    private static Container container(GameTestHelper helper, BuildingRecord building) {
        return (Container) helper.getLevel().getBlockEntity(building.position());
    }

    private static void tick(GameTestHelper helper, Fixture fixture, long elapsed) {
        if (elapsed % CitizenWorkService.WORK_INTERVAL_TICKS != 0L) return;
        fixture.citizen.updateProjection(fixture.entity.getUUID(), fixture.entity.blockPosition());
        CitizenWorkService.tickSettlement(helper.getLevel(), fixture.data, fixture.settlement);
    }

    private static void cleanup(GameTestHelper helper, Fixture fixture) {
        fixture.entity.discard();
        for (int x = fixture.min.x; x <= fixture.max.x; x++) for (int z = fixture.min.z; z <= fixture.max.z; z++) {
            helper.getLevel().setChunkForced(x, z, false);
        }
    }

    private record Fixture(SettlementSavedData data, SettlementRecord settlement, CitizenRecord citizen,
                           SettlementCitizenEntity entity, BlockPos core, BlockPos start, ChunkPos min, ChunkPos max) { }
}
