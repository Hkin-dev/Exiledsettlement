package com.hkin.exiledsettlement.building;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import java.util.*;

/** Vanilla potion identities, with steps obtained from the current server's brewing rules. */
public final class BrewingRecipes {
    public static final ResourceLocation DEFAULT_PRODUCT=ResourceLocation.fromNamespaceAndPath("exiledsettlement","potion/healing");
    public static final ResourceLocation WATER=ResourceLocation.fromNamespaceAndPath("exiledsettlement","potion/water");
    public record Step(ItemStack input,Item ingredient,ItemStack output) {}
    private static final List<Item> INGREDIENTS=List.of(Items.NETHER_WART,Items.REDSTONE,Items.GLOWSTONE_DUST,
            Items.FERMENTED_SPIDER_EYE,Items.GUNPOWDER,Items.DRAGON_BREATH,Items.SUGAR,Items.RABBIT_FOOT,
            Items.GLISTERING_MELON_SLICE,Items.SPIDER_EYE,Items.PUFFERFISH,Items.MAGMA_CREAM,
            Items.GOLDEN_CARROT,Items.BLAZE_POWDER,Items.GHAST_TEAR,Items.TURTLE_HELMET,
            Items.PHANTOM_MEMBRANE,Items.BREEZE_ROD,Items.SLIME_BLOCK,Items.STONE,Items.COBWEB);
    private static final Map<ServerLevel,Map<ResourceLocation,List<Step>>> PLANS=new WeakHashMap<>();
    private static List<ResourceLocation> products;
    private BrewingRecipes() {}
    public static List<ResourceLocation> products() {
        if(products==null) {
            var found=new ArrayList<ResourceLocation>();
            for(String form:List.of("potion","splash_potion","lingering_potion"))
                BuiltInRegistries.POTION.keySet().stream().filter(id->id.getNamespace().equals("minecraft"))
                        .filter(id->!id.getPath().equals("luck") && !BuiltInRegistries.POTION.get(id).getEffects().isEmpty())
                        .sorted(Comparator.comparing(ResourceLocation::getPath))
                        .forEach(id->found.add(ResourceLocation.fromNamespaceAndPath("exiledsettlement",form+"/"+id.getPath())));
            products=List.copyOf(found);
        }
        return products;
    }
    public static boolean supported(ResourceLocation id) {return id!=null && products().contains(id);}
    /** Also accepts the water/awkward identities needed by persisted intermediate batches. */
    public static ItemStack stack(ResourceLocation id) {
        if(id==null || !id.getNamespace().equals("exiledsettlement"))return ItemStack.EMPTY;
        String[] bits=id.getPath().split("/",2);if(bits.length!=2)return ItemStack.EMPTY;
        Item item=switch(bits[0]) {
            case "potion"->Items.POTION;case "splash_potion"->Items.SPLASH_POTION;
            case "lingering_potion"->Items.LINGERING_POTION;default->Items.AIR;
        };
        if(item==Items.AIR)return ItemStack.EMPTY;
        var potion=BuiltInRegistries.POTION.getHolder(ResourceKey.create(Registries.POTION,ResourceLocation.withDefaultNamespace(bits[1])));
        return potion.map(holder->PotionContents.createItemStack(item,holder)).orElse(ItemStack.EMPTY);
    }
    /** Reject renamed, custom-effect, custom-colour and other modified potion stacks. */
    public static ResourceLocation identity(ItemStack stack) {
        String form=stack.is(Items.POTION)?"potion":stack.is(Items.SPLASH_POTION)?"splash_potion":
                stack.is(Items.LINGERING_POTION)?"lingering_potion":null;
        if(form==null)return null;
        var holder=stack.getOrDefault(DataComponents.POTION_CONTENTS,PotionContents.EMPTY).potion();
        if(holder.isEmpty() || holder.get().unwrapKey().isEmpty())return null;
        var potion=holder.get().unwrapKey().orElseThrow().location();
        if(!potion.getNamespace().equals("minecraft"))return null;
        var id=ResourceLocation.fromNamespaceAndPath("exiledsettlement",form+"/"+potion.getPath());
        return ItemStack.isSameItemSameComponents(stack,stack(id))?id:null;
    }
    public static boolean matches(ItemStack actual,ItemStack expected) {
        return !actual.isEmpty() && !expected.isEmpty() && ItemStack.isSameItemSameComponents(actual,expected);
    }
    public static List<Step> plan(ServerLevel level,ResourceLocation product) {
        if(!supported(product))return List.of();
        return PLANS.computeIfAbsent(level,BrewingRecipes::discover).getOrDefault(product,List.of());
    }
    private static Map<ResourceLocation,List<Step>> discover(ServerLevel level) {
        var found=new LinkedHashMap<ResourceLocation,List<Step>>();
        var queue=new ArrayDeque<ResourceLocation>();found.put(WATER,List.of());queue.add(WATER);
        while(!queue.isEmpty() && found.size()<256) {
            var id=queue.removeFirst();var previous=found.get(id);if(previous.size()>=12)continue;
            var input=stack(id);
            for(var ingredient:INGREDIENTS) {
                var reagent=new ItemStack(ingredient);
                if(!level.potionBrewing().hasMix(input,reagent))continue;
                var output=level.potionBrewing().mix(reagent,input);
                var next=identity(output);
                if(next==null || next.equals(id) || found.containsKey(next))continue;
                var route=new ArrayList<>(previous);route.add(new Step(input.copy(),ingredient,output.copyWithCount(1)));
                found.put(next,List.copyOf(route));queue.add(next);
            }
        }
        return Collections.unmodifiableMap(found);
    }
    public static String signature(List<Step> steps) {
        return steps.stream().map(s->identity(s.input())+":"+BuiltInRegistries.ITEM.getKey(s.ingredient())+":"+identity(s.output()))
                .reduce((a,b)->a+";"+b).orElse("");
    }
}
