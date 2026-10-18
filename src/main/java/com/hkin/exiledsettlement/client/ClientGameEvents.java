package com.hkin.exiledsettlement.client;

import com.hkin.exiledsettlement.ExiledSettlementMod;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = ExiledSettlementMod.MOD_ID, value = Dist.CLIENT)
public final class ClientGameEvents {
    private ClientGameEvents() {
    }
    @SubscribeEvent public static void blizzardFog(net.neoforged.neoforge.client.event.ViewportEvent.RenderFog event) {
        float power=ClientSeasonController.shelteredStormIntensity();
        if(power<=.01F || !SeasonClientConfig.SCENERY.get())return;
        event.setFarPlaneDistance(Math.min(event.getFarPlaneDistance(),Math.max(18,100-80*power)));
        event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(),2));event.setCanceled(true);
    }
    @SubscribeEvent public static void blizzardFogColor(net.neoforged.neoforge.client.event.ViewportEvent.ComputeFogColor event) {
        float p=ClientSeasonController.shelteredStormIntensity()*.65F;
        if(!SeasonClientConfig.SCENERY.get())return;
        event.setRed(event.getRed()*(1-p)+.78F*p);event.setGreen(event.getGreen()*(1-p)+.83F*p);event.setBlue(event.getBlue()*(1-p)+.89F*p);
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (!ClientPlacementController.active()
                || Minecraft.getInstance().screen != null
                || event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            event.setCanceled(true);
            ClientPlacementController.confirm();
        } else if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            event.setCanceled(true);
            ClientPlacementController.cancel();
        } else if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            event.setCanceled(true);
            ClientPlacementController.cycleFuturePreviewMode();
        }
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (ClientPlacementController.active() && Minecraft.getInstance().screen == null) {
            event.setCanceled(true);
            ClientPlacementController.zoom(event.getScrollDeltaY());
        }
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        ClientPlacementController.freezePlayerInput(event.getInput());
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ProjectionImportScreen.tickUpload();
        ClientPlacementController.tick();
        ClientCitizenInteraction.tick();
        ClientSeasonController.tick();
        ClientWorkTargets.tick();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        ClientPlacementController.renderLevel(event);
        ClientWorkTargets.render(event);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        ClientPlacementController.renderHud(event);
        ClientSeasonController.renderHud(event);
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (ClientPlacementController.active()) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!ClientPlacementController.active()) return;
        if (event.getNewScreen() instanceof PauseScreen) {
            event.setCanceled(true);
            ClientPlacementController.cancel();
        } else if (event.getNewScreen() instanceof InventoryScreen) {
            event.setCanceled(true);
        }
    }
}
