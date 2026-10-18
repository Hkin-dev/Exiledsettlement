package com.hkin.exiledsettlement.citizen;

import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** A temporary, server-authorized conversation. Never saved as resident work or AI state. */
public final class CitizenInteractionService {
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, Set<UUID>> VIEWERS = new HashMap<>();
    private CitizenInteractionService() { }

    public static void begin(ServerPlayer player, SettlementCitizenEntity entity) {
        clear(player);
        if (entity == null || !entity.isAlive() || player.isSpectator()
                || player.level() != entity.level() || player.distanceToSqr(entity) > 36) return;
        var session = new Session(player, entity, entity.level().getGameTime() + 100);
        SESSIONS.put(player.getUUID(), session);
        VIEWERS.computeIfAbsent(entity.getUUID(), ignored -> new HashSet<>()).add(player.getUUID());
        entity.stopSettlementNavigation();
    }

    public static void renew(ServerPlayer player, boolean active) {
        if (!active) { clear(player); return; }
        // A client may keep its already-open conversation alive, never create one remotely.
        var session = SESSIONS.get(player.getUUID());
        if (session != null) session.expires = player.serverLevel().getGameTime() + 100;
    }

    public static boolean held(SettlementCitizenEntity entity) {
        if (entity.level().isClientSide) return false;
        var viewers = VIEWERS.get(entity.getUUID());
        if (viewers == null) return false;
        boolean held = false;
        for (UUID id : List.copyOf(viewers)) {
            var session = SESSIONS.get(id);
            if (session == null) { viewers.remove(id); continue; }
            var player = session.player;
            if (session.entity != entity || !entity.isAlive() || !player.isAlive() || player.hasDisconnected()
                    || player.level() != entity.level() || player.distanceToSqr(entity) > 64
                    || entity.level().getGameTime() > session.expires) clear(player);
            else held = true;
        }
        return held;
    }

    public static void clear(ServerPlayer player) {
        var session = SESSIONS.remove(player.getUUID());
        if (session == null) return;
        var viewers = VIEWERS.get(session.entity.getUUID());
        if (viewers != null) {
            viewers.remove(player.getUUID());
            if (viewers.isEmpty()) VIEWERS.remove(session.entity.getUUID());
        }
    }

    public static void clearAll() { SESSIONS.clear(); VIEWERS.clear(); }
    public static void updateThermal(SettlementCitizenEntity entity,CitizenRecord citizen) {
        if(!held(entity))return;
        for(UUID id:List.copyOf(VIEWERS.getOrDefault(entity.getUUID(),Set.of()))) {
            var s=SESSIONS.get(id);if(s!=null)net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(s.player,
                    com.hkin.exiledsettlement.network.CitizenThermalPayload.of(citizen));
        }
    }
    private static final class Session {
        final ServerPlayer player;
        final SettlementCitizenEntity entity;
        long expires;
        Session(ServerPlayer player, SettlementCitizenEntity entity, long expires) {
            this.player = player; this.entity = entity; this.expires = expires;
        }
    }
}
