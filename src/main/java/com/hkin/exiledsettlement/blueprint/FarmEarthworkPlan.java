package com.hkin.exiledsettlement.blueprint;

import com.hkin.exiledsettlement.building.BuildingType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import java.util.function.Predicate;

/** Frozen, bounded agricultural grading work. Dig top down, then fill bottom up. */
public final class FarmEarthworkPlan {
    public record Step(BlockPos position, boolean fill) {
        public BlueprintPlacement placement() {
            return new BlueprintPlacement(position, fill ? Blocks.DIRT.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                    fill ? Items.DIRT : null, fill ? BlueprintPlacementMode.STRUCTURE : BlueprintPlacementMode.CLEARANCE);
        }
    }
    public record Survey(FarmEarthworkPlan plan, BlockPos position, String reason) {
        public boolean valid() { return plan != null; }
    }
    private final List<Step> steps;
    private final int soilY, digHeight, fillDepth;
    private int cursor;
    private FarmEarthworkPlan(int soilY, int digHeight, int fillDepth, List<Step> steps, int cursor) {
        this.soilY=soilY; this.digHeight=digHeight; this.fillDepth=fillDepth;
        this.steps=new ArrayList<>(steps); this.cursor=Math.clamp(cursor, 0, steps.size());
    }
    public static boolean applies(BuildingType type) { return type==BuildingType.FARM_FIELD || type==BuildingType.MELON_FIELD; }
    public int soilY() { return soilY; }
    public int digHeight() { return digHeight; }
    public int fillDepth() { return fillDepth; }
    public int size() { return steps.size(); }
    public int completed() { return cursor; }
    public boolean done() { return cursor==steps.size(); }
    public Step next() { return done() ? null : steps.get(cursor); }
    public void advance() { if (!done()) cursor++; }
    public List<Step> remaining() { return steps.subList(cursor, steps.size()); }
    public void append(FarmEarthworkPlan repair) { steps.addAll(repair.steps); }
    public int fillCount() { return (int) steps.stream().filter(Step::fill).count(); }
    public int digCount() { return steps.size()-fillCount(); }
    public static boolean soil(BlockState s) {
        return s.is(Blocks.DIRT)||s.is(Blocks.GRASS_BLOCK)||s.is(Blocks.COARSE_DIRT)||s.is(Blocks.ROOTED_DIRT)||s.is(Blocks.FARMLAND);
    }
    public static boolean support(Level level, BlockPos p) {
        var s=level.getBlockState(p);
        return s.getFluidState().isEmpty() && s.isFaceSturdy(level,p,Direction.UP);
    }
    public static BlockPos missingSupport(Level level, BuildingBlueprintTemplate t) {
        int y=t.anchor().getY()-1;
        for(int x=t.minCorner().getX();x<=t.maxCorner().getX();x++) for(int z=t.minCorner().getZ();z<=t.maxCorner().getZ();z++) {
            var p=new BlockPos(x,y,z);
            if(!level.hasChunkAt(p) || !support(level,p.below())) return p.below();
            if(t.placementAt(p)==null && !support(level,p)) return p;
        }
        return null;
    }
    /** At most 1024 columns and 18 candidate cells per column; fixed checks, never a whole-world scan. */
    public static Survey survey(Level level, BuildingBlueprintTemplate template, Predicate<BlockPos> allowed) {
        return survey(level,template,allowed,template.minCorner().getX(),template.maxCorner().getX(),
                template.minCorner().getZ(),template.maxCorner().getZ(),FarmEarthworkConfig.DIG_HEIGHT.get(),FarmEarthworkConfig.FILL_DEPTH.get());
    }
    public Survey surveyColumn(Level level, BuildingBlueprintTemplate template, Predicate<BlockPos> allowed, BlockPos p) {
        return survey(level,template,allowed,p.getX(),p.getX(),p.getZ(),p.getZ(),digHeight,fillDepth);
    }
    private static Survey survey(Level level, BuildingBlueprintTemplate template, Predicate<BlockPos> allowed,
            int minX,int maxX,int minZ,int maxZ,int dig,int depth) {
        int y=template.anchor().getY()-1;
        List<Step> clears=new ArrayList<>(), fills=new ArrayList<>();
        for(int x=minX;x<=maxX;x++) for(int z=minZ;z<=maxZ;z++) {
            BlockPos ground=new BlockPos(x,y,z);
            for(int h=dig+1;h>=0;h--) {
                BlockPos p=ground.above(h);
                if (!allowed.test(p)) return new Survey(null,p,"FARM_EARTHWORK_CONFLICT");
                if(level.isOutsideBuildHeight(p)||!level.hasChunkAt(p)) return new Survey(null,p,"BLUEPRINT_AREA_NOT_LOADED");
                BlockState s=level.getBlockState(p); var expected=template.placementAt(p);
                boolean finalMatch=expected!=null && expected.mode()!=BlueprintPlacementMode.CLEARANCE && s.equals(expected.state());
                if(!s.getFluidState().isEmpty() && !finalMatch) return new Survey(null,p,"FARM_EARTHWORK_FLUID");
                if(h==dig+1) {
                    if(!s.isAir() && !finalMatch) return new Survey(null,p,"FARM_EARTHWORK_TOO_HIGH");
                    continue;
                }
                if(h>0 && !s.isAir() && !finalMatch) {
                    if(s.getDestroySpeed(level,p)<0) return new Survey(null,p,"BLUEPRINT_BLOCKED");
                    clears.add(new Step(p,false));
                }
            }
            // Support is checked even when the top soil already exists: no floating farmland.
            int bottom=y-1;
            while(bottom>=y-depth) {
                BlockPos p=new BlockPos(x,bottom,z);
                if(!allowed.test(p)) return new Survey(null,p,"FARM_EARTHWORK_CONFLICT");
                if(level.isOutsideBuildHeight(p)||!level.hasChunkAt(p)) return new Survey(null,p,"BLUEPRINT_AREA_NOT_LOADED");
                var s=level.getBlockState(p);
                if(!s.getFluidState().isEmpty()) return new Survey(null,p,"FARM_EARTHWORK_FLUID");
                if(support(level,p)) break;
                if(!s.isAir()) {
                    if(s.getDestroySpeed(level,p)<0) return new Survey(null,p,"BLUEPRINT_BLOCKED");
                    clears.add(new Step(p,false));
                }
                bottom--;
            }
            if(bottom<y-depth) return new Survey(null,new BlockPos(x,bottom,z),"FARM_EARTHWORK_NO_SUPPORT");
            for(int fy=bottom+1;fy<y;fy++) fills.add(new Step(new BlockPos(x,fy,z),true));
            var top=level.getBlockState(ground); var expected=template.placementAt(ground);
            boolean needsSoil=expected!=null && (expected.mode()==BlueprintPlacementMode.FARM_TILL || expected.state().is(Blocks.DIRT));
            boolean exactDirt=expected!=null && expected.state().is(Blocks.DIRT);
            boolean finalMatch=expected!=null && top.equals(expected.state());
            boolean replace=exactDirt ? !top.is(Blocks.DIRT) : needsSoil ? !soil(top)&&!finalMatch : !support(level,ground)&&!finalMatch;
            // Water/gravel replace the soil layer with their final block; only their lower support needs filling.
            boolean finalReplaces=expected!=null && !needsSoil;
            if(finalReplaces && !top.isAir() && !finalMatch) replace=true;
            if(replace) {
                if(!top.isAir()) {
                    if(top.getDestroySpeed(level,ground)<0) return new Survey(null,ground,"BLUEPRINT_BLOCKED");
                    clears.add(new Step(ground,false));
                }
                if(!finalReplaces) fills.add(new Step(ground,true));
            }
        }
        clears.sort(Comparator.comparingInt((Step s)->s.position().getY()).reversed().thenComparingLong(s->s.position().asLong()));
        fills.sort(Comparator.comparingInt((Step s)->s.position().getY()).thenComparingLong(s->s.position().asLong()));
        clears.addAll(fills);
        return new Survey(new FarmEarthworkPlan(y,dig,depth,clears,0),template.anchor(),"");
    }
    public CompoundTag save() {
        var tag=new CompoundTag(); tag.putInt("Version",1);tag.putInt("SoilY",soilY);tag.putInt("DigHeight",digHeight);tag.putInt("FillDepth",fillDepth);tag.putInt("Cursor",cursor);
        var list=new ListTag(); for(var s:steps) {var t=new CompoundTag();t.putLong("Position",s.position().asLong());t.putBoolean("Fill",s.fill());list.add(t);}tag.put("Steps",list);return tag;
    }
    public static FarmEarthworkPlan load(CompoundTag tag) {
        var steps=new ArrayList<Step>(); var list=tag.getList("Steps",Tag.TAG_COMPOUND);
        for(int i=0;i<Math.min(list.size(),32768);i++) {var s=list.getCompound(i);steps.add(new Step(BlockPos.of(s.getLong("Position")),s.getBoolean("Fill")));}
        return new FarmEarthworkPlan(tag.getInt("SoilY"),tag.getInt("DigHeight"),tag.getInt("FillDepth"),steps,tag.getInt("Cursor"));
    }
}
