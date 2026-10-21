package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** A resident interaction has a real position and a cancellable protection event. */
public final class ResidentAnimalInteraction {
    private ResidentAnimalInteraction() {}

    public static boolean allowed(ServerLevel level, SettlementRecord settlement,
                                  CitizenRecord citizen, SettlementCitizenEntity worker,
                                  Animal animal, ItemStack tool) {
        if (!animal.isAlive() || !level.hasChunkAt(animal.blockPosition())
                || !settlement.contains(animal.blockPosition())
                || worker.distanceToSqr(animal) > 6.25D || !worker.hasLineOfSight(animal)) return false;
        var actor = FakePlayerFactory.get(level, new GameProfile(citizen.id(), "[PastureWorker]"));
        actor.setPos(worker.position());
        actor.setItemInHand(InteractionHand.MAIN_HAND, tool.copy());
        try {
            return !NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(
                    actor, InteractionHand.MAIN_HAND, animal)).isCanceled();
        } finally {
            actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }
}
