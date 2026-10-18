package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.building.SettlementInventoryService;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.*;

/** Same-species wooden goods plus the requested two-plank charcoal conversion. */
public final class CarpenterRecipes {
    public record Recipe(Item input, int inputCount, Item output, int outputCount) { }
    private static final Map<Item, List<Recipe>> RECIPES = new LinkedHashMap<>();
    static {
        add(Items.OAK_PLANKS, Items.OAK_SLAB, Items.OAK_STAIRS, Items.OAK_DOOR, Items.OAK_TRAPDOOR,
                Items.OAK_PRESSURE_PLATE, Items.OAK_BUTTON, Items.OAK_BOAT);
        add(Items.SPRUCE_PLANKS, Items.SPRUCE_SLAB, Items.SPRUCE_STAIRS, Items.SPRUCE_DOOR, Items.SPRUCE_TRAPDOOR,
                Items.SPRUCE_PRESSURE_PLATE, Items.SPRUCE_BUTTON, Items.SPRUCE_BOAT);
        add(Items.BIRCH_PLANKS, Items.BIRCH_SLAB, Items.BIRCH_STAIRS, Items.BIRCH_DOOR, Items.BIRCH_TRAPDOOR,
                Items.BIRCH_PRESSURE_PLATE, Items.BIRCH_BUTTON, Items.BIRCH_BOAT);
        add(Items.JUNGLE_PLANKS, Items.JUNGLE_SLAB, Items.JUNGLE_STAIRS, Items.JUNGLE_DOOR, Items.JUNGLE_TRAPDOOR,
                Items.JUNGLE_PRESSURE_PLATE, Items.JUNGLE_BUTTON, Items.JUNGLE_BOAT);
        add(Items.ACACIA_PLANKS, Items.ACACIA_SLAB, Items.ACACIA_STAIRS, Items.ACACIA_DOOR, Items.ACACIA_TRAPDOOR,
                Items.ACACIA_PRESSURE_PLATE, Items.ACACIA_BUTTON, Items.ACACIA_BOAT);
        add(Items.DARK_OAK_PLANKS, Items.DARK_OAK_SLAB, Items.DARK_OAK_STAIRS, Items.DARK_OAK_DOOR, Items.DARK_OAK_TRAPDOOR,
                Items.DARK_OAK_PRESSURE_PLATE, Items.DARK_OAK_BUTTON, Items.DARK_OAK_BOAT);
        add(Items.MANGROVE_PLANKS, Items.MANGROVE_SLAB, Items.MANGROVE_STAIRS, Items.MANGROVE_DOOR, Items.MANGROVE_TRAPDOOR,
                Items.MANGROVE_PRESSURE_PLATE, Items.MANGROVE_BUTTON, Items.MANGROVE_BOAT);
        add(Items.CHERRY_PLANKS, Items.CHERRY_SLAB, Items.CHERRY_STAIRS, Items.CHERRY_DOOR, Items.CHERRY_TRAPDOOR,
                Items.CHERRY_PRESSURE_PLATE, Items.CHERRY_BUTTON, Items.CHERRY_BOAT);
        add(Items.CRIMSON_PLANKS, Items.CRIMSON_SLAB, Items.CRIMSON_STAIRS, Items.CRIMSON_DOOR,
                Items.CRIMSON_TRAPDOOR, Items.CRIMSON_PRESSURE_PLATE, Items.CRIMSON_BUTTON, null);
        add(Items.WARPED_PLANKS, Items.WARPED_SLAB, Items.WARPED_STAIRS, Items.WARPED_DOOR,
                Items.WARPED_TRAPDOOR, Items.WARPED_PRESSURE_PLATE, Items.WARPED_BUTTON, null);
    }

    private CarpenterRecipes() { }

    private static void add(Item plank, Item slab, Item stair, Item door, Item trapdoor,
            Item plate, Item button, Item boat) {
        List<Recipe> recipes = new ArrayList<>(List.of(
                new Recipe(plank, 2, Items.CHARCOAL, 1),
                new Recipe(plank, 3, slab, 6), new Recipe(plank, 6, stair, 4),
                new Recipe(plank, 6, door, 3), new Recipe(plank, 6, trapdoor, 2),
                new Recipe(plank, 2, plate, 1), new Recipe(plank, 1, button, 1),
                new Recipe(plank, 8, Items.CHEST, 1)));
        if (boat != null) recipes.add(new Recipe(plank, 5, boat, 1));
        RECIPES.put(plank, List.copyOf(recipes));
    }

    public static Set<Item> inputs() { return Set.copyOf(RECIPES.keySet()); }

    public static Recipe select(ServerLevel level, SettlementRecord settlement, CitizenRecord citizen) {
        for (var entry : RECIPES.entrySet()) {
            int carried = count(citizen, entry.getKey());
            if (carried == 0) continue;
            // Recipe choice must stay stable for the whole carried batch. A
            // time-dependent choice caused repeated unload/refetch cycles.
            int start = Math.floorMod(citizen.id().hashCode(), entry.getValue().size());
            for (int offset = 0; offset < entry.getValue().size(); offset++) {
                Recipe recipe = entry.getValue().get((start + offset) % entry.getValue().size());
                int output = count(citizen, recipe.output())
                        + SettlementInventoryService.countItem(level, settlement, recipe.output());
                if (carried >= recipe.inputCount() && output < 64
                        && citizen.inventoryCapacityFor(new ItemStack(recipe.output(), recipe.outputCount()))
                        >= recipe.outputCount()) return recipe;
            }
        }
        return null;
    }

    public static boolean craft(CitizenRecord citizen, Recipe recipe) {
        if (recipe == null || count(citizen, recipe.input()) < recipe.inputCount()) return false;
        ItemStack consumed = citizen.extractInventoryItem(s -> s.is(recipe.input()), recipe.inputCount());
        if (consumed.getCount() != recipe.inputCount()) { citizen.insertIntoInventory(consumed); return false; }
        ItemStack remainder = citizen.insertIntoInventory(new ItemStack(recipe.output(), recipe.outputCount()));
        if (!remainder.isEmpty()) { citizen.insertIntoInventory(consumed); return false; }
        return true;
    }

    private static int count(CitizenRecord citizen, Item item) {
        return citizen.inventory().stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum();
    }
}
