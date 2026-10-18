package com.hkin.exiledsettlement.building;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import java.util.*;
import java.util.function.ToIntFunction;

/** Immutable material recipes shared by the selector, chain planner and physical worker. */
public final class KilnRecipes {
    public enum Station { FURNACE, WORKBENCH, STONECUTTER, WATER }
    public record Ingredient(Item item, int count) {}
    public record Recipe(Item output, int outputCount, int minimumOutput, Station station,
                         List<Ingredient> inputs, Item returnedItem, int returnedCount) {
        public boolean usesFuel() { return station == Station.FURNACE; }
        public Item input() { return inputs.getFirst().item(); }
        public int inputCount() { return inputs.getFirst().count(); }
        public Item extraInput() { return inputs.size()>1 ? inputs.get(1).item() : Items.AIR; }
        public int extraCount() { return inputs.size()>1 ? inputs.get(1).count() : 0; }
        public List<Ingredient> inputs(int amount) {
            return inputs.stream().map(i -> new Ingredient(i.item(),i.count()*amount/outputCount)).toList();
        }
        public int returnedCount(int amount) { return returnedCount*amount/outputCount; }
        public boolean accepts(int amount) { return amount>0 && amount<=outputCount && amount%minimumOutput==0; }
    }
    public record Batch(Recipe recipe, int amount) {}
    public record Plan(List<Batch> steps, Item missing, int missingCount) {
        public boolean ready() { return missing==Items.AIR; }
        public int fuel() { return (int)steps.stream().filter(b -> b.recipe().usesFuel()).count(); }
        public boolean needsWater() { return steps.stream().anyMatch(b -> b.recipe().station()==Station.WATER); }
    }
    public record Cost(Map<Item,Integer> inputs,Map<Item,Integer> returned,int fuel,boolean needsWater) {}
    private static Recipe recipe(Item output,int amount,int minimum,Station station,Ingredient... inputs) {
        return new Recipe(output,amount,minimum,station,List.of(inputs),Items.AIR,0);
    }
    private static Ingredient in(Item item,int count) { return new Ingredient(item,count); }
    public static final List<Recipe> RECIPES=createRecipes();
    public static final List<ResourceLocation> PRODUCTS=createProducts();
    private static List<Recipe> createRecipes() {
        var r=new ArrayList<Recipe>();
        // Preserve the original eight batch contracts, including their order.
        r.add(recipe(Items.BRICKS,2,1,Station.FURNACE,in(Items.CLAY_BALL,8)));
        r.add(recipe(Items.TERRACOTTA,8,1,Station.FURNACE,in(Items.CLAY,8)));
        r.add(recipe(Items.GLASS,8,1,Station.FURNACE,in(Items.SAND,8)));
        r.add(recipe(Items.STONE,8,1,Station.FURNACE,in(Items.COBBLESTONE,8)));
        r.add(recipe(Items.SMOOTH_STONE,8,1,Station.FURNACE,in(Items.STONE,8)));
        r.add(recipe(Items.STONE_BRICKS,8,4,Station.STONECUTTER,in(Items.STONE,8)));
        r.add(recipe(Items.PACKED_MUD,8,1,Station.WORKBENCH,in(Items.MUD,8),in(Items.WHEAT,8)));
        r.add(recipe(Items.MUD_BRICKS,8,4,Station.WORKBENCH,in(Items.PACKED_MUD,8)));
        r.add(new Recipe(Items.MUD,8,1,Station.WORKBENCH,List.of(in(Items.DIRT,8),in(Items.POTION,8)),Items.GLASS_BOTTLE,8));
        r.add(recipe(Items.CLAY,8,1,Station.WORKBENCH,in(Items.CLAY_BALL,32)));
        r.add(recipe(Items.POTION,8,1,Station.WATER,in(Items.GLASS_BOTTLE,8)));
        for(var color:DyeColor.values()) {
            var dye=DyeItem.byColor(color);
            r.add(recipe(colored(color,"terracotta"),8,8,Station.WORKBENCH,in(Items.TERRACOTTA,8),in(dye,1)));
            r.add(recipe(colored(color,"concrete_powder"),8,8,Station.WORKBENCH,in(Items.SAND,4),in(Items.GRAVEL,4),in(dye,1)));
            r.add(recipe(colored(color,"concrete"),8,1,Station.WATER,in(colored(color,"concrete_powder"),8)));
        }
        return List.copyOf(r);
    }
    private static List<ResourceLocation> createProducts() {
        var ids=new ArrayList<ResourceLocation>();
        for(int i=0;i<9;i++)ids.add(BuiltInRegistries.ITEM.getKey(RECIPES.get(i).output()));
        for(String family:List.of("terracotta","concrete"))for(var color:DyeColor.values())
            ids.add(BuiltInRegistries.ITEM.getKey(colored(color,family)));
        return List.copyOf(ids);
    }
    public static Item colored(DyeColor color,String family) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(color.getName()+"_"+family));
    }
    public static Recipe forOutput(Item item) {
        return RECIPES.stream().filter(r -> r.output()==item).findFirst().orElse(null);
    }
    public static boolean matches(ItemStack stack,Item item) {
        if(!stack.is(item))return false;
        if(item!=Items.POTION)return true;
        var contents=stack.getOrDefault(DataComponents.POTION_CONTENTS,PotionContents.EMPTY);
        return contents.is(Potions.WATER) && !contents.hasEffects();
    }
    public static ItemStack stack(Item item,int count) {
        var stack=new ItemStack(item,count);
        if(item==Items.POTION)stack.set(DataComponents.POTION_CONTENTS,new PotionContents(Potions.WATER));
        return stack;
    }
    /** Read-only planning: existing intermediates are used first, with a bounded acyclic expansion. */
    public static Plan plan(Item goal,int amount,ToIntFunction<Item> available) {
        var state=new Planner(available);
        var recipe=forOutput(goal);
        if(recipe==null || !recipe.accepts(amount))return new Plan(List.of(),goal,amount);
        state.make(recipe,amount,0);
        return new Plan(List.copyOf(state.steps),state.missing,state.missingCount);
    }
    /** Full raw-material estimate for one selected-output batch, before existing warehouse stock is applied. */
    public static Cost fullCost(Item goal,int amount) {
        var inputs=new LinkedHashMap<Item,Integer>();var returned=new LinkedHashMap<Item,Integer>();
        var recipe=forOutput(goal);
        if(recipe==null || !recipe.accepts(amount))return new Cost(Map.of(),Map.of(),0,false);
        int[] totals={0,0};expandCost(recipe,amount,inputs,returned,totals,0);
        return new Cost(Map.copyOf(inputs),Map.copyOf(returned),totals[0],totals[1]>0);
    }
    private static void expandCost(Recipe recipe,int amount,Map<Item,Integer> inputs,Map<Item,Integer> returned,int[] totals,int depth) {
        if(depth>8)return;
        if(recipe.usesFuel())totals[0]++;
        if(recipe.station()==Station.WATER)totals[1]=1;
        for(var ingredient:recipe.inputs(amount)) {
            var parent=forOutput(ingredient.item());
            if(parent==null || depth==8)inputs.merge(ingredient.item(),ingredient.count(),Integer::sum);
            else {
                int remaining=ingredient.count();
                while(remaining>0) {
                    int batchAmount=Math.min(parent.outputCount(),Math.max(parent.minimumOutput(),((remaining+parent.minimumOutput()-1)/parent.minimumOutput())*parent.minimumOutput()));
                    expandCost(parent,batchAmount,inputs,returned,totals,depth+1);
                    remaining-=batchAmount;
                }
            }
        }
        if(recipe.returnedCount(amount)>0)returned.merge(recipe.returnedItem(),recipe.returnedCount(amount),Integer::sum);
    }
    private static final class Planner {
        final ToIntFunction<Item> available;
        final Map<Item,Integer> stock=new HashMap<>();
        final List<Batch> steps=new ArrayList<>();
        Item missing=Items.AIR;int missingCount;
        Planner(ToIntFunction<Item> available) { this.available=available; }
        int count(Item i) { return stock.computeIfAbsent(i,k -> Math.max(0,available.applyAsInt(k))); }
        boolean make(Recipe recipe,int amount,int depth) {
            if(depth>8 || steps.size()>=32) { missing=recipe.output();missingCount=amount;return false; }
            for(var input:recipe.inputs(amount)) {
                while(count(input.item())<input.count()) {
                    var parent=forOutput(input.item());
                    int need=input.count()-count(input.item());
                    if(parent==null) { missing=input.item();missingCount=need;return false; }
                    int n=Math.min(parent.outputCount(),((need+parent.minimumOutput()-1)/parent.minimumOutput())*parent.minimumOutput());
                    if(!make(parent,n,depth+1))return false;
                }
                stock.put(input.item(),count(input.item())-input.count());
            }
            stock.put(recipe.output(),count(recipe.output())+amount);
            if(recipe.returnedCount(amount)>0)stock.put(recipe.returnedItem(),count(recipe.returnedItem())+recipe.returnedCount(amount));
            steps.add(new Batch(recipe,amount));return true;
        }
    }
    private KilnRecipes() {}
}
