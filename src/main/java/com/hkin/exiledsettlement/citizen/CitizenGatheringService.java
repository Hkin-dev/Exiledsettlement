package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.settlement.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Temporary social destinations. Membership is never a work, inventory or teleport shortcut. */
public final class CitizenGatheringService {
    private CitizenGatheringService() { }
    private record Point(UUID building, BlockPos center) { }
    private record Visit(Point point, BlockPos standing, long until) { }
    private record Pool(long until, int buildings, List<Point> points) { }
    private static final Map<SettlementRecord, Pool> POOLS = new WeakHashMap<>();
    private static final Map<SettlementCitizenEntity, Visit> VISITS = new WeakHashMap<>();
    private static final Map<SettlementCitizenEntity,Long> RETRY=new WeakHashMap<>();
    public static BlockPos destination(ServerLevel level, SettlementRecord s, CitizenRecord c, SettlementCitizenEntity e) {
        long now = level.getGameTime();
        VISITS.entrySet().removeIf(v -> !v.getKey().isAlive() || v.getValue().until <= now
                || v.getKey().isSleeping() || v.getKey().activity() != CitizenActivity.IDLE);
        Visit visit = VISITS.get(e);
        if (visit != null && valid(level, s, visit.point, visit.standing, e)) return visit.standing;
        VISITS.remove(e);
        if(RETRY.getOrDefault(e,-1L)>now)return null;
        if (Math.floorMod(c.id().hashCode() + (int)(now / 2400), 4) == 0) return null;
        var pool = POOLS.get(s);
        if (pool == null || pool.until <= now || pool.buildings != s.buildings().size()) {
            pool = new Pool(now + 600, s.buildings().size(), points(level, s)); POOLS.put(s, pool);
        }
        List<Point> choices = new ArrayList<>(pool.points);
        choices.sort(Comparator.comparingDouble(p -> p.center.distSqr(e.blockPosition())
                - members(p, e) * 20 + Math.floorMod(c.id().hashCode() ^ p.center.hashCode() ^ (int)(now/2400), 60)));
        for (Point point : choices.stream().limit(4).toList()) {
            if (members(point, e) >= 5) continue;
            int start = Math.floorMod(c.id().hashCode(), 8);
            for (int a = 0; a < 8; a++) {
                double angle = (start + a) * Math.PI / 4;
                BlockPos at = point.center.offset((int)Math.round(Math.cos(angle)*2), 0, (int)Math.round(Math.sin(angle)*2));
                if (VISITS.entrySet().stream().anyMatch(v -> v.getKey() != e && v.getValue().standing.distSqr(at) < 2)) continue;
                for (int dy : new int[]{0,1,-1}) {
                    BlockPos stand = at.offset(0,dy,0);
                    if (valid(level,s,point,stand,e) && e.hasReachablePathTo(stand)) {
                        VISITS.put(e,new Visit(point,stand.immutable(),now+1200+Math.floorMod(c.id().hashCode(),1201)));
                        return stand;
                    }
                }
            }
        }
        RETRY.put(e,now+200);return null;
    }
    private static int members(Point p, SettlementCitizenEntity except) {
        return (int)VISITS.entrySet().stream().filter(v -> v.getKey()!=except && v.getValue().point.equals(p)).count();
    }
    private static List<Point> points(ServerLevel level, SettlementRecord s) {
        var leisure = s.buildings().stream().filter(b -> b.active() && b.operational() && !b.demolitionQueued())
                .filter(b -> b.type()==BuildingType.LEISURE_GARDEN || b.type()==BuildingType.TAVERN
                        || b.type()==BuildingType.RESTAURANT || b.type()==BuildingType.BENCH
                        && (com.hkin.exiledsettlement.decoration.DecorationService.forBuilding(s,b.id()) == null
                        || com.hkin.exiledsettlement.decoration.DecorationService.forBuilding(s,b.id()).kind.equals("bench"))).toList();
        var anchors = leisure.isEmpty() ? s.buildings().stream().filter(b -> b.active() && b.operational()
                && b.type().isHouse() && !b.demolitionQueued()).toList() : leisure;
        var result = new ArrayList<Point>();
        for (var b : anchors) {
            int x=(b.minCorner().getX()+b.maxCorner().getX())/2,z=(b.minCorner().getZ()+b.maxCorner().getZ())/2,y=b.position().getY();
            if (b.type()==BuildingType.TAVERN || b.type()==BuildingType.RESTAURANT)
                result.add(new Point(b.id(),new BlockPos(x,y+1,z)));
            result.add(new Point(b.id(),new BlockPos(x,y,b.minCorner().getZ()-4)));
            result.add(new Point(b.id(),new BlockPos(b.maxCorner().getX()+4,y,z)));
        }
        return List.copyOf(result);
    }
    private static boolean valid(ServerLevel level, SettlementRecord s, Point point, BlockPos at, SettlementCitizenEntity e) {
        var b=s.building(point.building);
        if(b==null || !b.active() || !b.operational() || b.demolitionQueued() || !s.contains(at)
                || !level.hasChunkAt(at) || s.community().danger(at) || !e.canStandAtPosition(at)) return false;
        var roads=com.hkin.exiledsettlement.road.RoadSpaceData.get(level);
        if(roads.contains(at) || roads.contains(at.below()) || com.hkin.exiledsettlement.road.AutoRoadService.plannedAt(s,at)
                || com.hkin.exiledsettlement.road.AutoRoadService.plannedAt(s,at.below())) return false;
        for(var building:s.buildings()) if(building.active()) {
            if(building.servicePositions().stream().anyMatch(p->p.distSqr(at)<9)) return false;
            if(BlueprintTemplates.supports(building.type())
                    && BlueprintTemplates.createForBuilding(building).entrances().stream().anyMatch(p->p.position().distSqr(at)<9)) return false;
        }
        for(var blueprint:s.blueprints()) {
            var t=BlueprintTemplates.create(blueprint);
            if(at.getX()>=t.minCorner().getX()-1 && at.getX()<=t.maxCorner().getX()+1
                    && at.getZ()>=t.minCorner().getZ()-1 && at.getZ()<=t.maxCorner().getZ()+1) return false;
        }
        boolean cold=WorldSeasonData.get(level).season(level.getDayTime())==3;
        return !(cold || level.isRaining()) || !level.canSeeSky(at.above());
    }
}
