package com.hkin.exiledsettlement.decoration;

import com.google.gson.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.settlement.CommunityStock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.*;
import net.minecraft.world.item.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Demand-driven component assembly performed in the resident's real backpack after physical collection. */
public final class DecorationCrafting {
    private record Ingredient(String id, int count) { }
    private record Recipe(int output, List<Ingredient> inputs) { }
    private static final Map<Item, Recipe> RECIPES = new LinkedHashMap<>();
    static {
        try (var stream = DecorationCrafting.class.getResourceAsStream("/data/exiledsettlement/decoration/assembly_recipes.json")) {
            if (stream == null) throw new IllegalStateException("Missing decoration assembly recipes");
            var root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var entry : root.getAsJsonObject("recipes").entrySet()) {
                var r = entry.getValue().getAsJsonObject(); var ingredients = new ArrayList<Ingredient>();
                for (var input : r.getAsJsonObject("inputs").entrySet()) ingredients.add(new Ingredient(input.getKey(), input.getValue().getAsInt()));
                RECIPES.put(item(entry.getKey()), new Recipe(r.get("output").getAsInt(), List.copyOf(ingredients)));
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    private DecorationCrafting() { }
    private static Item item(String id) {
        var key = ResourceLocation.parse(id); var result = BuiltInRegistries.ITEM.get(key);
        if (result == Items.AIR) throw new IllegalStateException("Unknown assembly item " + id); return result;
    }
    private static int carried(CitizenRecord c, Item item) {
        int count = 0; for (int i = 0; i < CitizenRecord.INVENTORY_SIZE; i++)
            if (!CitizenEquipmentService.isReservedEquipment(c, i) && c.inventoryItem(i).is(item)) count += c.inventoryItem(i).getCount();
        return count;
    }
    private static Item select(ServerLevel level, SettlementRecord s, CitizenRecord c, Ingredient in, Item excluded) {
        if (!in.id.startsWith("#")) return item(in.id);
        var tag = TagKey.create(net.minecraft.core.registries.Registries.ITEM, ResourceLocation.parse(in.id.substring(1)));
        var candidates = new LinkedHashSet<Item>();
        for (var stack : c.inventory()) if (!stack.isEmpty() && stack.is(tag)) candidates.add(stack.getItem());
        for (var b : SettlementInventoryService.warehouses(s)) for (var i : CommunityStock.items(level, s, b).keySet())
            if (new ItemStack(i).is(tag)) candidates.add(i);
        // Registry tags provide the fallback without a registry-wide scan for every assembly step.
        candidates.remove(excluded);
        if (candidates.isEmpty()) BuiltInRegistries.ITEM.getTag(tag).ifPresent(holders -> holders.forEach(holder -> { if (holder.value() != excluded) candidates.add(holder.value()); }));
        return candidates.stream().filter(i -> i != excluded)
                .max(Comparator.comparingInt((Item i) -> carried(c, i) * 1024 + Math.max(0, CommunityStock.count(level, s, i) - reserve(s, i)))
                        .thenComparing(i -> BuiltInRegistries.ITEM.getKey(i).toString())).orElse(Items.AIR);
    }
    public static int reserve(SettlementRecord s, Item item) {
        if (new ItemStack(item).is(ItemTags.LOGS) || new ItemStack(item).is(ItemTags.PLANKS)) return DecorationCatalog.WOOD_RESERVE;
        return item == Items.COAL || item == Items.CHARCOAL ? s.community().fuelReserve : 0;
    }
    public static Map<Item, Integer> ingredients(ServerLevel level, SettlementRecord s, CitizenRecord c, Map<Item, Integer> outputs) {
        var result = new LinkedHashMap<Item, Integer>();
        outputs.forEach((i, n) -> expand(level, s, c, i, Math.min(64, n), result, new HashSet<>(), 0)); return result;
    }
    private static void expand(ServerLevel level, SettlementRecord s, CitizenRecord c, Item output, int wanted,
                               Map<Item, Integer> result, Set<Item> visiting, int depth) {
        int missing = Math.max(0, wanted - carried(c, output)); if (missing == 0) return;
        if (CommunityStock.count(level, s, output) > reserve(s, output)) {
            result.merge(output, wanted, Integer::sum); return;
        }
        var recipe = RECIPES.get(output);
        if (recipe == null || depth > 8 || !visiting.add(output)) { result.merge(output, wanted, Integer::sum); return; }
        int batches = (missing + recipe.output - 1) / recipe.output;
        for (var input : recipe.inputs) expand(level, s, c, select(level, s, c, input, output), batches * input.count, result, visiting, depth + 1);
        visiting.remove(output);
    }
    public static BuildingRecord warehouse(ServerLevel level, SettlementRecord s, CitizenRecord c, Item output) {
        var ready = SettlementInventoryService.supplyWarehouse(level, s, c.lastPosition(), output);
        if (ready != null) return ready;
        var needed = ingredients(level, s, c, Map.of(output, 1));
        return SettlementInventoryService.warehouses(s).stream()
                .filter(b -> needed.entrySet().stream().anyMatch(e -> carried(c, e.getKey()) < e.getValue()
                        && WarehouseInventoryService.countItem(level, b, e.getKey()) > reserve(s, e.getKey())))
                .min(Comparator.comparingDouble(b -> b.position().distSqr(c.lastPosition())))
                .orElse(SettlementInventoryService.nearestWarehouse(s, c.lastPosition()));
    }
    public static int craft(ServerLevel level, SettlementRecord s, CitizenRecord c, Map<Item, Integer> wanted) {
        if (c.profession() != CitizenProfession.WORKER) return 0;
        int made = 0;
        for (var entry : wanted.entrySet()) made += craftItem(level, s, c, entry.getKey(), Math.min(64, entry.getValue()), new HashSet<>(), 0);
        return made;
    }
    private static int craftItem(ServerLevel level, SettlementRecord s, CitizenRecord c, Item output, int wanted, Set<Item> visiting, int depth) {
        var recipe = RECIPES.get(output);
        if (recipe == null || depth > 8 || !visiting.add(output)) return 0;
        int made = 0;
        for (int batch = 0; carried(c, output) < wanted && batch < 64; batch++) {
            var inputs = new LinkedHashMap<Item, Integer>();
            for (var in : recipe.inputs) inputs.merge(select(level, s, c, in, output), in.count, Integer::sum);
            for (var input : inputs.entrySet()) craftItem(level, s, c, input.getKey(), input.getValue(), visiting, depth + 1);
            if (inputs.entrySet().stream().anyMatch(e -> carried(c, e.getKey()) < e.getValue())
                    || c.inventoryCapacityFor(new ItemStack(output)) < recipe.output) break;
            // Output capacity and all inputs are checked before a single-thread commit.
            for (var in : inputs.entrySet()) {
                int remaining = in.getValue();
                while (remaining > 0) remaining -= c.extractInventoryItem(stack -> stack.is(in.getKey()), remaining).getCount();
            }
            c.insertIntoInventory(new ItemStack(output, recipe.output)); made += recipe.output;
        }
        visiting.remove(output); return made;
    }
}
