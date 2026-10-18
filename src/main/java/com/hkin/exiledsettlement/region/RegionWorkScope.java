package com.hkin.exiledsettlement.region;

import com.hkin.exiledsettlement.settlement.SettlementRecord;
import com.hkin.exiledsettlement.terrain.TerrainProject;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/** Ownership is independent of the shared physical earthwork index and public settings. */
public final class RegionWorkScope {
    private RegionWorkScope() {}
    public static ProductionRegion region(SettlementRecord s,UUID id){
        var b=s.blueprint(id);if(b!=null && b.region()!=null)return b.region();
        var built=s.building(id);return built!=null?built.region():null;
    }
    public static UUID owner(SettlementRecord s,UUID dependency){var r=region(s,dependency);return r==null?null:r.workingBuilding!=null?r.workingBuilding:dependency;}
    public static boolean same(SettlementRecord s,TerrainProject p,UUID owner){
        if(owner==null)return p.regionOwner==null && p.dependencies.stream().noneMatch(id->region(s,id)!=null);
        if(p.publicGround || p.road || p.bridge || p.areaClearance)return false;
        if(p.regionOwner!=null)return p.regionOwner.equals(owner) || owner.equals(owner(s,p.regionOwner));
        return p.dependencies.stream().anyMatch(id->owner.equals(owner(s,id)))
            && p.dependencies.stream().map(id->owner(s,id)).filter(java.util.Objects::nonNull).allMatch(owner::equals);
    }
    public static boolean publicOverlap(SettlementRecord s,UUID dependency,TerrainProject p,BlockPos at){
        var r=region(s,dependency);return r!=null && r.cell(at)!=null && p.publicGround && !p.road && !p.bridge && !p.areaClearance;
    }
    public static boolean allowed(SettlementRecord s,TerrainProject p){
        if(p.dependencies.stream().map(id->region(s,id)).filter(java.util.Objects::nonNull)
                .anyMatch(r->!ProductionRegionService.maintenanceScopeUnchanged(s,r)))return false;
        if(p.regionOwner==null){
            if(p.publicGround || p.road || p.bridge || p.areaClearance)return true;
            return p.dependencies.stream().map(id->region(s,id)).filter(java.util.Objects::nonNull).noneMatch(r->r.groundPaused);
        }
        var r=region(s,p.regionOwner);
        if(r==null)r=p.dependencies.stream().map(id->region(s,id)).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        return r!=null && !r.groundPaused && ProductionRegionService.maintenanceScopeUnchanged(s,r);
    }
}
