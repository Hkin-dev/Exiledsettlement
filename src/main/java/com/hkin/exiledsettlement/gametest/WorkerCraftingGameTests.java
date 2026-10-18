package com.hkin.exiledsettlement.gametest;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.citizen.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(ExiledSettlementMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorkerCraftingGameTests {
    @GameTest(templateNamespace="minecraft", template="bastion/mobs/empty")
    public static void sixSticksPerScaffoldAcrossStacksAndDemandLimit(GameTestHelper helper) {
        var worker = new CitizenRecord(UUID.randomUUID(), "Craft fixture", BlockPos.ZERO);
        worker.assignProfession(CitizenProfession.WORKER, null);
        worker.insertIntoInventory(new ItemStack(Items.STICK, 64));
        worker.insertIntoInventory(new ItemStack(Items.STICK, 8));
        helper.assertTrue(WorkerScaffoldCrafting.craft(worker, 11) == 11, "Split inputs not crafted");
        helper.assertTrue(count(worker, Items.STICK) == 6 && count(worker, Items.SCAFFOLDING) == 11, "Wrong ratio or exceeded demand");
        helper.assertTrue(WorkerScaffoldCrafting.craft(worker, 10) == 1 && count(worker, Items.STICK) == 0, "Insufficient input overproduced");
        helper.assertTrue(WorkerScaffoldCrafting.craft(worker, 1) == 0, "Free scaffold");
        var full = new CitizenRecord(UUID.randomUUID(), "Full fixture", BlockPos.ZERO);
        full.assignProfession(CitizenProfession.WORKER, null);
        full.insertIntoInventory(new ItemStack(Items.STICK, 12));
        for (int i=0; i<35; i++) full.insertIntoInventory(new ItemStack(Items.COBBLESTONE, 64));
        helper.assertTrue(WorkerScaffoldCrafting.craft(full, 1) == 0 && count(full, Items.STICK) == 12, "Full bag lost materials");
        helper.succeed();
    }

    private static int count(CitizenRecord citizen, net.minecraft.world.item.Item item) {
        return citizen.inventory().stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum();
    }
}
