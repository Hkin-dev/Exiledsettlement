package com.hkin.exiledsettlement.citizen;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** Transfer only: callers must establish physical source access before invoking. */
public final class ConstructionMaterialBatch {
    private ConstructionMaterialBatch() { }
    public static int load(CitizenRecord citizen, Item material, int requested,
            IntFunction<ItemStack> extract, Consumer<ItemStack> returnExcess) {
        int carried=0;
        for(int slot=0;slot<CitizenRecord.INVENTORY_SIZE;slot++) {
            var stack=citizen.inventoryItem(slot);
            if(stack.is(material) && !CitizenEquipmentService.isReservedEquipment(citizen,slot))carried+=stack.getCount();
        }
        var prototype=new ItemStack(material);
        int remaining=Math.min(Math.max(0,requested-carried),citizen.inventoryCapacityFor(prototype));
        int insertedTotal=0;
        while(remaining>0) {
            var extracted=extract.apply(Math.min(prototype.getMaxStackSize(),remaining));
            if(extracted.isEmpty())break;
            int offered=extracted.getCount();
            var leftover=citizen.insertIntoInventory(extracted);
            int inserted=offered-leftover.getCount();
            insertedTotal+=inserted;remaining-=inserted;
            if(!leftover.isEmpty())returnExcess.accept(leftover);
            // A short source stack is not the end of the batch.
            if(inserted<=0)break;
        }
        return insertedTotal;
    }
}
