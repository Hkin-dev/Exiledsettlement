package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WarehouseChestGameTests {
    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void warehousePairsUseVanillaConnectionAndPreserveInventory(GameTestHelper helper) {
        int index = 0;
        var level = helper.getLevel();
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockPos anchor = helper.absolutePos(new BlockPos(4 + 32 * index++, 10, 4));
            var blueprint = BlueprintRecord.create(BuildingType.WAREHOUSE, anchor, anchor, facing, UUID.randomUUID(), false);
            var saved = blueprint.save();
            helper.assertTrue(BlueprintRecord.load(saved).templateVersion() == 7, "New warehouse lost v7 on reload");
            saved.putInt("StructureVersion", 13);
            saved.remove("TemplateVersion");
            var old = BlueprintTemplates.create(BlueprintRecord.load(saved));
            helper.assertTrue(BlueprintRecord.load(saved).templateVersion() == 6, "v6 warehouse silently migrated");
            saved.putInt("StructureVersion", 12);
            helper.assertTrue(BlueprintRecord.load(saved).templateVersion() == 5, "Old warehouse silently migrated");
            var template = BlueprintTemplates.create(blueprint);
            var chests = template.placements().stream().filter(p -> p.state().is(Blocks.CHEST)).toList();
            helper.assertTrue(chests.size() == 48, "Warehouse chest count changed");
            for (var p : chests) {
                Direction front = p.state().getValue(ChestBlock.FACING);
                double towardCenter = front.getStepX() * ((template.minCorner().getX() + template.maxCorner().getX()) / 2.0 - p.position().getX())
                        + front.getStepZ() * ((template.minCorner().getZ() + template.maxCorner().getZ()) / 2.0 - p.position().getZ());
                helper.assertTrue(towardCenter > 0, "Chest latch faces away from central aisle: " + facing);
                var aisle = template.placementAt(p.position().relative(front));
                helper.assertTrue(aisle == null || aisle.state().isAir(), "Chest front obstructed by template");
                helper.assertTrue(old.placementAt(p.position()).state().getValue(ChestBlock.FACING) == front.getOpposite(),
                        "Old v6 snapshot was modified");
                var partner = template.placementAt(p.position().relative(ChestBlock.getConnectedDirection(p.state())));
                helper.assertTrue(partner != null && partner.state().is(Blocks.CHEST)
                        && partner.state().getValue(ChestBlock.TYPE) == p.state().getValue(ChestBlock.TYPE).getOpposite()
                        && partner.state().getValue(ChestBlock.FACING) == p.state().getValue(ChestBlock.FACING),
                        "Template pair invalid for " + facing + " at " + p.position());
                level.setBlock(p.position().above(), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(p.position(), p.state().setValue(ChestBlock.TYPE, ChestType.SINGLE)
                        .setValue(ChestBlock.FACING, p.state().getValue(ChestBlock.FACING).getOpposite()), 2);
                ((Container)level.getBlockEntity(p.position())).setItem(0, new ItemStack(Items.DIAMOND, 3));
            }
            for (var p : chests) BlueprintBlockStateResolver.refreshExisting(level, template, p);
            for (var p : chests) {
                var actual = level.getBlockState(p.position());
                helper.assertTrue(actual.equals(p.state()), "Pair facing/state not repaired: " + facing);
                var inventory = ChestBlock.getContainer((ChestBlock)Blocks.CHEST, actual, level, p.position(), false);
                helper.assertTrue(inventory != null && inventory.getContainerSize() == 54,
                        "Vanilla cannot open a real double chest: " + facing);
                helper.assertTrue(((Container)level.getBlockEntity(p.position())).getItem(0).getCount() == 3,
                        "State repair changed inventory");
            }
        }
        helper.succeed();
    }
}
