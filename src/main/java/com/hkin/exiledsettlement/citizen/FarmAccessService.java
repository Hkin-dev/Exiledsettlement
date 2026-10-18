package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.blueprint.*;
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
    private FarmAccessService(){ }
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
        var farm=s.buildings().stream().filter(b->b.active() && b.region()!=null && b.region().cell(feet)!=null
                && b.region().cell(target)==null).findFirst().orElse(null);
        var exit=EXITS.get(entity);
        // Crossing a block boundary does not mean the body has cleared the gate.
        // Finish the physical outside landing before selecting a distant road segment.
        if(farm==null) {
            if(exit!=null && exit.target().distSqr(target)<=25 && level.getGameTime()<=exit.expires()
                    && entity.distanceToSqr(exit.outside().getX()+.5,exit.outside().getY(),exit.outside().getZ()+.5)>0.81)
                return exit.outside();
            EXITS.remove(entity);CROSSING.remove(entity);return target;
        }
        if(exit==null || !exit.farm().equals(farm.id()) || !exit.target().equals(target) || level.getGameTime()>exit.expires()
                || !(level.getBlockState(exit.gate()).getBlock() instanceof FenceGateBlock)){
            Exit best=null;double score=Double.MAX_VALUE;
            var failed=FAILED_GATES.computeIfAbsent(entity,e->new HashMap<>());failed.entrySet().removeIf(e->e.getValue()<=level.getGameTime());
            for(var entry:BlueprintTemplates.createForBuilding(farm).entrances()){
                var gate=entry.position();if(failed.containsKey(gate) || !level.hasChunkAt(gate) || !(level.getBlockState(gate).getBlock() instanceof FenceGateBlock))continue;
                var inside=standing(entity,gate.relative(entry.outward().getOpposite()));
                var outside=standing(entity,gate.relative(entry.outward(),2));
                if(outside==null)outside=standing(entity,gate.relative(entry.outward()));
                if(inside==null || outside==null || Math.abs(inside.getY()-gate.getY())>1
                        || Math.abs(outside.getY()-gate.getY())>1 || !entity.hasReachablePathTo(outside))continue;
                double cost=Math.sqrt(inside.distSqr(feet))+Math.sqrt(outside.distSqr(target));
                if(cost<score){score=cost;best=new Exit(farm.id(),gate,entry.outward(),inside,outside,target.immutable(),level.getGameTime()+1200);}
            }
            if(best==null)return target;exit=best;EXITS.put(entity,exit);CROSSING.remove(entity);
        }
        double progress=(entity.getX()-exit.gate().getX()-.5)*exit.outward().getStepX()
                +(entity.getZ()-exit.gate().getZ()-.5)*exit.outward().getStepZ();
        if(progress>-.9 && entity.distanceToSqr(exit.gate().getX()+.5,exit.gate().getY(),exit.gate().getZ()+.5)<4
                || entity.distanceToSqr(exit.inside().getX()+.5,exit.inside().getY(),exit.inside().getZ()+.5)<=2.25)CROSSING.add(entity);
        return CROSSING.contains(entity)?exit.outside():exit.inside();
    }
    private static BlockPos standing(SettlementCitizenEntity e,BlockPos hint){
        for(int dy:new int[]{0,-1,1}){var p=hint.offset(0,dy,0);if(e.canStandAtPosition(p))return p;}
        return null;
    }
}
