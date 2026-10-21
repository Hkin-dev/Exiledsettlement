package com.hkin.exiledsettlement.region;

import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.citizen.CitizenWorkService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;
import java.util.*;

/** Persistent ownership with temporary, bounded return navigation for escaped animals. */
public final class PastureResidentService {
    private PastureResidentService() {}
    private static final String SETTLEMENT="exiledsettlement.pastureSettlement", BUILDING="exiledsettlement.pastureBuilding";
    private static final Map<Animal, StayGoal> CONTROLLED=new IdentityHashMap<>();
    public static long pathRequests;

    public static boolean hasOwnership(Animal animal) {
        var tag=animal.getPersistentData();
        return tag.hasUUID(SETTLEMENT) || tag.hasUUID(BUILDING);
    }

    public static boolean ownedBy(SettlementRecord settlement,BuildingRecord building,Animal animal) {
        var tag=animal.getPersistentData();
        return tag.hasUUID(SETTLEMENT) && tag.hasUUID(BUILDING)
                && settlement.id().equals(tag.getUUID(SETTLEMENT)) && building.id().equals(tag.getUUID(BUILDING));
    }

    /** An unmarked animal is not automatically public when a player is keeping or leading it. */
    public static boolean eligibleForClaim(Animal animal) {
        return animal.isAlive() && !animal.isRemoved() && !hasOwnership(animal)
                && !animal.hasCustomName() && !animal.isLeashed() && !animal.isPassenger() && !animal.isVehicle()
                && !animal.isNoAi() && (!(animal instanceof TamableAnimal tame) || !tame.isTame());
    }

    public static void capture(ServerLevel level,SettlementRecord s,BuildingRecord b,Animal a) {
        if(b.type()!=BuildingType.PASTURE_AREA || !ProductionRegionService.matchesAnimal(b,a))return;
        if(!ownedBy(s,b,a)) {
            if(!eligibleForClaim(a) || !inside(b,a.blockPosition()))return;
            a.getPersistentData().putUUID(SETTLEMENT,s.id());
            a.getPersistentData().putUUID(BUILDING,b.id());
        }
        attach(level,s,b,a);
    }
    private static void attach(ServerLevel level,SettlementRecord s,BuildingRecord b,Animal a) {
        var old=CONTROLLED.get(a);
        if(old!=null && old.settlement==s && old.building==b)return;
        release(a);
        if(!s.active() || !b.active() || !b.operational() || b.demolitionQueued())return;
        var goal=new StayGoal(level,s,b,a);
        CONTROLLED.put(a,goal);
        // Panic, mating, temptation and following a parent keep their native priorities.
        a.goalSelector.addGoal(5,goal);
    }
    public static void reconcile(ServerLevel level,SettlementRecord s,BuildingRecord b) {
        if(!s.active() || !b.active() || !b.operational() || b.demolitionQueued()
                || Math.floorMod(level.getGameTime()/10+b.id().hashCode(),20)!=0
                || PastureGroundSafety.active(b.region()))return;
        var area=new AABB(b.minCorner()).minmax(new AABB(b.maxCorner())).inflate(0,3,0);
        for(var a:level.getEntitiesOfClass(Animal.class,area,a->a.isAlive() && inside(b,a.blockPosition())
                && ProductionRegionService.matchesAnimal(b,a))) {
            if(eligibleForClaim(a) || ownedBy(s,b,a))capture(level,s,b,a);
        }
    }
    public static void tick(Animal a) {
        com.hkin.exiledsettlement.citizen.PastureCaptureService.reconcile(a);
        if(!(a.level() instanceof ServerLevel level) || (a.tickCount+a.getId())%40!=0)return;
        var tag=a.getPersistentData();
        if(!tag.hasUUID(SETTLEMENT)||!tag.hasUUID(BUILDING)){release(a);return;}
        var s=SettlementSavedData.get(level).findById(tag.getUUID(SETTLEMENT)).orElse(null);
        var b=s==null?null:s.building(tag.getUUID(BUILDING));
        // A paused settlement or workplace still owns its animals. Only disappearance
        // of the registered owner releases the persistent claim.
        if(s==null || b==null || b.type()!=BuildingType.PASTURE_AREA) {
            release(a);
            tag.remove(SETTLEMENT);tag.remove(BUILDING);
            return;
        }
        if(!s.active() || !b.active() || !b.operational() || b.demolitionQueued()
                || !ProductionRegionService.matchesAnimal(b,a)) {
            release(a);
            return;
        }
        attach(level,s,b,a);
    }
    public static boolean controls(Animal a){var goal=CONTROLLED.get(a);return goal!=null && goal.running && goal.returnAllowed();}
    /** Entity unload and server stop remove only transient goals, never the saved owner. */
    public static void release(Animal a){
        var goal=CONTROLLED.remove(a);
        if(goal!=null){
            boolean wasRunning=goal.running;
            a.goalSelector.removeGoal(goal);
            if(wasRunning)goal.stop();
        }
    }
    public static void releaseAll(){for(var a:List.copyOf(CONTROLLED.keySet()))release(a);}
    public static boolean inside(BuildingRecord b,BlockPos p) {
        if(b.productionSite()!=null){
            var cell=b.productionSite().cell(p);
            return cell!=null && (cell.use()==ProductionRegion.Use.PASTURE || cell.use()==ProductionRegion.Use.PATH)
                    && Math.abs(p.getY()-cell.ground().getY()-1)<2;
        }
        if(b.region()!=null) {
            var cell=b.region().cell(p);
            return cell!=null && (cell.use()==ProductionRegion.Use.PATH
                    || cell.use()==ProductionRegion.Use.PASTURE && b.region().readyPasture.contains(cell.ground().asLong()))
                    && Math.abs(p.getY()-cell.ground().getY()-1)<2;
        }
        return p.getX()>b.minCorner().getX() && p.getX()<b.maxCorner().getX()
                && p.getZ()>b.minCorner().getZ() && p.getZ()<b.maxCorner().getZ()
                && p.getY()>=b.minCorner().getY() && p.getY()<=b.maxCorner().getY()+2;
    }
    private static final class StayGoal extends Goal {
        final ServerLevel level;final SettlementRecord settlement;final BuildingRecord building;final Animal animal;
        long retry;int failures;boolean running;
        StayGoal(ServerLevel l,SettlementRecord s,BuildingRecord b,Animal a) {
            level=l;settlement=s;building=b;animal=a;setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK));
        }
        boolean returnAllowed() {
            return animal.isAlive() && !animal.isRemoved() && settlement.active() && building.active() && building.operational()
                    && !building.demolitionQueued() && ownedBy(settlement,building,animal)
                    && !PastureGroundSafety.active(building.region()) && !animal.isLeashed() && !animal.isPassenger()
                    && !animal.isVehicle()
                    && !animal.isNoAi() && !animal.isInWaterOrBubble() && !animal.isOnFire() && animal.hurtTime==0
                    && !animal.isInLove() && !animal.hasCustomName()
                    && (!(animal instanceof TamableAnimal tame) || !tame.isTame())
                    && ProductionRegionService.matchesAnimal(building,animal)
                    && !inside(building,animal.blockPosition());
        }
        @Override public boolean canUse() {
            return returnAllowed() && level.getGameTime()>=retry;
        }
        @Override public boolean canContinueToUse(){
            return returnAllowed() && !animal.getNavigation().isDone();
        }
        @Override public void start(){
            running=true;
            animal.getNavigation().stop();
            requestReturnPath();
        }
        @Override public void stop(){
            if(running)animal.getNavigation().stop();
            running=false;
        }
        @Override public void tick() {
            if(!returnAllowed()) {
                stop();
                if(inside(building,animal.blockPosition())){failures=0;retry=0;}
                return;
            }
            if(level.getGameTime()>=retry)requestReturnPath();
        }
        private void requestReturnPath() {
            var target=CitizenWorkService.pastureCaptureDestination(level,building,animal.blockPosition());
            boolean reached=false;
            if(target!=null && level.hasChunkAt(target)) {
                pathRequests++;
                reached=animal.getNavigation().moveTo(target.getX()+.5,target.getY(),target.getZ()+.5,1.0);
            }
            failures=reached?0:Math.min(3,failures+1);
            retry=level.getGameTime()+(reached?80:40L<<failures);
        }
    }
}
