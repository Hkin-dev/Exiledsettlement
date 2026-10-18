package com.hkin.exiledsettlement.building;

import com.hkin.exiledsettlement.citizen.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import org.jetbrains.annotations.Nullable;
import java.util.*;

/** Open camp loading aprons. Plans are short lived movement hints, never inventory locks. */
public final class CampStorageAccess {
    private record Trip(UUID store, BlockPos at, long progressed, double distance) { }
    private static final Map<SettlementCitizenEntity,Trip> TRIPS=new WeakHashMap<>();
    private static final Map<SettlementCitizenEntity,Map<BlockPos,Long>> RETRY=new WeakHashMap<>();
    private CampStorageAccess() { }

    public static boolean applies(BuildingRecord store) {
        return store.type()==BuildingType.WAGON && store.templateVersion()>=3;
    }

    public static void release(LivingEntity entity) { TRIPS.remove(entity);RETRY.remove(entity); }

    /** A failed interaction must retain the rejected destination until another route is tried. */
    public static void abandon(ServerLevel level, LivingEntity entity) {
        var old=TRIPS.remove(entity);
        if(old!=null && entity instanceof SettlementCitizenEntity citizen)
            RETRY.computeIfAbsent(citizen,e->new HashMap<>()).put(old.at(),level.getGameTime()+80);
    }

    private static boolean free(ServerLevel level, SettlementCitizenEntity entity, BlockPos at) {
        if(!WarehouseInventoryService.canStand(level,at))return false;
        var box=entity.getBoundingBox().move(at.getX()+.5-entity.getX(),at.getY()-entity.getY(),at.getZ()+.5-entity.getZ());
        return level.noCollision(entity,box) && level.getEntitiesOfClass(LivingEntity.class,box.inflate(.1,0,.1),
                other->other!=entity && other.isAlive()).isEmpty();
    }

    private static boolean visible(ServerLevel level, LivingEntity entity, BlockPos at, BlockPos barrel) {
        if(!CitizenInteractionRange.containsAt(at,barrel))return false;
        var eye=new Vec3(at.getX()+.5,at.getY()+entity.getEyeHeight(),at.getZ()+.5);
        var hit=level.clip(new ClipContext(eye,Vec3.atCenterOf(barrel),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,entity));
        return hit.getType()==HitResult.Type.MISS || hit.getBlockPos().equals(barrel);
    }

    /** Every registered barrel contributes reachable floor space, including the yard outside the shed. */
    public static List<BlockPos> stations(ServerLevel level, BuildingRecord store, SettlementCitizenEntity entity) {
        Set<BlockPos> cells=new LinkedHashSet<>();
        var barrels=WarehouseInventoryService.loadedContainers(level,store);
        for(var barrel:barrels)for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)for(int down=0;down<=2;down++) {
            var at=barrel.position().offset(dx,-down,dz);
            if(level.getBlockEntity(at.below()) instanceof net.minecraft.world.Container)continue;
            if(WarehouseInventoryService.canStand(level,at) && visible(level,entity,at,barrel.position()))cells.add(at);
        }
        return List.copyOf(cells);
    }

    @Nullable public static BlockPos destination(ServerLevel level, BuildingRecord store, SettlementCitizenEntity entity) {
        if(WarehouseInventoryService.canInteract(level,store,entity)) { release(entity); return entity.navigationFeetPosition(); }
        var old=TRIPS.get(entity);
        if(old!=null && (level.getGameTime()-old.progressed()>40 || entity.getNavigation().isDone()
                && entity.distanceToSqr(Vec3.atBottomCenterOf(old.at()))<2.25)) {
            RETRY.computeIfAbsent(entity,e->new HashMap<>()).put(old.at(),level.getGameTime()+80);
            TRIPS.remove(entity);
        }
        return choose(level,store,entity,stations(level,store,entity));
    }

    @Nullable public static BlockPos waiting(ServerLevel level, BuildingRecord store, SettlementCitizenEntity entity) {
        var loading=new HashSet<>(stations(level,store,entity));
        Set<BlockPos> candidates=new LinkedHashSet<>();
        for(var at:loading)for(var direction:net.minecraft.core.Direction.Plane.HORIZONTAL)
            for(int distance=1;distance<=3;distance++) {
                var p=at.relative(direction,distance);
                if(!loading.contains(p) && WarehouseInventoryService.canStand(level,p))candidates.add(p);
            }
        return choose(level,store,entity,new ArrayList<>(candidates));
    }

    @Nullable private static BlockPos choose(ServerLevel level, BuildingRecord store, SettlementCitizenEntity entity,List<BlockPos> candidates) {
        long now=level.getGameTime();
        var retry=RETRY.computeIfAbsent(entity,e->new HashMap<>());retry.entrySet().removeIf(e->e.getValue()<=now);
        TRIPS.entrySet().removeIf(entry->!entry.getKey().isAlive() || entry.getKey().isRemoved()
                || entry.getKey().level()!=level || now<entry.getValue().progressed()
                || now-entry.getValue().progressed()>40);
        var old=TRIPS.get(entity);
        if(old!=null && old.store().equals(store.id()) && candidates.contains(old.at()) && free(level,entity,old.at())) {
            double distance=entity.distanceToSqr(Vec3.atBottomCenterOf(old.at()));
            if(distance<old.distance()-.1)TRIPS.put(entity,new Trip(store.id(),old.at(),now,distance));
            return old.at();
        }
        TRIPS.remove(entity);
        // Prefer spreading approaching residents, without reserving or excluding a cell.
        // Only an actual body/collision can make a position unavailable.
        for(var at:candidates.stream().sorted(Comparator.comparingDouble((BlockPos p)->p.distSqr(entity.blockPosition())
                +TRIPS.entrySet().stream().filter(entry->entry.getKey()!=entity && entry.getValue().at().equals(p)).count()*16)).toList()) {
            if(retry.containsKey(at) || !free(level,entity,at))continue;
            if(!entity.hasReachablePathTo(at))continue;
            TRIPS.put(entity,new Trip(store.id(),at,now,entity.distanceToSqr(Vec3.atBottomCenterOf(at))));
            return at;
        }
        return null;
    }
}
