package com.hkin.exiledsettlement.settlement;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.*;
import java.util.*;

/** Finite starter budget derived from the current formal templates, never a creative supply source. */
public final class FoundingCampSupplies {
    private FoundingCampSupplies() { }
    public static final int FIELD_SIDE=13;
    public static final int BREAD=384;
    /** WAGON v1 remains a physical barrel; only newly granted camps use the bulk-stock version. */
    public static final int STORAGE_VERSION=2;
    public record Allocation(BuildingType type,int count,int side) { }
    public static final List<Allocation> BUILDINGS=List.of(
            new Allocation(BuildingType.HOUSE,2,0),new Allocation(BuildingType.HOUSE_2,2,0),
            new Allocation(BuildingType.FARM_FIELD,1,FIELD_SIDE),new Allocation(BuildingType.MELON_FIELD,1,FIELD_SIDE),
            new Allocation(BuildingType.TREE_FARM,1,FIELD_SIDE),new Allocation(BuildingType.PASTURE_AREA,1,FIELD_SIDE),
            new Allocation(BuildingType.MINE,1,0));
    public static BuildingBlueprintTemplate template(Allocation a) {
        return a.side()==0?BlueprintTemplates.create(a.type(),BlockPos.ZERO,Direction.SOUTH):
                BlueprintTemplates.createArea(a.type(),BlockPos.ZERO,new BlockPos(a.side()-1,0,a.side()-1),Direction.SOUTH);
    }
    public static Map<Item,Integer> constructionRequirements() {
        var result=new LinkedHashMap<Item,Integer>();
        for(var a:BUILDINGS) template(a).requirements().forEach((item,n)->result.merge(item,n*a.count(),Integer::sum));
        return Collections.unmodifiableMap(result);
    }
    public static Map<Item,Integer> quantities() {
        var result=new LinkedHashMap<Item,Integer>();
        // Exact finished construction materials plus 20%, so opening play does not require a workshop first.
        constructionRequirements().forEach((item,n)->result.put(item,n+(n+4)/5));
        add(result,Items.DIRT,2048);add(result,Items.COBBLESTONE,512);
        add(result,Items.SCAFFOLDING,512);add(result,Items.OAK_LOG,128);add(result,Items.STICK,128);
        add(result,Items.IRON_INGOT,64);add(result,Items.CHARCOAL,256);add(result,Items.BREAD,BREAD);
        add(result,Items.WHEAT_SEEDS,128);add(result,Items.BEETROOT_SEEDS,128);
        add(result,Items.CARROT,128);add(result,Items.POTATO,128);
        add(result,Items.MELON_SEEDS,64);add(result,Items.PUMPKIN_SEEDS,64);
        add(result,Items.OAK_SAPLING,32);add(result,Items.SPRUCE_SAPLING,32);
        add(result,Items.WHEAT,128);add(result,Items.LEAD,4);
        add(result,Items.IRON_AXE,6);add(result,Items.IRON_PICKAXE,12);add(result,Items.IRON_SHOVEL,6);
        add(result,Items.IRON_HOE,4);add(result,Items.FISHING_ROD,2);add(result,Items.IRON_SWORD,2);
        return Collections.unmodifiableMap(result);
    }
    private static void add(Map<Item,Integer> result,Item item,int count) { result.merge(item,count,Integer::sum); }
    public static List<ItemStack> stacks() {
        var stacks=new ArrayList<ItemStack>();
        quantities().forEach((item,count)->{
            int maximum=new ItemStack(item).getMaxStackSize();
            for(int left=count;left>0;left-=maximum)stacks.add(new ItemStack(item,Math.min(left,maximum)));
        });
        return List.copyOf(stacks);
    }
}
