package com.hkin.exiledsettlement.building;

import com.hkin.exiledsettlement.citizen.SettlementCitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Shared bounded discovery; cached candidates are rechecked before navigation. */
public final class StorageAccessService {
    private StorageAccessService() {}
    private static final Map<BuildingRecord, Scan> SCANS=new WeakHashMap<>();
    private static final Map<SettlementCitizenEntity, Trip> TRIPS=new WeakHashMap<>();
    private record Trip(UUID building, BlockPos position, long until) {}
    private static final class Scan {
        long tick=Long.MIN_VALUE, cursor;
        final LinkedHashSet<BlockPos> positions=new LinkedHashSet<>();
    }
    public static BlockPos destination(ServerLevel level,BuildingRecord b,SettlementCitizenEntity e) {
        long now=level.getGameTime();var trip=TRIPS.get(e);
        if(trip!=null && trip.building.equals(b.id()) && now<trip.until && now>=trip.until-80
                && (trip.position==null || WarehouseInventoryService.canStand(level,trip.position)))return trip.position;
        var scan=SCANS.computeIfAbsent(b,ignored->new Scan());
        int w=b.maxCorner().getX()-b.minCorner().getX()-1,d=b.maxCorner().getZ()-b.minCorner().getZ()-1;
        int top=b.type()==BuildingType.MINE?b.position().getY()-13:b.maxCorner().getY();
        int h=top-b.minCorner().getY();
        if(w<=0 || d<=0 || h<=0)return null;
        long area=(long)w*d,total=area*h;
        if(scan.tick!=now) {
            scan.tick=now;
            for(int i=0;i<128 && i<total;i++) {
                long index=scan.cursor++%total;
                // Start on the entrance floor, then visit the other actual floor levels.
                int entryFloor=b.type()==BuildingType.MINE?b.position().getY()-16:b.position().getY();
                int y=b.minCorner().getY()+Math.floorMod((int)(index/area)+entryFloor-b.minCorner().getY(),h);
                var p=new BlockPos(b.minCorner().getX()+1+(int)(index%w),y,b.minCorner().getZ()+1+(int)((index%area)/w));
                if(WarehouseInventoryService.canStand(level,p))scan.positions.add(p);else scan.positions.remove(p);
                if(scan.positions.size()>128)scan.positions.remove(scan.positions.iterator().next());
            }
        }
        var points=new ArrayList<>(scan.positions);
        points.removeIf(p->!WarehouseInventoryService.canStand(level,p));
        // Prefer room interiors over the first cell behind a door; distribute simultaneous visitors.
        points.sort(Comparator.comparingDouble(p->p.distSqr(e.blockPosition())
                + (Math.min(Math.min(p.getX()-b.minCorner().getX(),b.maxCorner().getX()-p.getX()),
                Math.min(p.getZ()-b.minCorner().getZ(),b.maxCorner().getZ()-p.getZ()))<2?64:0)));
        int offset=points.isEmpty()?0:Math.floorMod(e.getUUID().hashCode()+(int)(now/80),Math.min(8,points.size()));
        Collections.rotate(points,-offset);
        // One native multi-target search accepts any reachable interior cell; isolated roof
        // candidates cannot monopolize a sequence of failed single-point probes.
        BlockPos chosen=e.findReachableBedStation(points);
        // A failed short preview must still allow the normal navigator's segmented
        // detours and backoff to reach the selected room from outside.
        if(chosen==null && !points.isEmpty())chosen=points.getFirst();
        TRIPS.put(e,new Trip(b.id(),chosen,now+(chosen==null?20:80)));return chosen;
    }
}
