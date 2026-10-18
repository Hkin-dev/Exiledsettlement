package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingStatus;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.CitizenActivity;
import com.hkin.exiledsettlement.citizen.CitizenNavigationReservationService;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import com.hkin.exiledsettlement.citizen.CitizenWorkService;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** Physical bed routes in isolated loaded areas, without touching player saves. */
@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BedRoutingGameTests {
    private BedRoutingGameTests() {}

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "sleep_life_interruptions", timeoutTicks = 600)
    public static void hungrySleeperWakesWalksToRealFoodAndReturnsToBed(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 6, 18, 600);
        BlockPos store = f.origin.east(2);
        f.level.setBlock(store, Blocks.BARREL.defaultBlockState(), 3);
        var warehouse = f.settlement.addBuilding(BuildingType.WAGON, store);
        warehouse.updateFunction(true, BuildingStatus.OPERATIONAL, 27, List.of(store));
        var inventory = (net.minecraft.world.Container) f.level.getBlockEntity(store);
        inventory.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD, 16));
        f.entity.moveTo(f.bed.getX() - .5D, f.bed.getY(), f.bed.getZ() + .5D);
        f.entity.setOnGround(true); f.route();
        helper.assertTrue(f.entity.isSleeping(), "Fixture never entered real bed sleep");
        f.citizen.adjustNeeds(-45, 0, 0);
        com.hkin.exiledsettlement.settlement.CommunityLifeService.personal(f.level, f.data, f.settlement, f.citizen, f.entity);
        helper.assertTrue(!f.entity.isSleeping() && f.entity.getPose() == net.minecraft.world.entity.Pose.STANDING,
                "Night food task moved a resident without ending sleep");
        helper.assertTrue(!f.level.getBlockState(f.bed).getValue(BedBlock.OCCUPIED), "Waking left the bed occupied");
        boolean[] visited = {false};
        helper.onEachTick(() -> {
            if (f.finished) return;
            f.citizen.updateProjection(f.entity.getUUID(), f.entity.blockPosition());
            if (com.hkin.exiledsettlement.building.WarehouseInventoryService.canInteract(f.level,warehouse,f.entity)) visited[0] = true;
            if (helper.getTick() % 8 == 0) {
                boolean needs = com.hkin.exiledsettlement.settlement.CommunityLifeService.personal(
                        f.level, f.data, f.settlement, f.citizen, f.entity);
                if (!needs) f.route();
            }
            if (f.entity.isSleeping()) helper.assertTrue(f.entity.distanceToSqr(f.bed.getCenter()) < 2,
                    "Resident fell asleep on the storage floor");
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(visited[0] && f.citizen.nutrition() > 70 && f.entity.isSleeping(),
                        "Resident did not fetch, eat and walk back to sleep: " + f.citizen.activity() + "/" + f.citizen.workFailure()))
                .thenExecute(() -> {
                    helper.assertTrue(inventory.getItem(0).getCount() == 0
                                    && com.hkin.exiledsettlement.settlement.CommunityLifeService.carriedFood(f.citizen) == 15
                                    && f.settlement.community().mealsEaten == 1,
                            "Night food was free, duplicated or not consumed");
                    helper.assertTrue(f.entity.getSleepingPos().orElseThrow().equals(f.bed), "Returned to wrong bed");
                    f.finish();
                }).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "sleep_life_interruptions", timeoutTicks = 600)
    public static void staleSleepAwayFromHomeWakesInPlaceAndWalksBack(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 7, 24, 600);
        f.entity.moveTo(f.bed.getX() - .5D, f.bed.getY(), f.bed.getZ() + .5D);
        f.entity.setOnGround(true); f.route();
        helper.assertTrue(f.entity.isSleeping(), "Fixture never entered bed");
        // Reproduce an old entity retaining its bed identity after moving to a remote stockroom.
        f.entity.moveTo(f.origin.getX() + 2.5D, f.origin.getY(), f.origin.getZ() + .5D);
        var stranded = f.entity.position(); f.route();
        helper.assertTrue(!f.entity.isSleeping() && f.entity.getPose() == net.minecraft.world.entity.Pose.STANDING,
                "Stale sleep at a remote floor was mistaken for resting at home");
        helper.assertTrue(f.entity.position().distanceToSqr(stranded) < .01D, "Recovering old sleep teleported the resident home");
        helper.assertTrue(!f.level.getBlockState(f.bed).getValue(BedBlock.OCCUPIED), "Stale sleep left a ghost bed reservation");
        f.untilSleeping();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "sleep_life_interruptions", timeoutTicks = 40)
    public static void coldAndEvacuationWakeBeforeUsingFuelOrMoving(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 8, 18, 40);
        f.entity.moveTo(f.bed.getX() - .5D, f.bed.getY(), f.bed.getZ() + .5D);
        f.entity.setOnGround(true); f.route();
        helper.assertTrue(f.entity.isSleeping(), "Fixture did not sleep");
        f.citizen.adjustNeeds(0,-80,0);
        f.citizen.insertIntoInventory(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CHARCOAL));
        long day = com.hkin.exiledsettlement.settlement.CommunityLifeService.day(f.level);
        f.settlement.community().coldFrom=day; f.settlement.community().coldUntil=day+1;
        com.hkin.exiledsettlement.settlement.CommunityLifeService.personal(f.level,f.data,f.settlement,f.citizen,f.entity);
        helper.assertTrue(!f.entity.isSleeping() && f.citizen.warmth()==65 && f.settlement.community().coldFuelBurned==1
                && f.citizen.inventory().stream().noneMatch(s->s.is(net.minecraft.world.item.Items.CHARCOAL)),
                "Cold sleeper did not wake and consume real carried fuel");
        f.entity.setOnGround(true); f.route();
        helper.assertTrue(f.entity.isSleeping(), "Warm resident could not return to bed");
        f.settlement.community().evacuation=true; f.settlement.community().rally=f.origin.east(2);
        com.hkin.exiledsettlement.settlement.CommunityLifeService.personal(f.level,f.data,f.settlement,f.citizen,f.entity);
        helper.assertTrue(!f.entity.isSleeping() && f.entity.getPose()==net.minecraft.world.entity.Pose.STANDING
                && !f.level.getBlockState(f.bed).getValue(BedBlock.OCCUPIED), "Evacuation left a sleeping pose or occupied bed");
        f.finish();helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "personal_bed_routes", timeoutTicks = 1000)
    public static void distantResidentWalksHomeAndSleeps(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 0, 60, 1000);
        f.untilSleeping();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "personal_bed_routes", timeoutTicks = 240)
    public static void doorTrafficWaitIsNotAnUnreachableBed(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 1, 10, 240);
        BlockPos door = f.origin.east(5);
        f.level.setBlock(door, Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.EAST), 2);
        f.level.setBlock(door.above(), Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.EAST)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
        UUID other = UUID.randomUUID();
        helper.assertTrue(CitizenNavigationReservationService.requestPassage(
                f.level, other, door, Direction.WEST, f.level.getGameTime()), "Cannot reserve the test door");
        f.route();
        helper.assertTrue(f.citizen.activity() == CitizenActivity.RETURNING_HOME
                        && !f.citizen.workFailure().equals("BED_UNREACHABLE"),
                "Door queue was reported as an unreachable bed instead of waiting to return home");
        CitizenNavigationReservationService.releaseAll(f.level, other);
        f.untilSleeping();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "personal_bed_routes", timeoutTicks = 240)
    public static void carpetBesideBedAllowsActualSleep(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 2, 10, 240);
        BlockPos side = f.bed.west();
        f.level.setBlock(side, Blocks.WHITE_CARPET.defaultBlockState(), 2);
        // All bed-side cells have carpet, with enough actual headroom to use the bed.
        for (BlockPos p : List.of(f.bed.west(), f.bed.north(), f.bed.east(),
                f.bed.south().east(), f.bed.south(2), f.bed.south().west()))
            f.level.setBlock(p, Blocks.WHITE_CARPET.defaultBlockState(), 2);
        helper.assertTrue(!f.entity.canStandAtPosition(side) && f.entity.canUseBedStation(side),
                "Carpet must be accepted as a real bed-side standing surface");
        f.untilSleeping();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "personal_bed_routes", timeoutTicks = 100)
    public static void recoveryCanFirstMoveAwayFromDistantBed(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 3, 60, 100);
        BlockPos start = f.entity.blockPosition();
        // Only east is walkable. The bed is west of this local dead end.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx >= 0 && dz == 0) continue;
                f.level.setBlock(start.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 2);
                f.level.setBlock(start.offset(dx, 1, dz), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        double initialX = f.entity.getX();
        helper.assertTrue(f.entity.beginBedRecovery(start.west(40), 0.7D),
                "Bed recovery rejected the only real detour because it initially moves away");
        helper.assertTrue(f.entity.getX() == initialX, "Recovery teleported the resident");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                f.entity.getX() > initialX + 0.35D, "Recovery did not make a physical detour"))
                .thenExecute(f::finish).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "personal_bed_routes", timeoutTicks = 40)
    public static void nearbyBedBehindWallDoesNotCauseRemoteSleep(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 4, 10, 40);
        f.entity.moveTo(f.bed.getX() - 1.5D, f.bed.getY(), f.bed.getZ() + 0.5D, 0, 0);
        for (int dz = -4; dz <= 4; dz++) {
            for (int dy = 0; dy <= 3; dy++)
                f.level.setBlock(f.bed.offset(-1, dy, dz), Blocks.STONE.defaultBlockState(), 2);
        }
        f.route();
        helper.assertTrue(!f.entity.isSleeping(), "Resident slept through a wall from within the old distance threshold");
        f.finish();
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "personal_bed_routes", timeoutTicks = 40)
    public static void blockedHeadroomAndFluidsAreNotBedStations(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 5, 10, 40);
        BlockPos station = f.origin.east(5);
        f.level.setBlock(station.above(), Blocks.STONE.defaultBlockState(), 2);
        helper.assertTrue(!f.entity.canUseBedStation(station), "Bed station accepted obstructed headroom");
        f.level.setBlock(station.above(), Blocks.AIR.defaultBlockState(), 2);
        f.level.setBlock(station, Blocks.WATER.defaultBlockState(), 2);
        helper.assertTrue(!f.entity.canUseBedStation(station), "Bed station accepted water");
        f.finish();
        helper.succeed();
    }

    @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",batch="range_roads",timeoutTicks=1600)
    public static void residentLeavesLoopRoadAndActuallySleepsAtHome(GameTestHelper helper) {
        Fixture f=new Fixture(helper,9,75,1600);
        var roads=com.hkin.exiledsettlement.road.RoadSpaceData.get(f.level); java.util.List<BlockPos> cells=new java.util.ArrayList<>();
        for (int x=0;x<=80;x++) for (int z:new int[]{0,6}) cells.add(f.origin.offset(x,-1,z));
        for (int z=1;z<6;z++) { cells.add(f.origin.offset(0,-1,z));cells.add(f.origin.offset(80,-1,z)); }
        cells.forEach(cell -> f.level.setBlock(cell, Blocks.DIRT_PATH.defaultBlockState(), 2));
        roads.addAll(cells);
        helper.onEachTick(()->{ if (!f.finished && !f.entity.isSleeping()) f.route(); });
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(f.entity.isSleeping(),"Resident remained on the loop road: "+f.citizen.workFailure()+" @ "+f.entity.blockPosition()))
                .thenExecute(()->{helper.assertTrue(f.entity.getSleepingPos().orElseThrow().equals(f.bed),"Road exit used a wrong bed");cells.forEach(roads::remove);f.finish();}).thenSucceed();
    }

    @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",batch="resident_routes",timeoutTicks=1000)
    public static void residentExitsWarehouseAndWalksToUpperFloorBed(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 10, 24, 1000, 4);
        // A real enclosed warehouse, with its only exit facing away from home.
        for (BlockPos p : BlockPos.betweenClosed(f.origin.offset(0,0,-3), f.origin.offset(7,2,3))) {
            if (p.getX() == f.origin.getX() || p.getX() == f.origin.getX()+7
                    || Math.abs(p.getZ()-f.origin.getZ()) == 3) f.level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
        }
        f.level.setBlock(f.origin.offset(0,0,0), Blocks.AIR.defaultBlockState(), 2);
        f.level.setBlock(f.origin.offset(0,1,0), Blocks.AIR.defaultBlockState(), 2);
        f.untilSleeping();
    }

    @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",batch="resident_routes",timeoutTicks=900)
    public static void residentReachesRearWithoutEnteringUnrelatedBuilding(GameTestHelper helper) {
        Fixture f = new Fixture(helper, 11, 22, 900);
        BuildingRecord obstacle = f.settlement.addBuilding(BuildingType.WAREHOUSE, f.origin.east(8), Direction.NORTH,
                f.origin.offset(7,0,-3), f.origin.offset(15,3,3), 0);
        obstacle.updateFunction(true, BuildingStatus.OPERATIONAL, 1, List.of(f.origin.offset(8,0,0)));
        // Open through hall would otherwise be the shortest route to the bed.
        com.hkin.exiledsettlement.citizen.CitizenGroundNavigation nav =
                (com.hkin.exiledsettlement.citizen.CitizenGroundNavigation) f.entity.getNavigation();
        nav.destination(f.settlement, f.entity.navigationFeetPosition(), f.bed);
        // Use the real persistent record for navigation's settlement lookup.
        f.entity.getPersistentData().putString(com.hkin.exiledsettlement.citizen.CitizenProjectionService.SETTLEMENT_ID_TAG, f.settlement.id().toString());
        f.entity.getPersistentData().putString(com.hkin.exiledsettlement.citizen.CitizenProjectionService.CITIZEN_ID_TAG, f.citizen.id().toString());
        helper.onEachTick(() -> {
            if (!f.finished) helper.assertTrue(!(f.entity.getX() > obstacle.minCorner().getX()+1
                    && f.entity.getX() < obstacle.maxCorner().getX()
                    && f.entity.getZ() > obstacle.minCorner().getZ()+1
                    && f.entity.getZ() < obstacle.maxCorner().getZ()), "Resident entered unrelated interior en route behind it");
        });
        f.untilSleeping();
    }

    private static final class Fixture {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final BlockPos origin;
        private final BlockPos bed;
        private final SettlementSavedData data = new SettlementSavedData();
        private final SettlementRecord settlement;
        private final CitizenRecord citizen;
        private final SettlementCitizenEntity entity;
        private final ChunkPos minChunk;
        private final ChunkPos maxChunk;
        private boolean finished;

        private Fixture(GameTestHelper helper, int scenario, int distance, int timeout) {
            this(helper, scenario, distance, timeout, 0);
        }

        private Fixture(GameTestHelper helper, int scenario, int distance, int timeout, int elevation) {
            this.helper = helper;
            level = helper.getLevel();
            origin = helper.absolutePos(new BlockPos(4, 10, 4)).offset(32768 + scenario * 128, 0, 0);
            minChunk = new ChunkPos(origin.offset(-4, 0, -5));
            maxChunk = new ChunkPos(origin.offset(Math.max(66,distance+8), 0, 5));
            for (int x = minChunk.x; x <= maxChunk.x; x++)
                for (int z = minChunk.z; z <= maxChunk.z; z++) level.setChunkForced(x, z, true);
            for (BlockPos p : BlockPos.betweenClosed(origin.offset(-4, -1, -5), origin.offset(Math.max(66, distance + 8), 4, 5)))
                level.setBlock(p, p.getY() < origin.getY()
                        ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
            bed = origin.east(distance).above(elevation);
            if (elevation > 0) {
                for (int rise = 1; rise <= elevation; rise++) {
                    BlockPos step = origin.east(distance - elevation - 3 + rise);
                    for (int y = 0; y < rise; y++) level.setBlock(step.above(y), Blocks.STONE.defaultBlockState(), 2);
                }
                for (BlockPos p : BlockPos.betweenClosed(bed.offset(-3,-1,-2), bed.offset(2,-1,3)))
                    level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
            }
            level.setBlock(bed, Blocks.RED_BED.defaultBlockState()
                    .setValue(BedBlock.PART, BedPart.HEAD).setValue(BedBlock.FACING, Direction.NORTH), 2);
            level.setBlock(bed.south(), Blocks.RED_BED.defaultBlockState()
                    .setValue(BedBlock.PART, BedPart.FOOT).setValue(BedBlock.FACING, Direction.NORTH), 2);
            settlement = scenario == 11 ? SettlementSavedData.get(level).createOrRecover(origin, UUID.randomUUID(), "Transit route").value()
                    : new SettlementRecord(UUID.randomUUID(), "Bed route", origin, UUID.randomUUID());
            if (scenario == 11) settlement.deactivate();
            citizen = settlement.citizens().iterator().next();
            BuildingRecord home = settlement.addBuilding(BuildingType.HOUSE, bed, Direction.NORTH,
                    bed.offset(-4, 0, -4), bed.offset(4, 4, 4), 0);
            home.updateFunction(true, BuildingStatus.OPERATIONAL, 1, List.of(bed));
            citizen.assignHome(home.id(), bed);
            entity = ModEntities.SETTLEMENT_CITIZEN.get().create(level);
            helper.assertTrue(entity != null, "Cannot create bed-route resident");
            BlockPos start = origin.east(2);
            entity.moveTo(start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D, 0, 0);
            entity.setOnGround(true);
            helper.assertTrue(level.addFreshEntity(entity), "Cannot spawn bed-route resident");
            helper.runAtTickTime(timeout - 1L, this::finish);
        }

        private void route() {
            CitizenWorkService.routeResidentToPersonalBed(level, data, settlement, citizen, entity);
        }

        private void untilSleeping() {
            helper.onEachTick(() -> { if (!finished && !entity.isSleeping()) route(); });
            helper.startSequence().thenWaitUntil(() -> helper.assertTrue(entity.isSleeping(),
                    "Resident has not walked into the personal bed; " + citizen.activity() + ":" + citizen.workFailure()))
                    .thenExecute(() -> {
                        helper.assertTrue(entity.getSleepingPos().orElseThrow().equals(bed), "Resident used another bed");
                        finish();
                    }).thenSucceed();
        }

        private void finish() {
            if (finished) return;
            finished = true;
            CitizenNavigationReservationService.releaseAll(level, entity.getUUID());
            entity.discard();
            for (int x = minChunk.x; x <= maxChunk.x; x++)
                for (int z = minChunk.z; z <= maxChunk.z; z++) level.setChunkForced(x, z, false);
        }
    }
}
