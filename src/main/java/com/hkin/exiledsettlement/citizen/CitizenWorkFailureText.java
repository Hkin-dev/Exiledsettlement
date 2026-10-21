package com.hkin.exiledsettlement.citizen;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

import java.util.Locale;

/** Carries translatable brewing blockers through the existing saved text field. */
public final class CitizenWorkFailureText {
    private CitizenWorkFailureText() { }

    public static Component component(String failure) {
        String[] parts = failure.split("\\|", 3);
        if (parts.length == 3 && (parts[0].equals("PRIEST_INPUT_MISSING")
                || parts[0].equals("PRIEST_WATER_MISSING") || parts[0].equals("PRIEST_FUEL_MISSING"))) {
            ResourceLocation id = ResourceLocation.tryParse(parts[1]);
            try {
                int count = Integer.parseInt(parts[2]);
                if (id != null && BuiltInRegistries.ITEM.containsKey(id) && count > 0 && count <= 4096) {
                    ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(id));
                    if (stack.is(Items.POTION)) stack = PotionContents.createItemStack(Items.POTION, Potions.WATER);
                    return Component.translatable("work_failure.exiledsettlement.priest_input_missing_detail",
                            stack.getHoverName(), count);
                }
            } catch (NumberFormatException ignored) { }
            return Component.translatable("work_failure.exiledsettlement." + parts[0].toLowerCase(Locale.ROOT));
        }
        return failure.matches("[A-Z0-9_]+")
                ? Component.translatable("work_failure.exiledsettlement." + failure.toLowerCase(Locale.ROOT))
                : Component.literal(failure);
    }
}
