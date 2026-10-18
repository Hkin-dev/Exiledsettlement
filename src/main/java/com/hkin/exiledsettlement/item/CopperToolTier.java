package com.hkin.exiledsettlement.item;

import net.minecraft.world.item.Items;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.common.SimpleTier;

/** Iron performance, half durability, copper repair material. */
public final class CopperToolTier {
    public static final Tier COPPER = new SimpleTier(Tiers.IRON.getIncorrectBlocksForDrops(),
            Tiers.IRON.getUses() / 2, Tiers.IRON.getSpeed(), Tiers.IRON.getAttackDamageBonus(),
            Tiers.IRON.getEnchantmentValue(), () -> Ingredient.of(Items.COPPER_INGOT));

    private CopperToolTier() { }
}
