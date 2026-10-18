package com.hkin.exiledsettlement.registry;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.item.BuildingBlueprintItem;
import com.hkin.exiledsettlement.item.ClaimDeedItem;
import com.hkin.exiledsettlement.item.AppointmentDocumentItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ExiledSettlementMod.MOD_ID);
    private static final net.minecraft.world.item.Tier COPPER = com.hkin.exiledsettlement.item.CopperToolTier.COPPER;
    public static final DeferredItem<net.minecraft.world.item.AxeItem> COPPER_AXE = ITEMS.register("copper_axe",
            () -> new net.minecraft.world.item.AxeItem(COPPER, new Item.Properties().attributes(
                    net.minecraft.world.item.AxeItem.createAttributes(COPPER, 6.0F, -3.1F))));
    public static final DeferredItem<net.minecraft.world.item.PickaxeItem> COPPER_PICKAXE = ITEMS.register("copper_pickaxe",
            () -> new net.minecraft.world.item.PickaxeItem(COPPER, new Item.Properties().attributes(
                    net.minecraft.world.item.PickaxeItem.createAttributes(COPPER, 1.0F, -2.8F))));
    public static final DeferredItem<net.minecraft.world.item.ShovelItem> COPPER_SHOVEL = ITEMS.register("copper_shovel",
            () -> new net.minecraft.world.item.ShovelItem(COPPER, new Item.Properties().attributes(
                    net.minecraft.world.item.ShovelItem.createAttributes(COPPER, 1.5F, -3.0F))));
    public static final DeferredItem<net.minecraft.world.item.HoeItem> COPPER_HOE = ITEMS.register("copper_hoe",
            () -> new net.minecraft.world.item.HoeItem(COPPER, new Item.Properties().attributes(
                    net.minecraft.world.item.HoeItem.createAttributes(COPPER, -2.0F, -1.0F))));
    public static final DeferredItem<net.minecraft.world.item.SwordItem> COPPER_SWORD = ITEMS.register("copper_sword",
            () -> new net.minecraft.world.item.SwordItem(COPPER, new Item.Properties().attributes(
                    net.minecraft.world.item.SwordItem.createAttributes(COPPER, 3, -2.4F))));
    public static final DeferredItem<Item> WOOL_COAT = ITEMS.registerSimpleItem("wool_coat",new Item.Properties().durability(7200));
    public static final DeferredItem<Item> LEATHER_COAT = ITEMS.registerSimpleItem("leather_coat",new Item.Properties().durability(7200));
    public static final DeferredItem<com.hkin.exiledsettlement.item.FoundingSuppliesItem> FOUNDING_SUPPLIES = ITEMS.registerItem(
            "founding_supplies", com.hkin.exiledsettlement.item.FoundingSuppliesItem::new, new Item.Properties().stacksTo(1));

    public static final DeferredItem<BlockItem> SETTLEMENT_CORE = ITEMS.registerSimpleBlockItem(ModBlocks.SETTLEMENT_CORE);
    public static final DeferredItem<com.hkin.exiledsettlement.item.StarterCampBlueprintItem> STARTER_CAMP_BLUEPRINT = ITEMS.registerItem(
            "starter_camp_blueprint", com.hkin.exiledsettlement.item.StarterCampBlueprintItem::new, new Item.Properties());
    public static final DeferredItem<BlockItem> USELESS_SETTLEMENT_CORE = ITEMS.registerSimpleBlockItem(
            ModBlocks.USELESS_SETTLEMENT_CORE);
    public static final DeferredItem<BlockItem> EXILE_WAGON = ITEMS.registerSimpleBlockItem(ModBlocks.EXILE_WAGON);
    public static final DeferredItem<BlockItem> HOUSE_MARKER = ITEMS.registerSimpleBlockItem(ModBlocks.HOUSE_MARKER);
    public static final DeferredItem<BlockItem> WAREHOUSE_MARKER = ITEMS.registerSimpleBlockItem(ModBlocks.WAREHOUSE_MARKER);
    public static final DeferredItem<BlockItem> FARMSTEAD_MARKER = ITEMS.registerSimpleBlockItem(ModBlocks.FARMSTEAD_MARKER);
    public static final DeferredItem<BlockItem> PASTURE_MARKER = ITEMS.registerSimpleBlockItem(ModBlocks.PASTURE_MARKER);
    public static final DeferredItem<BlockItem> HUNTING_LODGE_MARKER = ITEMS.registerSimpleBlockItem(ModBlocks.HUNTING_LODGE_MARKER);
    public static final DeferredItem<BlockItem> TOOL_WORKSHOP_MARKER = ITEMS.registerSimpleBlockItem(ModBlocks.TOOL_WORKSHOP_MARKER);
    public static final DeferredItem<BlockItem> FORESTER_LODGE_MARKER = ITEMS.registerSimpleBlockItem(ModBlocks.FORESTER_LODGE_MARKER);
    public static final DeferredItem<BlockItem> TOWN_HALL_MARKER = ITEMS.registerSimpleBlockItem(ModBlocks.TOWN_HALL_MARKER);
    public static final DeferredItem<BlockItem> FISHING_HUT_MARKER = ITEMS.registerSimpleBlockItem(ModBlocks.FISHING_HUT_MARKER);
    public static final DeferredItem<BlockItem> LEGACY_STOCKPILE_MARKER = ITEMS.registerSimpleBlockItem(ModBlocks.LEGACY_STOCKPILE_MARKER);
    public static final DeferredItem<BuildingBlueprintItem> HOUSE_BLUEPRINT = ITEMS.registerItem(
            "house_blueprint",
            properties -> new BuildingBlueprintItem(BuildingType.HOUSE, properties),
            new Item.Properties()
    );
    public static final DeferredItem<BuildingBlueprintItem> WAREHOUSE_BLUEPRINT = ITEMS.registerItem(
            "warehouse_blueprint",
            properties -> new BuildingBlueprintItem(BuildingType.WAREHOUSE, properties),
            new Item.Properties()
    );
    public static final DeferredItem<Item> LEGACY_STOCKPILE_BLUEPRINT = ITEMS.registerItem(
            "stockpile_blueprint",
            Item::new,
            new Item.Properties()
    );
    public static final DeferredItem<Item> CREATIVE_SUPPLY_CORE = ITEMS.registerItem(
            "creative_supply_core",
            Item::new,
            new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)
    );
    public static final DeferredItem<ClaimDeedItem> CLAIM_DEED = ITEMS.registerItem(
            "claim_deed", ClaimDeedItem::new, new Item.Properties()
    );
    public static final DeferredItem<AppointmentDocumentItem> APPOINTMENT_DOCUMENT = ITEMS.registerItem(
            "appointment_document", AppointmentDocumentItem::new, new Item.Properties()
    );

    private ModItems() {
    }
}
