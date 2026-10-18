package com.hkin.exiledsettlement.building;

import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read-only, bounded recipe planning. A failed branch never changes real inventory. */
public final class WarehouseCrafting {
    public record Plan(List<ItemStack> remaining, ItemStack output, int operations) { }
    private record Index(Collection<RecipeHolder<?>> source, Map<Item,List<Recipe<?>>> recipes) { }
    private static final Map<RecipeManager,Index> INDICES = new WeakHashMap<>();
    private static final Map<Item,List<Item>> FAMILIES = families();
    private static Map<Item,List<Item>> families() {
        Map<Item,List<Item>> result=new IdentityHashMap<>();
        try(var stream=WarehouseCrafting.class.getResourceAsStream("/data/exiledsettlement/warehouse/conversions.json")) {
            if(stream==null)throw new IllegalStateException("Missing warehouse conversion families");
            var json=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            for(var group:json.getAsJsonArray("families")) {
                List<Item> items=new ArrayList<>();
                for(var id:group.getAsJsonArray()) {
                    Item item=BuiltInRegistries.ITEM.get(ResourceLocation.parse(id.getAsString()));
                    if(item==Items.AIR || result.containsKey(item) || items.contains(item))throw new IllegalStateException("Invalid conversion item "+id);
                    items.add(item);
                }
                var frozen=List.copyOf(items);for(Item item:items)result.put(item,frozen);
            }
        } catch(java.io.IOException e) {throw new IllegalStateException(e);}
        return result;
    }
    public static boolean plain(ItemStack stack) {
        return !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack,new ItemStack(stack.getItem()));
    }
    public static void invalidate() { INDICES.clear(); WarehouseConstructionSupply.invalidate(); }
    private static Index index(ServerLevel level) {
        var manager=level.getRecipeManager();var old=INDICES.get(manager);
        var source=manager.getRecipes();
        if(old!=null && old.source()==source)return old;
        Map<Item,List<Recipe<?>>> recipes=new IdentityHashMap<>();
        var holders=new ArrayList<>(source);holders.sort(Comparator.comparing(h->h.id().toString()));
        for(var holder:holders) {
            var recipe=holder.value();
            if(!(recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe || recipe instanceof StonecutterRecipe))continue;
            var output=recipe.getResultItem(level.registryAccess());
            if(output.isEmpty() || !plain(output) || recipe.getIngredients().isEmpty())continue;
            recipes.computeIfAbsent(output.getItem(),k->new ArrayList<>()).add(recipe);
        }
        var value=new Index(source,recipes);INDICES.put(manager,value);WarehouseConstructionSupply.invalidate();return value;
    }
    public static Object generation(ServerLevel level) { return index(level); }
    public static int indexedOutputs(ServerLevel level) { return index(level).recipes().size(); }
    public static Plan plan(ServerLevel level,List<ItemStack> source,Item target,int amount,Map<Item,Integer> protectedCounts) {
        if(amount<=0 || amount>64 || target==Items.AIR)return null;
        var solver=new Solver(level,index(level),source,protectedCounts);
        if(!solver.take(target,amount,0))return null;
        List<ItemStack> remaining=new ArrayList<>();
        for(var stack:source)if(!plain(stack))remaining.add(stack.copy());
        for(var entry:solver.stock.entrySet()) {
            int n=entry.getValue()+solver.protectedStock.getOrDefault(entry.getKey(),0);
            while(n>0){int part=Math.min(n,new ItemStack(entry.getKey()).getMaxStackSize());remaining.add(new ItemStack(entry.getKey(),part));n-=part;}
        }
        long quantity=remaining.stream().mapToLong(ItemStack::getCount).sum();
        if(quantity>WarehouseStock.CAPACITY)return null;
        return new Plan(List.copyOf(remaining),new ItemStack(target,amount),solver.operations);
    }
    private static final class Solver {
        final ServerLevel level;final Index index;
        Map<Item,Integer> stock=new LinkedHashMap<>();final Map<Item,Integer> protectedStock=new IdentityHashMap<>();
        final Set<Item> visiting=Collections.newSetFromMap(new IdentityHashMap<>());
        int budget=4096,operations;
        Solver(ServerLevel level,Index index,List<ItemStack> source,Map<Item,Integer> protectedCounts) {
            this.level=level;this.index=index;
            for(var stack:source)if(plain(stack))stock.merge(stack.getItem(),stack.getCount(),Integer::sum);
            for(var entry:protectedCounts.entrySet()) {
                int n=Math.min(count(entry.getKey()),Math.max(0,entry.getValue()));
                protectedStock.put(entry.getKey(),n);stock.put(entry.getKey(),count(entry.getKey())-n);
            }
        }
        int count(Item item){return stock.getOrDefault(item,0);}
        boolean take(Item item,int amount,int depth) {
            if(--budget<0 || depth>16)return false;
            if(count(item)>=amount){stock.put(item,count(item)-amount);return true;}
            if(!visiting.add(item))return false;
            var before=new LinkedHashMap<>(stock);int oldOps=operations;
            try {
                // Conversion uses only actual spare stock, never recursive conversion edges.
                var donors=new ArrayList<>(FAMILIES.getOrDefault(item,List.of()));
                donors.removeIf(i->i==item || visiting.contains(i));
                donors.sort(Comparator.comparingInt((Item i)->count(i)).reversed().thenComparing(i->BuiltInRegistries.ITEM.getKey(i).toString()));
                for(var donor:donors) {
                    int n=Math.min(amount-count(item),count(donor));
                    if(n>0){stock.put(donor,count(donor)-n);stock.put(item,count(item)+n);operations++;}
                    if(count(item)>=amount)break;
                }
                while(count(item)<amount) {
                    boolean made=false;
                    for(var recipe:index.recipes().getOrDefault(item,List.of())) {
                        if(--budget<0)break;
                        var saved=new LinkedHashMap<>(stock);int ops=operations;
                        var inputs=recipe.getIngredients();var slots=new ArrayList<ItemStack>();
                        for(int i=0;i<inputs.size();i++)slots.add(ItemStack.EMPTY);
                        var order=new ArrayList<Integer>();for(int i=0;i<inputs.size();i++)if(!inputs.get(i).isEmpty())order.add(i);
                        order.sort(Comparator.comparingInt(i->inputs.get(i).getItems().length));
                        if(fill(recipe,inputs,slots,order,0,depth+1,item)){made=true;operations++;break;}
                        stock=saved;operations=ops;
                    }
                    if(!made){stock=before;operations=oldOps;return false;}
                }
                stock.put(item,count(item)-amount);return true;
            } finally {visiting.remove(item);}
        }
        boolean fill(Recipe<?> recipe,List<Ingredient> inputs,List<ItemStack> slots,List<Integer> order,int at,int depth,Item target) {
            if(--budget<0)return false;
            if(at==order.size())return assemble(recipe,slots,target);
            int slot=order.get(at);var ingredient=inputs.get(slot);
            var choices=new ArrayList<Item>();
            for(var stack:ingredient.getItems())if(plain(stack) && ingredient.test(new ItemStack(stack.getItem())) && !choices.contains(stack.getItem()))choices.add(stack.getItem());
            choices.sort(Comparator.comparingInt((Item i)->count(i)>0?0:1).thenComparing(Comparator.comparingInt((Item i)->count(i)).reversed()));
            for(var choice:choices) {
                var saved=new LinkedHashMap<>(stock);int ops=operations;
                if(take(choice,1,depth)) {
                    slots.set(slot,new ItemStack(choice));
                    if(fill(recipe,inputs,slots,order,at+1,depth,target))return true;
                }
                stock=saved;operations=ops;slots.set(slot,ItemStack.EMPTY);
                if(budget<=0)break;
            }
            return false;
        }
        boolean assemble(Recipe<?> recipe,List<ItemStack> slots,Item target) {
            ItemStack output;List<ItemStack> returns;
            if(recipe instanceof CraftingRecipe crafting) {
                int width=recipe instanceof ShapedRecipe shaped?shaped.getWidth():3;
                int height=recipe instanceof ShapedRecipe shaped?shaped.getHeight():3;
                var grid=new ArrayList<>(slots);while(grid.size()<width*height)grid.add(ItemStack.EMPTY);
                var input=CraftingInput.of(width,height,grid);
                if(!crafting.matches(input,level))return false;
                output=crafting.assemble(input,level.registryAccess());returns=crafting.getRemainingItems(input);
            } else if(recipe instanceof StonecutterRecipe cutting) {
                var input=new SingleRecipeInput(slots.getFirst());
                if(!cutting.matches(input,level))return false;
                output=cutting.assemble(input,level.registryAccess());returns=cutting.getRemainingItems(input);
            } else return false;
            if(!output.is(target) || !plain(output) || output.getCount()<=0)return false;
            for(var back:returns)if(!back.isEmpty() && !plain(back))return false;
            stock.merge(output.getItem(),output.getCount(),Integer::sum);
            for(var back:returns)if(!back.isEmpty())stock.merge(back.getItem(),back.getCount(),Integer::sum);
            return true;
        }
    }
    private WarehouseCrafting() { }
}
