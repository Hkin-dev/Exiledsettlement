package com.hkin.exiledsettlement.settlement;

import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.blueprint.BlueprintTemplates;
import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Bottle filling and concrete hydration use the full cauldron built into the kiln. */
public final class KilnWaterService {
    public record Station(BlockPos water,BlockPos feet) {}
    private record Cached(long until,List<Station> stations) {}
    private static final Map<ServerLevel,Map<UUID,Cached>> CACHE=new WeakHashMap<>();
    private static boolean valid(ServerLevel level,BuildingRecord kiln,Station station) {
        return level.hasChunkAt(station.water()) && level.hasChunkAt(station.feet())
                && kiln.contains(station.water()) && kiln.contains(station.feet())
                && level.getBlockState(station.water()).is(Blocks.WATER_CAULDRON)
                && level.getBlockState(station.water()).getValue(LayeredCauldronBlock.LEVEL)>0
                && WarehouseInventoryService.canStand(level,station.feet())
                && level.getFluidState(station.feet()).isEmpty();
    }
    public static List<Station> stations(ServerLevel level,SettlementRecord settlement,BuildingRecord kiln) {
        var cache=CACHE.computeIfAbsent(level,k -> new HashMap<>());
        var old=cache.get(kiln.id());
        if(old==null || level.getGameTime()>=old.until()) {
            var found=new ArrayList<Station>();
            var template=BlueprintTemplates.createForBuilding(kiln);
            for(var placement:template.placements()) {
                var water=placement.position();
                if(!placement.mode().isFinalStructure() || !placement.state().is(Blocks.WATER_CAULDRON)
                        || !level.hasChunkAt(water) || !level.getBlockState(water).is(Blocks.WATER_CAULDRON))continue;
                for(var direction:Direction.Plane.HORIZONTAL)for(int dy=0;dy<=1;dy++) {
                    var station=new Station(water.immutable(),water.relative(direction).above(dy));
                    if(valid(level,kiln,station))found.add(station);
                }
            }
            found.sort(Comparator.comparingDouble(s -> s.feet().distSqr(kiln.position())));
            old=new Cached(level.getGameTime()+200,List.copyOf(found.stream().limit(32).toList()));
            cache.put(kiln.id(),old);
        }
        return old.stations().stream().filter(s -> valid(level,kiln,s)).toList();
    }
    public static boolean at(ServerLevel level,SettlementRecord settlement,BuildingRecord kiln,SettlementCitizenEntity entity) {
        return level.noCollision(entity) && !entity.isInWater() && stations(level,settlement,kiln).stream()
                .anyMatch(s -> entity.distanceToSqr(Vec3.atBottomCenterOf(s.feet()))<=1.0);
    }
    public static BlockPos destination(ServerLevel level,SettlementRecord settlement,BuildingRecord kiln,SettlementCitizenEntity entity) {
        if(at(level,settlement,kiln,entity))return entity.navigationFeetPosition();
        return stations(level,settlement,kiln).stream().map(Station::feet).distinct()
                .sorted(Comparator.comparingDouble(p -> p.distSqr(entity.blockPosition())))
                .filter(entity::hasReachablePathTo).findFirst().orElse(null);
    }
    private KilnWaterService() {}
}
