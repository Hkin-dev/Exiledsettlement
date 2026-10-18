package com.hkin.exiledsettlement.citizen;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.region.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.*;

/** Emergency natural-ground egress is paid resident work, never a teleport or free repair. */
public final class ResidentEgressService {
    private static final Map<CitizenRecord,Long> RETRIES=new WeakHashMap<>();
    private ResidentEgressService() { }
    public static boolean active(SettlementRecord s,CitizenRecord c) {
        var b=s.blueprint(c.targetBlueprintId());
        return b!=null && b.terrainProject()!=null && c.id().equals(b.terrainProject().escapingCitizen)
                && !b.cancellationQueued() && !b.terrainProject().completed;
    }
    /** Once a dry physical landing exists, leave it before resuming the personal bed route. */
    public static boolean leaveForBed(ServerLevel level,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e) {
        if(CitizenWorkService.schedulePhase(level.getDayTime(),c)!=CitizenWorkService.SchedulePhase.SLEEP)return false;
        var site=s.blueprint(c.targetBlueprintId());var p=site==null?null:site.terrainProject();
        if(p==null || !c.id().equals(p.escapingCitizen) || p.cancelled || p.completed)return false;
        var feet=e.navigationFeetPosition();
        if(e.onGround() && !e.isInWater() && e.canStandAtPosition(feet) && p.escapeFor!=null
                && feet.distSqr(p.escapeFor)>1) {
            p.cancelled=true;site.queueCancellation();c.clearWork();e.stopSettlementNavigation();
            SettlementSavedData.get(level).touch();return false;
        }
        var candidates=new ArrayList<BlockPos>();
        for(int dx=-8;dx<=8;dx++)for(int dz=-8;dz<=8;dz++)if(dx*dx+dz*dz<=64)
            for(int dy:new int[]{0,-1,1}) {
                var at=feet.offset(dx,dy,dz);
                if(p.escapeFor!=null && at.distSqr(p.escapeFor)>1 && e.canStandAtPosition(at))candidates.add(at);
            }
        candidates.sort(Comparator.comparingDouble(at->at.distSqr(feet)));
        for(var at:candidates)if(e.hasReachablePathTo(at)) {
            var failure=e.navigateTo(at,.65);c.updateActivity(CitizenActivity.RETURNING_HOME);c.updateWorkFailure(failure);
            SettlementSavedData.get(level).touch();return true;
        }
        return false;
    }
    public static boolean resume(SettlementRecord s,CitizenRecord c) {
        for(var project:s.terrain().projects.values())if(c.id().equals(project.escapingCitizen) && !project.cancelled && !project.completed && !project.done()) {
            var site=s.blueprint(project.id);if(site==null || site.cancellationQueued())continue;
            c.clearWork();return c.startTask(CitizenWorkState.TO_BLUEPRINT,project.next().position(),null,project.id);
        }
        return false;
    }
    public static boolean plan(ServerLevel level,SettlementRecord s,CitizenRecord c,SettlementCitizenEntity e) {
        boolean bedtime=c.workFailure().equals("BED_UNREACHABLE");
        var preferred=s.blueprint(c.preferredWorksiteId());
        boolean fieldTrap=c.workFailure().equals("CONSTRUCTION_SITE_NO_ROUTE") && preferred!=null
                && preferred.region()!=null && preferred.region().agricultural() && preferred.region().flatY!=null
                && preferred.region().cell(e.navigationFeetPosition())!=null
                && e.getY()<preferred.region().flatY+1;
        if(!(bedtime || fieldTrap) || !e.onGround() || level.getGameTime()<RETRIES.getOrDefault(c,0L))return false;
        RETRIES.put(c,level.getGameTime()+200);
        var feet=e.navigationFeetPosition();List<TerrainProject.Step> best=null;List<BlockPos> surfaces=null;
        for(var side:Direction.Plane.HORIZONTAL) {
            var steps=new ArrayList<TerrainProject.Step>();var path=new ArrayList<BlockPos>();int y=feet.getY()-1;
            for(int distance=1;distance<=6;distance++) {
                var p=feet.relative(side,distance);if(!level.hasChunkAt(p) || !s.contains(p))break;
                int actual=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,p.getX(),p.getZ())-1;
                if(distance>1)y=Math.clamp(actual,y-1,y+1);
                p=p.atY(y);
                if(!FarmEarthworkPlan.support(level,p))break;
                boolean safe=true;
                for(int dy:new int[]{2,3,1}) {
                    var at=p.above(dy);var state=level.getBlockState(at);if(state.isAir())continue;
                    if(!TerrainService.natural(state) || !state.getFluidState().isEmpty()
                            || s.community().danger(at) || s.decorations().forbidden(at)
                            || com.hkin.exiledsettlement.road.RoadSpaceData.get(level).manual(at)){safe=false;break;}
                    for(var building:s.buildings())if(building.active()) {
                        // Starter wagons have no construction template; retain their bounds protection.
                        if(!BlueprintTemplates.supports(building.type())) {
                            if(building.contains(at)){safe=false;break;}
                            continue;
                        }
                        var goal=BlueprintTemplates.createForBuilding(building).placementAt(at);
                        if(goal!=null && !goal.state().isAir() && goal.mode().isFinalStructure()){safe=false;break;}
                    }
                    if(!safe)break;
                    steps.add(new TerrainProject.Step(at,Blocks.AIR.defaultBlockState(),state));
                }
                if(!safe)break;path.add(p);
                if(distance>=2 && e.canStandAtPosition(p.above())) {
                    if(!steps.isEmpty() && (best==null || steps.size()<best.size())){best=steps;surfaces=path;}
                    break;
                }
            }
        }
        if(best==null)return false;
        // Existing public plans may not refill a temporary safety exit while it is in use.
        var positions=new HashSet<BlockPos>();for(var step:best)positions.add(step.position());
        for(var p:s.terrain().projects.values())if((p.publicGround || fieldTrap && p.dependencies.contains(preferred.id())) && !p.completed && !p.cancelled)
            for(int i=p.cursor;i<p.steps.size();i++)if(!p.finished.get(i) && positions.contains(p.steps.get(i).position()))p.abandon(i);
        if(fieldTrap)for(var at:positions) {
            var region=preferred.region();for(int i=0;i<region.ordered().size();i++)if(region.ordered().get(i).ground().getX()==at.getX()
                    && region.ordered().get(i).ground().getZ()==at.getZ())region.preparationRetries.add(i);
        }
        var project=ProductionRegionService.publish(level,s,c.homeBuildingId(),"居民安全离场通道",feet,best);
        if(project==null)return false;
        if(c.preferredWorksiteId()!=null)project.dependencies.add(c.preferredWorksiteId());
        project.escapeFor=feet;project.escapingCitizen=c.id();project.accessSurfaces.addAll(surfaces);
        c.clearWork();c.startTask(CitizenWorkState.TO_BLUEPRINT,best.getFirst().position(),null,project.id);
        SettlementSavedData.get(level).touch();return true;
    }
}
