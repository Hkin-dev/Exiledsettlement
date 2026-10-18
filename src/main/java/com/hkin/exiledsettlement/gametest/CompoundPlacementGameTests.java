package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CompoundPlacementGameTests {
    private CompoundPlacementGameTests() { }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "compound_placement")
    public static void doorsAndBedsPlaceBothCellsForOneItemInFourRotations(GameTestHelper helper) {
        int index = 0;
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Fixture fixture = new Fixture(helper, index++, facing);
            for (BlueprintPlacement primary : fixture.pairs()) {
                fixture.prepare(primary);
                ItemStack item = new ItemStack(primary.material(), 2);
                var result = BlueprintService.placeAt(fixture.level, fixture.data, fixture.settlement,
                        fixture.blueprint, primary.position(), item);
                helper.assertTrue(result.success(), "Paired placement failed: " + result.reason());
                helper.assertTrue(item.getCount() == 1, "Door/bed did not consume exactly one item");
                helper.assertTrue(BlueprintPlacementGroups.satisfied(fixture.level, fixture.template, primary),
                        "One action left half a door or bed");
                helper.assertTrue(BlueprintService.pendingAt(fixture.level, fixture.blueprint,
                        BlueprintPlacementGroups.partnerPosition(primary)) == null, "Partner remained a separate task");
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "compound_placement")
    public static void pairProtectionSeesBothCellsAndRollsBackWithoutMaterialLoss(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 5, Direction.NORTH);
        BlueprintPlacement primary = fixture.pairs().getFirst();
        fixture.prepare(primary);
        AtomicInteger events = new AtomicInteger();
        Consumer<BlockEvent.EntityMultiPlaceEvent> listener = event -> {
            if (event.getLevel() != fixture.level || !event.getPos().equals(primary.position())) return;
            events.incrementAndGet();
            helper.assertTrue(event.getReplacedBlockSnapshots().size() == 2,
                    "Protection event did not receive both world snapshots");
            helper.assertTrue(BlueprintPlacementGroups.satisfied(fixture.level, fixture.template, primary),
                    "Protection listener observed only half of the attempted structure");
            event.setCanceled(true);
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            ItemStack item = new ItemStack(primary.material());
            var result = BlueprintService.placeAt(fixture.level, fixture.data, fixture.settlement,
                    fixture.blueprint, primary.position(), item);
            helper.assertTrue(!result.success() && events.get() == 1 && item.getCount() == 1,
                    "Cancelled pair consumed material or bypassed multi-place event");
            for (BlueprintPlacement part : BlueprintPlacementGroups.parts(fixture.template, primary)) {
                helper.assertTrue(fixture.level.getBlockState(part.position()).isAir(),
                        "Cancelled pair left a partial world mutation");
            }
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "compound_placement")
    public static void occupiedPartnerAndMissingSupportRejectEntireAction(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 6, Direction.EAST);
        for (BlueprintPlacement primary : fixture.pairs()) {
            fixture.prepare(primary);
            BlockPos partner = BlueprintPlacementGroups.partnerPosition(primary);
            fixture.level.setBlock(partner, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
            ItemStack item = new ItemStack(primary.material());
            var result = BlueprintService.placeAt(fixture.level, fixture.data, fixture.settlement,
                    fixture.blueprint, primary.position(), item);
            helper.assertTrue(!result.success() && item.getCount() == 1
                            && fixture.level.getBlockState(primary.position()).isAir()
                            && fixture.level.getBlockState(partner).is(Blocks.DIAMOND_BLOCK),
                    "Blocked partner caused partial placement or overwrote player block");
            fixture.level.setBlock(partner, Blocks.AIR.defaultBlockState(), 18);
            fixture.level.setBlock(primary.position().below(), Blocks.AIR.defaultBlockState(), 18);
            result = BlueprintService.placeAt(fixture.level, fixture.data, fixture.settlement,
                    fixture.blueprint, primary.position(), item);
            helper.assertTrue(!result.success() && item.getCount() == 1, "Unsupported pair was placed");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "compound_placement")
    public static void legacyHalfAndPartnerClaimRecoverAsSinglePendingGroup(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 7, Direction.WEST);
        BlueprintPlacement primary = fixture.pairs().getFirst();
        fixture.prepare(primary);
        BlockPos partner = BlueprintPlacementGroups.partnerPosition(primary);
        fixture.level.setBlock(primary.position(), primary.state(), 18);
        BlueprintRecord reloaded = BlueprintRecord.load(fixture.blueprint.save());
        var pending = BlueprintService.pendingAt(fixture.level, reloaded, partner);
        helper.assertTrue(pending != null && pending.placement().position().equals(primary.position()),
                "Old half-placed structure did not resolve to canonical task after reload");
        helper.assertTrue(BlueprintService.pendingPlacements(fixture.level, reloaded, Set.of(partner)).stream()
                        .noneMatch(p -> p.placement().position().equals(primary.position())),
                "A second worker could claim the other half of an already-claimed group");
        ItemStack item = new ItemStack(primary.material());
        var result = BlueprintService.placeAt(fixture.level, fixture.data, fixture.settlement,
                reloaded, partner, item);
        helper.assertTrue(result.success() && item.isEmpty()
                        && BlueprintPlacementGroups.satisfied(fixture.level, fixture.template, primary),
                "Legacy orphan failed to recover with one complete item");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "compound_placement")
    public static void demolitionAndCancellationDropOneItemPerWholePair(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 8, Direction.SOUTH);
        var building = BuildingRecord.create(BuildingType.HOUSE, fixture.template.anchor(), fixture.template.facing(),
                fixture.template.minCorner(), fixture.template.maxCorner(), fixture.template.transitionMargin(),
                fixture.blueprint.templateVersion());
        building.queueDemolition();
        java.util.Set<net.minecraft.world.level.ChunkPos> chunks = fixture.pairs().stream()
                .map(p -> new net.minecraft.world.level.ChunkPos(p.position()))
                .collect(java.util.stream.Collectors.toSet());
        chunks.forEach(chunk -> fixture.level.setChunkForced(chunk.x, chunk.z, true));
        helper.runAtTickTime(99, () -> chunks.forEach(
                chunk -> fixture.level.setChunkForced(chunk.x, chunk.z, false)));
        helper.startSequence().thenWaitUntil(() -> {
            for (var chunk : chunks) {
                helper.assertTrue(fixture.level.areEntitiesLoaded(chunk.toLong())
                                && fixture.level.isPositionEntityTicking(chunk.getWorldPosition()),
                        "Removal fixture is waiting for entity-tracked chunks");
            }
        }).thenExecute(() -> {
        try {
        int index = 0;
        for (BlueprintPlacement primary : fixture.pairs()) {
            fixture.prepare(primary);
            var placed = BlueprintPlacementGroups.place(fixture.level, fixture.settlement, fixture.template,
                    primary, null, false);
            helper.assertTrue(placed.success(), "Could not prepare paired removal: " + placed.reason());
            BlockPos partner = BlueprintPlacementGroups.partnerPosition(primary);
            int before = fixture.drops(primary);
            boolean removed = index++ % 2 == 0
                    ? BuildingDemolitionService.removeAt(fixture.level, building, partner, null)
                    : BlueprintCancellationService.removePlacedBlock(fixture.level, fixture.blueprint, partner, null);
            helper.assertTrue(removed && fixture.drops(primary) == before + 1,
                    "Paired removal did not yield exactly one material item: removed=" + removed
                            + " before=" + before + " after=" + fixture.drops(primary)
                            + " state=" + primary.state() + " restoring=" + fixture.level.restoringBlockSnapshots);
            for (BlueprintPlacement part : BlueprintPlacementGroups.parts(fixture.template, primary)) {
                helper.assertTrue(fixture.level.getBlockState(part.position()).isAir(), "Paired removal left a half");
            }
        }
        helper.succeed();
        } finally {
            chunks.forEach(chunk -> fixture.level.setChunkForced(chunk.x, chunk.z, false));
        }
        });
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "compound_placement")
    public static void repairClearsBothDamageEntriesAndPreservesTemplateIdentity(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper, 9, Direction.NORTH);
        var building = BuildingRecord.create(BuildingType.HOUSE, fixture.template.anchor(), fixture.template.facing(),
                fixture.template.minCorner(), fixture.template.maxCorner(), fixture.template.transitionMargin(),
                fixture.blueprint.templateVersion());
        for (BlueprintPlacement primary : fixture.pairs()) {
            fixture.prepare(primary);
            BlockPos partner = BlueprintPlacementGroups.partnerPosition(primary);
            building.markDamaged(primary.position());
            building.markDamaged(partner);
            var loaded = BuildingRecord.load(building.save());
            var template = BlueprintTemplates.createForBuilding(loaded);
            var result = BlueprintPlacementGroups.place(fixture.level, fixture.settlement, template,
                    template.placementAt(partner), null, false);
            helper.assertTrue(result.success() && BuildingRepairService.satisfied(fixture.level, loaded, partner),
                    "Repair did not restore full pair from saved template");
            BuildingRepairService.clearRepair(fixture.settlement, loaded, partner);
            helper.assertTrue(!loaded.repairPositions().contains(primary.position())
                            && !loaded.repairPositions().contains(partner)
                            && loaded.templateVersion() == building.templateVersion(),
                    "Repair left duplicate task or changed saved template version");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "compound_placement")
    public static void everyRuntimeTemplateChargesWholeObjectsNotHalfCells(GameTestHelper helper) {
        for (BuildingType type : BuildingType.values()) {
            if (!BlueprintTemplates.supports(type) || type.isDynamicArea()) continue;
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                var template = BlueprintTemplates.create(type, BlockPos.ZERO, facing);
                for (BlueprintPlacement placement : template.placements()) {
                    if (!BlueprintPlacementGroups.isPaired(placement.state())) continue;
                    var group = BlueprintPlacementGroups.parts(template, placement);
                    helper.assertTrue(group.size() == 2, "Malformed pair in " + type + " " + facing
                            + " at=" + placement + " partner="
                            + template.placementAt(BlueprintPlacementGroups.partnerPosition(placement)));
                    helper.assertTrue(group.getFirst().material() != null && group.getLast().material() == null,
                            "Paired snapshot still charges two material items");
                }
                long chestCells = template.placements().stream().filter(p -> p.state().getBlock() instanceof ChestBlock).count();
                long chargedChests = template.placements().stream().filter(p -> p.state().getBlock() instanceof ChestBlock
                        && p.material() != null).count();
                helper.assertTrue(chestCells == chargedChests, "Double chests must still cost two chests");
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "compound_placement")
    public static void correctedDoorTemplatesDoNotSilentlyRewriteOlderPlansOrBuildings(GameTestHelper helper) {
        for (BuildingType type : List.of(BuildingType.HOUSE_3, BuildingType.ADVANCED_HOUSE_2)) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                var oldTemplate = BlueprintTemplates.create(type, BlockPos.ZERO, facing, 1);
                var current = BlueprintTemplates.create(type, BlockPos.ZERO, facing);
                var oldPlanTag = BlueprintRecord.create(type, BlockPos.ZERO, BlockPos.ZERO, facing,
                        UUID.randomUUID(), false).save();
                oldPlanTag.putInt("StructureVersion", 10);
                oldPlanTag.remove("TemplateVersion");
                var oldPlan = BlueprintRecord.load(oldPlanTag);
                var oldBuilding = BuildingRecord.create(type, BlockPos.ZERO, facing, oldTemplate.minCorner(),
                        oldTemplate.maxCorner(), oldTemplate.transitionMargin(), 1);
                var reloadedBuilding = BuildingRecord.load(oldBuilding.save());
                helper.assertTrue(oldPlan.templateVersion() == 1 && reloadedBuilding.templateVersion() == 1
                                && BlueprintTemplates.create(oldPlan).placementAt(BlockPos.ZERO.above()).state().is(Blocks.GLASS_PANE)
                                && BlueprintTemplates.createForBuilding(reloadedBuilding)
                                .placementAt(BlockPos.ZERO.above()).state().is(Blocks.GLASS_PANE),
                        "Old plan/building was silently changed to corrected geometry");
                helper.assertTrue(current.placementAt(BlockPos.ZERO.above()).state().getBlock() instanceof DoorBlock
                                && BlueprintPlacementGroups.parts(current, current.placementAt(BlockPos.ZERO)).size() == 2
                                && BlueprintRecord.load(BlueprintRecord.create(type, BlockPos.ZERO, BlockPos.ZERO,
                                facing, UUID.randomUUID(), false).save()).templateVersion() == BlueprintTemplates.currentTemplateVersion(type),
                        "New/reloaded plan did not retain its complete door");
            }
        }
        helper.succeed();
    }

    private static final class Fixture {
        final ServerLevel level;
        final SettlementSavedData data = new SettlementSavedData();
        final SettlementRecord settlement;
        final BlueprintRecord blueprint;
        final BuildingBlueprintTemplate template;

        Fixture(GameTestHelper helper, int index, Direction facing) {
            level = helper.getLevel();
            BlockPos anchor = helper.absolutePos(new BlockPos(8, 12, 8)).offset(4096 + index * 64, 0, 4096);
            UUID owner = UUID.randomUUID();
            settlement = new SettlementRecord(UUID.randomUUID(), "Compound placement", anchor, owner);
            blueprint = BlueprintRecord.create(BuildingType.HOUSE, anchor, anchor, facing, owner, false);
            template = BlueprintTemplates.create(blueprint);
        }

        List<BlueprintPlacement> pairs() {
            return template.placements().stream().filter(p -> BlueprintPlacementGroups.isPaired(p.state())
                    && BlueprintPlacementGroups.isPrimary(p.state())).toList();
        }

        void prepare(BlueprintPlacement primary) {
            for (BlueprintPlacement part : BlueprintPlacementGroups.parts(template, primary)) {
                level.getChunkAt(part.position());
                level.setBlock(part.position(), Blocks.AIR.defaultBlockState(), 18);
                level.setBlock(part.position().below(), Blocks.STONE.defaultBlockState(), 18);
            }
            // Preparing the upper door support must not fill the lower door cell.
            for (BlueprintPlacement part : BlueprintPlacementGroups.parts(template, primary)) {
                level.setBlock(part.position(), Blocks.AIR.defaultBlockState(), 18);
            }
        }

        int drops(BlueprintPlacement primary) {
            return level.getEntitiesOfClass(ItemEntity.class, new AABB(primary.position()).inflate(4),
                    entity -> entity.getItem().is(primary.material())).stream()
                    .mapToInt(entity -> entity.getItem().getCount()).sum();
        }
    }
}
