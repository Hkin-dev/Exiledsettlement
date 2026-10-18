package com.hkin.exiledsettlement.region;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.*;
import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** One-time compatibility conversion; never places blocks, generates supplies or moves animals. */
public final class ProductionSiteMigration {
    private static final Map<SettlementRecord,Integer> SURVEY_CURSORS=new WeakHashMap<>();
    private ProductionSiteMigration() {}
    public static void tickLevel(ServerLevel level){
        var data=SettlementSavedData.get(level);
        for(var s:data.settlements())if(s.active() && migrate(level,s))data.touch();
    }
    public static boolean migrate(ServerLevel level,SettlementRecord s){
        boolean changed=false;
        var legacyBuildings=s.buildings().stream().filter(b->b.region()!=null).toList();
        var legacySites=s.blueprints().stream().filter(b->b.region()!=null).toList();
        // Retire maintenance while its original owner is still available to child cancellation.
        for(var site:legacySites){
            var r=site.region();
            if(r.groundMaintenance || r.workingBuilding!=null && s.building(r.workingBuilding)!=null){
                changed|=BlueprintService.cancelConstruction(s,site);
                r.groundPaused=true;
            }
            if(!r.animalSafety.getCompound("Leads").isEmpty())r.groundPaused=true;
        }
        for(var b:legacyBuildings)if(!b.region().animalSafety.getCompound("Leads").isEmpty())b.region().groundPaused=true;
        if(!legacySites.isEmpty() || !legacyBuildings.isEmpty())PastureGroundSafety.recoverLeads(level,s);

        for(var b:legacyBuildings){
            var r=b.region();
            // Escrow for an unloaded animal must survive until the existing real-lead recovery runs.
            if(!r.animalSafety.getCompound("Leads").isEmpty())continue;
            for(var p:List.copyOf(s.terrain().projects.values()))if(Objects.equals(p.regionOwner,b.id())
                    || p.dependencies.contains(b.id()) && !p.publicGround && !p.road && !p.bridge){
                changed|=withdrawFinishedOwner(s,p,b.id());
            }
            var snapshot=ProductionSiteSnapshot.fromRegion(r);
            b.withProductionSite(snapshot).withRegion(null);
            for(var at:List.copyOf(b.repairPositions()))if(!snapshot.structural(at))b.clearRepairPosition(at);
            BuildingFunctionService.validateSingle(level,SettlementSavedData.get(level),s,b);
            changed=true;
        }

        // Read-only survey is bounded and rotates; one blocked old selection cannot starve another.
        var surveying=legacySites.stream().filter(site->!site.cancellationQueued() && !site.region().surveyed).toList();
        BlueprintRecord survey=null;
        if(!surveying.isEmpty()){
            int cursor=SURVEY_CURSORS.getOrDefault(s,0);survey=surveying.get(Math.floorMod(cursor,surveying.size()));
            SURVEY_CURSORS.put(s,cursor+1);
        }
        for(var site:legacySites){
            var r=site.region();
            if(!r.animalSafety.getCompound("Leads").isEmpty())continue;
            if(!site.cancellationQueued()){
                if(!r.surveyed && site!=survey)continue;
                if(!ProductionRegionService.finishLegacySurvey(level,s,site)){changed=true;continue;}
            }else changed|=BlueprintService.cancelConstruction(s,site);
            // Keep accepted work and surviving emergency routes executable after their owner loses its region.
            for(var p:s.terrain().projects.values())if(Objects.equals(p.regionOwner,site.id())){
                p.regionOwner=null;
                if(!site.cancellationQueued())p.dependencies.add(site.id());
                changed=true;
            }
            site.withProductionSite(ProductionSiteSnapshot.fromRegion(r)).withRegion(null);
            changed=true;
        }
        changed|=releaseCancelledWorkers(level,s);
        return changed;
    }
    private static boolean withdrawFinishedOwner(SettlementRecord s,TerrainProject p,UUID owner){
        // Public, manual, road and emergency access orders remain owned by their own lifecycle.
        if(p.publicGround || p.road || p.bridge || p.areaClearance || p.roadReclaim
                || p.escapeFor!=null || p.escapingCitizen!=null || p.playerBridge!=null){
            if(!Objects.equals(p.regionOwner,owner))return false;
            p.regionOwner=null;return true;
        }
        boolean changed=p.dependencies.remove(owner);
        if(Objects.equals(p.regionOwner,owner)){p.regionOwner=null;changed=true;}
        if(p.completed || p.cancelled || p.dependencies.stream().anyMatch(id->validOwner(s,id,new HashSet<>())))return changed;
        var carrier=s.blueprint(p.id);
        if(carrier!=null)return BlueprintService.cancelConstruction(s,carrier)||changed;
        // Very old orphan records may have no carrier. Preserve their paid history and paid cells.
        p.cancelled=true;
        var paid=new HashSet<net.minecraft.core.BlockPos>();
        for(int i=0;i<p.steps.size();i++)if(p.steps.get(i).fill()
                && (p.retiredPaid.get(i) || !p.abandoned.get(i) && (i<p.cursor || p.finished.get(i))))paid.add(p.steps.get(i).position());
        for(var step:p.steps)if(!paid.contains(step.position()))s.terrain().cells.remove(step.position(),p.id);
        s.terrain().index(p);return true;
    }
    private static boolean validOwner(SettlementRecord s,UUID id,Set<UUID> seen){
        if(id==null || !seen.add(id))return false;
        if(id.equals(s.id()))return true;
        var b=s.building(id);if(b!=null)return b.active() && !b.demolitionQueued();
        var site=s.blueprint(id);if(site!=null && site.cancellationQueued())return false;
        var p=s.terrain().projects.get(id);
        if(p!=null)return !p.cancelled && (p.publicGround || p.road || p.bridge || p.areaClearance || p.escapeFor!=null
                || p.dependencies.stream().anyMatch(parent->validOwner(s,parent,seen)));
        if(site!=null)return true;
        return s.autoRoads().routes.values().stream().anyMatch(route->route.id.equals(id) && !route.suppressed && !route.p0Invalidated);
    }
    private static boolean releaseCancelledWorkers(ServerLevel level,SettlementRecord s){
        boolean changed=false;
        for(var c:s.citizens()){
            var target=s.blueprint(c.targetBlueprintId());
            if(target==null || !target.cancellationQueued() || target.productionSite()==null && target.terrainProject()==null)continue;
            var entity=CitizenProjectionService.findProjection(level,c);
            // Existing descent/egress keeps ownership until the resident is safely off its supports.
            if(entity==null || entity.hasActiveConstructionMovement() || !entity.onGround()
                    || !target.temporaryFootings().isEmpty())continue;
            if(Objects.equals(c.preferredWorksiteId(),target.id()))c.setPreferredWorksite(null);
            WarehouseInventoryService.releaseInteractionPosition(level,entity);
            changed|=c.clearWork();entity.stopSettlementNavigation();entity.setActivity(CitizenActivity.IDLE);
        }
        return changed;
    }
}
