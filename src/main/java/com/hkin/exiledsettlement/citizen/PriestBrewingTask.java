package com.hkin.exiledsettlement.citizen;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Ownership and progress only. No saved item can be redeemed from this record. */
public final class PriestBrewingTask {
    public static final int FETCH=0, LOAD=1, BREW=2, COLLECT=3, DELIVER=4;
    public UUID token=UUID.randomUUID(), church;
    public BlockPos stand;
    public ResourceLocation product, bagProduct;
    public int amount, step, totalSteps, phase, bottlesInBag, delivered;
    public boolean cancelRequested, interference;
    public String signature="";
    /** Exact plain ingredient/return stacks fetched for this batch and still in the resident bag. */
    public final Map<ResourceLocation,Integer> cargo=new LinkedHashMap<>();
    public CompoundTag save() {
        var tag=new CompoundTag();
        tag.putUUID("Token",token);
        if(church!=null)tag.putUUID("Church",church);
        if(stand!=null)tag.putLong("Stand",stand.asLong());
        if(product!=null)tag.putString("Product",product.toString());
        if(bagProduct!=null)tag.putString("BagProduct",bagProduct.toString());
        tag.putInt("Amount",amount);tag.putInt("Step",step);tag.putInt("Phase",phase);
        tag.putInt("TotalSteps",totalSteps);
        tag.putInt("BottlesInBag",bottlesInBag);tag.putInt("Delivered",delivered);
        tag.putBoolean("CancelRequested",cancelRequested);tag.putBoolean("Interference",interference);
        tag.putString("Signature",signature);
        var cargoTag=new CompoundTag();
        cargo.forEach((id,count)->{if(count>0)cargoTag.putInt(id.toString(),count);});
        tag.put("Cargo",cargoTag);return tag;
    }
    public static PriestBrewingTask load(CompoundTag tag) {
        if(!tag.hasUUID("Token") || !tag.hasUUID("Church") || !tag.contains("Stand"))return null;
        var product=parseId(tag.getString("Product"));
        int amount=tag.getInt("Amount"),phase=tag.getInt("Phase"),step=tag.getInt("Step");
        if(product==null || amount<1 || amount>3 || phase<0 || phase>DELIVER || step<0 || step>16)return null;
        var task=new PriestBrewingTask();task.token=tag.getUUID("Token");task.church=tag.getUUID("Church");
        task.stand=BlockPos.of(tag.getLong("Stand"));task.product=product;task.amount=amount;task.phase=phase;task.step=step;
        task.totalSteps=Math.max(0,Math.min(16,tag.getInt("TotalSteps")));
        // Vanilla accepts "" as the resource id "minecraft:". Missing optional state must stay absent.
        task.bagProduct=parseId(tag.getString("BagProduct"));
        task.bottlesInBag=Math.max(0,Math.min(3,tag.getInt("BottlesInBag")));
        task.delivered=Math.max(0,Math.min(amount,tag.getInt("Delivered")));
        task.cancelRequested=tag.getBoolean("CancelRequested");task.interference=tag.getBoolean("Interference");
        task.signature=tag.getString("Signature");
        var cargo=tag.getCompound("Cargo");
        for(String key:cargo.getAllKeys()) {
            var id=parseId(key);
            if(id!=null && cargo.getInt(key)>0)task.cargo.put(id,Math.min(64,cargo.getInt(key)));
        }
        return task;
    }
    private static ResourceLocation parseId(String value) {
        if(value.isEmpty())return null;
        var id=ResourceLocation.tryParse(value);
        return id==null || id.getPath().isEmpty()?null:id;
    }
}
