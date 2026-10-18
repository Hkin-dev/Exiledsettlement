package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.network.WorkTargetsPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import java.util.*;

/** Per-observer temporary diagnostics, never persisted or used for dispatch. */
public final class WorkTargetDebugService {
    private WorkTargetDebugService() {}
    private record Viewer(UUID settlement,boolean workers) {}
    private static final Map<MinecraftServer,Map<UUID,Viewer>> VIEWERS=new WeakHashMap<>();
    public static String status(ServerPlayer p){var v=VIEWERS.getOrDefault(p.server,Map.of()).get(p.getUUID());return v==null?"已关闭":v.workers?"仅工人":"全部居民";}
    public static void set(ServerPlayer p,SettlementRecord s,boolean enabled,boolean workers) {
        var viewers=VIEWERS.computeIfAbsent(p.server,k->new HashMap<>());
        if(enabled)viewers.put(p.getUUID(),new Viewer(s.id(),workers));else viewers.remove(p.getUUID());
        PacketDistributor.sendToPlayer(p,enabled?snapshot(p.serverLevel(),s,p.position(),workers):new WorkTargetsPayload(false,List.of()));
    }
    public static void clear(ServerPlayer p){var viewers=VIEWERS.get(p.server);if(viewers!=null)viewers.remove(p.getUUID());}
    public static void shutdown(MinecraftServer server){VIEWERS.remove(server);}
    public static void tick(MinecraftServer server) {
        if(server.getTickCount()%10!=0)return;
        var viewers=VIEWERS.get(server);if(viewers==null || viewers.isEmpty())return;
        for(var entry:List.copyOf(viewers.entrySet())) {
            var p=server.getPlayerList().getPlayer(entry.getKey());
            if(p==null){viewers.remove(entry.getKey());continue;}
            var s=SettlementSavedData.get(p.serverLevel()).findById(entry.getValue().settlement).orElse(null);
            if(!p.hasPermissions(2) || s==null || !s.active()){set(p,null,false,false);continue;}
            PacketDistributor.sendToPlayer(p,snapshot(p.serverLevel(),s,p.position(),entry.getValue().workers));
        }
    }
    public static WorkTargetsPayload snapshot(ServerLevel level,SettlementRecord s,Vec3 viewer,boolean workers) {
        var targets=new ArrayList<WorkTargetsPayload.Target>();
        for(var c:s.citizens()) {
            if(!c.alive() || workers && c.profession()!=CitizenProfession.WORKER)continue;
            var actor=CitizenProjectionService.findProjection(level,c);if(actor==null || actor.distanceToSqr(viewer)>256*256)continue;
            var target=target(level,c,actor);if(target==null)continue;
            targets.add(new WorkTargetsPayload.Target(actor.getId(),target,c.activity()==CitizenActivity.BLOCKED || !c.workFailure().isEmpty()));
            if(targets.size()==WorkTargetsPayload.MAX_TARGETS)break;
        }
        return new WorkTargetsPayload(true,targets);
    }
    @Nullable public static Vec3 target(ServerLevel level,CitizenRecord c,SettlementCitizenEntity actor) {
        if(c.targetItemEntityId()!=null){var e=level.getEntity(c.targetItemEntityId());if(e!=null && e.isAlive())return e.position().add(0,e.getBbHeight()*.5,0);}
        // Navigation intent includes the actual supply station or detour destination, not just the parent worksite.
        var moving=actor.currentNavigationDestination();
        if(moving!=null)return Vec3.atCenterOf(moving);
        return c.workState()!=CitizenWorkState.IDLE && c.targetPosition()!=null?Vec3.atCenterOf(c.targetPosition()):null;
    }
}
