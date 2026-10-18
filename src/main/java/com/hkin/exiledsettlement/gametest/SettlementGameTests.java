package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.blueprint.BlueprintPlacementMode;
import com.hkin.exiledsettlement.blueprint.BlueprintBlockStateResolver;
import com.hkin.exiledsettlement.blueprint.BlueprintCancellationService;
import com.hkin.exiledsettlement.blueprint.BlueprintRecord;
import com.hkin.exiledsettlement.blueprint.BlueprintService;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.blueprint.BuildingBlueprintTemplate;
import com.hkin.exiledsettlement.blueprint.ConstructionPlanService;
import com.hkin.exiledsettlement.blueprint.PlayerDesignedTemplates;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.building.BuildingProductionService;
import com.hkin.exiledsettlement.building.BuildingStatus;
import com.hkin.exiledsettlement.building.BuildingFunctionService;
import com.hkin.exiledsettlement.building.WarehouseCategory;
import com.hkin.exiledsettlement.building.WarehouseInventoryService;
import com.hkin.exiledsettlement.building.BuildingDemolitionService;
import com.hkin.exiledsettlement.building.WorksiteAssignmentMode;
import com.hkin.exiledsettlement.citizen.CitizenActivity;
import com.hkin.exiledsettlement.citizen.CitizenProfession;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import com.hkin.exiledsettlement.citizen.CitizenEquipmentService;
import com.hkin.exiledsettlement.citizen.CitizenProjectionService;
import com.hkin.exiledsettlement.citizen.CitizenWorkState;
import com.hkin.exiledsettlement.citizen.ProfessionAssignmentService;
import com.hkin.exiledsettlement.citizen.CitizenWorkService;
import com.hkin.exiledsettlement.citizen.CitizenNavigationReservationService;
import com.hkin.exiledsettlement.citizen.CitizenHabitualRouteService;
import com.hkin.exiledsettlement.citizen.ConstructionAccessPlanService;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.citizen.SettlementCoreInteractionService;
import com.hkin.exiledsettlement.debug.ConstructionDebugService;
import com.hkin.exiledsettlement.client.RtsEdgePan;
import com.hkin.exiledsettlement.client.OverviewMovementMath;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import com.hkin.exiledsettlement.settlement.SettlementLevel;
import com.hkin.exiledsettlement.settlement.SettlementProgressionService;
import com.hkin.exiledsettlement.road.RoadService;
import com.hkin.exiledsettlement.road.RoadSpaceData;
import com.hkin.exiledsettlement.road.RoadTier;
import com.hkin.exiledsettlement.registry.ModItems;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.registry.ModBlocks;
import com.hkin.exiledsettlement.network.PlacementNetworking;
import com.hkin.exiledsettlement.registry.ExiledSettlementTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SettlementGameTests {
    private SettlementGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void newSettlementHasProfessionBudget(GameTestHelper helper) {
        SettlementRecord settlement = settlement();
        helper.assertTrue(settlement.citizens().size() == 6, "New settlement must start with six residents");
        helper.assertTrue(settlement.professionTarget(CitizenProfession.WORKER) == 6,
                "Unassigned residents must begin as builders");
        for (CitizenProfession profession : CitizenProfession.values()) {
            if (profession.specialist()) {
                helper.assertTrue(settlement.professionTarget(profession) == 0,
                        "Specialist quota must start at zero");
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void settlementRoundTripKeepsProfessionTargets(GameTestHelper helper) {
        SettlementRecord original = settlement();
        original.setProfessionTarget(CitizenProfession.WORKER, 4);
        BuildingRecord field = original.addBuilding(BuildingType.FARM_FIELD, new BlockPos(12, 70, 12));
        field.updateFunction(true, BuildingStatus.OPERATIONAL, 2, List.of(field.position()));
        field.migrateWorkerTarget(2);
        CompoundTag saved = original.save(helper.getLevel().registryAccess());
        SettlementRecord loaded = SettlementRecord.load(saved, helper.getLevel().registryAccess());
        helper.assertTrue(loaded.id().equals(original.id()), "Settlement identity changed after save/load");
        helper.assertTrue(loaded.professionTarget(CitizenProfession.FARMER) == 2
                        && loaded.professionTarget(CitizenProfession.WORKER) == 4,
                "Profession quotas did not survive save/load");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void preferredWorksiteAndAssignmentRevisionSurviveSaveLoad(GameTestHelper helper) {
        SettlementRecord original = settlement();
        BlueprintRecord blueprint = original.addBlueprint(BuildingType.HOUSE,
                new BlockPos(12, 70, 12), new BlockPos(12, 70, 6),
                Direction.NORTH, original.ownerId(), false);
        CitizenRecord citizen = original.citizens().iterator().next();
        citizen.setPreferredWorksite(blueprint.id());
        blueprint.markAssignmentChanged();
        blueprint.markAssignmentChanged();
        helper.assertTrue(blueprint.setAssignmentMode(WorksiteAssignmentMode.MANUAL, 2),
                "Blueprint rejected a revision-safe switch to assigned-workers-only mode");
        BuildingRecord demolition = original.addBuilding(BuildingType.HOUSE, new BlockPos(20, 70, 20));
        demolition.queueDemolition();
        helper.assertTrue(demolition.setWorksiteAssignmentMode(WorksiteAssignmentMode.MANUAL, 0),
                "Demolition target rejected assigned-workers-only mode");

        SettlementRecord loaded = SettlementRecord.load(
                original.save(helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
        CitizenRecord loadedCitizen = loaded.citizen(citizen.id());
        BlueprintRecord loadedBlueprint = loaded.blueprint(blueprint.id());
        BuildingRecord loadedDemolition = loaded.building(demolition.id());
        helper.assertTrue(loadedCitizen != null
                        && blueprint.id().equals(loadedCitizen.preferredWorksiteId())
                        && loadedBlueprint != null && loadedBlueprint.assignmentRevision() == 3
                        && loadedBlueprint.assignmentMode() == WorksiteAssignmentMode.MANUAL
                        && loadedDemolition != null
                        && loadedDemolition.worksiteAssignmentMode() == WorksiteAssignmentMode.MANUAL,
                "Manual construction-site assignment mode or its concurrency revision was lost on save/load");

        CompoundTag legacyBlueprintTag = blueprint.save();
        legacyBlueprintTag.remove("AssignmentMode");
        helper.assertTrue(BlueprintRecord.load(legacyBlueprintTag).assignmentMode() == WorksiteAssignmentMode.AUTO,
                "An older blueprint without assignment mode did not migrate to safe automatic scheduling");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void previousSaveLineMigratesWithoutDroppingSettlements(GameTestHelper helper) {
        CompoundTag root = new CompoundTag();
        root.putInt("SchemaVersion", SettlementSavedData.SCHEMA_VERSION - 1);
        ListTag settlements = new ListTag();
        settlements.add(settlement().save(helper.getLevel().registryAccess()));
        root.put("Settlements", settlements);
        SettlementSavedData migrated = SettlementSavedData.load(root, helper.getLevel().registryAccess());
        helper.assertTrue(migrated.settlements().size() == 1,
                "Version 0.14.5 dropped the immediately previous save line instead of migrating it");
        CompoundTag rewritten = migrated.save(new CompoundTag(), helper.getLevel().registryAccess());
        helper.assertTrue(rewritten.getInt("SchemaVersion") == SettlementSavedData.SCHEMA_VERSION,
                "Migrated settlement data was not rewritten at the current schema");

        root.putInt("SchemaVersion", SettlementSavedData.MIN_SUPPORTED_SCHEMA_VERSION - 1);
        SettlementSavedData rejected = SettlementSavedData.load(root, helper.getLevel().registryAccess());
        helper.assertTrue(rejected.settlements().isEmpty(),
                "An unsupported ancient save schema was loaded without a migration path");
        root.putInt("SchemaVersion", SettlementSavedData.SCHEMA_VERSION);
        SettlementSavedData accepted = SettlementSavedData.load(root, helper.getLevel().registryAccess());
        helper.assertTrue(accepted.settlements().size() == 1,
                "Version 0.14.5 rejected its own schema-15 settlement record");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void playerDesignedHouseAndWarehouseMatchApprovedSnapshots(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8)).offset(512, 0, 512);
        BuildingBlueprintTemplate house = BlueprintTemplates.create(
                BuildingType.HOUSE, anchor, Direction.SOUTH, PlayerDesignedTemplates.TEMPLATE_VERSION);
        BuildingBlueprintTemplate warehouse = BlueprintTemplates.create(
                BuildingType.WAREHOUSE, anchor, Direction.EAST, PlayerDesignedTemplates.TEMPLATE_VERSION);
        BuildingBlueprintTemplate houseTwo = BlueprintTemplates.create(
                BuildingType.HOUSE_2, anchor, Direction.EAST, 1);
        assertExactTemplate(helper, house, 11, 9, 9, 458);
        assertExactTemplate(helper, warehouse, 12, 8, 15, 748);
        assertExactTemplate(helper, houseTwo, 10, 12, 13, 698);
        helper.assertTrue(house.entrances().size() == 1
                        && house.entrances().getFirst().outward() == Direction.NORTH,
                "Player-designed house lost its north-side entrance marker");
        helper.assertTrue(warehouse.entrances().size() == 4
                        && warehouse.entrances().stream().map(entry -> entry.outward()).collect(
                        java.util.stream.Collectors.toSet()).equals(Set.of(Direction.WEST, Direction.EAST)),
                "Player-designed warehouse lost one of its two open entrance directions");
        helper.assertTrue(house.placements().stream().filter(p -> p.state().is(Blocks.RED_BED)).count() == 4
                        && house.placements().stream().filter(p -> p.state().is(Blocks.CHEST)).count() == 4,
                "Player-designed house must contain two beds and two double chests");
        helper.assertTrue(warehouse.placements().stream().filter(p -> p.state().is(Blocks.CHEST)).count() == 48,
                "Player-designed warehouse must contain twenty-four double chests");
        helper.assertTrue(houseTwo.placements().stream().filter(p -> p.state().is(Blocks.WHITE_BED)).count() == 6
                        && houseTwo.entrances().getFirst().outward() == Direction.WEST,
                "Player-designed second-tier house must contain three beds and its reviewed primary entrance");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void playerDesignedDirectionalStatesAndEntrancesRotateInAllDirections(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8)).offset(512, 0, 512);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BuildingBlueprintTemplate house = BlueprintTemplates.create(
                    BuildingType.HOUSE, anchor, direction, PlayerDesignedTemplates.TEMPLATE_VERSION);
            var lowerDoor = house.placements().stream()
                    .filter(placement -> placement.state().is(Blocks.OAK_DOOR))
                    .filter(placement -> placement.state().getValue(DoorBlock.HALF)
                            == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)
                    .findFirst().orElseThrow();
            var bed = house.placements().stream()
                    .filter(placement -> placement.state().is(Blocks.RED_BED)).findFirst().orElseThrow();
            helper.assertTrue(lowerDoor.state().getValue(DoorBlock.FACING) == direction
                            && bed.state().getValue(BedBlock.FACING) == direction
                            && house.entrances().getFirst().outward() == direction.getOpposite(),
                    "Player-designed house state or entrance marker did not rotate with " + direction);

            BuildingBlueprintTemplate warehouse = BlueprintTemplates.create(
                    BuildingType.WAREHOUSE, anchor, direction,
                    BlueprintTemplates.currentTemplateVersion(BuildingType.WAREHOUSE));
            helper.assertTrue(warehouse.entrances().stream().map(entry -> entry.outward()).collect(
                            java.util.stream.Collectors.toSet()).equals(Set.of(direction, direction.getOpposite())),
                    "Warehouse open entrances did not rotate with " + direction);
            helper.assertTrue(warehouse.placements().stream().filter(p -> p.state().is(Blocks.CHEST)).allMatch(
                            p -> p.state().getValue(net.minecraft.world.level.block.ChestBlock.FACING)
                                    .getAxis() == direction.getClockWise().getAxis()),
                    "Warehouse chest rows did not rotate with " + direction);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void currentBlueprintRecordsSelectPlayerDesignedTemplates(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8));
        BlueprintRecord house = BlueprintRecord.create(BuildingType.HOUSE, anchor, anchor,
                Direction.SOUTH, UUID.randomUUID(), false);
        BlueprintRecord warehouse = BlueprintRecord.create(BuildingType.WAREHOUSE, anchor, anchor,
                Direction.EAST, UUID.randomUUID(), false);
        BlueprintRecord houseTwo = BlueprintRecord.create(BuildingType.HOUSE_2, anchor, anchor,
                Direction.EAST, UUID.randomUUID(), false);
        BlueprintRecord townHall = BlueprintRecord.create(BuildingType.TOWN_HALL, anchor, anchor,
                Direction.NORTH, UUID.randomUUID(), false);
        helper.assertTrue(house.templateVersion() == 4 && warehouse.templateVersion() == 7
                        && houseTwo.templateVersion() == 1,
                "Current house or warehouse blueprint did not select the player-designed snapshot");
        helper.assertTrue(townHall.templateVersion() == 5,
                "New town halls should select the round-two v5 snapshot");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void warehouseV4AndV5RemainSeparateAndV5FixesEveryRoofStair(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8)).offset(640, 0, 640);
        BuildingBlueprintTemplate previous = BlueprintTemplates.create(
                BuildingType.WAREHOUSE, anchor, Direction.EAST, 4);
        BuildingBlueprintTemplate current = BlueprintTemplates.create(
                BuildingType.WAREHOUSE, anchor, Direction.EAST, 5);
        var previousStairs = previous.placements().stream()
                .filter(placement -> placement.state().is(Blocks.STONE_BRICK_STAIRS)).toList();
        var currentStairs = current.placements().stream()
                .filter(placement -> placement.state().is(Blocks.STONE_BRICK_STAIRS)).toList();
        helper.assertTrue(previousStairs.size() == 120 && currentStairs.size() == 120,
                "Warehouse stair correction changed the structure instead of its states");
        int corrected = 0;
        for (var placement : currentStairs) {
            BlockPos source = placement.position().subtract(anchor).offset(0, 1, 5);
            Half half = placement.state().getValue(net.minecraft.world.level.block.StairBlock.HALF);
            Direction expected = source.getZ() <= 2
                    ? (half == Half.BOTTOM ? Direction.SOUTH : Direction.NORTH)
                    : (half == Half.BOTTOM ? Direction.NORTH : Direction.SOUTH);
            if (placement.state().getValue(net.minecraft.world.level.block.StairBlock.FACING) == expected) corrected++;
        }
        long changed = currentStairs.stream().filter(placement -> previousStairs.stream()
                .filter(old -> old.position().equals(placement.position()))
                .noneMatch(old -> old.state().equals(placement.state()))).count();
        helper.assertTrue(corrected == 120 && changed == 120,
                "Not every warehouse v5 roof stair has the corrected immutable state");

        BlueprintRecord latest = BlueprintRecord.create(BuildingType.WAREHOUSE, anchor, anchor,
                Direction.EAST, UUID.randomUUID(), false);
        CompoundTag oldTag = latest.save();
        oldTag.putInt("StructureVersion", 9);
        oldTag.remove("TemplateVersion");
        BlueprintRecord old = BlueprintRecord.load(oldTag);
        helper.assertTrue(latest.templateVersion() == 7 && old.templateVersion() == 4,
                "New v6 or historical v4 warehouse selection is incorrect");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void constructionPlanCreatesBoundedLocalMaterialPackage(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8)).offset(640, 0, 0);
        BlueprintRecord blueprint = BlueprintRecord.create(BuildingType.WAREHOUSE, anchor, anchor,
                Direction.EAST, UUID.randomUUID(), false);
        var first = BlueprintService.pendingPlacements(helper.getLevel(), blueprint, Set.of(), anchor).stream()
                .filter(candidate -> candidate.placement().material() != null)
                .findFirst().orElseThrow();
        var workPackage = ConstructionPlanService.createWorkPackage(
                helper.getLevel(), blueprint, Set.of(), anchor, first.placement().position());
        helper.assertTrue(!workPackage.isEmpty()
                        && workPackage.placements().size() <= ConstructionPlanService.MAX_PACKAGE_PLACEMENTS,
                "Construction work package is empty or exceeded its bounded size");
        helper.assertTrue(workPackage.placements().stream().allMatch(candidate ->
                        candidate.placement().position().getY() == workPackage.layer()
                                && (blueprint.hasClearanceTarget(candidate.placement().position())
                                || BlueprintService.constructionStageFor(candidate.placement()) == workPackage.stage())
                                && candidate.placement().position().distManhattan(workPackage.seed()) <= 10),
                "Construction work package crossed its stage, layer or local cluster");
        int materialSteps = workPackage.requirements().values().stream().mapToInt(Integer::intValue).sum();
        var nodeTemplate = BlueprintTemplates.create(blueprint);
        var seedNode = nodeTemplate.constructionNode(workPackage.placements().getFirst().placement());
        helper.assertTrue(workPackage.placements().stream().allMatch(p ->
                        seedNode.equals(nodeTemplate.constructionNode(p.placement()))),
                "A work package crossed precomputed construction nodes");
        long expectedMaterialSteps = workPackage.placements().stream()
                .filter(candidate -> candidate.placement().material() != null).count();
        helper.assertTrue(materialSteps == expectedMaterialSteps,
                "Construction work package material list does not match its planned placements");
        var supplyPlan = ConstructionPlanService.createSupplyPlan(
                helper.getLevel(), blueprint, Set.of(), anchor, first.placement().position());
        int suppliedMaterialSteps = supplyPlan.requirements().values().stream()
                .mapToInt(Integer::intValue).sum();
        helper.assertTrue(!supplyPlan.isEmpty()
                        && supplyPlan.plannedPlacements() <= ConstructionPlanService.MAX_SUPPLY_PLACEMENTS
                        && suppliedMaterialSteps == supplyPlan.materialPlacements()
                        && suppliedMaterialSteps > materialSteps,
                "Backpack supply plan stayed limited to one local work package");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void settlementResidentsShareBoundedHabitualRouteHints(GameTestHelper helper) {
        UUID settlementId = UUID.randomUUID();
        BlockPos origin = helper.absolutePos(new BlockPos(4, 3, 4));
        BlockPos destination = origin.offset(80, 0, 40);
        BlockPos waypoint = origin.offset(20, 0, 10);
        long gameTime = helper.getLevel().getGameTime();
        CitizenHabitualRouteService.clear(helper.getLevel());
        CitizenHabitualRouteService.remember(
                helper.getLevel(), settlementId, origin, destination, waypoint, gameTime);
        helper.assertTrue(waypoint.equals(CitizenHabitualRouteService.nextWaypoint(
                        helper.getLevel(), settlementId, origin, destination, gameTime + 1))
                        && CitizenHabitualRouteService.routeCount(helper.getLevel(), gameTime + 1) == 1,
                "Residents in the same settlement and route cells could not reuse a successful waypoint");
        CitizenHabitualRouteService.invalidate(
                helper.getLevel(), settlementId, origin, destination);
        helper.assertTrue(CitizenHabitualRouteService.nextWaypoint(
                        helper.getLevel(), settlementId, origin, destination, gameTime + 2) == null,
                "An obstructed habitual route hint was not invalidated");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void constructionPlacementCannotSealLastResidentExit(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 3, 8)).offset(256, 0, 256);
        BlueprintRecord blueprint = BlueprintRecord.create(
                BuildingType.HOUSE, anchor, anchor, Direction.NORTH, UUID.randomUUID(), false);
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        int floorY = anchor.getY() - 1;
        for (int x = template.minCorner().getX() - 2; x <= template.maxCorner().getX() + 2; x++) {
            for (int z = template.minCorner().getZ() - 2; z <= template.maxCorner().getZ() + 2; z++) {
                helper.getLevel().setBlock(new BlockPos(x, floorY, z), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        BlockPos exit = new BlockPos(template.minCorner().getX(), anchor.getY(),
                (template.minCorner().getZ() + template.maxCorner().getZ()) / 2);
        for (int x = template.minCorner().getX(); x <= template.maxCorner().getX(); x++) {
            for (int z = template.minCorner().getZ(); z <= template.maxCorner().getZ(); z++) {
                if (x != template.minCorner().getX() && x != template.maxCorner().getX()
                        && z != template.minCorner().getZ() && z != template.maxCorner().getZ()) continue;
                BlockPos wall = new BlockPos(x, anchor.getY(), z);
                if (wall.equals(exit)) continue;
                helper.getLevel().setBlock(wall, Blocks.STONE_BRICKS.defaultBlockState(), 2);
                helper.getLevel().setBlock(wall.above(), Blocks.STONE_BRICKS.defaultBlockState(), 2);
            }
        }
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create resident for construction-egress test");
        BlockPos inside = new BlockPos(
                (template.minCorner().getX() + template.maxCorner().getX()) / 2,
                anchor.getY(),
                (template.minCorner().getZ() + template.maxCorner().getZ()) / 2);
        entity.moveTo(inside.getX() + 0.5D, inside.getY(), inside.getZ() + 0.5D, 0.0F, 0.0F);
        entity.setOnGround(true);
        helper.assertTrue(helper.getLevel().addFreshEntity(entity),
                "Could not add construction-egress resident to the test level");
        var closingPlacement = new com.hkin.exiledsettlement.blueprint.BlueprintPlacement(
                exit.above(), Blocks.STONE_BRICKS.defaultBlockState(), Items.STONE_BRICKS,
                BlueprintPlacementMode.STRUCTURE);
        helper.assertTrue(ConstructionAccessPlanService.hasEgressAfterChange(
                        helper.getLevel(), blueprint, inside, null, null),
                "Open two-block doorway did not provide a construction egress route");
        helper.assertTrue(!ConstructionAccessPlanService.hasEgressAfterChange(
                        helper.getLevel(), blueprint, inside,
                        closingPlacement.position(), closingPlacement.state()),
                "Virtual head-height wall placement still reported an impossible egress route");
        helper.assertTrue(!ConstructionAccessPlanService.preservesResidentEgress(
                        helper.getLevel(), blueprint, closingPlacement, entity),
                "Construction planner did not protect a resident inside the closing site");
        var overheadPlacement = new com.hkin.exiledsettlement.blueprint.BlueprintPlacement(
                inside.above(), Blocks.STONE_BRICKS.defaultBlockState(), Items.STONE_BRICKS,
                BlueprintPlacementMode.STRUCTURE);
        helper.assertTrue(!ConstructionAccessPlanService.preservesResidentEgress(
                        helper.getLevel(), blueprint, overheadPlacement, entity),
                "Construction planner allowed a worker to place a solid block through its own head");

        BlockPos elevatedOutside = new BlockPos(
                template.minCorner().getX() - 1, anchor.getY() + 4,
                (template.minCorner().getZ() + template.maxCorner().getZ()) / 2);
        helper.getLevel().setBlock(elevatedOutside.below(),
                Blocks.STONE_BRICKS.defaultBlockState(), 2);
        helper.assertTrue(!ConstructionAccessPlanService.hasEgressAfterChange(
                        helper.getLevel(), blueprint, elevatedOutside, null, null),
                "Crossing the footprint on an elevated roof was mistaken for a ground egress");
        for (int y = anchor.getY(); y <= elevatedOutside.getY(); y++) {
            helper.getLevel().setBlock(new BlockPos(
                    elevatedOutside.getX(), y, elevatedOutside.getZ()),
                    Blocks.SCAFFOLDING.defaultBlockState(), 2);
        }
        helper.assertTrue(ConstructionAccessPlanService.hasEgressAfterChange(
                        helper.getLevel(), blueprint, elevatedOutside, null, null),
                "A real scaffold column from the roof to ground was not accepted as egress");
        entity.discard();
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void playerDesignedHouseV3AndV4RemainSeparate(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8)).offset(512, 0, 512);
        assertExactTemplate(helper, BlueprintTemplates.create(BuildingType.HOUSE, anchor, Direction.EAST, 3),
                11, 9, 9, 416);
        assertExactTemplate(helper, BlueprintTemplates.create(BuildingType.HOUSE, anchor, Direction.EAST, 4),
                11, 9, 9, 458);

        BlueprintRecord current = BlueprintRecord.create(BuildingType.HOUSE, anchor, anchor,
                Direction.EAST, UUID.randomUUID(), false);
        CompoundTag previousTag = current.save();
        previousTag.putInt("StructureVersion", BlueprintRecord.PLAYER_DESIGNED_STRUCTURE_VERSION);
        previousTag.remove("TemplateVersion");
        BlueprintRecord previous = BlueprintRecord.load(previousTag);
        helper.assertTrue(current.templateVersion() == 4 && previous.templateVersion() == 3,
                "Structure version six was silently reinterpreted as the new house snapshot");
        helper.assertTrue(BlueprintTemplates.create(previous).placements().stream()
                        .filter(placement -> placement.mode() == BlueprintPlacementMode.STRUCTURE).count() == 416,
                "Reloaded version-three house no longer resolves to its original immutable snapshot");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void constructionOrderFollowsAuthoredPhasesThenLayers(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8)).offset(512, 0, 512);
        BlueprintRecord blueprint = BlueprintRecord.create(BuildingType.HOUSE, anchor, anchor,
                Direction.EAST, UUID.randomUUID(), false);
        List<BlueprintService.PendingPlacement> pending = BlueprintService.pendingPlacements(
                helper.getLevel(), blueprint, Set.of(), anchor);
        int previousLayer = Integer.MIN_VALUE;
        int previousStage = Integer.MIN_VALUE;
        int previousPhase = Integer.MIN_VALUE;
        var template = BlueprintTemplates.create(blueprint);
        for (BlueprintService.PendingPlacement placement : pending) {
            int phase = template.constructionPhase(placement.placement());
            helper.assertTrue(phase >= previousPhase, "Authored construction phase order was ignored");
            if (phase != previousPhase) {
                previousLayer = Integer.MIN_VALUE;
                previousStage = Integer.MIN_VALUE;
                previousPhase = phase;
            }
            int layer = placement.placement().position().getY();
            int stage = BlueprintService.constructionStageFor(placement.placement());
            helper.assertTrue(layer >= previousLayer,
                    "Construction returned to a lower layer after starting a higher one");
            if (layer == previousLayer) {
                helper.assertTrue(stage >= previousStage,
                        "One layer did not finish ordinary structure before its functional blocks");
            } else {
                previousLayer = layer;
                previousStage = Integer.MIN_VALUE;
            }
            previousStage = stage;
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void authoredSequencesCoverBuildingsRotateAndKeepEntrancesLast(GameTestHelper helper) {
        for (BuildingType type : BuildingType.values()) {
            if (!BlueprintTemplates.supports(type)) continue;
            helper.assertTrue(com.hkin.exiledsettlement.blueprint.ConstructionSequence.hasProfile(type),
                    "Missing authored sequence for " + type);
            if (type.isDynamicArea()) continue;
            java.util.Map<Integer, Long> northCounts = null;
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                var template = BlueprintTemplates.create(type, BlockPos.ZERO, facing);
                var counts = template.placements().stream().collect(java.util.stream.Collectors.groupingBy(
                        template::constructionPhase, java.util.stream.Collectors.counting()));
                if (northCounts == null) northCounts = counts;
                helper.assertTrue(northCounts.equals(counts), "Rotating " + type + " changed its construction phases");
                for (var placement : template.placements()) {
                    if (placement.state().getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
                        helper.assertTrue(template.constructionPhase(placement) == 5,
                                "Entrance no longer belongs to the final closing phase");
                    }
                }
            }
        }
        helper.assertTrue(!com.hkin.exiledsettlement.blueprint.ConstructionSequence.hasProfile(BuildingType.WAGON),
                "Unconfigured content must retain the generic planner fallback");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void farmerBatchesProduceAndKeepsOneSeedStack(GameTestHelper helper) {
        helper.getLevel().setDayTime(6000L);
        SettlementRecord settlement = settlement();
        BuildingRecord farm = settlement.addBuilding(BuildingType.FARM_FIELD,
                helper.absolutePos(new BlockPos(8, 10, 8)));
        farm.updateFunction(true, BuildingStatus.OPERATIONAL, 2, List.of(farm.position()));
        CitizenRecord farmer = settlement.citizens().iterator().next();
        farmer.assignProfession(CitizenProfession.FARMER, farm.id());
        farmer.insertIntoInventory(new ItemStack(Items.WHEAT_SEEDS, 64));
        farmer.insertIntoInventory(new ItemStack(Items.WHEAT, 2));
        helper.assertTrue(!CitizenWorkService.farmerDeliveryReady(helper.getLevel(), settlement, farmer),
                "Farmer attempted a warehouse trip for only two harvested items");
        int deliverySlot = CitizenWorkService.farmerDeliverySlot(settlement, farmer);
        helper.assertTrue(deliverySlot >= 0 && farmer.inventoryItem(deliverySlot).is(Items.WHEAT),
                "Farmer selected the protected seed reserve instead of harvested produce");
        farmer.insertIntoInventory(new ItemStack(Items.WHEAT, 126));
        helper.assertTrue(!CitizenWorkService.farmerDeliveryReady(helper.getLevel(), settlement, farmer),
                "Farmer still delivers after only two stacks");
        farmer.insertIntoInventory(new ItemStack(Items.WHEAT, 384));
        helper.assertTrue(!CitizenWorkService.farmerDeliveryReady(helper.getLevel(), settlement, farmer),
                "Eight stacks triggered daytime delivery before the backpack filled");
        helper.getLevel().setDayTime(23000L + CitizenWorkService.scheduleOffset(farmer));
        helper.assertTrue(CitizenWorkService.farmerDeliveryReady(helper.getLevel(), settlement, farmer), "Rest did not enable personal delivery");
        helper.getLevel().setDayTime(6000L);
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void builderIdleStillReservesConstructionStock(GameTestHelper helper) {
        SettlementRecord settlement = settlement();
        var worker = settlement.citizens().iterator().next();
        worker.assignProfession(CitizenProfession.WORKER, null);
        var anchor = helper.absolutePos(new BlockPos(8, 5, 8));
        var site = settlement.addBlueprint(BuildingType.HOUSE, anchor, anchor, Direction.NORTH, UUID.randomUUID(), false);
        var material = BlueprintTemplates.create(site).placements().stream().filter(p -> p.material() != null)
                .findFirst().orElseThrow().material();
        worker.clearWork();
        helper.assertTrue(CitizenWorkService.protectedConstructionMaterial(settlement, worker, new ItemStack(material, 32)),
                "Idle scheduler gap released construction supplies to warehouse delivery");
        settlement.removeBlueprint(site.id());
        helper.assertTrue(!CitizenWorkService.protectedConstructionMaterial(settlement, worker, new ItemStack(material, 32)),
                "Finished site kept materials reserved forever");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void sameSiteSharesRealStockAndPreservesLastUnit(GameTestHelper helper) {
        var settlement = settlement();
        var workers = new java.util.ArrayList<>(settlement.citizens());
        var donor = workers.get(0); var receiver = workers.get(1);
        var anchor = helper.absolutePos(new BlockPos(8, 5, 8));
        var site = settlement.addBlueprint(BuildingType.HOUSE, anchor, anchor, Direction.NORTH, UUID.randomUUID(), false);
        donor.startTask(CitizenWorkState.TO_BLUEPRINT, anchor, null, site.id());
        receiver.startTask(CitizenWorkState.TO_BLUEPRINT, anchor.east(), null, site.id());
        var projections = new java.util.ArrayList<com.hkin.exiledsettlement.citizen.SettlementCitizenEntity>();
        for (var record : java.util.List.of(donor, receiver)) {
            var entity = com.hkin.exiledsettlement.registry.ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
            var position = helper.absolutePos(new BlockPos(2 + projections.size(), 3, 2));
            entity.moveTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5, 0, 0);
            entity.getPersistentData().putString(com.hkin.exiledsettlement.citizen.CitizenProjectionService.CITIZEN_ID_TAG, record.id().toString());
            entity.getPersistentData().putString(com.hkin.exiledsettlement.citizen.CitizenProjectionService.SETTLEMENT_ID_TAG, settlement.id().toString());
            helper.getLevel().addFreshEntity(entity);
            record.updateProjection(entity.getUUID(), position);
            projections.add(entity);
        }
        donor.insertIntoInventory(new ItemStack(Items.STONE_BRICKS, 32));
        helper.assertTrue(CitizenWorkService.shareSiteMaterial(helper.getLevel(), settlement, receiver, Items.STONE_BRICKS, 64) == 31,
                "Same-site construction did not share its existing batch");
        helper.assertTrue(donor.inventoryItem(0).getCount() == 1 && receiver.inventoryItem(0).getCount() == 31,
                "Sharing lost, duplicated or took the donor's last unit");
        helper.assertTrue(CitizenWorkService.shareSiteMaterial(helper.getLevel(), settlement, receiver, Items.STONE_BRICKS, 64) == 0,
                "Repeated sharing stole the donor's reserved last unit");
        donor.insertIntoInventory(new ItemStack(Items.STONE_BRICKS, 32));
        projections.getFirst().setPos(projections.getFirst().getX() + 32, projections.getFirst().getY(), projections.getFirst().getZ());
        helper.assertTrue(CitizenWorkService.shareSiteMaterial(helper.getLevel(), settlement, receiver, Items.STONE_BRICKS, 64) == 0,
                "Opposite-bank inventory was remotely transferred");
        projections.forEach(net.minecraft.world.entity.Entity::discard);
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void warehouseKeeperMergesRealStacksOnlyAtStation(GameTestHelper helper) {
        BlockPos barrelPos = helper.absolutePos(new BlockPos(8, 10, 8));
        BlockPos station = barrelPos.east();
        helper.getLevel().setBlock(barrelPos, Blocks.BARREL.defaultBlockState(), 3);
        helper.getLevel().setBlock(station.below(), Blocks.STONE.defaultBlockState(), 3);
        Container barrel = (Container) helper.getLevel().getBlockEntity(barrelPos);
        barrel.setItem(0, new ItemStack(Items.STONE_BRICKS, 7));
        barrel.setItem(1, new ItemStack(Items.STONE_BRICKS, 11));
        var warehouse = BuildingRecord.create(BuildingType.WAREHOUSE, barrelPos);
        warehouse.updateFunction(true, BuildingStatus.OPERATIONAL, 27, List.of(barrelPos));
        var entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        entity.setPos(station.getX() + 12.5, station.getY(), station.getZ() + .5);
        helper.assertTrue(WarehouseInventoryService.mergeStacksForResident(helper.getLevel(), warehouse, entity) == 0,
                "Unreserved resident compacted inventory remotely");
        entity.setPos(station.getX() + .5, station.getY(), station.getZ() + .5);
        helper.assertTrue(WarehouseInventoryService.mergeStacksForResident(helper.getLevel(), warehouse, entity) == 11
                        && barrel.getItem(0).getCount() == 18 && barrel.getItem(1).isEmpty(),
                "Warehouse compaction failed conservation");
        helper.assertTrue(CitizenProfession.forWorkplace(BuildingType.WAREHOUSE) == CitizenProfession.WAREHOUSE_KEEPER
                        && warehouse.workerCapacity() == 1 && warehouse.capacity() == 27,
                "Warehouse storage slots leaked into job capacity");
        CitizenNavigationReservationService.releaseAll(helper.getLevel(), entity.getUUID());
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void reassignedBuilderHandsStockToOldSiteWithoutDuplication(GameTestHelper helper) {
        helper.getLevel().setDayTime(6000L);
        var settlement = settlement();
        var workers = new java.util.ArrayList<>(settlement.citizens());
        var donor = workers.get(0); var receiver = workers.get(1);
        var anchor = helper.absolutePos(new BlockPos(8, 5, 8));
        var old = settlement.addBlueprint(BuildingType.HOUSE, anchor, anchor, Direction.NORTH, UUID.randomUUID(), false);
        var next = settlement.addBlueprint(BuildingType.HOUSE, anchor.east(20), anchor.east(20), Direction.NORTH, UUID.randomUUID(), false);
        donor.assignProfession(CitizenProfession.WORKER, null);
        receiver.assignProfession(CitizenProfession.WORKER, null);
        donor.startTask(CitizenWorkState.TO_BLUEPRINT, anchor, null, old.id());
        receiver.startTask(CitizenWorkState.TO_BLUEPRINT, anchor, null, old.id());
        var material = BlueprintTemplates.create(old).placements().stream().filter(p -> p.material() != null)
                .findFirst().orElseThrow().material();
        donor.insertIntoInventory(new ItemStack(material, 32));
        var data = new SettlementSavedData();
        CitizenWorkService.tickSettlement(helper.getLevel(), data, settlement);
        donor.clearWork();
        donor.startTask(CitizenWorkState.TO_BLUEPRINT, next.anchor(), null, next.id());
        CitizenWorkService.tickSettlement(helper.getLevel(), data, settlement);
        int a = 0, b = 0;
        for (int i = 0; i < CitizenRecord.INVENTORY_SIZE; i++) {
            if (donor.inventoryItem(i).is(material)) a += donor.inventoryItem(i).getCount();
            if (receiver.inventoryItem(i).is(material)) b += receiver.inventoryItem(i).getCount();
        }
        helper.assertTrue(b > 0 && a + b == 32, "Site handoff lost or duplicated supplies: " + a + "/" + b);
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void anchorV2TemplatesMatchApprovedExactDrafts(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8)).offset(512, 0, 512);
        assertExactTemplate(helper, BlueprintTemplates.create(BuildingType.HOUSE, anchor, Direction.NORTH, 2),
                9, 9, 8, 223);
        assertExactTemplate(helper, BlueprintTemplates.create(BuildingType.TOWN_HALL, anchor, Direction.NORTH, 2),
                15, 13, 12, 584);
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void anchorV2DirectionalStatesRotateInAllFourDirections(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8));
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BuildingBlueprintTemplate house = BlueprintTemplates.create(
                    BuildingType.HOUSE, anchor, direction, 2);
            var lowerDoor = house.placements().stream()
                    .filter(placement -> placement.state().is(Blocks.SPRUCE_DOOR))
                    .filter(placement -> placement.state().getValue(DoorBlock.HALF)
                            == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)
                    .findFirst().orElseThrow();
            var bed = house.placements().stream()
                    .filter(placement -> placement.state().is(Blocks.RED_BED)).findFirst().orElseThrow();
            helper.assertTrue(lowerDoor.state().getValue(DoorBlock.FACING) == direction.getOpposite()
                            && bed.state().getValue(BedBlock.FACING) == direction,
                    "Door or bed state did not rotate with v2 house facing " + direction);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void buildingTemplateVersionRoundTripKeepsV1AndV2Separate(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8));
        BuildingBlueprintTemplate v2 = BlueprintTemplates.create(BuildingType.HOUSE, anchor, Direction.EAST, 2);
        BuildingRecord current = BuildingRecord.create(BuildingType.HOUSE, anchor, Direction.EAST,
                v2.minCorner(), v2.maxCorner(), v2.transitionMargin(), 2);
        BuildingRecord loadedCurrent = BuildingRecord.load(current.save());
        helper.assertTrue(loadedCurrent.templateVersion() == 2
                        && BlueprintTemplates.createForBuilding(loadedCurrent).placements().stream()
                        .filter(placement -> placement.mode() == BlueprintPlacementMode.STRUCTURE).count() == 223,
                "A new v2 building lost its template version after save/load");

        CompoundTag legacyTag = current.save();
        legacyTag.remove("TemplateVersion");
        BuildingRecord loadedLegacy = BuildingRecord.load(legacyTag);
        long legacyBlocks = BlueprintTemplates.createForBuilding(loadedLegacy).placements().stream()
                .filter(placement -> placement.mode() == BlueprintPlacementMode.STRUCTURE).count();
        helper.assertTrue(loadedLegacy.templateVersion() == 1 && legacyBlocks != 223,
                "A legacy building without TemplateVersion was silently reinterpreted as v2");
        helper.succeed();
    }

    private static void assertExactTemplate(GameTestHelper helper, BuildingBlueprintTemplate template,
                                            int width, int height, int depth, long structureBlocks) {
        long actualBlocks = template.placements().stream()
                .filter(placement -> placement.mode() == BlueprintPlacementMode.STRUCTURE).count();
        helper.assertTrue(template.width() == width && template.height() == height && template.depth() == depth,
                "V2 template dimensions do not match the approved exact draft for " + template.type());
        helper.assertTrue(actualBlocks == structureBlocks,
                "V2 template block count mismatch for " + template.type()
                        + ": expected " + structureBlocks + ", actual " + actualBlocks);
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void legacyStockpileDataIsRetiredSafely(GameTestHelper helper) {
        SettlementRecord original = settlement();
        CompoundTag saved = original.save(helper.getLevel().registryAccess());
        BlockPos retiredPosition = new BlockPos(12, 70, 12);
        CompoundTag oldBuilding = new CompoundTag();
        oldBuilding.putString("Id", UUID.randomUUID().toString());
        oldBuilding.putString("Type", "stockpile");
        oldBuilding.putLong("Position", retiredPosition.asLong());
        oldBuilding.putBoolean("Active", true);
        ListTag buildings = saved.getList("Buildings", Tag.TAG_COMPOUND);
        buildings.add(oldBuilding);
        saved.put("Buildings", buildings);

        SettlementRecord loaded = SettlementRecord.load(saved, helper.getLevel().registryAccess());
        helper.assertTrue(loaded.buildings().isEmpty(), "Legacy stockpile remained a functional building");
        helper.assertTrue(loaded.retiredBlockPositions().contains(retiredPosition),
                "Legacy stockpile controller was not queued for safe cleanup");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void allCurrentBuildingsHaveGroundedTemplates(GameTestHelper helper) {
        BlockPos anchor = new BlockPos(0, 70, 0);
        for (BuildingType type : BuildingType.values()) {
            if (type == BuildingType.WAGON || type.isDynamicArea()) continue;
            BuildingBlueprintTemplate template = BlueprintTemplates.create(type, anchor, Direction.NORTH);
            int minimumStructureY = template.placements().stream()
                    .filter(p -> p.mode() == BlueprintPlacementMode.STRUCTURE)
                    .mapToInt(p -> p.position().getY()).min().orElseThrow();
            helper.assertTrue(minimumStructureY == anchor.getY() - 1,
                    type + " foundation is not embedded into the selected surface");
            if (type != BuildingType.TOWN_HALL) {
                helper.assertTrue(template.placements().stream()
                                .noneMatch(p -> p.state().getBlock() instanceof com.hkin.exiledsettlement.block.SettlementBuildingBlock),
                        type + " template still contains a visible controller");
            }
        }
        BuildingBlueprintTemplate warehouse = BlueprintTemplates.create(BuildingType.WAREHOUSE, anchor, Direction.NORTH);
        long chests = warehouse.placements().stream().filter(p -> p.state().is(Blocks.CHEST)).count();
        helper.assertTrue(chests == 48,
                "Player-designed warehouse must expose twenty-four double chests");
        for (BuildingType areaType : List.of(
                BuildingType.FARM_FIELD, BuildingType.PASTURE_AREA, BuildingType.TREE_FARM)) {
            helper.assertTrue(areaType.isDynamicArea() && areaType.minimumAreaSize() > 0,
                    areaType + " is missing its variable-area catalog policy");
            int size = areaType.minimumAreaSize();
            BuildingBlueprintTemplate area = BlueprintTemplates.createArea(areaType, anchor,
                    anchor.offset(size - 1, 0, size - 1), Direction.NORTH);
            helper.assertTrue(area.width() == size && area.depth() == size,
                    areaType + " minimum variable-area template could not be created");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void variableFarmIncludesFenceGateAndIrrigation(GameTestHelper helper) {
        BuildingBlueprintTemplate farm = BlueprintTemplates.createArea(
                BuildingType.FARM_FIELD, new BlockPos(0, 70, 0), new BlockPos(6, 70, 6), Direction.NORTH);
        long farmland = farm.placements().stream().filter(p -> p.state().is(Blocks.FARMLAND)).count();
        long containedWater = farm.placements().stream().filter(p -> p.state().is(Blocks.OAK_SLAB)
                && !p.state().getFluidState().isEmpty()).count();
        long roads = farm.placements().stream().filter(p -> p.state().is(Blocks.GRAVEL)).count();
        long fences = farm.placements().stream().filter(p -> p.state().is(Blocks.OAK_FENCE)).count();
        long gates = farm.placements().stream().filter(p -> p.state().is(Blocks.OAK_FENCE_GATE)).count();
        helper.assertTrue(farmland == 16, "A 7x7 v4 farm must retain sixteen crop cells around its internal roads");
        helper.assertTrue(containedWater == 1 && roads == 8,
                "A 7x7 v4 farm must contain irrigation and expose a connected internal cross-road");
        helper.assertTrue(fences == 20 && gates == 4 && farm.entrances().size() == 4,
                "A new 7x7 farm must expose one safe entrance on every side");
        int firstTill = Integer.MAX_VALUE;
        int firstFence = Integer.MAX_VALUE;
        for (int index = 0; index < farm.placements().size(); index++) {
            BlueprintPlacementMode mode = farm.placements().get(index).mode();
            if (mode == BlueprintPlacementMode.FARM_TILL) firstTill = Math.min(firstTill, index);
            if (farm.placements().get(index).state().is(Blocks.OAK_FENCE)
                    || farm.placements().get(index).state().is(Blocks.OAK_FENCE_GATE)) {
                firstFence = Math.min(firstFence, index);
            }
        }
        helper.assertTrue(farm.placements().stream().noneMatch(p -> p.state().is(Blocks.WATER))
                        && farm.placements().stream().filter(p -> p.state().is(Blocks.OAK_SLAB))
                        .allMatch(p -> p.mode() == BlueprintPlacementMode.STRUCTURE)
                        && farm.placements().stream().filter(p -> p.state().is(Blocks.FARMLAND))
                        .allMatch(p -> p.mode() == BlueprintPlacementMode.FARM_TILL)
                        && firstFence < firstTill,
                "Farm construction must place the perimeter before tilling, without flowing water");

        BuildingBlueprintTemplate legacy = BlueprintTemplates.createArea(
                BuildingType.FARM_FIELD, new BlockPos(0, 70, 0), new BlockPos(6, 70, 6), Direction.NORTH, 2);
        helper.assertTrue(legacy.placements().stream().anyMatch(p -> p.state().is(Blocks.WATER))
                        && legacy.placements().stream().noneMatch(p -> p.state().is(Blocks.GRAVEL)),
                "The version-two farm snapshot changed instead of remaining loadable for existing saves");
        helper.assertTrue(!(ModBlocks.USELESS_SETTLEMENT_CORE.get()
                        instanceof com.hkin.exiledsettlement.block.SettlementCoreBlock),
                "The visual-only core accidentally gained settlement authority");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void openTrapdoorsAreNotResidentStandingSupports(GameTestHelper helper) {
        BlockPos support = helper.absolutePos(new BlockPos(4, 3, 4));
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create resident for trapdoor support regression");
        helper.getLevel().setBlock(support, Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.HALF, Half.TOP)
                .setValue(TrapDoorBlock.OPEN, false), 3);
        helper.assertTrue(entity.canStandAtPosition(support.above()),
                "A closed horizontal trapdoor was rejected as a usable top surface");
        helper.getLevel().setBlock(support, helper.getLevel().getBlockState(support)
                .setValue(TrapDoorBlock.OPEN, true), 3);
        helper.assertTrue(!entity.canStandAtPosition(support.above()),
                "An open vertical trapdoor was still treated as solid construction footing");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void sawmillTemplateProfessionAndSpeciesRecipesAreConnected(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8)).offset(256, 0, 256);
        BuildingBlueprintTemplate sawmill = BlueprintTemplates.create(
                BuildingType.SAWMILL, anchor, Direction.NORTH);
        long containers = sawmill.placements().stream()
                .filter(placement -> placement.state().hasBlockEntity()
                        && placement.state().is(Blocks.BARREL)).count();
        CitizenRecord sawyer = new CitizenRecord(UUID.randomUUID(), "Sawyer", anchor);
        sawyer.assignProfession(CitizenProfession.SAWYER, null);
        helper.assertTrue(sawmill.width() == 27 && sawmill.depth() == 19 && containers == 4
                        && CitizenProfession.forWorkplace(BuildingType.SAWMILL) == CitizenProfession.SAWYER
                        && CitizenEquipmentService.missingEquipment(sawyer) == Items.IRON_AXE
                        && CitizenWorkService.sawmillLogBatchLimit() == 256
                        && CitizenWorkService.sawmillOutput(Items.OAK_LOG) == Items.OAK_PLANKS
                        && CitizenWorkService.sawmillOutput(Items.CHERRY_WOOD) == Items.CHERRY_PLANKS
                        && CitizenWorkService.sawmillOutput(Items.WARPED_STEM) == Items.WARPED_PLANKS,
                "Sawmill template, batch size, carpenter loadout, or same-species recipe mapping is disconnected");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void treeFarmUsesExplicitAreaCorners(GameTestHelper helper) {
        BuildingBlueprintTemplate treeFarm = BlueprintTemplates.createArea(
                BuildingType.TREE_FARM, new BlockPos(0, 70, 0), new BlockPos(8, 70, 8), Direction.NORTH, 1);
        long corners = treeFarm.placements().stream().filter(p -> p.state().is(Blocks.OAK_FENCE)).count();
        helper.assertTrue(corners == 4, "Tree farm must have four explicit boundary corner posts");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void buildersCanClaimDifferentBlocksOnOneBlueprint(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8));
        SettlementRecord settlement = settlement();
        BlueprintRecord blueprint = settlement.addBlueprint(BuildingType.HOUSE,
                anchor, anchor, Direction.NORTH, settlement.ownerId(), false);
        SettlementSavedData data = new SettlementSavedData();
        var first = BlueprintService.nextUnclaimedPending(helper.getLevel(), data, blueprint, Set.of());
        helper.assertTrue(first != null, "Blueprint did not expose a first construction step");
        var second = BlueprintService.nextUnclaimedPending(
                helper.getLevel(), data, blueprint, Set.of(first.placement().position()));
        helper.assertTrue(second != null && !second.placement().position().equals(first.placement().position()),
                "A second builder could not reserve a different block on the same blueprint");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void constructionSchedulingUsesWorkerLocalFoundationTask(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8));
        BlueprintRecord blueprint = BlueprintRecord.create(BuildingType.HOUSE, anchor, anchor,
                Direction.NORTH, UUID.randomUUID(), false);
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        for (var placement : template.placements()) {
            if (placement.mode() == BlueprintPlacementMode.OPTIONAL_SUPPORT) {
                helper.getLevel().setBlock(placement.position(), Blocks.STONE.defaultBlockState(), 2);
            } else if (placement.mode() == BlueprintPlacementMode.CLEARANCE) {
                helper.getLevel().setBlock(placement.position(), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        var foundations = template.placements().stream()
                .filter(placement -> placement.mode() == BlueprintPlacementMode.STRUCTURE)
                .filter(placement -> placement.position().getY() == anchor.getY() - 1)
                .toList();
        helper.assertTrue(!foundations.isEmpty(), "House template has no foundation tasks");
        for (var foundation : foundations) {
            helper.getLevel().setBlock(foundation.position(), Blocks.AIR.defaultBlockState(), 2);
        }
        BlockPos workerPosition = foundations.getLast().position();
        var pending = BlueprintService.nextUnclaimedPending(
                helper.getLevel(), new SettlementSavedData(), blueprint, Set.of(), workerPosition);
        helper.assertTrue(pending != null && pending.placement().position().equals(workerPosition),
                "Construction scheduler still followed template scan order instead of the worker-local foundation task");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void constructedGlassPaneConnectsToExistingWall(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8));
        UUID owner = UUID.randomUUID();
        SettlementSavedData data = new SettlementSavedData();
        SettlementRecord settlement = new SettlementRecord(
                UUID.randomUUID(), "Pane Shape Test", anchor, owner);
        BlueprintRecord blueprint = BlueprintRecord.create(
                BuildingType.HOUSE, anchor, anchor, Direction.NORTH, owner, false);
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        var pane = template.placements().stream()
                .filter(placement -> placement.state().is(Blocks.GLASS_PANE))
                .findFirst().orElseThrow();
        Direction connection = null;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighborPosition = pane.position().relative(direction);
            var neighbor = template.placements().stream()
                    .filter(placement -> placement.mode() == BlueprintPlacementMode.STRUCTURE)
                    .filter(placement -> placement.position().equals(neighborPosition))
                    .filter(placement -> !placement.state().is(Blocks.GLASS_PANE))
                    .findFirst().orElse(null);
            if (neighbor != null) {
                helper.getLevel().setBlock(neighborPosition, neighbor.state(), 3);
                connection = direction;
                break;
            }
        }
        helper.assertTrue(connection != null, "Glass pane has no adjacent template wall for shape test");
        ItemStack supplied = new ItemStack(Items.GLASS_PANE);
        var result = BlueprintService.placeAt(
                helper.getLevel(), data, settlement, blueprint, pane.position(), supplied);
        helper.assertTrue(result.success(), "Glass pane construction step was rejected: " + result.reason());
        var actual = helper.getLevel().getBlockState(pane.position());
        boolean connected = switch (connection) {
            case NORTH -> actual.getValue(CrossCollisionBlock.NORTH);
            case EAST -> actual.getValue(CrossCollisionBlock.EAST);
            case SOUTH -> actual.getValue(CrossCollisionBlock.SOUTH);
            case WEST -> actual.getValue(CrossCollisionBlock.WEST);
            default -> false;
        };
        helper.assertTrue(actual.is(Blocks.GLASS_PANE) && connected,
                "Constructed glass pane kept its disconnected default shape beside a wall");
        helper.getLevel().setBlock(pane.position(), Blocks.GLASS_PANE.defaultBlockState(), 2);
        helper.assertTrue(BlueprintBlockStateResolver.refreshExisting(helper.getLevel(), pane)
                        && !helper.getLevel().getBlockState(pane.position())
                        .equals(Blocks.GLASS_PANE.defaultBlockState()),
                "Existing disconnected glass pane was not repaired from its surrounding wall");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void citizenProjectionDisplaysInventoryArmor(GameTestHelper helper) {
        CitizenRecord citizen = new CitizenRecord(UUID.randomUUID(), "Armored Resident", BlockPos.ZERO);
        citizen.insertIntoInventory(new ItemStack(Items.IRON_CHESTPLATE));
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create resident projection");
        CitizenEquipmentService.syncVisibleEquipment(citizen, entity);
        helper.assertTrue(entity.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                        && citizen.equipmentItem(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE),
                "Daily projection wore armor or lost personal ownership");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void residentsAcquireAndDisplayAvailableArmor(GameTestHelper helper) {
        SettlementRecord settlement = settlement();
        settlement.setCreativeCoreInstalled(true);
        CitizenRecord citizen = settlement.citizens().iterator().next();
        helper.assertTrue(CitizenEquipmentService.nextEquipment(helper.getLevel(), settlement, citizen)
                        == Items.IRON_HELMET,
                "Creative settlement did not offer the first missing wearable armor slot");
        citizen.insertIntoInventory(new ItemStack(Items.IRON_HELMET));
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create resident projection for armor acquisition test");
        CitizenEquipmentService.syncVisibleEquipment(citizen, entity);
        helper.assertTrue(entity.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                        && citizen.equipmentItem(EquipmentSlot.HEAD).is(Items.IRON_HELMET),
                "Acquired armor was worn in peace or was not kept as personal equipment");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void cancelledBlueprintPersistsAndStopsConstruction(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8));
        BlueprintRecord blueprint = BlueprintRecord.create(BuildingType.HOUSE, anchor, anchor,
                Direction.NORTH, UUID.randomUUID(), false);
        BlockPos footingPosition = BlueprintTemplates.create(blueprint).placements().stream()
                .filter(candidate -> candidate.mode() == BlueprintPlacementMode.CLEARANCE)
                .map(candidate -> candidate.position()).findFirst().orElseThrow();
        helper.getLevel().setBlock(footingPosition, Blocks.COBBLESTONE.defaultBlockState(), 2);
        helper.assertTrue(BlueprintService.pendingAt(helper.getLevel(), blueprint, footingPosition) != null,
                "An unregistered block inside the worksite was not treated as a clearance obstruction");
        helper.assertTrue(blueprint.addTemporaryFooting(footingPosition, Items.COBBLESTONE)
                        && BlueprintService.pendingAt(helper.getLevel(), blueprint, footingPosition) == null,
                "A registered temporary footing blocked internal worksite traversal");
        helper.assertTrue(blueprint.queueCancellation(), "Blueprint did not accept its first cancellation request");
        BlueprintRecord loaded = BlueprintRecord.load(blueprint.save());
        helper.assertTrue(loaded.cancellationQueued()
                        && loaded.temporaryFootingItem(footingPosition) == Items.COBBLESTONE,
                "Blueprint cancellation or temporary footing source was lost after save/load");
        helper.assertTrue(BlueprintService.nextPending(helper.getLevel(), new SettlementSavedData(), loaded) == null,
                "Cancelled blueprint still exposed a construction placement");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void cancelledBlueprintCleanupOnlyMatchesPlacedTemplateBlocks(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8));
        BlueprintRecord blueprint = BlueprintRecord.create(BuildingType.HOUSE, anchor, anchor,
                Direction.NORTH, UUID.randomUUID(), false);
        var placement = BlueprintTemplates.create(blueprint).placements().stream()
                .filter(candidate -> candidate.mode() == BlueprintPlacementMode.STRUCTURE)
                .findFirst().orElseThrow();
        var footing = BlueprintTemplates.create(blueprint).placements().stream()
                .filter(candidate -> candidate.mode() == BlueprintPlacementMode.CLEARANCE)
                .findFirst().orElseThrow();
        helper.getLevel().setBlock(placement.position(), placement.state(), 2);
        helper.getLevel().setBlock(footing.position(), Blocks.COBBLESTONE.defaultBlockState(), 2);
        blueprint.addTemporaryFooting(footing.position(), Items.COBBLESTONE);
        blueprint.queueCancellation();
        var cleanup = BlueprintCancellationService.nextCleanup(helper.getLevel(), blueprint, Set.of());
        helper.assertTrue(cleanup != null && cleanup.position().equals(placement.position()),
                "Cancellation did not preserve temporary access until the built structure was cleaned");
        helper.getLevel().setBlock(placement.position(), Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
        cleanup = BlueprintCancellationService.nextCleanup(helper.getLevel(), blueprint, Set.of());
        helper.assertTrue(cleanup != null && cleanup.position().equals(footing.position()),
                "Cleanup did not protect a player-modified structure block or recover the temporary footing last");
        helper.getLevel().setBlock(footing.position(), Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
        helper.assertTrue(BlueprintCancellationService.nextCleanup(helper.getLevel(), blueprint, Set.of()) == null
                        && blueprint.temporaryFootings().isEmpty(),
                "Cleanup tried to delete a player-modified footing or kept its stale temporary record");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void completedStructureWaitsForTemporaryFootingRecovery(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8));
        BlueprintRecord blueprint = BlueprintRecord.create(BuildingType.HOUSE, anchor, anchor,
                Direction.NORTH, UUID.randomUUID(), false);
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        BlockPos footingPosition = template.placements().stream()
                .filter(candidate -> candidate.mode() == BlueprintPlacementMode.CLEARANCE)
                .map(candidate -> candidate.position()).findFirst().orElseThrow();
        for (var placement : template.placements()) {
            helper.getLevel().setBlock(placement.position(),
                    placement.mode() == BlueprintPlacementMode.CLEARANCE
                            ? Blocks.AIR.defaultBlockState() : placement.state(), 2);
        }
        helper.getLevel().setBlock(footingPosition, Blocks.COBBLESTONE.defaultBlockState(), 2);
        blueprint.addTemporaryFooting(footingPosition, Items.COBBLESTONE);
        var incomplete = template.placements().stream().filter(placement -> {
            var current = helper.getLevel().getBlockState(placement.position());
            if (placement.mode() == BlueprintPlacementMode.CLEARANCE) {
                return !placement.position().equals(footingPosition) && !current.isAir();
            }
            if (placement.mode() == BlueprintPlacementMode.OPTIONAL_SUPPORT) return current.isAir();
            return !BlueprintService.matchesPlacedState(current, placement.state());
        }).findFirst().orElse(null);
        helper.assertTrue(BlueprintService.constructionComplete(helper.getLevel(), blueprint),
                "A registered internal footing made an otherwise completed structure incomplete: "
                        + (incomplete == null ? "unknown" : incomplete.mode() + " at " + incomplete.position()
                        + " expected " + incomplete.state() + " found "
                        + helper.getLevel().getBlockState(incomplete.position())));
        var result = BlueprintService.placeNext(helper.getLevel(), new SettlementSavedData(),
                settlement(), blueprint, ItemStack.EMPTY);
        helper.assertTrue(result.success() && result.value() == null,
                "Completed structure registered before its temporary footing was recovered");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void constructionUsesReachableAdjacentStandingGeometry(GameTestHelper helper) {
        BlockPos target = new BlockPos(8, 70, 8);
        helper.assertTrue(CitizenWorkService.constructionReachGeometry(target.offset(2, -1, 0), target),
                "An adjacent safe construction position was outside resident placement reach");
        helper.assertTrue(CitizenWorkService.constructionWorkStationGeometry(target.offset(3, -1, 0), target)
                        && CitizenWorkService.constructionReachGeometry(target.offset(4, -1, 0), target)
                        && !CitizenWorkService.constructionReachGeometry(target.offset(5, -1, 0), target),
                "Construction interaction did not preserve the normal four-block boundary");
        helper.assertTrue(!CitizenWorkService.constructionReachGeometry(target.offset(6, 2, 0), target),
                "An implausibly distant construction position was accepted");
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create resident for footing-height policy test");
        entity.moveTo(8.5D, 70.0D, 8.5D, 0.0F, 0.0F);
        helper.assertTrue(!CitizenWorkService.requiresElevatedConstructionAccess(entity, target)
                        && !CitizenWorkService.requiresElevatedConstructionAccess(entity, target.above())
                        && CitizenWorkService.requiresElevatedConstructionAccess(entity, target.above(2)),
                "Same-level construction requested a temporary footing or high construction did not");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "segmented_navigation", timeoutTicks = 160)
    public static void residentUsesBoundedSegmentsBeyondDirectPathRange(GameTestHelper helper) {
        // This real navigation corridor extends beyond the tiny GameTest template.
        // Keep it away from neighboring tests, whose structures otherwise cut the path.
        BlockPos feet = helper.absolutePos(new BlockPos(24000, 12, 28000));
        BlockPos target = feet.offset(25, 0, 0);
        setForcedChunksBetween(helper.getLevel(), feet.offset(-16, 0, -16), target.offset(16, 0, 16), true);
        for (int x = -1; x <= 26; x++) {
            for (int z = -1; z <= 1; z++) {
                helper.getLevel().setBlock(feet.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create resident for segmented-navigation test");
        setForcedChunksBetween(helper.getLevel(), feet.offset(-16, 0, -16), target.offset(16, 0, 16), true);
        entity.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
        entity.setOnGround(true);
        entity.restrictTo(feet.offset(24, 0, 0), 64);
        helper.assertTrue(helper.getLevel().addFreshEntity(entity),
                "Could not add segmented-navigation resident to the test level");
        BlockPos firstSegment = feet.offset(22, 0, 0);
        double startX = entity.getX();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(entity.tickCount > 0 && entity.onGround(),
                        "Waiting for entity tracking and actual contact with the corridor floor"))
                .thenExecute(() -> {
                    helper.assertTrue(entity.canStandAtPosition(firstSegment),
                            "The generated 22-block intermediate position was not safe to stand on");
                    helper.assertTrue(entity.hasReachablePathTo(firstSegment),
                            "Vanilla navigation rejected the safe 22-block intermediate position");
                    helper.assertTrue(entity.hasReachablePathTo(target),
                            "A far work position was rejected before the resident entered its local path window");
                    String failure = entity.navigateTo(target, 0.65D);
                    helper.assertTrue(failure.isEmpty() && entity.hasLongDistanceWaypoint(),
                            "Resident did not create a bounded intermediate waypoint beyond the direct path window");
                })
                .thenWaitUntil(() -> {
                    String retryFailure = entity.navigateTo(target, 0.65D);
                    helper.assertTrue(retryFailure.isEmpty() && entity.getX() >= startX + 4.0D,
                            "Resident did not make visible progress along refreshed long-distance segments: x="
                                    + entity.getX() + ", failure=" + retryFailure
                                    + ", noAi=" + entity.isNoAi()
                                    + ", added=" + (helper.getLevel().getEntity(entity.getId()) == entity)
                                    + ", tickCount=" + entity.tickCount
                                    + ", onGround=" + entity.onGround()
                                    + ", navigationDone=" + entity.getNavigation().isDone()
                                    + ", path=" + entity.getNavigation().getPath()
                                    + ", nextIndex=" + (entity.getNavigation().getPath() == null ? -1
                                    : entity.getNavigation().getPath().getNextNodeIndex())
                                    + ", nextNode=" + (entity.getNavigation().getPath() == null ? null
                                    : entity.getNavigation().getPath().getNextNodePos())
                                    + ", moveWanted=" + entity.getMoveControl().hasWanted()
                                    + ", wantedX=" + entity.getMoveControl().getWantedX()
                                    + ", waypoint=" + entity.hasLongDistanceWaypoint()
                                    + ", velocity=" + entity.getDeltaMovement());
                })
                .thenExecute(() -> {
                    entity.discard();
                    setForcedChunksBetween(helper.getLevel(), feet.offset(-16, 0, -16), target.offset(16, 0, 16), false);
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void farmingUsesFastCadenceAndSeparateWorkerZones(GameTestHelper helper) {
        UUID farmerId = UUID.fromString("00000000-0000-0000-0000-000000000123");
        int readyWindows = 0;
        for (long gameTime = 0; gameTime < 200; gameTime += CitizenWorkService.WORK_INTERVAL_TICKS) {
            if (CitizenWorkService.farmActionReadyAt(gameTime, farmerId)) readyWindows++;
        }
        BlockPos min = new BlockPos(0, 70, 0);
        BlockPos max = new BlockPos(16, 70, 14);
        helper.assertTrue(readyWindows == 20,
                "Farmer did not receive one action window every 10 ticks");
        helper.assertTrue(CitizenWorkService.farmWorkerZone(new BlockPos(1, 70, 7), min, max, 2) == 0
                        && CitizenWorkService.farmWorkerZone(new BlockPos(15, 70, 7), min, max, 2) == 1,
                "Two farmers were not assigned opposite contiguous halves of a large field");
        helper.assertTrue(CitizenWorkService.farmSerpentineIndex(
                        new BlockPos(1, 70, 1), min, max) == 0
                        && CitizenWorkService.farmSerpentineIndex(
                        new BlockPos(15, 70, 2), min, max) < CitizenWorkService.farmSerpentineIndex(
                        new BlockPos(14, 70, 2), min, max),
                "Farm row package did not reverse alternate rows for a continuous serpentine route");

        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create resident for construction-target cooldown test");
        BlockPos blocked = new BlockPos(8, 70, 8);
        entity.deferConstructionTarget(blocked, 200L);
        helper.assertTrue(entity.constructionTargetDeferred(blocked)
                        && !entity.constructionTargetDeferred(blocked.east()),
                "A blocked construction target was not isolated from other available work");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void constructionRouteIsRejectedAfterThreeFailuresForCurrentTask(GameTestHelper helper) {
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create resident for construction-route insurance test");
        UUID task = UUID.randomUUID();
        BlockPos target = helper.absolutePos(new BlockPos(8, 3, 8));
        BlockPos blockedRoute = target.west(4);
        BlockPos alternateRoute = target.east(4);

        entity.beginConstructionRouteTask(task, target);
        entity.trackConstructionRoute(blockedRoute);
        helper.assertTrue(entity.recordTrackedConstructionRouteFailure() == 1
                        && entity.constructionRouteAllowed(target, blockedRoute),
                "A route must remain eligible after its first failure");
        helper.assertTrue(entity.recordTrackedConstructionRouteFailure() == 2
                        && entity.constructionRouteAllowed(target, blockedRoute),
                "A route must remain eligible after its second failure");

        // Selecting another placement and later returning must not erase the
        // failure budget for the original target and physical route.
        entity.beginConstructionRouteTask(task, target.north());
        entity.beginConstructionRouteTask(task, target);
        entity.trackConstructionRoute(blockedRoute);
        helper.assertTrue(entity.recordTrackedConstructionRouteFailure() == 3
                        && entity.trackedConstructionRouteExhausted()
                        && !entity.constructionRouteAllowed(target, blockedRoute),
                "A route was not rejected after its third failure");
        helper.assertTrue(entity.constructionRouteAllowed(target.north(), blockedRoute),
                "An exhausted target route incorrectly rejected another block in the same blueprint task");
        helper.assertTrue(entity.recordTrackedConstructionRouteFailure() == 3,
                "An exhausted route was counted beyond the three-failure limit");
        helper.assertTrue(entity.constructionRouteAllowed(target, alternateRoute),
                "Rejecting one route incorrectly rejected an alternate route");

        // The blacklist belongs to this blueprint work order, not to this world
        // coordinate forever.
        entity.beginConstructionRouteTask(UUID.randomUUID(), target);
        helper.assertTrue(entity.constructionRouteAllowed(target, blockedRoute),
                "A later construction task inherited an expired route blacklist");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void constructionStepRejectsLowEaveBeforePlacingFooting(GameTestHelper helper) {
        BlockPos feet = helper.absolutePos(new BlockPos(8, 3, 8));
        BlockPos stepSupport = feet.east();
        BlockPos standing = stepSupport.above();
        helper.getLevel().setBlock(feet.below(), Blocks.STONE.defaultBlockState(), 2);
        helper.getLevel().setBlock(stepSupport, Blocks.COBBLESTONE.defaultBlockState(), 2);
        helper.getLevel().setBlock(feet.above(2), Blocks.STONE.defaultBlockState(), 2);
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create construction-headroom resident");
        entity.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
        helper.assertTrue(!entity.canStepOntoAdjacentConstructionBlock(standing),
                "Special construction climb accepted a route through a low eave");
        helper.getLevel().setBlock(feet.above(2), Blocks.AIR.defaultBlockState(), 2);
        helper.assertTrue(entity.canStepOntoAdjacentConstructionBlock(standing),
                "Safe one-block construction climb was rejected after clearing headroom");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void exteriorFootingPlannerDetoursAroundLowEaveWithReturnChain(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(20, 3, 20));
        SettlementRecord settlement = new SettlementRecord(
                UUID.randomUUID(), "Footing Route Test", anchor.offset(0, 0, -12), UUID.randomUUID());
        BlueprintRecord blueprint = BlueprintRecord.create(BuildingType.HOUSE,
                anchor, anchor, Direction.NORTH, settlement.ownerId(), false);
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        BlockPos target = template.placements().stream()
                .filter(placement -> placement.position().getX() == template.minCorner().getX())
                .max(java.util.Comparator.comparingInt(placement -> placement.position().getY()))
                .orElseThrow().position();
        BlockPos firstStanding = new BlockPos(template.minCorner().getX() - 1,
                anchor.getY() + 1, target.getZ());
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create exterior-route planning resident");
        entity.moveTo(firstStanding.getX() + 0.5D, firstStanding.getY(),
                firstStanding.getZ() + 0.5D, 0.0F, 0.0F);
        helper.assertTrue(CitizenWorkService.hasPlannedExteriorFootingRoute(
                        helper.getLevel(), settlement, blueprint, entity, firstStanding, target, 5),
                "A clear exterior staircase with a reversible predecessor chain was rejected");
        helper.getLevel().setBlock(firstStanding.above(2), Blocks.STONE.defaultBlockState(), 2);
        helper.assertTrue(CitizenWorkService.hasPlannedExteriorFootingRoute(
                        helper.getLevel(), settlement, blueprint, entity, firstStanding, target, 5),
                "Planner did not use a same-height exterior catwalk to detour around a low eave");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void exteriorFootingPlannerStopsBeforeStarvingServerTick(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(20, 3, 20));
        SettlementRecord settlement = new SettlementRecord(
                UUID.randomUUID(), "Bounded Footing Test", anchor.offset(0, 0, -12), UUID.randomUUID());
        BlueprintRecord blueprint = BlueprintRecord.create(BuildingType.HOUSE,
                anchor, anchor, Direction.NORTH, settlement.ownerId(), false);
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        BlockPos firstStanding = new BlockPos(template.minCorner().getX() - 1,
                anchor.getY() + 1, anchor.getZ());
        BlockPos unreachableHighTarget = firstStanding.above(48);
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create resident for bounded footing search test");
        entity.moveTo(firstStanding.getX() + 0.5D, firstStanding.getY(),
                firstStanding.getZ() + 0.5D, 0.0F, 0.0F);
        helper.assertTrue(!CitizenWorkService.hasPlannedExteriorFootingRoute(
                        helper.getLevel(), settlement, blueprint, entity,
                        firstStanding, unreachableHighTarget, 64),
                "Footing planner exceeded its bounded search to chase an unreachable high target");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void completedWorkplacesFinalizeWithoutAnActiveBuilder(GameTestHelper helper) {
        BlockPos houseAnchor = helper.absolutePos(new BlockPos(8, 10, 8)).offset(512, 0, 0);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        var created = data.createOrRecover(houseAnchor.offset(0, 0, -8),
                UUID.randomUUID(), "Completion Recovery Test");
        helper.assertTrue(created.success() && created.value() != null,
                "Could not create settlement for completed-building recovery test");
        SettlementRecord settlement = created.value();
        UUID owner = settlement.ownerId();
        BlueprintRecord house = settlement.addBlueprint(BuildingType.HOUSE,
                houseAnchor, houseAnchor, Direction.NORTH, owner, false);
        BuildingBlueprintTemplate houseTemplate = BlueprintTemplates.create(house);
        placeCompletedTemplate(helper, houseTemplate);
        var visibleMismatch = houseTemplate.placements().stream()
                .filter(placement -> placement.mode() == BlueprintPlacementMode.STRUCTURE)
                .findFirst().orElseThrow();
        house.setProgressIndex(houseTemplate.placements().size());
        helper.getLevel().setBlock(visibleMismatch.position(), Blocks.AIR.defaultBlockState(), 3);
        helper.assertTrue(BlueprintService.completedPlacementCount(helper.getLevel(), house)
                        < houseTemplate.placements().size(),
                "Management progress trusted the non-linear scheduling cursor instead of the actual world blocks");
        helper.getLevel().setBlock(visibleMismatch.position(), visibleMismatch.state(), 3);
        setTemplateDoorsOpen(helper, houseTemplate);
        data.touch();

        BlueprintService.tickLevel(helper.getLevel());

        BuildingRecord registeredHouse = settlement.buildingAt(houseTemplate.registrationPosition());
        helper.assertTrue(settlement.blueprint(house.id()) == null
                        && registeredHouse != null && registeredHouse.operational()
                        && registeredHouse.templateVersion() == 4,
                "Completed house remained a construction blueprint or did not become operational");

        data.unregisterBuilding(houseTemplate.registrationPosition());
        BlueprintRecord townHall = settlement.addBlueprint(BuildingType.TOWN_HALL,
                houseAnchor, houseAnchor, Direction.NORTH, owner, false);
        BuildingBlueprintTemplate townHallTemplate = BlueprintTemplates.create(townHall);
        placeCompletedTemplate(helper, townHallTemplate);
        setTemplateDoorsOpen(helper, townHallTemplate);
        townHall.setProgressIndex(townHallTemplate.placements().size());
        data.touch();

        BlueprintService.tickLevel(helper.getLevel());

        BuildingRecord registeredTownHall = settlement.buildingAt(townHallTemplate.registrationPosition());
        helper.assertTrue(settlement.blueprint(townHall.id()) == null
                        && registeredTownHall != null && registeredTownHall.operational()
                        && registeredTownHall.templateVersion() == 5,
                "Completed town hall remained a construction blueprint or did not become operational");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void completedPlayerDesignedWarehouseRegistersAllContainers(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8)).offset(768, 0, 0);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        var created = data.createOrRecover(anchor.offset(-8, 0, 0),
                UUID.randomUUID(), "Player Warehouse Test");
        helper.assertTrue(created.success() && created.value() != null,
                "Could not create settlement for player-designed warehouse test");
        SettlementRecord settlement = created.value();
        BlueprintRecord blueprint = settlement.addBlueprint(BuildingType.WAREHOUSE,
                anchor, anchor, Direction.EAST, settlement.ownerId(), false);
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        placeCompletedTemplate(helper, template);
        blueprint.setProgressIndex(template.placements().size());
        data.touch();

        var incomplete = template.placements().stream().filter(placement -> {
            var current = helper.getLevel().getBlockState(placement.position());
            if (placement.mode() == BlueprintPlacementMode.CLEARANCE) return !current.isAir();
            if (placement.mode() == BlueprintPlacementMode.OPTIONAL_SUPPORT) return current.isAir();
            return !BlueprintService.matchesPlacedState(current, placement.state());
        }).findFirst().orElse(null);
        helper.assertTrue(incomplete == null,
                "Completed player-designed warehouse has a state mismatch at "
                        + (incomplete == null ? "unknown" : incomplete.position() + " expected "
                        + incomplete.state() + " found "
                        + helper.getLevel().getBlockState(incomplete.position())));

        BlueprintService.tickLevel(helper.getLevel());
        BuildingRecord warehouse = settlement.buildingAt(template.registrationPosition());
        helper.assertTrue(warehouse != null, "Completed player-designed warehouse was not registered");
        BuildingFunctionService.validateSingle(helper.getLevel(), data, settlement, warehouse);
        helper.assertTrue(warehouse.operational() && warehouse.templateVersion() == 7,
                "Player-designed warehouse did not become an operational v7 building");
        helper.assertTrue(warehouse.servicePositions().size() == 48 && warehouse.capacity() == 48 * 27,
                "Player-designed warehouse did not register all forty-eight empty chest halves");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void residentConstructionFinishesDoubleChestPartnerBeforeRepairingState(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8)).offset(896, 0, 0);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        var created = data.createOrRecover(anchor.offset(-8, 0, 0),
                UUID.randomUUID(), "Double Chest Construction Test");
        helper.assertTrue(created.success() && created.value() != null,
                "Could not create settlement for double-chest construction test");
        SettlementRecord settlement = created.value();
        BlueprintRecord blueprint = settlement.addBlueprint(BuildingType.WAREHOUSE,
                anchor, anchor, Direction.EAST, settlement.ownerId(), false);
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        placeCompletedTemplate(helper, template);

        var first = template.placements().stream()
                .filter(placement -> placement.state().getBlock()
                        instanceof net.minecraft.world.level.block.ChestBlock)
                .filter(placement -> placement.state().getValue(
                        net.minecraft.world.level.block.ChestBlock.TYPE)
                        != net.minecraft.world.level.block.state.properties.ChestType.SINGLE)
                .findFirst().orElseThrow();
        var firstType = first.state().getValue(net.minecraft.world.level.block.ChestBlock.TYPE);
        Direction facing = first.state().getValue(net.minecraft.world.level.block.ChestBlock.FACING);
        Direction connected = net.minecraft.world.level.block.ChestBlock.getConnectedDirection(first.state());
        BlockPos partnerPosition = first.position().relative(connected);
        var partner = template.placements().stream()
                .filter(placement -> placement.position().equals(partnerPosition))
                .findFirst().orElseThrow();
        helper.getLevel().setBlock(partnerPosition, Blocks.AIR.defaultBlockState(), 3);
        helper.getLevel().setBlock(first.position(), first.state().setValue(
                net.minecraft.world.level.block.ChestBlock.TYPE,
                net.minecraft.world.level.block.state.properties.ChestType.SINGLE), 3);

        BlueprintService.PendingPlacement pending = BlueprintService.nextUnclaimedPending(
                helper.getLevel(), data, blueprint, Set.of(), first.position());
        helper.assertTrue(pending != null && pending.placement().position().equals(partnerPosition),
                "Scheduler retried the occupied intermediate chest half before its missing partner");
        ItemStack chest = new ItemStack(Items.CHEST);
        var result = BlueprintService.placeAt(helper.getLevel(), data, settlement,
                blueprint, partnerPosition, chest);
        helper.assertTrue(result.success() && chest.isEmpty()
                        && BlueprintService.matchesPlacedState(
                        helper.getLevel().getBlockState(first.position()), first.state())
                        && BlueprintService.matchesPlacedState(
                        helper.getLevel().getBlockState(partner.position()), partner.state()),
                "Placing the missing chest half did not repair both reviewed double-chest states");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void blueprintClearancePersistsAndProducesRealDrops(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos core = helper.absolutePos(new BlockPos(8, 10, 8));
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        var created = data.createOrRecover(core, player.getUUID(), "Clearance Test");
        helper.assertTrue(created.success() && created.value() != null,
                "Could not create settlement for clearance test");
        SettlementRecord settlement = created.value();
        BlockPos anchor = core.offset(8, 0, 0);
        BuildingBlueprintTemplate template = BlueprintTemplates.create(BuildingType.HOUSE_2, anchor, Direction.EAST);
        BlockPos obstruction = template.placements().stream()
                .filter(placement -> placement.mode() == BlueprintPlacementMode.CLEARANCE)
                .map(com.hkin.exiledsettlement.blueprint.BlueprintPlacement::position)
                .findFirst().orElseThrow();
        helper.getLevel().setBlock(obstruction, Blocks.GRASS_BLOCK.defaultBlockState(), 3);

        var placed = BlueprintService.placeBlueprint(helper.getLevel(), player.getUUID(),
                BuildingType.HOUSE_2, anchor, Direction.EAST, false, null);
        helper.assertTrue(placed.success() && placed.value() != null
                        && placed.value().hasClearanceTarget(obstruction),
                "A tagged terrain obstruction did not become a persisted clearance task");
        BlueprintRecord reloaded = BlueprintRecord.load(placed.value().save());
        helper.assertTrue(reloaded.hasClearanceTarget(obstruction)
                        && reloaded.clearanceOriginalState(obstruction).is(Blocks.GRASS_BLOCK),
                "Clearance original state did not survive blueprint save/load");

        SettlementCitizenEntity worker = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(worker != null, "Could not create a worker for clearance test");
        var cleared = BlueprintService.clearAt(helper.getLevel(), data, settlement, placed.value(),
                obstruction, worker, new ItemStack(Items.IRON_SHOVEL));
        helper.assertTrue(cleared.success() && helper.getLevel().getBlockState(obstruction).isAir()
                        && !placed.value().hasClearanceTarget(obstruction)
                        && !cleared.value().isEmpty(),
                "Resident clearance did not remove the exact target and return normal drops");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void clearanceUsesChangedCurrentBlockAndReturnsItsDrops(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(8, 10, 8));
        SettlementRecord settlement = new SettlementRecord(UUID.randomUUID(), "Changed Clearance",
                target, UUID.randomUUID());
        BlueprintRecord blueprint = settlement.addBlueprint(BuildingType.HOUSE, target, target,
                Direction.NORTH, settlement.ownerId(), false,
                java.util.Map.of(target, Blocks.GRASS_BLOCK.defaultBlockState()));
        helper.getLevel().setBlock(target, Blocks.DIRT.defaultBlockState(), 3);
        SettlementCitizenEntity worker = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(worker != null, "Could not create a worker for changed-clearance test");
        var cleared = BlueprintService.clearAt(helper.getLevel(), new SettlementSavedData(), settlement,
                blueprint, target, worker, ItemStack.EMPTY);
        helper.assertTrue(cleared.success() && helper.getLevel().getBlockState(target).isAir()
                        && !blueprint.hasClearanceTarget(target)
                        && cleared.value().stream().mapToInt(s -> s.is(Items.DIRT) ? s.getCount() : 0).sum() == 1,
                "Clearance failed to remove the current player-modified block and return its real loot");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void playerCompletedClearanceTargetDoesNotFreezeBlueprint(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8)).offset(896, 0, 0);
        BuildingBlueprintTemplate template = BlueprintTemplates.create(
                BuildingType.HOUSE_2, anchor, Direction.NORTH);
        var completedByPlayer = template.placements().stream()
                .filter(placement -> placement.mode() == BlueprintPlacementMode.STRUCTURE)
                .filter(placement -> !placement.state().hasBlockEntity())
                .findFirst().orElseThrow();
        BlueprintRecord blueprint = BlueprintRecord.create(BuildingType.HOUSE_2, anchor, anchor,
                Direction.NORTH, UUID.randomUUID(), false,
                java.util.Map.of(completedByPlayer.position(), Blocks.GRASS_BLOCK.defaultBlockState()));
        helper.getLevel().setBlock(completedByPlayer.position(), completedByPlayer.state(), 3);

        BlueprintService.nextPending(helper.getLevel(), new SettlementSavedData(), blueprint);

        helper.assertTrue(!blueprint.hasClearanceTarget(completedByPlayer.position()),
                "A player-completed final block remained in the clearance list and froze completion");
        helper.assertTrue(BlueprintService.matchesPlacedState(
                        helper.getLevel().getBlockState(completedByPlayer.position()), completedByPlayer.state()),
                "Clearance reconciliation changed a player-completed final block");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void legacyFootingCanRewindAFullProgressBlueprint(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8));
        BlueprintRecord blueprint = BlueprintRecord.create(BuildingType.HOUSE,
                anchor, anchor, Direction.NORTH, UUID.randomUUID(), false);
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        placeCompletedTemplate(helper, template);
        var obstructed = template.placements().stream()
                .filter(placement -> placement.mode() == BlueprintPlacementMode.STRUCTURE)
                .filter(placement -> placement.state().is(Blocks.OAK_PLANKS))
                .findFirst().orElseThrow();
        helper.getLevel().setBlock(obstructed.position(), Blocks.COBBLESTONE.defaultBlockState(), 3);
        blueprint.addTemporaryFooting(obstructed.position(), Items.COBBLESTONE);
        blueprint.setProgressIndex(template.placements().size());
        helper.assertTrue(BlueprintService.constructionComplete(helper.getLevel(), blueprint)
                        && BlueprintService.readyForFootingCleanup(helper.getLevel(), blueprint),
                "An old internal footing did not enter the cleanup-before-resume state");

        helper.getLevel().setBlock(obstructed.position(), Blocks.AIR.defaultBlockState(), 3);
        blueprint.removeTemporaryFooting(obstructed.position());
        var pending = BlueprintService.nextPending(helper.getLevel(), new SettlementSavedData(), blueprint);
        helper.assertTrue(pending != null && pending.placement().position().equals(obstructed.position()),
                "A full persisted progress index hid the structure block exposed by footing cleanup");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void constructionFootingsAvoidFutureStructureCells(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8));
        BlueprintRecord blueprint = BlueprintRecord.create(BuildingType.HOUSE, anchor, anchor,
                Direction.NORTH, UUID.randomUUID(), false);
        BuildingBlueprintTemplate template = BlueprintTemplates.create(blueprint);
        BlockPos structure = template.placements().stream()
                .filter(placement -> placement.mode().isFinalStructure())
                .filter(placement -> !placement.state().isAir())
                .map(com.hkin.exiledsettlement.blueprint.BlueprintPlacement::position)
                .findFirst().orElseThrow();
        BlockPos insideAir = template.placements().stream()
                .filter(placement -> placement.mode() == BlueprintPlacementMode.CLEARANCE)
                .map(com.hkin.exiledsettlement.blueprint.BlueprintPlacement::position)
                .findFirst().orElseThrow();
        BlockPos outside = new BlockPos(template.minCorner().getX() - 1,
                anchor.getY(), template.minCorner().getZ());
        helper.assertTrue(!CitizenWorkService.isConstructionFootingCandidate(blueprint, structure)
                        && !CitizenWorkService.isConstructionFootingCandidate(blueprint, insideAir)
                        && !CitizenWorkService.isExteriorConstructionFooting(blueprint, insideAir)
                        && CitizenWorkService.isConstructionFootingCandidate(blueprint, outside)
                        && CitizenWorkService.isExteriorConstructionFooting(blueprint, outside),
                "Scaffolding must stay outside the building footprint, including interior air cells");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void temporaryFootingLineagePersistsAndCleansLeafFirst(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8));
        BlueprintRecord blueprint = BlueprintRecord.create(BuildingType.HOUSE, anchor, anchor,
                Direction.NORTH, UUID.randomUUID(), false);
        BlockPos root = anchor.offset(-6, 0, 0);
        BlockPos child = root.offset(1, 1, 0);
        helper.getLevel().setBlock(root, Blocks.COBBLESTONE.defaultBlockState(), 3);
        helper.getLevel().setBlock(child, Blocks.COBBLESTONE.defaultBlockState(), 3);
        helper.assertTrue(blueprint.addTemporaryFooting(root, Items.COBBLESTONE)
                        && blueprint.addTemporaryFooting(child, Items.COBBLESTONE, root),
                "Could not create a parented construction footing chain");
        BlueprintRecord loaded = BlueprintRecord.load(blueprint.save());
        helper.assertTrue(root.equals(loaded.temporaryFootingParent(child))
                        && loaded.temporaryFootingOrder(child) > loaded.temporaryFootingOrder(root),
                "Construction footing parent/order was lost after save/load");
        var cleanup = BlueprintCancellationService.nextCleanup(helper.getLevel(), loaded, Set.of());
        helper.assertTrue(cleanup != null && cleanup.position().equals(child),
                "Construction cleanup removed a supporting parent before its leaf");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void completedBuilderCanContinueWithFootingCleanup(GameTestHelper helper) {
        UUID blueprintId = UUID.randomUUID();
        BlockPos buildTarget = new BlockPos(8, 70, 8);
        BlockPos footingTarget = buildTarget.offset(2, 1, 0);
        CitizenRecord citizen = new CitizenRecord(UUID.randomUUID(), "Cleanup Builder", buildTarget);
        helper.assertTrue(citizen.startTask(
                        CitizenWorkState.TO_BLUEPRINT, buildTarget, null, blueprintId)
                        && citizen.continueWithBlueprintCleanup(blueprintId, footingTarget)
                        && citizen.workState() == CitizenWorkState.TO_DEMOLITION
                        && blueprintId.equals(citizen.targetBlueprintId())
                        && footingTarget.equals(citizen.targetPosition()),
                "The last builder could not hand construction directly to persistent footing cleanup");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty",
            batch = "construction_navigation", timeoutTicks = 60)
    public static void residentReachesGroundLevelConstructionStation(GameTestHelper helper) {
        BlockPos feet = helper.absolutePos(new BlockPos(6, 2, 3));
        BlockPos target = helper.absolutePos(new BlockPos(5, 1, 7));
        for (BlockPos ground : BlockPos.betweenClosed(
                helper.absolutePos(new BlockPos(1, 1, 1)),
                helper.absolutePos(new BlockPos(11, 1, 11)))) {
            helper.getLevel().setBlock(ground, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
        }
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create resident for construction-station path test");
        setForcedChunksBetween(helper.getLevel(), feet, target, true);
        entity.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
        entity.setOnGround(true);
        entity.restrictTo(helper.absolutePos(new BlockPos(6, 2, 6)), 32);
        helper.assertTrue(helper.getLevel().addFreshEntity(entity),
                "Could not add construction-navigation resident to the test level");
        BlockPos station = CitizenWorkService.findConstructionWorkPosition(helper.getLevel(), entity, target);
        helper.assertTrue(station != null && !station.below().equals(target)
                        && CitizenWorkService.constructionWorkStationGeometry(station, target),
                "Resident standing four blocks from a ground-level target found no safe construction station");
        helper.startSequence()
                .thenExecute(() -> {
                    helper.assertTrue(entity.hasReachablePathTo(station),
                            "Resident could not find a direct path to the safe construction station at " + station);
                    helper.assertTrue(entity.navigateTo(station, 0.70D).isEmpty(),
                            "Resident could not begin walking to the safe construction station at " + station);
                })
                .thenWaitUntil(() -> helper.assertTrue(entity.isStandingAtConstructionPosition(station),
                        "Resident did not continuously walk to the safe ground-level construction station: pos="
                                + entity.position() + ", station=" + station
                                + ", noAi=" + entity.isNoAi()
                                + ", added=" + (helper.getLevel().getEntity(entity.getId()) == entity)
                                + ", tickCount=" + entity.tickCount
                                + ", onGround=" + entity.onGround()
                                + ", navigationDone=" + entity.getNavigation().isDone()
                                + ", path=" + entity.getNavigation().getPath()
                                + ", velocity=" + entity.getDeltaMovement()))
                .thenExecute(() -> {
                    entity.discard();
                    setForcedChunksBetween(helper.getLevel(), feet, target, false);
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 160)
    public static void residentPathfinderCanClimbTemporaryBlockSteps(GameTestHelper helper) {
        BlockPos feet = helper.absolutePos(new BlockPos(5, 2, 5));
        BlockPos firstStep = feet.east();
        BlockPos secondStep = firstStep.east().above();
        setForcedChunksBetween(helper.getLevel(), feet, secondStep, true);
        for (BlockPos ground : List.of(feet.below(), firstStep.below(), secondStep.below().below())) {
            helper.getLevel().setBlock(ground, Blocks.STONE.defaultBlockState(), 2);
        }
        // The persisted GameTest world may contain blocks left by an older
        // fixture at these absolute coordinates. Define the complete walking
        // volume instead of assuming the template supplied air here.
        for (BlockPos clear : List.of(feet, feet.above(), firstStep.above(), firstStep.above(2),
                secondStep.below(), secondStep.above(), secondStep.above(2))) {
            helper.getLevel().setBlock(clear, Blocks.AIR.defaultBlockState(), 2);
        }
        helper.getLevel().setBlock(firstStep, Blocks.COBBLESTONE.defaultBlockState(), 2);
        helper.getLevel().setBlock(secondStep, Blocks.COBBLESTONE.defaultBlockState(), 2);
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create resident for temporary-step path test");
        entity.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
        entity.setOnGround(true);
        helper.assertTrue(helper.getLevel().addFreshEntity(entity),
                "Could not add temporary-step resident to the test level");
        helper.assertTrue(entity.canStandAtPosition(feet)
                        && entity.canStandAtPosition(firstStep.above())
                        && entity.canStandAtPosition(secondStep.above()),
                "Temporary blocks did not create valid resident standing positions");
        helper.assertTrue(entity.canStepOntoAdjacentConstructionBlock(firstStep.above()),
                "Resident could not begin the first temporary construction step");
        helper.startSequence()
                .thenExecute(() -> helper.assertTrue(entity.beginConstructionStep(firstStep.above(), 0.70D),
                        "Resident refused the first temporary construction step"))
                .thenWaitUntil(() -> helper.assertTrue(entity.isStandingAtConstructionPosition(firstStep.above()),
                        "Resident did not continuously climb the first temporary construction step: pos="
                                + entity.position() + ", block=" + entity.blockPosition()
                                + ", target=" + firstStep.above() + ", onGround=" + entity.onGround()
                                + ", velocity=" + entity.getDeltaMovement()
                                + ", active=" + entity.hasActiveConstructionMovement()
                                + ", entityTicks=" + entity.tickCount
                                + ", driveTicks=" + entity.constructionDriveTicks()
                                + ", defending=" + entity.isDefending()
                                + ", noAi=" + entity.isNoAi()
                                + ", added=" + (helper.getLevel().getEntity(entity.getId()) == entity)))
                .thenExecute(() -> helper.assertTrue(entity.beginConstructionStep(secondStep.above(), 0.70D),
                        "Resident refused the second temporary construction step"))
                .thenWaitUntil(() -> helper.assertTrue(entity.isStandingAtConstructionPosition(secondStep.above()),
                        "Resident did not continuously climb the second temporary construction step: pos="
                                + entity.position() + ", block=" + entity.blockPosition()
                                + ", target=" + secondStep.above() + ", onGround=" + entity.onGround()
                                + ", velocity=" + entity.getDeltaMovement()
                                + ", active=" + entity.hasActiveConstructionMovement()))
                .thenExecute(() -> {
                    entity.discard();
                    setForcedChunksBetween(helper.getLevel(), feet, secondStep, false);
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 80)
    public static void residentCanCrossAdjacentScaffoldingWithoutGravityOscillation(GameTestHelper helper) {
        BlockPos start = helper.absolutePos(new BlockPos(5, 3, 5));
        BlockPos landing = start.east();
        for (BlockPos foundation : List.of(start.below(2), landing.below(2))) {
            helper.getLevel().setBlock(foundation, Blocks.STONE.defaultBlockState(), 2);
        }
        for (BlockPos support : List.of(start.below(), landing.below())) {
            helper.getLevel().setBlock(support, Blocks.SCAFFOLDING.defaultBlockState()
                    .setValue(ScaffoldingBlock.DISTANCE, 0)
                    .setValue(ScaffoldingBlock.BOTTOM, true), 2);
        }
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create resident for scaffold-catwalk test");
        entity.moveTo(start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D, 0.0F, 0.0F);
        entity.setOnGround(false);
        helper.assertTrue(helper.getLevel().addFreshEntity(entity)
                        && entity.canStandAtPosition(start)
                        && entity.canStandAtPosition(landing),
                "Adjacent scaffolding did not expose two valid construction standing cells");

        helper.startSequence()
                .thenExecute(() -> helper.assertTrue(
                        entity.beginAdjacentConstructionMovement(landing, 0.70D),
                        "Resident refused a same-height scaffold catwalk step"))
                .thenWaitUntil(() -> {
                    entity.continueConstructionStep(0.70D);
                    helper.assertTrue(entity.isStandingAtConstructionPosition(landing)
                                    && !entity.hasActiveConstructionMovement(),
                            "Resident oscillated at the scaffold seam instead of crossing it: pos="
                                    + entity.position() + ", landing=" + landing
                                    + ", diagnostics=" + entity.constructionMovementDiagnostics());
                })
                .thenExecute(() -> {
                    helper.assertTrue(!entity.isNoGravity(),
                            "Scaffold catwalk movement left no-gravity enabled");
                    entity.discard();
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 180)
    public static void residentClimbsAndHoldsExactScaffoldingWorkHeight(GameTestHelper helper) {
        BlockPos bottom = helper.absolutePos(new BlockPos(6, 2, 6));
        BlockPos workHeight = bottom.above(3);
        helper.getLevel().setBlock(bottom.below(), Blocks.STONE.defaultBlockState(), 2);
        for (int y = bottom.getY(); y <= workHeight.getY(); y++) {
            helper.getLevel().setBlock(new BlockPos(bottom.getX(), y, bottom.getZ()),
                    Blocks.SCAFFOLDING.defaultBlockState()
                            .setValue(ScaffoldingBlock.DISTANCE, 0)
                            .setValue(ScaffoldingBlock.BOTTOM, y == bottom.getY()), 2);
        }
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create resident for scaffold work-height test");
        entity.moveTo(bottom.getX() + 0.5D, bottom.getY(), bottom.getZ() + 0.5D,
                0.0F, 0.0F);
        entity.setOnGround(false);
        helper.assertTrue(helper.getLevel().addFreshEntity(entity),
                "Could not add scaffold work-height resident to the test level");
        double[] settledY = {Double.NaN};

        helper.startSequence()
                .thenExecute(() -> helper.assertTrue(
                        entity.beginScaffoldingWorkHeight(workHeight, 0.70D),
                        "Resident refused a clear multi-block scaffolding climb"))
                .thenExecuteFor(50, () -> entity.continueConstructionStep(0.70D))
                .thenExecute(() -> {
                    helper.assertTrue(entity.isStandingAtConstructionPosition(workHeight)
                                    && !entity.hasActiveConstructionMovement(),
                            "Resident did not finish the multi-block scaffolding climb: pos="
                                    + entity.position() + ", target=" + workHeight
                                    + ", diagnostics=" + entity.constructionMovementDiagnostics());
                    settledY[0] = entity.getY();
                })
                .thenExecuteAfter(20, () -> {
                    double dx = entity.getX() - (workHeight.getX() + 0.5D);
                    double dz = entity.getZ() - (workHeight.getZ() + 0.5D);
                    helper.assertTrue(Math.abs(entity.getY() - settledY[0]) <= 0.05D
                                    && dx * dx + dz * dz <= 1.0E-4D
                                    && !entity.hasActiveConstructionMovement(),
                            "Resident oscillated after reaching the centred scaffold work pose: pos="
                                    + entity.position() + ", target=" + workHeight);
                    entity.finishConstructionMovement();
                    entity.moveTo(bottom.getX() + 0.5D, bottom.getY(), bottom.getZ() + 0.5D,
                            0.0F, 0.0F);
                    helper.getLevel().setBlock(workHeight.above(),
                            Blocks.STONE.defaultBlockState(), 2);
                    helper.assertTrue(!entity.beginScaffoldingWorkHeight(workHeight, 0.70D),
                            "Resident was allowed to climb into occupied headroom");
                    entity.discard();
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 180)
    public static void recordedScaffoldRouteClimbsEveryNodeThenExitsColumn(GameTestHelper helper) {
        BlockPos bottom = helper.absolutePos(new BlockPos(6, 2, 6));
        helper.getLevel().setBlock(bottom.below(), Blocks.STONE.defaultBlockState(), 2);
        for (int y = 0; y <= 3; y++) {
            helper.getLevel().setBlock(bottom.above(y), Blocks.SCAFFOLDING.defaultBlockState()
                    .setValue(ScaffoldingBlock.DISTANCE, 0)
                    .setValue(ScaffoldingBlock.BOTTOM, y == 0), 2);
        }
        BlockPos landing = bottom.above(2).east();
        helper.getLevel().setBlock(landing.below(), Blocks.STONE.defaultBlockState(), 2);
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create route resident");
        entity.moveTo(bottom.getX() + 0.5D, bottom.getY(), bottom.getZ() + 0.5D, 0, 0);
        helper.assertTrue(helper.getLevel().addFreshEntity(entity), "Could not add route resident");
        helper.startSequence()
                .thenExecute(() -> helper.assertTrue(entity.beginRecordedConstructionRoute(
                        List.of(bottom.above(), bottom.above(2), landing), 0.70D),
                        "Recorded scaffold ascent rejected"))
                .thenExecuteFor(70, () -> entity.continueConstructionStep(0.70D))
                .thenExecute(() -> {
                    helper.assertTrue(entity.blockPosition().equals(landing)
                                    && !entity.hasActiveConstructionMovement(),
                            "Recorded route skipped an ascent node or retained its old column: "
                                    + entity.position() + " " + entity.constructionMovementDiagnostics());
                    entity.discard();
                }).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 80)
    public static void residentCanRiseBeforePlacingVerticalFooting(GameTestHelper helper) {
        BlockPos support = helper.absolutePos(new BlockPos(5, 2, 5));
        helper.getLevel().setBlock(support.below(), Blocks.STONE.defaultBlockState(), 2);
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create resident for vertical-footing movement test");
        entity.moveTo(support.getX() + 0.5D, support.getY(), support.getZ() + 0.5D, 0.0F, 0.0F);
        entity.setOnGround(true);
        helper.assertTrue(helper.getLevel().addFreshEntity(entity),
                "Could not add vertical-footing resident to the test level");

        helper.startSequence()
                .thenExecute(() -> helper.assertTrue(entity.beginVerticalPillarRise(support, 0.70D),
                        "Resident refused to rise through its own clear standing column"))
                .thenWaitUntil(() -> {
                    entity.continueConstructionStep(0.70D);
                    helper.assertTrue(entity.hasReadyVerticalPillarSupport(),
                            "Resident never reached the one-block-higher placement pose: pos="
                                    + entity.position() + ", target=" + support.above()
                                    + ", active=" + entity.hasActiveConstructionMovement());
                })
                .thenExecute(() -> {
                    helper.assertTrue(support.equals(entity.consumeReadyVerticalPillarSupport())
                                    && entity.blockPosition().equals(support.above())
                                    && !entity.isNoGravity(),
                            "Vertical rise did not hand off the exact support position or release no-gravity");
                    entity.discard();
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 120)
    public static void residentCentersBeforeVerticalConstructionRise(GameTestHelper helper) {
        BlockPos support = helper.absolutePos(new BlockPos(7, 2, 5));
        helper.getLevel().setBlock(support.below(), Blocks.STONE.defaultBlockState(), 2);
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create resident for construction-centering test");
        entity.moveTo(support.getX() + 0.06D, support.getY(), support.getZ() + 0.94D, 0.0F, 0.0F);
        entity.setOnGround(true);
        helper.assertTrue(helper.getLevel().addFreshEntity(entity),
                "Could not add off-centre resident to the test level");
        helper.assertTrue(!entity.isCenteredOnConstructionBlock(support)
                        && !entity.beginVerticalPillarRise(support, 0.70D),
                "Off-centre resident was allowed to begin an unsafe vertical rise");

        helper.startSequence()
                .thenExecute(() -> helper.assertTrue(entity.beginConstructionCentering(support, 0.70D),
                        "Resident refused to align with the centre of its current footing"))
                .thenWaitUntil(() -> {
                    entity.continueConstructionStep(0.70D);
                    helper.assertTrue(entity.isCenteredOnConstructionBlock(support),
                            "Resident did not finish sub-block construction alignment: pos="
                                    + entity.position() + ", target=" + support);
                })
                .thenExecute(() -> {
                    BlockPos overhead = support.above(2);
                    helper.getLevel().setBlock(overhead, Blocks.STONE.defaultBlockState(), 2);
                    helper.assertTrue(!entity.beginVerticalPillarRise(support, 0.70D),
                            "Centred resident was allowed to rise into an occupied headroom block");
                    helper.getLevel().removeBlock(overhead, false);
                    helper.assertTrue(entity.beginVerticalPillarRise(support, 0.70D),
                            "Centred resident refused a clear vertical construction rise");
                })
                .thenWaitUntil(() -> {
                    entity.continueConstructionStep(0.70D);
                    helper.assertTrue(entity.hasReadyVerticalPillarSupport(),
                            "Centred resident did not finish the vertical construction rise");
                })
                .thenExecute(entity::discard)
                .thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 80)
    public static void residentCanLeapOneMissingConstructionCell(GameTestHelper helper) {
        BlockPos start = helper.absolutePos(new BlockPos(4, 2, 5));
        BlockPos landing = start.east(2);
        helper.getLevel().setBlock(start.below(), Blocks.STONE.defaultBlockState(), 2);
        helper.getLevel().setBlock(landing.below(), Blocks.STONE.defaultBlockState(), 2);
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create resident for construction-leap test");
        entity.moveTo(start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D, 0.0F, 0.0F);
        entity.setOnGround(true);
        helper.assertTrue(entity.blockPosition().equals(start)
                        && entity.canStandAtPosition(start),
                "Ladder-route resident was not standing at a valid climb entrance: pos="
                        + entity.blockPosition() + ", expected=" + start);
        helper.assertTrue(helper.getLevel().addFreshEntity(entity),
                "Could not add construction-leap resident to the test level");

        helper.startSequence()
                .thenExecute(() -> helper.assertTrue(entity.beginConstructionLeapMovement(landing, 0.70D),
                        "Resident refused a clear one-cell construction gap"))
                .thenWaitUntil(() -> {
                    entity.continueConstructionStep(0.70D);
                    helper.assertTrue(entity.blockPosition().equals(landing)
                                    && !entity.hasActiveConstructionMovement(),
                            "Resident did not finish the bounded construction leap: pos="
                                    + entity.position() + ", landing=" + landing
                                    + ", active=" + entity.hasActiveConstructionMovement());
                })
                .thenExecute(() -> {
                    helper.assertTrue(!entity.isNoGravity(),
                            "Construction leap left the resident with no-gravity enabled");
                    entity.discard();
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 120)
    public static void residentUsesExistingLadderToReachConstructionPlatform(GameTestHelper helper) {
        BlockPos column = helper.absolutePos(new BlockPos(6, 2, 6));
        BlockPos start = column.west();
        BlockPos landing = column.east().above(4);
        helper.getLevel().setBlock(start, Blocks.AIR.defaultBlockState(), 2);
        helper.getLevel().setBlock(start.above(), Blocks.AIR.defaultBlockState(), 2);
        helper.getLevel().setBlock(landing, Blocks.AIR.defaultBlockState(), 2);
        helper.getLevel().setBlock(landing.above(), Blocks.AIR.defaultBlockState(), 2);
        helper.getLevel().setBlock(start.below(), Blocks.STONE.defaultBlockState(), 2);
        helper.getLevel().setBlock(column.below(), Blocks.STONE.defaultBlockState(), 2);
        for (int y = column.getY(); y <= column.getY() + 3; y++) {
            helper.getLevel().setBlock(new BlockPos(column.getX(), y, column.getZ() + 1),
                    Blocks.STONE_BRICKS.defaultBlockState(), 2);
            helper.getLevel().setBlock(new BlockPos(column.getX(), y, column.getZ()),
                    Blocks.LADDER.defaultBlockState().setValue(
                            net.minecraft.world.level.block.LadderBlock.FACING, Direction.NORTH), 2);
        }
        helper.getLevel().setBlock(landing.below(), Blocks.STONE_BRICKS.defaultBlockState(), 2);
        helper.assertTrue(helper.getLevel().getBlockState(column.above(3)).is(Blocks.LADDER),
                "Supported ladder column disappeared before construction navigation began");
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create resident for ladder-route test");
        entity.moveTo(start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D, 0.0F, 0.0F);
        entity.setOnGround(true);
        helper.assertTrue(helper.getLevel().addFreshEntity(entity),
                "Could not add ladder-route resident to the test level");
        helper.assertTrue(entity.hasExistingClimbableRouteTo(landing),
                "Existing ladder column was not recognized as a reusable construction route");

        helper.startSequence()
                .thenExecute(() -> helper.assertTrue(
                        entity.beginExistingClimbableMovement(landing, 0.70D),
                        "Resident refused to enter the existing ladder route"))
                .thenWaitUntil(() -> {
                    entity.continueConstructionStep(0.70D);
                    helper.assertTrue(entity.distanceToSqr(
                                    landing.getX() + 0.5D, landing.getY(), landing.getZ() + 0.5D) <= 0.10D
                                    && !entity.hasActiveConstructionMovement(),
                            "Resident did not continuously climb and exit onto the platform: pos="
                                    + entity.position() + ", landing=" + landing
                                    + ", active=" + entity.hasActiveConstructionMovement()
                                    + ", diagnostics=" + entity.constructionMovementDiagnostics());
                })
                .thenExecute(() -> {
                    helper.assertTrue(!entity.isNoGravity(),
                            "Existing ladder route left the resident with no-gravity enabled");
                    entity.discard();
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 160)
    public static void residentCanDescendAConstructionScaffoldingColumn(GameTestHelper helper) {
        BlockPos bottom = helper.absolutePos(new BlockPos(6, 2, 6));
        BlockPos top = bottom.above(3);
        BlockPos landing = bottom.west();
        helper.getLevel().setBlock(bottom.below(), Blocks.STONE.defaultBlockState(), 2);
        helper.getLevel().setBlock(landing.below(), Blocks.STONE.defaultBlockState(), 2);
        helper.getLevel().setBlock(landing, Blocks.AIR.defaultBlockState(), 2);
        helper.getLevel().setBlock(landing.above(), Blocks.AIR.defaultBlockState(), 2);
        for (int y = bottom.getY(); y <= top.getY(); y++) {
            helper.getLevel().setBlock(new BlockPos(bottom.getX(), y, bottom.getZ()),
                    Blocks.SCAFFOLDING.defaultBlockState()
                            .setValue(ScaffoldingBlock.DISTANCE, 0)
                            .setValue(ScaffoldingBlock.BOTTOM, y == bottom.getY()), 2);
        }
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create resident for scaffolding-descent test");
        entity.moveTo(top.getX() + 0.5D, top.getY() + 1.0D,
                top.getZ() + 0.5D, 0.0F, 0.0F);
        entity.setOnGround(true);
        helper.assertTrue(helper.getLevel().addFreshEntity(entity),
                "Could not add scaffolding-descent resident to the test level");
        helper.assertTrue(entity.canStandAtPosition(landing),
                "Scaffolding test did not provide a valid bottom landing: landing=" + landing
                        + ", feet=" + helper.getLevel().getBlockState(landing)
                        + ", support=" + helper.getLevel().getBlockState(landing.below()));

        helper.startSequence()
                .thenExecute(() -> helper.assertTrue(
                        entity.beginExistingScaffoldingDescent(0.70D),
                        "Resident did not recognize the construction scaffolding below it: pos="
                                + entity.position() + ", block=" + entity.blockPosition()
                                + ", current=" + helper.getLevel().getBlockState(entity.blockPosition())
                                + ", below=" + helper.getLevel().getBlockState(
                                entity.blockPosition().below())))
                .thenWaitUntil(() -> {
                    entity.continueConstructionStep(0.70D);
                    helper.assertTrue(entity.isStandingAtConstructionPosition(landing)
                                    && !entity.hasActiveConstructionMovement(),
                            "Resident did not descend through and exit the scaffolding column: pos="
                                    + entity.position() + ", landing=" + landing
                                    + ", active=" + entity.hasActiveConstructionMovement());
                })
                .thenExecute(() -> {
                    helper.assertTrue(!entity.isNoGravity() && !entity.isShiftKeyDown(),
                            "Scaffolding descent left special movement flags enabled");
                    entity.discard();
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void residentDoesNotReenterScaffoldingFromItsBottomLanding(GameTestHelper helper) {
        BlockPos bottom = helper.absolutePos(new BlockPos(6, 2, 6));
        BlockPos landing = bottom.west();
        helper.getLevel().setBlock(bottom.below(), Blocks.STONE.defaultBlockState(), 2);
        helper.getLevel().setBlock(landing.below(), Blocks.STONE.defaultBlockState(), 2);
        helper.getLevel().setBlock(landing, Blocks.AIR.defaultBlockState(), 2);
        helper.getLevel().setBlock(landing.above(), Blocks.AIR.defaultBlockState(), 2);
        helper.getLevel().setBlock(bottom, Blocks.SCAFFOLDING.defaultBlockState()
                .setValue(ScaffoldingBlock.DISTANCE, 0)
                .setValue(ScaffoldingBlock.BOTTOM, true), 2);
        helper.getLevel().setBlock(bottom.above(), Blocks.SCAFFOLDING.defaultBlockState()
                .setValue(ScaffoldingBlock.DISTANCE, 0)
                .setValue(ScaffoldingBlock.BOTTOM, false), 2);
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create resident for scaffold landing test");
        entity.moveTo(landing.getX() + 0.5D, landing.getY(), landing.getZ() + 0.5D,
                0.0F, 0.0F);
        entity.setOnGround(true);
        helper.assertTrue(helper.getLevel().addFreshEntity(entity)
                        && entity.canStandAtPosition(landing),
                "Scaffold landing test did not provide a valid safe ground cell");

        helper.assertTrue(!entity.beginScaffoldingDescentAt(bottom, 0.70D),
                "Resident re-entered a scaffold column that exits onto its current cell");
        helper.assertTrue(!entity.hasActiveConstructionMovement()
                        && !entity.isNoGravity() && !entity.isShiftKeyDown(),
                "Rejected no-op scaffold descent left special movement active");
        entity.discard();
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void farmerMustUseAnInteriorVisibleWorkStation(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(8, 2, 8));
        BlockPos insideStation = target.above();
        BlockPos fence = target.west();
        BlockPos outsideStation = target.west(2).above();
        helper.getLevel().setBlock(target, Blocks.FARMLAND.defaultBlockState(), 2);
        helper.getLevel().setBlock(target.north(), Blocks.FARMLAND.defaultBlockState(), 2);
        helper.getLevel().setBlock(target.south(), Blocks.FARMLAND.defaultBlockState(), 2);
        helper.getLevel().setBlock(target.east(), Blocks.FARMLAND.defaultBlockState(), 2);
        helper.getLevel().setBlock(fence, Blocks.OAK_FENCE.defaultBlockState(), 2);
        helper.getLevel().setBlock(fence.above(), Blocks.OAK_FENCE.defaultBlockState(), 2);
        helper.getLevel().setBlock(outsideStation.below(), Blocks.STONE.defaultBlockState(), 2);
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create resident for farm-visibility test");
        helper.assertTrue(CitizenWorkService.farmInteractionVisible(
                        helper.getLevel(), insideStation, target, entity)
                        && !CitizenWorkService.farmInteractionVisible(
                        helper.getLevel(), outsideStation, target, entity),
                "Farmer interaction still crossed a fence instead of requiring an interior sightline");
        entity.discard();
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void raisedTemplateOnlyChargesNeededSupports(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8));
        BuildingBlueprintTemplate template = BlueprintTemplates.create(BuildingType.HOUSE, anchor, Direction.NORTH);
        int before = template.requirements(helper.getLevel()).getOrDefault(Items.COBBLESTONE, 0);
        long supports = template.placements().stream()
                .filter(p -> p.mode() == BlueprintPlacementMode.OPTIONAL_SUPPORT)
                .filter(p -> helper.getLevel().getBlockState(p.position()).isAir())
                .peek(p -> helper.getLevel().setBlock(p.position(), Blocks.STONE.defaultBlockState(), 2))
                .count();
        int after = template.requirements(helper.getLevel()).getOrDefault(Items.COBBLESTONE, 0);
        helper.assertTrue(before - after == supports,
                "Existing ground did not remove optional support columns from material cost");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void retiredCitizenWorkStateFallsBackToIdle(GameTestHelper helper) {
        CitizenRecord citizen = new CitizenRecord(UUID.randomUUID(), "Legacy Worker", BlockPos.ZERO);
        CompoundTag saved = citizen.save(helper.getLevel().registryAccess());
        saved.putString("WorkState", "to_stockpile");
        saved.putString("Activity", "to_stockpile");
        CitizenRecord loaded = CitizenRecord.load(saved, helper.getLevel().registryAccess());
        helper.assertTrue(loaded.workState() == CitizenWorkState.IDLE
                        && loaded.activity() == CitizenActivity.IDLE,
                "Removed stockpile work state did not migrate to idle");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void pendingBlueprintKeepsDefaultBuildersAvailable(GameTestHelper helper) {
        SettlementRecord settlement = settlement();
        settlement.addBlueprint(BuildingType.WAREHOUSE, new BlockPos(12, 70, 12),
                new BlockPos(12, 70, 6), Direction.NORTH, settlement.ownerId(), false);
        ProfessionAssignmentService.refresh(new SettlementSavedData(), settlement);
        helper.assertTrue(settlement.professionActual(CitizenProfession.WORKER) == 6,
                "A construction site must remain available to all default builders");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void builderConstructionPlacesOneRealStep(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8)).offset(512, 0, 512);
        BlockPos core = anchor.offset(0, 0, -12);
        for (BlockPos pos : BlockPos.betweenClosed(anchor.offset(-10, -1, -10), anchor.offset(10, -1, 14))) {
            helper.getLevel().setBlock(pos, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
        }
        UUID owner = UUID.randomUUID();
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        var created = data.createOrRecover(core, owner, "Builder Test");
        helper.assertTrue(created.success() && created.value() != null, "Could not create construction test settlement");
        SettlementRecord settlement = created.value();
        BuildingBlueprintTemplate diagnosticTemplate = BlueprintTemplates.create(
                BuildingType.HOUSE, anchor, Direction.NORTH);
        var blockedPlacement = diagnosticTemplate.placements().stream().filter(placement -> {
            var current = helper.getLevel().getBlockState(placement.position());
            boolean satisfied = placement.mode() == BlueprintPlacementMode.CLEARANCE
                    ? current.isAir()
                    : placement.mode() == BlueprintPlacementMode.OPTIONAL_SUPPORT
                    ? !current.isAir()
                    : BlueprintService.matchesPlacedState(current, placement.state());
            if (satisfied) return false;
            if (!current.getFluidState().isEmpty()
                    || helper.getLevel().getBlockEntity(placement.position()) != null) return true;
            if (placement.mode() == BlueprintPlacementMode.OPTIONAL_SUPPORT) return !current.isAir();
            return !current.isAir() && !current.is(ExiledSettlementTags.REPLACEABLE_FOUNDATION);
        }).findFirst().orElse(null);
        helper.assertTrue(blockedPlacement == null,
                "Test terrain itself blocks v2 placement at " + (blockedPlacement == null ? "unknown"
                        : blockedPlacement.position() + " with "
                        + helper.getLevel().getBlockState(blockedPlacement.position())));
        var placed = BlueprintService.placeBlueprint(helper.getLevel(), owner, BuildingType.HOUSE,
                anchor, Direction.NORTH, false, null);
        helper.assertTrue(placed.success() && placed.value() != null,
                "Could not establish construction site: " + placed.reason());
        BlueprintRecord blueprint = placed.value();
        // This checks placement after excavation, not the removed legacy
        // placeNext shortcut that silently overwrote uncleared terrain.
        for (BlockPos clearance : java.util.List.copyOf(blueprint.clearanceTargets().keySet())) {
            helper.getLevel().destroyBlock(clearance, false);
        }
        var pending = BlueprintService.nextPending(helper.getLevel(), data, blueprint);
        helper.assertTrue(pending != null && pending.placement().material() != null,
                "Construction site did not expose its first material step");
        ItemStack supplied = new ItemStack(pending.placement().material());
        var result = BlueprintService.placeNext(helper.getLevel(), data, settlement, blueprint, supplied);
        helper.assertTrue(result.success(), "Architect step was rejected: " + result.reason());
        helper.assertTrue(helper.getLevel().getBlockState(pending.placement().position()).equals(pending.placement().state()),
                "Architect step did not change the real world block");
        helper.assertTrue(supplied.isEmpty(), "Successful construction step did not consume exactly one material");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 200)
    public static void forcedConstructionDebugAdvancesExactlyOneStepAndCompletes(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8)).offset(1536, 0, 1536);
        BlockPos core = anchor.offset(0, 0, -12);
        for (BlockPos pos : BlockPos.betweenClosed(anchor.offset(-10, -1, -10), anchor.offset(10, -1, 14))) {
            helper.getLevel().setBlock(pos, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
        }
        UUID owner = UUID.randomUUID();
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        var created = data.createOrRecover(core, owner, "Forced Construction Debug Test");
        helper.assertTrue(created.success() && created.value() != null,
                "Could not create forced-construction debug settlement: " + created.reason());
        SettlementRecord settlement = created.value();
        var placed = BlueprintService.placeBlueprint(helper.getLevel(), owner, BuildingType.HOUSE,
                anchor, Direction.NORTH, false, null);
        helper.assertTrue(placed.success() && placed.value() != null,
                "Could not establish forced-construction debug site: " + placed.reason());
        BlueprintRecord blueprint = placed.value();

        helper.assertTrue(!ConstructionDebugService.isEnabled(helper.getLevel(), settlement.id()),
                "Forced construction debug must default to disabled");
        helper.assertTrue(!ConstructionDebugService.shouldAdvance(9)
                        && ConstructionDebugService.shouldAdvance(10)
                        && ConstructionDebugService.shouldAdvance(20),
                "Forced construction debug interval is not exactly ten ticks");
        int pendingBefore = BlueprintService.pendingPlacements(
                helper.getLevel(), blueprint, Set.of()).size();
        ConstructionDebugService.tickLevel(helper.getLevel());
        helper.assertTrue(BlueprintService.pendingPlacements(
                        helper.getLevel(), blueprint, Set.of()).size() == pendingBefore,
                "Disabled forced construction changed the blueprint");

        ConstructionDebugService.setEnabled(helper.getLevel(), settlement.id(), true);
        ConstructionDebugService.tickLevel(helper.getLevel());
        int pendingAfterOne = BlueprintService.pendingPlacements(
                helper.getLevel(), blueprint, Set.of()).size();
        helper.assertTrue(pendingAfterOne == pendingBefore - 1,
                "One forced interval did not advance exactly one blueprint block: before="
                        + pendingBefore + ", after=" + pendingAfterOne);
        helper.assertTrue(settlement.citizens().stream().allMatch(citizen -> citizen.entityId() == null),
                "Forced construction test unexpectedly depended on resident entities");

        int remainingBudget = BlueprintTemplates.create(blueprint).placements().size() + 16;
        while (settlement.blueprint(blueprint.id()) != null && remainingBudget-- > 0) {
            ConstructionDebugService.tickLevel(helper.getLevel());
        }
        BuildingRecord completed = settlement.buildings().stream()
                .filter(building -> building.type() == BuildingType.HOUSE)
                .filter(building -> building.position().equals(
                        BlueprintTemplates.create(BuildingType.HOUSE, anchor, Direction.NORTH)
                                .registrationPosition()))
                .findFirst().orElse(null);
        helper.assertTrue(settlement.blueprint(blueprint.id()) == null && completed != null,
                "Forced construction did not complete and register the building within its step budget");
        helper.assertTrue(completed.operational(),
                "Forced construction registered a house that was not operational");
        helper.assertTrue(blueprint.temporaryFootings().isEmpty(),
                "Forced construction completion left temporary footings behind");
        ConstructionDebugService.setEnabled(helper.getLevel(), settlement.id(), false);
        helper.assertTrue(!ConstructionDebugService.isEnabled(helper.getLevel(), settlement.id()),
                "Forced construction debug did not turn off cleanly");
        helper.succeed();
    }

    /**
     * Archived throughput scenario, retired by user request on 2026-09-20.
     * Intentionally not registered as a GameTest and no longer a release gate.
     * Historical scenario: three residents
     * are assigned to three ordinary, approved blueprints and must independently
     * finish them within one logical Minecraft day. Production AI receives no
     * template-specific route, placement order, material preload, or reach bonus.
     */
    public static void threeResidentsBuildTierOneTierTwoAndWarehouseWithinOneDay(GameTestHelper helper) {
        BlockPos core = helper.absolutePos(new BlockPos(8, 4, 8)).offset(1024, 0, 0);
        BlockPos[] anchors = {
                core.offset(16, 1, 0),
                core.offset(-28, 1, 0),
                core.offset(0, 1, 30)
        };
        BuildingType[] types = {BuildingType.HOUSE, BuildingType.HOUSE_2, BuildingType.WAREHOUSE};
        Direction[] facings = {Direction.EAST, Direction.WEST, Direction.NORTH};
        int[] expectedTotals = {458, 698, 748};
        int groundY = core.getY() - 1;
        for (BlockPos ground : BlockPos.betweenClosed(
                new BlockPos(core.getX() - 64, groundY - 4, core.getZ() - 64),
                new BlockPos(core.getX() + 64, groundY, core.getZ() + 64))) {
            helper.getLevel().setBlock(ground, ground.getY() == groundY
                    ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2);
        }
        ChunkPos minChunk = new ChunkPos(core.offset(-80, 0, -80));
        ChunkPos maxChunk = new ChunkPos(core.offset(80, 0, 80));
        for (int chunkX = minChunk.x; chunkX <= maxChunk.x; chunkX++) {
            for (int chunkZ = minChunk.z; chunkZ <= maxChunk.z; chunkZ++) {
                helper.getLevel().setChunkForced(chunkX, chunkZ, true);
            }
        }
        helper.getLevel().setBlock(core, ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(), 3);
        helper.getLevel().setDayTime(0L);

        UUID owner = UUID.randomUUID();
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        var created = data.createOrRecover(core, owner, "Parallel Construction Throughput Test");
        helper.assertTrue(created.success() && created.value() != null,
                "Could not create parallel throughput-test settlement: " + created.reason());
        SettlementRecord settlement = created.value();
        // The three reviewed footprints are intentionally separated so their
        // independent workers and access networks cannot overlap. Use the
        // normal village territory size to keep that layout deterministic
        // regardless of the GameTest origin's position inside its chunk.
        settlement.upgradeLevel();
        settlement.upgradeLevel();
        settlement.setCreativeCoreInstalled(true);

        List<CitizenRecord> workers = settlement.citizens().stream()
                .sorted(java.util.Comparator.comparing(citizen -> citizen.id().toString()))
                .limit(3)
                .toList();
        helper.assertTrue(workers.size() == 3, "Parallel throughput gate requires three residents");
        Set<UUID> workerIds = workers.stream().map(CitizenRecord::id).collect(java.util.stream.Collectors.toSet());
        for (CitizenRecord citizen : settlement.citizens()) {
            if (!workerIds.contains(citizen.id())) {
                citizen.markDead(core);
                continue;
            }
            citizen.insertIntoInventory(new ItemStack(Items.IRON_HELMET));
            citizen.insertIntoInventory(new ItemStack(Items.IRON_CHESTPLATE));
            citizen.insertIntoInventory(new ItemStack(Items.IRON_LEGGINGS));
            citizen.insertIntoInventory(new ItemStack(Items.IRON_BOOTS));
        }

        BlueprintRecord[] blueprints = new BlueprintRecord[3];
        int[] totals = new int[3];
        for (int index = 0; index < blueprints.length; index++) {
            var placed = BlueprintService.placeBlueprint(helper.getLevel(), owner,
                    types[index], anchors[index], facings[index], false, null);
            helper.assertTrue(placed.success() && placed.value() != null,
                    "Could not place " + types[index] + " throughput blueprint: " + placed.reason());
            BlueprintRecord blueprint = placed.value();
            blueprints[index] = blueprint;
            totals[index] = (int) BlueprintTemplates.create(blueprint).placements().stream()
                    .filter(placement -> placement.mode() == BlueprintPlacementMode.STRUCTURE)
                    .count();
            helper.assertTrue(totals[index] == expectedTotals[index],
                    "Throughput gate is not using approved " + types[index]
                            + " snapshot: " + totals[index] + "/" + expectedTotals[index]);
            helper.assertTrue(blueprint.setAssignmentMode(
                            WorksiteAssignmentMode.MANUAL, blueprint.assignmentRevision()),
                    types[index] + " blueprint rejected assigned-workers-only mode");
            helper.assertTrue(workers.get(index).setPreferredWorksite(blueprint.id()),
                    "Resident could not be assigned to " + types[index]);
        }
        data.touch();

        CitizenProjectionService.ensureSettlementCitizens(helper.getLevel(), settlement);
        List<SettlementCitizenEntity> entities = workers.stream()
                .map(worker -> CitizenProjectionService.findProjection(helper.getLevel(), worker))
                .toList();
        helper.assertTrue(entities.stream().noneMatch(java.util.Objects::isNull),
                "At least one parallel throughput-test resident has no world projection");

        long startedAt = helper.getLevel().getGameTime();
        long[] completedAt = {-1L, -1L, -1L};
        int[] doneSnapshots = {0, 0, 0};
        boolean[] sawScaffolding = {false, false, false};
        // Accelerates wall-clock execution only. The gate remains 24000 logical
        // ticks and retains the normal resident rest schedule.
        helper.getLevel().getServer().tickRateManager().setTickRate(2000.0F);

        helper.startSequence()
                .thenWaitUntil(() -> {
                    long elapsed = helper.getLevel().getGameTime() - startedAt;
                    for (int index = 0; index < blueprints.length; index++) {
                        BlueprintRecord live = settlement.blueprint(blueprints[index].id());
                        if (completedAt[index] < 0L && live == null) completedAt[index] = elapsed;
                        if (live != null && live.temporaryFootings().values().stream()
                                .anyMatch(item -> item == Items.SCAFFOLDING)) {
                            sawScaffolding[index] = true;
                        }
                        UUID target = workers.get(index).targetBlueprintId();
                        helper.assertTrue(target == null || target.equals(blueprints[index].id()),
                                "Resident assigned to " + types[index] + " crossed into blueprint " + target);
                    }

                    if (elapsed % 1000L == 0L || elapsed >= 24000L) {
                        for (int index = 0; index < blueprints.length; index++) {
                            BlueprintRecord live = settlement.blueprint(blueprints[index].id());
                            doneSnapshots[index] = live == null ? totals[index]
                                    : totals[index] - (int) BlueprintService.pendingPlacements(
                                    helper.getLevel(), live, Set.of(), entities.get(index).blockPosition()).stream()
                                    .filter(pending -> pending.placement().mode()
                                            == BlueprintPlacementMode.STRUCTURE)
                                    .count();
                        }
                        ExiledSettlementMod.LOGGER.info(
                                "[construction-gate] elapsed={} house={}/{} house2={}/{} warehouse={}/{} states={}/{}/{} positions={}/{}/{} footings={}/{}/{}",
                                elapsed,
                                doneSnapshots[0], totals[0], doneSnapshots[1], totals[1],
                                doneSnapshots[2], totals[2],
                                workers.get(0).workState(), workers.get(1).workState(), workers.get(2).workState(),
                                entities.get(0).blockPosition(), entities.get(1).blockPosition(),
                                entities.get(2).blockPosition(),
                                blueprints[0].temporaryFootings().size(),
                                blueprints[1].temporaryFootings().size(),
                                blueprints[2].temporaryFootings().size());
                        for (int index = 0; index < blueprints.length; index++) {
                            BlockPos target = workers.get(index).targetPosition();
                            BlueprintRecord live = settlement.blueprint(blueprints[index].id());
                            BlueprintService.PendingPlacement pending = live == null || target == null
                                    ? null : BlueprintService.pendingAt(helper.getLevel(), live, target);
                            ExiledSettlementMod.LOGGER.info(
                                    "[construction-worker] elapsed={} type={} state={} failure={} decision={} target={} expected={} current={} movement={} accessFailures={}",
                                    elapsed, types[index], workers.get(index).workState(),
                                    workers.get(index).workFailure(), entities.get(index).constructionDecisionTrace(),
                                    target, pending == null ? null : pending.placement().state(),
                                    target == null ? null : helper.getLevel().getBlockState(target),
                                    entities.get(index).constructionMovementDiagnostics(),
                                    entities.get(index).constructionAccessFailureDiagnostics());
                        }
                    }

                    boolean complete = completedAt[0] >= 0L && completedAt[1] >= 0L && completedAt[2] >= 0L;
                    if (!complete && elapsed >= 24000L) {
                        helper.getLevel().getServer().tickRateManager().setTickRate(20.0F);
                        for (int index = 0; index < blueprints.length; index++) {
                            BlueprintRecord live = settlement.blueprint(blueprints[index].id());
                            if (live == null) continue;
                            ExiledSettlementMod.LOGGER.info(
                                    "[construction-gate-footings] type={} feet={} entries={}",
                                    types[index], entities.get(index).blockPosition(),
                                    live.temporaryFootings().keySet().stream()
                                            .sorted(java.util.Comparator
                                                    .comparingInt(live::temporaryFootingOrder))
                                            .map(position -> position
                                                    + ":order=" + live.temporaryFootingOrder(position)
                                                    + ":parent=" + live.temporaryFootingParent(position)
                                                    + ":item=" + live.temporaryFootingItem(position)
                                                    + ":world=" + helper.getLevel().getBlockState(position))
                                            .toList());
                        }
                        helper.assertTrue(false,
                                "One-day parallel construction gate failed: house="
                                        + doneSnapshots[0] + "/" + totals[0]
                                        + " completedAt=" + completedAt[0]
                                        + ", house2=" + doneSnapshots[1] + "/" + totals[1]
                                        + " completedAt=" + completedAt[1]
                                        + ", warehouse=" + doneSnapshots[2] + "/" + totals[2]
                                        + " completedAt=" + completedAt[2]
                                        + ", states=" + workers.stream().map(CitizenRecord::workState).toList()
                                        + ", failures=" + workers.stream().map(CitizenRecord::workFailure).toList()
                                        + ", targets=" + workers.stream().map(CitizenRecord::targetPosition).toList()
                                        + ", positions=" + entities.stream()
                                        .map(SettlementCitizenEntity::blockPosition).toList());
                    }
                    if (!complete) {
                        helper.assertTrue(false,
                                "Parallel construction still in progress at elapsed=" + elapsed
                                        + ": house=" + doneSnapshots[0] + "/" + totals[0]
                                        + ", house2=" + doneSnapshots[1] + "/" + totals[1]
                                        + ", warehouse=" + doneSnapshots[2] + "/" + totals[2]);
                        return;
                    }

                    for (int index = 0; index < blueprints.length; index++) {
                        helper.assertTrue(completedAt[index] >= 0L && completedAt[index] < 24000L,
                                types[index] + " did not complete strictly within one logical day: completedAt="
                                        + completedAt[index]);
                        helper.assertTrue(sawScaffolding[index],
                                types[index] + " completed without exercising real scaffolding access");
                        helper.assertTrue(blueprints[index].temporaryFootings().isEmpty(),
                                types[index] + " completed with temporary footings still registered");
                    }
                    for (BuildingType type : types) {
                        helper.assertTrue(settlement.buildings().stream()
                                        .anyMatch(building -> building.type() == type && building.operational()),
                                type + " did not become an operational building after construction");
                    }
                    ExiledSettlementMod.LOGGER.info(
                            "[construction-gate-pass] houseCompletedAt={} house2CompletedAt={} "
                                    + "warehouseCompletedAt={} elapsed={} footings={}/{}/{}",
                            completedAt[0], completedAt[1], completedAt[2], elapsed,
                            blueprints[0].temporaryFootings().size(),
                            blueprints[1].temporaryFootings().size(),
                            blueprints[2].temporaryFootings().size());
                })
                .thenExecute(() -> {
                    helper.getLevel().getServer().tickRateManager().setTickRate(20.0F);
                    for (int chunkX = minChunk.x; chunkX <= maxChunk.x; chunkX++) {
                        for (int chunkZ = minChunk.z; chunkZ <= maxChunk.z; chunkZ++) {
                            helper.getLevel().setChunkForced(chunkX, chunkZ, false);
                        }
                    }
                })
                .thenSucceed();
    }

    /**
     * Retained temporarily as a diagnostic comparison for the superseded
     * single-worker gate. It is intentionally not registered as a GameTest.
     */
    private static void legacyOneResidentBuildsTierOneAndTierTwoHousesWithinOneDay(GameTestHelper helper) {
        BlockPos core = helper.absolutePos(new BlockPos(8, 4, 8)).offset(1024, 0, 0);
        BlockPos tierOneAnchor = core.offset(9, 1, 0);
        BlockPos tierTwoAnchor = core.offset(-20, 1, 0);
        int groundY = core.getY() - 1;
        for (BlockPos ground : BlockPos.betweenClosed(
                new BlockPos(core.getX() - 40, groundY - 4, core.getZ() - 24),
                new BlockPos(core.getX() + 40, groundY, core.getZ() + 24))) {
            helper.getLevel().setBlock(ground, ground.getY() == groundY
                    ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2);
        }
        ChunkPos minChunk = new ChunkPos(core.offset(-48, 0, -32));
        ChunkPos maxChunk = new ChunkPos(core.offset(48, 0, 32));
        for (int chunkX = minChunk.x; chunkX <= maxChunk.x; chunkX++) {
            for (int chunkZ = minChunk.z; chunkZ <= maxChunk.z; chunkZ++) {
                helper.getLevel().setChunkForced(chunkX, chunkZ, true);
            }
        }
        helper.getLevel().setBlock(core, ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(), 3);
        helper.getLevel().setDayTime(0L);

        UUID owner = UUID.randomUUID();
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        var created = data.createOrRecover(core, owner, "Construction Throughput Test");
        helper.assertTrue(created.success() && created.value() != null,
                "Could not create throughput-test settlement: " + created.reason());
        SettlementRecord settlement = created.value();
        settlement.setCreativeCoreInstalled(true);

        CitizenRecord worker = settlement.citizens().iterator().next();
        for (CitizenRecord citizen : settlement.citizens()) {
            if (!citizen.id().equals(worker.id())) citizen.markDead(core);
        }
        worker.insertIntoInventory(new ItemStack(Items.IRON_HELMET));
        worker.insertIntoInventory(new ItemStack(Items.IRON_CHESTPLATE));
        worker.insertIntoInventory(new ItemStack(Items.IRON_LEGGINGS));
        worker.insertIntoInventory(new ItemStack(Items.IRON_BOOTS));

        var tierOnePlaced = BlueprintService.placeBlueprint(helper.getLevel(), owner,
                BuildingType.HOUSE, tierOneAnchor, Direction.EAST, false, null);
        var tierTwoPlaced = BlueprintService.placeBlueprint(helper.getLevel(), owner,
                BuildingType.HOUSE_2, tierTwoAnchor, Direction.EAST, false, null);
        helper.assertTrue(tierOnePlaced.success() && tierOnePlaced.value() != null,
                "Could not place tier-one throughput blueprint: " + tierOnePlaced.reason());
        helper.assertTrue(tierTwoPlaced.success() && tierTwoPlaced.value() != null,
                "Could not place tier-two throughput blueprint: " + tierTwoPlaced.reason());
        BlueprintRecord tierOne = tierOnePlaced.value();
        BlueprintRecord tierTwo = tierTwoPlaced.value();
        int tierOneTotal = (int) BlueprintTemplates.create(tierOne).placements().stream()
                .filter(placement -> placement.mode() == BlueprintPlacementMode.STRUCTURE).count();
        int tierTwoTotal = (int) BlueprintTemplates.create(tierTwo).placements().stream()
                .filter(placement -> placement.mode() == BlueprintPlacementMode.STRUCTURE).count();
        helper.assertTrue(tierOneTotal == 458 && tierTwoTotal == 698,
                "Throughput gate is not using the approved house snapshots: "
                        + tierOneTotal + "/" + tierTwoTotal);

        CitizenProjectionService.ensureSettlementCitizens(helper.getLevel(), settlement);
        SettlementCitizenEntity entity = CitizenProjectionService.findProjection(helper.getLevel(), worker);
        helper.assertTrue(entity != null, "Throughput-test worker has no world projection");
        long startedAt = helper.getLevel().getGameTime();
        long[] tierOneCompletedAt = {-1L};
        long[] tierTwoCompletedAt = {-1L};
        int[] previousDone = {0};
        int[] tierOneDoneSnapshot = {0};
        int[] tierTwoDoneSnapshot = {0};
        int[] placementTicks = {0};
        int[] multiPlacementTicks = {0};
        int[] maximumPlacementBurst = {0};
        int[] targetChanges = {0};
        int[] positionChanges = {0};
        long[] reachedTierOneBarrierAt = {-1L};
        long[] firstFootingAt = {-1L};
        boolean[] sawScaffolding = {false};
        int[] progressAtNightfall = {-1};
        int[] progressTimeline = new int[25];
        java.util.Map<String, Integer> failureCounts = new java.util.HashMap<>();
        java.util.ArrayDeque<String> movementSamples = new java.util.ArrayDeque<>();
        BlockPos[] previousTarget = {worker.targetPosition()};
        BlockPos[] previousPosition = {entity.blockPosition()};
        // Accelerate wall-clock execution only. Logical server ticks, navigation,
        // work cadence and the full 24000-tick day/night cycle remain unchanged.
        helper.getLevel().getServer().tickRateManager().setTickRate(2000.0F);

        helper.startSequence()
                .thenWaitUntil(() -> {
                    long elapsed = helper.getLevel().getGameTime() - startedAt;
                    if (tierOneCompletedAt[0] < 0L && settlement.blueprint(tierOne.id()) == null) {
                        tierOneCompletedAt[0] = elapsed;
                    }
                    if (tierTwoCompletedAt[0] < 0L && settlement.blueprint(tierTwo.id()) == null) {
                        tierTwoCompletedAt[0] = elapsed;
                    }
                    if (elapsed % 1000L == 0L || elapsed > 24000L) {
                        tierOneDoneSnapshot[0] = settlement.blueprint(tierOne.id()) == null ? tierOneTotal
                                : tierOneTotal - (int) BlueprintService.pendingPlacements(
                                helper.getLevel(), tierOne, Set.of(), entity.blockPosition()).stream()
                                .filter(placement -> placement.placement().mode() == BlueprintPlacementMode.STRUCTURE)
                                .count();
                        tierTwoDoneSnapshot[0] = settlement.blueprint(tierTwo.id()) == null ? tierTwoTotal
                                : tierTwoTotal - (int) BlueprintService.pendingPlacements(
                                helper.getLevel(), tierTwo, Set.of(), entity.blockPosition()).stream()
                                .filter(placement -> placement.placement().mode() == BlueprintPlacementMode.STRUCTURE)
                                .count();
                    }
                    int tierOneDone = tierOneDoneSnapshot[0];
                    int tierTwoDone = tierTwoDoneSnapshot[0];
                    if (elapsed >= 5200L && elapsed <= 8000L && elapsed % 100L == 0L) {
                        BlueprintRecord debugBlueprint = worker.targetBlueprintId() == null
                                ? null : settlement.blueprint(worker.targetBlueprintId());
                        ExiledSettlementMod.LOGGER.info(
                                "[construction-step] elapsed={} state={} failure={} decision={} target={} position={} movement={}->{} detail={} footings={} reserved={}",
                                elapsed, worker.workState(), worker.workFailure(),
                                entity.constructionDecisionTrace(), worker.targetPosition(),
                                entity.blockPosition(), entity.constructionMovementMode(),
                                entity.activeConstructionMovementTarget(),
                                entity.constructionMovementDiagnostics(),
                                debugBlueprint == null ? 0 : debugBlueprint.temporaryFootings().size(),
                                CitizenNavigationReservationService.stationFor(
                                        helper.getLevel(), entity.getUUID(), helper.getLevel().getGameTime()));
                    }
                    if (elapsed % 1000L == 0L && elapsed / 1000L < progressTimeline.length) {
                        progressTimeline[(int) (elapsed / 1000L)] = tierOneDone + tierTwoDone;
                        BlueprintRecord timelineBlueprint = worker.targetBlueprintId() == null
                                ? null : settlement.blueprint(worker.targetBlueprintId());
                        ExiledSettlementMod.LOGGER.info(
                                "[construction-gate] elapsed={} done={}/{}+{}/{} state={} failure={} decision={} target={} targetFooting={} parent={} position={} cells={}/{}/{} movement={}->{} movementDiagnostics={} footings={} reserved={} accessFailures={} remaining={}",
                                elapsed, tierOneDone, tierOneTotal, tierTwoDone, tierTwoTotal,
                                worker.workState(), worker.workFailure(), entity.constructionDecisionTrace(),
                                worker.targetPosition(),
                                timelineBlueprint == null || worker.targetPosition() == null ? null
                                        : timelineBlueprint.temporaryFootings().get(worker.targetPosition()),
                                timelineBlueprint == null || worker.targetPosition() == null ? null
                                        : timelineBlueprint.temporaryFootingParent(worker.targetPosition()),
                                entity.blockPosition(),
                                helper.getLevel().getBlockState(entity.blockPosition().below()),
                                helper.getLevel().getBlockState(entity.blockPosition()),
                                helper.getLevel().getBlockState(entity.blockPosition().above()),
                                entity.constructionMovementMode(),
                                entity.activeConstructionMovementTarget(),
                                entity.constructionMovementDiagnostics(),
                                timelineBlueprint == null ? 0 : timelineBlueprint.temporaryFootings().size(),
                                CitizenNavigationReservationService.stationFor(
                                        helper.getLevel(), entity.getUUID(), helper.getLevel().getGameTime()),
                                entity.constructionAccessFailureDiagnostics(),
                                timelineBlueprint == null || timelineBlueprint.temporaryFootings().size() > 5
                                        ? "" : timelineBlueprint.temporaryFootings().entrySet().stream()
                                        .map(entry -> entry.getKey() + ":" + entry.getValue()
                                                + ":parent=" + timelineBlueprint.temporaryFootingParent(entry.getKey())
                                                + ":child=" + timelineBlueprint.hasTemporaryFootingChild(entry.getKey())
                                                + ":world=" + helper.getLevel().getBlockState(entry.getKey())
                                                + ":egress=" + ConstructionAccessPlanService
                                                .preservesResidentEgressAfterRemoval(helper.getLevel(),
                                                        timelineBlueprint, entry.getKey()))
                                        .toList());
                    }
                    if (progressAtNightfall[0] < 0 && elapsed >= 14000L) {
                        progressAtNightfall[0] = tierOneDone + tierTwoDone;
                    }
                    if (reachedTierOneBarrierAt[0] < 0L && tierOneDone >= 224) {
                        reachedTierOneBarrierAt[0] = elapsed;
                    }
                    if (firstFootingAt[0] < 0L && !tierOne.temporaryFootings().isEmpty()) {
                        firstFootingAt[0] = elapsed;
                    }
                    if (!sawScaffolding[0]) {
                        sawScaffolding[0] = tierOne.temporaryFootings().values().stream()
                                .anyMatch(item -> item == Items.SCAFFOLDING)
                                || tierTwo.temporaryFootings().values().stream()
                                .anyMatch(item -> item == Items.SCAFFOLDING);
                    }
                    int done = tierOneDone + tierTwoDone;
                    int placedThisTick = done - previousDone[0];
                    if (placedThisTick > 0) {
                        placementTicks[0]++;
                        if (placedThisTick > 1) multiPlacementTicks[0]++;
                        maximumPlacementBurst[0] = Math.max(maximumPlacementBurst[0], placedThisTick);
                    }
                    previousDone[0] = done;
                    if (!java.util.Objects.equals(previousTarget[0], worker.targetPosition())) {
                        targetChanges[0]++;
                        previousTarget[0] = worker.targetPosition();
                    }
                    if (!previousPosition[0].equals(entity.blockPosition())) {
                        positionChanges[0]++;
                        previousPosition[0] = entity.blockPosition();
                    }
                    if (!worker.workFailure().isBlank()) {
                        failureCounts.merge(worker.workFailure(), 1, Integer::sum);
                    }
                    if (elapsed % 500L == 0L) {
                        if (movementSamples.size() >= 12) movementSamples.removeFirst();
                        movementSamples.addLast(elapsed + ":" + entity.blockPosition()
                                + "/" + entity.constructionMovementMode()
                                + "->" + entity.activeConstructionMovementTarget());
                    }
                    if (elapsed > 24000L) {
                        helper.getLevel().getServer().tickRateManager().setTickRate(20.0F);
                    }
                    BlockPos fallbackStation = null;
                    BlockPos targetParent = null;
                    BlockPos rootFooting = null;
                    String pendingDescription = null;
                    String nearestFootings = null;
                    String accessDescription = null;
                    BlockPos reservedStation = null;
                    BlueprintRecord activeBlueprint = worker.targetBlueprintId() == null
                            ? null : settlement.blueprint(worker.targetBlueprintId());
                    if (elapsed > 24000L && worker.targetPosition() != null) {
                        fallbackStation = CitizenWorkService.findConstructionWorkPosition(
                                helper.getLevel(), entity, worker.targetPosition());
                        if (activeBlueprint != null) {
                            targetParent = activeBlueprint.temporaryFootingParent(worker.targetPosition());
                            rootFooting = targetParent;
                            while (rootFooting != null
                                    && activeBlueprint.temporaryFootingParent(rootFooting) != null) {
                                rootFooting = activeBlueprint.temporaryFootingParent(rootFooting);
                            }
                            BlueprintService.PendingPlacement activePending = BlueprintService.pendingAt(
                                    helper.getLevel(), activeBlueprint, worker.targetPosition());
                            pendingDescription = activePending == null ? "satisfied"
                                    : activePending.placement().mode() + "/"
                                    + activePending.placement().state();
                            nearestFootings = activeBlueprint.temporaryFootings().entrySet().stream()
                                    .sorted(java.util.Comparator.comparingDouble(entry ->
                                            entry.getKey().distSqr(entity.blockPosition())))
                                    .limit(2)
                                    .map(entry -> entry.getKey() + ":" + entry.getValue() + ":parent="
                                            + activeBlueprint.temporaryFootingParent(entry.getKey()))
                                    .toList().toString();
                            accessDescription = CitizenWorkService.constructionAccessDiagnostics(
                                    helper.getLevel(), settlement, activeBlueprint, worker,
                                    entity, worker.targetPosition()).toString();
                            reservedStation = CitizenNavigationReservationService.stationFor(
                                    helper.getLevel(), entity.getUUID(), helper.getLevel().getGameTime());
                        }
                    }
                    helper.assertTrue(elapsed <= 24000L,
                            "One-day construction gate failed: elapsed=" + elapsed
                                    + ", tierOne=" + tierOneDone + "/" + tierOneTotal
                                    + ", tierTwo=" + tierTwoDone + "/" + tierTwoTotal
                                    + ", state=" + worker.workState()
                                    + ", failure=" + worker.workFailure()
                                    + ", target=" + worker.targetPosition()
                                    + ", position=" + entity.blockPosition()
                                    + ", movementMode=" + entity.constructionMovementMode()
                                    + ", movementTarget=" + entity.activeConstructionMovementTarget()
                                    + ", footings=" + (activeBlueprint == null
                                    ? 0 : activeBlueprint.temporaryFootings().size())
                                    + ", accessFailures=" + entity.constructionAccessFailureDiagnostics()
                                    + ", progressAtNightfall=" + progressAtNightfall[0]
                                    + ", dayTime=" + Math.floorMod(helper.getLevel().getDayTime(), 24000L));
                    helper.assertTrue(tierOneCompletedAt[0] >= 0L && tierTwoCompletedAt[0] >= 0L,
                            "Construction still in progress at elapsed=" + elapsed
                                    + ": tierOne=" + tierOneDone + "/" + tierOneTotal
                                    + ", tierTwo=" + tierTwoDone + "/" + tierTwoTotal);
                    helper.assertTrue(sawScaffolding[0],
                            "One-day construction completed without exercising worker scaffolding access");
                })
                .thenExecute(() -> {
                    helper.getLevel().getServer().tickRateManager().setTickRate(20.0F);
                    for (int chunkX = minChunk.x; chunkX <= maxChunk.x; chunkX++) {
                        for (int chunkZ = minChunk.z; chunkZ <= maxChunk.z; chunkZ++) {
                            helper.getLevel().setChunkForced(chunkX, chunkZ, false);
                        }
                    }
                })
                .thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void levelAndNewProfessionsRoundTrip(GameTestHelper helper) {
        SettlementRecord original = settlement();
        original.upgradeLevel();
        original.upgradeLevel();
        addTargetedWorkplace(original, BuildingType.TOOL_WORKSHOP, 1, new BlockPos(12, 70, 12));
        addTargetedWorkplace(original, BuildingType.TREE_FARM, 2, new BlockPos(20, 70, 12));
        addTargetedWorkplace(original, BuildingType.FISHING_HUT, 1, new BlockPos(28, 70, 12));
        CompoundTag saved = original.save(helper.getLevel().registryAccess());
        SettlementRecord loaded = SettlementRecord.load(saved, helper.getLevel().registryAccess());
        helper.assertTrue(loaded.level() == SettlementLevel.VILLAGE,
                "Settlement level did not survive save/load");
        helper.assertTrue(loaded.professionTarget(CitizenProfession.TOOLSMITH) == 1
                        && loaded.professionTarget(CitizenProfession.LUMBERJACK) == 2
                        && loaded.professionTarget(CitizenProfession.FISHER) == 1,
                "New profession targets did not survive save/load");
        saved.remove("Level");
        helper.assertTrue(SettlementRecord.load(saved, helper.getLevel().registryAccess()).level() == SettlementLevel.CAMP,
                "Schema 5 settlement without a level did not migrate to camp");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void roadLineAndLegacyStorageStayContinuous(GameTestHelper helper) {
        List<BlockPos> road = RoadService.line(new BlockPos(0, 70, 0), new BlockPos(12, 70, 8));
        helper.assertTrue(!road.isEmpty() && road.getFirst().equals(new BlockPos(0, 70, 0))
                        && road.getLast().equals(new BlockPos(12, 70, 8)),
                "Road line endpoints changed");
        for (int i = 1; i < road.size(); i++) {
            BlockPos previous = road.get(i - 1);
            BlockPos current = road.get(i);
            helper.assertTrue(Math.abs(previous.getX() - current.getX()) <= 1
                            && Math.abs(previous.getZ() - current.getZ()) <= 1,
                    "Road line contains a gap");
        }
        RoadSpaceData data = new RoadSpaceData();
        data.addAll(road);
        CompoundTag saved = data.save(new CompoundTag(), helper.getLevel().registryAccess());
        RoadSpaceData loaded = RoadSpaceData.load(saved, helper.getLevel().registryAccess());
        helper.assertTrue(road.stream().allMatch(loaded::contains),
                "Legacy-compatible Roads coordinate array lost positions");
        helper.assertTrue(road.stream().allMatch(position -> loaded.levelAt(position) == 1),
                "Legacy Roads coordinates were not migrated to level one");
        List<BlockPos> rectangle = RoadService.rectangle(new BlockPos(0, 70, 0), new BlockPos(3, 70, 2));
        helper.assertTrue(rectangle.size() == 12, "Road selection no longer creates a complete rectangle");
        loaded.addAll(rectangle, RoadTier.LEVEL_3);
        CompoundTag tiered = loaded.save(new CompoundTag(), helper.getLevel().registryAccess());
        RoadSpaceData tieredLoaded = RoadSpaceData.load(tiered, helper.getLevel().registryAccess());
        helper.assertTrue(rectangle.stream().allMatch(position -> tieredLoaded.levelAt(position) == 3),
                "Tier-three road levels did not survive save/load");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void buildingNamesBoundsAndHousingTiersRoundTrip(GameTestHelper helper) {
        SettlementRecord original = settlement();
        BlockPos anchor = new BlockPos(12, 70, 12);
        BuildingBlueprintTemplate template = BlueprintTemplates.create(
                BuildingType.ADVANCED_HOUSE_3, anchor, Direction.EAST);
        BuildingRecord building = original.addBuilding(BuildingType.ADVANCED_HOUSE_3, anchor, Direction.EAST,
                template.minCorner(), template.maxCorner(), template.transitionMargin());
        helper.assertTrue(building.rename("湖畔大宅"), "Building rename was rejected");
        CompoundTag saved = original.save(helper.getLevel().registryAccess());
        SettlementRecord loaded = SettlementRecord.load(saved, helper.getLevel().registryAccess());
        BuildingRecord roundTrip = loaded.building(building.id());
        helper.assertTrue(roundTrip != null && roundTrip.customName().equals("湖畔大宅")
                        && roundTrip.facing() == Direction.EAST
                        && roundTrip.minCorner().equals(template.minCorner())
                        && roundTrip.maxCorner().equals(template.maxCorner()),
                "Building name, facing, or bounds did not survive schema seven save/load");
        for (BuildingType type : new BuildingType[]{BuildingType.HOUSE, BuildingType.HOUSE_2, BuildingType.HOUSE_3,
                BuildingType.ADVANCED_HOUSE_1, BuildingType.ADVANCED_HOUSE_2, BuildingType.ADVANCED_HOUSE_3}) {
            BuildingBlueprintTemplate house = BlueprintTemplates.create(type, anchor, Direction.NORTH);
            long beds = house.placements().stream()
                    .filter(placement -> placement.state().getBlock() instanceof net.minecraft.world.level.block.BedBlock)
                    .filter(placement -> placement.state().getValue(net.minecraft.world.level.block.BedBlock.PART)
                            == net.minecraft.world.level.block.state.properties.BedPart.HEAD)
                    .count();
            helper.assertTrue(beds == type.housingLimit(), type + " bed count does not match its housing limit");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void manualCitizenAssignmentOverridesZeroQuota(GameTestHelper helper) {
        SettlementRecord settlement = settlement();
        CitizenRecord citizen = settlement.citizens().iterator().next();
        BuildingRecord field = settlement.addBuilding(BuildingType.FARM_FIELD, new BlockPos(12, 70, 12));
        field.updateFunction(true, BuildingStatus.OPERATIONAL, 2, List.of(field.position()));
        helper.assertTrue(citizen.setManualAssignment(CitizenProfession.FARMER, field.id()),
                "Manual farmer assignment was not stored");
        settlement.setProfessionTarget(CitizenProfession.FARMER, 0);
        ProfessionAssignmentService.refresh(new SettlementSavedData(), settlement);
        helper.assertTrue(citizen.profession() == CitizenProfession.FARMER
                        && field.id().equals(citizen.workBuildingId())
                        && citizen.hasManualAssignment(),
                "Zero GUI quota overrode the resident's personal assignment");
        CompoundTag saved = citizen.save(helper.getLevel().registryAccess());
        CitizenRecord loaded = CitizenRecord.load(saved, helper.getLevel().registryAccess());
        helper.assertTrue(loaded.manualProfession() == CitizenProfession.FARMER
                        && field.id().equals(loaded.manualWorkBuildingId()),
                "Manual assignment did not survive save/load");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void migrantRecruitmentConsumesFoodAndAddsExactlyOneResident(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos core = helper.absolutePos(new BlockPos(8, 10, 8));
        SettlementSavedData data = new SettlementSavedData();
        SettlementRecord settlement = new SettlementRecord(
                UUID.randomUUID(), "Migration Test", core, player.getUUID());

        BuildingRecord houseA = settlement.addBuilding(BuildingType.HOUSE, core.offset(3, 0, 0));
        BuildingRecord houseB = settlement.addBuilding(BuildingType.HOUSE, core.offset(-3, 0, 0));
        BlockPos barrelPosition = core.offset(0, 0, 3);
        BuildingRecord warehouse = settlement.addBuilding(BuildingType.WAREHOUSE, barrelPosition);
        houseA.updateFunction(true, BuildingStatus.OPERATIONAL, 4, List.of(
                core.offset(3, 0, 1), core.offset(3, 0, 2), core.offset(3, 0, 3), core.offset(3, 0, 4)));
        houseB.updateFunction(true, BuildingStatus.OPERATIONAL, 4, List.of(
                core.offset(-3, 0, 1), core.offset(-3, 0, 2), core.offset(-3, 0, 3), core.offset(-3, 0, 4)));
        warehouse.updateFunction(true, BuildingStatus.OPERATIONAL, 27, List.of(barrelPosition));
        helper.getLevel().setBlock(barrelPosition, Blocks.BARREL.defaultBlockState(), 3);
        helper.assertTrue(helper.getLevel().getBlockEntity(barrelPosition) instanceof Container,
                "Migration test barrel did not provide an inventory");
        Container barrel = (Container) helper.getLevel().getBlockEntity(barrelPosition);
        barrel.setItem(0, new ItemStack(Items.APPLE, 64));

        var upgraded = SettlementProgressionService.upgrade(data, settlement, player);
        helper.assertTrue(upgraded.success() && settlement.level() == SettlementLevel.HAMLET,
                "Three operational buildings did not unlock hamlet");
        var recruited = SettlementProgressionService.recruit(helper.getLevel(), data, settlement, player);
        helper.assertTrue(recruited.success(), "Eligible migrant recruitment failed: " + recruited.reason());
        helper.assertTrue(settlement.aliveCitizenCount() == 7, "Recruitment did not add exactly one resident");
        helper.assertTrue(barrel.getItem(0).getCount() == 24,
                "Hamlet recruitment did not consume exactly 40 food items");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void personalBedsAreUniqueAndPersistent(GameTestHelper helper) {
        SettlementRecord settlement = settlement();
        BuildingRecord first = settlement.addBuilding(BuildingType.HOUSE_2, new BlockPos(10, 70, 10));
        BuildingRecord second = settlement.addBuilding(BuildingType.HOUSE_2, new BlockPos(20, 70, 20));
        first.updateFunction(true, BuildingStatus.OPERATIONAL, 4, List.of(
                new BlockPos(10, 70, 10), new BlockPos(11, 70, 10),
                new BlockPos(10, 70, 11), new BlockPos(11, 70, 11)));
        second.updateFunction(true, BuildingStatus.OPERATIONAL, 4, List.of(
                new BlockPos(20, 70, 20), new BlockPos(21, 70, 20),
                new BlockPos(20, 70, 21), new BlockPos(21, 70, 21)));
        helper.assertTrue(settlement.refreshHousingAssignments(), "Housing refresh did not assign personal beds");
        Set<BlockPos> beds = new HashSet<>();
        for (CitizenRecord citizen : settlement.citizens()) {
            helper.assertTrue(citizen.homeBuildingId() != null && citizen.bedPosition() != null,
                    "A resident with available capacity did not receive a personal bed");
            helper.assertTrue(beds.add(citizen.bedPosition()), "Two residents were assigned the same personal bed");
        }
        SettlementRecord loaded = SettlementRecord.load(
                settlement.save(helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
        Set<BlockPos> loadedBeds = new HashSet<>();
        for (CitizenRecord citizen : loaded.citizens()) {
            helper.assertTrue(citizen.bedPosition() != null && loadedBeds.add(citizen.bedPosition()),
                    "Personal bed binding was lost or duplicated after save/load");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void housingOptimizationUsesWorkplaceDistance(GameTestHelper helper) {
        SettlementRecord settlement = settlement();
        BuildingRecord far = settlement.addBuilding(BuildingType.HOUSE_3, new BlockPos(40, 70, 40));
        BuildingRecord near = settlement.addBuilding(BuildingType.HOUSE_3, new BlockPos(12, 70, 12));
        List<BlockPos> farBeds = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> far.position().offset(i, 0, 0)).toList();
        List<BlockPos> nearBeds = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> near.position().offset(i, 0, 0)).toList();
        far.updateFunction(true, BuildingStatus.OPERATIONAL, 6, farBeds);
        near.updateFunction(true, BuildingStatus.OPERATIONAL, 6, nearBeds);
        BuildingRecord field = settlement.addBuilding(BuildingType.FARM_FIELD, new BlockPos(11, 70, 11));
        field.updateFunction(true, BuildingStatus.OPERATIONAL, 2, List.of(field.position()));
        CitizenRecord worker = settlement.citizens().iterator().next();
        worker.assignProfession(CitizenProfession.FARMER, field.id());
        worker.assignHome(far.id(), farBeds.getFirst());
        int changed = settlement.optimizeHousingAssignments();
        helper.assertTrue(changed > 0 && worker.homeBuildingId().equals(near.id()),
                "Housing optimization did not move a worker to the nearer home");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void progressionExpandsTerritoryAndPersists(GameTestHelper helper) {
        SettlementRecord settlement = settlement();
        helper.assertTrue(settlement.boundaryRadiusChunks() == 2, "Camp territory radius changed unexpectedly");
        settlement.upgradeLevel();
        settlement.upgradeLevel();
        helper.assertTrue(settlement.level() == SettlementLevel.VILLAGE && settlement.boundaryRadiusChunks() == 4,
                "Settlement upgrades did not expand territory to the village radius");
        SettlementRecord loaded = SettlementRecord.load(
                settlement.save(helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
        helper.assertTrue(loaded.boundaryRadiusChunks() == 4,
                "Expanded territory radius did not survive save/load");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void inventoryLimitsFiltersAndDemolitionRoundTrip(GameTestHelper helper) {
        SettlementRecord settlement = settlement();
        BuildingRecord warehouse = settlement.addBuilding(BuildingType.WAREHOUSE, new BlockPos(12, 70, 12));
        warehouse.updateFunction(true, BuildingStatus.OPERATIONAL, 27, List.of(warehouse.position()));
        settlement.setToolStockLimit(96);
        settlement.setEquipmentStockLimit(48);
        warehouse.toggleWarehouseCategory(WarehouseCategory.FOOD);
        settlement.queueBuildingDemolition(warehouse.id());
        SettlementRecord loaded = SettlementRecord.load(
                settlement.save(helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
        BuildingRecord loadedWarehouse = loaded.building(warehouse.id());
        helper.assertTrue(loaded.toolStockLimit() == 96 && loaded.equipmentStockLimit() == 48,
                "Tool or equipment stock limits did not survive save/load");
        helper.assertTrue(loadedWarehouse != null && loadedWarehouse.demolitionQueued()
                        && (loadedWarehouse.warehouseCategoryMask() & WarehouseCategory.FOOD.bit()) == 0,
                "Warehouse filter or demolition queue did not survive save/load");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void creativeSupplyCoreFulfillsWithoutCreatingInventory(GameTestHelper helper) {
        // This fixture exceeds the tiny empty template bounds. Keep it away
        // from adjacent full-building fixtures, which otherwise place chests
        // in the station and make results depend on the batch layout.
        BlockPos barrelPos = helper.absolutePos(new BlockPos(42000, 10, 42000));
        helper.getLevel().setBlock(barrelPos, Blocks.BARREL.defaultBlockState(), 3);
        BuildingRecord warehouse = BuildingRecord.create(BuildingType.WAREHOUSE, barrelPos);
        warehouse.updateFunction(true, BuildingStatus.OPERATIONAL, 27, List.of(barrelPos));
        Container barrel = (Container) helper.getLevel().getBlockEntity(barrelPos);
        barrel.setItem(0, new ItemStack(ModItems.CREATIVE_SUPPLY_CORE.get()));
        helper.assertTrue(WarehouseInventoryService.hasCreativeSupply(helper.getLevel(), warehouse),
                "Creative supply core in a warehouse was not detected");
        BlockPos station = barrelPos.east();
        helper.getLevel().setBlock(station.below(), Blocks.STONE_BRICKS.defaultBlockState(), 3);
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Creative supply resident could not be created");
        entity.setPos(station.getX() + 0.5D, station.getY(), station.getZ() + 0.5D);
        helper.getLevel().addFreshEntity(entity);
        BlockPos reserved = WarehouseInventoryService.reserveExtractionPosition(
                helper.getLevel(), warehouse, entity, Items.IRON_INGOT);
        helper.assertTrue(station.equals(reserved),
                "Creative supply core did not provide a legal virtual-material interaction station: expected="
                        + station + ", actual=" + reserved + ", feet=" + helper.getLevel().getBlockState(station)
                        + ", head=" + helper.getLevel().getBlockState(station.above())
                        + ", below=" + helper.getLevel().getBlockState(station.below())
                        + ", stand=" + entity.canStandAtPosition(station)
                        + ", reachable=" + entity.hasReachablePathTo(station));
        ItemStack supplied = WarehouseInventoryService.extractItemOrCreativeForResident(
                helper.getLevel(), warehouse, entity, Items.IRON_INGOT, 3);
        helper.assertTrue(supplied.is(Items.IRON_INGOT) && supplied.getCount() == 3,
                "Creative supply did not fulfill the requested material amount");
        helper.assertTrue(barrel.getItem(0).is(ModItems.CREATIVE_SUPPLY_CORE.get())
                        && WarehouseInventoryService.summarize(helper.getLevel(), warehouse).size() == 1,
                "Virtual supply incorrectly created real warehouse inventory or consumed the core");
        CitizenNavigationReservationService.releaseAll(helper.getLevel(), entity.getUUID());
        entity.discard();
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void settlementCoreSupplyUsesReservedReachableStations(GameTestHelper helper) {
        BlockPos core = helper.absolutePos(new BlockPos(8, 10, 8));
        for (BlockPos ground : BlockPos.betweenClosed(core.offset(-7, -1, -7), core.offset(7, -1, 7))) {
            helper.getLevel().setBlock(ground, Blocks.STONE_BRICKS.defaultBlockState(), 2);
        }
        helper.getLevel().setBlock(core, ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(), 3);
        SettlementRecord settlement = new SettlementRecord(
                UUID.randomUUID(), "Core Supply Station Test", core, UUID.randomUUID());
        settlement.setCreativeCoreInstalled(true);
        SettlementCitizenEntity first = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        SettlementCitizenEntity second = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(first != null && second != null, "Could not create core-supply residents");
        first.moveTo(core.getX() + 6.5D, core.getY(), core.getZ() + 0.5D, 0.0F, 0.0F);
        second.moveTo(core.getX() - 5.5D, core.getY(), core.getZ() + 0.5D, 0.0F, 0.0F);
        first.setOnGround(true);
        second.setOnGround(true);
        first.restrictTo(core, 32);
        second.restrictTo(core, 32);
        helper.getLevel().addFreshEntity(first);
        helper.getLevel().addFreshEntity(second);

        BlockPos eastStation = core.east();
        helper.assertTrue(first.canStandAtPosition(eastStation),
                "Core supply test did not create a standable adjacent station: " + eastStation);
        net.minecraft.world.phys.BlockHitResult stationSight = helper.getLevel().clip(
                new net.minecraft.world.level.ClipContext(
                        new net.minecraft.world.phys.Vec3(eastStation.getX() + 0.5D,
                                eastStation.getY() + 1.62D, eastStation.getZ() + 0.5D),
                        net.minecraft.world.phys.Vec3.atCenterOf(core),
                        net.minecraft.world.level.ClipContext.Block.OUTLINE,
                        net.minecraft.world.level.ClipContext.Fluid.NONE,
                        first));
        helper.assertTrue(stationSight.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        && stationSight.getBlockPos().equals(core),
                "Adjacent core station did not have line of sight: type=" + stationSight.getType()
                        + ", hit=" + stationSight.getBlockPos());
        helper.assertTrue(SettlementCoreInteractionService.hasPhysicalStation(
                        helper.getLevel(), settlement, first),
                "Core supply did not expose any physical interaction candidate");

        BlockPos firstStation = SettlementCoreInteractionService.reserveSupplyPosition(
                helper.getLevel(), settlement, first);
        BlockPos secondStation = SettlementCoreInteractionService.reserveSupplyPosition(
                helper.getLevel(), settlement, second);
        helper.assertTrue(firstStation != null && secondStation != null
                        && !firstStation.equals(core) && !secondStation.equals(core)
                        && !firstStation.equals(secondStation),
                "Core supply did not allocate distinct physical standing positions: first="
                        + firstStation + ", second=" + secondStation + ", core=" + core);
        first.setPos(firstStation.getX() + 0.5D, firstStation.getY(), firstStation.getZ() + 0.5D);
        helper.assertTrue(SettlementCoreInteractionService.canReceiveSupply(
                        helper.getLevel(), settlement, first),
                "A resident standing at the reserved visible core station could not receive supply");
        SettlementCoreInteractionService.release(helper.getLevel(), first);
        helper.assertTrue(CitizenNavigationReservationService.stationFor(
                        helper.getLevel(), first.getUUID(), helper.getLevel().getGameTime()) == null,
                "Core supply station remained reserved after the interaction completed");
        CitizenNavigationReservationService.releaseAll(helper.getLevel(), second.getUUID());
        first.discard();
        second.discard();
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void residentStandingBesideCoreActuallyReceivesEquipment(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos core = helper.absolutePos(new BlockPos(8, 10, 8));
        for (BlockPos ground : BlockPos.betweenClosed(core.offset(-7, -1, -7), core.offset(7, -1, 7))) {
            helper.getLevel().setBlock(ground, Blocks.STONE_BRICKS.defaultBlockState(), 2);
        }
        helper.getLevel().setBlock(core, ModBlocks.SETTLEMENT_CORE.get().defaultBlockState(), 3);
        helper.getLevel().setDayTime(6000L);
        SettlementSavedData data = new SettlementSavedData();
        SettlementRecord settlement = new SettlementRecord(
                UUID.randomUUID(), "Core Supply Work Test", core, player.getUUID());
        settlement.setCreativeCoreInstalled(true);
        CitizenRecord citizen = settlement.citizens().iterator().next();
        helper.assertTrue(citizen.startEquipmentFetch(null, core),
                "Resident could not enter the equipment-fetch work state");
        CitizenProjectionService.ensureSettlementCitizens(helper.getLevel(), settlement);
        SettlementCitizenEntity entity = CitizenProjectionService.findProjection(helper.getLevel(), citizen);
        helper.assertTrue(entity != null, "Equipment-fetch resident did not receive a world projection");
        entity.setOnGround(true);
        BlockPos station = SettlementCoreInteractionService.reserveSupplyPosition(
                helper.getLevel(), settlement, entity);
        helper.assertTrue(station != null, "Equipment-fetch resident could not reserve a core station");
        entity.moveTo(station.getX() + 0.5D, station.getY(), station.getZ() + 0.5D,
                entity.getYRot(), entity.getXRot());

        CitizenWorkService.tickSettlement(helper.getLevel(), data, settlement);

        helper.assertTrue(citizen.equipmentItem(EquipmentSlot.HEAD).is(Items.IRON_HELMET)
                        && citizen.findInventorySlot(stack -> stack.is(Items.IRON_HELMET)) < 0,
                "Resident stood at the core station but did not receive the requested equipment");
        helper.assertTrue(entity.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                        && citizen.equipmentItem(EquipmentSlot.HEAD).is(Items.IRON_HELMET),
                "Core-supplied armor was worn during peace or personal ownership was lost");
        helper.assertTrue(CitizenNavigationReservationService.stationFor(
                        helper.getLevel(), entity.getUUID(), helper.getLevel().getGameTime()) == null,
                "Core station was not released after the resident received equipment");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void overviewSnapshotIncludesBuildingsAndBlockedBlueprints(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos core = helper.absolutePos(new BlockPos(8, 10, 8));
        SettlementRecord settlement = new SettlementRecord(
                UUID.randomUUID(), "Overview Snapshot Test", core, player.getUUID());
        BuildingRecord building = settlement.addBuilding(BuildingType.HOUSE, core.offset(16, 0, 0));
        building.updateFunction(true, BuildingStatus.OPERATIONAL, 2, List.of());
        BlueprintRecord blueprint = settlement.addBlueprint(BuildingType.WAREHOUSE,
                core.offset(-24, 0, 0), Direction.EAST, player.getUUID(), false);
        CitizenRecord citizen = settlement.citizens().iterator().next();
        helper.assertTrue(citizen.startTask(CitizenWorkState.TO_BLUEPRINT,
                        blueprint.anchor(), null, blueprint.id())
                        && citizen.updateWorkFailure("NO_PATH"),
                "Could not create blocked blueprint assignment for overview snapshot");

        var entries = PlacementNetworking.overviewEntries(player, settlement);
        var buildingEntry = entries.stream().filter(entry -> entry.id().equals(building.id())).findFirst().orElse(null);
        var blueprintEntry = entries.stream().filter(entry -> entry.id().equals(blueprint.id())).findFirst().orElse(null);
        helper.assertTrue(buildingEntry != null && !buildingEntry.blueprint()
                        && blueprintEntry != null && blueprintEntry.blueprint()
                        && blueprintEntry.status() == BuildingStatus.CONSTRUCTION_BLOCKED,
                "Overview snapshot omitted the registered building or blocked future blueprint state");
        blueprint.queueCancellation();
        var cancelled = PlacementNetworking.overviewEntries(player, settlement).stream()
                .filter(entry -> entry.id().equals(blueprint.id())).findFirst().orElse(null);
        helper.assertTrue(cancelled != null && cancelled.status() == BuildingStatus.CANCELLATION_QUEUED,
                "Cancelled blueprint remained visible as an active future building");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void constructionMaterialExtractionBatchesAcrossStacksAndContainers(GameTestHelper helper) {
        BlockPos firstPos = helper.absolutePos(new BlockPos(7, 10, 8));
        BlockPos secondPos = firstPos.east(2);
        BlockPos station = firstPos.east();
        BlockPos hiddenPos = station.north(2);
        helper.getLevel().setBlock(firstPos, Blocks.BARREL.defaultBlockState(), 3);
        helper.getLevel().setBlock(secondPos, Blocks.BARREL.defaultBlockState(), 3);
        helper.getLevel().setBlock(hiddenPos, Blocks.BARREL.defaultBlockState(), 3);
        helper.getLevel().setBlock(station.north(), Blocks.STONE_BRICKS.defaultBlockState(), 3);
        helper.getLevel().setBlock(station.below(), Blocks.STONE_BRICKS.defaultBlockState(), 3);
        Container first = (Container) helper.getLevel().getBlockEntity(firstPos);
        Container second = (Container) helper.getLevel().getBlockEntity(secondPos);
        Container hidden = (Container) helper.getLevel().getBlockEntity(hiddenPos);
        first.setItem(0, new ItemStack(Items.STONE_BRICKS, 7));
        first.setItem(1, new ItemStack(Items.STONE_BRICKS, 11));
        second.setItem(0, new ItemStack(Items.STONE_BRICKS, 13));
        hidden.setItem(0, new ItemStack(Items.STONE_BRICKS, 17));
        BuildingRecord warehouse = BuildingRecord.create(BuildingType.WAREHOUSE, firstPos, Direction.NORTH,
                firstPos.north(2), secondPos, 0, 1);
        warehouse.updateFunction(true, BuildingStatus.OPERATIONAL, 81, List.of(firstPos, secondPos, hiddenPos));
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Warehouse interaction resident could not be created");
        entity.setPos(station.getX() + 0.5D, station.getY(), station.getZ() + 0.5D);
        CitizenNavigationReservationService.claimStation(
                helper.getLevel(), entity.getUUID(), station, helper.getLevel().getGameTime());
        ItemStack batch = WarehouseInventoryService.extractItemForResident(
                helper.getLevel(), warehouse, entity, Items.STONE_BRICKS, 64);
        helper.assertTrue(batch.getCount() == 48 && hidden.getItem(0).isEmpty(),
                "A warehouse loading point must serve every registered container in this warehouse");
        BuildingRecord other = BuildingRecord.create(BuildingType.WAREHOUSE, hiddenPos.east(8));
        other.updateFunction(true, BuildingStatus.OPERATIONAL, 27, List.of(hiddenPos.east(8)));
        helper.assertTrue(!WarehouseInventoryService.canInteract(helper.getLevel(), other, entity),
                "A loading station granted access to a different warehouse");
        CitizenNavigationReservationService.releaseAll(helper.getLevel(), entity.getUUID());
        helper.succeed();
    }

    // Historical exclusive-station contract retired by the approved range-interaction requirement.
    public static void narrowPassageAndContainerStationsUseExclusiveQueues(GameTestHelper helper) {
        BlockPos passage = helper.absolutePos(new BlockPos(8, 10, 8));
        UUID first = UUID.randomUUID();
        UUID follower = UUID.randomUUID();
        UUID opposite = UUID.randomUUID();
        helper.assertTrue(CitizenNavigationReservationService.requestPassage(
                        helper.getLevel(), first, passage, Direction.NORTH, 100L),
                "First resident did not receive the narrow-passage direction token");
        helper.assertTrue(CitizenNavigationReservationService.requestPassage(
                        helper.getLevel(), first, passage, Direction.SOUTH, 100L)
                        && CitizenNavigationReservationService.requestPassage(
                        helper.getLevel(), first, passage, Direction.NORTH, 100L),
                "A resident reversing before leaving a narrow passage queued behind itself");
        helper.assertTrue(!CitizenNavigationReservationService.requestPassage(
                        helper.getLevel(), follower, passage, Direction.NORTH, 100L)
                        && !CitizenNavigationReservationService.requestPassage(
                        helper.getLevel(), opposite, passage, Direction.SOUTH, 100L),
                "Same-direction follower skipped the queue or opposite traffic entered the passage");
        CitizenNavigationReservationService.releasePassedPassages(
                helper.getLevel(), first, passage.east(10), 100L);
        helper.assertTrue(!CitizenNavigationReservationService.requestPassage(
                        helper.getLevel(), follower, passage, Direction.NORTH, 100L),
                "A resident waiting before entry lost its passage priority and was re-queued every work cycle");
        CitizenNavigationReservationService.releasePassages(helper.getLevel(), first);
        helper.assertTrue(CitizenNavigationReservationService.requestPassage(
                        helper.getLevel(), follower, passage, Direction.NORTH, 101L),
                "Same-direction follower did not advance after the first resident released the passage");

        BlockPos junction = passage.east(3);
        CitizenNavigationReservationService.releasePassages(helper.getLevel(), follower);
        helper.assertTrue(CitizenNavigationReservationService.requestPassage(
                        helper.getLevel(), first, junction, Direction.NORTH, 120L)
                        && !CitizenNavigationReservationService.requestPassage(
                        helper.getLevel(), follower, junction, Direction.EAST, 120L)
                        && !CitizenNavigationReservationService.requestPassage(
                        helper.getLevel(), opposite, junction, Direction.WEST, 120L),
                "Three-way merge admitted conflicting approaches into the same narrow entrance");
        CitizenNavigationReservationService.releasePassages(helper.getLevel(), first);
        helper.assertTrue(CitizenNavigationReservationService.requestPassage(
                        helper.getLevel(), follower, junction, Direction.EAST, 121L),
                "Three-way merge queue did not advance after the entrance was released");

        BlockPos warehouseDoor = passage.west(3);
        CitizenNavigationReservationService.releasePassages(helper.getLevel(), follower);
        helper.assertTrue(CitizenNavigationReservationService.requestPassage(
                        helper.getLevel(), first, warehouseDoor, Direction.NORTH, 140L)
                        && !CitizenNavigationReservationService.requestPassage(
                        helper.getLevel(), opposite, warehouseDoor, Direction.SOUTH, 140L),
                "Warehouse single door allowed inbound and outbound traffic at the same time");
        CitizenNavigationReservationService.releasePassages(helper.getLevel(), first);
        helper.assertTrue(CitizenNavigationReservationService.requestPassage(
                        helper.getLevel(), opposite, warehouseDoor, Direction.SOUTH, 141L),
                "Warehouse outbound queue did not advance after inbound traffic cleared the door");

        BlockPos station = passage.east();
        helper.assertTrue(CitizenNavigationReservationService.claimStation(
                        helper.getLevel(), first, station, 200L)
                        && !CitizenNavigationReservationService.claimStation(
                        helper.getLevel(), opposite, station, 200L)
                        && CitizenNavigationReservationService.claimStation(
                        helper.getLevel(), opposite, station, 301L),
                "Container standing position was shared concurrently or not released after timeout");
        CitizenNavigationReservationService.releaseAll(helper.getLevel(), first);
        CitizenNavigationReservationService.releaseAll(helper.getLevel(), follower);
        CitizenNavigationReservationService.releaseAll(helper.getLevel(), opposite);
        helper.succeed();
    }

    // Historical exclusive-station contract retired by the approved range-interaction requirement.
    public static void remoteWarehouseStationOwnerExpiresAndPresentResidentWins(GameTestHelper helper) {
        BlockPos station = helper.absolutePos(new BlockPos(8, 10, 8));
        UUID remote = UUID.randomUUID();
        UUID present = UUID.randomUUID();
        helper.assertTrue(CitizenNavigationReservationService.claimStation(
                        helper.getLevel(), remote, station, station.east(12), 100L),
                "Remote resident could not create the initial station reservation");
        helper.assertTrue(CitizenNavigationReservationService.claimStation(
                        helper.getLevel(), present, station, station, 110L)
                        && station.equals(CitizenNavigationReservationService.stationFor(
                        helper.getLevel(), present, 110L)),
                "Resident physically at the interaction cell did not supersede a remote reservation");

        BlockPos second = station.east(3);
        helper.assertTrue(CitizenNavigationReservationService.claimStation(
                        helper.getLevel(), remote, second, second.east(12), 200L)
                        && CitizenNavigationReservationService.claimStation(
                        helper.getLevel(), remote, second, second.east(12), 240L)
                        && !CitizenNavigationReservationService.claimStation(
                        helper.getLevel(), present, second, second.east(2), 299L)
                        && CitizenNavigationReservationService.claimStation(
                        helper.getLevel(), present, second, second, 301L),
                "A non-approaching resident renewed a warehouse station beyond five seconds");
        CitizenNavigationReservationService.releaseAll(helper.getLevel(), remote);
        CitizenNavigationReservationService.releaseAll(helper.getLevel(), present);
        helper.succeed();
    }

    // Historical exclusive-station contract retired by the approved range-interaction requirement.
    public static void changedTemporaryAccessClearsOnlyItsOwnStationCooldown(GameTestHelper helper) {
        BlockPos station = helper.absolutePos(new BlockPos(8, 10, 8));
        UUID builder = UUID.randomUUID();
        helper.assertTrue(CitizenNavigationReservationService.claimStation(
                        helper.getLevel(), builder, station, station.east(6), 100L),
                "Builder could not establish the initial construction station");
        CitizenNavigationReservationService.abandonStation(helper.getLevel(), builder, 110L);
        helper.assertTrue(!CitizenNavigationReservationService.claimStation(
                        helper.getLevel(), builder, station, station.east(6), 111L),
                "Ordinary stuck recovery did not preserve its station retry cooldown");
        CitizenNavigationReservationService.resetStationAfterAccessTopologyChange(
                helper.getLevel(), builder);
        helper.assertTrue(CitizenNavigationReservationService.claimStation(
                        helper.getLevel(), builder, station, station.east(6), 112L),
                "A new temporary access route did not release the builder's stale cooldown");
        CitizenNavigationReservationService.releaseAll(helper.getLevel(), builder);
        helper.succeed();
    }

    // Historical exclusive-station contract retired by the approved range-interaction requirement.
    public static void remoteResidentDoesNotReserveWarehouseInteractionCell(GameTestHelper helper) {
        BlockPos barrelPos = helper.absolutePos(new BlockPos(8, 10, 8));
        BlockPos station = barrelPos.east();
        helper.getLevel().setBlock(barrelPos, Blocks.BARREL.defaultBlockState(), 3);
        helper.getLevel().setBlock(station.below(), Blocks.STONE_BRICKS.defaultBlockState(), 3);
        Container barrel = (Container) helper.getLevel().getBlockEntity(barrelPos);
        barrel.setItem(0, new ItemStack(Items.STONE_BRICKS, 32));
        BuildingRecord warehouse = BuildingRecord.create(BuildingType.WAREHOUSE, barrelPos);
        warehouse.updateFunction(true, BuildingStatus.OPERATIONAL, 27, List.of(barrelPos));
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Could not create remote warehouse resident");
        entity.moveTo(barrelPos.getX() + 20.5D, barrelPos.getY(), barrelPos.getZ() + 0.5D, 0.0F, 0.0F);
        long gameTime = helper.getLevel().getGameTime();
        CitizenNavigationReservationService.claimStation(
                helper.getLevel(), entity.getUUID(), station, entity.blockPosition(), gameTime);

        BlockPos reserved = WarehouseInventoryService.reserveExtractionPosition(
                helper.getLevel(), warehouse, entity, Items.STONE_BRICKS);
        helper.assertTrue(reserved == null && CitizenNavigationReservationService.stationFor(
                        helper.getLevel(), entity.getUUID(), gameTime) == null,
                "A remote resident reserved an indoor warehouse interaction cell before reaching the building");
        CitizenNavigationReservationService.releaseAll(helper.getLevel(), entity.getUUID());
        entity.discard();
        helper.succeed();
    }

    // Historical exclusive-station contract retired by the approved range-interaction requirement.
    public static void straightCorridorSharesOneReservationAcrossItsFullLength(GameTestHelper helper) {
        BlockPos west = helper.absolutePos(new BlockPos(4, 3, 8));
        BlockPos east = west.east(8);
        for (BlockPos ground : BlockPos.betweenClosed(west.offset(-1, -1, -1), west.offset(1, -1, 1))) {
            helper.getLevel().setBlock(ground, Blocks.STONE_BRICKS.defaultBlockState(), 2);
        }
        for (BlockPos ground : BlockPos.betweenClosed(east.offset(-1, -1, -1), east.offset(1, -1, 1))) {
            helper.getLevel().setBlock(ground, Blocks.STONE_BRICKS.defaultBlockState(), 2);
        }
        for (int offset = 0; offset <= 8; offset++) {
            BlockPos cell = west.east(offset);
            helper.getLevel().setBlock(cell.below(), Blocks.STONE_BRICKS.defaultBlockState(), 2);
            helper.getLevel().setBlock(cell, Blocks.AIR.defaultBlockState(), 2);
            helper.getLevel().setBlock(cell.above(), Blocks.AIR.defaultBlockState(), 2);
            if (offset > 0 && offset < 8) {
                helper.getLevel().setBlock(cell.north(), Blocks.STONE_BRICKS.defaultBlockState(), 2);
                helper.getLevel().setBlock(cell.south(), Blocks.STONE_BRICKS.defaultBlockState(), 2);
            }
        }
        SettlementCitizenEntity westbound = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        SettlementCitizenEntity eastbound = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(westbound != null && eastbound != null,
                "Could not create residents for full-corridor reservation test");
        westbound.moveTo(west.getX() + 0.5D, west.getY(), west.getZ() + 0.5D, 0.0F, 0.0F);
        eastbound.moveTo(east.getX() + 0.5D, east.getY(), east.getZ() + 0.5D, 0.0F, 0.0F);
        westbound.setOnGround(true);
        eastbound.setOnGround(true);
        helper.getLevel().addFreshEntity(westbound);
        helper.getLevel().addFreshEntity(eastbound);

        BlockPos westKey = westbound.upcomingPassageReservationKey(east);
        BlockPos eastKey = eastbound.upcomingPassageReservationKey(west);
        helper.assertTrue(westKey != null && westKey.equals(eastKey)
                        && westbound.upcomingPassageReservationSpan(east) == 9
                        && eastbound.upcomingPassageReservationSpan(west) == 9,
                "Opposite entrances did not resolve the straight corridor to one full-length reservation: westKey="
                        + westKey + ", eastKey=" + eastKey + ", westSpan="
                        + westbound.upcomingPassageReservationSpan(east) + ", eastSpan="
                        + eastbound.upcomingPassageReservationSpan(west));

        long gameTime = helper.getLevel().getGameTime();
        helper.assertTrue(westbound.navigateTo(east, 0.70D).isEmpty(),
                "First resident could not start navigation through the shared corridor");
        CitizenNavigationReservationService.releasePassedPassages(
                helper.getLevel(), westbound.getUUID(), west.east(4), gameTime);
        helper.assertTrue(!CitizenNavigationReservationService.requestPassage(
                        helper.getLevel(), eastbound.getUUID(), westKey, Direction.WEST, gameTime),
                "Full-corridor reservation was released while its owner was still inside the lane");
        CitizenNavigationReservationService.releasePassedPassages(
                helper.getLevel(), westbound.getUUID(), east.east(5), gameTime);
        helper.assertTrue(CitizenNavigationReservationService.requestPassage(
                        helper.getLevel(), eastbound.getUUID(), westKey, Direction.WEST, gameTime),
                "Opposite queue did not advance after the first resident left the full corridor");
        westbound.discard();
        eastbound.discard();
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void trafficYieldWaitsAtReachableWidePositionForItsCooldown(GameTestHelper helper) {
        BlockPos left = helper.absolutePos(new BlockPos(7, 3, 8));
        BlockPos right = left.east();
        for (BlockPos ground : BlockPos.betweenClosed(left.offset(-4, -1, -4), right.offset(4, -1, 4))) {
            helper.getLevel().setBlock(ground, Blocks.STONE_BRICKS.defaultBlockState(), 2);
        }
        SettlementCitizenEntity first = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        SettlementCitizenEntity second = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(first != null && second != null,
                "Could not create residents for traffic-yield cooldown test");
        first.moveTo(left.getX() + 0.5D, left.getY(), left.getZ() + 0.5D, 0.0F, 0.0F);
        second.moveTo(right.getX() + 0.5D, right.getY(), right.getZ() + 0.5D, 0.0F, 180.0F);
        first.setOnGround(true);
        second.setOnGround(true);
        helper.getLevel().addFreshEntity(first);
        helper.getLevel().addFreshEntity(second);
        BlockPos firstTarget = right.east(4);
        BlockPos secondTarget = left.west(4);
        first.navigateTo(firstTarget, 0.70D);
        second.navigateTo(secondTarget, 0.70D);
        first.navigateTo(firstTarget, 0.70D);

        SettlementCitizenEntity yielding = first.yieldingForTraffic() ? first : second;
        SettlementCitizenEntity other = yielding == first ? second : first;
        BlockPos originalTarget = yielding == first ? firstTarget : secondTarget;
        BlockPos yieldPosition = yielding.trafficYieldPosition();
        helper.assertTrue(yieldPosition != null && yielding.canStandAtPosition(yieldPosition),
                "Head-on resident did not choose a reachable wide traffic-yield position: firstYielding="
                        + first.yieldingForTraffic() + ", secondYielding=" + second.yieldingForTraffic()
                        + ", firstPath=" + first.getNavigation().getPath()
                        + ", secondPath=" + second.getNavigation().getPath());
        other.discard();
        yielding.moveTo(yieldPosition.getX() + 0.5D, yieldPosition.getY(),
                yieldPosition.getZ() + 0.5D, 0.0F, 0.0F);
        yielding.setOnGround(true);
        helper.assertTrue(yielding.navigateTo(originalTarget, 0.70D).isEmpty()
                        && yielding.yieldingForTraffic()
                        && yieldPosition.equals(yielding.trafficYieldPosition()),
                "Resident immediately resumed the conflicting route after reaching the yield position");
        yielding.discard();
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void perBuildingTargetsAndLockedBlockedAssignmentsPersist(GameTestHelper helper) {
        SettlementRecord settlement = settlement();
        BuildingRecord first = addTargetedWorkplace(
                settlement, BuildingType.FARM_FIELD, 1, new BlockPos(12, 70, 12));
        BuildingRecord second = addTargetedWorkplace(
                settlement, BuildingType.FARM_FIELD, 1, new BlockPos(24, 70, 12));
        ProfessionAssignmentService.refresh(new SettlementSavedData(), settlement);
        helper.assertTrue(settlement.citizens().stream()
                        .filter(citizen -> first.id().equals(citizen.workBuildingId())).count() == 1
                        && settlement.citizens().stream()
                        .filter(citizen -> second.id().equals(citizen.workBuildingId())).count() == 1,
                "Automatic staffing did not fill each building's independent target");
        CitizenRecord locked = settlement.citizens().stream()
                .filter(citizen -> first.id().equals(citizen.workBuildingId())).findFirst().orElseThrow();
        locked.setManualAssignment(CitizenProfession.FARMER, first.id());
        first.markConfigurationChanged();
        settlement.queueBuildingDemolition(first.id());
        ProfessionAssignmentService.refresh(new SettlementSavedData(), settlement);
        helper.assertTrue(locked.hasManualAssignment() && first.id().equals(locked.workBuildingId()),
                "A disabled workplace silently reassigned its locked resident");

        SettlementRecord loaded = SettlementRecord.load(
                settlement.save(helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
        CitizenRecord loadedCitizen = loaded.citizen(locked.id());
        BuildingRecord loadedFirst = loaded.building(first.id());
        helper.assertTrue(loadedCitizen != null && loadedFirst != null
                        && loadedCitizen.hasManualAssignment()
                        && first.id().equals(loadedCitizen.manualWorkBuildingId())
                        && loadedFirst.workerTarget() >= 1,
                "Locked building staffing intent did not survive save/load while blocked");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void lockedCitizenUnlockWaitsForSafeWorkHandoff(GameTestHelper helper) {
        SettlementRecord settlement = settlement();
        BuildingRecord field = addTargetedWorkplace(
                settlement, BuildingType.FARM_FIELD, 1, new BlockPos(12, 70, 12));
        CitizenRecord citizen = settlement.citizens().iterator().next();
        citizen.setManualAssignment(CitizenProfession.FARMER, field.id());
        helper.assertTrue(citizen.startTask(CitizenWorkState.TO_BLUEPRINT,
                        field.position(), null, UUID.randomUUID()),
                "Test resident could not enter an in-flight work state");
        helper.assertTrue(citizen.requestManualRelease(),
                "Unlock intent was not accepted for a working resident");
        ProfessionAssignmentService.refresh(new SettlementSavedData(), settlement);
        helper.assertTrue(citizen.hasManualAssignment() && citizen.manualReleasePending(),
                "A working resident was unlocked before reaching a safe handoff point");

        SettlementRecord loaded = SettlementRecord.load(
                settlement.save(helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
        CitizenRecord loadedCitizen = loaded.citizen(citizen.id());
        helper.assertTrue(loadedCitizen != null && loadedCitizen.hasManualAssignment()
                        && loadedCitizen.manualReleasePending(),
                "Pending safe unlock did not survive save/load");
        loadedCitizen.clearWork();
        ProfessionAssignmentService.refresh(new SettlementSavedData(), loaded);
        helper.assertTrue(!loadedCitizen.hasManualAssignment() && !loadedCitizen.manualReleasePending(),
                "Resident did not return to automatic staffing after the safe handoff point");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void tierThreeRoadReplacesTierOneSurface(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos surface = helper.absolutePos(new BlockPos(8, 10, 8));
        helper.getLevel().setBlock(surface, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
        SettlementRecord settlement = new SettlementRecord(UUID.randomUUID(), "Road Upgrade",
                surface.offset(0, 0, -2), player.getUUID());
        var first = RoadService.placeRoad(helper.getLevel(), settlement, player, surface, surface, RoadTier.LEVEL_1);
        helper.assertTrue(first.success() && helper.getLevel().getBlockState(surface).is(Blocks.DIRT_PATH),
                "Level-one road surface was not created");
        var upgraded = RoadService.placeRoad(helper.getLevel(), settlement, player, surface, surface, RoadTier.LEVEL_3);
        helper.assertTrue(upgraded.success() && helper.getLevel().getBlockState(surface).is(Blocks.STONE_BRICKS)
                        && RoadSpaceData.get(helper.getLevel()).levelAt(surface) == 3,
                "Level-three road did not replace the level-one surface and road data");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void demolitionRemovesTemplateBlockButKeepsDecoration(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 10, 8));
        BuildingBlueprintTemplate template = BlueprintTemplates.create(BuildingType.HOUSE, anchor, Direction.NORTH);
        var placements = template.placements().stream()
                .filter(value -> value.mode() == BlueprintPlacementMode.STRUCTURE)
                .limit(2).toList();
        var placement = placements.getFirst();
        var secondPlacement = placements.getLast();
        helper.getLevel().setBlock(placement.position(), placement.state(), 2);
        helper.getLevel().setBlock(secondPlacement.position(), secondPlacement.state(), 2);
        BlockPos decoration = template.maxCorner().offset(2, 0, 0);
        helper.getLevel().setBlock(decoration, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
        BuildingRecord building = BuildingRecord.create(BuildingType.HOUSE, anchor, Direction.NORTH,
                template.minCorner(), template.maxCorner(), template.transitionMargin(), 4);
        building.queueDemolition();
        BlockPos firstClaim = BuildingDemolitionService.nextBlock(
                helper.getLevel(), building, Set.of(), placement.position());
        helper.assertTrue(firstClaim != null, "First demolition worker could not reserve a template block");
        BlockPos secondClaim = BuildingDemolitionService.nextBlock(
                helper.getLevel(), building, Set.of(firstClaim), secondPlacement.position());
        helper.assertTrue(secondClaim != null && !firstClaim.equals(secondClaim),
                "Two demolition workers could not reserve different blocks on one building");
        helper.assertTrue(BuildingDemolitionService.removeAt(
                        helper.getLevel(), building, firstClaim, player),
                "A queued demolition could not remove its next template block");
        helper.assertTrue(helper.getLevel().getBlockState(firstClaim).isAir()
                        && !helper.getLevel().getBlockState(secondClaim).isAir()
                        && helper.getLevel().getBlockState(decoration).is(Blocks.GOLD_BLOCK),
                "Demolition removed another worker's claim, failed its own target, or removed transition decoration");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void nightRoutingUsesAssignedBedAndActualSleepPose(GameTestHelper helper) {
        BlockPos core = helper.absolutePos(new BlockPos(8, 10, 8));
        SettlementSavedData data = new SettlementSavedData();
        SettlementRecord settlement = new SettlementRecord(UUID.randomUUID(), "Sleep Test", core, UUID.randomUUID());
        CitizenRecord citizen = settlement.citizens().iterator().next();
        BlockPos bed = citizen.lastPosition();
        helper.getLevel().setBlock(bed,
                Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD), 2);
        BuildingRecord house = settlement.addBuilding(BuildingType.HOUSE, bed);
        house.updateFunction(true, BuildingStatus.OPERATIONAL, 1, List.of(bed));
        citizen.assignHome(house.id(), bed);
        SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Sleep test resident projection was not created");
        entity.moveTo(bed.getX() + 0.5D, bed.getY(), bed.getZ() + 0.5D, 0.0F, 0.0F);
        helper.getLevel().addFreshEntity(entity);
        CitizenWorkService.routeResidentToPersonalBed(helper.getLevel(), data, settlement, citizen, entity);
        helper.assertTrue(entity.isSleeping() && citizen.bedPosition().equals(bed),
                "Night routing did not put the resident into the assigned bed sleep pose");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", timeoutTicks = 100)
    public static void blockedBedRouteMakesARealShortRecoveryStep(GameTestHelper helper) {
        BlockPos start = helper.absolutePos(new BlockPos(8, 2, 8));
        for (int dx = -1; dx <= 5; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                helper.getLevel().setBlock(start.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 2);
                helper.getLevel().setBlock(start.offset(dx, 0, dz), Blocks.AIR.defaultBlockState(), 2);
                helper.getLevel().setBlock(start.offset(dx, 1, dz), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        SettlementCitizenEntity resident = ModEntities.SETTLEMENT_CITIZEN.get().create(helper.getLevel());
        helper.assertTrue(resident != null, "Recovery test resident was not created");
        setForcedChunksBetween(helper.getLevel(), start, start.east(5), true);
        resident.moveTo(start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D, 0.0F, 0.0F);
        resident.setOnGround(true);
        resident.restrictTo(start.east(3), 16);
        helper.assertTrue(helper.getLevel().addFreshEntity(resident), "Recovery test resident did not spawn");
        BlockPos bed = start.east(5);
        helper.startSequence().thenExecute(() -> {
            resident.setOnGround(true);
            helper.assertTrue(resident.canStandAtPosition(start.east()),
                    "The recovery fixture has no legal adjacent standing cell");
            helper.assertTrue(resident.hasReachablePathTo(start.east()),
                    "Vanilla pathfinder cannot reach the fixture's adjacent standing cell");
            helper.assertTrue(resident.beginBedRecovery(bed, 0.7D),
                    "No real local route was chosen toward the bed");
            helper.assertTrue(resident.blockPosition().equals(start), "Recovery teleported the resident");
        }).thenWaitUntil(() -> helper.assertTrue(
                resident.getX() > start.getX() + 0.9D,
                "Resident has not physically moved toward the bed yet"))
                .thenExecute(resident::discard).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void nightPauseKeepsBackpackContents(GameTestHelper helper) {
        helper.getLevel().setDayTime(13000L);
        BlockPos core = helper.absolutePos(new BlockPos(8, 10, 8));
        SettlementSavedData data = new SettlementSavedData();
        SettlementRecord settlement = new SettlementRecord(
                UUID.randomUUID(), "Night Inventory Test", core, UUID.randomUUID());
        CitizenRecord citizen = settlement.citizens().iterator().next();
        helper.assertTrue(citizen.insertIntoInventory(new ItemStack(Items.OAK_PLANKS, 32)).isEmpty(),
                "Could not prepare the resident backpack");
        int slot = citizen.findInventorySlot(stack -> stack.is(Items.OAK_PLANKS));
        helper.assertTrue(citizen.startTask(CitizenWorkState.TO_BLUEPRINT,
                        core.east(), null, UUID.randomUUID()) && citizen.selectActiveSlot(slot),
                "Could not prepare an active carried construction stack");
        CitizenProjectionService.ensureSettlementCitizens(helper.getLevel(), settlement);
        SettlementCitizenEntity entity = CitizenProjectionService.findProjection(helper.getLevel(), citizen);
        helper.assertTrue(entity != null, "Night inventory resident did not receive a projection");

        CitizenWorkService.tickSettlement(helper.getLevel(), data, settlement);

        helper.assertTrue(citizen.workState() == CitizenWorkState.IDLE
                        && citizen.findInventorySlot(stack -> stack.is(Items.OAK_PLANKS)
                        && stack.getCount() == 32) >= 0,
                "Night pause removed or dropped the resident's carried backpack stack");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void residentsCollectOnlyUnownedSettlementDrops(GameTestHelper helper) {
        helper.getLevel().setDayTime(6000L);
        BlockPos core = helper.absolutePos(new BlockPos(8, 10, 8));
        SettlementSavedData data = new SettlementSavedData();
        SettlementRecord settlement = new SettlementRecord(
                UUID.randomUUID(), "Public Drop Test", core, UUID.randomUUID());
        CitizenRecord citizen = settlement.citizens().iterator().next();
        ItemEntity publicDrop = new ItemEntity(helper.getLevel(),
                core.getX() + 1.5D, core.getY(), core.getZ() + 0.5D,
                new ItemStack(Items.COBBLESTONE, 12));
        ItemEntity ownedDrop = new ItemEntity(helper.getLevel(),
                core.getX() + 2.5D, core.getY(), core.getZ() + 0.5D,
                new ItemStack(Items.DIAMOND, 1)) {
            @Override
            public net.minecraft.world.entity.Entity getOwner() {
                return this;
            }
        };
        helper.assertTrue(helper.getLevel().addFreshEntity(publicDrop)
                        && helper.getLevel().addFreshEntity(ownedDrop),
                "Could not spawn public-drop regression entities");
        helper.assertTrue(CitizenWorkService.publicItemAvailable(settlement, publicDrop)
                        && !CitizenWorkService.publicItemAvailable(settlement, ownedDrop),
                "Player-owned and public drops were not separated");
        helper.assertTrue(citizen.startTask(CitizenWorkState.TO_PUBLIC_ITEM,
                        publicDrop.blockPosition(), publicDrop.getUUID(), null),
                "Resident could not start a public item collection task");
        CitizenProjectionService.ensureSettlementCitizens(helper.getLevel(), settlement);
        SettlementCitizenEntity entity = CitizenProjectionService.findProjection(helper.getLevel(), citizen);
        helper.assertTrue(entity != null, "Public-drop resident did not receive a projection");
        entity.moveTo(publicDrop.getX(), publicDrop.getY(), publicDrop.getZ(), 0.0F, 0.0F);

        CitizenWorkService.tickSettlement(helper.getLevel(), data, settlement);

        helper.assertTrue(!publicDrop.isAlive()
                        && citizen.findInventorySlot(stack -> stack.is(Items.COBBLESTONE)
                        && stack.getCount() == 12) >= 0
                        && ownedDrop.isAlive(),
                "Resident failed to collect the public drop or stole the player-owned drop");
        ownedDrop.discard();
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void residentInventoryHasThirtySixPersistentSlots(GameTestHelper helper) {
        CitizenRecord citizen = new CitizenRecord(UUID.randomUUID(), "Inventory Test", BlockPos.ZERO);
        helper.assertTrue(citizen.inventory().size() == 36, "Resident inventory is not player-sized");
        helper.assertTrue(citizen.inventoryCapacityFor(new ItemStack(Items.WHEAT)) == 36 * 64,
                "Empty resident backpack did not expose all 36 stack slots");
        helper.assertTrue(citizen.insertIntoInventory(new ItemStack(Items.WHEAT, 64)).isEmpty(),
                "Resident could not accept a normal full stack");
        helper.assertTrue(citizen.insertIntoInventory(new ItemStack(Items.CARROT, 70)).isEmpty(),
                "Resident inventory did not spread items across multiple slots");
        ItemStack hoe = new ItemStack(Items.IRON_HOE);
        hoe.setDamageValue(17);
        helper.assertTrue(citizen.insertIntoInventory(hoe).isEmpty(), "Resident could not store profession equipment");

        CitizenRecord loaded = CitizenRecord.load(citizen.save(helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        int carrots = loaded.inventory().stream().filter(stack -> stack.is(Items.CARROT))
                .mapToInt(ItemStack::getCount).sum();
        int hoeSlot = loaded.findInventorySlot(stack -> stack.is(Items.IRON_HOE));
        helper.assertTrue(loaded.inventory().size() == 36 && carrots == 70 && hoeSlot >= 0
                        && loaded.inventoryItem(hoeSlot).getDamageValue() == 17,
                "Resident inventory contents or tool durability did not survive save/load");
        helper.assertTrue(CitizenWorkService.constructionCapable(CitizenProfession.WORKER)
                        && !CitizenWorkService.constructionCapable(CitizenProfession.FARMER),
                "Default builders were not isolated as the construction-capable profession");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void professionEquipmentPolicyIsSequentialAndFunctional(GameTestHelper helper) {
        CitizenRecord farmer = new CitizenRecord(UUID.randomUUID(), "Farmer", BlockPos.ZERO);
        farmer.assignProfession(CitizenProfession.FARMER, null);
        helper.assertTrue(CitizenEquipmentService.missingEquipment(farmer) == Items.IRON_HOE,
                "Farmer did not request a hoe");
        farmer.insertIntoInventory(new ItemStack(Items.IRON_HOE));
        helper.assertTrue(CitizenEquipmentService.missingEquipment(farmer) == null
                        && CitizenEquipmentService.toolSlotForWork(farmer) >= 0,
                "Farmer did not recognize the stored hoe as working equipment");

        CitizenRecord worker = new CitizenRecord(UUID.randomUUID(), "Builder", BlockPos.ZERO);
        worker.insertIntoInventory(new ItemStack(Items.IRON_PICKAXE));
        helper.assertTrue(CitizenEquipmentService.missingEquipment(worker) == null
                        && CitizenEquipmentService.toolSlotForWork(worker) < 0,
                "An idle builder treated construction tools as permanent profession equipment");
        worker.startDemolition(UUID.randomUUID(), BlockPos.ZERO);
        helper.assertTrue(CitizenEquipmentService.toolSlotForWork(worker) >= 0,
                "A builder did not select an available tool after receiving a demolition task");

        CitizenRecord toolsmith = new CitizenRecord(UUID.randomUUID(), "Toolsmith", BlockPos.ZERO);
        toolsmith.assignProfession(CitizenProfession.TOOLSMITH, null);
        toolsmith.insertIntoInventory(new ItemStack(Items.IRON_PICKAXE));
        toolsmith.insertIntoInventory(new ItemStack(Items.IRON_AXE));
        int workingPickaxe = toolsmith.findInventorySlot(stack -> stack.is(Items.IRON_PICKAXE));
        int craftedAxe = toolsmith.findInventorySlot(stack -> stack.is(Items.IRON_AXE));
        helper.assertTrue(CitizenEquipmentService.isReservedEquipment(toolsmith, workingPickaxe)
                        && CitizenEquipmentService.isReservedEquipment(toolsmith, craftedAxe),
                "Toolsmith did not reserve one personal tool of each category");
        toolsmith.insertIntoInventory(new ItemStack(Items.IRON_AXE));
        helper.assertTrue(java.util.stream.IntStream.range(0,CitizenRecord.INVENTORY_SIZE)
                .filter(slot -> toolsmith.inventoryItem(slot).is(Items.IRON_AXE)
                        && !CitizenEquipmentService.isReservedEquipment(toolsmith,slot)).count() == 1,
                "Duplicate produced axe must remain deliverable");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void variableFarmAndPastureBlueprintsKeepSelectedBounds(GameTestHelper helper) {
        BlockPos first = helper.absolutePos(new BlockPos(3, 2, 4));
        BlockPos second = helper.absolutePos(new BlockPos(9, 2, 11));
        BlueprintRecord farm = BlueprintRecord.createArea(BuildingType.FARM_FIELD,
                new BlockPos(6, 70, 7), new BlockPos(2, 70, 4), Direction.NORTH,
                UUID.randomUUID(), false, first, second);
        BuildingBlueprintTemplate farmTemplate = BlueprintTemplates.create(farm);
        long farmland = farmTemplate.placements().stream()
                .filter(placement -> placement.mode() == BlueprintPlacementMode.FARM_TILL)
                .filter(placement -> placement.state().is(Blocks.FARMLAND)).count();
        long irrigation = farmTemplate.placements().stream()
                .filter(placement -> placement.state().is(Blocks.OAK_SLAB)
                        && !placement.state().getFluidState().isEmpty()).count();
        long farmRoads = farmTemplate.placements().stream()
                .filter(placement -> placement.state().is(Blocks.GRAVEL)).count();
        long farmPerimeter = farmTemplate.placements().stream()
                .filter(placement -> placement.state().is(Blocks.OAK_FENCE)
                        || placement.state().is(Blocks.OAK_FENCE_GATE)).count();
        long farmGates = farmTemplate.placements().stream()
                .filter(placement -> placement.state().is(Blocks.OAK_FENCE_GATE)).count();
        Set<BlockPos> farmTargets = new HashSet<>();
        boolean uniqueFarmTargets = farmTemplate.placements().stream()
                .allMatch(placement -> farmTargets.add(placement.position()));
        helper.assertTrue(farm.dynamicArea() && farmTemplate.width() == 7 && farmTemplate.depth() == 8
                        && farmland == 20 && irrigation == 1 && farmRoads == 9 && farmPerimeter == 26
                        && farmGates == 4 && farmTemplate.entrances().size() == 4 && uniqueFarmTargets,
                "Variable farm did not preserve its bounds or assigned multiple construction steps to one block");

        placeCompletedTemplate(helper, farmTemplate);
        for (var placement : farmTemplate.placements()) {
            if (placement.state().is(Blocks.FARMLAND)) {
                helper.getLevel().setBlock(placement.position(),
                        placement.state().setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE), 3);
            } else if (placement.state().is(Blocks.OAK_FENCE_GATE)) {
                helper.getLevel().setBlock(placement.position(),
                        helper.getLevel().getBlockState(placement.position())
                                .setValue(FenceGateBlock.OPEN, true), 3);
            }
        }
        helper.assertTrue(BlueprintService.constructionComplete(helper.getLevel(), farm),
                "A completed farm was invalidated by its final fence, open gate, or hydrated farmland");

        SettlementRecord gateAccessSettlement = new SettlementRecord(UUID.randomUUID(), "Farm Gate Test",
                farmTemplate.registrationPosition(), UUID.randomUUID());
        BuildingRecord operationalFarm = gateAccessSettlement.addBuilding(BuildingType.FARM_FIELD,
                farmTemplate.registrationPosition(), Direction.NORTH, farmTemplate.minCorner(),
                farmTemplate.maxCorner(), farmTemplate.transitionMargin(), 2);
        operationalFarm.updateFunction(true, BuildingStatus.OPERATIONAL, 1, List.of());
        CitizenRecord farmer = gateAccessSettlement.citizens().iterator().next();
        farmer.assignProfession(CitizenProfession.FARMER, operationalFarm.id());
        var gate = farmTemplate.placements().stream()
                .filter(placement -> placement.state().is(Blocks.OAK_FENCE_GATE))
                .findFirst().orElseThrow();
        helper.getLevel().setBlock(gate.position(),
                helper.getLevel().getBlockState(gate.position()).setValue(FenceGateBlock.OPEN, false), 3);
        CitizenWorkService.updateFarmGateAccess(helper.getLevel(), gateAccessSettlement);
        helper.assertTrue(helper.getLevel().getBlockState(gate.position()).getValue(FenceGateBlock.OPEN),
                "A staffed farm kept a pathfinding entrance closed");
        farmer.assignProfession(CitizenProfession.WORKER, null);
        CitizenWorkService.updateFarmGateAccess(helper.getLevel(), gateAccessSettlement);
        helper.assertTrue(!helper.getLevel().getBlockState(gate.position()).getValue(FenceGateBlock.OPEN),
                "An unstaffed farm entrance did not return to its closed state");

        BuildingBlueprintTemplate pasture = BlueprintTemplates.createArea(BuildingType.PASTURE_AREA,
                first, second, Direction.EAST);
        long perimeter = pasture.placements().stream()
                .filter(placement -> placement.state().is(Blocks.OAK_FENCE)
                        || placement.state().is(Blocks.OAK_FENCE_GATE)).count();
        helper.assertTrue(perimeter == 2L * 7 + 2L * 8 - 4,
                "Variable pasture did not create exactly one fenced perimeter around its interior fixtures");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void legacyAreaBlueprintKeepsSingleEntranceTemplate(GameTestHelper helper) {
        BlockPos first = helper.absolutePos(new BlockPos(3, 2, 4));
        BlockPos second = helper.absolutePos(new BlockPos(11, 2, 12));
        BlueprintRecord current = BlueprintRecord.createArea(BuildingType.FARM_FIELD,
                first, first, Direction.NORTH, UUID.randomUUID(), false, first, second);
        CompoundTag legacyTag = current.save();
        legacyTag.putInt("StructureVersion", 8);
        legacyTag.remove("TemplateVersion");
        BlueprintRecord legacy = BlueprintRecord.load(legacyTag);
        BuildingBlueprintTemplate legacyTemplate = BlueprintTemplates.create(legacy);
        BuildingBlueprintTemplate currentTemplate = BlueprintTemplates.create(current);
        helper.assertTrue(legacy.templateVersion() == 1 && legacyTemplate.entrances().size() == 1,
                "A pre-0.14.6 area blueprint silently changed from its one-entrance template");
        helper.assertTrue(current.templateVersion() == 4 && currentTemplate.entrances().size() == 4,
                "A new 9x9 area blueprint did not receive one centered entrance per side");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void productionRepairsAndCoreOptionsRoundTrip(GameTestHelper helper) {
        SettlementRecord settlement = settlement();
        BlockPos min = new BlockPos(10, 69, 10);
        BlockPos max = new BlockPos(16, 70, 17);
        BuildingRecord farm = settlement.addBuilding(BuildingType.FARM_FIELD, new BlockPos(13, 70, 13),
                Direction.NORTH, min, max, 1);
        helper.assertTrue(farm.setProductionId(net.minecraft.resources.ResourceLocation.withDefaultNamespace("carrots")),
                "Farm rejected a supported crop selection");
        helper.assertTrue(farm.markDamaged(min), "Building damage position was not recorded");
        settlement.setCreativeCoreInstalled(true);
        settlement.toggleChunkLoading();
        settlement.replaceForcedChunks(Set.of(new ChunkPos(2, 3).toLong(), new ChunkPos(3, 3).toLong()));
        settlement.markRoadDamaged(new BlockPos(20, 70, 20));

        SettlementRecord loaded = SettlementRecord.load(settlement.save(helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        BuildingRecord loadedFarm = loaded.building(farm.id());
        helper.assertTrue(loaded.creativeCoreInstalled() && loaded.chunkLoadingEnabled()
                        && loaded.forcedChunkKeys().size() == 2 && loaded.roadRepairPositions().size() == 1,
                "Creative core, chunk loading, or road repair state did not survive save/load");
        helper.assertTrue(loadedFarm != null
                        && loadedFarm.productionId().equals(BuildingProductionService.FARM_CROPS.get(1))
                        && loadedFarm.repairPositions().contains(min),
                "Farm crop selection or building repair queue did not survive save/load");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void rtsEdgePanUsesRawWindowCoordinatesWithoutIdleDrift(GameTestHelper helper) {
        RtsEdgePan.Direction center = RtsEdgePan.compute(960.0D, 540.0D,
                1920, 1080, 24.0D, true);
        helper.assertTrue(center.right() == 0.0D && center.forward() == 0.0D,
                "A centered raw cursor produced unintended RTS camera movement");
        RtsEdgePan.Direction rightEdge = RtsEdgePan.compute(1910.0D, 540.0D,
                1920, 1080, 24.0D, true);
        helper.assertTrue(rightEdge.right() == 1.0D && rightEdge.forward() == 0.0D,
                "The actual raw-window right edge did not produce camera movement");
        RtsEdgePan.Direction unfocused = RtsEdgePan.compute(0.0D, 0.0D,
                1920, 1080, 24.0D, false);
        helper.assertTrue(unfocused.equals(RtsEdgePan.Direction.NONE),
                "An unfocused game window still produced edge movement");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void spectatorOverviewMovementIsCameraRelativeAndDiagonalNormalized(GameTestHelper helper) {
        var northForward = OverviewMovementMath.horizontal(0.0F, 1.0D, 0.0D, 0.65D);
        var eastForward = OverviewMovementMath.horizontal(90.0F, 1.0D, 0.0D, 0.65D);
        var diagonal = OverviewMovementMath.horizontal(37.0F, 1.0D, 1.0D, 0.65D);
        helper.assertTrue(Math.abs(northForward.x()) < 0.0001D && northForward.z() > 0.64D
                        && eastForward.x() < -0.64D && Math.abs(eastForward.z()) < 0.0001D,
                "Overview WASD movement is not relative to camera yaw");
        helper.assertTrue(Math.abs(Math.sqrt(diagonal.x() * diagonal.x() + diagonal.z() * diagonal.z()) - 0.65D)
                        < 0.0001D,
                "Diagonal overview movement exceeded the fixed camera speed");
        helper.succeed();
    }

    private static void setForcedChunksBetween(
            ServerLevel level, BlockPos first, BlockPos second, boolean forced
    ) {
        int minChunkX = Math.min(first.getX(), second.getX()) >> 4;
        int maxChunkX = Math.max(first.getX(), second.getX()) >> 4;
        int minChunkZ = Math.min(first.getZ(), second.getZ()) >> 4;
        int maxChunkZ = Math.max(first.getZ(), second.getZ()) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                level.setChunkForced(chunkX, chunkZ, forced);
            }
        }
    }

    private static SettlementRecord settlement() {
        return new SettlementRecord(UUID.randomUUID(), "Test Settlement", new BlockPos(8, 70, 8), UUID.randomUUID());
    }

    private static BuildingRecord addTargetedWorkplace(
            SettlementRecord settlement, BuildingType type, int target, BlockPos position
    ) {
        BuildingRecord building = settlement.addBuilding(type, position);
        building.updateFunction(true, BuildingStatus.OPERATIONAL, target, List.of(position));
        building.migrateWorkerTarget(target);
        return building;
    }

    private static void placeCompletedTemplate(GameTestHelper helper, BuildingBlueprintTemplate template) {
        for (var placement : template.placements()) {
            helper.getLevel().getChunkAt(placement.position());
            helper.getLevel().setBlock(placement.position(),
                    placement.mode() == BlueprintPlacementMode.CLEARANCE
                            ? Blocks.AIR.defaultBlockState() : placement.state(), 3);
        }
        for (var placement : template.placements()) {
            BlueprintBlockStateResolver.refreshExisting(helper.getLevel(), placement);
        }
    }

    private static void setTemplateDoorsOpen(GameTestHelper helper, BuildingBlueprintTemplate template) {
        for (var placement : template.placements()) {
            if (placement.state().getBlock() instanceof DoorBlock) {
                helper.getLevel().setBlock(placement.position(),
                        helper.getLevel().getBlockState(placement.position())
                                .setValue(DoorBlock.OPEN, true), 3);
            }
        }
    }

}
