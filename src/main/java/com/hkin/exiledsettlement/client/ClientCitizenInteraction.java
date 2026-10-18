package com.hkin.exiledsettlement.client;

import com.hkin.exiledsettlement.network.CitizenInteractionPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ClientCitizenInteraction {
    private static boolean previous;
    private static int ticks;
    private ClientCitizenInteraction() { }
    public static void tick() {
        var client = Minecraft.getInstance();
        if (client.player == null || client.getConnection() == null) { previous = false; ticks = 0; return; }
        boolean active = client.screen instanceof CitizenAssignmentScreen
                || client.screen instanceof CitizenRenameScreen || client.screen instanceof CitizenYsmModelScreen;
        if (active && client.mouseHandler.isMouseGrabbed()) client.mouseHandler.releaseMouse();
        if (active != previous || active && ++ticks >= 20) {
            PacketDistributor.sendToServer(new CitizenInteractionPayload(active));
            previous = active; ticks = 0;
        }
    }
}
