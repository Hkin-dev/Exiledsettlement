package com.hkin.exiledsettlement.region;

import com.hkin.exiledsettlement.blueprint.BlueprintRecord;
import com.hkin.exiledsettlement.building.BuildingType;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.terrain.*;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Clear the entire accepted plot before soil/facilities; reuse saved preparation and child orders. */
public final class RegionConstructionStages {
    private RegionConstructionStages() {}
    public static boolean applies(ProductionRegion r) {
        return r!=null && (r.agricultural() || r.type==BuildingType.PASTURE_AREA || r.rectangularSelection && r.type==BuildingType.TREE_FARM);
    }
    public static boolean surveyed(BlueprintRecord b) {
        var r=b.region();return r.surveyed && r.layoutReady && r.prepared && r.preparationRetries.isEmpty();
    }
    public static boolean facilitiesReady(SettlementRecord s,BlueprintRecord b) {
        return !applies(b.region()) || surveyed(b) && TerrainService.dependenciesReady(s,b.id());
    }
    public static boolean facilitiesReady(ServerLevel l,BlueprintRecord b) {
        if(!applies(b.region()))return true;
        var s=SettlementSavedData.get(l).findContaining(b.anchor()).orElse(null);
        return s!=null && facilitiesReady(s,b);
    }
    private record Snapshot(long tick,boolean ready) {}
    private static final Map<BlueprintRecord,Snapshot> CLEAR=new WeakHashMap<>();
    public static void invalidate(SettlementRecord s,UUID dependency) {
        var b=s.blueprint(dependency);if(b!=null)CLEAR.remove(b);
    }
    private static boolean cleared(ServerLevel l,SettlementRecord s,BlueprintRecord b) {
        if(!surveyed(b))return false;
        var cached=CLEAR.get(b);if(cached!=null && cached.tick()==l.getGameTime())return cached.ready();
        boolean ready=true;
        for(var p:s.terrain().dependingOn(b.id())) {
            if(p.cancelled || p.road || p.bridge || p.escapeFor!=null || p.name.startsWith("牧场安全"))continue;
            for(int i=p.cursor;i<p.steps.size();i++) {
                var step=p.steps.get(i);
                if(step.fill() || p.finished.get(i) || p.abandoned.get(i)
                        || FoundationPlacement.coveredDig(l,p,step))continue;
                if(!l.hasChunkAt(step.position()) || !l.getBlockState(step.position()).isAir()){ready=false;break;}
            }
            if(!ready)break;
        }
        // Conservative within the tick: a final cut can unlock filling on the next tick.
        CLEAR.put(b,new Snapshot(l.getGameTime(),ready));return ready;
    }
    public static boolean fillAllowed(ServerLevel l,SettlementRecord s,TerrainProject p) {
        if(p==null || p.road || p.bridge || p.escapeFor!=null || p.name.startsWith("牧场安全"))return true;
        for(var id:p.dependencies){var b=s.blueprint(id);
            if(b!=null && applies(b.region()) && !cleared(l,s,b))return false;
        }
        return true;
    }

    /** A worker fells a surveyed natural tree from its actual root, like normal forestry. */
    public static net.minecraft.core.BlockPos treeWorkFace(ServerLevel l,TerrainProject p,TerrainProject.Step step) {
        if(p==null || step.fill() || !step.target().isAir())return null;
        var at=step.position();var current=l.getBlockState(at);
        if(!(current.is(net.minecraft.tags.BlockTags.LOGS) || FarmVegetation.leaf(current)))return null;
        for(var s:SettlementSavedData.get(l).settlements())for(var id:p.dependencies){
            var site=s.blueprint(id);var r=site==null?null:site.region();if(!applies(r) || r.cell(at)==null)continue;
            for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){
                long key=ProductionRegion.key(at.getX()+dx,at.getZ()+dz);if(!r.naturalTrees.contains(key))continue;
                var cell=r.cells.get(key);if(cell==null)continue;
                var root=cell.ground().atY(r.gradeOrigins.getOrDefault(key,cell.ground().getY()));
                if(root.getY()<at.getY() && l.hasChunkAt(root)
                        && com.hkin.exiledsettlement.blueprint.FarmEarthworkPlan.support(l,root)
                        && l.getBlockState(root.above()).is(net.minecraft.tags.BlockTags.LOGS))return root;
            }
        }
        return null;
    }
}
