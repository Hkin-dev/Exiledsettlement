package com.hkin.exiledsettlement.building;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Items;

import java.util.Locale;

public enum WarehouseCategory {
    FOOD,
    TOOLS,
    EQUIPMENT,
    MATERIALS;

    public int bit() {
        return 1 << ordinal();
    }

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String translationKey() {
        return "warehouse_category.exiledsettlement." + serializedName();
    }

    public static int allMask() {
        return (1 << values().length) - 1;
    }

    public static WarehouseCategory classify(ItemStack stack) {
        if (stack.has(DataComponents.FOOD)) return FOOD;
        if (stack.has(DataComponents.TOOL)) return TOOLS;
        if (stack.getItem() instanceof ArmorItem || stack.is(Items.ELYTRA) || stack.is(Items.SHIELD)
                || com.hkin.exiledsettlement.citizen.CitizenClothingService.isGarment(stack)) return EQUIPMENT;
        return MATERIALS;
    }
}
