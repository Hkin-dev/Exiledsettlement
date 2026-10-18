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
import com.hkin.exiledsettlement.citizen.CitizenEquipmentService;
import com.hkin.exiledsettlement.citizen.CitizenProfession;
import com.hkin.exiledsettlement.citizen.CitizenProjectionService;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import com.hkin.exiledsettlement.citizen.ProfessionAssignmentService;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.debug.ConstructionDebugService;
import com.hkin.exiledsettlement.registry.ModBlocks;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import com.hkin.exiledsettlement.settlement.TravelingMerchantService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Finite-stock, real-world profession baselines. Setup builds ordinary approved
 * templates, then the production server events and live entity navigation do all
 * work. There are no direct work-service calls, inventory preloads, accelerated
 * work cycles, or resident teleports after observation starts.
 *
 * These tests intentionally do not certify a complete survival economy: the
 * current herdsman requires a lead, not shears, and forestry replants without a
 * sapling debit. Those policies must not be hidden by the fixtures.
 */
@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OtherProfessionWorkGameTests {
    private static final int OBSERVATION_TICKS = 5900;

    private OtherProfessionWorkGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "profession_work_baselines", timeoutTicks = 6000)
    public static void foresterWalksForAxeHarvestsReplantsAndStoresLogs(GameTestHelper helper) {
        Fixture fixture = createFixture(helper, 0, "Forester finite-stock baseline");
        BuildingRecord treeFarm = placeBuilding(fixture, BlueprintTemplates.createArea(
                BuildingType.TREE_FARM, fixture.core.offset(8, 0, 18),
                fixture.core.offset(24, 0, 34), Direction.NORTH));
        BlockPos tree = fixture.core.offset(15, 0, 25);
        // Tall-tree regression: the bounded forestry flood fill must not stop at
        // the former sixteen-block scan ceiling.
        for (int height = 0; height < 24; height++) {
            fixture.level.setBlock(tree.above(height), Blocks.OAK_LOG.defaultBlockState(), 3);
        }
        for (BlockPos leaf : BlockPos.betweenClosed(tree.offset(-1, 23, -1), tree.offset(1, 24, 1))) {
            if (!fixture.level.getBlockState(leaf).is(Blocks.OAK_LOG)) {
                fixture.level.setBlock(leaf, Blocks.OAK_LEAVES.defaultBlockState(), 3);
            }
        }
        supply(fixture, new ItemStack(Items.IRON_AXE));
        startResident(fixture, CitizenProfession.LUMBERJACK, treeFarm);
        helper.assertTrue(treeFarm.operational(), "Formal tree-farm fixture is not operational");
        helper.assertTrue(treeFarm.capacity() == 2,
                "A 15x15 usable tree farm must provide one lumberjack slot per complete 100 square metres");
        fixture.workTarget = () -> tree;
        observe(fixture, Items.OAK_LOG, 24, 24,
                () -> fixture.level.getBlockState(tree).is(Blocks.OAK_SAPLING),
                "tree harvested and replanted");
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "profession_work_baselines", timeoutTicks = 6000)
    public static void foresterHarvestsOneBlockBirchAndReplantsBirch(GameTestHelper helper) {
        Fixture fixture = createFixture(helper, 4, "Short birch tree baseline");
        BuildingRecord treeFarm = placeBuilding(fixture, BlueprintTemplates.createArea(
                BuildingType.TREE_FARM, fixture.core.offset(8, 0, 18),
                fixture.core.offset(24, 0, 34), Direction.NORTH));
        BlockPos tree = fixture.core.offset(15, 0, 25);
        fixture.level.setBlock(tree, Blocks.BIRCH_LOG.defaultBlockState(), 3);
        fixture.level.setBlock(tree.above(), Blocks.BIRCH_LEAVES.defaultBlockState(), 3);
        supply(fixture, new ItemStack(Items.IRON_AXE));
        startResident(fixture, CitizenProfession.LUMBERJACK, treeFarm);
        fixture.workTarget = () -> tree;
        observe(fixture, Items.BIRCH_LOG, 1, 1,
                () -> fixture.level.getBlockState(tree).is(Blocks.BIRCH_SAPLING),
                "one-block birch harvested and same species replanted");
    }

    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty",batch="profession_work_baselines",timeoutTicks=6000)
    public static void foresterCutsMangroveRootTrunkCrownAndPreservesRootWater(GameTestHelper helper) {
        Fixture f = createFixture(helper, 15, "Mangrove roots and complete tall crown");
        BuildingRecord farm = placeBuilding(f, BlueprintTemplates.createArea(BuildingType.TREE_FARM,
                f.core.offset(8,0,18), f.core.offset(24,0,34), Direction.NORTH));
        BlockPos root = f.core.offset(15,0,25);
        f.level.setBlock(root, Blocks.MANGROVE_ROOTS.defaultBlockState(), 2);
        f.level.setBlock(root.east(), Blocks.MANGROVE_ROOTS.defaultBlockState().setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED,true), 2);
        f.level.setBlock(root.west(), Blocks.MUDDY_MANGROVE_ROOTS.defaultBlockState(), 2);
        for (int y=1;y<=34;y++) f.level.setBlock(root.above(y), Blocks.MANGROVE_LOG.defaultBlockState(),2);
        BlockPos leaf = root.above(35);
        f.level.setBlock(leaf, Blocks.MANGROVE_LEAVES.defaultBlockState(),2);
        supply(f,new ItemStack(Items.IRON_AXE)); startResident(f,CitizenProfession.LUMBERJACK,farm);
        f.workTarget=()-> f.worker.targetPosition() == null ? root : f.worker.targetPosition();
        boolean[] diagnosed = {false};
        helper.onEachTick(() -> {
            if (f.finished || diagnosed[0] || inventoryCount(f, Items.MANGROVE_LOG) < 34) return;
            diagnosed[0] = true;
            ExiledSettlementMod.LOGGER.info("[mangrove-completion] root={} east={} west={} leaf={} remainingLogs={} saplings={} target={} resident={}",
                    f.level.getBlockState(root), f.level.getBlockState(root.east()), f.level.getBlockState(root.west()),
                    f.level.getBlockState(leaf), java.util.stream.IntStream.rangeClosed(1,34)
                            .filter(y -> !f.level.getBlockState(root.above(y)).isAir()).count(),
                    java.util.stream.StreamSupport.stream(BlockPos.betweenClosed(root.offset(-2,0,-2),root.offset(2,2,2)).spliterator(),false)
                            .filter(p -> f.level.getBlockState(p).is(net.minecraft.tags.BlockTags.SAPLINGS))
                            .map(p -> p + ":" + f.level.getBlockState(p)).toList(), f.worker.targetPosition(), f.entity.position());
        });
        // An obstructed trunk approach may legitimately select a reachable
        // lateral root. Require the same species on valid soil beside this tree,
        // rather than forcing one planting cell; all harvested cells still count.
        observe(f,Items.MANGROVE_LOG,34,34,()-> java.util.stream.StreamSupport.stream(
                        BlockPos.betweenClosed(root.offset(-1,0,-1),root.offset(1,0,1)).spliterator(),false)
                        .anyMatch(p -> f.level.getBlockState(p).is(Blocks.MANGROVE_PROPAGULE)
                                && f.level.getBlockState(p).canSurvive(f.level, p))
                && !f.level.getBlockState(root).is(Blocks.MANGROVE_ROOTS)
                && f.level.getBlockState(root.east()).is(Blocks.WATER)
                && !f.level.getBlockState(root.west()).is(Blocks.MUDDY_MANGROVE_ROOTS)
                && f.level.getBlockState(leaf).isAir()
                && java.util.stream.IntStream.rangeClosed(1,34).allMatch(y->f.level.getBlockState(root.above(y)).isAir()),
                "all mangrove logs and roots removed with water and replant preserved");
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "profession_work_baselines", timeoutTicks = 6000)
    public static void foresterRecoversRemainingMangroveRootsWithoutTrunk(GameTestHelper helper) {
        Fixture f = createFixture(helper, 16, "Interrupted mangrove root recovery");
        BuildingRecord farm = placeBuilding(f, BlueprintTemplates.createArea(BuildingType.TREE_FARM,
                f.core.offset(8, 0, 18), f.core.offset(24, 0, 34), Direction.NORTH));
        BlockPos root = f.core.offset(15, 0, 25);
        for (int y = 0; y < 3; y++) f.level.setBlock(root.above(y), Blocks.MANGROVE_ROOTS.defaultBlockState(), 2);
        supply(f, new ItemStack(Items.IRON_AXE)); startResident(f, CitizenProfession.LUMBERJACK, farm);
        f.workTarget = () -> root;
        observe(f, Items.MANGROVE_ROOTS, 3, 3, () -> f.level.getBlockState(root).is(Blocks.MANGROVE_PROPAGULE)
                        && f.level.getBlockState(root.above()).isAir() && f.level.getBlockState(root.above(2)).isAir(),
                "remaining mangrove roots discovered without logs, fully removed and delivered, correct species replanted");
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "profession_work_baselines", timeoutTicks = 6000)
    public static void foresterClearsNaturalCrownKeepsPlayerLeavesAndDelivers(GameTestHelper helper) {
        Fixture f = createFixture(helper, 14, "Whole tree with overhanging branch and natural crown");
        BuildingRecord farm = placeBuilding(f, BlueprintTemplates.createArea(BuildingType.TREE_FARM,
                f.core.offset(8, 0, 18), f.core.offset(24, 0, 34), Direction.NORTH));
        BlockPos root = f.core.offset(9, 0, 25);
        for (int y = 0; y < 3; y++) f.level.setBlock(root.above(y), Blocks.OAK_LOG.defaultBlockState(), 3);
        List<BlockPos> branches = List.of(root.above(2).west(), root.above(2).west(2));
        for (BlockPos branch : branches) f.level.setBlock(branch, Blocks.OAK_LOG.defaultBlockState(), 3);
        List<BlockPos> leaves = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(root.offset(-1, 3, -1), root.offset(1, 4, 1))) {
            leaves.add(p.immutable()); f.level.setBlock(p, Blocks.OAK_LEAVES.defaultBlockState(), 3);
        }
        BlockPos playerLeaf = root.offset(2, 3, 0);
        f.level.setBlock(playerLeaf, Blocks.OAK_LEAVES.defaultBlockState().setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT, true), 3);
        supply(f, new ItemStack(Items.IRON_AXE)); startResident(f, CitizenProfession.LUMBERJACK, farm);
        f.workTarget = () -> root;
        observe(f, Items.OAK_LOG, 5, 5, () -> leaves.stream().allMatch(p -> f.level.getBlockState(p).isAir())
                        && branches.stream().allMatch(p -> f.level.getBlockState(p).isAir())
                        && f.level.getBlockState(playerLeaf).is(Blocks.OAK_LEAVES)
                        && f.level.getBlockState(root).is(Blocks.OAK_SAPLING),
                "overhanging branch and natural crown removed, player leaf protected, all five real logs delivered");
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "profession_work_baselines", timeoutTicks = 6000)
    public static void foresterHarvestsSeventyTwoBlockTreeWithoutHighRemainder(GameTestHelper helper) {
        Fixture f = createFixture(helper, 8, "Forestry above old height ceiling");
        BuildingRecord farm = placeBuilding(f, BlueprintTemplates.createArea(BuildingType.TREE_FARM,
                f.core.offset(8, 0, 18), f.core.offset(24, 0, 34), Direction.NORTH));
        BlockPos root = f.core.offset(15, 0, 25);
        for (int y = 0; y < 72; y++) f.level.setBlock(root.above(y), Blocks.OAK_LOG.defaultBlockState(), 3);
        supply(f, new ItemStack(Items.IRON_AXE)); startResident(f, CitizenProfession.LUMBERJACK, farm);
        f.workTarget = () -> root;
        observe(f, Items.OAK_LOG, 72, 72, () -> f.level.getBlockState(root).is(Blocks.OAK_SAPLING)
                && java.util.stream.IntStream.range(1, 72).allMatch(y -> f.level.getBlockState(root.above(y)).isAir()),
                "72-log tree fully harvested and delivered");
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "profession_work_baselines", timeoutTicks = 6000)
    public static void foresterKeepsRootUntilThreeHundredLogsAreHarvested(GameTestHelper helper) {
        Fixture f = createFixture(helper, 9, "Forestry multiple bounded batches");
        BuildingRecord farm = placeBuilding(f, BlueprintTemplates.createArea(BuildingType.TREE_FARM,
                f.core.offset(8, 0, 18), f.core.offset(24, 0, 34), Direction.NORTH));
        BlockPos root = f.core.offset(15, 0, 25);
        List<BlockPos> logs = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(root.offset(-2, 0, -2), root.offset(2, 11, 2))) {
            logs.add(p.immutable()); f.level.setBlock(p, Blocks.OAK_LOG.defaultBlockState(), 3);
        }
        boolean[] sawPartial = {false};
        helper.onEachTick(() -> {
            if (f.finished) return;
            long remaining = logs.stream().filter(p -> f.level.getBlockState(p).is(Blocks.OAK_LOG)).count();
            if (remaining > 0 && remaining < 300) {
                sawPartial[0] = true;
                helper.assertTrue(logs.stream().anyMatch(p -> p.getY() == root.getY()
                        && f.level.getBlockState(p).is(Blocks.OAK_LOG)), "Batch cut every grounded root before the crown");
            }
        });
        supply(f, new ItemStack(Items.IRON_AXE)); startResident(f, CitizenProfession.LUMBERJACK, farm);
        f.workTarget = () -> root.offset(-2, 0, -2);
        observe(f, Items.OAK_LOG, 300, 300, () -> sawPartial[0]
                && logs.stream().noneMatch(p -> f.level.getBlockState(p).is(Blocks.OAK_LOG)),
                "300 logs harvested in bounded batches and delivered without suspended crown");
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "profession_work_baselines", timeoutTicks = 6000)
    public static void foresterRecoversOldSuspendedLogsFromGround(GameTestHelper helper) {
        Fixture f = createFixture(helper, 10, "Forestry suspended crown recovery");
        BuildingRecord farm = placeBuilding(f, BlueprintTemplates.createArea(BuildingType.TREE_FARM,
                f.core.offset(8, 0, 18), f.core.offset(24, 0, 34), Direction.NORTH));
        BlockPos root = f.core.offset(15, 0, 25);
        for (int y = 60; y < 65; y++) f.level.setBlock(root.above(y), Blocks.OAK_LOG.defaultBlockState(), 3);
        supply(f, new ItemStack(Items.IRON_AXE)); startResident(f, CitizenProfession.LUMBERJACK, farm);
        f.workTarget = () -> root;
        observe(f, Items.OAK_LOG, 5, 5, () -> f.level.getBlockState(root).is(Blocks.OAK_SAPLING)
                && java.util.stream.IntStream.range(60, 65).allMatch(y -> f.level.getBlockState(root.above(y)).isAir()),
                "old suspended crown recovered from ground and output delivered");
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "profession_work_baselines", timeoutTicks = 6000)
    public static void foresterWorksAroundBlockedTrunkAndReplantsOffGrid(GameTestHelper helper) {
        Fixture fixture = createFixture(helper, 6, "Forestry nearby-area work");
        BuildingRecord treeFarm = placeBuilding(fixture, BlueprintTemplates.createArea(
                BuildingType.TREE_FARM, fixture.core.offset(8, 0, 18),
                fixture.core.offset(24, 0, 34), Direction.NORTH));
        // Deliberately off the planting grid and one block above the field.
        BlockPos tree = fixture.core.offset(16, 1, 25);
        fixture.level.setBlock(tree.below(), Blocks.DIRT.defaultBlockState(), 3);
        for (int height = 0; height < 4; height++) {
            fixture.level.setBlock(tree.above(height), Blocks.OAK_LOG.defaultBlockState(), 3);
        }
        fixture.level.setBlock(tree.above(4), Blocks.OAK_LEAVES.defaultBlockState(), 3);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos obstacle = tree.relative(direction);
            fixture.level.setBlock(obstacle.below(), Blocks.DIRT.defaultBlockState(), 3);
            fixture.level.setBlock(obstacle, Blocks.COBBLESTONE.defaultBlockState(), 3);
            fixture.level.setBlock(obstacle.above(), Blocks.COBBLESTONE.defaultBlockState(), 3);
        }
        supply(fixture, new ItemStack(Items.IRON_AXE));
        startResident(fixture, CitizenProfession.LUMBERJACK, treeFarm);
        fixture.workTarget = () -> tree;
        observe(fixture, Items.OAK_LOG, 4, 4,
                () -> fixture.level.getBlockState(tree).is(Blocks.OAK_SAPLING),
                "blocked adjacent cells bypassed by nearby work and off-grid replanting");
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "profession_work_baselines", timeoutTicks = 6000)
    public static void foresterSkipsInaccessibleTreeAndDeliversAnotherTree(GameTestHelper helper) {
        Fixture fixture = createFixture(helper, 7, "Forestry blocked-target recovery");
        BuildingRecord treeFarm = placeBuilding(fixture, BlueprintTemplates.createArea(
                BuildingType.TREE_FARM, fixture.core.offset(-2, 0, 10),
                fixture.core.offset(40, 0, 44), Direction.NORTH));
        BlockPos inaccessible = fixture.core.offset(10, 0, 25);
        for (BlockPos pos : BlockPos.betweenClosed(inaccessible.offset(-9, 0, -9),
                inaccessible.offset(9, 6, 9))) {
            fixture.level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 3);
        }
        fixture.level.setBlock(inaccessible, Blocks.BIRCH_LOG.defaultBlockState(), 3);
        BlockPos tree = fixture.core.offset(30, 0, 25);
        for (int height = 0; height < 3; height++) {
            fixture.level.setBlock(tree.above(height), Blocks.OAK_LOG.defaultBlockState(), 3);
        }
        fixture.level.setBlock(tree.above(3), Blocks.OAK_LEAVES.defaultBlockState(), 3);
        supply(fixture, new ItemStack(Items.IRON_AXE));
        startResident(fixture, CitizenProfession.LUMBERJACK, treeFarm);
        fixture.workTarget = () -> tree;
        observe(fixture, Items.OAK_LOG, 3, 3,
                () -> fixture.level.getBlockState(tree).is(Blocks.OAK_SAPLING)
                        && fixture.level.getBlockState(inaccessible).is(Blocks.BIRCH_LOG),
                "inaccessible first tree skipped and accessible tree delivered without deleting obstacles");
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "profession_work_baselines", timeoutTicks = 6000)
    public static void herdsmanWalksForLeadShearsSheepAndStoresWool(GameTestHelper helper) {
        Fixture fixture = createFixture(helper, 1, "Herdsman finite-stock baseline");
        BuildingRecord pasture = placeBuilding(fixture, BlueprintTemplates.createArea(
                BuildingType.PASTURE_AREA, fixture.core.offset(8, 0, 14),
                fixture.core.offset(24, 0, 30), Direction.NORTH));
        Sheep sheep = EntityType.SHEEP.create(fixture.level);
        helper.assertTrue(sheep != null, "Could not create pasture sheep");
        sheep.setAge(0);
        sheep.setSheared(false);
        sheep.setPersistenceRequired();
        BlockPos sheepStart = fixture.core.offset(16, 0, 22);
        sheep.moveTo(sheepStart.getX() + 0.5D, sheepStart.getY(), sheepStart.getZ() + 0.5D, 0.0F, 0.0F);
        helper.assertTrue(fixture.level.addFreshEntity(sheep), "Could not add live pasture sheep");
        supply(fixture, new ItemStack(Items.LEAD));
        startResident(fixture, CitizenProfession.HERDSMAN, pasture);
        fixture.workTarget = sheep::blockPosition;
        observe(fixture, Items.WHITE_WOOL, 1, 3, sheep::isSheared, "live sheep sheared");
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "profession_work_baselines", timeoutTicks = 6000)
    public static void herdsmanCullsOnlySurplusPastureAnimals(GameTestHelper helper) {
        Fixture fixture = createFixture(helper, 5, "Pasture population cap baseline");
        BuildingRecord pasture = placeBuilding(fixture, BlueprintTemplates.createArea(
                BuildingType.PASTURE_AREA, fixture.core.offset(8, 0, 14),
                fixture.core.offset(16, 0, 22), Direction.NORTH));
        helper.assertTrue(pasture.setProductionId(ResourceLocation.withDefaultNamespace("cow")),
                "Could not select cattle pasture");
        int cap = com.hkin.exiledsettlement.building.BuildingProductionService.animalCapacity(pasture);
        List<Cow> herd = new ArrayList<>();
        for (int index = 0; index <= cap; index++) {
            Cow cow = EntityType.COW.create(fixture.level);
            helper.assertTrue(cow != null, "Could not create pasture cow");
            BlockPos pos = fixture.core.offset(10 + index % 5, 0, 16 + index / 5);
            cow.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0, 0);
            cow.setPersistenceRequired();
            cow.restrictTo(pos, 1);
            helper.assertTrue(fixture.level.addFreshEntity(cow), "Could not spawn pasture cow");
            herd.add(cow);
        }
        supply(fixture, new ItemStack(Items.LEAD));
        startResident(fixture, CitizenProfession.HERDSMAN, pasture);
        helper.startSequence().thenWaitUntil(() -> {
            long alive = herd.stream().filter(Cow::isAlive).count();
            helper.assertTrue(alive == cap && herd.stream().filter(cow -> !cow.isAlive())
                            .allMatch(cow -> cow.getLastHurtByMob() == fixture.entity),
                    "Waiting for the herdsman to cull one cow without killing the breeding stock");
            helper.assertTrue(stock(fixture, Items.BEEF) + inventoryCount(fixture, Items.BEEF)
                    + looseCount(fixture, Items.BEEF) > 0, "Slaughter produced no real beef");
        }).thenExecute(() -> finish(fixture)).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "profession_work_baselines", timeoutTicks = 6000)
    public static void hunterWalksForWeaponHuntsAndStoresMeat(GameTestHelper helper) {
        Fixture fixture = createFixture(helper, 2, "Hunter finite-stock baseline");
        BuildingRecord lodge = placeBuilding(fixture, BlueprintTemplates.create(
                BuildingType.HUNTING_LODGE, fixture.core.offset(16, 0, 0), Direction.NORTH));
        Cow cow = EntityType.COW.create(fixture.level);
        helper.assertTrue(cow != null, "Could not create huntable cow");
        cow.setAge(0);
        cow.setPersistenceRequired();
        // NORTH-facing procedural lodges extend toward negative world Z.
        // Positive Z is the genuinely open hunting ground, not the rear wall.
        BlockPos cowStart = fixture.core.offset(16, 0, 10);
        helper.assertTrue(!lodge.contains(cowStart)
                        && fixture.level.getBlockState(cowStart).getCollisionShape(fixture.level, cowStart).isEmpty()
                        && fixture.level.getBlockState(cowStart.above())
                        .getCollisionShape(fixture.level, cowStart.above()).isEmpty()
                        && fixture.level.getBlockState(cowStart.below())
                        .isFaceSturdy(fixture.level, cowStart.below(), Direction.UP),
                "Hunt target must begin outside the lodge with two-block clearance and a solid floor");
        cow.moveTo(cowStart.getX() + 0.5D, cowStart.getY(), cowStart.getZ() + 0.5D, 0.0F, 0.0F);
        cow.restrictTo(cowStart, 4);
        helper.assertTrue(fixture.level.addFreshEntity(cow), "Could not add live hunt target");
        // The current hunter contract requests both weapon and capture lead,
        // even when this settlement has no pasture and is only hunting.
        supply(fixture, new ItemStack(Items.IRON_SWORD), new ItemStack(Items.LEAD));
        startResident(fixture, CitizenProfession.HUNTER, lodge);
        fixture.worker.insertIntoInventory(new ItemStack(Items.BREAD, 4)); // Personal food is separate from hunted output.
        fixture.attackerVerified = () -> cow.getLastHurtByMob() == fixture.entity;
        fixture.workTarget = cow::blockPosition;
        fixture.lootBasedOutput = true;
        observe(fixture, Items.BEEF, 1, 3,
                () -> !cow.isAlive() && fixture.attackerVerified.getAsBoolean(),
                "wild animal killed by this resident, not by fixture suffocation");
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "merchant_visit_regression", timeoutTicks = 200)
    public static void marketMerchantOrderSurvivesSaveAndCompletesOnlyAfterTrade(GameTestHelper helper) {
        Fixture fixture = createFixture(helper, 3, "Merchant visit and order persistence");
        BuildingRecord market = placeBuilding(fixture, BlueprintTemplates.create(
                BuildingType.MARKET, fixture.core.offset(17, 0, 0), Direction.NORTH));
        startResident(fixture, CitizenProfession.TRAVELING_MERCHANT, market);
        helper.assertTrue(TravelingMerchantService.catalog().size() >= 60,
                "Merchant catalog is still the tiny vanilla list");
        helper.assertTrue(TravelingMerchantService.order(fixture.level, fixture.settlement,
                ResourceLocation.withDefaultNamespace("oak_sapling"), 8), "Could not place a supported order");
        TravelingMerchantService.tickLevel(fixture.level);
        helper.assertTrue(fixture.settlement.merchantState().traderId() == null,
                "Merchant visited before the next day");
        SettlementRecord saved = SettlementRecord.load(
                fixture.settlement.save(fixture.level.registryAccess()), fixture.level.registryAccess());
        helper.assertTrue(saved.merchantState().orders().size() == 1,
                "Pending order did not persist through settlement save/load");
        fixture.level.setDayTime(24000L);
        TravelingMerchantService.tickLevel(fixture.level);
        var state = fixture.settlement.merchantState();
        helper.assertTrue(state.traderId() != null && state.activeOfferIndices().size() == 1,
                "The returning merchant did not carry the requested offer");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                fixture.level.getEntity(state.traderId()) instanceof WanderingTrader,
                "Waiting for the requested merchant to enter entity tracking"))
                .thenExecute(() -> {
        WanderingTrader trader = (WanderingTrader) fixture.level.getEntity(state.traderId());
        int index = state.activeOfferIndices().values().iterator().next();
        var offer = trader.getOffers().get(index);
        helper.assertTrue(offer.getResult().is(Items.OAK_SAPLING) && offer.getResult().getCount() == 8
                        && offer.getCostA().is(Items.EMERALD),
                "Order offer is not the requested item paid in emeralds");
        helper.assertTrue(state.orders().size() == 1, "Offer appearance consumed an unpaid order");
        offer.increaseUses();
        TravelingMerchantService.tickLevel(fixture.level);
        helper.assertTrue(state.orders().isEmpty(), "Purchased order was not cleared");
        finish(fixture);
                }).thenSucceed();
    }

    private static Fixture createFixture(GameTestHelper helper, int index, String name) {
        ServerLevel level = helper.getLevel();
        SettlementSavedData data = SettlementSavedData.get(level);
        BlockPos core = unusedFixtureCore(data,
                helper.absolutePos(new BlockPos(8, 8, 8)).offset(4096 + index * 512, 0, 4096));
        BlockPos min = core.offset(-48, -3, -32);
        BlockPos max = core.offset(48, 12, 48);
        List<ChunkPos> forced = new ArrayList<>();
        ChunkPos minChunk = new ChunkPos(min);
        ChunkPos maxChunk = new ChunkPos(max);
        for (int x = minChunk.x; x <= maxChunk.x; x++) {
            for (int z = minChunk.z; z <= maxChunk.z; z++) {
                level.setChunkForced(x, z, true);
                forced.add(new ChunkPos(x, z));
            }
        }
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            level.setBlock(pos, pos.getY() < core.getY() - 1 ? Blocks.DIRT.defaultBlockState()
                    : pos.getY() == core.getY() - 1 ? Blocks.GRASS_BLOCK.defaultBlockState()
                    : Blocks.AIR.defaultBlockState(), 2);
        }
        level.setBlock(core, ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(), 3);
        level.setDayTime(0L);
        // A single client inspector owns only this fixture. Dedicated/headless
        // or ambiguous multiplayer runs keep an isolated random owner.
        UUID owner = level.players().size() == 1 ? level.players().getFirst().getUUID() : UUID.randomUUID();
        var created = data.createOrRecover(core, owner, name);
        helper.assertTrue(created.success() && created.value() != null,
                "Could not create profession settlement: " + created.reason());
        SettlementRecord settlement = created.value();
        settlement.upgradeLevel();
        settlement.upgradeLevel();
        settlement.setCreativeCoreInstalled(false);
        List<CitizenRecord> citizens = settlement.citizens().stream()
                .sorted(Comparator.comparing(citizen -> citizen.id().toString())).toList();
        CitizenRecord worker = citizens.getFirst();
        for (CitizenRecord citizen : citizens) {
            if (citizen != worker) citizen.markDead(core);
        }
        Fixture fixture = new Fixture(helper, level, data, settlement, worker, core, forced);
        // Facing EAST preserves the player's approved double-entrance warehouse
        // orientation. The fixture does not replace it with an exposed barrel.
        BuildingBlueprintTemplate warehouse = BlueprintTemplates.create(
                BuildingType.WAREHOUSE, core.offset(-18, 0, 0), Direction.EAST);
        fixture.warehouse = placeBuilding(fixture, warehouse);
        var entrance = warehouse.entrances().getFirst();
        fixture.startPosition = entrance.position().relative(entrance.outward(), 12);
        helper.assertTrue(!fixture.warehouse.contains(fixture.startPosition)
                        && fixture.warehouse.servicePositions().stream()
                        .allMatch(container -> container.distSqr(fixture.startPosition) >= 144.0D),
                "The resident must start outside and at least 12 blocks from every warehouse container");
        helper.assertTrue(level.getBlockState(fixture.startPosition).getCollisionShape(level, fixture.startPosition).isEmpty()
                        && level.getBlockState(fixture.startPosition.above())
                        .getCollisionShape(level, fixture.startPosition.above()).isEmpty()
                        && level.getBlockState(fixture.startPosition.below())
                        .isFaceSturdy(level, fixture.startPosition.below(), Direction.UP),
                "The resident start must have two-block clearance and real ground");
        return fixture;
    }

    private static BlockPos unusedFixtureCore(SettlementSavedData data, BlockPos first) {
        // Retain prior observations rather than reactivating a stale resident
        // or replacing the terrain of a completed /test run.
        for (int attempt = 0; attempt < 32; attempt++) {
            BlockPos candidate = first.offset(0, 0, attempt * 192);
            if (data.settlements().stream().noneMatch(existing -> existing.overlapsBoundary(candidate, 4))) {
                return candidate;
            }
        }
        throw new IllegalStateException("No unused profession-test district; use a fresh disposable test world");
    }

    private static BuildingRecord placeBuilding(Fixture fixture, BuildingBlueprintTemplate template) {
        // A completed building is test setup, not measured resident work. Keep
        // the exact registered template version, natural block state updates and
        // real function validation; never force updateFunction(OPERATIONAL).
        for (var placement : template.placements()) {
            if (placement.mode() == BlueprintPlacementMode.OPTIONAL_SUPPORT
                    && !fixture.level.getBlockState(placement.position()).isAir()) continue;
            fixture.level.setBlock(placement.position(), placement.mode() == BlueprintPlacementMode.CLEARANCE
                    ? Blocks.AIR.defaultBlockState() : placement.state(), 2);
        }
        for (var placement : template.placements()) {
            BlueprintBlockStateResolver.refreshExisting(fixture.level, placement);
        }
        var registered = fixture.data.registerBuilding(template.registrationPosition(), template.type(),
                fixture.settlement.ownerId(), template.facing(), template.minCorner(), template.maxCorner(),
                template.transitionMargin(), BlueprintTemplates.currentTemplateVersion(template.type()));
        fixture.helper.assertTrue(registered.success() && registered.value() != null,
                "Could not register formal " + template.type() + " fixture: " + registered.reason());
        BuildingRecord building = registered.value();
        BuildingFunctionService.validateSingle(fixture.level, fixture.data, fixture.settlement, building);
        fixture.helper.assertTrue(building.operational(),
                "Formal " + template.type() + " failed function validation: " + building.status());
        return building;
    }

    private static void supply(Fixture fixture, ItemStack... supplies) {
        BlockPos supplyPosition = fixture.warehouse.servicePositions().stream()
                .min(Comparator.<BlockPos>comparingInt(BlockPos::getY)
                        .thenComparingDouble(pos -> pos.distSqr(fixture.startPosition)))
                .orElseThrow();
        fixture.helper.assertTrue(fixture.level.getBlockEntity(supplyPosition) instanceof Container,
                "Formal warehouse has no real supply container");
        Container container = (Container) fixture.level.getBlockEntity(supplyPosition);
        for (int slot = 0; slot < supplies.length; slot++) {
            fixture.helper.assertTrue(container.getItem(slot).isEmpty(), "Fixture supplies would overwrite existing stock");
            container.setItem(slot, supplies[slot].copy());
            fixture.initialTools.merge(supplies[slot].getItem(), supplies[slot].getCount(), Integer::sum);
        }
        container.setChanged();
    }

    private static void startResident(Fixture fixture, CitizenProfession profession, BuildingRecord workplace) {
        fixture.helper.assertTrue(fixture.worker.inventory().stream().allMatch(ItemStack::isEmpty),
                "Profession resident must begin with an empty backpack");
        fixture.worker.insertIntoInventory(new ItemStack(Items.BREAD, 16)); // Explicit finite personal rations.
        workplace.setWorkerTarget(1, workplace.configurationRevision());
        fixture.worker.setManualAssignment(profession, workplace.id());
        fixture.workplace = workplace;
        ProfessionAssignmentService.refresh(fixture.data, fixture.settlement);
        CitizenProjectionService.ensureSettlementCitizens(fixture.level, fixture.settlement);
        fixture.entity = CitizenProjectionService.findProjection(fixture.level, fixture.worker);
        fixture.helper.assertTrue(fixture.entity != null, "Profession resident has no world projection");
        // Initial position only. No subsequent callback is allowed to reposition
        // the resident, animal, tools, or outputs to make a test pass.
        fixture.entity.moveTo(fixture.startPosition.getX() + 0.5D, fixture.startPosition.getY(),
                fixture.startPosition.getZ() + 0.5D, 0.0F, 0.0F);
        fixture.data.updateProjection(fixture.settlement, fixture.worker,
                fixture.entity.getUUID(), fixture.entity.blockPosition());
        fixture.data.touch();
        ExiledSettlementMod.LOGGER.info(
                "[profession-baseline] start profession={} core={} resident={} warehouse={} workplace={} finiteStock=true",
                profession, fixture.core, fixture.startPosition, fixture.warehouse.position(), workplace.position());
    }

    private static void observe(Fixture fixture, Item output, int minimumOutput, int maximumOutput,
                                BooleanSupplier performedWork, String workDescription) {
        long started = fixture.level.getGameTime();
        fixture.helper.assertTrue(stock(fixture, output) == 0, "Output must not be preloaded into the warehouse");
        fixture.previousPosition = fixture.entity.position();
        fixture.previousWorkTarget = fixture.workTarget.get();
        // Isolate clocks of concurrent fixtures; still call the production scheduler and move real entities.
        fixture.settlement.deactivate();
        fixture.helper.runAtTickTime(OBSERVATION_TICKS, () -> {
            if (fixture.finished) return;
            ExiledSettlementMod.LOGGER.error("[profession-baseline] TIMEOUT {}", diagnostics(fixture, output));
            String detail = summary(fixture, output);
            finish(fixture);
            fixture.helper.fail("Profession did not complete finite-stock work within "
                    + OBSERVATION_TICKS + " ticks: " + detail);
        });
        fixture.helper.startSequence().thenWaitUntil(() -> {
            long elapsed = fixture.level.getGameTime() - started;
            fixture.visited.add(fixture.entity.blockPosition().immutable());
            fixture.sawEquipment |= CitizenEquipmentService.missingEquipment(fixture.worker) == null;
            fixture.sawWork |= performedWork.getAsBoolean();
            // Hunting includes collecting the real dropped meat before the
            // fixture advances to rest; killing alone is not a completed chain.
            fixture.level.setDayTime((fixture.sawWork && fixture.sawCarriedOutput ? 23000L : 6000L)
                    + CitizenWorkService.scheduleOffset(fixture.worker));
            if (elapsed % CitizenWorkService.WORK_INTERVAL_TICKS == 0) {
                fixture.worker.updateProjection(fixture.entity.getUUID(), fixture.entity.blockPosition());
                CitizenWorkService.tickSettlement(fixture.level, fixture.data, fixture.settlement);
            }
            BlockPos workTarget = fixture.workTarget.get();
            // Forestry explicitly uses the approved eight-block work area; other professions
            // retain their ordinary four-block interaction range. Check
            // both adjacent observations because entity and server-event ticks
            // bracket the action; this is not a larger gameplay reach.
            double workRadiusSquared = fixture.worker.profession() == CitizenProfession.LUMBERJACK ? 64.0D : 16.0D;
            boolean nearAction = near(fixture.entity.position(), workTarget, workRadiusSquared)
                    || near(fixture.previousPosition, workTarget, workRadiusSquared)
                    || near(fixture.entity.position(), fixture.previousWorkTarget, workRadiusSquared)
                    || near(fixture.previousPosition, fixture.previousWorkTarget, workRadiusSquared);
            fixture.sawWorkApproach |= nearAction;
            int carried = inventoryCount(fixture, output);
            fixture.sawCarriedOutput |= carried > 0;
            if (carried > fixture.previousCarriedOutput) {
                // Meat scatters away from a corpse. Check the actual dropped
                // item positions observed before pickup, not an artificially
                // tighter radius around the cow's body. A previous observation
                // remains available after the production pickup discards it.
                boolean pickupNear = fixture.lootBasedOutput
                        ? fixture.previousLooseOutputPositions.stream().anyMatch(position ->
                        near(fixture.entity.position(), BlockPos.containing(position), 16.0D)
                                || near(fixture.previousPosition, BlockPos.containing(position), 16.0D))
                        : nearAction;
                fixture.requireInvariant(pickupNear,
                        "Output entered the backpack without a real nearby work action or dropped item");
            }
            if (fixture.lootBasedOutput) {
                fixture.previousLooseOutputPositions = fixture.level.getEntitiesOfClass(ItemEntity.class,
                                new AABB(workTarget).inflate(8.0D),
                                item -> item.isAlive() && item.getItem().is(output))
                        .stream().map(ItemEntity::position).toList();
            }
            fixture.previousCarriedOutput = carried;
            fixture.previousPosition = fixture.entity.position();
            fixture.previousWorkTarget = workTarget;
            fixture.requireInvariant(fixture.entity.isAlive() && fixture.warehouse.operational()
                            && fixture.workplace.operational(), "Resident or a required work building stopped operating");
            fixture.requireInvariant(!fixture.settlement.creativeCoreInstalled()
                            && !WarehouseInventoryService.hasCreativeSupply(fixture.level, fixture.warehouse)
                            && !ConstructionDebugService.isEnabled(fixture.level, fixture.settlement.id()),
                    "Creative supply or construction bypass contaminated the measured gate");
            BlockPos feet = fixture.entity.blockPosition();
            fixture.sawWarehouseInterior |= WarehouseInventoryService.canInteract(fixture.level,fixture.warehouse,fixture.entity);
            if (elapsed % 20L != 0L) fixture.helper.assertTrue(false, "Awaiting next stock sample");
            int stored = stock(fixture, output);
            int totalOutput = stored + carried + looseCount(fixture, output);
            for (var entry : fixture.initialTools.entrySet()) {
                fixture.requireInvariant(stock(fixture, entry.getKey()) + inventoryCount(fixture, entry.getKey())
                                + looseCount(fixture, entry.getKey()) == entry.getValue(),
                        "Finite profession tool was lost or duplicated: " + entry.getKey());
            }
            if (fixture.sawWork && fixture.measuredOutput < 0) {
                fixture.requireInvariant(totalOutput >= minimumOutput && totalOutput <= maximumOutput,
                        "First real work action did not create the expected finite output: " + totalOutput);
                fixture.measuredOutput = totalOutput;
            }
            if (fixture.measuredOutput >= 0) {
                fixture.requireInvariant(totalOutput == fixture.measuredOutput,
                        "First-batch output was lost or duplicated between the work site and warehouse: "
                                + fixture.measuredOutput + " -> " + totalOutput);
            }
            if (elapsed % 1000L == 0L) {
                ExiledSettlementMod.LOGGER.info("[profession-baseline] elapsed={} {}",
                        elapsed, diagnostics(fixture, output));
            }
            fixture.helper.assertTrue(fixture.invariantFailure.isEmpty(), summary(fixture, output));
            fixture.helper.assertTrue(fixture.sawEquipment && fixture.sawWarehouseInterior
                            && fixture.visited.size() >= 12 && fixture.sawWork && fixture.sawWorkApproach
                            && fixture.sawCarriedOutput && stored >= minimumOutput
                            && stored == fixture.measuredOutput && carried == 0,
                    "Waiting for tool pickup, real walking, " + workDescription + ", and warehouse delivery: "
                            + summary(fixture, output));
            ExiledSettlementMod.LOGGER.info(
                    "[profession-baseline] PASS profession={} elapsed={} stored={} distinctFeet={} actualWork={} warehouseEntered={} attackerVerified={}",
                    fixture.worker.profession(), elapsed, stored, fixture.visited.size(),
                    fixture.sawWork, fixture.sawWarehouseInterior,
                    fixture.attackerVerified == null ? "not_applicable" : fixture.attackerVerified.getAsBoolean());
        }).thenExecute(() -> finish(fixture)).thenSucceed();
    }

    private static int stock(Fixture fixture, Item item) {
        return WarehouseInventoryService.summarize(fixture.level, fixture.warehouse).getOrDefault(item, 0);
    }

    private static int inventoryCount(Fixture fixture, Item item) {
        return fixture.worker.inventory().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static int looseCount(Fixture fixture, Item item) {
        return fixture.level.getEntitiesOfClass(ItemEntity.class,
                        new AABB(Vec3.atLowerCornerOf(fixture.core.offset(-48, -4, -32)),
                                Vec3.atLowerCornerOf(fixture.core.offset(49, 14, 49))),
                        entity -> entity.isAlive() && entity.getItem().is(item)).stream()
                .mapToInt(entity -> entity.getItem().getCount()).sum();
    }

    private static boolean near(Vec3 feet, BlockPos target, double radiusSquared) {
        if (radiusSquared == 64.0D) return feet.distanceToSqr(Vec3.atBottomCenterOf(target)) <= radiusSquared;
        double dx=Math.max(0,Math.abs(feet.x-target.getX()-.5)-.5),dz=Math.max(0,Math.abs(feet.z-target.getZ()-.5)-.5);
        return dx*dx+dz*dz<=radiusSquared && Math.abs(feet.y-target.getY())<=2;
    }

    private static String summary(Fixture fixture, Item output) {
        String value = "profession=" + fixture.worker.profession() + " state=" + fixture.worker.workState()
                + " failure=" + fixture.worker.workFailure() + " at=" + fixture.entity.blockPosition()
                + " target=" + fixture.worker.targetPosition() + " stored=" + stock(fixture, output)
                + " invariant=" + fixture.invariantFailure;
        // GameTest failure books limit each page to 1024 characters. Keep the
        // full path/inventory evidence in logs rather than breaking reporting.
        return value.length() <= 480 ? value : value.substring(0, 477) + "...";
    }

    private static String diagnostics(Fixture fixture, Item output) {
        return "profession=" + fixture.worker.profession() + " state=" + fixture.worker.workState()
                + " failure=" + fixture.worker.workFailure() + " position=" + fixture.entity.blockPosition()
                + " xyz=" + fixture.entity.position() + " entityTicks=" + fixture.entity.tickCount
                + " activity=" + fixture.worker.activity() + " defending=" + fixture.entity.isDefending()
                + " lastAttacker=" + (fixture.entity.getLastHurtByMob() == null ? null
                : fixture.entity.getLastHurtByMob().getUUID())
                + " target=" + fixture.worker.targetPosition() + " hasEquipment=" + fixture.sawEquipment
                + " targetBuilding=" + fixture.worker.targetBuildingId()
                + " targetBlueprint=" + fixture.worker.targetBlueprintId()
                + " workObserved=" + fixture.sawWork + " enteredWarehouse=" + fixture.sawWarehouseInterior
                + " approachedWork=" + fixture.sawWorkApproach + " carriedOutput=" + fixture.sawCarriedOutput
                + " measuredOutput=" + fixture.measuredOutput + " invariant=" + fixture.invariantFailure
                + " warehouseOutput=" + stock(fixture, output) + " distinctFeet=" + fixture.visited.size()
                + " nav={" + fixture.entity.settlementNavigationDiagnostics() + "}"
                + " attackerVerified=" + (fixture.attackerVerified == null ? "not_applicable"
                : fixture.attackerVerified.getAsBoolean());
    }

    private static void finish(Fixture fixture) {
        fixture.finished = true;
        fixture.settlement.deactivate();
        fixture.entity.stopSettlementNavigation();
        fixture.data.touch();
        for (ChunkPos chunk : fixture.forcedChunks) fixture.level.setChunkForced(chunk.x, chunk.z, false);
        // Preserve the actual result in the test world for a client inspection;
        // only stop the isolated test settlement and release its chunk tickets.
    }

    private static final class Fixture {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final SettlementSavedData data;
        private final SettlementRecord settlement;
        private final CitizenRecord worker;
        private final BlockPos core;
        private final List<ChunkPos> forcedChunks;
        private final Set<BlockPos> visited = new HashSet<>();
        private final Map<Item, Integer> initialTools = new LinkedHashMap<>();
        private BuildingRecord warehouse;
        private BuildingRecord workplace;
        private BlockPos startPosition;
        private SettlementCitizenEntity entity;
        private boolean sawEquipment;
        private boolean sawWarehouseInterior;
        private boolean sawWork;
        private boolean sawWorkApproach;
        private boolean sawCarriedOutput;
        private boolean finished;
        private BooleanSupplier attackerVerified;
        private Supplier<BlockPos> workTarget;
        private Vec3 previousPosition;
        private BlockPos previousWorkTarget;
        private int previousCarriedOutput;
        private int measuredOutput = -1;
        private String invariantFailure = "";
        private boolean lootBasedOutput;
        private List<Vec3> previousLooseOutputPositions = List.of();

        private void requireInvariant(boolean condition, String message) {
            if (!condition && invariantFailure.isEmpty()) invariantFailure = message;
        }

        private Fixture(GameTestHelper helper, ServerLevel level, SettlementSavedData data,
                        SettlementRecord settlement, CitizenRecord worker, BlockPos core,
                        List<ChunkPos> forcedChunks) {
            this.helper = helper;
            this.level = level;
            this.data = data;
            this.settlement = settlement;
            this.worker = worker;
            this.core = core;
            this.forcedChunks = forcedChunks;
        }
    }
}
