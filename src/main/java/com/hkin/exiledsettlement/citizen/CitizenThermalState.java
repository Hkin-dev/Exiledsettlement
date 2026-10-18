package com.hkin.exiledsettlement.citizen;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/** One persistent thermal garment, separate from armor and carried cargo. */
public final class CitizenThermalState {
    public float body=37,wet,ambient=14,change;
    public boolean shelter,warming;
    public ItemStack garment=ItemStack.EMPTY;
    public double wear;
    public long lastTick=-1,lastDayTime=-1,lastSwap=-1200;
    public String reason="",material="";
    public CompoundTag save(HolderLookup.Provider registries) {
        var t=new CompoundTag();t.putFloat("Body",body);t.putFloat("Wet",wet);t.putBoolean("Warming",warming);
        t.put("Garment",garment.saveOptional(registries));t.putDouble("Wear",wear);t.putString("TailorMaterial",material);
        return t;
    }
    public void load(CompoundTag t,HolderLookup.Provider registries,int oldWarmth) {
        body=t.contains("Body")?safe(t.getFloat("Body"),30,42):36+Math.clamp(oldWarmth,0,100)/100F;
        wet=safe(t.getFloat("Wet"),0,1);warming=t.getBoolean("Warming");
        garment=ItemStack.parseOptional(registries,t.getCompound("Garment"));
        if(!garment.isEmpty() && !CitizenClothingService.isGarment(garment))garment=ItemStack.EMPTY;
        if(!garment.isEmpty())garment.setCount(1);
        wear=Double.isFinite(t.getDouble("Wear"))?Math.clamp(t.getDouble("Wear"),0,1):0;
        material=t.getString("TailorMaterial");lastTick=-1;lastDayTime=-1;
    }
    private static float safe(float n,float min,float max) {return Float.isFinite(n)?Math.clamp(n,min,max):min;}
}
