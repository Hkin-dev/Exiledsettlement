package com.hkin.exiledsettlement.citizen;

import net.minecraft.world.level.Level;
import java.util.*;

/** Shared ordinary navigation search admission, independent of resident scheduling. */
public final class NavigationSearchBudget {
    private static final Map<Level,Slice> LEVELS=new WeakHashMap<>();
    private static final Map<SettlementCitizenEntity,Long> WATCHERS=new WeakHashMap<>();
    static void watch(SettlementCitizenEntity entity){WATCHERS.putIfAbsent(entity,Long.MIN_VALUE/2);}
    public static void blockChanged(Level level,net.minecraft.core.BlockPos p){
        long now=level.getGameTime();
        for(var e:WATCHERS.entrySet()){
            var mob=e.getKey();if(mob.level()!=level || !mob.isAlive() || now-e.getValue()<20)continue;
            var at=mob.navigationFeetPosition();
            if(Math.abs(p.getX()-at.getX())<=32 && Math.abs(p.getZ()-at.getZ())<=32 && Math.abs(p.getY()-at.getY())<=8){
                if(mob.navigationGeometryChanged(now))e.setValue(now);
            }
        }
    }
    private static final class Slice { long tick=Long.MIN_VALUE;int searches; }
    static boolean acquire(Level level){
        var slice=LEVELS.computeIfAbsent(level,k->new Slice());long now=level.getGameTime();
        if(slice.tick!=now){slice.tick=now;slice.searches=0;}
        if(slice.searches>=32)return false;
        slice.searches++;return true;
    }
    private NavigationSearchBudget(){ }
}
