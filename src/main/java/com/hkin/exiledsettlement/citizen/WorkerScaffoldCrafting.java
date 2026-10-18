package com.hkin.exiledsettlement.citizen;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Server-thread, demand-limited recipe. Never manufactures free inputs. */
public final class WorkerScaffoldCrafting {
    private WorkerScaffoldCrafting() { }

    public static int craft(CitizenRecord worker, int requested) {
        if (worker.profession() != CitizenProfession.WORKER || requested <= 0) return 0;
        int sticks = worker.inventory().stream().filter(s -> s.is(Items.STICK)).mapToInt(ItemStack::getCount).sum();
        int amount = Math.min(Math.min(requested, 256), Math.min(sticks / 6,
                worker.inventoryCapacityFor(new ItemStack(Items.SCAFFOLDING))));
        if (amount == 0) return 0;
        int needed = amount * 6;
        while (needed > 0) {
            ItemStack input = worker.extractInventoryItem(s -> s.is(Items.STICK), needed);
            // Total input was checked before entering this single-thread transaction.
            needed -= input.getCount();
        }
        int remaining = amount;
        while (remaining > 0) {
            int batch = Math.min(64, remaining);
            worker.insertIntoInventory(new ItemStack(Items.SCAFFOLDING, batch));
            remaining -= batch;
        }
        return amount;
    }
}
