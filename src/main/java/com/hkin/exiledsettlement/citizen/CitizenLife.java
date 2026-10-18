package com.hkin.exiledsettlement.citizen;

import net.minecraft.nbt.CompoundTag;
import java.util.UUID;

/** Persistent life facts. Adults never lose labour eligibility because of age. */
public final class CitizenLife {
    public long ageDays = 32;
    public boolean adultReached = true;
    public int adulthoodAt = 32;
    public UUID partner;
    public UUID parentA;
    public UUID parentB;
    public long lastBirthDay = -1;
    public long sickUntil = -1;
    public long[] dietDays = {-1000, -1000, -1000, -1000};
    public String task = "";
    public UUID destination;
    public UUID deliveryHome;
    public long taskStarted = -1;
    public int workTicks;
    public String cookingInput = "", cookingOutput = "", cookingFuel = "";
    public int cookingInputCount, cookingOutputCount, cookingStage;
    public String kilnFinalOutput = "";
    public int kilnFinalCount;
    public net.minecraft.core.BlockPos stewardTarget;
    public int lastThreat = -1;
    public double recoveryNutritionRemainder;
    public long lastDamageGameTime = -1;

    public boolean adult(int adulthoodDays) {
        if (!adultReached && ageDays >= adulthoodAt) adultReached = true;
        return adultReached;
    }
    public int dietCoverage(long day, int window) {
        int count = 0;
        for (long eaten : dietDays) if (eaten <= day && day - eaten < window) count++;
        return count;
    }
    public void clearTask() { task = ""; destination = null; deliveryHome = null; taskStarted = -1; workTicks = 0; stewardTarget = null; kilnFinalOutput="";kilnFinalCount=0; }
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("AgeDays", ageDays);
        tag.putDouble("RecoveryNutritionRemainder", recoveryNutritionRemainder);
        tag.putLong("LastDamageGameTime", lastDamageGameTime);
        tag.putBoolean("AdultReached", adultReached); tag.putInt("AdulthoodAt", adulthoodAt);
        if (partner != null) tag.putUUID("Partner", partner);
        if (parentA != null) tag.putUUID("ParentA", parentA);
        if (parentB != null) tag.putUUID("ParentB", parentB);
        tag.putLong("LastBirthDay", lastBirthDay); tag.putLong("SickUntil", sickUntil);
        tag.putLongArray("DietDays", dietDays);
        tag.putString("Task", task);
        if (destination != null) tag.putUUID("Destination", destination);
        if (deliveryHome != null) tag.putUUID("DeliveryHome", deliveryHome);
        tag.putLong("TaskStarted", taskStarted); tag.putInt("WorkTicks", workTicks);
        tag.putString("CookingInput", cookingInput); tag.putString("CookingOutput", cookingOutput);
        tag.putString("CookingFuel", cookingFuel); tag.putInt("CookingInputCount", cookingInputCount);
        tag.putInt("CookingOutputCount", cookingOutputCount); tag.putInt("CookingStage", cookingStage);
        tag.putString("KilnFinalOutput",kilnFinalOutput);tag.putInt("KilnFinalCount",kilnFinalCount);
        if(stewardTarget!=null)tag.putLong("StewardTarget",stewardTarget.asLong());
        return tag;
    }
    public void load(CompoundTag tag) {
        if (tag.isEmpty()) return; // Legacy residents remain adults, with names and work untouched.
        ageDays = Math.max(0, tag.getLong("AgeDays"));
        double remainder = tag.getDouble("RecoveryNutritionRemainder");
        recoveryNutritionRemainder = Double.isFinite(remainder) ? Math.max(0, Math.min(0.999999, remainder)) : 0;
        lastDamageGameTime = tag.contains("LastDamageGameTime") ? tag.getLong("LastDamageGameTime") : -1;
        adultReached = !tag.contains("AdultReached") || tag.getBoolean("AdultReached");
        adulthoodAt = Math.max(4, Math.min(4096, tag.contains("AdulthoodAt") ? tag.getInt("AdulthoodAt") : 32));
        partner = tag.hasUUID("Partner") ? tag.getUUID("Partner") : null;
        parentA = tag.hasUUID("ParentA") ? tag.getUUID("ParentA") : null;
        parentB = tag.hasUUID("ParentB") ? tag.getUUID("ParentB") : null;
        lastBirthDay = tag.contains("LastBirthDay") ? tag.getLong("LastBirthDay") : -1;
        sickUntil = tag.contains("SickUntil") ? tag.getLong("SickUntil") : -1;
        long[] days = tag.getLongArray("DietDays");
        if (days.length == 4) dietDays = days;
        // A task resumes using real inventory and a freshly validated destination.
        task = tag.getString("Task");
        destination = tag.hasUUID("Destination") ? tag.getUUID("Destination") : null;
        deliveryHome = tag.hasUUID("DeliveryHome") ? tag.getUUID("DeliveryHome") : null;
        taskStarted = tag.contains("TaskStarted") ? tag.getLong("TaskStarted") : -1;
        workTicks = Math.max(0, Math.min(1200, tag.getInt("WorkTicks")));
        cookingInput = tag.getString("CookingInput"); cookingOutput = tag.getString("CookingOutput");
        cookingFuel = tag.getString("CookingFuel");
        cookingInputCount = Math.max(0, Math.min(64, tag.getInt("CookingInputCount")));
        cookingOutputCount = Math.max(0, Math.min(64, tag.getInt("CookingOutputCount")));
        cookingStage = Math.max(0, Math.min(3, tag.getInt("CookingStage")));
        kilnFinalOutput=tag.getString("KilnFinalOutput");kilnFinalCount=Math.max(0,Math.min(64,tag.getInt("KilnFinalCount")));
        stewardTarget=tag.contains("StewardTarget")?net.minecraft.core.BlockPos.of(tag.getLong("StewardTarget")):null;
    }
}
