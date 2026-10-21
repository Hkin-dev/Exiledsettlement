package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.blueprint.BlueprintRecord;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import java.util.*;

/** Runtime-only construction queue, isolated from general construction_force. */
public final class TerrainEvolveDebugService {
    private record Key(ResourceKey<Level> dimension, UUID settlement) {}
    private static final class State { int multiplier, cursor; long steps; }
    private static final Map<MinecraftServer,Map<Key,State>> VALUES=new WeakHashMap<>();
    private TerrainEvolveDebugService() {}
    private static synchronized State state(ServerLevel level,SettlementRecord s) {
        return VALUES.getOrDefault(level.getServer(),Map.of()).get(new Key(level.dimension(),s.id()));
    }
    public static int multiplier(ServerLevel level,SettlementRecord s) { var value=state(level,s);return value==null?0:value.multiplier; }
    public static long steps(ServerLevel level,SettlementRecord s) { var value=state(level,s);return value==null?0:value.steps; }
    public static synchronized void set(ServerLevel level,SettlementRecord s,int multiplier) {
        if(multiplier<0 || multiplier>8)throw new IllegalArgumentException("Terrain evolution multiplier must be 0..8");
        var values=VALUES.computeIfAbsent(level.getServer(),ignored->new HashMap<>());var key=new Key(level.dimension(),s.id());
        if(multiplier==0)values.remove(key);else values.computeIfAbsent(key,ignored->new State()).multiplier=multiplier;
        if(values.isEmpty())VALUES.remove(level.getServer());
    }
    public static boolean handles(ServerLevel level,SettlementRecord s,BlueprintRecord site) {
        return site!=null && multiplier(level,s)>0;
    }
    public static boolean authorizes(ServerLevel level,SettlementRecord s,BlueprintRecord site) {
        // Each engineering service retains its own pause, phase and safety guards.
        return running(level,s) && handles(level,s,site);
    }
    public static boolean terrainRunning(ServerLevel level,SettlementRecord s) {
        return running(level,s) && s.terrain().enabled && !s.terrain().publicPaused;
    }
    public static boolean running(ServerLevel level,SettlementRecord s) {
        return multiplier(level,s)>0 && s.active()
                && !s.community().evacuation && level.hasChunkAt(s.corePosition());
    }
    /** Round-robin bounded real-order execution; blocked columns never hide all later columns. */
    public static void tick(ServerLevel level,SettlementRecord s,long deadline) {
        if(!running(level,s))return;
        var state=state(level,s);
        var jobs=new ArrayList<java.util.function.BooleanSupplier>();
        for(var site:List.copyOf(s.blueprints()))jobs.add(()->{
            if(s.blueprint(site.id())!=site)return false;
            if(site.cancellationQueued())return ConstructionForceWork.cleanup(level,s,site);
            var result=com.hkin.exiledsettlement.blueprint.BlueprintService.debugAdvanceOne(level,
                    com.hkin.exiledsettlement.settlement.SettlementSavedData.get(level),s,site);
            return result.advanced() || result.completed();
        });
        jobs.addAll(ConstructionForceWork.otherJobs(level,s));
        if(jobs.isEmpty())return;
        int idle=0;
        for(int n=0;n<32*state.multiplier && (n==0 || System.nanoTime()<deadline);n++) {
            var job=jobs.get(Math.floorMod(state.cursor++,jobs.size()));
            if(job.getAsBoolean()){state.steps++;idle=0;}else if(++idle>=jobs.size())break;
        }
    }
}
