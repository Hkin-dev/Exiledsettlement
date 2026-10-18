package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.building.SettlementInventoryService;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import java.util.List;

/** Bounded wood, stone, copper and iron tool recipes used by the artisan workshop. */
public final class ArtisanRecipes {
    public record Recipe(Item output, Item material, int materialCount, int sticks) { }
    private static final List<Recipe> WOOD = List.of(
            new Recipe(Items.WOODEN_AXE, Items.OAK_PLANKS, 3, 2),
            new Recipe(Items.WOODEN_PICKAXE, Items.OAK_PLANKS, 3, 2),
            new Recipe(Items.WOODEN_SHOVEL, Items.OAK_PLANKS, 1, 2),
            new Recipe(Items.WOODEN_HOE, Items.OAK_PLANKS, 2, 2),
            new Recipe(Items.WOODEN_SWORD, Items.OAK_PLANKS, 2, 1));
    private static final List<Recipe> STONE = List.of(
            new Recipe(Items.STONE_AXE, Items.COBBLESTONE, 3, 2),
            new Recipe(Items.STONE_PICKAXE, Items.COBBLESTONE, 3, 2),
            new Recipe(Items.STONE_SHOVEL, Items.COBBLESTONE, 1, 2),
            new Recipe(Items.STONE_HOE, Items.COBBLESTONE, 2, 2),
            new Recipe(Items.STONE_SWORD, Items.COBBLESTONE, 2, 1));
    private static final List<Recipe> IRON = List.of(
            new Recipe(Items.IRON_AXE, Items.IRON_INGOT, 3, 2),
            new Recipe(Items.IRON_PICKAXE, Items.IRON_INGOT, 3, 2),
            new Recipe(Items.IRON_SHOVEL, Items.IRON_INGOT, 1, 2),
            new Recipe(Items.IRON_HOE, Items.IRON_INGOT, 2, 2),
            new Recipe(Items.IRON_SWORD, Items.IRON_INGOT, 2, 1));

    private ArtisanRecipes() { }

    // Resolve deferred items after registry setup, rather than during class initialization.
    private static Recipe copper(int index) {
        var items = List.of(com.hkin.exiledsettlement.registry.ModItems.COPPER_AXE.get(),
                com.hkin.exiledsettlement.registry.ModItems.COPPER_PICKAXE.get(),
                com.hkin.exiledsettlement.registry.ModItems.COPPER_SHOVEL.get(),
                com.hkin.exiledsettlement.registry.ModItems.COPPER_HOE.get(),
                com.hkin.exiledsettlement.registry.ModItems.COPPER_SWORD.get());
        Recipe iron = IRON.get(index);
        return new Recipe(items.get(index), Items.COPPER_INGOT, iron.materialCount(), iron.sticks());
    }

    public static Recipe select(ServerLevel level, SettlementRecord settlement, CitizenRecord citizen) {
        // Keep a resident on one recipe while it is collecting and consuming a
        // finite batch. Changing this index with world time can strand partial
        // inputs and make the worker return the same materials to storage.
        int index = Math.floorMod(citizen.id().hashCode(), 5);
        // A carried material stack is an in-progress batch reservation. Keep
        // that recipe selected even when another consumer temporarily removes
        // the matching sticks from storage; the worker must wait with the
        // partial batch instead of unloading and fetching it again.
        if (carried(citizen, Items.IRON_INGOT) > 0) return IRON.get(index);
        if (carried(citizen, Items.COPPER_INGOT) > 0) return copper(index);
        if (carried(citizen, Items.COBBLESTONE) > 0) return STONE.get(index);
        for (Item plank : CitizenWorkService.carpenterPlanks()) {
            if (carried(citizen, plank) > 0) {
                Recipe wood = WOOD.get(index);
                return new Recipe(wood.output(), plank, wood.materialCount(), wood.sticks());
            }
        }
        if (available(level, settlement, citizen, Items.IRON_INGOT) >= IRON.get(index).materialCount()
                && available(level, settlement, citizen, Items.STICK) >= IRON.get(index).sticks()) return IRON.get(index);
        Recipe copper = copper(index);
        if (available(level, settlement, citizen, Items.COPPER_INGOT) >= copper.materialCount()
                && available(level, settlement, citizen, Items.STICK) >= copper.sticks()) return copper;
        if (available(level, settlement, citizen, Items.COBBLESTONE) >= STONE.get(index).materialCount()
                && available(level, settlement, citizen, Items.STICK) >= STONE.get(index).sticks()) return STONE.get(index);
        // Accept every vanilla plank species as a wooden tool material. The
        // actual extracted plank is selected by the supply step.
        for (Item plank : CitizenWorkService.carpenterPlanks()) {
            Recipe recipe = new Recipe(WOOD.get(index).output(), plank, WOOD.get(index).materialCount(), WOOD.get(index).sticks());
            if (available(level, settlement, citizen, plank) >= recipe.materialCount()
                    && available(level, settlement, citizen, Items.STICK) >= recipe.sticks()) return recipe;
        }
        return null;
    }

    private static int available(ServerLevel level, SettlementRecord settlement, CitizenRecord citizen, Item item) {
        return carried(citizen, item) + SettlementInventoryService.countItem(level, settlement, item);
    }

    private static int carried(CitizenRecord citizen, Item item) {
        return citizen.inventory().stream().filter(s -> s.is(item)).mapToInt(s -> s.getCount()).sum();
    }
}
