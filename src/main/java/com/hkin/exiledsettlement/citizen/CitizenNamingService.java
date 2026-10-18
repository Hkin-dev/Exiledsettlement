package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.network.OpenCitizenRenamePayload;
import com.hkin.exiledsettlement.settlement.SettlementSavedData;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Intercept before Mob's final interact method applies vanilla name tags to the projection alone. */
public final class CitizenNamingService {
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private CitizenNamingService() {}

    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof SettlementCitizenEntity citizen)
                || !event.getItemStack().is(Items.NAME_TAG)) return;
        event.setCancellationResult(interact(event.getEntity(), citizen, event.getHand()));
        event.setCanceled(true);
    }

    public static InteractionResult interact(Player player, SettlementCitizenEntity entity, InteractionHand hand) {
        if (player.level().isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.FAIL;
        clear(serverPlayer);
        var target = resolve(serverPlayer, entity);
        if (target == null) return fail(serverPlayer, "error.exiledsettlement.no_permission");
        ItemStack tag = player.getItemInHand(hand);
        if (!tag.is(Items.NAME_TAG)) return InteractionResult.FAIL;
        Component label = tag.get(DataComponents.CUSTOM_NAME);
        // Sneaking keeps the anvil-labelled name tag shortcut, with the same record/permission rules.
        if (player.isShiftKeyDown() && label != null) {
            return apply(serverPlayer, entity, target, tag, label.getString())
                    ? InteractionResult.CONSUME : InteractionResult.FAIL;
        }
        UUID token = UUID.randomUUID();
        CitizenInteractionService.begin(serverPlayer, entity);
        SESSIONS.put(player.getUUID(), new Session(token, entity.getUUID(), target.settlement.id(),
                target.citizen.id(), hand, tag.copy(), player.getInventory().selected,
                serverPlayer.serverLevel().getGameTime() + 1200));
        String suggested = label == null ? target.citizen.name() : label.getString();
        suggested = suggested.substring(0, Math.min(64, suggested.length()));
        PacketDistributor.sendToPlayer(serverPlayer, new OpenCitizenRenamePayload(token, target.citizen.name(),
                suggested, target.citizen.canWork() ? target.citizen.profession().translationKey()
                : "citizen_activity.exiledsettlement.growing"));
        return InteractionResult.CONSUME;
    }

    public static boolean confirm(ServerPlayer player, UUID token, String name, boolean cancel) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.token.equals(token)) return false;
        clear(player); // A confirmation is single-use, including invalid or stale submissions.
        if (cancel) return false;
        var entity = player.serverLevel().getEntity(session.entityId);
        if (!(entity instanceof SettlementCitizenEntity projection)
                || player.serverLevel().getGameTime() > session.expires
                || (session.hand == InteractionHand.MAIN_HAND && player.getInventory().selected != session.slot)) {
            fail(player, "error.exiledsettlement.rename_expired");
            return false;
        }
        var target = resolve(player, projection);
        ItemStack held = player.getItemInHand(session.hand);
        if (target == null || !target.settlement.id().equals(session.settlementId)
                || !target.citizen.id().equals(session.citizenId)
                || !held.is(Items.NAME_TAG) || !ItemStack.isSameItemSameComponents(held, session.tag)) {
            fail(player, "error.exiledsettlement.rename_expired");
            return false;
        }
        return apply(player, projection, target, held, name);
    }

    private static Target resolve(ServerPlayer player, SettlementCitizenEntity entity) {
        if (!entity.isAlive() || entity.level() != player.level() || player.distanceToSqr(entity) > 36
                || player.isSpectator()) return null;
        UUID settlementId = CitizenProjectionService.settlementId(entity);
        UUID citizenId = CitizenProjectionService.citizenId(entity);
        if (settlementId == null || citizenId == null) return null;
        var data = SettlementSavedData.get(player.serverLevel());
        var settlement = data.findById(settlementId).orElse(null);
        var citizen = settlement == null ? null : settlement.citizen(citizenId);
        return settlement != null && settlement.active() && settlement.isMember(player.getUUID())
                && citizen != null && citizen.alive() ? new Target(data, settlement, citizen) : null;
    }

    private static boolean apply(ServerPlayer player, SettlementCitizenEntity entity, Target target,
                                 ItemStack tag, String name) {
        if (!target.citizen.rename(name)) {
            fail(player, "error.exiledsettlement.invalid_citizen_name");
            return false;
        }
        CitizenProjectionService.syncName(target.citizen, entity);
        target.data.touch();
        if (!player.getAbilities().instabuild) tag.shrink(1);
        return true;
    }

    private static InteractionResult fail(ServerPlayer player, String message) {
        player.displayClientMessage(Component.translatable(message), true);
        return InteractionResult.FAIL;
    }

    public static UUID pendingToken(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        return session == null ? null : session.token;
    }
    public static void clear(ServerPlayer player) { SESSIONS.remove(player.getUUID()); }
    public static void clearAll() { SESSIONS.clear(); }
    private record Target(SettlementSavedData data, SettlementRecord settlement, CitizenRecord citizen) {}
    private record Session(UUID token, UUID entityId, UUID settlementId, UUID citizenId,
                           InteractionHand hand, ItemStack tag, int slot, long expires) {}
}
