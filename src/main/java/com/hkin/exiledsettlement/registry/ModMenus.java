package com.hkin.exiledsettlement.registry;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.menu.SettlementCoreMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(
            Registries.MENU,
            ExiledSettlementMod.MOD_ID
    );

    public static final DeferredHolder<MenuType<?>, MenuType<SettlementCoreMenu>> SETTLEMENT_CORE = MENUS.register(
            "settlement_core",
            () -> IMenuTypeExtension.create(SettlementCoreMenu::new)
    );

    public static final DeferredHolder<MenuType<?>, MenuType<com.hkin.exiledsettlement.building.WarehouseDisplayMenu>> WAREHOUSE_DISPLAY = MENUS.register(
            "warehouse_display", () -> IMenuTypeExtension.create(com.hkin.exiledsettlement.building.WarehouseDisplayMenu::new));

    private ModMenus() {
    }
}
