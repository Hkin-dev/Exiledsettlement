package com.hkin.exiledsettlement.building;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.*;
import java.util.*;

/** Mixed, non-selectable finds. Three independent draws per completed work cycle. */
public final class MineProductionService {
    private MineProductionService() {}
    public static final int PRODUCTION_INTERVAL_TICKS = 800;
    private record Find(Item item, int weight, int maximum) {}
    private static final List<Find> FINDS = List.of(
            // Total weight 800. Six rare finds retain 75% of their former draw probability;
            // their removed weight goes to cobblestone and cobbled deepslate.
            new Find(Items.COBBLESTONE,64,4), new Find(Items.COBBLED_DEEPSLATE,48,4),
            new Find(Items.GRAVEL,32,3), new Find(Items.COAL,128,3),
            new Find(Items.RAW_COPPER,96,3), new Find(Items.RAW_IRON,80,2),
            new Find(Items.RAW_GOLD,18,1), new Find(Items.REDSTONE,24,3),
            new Find(Items.LAPIS_LAZULI,16,2), new Find(Items.DIAMOND,6,1),
            new Find(Items.GRANITE,24,4), new Find(Items.DIORITE,24,4),
            new Find(Items.ANDESITE,24,4), new Find(Items.TUFF,16,4),
            new Find(Items.CALCITE,16,3), new Find(Items.DRIPSTONE_BLOCK,16,3),
            new Find(Items.BASALT,12,3), new Find(Items.BLACKSTONE,12,3),
            new Find(Items.SAND,8,3), new Find(Items.CLAY_BALL,4,3),
            new Find(Items.FLINT,4,2), new Find(Items.AMETHYST_SHARD,12,2),
            new Find(Items.EMERALD,6,1), new Find(Items.QUARTZ,24,3),
            new Find(Items.STONE,24,4), new Find(Items.DEEPSLATE,16,4),
            new Find(Items.OBSIDIAN,3,1), new Find(Items.POINTED_DRIPSTONE,8,2),
            new Find(Items.AMETHYST_BLOCK,3,1), new Find(Items.RED_SAND,8,3),
            new Find(Items.SANDSTONE,16,3), new Find(Items.RED_SANDSTONE,8,3));
    private static final int WEIGHT = FINDS.stream().mapToInt(Find::weight).sum();
    public static boolean isOutput(ItemStack stack) { return FINDS.stream().anyMatch(f -> stack.is(f.item)); }
    public static List<ItemStack> roll(RandomSource random) {
        Map<Item,Integer> counts = new LinkedHashMap<>();
        for(int draw=0;draw<3;draw++) {
            int choice=random.nextInt(WEIGHT);
            for(Find find:FINDS) {
                choice-=find.weight;
                if(choice<0) { counts.merge(find.item,1+random.nextInt(find.maximum),Integer::sum);break; }
            }
        }
        return counts.entrySet().stream().map(e -> new ItemStack(e.getKey(),e.getValue())).toList();
    }
}
