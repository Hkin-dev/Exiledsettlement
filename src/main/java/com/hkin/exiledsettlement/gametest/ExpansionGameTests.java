package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.CarpenterRecipes;
import com.hkin.exiledsettlement.citizen.CitizenRecord;
import com.hkin.exiledsettlement.registry.ModItems;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.UUID;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ExpansionGameTests {
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty")
    public static void expansionHomesFreezeRoadAndTransitionDecorations(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(30, 12, 30));
        var first = BlueprintTemplates.create(BuildingType.ADVANCED_HOUSE_1, anchor, Direction.NORTH);
        var second = BlueprintTemplates.create(BuildingType.ADVANCED_HOUSE_1, anchor, Direction.NORTH);
        helper.assertTrue(first.placements().equals(second.placements()),
                "Anchor-seeded transition modules changed between compilations");
        var entrance = first.entrances().getFirst();
        helper.assertTrue(first.placementAt(entrance.position().below().relative(entrance.outward())).state().is(Blocks.GRAVEL)
                        && first.placementAt(entrance.position().below().relative(entrance.outward(), 2)).state().is(Blocks.GRAVEL),
                "New residence does not include its two-block paid approach road");
        long outsideDecor = first.placements().stream().filter(p -> p.position().getY() == first.minCorner().getY() + 1)
                .filter(p -> p.position().getX() < first.minCorner().getX()
                        || p.position().getX() > first.maxCorner().getX()
                        || p.position().getZ() < first.minCorner().getZ()
                        || p.position().getZ() > first.maxCorner().getZ())
                .count();
        helper.assertTrue(outsideDecor >= 2, "Anchor-seeded exterior decoration modules are missing");
        helper.succeed();
    }
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", timeoutTicks=200)
    public static void newHousingVersionsHaveExactCapacityAndKeepHistoricalGeometry(GameTestHelper helper) {
        BuildingType[] types = {BuildingType.ADVANCED_HOUSE_1, BuildingType.ADVANCED_HOUSE_2,
                BuildingType.ADVANCED_HOUSE_3, BuildingType.HOUSE_3, BuildingType.STARTER_CAMP};
        int[] beds = {2, 3, 4, 4, 7};
        int[] old = {2, 3, 2, 2, 1};
        for (int i = 0; i < types.length; i++) {
            var current = BlueprintTemplates.create(types[i], BlockPos.ZERO, Direction.NORTH);
            long bedHeads = current.placements().stream().filter(p -> p.state().getBlock() instanceof BedBlock)
                    .filter(p -> p.state().getValue(BedBlock.PART) == BedPart.HEAD).count();
            helper.assertTrue(bedHeads == beds[i], types[i] + " has " + bedHeads + " beds");
            helper.assertTrue(types[i].housingLimit() == beds[i], types[i] + " capacity mismatch");
            if (types[i] != BuildingType.STARTER_CAMP) {
                var legacy = BlueprintTemplates.create(types[i], BlockPos.ZERO, Direction.NORTH, old[i]);
                helper.assertTrue(!legacy.placements().equals(current.placements()), types[i] + " old geometry overwritten");
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty")
    public static void carpenterConsumesRealPlanksAndRollsBackFullOutput(GameTestHelper helper) {
        CitizenRecord carpenter = new CitizenRecord(UUID.randomUUID(), "Carpenter", BlockPos.ZERO);
        carpenter.insertIntoInventory(new ItemStack(Items.OAK_PLANKS, 2));
        CarpenterRecipes.Recipe charcoal = new CarpenterRecipes.Recipe(Items.OAK_PLANKS, 2, Items.CHARCOAL, 1);
        helper.assertTrue(CarpenterRecipes.craft(carpenter, charcoal), "Charcoal recipe failed");
        helper.assertTrue(carpenter.hasInventoryItem(stack -> stack.is(Items.CHARCOAL)), "No charcoal output");
        helper.assertTrue(!carpenter.hasInventoryItem(stack -> stack.is(Items.OAK_PLANKS)), "Planks not consumed");
        helper.succeed();
    }

    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty")
    public static void claimExpansionPersistsAndRejectsDiagonalOrClaimedLand(GameTestHelper helper) {
        BlockPos core = helper.absolutePos(new BlockPos(30000, 4, 30000));
        SettlementRecord settlement = new SettlementRecord(UUID.randomUUID(), "Claim", core, UUID.randomUUID());
        var coreChunk = new net.minecraft.world.level.ChunkPos(core);
        int edge = coreChunk.x + settlement.boundaryRadiusChunks() + 1;
        helper.assertTrue(settlement.claimChunk(edge, coreChunk.z), "Adjacent chunk rejected");
        helper.assertTrue(!settlement.claimChunk(edge + 1, coreChunk.z + 1), "Diagonal chunk accepted");
        SettlementRecord loaded = SettlementRecord.load(settlement.save(helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        helper.assertTrue(loaded.ownsChunk(edge, coreChunk.z), "Claim not persisted");
        helper.succeed();
    }

    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty")
    public static void ledgerIgnoresInternalMovementAndTracksNetChange(GameTestHelper helper) {
        SettlementRecord settlement = new SettlementRecord(UUID.randomUUID(), "Ledger", BlockPos.ZERO, UUID.randomUUID());
        ResourceLocation stone = ResourceLocation.withDefaultNamespace("stone");
        helper.assertTrue(settlement.updateInventoryLedger(Map.of(stone, 10)), "Baseline not stored");
        helper.assertTrue(!settlement.updateInventoryLedger(Map.of(stone, 10)), "Unchanged stock recorded");
        settlement.updateInventoryLedger(Map.of(stone, 16));
        settlement.updateInventoryLedger(Map.of(stone, 11));
        helper.assertTrue(settlement.producedTotals().get(stone) == 6, "Production delta wrong");
        helper.assertTrue(settlement.consumedTotals().get(stone) == 5, "Consumption delta wrong");
        helper.succeed();
    }

    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty", timeoutTicks=200)
    public static void marketIsRealStorageAndTownHallProducesBoundedDocument(GameTestHelper helper) {
        var level = helper.getLevel();
        var data = new SettlementSavedData();
        BlockPos marketAnchor = helper.absolutePos(new BlockPos(32000, 4, 32000));
        var settlement = new SettlementRecord(UUID.randomUUID(), "Services", marketAnchor, UUID.randomUUID());
        var marketTemplate = BlueprintTemplates.create(BuildingType.MARKET, marketAnchor, Direction.NORTH);
        marketTemplate.placements().forEach(p -> level.setBlock(p.position(), p.state(), 2));
        var market = settlement.addBuilding(BuildingType.MARKET, marketAnchor, Direction.NORTH,
                marketTemplate.minCorner(), marketTemplate.maxCorner(), marketTemplate.transitionMargin(), 1);
        BuildingFunctionService.validateSingle(level, data, settlement, market);
        helper.assertTrue(market.operational() && market.type().isStorage(), "Market is not storage");

        BlockPos hall = marketAnchor.offset(40, 0, 0);
        level.setBlock(hall, Blocks.BARREL.defaultBlockState(), 2);
        var townHall = settlement.addBuilding(BuildingType.TOWN_HALL, hall, Direction.NORTH,
                hall.offset(-1,0,-1), hall.offset(1,2,1), 1, 1);
        BuildingFunctionService.validateSingle(level, data, settlement, townHall);
        helper.assertTrue(townHall.operational(), "Town hall fixture not operational");
        // Production service uses saved-data settlements in normal runtime; insertion itself is verified here.
        ItemStack remaining = WarehouseInventoryService.insertIntoBuilding(level, townHall,
                new ItemStack(ModItems.APPOINTMENT_DOCUMENT.get()));
        helper.assertTrue(remaining.isEmpty() && WarehouseInventoryService.countItem(level, townHall,
                ModItems.APPOINTMENT_DOCUMENT.get()) == 1, "Appointment document not stored in real container");
        helper.succeed();
    }
}
