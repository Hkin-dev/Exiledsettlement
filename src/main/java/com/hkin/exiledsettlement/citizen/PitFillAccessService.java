package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.blueprint.BlueprintRecord;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.terrain.TerrainService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import java.util.*;

/** Move a worker off the active fill layer before its occupied cell holds the whole selection. */
public final class PitFillAccessService {
    private PitFillAccessService() {}
    private record Route(BlockPos from,int layer,BlockPos destination,long retry) {}
    private static final Map<SettlementCitizenEntity,Route> ROUTES=new WeakHashMap<>();

    public static boolean yieldOccupiedLayer(ServerLevel level,SettlementRecord settlement,
            BlueprintRecord site,CitizenRecord citizen,SettlementCitizenEntity actor) {
        var project=site.terrainProject();
        if(project==null || !project.pitFill)return false;
        if(citizen.pitEscape.exit!=null)return true;
        int y=TerrainService.pitFillLayer(level,project);
        var feet=actor.navigationFeetPosition();
        if(y<feet.getY() || y>feet.getY()+1)return false;
        boolean occupied=false;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
            var at=feet.offset(dx,0,dz).atY(y);
            if(actor.getBoundingBox().intersects(new AABB(at))
                    && project.indices(at).stream().anyMatch(i->!project.finished.get(i)))occupied=true;
        }
        if(!occupied){ROUTES.remove(actor);return false;}
        var route=ROUTES.get(actor);
        if(route==null || !route.from().equals(feet) || route.layer()!=y || route.retry()<=level.getGameTime()) {
            var candidates=new ArrayList<BlockPos>();
            for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++) {
                var base=feet.offset(dx,0,dz);if(!level.hasChunkAt(base))continue;
                int surface=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,base.getX(),base.getZ());
                for(int height:new int[]{surface,feet.getY()+1,feet.getY()}) {
                    var at=base.atY(height);
                    if(height<=y && project.indices(base.atY(y)).stream().anyMatch(i->!project.finished.get(i)))continue;
                    if(level.getFluidState(at).isEmpty() && level.getFluidState(at.below()).isEmpty()
                            && actor.canStandAtPosition(at))candidates.add(at);
                }
            }
            var ordered=candidates.stream().distinct().sorted(Comparator.comparingDouble(at->at.distSqr(feet))).toList();
            var destination=actor.findReachableBedStation(ordered);
            route=new Route(feet,y,destination,level.getGameTime()+40);ROUTES.put(actor,route);
        }
        if(route.destination()!=null)actor.navigateTo(route.destination(),0.8D);
        else {
            actor.stopSettlementNavigation();
            // Existing paid escape checks real support, clearance, protection and
            // a usable bank. Keep the job and cargo if no safe exit exists yet.
            PitEscapeService.request(level,settlement,citizen,actor);
        }
        return true;
    }
}
