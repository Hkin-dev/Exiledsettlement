package com.hkin.exiledsettlement.region;

import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.citizen.CitizenWorkService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;
import java.util.*;

/** Persistent pasture ownership, transient AI control, bounded native navigation. */
public final class PastureResidentService {
    private PastureResidentService() {}
    private static final String SETTLEMENT="exiledsettlement.pastureSettlement", BUILDING="exiledsettlement.pastureBuilding";
    private static final Map<Animal, StayGoal> CONTROLLED=new IdentityHashMap<>();
    public static long pathRequests;
    public static void capture(ServerLevel level,SettlementRecord s,BuildingRecord b,Animal a) {
        a.getPersistentData().putUUID(SETTLEMENT,s.id());a.getPersistentData().putUUID(BUILDING,b.id());attach(level,s,b,a);
    }
    private static void attach(ServerLevel level,SettlementRecord s,BuildingRecord b,Animal a) {
        var old=CONTROLLED.get(a);if(old!=null && old.building==b)return;
        release(a);var goal=new StayGoal(level,s,b,a);CONTROLLED.put(a,goal);a.goalSelector.addGoal(-2,goal);
    }
    public static void reconcile(ServerLevel level,SettlementRecord s,BuildingRecord b) {
        if(Math.floorMod(level.getGameTime()/10+b.id().hashCode(),20)!=0 || PastureGroundSafety.active(b.region()))return;
        var area=new AABB(b.minCorner()).minmax(new AABB(b.maxCorner())).inflate(0,3,0);
        for(var a:level.getEntitiesOfClass(Animal.class,area,a->a.isAlive() && inside(b,a.blockPosition())
                && ProductionRegionService.matchesAnimal(b,a))) {
            if(!a.getPersistentData().hasUUID(BUILDING))capture(level,s,b,a);
        }
    }
    public static void tick(Animal a) {
        if(!(a.level() instanceof ServerLevel level) || (a.tickCount+a.getId())%40!=0)return;
        var tag=a.getPersistentData();if(!tag.hasUUID(SETTLEMENT)||!tag.hasUUID(BUILDING))return;
        var s=SettlementSavedData.get(level).findById(tag.getUUID(SETTLEMENT)).orElse(null);
        var b=s==null?null:s.building(tag.getUUID(BUILDING));
        if(s==null || !s.active() || b==null || !b.active() || b.demolitionQueued()) {
            release(a);tag.remove(SETTLEMENT);tag.remove(BUILDING);return;
        }
        attach(level,s,b,a);
    }
    public static boolean controls(Animal a){var goal=CONTROLLED.get(a);return goal!=null && goal.canUse();}
    public static void release(Animal a){var goal=CONTROLLED.remove(a);if(goal!=null)a.goalSelector.removeGoal(goal);}
    public static void releaseAll(){for(var a:List.copyOf(CONTROLLED.keySet()))release(a);}
    public static boolean inside(BuildingRecord b,BlockPos p) {
        if(b.productionSite()!=null){
            var cell=b.productionSite().cell(p);
            return cell!=null && cell.use()==ProductionRegion.Use.PASTURE && Math.abs(p.getY()-cell.ground().getY()-1)<2;
        }
        if(b.region()!=null) {
            var cell=b.region().cell(p);
            return cell!=null && cell.use()==ProductionRegion.Use.PASTURE && b.region().readyPasture.contains(cell.ground().asLong())
                    && Math.abs(p.getY()-cell.ground().getY()-1)<2;
        }
        return p.getX()>b.minCorner().getX() && p.getX()<b.maxCorner().getX()
                && p.getZ()>b.minCorner().getZ() && p.getZ()<b.maxCorner().getZ()
                && p.getY()>=b.minCorner().getY() && p.getY()<=b.maxCorner().getY()+2;
    }
    private static final class StayGoal extends Goal {
        final ServerLevel level;final SettlementRecord settlement;final BuildingRecord building;final Animal animal;
        long retry;int failures;
        StayGoal(ServerLevel l,SettlementRecord s,BuildingRecord b,Animal a) {
            level=l;settlement=s;building=b;animal=a;setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK));
        }
        @Override public boolean canUse() {
            return animal.isAlive() && !animal.isRemoved() && settlement.active() && building.active() && building.operational()
                    && !PastureGroundSafety.active(building.region()) && !animal.isLeashed() && !animal.isPassenger()
                    && !animal.isNoAi() && !animal.isInWaterOrBubble() && !animal.isOnFire() && animal.hurtTime==0
                    && !animal.isInLove();
        }
        @Override public boolean canContinueToUse(){return canUse();}
        @Override public void start(){animal.getNavigation().stop();retry=0;}
        @Override public void stop(){animal.getNavigation().stop();}
        @Override public void tick() {
            if(inside(building,animal.blockPosition())) {
                if(!animal.getNavigation().isDone())animal.getNavigation().stop();failures=0;return;
            }
            long now=level.getGameTime();if(now<retry)return;
            // One real path request, with backoff for a shut gate or broken enclosure.
            var target=CitizenWorkService.pastureCaptureDestination(level,building,animal.blockPosition());
            boolean reached=false;
            if(target!=null && level.hasChunkAt(target)) {
                pathRequests++;
                reached=animal.getNavigation().moveTo(target.getX()+.5,target.getY(),target.getZ()+.5,1.0);
            }
            failures=reached?0:Math.min(3,failures+1);retry=now+(reached?80:40L<<failures);
        }
    }
}
