package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BlueprintClearanceGameTests {
    private BlueprintClearanceGameTests() {}

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void snowStateChangeDoesNotStopAuthorizedClearance(GameTestHelper h) {
        Fixture f = new Fixture(h, 0);
        f.site.addClearanceTarget(f.target, Blocks.GRASS_BLOCK.defaultBlockState()
                .setValue(BlockStateProperties.SNOWY, true));
        f.set(Blocks.GRASS_BLOCK.defaultBlockState().setValue(BlockStateProperties.SNOWY, false));
        var result = f.clear(ItemStack.EMPTY);
        h.assertTrue(result.success() && f.level.getBlockState(f.target).isAir()
                        && !f.site.hasClearanceTarget(f.target) && count(result.value(), Items.DIRT) == 1,
                "Snow state changes still freeze the authorized blueprint clearance");
        h.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void replacedPlayerBarrelKeepsAllInventoryAndBlockLoot(GameTestHelper h) {
        Fixture f = new Fixture(h, 1);
        ChunkPos chunk = new ChunkPos(f.target);
        f.level.setChunkForced(chunk.x, chunk.z, true);
        h.runAtTickTime(90, () -> f.level.setChunkForced(chunk.x, chunk.z, false));
        // Synchronous block loading does not finish entity-section loading.
        // Wait before destruction so the real inventory drops can be queried.
        h.startSequence().thenWaitUntil(() -> h.assertTrue(f.level.areEntitiesLoaded(chunk.toLong())
                        && f.level.isPositionEntityTicking(f.target), "Waiting for barrel entity section"))
                .thenExecute(() -> {
                    f.site.addClearanceTarget(f.target, Blocks.GRASS_BLOCK.defaultBlockState());
                    f.set(Blocks.BARREL.defaultBlockState());
                    Container barrel = (Container) f.level.getBlockEntity(f.target);
                    h.assertTrue(barrel != null, "Missing real barrel inventory");
                    barrel.setItem(0, new ItemStack(Items.DIAMOND, 17));
                    var result = f.clear(new ItemStack(Items.IRON_AXE));
                    h.assertTrue(result.success() && f.level.getBlockState(f.target).isAir()
                                    && count(result.value(), Items.BARREL) == 1
                                    && f.looseCount(Items.DIAMOND) == 17
                                    && count(result.value(), Items.DIAMOND) == 0,
                            "Barrel inventory transfer failed: reason=" + result.reason()
                                    + ", looseDiamonds=" + f.looseCount(Items.DIAMOND)
                                    + ", returned=" + result.value());
                    f.level.setChunkForced(chunk.x, chunk.z, false);
                }).thenSucceed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void playerShulkerContentsRemainPackedExactlyOnce(GameTestHelper h) {
        Fixture f = new Fixture(h, 2);
        f.site.addClearanceTarget(f.target, Blocks.DIRT.defaultBlockState());
        f.set(Blocks.SHULKER_BOX.defaultBlockState());
        Container box = (Container) f.level.getBlockEntity(f.target);
        h.assertTrue(box != null, "Missing real shulker inventory");
        box.setItem(3, new ItemStack(Items.EMERALD, 23));
        var result = f.clear(new ItemStack(Items.IRON_PICKAXE));
        h.assertTrue(result.success() && count(result.value(), Items.SHULKER_BOX) == 1,
                "Shulker clearance failed or returned the wrong block loot");
        int packed = result.value().stream().flatMap(s -> s.getOrDefault(
                DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream())
                .mapToInt(s -> s.is(Items.EMERALD) ? s.getCount() : 0).sum();
        h.assertTrue(packed == 23 && f.looseCount(Items.EMERALD) == 0,
                "Shulker inventory was lost or duplicated instead of staying packed");
        h.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void latePlayerObstructionRequeuesAndSurvivesSaveLoad(GameTestHelper h) {
        Fixture f = new Fixture(h, 3);
        f.set(Blocks.BRICKS.defaultBlockState());
        var pending = BlueprintService.pendingAt(f.level, f.site, f.target);
        h.assertTrue(pending != null && pending.placement().position().equals(f.target)
                        && f.site.hasClearanceTarget(f.target),
                "An obstacle placed after blueprint creation did not re-enter clearance");
        BlueprintRecord loaded = BlueprintRecord.load(f.site.save());
        h.assertTrue(loaded.hasClearanceTarget(f.target), "Late obstacle was lost on reload");
        var result = BlueprintService.clearAt(f.level, f.data, f.settlement, loaded,
                f.target, f.worker, new ItemStack(Items.IRON_PICKAXE));
        h.assertTrue(result.success() && count(result.value(), Items.BRICKS) == 1
                        && f.level.getBlockState(f.target).isAir() && !loaded.hasClearanceTarget(f.target),
                "Reloaded clearance did not remove the real player block with its loot");
        h.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void obstacleInSecondaryDoorCellIsClearedBeforePairPlacement(GameTestHelper h) {
        Fixture f = new Fixture(h, 4);
        BlueprintPlacement upper = f.template.placements().stream()
                .filter(p -> BlueprintPlacementGroups.isPaired(p.state())
                        && !BlueprintPlacementGroups.isPrimary(p.state())).findFirst().orElseThrow();
        f.level.getChunkAt(upper.position());
        f.level.setBlock(upper.position(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        var pending = BlueprintService.pendingAt(f.level, f.site, upper.position());
        h.assertTrue(pending != null && pending.placement().position().equals(upper.position())
                        && f.site.hasClearanceTarget(upper.position()),
                "Secondary obstruction was incorrectly normalized to the empty primary cell");
        var result = BlueprintService.clearAt(f.level, f.data, f.settlement, f.site,
                upper.position(), f.worker, new ItemStack(Items.IRON_PICKAXE));
        h.assertTrue(result.success() && count(result.value(), Items.COBBLESTONE) == 1
                        && f.level.getBlockState(upper.position()).isAir(),
                "A secondary paired construction cell could not be cleared");
        h.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void cancelledServerProtectionEventPreservesContainerAndTask(GameTestHelper h) {
        Fixture f = new Fixture(h, 5);
        f.site.addClearanceTarget(f.target, Blocks.DIRT.defaultBlockState());
        f.set(Blocks.BARREL.defaultBlockState());
        Container barrel = (Container) f.level.getBlockEntity(f.target);
        barrel.setItem(0, new ItemStack(Items.DIAMOND, 11));
        Consumer<LivingDestroyBlockEvent> listener = event -> {
            if (event.getEntity() == f.worker && event.getPos().equals(f.target)) event.setCanceled(true);
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            var result = f.clear(new ItemStack(Items.IRON_AXE));
            h.assertTrue(!result.success() && result.reason().equals("PROTECTED_AREA")
                            && f.level.getBlockState(f.target).is(Blocks.BARREL)
                            && barrel.getItem(0).getCount() == 11 && f.looseCount(Items.DIAMOND) == 0
                            && f.site.hasClearanceTarget(f.target),
                    "Server event cancellation consumed contents or removed the queued clearance");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        h.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void impossibleBlocksAndOffTemplateTargetsRemainUntouched(GameTestHelper h) {
        Fixture f = new Fixture(h, 6);
        f.site.addClearanceTarget(f.target, Blocks.DIRT.defaultBlockState());
        for (BlockState state : new BlockState[]{Blocks.BEDROCK.defaultBlockState(), Blocks.WATER.defaultBlockState()}) {
            f.set(state);
            h.assertTrue(!f.clear(ItemStack.EMPTY).success() && f.level.getBlockState(f.target).equals(state)
                            && f.site.hasClearanceTarget(f.target),
                    "An intrinsically impossible clearance block was removed");
        }
        BlockPos outside = f.site.anchor().offset(48, 0, 0);
        f.level.getChunkAt(outside);
        f.level.setBlock(outside, Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
        f.site.addClearanceTarget(outside, Blocks.GOLD_BLOCK.defaultBlockState());
        var result = BlueprintService.clearAt(f.level, f.data, f.settlement, f.site,
                outside, f.worker, new ItemStack(Items.IRON_PICKAXE));
        h.assertTrue(!result.success() && f.level.getBlockState(outside).is(Blocks.GOLD_BLOCK),
                "Malformed save authorized destruction outside declared blueprint cells");
        h.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void initialPlayerContainerBecomesConfirmedBlueprintClearance(GameTestHelper h) {
        Fixture f = new Fixture(h, 7);
        var player = h.makeMockServerPlayerInLevel();
        var created = SettlementSavedData.get(f.level).createOrRecover(f.site.anchor(), player.getUUID(), "Container site");
        h.assertTrue(created.success(), "Could not create authorized settlement");
        for (BlueprintPlacement p : f.template.placements()) {
            f.level.getChunkAt(p.position());
            f.level.setBlock(p.position(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        f.set(Blocks.BARREL.defaultBlockState());
        var result = BlueprintService.placeBlueprint(f.level, player.getUUID(), BuildingType.HOUSE_2,
                f.site.anchor(), Direction.NORTH, false, null);
        h.assertTrue(result.success() && result.value().hasClearanceTarget(f.target)
                        && f.level.getBlockState(f.target).is(Blocks.BARREL),
                "Player container still prevented blueprint confirmation or was destroyed remotely");
        h.succeed();
    }

    private static int count(java.util.List<ItemStack> stacks, net.minecraft.world.item.Item item) {
        return stacks.stream().mapToInt(s -> s.is(item) ? s.getCount() : 0).sum();
    }

    private static final class Fixture {
        final ServerLevel level;
        final SettlementSavedData data = new SettlementSavedData();
        final SettlementRecord settlement;
        final BlueprintRecord site;
        final BuildingBlueprintTemplate template;
        final BlockPos target;
        final SettlementCitizenEntity worker;

        Fixture(GameTestHelper h, int scenario) {
            level = h.getLevel();
            BlockPos anchor = h.absolutePos(new BlockPos(4, 10, 4)).offset(49152 + scenario * 256, 0, 0);
            settlement = new SettlementRecord(UUID.randomUUID(), "Current clearance", anchor, UUID.randomUUID());
            site = settlement.addBlueprint(BuildingType.HOUSE_2, anchor, anchor, Direction.NORTH,
                    settlement.ownerId(), false, Map.of());
            template = BlueprintTemplates.create(site);
            target = template.placements().stream().filter(p -> p.mode() == BlueprintPlacementMode.CLEARANCE)
                    .map(BlueprintPlacement::position).findFirst().orElseThrow();
            level.getChunkAt(target);
            worker = ModEntities.SETTLEMENT_CITIZEN.get().create(level);
            h.assertTrue(worker != null, "Cannot create clearance resident");
        }

        void set(BlockState state) { level.setBlock(target, state, Block.UPDATE_CLIENTS); }

        com.hkin.exiledsettlement.settlement.OperationResult<java.util.List<ItemStack>> clear(ItemStack tool) {
            return BlueprintService.clearAt(level, data, settlement, site, target, worker, tool);
        }

        int looseCount(net.minecraft.world.item.Item item) {
            return level.getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(2))
                    .stream().mapToInt(e -> e.getItem().is(item) ? e.getItem().getCount() : 0).sum();
        }
    }
}
