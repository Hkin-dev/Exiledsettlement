package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingStatus;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.building.WarehouseInventoryService;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import com.hkin.exiledsettlement.citizen.CitizenWorkService;
import com.hkin.exiledsettlement.citizen.CitizenWorkState;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A real final walking step, with no teleports or synthetic entity ticks after setup. */
@net.neoforged.fml.common.EventBusSubscriber(modid = ExiledSettlementMod.MOD_ID,
        bus = net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
@GameTestHolder("exiledsettlement_efficiency")
@PrefixGameTestTemplate(false)
public final class WarehouseEntryEfficiencyGameTests {
    private static final int CARGO = 30 * 64;
    private static int serial;
    private WarehouseEntryEfficiencyGameTests() { }

    @net.neoforged.bus.api.SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if ("efficiency".equals(System.getProperty("exiledsettlement.testSuite")))
            event.register(WarehouseEntryEfficiencyGameTests.class);
    }

    @GameTest(templateNamespace = "exiledsettlement_efficiency", template = "empty",
            batch = "warehouse_entry_walk", timeoutTicks = 480)
    public static void warehouseBoundaryArrivalWalksInsideBeforeUnloading(GameTestHelper helper) throws Exception {
        var f = fixture(helper, 0.506D);
        var level = helper.getLevel();
        var start = f.actor.position();
        helper.assertTrue(f.actor.distanceToSqr(Vec3.atBottomCenterOf(f.target)) <= 0.81D
                        && f.actor.blockPosition().getX() == f.warehouse.minCorner().getX()
                        && !WarehouseInventoryService.canInteract(level, f.warehouse, f.actor),
                "Fixture did not reproduce near-target but outside-interior arrival");
        deliver(f);
        helper.assertTrue(stored(f) == 0 && carried(f) == CARGO,
                "The entry correction remotely deposited cargo before a physical step");
        boolean[] entered = {false};
        helper.onEachTick(() -> {
            if (f.actor.isRemoved()) return;
            if (WarehouseInventoryService.canInteract(level, f.warehouse, f.actor)) entered[0] = true;
            if (level.getGameTime() % CitizenWorkService.WORK_INTERVAL_TICKS == 0) deliver(f);
            helper.assertTrue(stored(f) + carried(f) == CARGO, "Final entry step lost or duplicated cargo");
            if (stored(f) > 0) helper.assertTrue(entered[0], "Cargo was deposited before legally entering the warehouse");
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(stored(f) == CARGO,
                        "Waiting for physical entry and complete delivery: " + f.citizen.workFailure()
                                + " pos=" + f.actor.position() + " cargo=" + carried(f)))
                .thenExecute(() -> {
                    helper.assertTrue(entered[0] && f.actor.position().distanceTo(start) > 0.01D
                                    && f.citizen.workState() != CitizenWorkState.TO_WAREHOUSE && carried(f) == 0,
                            "Delivery finished without physical entry or left the cargo task unfinished");
                    ExiledSettlementMod.LOGGER.info("WAREHOUSE_BOUNDARY_ENTRY stored={} moved={} startedOutside=true enteredLegally=true state={}",
                            stored(f), f.actor.position().distanceTo(start), f.citizen.workState());
                    clean(f);
                }).thenSucceed();
    }

    @GameTest(templateNamespace = "exiledsettlement_efficiency", template = "empty",
            batch = "warehouse_entry_blocked", timeoutTicks = 160)
    public static void warehouseBlockedEntryKeepsCargoAndCannotDepositThroughWall(GameTestHelper helper) throws Exception {
        // Stay outside the future wall's collision box while still inside the old 0.9 arrival radius.
        var f = fixture(helper, 0.85D);
        var level = helper.getLevel();
        var target = WarehouseInventoryService.reserveInsertionPosition(level, f.warehouse, f.actor,
                new ItemStack(Items.RAW_IRON, 64));
        helper.assertTrue(f.target.equals(target), "Fixture did not cache its only legal interior cell");
        level.setBlock(f.target, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(f.target.above(), Blocks.STONE.defaultBlockState(), 3);
        helper.assertTrue(level.noCollision(f.actor) && !WarehouseInventoryService.canStand(level, f.target),
                "Blocked fixture overlaps the actor or leaves the destination usable");
        helper.onEachTick(() -> {
            if (f.actor.isRemoved()) return;
            if (level.getGameTime() % CitizenWorkService.WORK_INTERVAL_TICKS == 0) deliver(f);
            helper.assertTrue(stored(f) == 0 && carried(f) == CARGO
                            && f.citizen.workState() == CitizenWorkState.TO_WAREHOUSE,
                    "A blocked entry bypassed collision, deposited remotely or abandoned cargo");
        });
        helper.startSequence().thenIdle(100).thenExecute(() -> {
            helper.assertTrue(level.getBlockState(f.target).is(Blocks.STONE)
                            && level.getBlockState(f.target.above()).is(Blocks.STONE),
                    "Entry movement removed a blocking wall");
            ExiledSettlementMod.LOGGER.info("WAREHOUSE_BOUNDARY_BLOCKED stored={} cargo={} wallPreserved=true state={}",
                    stored(f), carried(f), f.citizen.workState());
            clean(f);
        }).thenSucceed();
    }

    private record Fixture(GameTestHelper helper, SettlementSavedData data, SettlementRecord settlement,
                           BuildingRecord warehouse, CitizenRecord citizen, SettlementCitizenEntity actor,
                           BlockPos target, Set<ChunkPos> chunks, Method deliver) { }

    private static Fixture fixture(GameTestHelper helper, double centerDistance) throws Exception {
        var level = helper.getLevel();
        var target = helper.absolutePos(new BlockPos(120000 + serial++ * 64, 4, 120000));
        Set<ChunkPos> chunks = new HashSet<>();
        for (var p : BlockPos.betweenClosed(target.offset(-4, -1, -4), target.offset(4, -1, 4))) {
            var chunk = new ChunkPos(p);
            if (chunks.add(chunk)) level.setChunkForced(chunk.x, chunk.z, true);
            level.getChunkAt(p);
            level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
            for (int y = 1; y <= 4; y++) level.setBlock(p.above(y), Blocks.AIR.defaultBlockState(), 2);
        }
        var settlement = new SettlementRecord(UUID.randomUUID(), "Warehouse final entry", target.west(4), UUID.randomUUID());
        // Exactly one walkable interior column removes random station selection from the regression.
        var warehouse = settlement.addBuilding(BuildingType.WAREHOUSE, target, Direction.WEST,
                target.offset(-1, 0, -1), target.offset(1, 3, 1), 0, 1);
        warehouse.updateFunction(true, BuildingStatus.OPERATIONAL, 1, List.of());
        WarehouseInventoryService.stock(level, warehouse);
        var citizen = settlement.citizens().iterator().next();
        citizen.insertIntoInventory(new ItemStack(Items.RAW_IRON, CARGO));
        citizen.startInventoryDelivery(warehouse.id(), citizen.findInventorySlot(stack -> stack.is(Items.RAW_IRON)));
        var actor = ModEntities.SETTLEMENT_CITIZEN.get().create(level);
        helper.assertTrue(actor != null, "Missing resident entity");
        actor.moveTo(target.getX() + 0.5D - centerDistance, target.getY(), target.getZ() + 0.5D, 0, 0);
        actor.setOnGround(true);
        actor.setNoAi(false);
        helper.assertTrue(level.addFreshEntity(actor), "Resident entity was not added to the world");
        citizen.updateProjection(actor.getUUID(), actor.blockPosition());
        level.setDayTime(6000);
        var deliver = CitizenWorkService.class.getDeclaredMethod("deliverWarehouse", ServerLevel.class,
                SettlementSavedData.class, SettlementRecord.class, CitizenRecord.class, SettlementCitizenEntity.class);
        deliver.setAccessible(true);
        return new Fixture(helper, new SettlementSavedData(), settlement, warehouse, citizen, actor, target, chunks, deliver);
    }

    private static void deliver(Fixture f) {
        try { f.deliver.invoke(null, f.helper.getLevel(), f.data, f.settlement, f.citizen, f.actor); }
        catch (ReflectiveOperationException failure) { throw new RuntimeException(failure); }
    }
    private static int stored(Fixture f) { return WarehouseInventoryService.countItem(f.helper.getLevel(), f.warehouse, Items.RAW_IRON); }
    private static int carried(Fixture f) { return f.citizen.inventory().stream().filter(s -> s.is(Items.RAW_IRON)).mapToInt(ItemStack::getCount).sum(); }
    private static void clean(Fixture f) {
        f.actor.discard();
        f.settlement.deactivate();
        for (var chunk : f.chunks) f.helper.getLevel().setChunkForced(chunk.x, chunk.z, false);
    }
}
