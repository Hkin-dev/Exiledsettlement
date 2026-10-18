package com.hkin.exiledsettlement.client;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import com.hkin.exiledsettlement.registry.ModEntities;
import com.hkin.exiledsettlement.registry.ModMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = ExiledSettlementMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientModEvents {
    private ClientModEvents() {
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.SETTLEMENT_CORE.get(), SettlementCoreScreen::new);
        event.register(ModMenus.WAREHOUSE_DISPLAY.get(), WarehouseDisplayScreen::new);
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ClientPlacementController.ROTATE_KEY);
        event.register(ClientPlacementController.HEIGHT_UP_KEY);
        event.register(ClientPlacementController.HEIGHT_DOWN_KEY);
    }

    @SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.SETTLEMENT_CITIZEN.get(), SettlementCitizenRenderer::new);
    }
}
