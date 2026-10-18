package com.hkin.exiledsettlement.settlement;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Blend with the resource pack / biome color instead of replacing it. */
public final class SeasonVisualPalette {
    private SeasonVisualPalette() { }
    public static boolean temperate(float temperature) { return temperature >= 0.15F && temperature < 0.95F; }
    public static boolean supportsSnow(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.PODZOL) || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.STONE) || state.is(Blocks.ANDESITE)
                || state.is(Blocks.DIORITE) || state.is(Blocks.GRANITE);
    }
    public static int tint(int original, int season, boolean leaves, float temperature) {
        if (!temperate(temperature) || season < 0 || season > 3) return original;
        int target = switch (season) {
            case 0 -> leaves ? 0x86BD59 : 0x82BC57;
            case 1 -> leaves ? 0x377D35 : 0x52943C;
            case 2 -> leaves ? 0xDCA047 : 0xAB9A58;
            default -> leaves ? 0xA6B6A5 : 0xADB9AD;
        };
        int weight = season == 2 ? (leaves ? 78 : 48) : season == 3 ? 65 : 40;
        int result = 0;
        for (int shift : new int[] {16, 8, 0}) {
            int channel = (((original >> shift) & 255) * (100 - weight) + ((target >> shift) & 255) * weight) / 100;
            result |= channel << shift;
        }
        return result;
    }
    public static int tintTransition(int original,int season,int step,boolean leaves,float temperature) {
        int current=tint(original,season,leaves,temperature);
        if(step<=0||season<0)return current;
        int next=tint(original,(season+1)%4,leaves,temperature),weight=Math.min(15,step),result=0;
        for(int shift:new int[]{16,8,0})result|=((current>>shift&255)*(16-weight)+(next>>shift&255)*weight)/16<<shift;
        return result;
    }
}
