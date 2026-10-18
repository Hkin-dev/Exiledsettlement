package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.*;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PastureConstructionGameTests {
    private PastureConstructionGameTests() {}

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "pasture_fixture", timeoutTicks = 60)
    public static void finalFixtureIndexKeepsBothActionsAndMaterialCost(GameTestHelper h) {
        Fixture f = new Fixture(h, 0, 60);
        for (var composter : f.composters()) {
            h.assertTrue(f.template.placementAt(composter.position()).state().is(Blocks.COMPOSTER),
                    "Completed composter was indexed as preparatory air");
            h.assertTrue(f.template.placements().stream().anyMatch(p -> p.position().equals(composter.position())
                            && p.mode() == BlueprintPlacementMode.CLEARANCE), "Preparatory clearance action was lost");
        }
        h.assertTrue(f.template.requirements().get(Items.COMPOSTER) == 4, "Composter material cost changed");
        f.finish(); h.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "pasture_fixture", timeoutTicks = 60)
    public static void finishedPastureDoesNotRequeueComposterOrHayClearance(GameTestHelper h) {
        Fixture f = new Fixture(h, 1, 60);
        f.installExcept(false);
        h.assertTrue(BlueprintService.constructionComplete(f.level, f.site), "Final fixtures made pasture incomplete");
        h.assertTrue(BlueprintService.nextPending(f.level, f.data, f.site) == null
                        && BlueprintService.pendingPlacements(f.level, f.site, Set.of()).isEmpty(),
                "Completed fixtures were scheduled for clearing again");
        h.assertTrue(BlueprintService.completedPlacementCount(f.level, f.site) == f.template.placements().size(),
                "Completed clearance was missing from progress");
        f.finish(); h.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "pasture_fixture", timeoutTicks = 60)
    public static void composterFillLevelsPreserveStructureAndExistingClearanceRecovery(GameTestHelper h) {
        Fixture f = new Fixture(h, 2, 60);
        f.installExcept(false);
        BlockPos p = f.composters().getFirst().position();
        for (int fill = 0; fill <= 8; fill++) {
            var state = Blocks.COMPOSTER.defaultBlockState().setValue(ComposterBlock.LEVEL, fill);
            f.level.setBlock(p, state, Block.UPDATE_CLIENTS);
            h.assertTrue(BlueprintService.matchesPlacedState(state, Blocks.COMPOSTER.defaultBlockState())
                            && BlueprintService.pendingAt(f.level, f.site, p) == null,
                    "Compost fill " + fill + " was treated as structural damage");
        }
        var recovered = BlueprintRecord.createArea(BuildingType.PASTURE_AREA, f.site.anchor(), f.site.markerPosition(),
                Direction.NORTH, f.settlement.ownerId(), false, f.site.areaMin(), f.site.areaMax(),
                3, Map.of(p, Blocks.DIRT.defaultBlockState()));
        h.assertTrue(BlueprintService.nextPending(f.level, f.data, recovered) == null
                        && recovered.clearanceTargets().isEmpty(), "Stale clearance did not recognize the final fixture");
        h.assertTrue(f.level.getBlockState(p).getValue(ComposterBlock.LEVEL) == 8,
                "Clearance recovery reset or destroyed finished compost");
        f.finish(); h.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "pasture_fixture", timeoutTicks = 180)
    public static void groundWorkerConsumesFourCompostersAndCompletesPasture(GameTestHelper h) {
        Fixture f = new Fixture(h, 3, 180);
        f.installExcept(true);
        h.assertTrue(f.citizen.insertIntoInventory(new ItemStack(Items.COMPOSTER, 4)).isEmpty(), "Cannot stock four composters");
        f.citizen.startTask(CitizenWorkState.TO_BLUEPRINT, f.site.anchor(), null, f.site.id());
        f.level.setDayTime(6000);
        h.onEachTick(() -> {
            if (!f.finished) CitizenWorkService.advanceSiteConstruction(f.level, f.data, f.settlement, f.citizen, f.entity);
        });
        h.startSequence().thenWaitUntil(() -> h.assertTrue(f.settlement.blueprint(f.site.id()) == null,
                "Ground worker did not finish composter stage: " + f.citizen.workFailure()))
                .thenExecute(() -> {
                    h.assertTrue(f.composters().stream().allMatch(p -> f.level.getBlockState(p.position()).is(Blocks.COMPOSTER)),
                            "Worker failed to install all four real composters");
                    h.assertTrue(f.citizen.findInventorySlot(s -> s.is(Items.COMPOSTER)) < 0,
                            "Completed pasture did not consume exactly four supplied composters");
                    h.assertTrue(f.settlement.buildingAt(f.template.registrationPosition()) != null,
                            "Pasture was not registered at its template entrance after completion");
                    f.finish();
                }).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "pasture_fixture", timeoutTicks = 60)
    public static void missingComposterAndUnrelatedReplacementStillBlockConstruction(GameTestHelper h) {
        Fixture f = new Fixture(h, 4, 60);
        f.installExcept(true);
        BlockPos p = f.composters().getFirst().position();
        var missing = BlueprintService.placeAt(f.level, f.data, f.settlement, f.site, p, ItemStack.EMPTY, f.entity);
        h.assertTrue(!missing.success() && missing.reason().equals("MATERIALS_MISSING")
                        && f.level.getBlockState(p).isAir(), "Missing stock produced a free composter");
        f.level.setBlock(p, Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
        h.assertTrue(!BlueprintService.constructionComplete(f.level, f.site), "Unrelated replacement counted as finished structure");
        ItemStack supplied = new ItemStack(Items.COMPOSTER);
        var blocked = BlueprintService.placeAt(f.level, f.data, f.settlement, f.site, p, supplied, f.entity);
        h.assertTrue(!blocked.success() && f.level.getBlockState(p).is(Blocks.GOLD_BLOCK) && supplied.getCount() == 1,
                "Construction replaced unrelated player content or consumed stock on failure");
        f.finish(); h.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "pasture_fixture", timeoutTicks = 60)
    public static void protectedComposterPlacementRollsBackWithoutChargingMaterial(GameTestHelper h) {
        Fixture f = new Fixture(h, 5, 60);
        f.installExcept(true);
        BlockPos p = f.composters().getFirst().position();
        Consumer<BlockEvent.EntityPlaceEvent> listener = event -> {
            if (event.getLevel() == f.level && event.getPos().equals(p)) event.setCanceled(true);
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            ItemStack supplied = new ItemStack(Items.COMPOSTER);
            var result = BlueprintService.placeAt(f.level, f.data, f.settlement, f.site, p, supplied, f.entity);
            h.assertTrue(!result.success() && result.reason().equals("PROTECTED_AREA")
                            && supplied.getCount() == 1 && f.level.getBlockState(p).isAir(),
                    "Protected placement lost material or left a composter behind");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
            f.finish();
        }
        h.succeed();
    }

    private static final class Fixture {
        private final ServerLevel level;
        private final SettlementSavedData data = new SettlementSavedData();
        private final SettlementRecord settlement;
        private final BlueprintRecord site;
        private final BuildingBlueprintTemplate template;
        private final CitizenRecord citizen;
        private final SettlementCitizenEntity entity;
        private final ChunkPos firstChunk;
        private final ChunkPos lastChunk;
        private boolean finished;

        private Fixture(GameTestHelper h, int scenario, int timeout) {
            level = h.getLevel();
            BlockPos origin = h.absolutePos(new BlockPos(4, 10, 4)).offset(36864 + scenario * 128, 0, 0);
            firstChunk = new ChunkPos(origin.offset(-5, 0, -5));
            lastChunk = new ChunkPos(origin.offset(22, 0, 22));
            for (int x = firstChunk.x; x <= lastChunk.x; x++)
                for (int z = firstChunk.z; z <= lastChunk.z; z++) level.setChunkForced(x, z, true);
            for (BlockPos p : BlockPos.betweenClosed(origin.offset(-5, -2, -5), origin.offset(22, 4, 22)))
                level.setBlock(p, p.getY() < origin.getY() ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
            settlement = data.createOrRecover(origin, UUID.randomUUID(), "Pasture fixtures").value();
            h.assertTrue(settlement != null, "Cannot create pasture test settlement");
            site = settlement.addAreaBlueprint(BuildingType.PASTURE_AREA, origin, origin, Direction.NORTH,
                    settlement.ownerId(), false, origin, origin.offset(16, 0, 16), 3, Map.of());
            template = BlueprintTemplates.create(site);
            citizen = settlement.citizens().iterator().next();
            citizen.assignProfession(CitizenProfession.WORKER, null);
            entity = ModEntities.SETTLEMENT_CITIZEN.get().create(level);
            h.assertTrue(entity != null, "Cannot create pasture builder");
            BlockPos station = origin.offset(-2, 0, 2);
            entity.moveTo(station.getX() + 0.5D, station.getY(), station.getZ() + 0.5D, 0, 0);
            entity.setOnGround(true);
            entity.setNoAi(true);
            h.assertTrue(level.addFreshEntity(entity), "Cannot spawn pasture builder");
            h.runAtTickTime(timeout - 1L, this::finish);
        }

        private List<BlueprintPlacement> composters() {
            return template.placements().stream().filter(p -> p.state().is(Blocks.COMPOSTER)).toList();
        }

        private void installExcept(boolean omitComposters) {
            for (BlueprintPlacement p : template.placements()) {
                if (!p.mode().isFinalStructure() || omitComposters && p.state().is(Blocks.COMPOSTER)) continue;
                level.setBlock(p.position(), p.state(), Block.UPDATE_ALL);
            }
        }

        private void finish() {
            if (finished) return;
            finished = true;
            entity.discard();
            for (int x = firstChunk.x; x <= lastChunk.x; x++)
                for (int z = firstChunk.z; z <= lastChunk.z; z++) level.setChunkForced(x, z, false);
        }
    }
}
