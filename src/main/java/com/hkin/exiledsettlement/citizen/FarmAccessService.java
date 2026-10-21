package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.FenceGateBlock;
import java.util.*;

/** Stage an enclosed field exit before a distant road segment; never teleport or remove a fence. */
public final class FarmAccessService {
    private record Exit(UUID farm,BlockPos gate,Direction outward,BlockPos inside,BlockPos outside,BlockPos target,long expires) { }
    private static final Map<SettlementCitizenEntity,Exit> EXITS=new WeakHashMap<>();
    private static final Set<SettlementCitizenEntity> CROSSING=Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<SettlementCitizenEntity,Map<BlockPos,Long>> FAILED_GATES=new WeakHashMap<>();
    private record Shore(BlockPos position,BlockPos target,long expires) { }
    private static final Map<SettlementCitizenEntity,Shore> SHORES=new WeakHashMap<>();
    private static final Map<SettlementCitizenEntity,Map<BlockPos,Long>> FAILED_SHORES=new WeakHashMap<>();
    public static void failedWaypoint(SettlementCitizenEntity entity) {
        var exit=EXITS.remove(entity);CROSSING.remove(entity);
        if(exit!=null)FAILED_GATES.computeIfAbsent(entity,e->new HashMap<>()).put(exit.gate(),entity.level().getGameTime()+1200);
        var shore=SHORES.remove(entity);
        if(shore!=null && shore.position()!=null)FAILED_SHORES.computeIfAbsent(entity,e->new HashMap<>())
                .put(shore.position(),entity.level().getGameTime()+1200);
    }
    private static final Map<SettlementCitizenEntity,Long> REQUESTED = new WeakHashMap<>();
    private FarmAccessService(){ }
    private static boolean enclosure(BuildingRecord b) {
        return b.type()==BuildingType.FARM_FIELD || b.type()==BuildingType.MELON_FIELD
                || b.type()==BuildingType.TREE_FARM || b.type()==BuildingType.PASTURE_AREA;
    }
    private static boolean inside(BuildingRecord b,BlockPos p) {
        if(b.productionSite()!=null)return b.productionSite().containsColumn(p);
        if(b.region()!=null)return b.region().cell(p)!=null;
        return p.getX()>b.minCorner().getX() && p.getX()<b.maxCorner().getX()
                && p.getZ()>b.minCorner().getZ() && p.getZ()<b.maxCorner().getZ()
                && p.getY()>=b.minCorner().getY()-1 && p.getY()<=b.maxCorner().getY()+3;
    }
    /** A short-lived request exists only while a real resident approaches/crosses this entrance. */
    public static SettlementCitizenEntity requestingActor(ServerLevel level,BuildingRecord b,BlockPos gate) {
        for(var entry:EXITS.entrySet()) {
            var e=entry.getKey();var exit=entry.getValue();
            if(e.isAlive() && e.level()==level && exit.farm().equals(b.id())
                    && REQUESTED.getOrDefault(e,0L)>=level.getGameTime()
                    && gate.distManhattan(exit.gate())<=1
                    && e.distanceToSqr(exit.gate().getX()+.5,exit.gate().getY(),exit.gate().getZ()+.5)<=9)
                return e;
        }
        return null;
    }
    public static void updateGates(ServerLevel level,SettlementRecord settlement) {
        for(var b:settlement.buildings()) {
            if(!enclosure(b) || b.type()==BuildingType.PASTURE_AREA)continue;
            for(var p:BlueprintTemplates.createForBuilding(b).gatePlacements()) {
                var actor=requestingActor(level,b,p.position());
                FenceGateAccessService.change(level,settlement,p.position(),actor!=null,actor);
            }
        }
    }
    public static BlockPos waypoint(SettlementCitizenEntity entity,BlockPos target){
        if(!(entity.level() instanceof ServerLevel level))return target;
        var id=CitizenProjectionService.settlementId(entity);if(id==null)return target;
        var s=SettlementSavedData.get(level).findById(id).orElse(null);if(s==null)return target;
        var feet=entity.navigationFeetPosition();
        if((entity.isInWater() || SHORES.containsKey(entity) && !entity.onGround()) && level.getFluidState(target).isEmpty()) {
            var shore=SHORES.get(entity);
            if(shore==null || !shore.target().equals(target) || shore.expires()<=level.getGameTime() || shore.position()!=null && !entity.canStandAtPosition(shore.position())) {
                // A reachable dry destination is already a valid shore. Do not replace a
                // deliberate crossing with the closest bank behind the resident.
                if(entity.canStandAtPosition(target) && level.getFluidState(target).isEmpty()
                        && level.getFluidState(target.below()).isEmpty() && entity.hasReachablePathTo(target)) {
                    SHORES.put(entity,new Shore(target.immutable(),target.immutable(),level.getGameTime()+1200));return target;
                }
                var candidates=new ArrayList<BlockPos>();
                for(int dx=-12;dx<=12;dx++)for(int dz=-12;dz<=12;dz++)if(dx*dx+dz*dz<=144) {
                    int x=feet.getX()+dx,z=feet.getZ()+dz;
                    var at=new BlockPos(x,level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z),z);
                    if(at.getY()<=feet.getY()+1 && level.hasChunkAt(at) && entity.canStandAtPosition(at) && level.getFluidState(at).isEmpty()
                            && level.getFluidState(at.below()).isEmpty())candidates.add(at);
                }
                var failed=FAILED_SHORES.computeIfAbsent(entity,e->new HashMap<>());
                failed.entrySet().removeIf(e->e.getValue()<=level.getGameTime());
                var at=candidates.stream().filter(p->!failed.containsKey(p)).sorted(Comparator.<BlockPos>comparingInt(p->p.distSqr(target)<feet.distSqr(target)?0:1)
                        .thenComparingDouble(p->p.distSqr(target)).thenComparingDouble(p->p.distSqr(feet)))
                        .filter(entity::hasReachablePathTo).findFirst().orElse(null);
                shore=new Shore(at,target.immutable(),level.getGameTime()+1200);SHORES.put(entity,shore);
            }
            if(shore.position()!=null)return shore.position();
        }else SHORES.remove(entity);
        var exit=EXITS.get(entity);
        if(exit!=null && (!exit.target().equals(target) || level.getGameTime()>exit.expires()
                || !(level.getBlockState(exit.gate()).getBlock() instanceof FenceGateBlock))) {
            EXITS.remove(entity);CROSSING.remove(entity);exit=null;
        }
        if(exit!=null && CROSSING.contains(entity)
                && entity.distanceToSqr(exit.outside().getX()+.5,exit.outside().getY(),exit.outside().getZ()+.5)<=0.64) {
            EXITS.remove(entity);CROSSING.remove(entity);REQUESTED.remove(entity);return target;
        }
        var farm=exit==null?s.buildings().stream().filter(b->enclosure(b)
                && inside(b,feet)!=inside(b,target)).findFirst().orElse(null):s.building(exit.farm());
        if(farm==null) {EXITS.remove(entity);CROSSING.remove(entity);REQUESTED.remove(entity);return target;}
        // Capture has its own worker-and-animal landing contract. Do not replace it.
        var record=s.citizen(CitizenProjectionService.citizenId(entity));
        if(farm.type()==BuildingType.PASTURE_AREA && record!=null && record.workState()==CitizenWorkState.TO_CAPTURE)
            return target;
        if(exit==null) {
            Exit best=null;double score=Double.MAX_VALUE;
            boolean leaving=inside(farm,feet);
            var failed=FAILED_GATES.computeIfAbsent(entity,e->new HashMap<>());
            failed.entrySet().removeIf(e->e.getValue()<=level.getGameTime());
            for(var entry:BlueprintTemplates.createForBuilding(farm).entrances()) {
                var gate=entry.position();
                if(failed.containsKey(gate) || !level.hasChunkAt(gate)
                        || !(level.getBlockState(gate).getBlock() instanceof FenceGateBlock))continue;
                var travel=leaving?entry.outward():entry.outward().getOpposite();
                var near=standing(entity,gate.relative(travel.getOpposite()));
                var far=standing(entity,gate.relative(travel,2));
                if(far==null)far=standing(entity,gate.relative(travel));
                // Find a path on our side of the CLOSED gate, not through it.
                if(near==null || far==null || Math.abs(near.getY()-gate.getY())>1
                        || Math.abs(far.getY()-gate.getY())>1 || !entity.hasReachablePathTo(near))continue;
                double cost=Math.sqrt(near.distSqr(feet))+Math.sqrt(far.distSqr(target));
                if(cost<score) {score=cost;best=new Exit(farm.id(),gate,travel,near,far,target.immutable(),level.getGameTime()+1200);}
            }
            if(best==null)return target;
            exit=best;EXITS.put(entity,exit);CROSSING.remove(entity);
        }
        if(entity.distanceToSqr(exit.gate().getX()+.5,exit.gate().getY(),exit.gate().getZ()+.5)<=9) {
            REQUESTED.put(entity,level.getGameTime()+20);
            boolean opened=false;
            for(var p:BlueprintTemplates.createForBuilding(farm).gatePlacements())
                if(p.position().distManhattan(exit.gate())<=1) {
                    boolean ok=FenceGateAccessService.change(level,s,p.position(),true,entity);
                    if(p.position().equals(exit.gate()))opened=ok;
                }
            if(opened)CROSSING.add(entity);
        }
        return CROSSING.contains(entity)?exit.outside():exit.inside();
    }
    private static BlockPos standing(SettlementCitizenEntity e,BlockPos hint){
        for(int dy:new int[]{0,-1,1}){var p=hint.offset(0,dy,0);if(e.level().hasChunkAt(p) && e.canStandAtPosition(p))return p;}
        return null;
    }
}
