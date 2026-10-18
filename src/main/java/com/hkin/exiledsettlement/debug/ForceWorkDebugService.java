package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Runtime-only adult work override. No inventory, position or world edits. */
public final class ForceWorkDebugService {
    private record Scope(String dimension,UUID settlement) { }
    private static final Map<MinecraftServer,Set<Scope>> ENABLED=new WeakHashMap<>();
    private static final Map<CitizenRecord,Boolean> FORCED=new WeakHashMap<>();
    private ForceWorkDebugService() { }
    public static boolean enabled(ServerLevel level,SettlementRecord s){return ENABLED.getOrDefault(level.getServer(),Set.of()).contains(new Scope(level.dimension().location().toString(),s.id()));}
    public static boolean forced(CitizenRecord c){return c.canWork() && FORCED.getOrDefault(c,false);}
    public static void sync(ServerLevel level,SettlementRecord s){boolean enabled=enabled(level,s);for(var c:s.citizens())if(enabled && c.canWork())FORCED.put(c,true);else FORCED.remove(c);}
    public static void set(ServerLevel level,SettlementRecord s,boolean enabled){
        var scopes=ENABLED.computeIfAbsent(level.getServer(),k->new HashSet<>());var scope=new Scope(level.dimension().location().toString(),s.id());
        if(enabled){scopes.add(scope);ConstructionDebugService.setEnabled(level,s.id(),false);}else scopes.remove(scope);
        if(scopes.isEmpty())ENABLED.remove(level.getServer());sync(level,s);
    }
}
