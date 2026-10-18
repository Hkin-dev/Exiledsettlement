package com.hkin.exiledsettlement.building;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.*;
import java.util.Locale;

/** Presentation only: never changes warehouse acceptance or saved reserve policies. */
public enum WarehouseDisplayCategory {
    ALL, FOOD, INGREDIENTS, SEEDS, WOOD, STONE, ORES, FUEL, TOOLS, EQUIPMENT, PLANTS, OTHER;

    public String id() { return name().toLowerCase(Locale.ROOT); }
    public String translationKey() { return "warehouse_display.exiledsettlement." + id(); }
    public String filter() { return this == ALL ? "" : "category:" + id(); }

    public static WarehouseDisplayCategory classify(ItemStack stack) {
        // Data packs can extend or replace these tags; explicit categories precede fallbacks.
        for (var category : values()) {
            if (category != ALL && category != OTHER && stack.is(TagKey.create(Registries.ITEM,
                    ExiledSettlementMod.id("warehouse_display/" + category.id())))) return category;
        }
        if (stack.has(DataComponents.FOOD)) return FOOD;
        if (stack.getItem() instanceof ArmorItem || stack.is(Items.ELYTRA) || stack.is(Items.SHIELD)
                || com.hkin.exiledsettlement.citizen.CitizenClothingService.isGarment(stack)) return EQUIPMENT;
        if (stack.has(DataComponents.TOOL) || stack.getItem() instanceof SwordItem
                || stack.getItem() instanceof ProjectileWeaponItem || stack.getItem() instanceof TridentItem
                || stack.getItem() instanceof FishingRodItem || stack.getItem() instanceof ShearsItem) return TOOLS;
        if (stack.getItem() instanceof BlockItem) return STONE;
        return OTHER;
    }

    public static boolean matches(ItemStack stack, String query) {
        return query.startsWith("category:") && (query.equals("category:all")
                || query.equals(classify(stack).filter()));
    }
}
