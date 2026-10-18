package com.hkin.exiledsettlement.region;

import com.hkin.exiledsettlement.blueprint.*;
import com.hkin.exiledsettlement.building.BuildingRecord;
import com.hkin.exiledsettlement.settlement.*;

/** Workers build accepted new/expanded farms; established farm upkeep belongs to farmers. */
public final class FarmConstructionPolicy {
    private FarmConstructionPolicy() {}
    public static boolean farm(BuildingRecord b) {return b!=null && FarmEarthworkPlan.applies(b.type());}
    public static boolean maintenance(SettlementRecord s,BlueprintRecord site) {
        if(site==null || site.region()==null || !site.region().agricultural())return false;
        var r=site.region();
        return r.groundMaintenance || r.workingBuilding!=null && farm(s.building(r.workingBuilding));
    }
    public static boolean workerMayBuild(SettlementRecord s,BlueprintRecord site) {
        if(maintenance(s,site))return false;
        return site==null || site.terrainProject()==null || site.terrainProject().dependencies.stream()
                .map(s::blueprint).noneMatch(parent->maintenance(s,parent));
    }
    /** Keep all old order/progress records and world blocks, but stop automatic maintenance dispatch. */
    public static boolean stopAutomaticMaintenance(SettlementRecord s) {
        boolean changed=false;
        for(var site:s.blueprints())if(maintenance(s,site)) {
            changed|=!site.region().groundPaused;
            site.region().groundPaused=true;
            site.region().status="工人农田维护已停用；保留工程记录与现场";
        }
        return changed;
    }
}
