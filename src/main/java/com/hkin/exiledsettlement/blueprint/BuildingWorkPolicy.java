package com.hkin.exiledsettlement.blueprint;

import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;

/** Player building orders own their preparation; automatic landscaping settings do not veto them. */
public final class BuildingWorkPolicy {
    private BuildingWorkPolicy() {}
    public static boolean building(SettlementRecord s,BlueprintRecord site) {
        return WorkTaskService.building(s,site) && (site.region()==null || !site.region().groundMaintenance);
    }
    public static boolean construction(SettlementRecord s,BlueprintRecord site) {
        if(building(s,site))return true;
        if(site==null || site.cancellationQueued())return false;
        var p=site.terrainProject();
        if(p!=null && !p.cancelled && p.playerBridge!=null && p.playerBridge.buildingModel)return true;
        return p!=null && !p.cancelled && !p.publicGround && !p.road && !p.bridge && !p.areaClearance && !p.roadReclaim
                && p.dependencies.stream().map(s::blueprint).anyMatch(parent->building(s,parent));
    }
    public static boolean automaticForbidden(SettlementRecord s,BlueprintRecord site,BlockPos at) {
        return s.decorations().forbidden(at) && !construction(s,site);
    }
    /** A new accepted target supersedes old automatic no-rebuild marks only within its own work. */
    public static boolean suppressed(SettlementRecord s,BlueprintRecord site,BlockPos at) {
        if(!s.terrain().suppressed.contains(at.asLong()))return false;
        if(!construction(s,site))return true;
        return site.terrainProject()!=null?site.terrainProject().template().placementAt(at)==null
                :!site.containsColumn(at) && !site.hasClearanceTarget(at);
    }
    public static BlueprintRecord assigned(SettlementRecord s,CitizenRecord citizen) {
        if(citizen==null)return null;
        var site=s.blueprint(citizen.targetBlueprintId()!=null?citizen.targetBlueprintId():citizen.constructionResumeId());
        return construction(s,site)?site:null;
    }
}
