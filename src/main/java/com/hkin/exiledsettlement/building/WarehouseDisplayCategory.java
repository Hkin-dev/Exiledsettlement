package com.hkin.exiledsettlement.building;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.*;
import java.util.Locale;
import java.util.List;

/** Presentation only: never changes warehouse acceptance or saved reserve policies. */
public enum WarehouseDisplayCategory {
    ALL, FOOD, INGREDIENTS, SEEDS, WOOD, STONE, ORES, FUEL, TOOLS, EQUIPMENT, PLANTS, OTHER;

    public String id() { return name().toLowerCase(Locale.ROOT); }
    public String translationKey() { return "warehouse_display.exiledsettlement." + id(); }
    public String filter() { return this == ALL ? "" : "category:" + id(); }

    // Keep legacy enum identities for old category queries and integrations.
    public static List<WarehouseDisplayCategory> columns() {
        return List.of(FOOD, WOOD, STONE, ORES, EQUIPMENT, FUEL, PLANTS, OTHER);
    }
    public static WarehouseDisplayCategory[] visibleCategories() {
        return new WarehouseDisplayCategory[]{ALL, FOOD, WOOD, STONE, ORES, EQUIPMENT, FUEL, PLANTS, OTHER};
    }
    public int columnIndex() { return columns().indexOf(this); }
    private static boolean tagged(ItemStack stack, WarehouseDisplayCategory category) {
        return stack.is(TagKey.create(Registries.ITEM, ExiledSettlementMod.id("warehouse_display/" + category.id())));
    }

    public static WarehouseDisplayCategory classify(ItemStack stack) {
        // Agricultural ingredients and seeds share the food column. Material-specific
        // tools precede the general equipment column; every item has exactly one home.
        if (stack.has(DataComponents.FOOD) || tagged(stack, FOOD) || tagged(stack, INGREDIENTS) || tagged(stack, SEEDS)) return FOOD;
        if (tagged(stack, WOOD) || stack.getItem() instanceof TieredItem tool && tool.getTier() == Tiers.WOOD) return WOOD;
        if (tagged(stack, STONE) || stack.getItem() instanceof TieredItem tool && tool.getTier() == Tiers.STONE) return STONE;
        if (tagged(stack, FUEL)) return FUEL;
        if (tagged(stack, ORES)) return ORES;
        if (tagged(stack, TOOLS) || tagged(stack, EQUIPMENT)) return EQUIPMENT;
        if (stack.getItem() instanceof ArmorItem || stack.is(Items.ELYTRA) || stack.is(Items.SHIELD)
                || com.hkin.exiledsettlement.citizen.CitizenClothingService.isGarment(stack)) return EQUIPMENT;
        if (stack.has(DataComponents.TOOL) || stack.getItem() instanceof SwordItem
                || stack.getItem() instanceof ProjectileWeaponItem || stack.getItem() instanceof TridentItem
                || stack.getItem() instanceof FishingRodItem || stack.getItem() instanceof ShearsItem) return EQUIPMENT;
        if (tagged(stack, PLANTS)) return PLANTS;
        if (stack.getItem() instanceof BlockItem block) {
            var sound = block.getBlock().defaultBlockState().getSoundType();
            if (sound == net.minecraft.world.level.block.SoundType.WOOD || sound == net.minecraft.world.level.block.SoundType.BAMBOO_WOOD
                    || sound == net.minecraft.world.level.block.SoundType.CHERRY_WOOD) return WOOD;
            if (sound == net.minecraft.world.level.block.SoundType.STONE || sound == net.minecraft.world.level.block.SoundType.DEEPSLATE
                    || sound == net.minecraft.world.level.block.SoundType.DEEPSLATE_BRICKS || sound == net.minecraft.world.level.block.SoundType.DEEPSLATE_TILES
                    || sound == net.minecraft.world.level.block.SoundType.TUFF || sound == net.minecraft.world.level.block.SoundType.TUFF_BRICKS
                    || sound == net.minecraft.world.level.block.SoundType.POLISHED_TUFF || sound == net.minecraft.world.level.block.SoundType.CALCITE
                    || sound == net.minecraft.world.level.block.SoundType.NETHERRACK || sound == net.minecraft.world.level.block.SoundType.NETHER_BRICKS
                    || sound == net.minecraft.world.level.block.SoundType.BASALT) return STONE;
        }
        return OTHER;
    }

    public static boolean matches(ItemStack stack, String query) {
        if (query.equals("category:ingredients")) return tagged(stack, INGREDIENTS);
        if (query.equals("category:seeds")) return tagged(stack, SEEDS);
        if (query.equals("category:tools")) return classify(stack) == EQUIPMENT;
        return query.startsWith("category:") && (query.equals("category:all") || query.equals(classify(stack).filter()));
    }
}
