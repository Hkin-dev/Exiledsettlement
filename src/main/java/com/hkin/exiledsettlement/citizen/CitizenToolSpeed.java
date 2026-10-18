package com.hkin.exiledsettlement.citizen;

import net.minecraft.world.item.*;

/** Tools improve labour rate; none is a prerequisite for resident work. */
public final class CitizenToolSpeed {
    private CitizenToolSpeed() { }
    public static double multiplier(ItemStack stack) {
        if (stack.isEmpty()) return 1;
        if (stack.getItem() instanceof TieredItem tool) {
            var tier=tool.getTier();
            if(tier==Tiers.WOOD)return 1.15;
            if(tier==Tiers.STONE)return 1.3;
            if(tier==Tiers.IRON)return 1.6;
            if(tier==Tiers.GOLD)return 1.8;
            if(tier==Tiers.DIAMOND)return 2;
            if(tier==Tiers.NETHERITE)return 2.4;
            return Math.max(1.15,Math.min(2.4,1+tier.getSpeed()/10.0));
        }
        return stack.getItem() instanceof FishingRodItem || stack.getItem() instanceof BowItem
                || stack.is(Items.SHEARS) || stack.is(Items.LEAD) ? 1.15 : 1;
    }
    public static double multiplier(CitizenRecord citizen) {
        return multiplier(citizen.inventoryItem(CitizenEquipmentService.toolSlotForWork(citizen)));
    }
    public static int interval(CitizenRecord citizen,int base) {
        return Math.max(1,(int)Math.ceil(base/multiplier(citizen)));
    }
    public static int schedulerInterval(CitizenRecord citizen,int base) {
        return Math.max(1,(int)Math.floor(base/multiplier(citizen)));
    }
}
